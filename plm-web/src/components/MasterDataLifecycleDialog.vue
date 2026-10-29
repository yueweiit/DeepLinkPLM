<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {onBeforeRouteLeave,onBeforeRouteUpdate} from 'vue-router'
import {ElMessage,ElMessageBox} from 'element-plus'
import {isAxiosError} from 'axios'
import type {MasterDataIdentity,MasterDataInspection,MasterDataLifecycleAction,MasterDataLifecycleInput} from '@/types/masterDataLifecycle'
import {newRequestId} from '@/api/modules/partner'
import {partnerError} from '@/utils/partnerError'

const props=defineProps<{record?:MasterDataIdentity;kindLabel:string;action:MasterDataLifecycleAction;editable:boolean;context:string;load:(id:number|string)=>Promise<MasterDataInspection>;perform:(id:number|string,action:MasterDataLifecycleAction,input:MasterDataLifecycleInput)=>Promise<unknown>}>()
const emit=defineEmits<{close:[];completed:[result:unknown];open:[path:string]}>()
const action=ref<MasterDataLifecycleAction>('delete'),references=ref<MasterDataInspection>(),reason=ref(''),error=ref(''),loading=ref(false),busy=ref(false)
const labels={delete:'删除',deactivate:'停用',activate:'启用'}
type ReferenceGroup=MasterDataInspection['references'][number]
type ReferenceItem=ReferenceGroup['items'][number]
const referenceTypeLabels=new Map<string,string>([
  ['attachment','图纸与附件'],
  ['collaboration_history','历史修改记录'],
  ['collaboration_import_row','模具导入记录'],
  ['collaboration_item','模具主档'],
  ['collaboration_item_project','关联项目'],
  ['collaboration_mold_part','模具部件与实物事实'],
  ['collaboration_mold_set','模具实物套'],
  ['collaboration_phone_member','手机适配关系'],
  ['collaboration_phone_set_compatibility','手机适配关系'],
  ['development_budget','开发预算'],
  ['document_reference','图纸与附件'],
  ['document_reference_history','历史图纸关联'],
  ['integration_record','系统对接记录'],
  ['inventory','物料库存'],
  ['market_watch_link','新品监控关联'],
  ['master_data_source','来源系统关联'],
  ['mold_application','开模申请'],
  ['mold_application_config','开模申请配置'],
  ['mold_schedule_plan','模具排程计划'],
  ['mold_schedule_reminder','模具排程提醒'],
  ['order','订单'],
  ['order_quote','订单报价'],
  ['partner_record','合作业务与历史'],
  ['pilot_trial','试产验证'],
  ['process','工艺路线'],
  ['process_change_record','工艺变更记录'],
  ['process_production_operation_selection','量产工艺确认'],
  ['product','产品／型号资料'],
  ['product_attr','产品属性'],
  ['product_bom','BOM'],
  ['product_bom_cost_snapshot','BOM与成本快照'],
  ['product_bom_import_batch','BOM导入原件'],
  ['product_bom_item','BOM明细'],
  ['product_bom_route','BOM工艺路线'],
  ['product_bom_route_formal_selection','正式BOM工艺方案'],
  ['product_cost','产品成本'],
  ['product_development_event','研发办理记录'],
  ['product_development_release','研发发布记录'],
  ['product_development_round','样品与验证轮次'],
  ['product_development_state','新品研发资料'],
  ['product_mold_code','模具编码关联'],
  ['product_phone_model','标准手机关联'],
  ['product_production_color_decision','量产颜色方案'],
  ['product_quality_record','产品质量记录'],
  ['product_variant_color','型号颜色方案'],
  ['production_order','生产订单'],
  ['project_cost_item','项目费用与历史'],
  ['project_mold_transfer_express','发模物流'],
  ['project_requirement_form','项目需求资料'],
  ['physical_facts','制作与交付事实'],
  ['approval_definition','审批流程定义'],
  ['approval_instance','审批记录'],
  ['approval_task','审批任务'],
  ['code_item','基础编码'],
  ['customer','客户资料'],
  ['development_finance_history','开发费用历史'],
  ['development_finance_import','开发费用导入'],
  ['import_batch','数据导入批次'],
  ['import_detail','数据导入明细'],
  ['integration_log','系统对接日志'],
  ['integration_mapping','系统对接映射'],
  ['inventory_barcode','物料条码'],
  ['inventory_default','库存默认供方'],
  ['inventory_reorder_rule','库存补货规则'],
  ['inventory_supplier_item','库存与供货关系'],
  ['inventory_tax','物料税务资料'],
  ['inventory_uom_conversion','物料单位换算'],
  ['inventory_workstation_binding','物料工作站关联'],
  ['master_data_import','基础资料导入'],
  ['material_group','物料分组'],
  ['mold_code_reservation','模具编码预留'],
  ['mold_code_sequence','模具编码分配记录'],
  ['mold_code_source','模具编码来源'],
  ['mold_dingtalk_directory','钉钉模具资料'],
  ['mold_event_inbox','模具审批回调记录'],
  ['mold_import_job','模具资料导入任务'],
  ['mold_master_snapshot','模具资料历史快照'],
  ['mold_model_alias','模具型号别名'],
  ['mold_sales_line','模具销售明细'],
  ['mold_sales_reconciliation','模具销售核对'],
  ['mold_sales_snapshot','模具销售快照'],
  ['partner_catalog_style','供方款式目录'],
  ['partner_catalog_style_source','供方款式来源'],
  ['partner_create_request','合作业务建档记录'],
  ['partner_event','合作业务办理记录'],
  ['partner_file','合作业务附件关联'],
  ['partner_file_job','合作资料导入任务'],
  ['partner_file_job_image','合作资料导入图片'],
  ['partner_file_job_row','合作资料导入明细'],
  ['partner_import','合作资料导入记录'],
  ['partner_quote_line','供应商报价明细'],
  ['partner_quote_line_revision','供应商报价历史'],
  ['partner_revision','合作业务版本历史'],
  ['partner_selection_row','选品明细'],
  ['partner_selection_row_revision','选品明细历史'],
  ['partner_spec','合作产品规格'],
  ['partner_upload_part','附件分段上传记录'],
  ['partner_upload_session','附件上传记录'],
  ['phone_brand','手机品牌资料'],
  ['pilot_account','业务权限账号'],
  ['pilot_delivery_task','项目交付待办'],
  ['pilot_scope','业务授权范围'],
  ['pilot_trial_history','试产验证历史'],
  ['process_edge','工艺顺序关系'],
  ['supplier_color','供应商颜色资料'],
  ['supplier_color_version','供应商颜色历史'],
  ['supplier_profile','供应商档案'],
  ['supplier_range','供应商适用范围资料'],
  ['supplier_range_request','供应商范围维护记录'],
  ['supplier_range_version','供应商适用范围历史'],
  ['supplier_short_code_reservation','供应商简码历史归属'],
  ['workflow_template','项目流程模板'],
  ['workflow_template_node','项目流程节点'],
  ['workstation','工作站资料'],
])
// Keep the existing CollaborationFields wording; only these typed historical keys are shown.
const moldHistoryFieldLabels=new Map<string,string>([
  ['compatiblePhones','手机及适用实物套关系'],
  ['projects','关联项目'],
  ['pcPhysicalStatus','PC 实物核对'],
  ['tpuPhysicalStatus','TPU 实物核对'],
  ['pcInventoryId','PC 历史库存资料ID'],
  ['tpuInventoryId','TPU 历史库存资料ID'],
  ['pcStatus','PC 制作状态'],
  ['tpuStatus','TPU 制作状态'],
  ['openedDate','开模日期'],
  ['actualShipDate','实际发模日期'],
  ['actualMexicoArrivalDate','实际抵达墨西哥'],
  ['pcAcceptanceStatus','PC 验收结果'],
  ['tpuAcceptanceStatus','TPU 验收结果'],
])
function technicalReferenceKey(value:string){
  const legacy=/^业务记录（([a-z][a-z0-9_]*)）$/.exec(value)
  return legacy?.[1]||value.replace(/^plm_/,'')
}
function referenceGroupLabel(group:ReferenceGroup){
  const key=technicalReferenceKey(group.kind)
  return technicalReferenceKey(group.label)===key?referenceTypeLabels.get(key)||group.label:group.label
}
function referenceItemLabel(group:ReferenceGroup,item:ReferenceItem){
  const key=technicalReferenceKey(group.kind),prefix=group.label+' · '
  // Typed row identity proves this prefix was generated by the backend, not a business name.
  if(!String(item.id).startsWith(`plm_${key}:`)||!item.label.startsWith(prefix))return item.label
  const text=item.label.slice(prefix.length)
  const detail=key==='collaboration_history'&&item.history?moldHistoryFieldLabels.get(text)||text:text
  return referenceGroupLabel(group)+' · '+detail
}
const label=computed(()=>labels[action.value])
const allowed=computed(()=>!!references.value&&props.editable&&({delete:references.value.canDelete&&!references.value.hasRestrictedReferences,deactivate:references.value.canDeactivate,activate:references.value.canActivate})[action.value])
let generation=0,attempt:{signature:string;requestId:string}|undefined
function current(serial:number,code:number|string,context:string){return serial===generation&&props.record?.id===code&&props.context===context&&props.editable}
async function inspect(preserveError=false){
  const record=props.record;if(!record||!props.editable)return
  const serial=++generation,context=props.context;loading.value=true;if(!preserveError)error.value=''
  try{const value=await props.load(record.id);if(current(serial,record.id,context)){if(String(value.id)!==String(record.id))throw new Error(`${props.kindLabel}资料已变化，请重新打开`);references.value=value}}
  catch(e){if(current(serial,record.id,context)){references.value=undefined;error.value=partnerError(e)}}
  finally{if(current(serial,record.id,context))loading.value=false}
}
watch([()=>props.record?.id,()=>props.action,()=>props.context,()=>props.editable],()=>{
  ++generation;references.value=undefined;reason.value='';error.value='';busy.value=false;loading.value=false;attempt=undefined
  action.value=props.action
  if(props.record&&props.editable)void inspect()
},{immediate:true})
function changeAction(value:MasterDataLifecycleAction){action.value=value;attempt=undefined;error.value=''}
async function mayLeave(){if(busy.value)return false;if(!reason.value.trim())return true;try{await ElMessageBox.confirm('本次操作原因尚未提交，确定放弃？','未提交内容',{type:'warning'});return true}catch{return false}}
async function close(){if(await mayLeave()){++generation;emit('close')}}
async function leave(){if(!props.record)return true;const allowed=await mayLeave();if(allowed){++generation;emit('close')}return allowed}
onBeforeRouteLeave(leave);onBeforeRouteUpdate(leave)
async function submit(){
  const record=props.record,check=references.value
  if(!record||!check||!allowed.value||loading.value||busy.value)return
  const text=reason.value.trim();if(!text){error.value=`请填写${label.value}原因`;return}
  if(text.length>2000){error.value='操作原因最多2000字';return}
  const serial=generation,context=props.context,input={version:check.version,reason:text}
  const signature=JSON.stringify([record.id,action.value,input,context])
  if(attempt?.signature!==signature)attempt={signature,requestId:newRequestId()}
  busy.value=true;error.value=''
  try{
    const result=await props.perform(record.id,action.value,{...input,requestId:attempt.requestId})
    if(!current(serial,record.id,context))return
    attempt=undefined;reason.value='';ElMessage.success(`${props.kindLabel}已${label.value}`);emit('completed',result);emit('close')
  }catch(e){
    if(!current(serial,record.id,context))return
    error.value=partnerError(e)
    if(isAxiosError(e)&&e.response?.status===409){attempt=undefined;error.value+='；已重新检查，请核对后再次确认';await inspect(true)}
  }finally{if(props.record?.id===record.id&&props.context===context)busy.value=false}
}
function openReference(path?:string){if(path?.startsWith('/')&&!path.startsWith('//'))emit('open',path)}
</script>
<template>
  <el-dialog :model-value="!!record&&editable" :title="`${label}${kindLabel}`" width="min(640px,95vw)" class="record-lifecycle-dialog" append-to-body :close-on-click-modal="false" :close-on-press-escape="false" :before-close="close">
    <div v-if="record" class="record-lifecycle" v-loading="loading">
      <strong class="record-identity">{{references?.name||record.name}} <span>{{references?.code||record.code||record.id}}</span></strong>
      <el-alert v-if="error" type="error" :title="error" :closable="false"/>
      <div class="reference-toolbar"><h3>业务引用</h3><el-button link :disabled="busy||loading" @click="inspect()">{{references?'重新检查':'重试检查'}}</el-button></div>
      <section v-if="references" class="reference-check">
        <el-alert v-if="references.hasRestrictedReferences" title="还有当前无权查看的业务引用，不能删除。" type="warning" :closable="false"/>
        <section v-for="group in references.references" :key="group.kind" class="reference-group">
          <h4>{{referenceGroupLabel(group)}} · {{group.count}}</h4>
          <div v-for="(item,index) in group.items" :key="`${item.id}:${index}`" class="reference-item">
            <el-button v-if="item.targetPath" link type="primary" :disabled="busy" @click="openReference(item.targetPath)">{{referenceItemLabel(group,item)}}</el-button><span v-else>{{referenceItemLabel(group,item)}}</span><el-tag v-if="item.history" size="small" type="info">历史记录</el-tag>
            <el-tag v-if="item.needsReview" size="small" type="warning">待核对</el-tag><span v-if="item.needsReview&&item.matchBasis" class="muted" :title="item.matchBasis">{{item.matchBasis}}</span>
          </div>
        </section>
        <p v-if="!references.references.length&&!references.hasRestrictedReferences" class="muted">暂无业务引用</p>
        <p v-if="action==='delete'&&!references.canDelete" class="blocked-delete">{{references.blockedReason||`当前不能删除此${kindLabel}，可停用并保留历史业务。`}}</p>
        <p v-if="action==='activate'&&!references.canActivate" class="blocked-delete">{{references.blockedReason||`此${kindLabel}暂不可启用，请先核对档案。`}}</p>
        <el-form label-position="top" :disabled="busy"><el-form-item :label="`${label}原因`" required><el-input v-model="reason" type="textarea" :autosize="{minRows:2}" maxlength="2000" show-word-limit :placeholder="action==='delete'?'例如：误建、重复建档':'填写本次操作原因'"/></el-form-item></el-form>
      </section>
    </div>
    <template #footer><el-button :disabled="busy" @click="close">取消</el-button><el-button v-if="action==='delete'&&references?.canDeactivate" :disabled="busy||loading" @click="changeAction('deactivate')">改为停用</el-button><el-button :type="action==='delete'?'danger':'primary'" :loading="busy" :disabled="!allowed||loading" @click="submit">确认{{label}}</el-button></template>
  </el-dialog>
