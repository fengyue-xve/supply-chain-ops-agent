import { test, expect } from '@playwright/test'

// 浏览器级 E2E(最小):真实 Chromium + 真实 Java/FastAPI 之外的最小链路(Vue→Java→MySQL)。
// 账号为 db/seed.sql 演示账号;断言基于种子数据(PO20260903001 等,见 db/seed.sql)。

async function login(page, username, password) {
  await page.goto('/login')
  await page.fill('[data-test="username"]', username)
  await page.fill('[data-test="password"]', password)
  await page.click('[data-test="submit"]')
  await page.waitForURL('http://localhost:5173/')
}

test('admin 登录后可见待确认采购单与独立人工确认入口(不执行写入)', async ({ page }) => {
  await login(page, 'admin', 'admin123')

  const poCard = page.locator('section.card').filter({ hasText: '待确认采购单' })
  await expect(poCard).toBeVisible()
  await expect(poCard.locator('[data-test="po-notice"]')).toHaveText(
    '该列表只展示待人工确认（status=pending）的采购草稿；先核对单号、SKU 和数量，再独立确认。确认不增加库存，也不生成发货表单。',
  )

  const table = poCard.locator('table')
  await expect(table).toBeVisible()
  await expect(poCard.locator('[data-test="po-page-info"]')).toHaveText(/第 1 页 · 共 \d+ 条/)

  const firstRow = table.locator('tbody tr').first()
  await expect(firstRow).toContainText('PO20260903001')
  await expect(firstRow).toContainText('CHG-001')
  await expect(firstRow).toContainText('120')
  await expect(firstRow).toContainText('pending')
  await expect(firstRow).toContainText('是')
  await expect(firstRow).toContainText('2026-09-03 09:40:00')

  await expect(firstRow.getByRole('button', { name: '核对并确认' })).toBeVisible()
  await expect(page.locator('[data-test="form-workflow"]')).toContainText('采购确认不会自动创建该表单')

  await expect(table.getByText('PO20260812001')).toHaveCount(0)
  await expect(table.getByText('PO20260715001')).toHaveCount(0)
})

test('viewer 登录后不渲染待确认采购单与审计时间线区块', async ({ page }) => {
  await login(page, 'viewer', 'viewer123')

  await expect(page.getByText('库存查询')).toBeVisible()
  await expect(page.getByText('Agent 对话')).toBeVisible()
  await expect(page.locator('section.card').filter({ hasText: '待确认采购单' })).toHaveCount(0)
  await expect(page.locator('section.card').filter({ hasText: '审计时间线' })).toHaveCount(0)
  await expect(page.locator('[data-test="purchase-draft"]')).toHaveCount(0)
  await expect(page.locator('[data-test="form-workflow"]')).toBeVisible()
})
