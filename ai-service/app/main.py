
from contextlib import asynccontextmanager
from dataclasses import asdict

from fastapi import FastAPI, Query

from app.db import pool
from app.grpc_server import create_server
from app.rag.retrieval import DEFAULT_TOP_K, search


@asynccontextmanager
async def lifespan(_: FastAPI):
    # start() is non-blocking (daemon threads) — FastAPI and the AgentService
    # gRPC server share this one process.
    grpc_server = create_server()
    grpc_server.start()
    yield
    grpc_server.stop(grace=3)
    pool.close()


app = FastAPI(title="fleetmind-ai-service", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.get("/retrieve")
def retrieve(
    q: str = Query(min_length=1),          # empty query -> 422, not a silent []
    k: int = Query(DEFAULT_TOP_K, ge=1, le=20),
) -> list[dict]:
    return [asdict(r) for r in search(q, k)]
