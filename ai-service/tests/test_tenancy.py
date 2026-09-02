import pytest

from app.tenancy import require_tenant, reset_tenant, set_tenant


def test_require_returns_bound_tenant():
    assert require_tenant() == "acme"


def test_reset_restores_previous_tenant():
    token = set_tenant("globex")
    assert require_tenant() == "globex"
    reset_tenant(token)
    assert require_tenant() == "acme"


def test_unbound_tenant_fails_closed():
    token = set_tenant(None)
    try:
        with pytest.raises(RuntimeError, match="No tenant bound"):
            require_tenant()
    finally:
        reset_tenant(token)


def test_empty_tenant_fails_closed():
    token = set_tenant("")
    try:
        with pytest.raises(RuntimeError):
            require_tenant()
    finally:
        reset_tenant(token)


def test_retrieval_sql_is_tenant_scoped():
    import inspect

    from app.rag import retrieval

    assert "tenant_id = %(tenant)s" in inspect.getsource(retrieval.vector_search)
    assert "tenant_id = %(tenant)s" in inspect.getsource(retrieval.keyword_search)
