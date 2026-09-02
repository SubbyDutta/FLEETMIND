import pytest

from app.tenancy import reset_tenant, set_tenant


@pytest.fixture(autouse=True)
def bound_tenant():
    token = set_tenant("acme")
    yield
    reset_tenant(token)
