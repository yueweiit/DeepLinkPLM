<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, reactive, ref, watch } from 'vue'
import type { UnwrapNestedRefs } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import * as api from '@/api/modules/collaboration'
import { type CodeItem } from '@/api/modules/code'
import type { CollaborationRow } from '@/types/collaboration'
import { moldScheduleTaskRoute, platformRoute } from '@/utils/collaboration'
import type { useArchiveLedger } from '../composables/useArchiveLedger'
import { useUserStore } from '@/stores/user'
import CollaborationAssignments from './CollaborationAssignments.vue'
import SharedCompatiblePhoneSelector from '@/views/mold/components/SharedCompatiblePhoneSelector.vue'
import PhoneBrandSelect from '@/views/mold/components/PhoneBrandSelect.vue'
import PhoneCodeSelector from '@/views/mold/components/PhoneCodeSelector.vue'
import type { CompatiblePhone } from '@/types/molds'
import CollaborationObjectSelect from './CollaborationObjectSelect.vue'
import type { PhoneObjectOption } from '@/components/PhoneObjectSelect/types'
const props=defineProps<{ledger:UnwrapNestedRefs<ReturnType<typeof useArchiveLedger>>}>(),router=useRouter()
const user=useUserStore()
const createVisible=ref(false),creating=ref(false),phoneOptions=ref<CodeItem[]>([]),associationVisible=ref(false),associated=ref<CollaborationRow>()
const candidate=reactive({productLineId:null as number|null,styleName:'',brandId:null as number|null,compatibleModels:'',phoneModelCodeItemId:null as number|null,notes:''})
const candidateMemberIds=ref<number[]>([])
const association=reactive({productLineId:null as number|null,phoneModelCodeItemId:null as number|null,styleName:'',compatibleModels:''})
function openCreate(){Object.assign(candidate,{productLineId:null,styleName:'',brandId:null,compatibleModels:'',phoneModelCodeItemId:null,notes:''});candidateMemberIds.value=[];createVisible.value=true}
function chooseLine(){candidate.styleName=props.ledger.options?.productLines.find(p=>p.productLineId===candidate.productLineId)?.productName || candidate.styleName}
function choosePhones(phones:CompatiblePhone[]){const brands=[...new Set(phones.map(p=>p.brandId))];candidate.compatibleModels=phones.map(p=>p.modelName).join(' / ');candidate.brandId=brands.length===1?brands[0]!:null;candidate.phoneModelCodeItemId=phones.length===1?phones[0]!.codeItemId:null}
async function create(){if(!candidate.styleName.trim() || !candidate.compatibleModels.trim()){ElMessage.warning(uiText('请填写原始款式和兼容手机型号'));return}const token=user.token;if(!token)return;creating.value=true;try{await api.createCollaborationItem({...candidate});if(token!==user.token)return;createVisible.value=false;props.ledger.workspace.changed();ElMessage.success(uiText('市场档案已建立，产量仍为空'))}finally{if(token===user.token)creating.value=false}}
async function openAssociation(row:CollaborationRow){associated.value=row;Object.assign(association,{productLineId:props.ledger.value(row,'productLineId'),phoneModelCodeItemId:props.ledger.value(row,'phoneModelCodeItemId'),styleName:props.ledger.value(row,'styleName'),compatibleModels:props.ledger.value(row,'compatibleModels')});associationVisible.value=true;phoneOptions.value=row.phoneModelCodeItemId?[{codeItemId:row.phoneModelCodeItemId,codeType:'phone_model',codeValue:row.linkedPhoneModelCode || row.phoneModelCode || '',codeName:row.phoneModelCodeName || row.compatibleModels,status:'enabled',sortOrder:0}]:[]}
function stageAssociation(){const row=associated.value;if(!row)return;const keys=['productLineId','phoneModelCodeItemId',...(!row.productLineId?['styleName']:[]),...(!row.phoneModelCodeItemId?['compatibleModels']:[])];for(const key of keys){const f=props.ledger.options?.itemFields.find(f=>f.key===key);if(f && props.ledger.editable(f))props.ledger.drafts.set(row,key,association[key as keyof typeof association])}associationVisible.value=false;ElMessage.info(uiText('已暂存关联，保存当前模块后生效。原始名称不会随主资料改名。'))}
const linksVisible=ref(false),linkItem=ref<CollaborationRow>(),projectIds=ref<number[]>([]),confirmCompatibility=ref(false),linkLoading=ref(false)
async function openLinks(row:CollaborationRow){linkItem.value=row;projectIds.value=row.projects.map(p=>p.projectId);confirmCompatibility.value=false;linksVisible.value=true}
const chosenProjects=ref<PhoneObjectOption[]>([])
const projectReferences=computed<PhoneObjectOption[]>({get:()=>projectIds.value.map(id=>chosenProjects.value.find(r=>r.id===id)||{type:'product',id,code:linkItem.value?.projects.find(p=>p.projectId===id)?.productCode||`#${id}`,name:linkItem.value?.projects.find(p=>p.projectId===id)?.productName||`项目 #${id}`}),set:rows=>{chosenProjects.value=rows;projectIds.value=rows.map(r=>r.id)}})
async function saveLinks(){if(!linkItem.value)return;const token=user.token;linkLoading.value=true;try{await api.linkCollaborationProjects(linkItem.value.itemId,projectIds.value,confirmCompatibility.value,linkItem.value.projects.map(p=>p.projectId));if(token!==user.token)return;linksVisible.value=false;props.ledger.workspace.changed()}finally{if(token===user.token)linkLoading.value=false}}
async function addMold(row:CollaborationRow){if(row.moldSetCount){ElMessage.info(uiText('已有模具套，请在台账选择来源套复制 / 重新开模'));await router.push({path:'/molds',query:{itemId:row.itemId}});return}try{const r=await ElMessageBox.prompt(uiText('建立一套 PC＋TPU，日期仍由负责人填写。'),uiText('新增模具套'),{inputValue:uiText('第 1 套')});const mold=await api.createCollaborationMold(row.itemId,{setName:r.value});props.ledger.workspace.changed();await router.push(moldScheduleTaskRoute({moldSetId:mold.moldSetId}))}catch(e){if(e!=='cancel' && e!=='close')ElMessage.warning(uiText('新增失败，请重试'))}}
function act(command:string,row:CollaborationRow){if(command==='associate')void openAssociation(row);else if(command==='links')void openLinks(row);else if(command==='mold')void addMold(row);else if(command==='project'){if(!row.productLineId){ElMessage.warning(uiText('请先关联现有产品平台'));return}void router.push({path:'/products/create',query:{collaborationItemId:row.itemId}})}}
defineExpose({act})
const assignmentsVisible=ref(false)
watch(()=>user.token,()=>{phoneOptions.value=[];chosenProjects.value=[];projectIds.value=[];linkItem.value=undefined;associated.value=undefined;createVisible.value=false;associationVisible.value=false;linksVisible.value=false;assignmentsVisible.value=false;creating.value=false;linkLoading.value=false;Object.assign(candidate,{productLineId:null,styleName:'',brandId:null,compatibleModels:'',phoneModelCodeItemId:null,notes:''});candidateMemberIds.value=[];Object.assign(association,{productLineId:null,phoneModelCodeItemId:null,styleName:'',compatibleModels:''})},{flush:'sync'})
</script>
<template>
  <el-button v-if="ledger.options?.writableGroups.includes('market')" size="small" :icon="Plus" type="primary" @click="openCreate">{{ uiText("新增市场候选") }}</el-button>
  <el-button v-if="ledger.options?.administrator" size="small" @click="assignmentsVisible=true">{{ uiText("职责配置") }}</el-button>
  <el-dialog v-model="createVisible" :title='uiText("新增市场候选")' width="min(760px,95vw)" class="market-create-dialog">
    <el-form label-position="top" class="form-grid">
      <el-form-item :label='uiText("现有产品平台（可待关联）")'><el-select v-model="candidate.productLineId" clearable filterable @change="chooseLine"><el-option v-for="p in ledger.options?.productLines" :key="p.productLineId" :label="p.productName" :value="p.productLineId" /></el-select></el-form-item>
      <el-form-item :label='uiText("原始款式")' required><el-input v-model="candidate.styleName" maxlength="255" /></el-form-item>
      <el-form-item :label='uiText("市场意向手机（共用标准资料）")'><SharedCompatiblePhoneSelector v-model="candidateMemberIds" @selected="choosePhones"/><small>{{ uiText("这里只预填市场意向，不代表共模适配确认；共模成员与适用实物请在模具详情确认。") }}</small></el-form-item>
      <el-form-item :label='uiText("原始兼容型号")' required><el-input v-model="candidate.compatibleModels" maxlength="512" /></el-form-item>
      <el-form-item :label='uiText("品牌")'><PhoneBrandSelect v-model="candidate.brandId" :brands="ledger.brands" /><small>{{ uiText("共用已有品牌；标准手机带出单一品牌，跨品牌组合保留待核对，不拆分型号。") }}</small></el-form-item>
      <el-form-item :label='uiText("备注")'><el-input v-model="candidate.notes" type="textarea" maxlength="4000" /></el-form-item>
    </el-form>
    <template #footer><el-button @click="createVisible=false">{{ uiText("取消") }}</el-button><el-button type="primary" :loading="creating" @click="create">{{ uiText("建立档案") }}</el-button></template>
  </el-dialog>
  <el-dialog v-model="associationVisible" :title='uiText("基础资料关联与原始名称")' width="min(800px,95vw)"><p>{{ uiText("只关联已有主资料；不生成新编码，不拆分斜杠共模组，不改写历史项目。") }}</p><el-form label-position="top" class="form-grid"><el-form-item :label='uiText("产品平台")'><el-select v-model="association.productLineId" clearable filterable :disabled="!!associated?.projectCount || !ledger.options?.writableGroups.includes('market')"><el-option v-for="p in ledger.options?.productLines" :key="p.productLineId" :label="p.productName" :value="p.productLineId" /></el-select><el-button v-if="associated?.productLineId" link @click="router.push(platformRoute(associated.productLineId))">{{ uiText("打开平台资料") }}</el-button></el-form-item><el-form-item :label='uiText("历史编码关联锚点（不代表所有兼容手机的编码）")'><PhoneCodeSelector v-model="association.phoneModelCodeItemId" :members="phoneOptions" :disabled="!!associated?.projectCount || !!associated?.moldSetCount || !!associated?.moldName || !ledger.options?.writableGroups.includes('market')" :aria-label='uiText("历史编码关联锚点")'/><el-button link @click="router.push({path:'/molds',query:{itemId:associated?.itemId,panel:'reference-data'}})">{{ uiText("维护品牌、手机与共模关系") }}</el-button></el-form-item><el-form-item :label='uiText("原始款式")'><el-input v-model="association.styleName" :readonly="!!associated?.productLineId || !ledger.options?.writableGroups.includes('market')" maxlength="255" /></el-form-item><el-form-item :label='uiText("原始兼容型号")'><el-input v-model="association.compatibleModels" :readonly="!!associated?.phoneModelCodeItemId || !ledger.options?.writableGroups.includes('market')" maxlength="512" /></el-form-item></el-form><p>{{ uiText("原编码：") }}{{ associated?.phoneModelCode || uiText('未知') }}{{ uiText(" · 档案 ID ") }}{{ associated?.itemId }}{{ uiText("。名称差异请人工核对，关联后显示主资料当前名称，原始文字保留。") }}</p><template #footer><el-button @click="associationVisible=false">{{ uiText("关闭") }}</el-button><el-button v-if="ledger.options?.writableGroups.includes('market')" type="primary" @click="stageAssociation">{{ uiText("暂存关联") }}</el-button></template></el-dialog>
  <el-dialog v-model="linksVisible" :title='uiText("关联已有型号项目")' width="min(950px,95vw)"><p>{{ uiText("同一兼容组可关联多个颜色项目，累计产量只记录一次，不修改历史项目。") }}</p><CollaborationObjectSelect v-model="projectReferences" type="product" :product-line-id="linkItem?.productLineId" :owner-item-id="linkItem?.itemId" :aria-label='uiText("关联已有型号项目")'/><el-checkbox v-model="confirmCompatibility">{{ uiText("已人工确认所选项目属于此兼容型号组") }}</el-checkbox><template #footer><el-button @click="linksVisible=false">{{ uiText("取消") }}</el-button><el-button type="primary" :loading="linkLoading" @click="saveLinks">{{ uiText("保存项目关联") }}</el-button></template></el-dialog>
  <CollaborationAssignments v-model="assignmentsVisible" @saved="async()=>ledger.options=await api.getCollaborationOptions()" />
</template>
<style scoped>.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px 20px}.el-select{width:100%}.form-grid :deep(.compatible-selector){width:100%}small{display:block;width:100%}:global(.el-dialog.market-create-dialog){max-height:calc(100dvh - 32px);display:flex;flex-direction:column;margin:16px auto}:global(.market-create-dialog .el-dialog__body){min-height:0;overflow:auto}@media(max-width:700px){.form-grid{grid-template-columns:1fr}}</style>
