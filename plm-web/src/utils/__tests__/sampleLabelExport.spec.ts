// @vitest-environment node
import {describe,expect,it} from 'vitest'
import ExcelJS from 'exceljs'
import {buildSampleLabelWorkbook} from '../sampleLabelExport'
import type {SampleLabel} from '@/types/sampleManagement'

const origin='https://plm.yueweiportal.com'
const sample=(id=71):SampleLabel=>({sampleIdentityId:id,sampleCode:`SY-${String(id).padStart(6,'0')}`,name:'拉丝手机壳',supplierName:'佛山莫凡科技',phoneName:'Apple iPhone 18 Pro Max',colorName:'黑色'})
async function read(samples:SampleLabel[]){const blob=await buildSampleLabelWorkbook(samples,origin);const book=new ExcelJS.Workbook();await book.xlsx.load(await blob.arrayBuffer());return {blob,book,sheet:book.worksheets[0]!}}

describe('selected sample label Excel export',()=>{
 it('exports one row per selected sample type with the seven requested fields and embedded PNGs in G',async()=>{
  const {blob,book,sheet}=await read([sample(),{...sample(72),productName:'护盾手机壳',colorName:'白色'}])
  expect(blob.type).toBe('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')
  expect(sheet.rowCount).toBe(3);expect(sheet.columnCount).toBe(7)
  expect(sheet.getColumn(1).width).toBeGreaterThanOrEqual(10)
  expect(sheet.getRow(1).values).toEqual([undefined,'序号','编码','手机壳名称','供应商','型号','颜色','二维码'])
  expect(Array.from({length:6},(_,i)=>sheet.getCell(2,i+1).value)).toEqual([1,'SY-000071','拉丝手机壳','佛山莫凡科技','Apple iPhone 18 Pro Max','黑色'])
  expect(Array.from({length:6},(_,i)=>sheet.getCell(3,i+1).value)).toEqual([2,'SY-000072','护盾手机壳','佛山莫凡科技','Apple iPhone 18 Pro Max','白色'])
  expect(sheet.getImages()).toHaveLength(2)
  for(const image of sheet.getImages()){
   expect(image.range.tl.nativeCol).toBe(6)
   const png=book.getImage(Number(image.imageId));expect(png.extension).toBe('png')
   expect(Buffer.from(png.buffer!).subarray(0,8)).toEqual(Buffer.from([137,80,78,71,13,10,26,10]))
  }
  expect(sheet.views[0]).toMatchObject({state:'frozen',ySplit:1})
  expect(sheet.autoFilter).toBe('A1:G3');expect(sheet.pageSetup).toMatchObject({orientation:'landscape',fitToWidth:1,fitToHeight:0,printTitlesRow:'1:1',printArea:'A1:G3'})
 })
 it('preserves leading zeroes, formula-looking names and complete long model text as literal cells',async()=>{
  const model='OPPO A40 / OPPO A60 · '.repeat(12)
  const {sheet}=await read([{...sample(),sampleCode:'000071',productName:'=HYPERLINK("https://example.com")',supplierName:'+供应商原名称',phoneName:model}])
  expect(sheet.getCell('B2').value).toBe('000071');expect(sheet.getCell('B2').type).toBe(ExcelJS.ValueType.String)
  expect(sheet.getCell('C2').value).toBe('=HYPERLINK("https://example.com")');expect(sheet.getCell('C2').type).toBe(ExcelJS.ValueType.String)
  expect(sheet.getCell('D2').value).toBe('+供应商原名称');expect(sheet.getCell('E2').value).toBe(model)
  expect(sheet.getRow(2).height).toBeGreaterThan(100);expect(sheet.getRow(2).height).toBeLessThanOrEqual(409)
 })
 it('deduplicates repeated selections by real identity without multiplying rows by stock quantities',async()=>{
  const a=sample();const {sheet}=await read([a,{...a},sample(72)])
  expect(sheet.rowCount).toBe(3);expect(sheet.getCell('B2').value).toBe(a.sampleCode);expect(sheet.getCell('B3').value).toBe('SY-000072')
 })
 it('does not invent a supplier or model and keeps the existing source fallbacks',async()=>{
  const {sheet}=await read([{sampleIdentityId:1,sampleCode:'SY-000001',name:'自研样品',specName:'实际规格'}])
  expect(sheet.getCell('C2').value).toBe('自研样品');expect(sheet.getCell('D2').value||'').toBe('');expect(sheet.getCell('E2').value).toBe('实际规格');expect(sheet.getCell('F2').value||'').toBe('')
 })
 it('refuses conflicting identity/code records rather than exporting a misleading label',async()=>{
  await expect(buildSampleLabelWorkbook([sample(),{...sample(),colorName:'白色'}],origin)).rejects.toThrow('资料不一致')
  await expect(buildSampleLabelWorkbook([sample(),{...sample(72),sampleCode:sample().sampleCode}],origin)).rejects.toThrow('多个身份')
 })
 it('requires selected real identities, an existing code and a valid scan origin',async()=>{
  await expect(buildSampleLabelWorkbook([],origin)).rejects.toThrow('先勾选')
  await expect(buildSampleLabelWorkbook([{...sample(),sampleIdentityId:0}],origin)).rejects.toThrow('身份不完整')
  await expect(buildSampleLabelWorkbook([{...sample(),sampleCode:''}],origin)).rejects.toThrow('编码不完整')
  await expect(buildSampleLabelWorkbook([sample()],'http://example.com')).rejects.toThrow('HTTPS')
 })
 it('rejects more than 200 distinct types without silently exporting only part of the selection',async()=>{
  await expect(buildSampleLabelWorkbook(Array.from({length:201},(_,i)=>sample(i+1)),origin)).rejects.toThrow('最多导出200种')
 })
 it('rejects text that cannot fit an Excel row instead of generating a clipped or invalid workbook',async()=>{
  await expect(buildSampleLabelWorkbook([{...sample(),phoneName:'过长型号'.repeat(300)}],origin)).rejects.toThrow('无法在一行Excel中完整显示')
 })
})
