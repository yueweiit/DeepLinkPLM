import request,{unwrapResponse,authorizedPreviewOptions} from '../request'
import type {BusinessData,PartnerKind,PartnerOptions,PartnerPage,PartnerRow,ImportPreview} from '@/types/partner'
export const newRequestId=()=>crypto.randomUUID()
export async function partnerPage(kind:PartnerKind,params:BusinessData={}){return unwrapResponse<PartnerPage>(await request.get('/'+kind,{params}))}
export async function partnerDetail(kind:PartnerKind,id:number){return unwrapResponse<PartnerRow>(await request.get('/'+kind+'/'+id))}
export async function partnerSave(kind:PartnerKind,data:BusinessData,id?:number){return unwrapResponse<PartnerRow>(await request({url:'/'+kind+(id?'/'+id:''),method:id?'PUT':'POST',data}))}
export async function deletePartnerProduct(id:number,data:{version:number;requestId:string}){return unwrapResponse<{id:number;deleted:boolean}>(await request.delete('/partner-products/'+id,{data}))}
export async function deletePartnerSpec(id:number,specId:number,data:{version:number;requestId:string}){return unwrapResponse<PartnerRow>(await request.delete('/partner-products/'+id+'/specs/'+specId,{data}))}
export async function partnerAction(kind:PartnerKind,id:number,action:string,data:BusinessData){return unwrapResponse<PartnerRow>(await request.post('/'+kind+'/'+id+'/actions/'+action,data))}
export async function partnerOptions(){return unwrapResponse<PartnerOptions>(await request.get('/partner-options'))}
export async function sampleSources(projectId?:number){return unwrapResponse<BusinessData>(await request.get('/samples/sources',{params:{projectId}}))}
export async function sampleMovements(params:BusinessData){return unwrapResponse<PartnerPage>(await request.get('/samples/movements',{params}))}
export async function partnerRelations(type:'customers'|'suppliers',id:string|number){return unwrapResponse<BusinessData>(await request.get(type==='customers'?'/customers/'+id+'/business':'/inventories/suppliers/'+id+'/business'))}
export async function uploadPartnerFile(kind:PartnerKind|'partner-selections'|'sample-shipments',id:number,file:File,fields:BusinessData){const data=new FormData();data.append('file',file);Object.entries(fields).forEach(([k,v])=>{if(v!==undefined&&v!==null)data.append(k,String(v))});return unwrapResponse<BusinessData>(await request.post('/'+kind+'/'+id+'/files',data))}
export async function importPreview(kind:PartnerKind,file:File){const body=new FormData();body.append('file',file);return unwrapResponse<ImportPreview>(await request.post('/'+kind+'/import/preview',body))}
export async function importMatches(kind:PartnerKind,token:string,rows:BusinessData[]){return unwrapResponse<ImportPreview>(await request.put('/'+kind+'/import/'+token+'/matches',rows))}
export async function importCommit(kind:PartnerKind,token:string){return unwrapResponse<{saved:number;skipped:number}>(await request.post('/'+kind+'/import/'+token+'/commit'))}
export async function templateDownload(kind:PartnerKind,format:'csv'|'xlsx'){const res=await request.get('/'+kind+'/import/template',{params:{format},responseType:'blob'});saveBlob(res.data,kind+'-template.'+format)}
export function saveBlob(blob:Blob,name:string){const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),5000)}
export async function fileBlob(id:number,preview=true,options:{signal?:AbortSignal}={}){return (await request.get('/attachments/'+id+(preview?'/preview':'/download'),preview?authorizedPreviewOptions(options):{responseType:'blob',...options})).data as Blob}
