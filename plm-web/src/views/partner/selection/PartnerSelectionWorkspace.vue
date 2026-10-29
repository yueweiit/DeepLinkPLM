<script setup lang="ts">
import {computed,nextTick,onBeforeUnmount,reactive,ref,watch} from 'vue'
import {useElementSize,useResizeObserver} from '@vueuse/core'
import {onBeforeRouteLeave,onBeforeRouteUpdate,useRoute,useRouter} from 'vue-router'
import {ElMessage,ElMessageBox,type InputInstance} from 'element-plus'
import PageContainer from '@/components/PageContainer/index.vue'
import PhoneModelMultiSelect from '@/components/PhoneModelMultiSelect/index.vue'
import {fileBlob,newRequestId,partnerOptions,saveBlob} from '@/api/modules/partner'
import {operationCapabilities} from '@/api/modules/partnerOperations'
import {supplierRanges,type SupplierRange} from '@/api/modules/supplierRanges'
import {batchSelectionRows,transitionSelectionRows,patchSelectionRows,saveSelection,selectionDetail,selectionOriginals,selectionPage,selectionRows} from '@/api/modules/partnerSelections'
import type {SelectionPage,SelectionRow,SelectionSheet,SelectionReceiptResult} from '@/types/partnerSelections'
import type {BusinessData,PartnerOptions,PartnerFile} from '@/types/partner'
import {cloneSelection,fillableColumns,frozenSelectionRow,newSelectionRow,rectangularChanges,restrictedSelectionWriteFields,selectionRowFiles} from '@/utils/partnerSelections'
import {partnerError} from '@/utils/partnerError'
import {catalogPhoneSummary} from '../catalog/catalogPresentation'
import PartnerFiles from '../PartnerFiles.vue'
import PartnerPhoto from '../PartnerPhoto.vue'
import SelectionRowEditor from './SelectionRowEditor.vue'
import SelectionFileTasks from './SelectionFileTasks.vue'
import SelectionReceiptDialog from './SelectionReceiptDialog.vue'
import CatalogListingDialog from '../operations/CatalogListingDialog.vue'
const route=useRoute(),router=useRouter(),id=computed(()=>Number(route.query.id)||0),detail=ref<SelectionSheet>(),loading=ref(false),busy=ref(false),fileBusy=ref(false),error=ref('')
const options=ref<PartnerOptions>({specs:[],suppliers:[],customers:[],canReadMoney:false,canWriteMoney:false,canConfirmMoney:false}),caps=ref({engineering:false,costs:false,listing:false}),sheets=ref<SelectionPage>({content:[],totalElements:0,page:1,size:30,totalPages:0}),sheetFilter=reactive({keyword:'',page:1,size:30,purpose:'my_selection' as 'my_selection'|'historical'})
const rows=ref<SelectionRow[]>([]),total=ref(0),filter=reactive({keyword:'',supplierCode:'',state:'',page:1,size:30}),checked=ref(new Set<string>()),baselineRows=ref(new Map<string,string>()),headerBaseline=ref(''),requestId=ref(newRequestId()),editor=ref<SelectionRow>(),editorReadOnly=ref(false),editorBaseline=ref('')
const pasteOpen=ref(false),pasteText=ref(''),pasteError=ref(''),pasteStart=ref('name'),pasteAnchor=ref(''),pasteCount=ref(0),pasteChanges=ref<Partial<SelectionRow>[]>([])
const bulkOpen=ref(false),bulkScope=ref<'checked'|'page'|'filter'>('checked'),bulk=reactive<Record<string,string>>({}),bulkFields=ref<string[]>([]),bulkClear=ref<string[]>([])
const bulkPhonesEnabled=ref(false),bulkPhones=ref<number[]>([]),bulkRangesEnabled=ref(false),bulkModelRef=ref(''),bulkColorRef=ref('')
const checkedSuppliers=ref(new Map<string,string>()),bulkModelRanges=ref<SupplierRange[]>([]),bulkColorRanges=ref<SupplierRange[]>([])
const fileMode=ref<'import'|'export'>('import'),filesOpen=ref(false),listingRow=ref<SelectionRow>()
const receivingRow=ref<SelectionRow>(),receivingSpec=ref<number>(),receiptRows=ref<SelectionRow[]>([]),checkedRows=ref(new Map<string,SelectionRow>())
const materialsOpen=ref(false),materialsRow=ref<SelectionRow>(),rowPhotoVersions=reactive<Record<string,number>>({})
const originalsOpen=ref(false),originalsPage=ref(1),originals=ref<PartnerFile[]>([]),originalsTotal=ref(0)
let generation=0
const gridElement=ref<HTMLElement>(),nameInputs=ref<InputInstance[]>([]),renamingRow=ref('')
const {width:tableWidth}=useElementSize(gridElement),compactControls=computed(()=>tableWidth.value>0&&tableWidth.value<=800)
useResizeObserver(gridElement,()=>{void nextTick(()=>nameInputs.value.forEach(input=>input.resizeTextarea?.()))})
const historicalSource=computed(()=>detail.value?.catalogSource===true||detail.value?.purpose==='catalog_source')
const historicalDraft=computed(()=>detail.value?.purpose==='historical_draft')
const canWrite=computed(()=>caps.value.engineering&&detail.value?.canWrite!==false&&!historicalSource.value),canReadCost=computed(()=>caps.value.costs&&detail.value?.canReadCost!==false),locked=computed(()=>loading.value||busy.value||fileBusy.value)
const suppliers=computed(()=>options.value.suppliers.map(supplier=>({code:String(supplier.code),name:String(supplier.name)}))),supplierName=(code:string)=>suppliers.value.find(supplier=>supplier.code===code)?.name||code||'供方待补'
const headerState=()=>detail.value?JSON.stringify({name:detail.value.name,refs:detail.value.refs}):''
const dirty=computed(()=>headerState()!==headerBaseline.value||rows.value.some(row=>JSON.stringify(row)!==baselineRows.value.get(row.rowId))),editorDirty=computed(()=>!!editor.value&&JSON.stringify(editor.value)!==editorBaseline.value)
const editableRows=computed(()=>rows.value.filter(row=>row.canReviseSelection!==false&&!frozenSelectionRow(row)&&!row.discarded)),selectableRows=computed(()=>rows.value),allPageChecked=computed(()=>selectableRows.value.length>0&&selectableRows.value.every(row=>checked.value.has(row.rowId)))
const frozenChecked=computed(()=>[...checked.value].some(rowId=>{const row=checkedRows.value.get(rowId);return !!row&&frozenSelectionRow(row)}))
function acceptHeader(value:SelectionSheet){detail.value={...value,rows:[],files:value.files||[],refs:value.refs||[],events:value.events||[]};headerBaseline.value=headerState();requestId.value=newRequestId()}
function acceptRows(values:SelectionRow[]){renamingRow.value='';rows.value=values.map(row=>({...newSelectionRow(),...row,sampleQty:row.sampleQty===null||String(row.sampleQty)===''?undefined:row.sampleQty,rangeRefs:row.rangeRefs||[],pairings:row.pairings||[],selectedPairs:row.selectedPairs||[]}));baselineRows.value=new Map(rows.value.map(row=>[row.rowId,JSON.stringify(row)]));for(const row of rows.value){checkedSuppliers.value.set(row.rowId,row.supplierCode);if(checked.value.has(row.rowId))checkedRows.value.set(row.rowId,cloneSelection(row))}}
async function fetchRows(){if(!id.value)return;const data=await selectionRows(id.value,{q:filter.keyword,supplierCode:filter.supplierCode,state:filter.state,page:filter.page,size:filter.size});acceptRows(data.content);total.value=data.totalElements}
async function load(){const seq=++generation;loading.value=true;error.value='';try{const [permissions,opt]=await Promise.all([operationCapabilities(),partnerOptions()]);if(seq!==generation)return;caps.value=permissions;options.value=opt;if(id.value){const data=await selectionDetail(id.value);if(seq!==generation)return;acceptHeader(data);if(historicalSource.value){rows.value=[];total.value=0}else await fetchRows()}else{detail.value=undefined;rows.value=[];headerBaseline.value='';sheets.value=await selectionPage({...sheetFilter})}}catch(e){if(seq===generation)error.value=partnerError(e)}finally{if(seq===generation)loading.value=false}}
watch(()=>route.query.id,()=>{checked.value=new Set();checkedRows.value=new Map();editor.value=undefined;filter.page=1;void load()},{immediate:true})
function mutableChanges(row:SelectionRow){const value=cloneSelection(row) as unknown as Record<string,unknown>;if(row.sampleQty===undefined){const original=JSON.parse(baselineRows.value.get(row.rowId)||'{}') as {sampleQty?:unknown};if(original.sampleQty!==undefined&&original.sampleQty!==null&&original.sampleQty!=='')value.sampleQty=null}for(const key of ['rowId','rowVersion','productId','productCode','specId','specIds','specLinks','sampleBatches','sampleBatchCodes','fileDetails','confirmed','confirmedAt','confirmationSnapshot','rangeSnapshots','catalogStyleId','catalogStyleCode','catalogStyleLabel','rawQuoteFields','phones','availablePhones','unavailablePhones','sourcePhoneModelIds','sourcePhones','catalogSourceSnapshot','canReviseSelection','selectionChangeBlockedReason'])delete value[key];if(!canReadCost.value)for(const key of restrictedSelectionWriteFields)delete value[key];return value as Partial<SelectionRow>}
async function save(){if(!detail.value||!canWrite.value||busy.value)return false;busy.value=true;error.value='';try{if(headerState()!==headerBaseline.value)acceptHeader(await saveSelection({name:detail.value.name,refs:detail.value.refs,version:detail.value.version,requestId:requestId.value},id.value));const changed=rows.value.filter(row=>JSON.stringify(row)!==baselineRows.value.get(row.rowId));if(changed.length)acceptHeader(await patchSelectionRows(id.value,{version:detail.value.version,requestId:requestId.value,operations:changed.map(row=>({op:row.discarded?'exclude':'upsert',rowId:row.rowId,rowVersion:row.rowVersion,changes:mutableChanges(row)}))}));await fetchRows();ElMessage.success('本次增量已保存；未读取的行未被覆盖');return true}catch(e){error.value=partnerError(e);return false}finally{busy.value=false}}
async function ensureSaved(){return !dirty.value||await save()}
async function discardGuard(){if(locked.value)return false;if(!dirty.value&&!editorDirty.value)return true;try{await ElMessageBox.confirm('放弃尚未保存的本页或本行修改？','保留未保存资料',{confirmButtonText:'放弃并离开',cancelButtonText:'继续填写'});return true}catch{return false}}
onBeforeRouteLeave(discardGuard);onBeforeRouteUpdate(to=>to.query.id===route.query.id?true:discardGuard())
function beforeUnload(event:BeforeUnloadEvent){if(dirty.value||editorDirty.value){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload);onBeforeUnmount(()=>{generation++;window.removeEventListener('beforeunload',beforeUnload)})
async function changePage(value:number){if(!await ensureSaved())return;filter.page=value;loading.value=true;try{await fetchRows()}catch(e){error.value=partnerError(e)}finally{loading.value=false}}
async function search(){if(!await ensureSaved())return;await changePage(1)}
async function changeSize(value:number){if(!await ensureSaved())return;filter.size=value;await changePage(1)}
async function reload(){if(await discardGuard())await load()}
async function create(){if(!caps.value.engineering||!await discardGuard())return;try{const {value}=await ElMessageBox.prompt('给这份选品资料起一个名称','新建选品资料',{inputValidator:value=>!!value?.trim()||'请填写名称'});busy.value=true;const data=await saveSelection({name:value.trim(),refs:[],version:0,requestId:newRequestId()});busy.value=false;await router.push({path:'/partner-selections',query:{id:data.id}})}catch(e){if(e!=='cancel'&&e!=='close')error.value=partnerError(e)}finally{busy.value=false}}
function edit(row:SelectionRow,readOnly=false){editorReadOnly.value=readOnly;editor.value=cloneSelection(row);editorBaseline.value=JSON.stringify(editor.value)}
async function closeEditor(){if(editorDirty.value){try{await ElMessageBox.confirm('放弃本行尚未应用的修改？','保留本行填写',{confirmButtonText:'放弃修改',cancelButtonText:'继续填写'})}catch{return}}editor.value=undefined}
function applyEditor(){if(!editor.value)return;const index=rows.value.findIndex(row=>row.rowId===editor.value!.rowId);if(index>=0)rows.value[index]=cloneSelection(editor.value);else rows.value.unshift(cloneSelection(editor.value));editor.value=undefined}
async function chooseFromCatalog(row?:SelectionRow){if(!detail.value||!canWrite.value||locked.value||!await ensureSaved())return;await router.push({path:'/partner-products',query:{supplierCode:row?.supplierCode||filter.supplierCode||undefined,selectionId:id.value,from:route.fullPath}})}
function checkRow(row:SelectionRow,value:boolean){checkedSuppliers.value.set(row.rowId,row.supplierCode);if(value){checked.value.add(row.rowId);checkedRows.value.set(row.rowId,cloneSelection(row))}else{checked.value.delete(row.rowId);checkedRows.value.delete(row.rowId)}}
function checkPage(value:boolean){selectableRows.value.forEach(row=>checkRow(row,value))}
function clearChecked(){checked.value=new Set();checkedRows.value=new Map()}
const hasReceivedSamples=(row:SelectionRow)=>!!(row.sampleBatches?.length||row.sampleBatchCodes?.length)
const selectionHasDownstream=(row:SelectionRow)=>hasReceivedSamples(row)||!!(row.productId||row.specId||row.specLinks?.length)
const canReviseSelection=(row:SelectionRow)=>canWrite.value&&row.canReviseSelection!==false&&!selectionHasDownstream(row)
const transitionRequests=new Map<string,string>()
const checkedTargets=computed(()=>[...checked.value].map(rowId=>rows.value.find(row=>row.rowId===rowId)||checkedRows.value.get(rowId)).filter((row):row is SelectionRow=>!!row))
const checkedComplete=computed(()=>checkedTargets.value.length===checked.value.size)
const singleChecked=computed(()=>checked.value.size===1&&checkedComplete.value?checkedTargets.value[0]:undefined)
const singleOnPage=computed(()=>!!singleChecked.value&&rows.value.some(row=>row.rowId===singleChecked.value!.rowId))
const checkedCancelled=computed(()=>checkedTargets.value.some(row=>row.discarded))
const checkedActive=computed(()=>checkedTargets.value.some(row=>!row.discarded))
const canCancelChecked=computed(()=>checked.value.size>0&&checkedComplete.value&&checkedTargets.value.every(row=>canReviseSelection(row)&&!row.discarded))
const canRestoreChecked=computed(()=>checked.value.size>0&&checkedComplete.value&&checkedTargets.value.every(row=>canReviseSelection(row)&&row.discarded))
const canReceiveChecked=computed(()=>canWrite.value&&checked.value.size>0&&checkedComplete.value&&checkedTargets.value.every(row=>!row.discarded&&!!row.catalogStyleId)||canWrite.value&&checked.value.size===1&&!!singleChecked.value&&!singleChecked.value.discarded&&!!(singleChecked.value.specId||singleChecked.value.specLinks?.length))
const checkedBulkBlocked=computed(()=>!checkedComplete.value||checkedTargets.value.some(row=>row.discarded||frozenSelectionRow(row)||!canReviseSelection(row)))
async function transitionRows(targets:SelectionRow[],action:'edit'|'cancel'|'restore'){
 if(!detail.value||locked.value||!canWrite.value||!targets.length||!await ensureSaved())return
 const current=targets.map(row=>rows.value.find(item=>item.rowId===row.rowId)||checkedRows.value.get(row.rowId))
 if(current.some(row=>!row||!canReviseSelection(row)||(action==='restore'?!row.discarded:row.discarded))){error.value='已勾选款式包含不适用的记录，请按当前状态分别操作；不会跳过任何款式。';return}
 const values=current as SelectionRow[],key=action+':'+values.map(row=>`${row.rowId}:${row.rowVersion}`).sort().join('|'),request=transitionRequests.get(key)||newRequestId();transitionRequests.set(key,request);busy.value=true;error.value=''
 try{acceptHeader(await transitionSelectionRows(id.value,{version:detail.value.version,requestId:request,action,rows:values.map(row=>({rowId:row.rowId,rowVersion:row.rowVersion||0}))}));transitionRequests.delete(key);values.forEach(row=>checkRow(row,false));await fetchRows();if(action==='edit'){const fresh=rows.value.find(item=>item.rowId===values[0].rowId);if(fresh)edit(fresh)}ElMessage.success(action==='cancel'?'已取消选样，原资料保留，可恢复':action==='restore'?'已恢复选样，可继续修改或登记收样':'可以修改本次选样')}catch(e){error.value=partnerError(e)}finally{busy.value=false}
}
function transitionChecked(action:'cancel'|'restore'){if(action==='cancel'?!canCancelChecked.value:!canRestoreChecked.value)return;void transitionRows(checkedTargets.value,action)}
function viewChecked(){if(singleChecked.value&&!locked.value)edit(singleChecked.value,true)}
function reviseChecked(){if(singleChecked.value&&singleOnPage.value&&!locked.value)reviseSelection(singleChecked.value)}
function renameChecked(){const row=singleChecked.value;if(!row||!singleOnPage.value||!canReviseSelection(row)||frozenSelectionRow(row)||row.discarded||locked.value)return;renamingRow.value=renamingRow.value===row.rowId?'':row.rowId}
function receiveChecked(){if(!canReceiveChecked.value)return;if(singleChecked.value&&!singleChecked.value.catalogStyleId)registerSample(singleChecked.value);else void openReceipts()}

function reviseSelection(row:SelectionRow){if(!canReviseSelection(row)||row.discarded)return;if(frozenSelectionRow(row)){void transitionRows([row],'edit');return}edit(row)}

function openPaste(){pasteText.value='';pasteChanges.value=[];pasteError.value='';pasteCount.value=0;pasteAnchor.value=editableRows.value[0]?.rowId||'';pasteOpen.value=true}
function checkPaste(){pasteError.value='';try{pasteChanges.value=rectangularChanges(pasteText.value,pasteStart.value);pasteCount.value=pasteChanges.value.length;const start=rows.value.findIndex(row=>row.rowId===pasteAnchor.value);if(start<0||start+pasteCount.value>rows.value.length)throw new Error('矩形填充只修改本页已有行；新增款式或型号请到供应商产品目录选择。');if(rows.value.slice(start,start+pasteCount.value).some(row=>frozenSelectionRow(row)||row.discarded))throw new Error('目标中包含已确认或已排除行，不能覆盖。')}catch(e){pasteCount.value=0;pasteError.value=partnerError(e)}}
async function applyPaste(){if(!detail.value||!pasteCount.value||!await ensureSaved())return;checkPaste();if(!pasteCount.value)return;const start=rows.value.findIndex(row=>row.rowId===pasteAnchor.value);pasteChanges.value.forEach((changes,index)=>Object.assign(rows.value[start+index],changes));pasteOpen.value=false}
function openBulk(){if(checkedBulkBlocked.value){error.value='已勾选款式包含已取消、已锁定或不可修改的记录，请调整选择后批量填充。';return}if(frozenChecked.value){error.value='请先通过“修改选样”解锁可改记录；已收样历史不可覆盖。';return}bulkFields.value=[];bulkClear.value=[];bulkPhonesEnabled.value=false;bulkRangesEnabled.value=false;bulkPhones.value=[];bulkModelRef.value='';bulkColorRef.value='';Object.keys(bulk).forEach(key=>delete bulk[key]);bulkOpen.value=true}
function batchCommand(command:string){if(command==='add')chooseFromCatalog();else if(command==='paste')openPaste();else openBulk()}
const bulkTargets=computed(()=>bulkScope.value==='page'?editableRows.value.map(row=>row.rowId):[...checked.value])
const bulkSupplier=computed(()=>{if(bulkScope.value==='filter')return filter.supplierCode||'';const codes=[...new Set(bulkTargets.value.map(rowId=>checkedSuppliers.value.get(rowId)||rows.value.find(row=>row.rowId===rowId)?.supplierCode||''))];return codes.length===1?codes[0]:''})
async function queryBulkRanges(type:'model'|'color',keyword=''){if(!bulkSupplier.value)return;try{const data=await supplierRanges(bulkSupplier.value,{type,page:1,size:20,keyword});if(type==='model')bulkModelRanges.value=data.content;else bulkColorRanges.value=data.content}catch(e){error.value=partnerError(e)}}
const queryBulkModels=(keyword:string)=>queryBulkRanges('model',keyword),queryBulkColors=(keyword:string)=>queryBulkRanges('color',keyword)
watch([bulkSupplier,bulkRangesEnabled],()=>{bulkModelRef.value='';bulkColorRef.value='';bulkModelRanges.value=[];bulkColorRanges.value=[];if(bulkRangesEnabled.value&&bulkSupplier.value){void queryBulkRanges('model');void queryBulkRanges('color')}})
async function applyBulk(){if(checkedBulkBlocked.value){error.value='已勾选款式包含不可修改的记录，不会跳过或部分修改。';return}if(frozenChecked.value){error.value='已锁定记录不可批量覆盖，请先办理修改选样。';return}if(!detail.value||!await ensureSaved())return;const changes:Record<string,unknown>={};for(const key of bulkFields.value){if(bulkClear.value.includes(key)){changes[key]='';continue}const value=bulk[key];if(!value?.trim())continue;if(key==='sampleQty'){const quantity=Number(value);if(!Number.isSafeInteger(quantity)||quantity<0){error.value='拟拿样合计必须是非负整数';return}changes[key]=quantity}else changes[key]=value}if(bulkPhonesEnabled.value)changes.phoneModelIds=[...bulkPhones.value];if(bulkRangesEnabled.value){if(!bulkSupplier.value||!bulkModelRef.value&&!bulkColorRef.value){error.value='范围请按同一家供应商分批，且至少选择一个已有范围。';return}const references=[];if(bulkModelRef.value)references.push({type:'model',reference:bulkModelRef.value,version:bulkModelRanges.value.find(range=>range.code===bulkModelRef.value)?.version});if(bulkColorRef.value)references.push({type:'color',reference:bulkColorRef.value,version:bulkColorRanges.value.find(range=>range.code===bulkColorRef.value)?.version});changes.rangeRefs=references}if(!Object.keys(changes).length)return;try{await ElMessageBox.confirm(`将${bulkScope.value==='filter'?'当前全部筛选结果':bulkTargets.value.length+'行'}的【${Object.keys(changes).map(key=>fieldLabel(key)).join('、')}】统一修改。其他字段、未指定类型的范围及真实供货配对保留；引用不符项须重新核对。`,'确认批量差异');busy.value=true;acceptHeader(await batchSelectionRows(id.value,{version:detail.value.version,requestId:newRequestId(),...(bulkScope.value==='filter'?{filterSnapshot:{...filter,version:detail.value.version}}:{rowIds:bulkTargets.value}),changes}));bulkOpen.value=false;await fetchRows()}catch(e){if(e!=='cancel'&&e!=='close')error.value=partnerError(e)}finally{busy.value=false}}
const fieldLabel=(key:string)=>({name:'款式名称',series:'系列',material:'材质／配置',specification:'规格原文',modelText:'型号原文',color:'颜色原文',moq:'起订条件',packaging:'包装',notes:'备注',sampleQty:'拟拿样行合计',phoneModelIds:'期望精确型号',rangeRefs:'型号／颜色范围引用'} as Record<string,string>)[key]||key
async function openFiles(mode:'import'|'export'){if(!await ensureSaved())return;fileMode.value=mode;filesOpen.value=true}
async function refreshFiles(){const currentRows=rows.value;acceptHeader(await selectionDetail(id.value));rows.value=currentRows}
function refsChanged(refs:BusinessData[]){if(detail.value)detail.value.refs=refs.map(value=>({id:Number(value.id),type:String(value.type||'file'),name:value.name,version:value.version}))}
const rowFiles=(row:SelectionRow)=>selectionRowFiles(row,detail.value?.files)
const rowImages=(row:SelectionRow)=>rowFiles(row).filter(item=>item.file?.type?.startsWith('image/'))
const materialFiles=computed(()=>materialsRow.value?rowFiles(materialsRow.value).map(item=>item.file).filter((file):file is PartnerFile=>!!file):detail.value?.files||[])
function openRowMaterials(row:SelectionRow){materialsRow.value=row;materialsOpen.value=true}
function refreshRowPhotos(){const rowId=materialsRow.value?.rowId;if(rowId)rowPhotoVersions[rowId]=(rowPhotoVersions[rowId]||0)+1}
const editorFiles=computed(()=>editor.value?rowFiles(editor.value).map(item=>item.file).filter((file):file is PartnerFile=>!!file):[])
const sourceFiles=computed(()=>[...new Map([...(detail.value?.sourceOriginals||[]),...(detail.value?.files||[]).filter(file=>!file.type?.startsWith('image/'))].map(file=>[file.id,file])).values()])
async function downloadSource(file:PartnerFile){try{saveBlob(await fileBlob(file.id,false),file.name)}catch(e){error.value=partnerError(e)}}
async function loadOriginals(){originalsOpen.value=true;try{const data=await selectionOriginals(id.value,originalsPage.value,20);originals.value=data.content;originalsTotal.value=data.totalElements}catch(e){error.value=partnerError(e)}}
const selectedPhones=(row:SelectionRow)=>(row.phones||[]).filter(phone=>!row.phoneModelIds.length||row.phoneModelIds.includes(phone.phoneModelId))
const modelCount=(row:SelectionRow)=>new Set(row.phoneModelIds.length?row.phoneModelIds:selectedPhones(row).map(phone=>phone.phoneModelId)).size
const hasLongModelText=(row:SelectionRow)=>!selectedPhones(row).length&&row.modelText.length>60
const modelSummary=(row:SelectionRow)=>catalogPhoneSummary({phones:selectedPhones(row),modelText:row.phoneModelIds.length?'型号资料待加载':hasLongModelText(row)?'型号原文见详情':row.modelText})
const selectedColors=(row:SelectionRow)=>[...new Set(row.selectedPairs.map(pair=>row.pairings.find(value=>value.phoneModelId===pair.phoneModelId&&value.colorId===pair.colorId)?.colorSnapshot?.name||'颜色资料待加载'))]
const colorSummary=(row:SelectionRow)=>row.selectedPairs.length?selectedColors(row).slice(0,2).join('、'):row.catalogStyleId?'收样时填写':row.color||'未提供'
function registerSample(row:SelectionRow){if(row.catalogStyleId){void openReceipts(row);return}if((row.specLinks?.length||0)>1){receivingRow.value=row;receivingSpec.value=undefined;return}goToSample(row,row.specLinks?.[0]?.specId||row.specId)}
async function openSupplierShipment(){if(!detail.value||locked.value||!await ensureSaved())return;if(!checked.value.size){error.value='请先勾选本次供应商要寄出的款式';return}const selected=[...checked.value].map(rowId=>rows.value.find(row=>row.rowId===rowId)||checkedRows.value.get(rowId));if(selected.some(row=>!row||row.discarded||!row.catalogStyleId)){error.value='请只选择有真实目录来源且未取消的款式';return}if(new Set(selected.map(row=>row!.supplierCode)).size!==1){error.value='请按供应商分开登记寄样';return}void router.push({path:'/partner-samples',query:{view:'shipments',newShipment:'supplier_inbound',selectionId:id.value,selectionRowIds:[...checked.value].join(','),from:route.fullPath}})}
async function openReceipts(single?:SelectionRow){if(!detail.value||locked.value||!await ensureSaved())return;const targets=single?[single.rowId]:[...checked.value];if(!targets.length){error.value='请先勾选本次实际收到的款式，再登记收样。';return}busy.value=true;error.value='';try{const selected=targets.map(rowId=>rows.value.find(row=>row.rowId===rowId)||checkedRows.value.get(rowId)).filter((row):row is SelectionRow=>!!row);if(selected.length!==targets.length){error.value='选样资料已改变，请刷新后重新勾选本次实际收到的款式。';return}if(selected.some(row=>!row.catalogStyleId||row.discarded)){error.value='本次包含已取消或无目录来源的行，请调整选择后登记收样；不会跳过已勾选款式。';return}receiptRows.value=selected}catch(e){error.value=partnerError(e)}finally{busy.value=false}}
async function receiptSaved(result:SelectionReceiptResult){if(detail.value)detail.value.version=result.version;clearChecked();try{acceptHeader(await selectionDetail(id.value));await fetchRows()}catch(e){error.value='收样已保存，列表刷新失败：'+partnerError(e)}}
function goToSample(row:SelectionRow,specId?:number){if(!specId)return;receivingRow.value=undefined;router.push({path:'/samples',query:{new:'1',sourceType:'partner',specId,supplierCode:row.supplierCode,selectionId:id.value,selectionRowId:row.rowId,from:route.fullPath}})}
</script>
<template><PageContainer :title="historicalSource?'历史供应商资料':'选品目录'" class="selection-workspace"><template #actions><el-button v-if="detail" :size="compactControls?'small':'default'" @click="router.push('/partner-selections')">{{compactControls?'返回':'返回资料列表'}}</el-button><el-button v-if="!detail&&caps.engineering" type="primary" @click="create">新建我方选品</el-button><template v-if="detail&&!historicalSource"><el-button :size="compactControls?'small':'default'" :disabled="locked" @click="openFiles('export')">{{compactControls?'导出模板':'导出含图模板'}}</el-button><el-button v-if="canWrite" :size="compactControls?'small':'default'" :disabled="locked" @click="openFiles('import')">{{compactControls?'导入已选Excel':'导入已选Excel／续传'}}</el-button><el-button :size="compactControls?'small':'default'" :disabled="locked" @click="reload">刷新</el-button><el-button v-if="canWrite" :size="compactControls?'small':'default'" type="primary" :disabled="locked||!dirty" @click="save">{{compactControls?'保存增量':'保存本页增量'}}</el-button></template></template>
 <el-alert v-if="error" :title="error" type="error" :closable="false"/>
 <template v-if="!detail"><el-radio-group v-model="sheetFilter.purpose" @change="sheetFilter.page=1;load()"><el-radio-button value="my_selection">样品选择</el-radio-button><el-radio-button value="historical">历史草稿（待归类）</el-radio-button></el-radio-group><div class="search-bar"><el-input v-model="sheetFilter.keyword" clearable placeholder="搜索选品资料名称" @keyup.enter="sheetFilter.page=1;load()"/><el-button @click="sheetFilter.page=1;load()">查询</el-button></div><div class="sheet-list" v-loading="loading"><button v-for="sheet in sheets.content" :key="sheet.id" class="sheet-card" @click="router.push({path:'/partner-selections',query:{id:sheet.id}})"><strong>{{sheet.name}}</strong><span>{{sheet.code}} · V{{sheet.version}}</span><small>{{sheetFilter.purpose==='historical'?'查看历史草稿（原资料保留）':'打开查看与继续选品'}}</small></button><el-empty v-if="!loading&&!sheets.content.length" description="暂无选品资料"/></div><el-pagination v-model:current-page="sheetFilter.page" :page-size="30" :total="sheets.totalElements" :pager-count="5" layout="total,prev,pager,next" @current-change="load"/></template>
 <template v-else-if="historicalSource"><el-alert title="这份历史资料已整理为供应商候选来源，不代表我方已选。原件与行记录只读保留；请在供应商目录挑选款式和具体型号。" type="info" :closable="false"><el-button link type="primary" @click="router.push('/partner-products?sourceManager=1')">打开供应商目录</el-button></el-alert></template><template v-else><div class="sheet-heading"><el-input v-model="detail.name" :disabled="!canWrite||locked" aria-label="选品资料名称"/><span class="muted">{{detail.code}} · V{{detail.version}} · {{dirty?'本页有未保存修改':'已保存'}}</span></div>
 <div v-if="historicalDraft" class="history-note"><span>历史草稿只读保留，不代表本次已选样。</span><el-button link type="primary" @click="router.push('/partner-products')">去供应商产品目录</el-button></div><section class="selection-documents"><strong>原件与附件</strong><template v-if="sourceFiles.length"><el-button v-for="file in sourceFiles.slice(0,1)" :key="file.id" link @click="downloadSource(file)">{{file.name}} · {{file.version}}</el-button></template><span v-else class="muted">暂无原件／附件；行图片直接显示在下方</span><el-button v-if="(detail.sourceOriginalCount||0)>1" link @click="originalsPage=1;loadOriginals()">全部原件（{{detail.sourceOriginalCount}}）</el-button><el-button size="small" :disabled="locked" @click="materialsRow=undefined;materialsOpen=true">{{canWrite?'上传／资料库':'资料库'}}（最近{{detail.files.length}}份）</el-button></section>
 <div class="search-bar"><el-input v-model="filter.keyword" clearable placeholder="搜索款名、原文、我方编号" @keyup.enter="search"/><el-select v-model="filter.supplierCode" clearable filterable placeholder="全部供应商" @change="search"><el-option v-for="supplier in suppliers" :key="supplier.code" :value="supplier.code" :label="supplier.name"/></el-select><el-select v-model="filter.state" clearable placeholder="全部业务状态" @change="search"><el-option label="候选草稿" value="draft"/><el-option label="已选择" value="selected"/><el-option label="已锁定选样" value="confirmed"/><el-option label="已取消" value="discarded"/></el-select><el-button @click="search">查询</el-button></div>
 <div class="batch-bar"><el-checkbox :model-value="allPageChecked" :disabled="locked" @change="checkPage(Boolean($event))">勾选本页</el-checkbox><span>跨页已勾选 {{checked.size}} 行</span><el-button link :disabled="locked||!checked.size" @click="clearChecked">取消已勾选</el-button><template v-if="canWrite"><el-dropdown v-if="compactControls" :disabled="locked" @command="batchCommand"><el-button size="small" :disabled="locked">录入／批量 ▾</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="add">从目录选款</el-dropdown-item><el-dropdown-item command="paste" :disabled="checkedBulkBlocked">Excel矩形粘贴</el-dropdown-item><el-dropdown-item command="bulk" :disabled="checkedBulkBlocked">多字段批量填充</el-dropdown-item></el-dropdown-menu></template></el-dropdown><template v-else><el-button size="small" :disabled="locked" @click="chooseFromCatalog()">从目录选款</el-button><el-button size="small" :disabled="locked||checkedBulkBlocked" @click="openPaste">Excel矩形粘贴</el-button><el-button size="small" :disabled="locked||checkedBulkBlocked" @click="openBulk">多字段批量填充</el-button></template></template></div>
 <div v-if="checked.size" class="selection-actions" aria-label="已勾选款式操作"><template v-if="singleChecked"><el-button size="small" :disabled="locked" @click="viewChecked">查看资料</el-button><template v-if="canWrite"><el-button v-if="canReviseSelection(singleChecked)&&!singleChecked.discarded" size="small" :disabled="locked||!singleOnPage" @click="reviseChecked">修改选样</el-button><el-button v-if="canReviseSelection(singleChecked)&&!frozenSelectionRow(singleChecked)&&!singleChecked.discarded" size="small" :disabled="locked||!singleOnPage" @click="renameChecked">{{renamingRow===singleChecked.rowId?'完成改名':'改名'}}</el-button><el-button v-if="!singleChecked.discarded" size="small" :disabled="locked" @click="chooseFromCatalog(singleChecked)">增选规格</el-button></template><el-button v-if="singleChecked.productId" size="small" :disabled="locked" @click="router.push({path:'/partner-products',query:{id:singleChecked.productId,specId:singleChecked.specId,from:route.fullPath}})">产品草稿</el-button><el-button v-if="singleChecked.catalogStyleId&&singleChecked.confirmed&&!singleChecked.discarded&&caps.listing" size="small" :disabled="locked" @click="listingRow=singleChecked">准备上架</el-button></template><span v-else class="selection-action-hint">查看、修改、改名和增选规格需只勾选 1 款</span><template v-if="canWrite"><el-button v-if="checkedActive&&checkedTargets.some(canReviseSelection)" size="small" type="danger" plain :disabled="locked||!canCancelChecked" @click="transitionChecked('cancel')">取消选样</el-button><el-button v-if="checkedCancelled&&checkedTargets.some(canReviseSelection)" size="small" :disabled="locked||!canRestoreChecked" @click="transitionChecked('restore')">恢复选样</el-button><el-button v-if="checkedTargets.some(row=>row.catalogStyleId&&!row.discarded)" class="supplier-shipment-entry" size="small" :disabled="locked||checkedCancelled" @click="openSupplierShipment">登记供应商寄样</el-button><el-button v-if="checkedTargets.some(row=>!row.discarded&&(row.catalogStyleId||row.specId||row.specLinks?.length))" class="receipt-entry" size="small" type="primary" :disabled="locked||!canReceiveChecked" @click="receiveChecked">登记收样</el-button></template><span v-if="singleChecked&&!singleOnPage&&canReviseSelection(singleChecked)" class="selection-action-hint">修改或改名请返回该款所在页</span><span v-if="checkedCancelled&&checkedActive" class="selection-action-hint">已取消与未取消款式请分开操作</span></div><p v-else class="selection-action-hint">勾选款式后，在这里查看、修改或登记收样。</p>
 <div ref="gridElement" class="selection-grid" role="table" aria-label="供应商选品清单" v-loading="loading"><div class="selection-table-header" role="row"><div role="columnheader" class="head-check">勾选</div><div role="columnheader" class="head-photo">图片</div><div role="columnheader">款式号／名称</div><div role="columnheader" class="head-model">已选手机型号</div><div role="columnheader">已选颜色</div><div role="columnheader">拟拿样</div><div role="columnheader">实际收样</div><div role="columnheader">质检</div><div role="columnheader">状态</div></div><article v-for="row in rows" :key="row.rowId" class="selection-row" role="row" :class="{'is-frozen':frozenSelectionRow(row),'is-selected':checked.has(row.rowId)}"><div class="row-check" role="cell"><el-checkbox :model-value="checked.has(row.rowId)" :disabled="locked" :aria-label="`选择${row.name||'未命名行'}`" @change="checkRow(row,Boolean($event))"/></div><div class="row-choice" role="cell"><div class="row-media"><button v-for="item in rowImages(row).slice(0,2)" :key="item.ref.id+':'+(rowPhotoVersions[row.rowId]||0)" class="row-photo" :disabled="locked" :aria-label="`查看${row.name||'本行'}图片与附件`" @click="openRowMaterials(row)"><PartnerPhoto :id="item.ref.id" :allow-retry="false"/></button><el-button v-if="rowImages(row).length>2" class="extra-photos" link type="primary" :disabled="locked" @click="openRowMaterials(row)">+{{rowImages(row).length-2}}张</el-button><span v-if="!rowImages(row).length" class="photo-placeholder">待补图</span></div></div>
 <div class="row-primary" role="cell"><div class="row-identification"><p class="code">{{row.catalogStyleLabel||row.catalogStyleCode||'款式号待补'}}</p><p class="row-supplier">{{supplierName(row.supplierCode)}}<template v-if="row.supplierItemCode"> · {{row.supplierItemCode}}</template></p></div><div class="row-title"><strong v-if="renamingRow!==row.rowId" class="row-name">{{row.name||'未命名行'}}</strong><el-input v-else ref="nameInputs" v-model="row.name" type="textarea" autosize :disabled="locked" :aria-label="`款式${row.rowId}`" placeholder="款式名称"/></div><p v-if="row.productCode" class="code">我方款式 {{row.productCode}}</p><div class="row-files"><span v-for="item in rowFiles(row).filter(item=>!item.file?.type.startsWith('image/'))" :key="item.ref.id">📎 {{item.file?.name||item.ref.name||'附件'}} {{item.file?.version||item.ref.version}}</span></div></div>
 <div class="row-model" role="cell"><strong class="model-title">{{modelSummary(row)}}</strong><el-button v-if="modelCount(row)>2||hasLongModelText(row)" class="all-models" link type="primary" :disabled="locked" @click="edit(row)">{{modelCount(row)>2?'查看全部 '+modelCount(row)+' 个型号':'查看型号原文'}}</el-button></div>
 <div class="row-secondary">
 <div class="row-spec" role="cell"><span class="compact-field-label">颜色：</span><strong class="row-color">{{colorSummary(row)}}</strong><el-button v-if="selectedColors(row).length>2" link type="primary" @click="edit(row)">全部 {{selectedColors(row).length}} 色</el-button></div>
 <div class="row-planned" role="cell"><span class="compact-field-label">拟拿样：</span><el-input-number v-if="canReviseSelection(row)&&!frozenSelectionRow(row)&&!row.discarded" v-model="row.sampleQty" :key="row.rowId+':planned:'+locked" class="planned-quantity-input" :min="0" :max="Number.MAX_SAFE_INTEGER" :precision="0" :controls="false" placeholder="未定" :aria-label="row.name+'拟拿样本款合计'" :disabled="locked"/><strong v-else class="sample-quantity">{{row.sampleQty===undefined||row.sampleQty===null||String(row.sampleQty)===''?'未定':row.sampleQty+' 件'}}</strong><small class="quantity-hint">本款合计</small></div>
 <div class="row-sample-progress" role="cell"><span class="compact-field-label">实收：</span><div v-if="row.sampleBatches?.length" class="batch-labels"><el-button link type="primary" @click="router.push({path:'/samples',query:{id:row.sampleBatches[0].id,from:route.fullPath}})">{{row.sampleBatches[0].displayLabel||row.sampleBatches[0].label||row.sampleBatches[0].code}}</el-button><el-button v-if="row.sampleBatches.length>1" link @click="edit(row)">共 {{row.sampleBatches.length}} 批</el-button></div><span v-else>未收样</span></div>
 <div class="row-quality" role="cell"><span class="compact-field-label">质检：</span><el-button v-if="row.sampleBatches?.length" link type="primary" @click="router.push({path:'/samples',query:{id:row.sampleBatches[0].id,from:route.fullPath}})">查看检验</el-button><span v-else>未收样</span></div>
 </div>
 <div class="row-status" role="cell"><el-tag size="small" :type="frozenSelectionRow(row)?'success':'info'">{{row.discarded?'已取消':hasReceivedSamples(row)?'已收样':frozenSelectionRow(row)?(row.productId?'历史产品关联':'已选样'):row.selected?'已选样':'选品草稿'}}</el-tag></div>
 </article><el-empty v-if="!loading&&!rows.length" description="暂无已选款式，请从供应商产品目录选款及具体规格"/></div>
 <footer class="selection-footer"><span class="muted">保存选样不创建产品或样品批次；收到样品后登记实际数量。</span><el-pagination :current-page="filter.page" :page-size="filter.size" :page-sizes="[20,30,50,100]" :total="total" :pager-count="5" layout="total,sizes,prev,pager,next" @current-change="changePage" @size-change="changeSize"/></footer></template>
 <el-dialog v-model="originalsOpen" title="受限原件（按权限分页）" width="min(850px,96vw)" append-to-body><div v-for="file in originals" :key="file.id" class="selection-documents"><el-button link @click="downloadSource(file)">{{file.name}} · {{file.version}}</el-button><span class="muted">{{(file.size/1024/1024).toFixed(1)}} MiB</span></div><el-pagination v-model:current-page="originalsPage" :total="originalsTotal" :page-size="20" :pager-count="5" layout="total,prev,pager,next" @current-change="loadOriginals"/></el-dialog>
 <el-dialog v-model="materialsOpen" @closed="refreshRowPhotos" :title="materialsRow?'本行图片与附件 · '+materialsRow.name:'最近上传资料（每行关联图片与附件不受此列表数量影响）'" width="min(1000px,96vw)" append-to-body><PartnerFiles v-if="detail" :id="id" kind="partner-selections" :files="materialFiles" :model-value="materialsRow?.refs||detail.refs" :can-write="canWrite&&!locked&&!materialsRow" :can-write-money="canReadCost" :before-upload="ensureSaved" @changed="refreshFiles" @update:model-value="refsChanged" @uploading="fileBusy=$event"/><template #footer><el-button v-if="materialsRow" @click="materialsOpen=false;edit(materialsRow)">打开本行资料</el-button><el-button @click="materialsOpen=false">{{materialsRow?'返回选品':'返回选品（保留本次采用）'}}</el-button></template></el-dialog>
 <el-dialog :model-value="!!editor" :title="editor?.name||'本行选品资料'" class="selection-row-dialog" top="20px" width="min(1020px,96vw)" append-to-body :close-on-click-modal="false" :before-close="closeEditor"><SelectionRowEditor v-if="editor&&detail" :row="editor" :files="editorFiles" :library-files="detail.files" :suppliers="suppliers" :can-write="!editorReadOnly&&canReviseSelection(editor)&&!frozenSelectionRow(editor)&&!editor.discarded" :can-read-cost="canReadCost"/><template #footer><el-button @click="closeEditor">{{editor&&frozenSelectionRow(editor)?'关闭':'取消'}}</el-button><el-button v-if="editor&&!editorReadOnly&&canReviseSelection(editor)&&!frozenSelectionRow(editor)&&!editor.discarded" type="primary" @click="applyEditor">应用到本页（待保存）</el-button></template></el-dialog>
 <el-dialog v-model="pasteOpen" title="Excel矩形粘贴／向下填充" width="min(900px,96vw)" append-to-body :close-on-click-modal="false"><p>仅填写本页已有选样行；新增款式或型号请从供应商产品目录选择。</p><div class="search-bar"><el-select v-model="pasteAnchor"><el-option v-for="row in editableRows" :key="row.rowId" :value="row.rowId" :label="row.name||'未命名行'"/></el-select><el-select v-model="pasteStart"><el-option v-for="key in fillableColumns" :key="key" :value="key" :label="fieldLabel(key)"/></el-select></div><p>从所选行、字段开始向右及向下填充；列顺序：{{fillableColumns.map(fieldLabel).join(' → ')}}。空白保留原值，不复制供货结论。</p><el-input v-model="pasteText" type="textarea" :rows="8" @input="pasteCount=0"/><el-alert v-if="pasteError" :title="pasteError" type="error" :closable="false"/><p v-if="pasteCount">将修改 {{pasteCount}} 行，不改变未指定字段。</p><template #footer><el-button @click="pasteOpen=false">取消</el-button><el-button @click="checkPaste">核对粘贴</el-button><el-button type="primary" :disabled="!pasteCount||busy" @click="applyPaste">应用本次内容</el-button></template></el-dialog>
 <el-dialog v-model="bulkOpen" title="只修改指定字段，保留各行例外" width="min(850px,96vw)" append-to-body><el-radio-group v-model="bulkScope"><el-radio value="checked">已勾选 {{checked.size}} 行</el-radio><el-radio value="page">本页未确认行</el-radio><el-radio value="filter">全部筛选结果 {{total}} 行</el-radio></el-radio-group><p>请选择确实要统一的字段；未勾选字段不变。型号／颜色原文不会自动生成供货组合。实际配对的可供／不供结论仍逐项保留。</p><div class="bulk-fields"><article v-for="key in fillableColumns" :key="key"><el-checkbox :model-value="bulkFields.includes(key)" @change="bulkFields=$event?[...bulkFields,key]:bulkFields.filter(value=>value!==key)">{{fieldLabel(key)}}</el-checkbox><el-input v-model="bulk[key]" :disabled="!bulkFields.includes(key)||bulkClear.includes(key)"/><el-checkbox :model-value="bulkClear.includes(key)" :disabled="!bulkFields.includes(key)||key==='sampleQty'" @change="bulkClear=$event?[...bulkClear,key]:bulkClear.filter(value=>value!==key)">明确清空</el-checkbox></article></div><el-checkbox v-model="bulkPhonesEnabled">统一本次期望精确型号（非供货结论）</el-checkbox><PhoneModelMultiSelect v-if="bulkPhonesEnabled" v-model="bulkPhones"/><el-checkbox v-model="bulkRangesEnabled">一次选择，批量应用本供应商范围</el-checkbox><div v-if="bulkRangesEnabled" class="search-bar"><p v-if="!bulkSupplier" class="confirm-issue">目标包含多家供应商或尚未限定供应商。请按供应商筛选／分批勾选，不能跨供方套用同一范围。</p><template v-else><p>供应商：{{supplierName(bulkSupplier)}}。只替换选定类型，留空类型保留各行原值；不复制或生成供货结论。</p><el-select v-model="bulkModelRef" filterable remote clearable :remote-method="queryBulkModels" placeholder="搜索型号范围名称／编码"><el-option v-for="range in bulkModelRanges" :key="range.id" :value="range.code" :label="range.name+' · '+range.code+' · V'+range.version"/></el-select><el-select v-model="bulkColorRef" filterable remote clearable :remote-method="queryBulkColors" placeholder="搜索颜色范围名称／编码"><el-option v-for="range in bulkColorRanges" :key="range.id" :value="range.code" :label="range.name+' · '+range.code+' · V'+range.version"/></el-select></template></div><template #footer><el-button @click="bulkOpen=false">取消</el-button><el-button type="primary" :disabled="(!bulkFields.length&&!bulkPhonesEnabled&&!bulkRangesEnabled)||busy||bulkScope!=='filter'&&!bulkTargets.length||bulkRangesEnabled&&(!bulkSupplier||!bulkModelRef&&!bulkColorRef)" @click="applyBulk">预览目标并应用</el-button></template></el-dialog>
 <SelectionReceiptDialog v-if="receiptRows.length&&detail" :id="id" :version="detail.version" :rows="receiptRows" :from="route.fullPath" @close="receiptRows=[]" @saved="receiptSaved" @busy="fileBusy=$event"/>
 <el-dialog :model-value="!!receivingRow" title="选择本次实际收到的规格" width="min(650px,95vw)" append-to-body @update:model-value="!$event&&(receivingRow=undefined)"><p>本行有多个真实型号／颜色配对。选择这次实际收到的规格；拟拿样合计不会被平均分配。</p><el-select v-model="receivingSpec" filterable placeholder="选择实际规格"><el-option v-for="spec in receivingRow?.specLinks||[]" :key="spec.specId" :value="spec.specId" :label="spec.specName"/></el-select><template #footer><el-button @click="receivingRow=undefined">取消</el-button><el-button type="primary" :disabled="!receivingSpec" @click="receivingRow&&goToSample(receivingRow,receivingSpec)">登记本次实际收样</el-button></template></el-dialog>
 <CatalogListingDialog v-if="listingRow" :selection-id="id" :row="listingRow" @close="listingRow=undefined" @saved="listingRow=undefined;load()"/>
 <SelectionFileTasks v-if="id" :key="id" v-model="filesOpen" :id="id" :mode="fileMode" :suppliers="suppliers" :can-write="canWrite" @changed="load" @busy="fileBusy=$event"/>
 </PageContainer></template>
<style scoped>
.selection-workspace{height:100%;min-height:0;min-width:0;display:flex;flex-direction:column;gap:6px;container-type:inline-size}
:global(.selection-row-dialog){display:flex;flex-direction:column;max-height:calc(100vh - 40px);max-height:calc(100dvh - 40px);margin-top:20px;margin-bottom:20px;box-sizing:border-box}
:global(.selection-row-dialog>.el-dialog__header),:global(.selection-row-dialog>.el-dialog__footer){flex:none}
:global(.selection-row-dialog>.el-dialog__body){flex:1;min-height:0;overflow-y:auto;overscroll-behavior:contain}
.selection-workspace :deep(>.page-panel){padding:7px 12px;flex:none}
.selection-workspace :deep(.page-panel-header){flex-direction:row;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:0}
.selection-workspace :deep(.page-panel-title){font-size:17px;margin:0}
.selection-workspace :deep(.toolbar-actions){gap:5px;margin:0;flex-wrap:wrap}
.selection-workspace :deep(.el-button+.el-button){margin-left:0}
.muted{color:#6f8095;font-size:12px;overflow-wrap:anywhere}
.sheet-heading,.search-bar,.batch-bar,.selection-footer{display:flex;align-items:center;gap:7px;flex-wrap:wrap;flex:none;min-width:0}
.sheet-heading>.el-input{flex:1;min-width:140px}
.history-note{display:flex;align-items:baseline;gap:4px 12px;flex-wrap:wrap;flex:none;font-size:12px;line-height:1.5;color:#6f8095}
.history-note :deep(.el-button){font-size:12px;height:auto;padding:0;white-space:normal;line-height:1.5}
.search-bar>.el-input{flex:1;min-width:150px}
.search-bar>.el-select{width:150px}
.batch-bar{font-size:12px;padding:2px 0}
.batch-bar :deep(.el-checkbox){margin-right:0;height:26px}
.sheet-list,.selection-grid{flex:1;min-height:0;overflow-y:auto;min-width:0;border:1px solid #d7e1ed;border-radius:5px;background:white}
.sheet-card{width:100%;display:flex;gap:15px;align-items:center;padding:11px 14px;border:0;border-bottom:1px solid #edf1f6;background:white;text-align:left;cursor:pointer;min-width:0;font:inherit;color:inherit}
.sheet-card strong{flex:1;overflow-wrap:anywhere}
.sheet-card span,.sheet-card small{color:#6f8095;font-size:12px}
.selection-grid{--selection-columns:32px 106px minmax(0,1.4fr) minmax(0,1.8fr) minmax(0,.8fr) 68px 116px 76px 88px}
.row-secondary>[role=cell]{display:flex;flex-direction:column;justify-content:center;font-size:13px;line-height:1.5;overflow-wrap:anywhere}
.selection-table-header,.selection-row{display:grid;grid-template-columns:var(--selection-columns);min-width:0}
.selection-table-header{position:sticky;top:0;z-index:1;background:#eef3f9;border-bottom:1px solid #ccd8e7;color:#364c66;font-size:13px;font-weight:600}
.selection-table-header>div{padding:8px;border-right:1px solid #d7e1ed;min-width:0;overflow-wrap:anywhere}
.selection-table-header>div:last-child{border-right:0}
.selection-table-header .head-photo,.selection-table-header .head-check{text-align:center}
.selection-table-header .head-check{padding-left:4px;padding-right:4px;white-space:nowrap;font-size:12px}
.selection-table-header .head-model{background:#e5eef9;color:#234c7b}
.selection-row{align-items:stretch;min-height:54px;border-bottom:1px solid #dfe7f0}
.selection-row:nth-of-type(even){background:#fbfcfe}
.selection-row.is-frozen{background:#f9fcfa}
.selection-row.is-selected{background:#f1f7ff}
.selection-row>*{min-width:0}
.selection-row>[role=cell],.row-secondary>[role=cell]{padding:5px 8px;border-right:1px solid #e3eaf2;min-width:0}
.row-primary,.row-model,.row-spec{display:flex;flex-direction:column;justify-content:center}
.row-identification{display:flex;gap:3px 9px;flex-wrap:wrap;align-items:baseline;min-width:0}
.selection-row p{font-size:12px;line-height:1.4;margin:0;overflow-wrap:anywhere}
.selection-row :deep(textarea){font-size:14px;line-height:1.45;padding:3px 5px;overflow:hidden;overflow-wrap:anywhere;resize:none}
.row-name{display:block;font-size:14px;line-height:1.5;overflow-wrap:anywhere;color:#253a51}
.row-supplier{color:#687c93}
.row-model .model-title{font-size:15px;line-height:1.5;font-weight:650;color:#193c65;white-space:pre-line;overflow-wrap:anywhere}
.row-model .all-models{align-self:flex-start;font-size:12px;white-space:normal;line-height:1.5;height:auto;margin-top:2px}
.row-secondary{display:contents}
.row-spec .row-color{font-size:13px;font-weight:500;color:#344c66}
.compact-field-label{display:none}
.row-title{display:flex;align-items:flex-start;gap:5px;min-width:0}
.row-title>.row-name,.row-title>.el-textarea{flex:1;min-width:0}
.row-check{display:flex;align-items:center;justify-content:center}
.row-check :deep(.el-checkbox){margin-right:0}
.row-choice{display:flex;align-items:center;justify-content:center;gap:4px}
.row-choice :deep(.el-checkbox){margin-right:0}
.row-media{position:relative;display:flex;align-items:center;gap:3px;min-width:0}
.row-photo{border:0;padding:0;background:none;display:flex;cursor:zoom-in;min-width:0;flex:none}
.row-photo:focus-visible{outline:2px solid #409eff;outline-offset:2px;border-radius:5px}
.row-choice :deep(.partner-photo){width:44px;height:44px}
.row-media>.extra-photos{position:absolute;right:0;bottom:0;height:18px;padding:0 3px;border-radius:3px;background:#fff;box-shadow:0 0 0 1px #dfe7f1;font-size:11px}
.photo-placeholder{width:44px;height:44px;display:grid;place-items:center;border:1px dashed #d6dfeb;border-radius:4px;font-size:11px;color:#8492a5}
.row-status{display:flex;align-items:center;justify-content:flex-start}.selection-row>.row-status{border-right:0}.row-status>.el-tag{max-width:100%;height:auto;white-space:normal}.row-status :deep(.el-tag__content){overflow-wrap:anywhere}
.batch-labels :deep(.el-button),.row-quality :deep(.el-button){font-size:12px;margin-left:0;white-space:normal;height:auto;line-height:1.5;text-align:left;justify-content:flex-start;min-width:0}
.selection-actions{display:flex;align-items:center;flex-wrap:wrap;gap:4px 6px;flex:none;min-width:0}.selection-actions :deep(.el-button){margin:0;min-height:26px;height:auto;white-space:normal;font-size:12px;padding:4px 8px}.selection-action-hint{margin:0;color:#6f8095;font-size:12px;line-height:1.5;overflow-wrap:anywhere}
.batch-labels,.row-files{display:flex;gap:2px 7px;flex-wrap:wrap;align-items:center}
.row-files{font-size:12px;color:#526d8d;overflow-wrap:anywhere}
.row-files span{max-width:100%}
.planned-quantity-input{width:100%;min-width:0}.planned-quantity-input :deep(.el-input__wrapper){padding:0 4px}.quantity-hint{color:#72839a;font-size:11px}
.code{color:#2d6799}
.selection-footer{justify-content:space-between}
.selection-footer :deep(.el-pagination){max-width:100%;flex-wrap:wrap;gap:2px}
.selection-workspace :deep(.partner-files){flex:none}
.selection-documents{display:flex;align-items:center;gap:5px 10px;flex-wrap:wrap;min-width:0;flex:none;font-size:12px}
.selection-documents>strong{font-size:12px;white-space:nowrap}
.selection-documents>.el-button:last-child{margin-left:auto}
.selection-documents>.el-button{white-space:normal;height:auto;line-height:1.6;max-width:100%;overflow-wrap:anywhere;text-align:left}
.bulk-fields article{display:grid;grid-template-columns:130px minmax(0,1fr) 100px;gap:8px;align-items:center;margin:7px 0}
 .confirm-issue p{font-size:12px;color:#9a6412;line-height:1.6}
@container(max-width:1150px){
 .selection-table-header{display:none}
 .selection-row{grid-template-columns:28px 94px minmax(0,1.2fr) minmax(0,1fr) 106px;grid-template-rows:auto auto auto;padding:4px 6px;gap:1px 7px;align-items:center}
 .selection-row>[role=cell],.row-secondary>[role=cell]{border:0;padding:1px 0}
 .selection-row>.row-check{grid-column:1;grid-row:1/4}
 .selection-row>.row-choice{grid-column:2;grid-row:1/4}
 .selection-row>.row-primary{grid-column:3/5;grid-row:1}
 .selection-row>.row-model{grid-column:3/5;grid-row:2;flex-direction:row;align-items:baseline;justify-content:flex-start;flex-wrap:wrap;gap:1px 7px}
 .row-model .all-models{margin-top:0}
 .row-secondary{grid-column:3/5;grid-row:3;display:flex;flex-wrap:wrap;gap:1px 9px;min-width:0}
 .row-secondary>[role=cell]{display:flex;flex:0 1 auto;flex-direction:row;align-items:baseline;gap:4px;max-width:100%;font-size:13px;line-height:1.5}
 .compact-field-label{display:inline;color:#687c93;font-size:12px;flex:none}
 .selection-row>.row-status{grid-column:5;grid-row:1/4;max-width:100%;padding:1px 0}
 .selection-footer>.muted{display:none}
}
@container(max-width:800px){
 .search-bar>.el-select{flex:1;min-width:125px}
 .sheet-card{align-items:flex-start;flex-wrap:wrap}
 .sheet-card strong{flex-basis:100%}
 .selection-footer{justify-content:flex-end}
}
@container(max-width:460px){
 .selection-row{grid-template-columns:24px 48px minmax(0,1fr) 0 82px;gap:2px 5px}
 .row-media{flex-wrap:wrap;justify-content:center}
}
@media(max-width:750px){
 .bulk-fields article{grid-template-columns:1fr 2fr}
 .bulk-fields article>.el-checkbox:last-child{grid-column:2}
}
</style>
