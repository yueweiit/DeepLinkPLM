import { chromium, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

// Read-only checks against the running local service, without mocked API responses.
const base = process.env.PLM_TEST_URL || 'http://127.0.0.1:8111'
const token = process.env.PLM_TEST_TOKEN
if (!token) throw new Error('PLM_TEST_TOKEN is required; use an expiring local test session')
const output = path.resolve('test-results/crm-listing-runtime-20261112')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: 'msedge' })
try {
  for (const width of [1440, 390]) {
    const context = await browser.newContext({ viewport: { width, height: 1000 } })
    await context.addInitScript(value => localStorage.setItem('plm_token', value), token)
    const page = await context.newPage()
    const errors = [], failures = []
    page.on('pageerror', error => errors.push(error.message))
    page.on('response', response => {
      if (response.url().includes('/catalog/listings') && response.status() >= 400)
        failures.push(`${response.status()} ${new URL(response.url()).pathname}`)
    })
    for (const status of ['listed', 'pending']) {
      const capabilityResponse = page.waitForResponse(response => response.url().includes('/crm-capabilities'))
      const catalogResponse = page.waitForResponse(response => {
        const url = new URL(response.url())
        return url.pathname.endsWith('/catalog/listings') && url.searchParams.get('status') === status
      })
      await page.goto(`${base}/#/partner-listings?status=${status}`)
      const [capability, catalog] = await Promise.all([capabilityResponse, catalogResponse])
      expect(capability.status()).toBe(200)
      const capabilityBody = await capability.json()
      expect(capabilityBody.code).toBe(0)
      expect(capabilityBody.data.canSubmit).toBe(false)
      expect(catalog.status()).toBe(200)
      const catalogBody = await catalog.json()
      expect(catalogBody.code).toBe(0)
      const rows = catalogBody.data.content
      await expect(page.locator('.listed-catalog')).toBeVisible()
      await expect(page.locator('.listed-scroll .el-loading-mask')).toHaveCount(0)
      await page.waitForTimeout(800)
      await expect(page.getByText(/CRM 同步状态加载失败/)).toHaveCount(0)
      await expect(page.getByText('资源不存在', { exact: true })).toHaveCount(0)
      await page.screenshot({ path: path.join(output, `${status}-${width}.png`), fullPage: true })
      if (status === 'pending') {
        const selectable = page.locator('.listed-row .el-checkbox:not(.is-disabled)').first()
        if (await selectable.count()) {
          await selectable.click()
          const optionsResponse = page.waitForResponse(response => response.url().includes('/crm-push-options'))
          await page.getByRole('button', { name: '推送 CRM', exact: true }).click()
          const response = await optionsResponse
          expect(response.status()).toBe(200)
          expect((await response.json()).code).toBe(0)
          const dialog = page.getByRole('dialog')
          await expect(dialog).toBeVisible()
          await expect(dialog.getByRole('button', { name: '确认推送', exact: true })).toBeDisabled()
          await page.screenshot({ path: path.join(output, `options-${width}.png`), fullPage: true })
          await dialog.getByRole('button', { name: '关闭', exact: true }).click()
        }
      }
      console.log(`PASS ${width} ${status}: catalog + capabilities; ${rows.length} rows${rows.length ? '' : '; per-listing checks skipped (empty catalog)'}`)
    }
    if (width === 1440) {
      await page.goto(`${base}/#/mold-share-mappings`)
      await page.getByRole('button', { name: '新增共模映射' }).click()
      await expect(page.getByText('已开模机型', { exact: true })).toBeVisible()
      console.log('PASS existing mold sharing UI preserved')
    }
    expect(failures).toEqual([])
    expect(errors).toEqual([])
    await context.close()
  }
} finally {
  await browser.close()
}
