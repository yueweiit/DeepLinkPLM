<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { operationCapabilities, operationPage, operationTarget, type OperationKind, type OperationCapabilities } from '@/api/modules/partnerOperations'
import { partnerError } from '@/utils/partnerError'
import type { PartnerPage, PartnerRow } from '@/types/partner'
import PartnerOperationCreate from './PartnerOperationCreate.vue'
import { operationTitles, operationLabel, moneyOrUnknown } from './presentation'
const props = defineProps<{ kind: OperationKind; readOnly?: boolean; supplierCode?: string; parentId?: number; productId?: number; sourceName?: string; specId?: number; specDescription?: string }>()
const route = useRoute(), router = useRouter(), loading = ref(false), error = ref(''), dialog = ref(false), importInfo = ref(false)
const createForm = ref<InstanceType<typeof PartnerOperationCreate>>()
const capabilities = ref<OperationCapabilities>({ engineering: false, costs: false, listing: false, delivery: false, externalListingConnected: false })
const filters = reactive({ keyword: '', status: '', page: 1 }), data = ref<PartnerPage>({ content: [], totalElements: 0, totalPages: 0, page: 1, size: 30 })
const canView = computed(() => !['supplier_quote', 'sample_fee'].includes(props.kind) || capabilities.value.costs)
const canCreate = computed(() => !props.readOnly && capabilities.value.engineering && props.kind !== 'listing' && (!['inspection', 'sample_fee'].includes(props.kind) || !!props.parentId))
const statuses = computed(() => props.kind === 'inspection' ? ['draft', 'pending_signature', 'signed'] : props.kind === 'fulfilment' ? ['pending', 'accepted', 'completed', 'shipping', 'received', 'closed'] : props.kind === 'listing' ? ['not_connected'] : ['draft', 'recorded'])
let generation = 0
async function load(reset = false) { if (reset) filters.page = 1; const ticket = ++generation; loading.value = true; error.value = ''
  try { capabilities.value = await operationCapabilities(); if (ticket !== generation) return; if (!canView.value) return
    const result = await operationPage(props.kind, { ...filters, supplierCode: props.supplierCode, parentId: props.parentId, productId: props.productId }); if (ticket === generation) data.value = result
  } catch (e) { if (ticket === generation) error.value = partnerError(e) } finally { if (ticket === generation) loading.value = false }
}
function open(row: PartnerRow) { void router.push(operationTarget(props.kind, row.id, route.fullPath)) }
function created(row: PartnerRow) { dialog.value = false; open(row) }
function closeCreate() { void createForm.value?.close() }
watch(() => [props.kind, props.supplierCode, props.parentId, props.productId], () => { data.value.content = []; void load(true) }, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>
<template><section class="operations-panel"><header><h3>{{ operationTitles[kind] }}</h3><span class="count" v-if="canView">共 {{ data.totalElements }} 条</span><el-button v-if="canCreate" size="small" type="primary" @click="dialog = true">{{ kind === 'inspection' ? '发起本次检验／复检' : kind === 'fulfilment' ? '引用确认需求' : '新增记录' }}</el-button><el-button v-if="kind === 'supplier_quote'" size="small" @click="importInfo = true">导入报价单（预留）</el-button></header>
<el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load()">重新加载</el-button></el-alert><p v-if="!canView" class="hint">供方报价与样品费用仅向获授权工程／财务显示。</p>
<template v-else><div class="filters"><el-input v-model="filters.keyword" clearable placeholder="搜索名称、编号或规格" @keyup.enter="load(true)"/><el-select v-model="filters.status" clearable placeholder="全部状态" @change="load(true)"><el-option v-for="status in statuses" :key="status" :value="status" :label="operationLabel(status)"/></el-select><el-button @click="load(true)">查询</el-button></div>
<div class="records" v-loading="loading"><el-empty v-if="!loading && !data.content.length" description="暂无记录，不代表检验已通过或费用已办理"/><article v-for="row in data.content" :key="row.id"><div><el-button link type="primary" @click="open(row)">{{ row.name }}</el-button><p class="hint">{{ row.code }} · {{ row.supplierName || row.factory || '' }}</p></div><div><span>{{ operationLabel(row.status) }}</span><p class="hint" v-if="kind === 'supplier_quote'">{{ row.specDescription }} · {{ moneyOrUnknown(row.unitPriceCents, row.currency) }}</p><p class="hint" v-else-if="kind === 'fulfilment'">{{ row.lines?.length || 0 }} 项规格 · {{ row.sourceChannel }} · 预计出厂 {{ row.expectedFactoryDate || '待工厂确认' }}</p><p class="hint" v-else-if="kind === 'inspection'">{{ row.resultSnapshot ? operationLabel(row.resultSnapshot.result) : '待工程填写实际检查结果' }}</p><p class="hint" v-else>{{ row.reason || row.terms }}</p></div><el-button link @click="open(row)">查看／办理</el-button></article></div>
<footer><span>第 {{ filters.page }} 页</span><el-pagination v-model:current-page="filters.page" :page-size="30" :total="data.totalElements" :pager-count="5" layout="prev,pager,next" @current-change="load()"/></footer></template>
<el-dialog v-model="dialog" :title="operationTitles[kind]" width="min(760px,96vw)" :close-on-click-modal="false" :before-close="closeCreate" destroy-on-close append-to-body><PartnerOperationCreate ref="createForm" :kind="kind" :parent-id="parentId || (kind === 'supplier_quote' ? productId : undefined)" :spec-id="specId" :spec-description="specDescription" :supplier-code="supplierCode" :source-name="sourceName" @saved="created" @close="dialog = false"/></el-dialog>
<el-dialog v-model="importInfo" title="报价单导入：预留入口" width="min(480px,94vw)" append-to-body><p>本轮暂未启用识别、AI或批量选款，不上传或外传报价文件，也不产生调用费用。</p><p>现在可以手工记录需要的款式，并在记录详情附原报价单；不必录入整份未采用目录。</p><template #footer><el-button type="primary" @click="importInfo = false">返回正常询报价</el-button></template></el-dialog>
</section></template>
<style scoped>.operations-panel{display:flex;flex-direction:column;gap:8px;min-width:0;min-height:0;container-type:inline-size}header,.filters,footer{display:flex;gap:8px;align-items:center;flex-wrap:wrap}h3{font-size:15px;margin:0}.count{flex:1;font-size:12px;color:#708297}.filters>.el-input{flex:1;min-width:min(180px,100%)}.filters>.el-select{width:160px;max-width:100%}.records{min-height:90px;max-height:420px;overflow:auto;border:1px solid #e4eaf1;border-radius:6px}article{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr) auto;gap:12px;align-items:center;padding:10px 12px;border-bottom:1px solid #edf0f5}article>div{min-width:0;overflow-wrap:anywhere}article :deep(.el-button){white-space:normal;height:auto;text-align:left;line-height:1.6}.hint{font-size:13px;color:#708297;line-height:1.6;margin:3px 0;overflow-wrap:anywhere}footer{justify-content:space-between;font-size:12px;color:#708297}@container(max-width:640px){article{grid-template-columns:minmax(0,1fr) auto}article>div:nth-child(2){grid-column:1/-1;grid-row:2}.filters>.el-select{flex:1;min-width:120px}.filters>.el-input{flex-basis:100%}}@container(max-width:380px){footer :deep(.el-pager){display:none}}
</style>
