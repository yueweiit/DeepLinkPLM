import type { UnitCostExtraColumn, UnitCostRow } from '@/api/modules/productResearch'
import { parseClipboard } from '@/utils/collaboration'
import { t as uiText } from '@/locales'

export const costGroups = [
  { key: 'material', name: '材料成本' }, { key: 'process', name: '加工成本' },
  { key: 'tooling', name: '模具摊销' }, { key: 'tool_transport', name: '模具运输摊销' },
  { key: 'development', name: '开发费用' }, { key: 'freight_sea', name: '成品海运' },
  { key: 'freight_air', name: '成品空运' },
] as const
export const costModes = [
  { key: 'unit', name: '直接填单件' }, { key: 'usage', name: '单价×用量' },
  { key: 'divide', name: '总额÷数量' },
] as const
export const costColumns = ['category', 'name', 'mode', 'unitPrice', 'basisQuantity', 'lossPct', 'taxIncluded', 'taxRate', 'description', 'remark'] as const
export type CoreCostColumn = typeof costColumns[number]
export type CostColumn = CoreCostColumn | `extra:${string}`
export const costColumnLabels: Record<CoreCostColumn, string> = {
  category: '费用类型', name: '费用项目', mode: '计算方式', unitPrice: '单价／总额',
  basisQuantity: '用量／分摊数量', lossPct: '损耗%', taxIncluded: '原价含税',
  taxRate: '税率%', description: '费用说明', remark: '备注',
}
type CellValue = string | number | boolean | null
type Cell = readonly [CostColumn, CellValue]
const normalize = (value: string) => value.normalize('NFKC').trim().toLowerCase()
const blank = (value: CellValue) => typeof value === 'string' && !value.trim()
const pending = (value: CellValue) => value === null || value === '待补' || value === '待定'
const headerNames = new Map<string, CoreCostColumn>(costColumns.flatMap(key => [[normalize(key), key], [normalize(costColumnLabels[key]), key]] as [string, CoreCostColumn][]))
for (const [name, field] of Object.entries({ 类别: 'category', 项目: 'name', 模式: 'mode', 单价: 'unitPrice', 总额: 'unitPrice', 原料单价: 'unitPrice', '原价/总额': 'unitPrice', 用量: 'basisQuantity', 分摊数量: 'basisQuantity', '用量/分摊数': 'basisQuantity', 损耗率: 'lossPct', 损耗率百分比: 'lossPct', 含税: 'taxIncluded', 税率: 'taxRate', 税率百分比: 'taxRate', 说明: 'description', '费用说明/计算依据': 'description' })) headerNames.set(normalize(name), field as CoreCostColumn)
const resultHeaders = new Set(['单件成本', '单件金额', '核算单件成本', '换算单价', '换算单件', '换算价', '单件总成本', '单件总成本（成品运输前）', '材料＋加工小计', '含海运合计', '含空运合计', '小计', '合计', 'unitCost', 'displayCost', 'totals', 'groups'].map(normalize))
const isExtra = (field: CostColumn): field is `extra:${string}` => field.startsWith('extra:')

export function costColumnLabel(field: CostColumn, extraColumns: UnitCostExtraColumn[] = []): string {
  return isExtra(field) ? extraColumns.find(column => column.id === field.slice(6))?.label || uiText('补充列') : uiText(costColumnLabels[field])
}

export function validateCostExtraColumns(extraColumns: UnitCostExtraColumn[]): void {
  const ids = new Set<string>(), labels = new Set<string>([...headerNames.keys(), ...resultHeaders])
  for (const column of extraColumns) {
    if (!column || typeof column.id !== 'string' || !column.id.trim() || ids.has(normalize(column.id))) throw Error(uiText('补充列编号为空或重复，请重新添加该列。'))
    if (typeof column.label !== 'string' || !column.label.trim()) throw Error(uiText('请填写补充列名称。'))
    if (column.type !== 'text' && column.type !== 'number') throw Error(uiText('补充列类型须为文字或数值。'))
    ids.add(normalize(column.id))
  }
  for (const id of ids) labels.add(normalize(`extra:${id}`))
  for (const column of extraColumns) {
    const label = normalize(column.label)
    if (labels.has(label)) throw Error(uiText('补充列名称“{value0}”与已有字段重复，请更换名称。', { value0: column.label }))
    labels.add(label)
  }
}

export function newCostRow(category = 'material', mode: UnitCostRow['mode'] = 'unit'): UnitCostRow {
  return { id: crypto.randomUUID(), category: groupValue(category), name: '', mode: modeValue(mode), unitPrice: null,
    quantity: null, allocationQty: null, lossPct: null, taxIncluded: null, taxRate: null, description: '', remark: '' }
}

