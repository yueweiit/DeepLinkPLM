import type { CatalogIdentity, CatalogStyle } from '@/types/partnerCatalog'
import { phoneSeries, selectionSearchTerm, unavailablePhone } from '@/components/PhoneModelMultiSelect/selection'
import type { CompatiblePhone } from '@/types/molds'
import { t } from '@/locales'

export type SummaryPhone = Partial<CompatiblePhone> & { modelName?: string; displayName?: string }

function displayModelKey(phone: SummaryPhone) {
  const brands = [phone.brandName, ...phone.brandAliases || []].filter((name): name is string => !!name).map(name => name.normalize('NFKC').trim().toLowerCase())
  const members = (phone.modelName || phone.displayName || '').normalize('NFKC').split(/[\/,，、\n]/).map(member => {
    let name = selectionSearchTerm(member).toLowerCase().trim()
    for (const brand of brands) if (name.startsWith(brand + ' ')) name = name.slice(brand.length).trim()
    return name
  })
  const iphone = /^iphone(?:\s|\d)/.test(members[0] || '')
  return members.map(name => (iphone ? name.replace(/^iphone\s*/, '') : name).replace(/\s+/g, '')).join('/')
}

/** Collapse equivalent display entries only. Original member IDs and associations remain untouched. */
export function catalogDisplayPhones<P extends SummaryPhone>(phones: P[]): P[] {
  const ids = new Set<number>(), identities = new Set<string>(), result: P[] = []
  for (const phone of phones) {
    if (phone.phoneModelId && ids.has(phone.phoneModelId)) continue
    if (phone.phoneModelId) ids.add(phone.phoneModelId)
    const brand = phone.brandId ? 'id:' + phone.brandId : phone.brandName?.normalize('NFKC').trim().toLowerCase()
    const code = phone.phoneModelCode?.normalize('NFKC').trim(), model = displayModelKey(phone)
    const identity = brand && code && model ? JSON.stringify([brand, code, model]) : ''
    if (identity && identities.has(identity)) continue
    if (identity) identities.add(identity)
    result.push(phone)
  }
  return result
}

/** A display of the supplied members only; a generation span never claims every variant is selected. */
export function catalogPhoneGroups<P extends SummaryPhone>(phones: P[]) {
  const groups = new Map<string, { name: string; generations: Set<number>; phones: P[] }>()
  for (const phone of catalogDisplayPhones(phones)) {
    const name = selectionSearchTerm(phone.displayName || phone.modelName || '')
    const apple = name.match(/\biphone\s*(\d{1,2})(?!\d)/i)
    const samsung = /samsung|三星|\bsm\b/i.test([phone.brandName, name].join(' ')) && name.match(/\bs\s*(\d{1,2})(?!\d)/i)
    const key = apple ? 'iPhone' : samsung ? 'Samsung Galaxy S' : phone.brandName || t('其他型号')
    const group = groups.get(key) || { name: key, generations: new Set<number>(), phones: [] }
    const generation = apple?.[1] || (samsung && samsung[1])
    if (generation) group.generations.add(Number(generation))
    group.phones.push(phone)
    groups.set(key, group)
  }
  return [...groups.values()].map(group => {
    const generations = [...group.generations].sort((a, b) => a - b), spans: string[] = []
    for (let start = 0; start < generations.length;) {
      let end = start
      while (end + 1 < generations.length && generations[end + 1] === generations[end] + 1) end++
      spans.push(end - start >= 2 ? `${generations[start]}—${generations[end]}` : generations.slice(start, end + 1).join('、'))
      start = end + 1
    }
    const family = group.name === 'Samsung Galaxy S' ? group.name + spans.map(span => span.replace('—', '—S')).join('、S') : `${group.name} ${spans.join('、')}`
    return { name: group.name, label: spans.length ? t('{value0} 系列', { value0: family }) : group.name, phones: group.phones }
  })
}

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
export function catalogPhoneSummary(row: Pick<CatalogStyle,'phoneScopeMode'> & {phones?:SummaryPhone[];modelText?:string}): string {
  const phones = catalogDisplayPhones(row.phones || [])
  if (phones.length > 0 && phones.length <= 2) return phones.map(phone => phone.displayName||phone.modelName).filter(Boolean).join('、')||row.modelText||'型号资料待加载'
  if (phones.length > 2) return catalogPhoneGroups(phones).map(group => group.label).join(' / ')
  return row.modelText || (row.phoneScopeMode==='unspecified'?'供应商未注明型号':'型号资料待关联')
}

