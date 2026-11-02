import { describe, expect, it } from 'vitest'
import type { UnitCostExtraColumn, UnitCostRow } from '@/api/modules/productResearch'
import { currentLanguage, setLanguage } from '@/locales'
import { costColumnLabel, costColumns, fillCostRows, newCostRow, pasteCostRows, patchCostRows, validateCostExtraColumns } from '../unitCostEditing'

const row = (id: string, mode: UnitCostRow['mode'] = 'unit'): UnitCostRow => ({
  id, category: 'material', name: id, mode, unitPrice: 8, quantity: 2, allocationQty: 50,
  lossPct: 3, taxIncluded: true, taxRate: 13, description: '原规格', remark: '原备注',
  bomItemId: 91, sourceQuantity: '0.00000001', sourceOutputQty: '10.0000', sourceCell: '原表!D9',
})
describe('cost clipboard and batch editing use the existing row contract', () => {
  it('creates independent empty rows without inventing amounts or quantities', () => {
    const first = newCostRow('tooling', 'divide'), second = newCostRow('tooling', 'divide')
    expect(first.id).not.toBe(second.id)
    expect(first).toMatchObject({ category: 'tooling', mode: 'divide', unitPrice: null, quantity: null, allocationQty: null, lossPct: null, taxIncluded: null, taxRate: null })
    expect(first).not.toHaveProperty('unitCost')
  })
  it('parses reordered Chinese headers and resolves mode before quantity', () => {
    const original = [row('a')], before = JSON.stringify(original)
    const result = pasteCostRows(original, '分摊数量\t费用项目\t计算方式\t单价／总额\n100\t模具费\t总额÷数量\t500')
    expect(result[0]).toMatchObject({ id: 'a', mode: 'divide', name: '模具费', unitPrice: 500, allocationQty: 100, quantity: 2, lossPct: 3 })
    expect(JSON.stringify(original)).toBe(before)
  })
  it('reuses quoted Excel TSV for multiline descriptions and escaped quotes', () => {
    const result = pasteCostRows([row('a')], 'name\tdescription\tremark\nPC\t"第一行\n规格\"\"A\"\""\t原件依据\n')
    expect(result[0]).toMatchObject({ name: 'PC', description: '第一行\n规格"A"', remark: '原件依据' })
  })
  it('does not paste an explicitly named total as a unit amount or infer its calculation mode', () => {
    const original = [row('a')], before = JSON.stringify(original)
    expect(() => pasteCostRows(original, '总额\n1200')).toThrow('总额÷数量')
    expect(JSON.stringify(original)).toBe(before)
    expect(pasteCostRows(original, '总额\t计算方式\t分摊数量\n1200\t总额÷数量\t100')[0]).toMatchObject({ mode: 'divide', unitPrice: 1200, allocationQty: 100 })
    expect(pasteCostRows([row('a', 'divide')], '总额\n1200')[0].unitPrice).toBe(1200)
  })
  it('requires usage mode for an explicit raw-material price and accepts the existing generic UI headers', () => {
    const original = [row('a')]
    expect(() => pasteCostRows(original, '原料单价\n12')).toThrow('单价×用量')
    expect(pasteCostRows(original, '原料单价\tmode\t用量\n12\tusage\t2')[0]).toMatchObject({ mode: 'usage', unitPrice: 12, quantity: 2 })
    expect(pasteCostRows(original, '原价／总额\t用量／分摊数\tmode\n1200\t100\tdivide')[0]).toMatchObject({ mode: 'divide', unitPrice: 1200, allocationQty: 100 })
  })
  it('applies a headerless rectangle at the chosen field and preserves blank cells', () => {
    const original = [row('a', 'usage'), row('b', 'usage')]
    expect(pasteCostRows(original, '\t\n', 2, 'name')).toEqual(original)
    const result = pasteCostRows(original, '0\t4\t0\n\t\t待补', 0, 'unitPrice')
    expect(result[0]).toMatchObject({ unitPrice: 0, quantity: 4, lossPct: 0 })
    expect(result[1]).toMatchObject({ unitPrice: 8, quantity: 2, lossPct: null })
    expect(result[1]).toMatchObject({ id: 'b', allocationQty: 50, bomItemId: 91, sourceQuantity: '0.00000001', sourceOutputQty: '10.0000', sourceCell: '原表!D9' })
  })
  it('appends rows with only the preceding category and mode inherited', () => {
    const original = [{ ...row('a', 'divide'), category: 'tooling' }]
    const result = pasteCostRows(original, '第二项\n第三项', 1, 'name')
    expect(result).toHaveLength(3)
    for (const added of result.slice(1)) {
      expect(added).toMatchObject({ category: 'tooling', mode: 'divide', unitPrice: null, quantity: null, allocationQty: null, taxIncluded: null })
      expect(added).not.toHaveProperty('bomItemId')
      expect(added).not.toHaveProperty('sourceCell')
    }
    expect(new Set(result.map(item => item.id)).size).toBe(3)
    expect(original).toHaveLength(1)
  })
  it('keeps the existing 200-line server boundary and rejects excess paste without truncating', () => {
    const original = [row('a')], before = JSON.stringify(original)
    const text = 'name\n' + Array.from({ length: 200 }, (_, i) => `费用${i}`).join('\n')
    expect(pasteCostRows([], text)).toHaveLength(200)
    expect(() => pasteCostRows(original, text, 1)).toThrow('最多200项')
    expect(JSON.stringify(original)).toBe(before)
  })
  it.each([
    ['重复表头', 'name\t费用项目\nA\tB', '重复'],
    ['未知表头', 'name\t不存在的字段\nA\tB', '表头'],
    ['计算结果列', '单件成本\tname\n5\tA', '核算结果'],
    ['计算结果字段', 'unitCost\n5', '核算结果'],
  ])('rejects %s before changing rows', (_case, text, message) => {
    const original = [row('a')], before = JSON.stringify(original)
    expect(() => pasteCostRows(original, text)).toThrow(message)
    expect(JSON.stringify(original)).toBe(before)
  })
  it('rejects columns past the rectangle boundary instead of dropping them', () => {
    const original = [row('a')]
    expect(() => pasteCostRows(original, 'new\textra', 0, 'remark')).toThrow('超出')
    expect(original[0].remark).toBe('原备注')
  })
  it.each(['-1', '=1+2', '1+2', 'abc', 'Infinity', '1e999', '1000000000001'])('rejects invalid amount %s atomically', value => {
    const original = [row('a'), row('b')], before = JSON.stringify(original)
    expect(() => pasteCostRows(original, `unitPrice\n9\n${value}`)).toThrow('非负数字')
    expect(JSON.stringify(original)).toBe(before)
  })
  it('keeps unknown, explicit zero, tiny source values and text formulas distinct', () => {
    const original = [row('a'), row('b'), row('c')]
    const result = pasteCostRows(original, 'unitPrice\tremark\n待定\t=文字依据\n0\t\n1e-8\t')
    expect(result.map(item => item.unitPrice)).toEqual([null, 0, 0.00000001])
    expect(result[0].remark).toBe('=文字依据')
    expect(result[1].remark).toBe('原备注')
  })
  it('validates tax and loss percentages without assuming a tax status', () => {
    const original = [row('a')]
    expect(() => pasteCostRows(original, 'taxRate\n101')).toThrow('0到100')
    expect(() => patchCostRows(original, ['a'], 'lossPct', 101)).toThrow('0到100')
    expect(pasteCostRows(original, 'taxIncluded\ttaxRate\n待定\t0')[0]).toMatchObject({ taxIncluded: null, taxRate: 0 })
    expect(patchCostRows(original, ['a'], 'taxIncluded', '否')[0].taxIncluded).toBe(false)
    expect(() => patchCostRows(original, ['a'], 'taxIncluded', '看情况')).toThrow('是、否')
  })
  it('does not treat quantity as an algorithm switch or accept a zero divisor', () => {
    const original = [row('a')]
    expect(() => pasteCostRows(original, 'basisQuantity\n5')).toThrow('直接填单件')
    expect(() => pasteCostRows(original, 'mode\tbasisQuantity\ndivide\t0')).toThrow('大于0')
    expect(pasteCostRows(original, 'basisQuantity\tmode\n0\tusage')[0]).toMatchObject({ mode: 'usage', quantity: 0, allocationQty: 50 })
  })
  it('patches only selected rows and preserves mode-specific hidden values and metadata', () => {
    const original = [row('a', 'usage'), row('b', 'divide')], before = JSON.stringify(original)
    const result = patchCostRows(original, ['b'], 'mode', 'unit')
    expect(result[0]).toEqual(original[0])
    expect(result[1]).toMatchObject({ id: 'b', mode: 'unit', quantity: 2, allocationQty: 50, lossPct: 3, bomItemId: 91, sourceCell: '原表!D9' })
    expect(JSON.stringify(original)).toBe(before)
    expect(patchCostRows(original, ['a'], 'unitPrice', '')[0].unitPrice).toBe(8)
    expect(patchCostRows(original, ['a'], 'unitPrice', null)[0].unitPrice).toBeNull()
  })
  it('fills from the first displayed selected row, leaving unselected exceptions intact', () => {
    const original = [{ ...row('a'), unitPrice: 3 }, { ...row('b'), unitPrice: 7 }, { ...row('c'), unitPrice: 11 }]
    const result = fillCostRows(original, ['c', 'a'], 'unitPrice')
    expect(result.map(item => item.unitPrice)).toEqual([3, 7, 3])
    expect(original.map(item => item.unitPrice)).toEqual([3, 7, 11])
  })
  it('rejects quantity filling across different modes as one whole operation', () => {
    const original = [row('a', 'usage'), row('b', 'divide')], before = JSON.stringify(original)
    expect(() => fillCostRows(original, ['a', 'b'], 'basisQuantity')).toThrow('不同计算方式')
    expect(JSON.stringify(original)).toBe(before)
    expect(patchCostRows(original, ['a', 'b'], 'basisQuantity', 6).map(item => [item.quantity, item.allocationQty])).toEqual([[6, 50], [2, 6]])
  })
  it.each([['divide', 'unit'], ['divide', 'usage'], ['usage', 'unit']] as const)('rejects money filling or batch changes between %s and %s', (first, second) => {
    const original = [{ ...row('a', first), unitPrice: 1200 }, row('b', second)], before = JSON.stringify(original)
    expect(() => fillCostRows(original, ['a', 'b'], 'unitPrice')).toThrow('金额含义不同')
    expect(() => patchCostRows(original, ['a', 'b'], 'unitPrice', 1200)).toThrow('金额含义不同')
    expect(JSON.stringify(original)).toBe(before)
    expect(fillCostRows([row('a', first), { ...row('b', first), unitPrice: 1 }], ['a', 'b'], 'unitPrice').map(item => item.unitPrice)).toEqual([8, 8])
  })
  it('rejects stale selections and invalid start points without inventing rows', () => {
    const original = [row('a')]
    expect(() => patchCostRows(original, [], 'name', 'A')).toThrow('勾选')
    expect(() => fillCostRows(original, ['gone'], 'unitPrice')).toThrow('已变化')
    expect(() => pasteCostRows(original, 'A', 2, 'name')).toThrow('起始行')
    expect(() => pasteCostRows(original, 'A', 0, 'unitCost' as typeof costColumns[number])).toThrow('可录入')
    expect(original).toHaveLength(1)
  })
  it('translates validation rows, numeric fields and parser errors without translating saved data', () => {
    const language = currentLanguage.value, original = [row('a'), row('b')], before = JSON.stringify(original)
    try {
      setLanguage('es')
      expect(() => pasteCostRows(original, '总额\n1200')).toThrow('Fila 1: El encabezado Total')
      expect(() => pasteCostRows(original, 'unitPrice\n9\n=1+2')).toThrow('Fila 2: Precio unitario / total debe ser un número válido no negativo')
      expect(() => pasteCostRows(original, 'name\totherColumn\nA\tB')).toThrow('No se reconoce el encabezado «othercolumn»')
      expect(() => pasteCostRows(original, 'name\n"sin cierre')).toThrow('Las comillas del contenido pegado no están cerradas')
      expect(() => patchCostRows(original, ['a'], 'extra:reference', '=1+2', [{ id: 'reference', label: 'Referencia', type: 'number' }])).toThrow('La columna adicional «Referencia» requiere un número válido')
      expect(costColumnLabel('extra:reference', [{ id: 'reference', label: '包装原文', type: 'text' }])).toBe('包装原文')
      expect(JSON.stringify(original)).toBe(before)
    } finally { setLanguage(language) }
  })
  it.each(['', 'unitPrice', '单价／总额', '总额', '换算单件', '单件总成本（成品运输前）'])('rejects an empty or conflicting extra column name %s', label => {
    expect(() => validateCostExtraColumns([{ id: 'custom', label, type: 'text' }])).toThrow()
  })
  it('rejects duplicate extra names, stable identities and invalid types without changing definitions', () => {
    const columns: UnitCostExtraColumn[] = [{ id: 'a', label: '备注补充', type: 'text' }, { id: 'b', label: '备注补充', type: 'number' }], before = JSON.stringify(columns)
    expect(() => validateCostExtraColumns(columns)).toThrow('已有字段重复')
    expect(() => validateCostExtraColumns([{ ...columns[0] }, { ...columns[1], id: 'A', label: '其他字段' }])).toThrow('编号为空或重复')
    expect(() => validateCostExtraColumns([{ ...columns[0], type: 'formula' as 'text' }])).toThrow('文字或数值')
    expect(JSON.stringify(columns)).toBe(before)
  })
  it('pastes extra columns by renamed business label or stable identity without changing existing amounts', () => {
    const columns: UnitCostExtraColumn[] = [{ id: 'note', label: '包装信息', type: 'text' }, { id: 'metric', label: '对照数值', type: 'number' }]
    const original = [{ ...row('a'), extraValues: { note: '原记录', metric: 9 } }], before = JSON.stringify(original)
    const result = pasteCostRows(original, '对照数值\t包装信息\n-2.5\t"原文\n两行"', 0, 'category', columns)
    expect(result[0]).toMatchObject({ id: 'a', unitPrice: 8, extraValues: { note: '原文\n两行', metric: -2.5 } })
    const renamed = columns.map(column => column.id === 'note' ? { ...column, label: 'Supplier reference' } : column)
    expect(pasteCostRows(result, 'extra:note\n新说明', 0, 'category', renamed)[0].extraValues?.note).toBe('新说明')
    expect(costColumnLabel('extra:note', renamed)).toBe('Supplier reference')
    expect(JSON.stringify(original)).toBe(before)
  })
  it('keeps extra numeric null, zero and negative values separate from monetary computation', () => {
    const columns: UnitCostExtraColumn[] = [{ id: 'metric', label: '对照数值', type: 'number' }]
    const original = [row('a'), row('b'), row('c')], result = pasteCostRows(original, '对照数值\n待定\n0\n-12.5', 0, 'category', columns)
    expect(result.map(item => item.extraValues?.metric)).toEqual([null, 0, -12.5])
    expect(result.map(item => item.unitPrice)).toEqual([8, 8, 8])
    expect(result.every(item => item.unitCost == null)).toBe(true)
    expect(() => pasteCostRows(original, '对照数值\n2\n=1+2', 0, 'category', columns)).toThrow('不能填写计算公式')
    expect(original.every(item => item.extraValues == null)).toBe(true)
  })
  it('clones extra values for patch and fill so edits and undo snapshots never mutate the original rows', () => {
    const columns: UnitCostExtraColumn[] = [{ id: 'note', label: '补充说明', type: 'text' }]
    const original = [{ ...row('a'), extraValues: { note: '第一项' } }, { ...row('b'), extraValues: { note: '第二项' } }, { ...row('c'), extraValues: { note: '例外' } }], before = JSON.stringify(original)
    const filled = fillCostRows(original, ['a', 'b'], 'extra:note', columns)
    expect(filled.map(item => item.extraValues?.note)).toEqual(['第一项', '第一项', '例外'])
    expect(filled[0].extraValues).not.toBe(original[0].extraValues)
    expect(filled[2].extraValues).not.toBe(original[2].extraValues)
    expect(patchCostRows(original, ['a'], 'extra:note', null, columns)[0].extraValues?.note).toBeNull()
    expect(JSON.stringify(original)).toBe(before)
  })
  it('preserves hidden extra values and resolves a headerless range by the supplied column identities', () => {
    const columns: UnitCostExtraColumn[] = [{ id: 'note', label: '隐藏说明', type: 'text', hidden: true }, { id: 'metric', label: '对照数值', type: 'number' }]
    const original = [{ ...row('a'), extraValues: { note: '不删除', metric: 3 } }]
    const result = pasteCostRows(original, '\t0', 0, 'extra:note', columns)
    expect(result[0].extraValues).toEqual({ note: '不删除', metric: 0 })
    expect(patchCostRows(original, ['a'], 'extra:note', '=仅为原文', columns)[0].extraValues?.note).toBe('=仅为原文')
    expect(() => patchCostRows(original, ['a'], 'extra:missing', '错误', columns)).toThrow('可录入')
  })
  it('does not introduce an extra-column limit or silently drop later fields', () => {
    const columns: UnitCostExtraColumn[] = Array.from({ length: 250 }, (_, index) => ({ id: `c${index}`, label: `补充${index}`, type: 'number' }))
    const result = pasteCostRows([row('a')], columns.map(column => column.label).join('\t') + '\n' + columns.map(() => '0').join('\t'), 0, 'category', columns)
    expect(Object.keys(result[0].extraValues || {})).toHaveLength(250)
    expect(result[0].extraValues?.c249).toBe(0)
  })
})
