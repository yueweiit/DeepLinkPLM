<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {onBeforeRouteLeave,useRouter} from 'vue-router'
import {useCollaborationWorkspace} from '@/stores/collaborationWorkspace'
import {useUserStore} from '@/stores/user'
import * as api from '@/api/modules/moldApplications'
import type {ManufacturingRelation} from '@/api/modules/manufacturing'
import MoldApplicationFile from './MoldApplicationFile.vue'
import EngineeringPlanEditor from './EngineeringPlanEditor.vue'
import MoldScopeEditor from './MoldScopeEditor.vue'
import {initialEngineeringPlan,applicationScopePlan,engineeringPlanIssues,copyEngineeringPlan,mappedHoleTypeValue} from '@/utils/engineeringPlan'
import DocumentPreview from '@/views/file/components/DocumentPreview.vue'
import {getAttachmentDetail,type AttachmentVO} from '@/api/modules/attachment'
import {useAttachmentViewer} from '@/composables/useAttachmentViewer'
import PhoneCodeSelector from '@/views/mold/components/PhoneCodeSelector.vue'
import {getPhoneModelOptions,getPhoneApplicationOptions} from '@/api/modules/phoneReference'
import type {CompatiblePhone} from '@/types/molds'
import {launchStage} from '@/constants/productResearch'
import '../application-drawer.scss'
const emit=defineEmits<{saved:[id:number];submitted:[id:number];progress:[id:number];closed:[];reuse:[projectId:number,plan:api.EngineeringPlan]}>()
const user=useUserStore(),workspace=useCollaborationWorkspace(),router=useRouter(),options=ref<api.MoldOptions>()
const busy=ref(false),error=ref('')
let opening=0
const controls=computed(()=>options.value?.controls.filter(c=>!['DDPhotoField','DDAttachment'].includes(c.type)&&!['generatedCodes','informationComplete'].includes(c.key)).map(c=>c.key==='expectedDeliveryDate'?{...c,required:true}:c)||[])
async function attempt(action:()=>Promise<unknown>){const auth=user.token,epoch=opening;try{await action()}catch(e){if(auth!==user.token||epoch!==opening||e==='cancel'||e==='close')return;error.value=e instanceof Error?e.message:'操作失败，请核对后重试';ElMessage.error(error.value)}}
type EditorDraft=api.MoldApplication|api.MoldResearchContext
const projectInputUnsaved=ref(false)
const editor=ref(false),editing=ref<EditorDraft>(),form=reactive<Record<string,string|null>>({}),phoneCodeItemId=ref<number|null>(null),productLineId=ref<number|null>(null)
const researchSource=computed(()=>editing.value?.sourceSnapshot?.research);
const productFlow=computed(()=>!!researchSource.value&&controls.value.some(control=>control.key==='productName'));
const flowMigration=computed(()=>!!editing.value?.applicationId&&!!researchSource.value&&(editing.value.processMigrationRequired||!!editing.value.processCode&&editing.value.processCode!==options.value?.processCode));
function optionContext(row:EditorDraft|undefined){return row?.applicationId?{applicationId:row.applicationId}:row?.sourceSnapshot?.research?{projectId:row.sourceSnapshot.research.projectId}:undefined}
const fileSections=[{category:'key_photo',key:'keyPhotos',label:'关键图片',upload:'上传关键图片',accept:'image/png,image/jpeg,image/webp'},{category:'sample_photo',key:'samplePhotos',label:'样板 / 测试图片',upload:'上传测试图片',accept:'image/png,image/jpeg,image/webp'},{category:'attachment',key:'attachments',label:'Adjunto附件',upload:'上传其他附件',accept:undefined}];
function fileRequired(key:string){return key==='keyPhotos'||!!options.value?.controls.find(control=>control.key===key)?.required}
function sourceFiles(category:string){return !editing.value?.applicationId?(researchSource.value?.references||[]).filter(file=>file.type==='file'&&(file.category||'attachment')===category):[]}
function savedFiles(category:string){return (editing.value?.files||[]).filter(file=>file.category===category)}
const fieldContainers=ref<Record<string,HTMLElement>>({});
function registerField(key:string,element:unknown){const root=(element as {$el?:HTMLElement}|null)?.$el||element;if(root instanceof HTMLElement)fieldContainers.value[key]=root}
function locateField(key:string){const target=key==='tipo'&&!productLineId.value?'productLineId':key;const element=fieldContainers.value[target];element?.scrollIntoView?.({block:'center',behavior:'smooth'});element?.querySelector<HTMLElement>('input,textarea,button')?.focus()}

