"""工具契约加载：远端（Java 发现端点）优先，失败回退 contracts/tools-schema.json 快照。

进程内缓存：远端成功按长 TTL 缓存；回退快照按短 TTL 缓存，Java 恢复后自动切回。
schema 只用于发现/元数据，工具执行永远走 Java POST 端点。
"""

import json
import logging
import time
from pathlib import Path

import httpx

logger = logging.getLogger(__name__)

_FALLBACK_TTL_SECONDS = 5.0


class ToolSchemaLoader:
    def __init__(
        self,
        client,
        snapshot_path: Path,
        ttl_seconds: float = 60.0,
    ) -> None:
        self._client = client
        self._snapshot_path = Path(snapshot_path)
        self._ttl_seconds = ttl_seconds
        self._cached: dict | None = None
        self._expires_at = 0.0
        self.source: str | None = None

    async def load(self, authorization: str | None = None) -> dict:
        now = time.monotonic()
        if self._cached is not None and now < self._expires_at:
            return self._cached
        try:
            # 只透传凭证不缓存:缓存内容 = schema + source,绝不含 authorization/token。
            schema = await self._client.fetch_schema(authorization=authorization)
        except (httpx.HTTPError, RuntimeError, ValueError, KeyError, TypeError):
            logger.warning("Java schema 不可用，回退快照：%s", self._snapshot_path)
            return self._load_snapshot()
        self._cached = schema
        self._expires_at = now + self._ttl_seconds
        self.source = "java"
        return schema

    def invalidate(self) -> None:
        self._cached = None
        self._expires_at = 0.0

    def _load_snapshot(self) -> dict:
        if not self._snapshot_path.is_file():
            raise RuntimeError(f"契约快照不存在：{self._snapshot_path}")
        with open(self._snapshot_path, encoding="utf-8") as f:
            schema = json.load(f)
        self._cached = schema
        self._expires_at = time.monotonic() + _FALLBACK_TTL_SECONDS
        self.source = "snapshot"
        return schema
