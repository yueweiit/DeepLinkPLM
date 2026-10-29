import {computed,ref} from 'vue'
import {isAxiosError} from 'axios'
import {useUserStore} from '@/stores/user'
import {partnerError} from '@/utils/partnerError'
/** Preserve the same command across an ambiguous response; retry never invents a new actual record. */
export function useSampleSubmission(scope:string){
 const user=useUserStore(),actor=user.profile?.userId,storageKey=actor?`plm:sample-command:${actor}:${scope}`:'',busy=ref(false),error=ref(''),pending=ref<Record<string,unknown>>()
 try{const raw=storageKey&&sessionStorage.getItem(storageKey);if(raw)pending.value=JSON.parse(raw)}catch{if(storageKey)sessionStorage.removeItem(storageKey)}
 const unknown=computed(()=>!!pending.value)
 function clear(){pending.value=undefined;if(storageKey)try{sessionStorage.removeItem(storageKey)}catch{}}
 async function submit<T>(make:()=>Record<string,unknown>,send:(data:Record<string,unknown>)=>Promise<T>){
  if(busy.value)return;const auth=user.token;busy.value=true;error.value=''
  try{const payload=pending.value||make();pending.value=JSON.parse(JSON.stringify(payload));if(storageKey)try{sessionStorage.setItem(storageKey,JSON.stringify(pending.value))}catch{}const value=await send(pending.value!);if(auth!==user.token)return;clear();return value}
  catch(e){if(auth!==user.token)return;const uncertain=isAxiosError(e)&&(!e.response||Number(e.response.status)>=500);if(!uncertain)clear();error.value=partnerError(e)+(pending.value?'；保存结果待核对，请用原内容重试，避免重复办理。':'')}
  finally{busy.value=false}
 }
 return{busy,error,unknown,pending,submit}
}
