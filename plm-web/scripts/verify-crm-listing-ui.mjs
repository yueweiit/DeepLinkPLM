import { chromium, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

// All API requests are intercepted. This check never writes to a PLM or CRM service.
const output = path.resolve('test-results/crm-listing-20261112')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: process.env.PLM_UI_BROWSER_CHANNEL || 'msedge' })
try {
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
    const context = await browser.newContext({ viewport })
    await context.addInitScript(() => localStorage.setItem('plm_token', 'isolated-ui-fixture'))
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', e => errors.push(e.message))
    let ready = false, submitted
    const product = { productId: 71, productCode: 'PP-000071', name: '\u7ecf\u8425\u6b3e', catalogStyleCode: 'CP-000001', photos: [{ id: 81, version: 'V1' }] }
    const skus = [
      { specId: 1, internalSkuId: 101, skuCode: 'NBA4030018501', skuName: '\u6b63\u5f0f SKU', phoneModelName: 'Redmi A7 Pro', phoneModelCode: '0185', color: '\u9ed1\u8272', colorCode: '01', eligible: true, reason: '' },
      { specId: 2, internalSkuId: 0, specName: '\u672a\u5173\u8054\u89c4\u683c', eligible: false, reason: '\u5c1a\u672a\u5173\u8054\u6b63\u5f0f SKU' }
    ]
    const capability = () => ({ enabled: ready, adapterReady: ready, authorized: true, canSubmit: ready, reason: ready ? '' : '\u7b49\u5f85 CRM \u63a5\u6536\u63a5\u53e3\u63a5\u5165' })
    const sync = () => ({ listingId: 88, status: 'not_submitted', skus: [] })
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url()), pathname = url.pathname
      if (!pathname.startsWith('/api/')) return route.continue()
      if (pathname.includes('/attachments/')) return route.fulfill({ path: 'src/assets/brand/deeplinkplm-logo.png', contentType: 'image/png' })
      let data = {}
      if (pathname.endsWith('/auth/profile')) data = { userId: 14, username: 'qa', displayName: 'QA', allPermissions: true, roleCode: 'administrator', permissions: ['*'] }
      else if (pathname.endsWith('/crm-capabilities')) data = capability()
      else if (pathname.endsWith('/crm-sync')) data = sync()
      else if (pathname.endsWith('/crm-push-options')) data = { listingId: 88, snapshotVersion: 'a'.repeat(64), product, skus, reasons: [], sync: sync(), ...capability() }
      else if (pathname.endsWith('/crm-pushes')) { submitted = route.request().postDataJSON(); data = { taskIds: [1], status: 'pending' } }
      else if (pathname.endsWith('/catalog/listings')) data = { content: [{ id: 88, listingId: 88, productId: 71, status: 'pending', integrationStatus: 'not_connected', name: product.name, catalogStyleCode: product.catalogStyleCode, phones: [{ phoneModelId: 18, modelName: 'Redmi A7 Pro', phoneModelCode: '0185' }], color: '\u9ed1\u8272', photoId: 81 }], totalElements: 1 }
      else if (pathname.endsWith('/tasks') || pathname.endsWith('/notifications')) data = []
      return route.fulfill({ json: { code: 0, message: '', data } })
    })
    await page.goto('http://127.0.0.1:5184/#/partner-listings?status=pending')
    const row = page.locator('.listed-row').first()
    await expect(row).toBeVisible()
    await row.locator('.el-checkbox').click()
    await page.getByRole('button', { name: '\u63a8\u9001 CRM', exact: true }).click()
    let dialog = page.getByRole('dialog')
    await expect(dialog.getByText('\u7b49\u5f85 CRM \u63a5\u6536\u63a5\u53e3\u63a5\u5165', { exact: true })).toBeVisible()
    await expect(dialog.getByRole('button', { name: '\u786e\u8ba4\u63a8\u9001', exact: true })).toBeDisabled()
    await page.waitForTimeout(350)
    await page.screenshot({ path: path.join(output, `disabled-${viewport.width}.png`), fullPage: true })
    await dialog.getByRole('button', { name: '\u5173\u95ed', exact: true }).click()
    ready = true
    await page.getByRole('button', { name: '\u5237\u65b0', exact: true }).click()
    await row.locator('.el-checkbox').click()
    await page.getByRole('button', { name: '\u63a8\u9001 CRM', exact: true }).click()
    dialog = page.getByRole('dialog')
    await expect(dialog.locator('input:checked')).toHaveCount(0)
    await dialog.locator('.el-checkbox').first().click()
    await expect(dialog.getByRole('checkbox').nth(1)).toBeDisabled()
    await expect(dialog.getByRole('button', { name: '\u786e\u8ba4\u63a8\u9001', exact: true })).toBeEnabled()
    const skuDetails = dialog.locator('.crm-sku-row').first().locator(':scope > span').first()
    await expect(skuDetails).toBeVisible()
    expect(await skuDetails.evaluate(el => parseFloat(getComputedStyle(el).fontSize))).toBeGreaterThanOrEqual(12)
    await page.waitForTimeout(350)
    const box = await dialog.boundingBox()
    if (!box || box.x < -1 || box.x + box.width > viewport.width + 1) throw new Error('Dialog overflows viewport')
    const overflow = await dialog.evaluate(el => el.scrollWidth > el.clientWidth + 1)
    if (overflow) throw new Error('Dialog has horizontal overflow')
    await page.screenshot({ path: path.join(output, `selected-${viewport.width}.png`), fullPage: true })
    await dialog.getByRole('button', { name: '\u786e\u8ba4\u63a8\u9001', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    expect(submitted.items[0].internalSkuIds).toEqual([101])
    expect(errors).toEqual([])
    console.log(`PASS ${viewport.width}x${viewport.height}: default gate, explicit SKU, submit, no overflow`)
    await context.close()
  }
} finally {
  await browser.close()
}
