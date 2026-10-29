import type {SampleLabel} from '@/types/sampleManagement'
import {t} from '@/locales'
import {MAX_LABEL_COPIES,sampleQrPngDataUrl,sampleScanUrl} from './sampleLabels'

const HEADERS=['序号','编码','手机壳名称','供应商','型号','颜色','二维码']
const WIDTHS=[10,18,26,30,36,18,20]
const MIME='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
type ExportLabel={id:number;code:string;product:string;supplier:string;phone:string;color:string;version:string}
type PreparedExportLabel=ExportLabel&{height:number}
const text=(value:unknown)=>value==null?'':String(value)
function exportLabels(samples:SampleLabel[],origin:string):PreparedExportLabel[]{
 if(!samples.length)throw new Error('请先勾选要导出标签的样品')
 const identities=new Map<number,ExportLabel>(),codes=new Map<string,number>()
 for(const sample of samples){
  if(!Number.isSafeInteger(sample.sampleIdentityId)||sample.sampleIdentityId<=0)throw new Error('样品身份不完整，请刷新后重新勾选')
  if(typeof sample.sampleCode!=='string'||!sample.sampleCode.trim()||/[\u0000-\u001f\u007f]/.test(sample.sampleCode))throw new Error('样品编码不完整，请刷新后重新勾选')
  const row={id:sample.sampleIdentityId,code:sample.sampleCode.trim(),product:text(sample.productName||sample.name),supplier:text(sample.supplierName),phone:text(sample.phoneName||sample.specName),color:text(sample.colorName),version:text(sample.sampleVersion)}
  if([row.code,row.product,row.supplier,row.phone,row.color].some(value=>value.length>32767))throw new Error('样品标签文字超过Excel单元格长度限制，请先核对资料')
  const original=identities.get(row.id),codeOwner=codes.get(row.code)
  if(codeOwner!==undefined&&codeOwner!==row.id)throw new Error(`样品编码 ${row.code} 对应多个身份，请先核对资料`)
  if(original&&JSON.stringify(original)!==JSON.stringify(row))throw new Error(`样品 ${row.code} 的编码或标签资料不一致，请刷新后重新勾选`)
  if(!original){identities.set(row.id,row);codes.set(row.code,row.id)}
 }
 if(identities.size>MAX_LABEL_COPIES)throw new Error(`单次最多导出${MAX_LABEL_COPIES}种样品标签，请减少勾选`)
 const rows=[...identities.values()]
 for(const row of rows)sampleScanUrl(row.code,origin)
 return rows.map(row=>{
  const height=Math.max(100,...[row.code,row.product,row.supplier,row.phone,row.color].map((value,column)=>lineCount(value,WIDTHS[column+1]!)*14+12))
  if(height>409)throw new Error(`样品 ${row.code} 的标签文字过长，无法在一行Excel中完整显示，请核对名称或型号`)
  return {...row,height}
 })
}
function lineCount(value:string,width:number){return value.split(/\r\n|\r|\n/).reduce((count,line)=>count+Math.max(1,Math.ceil([...line].reduce((n,char)=>n+(/[\u0000-\u00ff]/.test(char)?1:2),0)/Math.max(1,width-2))),0)}

export async function buildSampleLabelWorkbook(samples:SampleLabel[],origin:string):Promise<Blob>{
 const labels=exportLabels(samples,origin)
 const {default:ExcelJS}=await import('exceljs')
 const workbook=new ExcelJS.Workbook()
 workbook.creator=t('common.appName')
 const sheet=workbook.addWorksheet('样品标签',{views:[{state:'frozen',ySplit:1,activeCell:'A2'}],pageSetup:{orientation:'landscape',paperSize:9,fitToPage:true,fitToWidth:1,fitToHeight:0,margins:{left:.25,right:.25,top:.35,bottom:.35,header:.2,footer:.2}}})
 sheet.columns=WIDTHS.map(width=>({width}))
 const header=sheet.addRow(HEADERS)
 header.height=24
 header.eachCell(cell=>{cell.font={name:'Microsoft YaHei',size:10,bold:true,color:{argb:'FF243447'}};cell.fill={type:'pattern',pattern:'solid',fgColor:{argb:'FFF0F3F6'}};cell.alignment={horizontal:'center',vertical:'middle',wrapText:true};cell.border={bottom:{style:'thin',color:{argb:'FFD7DFE8'}}}})
 for(const [index,label] of labels.entries()){
  const values=[String(index+1),label.code,label.product,label.supplier,label.phone,label.color,'']
  const row=sheet.addRow([index+1,...values.slice(1)])
  row.height=label.height
  row.eachCell({includeEmpty:true},(cell,column)=>{cell.font={name:'Microsoft YaHei',size:10,color:{argb:'FF243447'}};cell.alignment={horizontal:column===1?'center':'left',vertical:'middle',wrapText:true};cell.border={bottom:{style:'hair',color:{argb:'FFE2E7ED'}}};if(column>1)cell.numFmt='@'})
  const image=workbook.addImage({base64:await sampleQrPngDataUrl(label.code,origin),extension:'png'})
  sheet.addImage(image,{tl:{col:6.1,row:row.number-1+.05},ext:{width:110,height:110},editAs:'oneCell'})
 }
 sheet.autoFilter={from:'A1',to:`G${labels.length+1}`}
 sheet.pageSetup.printTitlesRow='1:1'
 sheet.pageSetup.printArea=`A1:G${labels.length+1}`
 const bytes=new Uint8Array(await workbook.xlsx.writeBuffer())
 return new Blob([bytes.buffer],{type:MIME})
}
