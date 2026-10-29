<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {useUserStore} from '@/stores/user'
import {useCollaborationWorkspace} from '@/stores/collaborationWorkspace'
import {saveProductionMetadata} from '@/api/modules/productionMetadata'
import {productionStatusOptions} from '@/utils/productionStatus'
import type {PhoneBrand} from '@/types/molds'
import type {ProductionMetadataTarget,ProductionMetadataResult,ProductionStatus} from '@/types/production'
const props=defineProps<{modelValue:boolean;kind:'phone'|'product';items:ProductionMetadataTarget[];brands?:PhoneBrand[]}>()
const emit=defineEmits<{'update:modelValue':[boolean];saved:[ProductionMetadataResult[]]}>()
const user=useUserStore(),workspace=useCollaborationWorkspace(),state=ref<ProductionStatus|''>(''),brandIds=ref<number[]>([]),updateBrands=ref(false),reason=ref(''),busy=ref(false),error=ref('')
const visible=computed({get:()=>props.modelValue,set:v=>emit('update:modelValue',v)})
let generation=0
watch(()=>props.modelValue,open=>{generation++;busy.value=false;if(open){state.value=props.items.length===1?props.items[0]?.productionStatus||'unknown':'';brandIds.value=props.items.length===1?[...(props.items[0]?.brandIds||[])]:[];updateBrands.value=false;reason.value='';error.value=''}},{immediate:true})
watch(()=>user.token,()=>{generation++;visible.value=false;reason.value='';brandIds.value=[];error.value=''})
function disabledBrand(b:PhoneBrand){return b.status!=='enabled'&&!props.items.every(i=>i.brandIds?.includes(b.brandId))}
const pendingItems=computed(()=>props.items.map(i=>{
  const changes:Record<string,unknown>={},original:Record<string,unknown>={}
  if(state.value&&state.value!==(i.productionStatus||'unknown')){changes.productionStatus=state.value;original.productionStatus=i.productionStatus||'unknown'}
  if(props.kind==='phone'&&updateBrands.value){const next=[...new Set([...brandIds.value,...(i.memberBrandIds||[])])].sort((a,b)=>a-b),before=[...new Set(i.brandIds||[])].sort((a,b)=>a-b);if(JSON.stringify(next)!==JSON.stringify(before)){changes.brandIds=next;original.brandIds=before}}
  return {id:i.id,changes,original}
}).filter(i=>Object.keys(i.changes).length>0))
async function save(){
  if(!reason.value.trim()||!pendingItems.value.length)return
  const epoch=generation,token=user.token,signature=props.items.map(i=>i.id).join(','),wasOpen=props.modelValue;busy.value=true;error.value=''
  const items=pendingItems.value
  try{const rows=await saveProductionMetadata(props.kind,items,reason.value.trim());if(epoch!==generation||token!==user.token||!wasOpen||!props.modelValue||signature!==props.items.map(i=>i.id).join(','))return;workspace.changed();emit('saved',rows);visible.value=false;ElMessage.success('生产标识已保存，既有业务和流程状态未改变')}
  catch(e){if(epoch===generation&&token===user.token&&props.modelValue)error.value=e instanceof Error?e.message:'保存失败，请刷新核对；本批未部分保存'}
  finally{if(epoch===generation)busy.value=false}
}
</script>
<template>
  <el-dialog v-model="visible" append-to-body title="维护生产状态" width="min(560px,94vw)" :close-on-click-modal="false">
    <p class="scope">{{items.length===1?items[0]?.label:`已选择 ${items.length} 条`}} · {{kind==='phone'?'整组配套产品':'独立产品'}}标识</p>
    <el-alert title="仅标识和提醒，不阻止新业务，不改变归档、冻结、库存或订单。" type="info" :closable="false"/>
    <el-form label-position="top" class="metadata-form"><el-form-item label="生产状态"><el-select v-model="state" aria-label="生产状态选择" placeholder="保持原状态"><el-option v-if="items.length>1" value="" label="保持原状态"/><el-option v-for="s in productionStatusOptions" :key="s.value" :value="s.value" :label="s.label"/></el-select></el-form-item>
      <template v-if="kind==='phone'"><el-checkbox v-model="updateBrands">同时维护人工品牌分类</el-checkbox><el-select v-if="updateBrands" v-model="brandIds" multiple filterable aria-label="编码组品牌" placeholder="选择品牌，留空可清除人工分类"><el-option v-for="b in brands" :key="b.brandId" :value="b.brandId" :label="b.name" :disabled="disabledBrand(b)"/></el-select><p v-if="updateBrands" class="scope">将替换所选组的人工品牌分类；标准成员已确认的品牌自动保留，不拆分型号名称。</p></template>
      <el-form-item label="变更原因（必填）"><el-input v-model="reason" type="textarea" aria-label="生产状态变更原因" :rows="2" maxlength="1000" show-word-limit/></el-form-item></el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <template #footer><el-button :disabled="busy" @click="visible=false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!reason.trim()||!pendingItems.length" @click="save">保存生产标识</el-button></template>
  </el-dialog>
</template>
<style scoped>.scope{font-size:12px;line-height:1.6;overflow-wrap:anywhere;color:var(--el-text-color-secondary)}.metadata-form{margin-top:12px}.el-select{width:100%}.el-checkbox{margin-bottom:8px}</style>