const submissionPolicy=computed(()=>editing.value?.submissionPolicy);
const researchSubmission=computed(()=>submissionPolicy.value?.mode==='research'&&submissionPolicy.value.requiresCodes===false&&submissionPolicy.value.requiresTipoMapping===false);
const requiresCodes=computed(()=>!researchSubmission.value);
const requiresStandardPhone=computed(()=>!researchSubmission.value||submissionPolicy.value?.requiresStandardPhone!==false);
const existingApplicationNotice=computed(()=>editing.value&&'existingApplicationNotice' in editing.value?editing.value.existingApplicationNotice:'');
const sourceAttachments=ref<Record<number,AttachmentVO>>({}),sourceAttachmentErrors=ref<Record<number,string>>({});
const sourceViewer=useAttachmentViewer();
let sourceFilesEpoch=0,phoneRestoreEpoch=0;
async function loadSourceFiles(row?:EditorDraft){
  const epoch=++sourceFilesEpoch,auth=user.token;sourceAttachments.value={};sourceAttachmentErrors.value={};
  if(!row||row.applicationId||row.sourceSnapshot?.research?.restricted)return;
  await Promise.all((row.sourceSnapshot?.research?.references||[]).filter(file=>file.type==='file').map(async file=>{
    try{const attachment=await getAttachmentDetail(file.id);if(epoch!==sourceFilesEpoch||auth!==user.token)return;if(attachment.attachmentId!==file.id||file.checksum&&attachment.checksum!==file.checksum)throw new Error('来源文件版本不匹配，请重新核对');sourceAttachments.value[file.id]=attachment;}
    catch(error){if(epoch===sourceFilesEpoch&&auth===user.token)sourceAttachmentErrors.value[file.id]=error instanceof Error?error.message:'来源文件读取失败，请重试';}
  }));
}
watch(editing,row=>void loadSourceFiles(row));
watch(editor,active=>{if(!active){invalidatePhoneRestore();++sourceFilesEpoch;sourceAttachments.value={};sourceAttachmentErrors.value={};}});
const executionReason=ref(''),newApplication=ref(false),draftRequestId=ref(''),sourceRequestId=crypto.randomUUID()
const targetItemId=ref<number|null>(null),targetMoldSetId=ref<number|null>(null),targetProjectId=ref<number|null>(null),targets=ref<api.MoldTargetOptions>({projects:[],items:[]})
const uploading=ref(false)
const engineeringPlan=ref<api.EngineeringPlan>(initialEngineeringPlan())
const executionIntent=computed<api.MoldExecutionIntent>(()=>engineeringPlan.value.handling==='new_set'?'new_set':'new')
function initialEditorPlan(row?:EditorDraft){const plan=initialEngineeringPlan(row);return researchSubmission.value?applicationScopePlan(plan):plan}
const engineeringDirty=computed(()=>JSON.stringify(engineeringPlan.value)!==JSON.stringify(initialEditorPlan(editing.value)))
const engineeringIssues=computed(()=>engineeringPlanIssues(engineeringPlan.value))
const standardPhoneIds=ref<number[]>([]),standardPhone=ref<CompatiblePhone>()
const modelSyncing=ref(false)
const scopePhoneIds=computed(()=>[...new Set(engineeringPlan.value.specifications.flatMap(row=>row.phoneModelIds))].sort((a,b)=>a-b))
const researchReapplication=computed(()=>researchSubmission.value&&(newApplication.value||!!editing.value&&'historyApplications' in editing.value&&editing.value.historyApplications?.some(row=>row.phoneModelId!=null&&scopePhoneIds.value.includes(row.phoneModelId))))
const currentPhoneCodes=computed(()=>standardPhone.value?.codeItemId?[{codeItemId:standardPhone.value.codeItemId,codeValue:standardPhone.value.phoneModelCode || '',codeName:standardPhone.value.modelName,status:standardPhone.value.codeStatus || 'enabled'}]:editing.value?.phoneCodeItemId?[{codeItemId:editing.value.phoneCodeItemId,codeValue:editing.value.originalPhoneModelCode || editing.value.phoneModelCode || '原编码待核实',codeName:editing.value.model || '历史引用',status:'enabled'}]:[])
const eligibleProductIds=computed(()=>new Set(options.value?.productLines.filter(p=>p.moldApplicationEligibility?.eligible===true).map(p=>p.productId)))
const productChoices=computed(()=>options.value?.productLines.filter(p=>eligibleProductIds.value.has(p.productId)||p.productId===productLineId.value)||[])
const productSelectionIssue=computed(()=>{const line=productLineId.value;if(line&&!eligibleProductIds.value.has(line)){const p=options.value?.productLines.find(p=>p.productId===line);return p?.moldApplicationEligibility?.reason||'产品开模资格尚未读取，请刷新后核对；历史草稿保留。'}const config=!productFlow.value&&options.value?.configs.find(c=>c.tipo===form.tipo);if(config&&!eligibleProductIds.value.has(config.productLineId))return options.value?.productLines.find(p=>p.productId===config.productLineId)?.moldApplicationEligibility?.reason||'原 Tipo 对应产品当前不能新开模，请核对产品资格。';return ''})
const tipoControl=computed(()=>controls.value.find(c=>c.key==='tipo'))
const inputControls=computed(()=>controls.value.filter(c=>c.key!=='tipo'&&c.key!=='holeType'))
const mappedTypes=computed(()=>options.value?.configs.filter(c=>c.productLineId===productLineId.value&&controls.value.some(control=>control.key==='tipo'&&control.options.includes(c.tipo)))||[])
const selectedProduct=computed(()=>options.value?.productLines.find(product=>product.productId===productLineId.value));
const productIdentityMissing=computed(()=>!!researchSource.value&&!!selectedProduct.value&&!selectedProduct.value.productSpecificCode?.trim());
const canMaintainProductIdentity=computed(()=>selectedProduct.value?.canMaintainProductIdentity===true);
const productIdentityIssue=computed(()=>productIdentityMissing.value&&requiresCodes.value?'尚未维护本产品的两位产品特定编码；可先保存草稿，填写已确认编码后再预留或提交。':'');
const configurationMissing=computed(()=>!researchSubmission.value&&!!productLineId.value&&eligibleProductIds.value.has(productLineId.value)&&!mappedTypes.value.length)
const configurationIssue=computed(()=>{if(!productLineId.value||productSelectionIssue.value)return '';if(researchSubmission.value&&!tipoControl.value)return '';if(researchSubmission.value)return form.tipo&&tipoSelectable(form.tipo)?'':tipoChoices.value.length?'请核对并选择与当前新品对应的钉钉审批类型。':'当前钉钉模板没有可用于此新品的审批类型，请审批管理员核对模板；不自动新增选项。';if(configurationMissing.value)return '尚未配置本产品在当前钉钉审批中的 Tipo 与 PLM 产品线映射；可先保存草稿，补齐配置后再预留编码或提交。';if(!mappedTypes.value.some(c=>c.tipo===form.tipo))return '请选择与本产品对应的当前审批 Tipo，再预留编码或提交。';return ''})
const executionIssue=computed(()=>productSelectionIssue.value||productIdentityIssue.value||configurationIssue.value)
function productLabel(p:api.MoldOptions['productLines'][number]){return p.productName+(p.moldApplicationEligibility?.kind==='research'?' · 新品研发':p.salesStatus==='off_sale'?' · 已停售':p.moldApplicationEligibility?.eligible?'':' · 暂不可申请')}
const currentResearchTipos=computed(()=>researchSubmission.value?(submissionPolicy.value?.allowedTipos||[]).filter(tipo=>tipoControl.value?.options.includes(tipo)):[]);
function tipoSelectable(tipo:string){if(researchSubmission.value)return currentResearchTipos.value.includes(tipo);const c=options.value?.configs.find(c=>c.tipo===tipo);return !!c&&c.productLineId===productLineId.value&&eligibleProductIds.value.has(c.productLineId)}
const tipoChoices=computed(()=>[...new Set([...(researchSubmission.value?currentResearchTipos.value:mappedTypes.value.map(c=>c.tipo)),...(form.tipo?[form.tipo]:[])])])
const automaticTipo=computed(()=>!!productLineId.value&&eligibleProductIds.value.has(productLineId.value)&&mappedTypes.value.length===1&&mappedTypes.value[0]?.tipo===form.tipo&&tipoSelectable(form.tipo))
function chooseProduct(){if(researchSubmission.value){if(form.tipo&&tipoSelectable(form.tipo))return;const mapped=mappedTypes.value.length===1?mappedTypes.value[0]?.tipo:null;form.tipo=mapped&&currentResearchTipos.value.includes(mapped)?mapped:null;return;}const current=options.value?.configs.find(c=>c.tipo===form.tipo);if(current?.productLineId===productLineId.value)return;const choices=options.value?.configs.filter(c=>c.productLineId===productLineId.value&&controls.value.find(control=>control.key==='tipo')?.options.includes(c.tipo))||[];form.tipo=choices.length===1?choices[0]!.tipo:null}
let optionsEpoch=0
async function refreshOptions(){const epoch=++optionsEpoch,auth=user.token;try{const value=await api.getMoldOptions(optionContext(editing.value));if(epoch===optionsEpoch&&auth===user.token&&editor.value){options.value=value;if(!form.tipo)chooseProduct()}}catch(e){if(epoch===optionsEpoch&&auth===user.token&&editor.value)error.value=e instanceof Error?e.message:'产品开模资格刷新失败，请重试'}}
function openMaintenance(path:string,query:Record<string,string>){if(busy.value||uploading.value)return;const destination=router.resolve({path,query});window.open(destination.href,'_blank','noopener,noreferrer')}
function openConfiguration(){if(!options.value?.administrator||!productLineId.value)return;openMaintenance('/mold-applications',{configure:'1',productLineId:String(productLineId.value)})}
function openProductIdentity(){const source=researchSource.value,step=selectedProduct.value?.researchStepNo||source?.currentStepNo;if(!source||!canMaintainProductIdentity.value||!step)return;openMaintenance(`/products/${source.projectId}/edit`,{source:'research',step:launchStage(step).key})}
function refreshOnFocus(){if(editor.value&&!busy.value&&!uploading.value)void refreshOptions()}
watch(()=>workspace.revision,()=>{if(editor.value)void refreshOptions()})
async function loadCodePhone(id?:number,rewriteModel=false){
 invalidatePhoneRestore();const epoch=phoneRestoreEpoch,auth=user.token,ids=researchSubmission.value?scopePhoneIds.value:id?[id]:[]
 standardPhoneIds.value=id?[id]:[];standardPhone.value=undefined;phoneCodeItemId.value=editing.value?.phoneCodeItemId||null;modelSyncing.value=rewriteModel
 if(rewriteModel)form.model=null
 if(!ids.length){modelSyncing.value=false;return}
 try{const result=researchSubmission.value?await getPhoneApplicationOptions(ids):{phones:await getPhoneModelOptions(ids),modelos:''};if(epoch!==phoneRestoreEpoch||auth!==user.token)return;if(rewriteModel)form.model=result.modelos;standardPhone.value=result.phones.find(phone=>phone.phoneModelId===id);phoneCodeItemId.value=standardPhone.value?.codeItemId||null;await loadTargets()}
 catch{if(epoch===phoneRestoreEpoch&&auth===user.token)error.value='标准手机资料读取失败，请核对后继续'}
 finally{if(epoch===phoneRestoreEpoch&&auth===user.token)modelSyncing.value=false}
}
watch([()=>engineeringPlan.value.codePhoneModelId,()=>researchSubmission.value?scopePhoneIds.value.join(','):'',editing] as const,([id,scope,row],previous)=>{void loadCodePhone(id,researchSubmission.value&&!!previous&&row===previous[2]&&scope!==previous[1])})
let targetsEpoch=0
async function loadTargets(){const epoch=++targetsEpoch;targets.value={projects:[],items:[]};if(!standardPhone.value||!productLineId.value)return;const auth=user.token;try{const value=await api.getMoldTargetOptions(standardPhone.value.phoneModelId,productLineId.value);if(epoch===targetsEpoch&&auth===user.token)targets.value=value}catch(e){if(epoch===targetsEpoch&&auth===user.token)error.value=e instanceof Error?e.message:'建档目标读取失败，请重试'}}
watch(productLineId,()=>{void loadTargets()})
watch(executionIntent,value=>{if(value==='new'){targetItemId.value=null;targetMoldSetId.value=null}})
const departmentId=ref<number>()
watch(()=>options.value?.submitter,who=>{if(who?.departments.some(department=>department.id===departmentId.value))return;departmentId.value=who?.departments.length===1?who.departments[0]!.id:undefined})
const failedUploads=ref<{selectionId:string;category:string;file:File;registered:boolean}[]>([])
const filesComplete=computed(()=>!failedUploads.value.length&&!(editing.value?.files||[]).some(f=>f.downloadStatus!=='downloaded'))
const generated=computed(()=>editing.value?.generatedCodes||(researchSubmission.value?'待工程建档时确认，不影响新品审批':'尚未生成'))
const missingControls=computed(()=>{
 const missing=controls.value.filter(control=>control.key!=='holeType'&&control.required&&!String(form[control.key]||'').trim()).map(control=>({key:control.key,label:control.key==='tipo'&&!productLineId.value?uiText('开模产品'):control.label}));
 for(const issue of engineeringIssues.value)missing.push({key:'engineeringPlan',label:uiText(issue)});
 for(const section of fileSections)if(fileRequired(section.key)&&!savedFiles(section.category).some(file=>file.downloadStatus==='downloaded'))missing.push({key:section.key,label:uiText(section.label)});
 return missing;
});
const submissionMissing=computed(()=>missingControls.value.map(control=>control.label));
const templateHoleIssue=computed(()=>!controls.value.some(control=>control.key==='holeType')?'孔型已在PLM记录；当前审批模板缺少孔型映射，请调整模板后刷新申请配置，暂不提交正式审批。':new Set(engineeringPlan.value.specifications.map(row=>row.holeType).filter(Boolean)).size>1?'多个孔型已保存在规格中；当前审批单选孔型无法表达，请调整真实模板映射后再送审。':'')
const templateHoleReady=computed(()=>!templateHoleIssue.value)

