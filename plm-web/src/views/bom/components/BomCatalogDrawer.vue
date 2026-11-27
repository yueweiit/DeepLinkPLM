<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { t } from '@/locales'
import { useRouter } from 'vue-router'
import { getCurrentSku, type BomCatalogColor, type CurrentSkuDetail } from '@/api/modules/masterData'
import { useProductionMetadataStore } from '@/stores/productionMetadata'
import { useUserStore } from '@/stores/user'
import { productColorSwatches } from '@/utils/productColorSwatches'
import ProjectBomPanel from '@/views/project/components/ProjectBomPanel.vue'
import ProductProductionControl from '@/components/ProductionMetadata/ProductProductionControl.vue'
import BomProcessMaterials from './BomProcessMaterials.vue'
import BomEngineeringConfirm from './BomEngineeringConfirm.vue'
import { colorLabel, materialLabel, preferredRecipe, productLabel, stateLabel, versionLabel } from '../catalogPresentation'

const props=defineProps<{colors:BomCatalogColor[];initial:BomCatalogColor}>()
const emit=defineEmits<{close:[];changed:[];color:[id:string]}>()
const row=ref(props.initial),detail=ref<CurrentSkuDetail>(),routeId=ref<number>(),loading=ref(false),error=ref('')
const confirming=ref(false),editing=ref(false),edited=ref(false),scrollArea=ref<HTMLElement>()
const user=useUserStore(),permissions=useProductionMetadataStore(),router=useRouter()
const recipe=computed(()=>detail.value?.manufacturing.find(r=>r.bomRouteId===routeId.value))
const available=computed(()=>detail.value?.manufacturing ?? [])
const canConfirm=computed(()=>permissions.canMaintain && user.hasPermission('bom:publish') && recipe.value?.engineeringState==='pending' && !!recipe.value.items.length)
const versions=computed(()=>available.value.map(r=>{
  let label='BOM '+versionLabel(r)
  if(available.value.filter(v=>versionLabel(v)===versionLabel(r)).length>1) label+=' · '+materialLabel(r.routeName)+' · '+r.internalBomCode
  return {id:r.bomRouteId,label}
}))
const scopedColors=computed(()=>props.colors.some(c=>c.sourceId===row.value.sourceId)?props.colors:[row.value])
let generation=0
async function open(color:BomCatalogColor,keepRoute?:number) {
  row.value=color;detail.value=undefined;routeId.value=undefined;error.value='';loading.value=true;confirming.value=false
  if(scrollArea.value) scrollArea.value.scrollTop=0
  const ticket=++generation
  try {
    const result=await getCurrentSku(color.sourceId,true)
    if(ticket!==generation) return
    const recipes=result.manufacturing
    const wanted=recipes.find(r=>r.bomRouteId===keepRoute)
    const choice=preferredRecipe(recipes.map(r=>({id:r.bomRouteId,state:r.engineeringState || 'history'})))
    routeId.value=wanted?.bomRouteId ?? choice?.id
    detail.value=result
  } catch(e) { if(ticket===generation)error.value=e instanceof Error?e.message:t('读取失败') }
  finally { if(ticket===generation)loading.value=false }
}
function switchColor(id:string){if(scopedColors.value.some(c=>c.sourceId===id))emit('color',id)}
watch(()=>props.initial.sourceId,()=>void open(props.initial))
function changeVersion(id:number){routeId.value=id;if(scrollArea.value)scrollArea.value.scrollTop=0}
function changed() {confirming.value=false;emit('changed');void open(row.value,routeId.value)}
function archive() {
  const r=row.value
  void router.push(r.objectType==='partner'?{path:'/partner-products',query:{id:r.objectId}}:{path:'/projects',query:{tab:'archived',archiveView:'product',productId:r.objectId,history:'1'}})
}
function edit(){edited.value=false;editing.value=true}
function closeEditor(){if(edited.value)changed()}
onMounted(()=>{void permissions.ensure();void open(props.initial)})
onUnmounted(()=>{generation++})
</script>
<template>
  <el-drawer :model-value="true" :title="productLabel(row)" destroy-on-close append-to-body size="min(820px,100vw)" class="bom-catalog-drawer" @update:model-value="!$event && emit('close')">
    <template #header="{titleId}">
      <div class="bom-heading">
        <div class="headline"><strong :id="titleId">{{productLabel(row)}}</strong>
          <span class="color"><i v-if="productColorSwatches[row.colorNameZh] || row.colorNameZh==='透明'" :class="{clear:row.colorNameZh==='透明'}" :style="{backgroundColor:productColorSwatches[row.colorNameZh]}" aria-hidden="true"></i>
            <el-select v-if="scopedColors.length>1" :model-value="row.sourceId" :aria-label="t('当前颜色')" size="small" class="color-picker" @change="switchColor">
              <el-option v-for="c in scopedColors" :key="c.sourceId" :value="c.sourceId" :label="colorLabel(c)+(scopedColors.filter(x=>colorLabel(x)===colorLabel(c)).length>1?' · '+c.itemCode:'')"/>
            </el-select><span v-else>{{colorLabel(row)}}</span>
          </span>
        </div>
        <div class="identity"><span>{{row.modelName}}</span><code>{{row.itemCode}}</code></div>
        <div v-if="recipe" class="version-line">
          <el-select v-if="versions.length>1" :model-value="routeId" :aria-label="t('BOM 版本')" size="small" @change="changeVersion"><el-option v-for="v in versions" :key="v.id" :value="v.id" :label="v.label"/></el-select>
          <b v-else>BOM {{versionLabel(recipe)}}</b>
          <span class="bom-state" :data-state="recipe.engineeringState">{{stateLabel(recipe.engineeringState || 'history')}}</span>
        </div>
      </div>
    </template>
    <div ref="scrollArea" class="material-scroll" v-loading="loading">
      <el-alert v-if="error" type="error" :closable="false" :title="error"><el-button link @click="open(row)">{{t('重试')}}</el-button></el-alert>
      <template v-else-if="detail">
        <BomProcessMaterials v-if="recipe" :key="recipe.bomRouteId" :recipe="recipe"/>
        <el-empty v-else :description="row.objectType==='partner'?t('采购成品，无自制 BOM'):t('暂无适用 BOM')" :image-size="56"/>
        <details :key="row.sourceId+'-'+routeId" class="recipe-files"><summary>{{t('配方资料')}}</summary>
          <dl v-if="recipe"><dt>{{t('配方编号')}}</dt><dd>{{recipe.bomCode}}</dd><dt>{{t('工程版本')}}</dt><dd>{{recipe.versionNo}}</dd><dt>{{t('工艺路线')}}</dt><dd>{{recipe.routeCode}}</dd><template v-if="recipe.confirmedBy"><dt>{{t('工程确认')}}</dt><dd>{{recipe.confirmedBy}} · {{recipe.confirmedAt}}</dd></template></dl>
          <details v-if="recipe?.graph.nodes.length"><summary>{{t('工艺节点与依赖')}}</summary><ol><li v-for="node in recipe.graph.nodes" :key="node.id">{{node.name}} · {{node.nodeCode}}</li></ol><div v-for="edge in recipe.graph.edges" :key="edge.id">{{recipe.graph.nodes.find(n=>n.id===edge.fromNodeId)?.name}} → {{recipe.graph.nodes.find(n=>n.id===edge.toNodeId)?.name}} {{edge.label}}</div></details>
          <details><summary>{{t('来源资料')}}</summary><dl><template v-for="(v,k) in detail.source" :key="k"><dt>{{k}}</dt><dd>{{v??'—'}}</dd></template></dl></details>
          <el-button size="small" @click="archive">{{row.objectType==='partner'?t('打开合作产品'):t('产品档案')}}</el-button>
          <el-button v-if="recipe && permissions.canMaintain" size="small" @click="edit">{{t('配方维护')}}</el-button>
          <ProductProductionControl v-if="row.objectType==='product'" :product-id="row.objectId" :model-value="row.productionStatus" @changed="emit('changed')"/>
        </details>
      </template>
    </div>
    <template #footer><div class="bom-actionbar"><span v-if="recipe" :title="recipe.routeName"><small>{{t('工艺路线')}}</small> {{materialLabel(recipe.routeName)}}</span><el-button v-if="canConfirm && recipe" type="primary" @click="confirming=true">{{t('工程确认 {version}',{version:versionLabel(recipe)})}}</el-button><el-button v-else @click="emit('close')">{{t('返回列表')}}</el-button></div></template>
    <BomEngineeringConfirm v-if="confirming && recipe" :row="row" :recipe="recipe" :ambiguous="available.filter(v=>versionLabel(v)===versionLabel(recipe!)).length>1" @close="confirming=false" @confirmed="changed"/>
    <el-drawer v-if="recipe" v-model="editing" append-to-body destroy-on-close :title="t('配方维护')" size="min(1060px,98vw)" @closed="closeEditor"><ProjectBomPanel v-if="editing" :project-id="recipe.projectId" :initial-bom-id="recipe.bomId" @changed="edited=true"/></el-drawer>
  </el-drawer>
