"""Real workbench trace, context/schema/timing, admin reset and viewer boundaries."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'artifacts/agent-upgrade-browser'
OUT.mkdir(parents=True,exist_ok=True)
checks,errors=[],[]
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True)
    page=browser.new_page(viewport={'width':1440,'height':1100})
    page.on('pageerror',lambda e:errors.append(str(e)))
    page.on('dialog',lambda d:d.accept())
    page.goto('http://127.0.0.1:15173')
    page.locator('[data-test=username]').fill('admin')
    page.locator('[data-test=password]').fill('admin123')
    page.locator('[data-test=submit]').click()
    page.locator('[data-test=chat-input]').fill('AUD-001 补货')
    page.locator('[data-test=chat-send]').click()
    panel=page.locator('[data-test=agent-trace-panel]')
    expect(panel.locator('[data-test=trace-state]')).to_contain_text('ok',timeout=15000)
    for value in ['system','history','reference','user','Schema passed','ms']:
        expect(panel).to_contain_text(value)
    checks.append('actual authenticated trace renders rounds, four prompt layers, schema and timing')
    panel.scroll_into_view_if_needed()
    page.screenshot(path=str(OUT/'trace-desktop.png'),full_page=True)
    page.set_viewport_size({'width':390,'height':844})
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
    page.screenshot(path=str(OUT/'trace-mobile.png'),full_page=True)
    checks.append('trace and topbar mobile layout have no page overflow')
    page.set_viewport_size({'width':1440,'height':1100})
    page.locator('[data-test=reset-demo]').click()
    expect(page.locator('[data-test=purchase-status]')).to_contain_text('运营和表单演示基线已恢复',timeout=15000)
    expect(panel.locator('[data-test=trace-state]')).to_have_count(0)
    checks.append('admin reset restores both services and clears current panel context')
    page.locator('[data-test=logout]').click()
    page.locator('[data-test=username]').fill('viewer')
    page.locator('[data-test=password]').fill('viewer123')
    page.locator('[data-test=submit]').click()
    expect(page.locator('[data-test=role-badge]')).to_contain_text('查看者')
    expect(page.locator('[data-test=reset-demo]')).to_have_count(0)
    checks.append('viewer has no reset control')
    assert not errors,errors
    checks.append('no uncaught browser errors')
    browser.close()
result={'passed':len(checks),'checks':checks,'routeMocks':False}
(OUT/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False))
