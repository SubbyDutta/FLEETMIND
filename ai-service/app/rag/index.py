
from __future__ import annotations

import sys
import logging
from pathlib import Path

from psycopg import Connection
from psycopg.types.json import Jsonb

from app.db import pool
from app.rag.loader import Document, load_runbooks
from app.rag.chunker import Chunk, GeminiTokenCounter, chunk
from app.rag.embeddings import embed_documents

logger = logging.getLogger(__name__)


_RUNBOOKS_DIR = Path(__file__).resolve().parent / "runbooks"


def index_all(force: bool = False) -> None:

    counter = GeminiTokenCounter()
    indexed = skipped = total_chunks = 0

    tenant_dirs = sorted(p for p in _RUNBOOKS_DIR.iterdir() if p.is_dir())
    if not tenant_dirs:
        raise NotADirectoryError(
            f"No per-tenant runbook directories under {_RUNBOOKS_DIR} "
            f"(expected e.g. runbooks/acme/*.md)")

    for tenant_dir in tenant_dirs:
        tenant = tenant_dir.name
        for doc in load_runbooks(tenant_dir):

            with pool.connection() as conn:
                if not force and _doc_unchanged(conn, tenant, doc):
                    skipped += 1
                    continue
                chunks = chunk(doc, counter)
                vectors = embed_documents([c.content for c in chunks])
                _reindex_doc(conn, tenant, doc, chunks, vectors)
                indexed += 1
                total_chunks += len(chunks)
                logger.info("indexed %s/%s (%d chunks)", tenant, doc.doc_id, len(chunks))


    print(f"indexed {indexed} docs ({total_chunks} chunks), skipped {skipped} unchanged")


def _doc_unchanged(conn: Connection, tenant: str, doc: Document) -> bool:

    row = conn.execute(
        "SELECT metadata->>'content_hash' FROM knowledge_chunks "
        "WHERE tenant_id = %s AND doc_id = %s LIMIT 1",
        (tenant, doc.doc_id),
    ).fetchone()
    return row is not None and row[0] == doc.content_hash


def _reindex_doc(
    conn: Connection,
    tenant: str,
    doc: Document,
    chunks: list[Chunk],
    vectors: list[list[float]],
) -> None:

    conn.execute("DELETE FROM knowledge_chunks WHERE tenant_id = %s AND doc_id = %s",
                 (tenant, doc.doc_id))

    conn.cursor().executemany(
        "INSERT INTO knowledge_chunks (tenant_id, doc_id, chunk_no, content, embedding, metadata) "
        "VALUES (%s, %s, %s, %s, %s::vector, %s)",
        [
            (
                tenant,
                c.doc_id,
                c.chunk_no,
                c.content,
                str(vec),  # Python's list repr is a valid pgvector literal; the ::vector cast parses it
                Jsonb({
                    "heading": c.heading,
                    "token_count": c.token_count,
                    "content_hash": doc.content_hash,
                }),
            )

            for c, vec in zip(chunks, vectors, strict=True)   #bulding a list of tuples here .first this loop runs as many times then the executemany gets executed at once so its diff than looping on execute queries cuh
        ],
    )


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        index_all(force="--force" in sys.argv)
    finally:

        pool.close()
