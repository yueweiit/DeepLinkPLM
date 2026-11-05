import ExcelJS from 'exceljs'
import type {SourceOutlay} from '@/api/modules/productResearch'
const plain=(value:ExcelJS.CellValue):string=>value==null?'':typeof value==='object'?'richText'in value?value.richText.map(p=>p.text).join(''):'text'in value?String(value.text):'result'in value?String(value.result??''):String(value):String(value)
function number(value:ExcelJS.CellValue,label:string):number|null{
 if(value==null||plain(value).trim()==='')return null
 const raw=typeof value==='object'&&'result'in value?value.result:value
 if(typeof raw!=='number'||!Number.isFinite(raw)||raw<0)throw Error(`${label}不是有效的非负数字`)
 return raw
}
/** Read locally and adopt the existing project attachment; no second upload or ledger. */
export async function readSourceOutlay(bytes:ArrayBuffer,fileName:string,currency:string):Promise<SourceOutlay>{
 const workbook=new ExcelJS.Workbook();await workbook.xlsx.load(bytes);const matches:SourceOutlay[]=[]
 for(const sheet of workbook.worksheets){
  for(let rowNo=1;rowNo<=Math.min(sheet.rowCount,10);rowNo++){
   const columns=new Map<string,number>();sheet.getRow(rowNo).eachCell((cell,index)=>columns.set(plain(cell.value).replace(/\s/g,''),index))
   if(!['明细','单价','数量','运输+清关费用'].every(name=>columns.has(name)))continue
   const rows:SourceOutlay['rows']=[]
   for(let index=rowNo+1;index<=sheet.rowCount;index++){
    const row=sheet.getRow(index),nameCell=row.getCell(columns.get('明细')!),name=plain(nameCell.value).trim();if(!name)continue
    const price=row.getCell(columns.get('单价')!),quantity=row.getCell(columns.get('数量')!),transport=row.getCell(columns.get('运输+清关费用')!)
    if(nameCell.isMerged&&nameCell.master.col<nameCell.col&&[price,quantity,transport].some(cell=>cell.isMerged&&cell.master.address===nameCell.master.address))continue
    if([price,quantity,transport].every(c=>plain(c.value).trim()===''))continue
    if(rows.length>=200)throw Error('前期投入估算最多200项')
    rows.push({name,unitPrice:number(price.value,`${name}单价`),quantity:number(quantity.value,`${name}数量`),transportAmount:number(transport.value,`${name}运输及清关`),sourceCell:`${price.address}:${transport.address}`,notes:columns.has('备注')?plain(row.getCell(columns.get('备注')!).value):''})
   }
   if(!rows.length)throw Error('原表没有可读取的前期投入明细')
   matches.push({currency,scope:plain(sheet.getCell('A1').value),sourceFileName:fileName,sourceSheet:sheet.name,sourceHash:Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))).map(b=>b.toString(16).padStart(2,'0')).join(''),rows});break
  }
 }
 if(matches.length!==1)throw Error(matches.length?'原文件包含多份前期投入表，请分别核对后采用':'未找到包含明细、单价、数量及运输＋清关费用的原表')
 return matches[0]
}
