"""No live DB, browser, SMS gateway or production network is used by these tests."""
import os
import sys
import tempfile
import unittest
import time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch
from fastapi import FastAPI
from fastapi.testclient import TestClient

SOURCE = Path(os.environ.get("MAHANBOT_SOURCE_DIR", ""))


@unittest.skipUnless((SOURCE / "relay_transport.py").exists(), "Set MAHANBOT_SOURCE_DIR to the bot source")
class BotIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sys.path.insert(0, str(SOURCE))
        cls.temp=tempfile.TemporaryDirectory()
        import database
        database.DB_PATH=str(Path(cls.temp.name)/"fixture-bot.db")
        database.DBHandler.init_db()
        import relay_transport,otp_transport
        from routes import jobs,app_releases
        cls.transport=relay_transport;cls.otp=otp_transport;cls.jobs=jobs;cls.releases=app_releases
        cls.app=FastAPI();cls.app.include_router(jobs.router);cls.app.include_router(app_releases.router)
        cls.client=TestClient(cls.app)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def setUp(self):
        self.env=patch.dict(os.environ,{"RELAY_BOT_KEY":"fixture-only-key","MAHAN_OTP_RELAY_URL":"https://relay.invalid",
            "RELAY_RELEASE_ADMIN_KEY":"fixture-delete-authority",
            "MAHAN_OTP_RELAY_DISABLED":"0","MAHANBOT_DISABLE_AUTH":"false","MAHANBOT_EDITION":"ADMIN"})
        self.env.start();self.app.dependency_overrides.clear()
        self.bot=SimpleNamespace(nid="0012345679",settings={"otp_validity_seconds":120},user_data={"mobile":"09123456789"},
            _otp_entry_time=time.time()-1,_otp_request_id="fixture-request")

    def tearDown(self): self.env.stop();self.app.dependency_overrides.clear()

    def test_registration_wire_remains_legacy_compatible(self):
        with patch.object(self.transport.requests,"post",return_value=Mock(status_code=200,**{"json.return_value":{"accepted":True}})) as post:
            self.assertTrue(self.transport.publish_wait(self.bot))
            self.assertNotIn("otp_type",post.call_args.kwargs["json"])

    def test_recovery_wait_is_explicitly_typed(self):
        self.bot.OTP_TYPE="TRACKING_RECOVERY_OTP"
        with patch.object(self.transport.requests,"post",return_value=Mock(status_code=200,**{"json.return_value":{"accepted":True}})) as post:
            self.assertTrue(self.transport.publish_wait(self.bot));self.assertEqual(post.call_args.kwargs["json"]["otp_type"],self.bot.OTP_TYPE)

    def test_unauthorized_delete_never_registers_wait(self):
        self.bot.OTP_TYPE="REGISTRATION_DELETE_OTP"
        with patch.object(self.transport.requests,"post") as post,patch("database.DBHandler.update_runtime"):
            self.assertFalse(self.transport.publish_wait(self.bot));post.assert_not_called()

    def test_authorized_delete_carries_operator_confirmation(self):
        self.bot.OTP_TYPE="REGISTRATION_DELETE_OTP";self.bot.settings.update(delete_approved=True,delete_confirmed_by="fixture-admin")
        with patch.object(self.transport.requests,"post",return_value=Mock(status_code=200,**{"json.return_value":{"accepted":True}})) as post:
            self.assertTrue(self.transport.publish_wait(self.bot));data=post.call_args.kwargs["json"]
            self.assertTrue(data["delete_approved"]);self.assertEqual(data["confirmed_by"],"fixture-admin")

    def test_other_operation_cached_code_cannot_reach_recovery(self):
        self.bot.OTP_TYPE="TRACKING_RECOVERY_OTP"
        record={"otp":"123456","ts":time.time(),"request_id":"fixture-request","otp_type":"REGISTRATION_OTP"}
        self.assertIsNone(self.otp.fresh_record(self.bot,record))
        record["otp_type"]="TRACKING_RECOVERY_OTP";self.assertEqual(self.otp.fresh_record(self.bot,record),"123456")

    def test_delete_api_requires_admin_and_explicit_confirmation(self):
        path="/bot/action/delete-request/0012345679"
        self.assertEqual(self.client.post(path,json={"confirmed":True}).status_code,401)
        self.app.dependency_overrides[self.jobs._admin_user]=lambda:{"username":"fixture-admin","is_admin":True}
        self.assertEqual(self.client.post(path,json={}).status_code,422)
        self.assertEqual(self.client.post(path,json={"confirmed":False}).status_code,422)
        fake_queue=Mock();fake_queue.enqueue.return_value=SimpleNamespace(to_dict=lambda:{"fixture":True})
        with patch.object(self.jobs._state,"JOB_QUEUE",fake_queue),patch("database.DBHandler.get_applicant",return_value={"national_id":"0012345679"}),patch.object(self.jobs,"_emit_applicants_snapshot"),patch("event_logger.log_event"):
            self.assertEqual(self.client.post(path,json={"confirmed":True}).status_code,200)
            self.assertEqual(fake_queue.enqueue.call_args.args[2]["delete_confirmed_by"],"fixture-admin")

    def test_generic_job_endpoint_cannot_bypass_delete_approval(self):
        self.app.dependency_overrides[self.jobs.require_api_key]=lambda:True
        with patch.object(self.jobs._state,"JOB_QUEUE",Mock()):
            data={"bot_name":"delete_req","nid":"0012345679"}
            self.assertEqual(self.client.post("/jobs/start",json=data).status_code,403)
            self.assertEqual(self.client.post("/jobs/restart",json=data).status_code,403)

    def test_release_dashboard_requires_admin(self):
        self.assertEqual(self.client.get("/admin/app-releases").status_code,401)
        self.app.dependency_overrides[self.releases._admin_user]=lambda:{"username":"fixture-admin","is_admin":True}
        self.assertEqual(self.client.get("/admin/app-releases").status_code,200)
        self.assertEqual(self.client.post("/api/app-admin/releases",json={}).status_code,403)
        self.assertEqual(self.client.post("/api/app-admin/releases",json={},headers={"X-MAHAN-ADMIN":"1","Origin":"https://evil.invalid"}).status_code,403)

    def test_release_proxy_rejects_unlisted_paths(self):
        self.app.dependency_overrides[self.releases._admin_user]=lambda:{"username":"fixture-admin","is_admin":True}
        self.assertEqual(self.client.get("/api/app-admin/other-secret").status_code,404)

    def test_dashboard_does_not_expose_backend_credentials(self):
        self.app.dependency_overrides[self.releases._admin_user]=lambda:{"username":"fixture-admin","is_admin":True}
        html=self.client.get("/admin/app-releases").text
        self.assertNotIn("fixture-only-key",html)
        self.assertNotIn("X-BOT-KEY",html)

if __name__ == "__main__": unittest.main()
