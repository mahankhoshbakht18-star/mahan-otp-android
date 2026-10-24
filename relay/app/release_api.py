"""Private release control plane. No release is public until a separate approval action.

No FCM is configured: notifications below are queued-for-poll, never called delivered push.
"""
from __future__ import annotations
import asyncio
import hashlib
import json
import os
import re
import sqlite3
import subprocess
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Literal

from fastapi import Depends, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field

PACKAGE = "com.example.codresan"
CERTIFICATE = "aa151db817e67e44f31840b3deb0e5b146723d0586097dd1096dda33c3f20259"
MAX_APK = 100 * 1024 * 1024


class ReleaseInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    version_code: int = Field(gt=0)
    version_name: str = Field(min_length=1, max_length=50)
    min_supported_version_code: int = Field(default=1, gt=0)
    release_notes: str = Field(min_length=1, max_length=4000)
    update_priority: Literal["normal", "critical"] = "normal"


class Approval(BaseModel):
    model_config = ConfigDict(extra="forbid")
    channel: Literal["pilot", "production"]
    approval: str
    test_report: str = Field(min_length=10, max_length=2000)
    pilot_device_ids: list[str] = Field(default_factory=list, max_length=200)


class DeviceReport(BaseModel):
    model_config = ConfigDict(extra="forbid")
    device_id: uuid.UUID
    version_code: int = Field(gt=0, le=10000000)
    version_name: str = Field(min_length=1, max_length=50)
    package_name: Literal["com.example.codresan"] = PACKAGE
    update_notifications: bool = False
    reporting_consent: Literal[True]
    update_state: Literal["first_launch", "heartbeat", "install_verified", "notification_shown", "notification_opened", "install_failed"] = "heartbeat"
    previous_version: int = Field(default=0, ge=0)


