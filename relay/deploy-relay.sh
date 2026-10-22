#!/usr/bin/env bash
set -euo pipefail
backup=/root/mahan-otp-relay-backup-20261017
mkdir -p "$backup"
cp -n /etc/systemd/system/mahan-otp-relay.service "$backup/mahan-otp-relay.service"
cp -n /etc/mahan-otp-relay.env "$backup/relay.env"
if [ -f /var/lib/mahan-otp-relay/relay.db ] && [ ! -f "$backup/relay.db" ]; then
    /opt/mahan-otp/venv/bin/python -c "import sqlite3; s=sqlite3.connect('/var/lib/mahan-otp-relay/relay.db'); d=sqlite3.connect('/root/mahan-otp-relay-backup-20261017/relay.db'); s.backup(d); d.close(); s.close()"
fi
if [ -f /etc/systemd/system/mahan-otp-relay.service.d/override.conf ] && [ ! -f "$backup/override.conf" ]; then
    cp /etc/systemd/system/mahan-otp-relay.service.d/override.conf "$backup/override.conf"
fi
install -d -o mahanotp -g mahanotp -m 750 /opt/mahan-otp-relay /opt/mahan-otp-relay/app
install -o mahanotp -g mahanotp -m 640 /root/mahan-otp-relay-update/main.py /opt/mahan-otp-relay/app/main.py
install -d -m 755 /etc/systemd/system/mahan-otp-relay.service.d
install -m 644 /root/mahan-otp-relay-update/relay-override.conf /etc/systemd/system/mahan-otp-relay.service.d/override.conf
/opt/mahan-otp/venv/bin/python -m py_compile /opt/mahan-otp-relay/app/main.py
systemctl daemon-reload
systemctl restart mahan-otp-relay
for attempt in 1 2 3 4 5; do
    if curl --fail --silent http://127.0.0.1:18019/status; then
        printf '\nRelay restored; legacy port 8010 service left running. Backup: %s\n' "$backup"
        exit 0
    fi
    sleep 1
done
systemctl status mahan-otp-relay --no-pager
exit 1
