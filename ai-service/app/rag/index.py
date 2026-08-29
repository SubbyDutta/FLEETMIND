
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

    for doc in load_runbooks(_RUNBOOKS_DIR):

        with pool.connection() as conn:
            if not force and _doc_unchanged(conn, doc):
                skipped += 1
                continue
            chunks = chunk(doc, counter)
            vectors = embed_documents([c.content for c in chunks])
            _reindex_doc(conn, doc, chunks, vectors)
            indexed += 1
            total_chunks += len(chunks)
            logger.info("indexed %s (%d chunks)", doc.doc_id, len(chunks))


    print(f"indexed {indexed} docs ({total_chunks} chunks), skipped {skipped} unchanged")


def _doc_unchanged(conn: Connection, doc: Document) -> bool:

    row = conn.execute(
        "SELECT metadata->>'content_hash' FROM knowledge_chunks WHERE doc_id = %s LIMIT 1",
        (doc.doc_id,),
    ).fetchone()
    return row is not None and row[0] == doc.content_hash


def _reindex_doc(
    conn: Connection,
    doc: Document,
    chunks: list[Chunk],
    vectors: list[list[float]],
) -> None:

    conn.execute("DELETE FROM knowledge_chunks WHERE doc_id = %s", (doc.doc_id,))

    conn.cursor().executemany(
        "INSERT INTO knowledge_chunks (doc_id, chunk_no, content, embedding, metadata) "
        "VALUES (%s, %s, %s, %s::vector, %s)",
        [
            (
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
