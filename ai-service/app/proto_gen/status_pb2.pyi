from google.protobuf import empty_pb2 as _empty_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class StatusResponse(_message.Message):
    __slots__ = ("model_name", "registered_tools", "database_alive")
    MODEL_NAME_FIELD_NUMBER: _ClassVar[int]
    REGISTERED_TOOLS_FIELD_NUMBER: _ClassVar[int]
    DATABASE_ALIVE_FIELD_NUMBER: _ClassVar[int]
    model_name: str
    registered_tools: _containers.RepeatedScalarFieldContainer[str]
    database_alive: bool
    def __init__(self, model_name: _Optional[str] = ..., registered_tools: _Optional[_Iterable[str]] = ..., database_alive: _Optional[bool] = ...) -> None: ...
