<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { ArrowRight, Filter, MoreFilled, Search } from '@element-plus/icons-vue'
import { t } from '@/locales'
import { getCurrentBomProducts, getCurrentBomGroups, getCurrentCatalogOptions, type BomCatalogColor, type BomCatalogGroup, type BomCatalogProduct, type CatalogOptions } from '@/api/modules/masterData'
import { useCachedRequest } from '@/composables/useCachedRequest'
import { productColorSwatches } from '@/utils/productColorSwatches'
import { colorLabel, productLabel, stateLabel, preferredRecipe } from '../catalogPresentation'
import BomCatalogDrawer from './BomCatalogDrawer.vue'

defineProps<{administrator?:boolean}>()
const emit=defineEmits<{history:[];align:[]}>()
const route=useRoute(),router=useRouter()
const catalogPath=route.path
function param(name:string) {const value=route.query[name];return typeof value==='string'?value:''}
const productKey=computed(()=>param('bomProduct')),itemId=computed(()=>param('bomItem'))
const query=computed(()=>({
  page:Math.max(1,Math.min(100000,Math.floor(Number(param('bomPage')))||1)),size:25,keyword:param('bomSearch'),
  seriesCode:param('bomSeries'),brandId:param('bomBrand'),listingStatus:param('bomListing'),productionStatus:param('bomProduction'),
}))
const products=useCachedRequest(getCurrentBomProducts),models=useCachedRequest(getCurrentBomGroups)
const request=computed(()=>productKey.value?models:products)
const productRows=computed(()=>products.hasCurrentData.value&&!products.error.value?products.data.value?.content??[]:[])
const groups=computed(()=>models.hasCurrentData.value&&!models.error.value?models.data.value?.content??[]:[])
const modelRows=computed(()=>groups.value.map(group=>({group,visible:group.colors.filter(c=>c.matched!==false)})))
const chosen=computed(()=>{
  if(route.path!==catalogPath||!productKey.value||!itemId.value)return
  for(const group of groups.value){const row=group.colors.find(c=>c.sourceId===itemId.value);if(row)return {row,group}}
})
const product=computed(()=>productRows.value.find(p=>p.key===productKey.value)??groups.value[0]?.colors[0])
const total=computed(()=>request.value.data.value?.totalElements??0)
const filterCount=computed(()=>[query.value.seriesCode,query.value.brandId,query.value.listingStatus,query.value.productionStatus].filter(Boolean).length)
const search=ref(query.value.keyword),moreFilters=ref(false),options=ref<CatalogOptions>(),filterError=ref(''),listArea=ref<HTMLElement>()
let timer:ReturnType<typeof setTimeout>|undefined,epoch=0,productScroll=0,enteredProduct=false,enteredItem=false
let lastProduct=productKey.value
function navigate(delta:LocationQueryRaw,replace=false){
  clearTimeout(timer)
  const next={...route.query,...delta}
  for(const key of Object.keys(next)) if(next[key]===undefined||next[key]===null||next[key]==='')delete next[key]
  return replace?router.replace({query:next}):router.push({query:next})
}
async function load(force=false){
  const id=++epoch, key=productKey.value, q=query.value, active=request.value
  const scroll=lastProduct&&!key?productScroll:0
  lastProduct=key
  if(key)await models.execute({...q,productKey:key},force)
  else await products.execute(q,force)
  if(id!==epoch||active.error.value)return
  const last=active.data.value?.totalPages??0
  if(q.page>1&&q.page>last){void navigate({bomPage:Math.max(1,last)},true);return}
  if(!force){await nextTick();if(id===epoch&&listArea.value)listArea.value.scrollTop=scroll}
}
watch(()=>route.path===catalogPath?JSON.stringify([productKey.value,query.value]):'',active=>{
  clearTimeout(timer)
  if(!active){epoch++;return}
  search.value=query.value.keyword;void load()
},{immediate:true})
onUnmounted(()=>{epoch++;clearTimeout(timer)})
function refresh(){products.clear();models.clear();return load(true)}
defineExpose({refresh})
function runSearch(){void navigate({bomSearch:search.value.trim(),bomPage:undefined,bomItem:undefined},true)}
function typing(){clearTimeout(timer);timer=setTimeout(runSearch,250)}
function filter(key:string,value:string){void navigate({[key]:value,bomSearch:search.value.trim(),bomPage:undefined,bomItem:undefined},true)}
async function filterOptions(){
  if(options.value)return
  filterError.value=''
  try{options.value=await getCurrentCatalogOptions()}catch{filterError.value=t('筛选选项读取失败')}
}
function toggleFilters(){moreFilters.value=!moreFilters.value;if(moreFilters.value)void filterOptions()}
function enterProduct(p:BomCatalogProduct){
  productScroll=listArea.value?.scrollTop??0;enteredProduct=true
  void navigate({bomProduct:p.key,bomPage:undefined,bomItem:undefined})
}
function allProducts(){
  if(enteredProduct){router.back();return}
  void navigate({bomProduct:undefined,bomPage:undefined,bomItem:undefined},true)
}
function open(row:BomCatalogColor){enteredItem=true;void navigate({bomItem:row.sourceId})}
function closeItem(){if(enteredItem){router.back();return}void navigate({bomItem:undefined},true)}
function openModel(rows:BomCatalogColor[]){const row=rows.find(c=>c.recipes.length)||rows[0];if(row)open(row)}
function chipTitle(row:BomCatalogColor){
  const recipe=preferredRecipe(row.recipes)
  return [colorLabel(row),row.itemCode,recipe?.version,recipe&&stateLabel(recipe.state)].filter(Boolean).join(' · ')
}
function chipName(group:BomCatalogGroup,row:BomCatalogColor){
  const label=colorLabel(row)
  return group.colors.filter(c=>colorLabel(c)===label).length>1?label+' · '+row.itemCode:label
}
</script>
<template>
  <section class="bom-workspace" :aria-label="t('BOM 管理')">
    <header class="bom-tools">
      <nav v-if="productKey" class="path" :aria-label="t('BOM 管理')"><button type="button" @click="allProducts">{{t('产品')}}</button><el-icon><ArrowRight/></el-icon><h2>{{product?productLabel(product):t('型号')}}</h2></nav>
      <h2 v-else>{{t('BOM 管理')}}</h2>
      <div class="search-tools"><el-input v-model="search" :prefix-icon="Search" clearable maxlength="128" :placeholder="t('搜索款式、型号或品目编码')" :aria-label="t('搜索款式、型号或品目编码')" @input="typing" @keyup.enter="runSearch" @clear="runSearch"/>
        <el-button :icon="Filter" :type="moreFilters||filterCount?'primary':'default'" plain :aria-expanded="moreFilters" :title="t('筛选')" :aria-label="t('筛选')" @click="toggleFilters"/>
        <el-dropdown trigger="click"><el-button :icon="MoreFilled" :title="t('更多操作')" :aria-label="t('更多操作')"/><template #dropdown><el-dropdown-menu>
          <el-dropdown-item @click="refresh">{{t('刷新')}}</el-dropdown-item><el-dropdown-item @click="emit('history')">{{t('历史资料')}}</el-dropdown-item>
          <el-dropdown-item v-if="administrator" @click="emit('align')">{{t('本地来源对齐')}}</el-dropdown-item>
        </el-dropdown-menu></template></el-dropdown>
      </div>
    </header>
    <div v-if="moreFilters" class="bom-filters">
      <el-select v-if="!productKey" :model-value="query.seriesCode" clearable :placeholder="t('产品系列')" :aria-label="t('产品系列')" @change="filter('bomSeries',$event)"><el-option v-for="s in options?.series" :key="s.code" :label="s.name||s.code" :value="s.code"/></el-select>
      <el-select :model-value="query.brandId" clearable filterable :placeholder="t('品牌')" :aria-label="t('品牌')" @change="filter('bomBrand',$event)"><el-option v-for="b in options?.brands" :key="b.id" :label="b.name" :value="b.id"/></el-select>
      <el-select :model-value="query.listingStatus" clearable :placeholder="t('上架状态')" :aria-label="t('上架状态')" @change="filter('bomListing',$event)"><el-option value="listed" :label="t('已上架')"/><el-option value="unlisted" :label="t('暂未上架')"/></el-select>
      <el-select :model-value="query.productionStatus" clearable :placeholder="t('生产状态')" :aria-label="t('生产状态')" @change="filter('bomProduction',$event)"><el-option v-for="(name,key) in {unknown:'待确认',active:'在用',discontinued:'已停产'}" :key="key" :value="key" :label="t(name)"/></el-select>
      <el-button v-if="filterError" @click="filterOptions">{{filterError}} · {{t('重试')}}</el-button>
    </div>
    <el-alert v-if="request.error.value" :title="t(request.error.value)" type="error" :closable="false"><el-button link @click="refresh">{{t('重试')}}</el-button></el-alert>
    <div ref="listArea" v-loading="request.loading.value" class="catalog-list">
      <table v-if="!productKey" class="product-list" :aria-label="t('产品')">
        <colgroup><col><col class="product-code"><col class="count"><col class="arrow"></colgroup>
        <thead><tr><th>{{t('产品')}}</th><th>{{t('产品编码')}}</th><th class="numeric">{{t('型号数')}}</th><th></th></tr></thead>
        <tbody><tr v-for="p in productRows" :key="p.key" @click="enterProduct(p)">
          <td><button class="row-link" type="button" @click.stop="enterProduct(p)">{{productLabel(p)}}</button></td><td><code>{{p.seriesCode}}</code></td>
          <td class="numeric">{{p.modelCount}}</td><td class="end"><el-icon><ArrowRight/></el-icon></td>
        </tr></tbody>
      </table>
      <table v-else class="model-list" :aria-label="t('型号')">
        <colgroup><col class="model"><col><col class="arrow"></colgroup>
        <thead><tr><th>{{t('型号')}}</th><th>{{t('颜色')}}</th><th></th></tr></thead>
        <tbody><tr v-for="{group,visible} in modelRows" :key="group.key" @click="openModel(visible)">
          <td><button class="row-link" type="button" @click.stop="openModel(visible)">{{group.colors[0].modelName || t('型号待核对')}}</button><small>{{[group.colors[0].modelCode,group.colors[0].commercialVersion].filter(Boolean).join(' · ')}}</small></td>
          <td><div class="chips"><button v-for="row in visible.slice(0,6)" :key="row.sourceId" type="button" class="chip" :title="chipTitle(row)" @click.stop="open(row)">
            <i v-if="productColorSwatches[row.colorNameZh]||row.colorNameZh==='透明'" :class="{clear:row.colorNameZh==='透明'}" :style="{backgroundColor:productColorSwatches[row.colorNameZh]}" aria-hidden="true"></i>{{chipName(group,row)}}
          </button>
          <el-popover v-if="visible.length>6" trigger="click" placement="bottom-start" :width="240">
            <template #reference><button class="chip more" type="button" :aria-label="t('更多颜色')" @click.stop>+{{visible.length-6}}</button></template>
            <div class="extra-colors"><button v-for="row in visible.slice(6)" :key="row.sourceId" class="chip" type="button" :title="chipTitle(row)" @click="open(row)">{{chipName(group,row)}}</button></div>
          </el-popover></div></td>
          <td class="end"><el-icon><ArrowRight/></el-icon></td>
        </tr></tbody>
      </table>
      <el-empty v-if="!request.loading.value&&!request.error.value&&!(productKey?modelRows.length:productRows.length)" :description="productKey?t('没有匹配的型号'):t('没有匹配的产品')" :image-size="48"/>
    </div>
    <footer><span>{{productKey?t('{n} 个型号',{n:total}):t('{n} 个产品',{n:total})}}</span><el-pagination v-if="total>query.size" :current-page="query.page" :page-size="query.size" :total="total" layout="prev, pager, next" :pager-count="5" @current-change="navigate({bomPage:$event,bomItem:undefined},true)"/></footer>
    <BomCatalogDrawer v-if="chosen" :initial="chosen.row" :colors="chosen.group.colors" @close="closeItem" @color="navigate({bomItem:$event},true)" @changed="refresh"/>
  </section>
