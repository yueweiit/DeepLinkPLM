<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate, type LocationQueryRaw } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { FolderAdd, MoreFilled } from '@element-plus/icons-vue'
import PageContainer from '@/components/PageContainer/index.vue'
import { catalogStyle, catalogStyles, catalogSuppliers, updateCatalogStyle, uploadCatalogPhoto } from '@/api/modules/partnerCatalog'
import {t} from '@/locales'
import { newRequestId } from '@/api/modules/partner'
import type { CatalogAdditionResult, CatalogPage, CatalogStyle, CatalogSupplier } from '@/types/partnerCatalog'
import { partnerError } from '@/utils/partnerError'
import PartnerPhoto from '../PartnerPhoto.vue'
import CatalogSelectionDialog from './CatalogSelectionDialog.vue'
import SupplierStyleDetail from './SupplierStyleDetail.vue'
import PhoneModelSummary from '../PhoneModelSummary.vue'
import CatalogSourceImport from './CatalogSourceImport.vue'
import CatalogIdentityDialog from './CatalogIdentityDialog.vue'
import CatalogModelSheetDialog from './CatalogModelSheetDialog.vue'
import CatalogStyleEditor from './CatalogStyleEditor.vue'
import { catalogKey, catalogPhoneSummary } from './catalogPresentation'
import { moqScopeLabel, supplierCurrency, supplierPriceValue } from '@/utils/partnerPrice'

const route = useRoute(), router = useRouter()
const supplierCode = computed(() => String(route.query.supplierCode || ''))
const sourceKind = computed(() => String(route.query.sourceKind || ''))
const isDetail = computed(() => Boolean(supplierCode.value && ['quote', 'selection'].includes(sourceKind.value) && Number(route.query.recordId) > 0 && route.query.rowId))
const supplierQuery = ref(''), keyword = ref(''), loading = ref(false), error = ref('')
const suppliers = ref<CatalogPage<CatalogSupplier>>(), styles = ref<CatalogPage<CatalogStyle>>(), detail = ref<CatalogStyle>()
const selectionBusy=ref(false)
const checked = ref(new Map<string, CatalogStyle>()), selectionOpen = ref(false), selectionItems = ref<CatalogStyle[]>([]), addition = ref<CatalogAdditionResult>(), sourcesOpen = ref(false)
const identityItems=ref<CatalogStyle[]>([])
const modelSheetItem=ref<CatalogStyle>(),modelSheetMessage=ref('')
const removing=ref(false),editing=ref<CatalogStyle>(),singleEntry=ref(false)
const photoInput=ref<HTMLInputElement>(),photoTarget=ref<CatalogStyle>(),photoUploading=ref(false),photoError=ref('')
const pendingPhoto=ref<{row:CatalogStyle;file:File;requestId:string;identity?:{sourceKind:CatalogStyle['sourceKind'];recordId:number;rowId:string;rowVersion:number;recordVersion:number;supplierCode:string;requestId:string}}>()
const deleted=computed(()=>route.query.deleted==='true')
const selectionLoading=ref(false),selectionLoadError=ref(''),selectionLoaded=ref(0),pendingSelection=ref<CatalogStyle[]>([])
let generation = 0,selectionGeneration=0
const positive = (value: unknown, fallback: number) => { const number = Number(value); return Number.isSafeInteger(number) && number > 0 ? number : fallback }
const page = computed(() => positive(route.query.catalogPage, 1)), size = computed(() => Math.min(100, positive(route.query.catalogSize, 30)))
const targetRaw=computed(()=>route.query.targetSelectionId??route.query.selectionId)
const targetSelectionId=computed(()=>targetRaw.value===undefined?undefined:Number(targetRaw.value))
const targetVersionRaw=computed(()=>route.query.targetVersion??route.query.selectionVersion)
const targetVersion=computed(()=>targetVersionRaw.value===undefined?undefined:Number(targetVersionRaw.value))
const invalidTargetContext=computed(()=>targetRaw.value!==undefined&&(!Number.isSafeInteger(targetSelectionId.value)||Number(targetSelectionId.value)<=0)||targetVersionRaw.value!==undefined&&(!Number.isSafeInteger(targetVersion.value)||Number(targetVersion.value)<=0))
const supplierPage = computed(() => positive(route.query.supplierPage, 1))
const supplier = computed(() => styles.value?.supplier || (detail.value ? { supplierCode: detail.value.supplierCode, supplierName: detail.value.supplierName, styleCount: 0 } : undefined))
const supplierName = computed(() => supplier.value?.supplierName || supplierCode.value)
const canWrite = computed(() => isDetail.value ? detail.value?.canWrite === true : styles.value?.canWrite === true)
const canReadCost = computed(() => isDetail.value ? detail.value?.canReadCost === true : styles.value?.canReadCost === true)
const canManageSources = computed(() => canWrite.value || suppliers.value?.canWrite === true)
const rows = computed(() => styles.value?.content || [])
const allPageChecked = computed(() => rows.value.length > 0 && rows.value.every(row => checked.value.has(catalogKey(row))))
const title = computed(() => isDetail.value ? '供应商款式详情' : supplierCode.value ? `${supplierName.value} · 供应商产品目录` : '供应商产品目录')

