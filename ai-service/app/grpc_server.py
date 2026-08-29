# app/grpc_server.py
import json
from concurrent import futures

import grpc

from app.agent.agent_loop import run_agent
from app.agent.analytics_agent import run_analytics_agent
from app.tools import DISPATCH_TOOLS
from app.config import settings
from app.db import pool
from app.proto_gen import agent_pb2, agent_pb2_grpc, status_pb2, status_pb2_grpc
from app.tools import base


class AgentServicer(agent_pb2_grpc.AgentServiceServicer):
    def Chat(self, request, context):
        if not request.question.strip():
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "question is empty")
        # Explicit toolset: the default (None -> full registry) would leak the
        # analytics tools into the dispatch agent's declarations.
        for ev in run_agent(request.question, tools=DISPATCH_TOOLS):
            if not context.is_active():   # Java client hung up — stop burning tokens
                return
            yield agent_pb2.ChatEvent(
                type=ev.type.upper(),
                step=ev.step,
                tool_name=ev.tool_name,
                payload_json=json.dumps(ev.payload),
            )
    def Analytics(self, request, context):
        if not request.question.strip():
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "question is empty")
        for e in run_analytics_agent(request.question):
            if not context.is_active(): return
            yield  agent_pb2.ChatEvent(
                type=e.type.upper(),
                step=e.step,
                tool_name=e.tool_name,
                payload_json=json.dumps(e.payload),

            )



def _db_alive() -> bool:
    try:
        with pool.connection() as conn:
            conn.execute("SELECT 1")
        return True
    except Exception:
        return False


class StatusService(status_pb2_grpc.AgentDiagnosticsServicer):

    def Status(self, request, context):
        return status_pb2.StatusResponse(
            model_name=settings.agent_model,
            registered_tools=sorted(base.registry),  # iterating the dict yields tool names
            database_alive=_db_alive(),
        )


def create_server() -> grpc.Server:
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=8))
    agent_pb2_grpc.add_AgentServiceServicer_to_server(AgentServicer(), server)
    status_pb2_grpc.add_AgentDiagnosticsServicer_to_server(StatusService(), server)
    server.add_insecure_port(f"[::]:{settings.agent_grpc_port}")
    return server