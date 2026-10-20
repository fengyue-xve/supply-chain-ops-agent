"""知识文档加载与分块（离线、纯内存、零新依赖）。

- 默认知识目录基于模块文件位置解析（不依赖启动时 cwd），KNOWLEDGE_DIR 可覆盖；
- 只加载白名单目录顶层的普通 .md 文件：不递归子目录、每个候选文件 resolve 后必须
  仍在知识根目录内（指向目录外的符号链接一律拒绝）、目录或非普通文件不加载；
- 显式 UTF-8 严格读取：缺目录 / 空目录 / 坏文件都不抛异常，以降级原因记录一次；
- 按显式 section ID（`## [section-id] 标题`）分块；正文超过 500 字符时按段落继续
  拆分（段落超限再按行、再按字符窗口），只做切分不丢弃内容；
- chunk_id = `文档ID#section-id-part序号`，文档 ID 取文件名去扩展名，稳定可复现（不使用 hash）；
- 索引一经加载即不可变（frozen dataclass + tuple）。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

# app/rag/documents.py -> parents[0]=rag、[1]=app、[2]=fastapi-service
DEFAULT_KNOWLEDGE_DIR = Path(__file__).resolve().parents[2] / "knowledge"

CHUNK_CHAR_LIMIT = 500

_SECTION_RE = re.compile(r"^##\s+\[([a-z0-9][a-z0-9\-]*)\]\s*(.*?)\s*$")
_DOC_TITLE_RE = re.compile(r"^#\s+(\S.*?)\s*$")


@dataclass(frozen=True)
class KnowledgeChunk:
    doc: str
    doc_id: str
    section_id: str
    title: str
    part: int
    chunk_id: str
    text: str
    source_path: str


@dataclass(frozen=True)
class KnowledgeIndex:
    """加载结果；degraded = None 表示正常，否则记录一次降级原因。

    原因取值："missing-dir"（目录不存在）/ "read-error"（单文件读取或解码失败）/
    "empty"（目录下没有可用的 .md 内容）。降级不阻断 chat，由检索器记录一次日志。
    """

    chunks: tuple[KnowledgeChunk, ...]
    directory: str
    degraded: str | None = None


def load_knowledge_index(directory: Path | str) -> KnowledgeIndex:
    base = Path(directory)
    if not base.is_dir():
        return KnowledgeIndex(chunks=(), directory=str(base), degraded="missing-dir")
    root = base.resolve()
    chunks: list[KnowledgeChunk] = []
    degraded: str | None = None
    for path in sorted(base.glob("*.md"), key=lambda item: item.name):
        try:
            resolved = path.resolve(strict=True)
            resolved.relative_to(root)
        except (OSError, ValueError):
            # 断链、无法解析或 resolve 后位于知识根目录之外（含越界符号链接）：
            # 拒绝加载该候选文件，其余文件继续处理。
            degraded = degraded or "read-error"
            continue
        if not resolved.is_file():
            continue
        try:
            text = resolved.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            degraded = degraded or "read-error"
            continue
        chunks.extend(_chunks_for_file(path, base, text))
    if degraded is None and not chunks:
        degraded = "empty"
    return KnowledgeIndex(chunks=tuple(chunks), directory=str(base), degraded=degraded)


def _chunks_for_file(path: Path, base: Path, text: str) -> list[KnowledgeChunk]:
    doc_id = path.stem
    doc_title = _document_title(text) or doc_id
    try:
        source_path = path.relative_to(base).as_posix()
    except ValueError:
        source_path = path.name
    chunks: list[KnowledgeChunk] = []
    for section_id, title, body in _parse_sections(text):
        if not body:
            continue
        for part_no, part in enumerate(_split_body(body), start=1):
            chunks.append(
                KnowledgeChunk(
                    doc=doc_title,
                    doc_id=doc_id,
                    section_id=section_id,
                    title=title,
                    part=part_no,
                    chunk_id=f"{doc_id}#{section_id}-{part_no}",
                    text=part,
                    source_path=source_path,
                )
            )
    return chunks


def _document_title(text: str) -> str | None:
    for line in text.splitlines():
        match = _DOC_TITLE_RE.match(line)
        if match:
            return match.group(1)
    return None


def _parse_sections(text: str) -> list[tuple[str, str, str]]:
    """只索引带显式 ID 的小节；小节标题行之前的内容（导语）不进索引。"""
    sections: list[tuple[str, str, str]] = []
    section_id: str | None = None
    title = ""
    lines: list[str] = []
    for line in text.splitlines():
        match = _SECTION_RE.match(line)
        if match:
            if section_id is not None:
                sections.append((section_id, title, "\n".join(lines).strip()))
            section_id = match.group(1)
            title = match.group(2).strip() or section_id
            lines = []
            continue
        if section_id is not None:
            lines.append(line)
    if section_id is not None:
        sections.append((section_id, title, "\n".join(lines).strip()))
    return sections


def _split_body(body: str, limit: int = CHUNK_CHAR_LIMIT) -> list[str]:
    """按段落打包到不超过 limit 字符；单段超限时按行、单行超限时按字符窗口继续拆分。"""
    units: list[str] = []
    for paragraph in re.split(r"\n\s*\n", body):
        paragraph = paragraph.strip()
        if not paragraph:
            continue
        if len(paragraph) <= limit:
            units.append(paragraph)
            continue
        for line in paragraph.split("\n"):
            line = line.strip()
            if not line:
                continue
            if len(line) <= limit:
                units.append(line)
            else:
                units.extend(line[index : index + limit] for index in range(0, len(line), limit))

    parts: list[str] = []
    current = ""
    for unit in units:
        candidate = unit if not current else f"{current}\n\n{unit}"
        if len(candidate) <= limit:
            current = candidate
        else:
            if current:
                parts.append(current)
            current = unit
    if current:
        parts.append(current)
    return parts
