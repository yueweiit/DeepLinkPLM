<script setup lang="ts">
import {computed,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {confirmCurrentBomVersion} from '@/api/modules/bom'
import type {ScopedRecipe} from '@/api/modules/masterData'
import {useProductionMetadataStore} from '@/stores/productionMetadata'
import ProjectBomPanel from '@/views/project/components/ProjectBomPanel.vue'
const props=defineProps<{recipes:ScopedRecipe[];purchased?:boolean}>()
const emit=defineEmits<{changed:[]}>()
const permissions=useProductionMetadataStore(),busy=ref(false)
void permissions.ensure()
const expanded=ref<number[]>([])
const editing=ref<ScopedRecipe>(),editVisible=ref(false),editChanged=ref(false)
const nodes=computed(()=>new Map(props.recipes.flatMap(r=>r.graph.nodes.map(n=>[n.id,n.name] as const))))
async function confirm(recipe:ScopedRecipe){
  try {await ElMessageBox.confirm('确认此 SKU 范围对应的整份配方及指定路线版本。仅记录国内工程确认，不发送 MES／ERP，不增加其他审核。','工程确认',{confirmButtonText:'确认配方版本',cancelButtonText:'取消'});busy.value=true;await confirmCurrentBomVersion(recipe.bomId);ElMessage.success('工程版本已确认');emit('changed')}catch(error){if(error!=='cancel'&&error!=='close')ElMessage.error(error instanceof Error?error.message:'确认失败，资料未覆盖')}finally{busy.value=false}
}
</script>
<template>
  <div class="scoped-recipes">
    <el-alert v-if="purchased" type="info" :closable="false" title="采购成品／合作产品：不要求自制 BOM、工艺路线或模具。供方和采购报价在合作产品中维护。"/>
    <el-alert v-else-if="!recipes.length" type="warning" :closable="false" title="MES 尚无适用于此 SKU 的 BOM／路线，保留真实缺项，不能据此确认生产资料。"/>
    <article v-for="recipe in recipes" :key="recipe.bomRouteId" class="recipe">
      <header><div><strong>{{recipe.bomCode}}</strong><small>{{recipe.parentCode}} · {{recipe.parentName}} · MES {{recipe.sourceVersion}} · {{recipe.specification||'未提供配方描述'}}</small></div><el-tag size="small">{{recipe.status==='released'?'工程已确认':'来源基线 · 待工程确认'}}</el-tag></header>
      <p class="basis">基准产量 <b>{{recipe.outputQty}}</b>；以下数量均相对此基准，不是整份共享配方的全部行。型号专用料已按当前品目过滤。</p>
      <p>{{recipe.routeName}}<small>{{recipe.routeCode}}</small></p>
      <div class="material-head"><span>节点／工序</span><span>组件编码／名称</span><span>用量／单位</span></div>
      <div v-for="item in recipe.items" :key="`${item.id}-${item.nodeId}`" class="material-row">
        <div><strong>{{item.nodeName}}</strong><small>{{item.nodeCode}}</small><small>{{item.operationCode}}</small></div>
        <div><code>{{item.childCode}}</code> <el-tag v-if="item.articleSpecific" size="small" type="info">型号专用</el-tag><p>{{item.childName}}</p></div>
        <div><b>{{item.quantity}}</b> {{item.unit}}<small v-if="item.unitRaw&&item.unitRaw!==item.unit">原单位：{{item.unitRaw}}</small><small v-if="item.stockUnit&&item.stockUnit!==item.unit">库存单位：{{item.stockUnit}}；未隐式换算数量</small><small v-if="item.unitProjection">MES 已核验的物料特定单位投影；不是通用换算</small><small v-if="item.unitPrice!=null">原单价 {{item.unitPrice}} {{item.currency||'币种未提供，不作金额计算'}}</small></div>
      </div>
      <el-collapse v-model="expanded"><el-collapse-item :name="recipe.bomRouteId" :title="`工艺节点与依赖 · ${recipe.graph.nodes.length} 节点／${recipe.graph.edges.length} 连接`">
        <ol class="nodes"><li v-for="node in recipe.graph.nodes" :key="node.id"><strong>{{node.stepNo}} · {{node.name}}</strong><code>{{node.nodeCode}}</code><span>{{node.operationCode}}</span></li></ol>
        <div class="edge" v-for="edge in recipe.graph.edges" :key="edge.id"><span>{{nodes.get(edge.fromNodeId)}}</span><b>→</b><span>{{nodes.get(edge.toNodeId)}}</span><small>{{edge.relationType}}{{edge.label?' · '+edge.label:''}}</small></div>
      </el-collapse-item></el-collapse>
      <footer><small>{{recipe.confirmedBy?`确认：${recipe.confirmedBy} · ${recipe.confirmedAt}`:'未冒用 MES 已生效状态作为 PLM 审核事实'}}</small><div><el-button v-if="permissions.canMaintain" size="small" @click="editing=recipe;editChanged=false;editVisible=true">维护配方／复制新版本</el-button><el-button v-if="permissions.canMaintain&&recipe.status==='frozen'" size="small" :loading="busy" @click="confirm(recipe)">工程确认此版本</el-button></div></footer>
    </article>
    <el-drawer v-model="editVisible" title="工程用料与版本（复用原 BOM 维护）" size="min(1060px,98vw)" destroy-on-close append-to-body @closed="editChanged&&emit('changed')"><ProjectBomPanel v-if="editing" :project-id="editing.projectId" :initial-bom-id="editing.bomId" @changed="editChanged=true"/></el-drawer>
  </div>
</template>
<style scoped>
.scoped-recipes{display:grid;gap:12px;container-type:inline-size}.recipe{border:1px solid var(--el-border-color-light);border-radius:6px;padding:12px;min-width:0}.recipe header,.recipe footer{display:flex;justify-content:space-between;gap:8px;align-items:flex-start;flex-wrap:wrap}.recipe p{margin:6px 0;overflow-wrap:anywhere}.recipe small{display:block;color:var(--el-text-color-secondary);overflow-wrap:anywhere;font-size:12px}.recipe strong,.recipe code{overflow-wrap:anywhere}.basis{background:var(--el-fill-color-light);padding:8px}.material-head,.material-row{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.6fr) minmax(0,.75fr);gap:12px;padding:8px 0}.material-head{color:var(--el-text-color-secondary);border-bottom:1px solid var(--el-border-color)}.material-row{border-bottom:1px solid var(--el-border-color-lighter)}.material-row>*{min-width:0}.nodes{list-style:none;padding:0;display:grid;gap:6px}.nodes li{display:flex;gap:8px;flex-wrap:wrap}.edge{display:flex;gap:8px;flex-wrap:wrap;padding:4px}.recipe footer{margin-top:8px}@container(max-width:560px){.material-head{display:none}.material-row{grid-template-columns:1fr}.material-row>div:first-child{background:var(--el-fill-color-light);padding:4px}.recipe{padding:8px}}
</style>
