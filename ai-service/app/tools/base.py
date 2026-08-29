from typing import Any, Protocol, runtime_checkable

from pydantic import BaseModel

@runtime_checkable
class Tool(Protocol):
    name: str
    description: str
    args_model: type[BaseModel]

    def call(self,args:BaseModel) -> dict[str,Any]: ...

registry: dict[str,"Tool"]={}

def register(tool:Tool)->Tool:
    if tool.name in registry:
        raise ValueError(f"tool {tool.name} already exists")
    registry[tool.name]=tool
    return tool

Toolset=dict[str, Tool]
def toolset(*tools: Tool)->Toolset:
    return {t.name: t for t in tools}
def run_tool(name:str,raw_args:dict[str,Any],tools:Toolset|None=None) -> dict[str,Any]:

    tool = (tools if tools is not None else registry)[name]
    args=tool.args_model.model_validate(raw_args)
    return tool.call(args)