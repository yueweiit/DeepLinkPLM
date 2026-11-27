import { chromium, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
const token = process.env.PLM_TEST_TOKEN
if (!token) throw new Error('Expiring test session required')
await mkdir('test-results/batch-listing-20261112', { recursive: true })
const browser = await chromium.launch({ headless: true, channel: 'msedge' })
try {
  for (const width of [1440, 390]) {
    const context = await browser.newContext({ viewport: { width, height: 1000 } })
    await context.addInitScript(value => localStorage.setItem('plm_token', value), token)
    const page = await context.newPage()
    let writes = 0
    await page.route('**/catalog/listing-confirmations', route => { writes++; return route.abort() })
    await page.goto('http://127.0.0.1:8111/#/partner-selections?id=54')
    const rows = page.locator('article.selection-row')
    await expect(rows.first()).toBeVisible({ timeout: 60000 })
    expect(await rows.count()).toBeGreaterThan(1)
    const entry = page.getByRole('button', { name: /批量准备上架/ })
    await rows.nth(0).locator('.row-check .el-checkbox').click()
    await rows.nth(1).locator('.row-check .el-checkbox').click()
    await expect(entry).toBeEnabled()
    await entry.click()
    const dialog = page.getByRole('dialog', { name: '批量准备上架', exact: true })
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('.batch-listing-item')).toHaveCount(2)
    await expect(dialog.locator('.el-loading-mask')).toHaveCount(0, { timeout: 30000 })
    await expect(dialog.locator('.batch-pairs input:checked')).toHaveCount(0)
    await expect(dialog.getByRole('button', { name: /批量保存待上架/ })).toBeDisabled()
    const box = await dialog.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(width + 1)
    await page.screenshot({ path: `test-results/batch-listing-20261112/dialog-${width}.png`, fullPage: true })
    expect(writes).toBe(0)
    console.log(JSON.stringify({ width, selectedStyles: 2, writes }))
    await context.close()
  }
} finally { await browser.close() }
