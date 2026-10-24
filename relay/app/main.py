from __future__ import annotations

import hmac
import os
import re
import sqlite3
import time
import asyncio
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path
from typing import Iterator, Optional, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response, status
from pydantic import BaseModel, ConfigDict, Field, field_validator

APP_VERSION = "2.2.0"
REGISTRATION = "REGISTRATION_OTP"
RECOVERY = "TRACKING_RECOVERY_OTP"
DELETION = "REGISTRATION_DELETE_OTP"
UNLABELED = "UNLABELED_SIX_DIGIT_CODE"
Operation = Literal["REGISTRATION_OTP", "TRACKING_RECOVERY_OTP", "REGISTRATION_DELETE_OTP"]
NID_RE = re.compile(r"^\d{10}$")
OTP_RE = re.compile(r"^\d{4,8}$")
ID_RE = re.compile(r"^[A-Za-z0-9._:-]{6,160}$")


def env_int(name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(maximum, value))


DB_PATH = Path(os.getenv("RELAY_DB_PATH", "/var/lib/mahan-otp-relay/relay.db"))
BOT_KEY = os.getenv("RELAY_BOT_KEY", "").strip()
PHONE_KEY = os.getenv("RELAY_PHONE_KEY", "").strip()
RELEASE_ADMIN_KEY = os.getenv("RELAY_RELEASE_ADMIN_KEY", "").strip()
WAIT_TTL_SECONDS = env_int("RELAY_WAIT_TTL_SECONDS", 600, 60, 3600)
MAX_OTP_AGE_SECONDS = env_int("RELAY_MAX_OTP_AGE_SECONDS", 600, 30, 1800)
MAX_FUTURE_SKEW_SECONDS = env_int("RELAY_MAX_FUTURE_SKEW_SECONDS", 120, 0, 300)
WAIT_CLOCK_SKEW_SECONDS = env_int("RELAY_WAIT_CLOCK_SKEW_SECONDS", 5, 0, 30)
LONG_POLL_MAX_SECONDS = env_int("RELAY_LONG_POLL_MAX_SECONDS", 25, 1, 30)


@contextmanager
def db_connection() -> Iterator[sqlite3.Connection]:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=30, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.execute("PRAGMA busy_timeout=5000")
        conn.execute("PRAGMA foreign_keys=ON")
        yield conn
    finally:
        conn.close()


