import { describe, expect, it } from 'vitest'
import { parsePhoneIntakeSource } from '@/utils/phoneModelIntake'
const identity = { supplierCode: 'SUP', sourceKind: 'selection', recordId: 3, rowId: 'stable-row', rowVersion: 1, recordVersion: 0 }
const entry = { supplierIndex: 21, componentText: '真实型号 5G', classification: 'review', reviewReason: 'variant', reason: '保留原表5G差异，需核对', brandId: 7 }
describe('single-style engineering intake file', () => {
  it('supports flat or nested references and whitelists decisions, not imported source facts or costs', () => { const parsed = parsePhoneIntakeSource(JSON.stringify({ identity, entries: [{ ...entry, rawStatus: '伪造现货', unitPrice: '99', password: 'secret' }], modelSheetFileDetails: [{ id: 99 }] })); expect(parsed).toEqual({ ...identity, entries: [entry] }); expect(parsePhoneIntakeSource(JSON.stringify({ ...identity, entries: [entry] }))).toEqual(parsed); expect(JSON.stringify(parsed)).not.toMatch(/secret|99|伪造现货/) })
  it('keeps 2501 slash fragments without guessing brand, phone code or missing classification', () => { const entries = Array.from({ length: 2501 }, (_, index) => ({ ...entry, supplierIndex: String(index + 1), componentText: 'G24 / G34 4G', brandId: null })); const parsed = parsePhoneIntakeSource(JSON.stringify({ ...identity, entries })); expect(parsed.entries).toHaveLength(2501); expect(parsed.entries[2500]).toEqual({ supplierIndex: '2501', componentText: 'G24 / G34 4G', classification: 'review', reviewReason: 'variant', reason: entry.reason }); expect(parsed.entries.some(value => 'phoneModelIds' in value)).toBe(false) })
  it.each([{ value: '{', text: 'JSON 文件格式不正确' }, { value: JSON.stringify({ ...identity, entries: [{ ...entry, componentText: '' }] }), text: '第 1 行' }, { value: JSON.stringify({ ...identity, entries: [{ ...entry, classification: 'auto' }] }), text: '确实缺档或需要核对' }, { value: JSON.stringify({ ...identity, entries: [{ ...entry, reviewReason: '' }] }), text: '具体核对类型' }, { value: JSON.stringify({ ...identity, entries: [{ ...entry, reason: '' }] }), text: '核对依据' }])('reports input errors without silently dropping rows: $text', ({ value, text }) => { expect(() => parsePhoneIntakeSource(value)).toThrow(text) })
  it('carries optional exact source locators unchanged while keeping legacy index-only files compatible',()=>{
    const originals=[{...entry,supplierIndex:'001',rawBrandGroup:' For HUAWEI ',rawModelText:'POCO X7 Pro\n原字串'},
      {...entry,supplierIndex:17,rawBrandGroup:'Samsung',rawModelText:'S26'},
      {...entry,supplierIndex:17,rawBrandGroup:'Samsung',rawModelText:'S26 Ultra'},
      {...entry,supplierIndex:'原无序号（Samsung栏111后第1行）',rawBrandGroup:'Samsung',rawModelText:'Z FOLD8'},
      {...entry,supplierIndex:'原无序号（Samsung栏111后第2行）',rawBrandGroup:'Samsung',rawModelText:'Z FOLD8 WIDE'}]
    const parsed=parsePhoneIntakeSource(JSON.stringify({...identity,entries:originals}))
    expect(parsed.entries).toEqual(originals)
    expect(parsePhoneIntakeSource(JSON.stringify({...identity,entries:[entry]})).entries[0]).toEqual(entry)
  })
  it('rejects non-text source locators instead of silently omitting a supplied selector',()=>{
    for(const invalid of [{rawBrandGroup:2},{rawModelText:null}])expect(()=>parsePhoneIntakeSource(JSON.stringify({...identity,entries:[{...entry,...invalid}]}))).toThrow('原条目定位须保留原文')
  })

  it('retains exact locator whitespace and line breaks while rejecting a whitespace-only index',()=>{
    const original={...entry,supplierIndex:' 001 ',rawBrandGroup:' Samsung ',rawModelText:'Z FOLD8\n原文 '}
    expect(parsePhoneIntakeSource(JSON.stringify({...identity,entries:[original]})).entries[0]).toEqual(original)
    expect(()=>parsePhoneIntakeSource(JSON.stringify({...identity,entries:[{...original,supplierIndex:'   '}]}))).toThrow('原序号')
  })

})
