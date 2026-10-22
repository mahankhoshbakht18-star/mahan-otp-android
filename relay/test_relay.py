import importlib.util
import os
import tempfile
import time
import unittest
import sys
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
        self.assertEqual(set(row), {"national_id", "job_id", "sender_hash", "waiting_since", "expires_at"})

    def test_old_clear_cannot_cancel_a_new_request(self):
        self.wait("fixture-request-2")
        self.assertEqual(self.client.post(f"/api/v1/bot/clear/{self.nid}", params={"request_id": self.job}, headers=self.bot_headers).status_code, 409)

if __name__ == "__main__":
    unittest.main()
