import type { SelectionFileJob, SelectionRow, SelectionStagedRow, SupplierPriceOption, SupplierRangeReference } from '@/types/partnerSelections'
import { supplierPriceSummary } from './partnerPrice'

const states: Record<string, string> = {
  loading: '恢复进度中', awaiting_sheets: '请选择工作表', queued: '等待处理',
  processing: '正在处理', ready: '可以预览', failed: '处理未完成', committed: '已保存草稿'
}
export const selectionFileStateLabel = (status?: string) => states[status || ''] || '准备中'
const actions: Record<string, string> = { new: '新增', update: '有修改', same: '无变化', conflict: '需要修正' }
export const selectionImportActionLabel = (action: string) => actions[action] || '待检查'

export function selectionFileHistoryLabel(job: SelectionFileJob, index: number) {
  const sourcePages = job.kind !== 'export' && job.sheets?.length ? `（原件含${job.sheets.length}页）` : ''
  const title = `${job.kind === 'export' ? '模板导出' : '导入'}记录 ${index + 1}${sourcePages}`
  return `${title} · ${selectionFileStateLabel(job.status)}`
}

const fields = [
  ['name', '款式名称'], ['series', '系列'], ['supplierCode', '供应商'], ['supplierItemCode', '供方款号'],
  ['modelText', '手机型号'], ['color', '供方颜色'], ['material', '材质／配置'],
  ['specification', '规格补充'], ['unitPrice', '单价'], ['currency', '币种'],
  ['thickness', '厚度'], ['features', '工艺／功能与特点'], ['leadTimeNote', '参考生产周期'],
  ['sampleFeeNote', '样品费及退抵约定'], ['priceOptions', '配置与报价选项'],
  ['priceNote', '价格说明'], ['moq', '起订条件'], ['packaging', '包装'],
  ['sampleQty', '拟拿样数量（行合计）'], ['notes', '备注'], ['supplyScopeNote', '供货说明'],
  ['sameStyleKey', '同款分组'], ['developedOn', '开发日期'], ['developmentEvidence', '开发依据'],
  ['rangeRefs', '引用范围']
] as const

export interface SelectionPreviewFact { key:string;label:string;text:string;expand:boolean }
const extraFields = new Set<string>(['thickness','features','leadTimeNote','sampleFeeNote','priceNote','notes'])
const optionFields = [['priceNote','报价说明'],['moq','起订条件'],['packaging','包装']] as const
function previewFacts(value:Record<string,unknown>,keys:readonly (readonly [string,string])[]):SelectionPreviewFact[] {
  return keys.flatMap(([key,label])=>{
    const source=value[key]
    if(typeof source!=='string'&&typeof source!=='number')return []
    const text=String(source)
    return text?[{key,label,text,expand:text.length>120||text.split('\n').length>3||key==='notes'&&/(?:SHA-?256|原始来源|来源原件|校验值)/i.test(text)}]:[]
  })
}
/** Source fields are already filtered by the staged-row API; never inspect hidden workbook JSON here. */
export const selectionImportPreviewFacts=(values:Partial<SelectionRow>)=>previewFacts(values,fields.filter(([key])=>extraFields.has(key)))
export const selectionImportPriceFacts=(option:SupplierPriceOption)=>previewFacts({...option},optionFields)

function priceOptionsDisplay(value:unknown):string {
  if(!Array.isArray(value)||!value.length)return '未填写'
  return value.map((entry,index)=>{
    if(!entry||typeof entry!=='object'||Array.isArray(entry))return `报价选项 ${index+1}：待核对`
    const option=entry as SupplierPriceOption
    const label=typeof option.label==='string'&&option.label?option.label:`报价选项 ${index+1}`
    const conditions=selectionImportPriceFacts(option).map(fact=>`${fact.label}：${fact.text}`)
    return [`${label}：${supplierPriceSummary(option)}`,...conditions].join('；')
  }).join('\n')
}

function display(value: unknown): string {
  if (value === undefined || value === null || value === '') return '未填写'
  if (typeof value === 'string' || typeof value === 'number') return String(value)
  return '已有关联资料'
}
function rangeNames(value: unknown) {
  if (!Array.isArray(value) || !value.length) return '未引用范围'
  return (value as SupplierRangeReference[]).map(ref =>
    `${ref.type === 'model' ? '型号' : '颜色'}：${ref.reference}${ref.version ? `（第${ref.version}版）` : ''}`
  ).join('；')
}
/** Deliberately supported business fields only; never render arbitrary JSON keys or internal identities. */
export function selectionImportDifferences(row: SelectionStagedRow, suppliers: {code:string;name:string}[] = []) {
  if (!row.original || row.action === 'new') return []
  return fields.flatMap(([key, label]) => {
    const previous = row.original?.[key], next = row.values[key]
    if (JSON.stringify(previous) === JSON.stringify(next)) return []
    const supplierName = (value: unknown, position: string) => !value ? '未关联' :
      suppliers.find(supplier => supplier.code === value)?.name || `${position}供应商（名称待核对）`
    const before = key === 'supplierCode' ? supplierName(previous, '原') : key === 'rangeRefs' ? rangeNames(previous) : key === 'priceOptions' ? priceOptionsDisplay(previous) : display(previous)
    const after = key === 'supplierCode' ? supplierName(next, '本次') : key === 'rangeRefs' ? rangeNames(next) : key === 'priceOptions' ? priceOptionsDisplay(next) : display(next)
    return before === after ? [] : [{ key, label, previous: before, next: after }]
  })
}