class ReleaseStore:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS app_uploads(sha256 TEXT PRIMARY KEY, path TEXT NOT NULL,
                    size INTEGER NOT NULL, package_name TEXT NOT NULL, version_code INTEGER NOT NULL,
                    version_name TEXT NOT NULL, certificate_sha256 TEXT NOT NULL, verified_at REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS app_releases(version_code INTEGER PRIMARY KEY, metadata TEXT NOT NULL,
                    sha256 TEXT NOT NULL, channel TEXT NOT NULL DEFAULT 'draft', enabled INTEGER NOT NULL DEFAULT 0,
                    published_at TEXT NOT NULL DEFAULT '', pilot_ids TEXT NOT NULL DEFAULT '[]', test_report TEXT NOT NULL DEFAULT '');
                CREATE TABLE IF NOT EXISTS app_devices(device_id TEXT PRIMARY KEY, version_code INTEGER NOT NULL,
                    version_name TEXT NOT NULL, last_seen REAL NOT NULL, notifications INTEGER NOT NULL,
                    update_state TEXT NOT NULL, previous_version INTEGER NOT NULL DEFAULT 0,
                    reported_upgrade_code INTEGER NOT NULL DEFAULT 0, reported_upgrade_at REAL NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS app_release_audit(id INTEGER PRIMARY KEY, at REAL NOT NULL, action TEXT NOT NULL,
                    version_code INTEGER, detail TEXT NOT NULL DEFAULT '');
                CREATE TABLE IF NOT EXISTS app_notices(device_id TEXT NOT NULL, version_code INTEGER NOT NULL,
                    state TEXT NOT NULL DEFAULT 'queued_for_poll', created_at REAL NOT NULL,
                    PRIMARY KEY(device_id,version_code));
            """)
            columns = {row[1] for row in db.execute("PRAGMA table_info(app_devices)")}
            if "reported_upgrade_code" not in columns:
                db.execute("ALTER TABLE app_devices ADD COLUMN reported_upgrade_code INTEGER NOT NULL DEFAULT 0")
            if "reported_upgrade_at" not in columns:
                db.execute("ALTER TABLE app_devices ADD COLUMN reported_upgrade_at REAL NOT NULL DEFAULT 0")

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def upload_directory(self):
        path = self.path.parent / "verified-app-uploads"
        path.mkdir(mode=0o700, exist_ok=True)
        return path

    def latest(self, device: str = ""):
        with self.connection() as db:
            for row in db.execute("SELECT * FROM app_releases WHERE enabled=1 ORDER BY version_code DESC"):
                if row["channel"] == "production" or (device and row["channel"] == "pilot" and device in json.loads(row["pilot_ids"])):
                    metadata = json.loads(row["metadata"])
                    metadata["published_at"] = row["published_at"]
                    return metadata
        raise HTTPException(404, "no_approved_release")


def verify_apk(path: Path) -> dict:
    """Verification executables are fixed host configuration, never supplied by the browser."""
    java = os.getenv("RELAY_JAVA", "java")
    signer = os.getenv("RELAY_APKSIGNER_JAR", "")
    aapt = os.getenv("RELAY_AAPT", "")
    if not signer or not aapt:
        raise HTTPException(503, "apk_verifier_not_configured")
    try:
        signature = subprocess.run([java, "-jar", signer, "verify", "--print-certs", str(path)],
            capture_output=True, text=True, timeout=60, check=True).stdout
        if set(re.findall(r"certificate SHA-256 digest: ([a-f0-9]{64})", signature)) != {CERTIFICATE}:
            raise HTTPException(422, "wrong_signing_certificate")
        badging = subprocess.run([aapt, "dump", "badging", str(path)], capture_output=True,
            text=True, encoding="utf-8", errors="replace", timeout=30, check=True).stdout
        match = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
        if not match or match[1] != PACKAGE:
            raise HTTPException(422, "wrong_package")
        return {"package_name": match[1], "version_code": int(match[2]), "version_name": match[3],
                "certificate_sha256": CERTIFICATE}
    except (subprocess.SubprocessError, OSError):
        raise HTTPException(422, "apk_verification_failed") from None


def register_routes(app, path_provider: Callable[[], Path], phone_auth, bot_auth):
    def store():
        return ReleaseStore(path_provider())

    @app.get("/api/app/version")
    @app.get("/app/version.json")
    def public_version():
        return store().latest()

    @app.get("/api/v1/sms/app/version", dependencies=[Depends(phone_auth)])
    def device_version(request: Request):
        return store().latest(request.headers.get("X-DEVICE-ID", ""))

    @app.post("/api/v1/sms/app/report", dependencies=[Depends(phone_auth)])
    def device_report(payload: DeviceReport, request: Request):
        device = str(payload.device_id)
        if request.headers.get("X-DEVICE-ID") != device:
            raise HTTPException(400, "device_mismatch")
        release_store = store()
        with release_store.connection() as db:
            previous = db.execute("SELECT * FROM app_devices WHERE device_id=?", (device,)).fetchone()
            if payload.update_state == "install_verified" and not (
                    previous and payload.version_code > previous["version_code"] and payload.previous_version == previous["version_code"]):
                # Self-reported first launch is not proof that an update occurred.
                state = "first_launch"
            else:
                state = payload.update_state
            db.execute("""INSERT INTO app_devices(device_id,version_code,version_name,last_seen,notifications,update_state,previous_version)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET
                version_code=excluded.version_code,version_name=excluded.version_name,last_seen=excluded.last_seen,
                notifications=excluded.notifications,update_state=excluded.update_state,previous_version=excluded.previous_version""",
                (device, payload.version_code, payload.version_name, time.time(), int(payload.update_notifications), state, payload.previous_version))
            if state == "notification_shown":
                db.execute("UPDATE app_notices SET state='display_reported' WHERE device_id=? AND state='queued_for_poll'", (device,))
            if state == "install_verified":
                db.execute("UPDATE app_devices SET reported_upgrade_code=?,reported_upgrade_at=? WHERE device_id=?", (payload.version_code, time.time(), device))
                db.execute("UPDATE app_notices SET state='installation_reported' WHERE device_id=? AND version_code<=?", (device, payload.version_code))
        return {"accepted": True, "recorded_state": state}

    @app.post("/api/v1/bot/app/upload", dependencies=[Depends(bot_auth)])
    async def upload(request: Request):
        release_store = store()
        partial = release_store.upload_directory() / (uuid.uuid4().hex + ".part")
        size = 0
        digest = hashlib.sha256()
        try:
            with partial.open("xb") as output:
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > MAX_APK:
                        raise HTTPException(413, "apk_too_large")
                    digest.update(chunk)
                    output.write(chunk)
            if size < 1024:
                raise HTTPException(422, "invalid_apk")
            # Android verification starts subprocesses; keep OTP handling responsive.
            verified = await asyncio.to_thread(verify_apk, partial)
            sha = digest.hexdigest()
            target = partial.with_name(sha + ".apk")
            partial.replace(target)
            with release_store.connection() as db:
                db.execute("INSERT OR REPLACE INTO app_uploads VALUES(?,?,?,?,?,?,?,?)", (sha, str(target), size,
                    verified["package_name"], verified["version_code"], verified["version_name"], verified["certificate_sha256"], time.time()))
            return {"sha256": sha, "size": size, **verified}
        finally:
            partial.unlink(missing_ok=True)

    @app.post("/api/v1/bot/app/releases", dependencies=[Depends(bot_auth)])
    def create_release(payload: ReleaseInput):
        release_store = store()
        with release_store.connection() as db:
            uploaded = db.execute("SELECT * FROM app_uploads WHERE sha256=?", (payload.sha256,)).fetchone()
            if not uploaded or uploaded["version_code"] != payload.version_code or uploaded["version_name"] != payload.version_name:
                raise HTTPException(409, "upload_verified_apk_first")
            if payload.min_supported_version_code > payload.version_code:
                raise HTTPException(422, "invalid_minimum_version")
            if db.execute("SELECT 1 FROM app_releases WHERE version_code=?", (payload.version_code,)).fetchone():
                raise HTTPException(409, "version_already_exists")
            metadata = {**payload.model_dump(exclude={"sha256"}), "apk_sha256": payload.sha256,
                "download_url": f"https://otp.mahanvip.ir/downloads/mahanbot-{payload.sha256}.apk",
                "package_name": PACKAGE, "certificate_sha256": CERTIFICATE,
                "latest_version": payload.version_name, "latest_version_code": payload.version_code,
                "minimum_supported_version": payload.min_supported_version_code, "sha256": payload.sha256}
            db.execute("INSERT INTO app_releases(version_code,metadata,sha256) VALUES(?,?,?)",
                (payload.version_code, json.dumps(metadata, ensure_ascii=False), payload.sha256))
            db.execute("INSERT INTO app_release_audit(at,action,version_code) VALUES(?,?,?)", (time.time(), "draft_created", payload.version_code))
        return {"status": "draft", "version_code": payload.version_code}

    @app.post("/api/v1/bot/app/releases/{version}/approve", dependencies=[Depends(bot_auth)])
    def approve(version: int, payload: Approval):
        required = "APPROVE_PRODUCTION" if payload.channel == "production" else "APPROVE_PILOT"
        if payload.approval != required:
            raise HTTPException(403, "explicit_release_approval_required")
        if payload.channel == "pilot" and not payload.pilot_device_ids:
            raise HTTPException(422, "pilot_devices_required")
        release_store = store()
        with release_store.connection() as db:
            row = db.execute("SELECT * FROM app_releases WHERE version_code=?", (version,)).fetchone()
            if not row:
                raise HTTPException(404, "release_not_found")
            latest = db.execute("SELECT MAX(version_code) FROM app_releases WHERE channel='production' AND enabled=1").fetchone()[0]
            if payload.channel == "production" and latest and version < latest:
                raise HTTPException(409, "downgrade_not_allowed")
            if payload.channel == "production" and row["channel"] != "pilot":
                raise HTTPException(409, "pilot_release_required_first")
            uploaded = db.execute("SELECT * FROM app_uploads WHERE sha256=?", (row["sha256"],)).fetchone()
            if not uploaded or hashlib.sha256(Path(uploaded["path"]).read_bytes()).hexdigest() != row["sha256"]:
                raise HTTPException(409, "apk_changed_after_verification")
            db.execute("UPDATE app_releases SET channel=?,enabled=1,published_at=?,pilot_ids=?,test_report=? WHERE version_code=?",
                (payload.channel, datetime.now(timezone.utc).isoformat(), json.dumps(payload.pilot_device_ids), payload.test_report, version))
            db.execute("INSERT INTO app_release_audit(at,action,version_code,detail) VALUES(?,?,?,?)",
                (time.time(), f"approved_{payload.channel}", version, payload.test_report))
        return {"enabled": True, "channel": payload.channel}

    @app.post("/api/v1/bot/app/releases/{version}/stop", dependencies=[Depends(bot_auth)])
    def stop_release(version: int):
        with store().connection() as db:
            if not db.execute("SELECT 1 FROM app_releases WHERE version_code=?", (version,)).fetchone():
                raise HTTPException(404, "release_not_found")
            db.execute("UPDATE app_releases SET enabled=0 WHERE version_code=?", (version,))
            db.execute("INSERT INTO app_release_audit(at,action,version_code) VALUES(?,?,?)", (time.time(), "distribution_stopped", version))
        return {"enabled": False}

    @app.post("/api/v1/bot/app/releases/{version}/notify", dependencies=[Depends(bot_auth)])
    def notify(version: int):
        queued = 0
        with store().connection() as db:
            release = db.execute("SELECT * FROM app_releases WHERE version_code=? AND enabled=1", (version,)).fetchone()
            if not release:
                raise HTTPException(409, "release_not_active")
            pilots = json.loads(release["pilot_ids"])
            for device in db.execute("SELECT device_id FROM app_devices WHERE version_code<? AND notifications=1", (version,)):
                if release["channel"] == "pilot" and device["device_id"] not in pilots:
                    continue
                queued += db.execute("INSERT OR IGNORE INTO app_notices VALUES(?,?,'queued_for_poll',?)",
                    (device["device_id"], version, time.time())).rowcount
        return {"queued_for_poll": queued, "push_sent": 0, "fcm_available": False,
                "note": "No receiver can be added remotely to old app versions; use the approved bot/channel download notice."}

    @app.get("/api/v1/bot/app/overview", dependencies=[Depends(bot_auth)])
    def overview():
        with store().connection() as db:
            releases = [dict(row) for row in db.execute("SELECT * FROM app_releases ORDER BY version_code DESC")]
            devices = [dict(row) for row in db.execute("SELECT * FROM app_devices ORDER BY last_seen DESC")]
            notices = [dict(row) for row in db.execute("SELECT version_code,state,COUNT(*) AS count FROM app_notices GROUP BY version_code,state")]
            audit = [dict(row) for row in db.execute("SELECT * FROM app_release_audit ORDER BY id DESC LIMIT 100")]
        enabled = [row["version_code"] for row in releases if row["enabled"] and row["channel"] == "production"]
        latest = max(enabled, default=0)
        return {"latest_version_code": latest, "registered_devices": len(devices),
            "outdated_devices": sum(device["version_code"] < latest for device in devices) if latest else 0,
            "devices": devices, "releases": releases, "notices": notices, "history": audit,
            "fcm_available": False, "unregistered_legacy_devices": "unknown"}

    @app.get("/downloads/mahanbot-{sha}.apk")
    def download(sha: str, request: Request):
        if not re.fullmatch(r"[0-9a-f]{64}", sha):
            raise HTTPException(404)
        release_store = store()
        with release_store.connection() as db:
            row = db.execute("SELECT * FROM app_releases WHERE sha256=? AND enabled=1", (sha,)).fetchone()
            uploaded = db.execute("SELECT path FROM app_uploads WHERE sha256=?", (sha,)).fetchone()
        if not row or not uploaded:
            raise HTTPException(404, "distribution_not_active")
        if row["channel"] != "production":
            # Uses the same device authentication as the version request.
            import hmac
            expected = os.getenv("RELAY_PHONE_KEY", "")
            if not expected or not hmac.compare_digest(request.headers.get("X-DEVICE-KEY", ""), expected):
                raise HTTPException(401)
            if request.headers.get("X-DEVICE-ID", "") not in json.loads(row["pilot_ids"]):
                raise HTTPException(403)
        return FileResponse(uploaded["path"], filename="MAHANBOT-Android-release.apk", media_type="application/vnd.android.package-archive")
