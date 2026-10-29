<script setup lang="ts">
import {computed,ref,watch,onBeforeUnmount} from 'vue'
import CodeMaintenancePanel from '@/views/code/components/CodeMaintenancePanel.vue'
import {getCodeItems,type CodeItem} from '@/api/modules/code'
import {getPhoneBrands} from '@/api/modules/phoneReference'
import {useUserStore} from '@/stores/user'
import FixedTableViewport from '@/components/FixedTableViewport/index.vue'
import MetadataDialog from '@/components/ProductionMetadata/MetadataDialog.vue'
import PhoneBrandReviewDialog from '@/components/ProductionMetadata/PhoneBrandReviewDialog.vue'
import {productionStatusOptions,productionStatusName,productionStatusType} from '@/utils/productionStatus'
import type {PhoneBrand} from '@/types/molds'
import type {ProductionMetadataTarget,ProductionStatus} from '@/types/production'
const props=defineProps<{visible:boolean;editable:boolean;keyword?:string}>()
const emit=defineEmits<{changed:[]}>()
const user=useUserStore(),rows=ref<CodeItem[]>([]),brands=ref<PhoneBrand[]>([]),keyword=ref(''),brand=ref<number|''|'unclassified'>(''),state=ref<ProductionStatus|''>(''),page=ref(1),size=ref(50),total=ref(0),loading=ref(false),error=ref('')
const selection=ref(new Map<number,CodeItem>()),editor=ref(false),brandReview=ref(false),targets=ref<ProductionMetadataTarget[]>([])
const codePanel=ref<InstanceType<typeof CodeMaintenancePanel>>()
let generation=0
const allSelected=computed(()=>!!rows.value.length&&rows.value.every(r=>selection.value.has(r.codeItemId)))
const target=(r:CodeItem):ProductionMetadataTarget=>({id:r.codeItemId,label:`${r.codeValue} · ${r.codeName}`,productionStatus:r.productionStatus||'unknown',brandIds:r.brands?.map(b=>b.brandId)||[],memberBrandIds:r.brands?.filter(b=>b.memberConfirmed).map(b=>b.brandId)||[]})
async function load(){if(!props.visible)return;const token=user.token,epoch=++generation;loading.value=true;error.value='';try{const [result,bs]=await Promise.all([getCodeItems({codeType:'phone_model',page:page.value,size:size.value,keyword:keyword.value.trim(),...(typeof brand.value==='number'?{brandId:brand.value}:brand.value==='unclassified'?{unclassified:true}:{}),...(state.value?{productionStatus:state.value}:{})}),getPhoneBrands({page:1,size:100})]);if(epoch===generation&&token===user.token&&props.visible){rows.value=result.content;total.value=result.totalElements;brands.value=bs.content}}catch(e){if(epoch===generation&&token===user.token)error.value=e instanceof Error?e.message:'编码组加载失败，请重试'}finally{if(epoch===generation&&token===user.token)loading.value=false}}
function filter(){page.value=1;selection.value=new Map();void load()}
function select(r:CodeItem,checked:boolean){const next=new Map(selection.value);if(checked)next.set(r.codeItemId,r);else next.delete(r.codeItemId);selection.value=next}
function selectPage(){const checked=!allSelected.value;for(const r of rows.value)select(r,checked)}
function edit(group?:CodeItem){targets.value=(group?[group]:[...selection.value.values()]).map(target);editor.value=true}
function saved(){selection.value=new Map();emit('changed');void load()}
function locate(code:string){brandReview.value=false;keyword.value=code;brand.value='';state.value='';filter()}
watch(()=>props.visible,v=>{if(v){keyword.value=props.keyword||'';page.value=1;void load()}else{generation++;rows.value=[];selection.value=new Map();editor.value=false;brandReview.value=false}},{immediate:true})
watch(()=>props.keyword,v=>{keyword.value=v||'';if(props.visible)filter()})
watch(()=>user.token,()=>{generation++;rows.value=[];brands.value=[];selection.value=new Map();editor.value=false;brandReview.value=false;total.value=0})
onBeforeUnmount(()=>generation++)
</script>
<template>
  <section class="code-groups" aria-label="手机型号编码组">
    <div class="group-filters"><el-input v-model="keyword" size="small" aria-label="型号编码组关键词" clearable placeholder="四位编码 / 原型号名称" @keyup.enter="filter"/><el-select v-model="brand" size="small" aria-label="手机编码品牌筛选" placeholder="全部品牌" @change="filter"><el-option value="" label="全部品牌"/><el-option value="unclassified" label="未归类"/><el-option v-for="b in brands" :key="b.brandId" :value="b.brandId" :label="b.name"/></el-select><el-select v-model="state" size="small" aria-label="手机生产状态筛选" placeholder="全部生产状态" @change="filter"><el-option value="" label="全部生产状态"/><el-option v-for="s in productionStatusOptions" :key="s.value" :value="s.value" :label="s.label"/></el-select><el-button size="small" @click="filter">查询</el-button></div>
    <div class="group-toolbar"><small>共 {{total}} 个编码组 · 数字降序 · 原名称与前导零保留</small><div v-if="editable" class="group-actions"><el-button type="primary" size="small" @click="codePanel?.open()">新增手机编码</el-button><el-button size="small" @click="brandReview=true">一键核对品牌</el-button><el-button size="small" :disabled="!selection.size||selection.size>100" @click="edit()">批量维护{{selection.size?` (${selection.size})`:''}}</el-button><small v-if="selection.size>100">每批最多100条</small></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load">重试</el-button></el-alert>
    <FixedTableViewport fill :refresh-key="rows"><div class="group-list" role="table" aria-label="手机编码组列表" v-loading="loading"><div class="group-heading group-row" role="row"><span role="columnheader"><input v-if="editable" type="checkbox" aria-label="选择本页编码组" :checked="allSelected" @change="selectPage"/></span><span role="columnheader">编码</span><span role="columnheader">原型号名称</span><span role="columnheader">品牌</span><span role="columnheader">生产状态</span><span role="columnheader">操作</span></div><div class="group-body" role="rowgroup" tabindex="0" aria-label="手机编码组记录">
      <div v-for="r in rows" :key="r.codeItemId" class="group-row group-record" role="row"><div class="group-check" role="cell"><input v-if="editable" type="checkbox" :aria-label="`选择编码 ${r.codeValue}`" :checked="selection.has(r.codeItemId)" @change="select(r,($event.target as HTMLInputElement).checked)"/></div><div class="group-code" role="cell"><strong>{{r.codeValue}}</strong></div><div class="group-name" role="cell">{{r.codeName}}<small v-if="r.status==='disabled'">资料已停用</small></div><div class="group-brands" role="cell">{{r.brands?.map(b=>b.name).join(' / ')||'未归类'}}</div><div class="group-state" role="cell"><el-tag size="small" :type="productionStatusType(r.productionStatus)">{{productionStatusName(r.productionStatus)}}</el-tag></div><div class="group-action" role="cell"><el-button size="small" link @click="codePanel?.show(r)">详情</el-button><template v-if="editable"><el-button size="small" link @click="codePanel?.open(r)">编辑</el-button><el-button size="small" link @click="codePanel?.lifecycle(r,r.status==='enabled'?'deactivate':'activate')">{{r.status==='enabled'?'停用':'启用'}}</el-button><el-button size="small" link type="danger" @click="codePanel?.lifecycle(r,'delete')">删除</el-button></template><el-button v-if="editable" size="small" link @click="edit(r)">维护</el-button><small v-else>只读</small></div></div><el-empty v-if="!rows.length&&!loading" description="没有符合条件的编码组" :image-size="60"/>
    </div></div></FixedTableViewport>
    <el-pagination size="small" v-model:current-page="page" v-model:page-size="size" :page-sizes="[20,50,100]" :total="total" :pager-count="5" layout="sizes,prev,pager,next" @current-change="load" @size-change="filter"/>
    <CodeMaintenancePanel ref="codePanel" code-type="phone_model" :editable="editable" @changed="saved"/>
    <MetadataDialog v-model="editor" kind="phone" :items="targets" :brands="brands" @saved="saved"/>
    <PhoneBrandReviewDialog v-model="brandReview" :editable="editable" @saved="saved" @locate="locate"/>
  </section>
