<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,ref} from 'vue'
import {useUserStore} from '@/stores/user'
import BomProcessMaterials from './BomProcessMaterials.vue'
import BomEngineeringConfirm from './BomEngineeringConfirm.vue'
import {type BomIdentity,stateLabel,versionLabel} from '../catalogPresentation'
import type {ScopedRecipe} from '@/api/modules/masterData'
import {useProductionMetadataStore} from '@/stores/productionMetadata'
import ProjectBomPanel from '@/views/project/components/ProjectBomPanel.vue'
const props=defineProps<{row:BomIdentity;recipes:ScopedRecipe[];purchased?:boolean}>()
const emit=defineEmits<{changed:[]}>()
const permissions=useProductionMetadataStore()
void permissions.ensure()
const expanded=ref<number[]>([])
const editing=ref<ScopedRecipe>(),editVisible=ref(false),editChanged=ref(false)
const nodes=computed(()=>new Map(props.recipes.flatMap(r=>r.graph.nodes.map(n=>[n.id,n.name] as const))))
const confirming=ref<ScopedRecipe>()
const user=useUserStore()
function confirmed(){confirming.value=undefined;emit('changed')}
</script>
<template>
  <div class="scoped-recipes">
    <el-alert v-if="purchased" type="info" :closable="false" :title='uiText("采购成品／合作产品：不要求自制 BOM、工艺路线或模具。供方和采购报价在合作产品中维护。")'/>
    <el-alert v-else-if="!recipes.length" type="warning" :closable="false" :title='uiText("MES 尚无适用于此 SKU 的 BOM／路线，保留真实缺项，不能据此确认生产资料。")'/>
    <article v-for="recipe in recipes" :key="recipe.bomRouteId" class="recipe">
      <header><div><strong>{{recipe.bomCode}}</strong><small>{{recipe.parentCode}} · {{recipe.parentName}} · MES {{recipe.sourceVersion}} · {{recipe.specification||uiText('未提供配方描述')}}</small></div><el-tag size="small">{{stateLabel(recipe.engineeringState || (recipe.status==='released'?'released':'pending'))}}</el-tag></header>
      <BomProcessMaterials :key="recipe.bomRouteId" :recipe="recipe"/>
      <el-collapse v-model="expanded"><el-collapse-item :name="recipe.bomRouteId" :title="uiText('工艺节点与依赖 · {value0} 节点／{value1} 连接',{value0:(recipe.graph.nodes.length),value1:(recipe.graph.edges.length)})">
        <ol class="nodes"><li v-for="node in recipe.graph.nodes" :key="node.id"><strong>{{node.stepNo}} · {{node.name}}</strong><code>{{node.nodeCode}}</code><span>{{node.operationCode}}</span></li></ol>
        <div class="edge" v-for="edge in recipe.graph.edges" :key="edge.id"><span>{{nodes.get(edge.fromNodeId)}}</span><b>→</b><span>{{nodes.get(edge.toNodeId)}}</span><small>{{edge.relationType}}{{edge.label?' · '+edge.label:''}}</small></div>
      </el-collapse-item></el-collapse>
      <footer><small>{{recipe.confirmedBy?uiText('确认：{value0} · {value1}',{value0:(recipe.confirmedBy),value1:(recipe.confirmedAt)}):uiText('未冒用 MES 已生效状态作为 PLM 审核事实')}}</small><div><el-button v-if="permissions.canMaintain" size="small" @click="editing=recipe;editChanged=false;editVisible=true">{{ uiText("维护配方／复制新版本") }}</el-button><el-button v-if="permissions.canMaintain && user.hasPermission('bom:publish') && recipe.engineeringState==='pending' && recipe.items.length" size="small" @click="confirming=recipe">{{ uiText("工程确认此版本") }}</el-button></div></footer>
    </article>
    <BomEngineeringConfirm v-if="confirming" :row="row" :recipe="confirming" :ambiguous="recipes.filter(v=>versionLabel(v)===versionLabel(confirming!)).length>1" @close="confirming=undefined" @confirmed="confirmed"/>
    <el-drawer v-model="editVisible" :title='uiText("工程用料与版本（复用原 BOM 维护）")' size="min(1060px,98vw)" destroy-on-close append-to-body @closed="editChanged&&emit('changed')"><ProjectBomPanel v-if="editing" :project-id="editing.projectId" :initial-bom-id="editing.bomId" @changed="editChanged=true"/></el-drawer>
  </div>
</template>
<style scoped>
.scoped-recipes{display:grid;gap:12px;container-type:inline-size}.recipe{border:1px solid var(--el-border-color-light);border-radius:6px;padding:12px;min-width:0}.recipe header,.recipe footer{display:flex;justify-content:space-between;gap:8px;align-items:flex-start;flex-wrap:wrap}.recipe p{margin:6px 0;overflow-wrap:anywhere}.recipe small{display:block;color:var(--el-text-color-secondary);overflow-wrap:anywhere;font-size:12px}.recipe strong,.recipe code{overflow-wrap:anywhere}.nodes{list-style:none;padding:0;display:grid;gap:6px}.nodes li{display:flex;gap:8px;flex-wrap:wrap}.edge{display:flex;gap:8px;flex-wrap:wrap;padding:4px}.recipe footer{margin-top:8px}@container(max-width:560px){.recipe{padding:8px}}
</style>