const unsaved=computed(()=>!editing.value||engineeringDirty.value||controls.value.some(c=>String(form[c.key]||'')!==String(editing.value?.form[c.key]||''))||phoneCodeItemId.value!==editing.value.phoneCodeItemId||productLineId.value!==editing.value.productLineId||standardPhoneIds.value[0]!== (editing.value.phoneModelId||undefined)||executionIntent.value!==(editing.value.executionIntent||'new')||targetItemId.value!==(editing.value.targetItemId||null)||targetProjectId.value!==(editing.value.targetProjectId||null)||executionReason.value!==(editing.value.executionReason||'')||newApplication.value!==!!editing.value.newApplication)
const dirty=computed(()=>(unsaved.value||engineeringDirty.value||flowMigration.value)&&(!!editing.value||Object.values(form).some(Boolean)||!!standardPhoneIds.value.length||!!productLineId.value||!!executionReason.value.trim()))
function invalidatePhoneRestore(){++phoneRestoreEpoch;++targetsEpoch}
function edit(row?:EditorDraft){invalidatePhoneRestore();editing.value=row;engineeringPlan.value=initialEditorPlan(row);draftRequestId.value=crypto.randomUUID();executionReason.value=row?.executionReason||'';newApplication.value=!!row?.newApplication;failedUploads.value=[];standardPhoneIds.value=[];standardPhone.value=undefined;Object.keys(form).forEach(k=>delete form[k]);Object.assign(form,row?.form||{});if(productFlow.value&&form.productName===undefined)form.productName=row?.sourceSnapshot?.research?.productName||null;phoneCodeItemId.value=researchSubmission.value?null:row?.phoneCodeItemId||null;productLineId.value=row?.productLineId||null;targetItemId.value=researchSubmission.value?null:row?.targetItemId||null;targetMoldSetId.value=researchSubmission.value?null:row?.targetMoldSetId||null;targetProjectId.value=researchSubmission.value?null:row?.targetProjectId||null;targets.value={projects:[],items:[]};editor.value=true}
async function openExisting(id:number){if(busy.value||uploading.value)return;const token=user.token,row=await api.getMoldApplication(id);if(token!==user.token)return;await open(row);if(editor.value&&editing.value===row)emit('saved',row.applicationId)}
function checkSession(auth:string,epoch?:number){if(auth!==user.token)throw new Error('登录身份已变化，请重新打开申请');if(epoch!==undefined&&epoch!==opening)throw new Error('申请详情已变化，请重新打开后操作')}
function updateEngineeringPlan(value:api.EngineeringPlan){engineeringPlan.value=value;const holes=value.specifications.map(row=>row.holeType).filter((type):type is api.HoleType=>!!type),holeOptions=controls.value.find(control=>control.key==='holeType')?.options||[];form.holeType=[...new Set(holes)].map(type=>mappedHoleTypeValue(type,holeOptions)).join(' / ')||null}
function adoptEngineeringTarget(row:ManufacturingRelation){targetItemId.value=row.itemId;targetMoldSetId.value=row.moldSetId;targetProjectId.value=engineeringPlan.value.handling==='new_set'?row.manufacturingProductId:null}
async function confirmReuse(){if(!researchSource.value)throw new Error('请从新品项目办理沿用');if(engineeringIssues.value.length)throw new Error(engineeringIssues.value.join('；'));emit('reuse',researchSource.value.projectId,copyEngineeringPlan(engineeringPlan.value))}
async function save(){
  const auth=user.token,epoch=opening;
  if(busy.value)throw new Error('申请正在保存，请等待完成');
  if(modelSyncing.value)throw new Error(uiText('正在按已选手机填写型号，请稍候'));
  if(!options.value?.canCreate||editing.value&&editing.value.status!=='draft')throw new Error('此申请已冻结或当前账号无编辑权限');
  if(researchReapplication.value&&(!newApplication.value||!executionReason.value.trim()))throw new Error(uiText('同型号重新申请须明确选择并填写原因'));
  if(productSelectionIssue.value&&(!editing.value||productLineId.value!==editing.value.productLineId||form.tipo!==editing.value.form.tipo))throw new Error(productSelectionIssue.value);
  const input:api.MoldDraftInput={form:{...form},productLineId:productLineId.value,phoneCodeItemId:engineeringPlan.value.codePhoneModelId?phoneCodeItemId.value:null,version:editing.value?.applicationId?editing.value.version:undefined,phoneModelId:engineeringPlan.value.codePhoneModelId||null,executionIntent:executionIntent.value,targetItemId:targetItemId.value,targetMoldSetId:targetMoldSetId.value,targetProjectId:targetProjectId.value,executionReason:executionReason.value.trim()||null,newApplication:newApplication.value,draftRequestId:draftRequestId.value||sourceRequestId,engineeringPlan:copyEngineeringPlan(engineeringPlan.value)};
  busy.value=true;
  try{
    const source=researchSource.value;
    const row=!editing.value?.applicationId&&source
      ?await api.startResearchMoldApplication({projectId:source.projectId,version:source.workflowVersion,phoneModelId:input.phoneModelId||null,requestId:input.draftRequestId!,draft:input})
      :await api.saveMoldApplication(editing.value?.applicationId||null,input);
    checkSession(auth,epoch);
    if(!row.applicationId)throw new Error('申请未返回记录编号，请刷新核对后重试');
    const latest=await api.getMoldApplication(row.applicationId);checkSession(auth,epoch);
    if(latest.status!=='draft'||latest.canEditDraft===false){editor.value=false;emit('progress',latest.applicationId);throw new Error('已有申请已提交或当前无编辑权限，请查看审批进度');}
    if(row.alreadyExists){const pending=failedUploads.value;edit(latest);failedUploads.value=pending;}else{editing.value=latest;engineeringPlan.value=initialEditorPlan(latest);}
    workspace.changed();ElMessage.success(row.alreadyExists?uiText('已继续原申请草稿，保留原填写内容'):uiText('草稿已保存'));emit('saved',latest.applicationId);return latest;
  }finally{if(auth===user.token&&epoch===opening)busy.value=false}
}
async function reserve(){if(executionIssue.value)throw new Error(executionIssue.value);const auth=user.token;if(standardPhone.value&&(!standardPhone.value.codeItemId||standardPhone.value.codeLinkStatus!=='linked')){ElMessage.warning(uiText('标准手机编码尚待核对，不自动分配新编码'));return}const row=await save();checkSession(auth);busy.value=true;try{if(standardPhone.value)await api.reserveMoldCodes(row.applicationId,row.version,{phoneModelId:standardPhone.value.phoneModelId});else await api.reserveMoldCodes(row.applicationId,row.version);checkSession(auth);const latest=await api.getMoldApplication(row.applicationId);checkSession(auth);editing.value=latest;phoneCodeItemId.value=editing.value.phoneCodeItemId;productLineId.value=editing.value.productLineId;ElMessage.success(uiText('编码已预留'));emit('saved',editing.value!.applicationId)}finally{busy.value=false}}
async function uploadPending(){const auth=user.token;if(!failedUploads.value.length)return;uploading.value=true;try{const row=!editing.value?.applicationId||unsaved.value?await save():editing.value;checkSession(auth);const selected=await api.selectMoldFiles(row.applicationId,failedUploads.value.map(p=>({selectionId:p.selectionId,category:p.category,fileName:p.file.name,fileSize:p.file.size,contentType:p.file.type||'application/octet-stream'})));checkSession(auth);editing.value=selected;failedUploads.value.forEach(p=>p.registered=true);for(const pending of [...failedUploads.value]){try{checkSession(auth);const uploaded=await api.uploadMoldFile(row.applicationId,pending.category,pending.file,pending.selectionId);checkSession(auth);editing.value=uploaded;failedUploads.value=failedUploads.value.filter(f=>f!==pending)}catch(e){checkSession(auth);error.value=e instanceof Error?e.message:'文件上传失败'}}if(failedUploads.value.length)ElMessage.error(uiText('仍有文件上传失败；请重试或明确移除，暂不能提交审批'));else ElMessage.success(uiText('所选文件已完整归档'))}finally{uploading.value=false}}
async function upload(event:Event,category:string){const input=event.target as HTMLInputElement;const files=Array.from(input.files||[]);if(!files.length)return;for(const file of files){const existing=editing.value?.files?.find(f=>f.downloadStatus==='pending_upload'&&f.category===category&&f.fileName===file.name&&f.fileSize===file.size);const selectionId=existing?.selectionId||crypto.randomUUID();failedUploads.value=failedUploads.value.filter(p=>p.selectionId!==selectionId);failedUploads.value.push({selectionId,category,file,registered:!!existing})}input.value='';await attempt(uploadPending)}
async function discardStored(f:api.MoldFile){if(!editing.value?.applicationId)return;editing.value=await api.discardMoldSelection(editing.value.applicationId,f.attachmentId);failedUploads.value=failedUploads.value.filter(p=>p.selectionId!==f.selectionId)}
async function discardPending(selectionId:string){if(editing.value?.applicationId){editing.value=await api.getMoldApplication(editing.value.applicationId);const f=editing.value.files?.find(f=>f.selectionId===selectionId);if(f)await discardStored(f)}failedUploads.value=failedUploads.value.filter(p=>p.selectionId!==selectionId)}
async function closeEditor(done:()=>void){if(busy.value||uploading.value)return;if(failedUploads.value.length||dirty.value){try{await ElMessageBox.confirm(failedUploads.value.length?uiText('关闭将清除本次重试队列；已登记的未完成文件仍保留在草稿中，需重新上传或明确移除后才能提交。'):uiText('当前填写内容尚未保存，关闭将放弃这些修改。'),uiText('关闭填写窗口？'))}catch{return}}invalidatePhoneRestore();done();emit('closed')}
onBeforeRouteLeave(async()=>{if(!editor.value)return true;if(busy.value||uploading.value)return false;if(!dirty.value&&!failedUploads.value.some(p=>!p.registered))return true;try{await ElMessageBox.confirm(uiText('当前申请或文件选择尚未保存，离开将放弃未保存内容。'),uiText('离开页面？'));return true}catch{return false}})
const protectUnload=(e:BeforeUnloadEvent)=>{if(failedUploads.value.some(p=>!p.registered)){e.preventDefault();e.returnValue=''}}
onMounted(()=>{window.addEventListener('beforeunload',protectUnload);window.addEventListener('focus',refreshOnFocus)});onBeforeUnmount(()=>{invalidatePhoneRestore();++sourceFilesEpoch;window.removeEventListener('beforeunload',protectUnload);window.removeEventListener('focus',refreshOnFocus)})
async function submit(){if(!editing.value?.applicationId||busy.value||uploading.value||!options.value?.canSubmit)return;if(templateHoleIssue.value)throw new Error(templateHoleIssue.value);if(executionIssue.value)throw new Error(executionIssue.value);if(requiresStandardPhone.value&&!standardPhone.value){ElMessage.warning(uiText('请选择标准手机型号，保证申请与模具资料连贯'));return}if(submissionMissing.value.length){ElMessage.warning(uiText('送审还缺：{value0}',{value0:(submissionMissing.value.join('、'))}));return}if((executionIntent.value==='new_set'||newApplication.value)&&!executionReason.value.trim()){ElMessage.warning(uiText('新增套或重新开模必须填写原因'));return}if(executionIntent.value==='new_set'&&!targetItemId.value){ElMessage.warning(uiText('请选择加开一套所属的已有模具主档'));return}if(!researchSubmission.value&&engineeringPlan.value.handling!=='new_set'&&targets.value.projects.length&&!targetProjectId.value){ElMessage.warning(uiText('已有型号项目，请明确选择建档关联目标'));return}if(unsaved.value||!filesComplete.value){ElMessage.warning(requiresCodes.value?uiText('请保存修改、重新生成编码，并处理全部失败文件'):uiText('请保存修改并处理全部失败文件'));return}if(options.value?.submitter?.departments.length&&!departmentId.value){ElMessage.warning(uiText('请明确选择真实钉钉部门'));return}const auth=user.token;busy.value=true;try{await ElMessageBox.confirm(uiText('将以 {value0} 发起正式开模审批，并产生审批待办。提交后表单冻结。',{value0:(options.value?.submitter?.name||'已绑定的真实钉钉身份')}),uiText('提交正式钉钉审批'),{confirmButtonText:uiText('确认提交'),cancelButtonText:uiText('继续填写')});checkSession(auth);const row=await api.submitMoldApplication(editing.value.applicationId,editing.value.version,departmentId.value);checkSession(auth);editing.value=row;workspace.changed();editor.value=false;emit('submitted',row.applicationId)}catch(e){if(auth===user.token&&e!=='cancel'&&e!=='close'){const row=await api.getMoldApplication(editing.value!.applicationId!).catch(()=>undefined);if(auth===user.token&&row&&row.status!=='draft'){editing.value=row;editor.value=false;workspace.changed();emit('progress',row.applicationId)}}throw e}finally{busy.value=false}}

