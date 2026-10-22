from __future__ import annotations

import hmac
import os
import re
import sqlite3
import time
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path
from typing import Iterator, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response, status
from pydantic import BaseModel, ConfigDict, Field, field_validator

APP_VERSION = "2.0.0"
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
        conn.commit()


def normalize_digits(value: object) -> str:
    translation = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")
    return str(value or "").translate(translation)


def normalize_nid(value: object) -> str:
    return normalize_digits(value).replace("-", "").replace(" ", "").strip()


def normalize_otp(value: object) -> str:
    return "".join(ch for ch in normalize_digits(value) if ch.isdigit())


def secure_compare(provided: str, expected: str) -> bool:
    return bool(expected) and bool(provided) and hmac.compare_digest(provided, expected)


def require_bot_key(x_bot_key: str = Header(default="", alias="X-BOT-KEY")) -> None:
    if not secure_compare(x_bot_key.strip(), BOT_KEY):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="unauthorized_bot")


def require_phone_key(x_device_key: str = Header(default="", alias="X-DEVICE-KEY")) -> None:
    if not secure_compare(x_device_key.strip(), PHONE_KEY):
        raise HTTPException(status_code=401, detail="unauthorized_device")


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class WaitRequest(StrictModel):
    national_id: str
    waiting_since: float = Field(gt=0)
    job_id: Optional[str] = Field(default=None, max_length=160)
    ttl_seconds: int = Field(default=WAIT_TTL_SECONDS, ge=60, le=3600)
    sender_hash: str = Field(default="", pattern=r"^(?:[0-9a-f]{64})?$")

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
    yield


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
    expires_at = now + payload.ttl_seconds
    with db_connection() as conn:
        conn.execute("BEGIN IMMEDIATE")
        existing = conn.execute("SELECT job_id, status, expires_at FROM wait_sessions WHERE national_id=?", (payload.national_id,)).fetchone()
        if existing and payload.job_id and existing["job_id"] == payload.job_id:
            conn.rollback()
            return {"accepted": existing["status"] == "waiting", "expires_at": existing["expires_at"]}
        conn.execute(
            "UPDATE otp_messages SET status='superseded' "
            "WHERE national_id=? AND status IN ('received','delivered')",
            (payload.national_id,),
        )
        conn.execute(
            """
            INSERT INTO wait_sessions(national_id, waiting_since, expires_at, job_id, status, updated_at, sender_hash)
            VALUES (?, ?, ?, ?, 'waiting', ?, ?)
            ON CONFLICT(national_id) DO UPDATE SET
                waiting_since=excluded.waiting_since,
                expires_at=excluded.expires_at,
                job_id=excluded.job_id,
                status='waiting',
                updated_at=excluded.updated_at,
                sender_hash=excluded.sender_hash
            """,
            (payload.national_id, payload.waiting_since, expires_at, payload.job_id, now, payload.sender_hash),
        )
        conn.commit()
    return {"accepted": True, "waiting_since": payload.waiting_since, "expires_at": expires_at}


@app.get("/api/v1/bot/pull/{national_id}", dependencies=[Depends(require_bot_key)])
def bot_pull(
    national_id: str,
    response: Response,
    after_ts: float = Query(default=0.0, ge=0.0),
    timeout: int = Query(default=20, ge=0, le=30),
):
    nid = normalize_nid(national_id)
    if not NID_RE.fullmatch(nid):
        raise HTTPException(status_code=400, detail="invalid_national_id")

    deadline = time.monotonic() + min(timeout, LONG_POLL_MAX_SECONDS)
    while True:
        now = time.time()
        with db_connection() as conn:
            wait = conn.execute(
                "SELECT waiting_since, expires_at, status, job_id FROM wait_sessions WHERE national_id=?",
                (nid,),
            ).fetchone()
            if wait and wait["status"] == "waiting" and float(wait["expires_at"]) >= now:
                minimum_ts = max(float(after_ts), float(wait["waiting_since"]) - WAIT_CLOCK_SKEW_SECONDS)
                row = conn.execute(
                    """
                    SELECT id, otp, received_at, message_id
                    FROM otp_messages
                    WHERE national_id=?
                      AND status IN ('received','delivered')
                      AND received_at>?
                    ORDER BY received_at DESC, id DESC
                    LIMIT 1
                    """,
                    (nid, minimum_ts),
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
        row = conn.execute("SELECT job_id FROM wait_sessions WHERE national_id=?", (nid,)).fetchone()
        if request_id and (not row or row["job_id"] != request_id):
            raise HTTPException(status_code=409, detail="request_mismatch")
        conn.execute(
            "UPDATE otp_messages SET status='consumed', consumed_at=? "
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
        row = conn.execute("SELECT job_id FROM wait_sessions WHERE national_id=?", (nid,)).fetchone()
        if request_id and (not row or row["job_id"] != request_id):
            raise HTTPException(status_code=409, detail="request_mismatch")
        conn.execute(
            "UPDATE otp_messages SET status='cleared' "
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
        rows = conn.execute("SELECT national_id, job_id, sender_hash, waiting_since, expires_at FROM wait_sessions WHERE status='waiting' AND expires_at>?", (time.time(),)).fetchall()
    return {"status": "ok", "targets": [dict(row) for row in rows]}


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
    header_device_id = x_device_id.strip()
    if header_device_id and not hmac.compare_digest(header_device_id, payload.device_id):
        raise HTTPException(status_code=400, detail="device_id_mismatch")

    now = time.time()
    if payload.received_at > now + MAX_FUTURE_SKEW_SECONDS:
        raise HTTPException(status_code=410, detail="otp_from_future")
    if now - payload.received_at > MAX_OTP_AGE_SECONDS:
        raise HTTPException(status_code=410, detail="stale_otp")

    with db_connection() as conn:
        conn.execute("BEGIN IMMEDIATE")
        duplicate = conn.execute(
            "SELECT status FROM otp_messages WHERE device_id=? AND message_id=?",
            (payload.device_id, payload.message_id),
        ).fetchone()
        if duplicate:
            conn.rollback()
            return {"status": "ok", "accepted": True, "duplicate": True}

        wait = conn.execute(
            "SELECT waiting_since, expires_at, status, job_id FROM wait_sessions WHERE national_id=?",
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
                national_id, otp, received_at, device_id, message_id, status, created_at
            ) VALUES (?, ?, ?, ?, ?, 'received', ?)
            """,
            (
                payload.national_id,
                payload.otp,
                payload.received_at,
                payload.device_id,
                payload.message_id,
                now,
            ),
        )
        conn.commit()
    return {"status": "ok", "accepted": True, "duplicate": False}
