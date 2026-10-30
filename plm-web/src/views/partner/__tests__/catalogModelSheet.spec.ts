import { describe, expect, it } from 'vitest'
import { catalogModelIndexDisplay, catalogModelEntryInput, catalogModelEntryKey, catalogPhoneProgress, modelEntryMatches, modelSheetIssues, parseCatalogModelSheet } from '../catalog/catalogModelSheet'
import type { CatalogModelEntry } from '@/types/partnerCatalog'

const entry = ():CatalogModelEntry => ({supplierIndex:'001',rawBrandGroup:'OPPO',rawModelText:'原型号A/B',rawStatus:'开模中',rawNote:'原备注',phoneModelIds:[11,12],phones:[]})
describe('supplier model-sheet column parsing without another matcher',()=>{
  it('preserves slash names, leading index zeros, quoted line breaks and source states without allocating or inferring identities',()=>{
    const text='原序号\t品牌分组\t原型号\t原状态\t原备注\t匹配用名称\t匹配说明\r\n001\tOPPO\t"型号A/型号B\n4G/5G"\t试模\t"原备注\t双引号""保留"""\t已有组合型号\t按原图核对'
    expect(parseCatalogModelSheet(text)).toEqual([{supplierIndex:'001',rawBrandGroup:'OPPO',rawModelText:'型号A/型号B\n4G/5G',rawStatus:'试模',rawNote:'原备注\t双引号"保留"',matchText:'已有组合型号',matchNote:'按原图核对'}])
    expect(parseCatalogModelSheet('02\t\t未知型号\t\t')).toEqual([{supplierIndex:'02',rawBrandGroup:'',rawModelText:'未知型号',rawStatus:'',rawNote:''}])
  })
  it.each(['1\tOPPO\t型号A\t现货','1\tOPPO\t型号A\t现货\t说明\t匹配\t依据\t多余列','\tOPPO\t型号A\t现货\t说明','1\tOPPO\t\t现货\t说明','原序号\t原型号\t品牌分组\t原状态\t原备注','1\tOPPO\t"未闭合\t现货\t说明'])('rejects incomplete or ambiguous column contracts instead of silently dropping data: %s',text=>{expect(()=>parseCatalogModelSheet(text)).toThrow()})
  it('supports more than 2000 source entries without truncating or turning them into styles',()=>{const rows=parseCatalogModelSheet(Array.from({length:2501},(_,i)=>`${i+1}\t分组\t原型号${i}\t现货\t`).join('\n'));expect(rows).toHaveLength(2501);expect(rows[2500].supplierIndex).toBe('2501');expect(rows.every(row=>row.phoneModelIds===undefined)).toBe(true)})
  it('passes only whitelisted input fields and clones explicit manual IDs without copying phone projections',()=>{const original={...entry(),matchText:'已有型号',matchNote:'已核对',internalCost:999};const input=catalogModelEntryInput(original);expect(input).toEqual({supplierIndex:'001',rawBrandGroup:'OPPO',rawModelText:'原型号A/B',rawStatus:'开模中',rawNote:'原备注',phoneModelIds:[11,12],matchText:'已有型号',matchNote:'已核对'});input.phoneModelIds!.push(13);expect(original.phoneModelIds).toEqual([11,12])})
  it('searches supplied model data and codes while preserving raw progress separately from match issues',()=>{const value={...entry(),phones:[{phoneModelId:11,modelName:'现有机型',phoneModelCode:'0011'}] as CatalogModelEntry['phones'],issues:['人工核对'],automaticIssues:['原名未匹配','人工核对']};expect(modelEntryMatches(value,'0011')).toBe(true);expect(modelEntryMatches(value,'开模中')).toBe(true);expect(modelEntryMatches(value,'不存在')).toBe(false);const provenance={...value,rawModelText:'Galaxy S24',matchText:'',rawNote:'原图SHA a3cd4cf23a79117a63982f1186c8d11bd76291623d82fbb0624309ca008b9650'};expect(modelEntryMatches(provenance,'17')).toBe(false);expect(modelEntryMatches(provenance,'Galaxy S24')).toBe(true);expect(catalogModelEntryInput(provenance).rawNote).toBe(provenance.rawNote);expect(modelSheetIssues(value)).toEqual(['人工核对','原名未匹配']);expect(catalogPhoneProgress({supplierModelEntries:[value]},11)).toEqual(['开模中 · 原备注（原表 001）']);expect(catalogPhoneProgress({supplierModelEntries:[value]},999)).toEqual([])})
  it('identifies an original by index text, source brand and raw model without collapsing zeros or unnumbered positions',()=>{
    const original=entry();const key=catalogModelEntryKey(original)
    expect(catalogModelEntryKey({...original,supplierIndex:1})).toBe(catalogModelEntryKey({...original,supplierIndex:'1'}))
    expect(key).not.toBe(catalogModelEntryKey({...original,supplierIndex:'1'}))
    expect(key).not.toBe(catalogModelEntryKey({...original,supplierIndex:' 001 '}))
    expect(key).not.toBe(catalogModelEntryKey({...original,rawBrandGroup:' OPPO '}))
    expect(key).not.toBe(catalogModelEntryKey({...original,rawModelText:'原型号A/B '}))
    expect(key).not.toBe(catalogModelEntryKey({...original,rawBrandGroup:'Samsung'}))
    expect(key).not.toBe(catalogModelEntryKey({...original,rawModelText:'另一原型号'}))
    const first={...original,supplierIndex:'原无序号（Samsung栏111后第1行）'},second={...first,supplierIndex:'原无序号（Samsung栏111后第2行）'}
    expect(catalogModelEntryKey(first)).not.toBe(catalogModelEntryKey(second))
    expect(original.supplierIndex).toBe('001')
  })

  it('shortens only explicit unnumbered source-position labels for display and retains their full original',()=>{
    const labels=['原无序号（Samsung栏111后第1行）','原无序号（Samsung栏111后第2行）']
    expect(labels.map(catalogModelIndexDisplay)).toEqual(labels.map(sourceLocation=>({label:'—',sourceLocation})))
    for(const label of ['001','A-01','未编号（其它原号）','原无序号'])expect(catalogModelIndexDisplay(label)).toEqual({label,sourceLocation:''})
    expect(labels[0]).toBe('原无序号（Samsung栏111后第1行）')
  })

})
