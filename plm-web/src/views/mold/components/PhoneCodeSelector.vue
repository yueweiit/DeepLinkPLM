<script setup lang="ts">
// Historical code groups are not standard phone IDs: preserve combined names and original code anchors.
import { computed, inject, ref, watch } from 'vue'
import { formContextKey } from 'element-plus'
import { getCodeItems, type CodeItem } from '@/api/modules/code'
import { getPhoneSelectionBrands } from '@/api/modules/phoneReference'
import { useUserStore } from '@/stores/user'
import { comparePhoneCodes, productionHint } from '@/utils/productionStatus'
import type { PhoneBrand } from '@/types/molds'
type CodeOption={codeItemId:number;codeValue:string;codeName:string;status:string;productionStatus?:CodeItem['productionStatus']}
const props=defineProps<{modelValue:number|null;members?:CodeOption[];brandId?:number;disabled?:boolean;ariaLabel?:string}>()
const emit=defineEmits<{'update:modelValue':[number|null]}>()
const user=useUserStore(),form=inject(formContextKey,undefined),disabled=computed(()=>props.disabled || !!form?.disabled)
const brands=ref<PhoneBrand[]>([]),brand=ref<number|null>(props.brandId ?? null),rows=ref<CodeItem[]>([]),keyword=ref(''),page=ref(0),total=ref(0),loading=ref(false),error=ref(''),brandError=ref('')
let generation=0,brandGeneration=0
const selectedMember=computed(()=>props.members?.find(c=>c.codeItemId===props.modelValue))
const choices=computed(()=>{
  const all=new Map<number,CodeOption>(rows.value.map(c=>[c.codeItemId,c]))
  if(selectedMember.value && !all.has(selectedMember.value.codeItemId))all.set(selectedMember.value.codeItemId,selectedMember.value)
  return [...all.values()].sort(comparePhoneCodes)
})
async function loadBrands(){const auth=user.token,ticket=++brandGeneration;if(!auth || props.brandId)return;brandError.value='';try{const result=await getPhoneSelectionBrands();if(!Array.isArray(result))throw new Error();if(auth===user.token && ticket===brandGeneration)brands.value=result}catch{if(auth===user.token && ticket===brandGeneration)brandError.value='品牌读取失败'}}
async function query(next:number,ticket=generation){
  if(!user.token || brand.value===null)return
  const auth=user.token;loading.value=true;error.value=''
  try{
    const result=await getCodeItems({codeType:'phone_model',status:'enabled',page:next,size:100,keyword:keyword.value,...(brand.value===0?{unclassified:true}:{brandId:brand.value})})
    if(ticket===generation && auth===user.token){rows.value=[...new Map([...rows.value,...result.content].map(c=>[c.codeItemId,c])).values()];page.value=next;total.value=result.totalElements ?? rows.value.length}
  }catch{if(ticket===generation && auth===user.token)error.value='手机编码读取失败，请重试'}
  finally{if(ticket===generation)loading.value=false}
}
async function search(value=''){keyword.value=value;rows.value=[];page.value=0;total.value=0;loading.value=false;error.value='';await query(1,++generation)}
function choose(value?:number){if(disabled.value || !user.token)return;const row=choices.value.find(c=>c.codeItemId===value);if(value && value!==props.modelValue && (brand.value===null || row?.status!=='enabled'))return;emit('update:modelValue',value || null)}
function changeBrand(value?:number){if(!disabled.value && !props.brandId)brand.value=value ?? null}
watch(brand,()=>{void search()})
watch(()=>props.brandId,value=>{brand.value=value ?? null})
watch(()=>user.token,()=>{generation++;brandGeneration++;rows.value=[];brands.value=[];brand.value=props.brandId ?? null;loading.value=false;error.value='';brandError.value='';if(user.token){void loadBrands();if(brand.value!==null)void search()}},{immediate:true})
</script>
<template>
  <div v-if="user.token" class="phone-code-selector">
    <el-select size="small" v-if="!brandId" class="code-brand" :model-value="brand" filterable clearable :aria-label="`${ariaLabel || '手机编码'}品牌`" :disabled="disabled" placeholder="先选择品牌" @update:model-value="changeBrand"><el-option v-for="b in brands" :key="b.brandId" :value="b.brandId" :label="b.name" :disabled="b.status==='disabled'"/><el-option :value="0" label="未归类（历史编码）"/></el-select>
    <el-select size="small" class="code-model" :model-value="modelValue" clearable filterable remote :fit-input-width="true" :remote-method="search" :loading="loading" :disabled="disabled || (brand===null && !modelValue)" :aria-label="ariaLabel || '手机编码'" :placeholder="brand===null?'请先选择品牌':'选择型号／原编码，可搜索'" @update:model-value="choose" @visible-change="$event && search(keyword)"><el-option v-for="c in choices" :key="c.codeItemId" class="code-option" :value="c.codeItemId" :label="`${c.codeValue} · ${c.codeName}${productionHint(c.productionStatus)}${c.status==='disabled'?' · 停用，历史引用':''}`" :disabled="c.status==='disabled' && c.codeItemId!==modelValue"/><template v-if="total>rows.length || error" #footer><el-button link :loading="loading" :disabled="disabled" @click="query(page+1)">{{error?'重试读取编码':`加载更多（已加载 ${rows.length} / ${total}）`}}</el-button></template></el-select>
    <div v-if="error || brandError" class="code-error" role="alert">{{brandError || error}}<el-button link :disabled="disabled" @click="brandError?loadBrands():search(keyword)">重试</el-button></div>
  </div>
</template>
<style scoped>
.phone-code-selector{display:flex;flex-wrap:wrap;gap:6px;width:100%;min-width:0;container-type:inline-size}.code-brand{flex:0 1 140px;min-width:110px}.code-model{flex:1 1 210px;min-width:0}.phone-code-selector :deep(.el-select__wrapper){min-height:28px;font-size:12px}.code-error{width:100%;font-size:12px;color:#a26025;display:flex;align-items:center;gap:6px}@container(max-width:360px){.code-brand,.code-model{flex-basis:100%;width:100%}}
.code-option{height:auto;min-height:30px;line-height:18px;padding:6px 12px;white-space:normal;overflow-wrap:anywhere;font-size:12px}
</style>
