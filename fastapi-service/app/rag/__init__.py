"""RAG v0.1：静态供应链业务知识的内存词法检索（无向量库、无外部依赖）。"""

from app.rag.documents import (
    CHUNK_CHAR_LIMIT,
    DEFAULT_KNOWLEDGE_DIR,
    KnowledgeChunk,
    KnowledgeIndex,
    load_knowledge_index,
)
from app.rag.retriever import (
    MIN_SCORE,
    PROMPT_BUDGET_CHARS,
    TOP_K,
    EmptyRetriever,
    KnowledgeRetriever,
    RetrievalHit,
    render_chunk,
    select_for_prompt,
    tokenize,
)
from app.rag.retriever_v2 import KnowledgeRetrieverV2

__all__ = [
    "CHUNK_CHAR_LIMIT",
    "DEFAULT_KNOWLEDGE_DIR",
    "EmptyRetriever",
    "KnowledgeChunk",
    "KnowledgeIndex",
    "KnowledgeRetriever",
    "KnowledgeRetrieverV2",
    "MIN_SCORE",
    "PROMPT_BUDGET_CHARS",
    "RetrievalHit",
    "TOP_K",
    "load_knowledge_index",
    "render_chunk",
    "select_for_prompt",
    "tokenize",
]
