from contextvars import ContextVar

_current_tenant: ContextVar[str | None] = ContextVar("tenant", default=None)


def set_tenant(tenant_id: str):
    return _current_tenant.set(tenant_id)


def reset_tenant(token) -> None:
    _current_tenant.reset(token)


def require_tenant() -> str:
    tenant = _current_tenant.get()
    if not tenant:
        raise RuntimeError("No tenant bound to this request — refusing unscoped access")
    return tenant
