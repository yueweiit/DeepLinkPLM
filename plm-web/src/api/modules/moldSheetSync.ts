import request, { unwrapResponse } from '../request'
const root='/mold-schedules/sheet-sync'
export interface SheetSyncRow { sourceKey:string;rowNo:number;style:string;model:string;state:string;message?:string;moldSetId?:number;candidates:number[];changedFields?:string[];protectedFields?:string[];sourceDates:Record<string,string> }
export interface SheetSyncStatus {sourceUrl:string;automatic:boolean;intervalMinutes:number;version:number;initializedAt:string|null;lastSuccessAt:string|null;lastError:string|null;running:boolean;job?:{run_id:string;status:string;started_at:string;finished_at:string|null;source_hash:string|null;result:{sourceRows?:number;matched?:number;needsReview?:number;rows?:SheetSyncRow[];message?:string}}}
export interface SheetSyncFields {linked:boolean;source_key?:string;source_row_no?:number;source_notes?:string;version_no?:number;identity?:{style:string;brand:string;model:string};fields?:Record<string,{manual:boolean;currentValue?:string|null;sourceValue?:string;appliedValue?:string|null;manualValue?:string|null;manualAt?:string}>}
export async function getMoldSheetSync():Promise<SheetSyncStatus>{return unwrapResponse(await request.get(root))}
export async function updateMoldSheet():Promise<SheetSyncStatus>{return unwrapResponse(await request.post(root))}
export async function saveMoldSheetSettings(input:{version:number;automatic:boolean;intervalMinutes:number}):Promise<SheetSyncStatus>{return unwrapResponse(await request.put(`${root}/settings`,input))}
export async function getMoldSheetFields(id:number):Promise<SheetSyncFields>{return unwrapResponse(await request.get(`${root}/fields/${id}`))}
export async function resumeMoldSheetFields(id:number,input:{version:number;fields:string[];reason:string}):Promise<SheetSyncFields>{return unwrapResponse(await request.post(`${root}/fields/${id}/resume`,input))}
export async function bindMoldSheet(input:{sourceKey:string;moldSetId:number;reason:string}):Promise<SheetSyncStatus>{return unwrapResponse(await request.post(`${root}/bindings`,input))}
