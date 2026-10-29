<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import FixedTableViewport from '@/components/FixedTableViewport/index.vue'
import StatusTag from '@/components/StatusTag/index.vue'
import ProductProductionControl from './ProductProductionControl.vue'
import type {ProductionStatus} from '@/types/production'
import type {CommonStatus} from '@/types/common'
import {formatDate} from '@/utils/format'
interface Row {productId:number;productName:string;productCode:string;productionStatus?:ProductionStatus;status:CommonStatus;model?:string;color?:string;seriesName?:string;versionNo?:string;ownerUserName?:string;updatedAt?:string|null;lastActiveAt?:string;displaySkuCode?:string;stockUom?:string;projectSource?:string;sampleImageUrl?:string;activeBomVersion?:string|null;completionRate?:number}
const props=withDefaults(defineProps<{rows:Row[];selectable?:boolean;sku?:boolean;code?:(row:any)=>string}>(),{selectable:false,sku:false})
const emit=defineEmits<{detail:[any];selectionChange:[Row[]];changed:[]}>()
const selected=ref(new Set<number>())
const allSelected=computed(()=>!!props.rows.length&&props.rows.every(r=>selected.value.has(r.productId)))
function select(id:number,on:boolean){const next=new Set(selected.value);if(on)next.add(id);else next.delete(id);selected.value=next;emit('selectionChange',props.rows.filter(r=>next.has(r.productId)))}
function selectAll(){const on=!allSelected.value;selected.value=new Set(on?props.rows.map(r=>r.productId):[]);emit('selectionChange',on?props.rows:[])}
watch(()=>props.rows,()=>{selected.value=new Set();emit('selectionChange',[])})
</script>
<template>
  <FixedTableViewport fill :refresh-key="rows"><div class="product-record-list" role="table" :aria-label="sku?'SKU 生产状态列表':'产品生产状态列表'">
    <div class="record-header record-row" role="row"><span><input v-if="selectable" type="checkbox" aria-label="选择本页产品" :checked="allSelected" @change="selectAll"/></span><span>名称 / 编码</span><span>{{sku?'型号 / 颜色 / 来源':'系列 / 负责人 / 版本'}}</span><span>开发 / 生产状态</span><span>操作</span></div>
    <div class="record-body" role="rowgroup" tabindex="0">
      <div v-for="r in rows" :key="r.productId" class="record-row" role="row">
        <div class="record-check" role="cell"><input v-if="selectable" type="checkbox" :aria-label="`选择产品 ${r.productName}`" :checked="selected.has(r.productId)" @change="select(r.productId,($event.target as HTMLInputElement).checked)"/></div>
        <div class="record-name" role="cell"><slot name="image" :row="r"/><strong>{{r.productName}}</strong><span>{{code?code(r):r.displaySkuCode||r.productCode}}</span></div>
        <div class="record-meta" role="cell"><template v-if="sku"><span>型号：{{r.model||'--'}} · 颜色：{{r.color||'--'}}</span><span>单位：{{r.stockUom||'pcs'}} · 来源：{{r.projectSource||'历史归档'}}</span></template><template v-else><span>{{r.seriesName||'--'}} · 负责人：{{r.ownerUserName||'--'}}</span><span>BOM：{{r.activeBomVersion||'--'}} · 完整率：{{r.completionRate===undefined?'--':Math.round(r.completionRate*100)+'%'}}</span></template><span>版本：{{r.versionNo||'--'}}<template v-if="r.updatedAt||r.lastActiveAt"> · 更新：{{formatDate(r.updatedAt||r.lastActiveAt)}}</template></span></div>
        <div class="record-state" role="cell"><StatusTag :status="r.status" object-type="product"/><ProductProductionControl :product-id="r.productId" :model-value="r.productionStatus||'unknown'" :label="r.productName" @update:model-value="r.productionStatus=$event" @changed="emit('changed')"/></div>
        <div class="record-action" role="cell"><el-button size="small" link type="primary" @click="emit('detail',r)">详情</el-button></div>
      </div><el-empty v-if="!rows.length" description="暂无符合条件的记录" :image-size="60"/>
    </div>
  </div></FixedTableViewport>
</template>
<style scoped>
.product-record-list{height:100%;min-height:0;min-width:0;display:flex;flex-direction:column;container-type:inline-size;border:1px solid var(--el-border-color-lighter);border-radius:4px;overflow:hidden}.record-row{display:grid;grid-template-columns:22px minmax(0,2fr) minmax(0,1.7fr) minmax(0,1fr) 42px;gap:8px;padding:6px 8px;align-items:center;font-size:13px;line-height:19px}.record-row>div{min-width:0;overflow-wrap:anywhere}.record-header{background:var(--el-fill-color-light);color:var(--el-text-color-secondary);flex:none}.record-body{min-height:0;flex:1;overflow-y:auto;overflow-x:hidden;scrollbar-gutter:stable}.record-body>.record-row{border-bottom:1px solid var(--el-border-color-lighter)}.record-body>.record-row:nth-child(even){background:var(--el-fill-color-lighter)}.record-name,.record-meta,.record-state{display:flex;flex-direction:column;align-items:flex-start;gap:2px}.record-name>span,.record-meta{color:var(--el-text-color-secondary);font-size:12px}.record-action{justify-self:end}
@container(max-width:760px){.record-header{display:none}.record-row{grid-template-columns:18px minmax(0,1fr) 44px;grid-template-areas:"check name action" ". meta meta" ". state state";gap:3px 6px}.record-check{grid-area:check}.record-name{grid-area:name}.record-meta{grid-area:meta}.record-state{grid-area:state;flex-direction:row;flex-wrap:wrap;align-items:center}.record-action{grid-area:action}}
</style>

<style scoped>
@container(max-width:760px){.record-name,.record-meta{flex-direction:row;flex-wrap:wrap;align-items:center;gap:2px 8px}}
</style>