export function pasteCostRows(rows: UnitCostRow[], text: string, startRow = 0, startColumn: CostColumn = 'category', extraColumns: UnitCostExtraColumn[] = []): UnitCostRow[] {
  validateCostExtraColumns(extraColumns)
  if (!Number.isInteger(startRow) || startRow < 0 || startRow > rows.length) throw Error(uiText('粘贴起始行无效，请选择已有行或末尾新增行。'))
  const allColumns: CostColumn[] = [...costColumns, ...extraColumns.map(column => `extra:${column.id}` as const)]
  columnIndex(startColumn, allColumns)
  let matrix: string[][]
  try { matrix = parseClipboard(text) }
  catch (error) { throw Error(uiText(error instanceof Error ? error.message : '粘贴内容的引号不完整')) }
  if (!matrix.some(cells => cells.some(value => value.trim()))) return rows.map(cloneRow)
  const headers = new Map<string, CostColumn>(headerNames)
  for (const column of extraColumns) {
    headers.set(normalize(column.label), `extra:${column.id}`)
    headers.set(normalize(`extra:${column.id}`), `extra:${column.id}`)
  }
  const first = matrix[0].map(normalize)
  const hasHeader = first.some(value => headers.has(value) || resultHeaders.has(value))
  const columns: readonly CostColumn[] = hasHeader ? first.map(value => {
    if (resultHeaders.has(value)) throw Error(uiText('“单件成本”等核算结果不能导入为单价或总额，请使用“单价／总额”输入列。'))
    const field = headers.get(value)
    if (!field) throw Error(uiText('无法识别表头“{value0}”，请使用已有成本字段。', { value0: value }))
    return field
  }) : allColumns.slice(columnIndex(startColumn, allColumns))
  if (new Set(columns).size !== columns.length) throw Error(uiText('粘贴表头存在重复字段，请核对后重新粘贴。'))
  const dataRows = hasHeader ? matrix.slice(1) : matrix
  if (Math.max(rows.length, startRow + dataRows.length) > 200) throw Error(uiText('成本明细最多200项，整块粘贴未应用；请先调整已有明细。'))
  const next = rows.map(cloneRow)
  for (const [offset, cells] of dataRows.entries()) {
    if (cells.length > columns.length) throw Error(uiText('第 {value0} 行超出可粘贴字段范围，整块内容未应用。', { value0: offset + 1 }))
    const index = startRow + offset
    if (index === next.length) {
      const previous = next[index - 1]
      next.push(newCostRow(previous?.category, previous?.mode))
    }
    try {
      applyCells(next[index], cells.map((value, col) => [columns[col], value] as const), extraColumns)
      if (hasHeader) for (const [col, value] of cells.entries()) {
        if (blank(value)) continue
        if (first[col] === '总额' && next[index].mode !== 'divide') throw Error(uiText('“总额”只能用于总额÷数量，请先明确本行计算方式。'))
        if (first[col] === '原料单价' && next[index].mode !== 'usage') throw Error(uiText('“原料单价”只能用于单价×用量，请先明确本行计算方式。'))
      }
    }
    catch (error) { throw Error(uiText('第 {value0} 行：{value1}', { value0: offset + 1, value1: error instanceof Error ? error.message : String(error) })) }
  }
  return next
}

export function patchCostRows(rows: UnitCostRow[], selectedIds: string[], field: CostColumn, value: CellValue, extraColumns: UnitCostExtraColumn[] = []): UnitCostRow[] {
  validateCostExtraColumns(extraColumns)
  columnIndex(field, [...costColumns, ...extraColumns.map(column => `extra:${column.id}` as const)])
  return applyToSelected(rows, selectedRows(rows, selectedIds), field, value, extraColumns)
}

export function fillCostRows(rows: UnitCostRow[], selectedIds: string[], field: CostColumn, extraColumns: UnitCostExtraColumn[] = []): UnitCostRow[] {
  validateCostExtraColumns(extraColumns)
  columnIndex(field, [...costColumns, ...extraColumns.map(column => `extra:${column.id}` as const)])
  const selected = selectedRows(rows, selectedIds), first = selected[0]
  if (field === 'basisQuantity' && selected.some(row => row.mode !== first.mode)) throw Error(uiText('不同计算方式的用量与分摊数量不能互相填充，请选择相同计算方式的行。'))
  const value = isExtra(field) ? first.extraValues?.[field.slice(6)] : field === 'basisQuantity' ? first.mode === 'usage' ? first.quantity : first.mode === 'divide' ? first.allocationQty : null : first[field]
  return applyToSelected(rows, selected, field, value ?? null, extraColumns)
}

function cloneRow(row: UnitCostRow): UnitCostRow { return { ...row, ...(row.extraValues ? { extraValues: { ...row.extraValues } } : {}) } }

