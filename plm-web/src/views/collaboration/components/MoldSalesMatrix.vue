<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,onBeforeUnmount,onMounted,ref} from 'vue'
import {moldSalesProductKey as productKey} from '@/composables/useMoldSalesPreferences'
import type {MoldSalesMatrixCell,MoldSalesMatrixPage,MoldSalesMatrixRow,SalesProductOption} from '@/api/modules/moldSales'
const props=defineProps<{data?:MoldSalesMatrixPage;products:SalesProductOption[]}>()
const emit=defineEmits<{open:[cell:MoldSalesMatrixCell,row:MoldSalesMatrixRow];total:[row:MoldSalesMatrixRow]}>()
const root=ref<HTMLElement>(),width=ref(0)
let observer:ResizeObserver|undefined
onMounted(()=>{if(typeof ResizeObserver!=='undefined'){observer=new ResizeObserver(entries=>{width.value=entries[0]?.contentRect.width||0});if(root.value)observer.observe(root.value)}})
onBeforeUnmount(()=>observer?.disconnect())
const stacked=computed(()=>width.value>0&&width.value<376+props.products.length*120)
const columns=computed(()=>`78px minmax(170px,1.6fr) ${props.products.map(()=>'minmax(90px,1fr)').join(' ')} 100px`)
const qty=(n:number|null|undefined)=>n==null?'—':new Intl.NumberFormat('zh-CN',{maximumFractionDigits:0}).format(n)
function cell(row:MoldSalesMatrixRow,product:SalesProductOption){return row.cells.find(c=>productKey(c)===productKey(product))}
const clickable=(value:MoldSalesMatrixCell|undefined)=>!!value&&((value.sourceGroupKeys?.length||0)>0||value.groupIds.length>0||value.applicationIds.length>0||value.itemIds?.length>0)
const tone=(state?:string)=>({reusable:'ready',confirmed:'ready',making:'active',planned:'waiting',applying:'waiting',draft:'waiting',paused:'review',terminated:'review'}[state||'']||'neutral')
const visibleState=(value:MoldSalesMatrixCell|undefined)=>!!value?.stateLabel&&value.stateLabel!=='—'&&!['historical','needs_review'].includes(value.state)
function open(value:MoldSalesMatrixCell|undefined,row:MoldSalesMatrixRow){if(value&&clickable(value))emit('open',value,row)}
</script>
<template>
 <div ref="root" class="sales-matrix" :class="{'is-stacked':stacked}" :style="{'--matrix-columns':columns}" role="table" :aria-label='uiText("手机型号产品销量总览")'>
  <div class="matrix-row matrix-head" role="row"><span role="columnheader">{{ uiText("品牌") }}</span><span role="columnheader">{{ uiText("手机型号 / 编码") }}</span><span v-for="p in products" :key="productKey(p)" role="columnheader">{{p.productName}}</span><span role="columnheader">{{ uiText("已选产品合计") }}</span></div>
  <div class="matrix-body">
   <article v-for="row in data?.content" :key="row.rowKey" class="matrix-row matrix-record" role="row">
    <div class="brand-cell" role="cell"><small class="record-label">{{ uiText("品牌") }}</small><strong>{{row.brandNames.join(' / ')||uiText('未登记品牌')}}</strong></div>
    <div class="model-cell" role="cell"><small class="record-label">{{ uiText("手机型号") }}</small><strong>{{row.modelNames.join(' / ')||uiText('未登记型号')}}</strong><div class="model-meta"><small v-if="row.phoneCodes.length">{{ uiText("编码 ") }}{{row.phoneCodes.join(' / ')}}</small><span v-if="row.modelKind==='shared'" class="model-badge">{{ uiText("通用组合 · 合计一次") }}</span></div></div>
    <div v-for="p in products" :key="productKey(p)" class="product-cell" role="cell" :data-product-id="p.productLineId" :data-product-key="productKey(p)">
     <small class="record-label">{{p.productName}}</small>
     <button v-if="clickable(cell(row,p))" class="matrix-cell-button" :aria-label="uiText('{value0} · {value1} · {value2}',{value0:(p.productName),value1:(row.modelNames.join(' / ')),value2:(cell(row,p)?.stateLabel||'查看依据')})" @click="open(cell(row,p),row)"><strong>{{qty(cell(row,p)?.salesQuantity)}}<small v-if="cell(row,p)?.salesQuantity!=null">{{ uiText("件") }}</small></strong><span v-if="visibleState(cell(row,p))" class="state-label" :class="tone(cell(row,p)?.state)">{{uiText(String((cell(row,p)?.stateLabel) ?? ''))}}</span></button>
     <template v-else><strong class="plain-quantity">{{qty(cell(row,p)?.salesQuantity)}}</strong><span v-if="visibleState(cell(row,p))" class="state-label" :class="tone(cell(row,p)?.state)">{{uiText(String((cell(row,p)?.stateLabel) ?? ''))}}</span></template>
    </div>
    <div class="matrix-total" role="cell"><small class="record-label">{{ uiText("已选产品合计") }}</small><button v-if="row.cells.some(c=>c.groupIds.length||c.sourceGroupKeys?.length)" @click="emit('total',row)">{{qty(row.totalSalesQuantity)}}<small>{{ uiText("件") }}</small></button><strong v-else>{{qty(row.totalSalesQuantity)}}</strong></div>
   </article>
   <el-empty v-if="data&&!data.content.length" :description="products.length?uiText('当前筛选范围暂无型号记录'):uiText('请先选择要查看的产品列')"/>
  </div>
  <div v-if="data&&products.length" class="matrix-row matrix-foot" role="row"><strong class="summary-label">{{ uiText("当前筛选合计") }}</strong><div v-for="p in products" :key="productKey(p)" role="cell"><small class="record-label">{{p.productName}}</small><strong>{{qty(data.productTotals.find(t=>productKey(t)===productKey(p))?.salesQuantity)}}</strong></div><div class="matrix-total" role="cell"><small class="record-label">{{ uiText("已选产品合计") }}</small><strong>{{qty(data.totalSalesQuantity)}}</strong></div></div>
 </div>
