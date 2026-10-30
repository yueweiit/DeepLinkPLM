import type { SelectionRow } from '@/types/partnerSelections'
import type { PartnerFile } from '@/types/partner'
export const developmentLabels={unknown:'开发先后待核对',claimed:'供方声明',preferred:'工程确认优先',alternative:'其他供方',disputed:'存在争议'} as const
export const cloneSelection=<T,>(value:T):T=>JSON.parse(JSON.stringify(value))
export const frozenSelectionRow=(row:SelectionRow)=>!!(row.confirmed||row.confirmedAt||row.productId||row.specId)
export const restrictedSelectionWriteFields=['unitPrice','currency','priceNote','priceOptions','sampleFeeNote','terms','priceUnit','rawQuoteFields','currencySource','sourceAttachmentId','pairings'] as const
/** Row refs alone define adoption; recent sheet files are only authorized metadata. */
export function selectionRowFiles(row:SelectionRow,availableFiles:PartnerFile[]=[]){const files=new Map([...availableFiles,...(row.fileDetails||[])].map(file=>[file.id,file]));return row.refs.map(ref=>({ref,file:files.get(ref.id)}))}
export function newSelectionRow():SelectionRow{return {rowId:crypto.randomUUID(),rowVersion:0,selected:false,name:'',series:'',supplierCode:'',supplierItemCode:'',material:'',specification:'',color:'',modelText:'',phoneModelIds:[],availablePhoneModelIds:[],unavailablePhoneModelIds:[],supplyScopeNote:'',currency:'',priceNote:'',moq:'',packaging:'',notes:'',sourcePosition:'',sameStyleKey:'',developmentStatus:'unknown',developedOn:'',developmentEvidence:'',refs:[],rangeRefs:[],pairings:[],selectedPairs:[]}}
export function changeSelectionSupplier(row:SelectionRow,code:string){if(row.supplierCode===code)return;row.supplierCode=code;row.availablePhoneModelIds=[];row.unavailablePhoneModelIds=[];row.availablePhones=[];row.unavailablePhones=[];row.supplyScopeNote='';row.rangeRefs=[];row.rangeSnapshots=[];row.rangeVersionAcknowledgements=[];row.targetColorMappingAcknowledged=false;row.pairings=[];row.selectedPairs=[];row.supplierColorIds=[];row.newSupplierColors=[]}
export const pasteColumns=['name','series','supplierCode','supplierItemCode','material','specification','color','modelText','unitPrice','currency','priceNote','moq','packaging','sampleQty','notes','sourcePosition','priceUnit','moqQuantity','moqUnit','moqScope','moqNote'] as const
export type PasteColumn=typeof pasteColumns[number]
export const pasteLabels:Record<PasteColumn,string>={name:'款式名称',series:'系列',supplierCode:'供应商编码',supplierItemCode:'供方款号',material:'材质',specification:'规格',color:'颜色',modelText:'型号原文',unitPrice:'单价',currency:'币种',priceNote:'报价说明',moq:'起订条件',packaging:'包装',sampleQty:'拟拿样数量',notes:'备注',sourcePosition:'来源位置',priceUnit:'计价单位',moqQuantity:'起订数量',moqUnit:'起订单位',moqScope:'起订口径',moqNote:'起订补充'}
/** Excel quoted TSV, including embedded newlines. This never parses or allocates phone/SKU identities. */
export function parseSelectionTsv(text:string,delimiter='\t'):string[][] {
  const rows:string[][]=[];let row:string[]=[],cell='',quoted=false
  for(let i=0;i<text.length;i++){const ch=text[i];if(ch==='"'&&(quoted||!cell)){if(quoted&&text[i+1]==='"'){cell+='"';i++}else quoted=!quoted}else if(!quoted&&(ch===delimiter||ch==='\n'||ch==='\r')){row.push(cell);cell='';if(ch!==delimiter){rows.push(row);row=[];if(ch==='\r'&&text[i+1]==='\n')i++}}else cell+=ch}
  if(quoted)throw new Error('粘贴内容中的双引号未闭合，请核对 Excel 单元格。')
  row.push(cell);rows.push(row);return rows.filter(values=>values.some(value=>value.trim()))
}
export function pasteSelectionRows(text:string):SelectionRow[]{let values=parseSelectionTsv(text);if(!values.length)return [];let columns:readonly PasteColumn[]=pasteColumns
  const header=values[0].map(v=>Object.entries(pasteLabels).find(([,label])=>label===v.trim())?.[0] as PasteColumn|undefined)
  if(header.every(Boolean)){columns=header as PasteColumn[];if(new Set(columns).size!==columns.length)throw new Error('表头存在重复列。');values=values.slice(1)}
  return values.map((cells,index)=>{if(cells.length>columns.length)throw new Error(`第 ${index+1} 行列数超出粘贴字段范围。`);const row=newSelectionRow();cells.forEach((value,n)=>{const key=columns[n];if(key==='sampleQty'){if(value.trim()){const quantity=Number(value);if(!Number.isSafeInteger(quantity)||quantity<0)throw new Error(`第 ${index+1} 行拟拿样数量应为非负整数。`);row.sampleQty=quantity}}else row[key]=value});return row})
}
/** Only explicitly confirmed pairs are supply facts; range cross-products are never inferred. */
export function pairCoverage(row:SelectionRow){return row.selectedPairs.map(pair=>({pair,record:row.pairings.find(item=>item.phoneModelId===pair.phoneModelId&&item.colorId===pair.colorId)})).reduce((counts,item)=>{counts[item.record?.state||'unverified']++;return counts},{available:0,unavailable:0,unverified:0})}
export const fillableColumns=['name','series','material','specification','modelText','color','moq','packaging','notes','sampleQty'] as const
export function rectangularChanges(text:string,start:string){const startIndex=fillableColumns.indexOf(start as typeof fillableColumns[number]);if(startIndex<0)throw new Error('请选择可填充的起始字段。');return parseSelectionTsv(text).map((cells,index)=>{if(cells.length+startIndex>fillableColumns.length)throw new Error(`第 ${index+1} 行超出当前粘贴字段。`);const changes:Partial<SelectionRow>={};cells.forEach((value,offset)=>{const key=fillableColumns[startIndex+offset];if(!value.trim())return;if(key==='sampleQty'){const quantity=Number(value);if(!Number.isSafeInteger(quantity)||quantity<0)throw new Error('拟拿样数量应为非负整数。');changes.sampleQty=quantity}else changes[key]=value});return changes})}
