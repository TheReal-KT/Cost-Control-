"""Compatibility entry point; legacy password/CRUD routers are intentionally inactive."""

from agentic_subscription_manager.api import create_app

app = create_app()


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="127.0.0.1", port=8000, reload=True, access_log=False)
