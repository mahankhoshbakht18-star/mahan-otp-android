"""Export only this task's delta against its pre-upgrade snapshot, not unrelated user edits."""
import difflib
from pathlib import Path
import sys

source=Path(sys.argv[1]); baseline=Path(sys.argv[2])
changed=["bot_core.py","bot_recovery.py","bot_delete.py","relay_transport.py","otp_transport.py",
         "core/job_queue.py","routes/jobs.py","server.py","static/index.html","static/script.js"]
added=["routes/app_releases.py","static/app-releases.html","static/app-releases.js"]
for relative in changed+added:
    before=(baseline/relative).read_text(encoding="utf-8-sig").splitlines(keepends=True) if relative in changed else []
    after=(source/relative).read_text(encoding="utf-8-sig").splitlines(keepends=True)
    sys.stdout.write("".join(difflib.unified_diff(before,after,fromfile="a/"+relative if before else "/dev/null",tofile="b/"+relative)))
