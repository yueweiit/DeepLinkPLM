import type { CatalogIdentity, CatalogStyle } from '@/types/partnerCatalog'
import { phoneSeries, selectionSearchTerm, unavailablePhone } from '@/components/PhoneModelMultiSelect/selection'

export const catalogKey = (row: CatalogIdentity) => `${row.sourceKind}:${row.recordId}:${row.rowId}`
export const catalogIdentity = (row: CatalogIdentity): CatalogIdentity => ({ sourceKind: row.sourceKind, recordId: row.recordId, rowId: row.rowId, rowVersion: row.rowVersion, recordVersion: row.recordVersion })

export function catalogAllowedPhoneIds(row: Pick<CatalogStyle, 'phoneModelIds' | 'unavailablePhoneModelIds'>): number[] {
  const unavailable = new Set(row.unavailablePhoneModelIds || [])
  return [...new Set(row.phoneModelIds)].filter(id => !unavailable.has(id))
}

/** Display shortcuts over existing source members, not new series or supply identities. */
export function catalogPhoneFamilies(row: Pick<CatalogStyle, 'phones' | 'phoneModelIds' | 'unavailablePhoneModelIds'>) {
  const allowed = new Set(catalogAllowedPhoneIds(row)), groups = new Map<string, { key: string; name: string; phoneModelIds: number[] }>()
  for (const phone of row.phones) {
    if (!allowed.has(phone.phoneModelId) || unavailablePhone(phone)) continue
    const brands = [phone.brandName, ...phone.brandAliases || []].map(value => value.normalize('NFKC').trim().toLowerCase())
    const apple = brands.some(value => value === 'apple' || value === '苹果')
    const samsung = brands.some(value => value === 'samsung' || value === '三星')
    if (apple === samsung) continue
    const names = [phone.modelName, phone.displayName, ...phone.aliases || [], phoneSeries(phone).name].filter((value): value is string => !!value)
      .map(value => selectionSearchTerm(value).replace(/^(?:apple|苹果|samsung|三星)\s*/i, '').replace(/^galaxy\s*/i, '').trim())
    const families = new Set(names.flatMap(value => {
      if (apple) {
        if (/^iphone\s*(?:\d{1,2}(?!\d)|se\b|x(?:[rs])?\b|(?:系列)?$)/i.test(value)) return ['iphone']
        return /^(?:ipad|ipod)\b/i.test(value) ? ['other'] : []
      }
      return value.match(/^(s|a|m|f|z|note)(?=\s*\d|\s*(?:系列)?$)/i)?.[1]?.toLowerCase() || []
    }))
    if (families.size !== 1) continue
    const family = [...families][0]
    if (family !== 'iphone' && family !== 's') continue
    const key = family === 'iphone' ? 'iphone' : 'samsung-s', name = family === 'iphone' ? 'iPhone系列' : 'Samsung Galaxy S系列'
    const group = groups.get(key) || { key, name, phoneModelIds: [] }
    if (!group.phoneModelIds.includes(phone.phoneModelId)) group.phoneModelIds.push(phone.phoneModelId)
    groups.set(key, group)
  }
  return [...groups.values()]
}

/** Names and codes come from the shared phone records, never a second selector. */
export function catalogPhoneSummary(row: Pick<CatalogStyle,'phoneScopeMode'> & {phones?:{modelName?:string;displayName?:string}[];modelText?:string}): string {
  const phones = row.phones || []
  if (phones.length > 0 && phones.length <= 2) return phones.map(phone => phone.modelName||phone.displayName).filter(Boolean).join('、')||row.modelText||'型号资料待加载'
  if (phones.length > 2) return phones[0].modelName||phones[0].displayName||'型号资料待加载'
  return row.modelText || (row.phoneScopeMode==='unspecified'?'供应商未注明型号':'型号资料待关联')
}