async function load() {
  if(photoUploading.value)return
  cancelSelectionLoad()
  const serial = ++generation
  loading.value = true; error.value = ''
  supplierQuery.value = String(route.query.supplierQ || '')
  keyword.value = String(route.query.keyword || '')
  detail.value = undefined
  try {
    if (!supplierCode.value) {
      styles.value = undefined
      const result = await catalogSuppliers({ q: supplierQuery.value.trim(), page: supplierPage.value, size: 24,...(deleted.value?{deleted:'true'}:{}) })
      if (serial === generation) suppliers.value = result
    } else if (isDetail.value) {
      const result = await catalogStyle(supplierCode.value, { sourceKind: sourceKind.value as CatalogStyle['sourceKind'], recordId: Number(route.query.recordId), rowId: String(route.query.rowId) },deleted.value)
      if (serial === generation) detail.value = result
    } else {
      const result = await catalogStyles({ supplierCode: supplierCode.value, keyword: keyword.value.trim(), page: page.value, size: size.value,...(deleted.value?{deleted:'true'}:{}) })
      if (serial === generation) styles.value = result
    }
  } catch (cause) { if (serial === generation) error.value = partnerError(cause) }
  finally { if (serial === generation) loading.value = false }
}
watch([supplierCode,deleted], () => { checked.value = new Map(); selectionOpen.value = false; addition.value = undefined; styles.value = undefined })
watch(() => route.fullPath, () => { selectionOpen.value = false; modelSheetItem.value=undefined; modelSheetMessage.value=''; void load() }, { immediate: true })
watch(() => route.query.sourceManager, value => { if(value==='1')sourcesOpen.value=true }, {immediate:true})
function guardInput(){if(photoUploading.value||selectionBusy.value||removing.value||editing.value){ElMessage.warning(uiText('请先完成或取消当前填写，再切换页面。'));return false}}
onBeforeRouteUpdate(guardInput);onBeforeRouteLeave(guardInput)
onBeforeUnmount(() => { generation++;selectionGeneration++ })
function navigate(query: LocationQueryRaw) { return router.push({ path: '/partner-products', query }) }
function toggleDeleted() { return navigate({ ...route.query, sourceKind: undefined, recordId: undefined, rowId: undefined, deleted: deleted.value ? undefined : 'true', catalogPage: 1, supplierPage: 1 }) }
function runHeaderAction(command: string) {
  if (command === 'back') return back()
  if (command === 'supplier') return chooseAnother()
  if (command === 'edit' && detail.value) return editStyle(detail.value)
  if (command === 'product' && detail.value?.productId) return openProduct(detail.value.productId)
  if (command === 'remove' && detail.value) return setRemoved(detail.value)
  if (command === 'sources') { sourcesOpen.value = true; return }
  if (command === 'single') { openSingleEntry(); return }
  if (command === 'deleted') return toggleDeleted()
  if (command === 'identity' && detail.value) { identityItems.value = [detail.value]; return }
  if (command === 'listings') return router.push('/partner-listings')
  if (command === 'refresh') return load()
}
function runSelectionAction(command: string) {
  if (command === 'identity') identityItems.value = [...checked.value.values()]
  if (command === 'clear') { cancelSelectionLoad(); checked.value = new Map() }
}
function selectionContext():LocationQueryRaw{return Object.fromEntries(['selectionId','targetSelectionId','targetVersion','selectionVersion','from'].filter(key=>route.query[key]!==undefined).map(key=>[key,route.query[key]]))}
function chooseSupplier(row: CatalogSupplier) { return navigate({ ...selectionContext(),supplierCode: row.supplierCode, ...(route.query.supplierQ ? { supplierQ: String(route.query.supplierQ) } : {}),supplierPage: supplierPage.value,...(deleted.value?{deleted:'true'}:{}) }) }
function chooseAnother() { return navigate({ ...selectionContext(),...(route.query.supplierQ ? { supplierQ: String(route.query.supplierQ) } : {}), supplierPage: supplierPage.value }) }
function searchSuppliers() { return navigate({ ...selectionContext(),supplierQ: supplierQuery.value.trim() || undefined, supplierPage: 1 }) }
function searchStyles() { return navigate({ ...route.query, keyword: keyword.value.trim() || undefined, catalogPage: 1 }) }
function changePage(value: number) { return navigate({ ...route.query, catalogPage: value }) }
function changeSize(value: number) { return navigate({ ...route.query, catalogSize: value, catalogPage: 1 }) }
function open(row: CatalogStyle) { return navigate({ ...route.query, sourceKind: row.sourceKind, recordId: row.recordId, rowId: row.rowId }) }
async function setRemoved(row:CatalogStyle){
  if(!canWrite.value||removing.value||photoUploading.value)return
  removing.value=true;error.value=''
  try{
    const discarded=row.discarded!==true
    await ElMessageBox.confirm(discarded?t('删除后从目录移除，已有选品、样品和历史仍保留，可在已删除中恢复。'):t('恢复此产品到供应商目录？'),discarded?t('删除产品'):t('恢复产品'),{confirmButtonText:t('确认'),cancelButtonText:t('取消'),closeOnClickModal:false,type:'warning'})
    const current=await catalogStyle(supplierCode.value,{sourceKind:row.sourceKind,recordId:row.recordId,rowId:row.rowId},row.discarded===true)
    await updateCatalogStyle(supplierCode.value,current,{changes:{discarded},rowVersion:current.rowVersion,recordVersion:current.recordVersion,requestId:newRequestId()})
    checked.value=new Map();await load()
  }catch(cause){if(cause!=='cancel'&&cause!=='close')error.value=partnerError(cause)}finally{removing.value=false}
}
async function edited(){editing.value=undefined;checked.value=new Map();ElMessage.success(t('产品资料已保存'));await load()}
function editStyle(row:CatalogStyle){if(canWrite.value&&!row.discarded&&!photoUploading.value)editing.value=row}
function back() { const { sourceKind: _source, recordId: _record, rowId: _row, ...query } = route.query; return navigate(query) }
function openProduct(id: number) { return navigate({ id, from: route.fullPath }) }
function openSource() { if (!detail.value) return; const row = detail.value; if(row.sourcePurpose==='catalog_source'){sourcesOpen.value=true;return} return router.push(row.sourceKind === 'selection' ? { path: '/partner-selections', query: { id: row.recordId, from: route.fullPath } } : { path: `/partner-operations/supplier_quote/${row.recordId}`, query: { from: route.fullPath } }) }
function openSingleEntry() { singleEntry.value=true; sourcesOpen.value=true }
function check(row: CatalogStyle, value: boolean) { if(row.discarded)return;const next = new Map(checked.value); if (value) next.set(catalogKey(row), row); else next.delete(catalogKey(row)); checked.value = next }
function checkPage(value: boolean) { for (const row of rows.value) check(row, value) }
function cancelSelectionLoad(){selectionGeneration++;selectionLoading.value=false;selectionLoadError.value='';selectionLoaded.value=0;pendingSelection.value=[]}
async function add(candidates:CatalogStyle[]){
  if(!canWrite.value||photoUploading.value||loading.value||selectionLoading.value||!candidates.length)return
  if(invalidTargetContext.value){error.value='原选品目录链接无效，请返回后重新增选。';return}
  const selected=[...new Map(candidates.map(row=>[catalogKey(row),row])).values()],scope=supplierCode.value,serial=++selectionGeneration
  pendingSelection.value=selected;selectionLoadError.value='';selectionLoaded.value=0;selectionLoading.value=true;selectionOpen.value=false
  const current=()=>serial===selectionGeneration&&scope===supplierCode.value
  const complete:CatalogStyle[]=new Array(selected.length);let next=0,failure=''
  const worker=async()=>{while(current()&&!failure&&next<selected.length){const index=next++,candidate=selected[index];try{if(candidate.supplierCode!==scope)throw new Error('所选款式不属于当前供应商，请重新核对');const value=await catalogStyle(scope,{sourceKind:candidate.sourceKind,recordId:candidate.recordId,rowId:candidate.rowId});if(!current())return;if(catalogKey(value)!==catalogKey(candidate)||value.supplierCode!==scope)throw new Error('返回资料与所选款式不一致，请重新核对');if(value.canWrite!==true)throw new Error('当前无权选取本款，请核对权限');complete[index]=value;selectionLoaded.value++}catch(cause){if(current())failure=`${candidate.name||'所选款式'}：${partnerError(cause)}`}}}
  try{await Promise.all(Array.from({length:Math.min(3,selected.length)},worker));if(!current())return;if(failure){selectionLoadError.value=`款式资料读取未完成，未打开部分选样。${failure}。原勾选已保留，可重试。`;return}selectionItems.value=complete;selectionOpen.value=true;pendingSelection.value=[]}finally{if(current())selectionLoading.value=false}
}
function added(result: CatalogAdditionResult) {
  addition.value = result; checked.value = new Map()
  const refresh = load(), serial = generation
  void refresh.then(() => { if (serial === generation && error.value) error.value = `选品已保存，目录显示暂未更新：${error.value}。请重新加载目录。` })
}
function modelSheetSaved(result:{style:CatalogStyle}){modelSheetItem.value=undefined;detail.value=result.style;modelSheetMessage.value='本款型号表已更新；未创建产品、样品或手机编码。'}
const photos = (row: CatalogStyle) => (row.fileDetails || []).filter(file => file.type?.startsWith('image/'))
const photo = (row: CatalogStyle) => photos(row)[0]
const canAddPhoto=(row:CatalogStyle)=>canWrite.value&&row.canWrite!==false&&!row.discarded&&!deleted.value
function choosePhoto(row:CatalogStyle){
  if(!canAddPhoto(row)||photoUploading.value||editing.value||removing.value||selectionBusy.value)return
  photoTarget.value=row
  if(photoInput.value){photoInput.value.value='';photoInput.value.click()}
}
async function photoChosen(event:Event){
  const file=(event.target as HTMLInputElement).files?.[0],row=photoTarget.value
  if(!file||!row||!canAddPhoto(row)||photoUploading.value)return
  if(!['image/png','image/jpeg','image/webp'].includes(file.type)||!file.size||file.size>20*1024*1024){ElMessage.warning(uiText('请选择不超过20MB的PNG、JPEG或WebP图片'));return}
  pendingPhoto.value={row,file,requestId:newRequestId()};photoError.value=''
  await savePhoto()
}
async function savePhoto(){
  const pending=pendingPhoto.value
  if(!pending||photoUploading.value)return
  photoUploading.value=true;photoError.value=''
  try{
    if(!pending.identity){
      const current=await catalogStyle(pending.row.supplierCode,pending.row)
      if(catalogKey(current)!==catalogKey(pending.row)||current.supplierCode!==pending.row.supplierCode||current.canWrite!==true||current.discarded)throw new Error(uiText('当前产品不可添加图片，请刷新后重试'))
      pending.identity={sourceKind:current.sourceKind,recordId:current.recordId,rowId:current.rowId,rowVersion:current.rowVersion,recordVersion:current.recordVersion,supplierCode:current.supplierCode,requestId:pending.requestId}
    }
    const result=await uploadCatalogPhoto(pending.identity,pending.file)
    if(catalogKey(result)!==catalogKey(pending.row)||result.supplierCode!==pending.row.supplierCode)throw new Error(uiText('图片关联结果与当前产品不一致，请刷新核对'))
    if(styles.value)styles.value={...styles.value,content:styles.value.content.map(row=>catalogKey(row)===catalogKey(result)?result:row.recordId===result.recordId&&row.sourceKind===result.sourceKind?{...row,recordVersion:result.recordVersion}:row)}
    if(checked.value.has(catalogKey(result))){const next=new Map(checked.value);next.set(catalogKey(result),result);checked.value=next}
    pendingPhoto.value=undefined;ElMessage.success(uiText('产品图片已添加'))
  }catch(cause){photoError.value=partnerError(cause)}finally{photoUploading.value=false}
}
watch(()=>route.fullPath,()=>{pendingPhoto.value=undefined;photoTarget.value=undefined;photoError.value=''})
</script>