</template>
<style scoped>
.sales-matrix{min-width:0;min-height:0;flex:1;display:flex;flex-direction:column;overflow:hidden;border:1px solid #dfe6ef;border-radius:4px;background:#fff;font-size:12px}.matrix-row{display:grid;grid-template-columns:var(--matrix-columns);gap:8px;align-items:center;padding:2px 10px;min-width:0;box-sizing:border-box;min-height:40px;border-bottom:1px solid #e6ecf3;line-height:18px}.matrix-row>div,.matrix-row>span{min-width:0;overflow-wrap:anywhere}.matrix-head{background:#edf2f8;color:#526479;min-height:32px;font-size:13px;font-weight:600}.matrix-head>span:nth-child(n+3),.product-cell,.matrix-total,.matrix-foot>div{text-align:right}.matrix-body{min-height:0;flex:1;overflow-y:auto;overflow-x:hidden}.matrix-record:nth-child(even){background:#fafcff}.brand-cell strong,.model-cell strong{font-weight:600;color:#253951}.model-cell>strong{display:block}.model-cell small{font-size:12px;color:#738298}.model-cell>.record-label{display:none}.model-meta{display:flex;align-items:center;flex-wrap:wrap;gap:0 6px;min-width:0}.model-meta small{min-width:0;overflow-wrap:anywhere;line-height:18px}.model-badge{font-size:11px;line-height:16px;background:#edf3ff;color:#41689f;border-radius:2px;padding:0 4px;display:inline-block}.matrix-cell-button,.matrix-total button{border:0;padding:0;background:none;color:#2469b6;font:inherit;text-align:inherit;cursor:pointer;width:100%;white-space:normal;overflow-wrap:anywhere}.matrix-cell-button strong,.matrix-total strong,.matrix-total button{font-weight:600}.matrix-cell-button strong small,.matrix-total button small{font-size:11px;margin-left:3px;color:#78869a}.state-label{display:block;font-size:11px;line-height:16px;overflow-wrap:anywhere}.neutral{color:#7e8b9d}.ready{color:#29805e}.active{color:#2669b4}.waiting{color:#a96c15}.review{color:#a75e36}.plain-quantity{font-weight:500;color:#7e8b9d}.record-label{display:none}.matrix-foot{border-bottom:0;border-top:1px solid #dce5ee;background:#f1f6fb;min-height:32px;color:#334d6d}.summary-label{grid-column:1/3}.is-stacked .matrix-head{display:none}.is-stacked .matrix-row{grid-template-columns:repeat(3,minmax(0,1fr));gap:5px 12px;padding:8px 10px}.is-stacked .brand-cell{grid-column:1/2}.is-stacked .model-cell{grid-column:2/-1}.is-stacked .record-label,.is-stacked .model-cell>.record-label{display:block;color:#738298;font-size:11px;line-height:16px;font-weight:400}.is-stacked .product-cell,.is-stacked .matrix-total,.is-stacked .matrix-foot>div{text-align:left}.is-stacked .summary-label{grid-column:1/-1}.is-stacked .matrix-total{border-top:1px solid #e7edf3;padding-top:4px}.is-stacked .matrix-cell-button{text-align:left}.is-stacked .matrix-cell-button strong,.is-stacked .matrix-cell-button .state-label{display:inline}.is-stacked .matrix-cell-button .state-label{margin-left:6px}.is-stacked .matrix-foot{flex-shrink:0}.sales-matrix{container-type:inline-size}@container(max-width:460px){.is-stacked .matrix-row{grid-template-columns:repeat(2,minmax(0,1fr))}.is-stacked .brand-cell,.is-stacked .model-cell{grid-column:1/-1}}
</style>
