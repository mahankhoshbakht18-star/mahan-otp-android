import importlib.util
from pathlib import Path
import sqlite3
import sys
import tempfile
import time
import unittest

spec=importlib.util.spec_from_file_location("migration_relay",Path(__file__).parent/"app"/"main.py")
relay=importlib.util.module_from_spec(spec);sys.modules[spec.name]=relay;spec.loader.exec_module(relay)


class LegacyMigrationTests(unittest.TestCase):
    def test_old_schema_and_pending_message_are_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            relay.DB_PATH=Path(temporary)/"legacy.db";relay.PHONE_KEY="fixture-only-migration"
            now=time.time()
            with sqlite3.connect(relay.DB_PATH) as db:
                db.executescript("""
                CREATE TABLE wait_sessions(national_id TEXT PRIMARY KEY,waiting_since REAL NOT NULL,expires_at REAL NOT NULL,
                    job_id TEXT,status TEXT NOT NULL DEFAULT 'waiting',updated_at REAL NOT NULL);
                CREATE TABLE otp_messages(id INTEGER PRIMARY KEY AUTOINCREMENT,national_id TEXT NOT NULL,otp TEXT NOT NULL,
                    received_at REAL NOT NULL,device_id TEXT NOT NULL,message_id TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'received',
                    created_at REAL NOT NULL,delivered_at REAL,consumed_at REAL,UNIQUE(device_id,message_id));
                """)
                db.execute("INSERT INTO wait_sessions VALUES(?,?,?,?,'waiting',?)",("0012345679",now-2,now+120,"fixture-job",now))
                db.execute("INSERT INTO otp_messages(national_id,otp,received_at,device_id,message_id,created_at) VALUES(?,?,?,?,?,?)",
                    ("0012345679","123456",now-1,"fixture-device","fixture-message",now))
            db.close()
            relay.init_db();relay.init_db()  # A repeated migration must be idempotent.
            with relay.db_connection() as db:
                row=db.execute("SELECT * FROM otp_messages").fetchone()
                self.assertEqual((row["otp"],row["request_id"],row["otp_type"]),("123456","fixture-job","REGISTRATION_OTP"))
                self.assertEqual(len(row["body_hash"]),64)
                self.assertEqual(db.execute("SELECT COUNT(*) FROM otp_messages").fetchone()[0],1)
                self.assertEqual(db.execute("SELECT COUNT(*) FROM wait_sessions").fetchone()[0],1)

if __name__=="__main__":unittest.main()
