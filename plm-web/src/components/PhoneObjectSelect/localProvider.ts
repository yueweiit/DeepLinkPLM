import { selectionSearchTerm } from '@/components/PhoneModelMultiSelect/selection'
import type { ObjectPhone, ObjectPhoneGroup, PhoneObjectOption, PhoneObjectProvider, PhoneObjectQuery } from './types'

/** For existing, permission-filtered option collections. Render one page, never thousands of checkboxes. */
export function localObjectProvider(source: () => PhoneObjectOption[] | Promise<PhoneObjectOption[]>): PhoneObjectProvider {
  async function scope(q: PhoneObjectQuery) {
    const terms = selectionSearchTerm(q.keyword || '').toLocaleLowerCase().split(/\s+/).filter(Boolean)
    const rows = (await source()).filter(row => (!q.type || row.type === q.type) && (!q.productType || row.productType === q.productType)).filter(row => {
      const text = [row.name, row.code, row.subtitle, row.modelName, row.phoneModelCode, row.productLineName,
        ...(row.phoneModels || []).flatMap(p => [p.brandName, p.modelName, p.phoneModelCode, ...(p.aliases || [])])].join(' ').toLocaleLowerCase()
      return terms.every(t => text.includes(t))
    })
    const brands = [...new Map(rows.flatMap(r => r.phoneModels || []).map(p => [p.brandId, { brandId: p.brandId, name: p.brandName }])).values()].sort((a, b) => a.name.localeCompare(b.name))
    const productLines = [...new Map(rows.filter(r => r.productLineId).map(r => [r.productLineId!, { productLineId: r.productLineId!, name: r.productLineName || `产品 #${r.productLineId}` }])).values()]
    const filtered = rows.filter(r => (!q.productLineId || r.productLineId === q.productLineId)
      && (!(q.brandId || q.phoneModelId) || r.phoneModels?.some(p => (!q.brandId || p.brandId === q.brandId) && (!q.phoneModelId || p.phoneModelId === q.phoneModelId)))
      && (!q.unlinkedPhone || !r.phoneModels?.some(p => p.phoneModelId)))
    return { filtered, brands, productLines }
  }
  const code = (phones?: ObjectPhone[]) => (phones || []).reduce((max, p) => (p.phoneModelCode || '') > max ? p.phoneModelCode! : max, '')
  const page = <T,>(rows: T[], q: PhoneObjectQuery) => rows.slice((q.page - 1) * q.size, q.page * q.size)
  return {
    async page(q) {
      const { filtered, ...facets } = await scope(q)
      filtered.sort((a, b) => code(b.phoneModels).localeCompare(code(a.phoneModels)) || a.code.localeCompare(b.code) || a.id - b.id)
      return { ...facets, content: page(filtered, q), totalElements: filtered.length }
    },
    async phones(q) {
      const { filtered, ...facets } = await scope(q), groups = new Map<number, ObjectPhoneGroup>()
      for (const row of filtered) for (const p of new Map((row.phoneModels || []).filter(p => p.phoneModelId && (!q.brandId || p.brandId === q.brandId) && (!q.phoneModelId || p.phoneModelId === q.phoneModelId)).map(p => [p.phoneModelId, p])).values()) {
        const old = groups.get(p.phoneModelId!)
        groups.set(p.phoneModelId!, { ...p, phoneModelId: p.phoneModelId!, referenceCount: (old?.referenceCount || 0) + 1 })
      }
      const phones = [...groups.values()].sort((a, b) => (b.phoneModelCode || '').localeCompare(a.phoneModelCode || '') || a.modelName.localeCompare(b.modelName) || a.phoneModelId - b.phoneModelId)
      return { ...facets, content: page(phones, q), totalElements: phones.length, unlinkedCount: filtered.filter(r => !r.phoneModels?.some(p => p.phoneModelId)).length }
    }
  }
}
