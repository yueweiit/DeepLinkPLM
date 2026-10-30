import type { PhoneIntakeEntryInput, PhoneIntakeSourceInput } from '@/types/phoneModelIntake'

export const phoneIntakeStates = { pending: '待处理', resolved: '已解决', deferred: '暂不处理' } as const
export const phoneIntakeClassifications = { missing: '确实缺档', review: '需要核对' } as const
export const phoneIntakeReviewReasons = { name: '名称写法', variant: '版本歧义', shared_group: '组合／共模', incomplete: '原文不完整', code: '已有资料未编码／编码核对' } as const

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('请选择包含单款来源和待补条目的 JSON 文件')
  return value as Record<string, unknown>
}
function positive(value: unknown, label: string): number {
  const id = Number(value)
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error(`${label}无效，请重新核对来源文件`)
  return id
}
function version(value: unknown, label: string): number {
  const result = Number(value)
  if (value === null || value === undefined || value === '' || !Number.isSafeInteger(result) || result < 0) throw new Error(`${label}无效，请重新核对来源文件`)
  return result
}
function text(value: unknown) { return typeof value === 'string' ? value.trim() : '' }

/** Import only references and engineering decisions. Source facts always come from the server. */
export function parsePhoneIntakeSource(contents: string): PhoneIntakeSourceInput {
  let root: Record<string, unknown>
  try { root = object(JSON.parse(contents.replace(/^\uFEFF/, ''))) } catch (e) {
    if (e instanceof SyntaxError) throw new Error('JSON 文件格式不正确，请核对后重试')
    throw e
  }
  const identity = root.identity ? object(root.identity) : root
  if (identity.sourceKind !== 'quote' && identity.sourceKind !== 'selection') throw new Error('来源类型无效，请使用单款来源文件')
  if (!text(identity.supplierCode) || !text(identity.rowId)) throw new Error('供应商或款式来源缺失，请重新核对文件')
  if (!Array.isArray(root.entries) || !root.entries.length) throw new Error('文件中没有待补条目')
  const entries: PhoneIntakeEntryInput[] = root.entries.map((value, index) => {
    const row = object(value), prefix = `第 ${index + 1} 行：`
    const supplierIndex = typeof row.supplierIndex === 'number' ? row.supplierIndex : typeof row.supplierIndex === 'string' ? row.supplierIndex : ''
    if (!String(supplierIndex).trim() || !text(row.componentText)) throw new Error(`${prefix}请填写原序号和待核对型号片段`)
    if (['rawBrandGroup', 'rawModelText'].some(field => row[field] !== undefined && typeof row[field] !== 'string')) throw new Error(`${prefix}原条目定位须保留原文`)
    if (row.classification !== 'missing' && row.classification !== 'review') throw new Error(`${prefix}请区分确实缺档或需要核对`)
    const reviewReason = typeof row.reviewReason === 'string' && row.reviewReason in phoneIntakeReviewReasons ? row.reviewReason as PhoneIntakeEntryInput['reviewReason'] : undefined
    if (row.classification === 'review' && !reviewReason) throw new Error(`${prefix}请选择具体核对类型`)
    if (!text(row.reason)) throw new Error(`${prefix}请补充核对依据`)
    return { supplierIndex, ...(row.rawBrandGroup === undefined ? {} : { rawBrandGroup: row.rawBrandGroup as string }), ...(row.rawModelText === undefined ? {} : { rawModelText: row.rawModelText as string }), componentText: text(row.componentText), classification: row.classification, ...(reviewReason ? { reviewReason } : {}), ...(row.brandId === undefined || row.brandId === null || row.brandId === '' ? {} : { brandId: positive(row.brandId, `${prefix}品牌`) }), reason: text(row.reason) }
  })
  return { supplierCode: text(identity.supplierCode), sourceKind: identity.sourceKind, recordId: positive(identity.recordId, '来源记录'), rowId: text(identity.rowId), rowVersion: version(identity.rowVersion, '款式版本'), recordVersion: version(identity.recordVersion, '来源版本'), entries }
}
