from pydantic_settings import BaseSettings,SettingsConfigDict

class Settings(BaseSettings):
    gemini_api_key: str
    database_url: str

    embedding_model: str ="gemini-embedding-001"
    embedding_dims: int = 768

    # countTokens needs a generative model — embedding models don't support it
    tokenizer_model: str = "gemini-2.5-flash"

    # command-service gRPC ToolService (write tools cross this boundary)
    command_service_grpc: str = "localhost:9091"

    model_config = SettingsConfigDict(
        env_file='.env',
        env_file_encoding="utf-8",
        extra="ignore"
    )
    agent_model: str = "gemini-2.5-flash"
    # 6 was too tight in practice: investigate (2-3 tools) + failed reassign +
    # retry with a new candidate + verify already needs ~8. Override via env.
    agent_max_steps: int = 10
    agent_grpc_port: int = 50051

settings = Settings()
