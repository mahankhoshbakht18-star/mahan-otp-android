#!/usr/bin/env bash
set -euo pipefail
[ "${MAHAN_PRODUCTION_APPROVED:-}" = "YES_AFTER_PILOT" ] || { echo 'Production deployment requires explicit owner approval after pilot tests.'; exit 1; }
stage="$(cd "${1:?Provide a staging source directory}" && pwd)"
test -f "$stage/main.py" && test -f "$stage/release_api.py"
grep -q '^RELAY_RELEASE_ADMIN_KEY=.' /etc/mahan-otp-relay.env || { echo 'Configure a separate release administration key first.'; exit 1; }
command -v java >/dev/null && command -v aapt >/dev/null || { echo 'Install the approved APK verification tools first.'; exit 1; }
umask 077
backup=$(mktemp -d /var/backups/mahan-relay-2.2.0.XXXXXXXX)
cp -p /etc/mahan-otp-relay.env "$backup/relay.env"
cp -p /opt/mahan-otp-relay/app/main.py "$backup/main.py"
if [ -f /opt/mahan-otp-relay/app/release_api.py ]; then cp -p /opt/mahan-otp-relay/app/release_api.py "$backup/release_api.py"; fi
/opt/mahan-otp/venv/bin/python - "$backup" <<'PY'
import sqlite3, sys
source=sqlite3.connect('file:/var/lib/mahan-otp-relay/relay.db?mode=ro',uri=True)
target=sqlite3.connect(sys.argv[1]+'/relay.db')
source.backup(target); target.close(); source.close()
PY
install -d -o mahanotp -g mahanotp -m 750 /opt/mahan-otp-relay /opt/mahan-otp-relay/app
install -o mahanotp -g mahanotp -m 640 "$stage/release_api.py" /opt/mahan-otp-relay/app/release_api.py
install -o mahanotp -g mahanotp -m 640 "$stage/main.py" /opt/mahan-otp-relay/app/main.py
/opt/mahan-otp/venv/bin/python -m py_compile /opt/mahan-otp-relay/app/main.py /opt/mahan-otp-relay/app/release_api.py
systemctl restart mahan-otp-relay
for attempt in 1 2 3 4 5; do
    if curl --fail --silent http://127.0.0.1:18019/status; then
        printf '\nRelay updated; APK remains draft until separately approved. Backup: %s\n' "$backup"
        exit 0
    fi
    sleep 1
done
systemctl status mahan-otp-relay --no-pager
exit 1
