from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class WatchRequest(_message.Message):
    __slots__ = ("driver_id", "samples")
    DRIVER_ID_FIELD_NUMBER: _ClassVar[int]
    SAMPLES_FIELD_NUMBER: _ClassVar[int]
    driver_id: str
    samples: int
    def __init__(self, driver_id: _Optional[str] = ..., samples: _Optional[int] = ...) -> None: ...

class DriverPing(_message.Message):
    __slots__ = ("lat", "lng", "status", "speed_kmph", "ts_millis")
    LAT_FIELD_NUMBER: _ClassVar[int]
    LNG_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    SPEED_KMPH_FIELD_NUMBER: _ClassVar[int]
    TS_MILLIS_FIELD_NUMBER: _ClassVar[int]
    lat: float
    lng: float
    status: str
    speed_kmph: float
    ts_millis: int
    def __init__(self, lat: _Optional[float] = ..., lng: _Optional[float] = ..., status: _Optional[str] = ..., speed_kmph: _Optional[float] = ..., ts_millis: _Optional[int] = ...) -> None: ...