</template>
<style scoped>
.code-groups{display:flex;flex:1;min-height:0;min-width:0;flex-direction:column;gap:8px;container-type:inline-size}.group-filters{display:flex;gap:6px;flex-wrap:wrap;flex:none}.group-filters .el-input{flex:1;min-width:180px}.group-filters .el-select{width:150px}.group-toolbar{display:flex;justify-content:space-between;align-items:center;gap:8px;flex:none;flex-wrap:wrap}.group-actions{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.group-actions .el-button+.el-button{margin-left:0}small{color:var(--el-text-color-secondary);font-size:12px;line-height:17px}.group-list{height:100%;min-height:0;display:flex;flex-direction:column;border:1px solid var(--el-border-color-lighter);border-radius:4px;overflow:hidden}.group-heading{background:var(--el-fill-color-light);color:var(--el-text-color-secondary);flex:none}.group-row{display:grid;grid-template-columns:22px 60px minmax(0,2.5fr) minmax(0,1.3fr) 70px minmax(110px,1fr);gap:8px;padding:6px 8px;align-items:center;font-size:12px;line-height:18px}.group-row>div{min-width:0;overflow-wrap:anywhere}.group-body{flex:1;min-height:0;overflow-y:auto;overflow-x:hidden;scrollbar-gutter:stable}.group-record{border-bottom:1px solid var(--el-border-color-lighter)}.group-record:nth-child(even){background:var(--el-fill-color-lighter)}.group-record small{display:block}.group-code{font-variant-numeric:tabular-nums}.group-brands{font-size:12px;color:var(--el-text-color-secondary)}.el-pagination{justify-content:flex-end;flex:none}
@container(max-width:650px){.group-heading{display:none}.group-row{grid-template-columns:18px 48px minmax(0,1fr) 62px;grid-template-areas:"check code name state" ". brands brands action";gap:1px 6px;padding:4px 6px}.group-check{grid-area:check}.group-code{grid-area:code}.group-name{grid-area:name}.group-brands{grid-area:brands}.group-state{grid-area:state}.group-action{grid-area:action;justify-self:end;display:flex;flex-wrap:wrap;gap:4px}.group-action .el-button{height:18px;min-height:18px;padding:0}.group-filters .el-select{flex:1;min-width:120px}}
</style>
