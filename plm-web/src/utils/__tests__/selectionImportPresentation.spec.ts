import { describe, expect, it } from 'vitest'
import { selectionFileHistoryLabel, selectionFileStateLabel, selectionImportActionLabel, selectionImportDifferences, selectionImportPreviewFacts, selectionImportPriceFacts } from '../selectionImportPresentation'
import type { SelectionStagedRow } from '@/types/partnerSelections'

const row = (values: SelectionStagedRow['values'], original?: SelectionStagedRow['original']): SelectionStagedRow =>
  ({ rowId: 'hidden-row', rowNumber: 2, sourceSheet: '熊大选品', action: 'update', values, original, messages: [], version: 1 })

describe('selection import business presentation', () => {
  it('collapses source evidence even below the length threshold without hiding short business notes or changing their values', () => {
    const technical='原始来源：报价.xlsx；SHA256 '+ 'a'.repeat(64)
    const values={notes:technical,features:'磁吸',unitPrice:'12.00'}
    expect(selectionImportPreviewFacts(values).find(f=>f.key==='notes')).toEqual({key:'notes',label:'备注',text:technical,expand:true})
    expect(values.notes).toBe(technical)
    expect(selectionImportPreviewFacts({notes:'每色先寄两件'})[0]).toMatchObject({text:'每色先寄两件',expand:false})
  })

  it('does not expose unknown status keys or task identities', () => {
    expect(selectionFileStateLabel('private_failure_state')).toBe('准备中')
    expect(selectionImportActionLabel('unknown_action')).toBe('待检查')
    expect(selectionFileHistoryLabel({ id: 'private-uuid', kind: 'import', status: 'processing' }, 0)).toBe('导入记录 1 · 正在处理')
  })
  it('shows only changed business fields and preserves zero or empty values accurately', () => {
    expect(selectionImportDifferences(row({ name: '相同', sampleQty: 0, currency: '' }, { name: '相同', sampleQty: 2, currency: 'CNY' }))).toEqual([
      { key: 'currency', label: '币种', previous: 'CNY', next: '未填写' },
      { key: 'sampleQty', label: '拟拿样数量（行合计）', previous: '2', next: '0' }
    ])
  })
  it('ignores read-only identities, file JSON, and arbitrary technical fields', () => {
    expect(selectionImportDifferences(row({ rowId: 'new', phoneModelIds: [987], refs: [{ id: 7, type: 'Document' }] }, { rowId: 'old', phoneModelIds: [654], refs: [] }))).toEqual([])
    expect(selectionImportDifferences(row({ name: '新记录' }))).toEqual([])
    expect(selectionImportDifferences({ ...row({ name: '新记录', modelText: 'iPhone18', notes: '', rangeRefs: [] }, {}), action: 'new' })).toEqual([])
  })
  it('presents versioned range names instead of dumping nested objects', () => {
    expect(selectionImportDifferences(row({ rangeRefs: [{ type: 'model', reference: '苹果系列', version: 3 }] }, { rangeRefs: [] }))).toEqual([
      { key: 'rangeRefs', label: '引用范围', previous: '未引用范围', next: '型号：苹果系列（第3版）' }
    ])
  })
  it('does not invent differences between semantically empty fields while retaining actual clearing', () => {
    expect(selectionImportDifferences(row({ name: '', notes: '', rangeRefs: [] }, {}))).toEqual([])
    expect(selectionImportDifferences(row({ notes: '', rangeRefs: [] }, { notes: '原约定', rangeRefs: [{ type: 'color', reference: '熊大原色' }] }))).toEqual([
      { key: 'notes', label: '备注', previous: '原约定', next: '未填写' },
      { key: 'rangeRefs', label: '引用范围', previous: '颜色：熊大原色', next: '未引用范围' }
    ])
  })
  it('uses supplier names in business differences rather than exposing lookup codes', () => {
    expect(selectionImportDifferences(row({ supplierCode: 'SUP-B' }, { supplierCode: 'SUP-A' }), [{code:'SUP-A',name:'熊大'},{code:'SUP-B',name:'喜中喜'}])).toEqual([
      { key: 'supplierCode', label: '供应商', previous: '熊大', next: '喜中喜' }
    ])
  })
  it('presents unified source facts and marks long text for optional expansion without truncating the original',()=>{
    const notes='原件给出的型号范围与供货说明。'.repeat(20)
    const facts=selectionImportPreviewFacts({thickness:'1.5 mm',features:'旋转支架',leadTimeNote:'以本单约定为准',sampleFeeNote:'大货后按约抵扣',priceNote:'未注明币种',notes})
    expect(facts.map(fact=>fact.key)).toEqual(['thickness','features','leadTimeNote','sampleFeeNote','priceNote','notes'])
    expect(facts.find(fact=>fact.key==='notes')).toMatchObject({label:'备注',text:notes,expand:true})
    expect(facts.find(fact=>fact.key==='thickness')).toMatchObject({text:'1.5 mm',expand:false})
    expect(selectionImportPreviewFacts({features:'',notes:undefined})).toEqual([])
  })
  it('describes every quotation option difference with its original label, amount and actual currency condition',()=>{
    const changes=selectionImportDifferences(row({priceOptions:[{label:'裸壳',unitPrice:'2.40',currency:'CNY',moq:'每色20个'},{label:'OPP包装加价',unitPrice:'0.10',currency:'',packaging:'OPP袋',priceNote:'另计，不含裸壳'}]}, {priceOptions:[{label:'裸壳',unitPrice:'2.30',currency:'CNY'}]}))
    expect(changes).toEqual([{key:'priceOptions',label:'配置与报价选项',previous:'裸壳：2.30 CNY',next:'裸壳：2.40 CNY；起订条件：每色20个\nOPP包装加价：0.10 · 币种未注明；报价说明：另计，不含裸壳；包装：OPP袋'}])
    expect(changes[0].next).not.toContain('2.50')
    expect(changes[0].next).not.toMatch(/unitPrice|currency|priceOptions|\{/)
    expect(selectionImportPriceFacts({label:'原配置',unitPrice:'2.40',currency:'CNY',priceNote:'说明',moq:'20',packaging:'原包装'}).map(fact=>fact.key)).toEqual(['priceNote','moq','packaging'])
  })
  it('adds new facts to business differences while suppressing empty price arrays and unknown nested identities',()=>{
    expect(selectionImportDifferences(row({thickness:'1.5 mm',features:'原件功能',leadTimeNote:'本单确认',sampleFeeNote:'退款另议',priceOptions:[]},{thickness:'1.2 mm',features:'旧功能',leadTimeNote:'旧周期',sampleFeeNote:'旧约定'})).map(change=>change.key)).toEqual(['thickness','features','leadTimeNote','sampleFeeNote'])
    expect(selectionImportDifferences(row({priceOptions:[{label:'裸壳',unitPrice:'2.40',currency:'CNY',privateId:'do-not-display'}] as never},{priceOptions:[{label:'裸壳',unitPrice:'2.40',currency:'CNY'}]}))).toEqual([])
  })
  it('labels original-workbook page counts without claiming that every supplier sheet was processed', () => {
    const sheets = ['汇总', '蓝之碧选品', '熊大选品', '供方四', '供方五', '供方六', '供方七', '供方八'].map((name, index) => ({ name, index }))
    const label = selectionFileHistoryLabel({ id: 'private-job', kind: 'import', status: 'committed', sheets }, 0)
    expect(label).toBe('导入记录 1（原件含8页） · 已保存草稿')
    expect(label).not.toMatch(/汇总|蓝之碧|熊大|已导入8页|private-job/)
  })
})