<template>
  <PageContainer :title="title" class="supplier-catalog-workspace">
    <input ref="photoInput" class="catalog-photo-input" type="file" accept="image/png,image/jpeg,image/webp" aria-label="选择产品图片" @change="photoChosen"/>
    <el-alert v-if="photoError&&pendingPhoto" :title="`${pendingPhoto.row.name}：${photoError}`" type="error" :closable="false" show-icon>
      <el-button link :disabled="photoUploading" @click="savePhoto">{{uiText('重试上传')}}</el-button>
      <el-button link :disabled="photoUploading" @click="choosePhoto(pendingPhoto.row)">{{uiText('重新选择图片')}}</el-button>
      <el-button link :disabled="photoUploading" @click="pendingPhoto=undefined;photoError=''">{{uiText('取消')}}</el-button>
    </el-alert>
    <template #actions>
      <div class="catalog-header-actions">
        <div class="catalog-header-actions__desktop">
      <el-button v-if="isDetail" @click="back">{{ uiText("返回款式列表") }}</el-button>
      <el-button v-else-if="supplierCode" @click="chooseAnother">{{ uiText("更换供应商") }}</el-button>
      <el-button v-if="canManageSources" @click="sourcesOpen=true">{{ uiText("供应商资料／导入") }}</el-button>
      <el-button v-if="supplierCode&&canWrite&&!deleted" type="primary" @click="sourcesOpen=true">{{t('新增产品')}}</el-button>
      <el-button v-if="!supplierCode&&canManageSources&&!deleted" @click="openSingleEntry">{{t('单个新增')}}</el-button>
      <el-button v-if="supplierCode&&canWrite&&!deleted" @click="openSingleEntry">{{t('单个新增')}}</el-button>
      <el-button @click="toggleDeleted">{{deleted?t('返回在用产品'):t('已删除产品')}}</el-button>
      <el-button v-if="isDetail&&detail&&canWrite" @click="identityItems=[detail]">{{ uiText("款式号核对") }}</el-button>
      <el-button @click="router.push('/partner-listings')">{{ uiText("上架产品目录") }}</el-button>
      <el-button :loading="loading" @click="load">{{ uiText("刷新") }}</el-button>
        </div>
      </div>
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon><el-button link @click="load">{{ uiText("重新加载") }}</el-button><el-button v-if="supplierCode" link @click="chooseAnother">{{ uiText("重新选择供应商") }}</el-button></el-alert>
    <el-alert v-if="modelSheetMessage" :title="modelSheetMessage" type="success" :closable="false"/>
    <div v-if="selectionLoading" class="catalog-selection-loading" role="status"><span>{{ uiText("正在读取所选款式的可选型号 ") }}{{selectionLoaded}} / {{pendingSelection.length}}</span><el-button link @click="cancelSelectionLoad">{{ uiText("取消本次读取") }}</el-button></div><el-alert v-else-if="selectionLoadError" :title="selectionLoadError" type="error" :closable="false" class="catalog-selection-load-error"><el-button link type="primary" @click="add(pendingSelection)">{{ uiText("重试本次所选款式") }}</el-button><el-button link @click="cancelSelectionLoad">{{ uiText("保留勾选，暂不选样") }}</el-button></el-alert>
    <div v-if="addition" class="catalog-addition" role="status"><span>{{ uiText("已保存到选品目录：") }}{{ addition.selection.name }}<template v-if="addition.reusedCount">（{{ addition.reusedCount }}{{ uiText(" 项沿用已有选择）") }}</template></span><el-button link type="primary" @click="router.push({path:'/partner-selections',query:{id:addition.selection.id,from:route.fullPath}})">{{ uiText("打开选品目录") }}</el-button></div>
    <div v-if="!supplierCode" key="suppliers" class="catalog-branch">
      <form class="catalog-search supplier-search" @submit.prevent="searchSuppliers">
        <el-input v-model="supplierQuery" :placeholder='uiText("搜索有款式的供应商")' :aria-label='uiText("搜索供应商")' clearable/>
        <el-button native-type="submit">{{ uiText("查询") }}</el-button>
        <el-dropdown class="catalog-search__more-actions" trigger="click" placement="bottom-end" @command="runHeaderAction">
          <el-button text :icon="MoreFilled" :aria-label="uiText('更多操作')" :title="uiText('更多操作')" />
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item v-if="canManageSources" command="sources">{{ uiText('供应商资料／导入') }}</el-dropdown-item>
            <el-dropdown-item v-if="canManageSources&&!deleted" command="single">{{ t('单个新增') }}</el-dropdown-item>
            <el-dropdown-item command="deleted">{{ deleted?t('返回在用产品'):t('已删除产品') }}</el-dropdown-item>
            <el-dropdown-item command="listings">{{ uiText('上架产品目录') }}</el-dropdown-item>
            <el-dropdown-item command="refresh" :disabled="loading">{{ uiText('刷新') }}</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
      </form>
      <div class="catalog-scroll supplier-scroll" :aria-busy="loading">
        <table v-if="suppliers?.content.length" class="supplier-table" :aria-label="uiText('合作选品供应商目录')">
          <thead><tr><th>{{uiText('供应商全名')}}</th><th>{{uiText('简称')}}</th><th>{{uiText('供应商类型')}}</th><th class="numeric">{{uiText('目录产品')}}</th><th class="numeric">{{uiText('已选产品')}}</th><th>{{uiText('操作')}}</th></tr></thead>
          <tbody><tr v-for="row in suppliers.content" :key="row.supplierCode"><td><button class="supplier-choice" type="button" :aria-label="uiText('{value0}，{value1}款，查看款式',{value0:row.supplierName,value1:row.styleCount})" @click="chooseSupplier(row)">{{row.supplierName}}</button><small v-if="row.status==='inactive'">{{uiText('资料已停用')}}</small></td><td :data-label="uiText('简称')">{{row.shortName||uiText('未填写')}}</td><td :data-label="uiText('类型')">{{row.businessCategories?.includes('partner_selection')?uiText('合作选品供应商'):uiText('待分类')}}</td><td class="numeric" :data-label="uiText('目录')">{{row.styleCount}} {{uiText('款')}}</td><td class="numeric selected-count" :data-label="uiText('已选')"><strong>{{row.selectedStyleCount??0}}</strong> {{uiText('款')}}</td><td><el-button link type="primary" @click="chooseSupplier(row)">{{uiText('查看产品')}}</el-button></td></tr></tbody>
        </table>
        <el-empty v-else-if="!loading&&!error" :description='uiText("没有找到有款式的供应商")' :image-size="65"><el-button v-if="suppliers?.canWrite" @click="router.push({path:'/suppliers',query:{from:route.fullPath}})">{{ uiText("维护供应商资料") }}</el-button></el-empty>
        <p v-if="loading&&!suppliers" class="catalog-loading" role="status">{{ uiText("正在加载供应商…") }}</p>
      </div>
      <footer class="catalog-pager"><span>{{ uiText("共 ") }}{{ suppliers?.totalElements || 0 }}{{ uiText(" 家有款式的供应商") }}</span><el-pagination v-if="(suppliers?.totalElements||0)>24" :current-page="supplierPage" :page-size="24" :total="suppliers?.totalElements||0" :pager-count="5" layout="prev,pager,next" :disabled="loading" @current-change="navigate({...route.query,supplierPage:$event})"/></footer>
    </div>
    <div v-else-if="isDetail" key="detail" class="catalog-scroll detail-scroll" :aria-busy="loading"><SupplierStyleDetail v-if="detail" :row="detail" :can-read-cost="canReadCost" :can-edit="canWrite&&!removing&&!detail.discarded" @edit="editStyle(detail)" @product="openProduct" @source="openSource" @model-sheet="modelSheetItem=detail"><template #mobile-actions><el-dropdown class="catalog-detail-actions-mobile" trigger="click" placement="bottom-end" @command="runHeaderAction"><el-button text :icon="MoreFilled" :aria-label="uiText('更多操作')" :title="uiText('更多操作')"/><template #dropdown><el-dropdown-menu><el-dropdown-item command="back">{{uiText('返回款式列表')}}</el-dropdown-item><el-dropdown-item v-if="canWrite&&!detail.discarded" command="edit">{{uiText('编辑产品资料')}}</el-dropdown-item><el-dropdown-item v-if="detail.productId" command="product">{{uiText('关联我方产品')}} {{detail.productCode||''}}</el-dropdown-item><el-dropdown-item v-if="canManageSources" command="sources">{{uiText('供应商资料／导入')}}</el-dropdown-item><el-dropdown-item v-if="canWrite&&!deleted" command="single">{{t('单个新增')}}</el-dropdown-item><el-dropdown-item command="deleted">{{deleted?t('返回在用产品'):t('已删除产品')}}</el-dropdown-item><el-dropdown-item v-if="canWrite" command="identity">{{uiText('款式号核对')}}</el-dropdown-item><el-dropdown-item v-if="canWrite" command="remove" divided>{{detail.discarded?t('恢复产品'):t('删除产品')}}</el-dropdown-item><el-dropdown-item command="listings">{{uiText('上架产品目录')}}</el-dropdown-item><el-dropdown-item command="refresh" :disabled="loading">{{uiText('刷新')}}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></SupplierStyleDetail><el-button class="detail-remove" v-if="detail&&canWrite" :disabled="removing" @click="setRemoved(detail)">{{detail.discarded?t('恢复产品'):t('删除产品')}}</el-button><p v-else-if="loading" class="catalog-loading" role="status">{{ uiText("正在加载本款资料…") }}</p></div>
    <div v-else key="styles" class="catalog-branch">
      <form class="catalog-search" @submit.prevent="searchStyles">
        <el-input v-model="keyword" :placeholder='uiText("搜索款式、货号、手机型号或编码")' :aria-label='uiText("搜索供应商款式")' clearable/>
        <el-button native-type="submit" :disabled="loading">{{ uiText("查询") }}</el-button>
        <el-dropdown class="catalog-search__more-actions" trigger="click" placement="bottom-end" @command="runHeaderAction">
          <el-button text :icon="MoreFilled" :aria-label="uiText('更多操作')" :title="uiText('更多操作')" />
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item command="supplier">{{ uiText('更换供应商') }}</el-dropdown-item>
            <el-dropdown-item v-if="canManageSources" command="sources">{{ uiText('供应商资料／导入') }}</el-dropdown-item>
            <el-dropdown-item v-if="canWrite&&!deleted" command="single">{{ t('单个新增') }}</el-dropdown-item>
            <el-dropdown-item command="deleted">{{ deleted?t('返回在用产品'):t('已删除产品') }}</el-dropdown-item>
            <el-dropdown-item command="listings">{{ uiText('上架产品目录') }}</el-dropdown-item>
            <el-dropdown-item command="refresh" :disabled="loading">{{ uiText('刷新') }}</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
      </form>
      <div class="catalog-list-toolbar">
        <div class="catalog-list-toolbar__summary"><el-checkbox v-if="canWrite" :model-value="allPageChecked" :indeterminate="!allPageChecked&&rows.some(row=>checked.has(catalogKey(row)))" :disabled="loading||selectionLoading" @change="checkPage(Boolean($event))">{{ uiText("勾选本页") }}</el-checkbox><span v-if="canWrite">{{ uiText("已选 ") }}{{ checked.size }}{{ uiText(" 款（跨页保留）") }}</span><span v-else>{{ supplierName }}{{ uiText("的款式") }}</span><el-button v-if="checked.size" class="catalog-clear-selection" link @click="cancelSelectionLoad();checked=new Map()">{{ uiText("取消勾选") }}</el-button></div>
        <div v-if="canWrite" class="catalog-list-toolbar__desktop-actions"><el-button :disabled="!checked.size||loading" @click="identityItems=[...checked.values()]">{{ uiText("款式号／字段核对") }}</el-button></div>
        <div v-if="canWrite" class="catalog-list-toolbar__mobile-actions">
          <el-dropdown trigger="click" placement="bottom-end" @command="runSelectionAction">
            <el-button text :icon="MoreFilled" :aria-label="uiText('更多选品操作')" :title="uiText('更多选品操作')" />
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="identity" :disabled="!checked.size||loading">{{ uiText('款式号／字段核对') }}</el-dropdown-item><el-dropdown-item v-if="checked.size" command="clear">{{ uiText('取消勾选') }}</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
        <el-button v-if="canWrite" class="catalog-add-action" type="primary" size="small" :icon="FolderAdd" :aria-label="uiText('加入选品目录')" :title="uiText('加入选品目录')" :disabled="!checked.size||loading||selectionLoading" :loading="selectionLoading" @click="add([...checked.values()])"><span class="catalog-add-action__label" :data-short-label="uiText('加入')">{{ uiText("加入选品目录") }}</span></el-button>
      </div>
      <div class="catalog-scroll catalog-style-list" :class="{'without-cost':!canReadCost,'readonly-catalog':!canWrite}" :aria-busy="loading">
        <div class="catalog-style-head" aria-hidden="true"><span>{{ uiText("图片") }}</span><span v-if="canWrite" class="catalog-check-title">{{ uiText("勾选") }}</span><span>{{ uiText("款式编号／名称") }}</span><span>{{ uiText("手机型号") }}</span><span>{{ uiText("供应商颜色") }}</span><span>{{ uiText("材质") }}</span><span v-if="canReadCost">{{ uiText("报价") }}</span><span v-if="canReadCost">{{ uiText("币种") }}</span><span>{{ uiText("起订数量") }}</span><span>{{ uiText("起订口径") }}</span><span>{{ uiText("操作") }}</span></div>
        <article v-for="row in rows" :key="catalogKey(row)" class="catalog-style-row" :data-style-key="catalogKey(row)">
          <div class="catalog-style-photo">
            <PartnerPhoto v-if="photo(row)" :id="photo(row)!.id" previewable :preview-files="photos(row)"/>
            <button v-else-if="canAddPhoto(row)" type="button" class="catalog-no-photo catalog-photo-upload" :disabled="photoUploading" :aria-label="`${uiText('添加产品图片')}：${row.name}`" :title="uiText('点击添加图片（PNG / JPEG / WebP，最大20MB）')" @click.stop="choosePhoto(row)">{{photoUploading&&pendingPhoto&&catalogKey(pendingPhoto.row)===catalogKey(row)?uiText('上传中…'):uiText('添加图片')}}</button>
            <span v-else class="catalog-no-photo">{{ uiText("暂无图片") }}</span>
            <button v-if="photo(row)&&canAddPhoto(row)" type="button" class="catalog-photo-add" :disabled="photoUploading" :aria-label="`${uiText('添加产品图片')}：${row.name}`" :title="uiText('添加图片')" @click.stop="choosePhoto(row)">+</button>
            <span v-if="photo(row)" class="catalog-photo-open" aria-hidden="true">{{ photos(row).length }}{{ uiText("图") }}</span>
          </div>
          <div v-if="canWrite" class="catalog-style-check"><el-checkbox :model-value="checked.has(catalogKey(row))" :aria-label="uiText('选择{value0}',{value0:(row.name)})" :disabled="loading||selectionLoading||row.discarded" @change="check(row,Boolean($event))"/></div>
          <div class="catalog-style-name"><strong class="catalog-style-code">{{row.catalogStyleLabel||row.catalogStyleCode||uiText('款式号待补')}}</strong><el-button link type="primary" @click="open(row)">{{ row.name || uiText('未命名款式') }}</el-button><small v-if="row.supplierItemCode">{{ uiText("供方款号：") }}{{row.supplierItemCode}}</small><el-button v-if="row.productId" link @click="openProduct(row.productId)">{{ row.productCode || uiText('关联我方产品') }}</el-button></div>
          <div class="catalog-style-model"><span class="catalog-cell-label">{{ uiText("手机型号") }}</span><PhoneModelSummary :phones="row.phones||[]" :count="Math.max(new Set(row.phoneModelIds||[]).size,new Set((row.phones||[]).map(phone=>phone.phoneModelId)).size)" :fallback="catalogPhoneSummary(row)" :original-text="row.modelText" scope="supply"/></div>
          <div class="catalog-style-config"><span class="catalog-cell-label">{{ uiText("供应商颜色") }}</span><strong>{{ row.color || uiText('未提供') }}</strong></div>
          <div class="catalog-style-material"><span class="catalog-cell-label">{{ uiText("材质") }}</span><span v-if="!row.material||row.material.length<=12">{{row.material||uiText('未提供')}}</span><el-button v-else link type="primary" @click="open(row)">{{ uiText("查看材质") }}</el-button></div>
          <div v-if="canReadCost" class="catalog-style-price"><span class="catalog-cell-label">{{ uiText("报价") }}</span><strong>{{ supplierPriceValue(row) }}</strong><small class="catalog-style-price__currency">{{supplierCurrency(row)}}<span v-if="row.currencySource==='business_default'"> · {{uiText("业务默认")}}</span></small></div>
          <div v-if="canReadCost" class="catalog-style-currency"><span class="catalog-cell-label">{{ uiText("币种") }}</span><span>{{supplierCurrency(row)}}</span><small v-if="row.currencySource==='business_default'">{{ uiText("业务默认") }}</small></div>
          
          <div class="catalog-style-moq"><span class="catalog-cell-label">{{ uiText("起订数量") }}</span><span>{{row.moqQuantity??uiText('未确认')}}<small v-if="row.moqUnit"> {{row.moqUnit}}</small></span></div>
          
          <div class="catalog-style-moq-scope"><span class="catalog-cell-label">{{ uiText("起订口径") }}</span><span>{{uiText(moqScopeLabel(row.moqScope))}}</span></div>
          <div class="catalog-style-actions"><el-button link type="primary" @click="open(row)">{{ uiText("详情") }}</el-button><el-button v-if="canWrite&&!row.discarded" link type="primary" :disabled="removing" :aria-label="t('编辑产品')+' '+row.name" @click="editStyle(row)">{{t('编辑')}}</el-button><el-button v-if="canWrite" link :type="row.discarded?'primary':'danger'" :disabled="removing" @click="setRemoved(row)">{{row.discarded?t('恢复'):t('删除')}}</el-button></div>
        </article>
        <el-empty v-if="!loading&&!rows.length&&!error" :description="keyword?uiText('该供应商暂无符合条件的款式'):uiText('该供应商还没有目录款式')" :image-size="65"><div v-if="canWrite" class="catalog-empty-actions"><p>{{ uiText("先导入供应商资料，再选需要的款式和具体型号。") }}</p><el-button @click="sourcesOpen=true">{{ uiText("导入供应商资料") }}</el-button><el-button @click="router.push({path:'/suppliers',query:{code:supplierCode,from:route.fullPath}})">{{ uiText("维护型号与颜色范围") }}</el-button></div></el-empty>
        <p v-if="loading&&!styles" class="catalog-loading" role="status">{{ uiText("正在加载供应商款式…") }}</p>
      </div>
      <footer class="catalog-pager"><span>{{ uiText("共 ") }}{{ styles?.totalElements || 0 }}{{ uiText(" 款") }}</span><el-pagination :current-page="page" :page-size="size" :page-sizes="[20,30,50,100]" :total="styles?.totalElements||0" :pager-count="5" layout="sizes,prev,pager,next" :disabled="loading" @current-change="changePage" @size-change="changeSize"/></footer>
    </div>
    <CatalogSelectionDialog v-model="selectionOpen" :supplier-code="supplierCode" :supplier-name="supplierName" :items="selectionItems" :can-write="canWrite" :target-selection-id="targetSelectionId" :target-version="targetVersion" @busy="selectionBusy=$event" @added="added"/>
    <CatalogSourceImport v-model="sourcesOpen" :supplier-code="supplierCode" :can-write="canManageSources" :single-entry="singleEntry" @update:model-value="value=>{sourcesOpen=value;if(!value)singleEntry=false}" @changed="load"/>
    <CatalogIdentityDialog v-if="identityItems.length" :supplier-code="supplierCode" :items="identityItems" @close="identityItems=[]" @saved="identityItems=[];checked=new Map();load()"/>
    <CatalogModelSheetDialog v-if="modelSheetItem" :row="modelSheetItem" @close="modelSheetItem=undefined" @saved="modelSheetSaved"/>
    <CatalogStyleEditor v-if="editing" :item="editing" :can-read-cost="canReadCost" @close="editing=undefined" @saved="edited"/>
  </PageContainer>