</template>
<style scoped>
.bom-workspace{display:flex;flex-direction:column;gap:10px;min-height:0;height:100%;container-type:inline-size;color:var(--el-text-color-primary)}
.bom-tools,.search-tools,.bom-filters,.path,footer{display:flex;align-items:center;gap:8px}.bom-tools,footer{justify-content:space-between}.bom-tools h2{margin:0;font-size:19px}.path button,.row-link{border:0;background:none;font:inherit;text-align:left;padding:0;color:var(--el-color-primary);cursor:pointer}.row-link{color:var(--el-text-color-primary);font-weight:600}.row-link:hover,.row-link:focus-visible{color:var(--el-color-primary)}.path .el-icon,.end{color:var(--el-text-color-secondary)}.path .el-icon{font-size:12px}.search-tools .el-input{width:260px;max-width:100%}.search-tools .el-button{margin:0}.bom-filters{flex-wrap:wrap}.bom-filters .el-select{width:clamp(124px,16%,198px)}
.catalog-list{flex:1;min-height:60px;overflow:auto;border:1px solid var(--el-border-color-light);border-radius:6px;background:var(--el-bg-color)}
table{width:100%;border-collapse:separate;border-spacing:0;table-layout:fixed;font-size:14px}th,td{padding:11px 16px;text-align:left;overflow-wrap:anywhere;border-right:1px solid var(--el-border-color-lighter);border-bottom:1px solid var(--el-border-color-lighter)}th:last-child,td:last-child{border-right:0}.end{border-left:0;text-align:center;padding:6px}.product-code{width:27%}.count{width:17%}.arrow{width:36px}.numeric{text-align:right;font-variant-numeric:tabular-nums}.model{width:41%}thead th{position:sticky;top:0;z-index:1;font-size:12px;font-weight:500;color:var(--el-text-color-secondary);background:var(--el-fill-color-light)}tbody tr{cursor:pointer}tbody tr:hover,tbody tr:focus-within{background:var(--el-color-primary-light-9)}code,small,footer{font-size:12px;color:var(--el-text-color-secondary)}small{display:block;margin-top:3px}
.chips,.extra-colors{display:flex;align-items:center;flex-wrap:wrap;gap:4px 6px}.extra-colors{max-height:240px;overflow:auto}.chip{display:inline-flex;align-items:center;gap:4px;border:1px solid var(--el-border-color-light);border-radius:4px;background:var(--el-bg-color);padding:3px 7px;font:inherit;font-size:12px;color:var(--el-text-color-regular);cursor:pointer}.chip:hover,.chip:focus-visible{color:var(--el-color-primary);border-color:var(--el-color-primary-light-5)}.chip i{width:10px;height:10px;border-radius:50%;box-shadow:inset 0 0 0 1px var(--el-border-color)}.clear{background:repeating-conic-gradient(var(--el-fill-color) 0% 25%,var(--el-bg-color) 0% 50%) 0 0/6px 6px}
@container(max-width:570px){.bom-tools{align-items:stretch;flex-direction:column}.search-tools .el-input{width:auto;flex:1}.arrow{width:23px}th,td{padding:8px}.product-code{width:27%}.count{width:20%}.bom-filters .el-select{width:calc(50% - 4px)}.chip{padding:3px 5px}.chips{gap:4px}.model{width:44%}}
</style>
<style>
.bom-state{display:inline-flex;align-items:center;gap:5px;font-size:12px}.bom-state::before{content:'';display:inline-block;width:7px;height:7px;border-radius:50%;background:var(--el-text-color-secondary);flex:none}.bom-state[data-state=pending]::before{background:var(--el-color-warning)}.bom-state[data-state=released]::before{background:var(--el-color-success)}.bom-state[data-state=conflict]::before{background:var(--el-color-danger)}
</style>