function applyToSelected(rows: UnitCostRow[], selected: UnitCostRow[], field: CostColumn, value: CellValue, extraColumns: UnitCostExtraColumn[]) {
  if (field === 'unitPrice' && selected.some(row => row.mode !== selected[0].mode)) throw Error(uiText('不同计算方式的金额含义不同，请先统一计算方式再批量填写或向下填充。'))
  const ids = new Set(selected.map(row => row.id))
  return rows.map(row => {
    const next = cloneRow(row)
    if (ids.has(row.id)) applyCells(next, [[field, value]], extraColumns)
    return next
  })
}

function selectedRows(rows: UnitCostRow[], selectedIds: string[]) {
  const ids = new Set(selectedIds), selected = rows.filter(row => ids.has(row.id))
  if (!ids.size) throw Error(uiText('请先勾选需要处理的费用行。'))
  if (selected.length !== ids.size) throw Error(uiText('所选费用行已变化，请重新勾选。'))
  return selected
}
function columnIndex(field: CostColumn, columns: readonly CostColumn[]) {
  const index = columns.indexOf(field)
  if (index < 0) throw Error(uiText('请选择可录入的成本字段。'))
  return index
}
function groupValue(value: CellValue) {
  const found = costGroups.find(group => normalize(String(value)) === group.key || normalize(String(value)) === normalize(group.name))
  if (!found) throw Error(uiText('费用类型无效，请使用已有的七种费用类型。'))
  return found.key
}
function modeValue(value: CellValue): UnitCostRow['mode'] {
  const found = costModes.find(mode => normalize(String(value)) === mode.key || normalize(String(value)) === normalize(mode.name))
  if (!found) throw Error(uiText('计算方式无效，请选择直接填单件、单价×用量或总额÷数量。'))
  return found.key
}
function numberValue(field: CoreCostColumn, value: CellValue, mode: UnitCostRow['mode']) {
  if (pending(value)) return null
  const text = typeof value === 'string' ? value.trim() : value
  const number = typeof text === 'number' ? text : typeof text === 'string' && /^[+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(text) ? Number(text) : NaN
  if (!Number.isFinite(number) || number < 0 || number > 1e12) throw Error(uiText('{value0}必须是有效的非负数字，不能填写计算公式。', { value0: uiText(costColumnLabels[field]) }))
  if ((field === 'lossPct' || field === 'taxRate') && number > 100) throw Error(uiText('{value0}须在0到100之间。', { value0: uiText(costColumnLabels[field]) }))
  if (field === 'basisQuantity' && mode === 'divide' && number === 0) throw Error(uiText('分摊数量必须大于0。'))
  return number
}
function applyCells(row: UnitCostRow, cells: readonly Cell[], extraColumns: UnitCostExtraColumn[]) {
  const mode = cells.find(([field, value]) => field === 'mode' && !blank(value))
  if (mode) row.mode = modeValue(mode[1])
  for (const [field, raw] of cells) {
    if (field === 'mode' || blank(raw)) continue
    const value = typeof raw === 'string' ? raw.trim() : raw
    if (isExtra(field)) {
      const column = extraColumns.find(column => column.id === field.slice(6))!
      let extraValue: string | number | null = raw == null ? null : String(raw)
      if (column.type === 'number') {
        if (pending(value)) extraValue = null
        else {
          const text = typeof value === 'number' ? value : typeof value === 'string' && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value) ? Number(value) : NaN
          if (!Number.isFinite(text)) throw Error(uiText('补充列“{value0}”须填写有效数值，不能填写计算公式。', { value0: column.label }))
          extraValue = text
        }
      }
      row.extraValues = { ...row.extraValues, [column.id]: extraValue }
    } else if (field === 'category') row.category = groupValue(value)
    else if (field === 'name' || field === 'description' || field === 'remark') row[field] = raw == null ? '' : String(raw)
    else if (field === 'taxIncluded') {
      if (pending(value)) row.taxIncluded = null
      else if (value === true || value === 1 || ['是', '含税', 'true', '1'].includes(normalize(String(value)))) row.taxIncluded = true
      else if (value === false || value === 0 || ['否', '未税', 'false', '0'].includes(normalize(String(value)))) row.taxIncluded = false
      else throw Error(uiText('原价含税请填写是、否或待定。'))
    } else {
      if (field === 'basisQuantity' && row.mode === 'unit') throw Error(uiText('直接填单件不使用数量，请留空或先选择单价×用量／总额÷数量。'))
      const number = numberValue(field, value, row.mode)
      if (field === 'basisQuantity') {
        if (row.mode === 'usage') row.quantity = number
        else row.allocationQty = number
      } else row[field] = number
    }
  }
}