async function open(row?:EditorDraft,context?:{projectInputUnsaved?:boolean}){if(busy.value||uploading.value)throw new Error('请等待当前操作完成');++optionsEpoch;const epoch=++opening,auth=user.token;error.value='';const value=await api.getMoldOptions(optionContext(row));if(epoch!==opening||auth!==user.token)return;if(busy.value||uploading.value)throw new Error('请等待当前操作完成');if(editor.value&&unsaved.value){await ElMessageBox.confirm(uiText('当前填写内容尚未保存，是否放弃并打开其他申请？'),uiText('切换申请'));if(epoch!==opening||auth!==user.token)return}if(!value.canCreate)throw new Error('当前账号无填写开模申请权限');options.value=value;if(row&&(row.status!=='draft'||row.canEditDraft===false)){if(row.applicationId)emit('progress',row.applicationId);return}projectInputUnsaved.value=!!context?.projectInputUnsaved;edit(row)}
function invalidate(){invalidatePhoneRestore();++sourceFilesEpoch;sourceAttachments.value={};sourceAttachmentErrors.value={};++optionsEpoch;++opening;++targetsEpoch;standardPhoneIds.value=[];standardPhone.value=undefined;busy.value=false;editing.value=undefined;engineeringPlan.value=initialEngineeringPlan();failedUploads.value=[];Object.keys(form).forEach(k=>delete form[k]);editor.value=false;options.value=undefined;error.value=''}
watch(()=>user.token,invalidate)
async function openResearch(row:api.MoldResearchContext,context?:{projectInputUnsaved?:boolean}){await open(row,context)}
defineExpose({open,openResearch,invalidate})
</script>

