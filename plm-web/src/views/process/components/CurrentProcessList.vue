<script setup lang="ts">
import { ArrowRight, Refresh, Search } from '@element-plus/icons-vue'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCurrentProcesses, getCurrentProcessGraph, type MesOperation, type MesRoute } from '@/api/modules/process'
import { useCachedRequest } from '@/composables/useCachedRequest'
import { t } from '@/locales'
import ProjectProcessRoutePanel from '@/views/project/components/ProjectProcessRoutePanel.vue'
import ProcessDefinitionDialog from './ProcessDefinitionDialog.vue'
import ProcessGraphView from './ProcessGraphView.vue'
import { matchesProcess, operationCategory, operationName, processValue, routeName, routeProductKey, routeProductName } from '../processPresentation'

const emit = defineEmits<{history: []}>()
const location = useRoute(), router = useRouter()
const catalog = useCachedRequest(getCurrentProcesses)
const graph = useCachedRequest(getCurrentProcessGraph)
const tab = computed(() => location.query.processTab === 'operations' ? 'operations' : 'routes')
const searches = reactive({products:'',routes:'',operations:''})
const operations = computed(() => catalog.data.value?.operations || [])
const routes = computed(() => catalog.data.value?.routes || [])
const products = computed(() => {
  const groups = new Map<string,MesRoute[]>()
  for (const route of routes.value) {
    const key = routeProductKey(route)
    if (!groups.has(key)) groups.set(key,[])
    groups.get(key)!.push(route)
  }
  return [...groups].sort((a,b) => b[1].length-a[1].length || routeProductName(a[1][0]).localeCompare(routeProductName(b[1][0])))
})
const product = computed(() => products.value.find(([key]) => key === location.query.processProduct))
const active = computed(() => tab.value === 'routes' && product.value?.[1].find(r => String(r.objectId) === location.query.processRoute) || undefined)
const references = computed(() => {
  const result = new Map<number,MesRoute[]>()
  for (const route of routes.value) {
    for (const id of new Set(route.operationIds)) {
      if (id == null) continue
      if (!result.has(id)) result.set(id,[])
      result.get(id)!.push(route)
    }
  }
  return result
})
const definitionId = ref<number>(), editing = ref(false)
const selectedOp = computed(() => operations.value.find(op => op.objectId === definitionId.value))
const currentList = computed(() => tab.value === 'operations' ? 'operations' : product.value ? 'routes' : 'products')
const search = computed({get:() => searches[currentList.value],set:(v:string) => {searches[currentList.value]=v}})
const titles = computed(() => currentList.value === 'operations' ? ['工序名称','类别','引用路线'] : product.value ? ['路线名称','MES 版本','工序数'] : ['产品','产品基码','路线数'])
const placeholder = computed(() => t(currentList.value === 'operations' ? '搜索工序' : product.value ? '搜索路线' : '搜索产品或路线'))
function routeMatches(route: MesRoute, q: string) {
  return matchesProcess(q,[routeName(route),route.source.route_name,route.source.route_name_es,route.sourceCode,route.source.version])
}
function productMatches(route: MesRoute, q: string) {
  return matchesProcess(q,[routeProductName(route),route.seriesName,route.seriesNameEs,route.source.product_code])
}
const rows = computed(() => {
  if (currentList.value === 'operations') return operations.value.filter(op => matchesProcess(search.value,
    [operationName(op),op.source.operation_name,op.source.operation_name_es,op.sourceCode,op.source.category,op.source.category_es])).map(op =>
    ({key:String(op.objectId),name:operationName(op),secondary:operationCategory(op),count:references.value.get(op.objectId)?.length || 0}))
  if (product.value) return product.value[1].filter(r => routeMatches(r,search.value)).map(r =>
    ({key:String(r.objectId),name:routeName(r),secondary:r.source.version || '—',count:r.operationIds.length}))
  return products.value.filter(([,items]) => productMatches(items[0],search.value) || items.some(r => routeMatches(r,search.value))).map(([key,items]) =>
    ({key,name:routeProductName(items[0]),secondary:items[0].source.product_code || '—',count:items.length}))
})
const listArea = ref<HTMLElement>(), routeTitle = ref<HTMLElement>()
async function navigate(fields: Record<string,string|undefined>,focusKey?:string) {
  definitionId.value=undefined; editing.value=false
  await router.push({query:{...location.query,...fields}})
  await nextTick()
  if (active.value) routeTitle.value?.focus({preventScroll:true})
  else {
    const buttons = Array.from(listArea.value?.querySelectorAll<HTMLButtonElement>('.row-name') || [])
    const target = buttons.find(b=>b.dataset.key===focusKey) || buttons[0]
    target?.focus({preventScroll:true})
  }
}
function openRoute(route: MesRoute) {
  return navigate({processTab:undefined,processProduct:routeProductKey(route),processRoute:String(route.objectId)})
}
function openReference(route: MesRoute) {
  searches.routes=''
  if (!productMatches(route,searches.products) && !routeMatches(route,searches.products)) searches.products=''
  return openRoute(route)
}
function openRow(key:string) {
  if (currentList.value === 'operations') { definitionId.value=Number(key); return }
  if (product.value) {
    const route=product.value[1].find(r=>String(r.objectId)===key)
    if (route) void openRoute(route)
  } else {
    const item=products.value.find(([id])=>id===key)
    if (!item) return
    searches.routes=productMatches(item[1][0],searches.products) ? '' : searches.products
    void navigate({processProduct:key,processRoute:undefined})
  }
}
function changeTab(value:string|number|boolean|undefined) {
  if (value!==tab.value) void navigate({processTab:value==='operations'?'operations':undefined})
}
function openDefinition(op:MesOperation) { definitionId.value=op.objectId }
watch([() => location.query.processTab,() => location.query.processProduct,() => location.query.processRoute],() => { definitionId.value=undefined })
async function refresh() {
  const previous=active.value?.objectId
  await catalog.execute(undefined,true)
  if (previous && active.value?.objectId===previous && !catalog.error.value) await graph.execute(previous,true)
}
watch(() => active.value?.objectId,id => {
  editing.value=false
  if (id) void graph.execute(id)
  else graph.invalidate()
},{immediate:true})
onMounted(() => catalog.execute(undefined))
</script>

