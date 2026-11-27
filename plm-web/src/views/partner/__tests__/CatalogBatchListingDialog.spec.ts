import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CatalogBatchListingDialog from '../operations/CatalogBatchListingDialog.vue'
import type { SelectionRow } from '@/types/partnerSelections'
const api = vi.hoisted(() => ({ catalogListingOptions: vi.fn(), confirmCatalogListing: vi.fn(), newRequestId: vi.fn() }))
vi.mock('@/api/modules/partnerOperations', () => api)
vi.mock('@/api/modules/partner', () => ({ newRequestId: api.newRequestId }))
vi.mock('../PartnerPhoto.vue', () => ({ default: { template: '<span />' } }))
let wrapper: VueWrapper
const rows = [1, 2].map(id => ({ rowId: String(id), name: `Style ${id}`, catalogStyleId: id, confirmed: true, discarded: false })) as SelectionRow[]
const button = () => wrapper.findAll('button').find(b => b.text().startsWith('批量保存待上架'))!
async function start(input = rows) {
  wrapper = mount(CatalogBatchListingDialog, { props: { selectionId: 54, rows: input }, global: { plugins: [ElementPlus], stubs: { ElDialog: { template: '<section><slot/><slot name="footer"/></section>' } } } })
  await flushPromises()
}
async function fill() {
  for (const section of wrapper.findAll('.batch-listing-item')) {
    await section.find('.batch-pairs input').setValue(true)
    await section.find('textarea').setValue('Description')
  }
}
beforeEach(() => {
  vi.clearAllMocks()
  let seq = 0
  api.newRequestId.mockImplementation(() => `request-${++seq}`)
  api.catalogListingOptions.mockImplementation(async (_id, rowId) => ({ name: `Style ${rowId}`, selectionVersion: 1, selectionRowVersion: 2, photos: [{ id: 81, name: 'Photo', version: 'V1' }], pairs: [{ phoneModelId: Number(rowId), colorId: 10, specName: 'Eligible', eligible: true }, { phoneModelId: 99, colorId: 10, specName: 'Blocked', eligible: false, reason: 'Missing inspection' }] }))
  api.confirmCatalogListing.mockResolvedValue({})
})
afterEach(() => wrapper?.unmount())
describe('batch catalog listing', () => {
  it('requires explicit specs and sends independent existing requests', async () => {
    await start()
    expect(wrapper.findAll('.batch-pairs input:checked')).toHaveLength(0)
    expect(button().attributes('disabled')).toBeDefined()
    await fill()
    await button().trigger('click'); await flushPromises()
    expect(api.confirmCatalogListing).toHaveBeenCalledTimes(2)
    expect(api.confirmCatalogListing.mock.calls[0][0]).toMatchObject({ selectionRowId: '1', productLinkMode: 'create', selectedPairs: [{ phoneModelId: 1, colorId: 10 }] })
    expect(wrapper.emitted('saved')).toHaveLength(2)
    expect(wrapper.text()).toContain('已保存待上架')
  })
  it('retries only failed rows with the identical request', async () => {
    api.confirmCatalogListing.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('Timeout'))
    await start(); await fill(); await button().trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('Timeout')
    const failed = api.confirmCatalogListing.mock.calls[1][0]
    await button().trigger('click'); await flushPromises()
    expect(api.confirmCatalogListing).toHaveBeenCalledTimes(3)
    expect(api.confirmCatalogListing.mock.calls[2][0]).toEqual(failed)
  })
  it('does not read or submit discarded rows', async () => {
    await start([{ ...rows[0], discarded: true }])
    expect(api.catalogListingOptions).not.toHaveBeenCalled()
    expect(button().attributes('disabled')).toBeDefined()
    expect(api.confirmCatalogListing).not.toHaveBeenCalled()
  })
})