<template>
    <el-drawer class="application-drawer" :style="{'--el-font-size-base':'12px'}" v-model="editor" :title="researchSource?uiText('填写LEMOS开模申请'):uiText('填写开模申请')" size="min(980px,94vw)" :close-on-click-modal="false" :before-close="closeEditor" :close-on-press-escape="!busy&&!uploading" :show-close="!busy&&!uploading">
      <el-alert v-if="error" :title="error" type="error" :closable="false"/>
      <el-alert v-if="projectInputUnsaved" :title='uiText("当前页有未保存修改；本次申请带入项目已保存的资料，未保存输入继续保留。")' type="info" :closable="false"/>
      <el-alert v-if="researchSource?.restricted" :title='uiText("来源新品项目受权限限制，请联系项目工程负责人查看或补充。")' type="info" :closable="false"/>
      <section v-else-if="researchSource" class="research-source" :aria-label='uiText("新品项目带入资料")'>
        <div class="tools"><strong>{{ uiText("来自新品项目：") }}{{researchSource.productName}}</strong><span>{{researchSource.productCode}}</span><el-button link @click="router.push({path:'/product-research',query:{productId:researchSource.projectId}})">{{ uiText("查看来源项目") }}</el-button></div>
        <el-alert v-if="existingApplicationNotice" :title="existingApplicationNotice" type="warning" :closable="false"/>
        <p><template v-if="!researchSubmission">{{researchSource.phoneModelName||uiText('标准手机待补充')}} · </template>{{uiText('负责人')}}: {{researchSource.ownerName||uiText('待分配')}}</p>
        <p v-if="researchSource.referencesRestricted" class="source-warning">{{uiText('部分项目附件受权限限制，请联系有权限的工程人员补充。')}}</p>
      </section>
      <p class="application-flow-name">{{options?.processName||editing?.processName}}</p>
      <div v-if="editing&&!editing.applicationId&&'existingApplications' in editing&&editing.existingApplications?.length" class="tools" :aria-label='uiText("项目原开模申请")'><span>{{ uiText("已有申请，可继续原记录：") }}</span><el-button v-for="application in editing.existingApplications" :key="application.applicationId" link type="primary" @click="attempt(()=>openExisting(application.applicationId))">{{application.approvalNo||uiText('申请 #{value0}',{value0:(application.applicationId)})}} · {{application.model||uiText('标准手机待补充')}}</el-button></div>
      <el-form label-position="top" class="application-form" :class="{'research-application-form':!!researchSource}" :disabled="busy||uploading">
        <el-form-item v-if="!productFlow" :ref="(element:unknown)=>registerField('productLineId',element)" :label='uiText("开模产品")' :required="true"><el-select v-model="productLineId" :aria-label='uiText("开模产品")' :disabled="!!researchSource" clearable filterable @change="chooseProduct"><el-option v-for="p in productChoices" :key="p.productId" :value="p.productId" :disabled="!eligibleProductIds.has(p.productId)" :label="productLabel(p)"/></el-select><small>{{ uiText("在售产品及研发各阶段的新品可先填写草稿；正式提交前需补齐申请资料。") }}</small><el-alert v-if="executionIssue" :title="executionIssue" type="warning" :closable="false"/><div v-if="productIdentityMissing" class="tools"><el-button v-if="canMaintainProductIdentity" link type="primary" :disabled="busy||uploading" @click="openProductIdentity">{{ uiText("维护产品特定编码") }}</el-button><small v-else>{{ uiText("请联系有产品编码维护权限的工程人员补齐已确认编码。") }}</small></div><el-button v-if="researchSource" link type="primary" :disabled="busy||uploading" @click="refreshOptions">{{ uiText("刷新申请配置") }}</el-button><template v-if="configurationMissing"><el-button v-if="options?.administrator" link type="primary" @click="openConfiguration">{{ uiText("补充产品线配置") }}</el-button><small v-else>{{ uiText("请管理员在“开模申请进度 → 更多 → 产品线配置”补充映射；当前钉钉模板缺少对应 Tipo 时需先由审批管理员核对。") }}</small></template></el-form-item>
        <section v-if="researchSubmission" :ref="(element:unknown)=>registerField('engineeringPlan',element)" class="application-scope" :aria-label="uiText('本次开模规格')"><h3>{{uiText('本次开模规格')}}</h3><MoldScopeEditor :model-value="engineeringPlan" :disabled="busy||uploading||modelSyncing" allow-create @update:model-value="updateEngineeringPlan"/><small>{{uiText('这里只申请本次开模；沿用已有模具在工程关联流程办理。')}}</small></section>
        <EngineeringPlanEditor v-else :ref="(element:unknown)=>registerField('engineeringPlan',element)" :model-value="engineeringPlan" :product-line-id="productLineId" :disabled="busy||uploading" @update:model-value="updateEngineeringPlan" @relation-selected="adoptEngineeringTarget"/>
        <el-form-item v-if="tipoControl&&productLineId" :ref="(element:unknown)=>registerField('tipo',element)" :label='uiText("审批类型（随产品匹配）")' :required="tipoControl.required">
          <small v-if="automaticTipo" class="automatic-tipo" :aria-label='uiText("自动匹配的审批类型")'>{{ uiText("已按所选产品匹配：") }}{{form.tipo}}</small>
          <el-select v-else-if="tipoChoices.length" v-model="form.tipo" clearable aria-label="tipo"><el-option v-for="tipo in tipoChoices" :key="tipo" :value="tipo" :label="tipoSelectable(tipo)?tipo:uiText('{value0} · 不可新申请',{value0:(tipo)})" :disabled="!tipoSelectable(tipo)"/></el-select>
          <small v-else>{{researchSubmission?uiText('当前模板暂无允许的新品审批类型，请审批管理员核对原模板。'):uiText('补齐所选产品的审批映射后自动带出；当前可先保存草稿。')}}</small>
        </el-form-item>