<template>
  <section class="process-workspace" :aria-label="t('工艺管理')">
    <header class="tools">
      <el-radio-group :model-value="tab" size="small" :aria-label="t('工艺管理')" @update:model-value="changeTab">
        <el-radio-button value="routes">{{t('工艺路线')}}</el-radio-button><el-radio-button value="operations">{{t('工序定义')}}</el-radio-button>
      </el-radio-group>
      <div class="controls">
        <el-input v-if="!active" v-model="search" clearable :prefix-icon="Search" :placeholder="placeholder" :aria-label="placeholder"/>
        <el-button size="small" @click="emit('history')">{{t('历史资料')}}</el-button>
        <el-button size="small" :icon="Refresh" :title="t('刷新')" :aria-label="t('刷新')" @click="refresh"/>
      </div>
    </header>
    <el-alert v-if="catalog.error.value" type="error" :title="t(catalog.error.value)" :closable="false"/>
    <div v-else v-loading="catalog.loading.value" class="body">
      <nav v-if="product && tab==='routes'" :aria-label="t('当前位置')" class="path"><button type="button" @click="navigate({processProduct:undefined,processRoute:undefined},product[0])">{{t('产品')}}</button><el-icon aria-hidden="true"><ArrowRight/></el-icon>
        <button v-if="active" type="button" @click="navigate({processRoute:undefined},String(active.objectId))">{{routeProductName(product[1][0])}}</button><strong v-else>{{routeProductName(product[1][0])}}</strong>
      </nav>
      <article v-if="active" class="route-panel">
        <header class="route-head"><h2 ref="routeTitle" tabindex="-1">{{routeName(active)}}</h2><small v-if="active.source.version">MES {{active.source.version}}</small></header>
        <div class="diagram" v-loading="graph.loading.value">
          <el-alert v-if="graph.error.value" type="error" :title="t(graph.error.value)" :closable="false"/>
          <ProcessGraphView v-else-if="graph.hasCurrentData.value && graph.data.value" :key="active.objectId" :graph="graph.data.value" :operations="operations" @definition="openDefinition"/>
        </div>
        <details :key="active.objectId" class="route-meta"><summary>{{t('路线资料')}}</summary>
          <dl><dt>{{t('路线编码')}}</dt><dd><code>{{active.sourceCode}}</code></dd><dt>{{t('MES 状态')}}</dt><dd>{{processValue(active.source.status)}}</dd>
            <dt>{{t('PLM 路线')}}</dt><dd>{{processValue(active.routeStatus)}}</dd><template v-if="active.source.updated_at"><dt>{{t('更新日期')}}</dt><dd>{{active.source.updated_at.slice(0,10)}}</dd></template></dl>
          <el-button v-if="active.productId" size="small" @click="editing=true">{{t('维护版本')}}</el-button>
        </details>
      </article>
      <div v-else ref="listArea" class="table-box">
        <table><colgroup><col><col class="second"><col class="num"><col class="arrow"></colgroup>
          <thead><tr><th v-for="(label,i) in titles" :key="label" scope="col" :class="{numeric:i===2}">{{t(label)}}</th><th aria-hidden="true"/></tr></thead>
          <tbody><tr v-for="row in rows" :key="row.key" @click="openRow(row.key)"><td><button type="button" class="row-name" :data-key="row.key" @click.stop="openRow(row.key)">{{row.name}}</button></td>
            <td><code v-if="currentList==='products'">{{row.secondary}}</code><template v-else>{{row.secondary}}</template></td><td class="numeric">{{row.count}}</td><td class="chevron" aria-hidden="true"><el-icon><ArrowRight/></el-icon></td>
          </tr></tbody>
        </table>
        <el-empty v-if="!catalog.loading.value && !rows.length" :description="t(search?'没有匹配结果':'暂无资料')" :image-size="45">
          <el-button v-if="search" link @click="search=''">{{t('清空搜索')}}</el-button>
        </el-empty>
      </div>
    </div>
    <ProcessDefinitionDialog v-if="selectedOp" :operation="selectedOp" :routes="references.get(selectedOp.objectId) || []" @close="definitionId=undefined" @route="openReference"/>
    <el-drawer v-model="editing" :title="t('工艺版本维护')" size="min(1040px,98vw)" destroy-on-close append-to-body>
      <ProjectProcessRoutePanel v-if="active?.productId && editing" :project-id="active.productId" :initial-process-id="active.objectId"/>
    </el-drawer>
  </section>
