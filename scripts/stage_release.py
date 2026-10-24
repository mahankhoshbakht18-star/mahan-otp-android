"""Local-only release rehearsal: real signed APK, separate DB, no public approval."""
import json
import os
from pathlib import Path
import secrets
import sys
import tempfile
from fastapi.testclient import TestClient

root=Path(__file__).resolve().parent.parent
apk=Path(sys.argv[1]).resolve()
stage_root=root/".private"/"staging"
stage_root.mkdir(parents=True,exist_ok=True)
stage=Path(tempfile.mkdtemp(prefix="release-2.2.0-",dir=stage_root))
sdk=Path(os.environ["ANDROID_HOME"])
java=Path(os.environ["JAVA_HOME"])/"bin"/"java.exe"
os.environ.update(RELAY_DB_PATH=str(stage/"relay.db"),RELAY_BOT_KEY=secrets.token_urlsafe(32),
    RELAY_PHONE_KEY=secrets.token_urlsafe(32),RELAY_RELEASE_ADMIN_KEY=secrets.token_urlsafe(32),
    RELAY_JAVA=str(java),RELAY_AAPT=str(sdk/"build-tools"/"35.0.0"/"aapt.exe"),
    RELAY_APKSIGNER_JAR=str(sdk/"build-tools"/"35.0.0"/"lib"/"apksigner.jar"))
sys.path.insert(0,str(root))
from relay.app.main import app
headers={"X-RELEASE-ADMIN-KEY":os.environ["RELAY_RELEASE_ADMIN_KEY"]}
with TestClient(app) as client:
    original=apk.read_bytes()
    corrupted=bytearray(original)
    corrupted[len(corrupted)//2] ^= 1
    rejected=client.post("/api/v1/bot/app/upload",headers=headers,content=bytes(corrupted))
    assert rejected.status_code==422,rejected.text
    upload=client.post("/api/v1/bot/app/upload",headers=headers,content=original)
    assert upload.status_code==200,upload.text
    verified=upload.json()
    assert verified["package_name"]=="com.example.codresan" and verified["version_code"]==5 and verified["version_name"]=="2.2.0"
    data={"sha256":verified["sha256"],"version_code":5,"version_name":"2.2.0","min_supported_version_code":1,
          "release_notes":"تشخیص تفکیک‌شده کدهای ازدواج؛ تأیید کد بدون متن؛ دانلود آپدیت با بررسی هش و امضا"}
    draft=client.post("/api/v1/bot/app/releases",headers=headers,json=data)
    assert draft.status_code==200,draft.text
    assert client.get("/api/app/version").status_code==404
    assert client.get(f"/downloads/mahanbot-{verified['sha256']}.apk").status_code==404
    overview=client.get("/api/v1/bot/app/overview",headers=headers).json()
    assert overview["registered_devices"]==0
report={"apk":verified,"staging_database":str(stage/"relay.db"),"channel":"draft", "publicly_downloadable":False,
    "registered_devices":0,"real_signature_verification":True,"corrupted_apk_rejected":True,"production_unchanged":True}
output=root/"delivery"/"2.2.0"
output.mkdir(parents=True,exist_ok=True)
(output/"backend-staging-report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps({key:value for key,value in report.items() if key!="staging_database"},ensure_ascii=False,indent=2))