</template>
<style scoped>
:global(.record-lifecycle-dialog){max-height:calc(100dvh - 48px);margin:24px auto;display:flex;flex-direction:column}
:global(.record-lifecycle-dialog>.el-dialog__header),:global(.record-lifecycle-dialog>.el-dialog__footer){flex-shrink:0}
:global(.record-lifecycle-dialog>.el-dialog__body){flex:1 1 auto;min-height:0;overflow-y:auto}
:global(.record-lifecycle-dialog .el-button){font-size:12px;min-height:28px;height:28px}
.record-lifecycle,.reference-check{display:flex;flex-direction:column;gap:10px;font-size:12px;min-width:0}.record-identity{font-size:14px;overflow-wrap:anywhere}.record-identity span{font-size:12px;font-weight:400;color:#66768a;margin-left:8px}.reference-toolbar,.reference-item{display:flex;gap:8px;align-items:center;flex-wrap:wrap;min-width:0}.reference-toolbar{justify-content:space-between}.reference-toolbar h3,.reference-group h4{margin:0;font-size:13px}.reference-group{border-top:1px solid #e4eaf2;padding-top:8px}.reference-item{padding:4px 0}.reference-item .el-button{height:auto;min-height:28px;white-space:normal;text-align:left;max-width:100%;overflow-wrap:anywhere}.reference-item :deep(.el-button>span){white-space:normal;overflow-wrap:anywhere;min-width:0}.record-lifecycle :deep(.el-form-item__label){font-size:12px;font-weight:700}.record-lifecycle :deep(.el-textarea__inner){font-size:12px}.muted{margin:0;color:#718096}.blocked-delete{color:#9a5b18;margin:0}
</style>
