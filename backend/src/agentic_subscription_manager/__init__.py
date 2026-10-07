def main() -> None:
    import uvicorn

    uvicorn.run(
        "agentic_subscription_manager.api:create_app",
        factory=True,
        host="127.0.0.1",
        port=8000,
        workers=1,
        access_log=False,
    )
