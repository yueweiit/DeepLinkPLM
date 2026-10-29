<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteUpdate, type LocationQueryRaw } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageContainer from '@/components/PageContainer/index.vue'
import { catalogStyle, catalogStyles, catalogSuppliers, renameCatalogStyle } from '@/api/modules/partnerCatalog'
import { newRequestId } from '@/api/modules/partner'
import type { CatalogAdditionResult, CatalogPage, CatalogStyle, CatalogSupplier } from '@/types/partnerCatalog'
import { partnerError } from '@/utils/partnerError'
import PartnerPhoto from '../PartnerPhoto.vue'
import CatalogSelectionDialog from './CatalogSelectionDialog.vue'
import SupplierStyleDetail from './SupplierStyleDetail.vue'
import CatalogSourceImport from './CatalogSourceImport.vue'
import CatalogIdentityDialog from './CatalogIdentityDialog.vue'
import CatalogModelSheetDialog from './CatalogModelSheetDialog.vue'
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
const renaming=ref(false)
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
  cancelSelectionLoad()
  const serial = ++generation
  loading.value = true; error.value = ''
  supplierQuery.value = String(route.query.supplierQ || '')
  keyword.value = String(route.query.keyword || '')
  detail.value = undefined
  try {
    if (!supplierCode.value) {
      styles.value = undefined
      const result = await catalogSuppliers({ q: supplierQuery.value.trim(), page: supplierPage.value, size: 24 })
      if (serial === generation) suppliers.value = result
    } else if (isDetail.value) {
      const result = await catalogStyle(supplierCode.value, { sourceKind: sourceKind.value as CatalogStyle['sourceKind'], recordId: Number(route.query.recordId), rowId: String(route.query.rowId) })
      if (serial === generation) detail.value = result
    } else {
      const result = await catalogStyles({ supplierCode: supplierCode.value, keyword: keyword.value.trim(), page: page.value, size: size.value })
      if (serial === generation) styles.value = result
    }
  } catch (cause) { if (serial === generation) error.value = partnerError(cause) }
  finally { if (serial === generation) loading.value = false }
}
watch(() => supplierCode.value, () => { checked.value = new Map(); selectionOpen.value = false; addition.value = undefined; styles.value = undefined })
watch(() => route.fullPath, () => { selectionOpen.value = false; modelSheetItem.value=undefined; modelSheetMessage.value=''; void load() }, { immediate: true })
watch(() => route.query.sourceManager, value => { if(value==='1')sourcesOpen.value=true }, {immediate:true})
onBeforeRouteUpdate(()=>{if(selectionBusy.value||renaming.value){ElMessage.warning('请先完成或取消当前填写，再切换页面。');return false}})
onBeforeUnmount(() => { generation++;selectionGeneration++ })
function navigate(query: LocationQueryRaw) { return router.push({ path: '/partner-products', query }) }
function selectionContext():LocationQueryRaw{return Object.fromEntries(['selectionId','targetSelectionId','targetVersion','selectionVersion','from'].filter(key=>route.query[key]!==undefined).map(key=>[key,route.query[key]]))}
function chooseSupplier(row: CatalogSupplier) { return navigate({ ...selectionContext(),supplierCode: row.supplierCode, ...(route.query.supplierQ ? { supplierQ: String(route.query.supplierQ) } : {}), supplierPage: supplierPage.value }) }
function chooseAnother() { return navigate({ ...selectionContext(),...(route.query.supplierQ ? { supplierQ: String(route.query.supplierQ) } : {}), supplierPage: supplierPage.value }) }
function searchSuppliers() { return navigate({ ...selectionContext(),supplierQ: supplierQuery.value.trim() || undefined, supplierPage: 1 }) }
function searchStyles() { return navigate({ ...route.query, keyword: keyword.value.trim() || undefined, catalogPage: 1 }) }
function changePage(value: number) { return navigate({ ...route.query, catalogPage: value }) }
function changeSize(value: number) { return navigate({ ...route.query, catalogSize: value, catalogPage: 1 }) }
function open(row: CatalogStyle) { return navigate({ ...route.query, sourceKind: row.sourceKind, recordId: row.recordId, rowId: row.rowId }) }
async function renameStyle(row:CatalogStyle){
  if(!canWrite.value||renaming.value)return
  renaming.value=true;error.value=''
  try{
    const current=await catalogStyle(supplierCode.value,{sourceKind:row.sourceKind,recordId:row.recordId,rowId:row.rowId})
    const result=await ElMessageBox.prompt('填写正确的款式名称。','修改款式名称',{inputValue:current.name,confirmButtonText:'保存名称',cancelButtonText:'取消',inputValidator:value=>Boolean(value?.trim())&&value.trim().length<=12000||'请填写款式名称（最多12000字）',closeOnClickModal:false})
    await renameCatalogStyle(supplierCode.value,current,{name:result.value.trim(),rowVersion:current.rowVersion,recordVersion:current.recordVersion,requestId:newRequestId()})
    ElMessage.success('款式名称已保存');await load()
  }catch(cause){if(cause!=='cancel'&&cause!=='close')error.value=partnerError(cause)}finally{renaming.value=false}
}
function back() { const { sourceKind: _source, recordId: _record, rowId: _row, ...query } = route.query; return navigate(query) }
function openProduct(id: number) { return navigate({ id, from: route.fullPath }) }
function openSource() { if (!detail.value) return; const row = detail.value; if(row.sourcePurpose==='catalog_source'){sourcesOpen.value=true;return} return router.push(row.sourceKind === 'selection' ? { path: '/partner-selections', query: { id: row.recordId, from: route.fullPath } } : { path: `/partner-operations/supplier_quote/${row.recordId}`, query: { from: route.fullPath } }) }
function check(row: CatalogStyle, value: boolean) { const next = new Map(checked.value); if (value) next.set(catalogKey(row), row); else next.delete(catalogKey(row)); checked.value = next }
function checkPage(value: boolean) { for (const row of rows.value) check(row, value) }
function cancelSelectionLoad(){selectionGeneration++;selectionLoading.value=false;selectionLoadError.value='';selectionLoaded.value=0;pendingSelection.value=[]}
async function add(candidates:CatalogStyle[]){
  if(!canWrite.value||loading.value||selectionLoading.value||!candidates.length)return
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
const photo = (row: CatalogStyle) => (row.fileDetails || []).find(file => file.type?.startsWith('image/'))
const photoCount = (row: CatalogStyle) => (row.fileDetails || []).filter(file => file.type?.startsWith('image/')).length
</script>

<template>
  <PageContainer :title="title" class="supplier-catalog-workspace">
    <template #actions>
      <el-button v-if="isDetail" @click="back">返回款式列表</el-button>
      <el-button v-else-if="supplierCode" @click="chooseAnother">更换供应商</el-button>
      <el-button v-if="canManageSources" @click="sourcesOpen=true">供应商资料／导入</el-button>
      <el-button v-if="isDetail&&detail&&canWrite" @click="identityItems=[detail]">款式号核对</el-button>
      <el-button @click="router.push('/partner-listings')">上架产品目录</el-button>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon><el-button link @click="load">重新加载</el-button><el-button v-if="supplierCode" link @click="chooseAnother">重新选择供应商</el-button></el-alert>
    <el-alert v-if="modelSheetMessage" :title="modelSheetMessage" type="success" :closable="false"/>
    <div v-if="selectionLoading" class="catalog-selection-loading" role="status"><span>正在读取所选款式的可选型号 {{selectionLoaded}} / {{pendingSelection.length}}</span><el-button link @click="cancelSelectionLoad">取消本次读取</el-button></div><el-alert v-else-if="selectionLoadError" :title="selectionLoadError" type="error" :closable="false" class="catalog-selection-load-error"><el-button link type="primary" @click="add(pendingSelection)">重试本次所选款式</el-button><el-button link @click="cancelSelectionLoad">保留勾选，暂不选样</el-button></el-alert>
    <div v-if="addition" class="catalog-addition" role="status"><span>已保存到选品目录：{{ addition.selection.name }}<template v-if="addition.reusedCount">（{{ addition.reusedCount }} 项沿用已有选择）</template></span><el-button link type="primary" @click="router.push({path:'/partner-selections',query:{id:addition.selection.id,from:route.fullPath}})">打开选品目录</el-button></div>
    <div v-if="!supplierCode" key="suppliers" class="catalog-branch">
      <form class="catalog-search supplier-search" @submit.prevent="searchSuppliers"><el-input v-model="supplierQuery" placeholder="搜索有款式的供应商" aria-label="搜索供应商" clearable/><el-button native-type="submit">查询</el-button></form>
      <div class="catalog-scroll supplier-scroll" :aria-busy="loading">
        <div v-if="suppliers?.content.length" class="supplier-choices"><button v-for="row in suppliers.content" :key="row.supplierCode" class="supplier-choice" type="button" :aria-label="`${row.supplierName}，${row.styleCount}款，查看款式`" @click="chooseSupplier(row)"><span class="supplier-monogram" aria-hidden="true">{{ row.shortCode || row.supplierName.slice(0,1) }}</span><span class="supplier-choice-info"><strong>{{ row.supplierName }}</strong><small v-if="row.status==='inactive'">资料已停用</small></span><span class="supplier-style-count">{{ row.styleCount }} 款<span aria-hidden="true">→</span></span></button></div>
        <el-empty v-else-if="!loading&&!error" description="没有找到有款式的供应商" :image-size="65"><el-button v-if="suppliers?.canWrite" @click="router.push({path:'/suppliers',query:{from:route.fullPath}})">维护供应商资料</el-button></el-empty>
        <p v-if="loading&&!suppliers" class="catalog-loading" role="status">正在加载供应商…</p>
      </div>
      <footer class="catalog-pager"><span>共 {{ suppliers?.totalElements || 0 }} 家有款式的供应商</span><el-pagination v-if="(suppliers?.totalElements||0)>24" :current-page="supplierPage" :page-size="24" :total="suppliers?.totalElements||0" :pager-count="5" layout="prev,pager,next" :disabled="loading" @current-change="navigate({...route.query,supplierPage:$event})"/></footer>
    </div>
    <div v-else-if="isDetail" key="detail" class="catalog-scroll detail-scroll" :aria-busy="loading"><SupplierStyleDetail v-if="detail" :row="detail" :can-read-cost="canReadCost" :can-edit="canWrite&&!renaming" @edit="renameStyle(detail)" @product="openProduct" @source="openSource" @model-sheet="modelSheetItem=detail"/><p v-else-if="loading" class="catalog-loading" role="status">正在加载本款资料…</p></div>
    <div v-else key="styles" class="catalog-branch">
      <form class="catalog-search" @submit.prevent="searchStyles"><el-input v-model="keyword" placeholder="搜索款式、货号、手机型号或编码" aria-label="搜索供应商款式" clearable/><el-button native-type="submit" :disabled="loading">查询</el-button></form>
      <div class="catalog-list-toolbar"><div><el-checkbox v-if="canWrite" :model-value="allPageChecked" :indeterminate="!allPageChecked&&rows.some(row=>checked.has(catalogKey(row)))" :disabled="loading||selectionLoading" @change="checkPage(Boolean($event))">勾选本页</el-checkbox><span v-if="canWrite">已选 {{ checked.size }} 款（跨页保留）</span><span v-else>{{ supplierName }}的款式</span><el-button v-if="checked.size" link @click="cancelSelectionLoad();checked=new Map()">取消勾选</el-button></div><div v-if="canWrite"><el-button :disabled="!checked.size||loading" @click="identityItems=[...checked.values()]">款式号／字段核对</el-button><el-button type="primary" :disabled="!checked.size||loading||selectionLoading" :loading="selectionLoading" @click="add([...checked.values()])">选择样品</el-button></div></div>
      <div class="catalog-scroll catalog-style-list" :class="{'without-cost':!canReadCost,'readonly-catalog':!canWrite}" :aria-busy="loading">
        <div class="catalog-style-head" aria-hidden="true"><span>图片</span><span v-if="canWrite" class="catalog-check-title">勾选</span><span>款式编号／名称</span><span>手机型号</span><span>颜色</span><span>材质</span><span v-if="canReadCost">报价</span><span v-if="canReadCost">币种</span><span>起订数量</span><span>起订口径</span><span>操作</span></div>
        <article v-for="row in rows" :key="catalogKey(row)" class="catalog-style-row" :data-style-key="catalogKey(row)">
          <div class="catalog-style-photo"><PartnerPhoto v-if="photo(row)" :id="photo(row)!.id"/><span v-else class="catalog-no-photo">暂无图片</span><button v-if="photo(row)" type="button" class="catalog-photo-open" :aria-label="`查看${row.name}的${photoCount(row)}张图片`" @click="open(row)">{{ photoCount(row) }}图</button></div>
          <div v-if="canWrite" class="catalog-style-check"><el-checkbox :model-value="checked.has(catalogKey(row))" :aria-label="`选择${row.name}`" :disabled="loading||selectionLoading" @change="check(row,Boolean($event))"/></div>
          <div class="catalog-style-name"><strong class="catalog-style-code">{{row.catalogStyleLabel||row.catalogStyleCode||'款式号待补'}}</strong><el-button link type="primary" @click="open(row)">{{ row.name || '未命名款式' }}</el-button><small v-if="row.supplierItemCode">供方款号：{{row.supplierItemCode}}</small><el-button v-if="row.productId" link @click="openProduct(row.productId)">{{ row.productCode || '关联我方产品' }}</el-button></div>
          <div class="catalog-style-model"><span class="catalog-cell-label">手机型号</span><strong>{{ catalogPhoneSummary(row) }}</strong><small v-if="row.phones?.length>2">{{ row.phoneMatchSummary?.codeGroupCount || row.phones.length }} 个型号编码组 · 详情查看</small><small v-else-if="row.phones?.length">{{ row.phones.map(phone=>phone.phoneModelCode).filter(Boolean).join(' / ') }}</small></div>
          <div class="catalog-style-config"><span class="catalog-cell-label">颜色</span><strong>{{ row.color || '未提供' }}</strong></div>
          <div class="catalog-style-material"><span class="catalog-cell-label">材质</span><span v-if="!row.material||row.material.length<=12">{{row.material||'未提供'}}</span><el-button v-else link type="primary" @click="open(row)">查看材质</el-button></div>
          <div v-if="canReadCost" class="catalog-style-price"><span class="catalog-cell-label">报价</span><strong>{{ supplierPriceValue(row) }}</strong></div>
          <div v-if="canReadCost" class="catalog-style-currency"><span class="catalog-cell-label">币种</span><span>{{supplierCurrency(row)}}</span><small v-if="row.currencySource==='business_default'">业务默认</small></div>
          
          <div class="catalog-style-moq"><span class="catalog-cell-label">起订数量</span><span>{{row.moqQuantity??'未确认'}}<small v-if="row.moqUnit"> {{row.moqUnit}}</small></span></div>
          
          <div class="catalog-style-moq-scope"><span class="catalog-cell-label">起订口径</span><span>{{moqScopeLabel(row.moqScope)}}</span></div>
          <div class="catalog-style-actions"><el-button link type="primary" @click="open(row)">详情</el-button><el-button v-if="canWrite" link type="primary" :disabled="renaming" :aria-label="'修改'+row.name+'名称'" @click="renameStyle(row)">改名</el-button></div>
        </article>
        <el-empty v-if="!loading&&!rows.length&&!error" :description="keyword?'该供应商暂无符合条件的款式':'该供应商还没有目录款式'" :image-size="65"><div v-if="canWrite" class="catalog-empty-actions"><p>先导入供应商资料，再选需要的款式和具体型号。</p><el-button @click="sourcesOpen=true">导入供应商资料</el-button><el-button @click="router.push({path:'/suppliers',query:{code:supplierCode,from:route.fullPath}})">维护型号与颜色范围</el-button></div></el-empty>
        <p v-if="loading&&!styles" class="catalog-loading" role="status">正在加载供应商款式…</p>
      </div>
      <footer class="catalog-pager"><span>共 {{ styles?.totalElements || 0 }} 款</span><el-pagination :current-page="page" :page-size="size" :page-sizes="[20,30,50,100]" :total="styles?.totalElements||0" :pager-count="5" layout="sizes,prev,pager,next" :disabled="loading" @current-change="changePage" @size-change="changeSize"/></footer>
    </div>
    <CatalogSelectionDialog v-model="selectionOpen" :supplier-code="supplierCode" :supplier-name="supplierName" :items="selectionItems" :can-write="canWrite" :target-selection-id="targetSelectionId" :target-version="targetVersion" @busy="selectionBusy=$event" @added="added"/>
    <CatalogSourceImport v-model="sourcesOpen" :supplier-code="supplierCode" :can-write="canManageSources" @changed="load"/>
    <CatalogIdentityDialog v-if="identityItems.length" :supplier-code="supplierCode" :items="identityItems" @close="identityItems=[]" @saved="identityItems=[];checked=new Map();load()"/>
    <CatalogModelSheetDialog v-if="modelSheetItem" :row="modelSheetItem" @close="modelSheetItem=undefined" @saved="modelSheetSaved"/>
  </PageContainer>
</template>

<style scoped>
.supplier-catalog-workspace{height:100%;min-height:0;min-width:0;display:flex;flex-direction:column;gap:7px;container-type:inline-size}
.supplier-catalog-workspace :deep(>.page-panel){padding:6px 10px;flex:none}
.supplier-catalog-workspace :deep(.page-panel-header){margin:0;flex-direction:row;align-items:center;flex-wrap:wrap;gap:6px 12px}
.supplier-catalog-workspace :deep(.page-panel-title){font-size:17px;line-height:1.5;margin:0;overflow-wrap:anywhere}
.supplier-catalog-workspace :deep(.toolbar-actions){gap:6px;flex-wrap:wrap}
.catalog-selection-loading{display:flex;align-items:center;gap:7px;flex-wrap:wrap;font-size:13px;line-height:1.6;flex:none}.catalog-selection-load-error{flex:none}
.catalog-branch{display:flex;flex-direction:column;gap:7px;flex:1;min-height:0;min-width:0}
.catalog-search,.catalog-list-toolbar,.catalog-list-toolbar>div,.catalog-pager{display:flex;gap:6px 10px;align-items:center;flex-wrap:wrap;flex:none;min-width:0}
.catalog-search .el-input{flex:1;min-width:0}.supplier-search .el-input{max-width:480px}
.catalog-scroll{flex:1;min-height:0;min-width:0;overflow-y:auto}
.supplier-choices{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;padding:1px}
.supplier-choice{display:flex;gap:10px;align-items:center;min-height:58px;min-width:0;box-sizing:border-box;border:1px solid var(--el-border-color-lighter);border-radius:6px;background:var(--el-bg-color);color:var(--el-text-color-primary);padding:10px;cursor:pointer;text-align:left}
.supplier-choice:hover,.supplier-choice:focus-visible{border-color:var(--el-color-primary);background:var(--el-color-primary-light-9)}
.supplier-monogram{display:grid;place-items:center;flex:none;width:36px;height:36px;border-radius:6px;background:var(--el-color-primary-light-9);color:var(--el-color-primary);font-size:15px;font-weight:700;overflow-wrap:anywhere}
.supplier-choice-info{display:flex;flex:1;min-width:0;flex-direction:column;gap:3px}.supplier-choice-info strong{font-size:15px;overflow-wrap:anywhere}.supplier-choice-info small{font-size:12px;color:var(--el-text-color-secondary)}
.supplier-style-count{display:flex;align-items:center;gap:8px;font-size:14px;flex:none;white-space:nowrap}.supplier-style-count>span{font-size:16px;color:var(--el-color-primary)}
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
.catalog-photo-open{position:absolute;right:4px;top:4px;border:1px solid var(--el-color-primary-light-7);border-radius:3px;background:var(--el-bg-color);color:var(--el-color-primary);padding:0 3px;font-size:11px;line-height:16px;cursor:pointer}
.catalog-no-photo{font-size:11px;color:var(--el-text-color-secondary);background:var(--el-fill-color-light);width:44px;height:44px;display:grid;place-items:center;border-radius:5px}
.catalog-style-check{display:grid;place-items:center;padding:3px!important}.catalog-style-check .el-checkbox{margin:0}
.catalog-style-name>.el-button{font-size:14px;font-weight:600;justify-content:flex-start}.catalog-style-code{font-size:12px;color:var(--el-color-primary);font-weight:500}
.catalog-style-row small{font-size:12px;color:var(--el-text-color-secondary);line-height:1.5}.catalog-style-model strong{font-size:15px;font-weight:600;color:#284d7c}
.catalog-style-actions{display:flex;align-items:center;align-content:center;flex-wrap:nowrap;gap:3px 8px}.catalog-style-actions :deep(.el-button){flex:0 0 auto;font-size:13px;padding:0}
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
 .supplier-choices{grid-template-columns:repeat(2,minmax(0,1fr))}
}
@container(max-width:480px){
 .catalog-style-row,.without-cost .catalog-style-row,.readonly-catalog .catalog-style-row,.readonly-catalog.without-cost .catalog-style-row{grid-template-columns:44px 22px repeat(4,minmax(0,1fr));grid-template-rows:auto auto auto auto}
 .catalog-style-name{grid-column:3/-1;grid-row:1}.catalog-style-model{grid-column:3/-1;grid-row:2}
 .catalog-style-row>.catalog-style-config{grid-column:3;grid-row:3}.catalog-style-row>.catalog-style-price{grid-column:4;grid-row:3}.catalog-style-row>.catalog-style-currency{grid-column:5;grid-row:3}.catalog-style-row>.catalog-style-moq{grid-column:6;grid-row:3}
 .catalog-style-actions{grid-column:3/-1;grid-row:4;flex-direction:row;gap:8px}
 .supplier-choices{grid-template-columns:minmax(0,1fr)}.catalog-pager :deep(.el-pagination){max-width:100%;flex-wrap:wrap}
}
</style>