</template>
<style scoped>
.bom-heading{min-width:0;color:var(--el-text-color-primary)}.headline,.identity,.version-line,.color,.bom-actionbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.headline strong{font-size:20px}.color{gap:5px}.color i{width:11px;height:11px;border:1px solid var(--el-border-color);border-radius:50%}.clear{background:repeating-conic-gradient(var(--el-fill-color) 0% 25%,var(--el-bg-color) 0% 50%) 0 0/6px 6px}.color-picker{width:116px}.identity{margin:5px 0;color:var(--el-text-color-regular);font-size:14px}.identity code,small,dt{font-size:12px;color:var(--el-text-color-secondary)}.version-line{margin-top:8px;font-size:13px}.version-line .el-select{width:min(295px,65vw)}
.material-scroll{height:100%;min-height:80px;overflow:auto;overscroll-behavior:contain;padding:16px 20px}.recipe-files{margin-top:15px}details{font-size:13px;color:var(--el-text-color-regular)}details details{margin:10px 0}summary{cursor:pointer;width:fit-content}dl{display:grid;grid-template-columns:auto 1fr;gap:5px 12px}dd{margin:0;overflow-wrap:anywhere}.bom-actionbar{flex-wrap:nowrap;justify-content:space-between;text-align:left}.bom-actionbar>span{overflow-wrap:anywhere;min-width:0}.bom-actionbar .el-button{margin-left:auto}.bom-actionbar small{margin-right:5px}
:global(.bom-catalog-drawer .el-drawer__header){margin:0;padding:16px 20px 12px;border-bottom:1px solid var(--el-border-color-light);align-items:flex-start}
:global(.bom-catalog-drawer .el-drawer__body){padding:0;min-height:0;overflow:hidden}
:global(.bom-catalog-drawer .el-drawer__footer){padding:10px 20px;border-top:1px solid var(--el-border-color-light)}
@media(max-width:560px){.material-scroll{padding:12px 8px}:global(.bom-catalog-drawer .el-drawer__header){padding:12px}:global(.bom-catalog-drawer .el-drawer__footer){padding:9px 12px}}
</style>
