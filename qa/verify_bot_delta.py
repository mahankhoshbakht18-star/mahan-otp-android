"""Read-only bot/captcha checks plus patch application check on a temporary snapshot."""
import ast
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

source, baseline = map(Path, sys.argv[1:3])
patch = Path(__file__).resolve().parent.parent / "integration" / "mahanbot-typed-otp.patch"
count = 0
for name in ("bot_core.py", "bot_recovery.py", "bot_delete.py"):
    def protected(path):
        tree = ast.parse(path.read_text(encoding="utf-8-sig"))
        return {node.name: ast.dump(node, include_attributes=False)
                for node in ast.walk(tree)
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                and any(word in node.name.lower() for word in ("captcha", "firewall"))}
    before, after = protected(baseline / name), protected(source / name)
    assert before == after, f"Protected functions changed: {name}"
    count += len(before)

with tempfile.TemporaryDirectory(prefix="mahan-patch-check-") as folder:
    target = Path(folder)
    for file in baseline.rglob("*"):
        if file.is_file():
            destination = target / file.relative_to(baseline)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(file, destination)
    subprocess.run(["git", "apply", "--check", "--whitespace=nowarn", str(patch)], cwd=target, check=True)
print(f"PASS: {count} captcha/firewall functions unchanged; integration patch applies to the pre-task snapshot")