<el-form-item v-for="c in inputControls" :key="c.key" :ref="(element:unknown)=>registerField(c.key,element)" :label="c.label" :required="c.required"><el-select v-if="c.options.length" v-model="form[c.key]" clearable :aria-label="c.key"><el-option v-for="v in c.options" :key="v" :value="v" :label="v"/></el-select><el-date-picker v-else-if="c.type==='DDDateField'" v-model="form[c.key]" value-format="YYYY-MM-DD" :aria-label="c.key"/><el-input v-else v-model="form[c.key]" :type="c.type==='TextareaField'?'textarea':'text'" :rows="3" :aria-label="c.key" :maxlength="c.type==='TextareaField'?4000:255"/></el-form-item>
        <el-form-item v-if="targets.existingApplications?.some(a=>a.applicationId!==editing?.applicationId)" :label='uiText("已有进行中申请")'><el-button v-for="application in targets.existingApplications?.filter(a=>a.applicationId!==editing?.applicationId)" :key="application.applicationId" size="small" @click="openExisting(application.applicationId)">{{ uiText("继续已有申请 ") }}{{application.approvalNo||`#${application.applicationId}`}}</el-button></el-form-item>
        <el-form-item v-if="!researchSubmission&&executionIntent==='new'&&(targets.items.length||targets.existingApplications?.length||newApplication)" :label='uiText("重新开模依据")'><el-checkbox v-model="newApplication">{{ uiText("明确另建不同模具主档，并填写原因") }}</el-checkbox></el-form-item>
        <el-form-item v-if="engineeringPlan.handling==='new_set'" :label='uiText("加开所属模具主档")'><span>{{targetItemId?`#${targetItemId}`:uiText('选择已确认关系后带出原主档；不按业务手机重新分配编码。')}}</span></el-form-item>
        <el-form-item v-if="!researchSubmission&&engineeringPlan.handling!=='new_set'&&targets.projects.length" :label='uiText("已有型号项目")'><el-select v-model="targetProjectId" clearable :aria-label='uiText("已有型号项目")'><el-option v-for="project in targets.projects" :key="project.projectId" :value="project.projectId" :label="project.productName"/></el-select><small>{{ uiText("请明确选择；审批同意后直接关联此项目。") }}</small></el-form-item>

        <el-form-item v-if="researchReapplication"><el-checkbox v-model="newApplication">{{uiText('同型号重新申请，并填写原因')}}</el-checkbox></el-form-item>
        <el-form-item v-if="!researchSubmission||researchReapplication||engineeringPlan.handling==='new_set'" :label='uiText("新增套／重新开模原因")'><el-input v-model="executionReason" type="textarea" :rows="3" maxlength="2000" :placeholder='uiText("新增实物套或另建模具时填写原因")'/></el-form-item>
        <el-form-item v-if="!researchSubmission" :label='uiText("旧资料原编码核对")'><PhoneCodeSelector v-model="phoneCodeItemId" :disabled="!!standardPhone" :members="currentPhoneCodes" :aria-label='uiText("旧资料原编码核对")'/><small>{{ uiText("已有编码不因成员扩展改变；未分配的标准手机不自动编码。") }}</small></el-form-item>
        <el-form-item v-if="editing?.reservedCompatibility" :label='uiText("保留历史已确认共模套")'><span>{{editing.reservedCompatibility.moldName}} · {{uiText('原刻码')}} {{editing.reservedCompatibility.engravedCode}} · {{editing.reservedCompatibility.physicalCode}}</span><small>{{editing.reservedCompatibility.parts.map(part=>part.code).join(' / ')}}</small></el-form-item>
      </el-form><el-form v-if="options?.submitter" label-position="top" :disabled="busy||uploading"><el-form-item :label='uiText("实际钉钉发起人")'>{{ options.submitter.name }}{{ uiText("（当前 PLM 账号绑定身份）") }}</el-form-item><el-form-item v-if="options.submitter.departments.length" :label='uiText("钉钉发起部门")'><el-select v-model="departmentId" :aria-label='uiText("钉钉发起部门")'><el-option v-for="d in options.submitter.departments" :key="d.id" :value="d.id" :label="d.name"/></el-select></el-form-item></el-form><p v-if="!researchSubmission">{{uiText('模具编码：')}}{{generated}}</p>
      <section v-for="section in fileSections" :key="section.key" :ref="(element:unknown)=>registerField(section.key,element)" class="application-file-section" :aria-label="uiText(section.label)">
       <div class="file-section-heading"><strong><span v-if="fileRequired(section.key)" class="required-marker">*</span>{{uiText(section.label)}}</strong><span>{{fileRequired(section.key)?uiText('必填'):uiText('选填')}} · {{savedFiles(section.category).length+sourceFiles(section.category).length}} {{section.category==='attachment'?uiText('份'):uiText('张')}}</span></div>
       <label v-if="engineeringPlan.handling!=='reuse'" class="application-upload">{{uiText(section.upload)}}<input :aria-label="uiText(section.upload)" type="file" :accept="section.accept" multiple :disabled="uploading||busy" @change="upload($event,section.category)"/></label>
       <small v-if="section.key==='keyPhotos'">{{uiText('至少一张；项目图片自动带入，无需重复上传')}}</small>
       <div class="application-files" :aria-label="section.category==='key_photo'?uiText('申请图片与附件'):undefined">
        <MoldApplicationFile v-for="file in savedFiles(section.category)" :key="file.attachmentId" :application-id="editing!.applicationId!" :file="file" :active="editor"/>
        <article v-for="file in sourceFiles(section.category)" :key="file.id" class="source-file-card"><div v-if="editor&&sourceAttachments[file.id]&&section.category==='key_photo'" class="source-file-thumbnail"><DocumentPreview :file="sourceAttachments[file.id]!" compact/></div><div class="source-file-details"><p>{{file.name}}<span v-if="file.versionNo"> · {{file.versionNo}}</span></p><small>{{uiText('保存草稿时带入')}}</small><div v-if="editor&&sourceAttachments[file.id]" class="tools"><el-button v-if="sourceAttachments[file.id]!.previewable" link @click="attempt(()=>sourceViewer.viewAttachment(sourceAttachments[file.id]!))">{{uiText('预览来源文件')}}</el-button><el-button link @click="attempt(()=>sourceViewer.downloadFile(sourceAttachments[file.id]!))">{{uiText('下载来源文件')}}</el-button></div><p v-else-if="sourceAttachmentErrors[file.id]" role="alert">{{sourceAttachmentErrors[file.id]}} <el-button link @click="loadSourceFiles(editing)">{{uiText('重试来源文件')}}</el-button></p></div></article>
       </div>
      </section>
      <el-alert v-if="failedUploads.length" :title='uiText("有文件上传失败，审批提交已暂停")' type="error" :closable="false"><p v-for="pending in failedUploads" :key="pending.selectionId">{{ pending.file.name }} <el-button text :disabled="uploading" @click="attempt(()=>discardPending(pending.selectionId))">{{ uiText("移除此选择") }}</el-button></p><el-button :disabled="uploading" @click="attempt(uploadPending)">{{ uiText("重试失败文件") }}</el-button></el-alert>
      <p v-for="f in editing?.files?.filter(f=>f.downloadStatus==='pending_upload')" :key="f.attachmentId">{{ f.fileName }}{{ uiText("：已登记但未归档，请重新选择此文件上传，或 ") }}<el-button text @click="attempt(()=>discardStored(f))">{{ uiText("移除未上传选择") }}</el-button></p>
      <template #footer><template v-if="engineeringPlan.handling==='reuse'"><el-button type="primary" :disabled="busy||uploading||!!engineeringIssues.length" @click="attempt(confirmReuse)">{{uiText('确认沿用，不发起新审批')}}</el-button><p>{{uiText('原模具、生产编码和库存不变；正式采用须在原模具设计阶段办理。')}}</p><small v-for="issue in engineeringIssues" :key="issue" role="alert">{{uiText(issue)}}</small></template><template v-else><div class="tools"><el-button :disabled="busy||uploading||modelSyncing" @click="attempt(save)">{{ uiText("保存草稿") }}</el-button><el-button v-if="requiresCodes&&engineeringPlan.handling!=='pending'" :disabled="busy||uploading||!!executionIssue" @click="attempt(reserve)">{{uiText('生成 / 预留编码')}}</el-button><el-button type="primary" :disabled="!options?.canSubmit||!templateHoleReady||!editing?.applicationId||editing.status!=='draft'||(requiresCodes&&!editing.generatedCodes)||unsaved||flowMigration||!filesComplete||uploading||busy||!!executionIssue||!!submissionMissing.length||modelSyncing" @click="attempt(submit)">{{ uiText("提交钉钉审批") }}</el-button></div><small v-if="!templateHoleReady" role="alert">{{uiText(templateHoleIssue)}}</small><small v-else-if="!options?.canSubmit">{{ options?.submissionBlockReason||uiText('钉钉提交当前未向此账号开放') }}</small><small v-else-if="!editing?.applicationId">{{uiText('请先保存草稿，带入项目文件')}}</small><small v-else-if="unsaved||flowMigration">{{requiresCodes?uiText('有未保存修改，请先保存并重新核对编码'):uiText('有未保存修改，请先保存草稿')}}</small><div v-if="missingControls.length" class="application-missing" role="alert"><span>{{uiText("送审还缺：")}}</span><el-button v-for="control in missingControls" :key="control.key" link type="primary" @click="locateField(control.key)">{{control.label}}</el-button></div></template></template>
    </el-drawer>
