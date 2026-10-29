import request, { unwrapResponse, authorizedPreviewOptions } from '../request'
import { saveBlob } from './partner'
import type {PartnerFile} from '@/types/partner'
import type { SelectionConfirmation, SelectionConfirmationPreview, SelectionFileJob, SelectionFilter, SelectionMutation, SelectionPage, SelectionReceiptInput, SelectionReceiptPreview, SelectionReceiptResult, SelectionRow, SelectionRowPage, SelectionSave, SelectionSheet, SelectionStagedImage, SelectionStagedRow, SelectionUpload, SelectionTransition } from '@/types/partnerSelections'
const root='/partner-selections'
export const selectionPage=async(params:{keyword?:string;supplierCode?:string;purpose?:'my_selection'|'historical'|'all';page:number;size:number})=>unwrapResponse<SelectionPage>(await request.get(root,{params}))
export const selectionDetail=async(id:number)=>unwrapResponse<SelectionSheet>(await request.get(`${root}/${id}`))
export const selectionOriginals=async(id:number,page=1,size=20)=>unwrapResponse<{content:PartnerFile[];totalElements:number;page:number;size:number}>(await request.get(`${root}/${id}/originals`,{params:{page,size}}))
export const saveSelection=async(data:SelectionSave,id?:number)=>unwrapResponse<SelectionSheet>(await request({url:root+(id?`/${id}`:''),method:id?'PUT':'POST',data}))
export const selectionRows=async(id:number,params:SelectionFilter)=>unwrapResponse<SelectionRowPage>(await request.get(`${root}/${id}/rows`,{params}))
export const selectionRow=async(id:number,rowId:string)=>unwrapResponse<SelectionRow>(await request.get(`${root}/${id}/rows/${encodeURIComponent(rowId)}`))
export const patchSelectionRows=async(id:number,data:SelectionMutation)=>unwrapResponse<SelectionSheet>(await request.patch(`${root}/${id}/rows`,data))
export const batchSelectionRows=async(id:number,data:{version:number;requestId:string;rowIds?:string[];filterSnapshot?:Record<string,unknown>;changes:Partial<SelectionRow>})=>unwrapResponse<SelectionSheet>(await request.post(`${root}/${id}/batch`,data))
export const previewSelectionConfirmation=async(id:number,data:SelectionConfirmation)=>unwrapResponse<SelectionConfirmationPreview>(await request.post(`${root}/${id}/confirmation-preview`,data))
export const confirmSelection=async(id:number,data:SelectionConfirmation)=>unwrapResponse<SelectionSheet>(await request.post(`${root}/${id}/confirm`,data))
export const previewSelectionReceipts=async(id:number,data:SelectionReceiptInput)=>unwrapResponse<SelectionReceiptPreview>(await request.post(`${root}/${id}/sample-receipts/preview`,data))
export const saveSelectionReceipts=async(id:number,data:SelectionReceiptInput)=>unwrapResponse<SelectionReceiptResult>(await request.post(`${root}/${id}/sample-receipts`,data))
export const transitionSelectionRows=async(id:number,data:SelectionTransition)=>unwrapResponse<SelectionSheet>(await request.post(`${root}/${id}/rows/transition`,data))
// One file contract serves source records and historical selections; ownership stays server-checked.
export type PartnerFileContext='selection'|'catalog'
function createFileClient(base:string,label:string){return {
 createSelectionUpload:async(id:number,data:{fileName:string;fileSize:number;requestId:string;purpose:string})=>unwrapResponse<SelectionUpload>(await request.post(`${base}/${id}/uploads`,data)),
 getSelectionUpload:async(id:number,uploadId:string)=>unwrapResponse<SelectionUpload>(await request.get(`${base}/${id}/uploads/${uploadId}`)),
 putSelectionUploadPart:async(id:number,uploadId:string,index:number,blob:Blob,sha256:string)=>unwrapResponse<SelectionUpload>(await request.put(`${base}/${id}/uploads/${uploadId}/parts/${index}`,blob,{headers:{'Content-Type':'application/octet-stream','X-Part-SHA256':sha256},timeout:120000})),
 completeSelectionUpload:async(id:number,uploadId:string)=>unwrapResponse<SelectionUpload>(await request.post(`${base}/${id}/uploads/${uploadId}/complete`,{})),
 createSelectionImportJob:async(id:number,attachmentId:number,requestId:string)=>unwrapResponse<SelectionFileJob>(await request.post(`${base}/${id}/import-jobs`,{attachmentId,requestId})),
 getSelectionImportJob:async(id:number,jobId:string)=>unwrapResponse<SelectionFileJob>(await request.get(`${base}/${id}/import-jobs/${jobId}`)),
 selectionFileJobs:async(id:number)=>unwrapResponse<{content:SelectionFileJob[]}>(await request.get(`${base}/${id}/file-jobs`)),
 retrySelectionFileJob:async(id:number,kind:'import'|'export',jobId:string)=>unwrapResponse<SelectionFileJob>(await request.post(`${base}/${id}/${kind}-jobs/${jobId}/retry`)),
 parseSelectionImportJob:async(id:number,jobId:string,data:{sheets:{name:string;supplierCode:string;supplierConfirmed?:boolean}[];requestId:string})=>unwrapResponse<SelectionFileJob>(await request.post(`${base}/${id}/import-jobs/${jobId}/parse`,data)),
 selectionImportJobRows:async(id:number,jobId:string,page:number,size=20)=>unwrapResponse<{content:SelectionStagedRow[];totalElements:number;page:number;size:number}>(await request.get(`${base}/${id}/import-jobs/${jobId}/rows`,{params:{page,size}})),
 selectionImportImage:async(id:number,jobId:string,imageId:string,options:{signal?:AbortSignal}={})=>(await request.get(`${base}/${id}/import-jobs/${jobId}/images/${encodeURIComponent(imageId)}`,authorizedPreviewOptions(options))).data as Blob,
 selectionImportImages:async(id:number,jobId:string,params:{page:number;size:number;unassignedOnly?:boolean})=>unwrapResponse<{content:SelectionStagedImage[];totalElements:number;page:number;size:number}>(await request.get(`${base}/${id}/import-jobs/${jobId}/images`,{params})),
 assignSelectionImportImage:async(id:number,jobId:string,imageId:string,rowId:string)=>unwrapResponse<SelectionFileJob>(await request.patch(`${base}/${id}/import-jobs/${jobId}/images/${encodeURIComponent(imageId)}`,{rowId})),
 updateSelectionImportRow:async(id:number,jobId:string,row:SelectionStagedRow,values:Partial<SelectionRow>)=>unwrapResponse<SelectionStagedRow>(await request.patch(`${base}/${id}/import-jobs/${jobId}/rows/${encodeURIComponent(row.rowId)}`,{version:row.version,values})),
 commitSelectionImportJob:async(id:number,jobId:string,requestId:string)=>unwrapResponse<SelectionSheet>(await request.post(`${base}/${id}/import-jobs/${jobId}/commit`,{requestId})),
 createSelectionExportJob:async(id:number,data:{requestId:string;supplierCode?:string})=>unwrapResponse<SelectionFileJob>(await request.post(`${base}/${id}/export-jobs`,data)),
 getSelectionExportJob:async(id:number,jobId:string)=>unwrapResponse<SelectionFileJob>(await request.get(`${base}/${id}/export-jobs/${jobId}`)),
 downloadSelectionExportJob:async(id:number,jobId:string)=>{const response=await request.get(`${base}/${id}/export-jobs/${jobId}/download`,{responseType:'blob',timeout:120000});saveBlob(response.data,`${label}-${id}.xlsx`)}
}}
const selectionFileClient=createFileClient(root,'选品表')
export const catalogSourceFileClient=createFileClient('/partner-catalog/sources','供应商资料')
// Keep old selection consumers on the same implementation until their historical URLs retire.
export const {createSelectionUpload,getSelectionUpload,putSelectionUploadPart,completeSelectionUpload,createSelectionImportJob,getSelectionImportJob,selectionFileJobs,retrySelectionFileJob,parseSelectionImportJob,selectionImportJobRows,selectionImportImage,selectionImportImages,assignSelectionImportImage,updateSelectionImportRow,commitSelectionImportJob,createSelectionExportJob,getSelectionExportJob,downloadSelectionExportJob}=selectionFileClient
