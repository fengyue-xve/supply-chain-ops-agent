"""RAG 文档加载与分块单测：目录解析、降级语义、chunk 契约、拆分完整性与顺序。"""

import re
from dataclasses import FrozenInstanceError
from pathlib import Path

import pytest

from app.rag.documents import (
    CHUNK_CHAR_LIMIT,
    DEFAULT_KNOWLEDGE_DIR,
    KnowledgeIndex,
    load_knowledge_index,
)

_DATE_LITERAL_RE = re.compile(r"\d{4}-\d{1,2}-\d{1,2}|\d{4}\s*年\s*\d{1,2}\s*月")

SAMPLE_DOC = """# 测试知识

这是导语，不应进入索引。

## 无ID标题
这段同样不应进入索引。

## [alpha] 第一节
第一节正文。

## [beta] 第二节
第二节正文。
"""


def _write(base: Path, name: str, text: str) -> Path:
    path = base / name
    path.write_text(text, encoding="utf-8")
    return path


def test_default_directory_is_module_relative_not_cwd(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    assert DEFAULT_KNOWLEDGE_DIR == Path(__file__).resolve().parents[1] / "knowledge"
    index = load_knowledge_index(DEFAULT_KNOWLEDGE_DIR)
    assert index.degraded is None
    assert index.chunks
    assert all(chunk.source_path.endswith(".md") for chunk in index.chunks)


def test_missing_directory_degrades_without_raising(tmp_path):
    index = load_knowledge_index(tmp_path / "not-there")
    assert index.degraded == "missing-dir"
    assert index.chunks == ()


def test_empty_directory_is_degraded_as_empty(tmp_path):
    index = load_knowledge_index(tmp_path)
    assert index.degraded == "empty"
    assert index.chunks == ()


def test_non_markdown_files_and_subdirectories_are_not_loaded(tmp_path):
    _write(tmp_path, "notes.txt", SAMPLE_DOC)
    nested = tmp_path / "sub"
    nested.mkdir()
    _write(nested, "nested.md", SAMPLE_DOC)
    index = load_knowledge_index(tmp_path)
    assert index.degraded == "empty"
    assert index.chunks == ()


def test_broken_utf8_file_skips_only_itself(tmp_path):
    _write(tmp_path, "good.md", "# 好文档\n\n## [keep] 保留\n\n可以读取。\n")
    (tmp_path / "broken.md").write_bytes(b"# broken\n\n## [x] \xff\xfe\n\nbad bytes")
    index = load_knowledge_index(tmp_path)
    assert index.degraded == "read-error"
    assert [chunk.chunk_id for chunk in index.chunks] == ["good#keep-1"]


def test_only_explicit_section_ids_are_indexed(tmp_path):
    _write(tmp_path, "doc.md", SAMPLE_DOC)
    chunks = load_knowledge_index(tmp_path).chunks
    assert [chunk.chunk_id for chunk in chunks] == ["doc#alpha-1", "doc#beta-1"]
    assert [chunk.section_id for chunk in chunks] == ["alpha", "beta"]
    assert [chunk.title for chunk in chunks] == ["第一节", "第二节"]
    assert all(
        "导语" not in chunk.text and "无ID标题" not in chunk.text for chunk in chunks
    )
    assert chunks[0].doc == "测试知识"
    assert chunks[0].doc_id == "doc"
    assert chunks[0].source_path == "doc.md"


def test_document_title_falls_back_to_file_stem(tmp_path):
    _write(tmp_path, "untitled.md", "## [s] 节\n\n正文。\n")
    assert load_knowledge_index(tmp_path).chunks[0].doc == "untitled"


def test_chunk_ids_are_stable_across_loads(tmp_path):
    _write(tmp_path, "doc.md", SAMPLE_DOC)
    first = [chunk.chunk_id for chunk in load_knowledge_index(tmp_path).chunks]
    second = [chunk.chunk_id for chunk in load_knowledge_index(tmp_path).chunks]
    assert first == second == ["doc#alpha-1", "doc#beta-1"]


def test_multi_paragraph_body_is_split_without_losing_content(tmp_path):
    paragraphs = [f"段落{i}：" + "内容" * 60 for i in range(6)]
    body = "\n\n".join(paragraphs)
    _write(tmp_path, "doc.md", f"# 拆分\n\n## [long] 长段\n\n{body}\n")
    chunks = load_knowledge_index(tmp_path).chunks
    assert len(chunks) >= 2
    assert [chunk.part for chunk in chunks] == list(range(1, len(chunks) + 1))
    assert [chunk.chunk_id for chunk in chunks] == [
        f"doc#long-{part}" for part in range(1, len(chunks) + 1)
    ]
    for chunk in chunks:
        assert len(chunk.text) <= CHUNK_CHAR_LIMIT
    joined = "\n\n".join(chunk.text for chunk in chunks)
    assert "".join(joined.split()) == "".join(body.split())


def test_oversized_single_paragraph_splits_by_line_without_loss(tmp_path):
    body = "\n".join(f"行{i}：" + "细节" * 140 for i in range(3))
    _write(tmp_path, "doc.md", f"# 拆分\n\n## [long] 长段\n\n{body}\n")
    chunks = load_knowledge_index(tmp_path).chunks
    assert len(chunks) >= 2
    for chunk in chunks:
        assert len(chunk.text) <= CHUNK_CHAR_LIMIT
    joined = "\n\n".join(chunk.text for chunk in chunks)
    assert "".join(joined.split()) == "".join(body.split())


def test_oversized_single_line_splits_by_char_window_without_loss(tmp_path):
    body = "超长" * 400
    _write(tmp_path, "doc.md", f"# 拆分\n\n## [long] 长段\n\n{body}\n")
    chunks = load_knowledge_index(tmp_path).chunks
    assert len(chunks) >= 2
    for chunk in chunks:
        assert len(chunk.text) <= CHUNK_CHAR_LIMIT
    joined = "\n\n".join(chunk.text for chunk in chunks)
    assert "".join(joined.split()) == body


def test_file_and_section_order_is_deterministic(tmp_path):
    _write(tmp_path, "zeta.md", "# Z\n\n## [z1] Z1\n\nzzz。\n")
    _write(tmp_path, "alpha.md", "# A\n\n## [a1] A1\n\naaa。\n\n## [a2] A2\n\naaa2。\n")
    chunks = load_knowledge_index(tmp_path).chunks
    assert [chunk.chunk_id for chunk in chunks] == ["alpha#a1-1", "alpha#a2-1", "zeta#z1-1"]


def test_index_and_chunks_are_immutable(tmp_path):
    _write(tmp_path, "doc.md", SAMPLE_DOC)
    index = load_knowledge_index(tmp_path)
    assert isinstance(index, KnowledgeIndex)
    assert isinstance(index.chunks, tuple)
    with pytest.raises(FrozenInstanceError):
        index.degraded = "read-error"
    with pytest.raises(FrozenInstanceError):
        index.chunks[0].text = "改写"


def test_default_corpus_has_no_concrete_dates():
    index = load_knowledge_index(DEFAULT_KNOWLEDGE_DIR)
    assert index.chunks
    for chunk in index.chunks:
        assert not _DATE_LITERAL_RE.search(chunk.text), chunk.chunk_id
        assert "2026" not in chunk.text, chunk.chunk_id


def test_same_section_id_across_documents_does_not_conflict(tmp_path):
    body = "## [stock-level] 库存档位\n\n第一份文档的说明。\n"
    _write(tmp_path, "doc-a.md", f"# A\n\n{body}")
    _write(tmp_path, "doc-b.md", f"# B\n\n{body}")
    chunks = load_knowledge_index(tmp_path).chunks
    assert [chunk.chunk_id for chunk in chunks] == [
        "doc-a#stock-level-1",
        "doc-b#stock-level-1",
    ]
    assert chunks[0].chunk_id != chunks[1].chunk_id
    for chunk in chunks:
        assert re.fullmatch(r"[a-z0-9.\-]+#[a-z0-9\-]+-\d+", chunk.chunk_id)


def test_directory_named_like_markdown_is_not_loaded(tmp_path):
    _write(tmp_path, "real.md", SAMPLE_DOC)
    (tmp_path / "fake.md").mkdir()
    index = load_knowledge_index(tmp_path)
    assert [chunk.chunk_id for chunk in index.chunks] == ["real#alpha-1", "real#beta-1"]
    assert index.degraded is None


def _symlink_or_skip(link: Path, target: Path) -> None:
    try:
        link.symlink_to(target)
    except (OSError, NotImplementedError):
        pytest.skip("当前环境不支持创建符号链接（Windows 需开发者模式或管理员权限）")


def test_symlink_escaping_knowledge_root_is_rejected(tmp_path):
    outside = tmp_path / "outside.md"
    outside.write_text("# 外部\n\n## [out] 外部\n\n外部内容不应加载。\n", encoding="utf-8")
    base = tmp_path / "knowledge"
    base.mkdir()
    _symlink_or_skip(base / "link.md", outside)
    index = load_knowledge_index(base)
    assert index.chunks == ()
    assert index.degraded == "read-error"


def test_symlink_escaping_root_is_rejected_while_regular_file_loads(tmp_path):
    outside = tmp_path / "outside.md"
    outside.write_text("# 外部\n\n## [out] 外部\n\n外部内容不应加载。\n", encoding="utf-8")
    base = tmp_path / "knowledge"
    base.mkdir()
    _write(base, "real.md", SAMPLE_DOC)
    _symlink_or_skip(base / "link.md", outside)
    index = load_knowledge_index(base)
    assert [chunk.chunk_id for chunk in index.chunks] == ["real#alpha-1", "real#beta-1"]
    assert index.degraded == "read-error"
    assert all("外部内容" not in chunk.text for chunk in index.chunks)


def test_symlink_pointing_inside_root_is_loaded(tmp_path):
    base = tmp_path / "knowledge"
    base.mkdir()
    (base / "data.txt").write_text("## [inner] 内部\n\n内部链接内容。\n", encoding="utf-8")
    _symlink_or_skip(base / "alias.md", base / "data.txt")
    index = load_knowledge_index(base)
    assert [chunk.chunk_id for chunk in index.chunks] == ["alias#inner-1"]
    assert index.degraded is None
