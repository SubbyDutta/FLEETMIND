from functools import lru_cache

import httpx
from google import genai
from google.genai import errors, types
from tenacity import retry, retry_if_exception, stop_after_attempt, wait_exponential
from app.config import settings
from app.tools import base

# This machine's network advertises IPv6 routes to Google that don't route:
# every API call walked 8 dead v6 addresses (~21s TCP timeout each, ~170s
# total) before IPv4 succeeded. Binding to the IPv4 wildcard address makes
# httpx skip the v6 attempts entirely.
_IPV4_ONLY_ARGS = {"transport": httpx.HTTPTransport(local_address="0.0.0.0")}
_IPV4_ONLY_ASYNC_ARGS = {"transport": httpx.AsyncHTTPTransport(local_address="0.0.0.0")}

@lru_cache
def _client()->genai.Client:
    return genai.Client(
        api_key=settings.gemini_api_key,
        http_options=types.HttpOptions(
            timeout=30_000,
            client_args=_IPV4_ONLY_ARGS,
            async_client_args=_IPV4_ONLY_ASYNC_ARGS,
        ),
    )
def tool_declarations(tools: "base.Toolset|None"=None)->types.Tool:
    source=tools if tools is not None else base.registry
    return types.Tool(function_declarations=[
        types.FunctionDeclaration(
            name=tool.name,
            description=tool.description,
            parameters_json_schema=tool.args_model.model_json_schema(),

        )for tool in source.values()
    ])
def _is_retryable(e: BaseException)->bool:
    return isinstance(e,errors.APIError) and (e.code == 429 or e.code>=500)

@retry(
    retry=retry_if_exception(_is_retryable),
    wait=wait_exponential(min=1,max=8),
    stop=stop_after_attempt(5),
    reraise=True,
)
def generate(contents: list[types.Content],
             system_prompt: str,
             tools:"base.Toolset|None"=None) -> types.GenerateContentResponse:
    return _client().models.generate_content(
        model=settings.agent_model,
        contents=contents,
        config=types.GenerateContentConfig(
            system_instruction=system_prompt,
            tools=[tool_declarations(tools)],

            automatic_function_calling=types.AutomaticFunctionCallingConfig(
                disable=True,
            ),
            temperature=0.0,
        ),
    )


