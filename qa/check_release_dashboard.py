"""Isolated visual/UI fixtures only; every browser request is intercepted, no server contacted."""
import json
from pathlib import Path
import sys
from playwright.sync_api import sync_playwright

source, output = map(Path, sys.argv[1:3])
output.mkdir(parents=True, exist_ok=True)
overview = {"latest_version_code": 0, "registered_devices": 0, "outdated_devices": 0,
            "devices": [], "releases": [], "notices": [], "history": []}
with sync_playwright() as playwright:
    browser = playwright.chromium.launch(channel="msedge", headless=True)
    page = browser.new_page(viewport={"width": 1366, "height": 1000}, device_scale_factor=1)
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    def intercept(route):
        path = route.request.url.split("mahan-ui.test", 1)[-1]
        if path == "/admin/app-releases":
            route.fulfill(path=str(source / "static" / "app-releases.html"), content_type="text/html; charset=utf-8")
        elif path == "/static/app-releases.js":
            route.fulfill(path=str(source / "static" / "app-releases.js"), content_type="text/javascript; charset=utf-8")
        elif path == "/api/app-admin/overview":
            route.fulfill(json=overview)
        else:
            route.abort()
    page.route("**/*", intercept)
    page.goto("https://mahan-ui.test/admin/app-releases")
    page.wait_for_function("document.getElementById('devices').textContent === '0'")
    assert page.locator("html").get_attribute("dir") == "rtl"
    assert page.locator("#latest").inner_text() == "هنوز منتشر نشده"
    assert page.locator("#deviceRows tr").count() == 0
    page.screenshot(path=str(output / "release-dashboard-desktop.png"), full_page=True)
    page.set_viewport_size({"width": 390, "height": 844})
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
    page.screenshot(path=str(output / "release-dashboard-mobile.png"), full_page=True)
    page.evaluate("approve({channel:'draft',version_code:5})")
    assert page.locator("#approval").is_visible()
    page.locator("#approveButton").click()
    assert "گزارش واقعی" in page.locator("#status").inner_text()
    assert not errors, errors
    browser.close()
report = {"passed": True, "isolated_fixtures_only": True,
          "checks": ["RTL", "empty-real-state-rendering", "no-fabricated-installations",
                     "mobile-overflow", "pilot-dialog", "test-report-required", "no-JavaScript-errors"]}
(output / "dashboard-ui-results.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report))