</template>
<style scoped>
.application-scope{font-size:12px;border:1px solid var(--el-border-color-light);border-radius:4px;padding:10px 12px;margin:8px 0 12px}.application-scope h3{font-size:13px;margin:0 0 10px}.application-scope>small{display:block;margin-top:4px;color:var(--el-text-color-secondary);line-height:1.6}
.research-source{padding:10px 12px;margin-bottom:12px;background:var(--el-fill-color-light);border:1px solid var(--el-border-color-lighter);border-radius:4px;font-size:12px;overflow-wrap:anywhere}.research-source p{margin:5px 0;line-height:1.6}.source-warning{color:var(--el-color-warning-dark-2)}
.application-files{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,280px),1fr));gap:8px;margin-top:12px}
.application-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px}.application-form .el-select,.application-form .el-date-editor{width:100%}.tools{display:flex;gap:6px;flex-wrap:wrap}.tools .el-button+.el-button{margin-left:0}small{font-size:12px;color:var(--el-text-color-secondary)}@media(max-width:800px){.application-form{grid-template-columns:1fr}}
.application-form :deep(textarea){field-sizing:content}
.research-application-form{display:flex!important;flex-direction:column;gap:0}.research-application-form :deep(.el-form-item__label){font-weight:650;color:#243a54}.research-application-form :deep(.el-form-item__content){min-width:0}.application-file-section{margin:12px 0;padding:12px;border:1px solid var(--el-border-color-light);border-radius:4px}.file-section-heading{display:flex;gap:10px;align-items:center;margin-bottom:8px;color:#243a54}.file-section-heading>span{font-size:12px;color:var(--el-text-color-secondary)}.required-marker{color:var(--el-color-danger);margin-right:4px}.application-upload{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin:8px 0}.application-missing{display:flex;align-items:center;flex-wrap:wrap;gap:6px}.application-flow-name{font-size:12px;color:var(--el-text-color-secondary)}.source-file-card{display:flex;align-items:flex-start;flex-wrap:wrap;gap:10px;min-width:0;overflow-wrap:anywhere;border:1px solid var(--el-border-color-lighter);padding:8px}.source-file-thumbnail{flex:0 0 112px;width:112px;height:84px}.source-file-thumbnail :deep(.document-preview--compact),.source-file-thumbnail :deep(.document-preview--compact img){height:84px;max-height:84px}.source-file-details{flex:1 1 120px;min-width:0}.source-file-details p{margin:0 0 4px}.source-file-details .tools{gap:4px}.application-file-section .application-files{grid-template-columns:repeat(auto-fit,minmax(min(100%,240px),1fr))}
</style>