</template>
<style scoped>
.process-workspace{min-width:0;min-height:0;display:flex;flex-direction:column;height:100%;gap:12px;container-type:inline-size;color:var(--el-text-color-primary)}
.tools,.controls,.path,.route-head{display:flex;align-items:center;gap:10px}.tools{justify-content:space-between;flex-wrap:wrap;border-bottom:1px solid var(--el-border-color-light);padding-bottom:10px}
.controls{gap:7px}.controls .el-input{width:230px;max-width:100%}.controls .el-button+.el-button{margin:0}.body{overflow:auto;min-height:90px}
.path{margin-bottom:10px;font-size:13px}.path button,.row-name{border:0;background:transparent;font:inherit;text-align:start;color:var(--el-color-primary);padding:3px 0;cursor:pointer}.path .el-icon,.chevron,small,code,dt{color:var(--el-text-color-secondary)}
.table-box,.route-panel{background:var(--el-bg-color);border:1px solid var(--el-border-color-light);border-radius:6px;overflow:clip}table{border-spacing:0;table-layout:fixed;width:100%}
th,td{padding:11px 15px;text-align:start;border-bottom:1px solid var(--el-border-color-lighter);border-right:1px solid var(--el-border-color-lighter);overflow-wrap:anywhere}th{background:var(--el-fill-color-light);font-size:12px;font-weight:500;color:var(--el-text-color-secondary)}td:last-child,th:last-child{border-right:0}tr:last-child td{border-bottom:0}
.second{width:25%}.num{width:16%}.arrow{width:35px}.numeric{text-align:end;font-variant-numeric:tabular-nums}.chevron{text-align:center;padding:4px}.row-name{color:var(--el-text-color-primary);font-weight:600}tbody tr{cursor:pointer}tbody tr:is(:hover,:focus-within){background:var(--el-color-primary-light-9)}tbody tr:is(:hover,:focus-within) .row-name{color:var(--el-color-primary)}
.route-head,.diagram,.route-meta{padding:12px 16px}.route-head{align-items:baseline;flex-wrap:wrap;border-bottom:1px solid var(--el-border-color-lighter)}h2{font-size:18px;margin:0}.diagram{min-height:60px}.route-meta{border-top:1px solid var(--el-border-color-lighter);font-size:13px}summary{cursor:pointer;width:fit-content}dl{display:grid;grid-template-columns:auto minmax(0,1fr);gap:6px 16px}dd{margin:0;overflow-wrap:anywhere}
@container(max-width:590px){.controls{flex:1 1 100%;flex-wrap:wrap}.controls .el-input{flex:1;min-width:120px}.arrow{width:22px}.num{width:19%}.second{width:26%}th,td{padding:8px}.route-head,.diagram,.route-meta{padding:10px}}
@media(pointer:coarse){.row-name,.path button,summary,.controls .el-button{min-height:44px}}
</style>
