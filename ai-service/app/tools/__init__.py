# Importing a tool module registers it in base.registry (see base.register).
# Anything that touches the registry — agent, gRPC server, tests — gets the
# full toolset just by importing app.tools.
from app.tools import analytics_tools, command_tools, geo, runbooks  # noqa: F401
from app.tools.base import toolset

DISPATCH_TOOLS = toolset(
    command_tools.STATUS, command_tools.REASSIGN,
    command_tools.NOTIFY, command_tools.WATCHDRIVER,command_tools.DRIVER_OVERVIEW,
    geo.TOOL, runbooks.TOOL,
)

ANALYTICS_TOOLS = toolset(
    analytics_tools.BREACHES, analytics_tools.ETA, analytics_tools.UTILIZATION,
)