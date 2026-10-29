import {defineStore} from 'pinia'
import {ref,watch} from 'vue'
import {useUserStore} from './user'
import {getProductionMetadataOptions} from '@/api/modules/productionMetadata'
export const useProductionMetadataStore=defineStore('production-metadata',()=>{
  const user=useUserStore(),canMaintain=ref(false),loaded=ref(false)
  let pending:Promise<void>|undefined
  watch(()=>user.token,()=>{canMaintain.value=false;loaded.value=false;pending=undefined})
  function ensure(){
    if(!user.token||loaded.value)return Promise.resolve();if(pending)return pending
    const token=user.token
    const work=getProductionMetadataOptions().then(r=>{if(token===user.token){canMaintain.value=r.canMaintain;loaded.value=true}}).catch(()=>{if(token===user.token){canMaintain.value=false;loaded.value=true}}).finally(()=>{if(pending===work)pending=undefined})
    pending=work;return work
  }
  return {canMaintain,ensure}
})
