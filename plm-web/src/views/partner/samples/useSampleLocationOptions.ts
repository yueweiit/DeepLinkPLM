import {inject,onBeforeUnmount,provide,ref,watch,type InjectionKey} from 'vue'
import {useUserStore} from '@/stores/user'
import {sampleLocationOptions} from '@/api/modules/sampleManagement'
import type {SampleLocationOption,SampleLocationSite} from '@/types/sampleManagement'
import {partnerError} from '@/utils/partnerError'

type LocationOptions=ReturnType<typeof createLocationOptions>
const locationOptionsKey:InjectionKey<LocationOptions>=Symbol('sample-location-options')

/** One operation workspace shares its catalogue request; it is not a second location ledger. */
export function useSampleLocationOptions(){
 const inherited=inject(locationOptionsKey,undefined)
 if(inherited)return inherited
 const state=createLocationOptions()
 provide(locationOptionsKey,state)
 return state
}
function createLocationOptions(){
 const user=useUserStore(),rows=ref<SampleLocationOption[]>([]),loading=ref(false),error=ref(''),loaded=ref(false),more=ref(false)
 let request:Promise<void>|undefined,generation=0,disposed=false,currentQuery:{keyword?:string;site?:SampleLocationSite}={}
 async function load(query:{keyword?:string;site?:SampleLocationSite}={},force=false):Promise<void>{
  const same=JSON.stringify(query)===JSON.stringify(currentQuery)
  if(!force&&same&&(loaded.value||request))return request
  currentQuery={...query};loaded.value=false;rows.value=[]
  const ticket=++generation,auth=user.token;loading.value=true;error.value=''
  const pending=(async()=>{try{const result=await sampleLocationOptions({...query,size:50});if(!disposed&&ticket===generation&&auth===user.token){rows.value=result.content.slice(0,50);more.value=result.hasMore===true||result.content.length>50;loaded.value=true}}catch(e){if(!disposed&&ticket===generation&&auth===user.token)error.value=partnerError(e)}finally{if(ticket===generation){loading.value=false;request=undefined}}})()
  request=pending;return pending
 }
 watch(()=>[user.token,user.profile?.userId],()=>{++generation;request=undefined;rows.value=[];loaded.value=false;more.value=false;loading.value=false;error.value=''})
 onBeforeUnmount(()=>{disposed=true;++generation})
 return{rows,loading,error,more,load,reload:()=>load(currentQuery,true)}
}
