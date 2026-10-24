"""Run from a private copied staging tree with the existing server Python; no live DB/env."""
import asyncio
import json
import os
from pathlib import Path
import secrets
import tempfile

async def run():
    with tempfile.TemporaryDirectory(prefix="mahan-isolated-startup-") as folder:
        os.environ.update(RELAY_DB_PATH=str(Path(folder)/"relay.db"),
                          RELAY_PHONE_KEY=secrets.token_hex(32), RELAY_BOT_KEY=secrets.token_hex(32),
                          RELAY_RELEASE_ADMIN_KEY=secrets.token_hex(32))
        from relay.app.main import app
        async with app.router.lifespan_context(app):
            schema=app.openapi()
            assert "/api/v1/sms/otp" in schema["paths"]
            assert "/api/v1/bot/app/upload" in schema["paths"]
            assert Path(folder,"relay.db").is_file()
            print(json.dumps({"isolated_startup":True,"schema":True,"temporary_database":True,
                              "production_environment_loaded":False,"version":schema["info"]["version"]}))

asyncio.run(run())
