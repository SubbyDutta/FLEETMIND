from collections import defaultdict
from dataclasses import dataclass

from app.db import pool
from app.rag.embeddings import embed_query

RRF_K=60
DEFAULT_RETRIEVAL_LIMIT=20
DEFAULT_TOP_K=5
@dataclass(frozen=True)
class Result:
    id:int
    doc_id:str
    heading:str|None
    content:str
    score:float

# Stateless module-level functions (no class): nothing here has state to hold,
# matching the loader/embeddings style.
def vector_search(query:str,limit:int=DEFAULT_RETRIEVAL_LIMIT) -> list[Result]:
    if not query.strip():
        return []
    if limit <=0:
        raise ValueError("limit must be greater than 0")
    query_vector= embed_query(query)
    sql="""
    SELECT
        id,
        doc_id,
        metadata->>'heading' AS heading,
        content,
        1-(embedding <=> %(qvec)s::vector) AS score
    FROM knowledge_chunks
    WHERE embedding IS NOT NULL
    ORDER BY embedding <=>%(qvec)s::vector
    LIMIT %(limit)s;
    """
    with pool.connection() as conn:
        with conn.cursor() as cur:
            cur.execute(sql,{'qvec': str(query_vector),'limit':limit},)

            rows =cur.fetchall()

    return[
        Result(
            id=row[0],
            doc_id=row[1],
            heading=row[2],
            content=row[3],
            score=float(row[4]),
        )
        for row in rows
    ]

def keyword_search(query:str,limit:int=DEFAULT_RETRIEVAL_LIMIT) -> list[Result]:
    if not query.strip():
        return[]
    if limit<=0:
        raise ValueError("limit must be greater than 0")
    sql="""
        SELECT
        id,
        doc_id,
        metadata->>'heading' AS heading,
        content,
        ts_rank(tsv, plainto_tsquery('english',%(q)s)) AS score
        FROM knowledge_chunks
        WHERE tsv @@ plainto_tsquery('english',%(q)s)
        ORDER BY score DESC LIMIT %(limit)s;
    """
    with pool.connection() as conn:
        with conn.cursor() as cur:
            cur.execute(sql,{'q': str(query),'limit':limit},)
            rows=cur.fetchall()

    return[

       Result(
            id=row[0],
       doc_id=row[1],
       heading=row[2],
        content=row[3],
       score=float(row[4]),)
        for row in rows
    ]

def search(
        query:str,
        k:int = DEFAULT_TOP_K
)->list[Result]:
        if not query.strip():
            return []

        if k <= 0:
            raise ValueError("k must be greater than 0")

        vector_results=vector_search(query,limit=DEFAULT_RETRIEVAL_LIMIT)
        key_results=keyword_search(query,limit=DEFAULT_RETRIEVAL_LIMIT)

        fused_scores:defaultdict[int,float]=defaultdict(float)
        chunks: dict[int,Result]={}

        for rank,chunk in enumerate(vector_results,start=1):
            fused_scores[chunk.id] += 1/(RRF_K+rank)
            chunks[chunk.id] = chunk
        for rank, chunk in enumerate(key_results, start=1):
            fused_scores[chunk.id] += 1 / (RRF_K + rank)
            chunks[chunk.id] = chunk
        # Tiebreak on -id so equal fused scores rank deterministically —
        # keeps test failures reproducible instead of dict-order lottery.
        ranked_ids = sorted(
            fused_scores,
            key=lambda cid: (fused_scores[cid], -cid),
            reverse=True,
        )
        return [
            Result(
                id=chunks[chunk_id].id,
                doc_id=chunks[chunk_id].doc_id,
                heading=chunks[chunk_id].heading,
                content=chunks[chunk_id].content,
                score=fused_scores[chunk_id],
            )
            for chunk_id in ranked_ids[:k]
        ]
