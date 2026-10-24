import importlib.util
import os
import tempfile
import time
import unittest
import sys
import hmac
from pathlib import Path
from fastapi.testclient import TestClient

spec = importlib.util.spec_from_file_location("tested_relay", Path(__file__).parent / "app" / "main.py")
relay = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = relay
spec.loader.exec_module(relay)


class RelayTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        relay.DB_PATH = Path(self.temp.name) / "relay.db"
        relay.BOT_KEY, relay.PHONE_KEY = "fixture-bot", "fixture-phone"
        relay.RELEASE_ADMIN_KEY = "fixture-delete-authority"
        self.bot_headers = {"X-BOT-KEY": relay.BOT_KEY}
        self.phone_headers = {"X-DEVICE-KEY": relay.PHONE_KEY, "X-DEVICE-ID": "fixture-device"}
        self.client = TestClient(relay.app).__enter__()
        self.nid = "0012345679"
        self.job = "fixture-request-1"

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.temp.cleanup()

    def wait(self, job=None):
        return self.client.post("/api/v1/bot/wait", headers=self.bot_headers,
            json={"national_id": self.nid, "waiting_since": time.time() - 1,
                  "job_id": job or self.job, "ttl_seconds": 120, "sender_hash": "a" * 64})

    def payload(self, **changes):
        value = {"device_id": "fixture-device", "message_id": "fixture-message-1", "national_id": self.nid,
                 "otp": "123456", "received_at": time.time(), "request_id": self.job}
        value.update(changes)
        return value

    def test_complete_delivery_duplicate_and_consumption(self):
        self.assertEqual(self.wait().status_code, 200)
        data = self.payload()
        first = self.client.post("/api/v1/sms/otp", json=data, headers=self.phone_headers)
        self.assertTrue(first.json()["accepted"])
        self.assertTrue(self.client.post("/api/v1/sms/otp", json=data, headers=self.phone_headers).json()["duplicate"])
        got = self.client.get(f"/api/v1/bot/pull/{self.nid}", params={"timeout": 0}, headers=self.bot_headers).json()
        self.assertEqual((got["otp"], got["request_id"]), ("123456", self.job))
        self.assertEqual(self.client.post(f"/api/v1/bot/consumed/{self.nid}", params={"request_id": self.job}, headers=self.bot_headers).status_code, 200)
        self.assertEqual(self.client.get("/api/v1/sms/otp/targets", headers=self.phone_headers).json()["targets"], [])
        retried = self.client.post("/api/v1/sms/otp", json=data, headers=self.phone_headers)
        self.assertTrue(retried.json()["duplicate"])
        self.assertEqual(self.client.get(f"/api/v1/bot/pull/{self.nid}", params={"timeout": 0}, headers=self.bot_headers).status_code, 204)

    def test_authentication_on_all_sensitive_phone_routes(self):
        self.assertEqual(self.client.get("/api/v1/sms/otp/targets").status_code, 401)
        self.assertEqual(self.client.get("/api/v1/sms/otp/status", params={"national_id": self.nid}).status_code, 401)
        self.assertEqual(self.client.post("/api/v1/sms/otp", json=self.payload()).status_code, 401)

    def test_no_waiter_cannot_accept_otp(self):
        self.assertEqual(self.client.post("/api/v1/sms/otp", json=self.payload(), headers=self.phone_headers).status_code, 409)

    def test_old_request_cannot_receive_new_request_code(self):
        self.wait("fixture-request-2")
        self.assertEqual(self.client.post("/api/v1/sms/otp", json=self.payload(), headers=self.phone_headers).status_code, 409)

    def test_register_retry_does_not_supersede_received_otp(self):
        self.wait()
        self.client.post("/api/v1/sms/otp", json=self.payload(), headers=self.phone_headers)
        self.wait()
        self.assertEqual(self.client.get(f"/api/v1/bot/pull/{self.nid}", params={"timeout": 0}, headers=self.bot_headers).status_code, 200)

    def test_expired_and_future_sms_rejected(self):
        self.wait()
        for ts in (time.time() - 4000, time.time() + 4000):
            self.assertEqual(self.client.post("/api/v1/sms/otp", json=self.payload(received_at=ts), headers=self.phone_headers).status_code, 410)

    def test_raw_sms_and_phone_never_accepted(self):
        self.wait()
        self.assertEqual(self.client.post("/api/v1/sms/otp", json=self.payload(text="private"), headers=self.phone_headers).status_code, 422)

    def test_targets_only_expose_routing_data(self):
        self.wait()
        row = self.client.get("/api/v1/sms/otp/targets", headers=self.phone_headers).json()["targets"][0]
        self.assertEqual(set(row), {"national_id", "job_id", "sender_hash", "waiting_since", "expires_at", "otp_type", "delete_approved", "requires_confirmation"})
        self.assertIsInstance(row["delete_approved"],bool)

    def typed_wait(self, operation, **changes):
        data = {"national_id": self.nid, "waiting_since": time.time()-1, "job_id": self.job,
                "otp_type": operation, "sender_hash": "a"*64}
        data.update(changes)
        return self.client.post("/api/v1/bot/wait", headers=self.bot_headers, json=data)

    def send(self, **changes):
        return self.client.post("/api/v1/sms/otp", headers=self.phone_headers, json=self.payload(**changes))

    def test_recovery_delivered_only_to_recovery_request(self):
        self.typed_wait(relay.RECOVERY)
        self.assertEqual(self.send().status_code, 409)
        self.assertEqual(self.send(otp_type=relay.RECOVERY,source_type=relay.RECOVERY).status_code,200)
        self.assertEqual(self.client.get(f"/api/v1/bot/pull/{self.nid}",params={"timeout":0},headers=self.bot_headers).status_code,204)
        response=self.client.get(f"/api/v1/bot/pull/{self.nid}",params={"timeout":0,"otp_type":relay.RECOVERY,"request_id":self.job},headers=self.bot_headers)
        self.assertEqual(response.json()["otp_type"],relay.RECOVERY)

    def test_deletion_without_explicit_approval_rejected(self):
        self.assertEqual(self.typed_wait(relay.DELETION).status_code,403)
        self.assertEqual(self.typed_wait(relay.DELETION,delete_approved=True).status_code,403)
        self.assertEqual(self.typed_wait(relay.DELETION,delete_approved=True,confirmed_by="fixture-admin").status_code,403)
        expires=int(time.time())+120
        message=f"delete-v1|{self.nid}|{self.job}|fixture-admin|{expires}"
        signature=hmac.new(relay.RELEASE_ADMIN_KEY.encode(),message.encode(),"sha256").hexdigest()
        self.assertEqual(self.typed_wait(relay.DELETION,delete_approved=True,confirmed_by="fixture-admin",delete_authorization=f"{expires}:{signature}").status_code,200)
        self.assertEqual(self.send(otp_type=relay.DELETION,source_type=relay.DELETION).status_code,200)

    def test_deletion_never_satisfies_registration(self):
        self.wait()
        self.assertEqual(self.send(otp_type=relay.DELETION,source_type=relay.DELETION).status_code,409)

    def test_bare_requires_both_confirmation_and_correlated_sender(self):
        self.wait()
        self.assertEqual(self.send(source_type=relay.UNLABELED).status_code,409)
        self.assertEqual(self.send(source_type=relay.UNLABELED,match_status="USER_CONFIRMED",sender_reference="b"*64).status_code,409)
        self.assertEqual(self.send(source_type=relay.UNLABELED,match_status="USER_CONFIRMED",sender_reference="a"*64).status_code,200)

    def test_bare_from_known_service_requires_confirmation(self):
        self.wait()
        self.assertEqual(self.send(source_type=relay.UNLABELED,match_status="USER_CONFIRMED",sender_reference="service:2000411").status_code,200)

    def test_changed_event_cannot_be_reassigned(self):
        self.wait();self.send()
        self.assertEqual(self.send(request_id="fixture-other-request").status_code,409)
        self.assertEqual(self.send(otp="654321").status_code,409)

    def test_same_code_resent_as_new_sms_is_not_delivered_twice(self):
        self.wait();self.send()
        self.assertTrue(self.send(message_id="fixture-second-message").json()["duplicate"])
        with relay.db_connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM otp_messages").fetchone()[0],1)

    def test_operation_cannot_be_changed_on_same_request(self):
        self.wait()
        self.assertEqual(self.typed_wait(relay.RECOVERY).status_code,409)

    def test_cancelled_request_cannot_receive_code(self):
        self.wait();self.client.post(f"/api/v1/bot/clear/{self.nid}",params={"request_id":self.job},headers=self.bot_headers)
        self.assertEqual(self.send().status_code,409)

    def test_non_numeric_otp_is_not_silently_cleaned(self):
        self.wait()
        self.assertEqual(self.send(otp="text123456").status_code,422)

    def test_duplicate_after_consumption_does_not_retain_plaintext(self):
        self.wait();self.send()
        self.client.post(f"/api/v1/bot/consumed/{self.nid}",params={"request_id":self.job},headers=self.bot_headers)
        with relay.db_connection() as db:
            self.assertEqual(db.execute("SELECT otp FROM otp_messages").fetchone()[0],"")
        self.assertTrue(self.send().json()["duplicate"])

    def test_purge_expired_codes_preserves_receipts(self):
        self.wait();self.send()
        with relay.db_connection() as db:
            db.execute("UPDATE otp_messages SET received_at=?",(time.time()-4000,));db.commit()
        relay.purge_sensitive_codes()
        with relay.db_connection() as db:
            row=db.execute("SELECT otp,message_id FROM otp_messages").fetchone()
            self.assertEqual(row["otp"],"");self.assertEqual(row["message_id"],"fixture-message-1")

    def test_replaced_request_requires_confirmation_for_new_clients(self):
        self.wait("fixture-previous-request");self.wait()
        self.assertEqual(self.send(app_version_code=5,match_status="MATCHED").status_code,409)
        self.assertEqual(self.send(app_version_code=5,match_status="USER_CONFIRMED").status_code,200)

    def test_consumed_code_cannot_be_reused_for_next_request(self):
        self.wait();self.send()
        self.client.post(f"/api/v1/bot/consumed/{self.nid}",params={"request_id":self.job},headers=self.bot_headers)
        self.wait("fixture-next-request")
        self.assertEqual(self.send(message_id="fixture-new-message",request_id="fixture-next-request").status_code,410)

    def test_old_clear_cannot_cancel_a_new_request(self):
        self.wait("fixture-request-2")
        self.assertEqual(self.client.post(f"/api/v1/bot/clear/{self.nid}", params={"request_id": self.job}, headers=self.bot_headers).status_code, 409)

if __name__ == "__main__":
    unittest.main()
