import { parseSelectionTsv } from '@/utils/partnerSelections'
import type { CatalogModelEntry, CatalogModelEntryInput, CatalogStyle } from '@/types/partnerCatalog'

const columns = ['supplierIndex', 'rawBrandGroup', 'rawModelText', 'rawStatus', 'rawNote', 'matchText', 'matchNote'] as const
export const modelSheetHeaders = ['原序号', '品牌分组', '原型号', '原状态', '原备注', '匹配用名称', '匹配说明']

/** Parse columns only. Model identities and supply conclusions remain server-owned. */
export function parseCatalogModelSheet(text: string): CatalogModelEntryInput[] {
  const values = parseSelectionTsv(text.replace(/^\uFEFF/, ''))
  if (values[0]?.[0]?.trim() === modelSheetHeaders[0]) {
    if (values[0].length < 5 || values[0].length > columns.length || !values[0].every((value, index) => value.trim() === modelSheetHeaders[index])) throw new Error('请按原序号、品牌分组、原型号、原状态、原备注、匹配用名称、匹配说明的顺序粘贴；后两列可省略。')
    values.shift()
  }
  return values.map((cells, index) => {
    if (cells.length < 5 || cells.length > columns.length) throw new Error(`第 ${index + 1} 条应包含原序号、品牌分组、原型号、原状态、原备注五列；后面可加匹配用名称和匹配说明。空白列也请保留。`)
    if (!cells[0].trim() || !cells[2].trim()) throw new Error(`第 ${index + 1} 条缺少原序号或原型号，请保留原表条目后补齐。`)
    return Object.fromEntries(columns.slice(0, cells.length).map((key, column) => [key, cells[column]])) as unknown as CatalogModelEntryInput
  })
}

export function catalogModelIndexDisplay(index: string | number): { label: string; sourceLocation: string } {
  const original = String(index)
  return /^原无序号（.+）$/s.test(original) ? { label: '—', sourceLocation: original } : { label: original, sourceLocation: '' }
}

export function catalogModelEntryKey(entry: Pick<CatalogModelEntryInput, 'supplierIndex' | 'rawBrandGroup' | 'rawModelText'>): string {
  return JSON.stringify([String(entry.supplierIndex), entry.rawBrandGroup, entry.rawModelText])
}

export function catalogModelEntryInput(entry: CatalogModelEntryInput): CatalogModelEntryInput {
  return { supplierIndex: String(entry.supplierIndex), rawBrandGroup: entry.rawBrandGroup, rawModelText: entry.rawModelText, rawStatus: entry.rawStatus, rawNote: entry.rawNote,
    ...(entry.matchText !== undefined ? { matchText: entry.matchText } : {}), ...(entry.matchNote !== undefined ? { matchNote: entry.matchNote } : {}),
    ...(entry.phoneModelIds !== undefined ? { phoneModelIds: [...entry.phoneModelIds] } : {}) }
}

export function modelEntryMatches(entry: CatalogModelEntry, search: string): boolean {
  const query = search.trim().toLocaleLowerCase().replace(/\s+/g, '')
  return !query || [entry.supplierIndex, entry.rawBrandGroup, entry.rawModelText, entry.rawStatus, entry.matchText, ...entry.phones?.flatMap(phone => [phone.modelName, phone.displayName, phone.phoneModelCode, phone.brandName]) || []]
    .some(value => String(value || '').toLocaleLowerCase().replace(/\s+/g, '').includes(query))
}

export function catalogPhoneProgress(row: Pick<CatalogStyle, 'supplierModelEntries'>, id: number): string[] {
  return (row.supplierModelEntries || []).filter(entry => entry.phoneModelIds.includes(id)).map(entry =>
    `${entry.rawStatus || '原表未注明进度'}${entry.rawNote ? ` · ${entry.rawNote}` : ''}（原表 ${entry.supplierIndex}）`)
}

export const modelSheetIssues = (entry: Pick<CatalogModelEntry,'issues'|'automaticIssues'>) => [...new Set([...(entry.issues || []), ...(entry.automaticIssues || [])])]
