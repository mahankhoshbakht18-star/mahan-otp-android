"""Inject the owner's connection only locally into an unsigned CI-generated APK."""
import json
from pathlib import Path
import sys
import zipfile

source, destination, connection_path = map(Path, sys.argv[1:4])
assert source.resolve() != destination.resolve()
connection = json.loads(connection_path.read_text(encoding="utf-8-sig"))
assert connection["endpoint"] == "https://otp.mahanvip.ir/api/v1/sms/otp"
assert 16 <= len(connection["device_key"]) <= 512
with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, "w") as prepared:
    assert {"AndroidManifest.xml", "classes.dex"}.issubset(original.namelist())
    for entry in original.infolist():
        assert not (entry.filename.startswith("META-INF/") and entry.filename.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))), "Input must be unsigned"
        if entry.filename != "assets/mahan-connection.json":
            prepared.writestr(entry, original.read(entry.filename))
    prepared.writestr("assets/mahan-connection.json", json.dumps(connection, ensure_ascii=False).encode("utf-8"),
                      compress_type=zipfile.ZIP_DEFLATED)
print("Private connection injected locally; no credential printed or uploaded")
