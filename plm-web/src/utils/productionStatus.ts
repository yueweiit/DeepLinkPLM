import type {ProductionStatus} from '@/types/production'
export const productionStatusOptions:{value:ProductionStatus;label:string}[]=[{value:'unknown',label:'待确认'},{value:'active',label:'在用'},{value:'discontinued',label:'已停产'}]
export const productionStatusName=(value?:ProductionStatus)=>productionStatusOptions.find(s=>s.value===(value||'unknown'))?.label||'待确认'
export const productionStatusType=(value?:ProductionStatus)=>value==='discontinued'?'warning':value==='active'?'success':'info'
export const productionHint=(value?:ProductionStatus)=>value==='discontinued'?' · 已停产（仅提醒）':''
interface PhoneCode {codeValue?:string|null;phoneModelCode?:string|null;modelName?:string;codeName?:string;phoneModelId?:number;codeItemId?:number|null}
export function comparePhoneCodes(a:PhoneCode,b:PhoneCode){
  const numeric=(r:PhoneCode)=>{const c=r.codeValue??r.phoneModelCode;return c&&/^\d{4}$/.test(c)?Number(c):-1}
  return numeric(b)-numeric(a)||(a.modelName||a.codeName||'').localeCompare(b.modelName||b.codeName||'')||(a.phoneModelId||a.codeItemId||0)-(b.phoneModelId||b.codeItemId||0)
}
