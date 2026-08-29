from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class ChatRequest(_message.Message):
    __slots__ = ("question",)
    QUESTION_FIELD_NUMBER: _ClassVar[int]
    question: str
    def __init__(self, question: _Optional[str] = ...) -> None: ...

class ChatEvent(_message.Message):
    __slots__ = ("type", "step", "tool_name", "payload_json")
    TYPE_FIELD_NUMBER: _ClassVar[int]
    STEP_FIELD_NUMBER: _ClassVar[int]
    TOOL_NAME_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_JSON_FIELD_NUMBER: _ClassVar[int]
    type: str
    step: int
    tool_name: str
    payload_json: str
    def __init__(self, type: _Optional[str] = ..., step: _Optional[int] = ..., tool_name: _Optional[str] = ..., payload_json: _Optional[str] = ...) -> None: ...