def init_db() -> None:
    with db_connection() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS wait_sessions (
                national_id TEXT PRIMARY KEY,
                waiting_since REAL NOT NULL,
                expires_at REAL NOT NULL,
                job_id TEXT,
                status TEXT NOT NULL DEFAULT 'waiting',
                updated_at REAL NOT NULL
            );

            CREATE TABLE IF NOT EXISTS otp_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                national_id TEXT NOT NULL,
                otp TEXT NOT NULL,
                received_at REAL NOT NULL,
                device_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'received',
                created_at REAL NOT NULL,
                delivered_at REAL,
                consumed_at REAL,
                UNIQUE(device_id, message_id)
            );

            CREATE INDEX IF NOT EXISTS idx_otp_nid_status_received
            ON otp_messages(national_id, status, received_at DESC);
            """
        )
        columns = {row[1] for row in conn.execute("PRAGMA table_info(wait_sessions)")}
        if "sender_hash" not in columns:
            conn.execute("ALTER TABLE wait_sessions ADD COLUMN sender_hash TEXT NOT NULL DEFAULT ''")
        for name, declaration in {"otp_type": "TEXT NOT NULL DEFAULT 'REGISTRATION_OTP'",
                                  "delete_approved": "INTEGER NOT NULL DEFAULT 0",
                                  "requires_confirmation": "INTEGER NOT NULL DEFAULT 0",
                                  "confirmed_by": "TEXT NOT NULL DEFAULT ''"}.items():
            if name not in columns:
                conn.execute(f"ALTER TABLE wait_sessions ADD COLUMN {name} {declaration}")
        message_columns = {row[1] for row in conn.execute("PRAGMA table_info(otp_messages)")}
        for name, declaration in {"request_id": "TEXT NOT NULL DEFAULT ''",
                "otp_type": "TEXT NOT NULL DEFAULT 'REGISTRATION_OTP'", "source_type": "TEXT NOT NULL DEFAULT 'REGISTRATION_OTP'",
                "sender_reference": "TEXT NOT NULL DEFAULT ''", "match_status": "TEXT NOT NULL DEFAULT 'LEGACY'",
                "app_version": "TEXT NOT NULL DEFAULT ''", "body_hash": "TEXT NOT NULL DEFAULT ''"}.items():
            if name not in message_columns:
                conn.execute(f"ALTER TABLE otp_messages ADD COLUMN {name} {declaration}")
        # Backfill only the still-active legacy request, never an expired or superseded row.
        conn.execute("""UPDATE otp_messages SET request_id=(SELECT w.job_id FROM wait_sessions w
            WHERE w.national_id=otp_messages.national_id AND w.status='waiting' AND w.otp_type='REGISTRATION_OTP'
            AND w.expires_at>? AND otp_messages.received_at>=w.waiting_since-5)
            WHERE request_id='' AND status IN ('received','delivered')
            AND EXISTS (SELECT 1 FROM wait_sessions w WHERE w.national_id=otp_messages.national_id
            AND w.status='waiting' AND w.otp_type='REGISTRATION_OTP' AND w.expires_at>?
            AND otp_messages.received_at>=w.waiting_since-5 AND w.job_id IS NOT NULL)""", (time.time(), time.time()))
        for row in conn.execute("SELECT id,national_id,request_id,otp_type,otp FROM otp_messages WHERE body_hash='' AND otp<>'' AND request_id<>''"):
            digest = hmac.new(PHONE_KEY.encode(), f"{row['national_id']}|{row['request_id']}|{row['otp_type']}|{row['otp']}".encode(), "sha256").hexdigest()
            conn.execute("UPDATE otp_messages SET body_hash=? WHERE id=?", (digest, row["id"]))
        conn.commit()


def purge_sensitive_codes():
    with db_connection() as conn:
        conn.execute("UPDATE otp_messages SET otp='' WHERE received_at<? OR status IN ('consumed','cleared','superseded')",
                     (time.time() - MAX_OTP_AGE_SECONDS,))
        conn.commit()


async def cleanup_loop():
    while True:
        await asyncio.sleep(60)
        await asyncio.to_thread(purge_sensitive_codes)


def normalize_digits(value: object) -> str:
    translation = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")
    return str(value or "").translate(translation)


def normalize_nid(value: object) -> str:
    return normalize_digits(value).replace("-", "").replace(" ", "").strip()


def normalize_otp(value: object) -> str:
    return normalize_digits(value).strip()


def secure_compare(provided: str, expected: str) -> bool:
    return bool(expected) and bool(provided) and hmac.compare_digest(provided, expected)


def require_bot_key(x_bot_key: str = Header(default="", alias="X-BOT-KEY")) -> None:
    if not secure_compare(x_bot_key.strip(), BOT_KEY):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="unauthorized_bot")


def require_phone_key(x_device_key: str = Header(default="", alias="X-DEVICE-KEY")) -> None:
    if not secure_compare(x_device_key.strip(), PHONE_KEY):
        raise HTTPException(status_code=401, detail="unauthorized_device")


def require_release_admin(x_release_admin_key: str = Header(default="", alias="X-RELEASE-ADMIN-KEY")) -> None:
    if not RELEASE_ADMIN_KEY:
        raise HTTPException(503, "release_admin_not_configured")
    if not secure_compare(x_release_admin_key.strip(), RELEASE_ADMIN_KEY):
        raise HTTPException(401, "unauthorized_release_admin")


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class WaitRequest(StrictModel):
    national_id: str
    waiting_since: float = Field(gt=0)
    job_id: Optional[str] = Field(default=None, max_length=160)
    ttl_seconds: int = Field(default=WAIT_TTL_SECONDS, ge=60, le=3600)
    sender_hash: str = Field(default="", pattern=r"^(?:[0-9a-f]{64})?$")
    otp_type: Operation = REGISTRATION
    delete_approved: bool = False
    confirmed_by: str = Field(default="", max_length=100)
    delete_authorization: str = Field(default="", max_length=256)

    @field_validator("national_id")
    @classmethod
    def valid_nid(cls, value: str) -> str:
        normalized = normalize_nid(value)
        if not NID_RE.fullmatch(normalized):
            raise ValueError("national_id must contain exactly 10 digits")
        return normalized


class PhoneOtpRequest(StrictModel):
    device_id: str
    message_id: str
    national_id: str
    otp: str
    received_at: float = Field(gt=0)
    source: str = Field(default="android", max_length=32)
    consent_version: str = Field(default="1", max_length=16)
    request_id: Optional[str] = Field(default=None, max_length=160)
    otp_type: Operation = REGISTRATION
    source_type: Literal["REGISTRATION_OTP", "TRACKING_RECOVERY_OTP", "REGISTRATION_DELETE_OTP", "UNLABELED_SIX_DIGIT_CODE"] = REGISTRATION
    match_status: Literal["LEGACY", "MATCHED", "USER_CONFIRMED"] = "LEGACY"
    sender_reference: str = Field(default="", pattern=r"^(?:[0-9a-f]{64}|service:2000411)?$")
    app_version: str = Field(default="", max_length=50)
    app_version_code: int = Field(default=0, ge=0)
    event_id: str = Field(default="", max_length=160)

    @field_validator("national_id")
    @classmethod
    def valid_nid(cls, value: str) -> str:
        normalized = normalize_nid(value)
        if not NID_RE.fullmatch(normalized):
            raise ValueError("national_id must contain exactly 10 digits")
        return normalized

    @field_validator("otp")
    @classmethod
    def valid_otp(cls, value: str) -> str:
        normalized = normalize_otp(value)
        if not OTP_RE.fullmatch(normalized):
            raise ValueError("otp must contain 4 to 8 digits")
        return normalized

    @field_validator("device_id", "message_id")
    @classmethod
    def valid_identifier(cls, value: str) -> str:
        if not ID_RE.fullmatch(value):
            raise ValueError("invalid identifier")
        return value


@asynccontextmanager
async def lifespan(_app: FastAPI):
    if not BOT_KEY or not PHONE_KEY:
        raise RuntimeError("Relay authentication keys must be configured")
    init_db()
    purge_sensitive_codes()
    task = asyncio.create_task(cleanup_loop())
    try:
        yield
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass


app = FastAPI(
    title="Mahan OTP Relay",
    version=APP_VERSION,
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, private"
    response.headers["Pragma"] = "no-cache"
    response.headers["Expires"] = "0"
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    return response


@app.get("/status")
def public_status() -> dict:
    return {"ok": True, "service": "mahan-otp-relay", "version": APP_VERSION}


@app.post("/api/v1/bot/wait", dependencies=[Depends(require_bot_key)])
def bot_wait(payload: WaitRequest) -> dict:
    now = time.time()
    if payload.otp_type == DELETION and not (payload.delete_approved and payload.confirmed_by):
        raise HTTPException(403, "deletion_requires_explicit_authorization")
    if payload.otp_type == DELETION:
        try:
            expires, signature = payload.delete_authorization.split(":", 1)
            expires = int(expires)
            message = f"delete-v1|{payload.national_id}|{payload.job_id}|{payload.confirmed_by}|{expires}"
            expected = hmac.new(RELEASE_ADMIN_KEY.encode(), message.encode(), "sha256").hexdigest()
            authorized = bool(RELEASE_ADMIN_KEY) and now <= expires <= now + 420 and hmac.compare_digest(signature, expected)
        except (ValueError, TypeError):
            authorized = False
        if not authorized:
            raise HTTPException(403, "deletion_authority_signature_required")
    if payload.waiting_since > now + MAX_FUTURE_SKEW_SECONDS or now - payload.waiting_since > 3600:
        raise HTTPException(410, "invalid_request_time")
    expires_at = now + payload.ttl_seconds
    with db_connection() as conn:
        conn.execute("BEGIN IMMEDIATE")
        existing = conn.execute("SELECT job_id, status, expires_at, otp_type, delete_approved FROM wait_sessions WHERE national_id=?", (payload.national_id,)).fetchone()
        if existing and payload.job_id and existing["job_id"] == payload.job_id:
            if existing["otp_type"] != payload.otp_type or bool(existing["delete_approved"]) != payload.delete_approved:
                raise HTTPException(409, "request_operation_cannot_change")
            conn.rollback()
            return {"accepted": existing["status"] == "waiting", "expires_at": existing["expires_at"]}
        ambiguous_replacement = bool(existing and existing["job_id"] != payload.job_id and existing["otp_type"] == payload.otp_type
            and existing["expires_at"] > now - MAX_OTP_AGE_SECONDS)
        conn.execute(
            "UPDATE otp_messages SET status='superseded' "
            "WHERE national_id=? AND status IN ('received','delivered')",
            (payload.national_id,),
        )
        conn.execute(
            """
            INSERT INTO wait_sessions(national_id, waiting_since, expires_at, job_id, status, updated_at, sender_hash, otp_type, delete_approved, confirmed_by, requires_confirmation)
            VALUES (?, ?, ?, ?, 'waiting', ?, ?, ?, ?, ?, ?)
            ON CONFLICT(national_id) DO UPDATE SET
                waiting_since=excluded.waiting_since,
                expires_at=excluded.expires_at,
                job_id=excluded.job_id,
                status='waiting',
                updated_at=excluded.updated_at,
                sender_hash=excluded.sender_hash, otp_type=excluded.otp_type,
                delete_approved=excluded.delete_approved, confirmed_by=excluded.confirmed_by,
                requires_confirmation=excluded.requires_confirmation
            """,
            (payload.national_id, payload.waiting_since, expires_at, payload.job_id, now, payload.sender_hash,
                payload.otp_type, int(payload.delete_approved), payload.confirmed_by, int(ambiguous_replacement)),
        )
        conn.commit()
    return {"accepted": True, "waiting_since": payload.waiting_since, "expires_at": expires_at}


@app.get("/api/v1/bot/pull/{national_id}", dependencies=[Depends(require_bot_key)])
def bot_pull(
    national_id: str,
    response: Response,
    after_ts: float = Query(default=0.0, ge=0.0),
    timeout: int = Query(default=20, ge=0, le=30),
    otp_type: Operation = REGISTRATION,
    request_id: str = "",
):
    nid = normalize_nid(national_id)
    if not NID_RE.fullmatch(nid):
        raise HTTPException(status_code=400, detail="invalid_national_id")
    if otp_type != REGISTRATION and not request_id:
        raise HTTPException(400, "typed_request_id_required")

    deadline = time.monotonic() + min(timeout, LONG_POLL_MAX_SECONDS)
    while True:
        now = time.time()
        with db_connection() as conn:
            wait = conn.execute(
                "SELECT waiting_since, expires_at, status, job_id, otp_type FROM wait_sessions WHERE national_id=?",
                (nid,),
            ).fetchone()
            if wait and wait["status"] == "waiting" and float(wait["expires_at"]) >= now and wait["otp_type"] == otp_type and (not request_id or request_id == wait["job_id"]):
                minimum_ts = max(float(after_ts), float(wait["waiting_since"]) - WAIT_CLOCK_SKEW_SECONDS)
                row = conn.execute(
                    """
                    SELECT id, otp, received_at, message_id, otp_type
                    FROM otp_messages
                    WHERE national_id=?
                      AND status IN ('received','delivered')
                      AND received_at>?
                      AND otp_type=? AND request_id=?
                      AND otp<>'' AND received_at>?
                    ORDER BY received_at DESC, id DESC
                    LIMIT 1
                    """,
                    (nid, minimum_ts, otp_type, wait["job_id"], now - MAX_OTP_AGE_SECONDS),
                ).fetchone()
                if row:
                    conn.execute(
                        "UPDATE otp_messages SET status='delivered', delivered_at=COALESCE(delivered_at, ?) WHERE id=?",
                        (now, row["id"]),
                    )
                    conn.commit()
                    return {
                        "otp": row["otp"],
                        "ts": float(row["received_at"]),
                        "message_id": row["message_id"],
                        "request_id": wait["job_id"],
                        "nid": nid,
                        "otp_type": row["otp_type"],
                    }

        if timeout <= 0 or time.monotonic() >= deadline:
            response.status_code = status.HTTP_204_NO_CONTENT
            return None
        time.sleep(0.5)


@app.post("/api/v1/bot/consumed/{national_id}", dependencies=[Depends(require_bot_key)])
def bot_consumed(national_id: str, request_id: str = "") -> dict:
    nid = normalize_nid(national_id)
    if not NID_RE.fullmatch(nid):
        raise HTTPException(status_code=400, detail="invalid_national_id")
    now = time.time()
    with db_connection() as conn:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT job_id,otp_type FROM wait_sessions WHERE national_id=?", (nid,)).fetchone()
        if row and row["otp_type"] != REGISTRATION and not request_id:
            raise HTTPException(409, "typed_request_id_required")
        if request_id and (not row or row["job_id"] != request_id):
            raise HTTPException(status_code=409, detail="request_mismatch")
        conn.execute(
            "UPDATE otp_messages SET status='consumed', consumed_at=?, otp='' "
            "WHERE national_id=? AND status IN ('received','delivered')",
            (now, nid),
        )
        conn.execute(
            "UPDATE wait_sessions SET status='consumed', updated_at=? WHERE national_id=?",
            (now, nid),
        )
        conn.commit()
    return {"consumed": True}


@app.post("/api/v1/bot/clear/{national_id}", dependencies=[Depends(require_bot_key)])
def bot_clear(national_id: str, request_id: str = "") -> dict:
    nid = normalize_nid(national_id)
    if not NID_RE.fullmatch(nid):
        raise HTTPException(status_code=400, detail="invalid_national_id")
    now = time.time()
    with db_connection() as conn:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT job_id,otp_type FROM wait_sessions WHERE national_id=?", (nid,)).fetchone()
        if row and row["otp_type"] != REGISTRATION and not request_id:
            raise HTTPException(409, "typed_request_id_required")
        if request_id and (not row or row["job_id"] != request_id):
            raise HTTPException(status_code=409, detail="request_mismatch")
        conn.execute(
            "UPDATE otp_messages SET status='cleared', otp='' "
            "WHERE national_id=? AND status IN ('received','delivered')",
            (nid,),
        )
        conn.execute(
            "UPDATE wait_sessions SET status='cleared', updated_at=? WHERE national_id=?",
            (now, nid),
        )
        conn.commit()
    return {"cleared": True}


@app.get("/api/v1/sms/otp/targets", dependencies=[Depends(require_phone_key)])
def phone_targets() -> dict:
    with db_connection() as conn:
        rows = conn.execute("SELECT national_id, job_id, sender_hash, waiting_since, expires_at, otp_type, delete_approved, requires_confirmation FROM wait_sessions WHERE status='waiting' AND expires_at>?", (time.time(),)).fetchall()
    return {"status": "ok", "protocol_version": 2,
            "targets": [{**dict(row), "delete_approved": bool(row["delete_approved"]),
                         "requires_confirmation": bool(row["requires_confirmation"])} for row in rows]}


@app.get("/api/v1/sms/otp/status", dependencies=[Depends(require_phone_key)])
def phone_status(national_id: str = Query(default="")) -> dict:
    nid = normalize_nid(national_id)
    if not NID_RE.fullmatch(nid):
        raise HTTPException(status_code=400, detail="invalid_national_id")
    now = time.time()
    with db_connection() as conn:
        row = conn.execute(
            "SELECT waiting_since, expires_at, status FROM wait_sessions WHERE national_id=?",
            (nid,),
        ).fetchone()
    waiting = bool(row and row["status"] == "waiting" and float(row["expires_at"]) >= now)
    return {
        "ok": True,
        "status": "ok",
        "applicant_found": True,
        "waiting": waiting,
        "waiting_since": float(row["waiting_since"]) if row else None,
    }


@app.post("/api/v1/sms/otp", dependencies=[Depends(require_phone_key)])
def phone_otp(
    payload: PhoneOtpRequest,
    x_device_id: str = Header(default="", alias="X-DEVICE-ID"),
) -> dict:
    try:
        from .release_api import ReleaseStore
    except ImportError:
        from relay.app.release_api import ReleaseStore
    try:
        minimum = ReleaseStore(DB_PATH.with_name("app-releases.db")).latest()["min_supported_version_code"]
    except HTTPException as error:
        if error.status_code != 404:
            raise
        minimum = 1
    if minimum > 1 and payload.app_version_code < minimum:
        raise HTTPException(426, "app_update_required")
    header_device_id = x_device_id.strip()
    if header_device_id and not hmac.compare_digest(header_device_id, payload.device_id):
        raise HTTPException(status_code=400, detail="device_id_mismatch")
    if payload.event_id and payload.event_id != payload.message_id:
        raise HTTPException(400, "event_identifier_mismatch")
    if payload.source_type != UNLABELED and payload.source_type != payload.otp_type:
        raise HTTPException(409, "source_operation_mismatch")
    if payload.source_type == UNLABELED and payload.match_status != "USER_CONFIRMED":
        raise HTTPException(409, "bare_code_needs_confirmation")

    now = time.time()
    if payload.received_at > now + MAX_FUTURE_SKEW_SECONDS:
        raise HTTPException(status_code=410, detail="otp_from_future")
    if now - payload.received_at > MAX_OTP_AGE_SECONDS:
        raise HTTPException(status_code=410, detail="stale_otp")
    body_hash = hmac.new(PHONE_KEY.encode(), f"{payload.national_id}|{payload.request_id}|{payload.otp_type}|{payload.otp}".encode(), "sha256").hexdigest()

    with db_connection() as conn:
        conn.execute("BEGIN IMMEDIATE")
        duplicate = conn.execute(
            "SELECT status, national_id, request_id, otp_type, body_hash FROM otp_messages WHERE device_id=? AND message_id=?",
            (payload.device_id, payload.message_id),
        ).fetchone()
        if duplicate:
            if (duplicate["national_id"], duplicate["request_id"], duplicate["otp_type"]) != (payload.national_id, payload.request_id, payload.otp_type):
                raise HTTPException(409, "event_already_bound_to_another_request")
            if duplicate["body_hash"] and not hmac.compare_digest(duplicate["body_hash"], body_hash):
                raise HTTPException(409, "event_content_changed")
            conn.rollback()
            return {"status": "ok", "accepted": True, "duplicate": True}

        content_duplicate = conn.execute("SELECT 1 FROM otp_messages WHERE national_id=? AND request_id=? AND otp_type=? AND body_hash=?",
            (payload.national_id, payload.request_id, payload.otp_type, body_hash)).fetchone()
        if content_duplicate:
            return {"status": "ok", "accepted": True, "duplicate": True}

        wait = conn.execute(
            "SELECT waiting_since, expires_at, status, job_id, otp_type, delete_approved, sender_hash, requires_confirmation FROM wait_sessions WHERE national_id=?",
            (payload.national_id,),
        ).fetchone()
        if not wait:
            conn.rollback()
            raise HTTPException(status_code=409, detail="not_waiting")
        if wait["status"] != "waiting" or float(wait["expires_at"]) < now:
            conn.rollback()
            raise HTTPException(status_code=409, detail="not_waiting")
        if not payload.request_id or payload.request_id != wait["job_id"]:
            conn.rollback()
            raise HTTPException(status_code=409, detail="request_mismatch")
        if payload.otp_type != wait["otp_type"]:
            raise HTTPException(409, "operation_mismatch")
        if payload.app_version_code >= 5 and payload.match_status != "USER_CONFIRMED" and (
                wait["requires_confirmation"] or payload.received_at < wait["waiting_since"]):
            raise HTTPException(409, "request_correlation_needs_confirmation")
        if payload.otp_type == DELETION and not wait["delete_approved"]:
            raise HTTPException(403, "deletion_not_authorized")
        if payload.source_type == UNLABELED and payload.sender_reference != "service:2000411" and not (
                payload.sender_reference and payload.sender_reference == wait["sender_hash"]):
            raise HTTPException(409, "bare_sender_not_correlated")
        if payload.received_at + WAIT_CLOCK_SKEW_SECONDS < float(wait["waiting_since"]):
            conn.rollback()
            raise HTTPException(status_code=410, detail="otp_before_wait_window")

        conn.execute(
            "UPDATE otp_messages SET status='superseded' "
            "WHERE national_id=? AND status IN ('received','delivered')",
            (payload.national_id,),
        )
        conn.execute(
            """
            INSERT INTO otp_messages(
                national_id, otp, received_at, device_id, message_id, status, created_at,
                request_id, otp_type, source_type, match_status, sender_reference, app_version, body_hash
            ) VALUES (?, ?, ?, ?, ?, 'received', ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                payload.national_id,
                payload.otp,
                payload.received_at,
                payload.device_id,
                payload.message_id,
                now,
                payload.request_id, payload.otp_type, payload.source_type, payload.match_status,
                payload.sender_reference, payload.app_version,
                body_hash,
            ),
        )
        conn.commit()
    return {"status": "ok", "accepted": True, "duplicate": False}


try:
    from .release_api import register_routes as register_release_routes
except ImportError:  # Standalone test module loader.
    from relay.app.release_api import register_routes as register_release_routes
register_release_routes(app, lambda: DB_PATH.with_name("app-releases.db"), require_phone_key, require_release_admin)
