import { currentLanguage, t } from '@/locales'
import { materialLabel, productLabel } from '@/views/bom/catalogPresentation'
import type { MesOperation, MesRoute } from '@/api/modules/process'
import type { RouteNode } from '@/api/modules/masterData'

export function routeProductKey(row: MesRoute) {
  return row.productId != null ? String(row.productId) : row.source.product_code || 'route-' + row.objectId
}
export function routeProductName(row: MesRoute) {
  return productLabel({seriesName: row.seriesName || '', seriesNameEs: row.seriesNameEs, seriesCode: row.source.product_code || '—'})
}
export function routeName(row: MesRoute) {
  const raw = row.source.route_name || row.sourceCode
  let label = (currentLanguage.value === 'es' && row.source.route_name_es) || materialLabel(raw)
  // MES titles often repeat the product before the variant in each language.
  const product = routeProductName(row)
  if (product && label.toLowerCase().startsWith(product.toLowerCase())) label = label.slice(product.length).trim() || label
  if (/^(?:1\.0\s*)?标准路线$/.test(label)) return t('标准路线')
  return label.replace(/^(染色|透明款)\s*[-－]\s*(带|无)\s*UV\s*打印$/, '$1 · $2 UV 打印').replace(/^无PC片打印路线$/,'无 PC 片打印')
}
export function operationName(op: MesOperation) {
  return (currentLanguage.value === 'es' && op.source.operation_name_es) || op.source.operation_name || op.sourceCode
}
export function operationCategory(op: MesOperation) {
  return (currentLanguage.value === 'es' && op.source.category_es) || t(op.source.category || '—')
}
export function graphNodeName(node: RouteNode, op?: MesOperation) {
  if (op && node.name === op.source.operation_name) return operationName(op)
  return t(materialLabel(node.name || node.nodeCode))
}
const labels: Record<string,string> = {
  published:'已发布', enabled:'已启用', disabled:'已停用', locked:'已锁定', draft:'草稿', confirmed:'已确认',
  changed:'已变更', archived:'已归档', released:'已发布', finish_to_start:'完成后开始', start_to_start:'同时开始',
  finish_to_finish:'同时完成', start_to_finish:'开始后完成', in_house_line:'厂内产线', in_house_machine:'厂内设备',
  resource_timeline:'按资源排程', semi_finished:'半成品', finished:'成品'
}
export function processValue(value: unknown): string {
  if (typeof value === 'boolean') return t(value ? '需要' : '无需')
  if (typeof value !== 'string' || !value.trim()) return '—'
  return t(labels[value] || value)
}
export function matchesProcess(query: string, values: Array<string | undefined>) {
  const fold = (value: string) => value.normalize('NFKD').replace(/[\u0300-\u036f\s·._-]/g,'').toLowerCase()
  const key = fold(query)
  return !key || values.some(value => value && fold(value).includes(key))
}

// The maintenance view also serves historical and user-defined parameters.
export function maintenanceParameters(config: Record<string,unknown>) {
  const covered = new Set(['outputType','output_type','outputBaseCode','output_base_code','needMaterialIssue','schedulable','needEquipment','equipmentType'])
  const labels: Record<string,string> = {nodeName:'节点名称',processCode:'工序编码',resourceType:'资源类型',capacityRuleId:'产能规则',materialRuleId:'物料规则',ruleTemplateId:'规则模板',reportTemplateId:'报工模板',sourceNodeCode:'来源节点',outputColorCode:'产出颜色码',outputColorMode:'颜色方式',outputDimensions:'产出维度',inputDimensions:'输入维度',materialDimensions:'物料维度',planningDimensions:'排程维度'}
  const result: string[][] = []
  function visit(label: string, value: unknown) {
    if (Array.isArray(value) && value.every(v => typeof v === 'string' || typeof v === 'number')) {
      result.push([label,value.join('、') || '—'])
    } else if (value && typeof value === 'object' && Object.keys(value).length) {
      for (const [key,child] of Object.entries(value)) visit(label + ' / ' + t(labels[key] || key),child)
    } else {
      const text = typeof value === 'boolean' ? t(value?'是':'否') : value == null || typeof value === 'object' ? '—' : String(value)
      result.push([label,text])
    }
  }
  for (const [key,value] of Object.entries(config)) {
    if (!covered.has(key)) visit(t(labels[key] || key),value)
  }
  return result
}
