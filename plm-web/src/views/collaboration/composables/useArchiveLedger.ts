import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/modules/collaboration'
import { getAllPhoneBrands } from '@/api/modules/phoneReference'
import type { PhoneBrand } from '@/types/molds'
import { useCachedRequest } from '@/composables/useCachedRequest'
import { useUserStore } from '@/stores/user'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'
import type { CellValue, CollaborationOptions, CollaborationRow, PlanField, PlanHistory } from '@/types/collaboration'
import { collaborationFieldEditable, isCollaborationEntry, parseCell, parseClipboard, productionFields, rowKey } from '@/utils/collaboration'

/** One archive query/edit/save engine; modules supply their own visible columns. */
export function useArchiveLedger(kind: 'market'|'production', keys: readonly string[], initialItemId?:()=>number|undefined) {
  const route=useRoute(), user=useUserStore(), workspace=useCollaborationWorkspace(), drafts=workspace.drafts
  const options=ref<CollaborationOptions>(), keyword=ref(''), productLineId=ref<number>(), status=ref(''), mineOnly=ref(false)
  const brands=ref<PhoneBrand[]>([])
  const page=ref(1), size=ref(50), itemId=ref<number>(), expanded=ref(false), error=ref(''), saving=ref(false), reviewVisible=ref(false)
  const request=useCachedRequest(api.getCollaborationPage)
  const rows=computed(()=>request.hasCurrentData.value ? request.data.value?.content || [] : [])
  const total=computed(()=>request.data.value?.totalElements || 0)
  const fields=computed(()=>keys.map(key=>options.value?.itemFields.find(f=>f.key===key)).filter((f):f is PlanField=>!!f))
  const moduleFields=computed(()=>(options.value?.itemFields || []).filter(f=>f.key!=='brand'&&(f.group===kind || kind==='market'&&f.key==='brandId')).map(f=>f.key))
  const scope=computed(()=>({entity:'item' as const,fields:moduleFields.value}))
  const patches=computed(()=>drafts.patches(scope.value)), dirtyCells=computed(()=>patches.value.reduce((n,p)=>n+p.changes.length,0))
  const draftRows=computed(()=>patches.value.map(p=>({key:`item:${p.id}`,patch:p,draft:drafts.rows.get(`item:${p.id}`)!})))
  const editable=(field:PlanField)=>collaborationFieldEditable(field,options.value?.writableGroups)
  const value=(row:CollaborationRow,key:string)=>drafts.value(row,key)
  const dirty=(row:CollaborationRow,key:string)=>!!drafts.rows.get(rowKey(row))?.changes.has(key)
  let timer:ReturnType<typeof setTimeout>|undefined, mounted=false, active=true, generation=0, brandGeneration=0, routeScope=''
  const query=()=>({view:kind,projectId:Number(route.query.projectId)||undefined,page:page.value,size:size.value,keyword:keyword.value.trim(),productLineId:productLineId.value,status:status.value,mineOnly:mineOnly.value,itemId:itemId.value})
  function load(force=false) {return options.value ? request.execute(query(),force) : Promise.resolve()}
  function reset(debounce=false) {clearTimeout(timer);request.invalidate();page.value=1;if(debounce)timer=setTimeout(()=>void load(),300);else void load()}
  function applyRoute() {if(route.path!==`/${kind==='market'?'market-data':'production-totals'}`&&!(kind==='market'&&route.path==='/market-watch'&&route.query.panel==='market-data'))return;const next=JSON.stringify([route.query.projectId,initialItemId?.() || route.query.itemId || null,route.query.productLineId || null]);if(next===routeScope)return;routeScope=next;itemId.value=initialItemId?.() || Number(route.query.itemId)||undefined;productLineId.value=Number(route.query.productLineId)||undefined;if(mounted)reset()}
  watch(keyword,()=>reset(true),{flush:'sync'});watch([productLineId,status,mineOnly,size],()=>reset(),{flush:'sync'})
  watch(()=>route.fullPath,applyRoute);if(initialItemId)watch(initialItemId,applyRoute)
  watch(()=>workspace.revision,()=>{request.clear();if(active && mounted)void load()},{flush:'sync'})
  watch(()=>user.token,()=>{generation++;clearTimeout(timer);request.clear();request.data.value=undefined;request.hasCurrentData.value=false;options.value=undefined;brands.value=[];historyRows.value=[];references.value=undefined;historyVisible.value=false;referencesVisible.value=false;editorVisible.value=false;editorRow.value=undefined;editorText.value='';reviewVisible.value=false;saving.value=false;error.value=''}, {flush:'sync'})
  async function loadBrands(){const token=user.token,ticket=++brandGeneration;const result=kind==='market'?await getAllPhoneBrands():[];if(token===user.token&&ticket===brandGeneration)brands.value=result}
  async function loadOptions() {const token=user.token;const [next]=await Promise.all([api.getCollaborationOptions(),loadBrands()]);if(token!==user.token)return false;options.value=next;return true}
  async function refreshBrands(){if(kind!=='market')return;const token=user.token;try{await loadBrands()}catch{if(token===user.token)error.value='品牌资料刷新失败，请点击刷新重试'}}
  async function refresh() {const token=user.token;try{if(!await loadOptions())return;error.value='';await load(true)}catch{if(token===user.token)error.value='刷新失败，请检查登录状态后重试'}}
  function stage(row:CollaborationRow,field:PlanField,text:string) {if(!editable(field))return;drafts.set(row,field.key,parseCell(field,text))}
  function edit(row:CollaborationRow,field:PlanField,text:string) {try{stage(row,field,text)}catch(e){ElMessage.warning(e instanceof Error?e.message:'填写格式不正确')}}
  function preview(row:CollaborationRow,field:PlanField,text:string) {try{stage(row,field,text)}catch{/* Incomplete typing is validated on blur. */}}
  function paste(row:CollaborationRow,field:PlanField,event:ClipboardEvent) {
    const text=event.clipboardData?.getData('text/plain')||'';if(!/[\t\n\r]/.test(text))return;event.preventDefault()
    try{const cells=parseClipboard(text),startRow=rows.value.findIndex(r=>r.itemId===row.itemId),startCol=fields.value.findIndex(f=>f.key===field.key)
      if(startRow+cells.length>rows.value.length)throw new Error('粘贴超出当前页，请分批粘贴')
      const changes:{row:CollaborationRow;field:PlanField;value:CellValue}[]=[]
      cells.forEach((line,ri)=>line.forEach((text,ci)=>{const f=fields.value[startCol+ci];if(!f || !editable(f))throw new Error('粘贴范围包含只读列或超出填写列');changes.push({row:rows.value[startRow+ri],field:f,value:parseCell(f,text)})}))
      changes.forEach(c=>drafts.set(c.row,c.field.key,c.value));ElMessage.success(`已暂存 ${changes.length} 个单元格`)
    }catch(e){ElMessage.warning(e instanceof Error?e.message:'粘贴失败')}
  }
  async function save() {
    const submitted=patches.value,token=user.token,ticket=generation;if(!submitted.length || !token)return
    if(submitted.some(p=>p.changes.some(c=>productionFields.slice(0,3).includes(c.field) && c.before!=null && (c.after==null || Number(c.after)<Number(c.before)) && !c.reason))){ElMessage.warning('下调或清空累计数量，请先填写更正说明');return}
    saving.value=true;error.value='';let saved=0,failed=0
    try{for(let n=0;n<submitted.length;n+=100){if(token!==user.token || ticket!==generation)return;const chunk=submitted.slice(n,n+100),results=await api.saveCollaborationRows(chunk);if(token!==user.token || ticket!==generation)return;results.forEach(r=>{drafts.apply(r,chunk.find(p=>p.id===r.id && p.entityType===r.entityType));r.status==='saved'?saved++:failed++})}
      if(saved)workspace.changed();else request.clear();await load();if(saved)ElMessage.success(`已保存 ${saved} 行`);if(failed){error.value=`${failed} 行未保存，草稿已保留，请核对错误或冲突`;reviewVisible.value=true}
    }catch{if(token===user.token)error.value='保存未完成，未确认成功的草稿已保留'}finally{if(ticket===generation)saving.value=false}
  }
  async function discard() {if(!dirtyCells.value)return;try{await ElMessageBox.confirm('只撤销当前模块的未保存修改？其他模块草稿保留。','撤销修改',{confirmButtonText:'撤销修改',cancelButtonText:'保留'});drafts.clear(scope.value);error.value=''}catch{}}
  async function explain(row:CollaborationRow) {const d=drafts.rows.get(rowKey(row));if(!d)return;try{const result=await ElMessageBox.prompt('填写累计产量更正原因或资料核对说明。','填写说明',{inputType:'textarea',inputValue:d.reason});d.reason=result.value.trim()}catch{}}
  const editorVisible=ref(false),editorRow=ref<CollaborationRow>(),editorField=ref<PlanField>(),editorText=ref('')
  function openEditor(row:CollaborationRow,field:PlanField) {editorRow.value=row;editorField.value=field;editorText.value=String(value(row,field.key)??'');editorVisible.value=true}
  function stageEditor() {if(!editorRow.value || !editorField.value)return;try{stage(editorRow.value,editorField.value,editorText.value);editorVisible.value=false}catch(e){ElMessage.warning(e instanceof Error?e.message:'格式不正确')}}
  const historyVisible=ref(false),historyRows=ref<PlanHistory[]>([]),historyItem=ref<CollaborationRow>(),historyPage=ref(1),historyTotal=ref(0),historyLoading=ref(false)
  async function loadHistory() {if(!historyItem.value)return;const id=historyItem.value.itemId,ticket=++historyGeneration,session=generation;historyLoading.value=true;try{const r=await api.getCollaborationHistory(id,historyPage.value);if(ticket===historyGeneration && session===generation){historyRows.value=r.content;historyTotal.value=r.totalElements}}finally{if(ticket===historyGeneration)historyLoading.value=false}}
  async function openHistory(row:CollaborationRow) {historyItem.value=row;historyPage.value=1;historyVisible.value=true;await loadHistory()}
  const referencesVisible=ref(false),references=ref<Awaited<ReturnType<typeof api.getCollaborationReferences>>>()
  let historyGeneration=0, referenceGeneration=0
  async function openReferences(row:CollaborationRow) {const ticket=++referenceGeneration,session=generation;references.value=undefined;referencesVisible.value=true;const result=await api.getCollaborationReferences(row.itemId);if(ticket===referenceGeneration && session===generation)references.value=result}
  function label(key:string) {return [...(options.value?.itemFields || []),...(options.value?.moldFields || [])].find(f=>f.key===key)?.label || key}
  function beforeUnload(event:BeforeUnloadEvent) {if(drafts.rows.size){event.preventDefault();event.returnValue=''}}
  onBeforeRouteLeave(async to=>{if(!dirtyCells.value)return true;const shared=isCollaborationEntry(to.path);try{await ElMessageBox.confirm(shared?'还有未保存修改，前往关联入口后保留草稿。':'还有未保存修改，离开将丢弃当前模块草稿。','未保存修改',{confirmButtonText:shared?'保留草稿并前往':'离开并丢弃',cancelButtonText:'继续填写',type:'warning'});if(!shared)drafts.clear(scope.value);return true}catch{return false}})
  onMounted(async()=>{window.addEventListener('beforeunload',beforeUnload);const token=user.token;try{if(!await loadOptions())return;applyRoute();await load();mounted=true}catch{if(token===user.token)error.value='初始化失败，请刷新重试'}})
  onActivated(()=>{active=true;if(mounted){void load();void refreshBrands()}});onDeactivated(()=>{active=false;expanded.value=false;clearTimeout(timer);request.invalidate()})
  onBeforeUnmount(()=>{clearTimeout(timer);window.removeEventListener('beforeunload',beforeUnload)})
  return {kind,options,brands,keyword,productLineId,status,mineOnly,page,size,itemId,expanded,error,saving,reviewVisible,request,rows,total,fields,scope,patches,dirtyCells,draftRows,drafts,editable,value,dirty,query,load,reset,refresh,edit,preview,paste,save,discard,explain,editorVisible,editorRow,editorField,editorText,openEditor,stageEditor,historyVisible,historyRows,historyItem,historyPage,historyTotal,historyLoading,openHistory,loadHistory,referencesVisible,references,openReferences,label,workspace}
}
