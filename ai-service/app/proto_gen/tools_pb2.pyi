from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ReassignRequest(_message.Message):
    __slots__ = ("order_id", "new_driver_id", "reason")
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    NEW_DRIVER_ID_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    order_id: str
    new_driver_id: str
    reason: str
    def __init__(self, order_id: _Optional[str] = ..., new_driver_id: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class ReassignResponse(_message.Message):
    __slots__ = ("success", "message")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    success: bool
    message: str
    def __init__(self, success: _Optional[bool] = ..., message: _Optional[str] = ...) -> None: ...

class NotifyRequest(_message.Message):
    __slots__ = ("order_id", "message", "reason")
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    order_id: str
    message: str
    reason: str
    def __init__(self, order_id: _Optional[str] = ..., message: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class NotifyResponse(_message.Message):
    __slots__ = ("success", "message")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    success: bool
    message: str
    def __init__(self, success: _Optional[bool] = ..., message: _Optional[str] = ...) -> None: ...

class OrderStatusRequest(_message.Message):
    __slots__ = ("order_id",)
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    order_id: str
    def __init__(self, order_id: _Optional[str] = ...) -> None: ...

class Alert(_message.Message):
    __slots__ = ("type", "severity", "reason", "created_at")
    TYPE_FIELD_NUMBER: _ClassVar[int]
    SEVERITY_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    type: str
    severity: str
    reason: str
    created_at: str
    def __init__(self, type: _Optional[str] = ..., severity: _Optional[str] = ..., reason: _Optional[str] = ..., created_at: _Optional[str] = ...) -> None: ...

class OrderStatusResponse(_message.Message):
    __slots__ = ("found", "order_id", "status", "customer_name", "restaurant", "assigned_driver", "sla_deadline", "promised_eta", "current_eta", "open_alerts")
    FOUND_FIELD_NUMBER: _ClassVar[int]
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    CUSTOMER_NAME_FIELD_NUMBER: _ClassVar[int]
    RESTAURANT_FIELD_NUMBER: _ClassVar[int]
    ASSIGNED_DRIVER_FIELD_NUMBER: _ClassVar[int]
    SLA_DEADLINE_FIELD_NUMBER: _ClassVar[int]
    PROMISED_ETA_FIELD_NUMBER: _ClassVar[int]
    CURRENT_ETA_FIELD_NUMBER: _ClassVar[int]
    OPEN_ALERTS_FIELD_NUMBER: _ClassVar[int]
    found: bool
    order_id: str
    status: str
    customer_name: str
    restaurant: str
    assigned_driver: str
    sla_deadline: str
    promised_eta: str
    current_eta: str
    open_alerts: _containers.RepeatedCompositeFieldContainer[Alert]
    def __init__(self, found: _Optional[bool] = ..., order_id: _Optional[str] = ..., status: _Optional[str] = ..., customer_name: _Optional[str] = ..., restaurant: _Optional[str] = ..., assigned_driver: _Optional[str] = ..., sla_deadline: _Optional[str] = ..., promised_eta: _Optional[str] = ..., current_eta: _Optional[str] = ..., open_alerts: _Optional[_Iterable[_Union[Alert, _Mapping]]] = ...) -> None: ...

class DriverOverviewRequest(_message.Message):
    __slots__ = ("driver_id",)
    DRIVER_ID_FIELD_NUMBER: _ClassVar[int]
    driver_id: str
    def __init__(self, driver_id: _Optional[str] = ...) -> None: ...

class CurrentOrder(_message.Message):
    __slots__ = ("order_id", "status", "restaurant", "current_eta", "sla_deadline")
    ORDER_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    RESTAURANT_FIELD_NUMBER: _ClassVar[int]
    CURRENT_ETA_FIELD_NUMBER: _ClassVar[int]
    SLA_DEADLINE_FIELD_NUMBER: _ClassVar[int]
    order_id: str
    status: str
    restaurant: str
    current_eta: str
    sla_deadline: str
    def __init__(self, order_id: _Optional[str] = ..., status: _Optional[str] = ..., restaurant: _Optional[str] = ..., current_eta: _Optional[str] = ..., sla_deadline: _Optional[str] = ...) -> None: ...

class DriverOverviewResponse(_message.Message):
    __slots__ = ("found", "driver_id", "name", "status", "speed_kmph", "last_seen", "current_order", "open_alerts")
    FOUND_FIELD_NUMBER: _ClassVar[int]
    DRIVER_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    SPEED_KMPH_FIELD_NUMBER: _ClassVar[int]
    LAST_SEEN_FIELD_NUMBER: _ClassVar[int]
    CURRENT_ORDER_FIELD_NUMBER: _ClassVar[int]
    OPEN_ALERTS_FIELD_NUMBER: _ClassVar[int]
    found: bool
    driver_id: str
    name: str
    status: str
    speed_kmph: float
    last_seen: str
    current_order: CurrentOrder
    open_alerts: _containers.RepeatedCompositeFieldContainer[Alert]
    def __init__(self, found: _Optional[bool] = ..., driver_id: _Optional[str] = ..., name: _Optional[str] = ..., status: _Optional[str] = ..., speed_kmph: _Optional[float] = ..., last_seen: _Optional[str] = ..., current_order: _Optional[_Union[CurrentOrder, _Mapping]] = ..., open_alerts: _Optional[_Iterable[_Union[Alert, _Mapping]]] = ...) -> None: ...
