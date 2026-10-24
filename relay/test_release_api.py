import importlib.util
import os
import sys
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
from relay.app import release_api

spec = importlib.util.spec_from_file_location("release_test_relay", Path(__file__).parent / "app" / "main.py")
relay = importlib.util.module_from_spec(spec); sys.modules[spec.name] = relay; spec.loader.exec_module(relay)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        relay.DB_PATH = Path(self.temp.name) / "relay.db"
        relay.BOT_KEY, relay.PHONE_KEY = "fixture-admin", "fixture-device-key"
        relay.RELEASE_ADMIN_KEY="fixture-release-control-only"
        self.bot = {"X-RELEASE-ADMIN-KEY": relay.RELEASE_ADMIN_KEY}
        self.id = str(uuid.uuid4())
        self.phone = {"X-DEVICE-KEY": relay.PHONE_KEY, "X-DEVICE-ID": self.id}
        self.env = patch.dict(os.environ, {"RELAY_PHONE_KEY": relay.PHONE_KEY}); self.env.start()
        self.client = TestClient(relay.app).__enter__()
        self.sha = ""

    def tearDown(self):
        self.client.__exit__(None,None,None); self.env.stop(); self.temp.cleanup()

    def draft(self, code=5):
        fixture = b"isolated-fake-apk-for-unit-test-only" * 100
        verification = {"package_name":release_api.PACKAGE,"version_code":code,"version_name":f"2.{code}.0","certificate_sha256":release_api.CERTIFICATE}
        with patch.object(release_api, "verify_apk", return_value=verification):
            uploaded = self.client.post("/api/v1/bot/app/upload", content=fixture, headers=self.bot)
        self.assertEqual(uploaded.status_code,200)
        self.sha=uploaded.json()["sha256"]
        response=self.client.post("/api/v1/bot/app/releases",headers=self.bot,json={"sha256":self.sha,
            "version_code":code,"version_name":f"2.{code}.0","release_notes":"fixture release notes","min_supported_version_code":1})
        self.assertEqual(response.status_code,200)

    def approve(self, channel="pilot", code=5, approval=None):
        return self.client.post(f"/api/v1/bot/app/releases/{code}/approve",headers=self.bot,
            json={"channel":channel,"approval":approval or ("APPROVE_PILOT" if channel=="pilot" else "APPROVE_PRODUCTION"),
                  "test_report":"Synthetic test fixture; no production approval", "pilot_device_ids":[self.id]})

    def report(self, code=4, **fields):
        body={"device_id":self.id,"version_code":code,"version_name":"fixture","reporting_consent":True,"update_notifications":True}
        body.update(fields)
        return self.client.post("/api/v1/sms/app/report",headers=self.phone,json=body)

    def test_all_management_routes_require_admin_secret(self):
        self.assertEqual(self.client.get("/api/v1/bot/app/overview").status_code,401)
        self.assertEqual(self.client.post("/api/v1/bot/app/upload",content=b"private").status_code,401)
        self.assertEqual(self.client.get("/api/v1/bot/app/overview",headers=self.phone).status_code,401)
        self.assertEqual(self.client.get("/api/v1/bot/app/overview",headers={"X-BOT-KEY":relay.BOT_KEY}).status_code,401)

    def test_draft_is_not_downloadable_or_announced(self):
        self.draft()
        self.assertEqual(self.client.get("/api/app/version").status_code,404)
        self.assertEqual(self.client.get(f"/downloads/mahanbot-{self.sha}.apk").status_code,404)
        self.assertEqual(self.client.post("/api/v1/bot/app/releases/5/notify",headers=self.bot).status_code,409)

    def test_pilot_is_restricted_to_authenticated_selected_devices(self):
        self.draft();self.assertEqual(self.approve().status_code,200)
        self.assertEqual(self.client.get("/api/app/version").status_code,404)
        self.assertEqual(self.client.get("/api/v1/sms/app/version",headers=self.phone).json()["version_code"],5)
        self.assertEqual(self.client.get(f"/downloads/mahanbot-{self.sha}.apk").status_code,401)
        self.assertEqual(self.client.get(f"/downloads/mahanbot-{self.sha}.apk",headers={**self.phone,"X-DEVICE-ID":str(uuid.uuid4())}).status_code,403)
        self.assertEqual(self.client.get(f"/downloads/mahanbot-{self.sha}.apk",headers=self.phone).status_code,200)

    def test_explicit_production_approval_and_pilot_are_required(self):
        self.draft()
        self.assertEqual(self.approve("production").status_code,409)
        self.approve()
        self.assertEqual(self.approve("production",approval="yes").status_code,403)
        self.assertEqual(self.approve("production").status_code,200)
        self.assertEqual(self.client.get("/app/version.json").json()["latest_version_code"],5)
        self.assertEqual(self.client.get(f"/downloads/mahanbot-{self.sha}.apk").status_code,200)

    def test_stop_blocks_download_and_version(self):
        self.draft();self.approve();self.approve("production")
        self.client.post("/api/v1/bot/app/releases/5/stop",headers=self.bot)
        self.assertEqual(self.client.get("/api/app/version").status_code,404)
        self.assertEqual(self.client.get(f"/downloads/mahanbot-{self.sha}.apk").status_code,404)

    def test_modified_uploaded_file_cannot_be_approved(self):
        self.draft()
        store=release_api.ReleaseStore(relay.DB_PATH.with_name("app-releases.db"))
        with store.connection() as db:
            path=db.execute("SELECT path FROM app_uploads").fetchone()[0]
        Path(path).write_bytes(b"tampered fixture")
        self.assertEqual(self.approve().status_code,409)

    def test_arbitrary_metadata_cannot_replace_verified_file(self):
        response=self.client.post("/api/v1/bot/app/releases",headers=self.bot,json={"sha256":"a"*64,"version_code":5,"version_name":"2.2.0","release_notes":"fixture"})
        self.assertEqual(response.status_code,409)

    def test_device_counts_are_based_only_on_real_reports(self):
        data=self.client.get("/api/v1/bot/app/overview",headers=self.bot).json()
        self.assertEqual(data["registered_devices"],0); self.assertEqual(data["unregistered_legacy_devices"],"unknown")
        self.assertEqual(self.report().status_code,200)
        data=self.client.get("/api/v1/bot/app/overview",headers=self.bot).json()
        self.assertEqual(data["registered_devices"],1)
        self.assertGreater(data["devices"][0]["last_seen"],time.time()-30)

    def test_reporting_requires_consent_and_matching_install_id(self):
        self.assertEqual(self.report(reporting_consent=False).status_code,422)
        self.assertEqual(self.report(device_id=str(uuid.uuid4())).status_code,400)

    def test_notification_deduplicates_and_does_not_claim_push_or_installation(self):
        self.draft();self.approve();self.approve("production");self.report()
        path="/api/v1/bot/app/releases/5/notify"
        first=self.client.post(path,headers=self.bot).json();second=self.client.post(path,headers=self.bot).json()
        self.assertEqual(first["queued_for_poll"],1);self.assertEqual(first["push_sent"],0)
        self.assertEqual(second["queued_for_poll"],0)
        overview=self.client.get("/api/v1/bot/app/overview",headers=self.bot).json()
        self.assertEqual(overview["notices"][0]["state"],"queued_for_poll")

    def test_notification_opt_out_respected(self):
        self.draft();self.approve();self.approve("production");self.report(update_notifications=False)
        self.assertEqual(self.client.post("/api/v1/bot/app/releases/5/notify",headers=self.bot).json()["queued_for_poll"],0)

    def test_installation_is_not_assumed_on_first_report(self):
        self.assertEqual(self.report(5,update_state="install_verified",previous_version=4).json()["recorded_state"],"first_launch")

    def test_upgrade_report_needs_observed_previous_version(self):
        self.report(4)
        self.assertEqual(self.report(5,update_state="install_verified",previous_version=4).json()["recorded_state"],"install_verified")

    def test_invalid_apk_is_not_accepted_without_signature_verifier(self):
        with patch.dict(os.environ,{"RELAY_APKSIGNER_JAR":"","RELAY_AAPT":""}):
            self.assertEqual(self.client.post("/api/v1/bot/app/upload",headers=self.bot,content=b"invalid fixture"*100).status_code,503)

    def test_minimum_supported_version_is_enforced_by_backend(self):
        self.draft(code=6);self.approve(code=6);self.approve("production",code=6)
        release_store=release_api.ReleaseStore(relay.DB_PATH.with_name("app-releases.db"))
        import json
        with release_store.connection() as db:
            row=db.execute("SELECT metadata FROM app_releases WHERE version_code=6").fetchone()
            metadata=json.loads(row[0]);metadata["min_supported_version_code"]=6
            db.execute("UPDATE app_releases SET metadata=? WHERE version_code=6",(json.dumps(metadata),))
        data={"device_id":self.id,"message_id":"fixture-version-policy","national_id":"0012345679","otp":"123456",
              "received_at":time.time(),"request_id":"fixture-version-request","app_version_code":5}
        self.assertEqual(self.client.post("/api/v1/sms/otp",headers=self.phone,json=data).status_code,426)
        data["app_version_code"]=6
        self.assertEqual(self.client.post("/api/v1/sms/otp",headers=self.phone,json=data).status_code,409) # current version passes version gate, but still needs a live request

if __name__ == "__main__": unittest.main()
