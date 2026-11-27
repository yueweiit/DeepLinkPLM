import { chromium, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'

const token = process.env.PLM_TEST_TOKEN
if (!token) throw new Error('Expiring local test session required')
const base = 'http://127.0.0.1:8111'
const output = 'test-results/listing-phone-fix-20261112'
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: 'msedge' })
try {
  for (const width of [1440, 390]) {
    const context = await browser.newContext({ viewport: { width, height: 1000 } })
    await context.addInitScript(value => localStorage.setItem('plm_token', value), token)
    const page = await context.newPage()
    await page.goto(`${base}/#/partner-selections?id=54`)
    const row = page.locator('article.selection-row').filter({ hasText: 'CP-000126' })
    await expect(row).toHaveCount(1, { timeout: 60000 })
    await row.locator('.row-check .el-checkbox').click()
    const responsePromise = page.waitForResponse(response => response.url().includes('/catalog/listing-options'))
    await page.getByRole('button', { name: '准备上架', exact: true }).click()
    const response = await responsePromise
    expect(response.status()).toBe(200)
    const body = await response.json()
    expect(body.code).toBe(0)
    expect(body.data.pairs.length).toBeGreaterThan(0)
    const dialog = page.getByRole('dialog', { name: '确认上架', exact: true })
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('.listing-pair')).toHaveCount(body.data.pairs.length)
    await expect(dialog.locator('.el-loading-mask')).toHaveCount(0)
    await page.waitForTimeout(400)
    const bounds = await dialog.locator('.listing-pair').evaluateAll(rows => rows.map(row => {
      const box = row.getBoundingClientRect()
      return { top: box.top, bottom: box.bottom, height: box.height }
    }))
    for (let i = 0; i < bounds.length; i++) {
      expect(bounds[i].height).toBeGreaterThan(18)
      if (i) expect(bounds[i].top).toBeGreaterThanOrEqual(bounds[i - 1].bottom)
    }
    await expect(dialog.getByText('本款当前没有可登记的真实手机型号')).toHaveCount(0)
    await page.screenshot({ path: `${output}/dialog-${width}.png`, fullPage: true })
    console.log(JSON.stringify({ width, pairs: body.data.pairs.map(p => ({ phoneModelId: p.phoneModelId, colorId: p.colorId, eligible: p.eligible, reason: p.reason })) }))
    await context.close()
  }
} finally { await browser.close() }
