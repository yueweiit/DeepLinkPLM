import {computed,ref,watch} from 'vue'
import {useUserStore} from '@/stores/user'

export type MoldSalesView='detail'|'matrix'
interface Preferences {version:1;view:MoldSalesView;productLineIds?:number[];productKeys?:string[]}
const validIds=(value:unknown):value is number[]=>Array.isArray(value)&&value.every(id=>Number.isSafeInteger(id)&&id>0)
const validKeys=(value:unknown):value is string[]=>Array.isArray(value)&&value.every(key=>typeof key==='string'&&key.length>0)
export const moldSalesPreferenceKey=(userId:number,scope='sales')=>`plm:mold-sales:${scope}:columns:v1:user:${userId}`
export const moldSalesProductKey=(product:{productKey?:string;productLineId:number|null})=>product.productKey??`product:${product.productLineId}`

/** Browser presentation preferences only. Business IDs and server access remain authoritative. */
export function useMoldSalesPreferences(scope='sales'){
 const user=useUserStore(),view=ref<MoldSalesView>('matrix'),savedProductLineIds=ref<number[]>(),savedProductKeys=ref<string[]>()
 const owner=computed(()=>user.token&&Number.isSafeInteger(user.profile?.userId)&&Number(user.profile?.userId)>0?Number(user.profile?.userId):undefined)
 const storageAvailable=ref(true)
 function read(){
  view.value='matrix';savedProductLineIds.value=undefined;savedProductKeys.value=undefined;storageAvailable.value=true
  if(owner.value==null)return
  try{
   const raw=localStorage.getItem(moldSalesPreferenceKey(owner.value,scope));if(!raw)return
   const value=JSON.parse(raw) as Partial<Preferences>
   if(value.version!==1)return
   if(value.view==='detail'||value.view==='matrix')view.value=value.view
   if(validIds(value.productLineIds))savedProductLineIds.value=[...new Set(value.productLineIds)]
   if(validKeys(value.productKeys))savedProductKeys.value=[...new Set(value.productKeys)]
  }catch{storageAvailable.value=false}
 }
 function write(){
  if(owner.value==null)return
  try{localStorage.setItem(moldSalesPreferenceKey(owner.value,scope),JSON.stringify({version:1,view:view.value,...(savedProductLineIds.value===undefined?{}:{productLineIds:savedProductLineIds.value}),...(savedProductKeys.value===undefined?{}:{productKeys:savedProductKeys.value})} satisfies Preferences));storageAvailable.value=true}catch{storageAvailable.value=false}
 }
 function chooseView(value:MoldSalesView){view.value=value;write()}
 function chooseProducts(ids:number[]){savedProductLineIds.value=[...new Set(ids.filter(id=>Number.isSafeInteger(id)&&id>0))];savedProductKeys.value=undefined;write()}
 function chooseProductKeys(keys:string[],ids:number[]){savedProductKeys.value=[...new Set(keys)];savedProductLineIds.value=[...new Set(ids)];write()}
 // A restored profile can arrive after the token. Unknown identities never share a fallback key.
 watch(()=>[user.token,user.profile?.userId],read,{immediate:true})
 return {view,savedProductLineIds,savedProductKeys,storageAvailable,chooseView,chooseProducts,chooseProductKeys}
}