</template>

<style scoped>
.supplier-catalog-workspace{height:100%;min-height:0;min-width:0;display:flex;flex-direction:column;gap:7px;container-type:inline-size}
.supplier-catalog-workspace :deep(>.page-panel){padding:6px 10px;flex:none}
.supplier-catalog-workspace :deep(.page-panel-header){margin:0;flex-direction:row;align-items:center;flex-wrap:wrap;gap:6px 12px}
.supplier-catalog-workspace :deep(.page-panel-title){font-size:17px;line-height:1.5;margin:0;overflow-wrap:anywhere}
.supplier-catalog-workspace :deep(.toolbar-actions){gap:6px;flex-wrap:wrap}
.catalog-header-actions{display:flex;align-items:center;justify-content:flex-end;min-width:0}
.catalog-header-actions__desktop{display:flex;align-items:center;justify-content:flex-end;gap:6px;flex-wrap:wrap}
.catalog-search__more-actions{display:none}
.catalog-detail-actions-mobile{display:none}
.catalog-selection-loading{display:flex;align-items:center;gap:7px;flex-wrap:wrap;font-size:13px;line-height:1.6;flex:none}.catalog-selection-load-error{flex:none}
.catalog-branch{display:flex;flex-direction:column;gap:7px;flex:1;min-height:0;min-width:0}
.catalog-search,.catalog-list-toolbar,.catalog-list-toolbar>div,.catalog-pager{display:flex;gap:6px 10px;align-items:center;flex-wrap:wrap;flex:none;min-width:0}
.catalog-search .el-input{flex:1;min-width:0}.supplier-search .el-input{max-width:480px}
.catalog-list-toolbar__mobile-actions{display:none}
.catalog-list-toolbar__summary{min-width:0}
.catalog-list-toolbar__summary>span{white-space:nowrap}
.catalog-scroll{flex:1;min-height:0;min-width:0;overflow-y:auto}
.supplier-scroll{overflow:auto;border:1px solid var(--el-border-color-lighter);border-radius:4px;background:var(--el-bg-color)}
.supplier-table{width:100%;border-collapse:separate;border-spacing:0;font-size:12px;line-height:1.5;text-align:left}
.supplier-table th{position:sticky;top:0;z-index:1;background:var(--el-fill-color-light);color:var(--el-text-color-secondary);font-weight:500;white-space:nowrap}
.supplier-table th,.supplier-table td{padding:7px 12px;height:40px;box-sizing:border-box;border-bottom:1px solid var(--el-border-color-lighter)}
.supplier-table th:first-child{width:38%}.supplier-table td:first-child{overflow-wrap:anywhere}.supplier-table td:not(:first-child){white-space:nowrap}.supplier-table tr:hover td{background:var(--el-fill-color-lighter)}
.supplier-table .numeric{text-align:right;font-variant-numeric:tabular-nums}.selected-count strong{font-size:15px;color:var(--el-color-primary)}
.supplier-choice{border:0;background:none;padding:0;color:var(--el-color-primary);font:inherit;text-align:left;cursor:pointer;overflow-wrap:anywhere}.supplier-choice:hover{text-decoration:underline}.supplier-table small{display:block;color:var(--el-text-color-secondary)}
@container(max-width:760px){
 .supplier-table,.supplier-table tbody{display:block}.supplier-table thead{display:none}
 .supplier-table tbody tr{position:relative;display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);padding:7px 10px;border-bottom:1px solid var(--el-border-color-lighter)}
 .supplier-table td{border:0;height:auto;padding:3px 0;min-width:0}.supplier-table td:first-child{grid-column:1/-1;padding-right:72px}.supplier-table td:not(:first-child){white-space:normal}
 .supplier-table td::before{content:attr(data-label);color:var(--el-text-color-secondary);margin-right:6px}.supplier-table .numeric{text-align:left}.supplier-table td:last-child{position:absolute;right:10px;top:7px}
}
.catalog-pager,.catalog-list-toolbar{justify-content:space-between;font-size:12px;color:var(--el-text-color-secondary)}.catalog-list-toolbar .el-checkbox{margin:0;height:28px}
.catalog-style-list{border:1px solid var(--el-border-color-lighter);border-radius:6px;background:var(--el-bg-color);--catalog-columns:64px 30px minmax(0,1.5fr) minmax(0,1.7fr) minmax(0,1fr) minmax(0,1fr) minmax(0,.8fr) minmax(0,.65fr) minmax(0,.65fr) minmax(0,.9fr) 108px}
.without-cost{--catalog-columns:64px 30px minmax(0,1.5fr) minmax(0,1.7fr) minmax(0,1fr) minmax(0,1fr) minmax(0,.65fr) minmax(0,.9fr) 108px}
.readonly-catalog{--catalog-columns:64px minmax(0,1.5fr) minmax(0,1.7fr) minmax(0,1fr) minmax(0,1fr) minmax(0,.8fr) minmax(0,.65fr) minmax(0,.65fr) minmax(0,.9fr) 108px}
.readonly-catalog.without-cost{--catalog-columns:64px minmax(0,1.5fr) minmax(0,1.7fr) minmax(0,1fr) minmax(0,1fr) minmax(0,.65fr) minmax(0,.9fr) 108px}
.catalog-style-head,.catalog-style-row{display:grid;grid-template-columns:var(--catalog-columns);align-items:stretch;min-width:0}
.catalog-style-head{position:sticky;top:0;z-index:1;background:var(--el-fill-color-light);font-size:12px;color:var(--el-text-color-secondary)}
.catalog-style-head>span,.catalog-style-row>div{padding:4px 6px;border-right:1px solid var(--el-border-color-lighter);min-width:0;overflow-wrap:anywhere}
.catalog-style-head>span:last-child,.catalog-style-row>div:last-child{border-right:0}
.catalog-style-row{border-top:1px solid var(--el-border-color-lighter);font-size:13px;line-height:1.6;min-height:52px}.catalog-style-row:hover{background:var(--el-fill-color-extra-light)}
.catalog-style-row>div:not(.catalog-style-photo):not(.catalog-style-check):not(.catalog-style-actions){display:flex;flex-direction:column;justify-content:center;gap:2px}
.catalog-style-photo{position:relative;display:flex;align-items:center;justify-content:center}.catalog-style-photo :deep(.partner-photo){width:44px;height:44px}
.catalog-photo-open{position:absolute;right:4px;top:4px;border:1px solid var(--el-color-primary-light-7);border-radius:3px;background:var(--el-bg-color);color:var(--el-color-primary);padding:0 3px;font-size:11px;line-height:16px;pointer-events:none}
.catalog-no-photo{font-size:11px;color:var(--el-text-color-secondary);background:var(--el-fill-color-light);width:44px;height:44px;display:grid;place-items:center;border-radius:5px}
.catalog-photo-input{display:none}.catalog-photo-upload{border:1px dashed var(--el-border-color);cursor:pointer;padding:2px;line-height:1.4}.catalog-photo-upload:hover{border-color:var(--el-color-primary);color:var(--el-color-primary);background:var(--el-color-primary-light-9)}.catalog-photo-upload:disabled,.catalog-photo-add:disabled{cursor:wait;opacity:.6}.catalog-photo-add{position:absolute;right:0;top:-5px;width:20px;height:20px;border:1px solid var(--el-border-color);border-radius:50%;background:white;color:#303133;cursor:pointer;z-index:1;line-height:16px;padding:0;font-size:16px}.catalog-photo-upload:focus-visible,.catalog-photo-add:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}
.catalog-style-check{display:grid;place-items:center;padding:3px!important}.catalog-style-check .el-checkbox{margin:0}
.catalog-style-name>.el-button{font-size:14px;font-weight:600;justify-content:flex-start}.catalog-style-code{font-size:12px;color:var(--el-color-primary);font-weight:500}
.catalog-style-row small{font-size:12px;color:var(--el-text-color-secondary);line-height:1.5}.catalog-style-model strong{font-size:15px;font-weight:600;color:#284d7c}
.catalog-style-actions{display:flex;align-items:center;align-content:center;flex-wrap:nowrap;gap:3px 8px}.catalog-style-actions :deep(.el-button){flex:0 0 auto;font-size:13px;padding:0}
.catalog-style-price__currency{display:none}
.catalog-cell-label{display:none}
.supplier-catalog-workspace :deep(.el-button){white-space:normal;height:auto;min-height:24px;max-width:100%;line-height:1.5;overflow-wrap:anywhere}
.supplier-catalog-workspace :deep(.el-button>span){min-width:0;white-space:normal;overflow-wrap:anywhere}.supplier-catalog-workspace :deep(.el-button+.el-button){margin-left:0}
.catalog-addition{display:flex;align-items:center;gap:8px;flex-wrap:wrap;background:var(--el-color-success-light-9);padding:5px 10px;border-radius:5px;font-size:13px;overflow-wrap:anywhere;flex:none}
.catalog-loading{text-align:center;color:var(--el-text-color-secondary);padding:24px;font-size:13px}.catalog-empty-actions{font-size:13px;line-height:1.6}.catalog-empty-actions p{margin:0 0 8px}
@container(max-width:1150px){
 .catalog-style-head{display:none}
 .catalog-style-row{display:grid;grid-template-columns:44px 24px minmax(0,1.25fr) minmax(0,.7fr) minmax(0,.8fr) minmax(0,1fr) 88px;grid-template-rows:auto auto;align-items:center;padding:5px 6px;gap:1px 6px}
 .catalog-style-row>div{border:0;padding:0}
 .catalog-style-photo{grid-column:1;grid-row:1/3;width:44px;align-self:start;margin-top:3px}
 .catalog-style-check{grid-column:2;grid-row:1/3;align-self:start}
 .catalog-style-name{grid-column:3/5;grid-row:1}
 .catalog-style-model{grid-column:5/7;grid-row:1}
 .catalog-style-model>.catalog-cell-label{display:none}
 .catalog-style-row>.catalog-style-config{grid-column:3;grid-row:2}
 .catalog-style-row>.catalog-style-price{grid-column:4;grid-row:2}
 .catalog-style-row>.catalog-style-currency{grid-column:5;grid-row:2}
 .catalog-style-row>.catalog-style-moq{grid-column:6;grid-row:2}
 .catalog-style-row>.catalog-style-material,.catalog-style-row>.catalog-style-moq-scope{display:none!important}
 .catalog-style-row>div:not(.catalog-style-photo):not(.catalog-style-check):not(.catalog-style-name):not(.catalog-style-model):not(.catalog-style-actions){flex-direction:row;align-items:baseline;justify-content:flex-start;gap:4px;flex-wrap:wrap;line-height:1.5}
 .catalog-cell-label{display:inline;font-size:12px;color:var(--el-text-color-secondary);flex:none}
 .catalog-style-actions{grid-column:7;grid-row:1/3;flex-direction:column;align-items:flex-start;justify-content:center;gap:1px}
 .without-cost .catalog-style-row{grid-template-columns:44px 24px repeat(4,minmax(0,1fr)) 88px}
 .without-cost .catalog-style-config{grid-column:3/5}.without-cost .catalog-style-moq{grid-column:5/7}
 .readonly-catalog .catalog-style-row{grid-template-columns:44px 0 minmax(0,1.25fr) minmax(0,.7fr) minmax(0,.8fr) minmax(0,1fr) 52px}
 .readonly-catalog.without-cost .catalog-style-row{grid-template-columns:44px 0 repeat(4,minmax(0,1fr)) 52px}
}
@container(max-width:600px){
 .supplier-catalog-workspace :deep(>.page-panel){display:none}
 .catalog-header-actions__desktop{display:none}
 .catalog-search{display:grid;grid-template-columns:minmax(0,1fr) auto 40px;flex-wrap:nowrap;gap:6px;width:100%}
 .catalog-search>.el-input{width:100%;min-width:0;margin:0}
 .catalog-search>.el-button{flex:none;min-width:54px;margin:0;padding-inline:10px}
 .catalog-search__more-actions{display:flex;align-items:center;justify-content:center;width:40px;height:40px}
 .catalog-search__more-actions :deep(.el-button){width:40px;height:40px;min-height:40px;margin:0;padding:0}
 .catalog-detail-actions-mobile{display:flex;align-items:center;justify-content:center;flex:none;width:40px;height:40px}
 .catalog-detail-actions-mobile :deep(.el-button){width:40px;height:40px;min-height:40px;margin:0;padding:0}
 .detail-remove{display:none}
 .catalog-list-toolbar{flex-wrap:nowrap;gap:6px}
 .catalog-list-toolbar__summary{display:flex;flex:1;align-items:center;gap:5px;white-space:nowrap;overflow:hidden}
 .catalog-list-toolbar__summary>span{overflow:hidden;text-overflow:ellipsis}
 .catalog-list-toolbar__summary .el-checkbox{flex:none}
 .catalog-list-toolbar__summary .catalog-clear-selection{display:none}
 .catalog-list-toolbar__desktop-actions{display:none!important}
 .catalog-list-toolbar__mobile-actions{display:flex;flex:none;align-items:center;gap:5px}
 .catalog-list-toolbar__mobile-actions>.el-button{flex:none}
 .catalog-add-action{white-space:nowrap!important;min-width:86px;padding-inline:9px!important}
 .catalog-add-action__label{white-space:nowrap!important}
 .catalog-style-list{border:0;background:transparent;border-radius:0;padding:0 1px 8px;overflow-y:auto}
 .catalog-style-row,.without-cost .catalog-style-row,.readonly-catalog .catalog-style-row,.readonly-catalog.without-cost .catalog-style-row{position:relative;grid-template-columns:42px minmax(0,1fr) minmax(0,1fr);grid-template-rows:auto;grid-auto-rows:minmax(0,auto);align-items:start;gap:3px 8px;padding:8px;border:1px solid var(--el-border-color-lighter);border-radius:8px;background:var(--el-bg-color);box-shadow:0 1px 2px rgb(15 23 42 / 4%);min-height:0;margin-bottom:7px}
 .catalog-style-row>div{border:0;padding:0}
 .catalog-style-photo{grid-column:1;grid-row:1/7;width:42px;align-self:start;margin-top:2px}
 .catalog-style-photo :deep(.partner-photo),.catalog-no-photo{width:40px;height:40px}
 .catalog-style-check{position:absolute;top:5px;right:7px;z-index:2;display:grid;place-items:center;background:var(--el-bg-color);border-radius:3px;padding:1px!important}
 .catalog-style-name{grid-column:2/-1;grid-row:1;display:flex!important;flex-direction:row!important;align-items:baseline!important;flex-wrap:wrap;gap:0 6px;min-width:0;padding-right:26px!important}
 .catalog-style-code{flex:none;white-space:nowrap;padding:1px 4px;border-radius:4px;background:var(--el-fill-color-light);font-size:11px;color:var(--el-text-color-secondary)}
 .catalog-style-name>.el-button{flex:0 1 auto;min-width:0;justify-content:flex-start;font-size:14px;line-height:1.35;font-weight:600;color:var(--el-text-color-primary)}
 .catalog-style-name>small{flex:1 0 100%}
 .catalog-style-model{grid-column:2/-1;grid-row:2;display:flex!important;flex-direction:row!important;align-items:baseline!important;flex-wrap:wrap;gap:2px 5px;min-width:0;margin-top:2px;padding-top:4px!important;border-top:1px solid var(--el-border-color-lighter)!important}
 .catalog-style-model>.catalog-cell-label{display:inline;margin:0}
 .catalog-style-model strong,.catalog-style-model .model-title{font-size:12px;line-height:1.35}
 .catalog-style-model :deep(.phone-model-summary){display:flex;flex:1 1 auto;align-items:baseline;flex-wrap:wrap;gap:0 4px;min-width:0}
 .catalog-style-model :deep(.phone-model-summary .all-models){margin:0}
 .catalog-style-row>.catalog-style-config{grid-column:2;grid-row:3}
 .catalog-style-row>.catalog-style-price{grid-column:3;grid-row:3}
 .catalog-style-row>.catalog-style-currency{display:none!important}
 .catalog-style-row>.catalog-style-moq{grid-column:3;grid-row:4}
 .catalog-style-row>.catalog-style-material{display:flex!important;grid-column:2;grid-row:4}
 .catalog-style-row>.catalog-style-moq-scope{display:flex!important;grid-column:2/-1;grid-row:5}
 .catalog-style-row>.catalog-style-config,.catalog-style-row>.catalog-style-price,.catalog-style-row>.catalog-style-currency,.catalog-style-row>.catalog-style-moq,.catalog-style-row>.catalog-style-material,.catalog-style-row>.catalog-style-moq-scope{display:flex;min-width:0;flex-direction:row!important;align-items:baseline!important;justify-content:flex-start!important;flex-wrap:nowrap!important;gap:0 4px!important;line-height:1.35;white-space:nowrap;overflow:hidden}
 .catalog-style-price__currency{display:inline-flex;margin-left:2px}
 .catalog-style-row>.catalog-style-material,.catalog-style-row>.catalog-style-moq-scope{display:flex!important}
 .catalog-style-row>.catalog-style-material>span:not(.catalog-cell-label),.catalog-style-row>.catalog-style-moq-scope>span:not(.catalog-cell-label),.catalog-style-row>.catalog-style-config>strong{overflow-wrap:anywhere}
 .catalog-cell-label{display:inline;font-size:10px;line-height:1.3;white-space:nowrap;flex:none}
 .catalog-style-row>.catalog-style-config>strong,.catalog-style-row>.catalog-style-price>strong,.catalog-style-row>.catalog-style-moq>span,.catalog-style-row>.catalog-style-material>span:not(.catalog-cell-label),.catalog-style-row>.catalog-style-moq-scope>span:not(.catalog-cell-label){min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
 .catalog-style-row small{font-size:10px;line-height:1.3;white-space:nowrap}
 .catalog-style-photo :deep(.photo-state){max-width:100%;font-size:0;line-height:1.15;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
 .catalog-style-photo :deep(.photo-state::before){content:'图片';font-size:10px}
 .catalog-style-photo :deep(.photo-state small){display:none}
 .catalog-style-currency small{margin-left:2px}
 .catalog-style-actions{grid-column:2/-1;grid-row:6;display:flex;flex-direction:row;align-items:center;gap:2px 14px;padding-top:3px!important;border-top:1px solid var(--el-border-color-lighter)!important}
 .catalog-style-actions :deep(.el-button){min-height:28px;font-size:12px}
 .catalog-add-action{min-width:62px!important;height:30px!important;padding:0 8px!important;font-size:12px!important;gap:4px}
 .catalog-add-action__label{font-size:0!important;line-height:1}
 .catalog-add-action__label::after{content:attr(data-short-label);font-size:12px}
 .readonly-catalog .catalog-style-row,.readonly-catalog.without-cost .catalog-style-row{grid-template-columns:42px minmax(0,1fr) minmax(0,1fr)}
 .readonly-catalog .catalog-style-photo,.readonly-catalog.without-cost .catalog-style-photo{grid-column:1}
 .readonly-catalog .catalog-style-check,.readonly-catalog.without-cost .catalog-style-check{display:none}
 .readonly-catalog .catalog-style-name,.readonly-catalog .catalog-style-model,.readonly-catalog .catalog-style-actions,.readonly-catalog.without-cost .catalog-style-name,.readonly-catalog.without-cost .catalog-style-model,.readonly-catalog.without-cost .catalog-style-actions{grid-column:2/-1}
 .without-cost .catalog-style-row>.catalog-style-config{grid-column:2;grid-row:3}
 .without-cost .catalog-style-row>.catalog-style-material{grid-column:3;grid-row:3}
 .without-cost .catalog-style-row>.catalog-style-moq{grid-column:2;grid-row:4}
 .without-cost .catalog-style-row>.catalog-style-moq-scope{grid-column:3;grid-row:4}
 .without-cost .catalog-style-actions{grid-row:5}
 .readonly-catalog.without-cost .catalog-style-actions{grid-row:5}
 .catalog-pager{flex-wrap:nowrap;gap:5px}
 .catalog-pager>span{white-space:nowrap}
 .catalog-pager :deep(.el-pagination){max-width:100%;min-width:0;flex-wrap:nowrap;justify-content:flex-end;gap:2px}
 .catalog-pager :deep(.el-pagination__sizes){margin-right:0}
 .catalog-pager :deep(.el-select){max-width:92px}
}
</style>
