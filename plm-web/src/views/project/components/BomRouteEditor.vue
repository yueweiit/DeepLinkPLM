<script setup lang="ts">
import { Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'

import type { BomItem, BomRoute } from '@/types/bom'
import { getEnabledColorCodes, type CodeItem } from '@/api/modules/code'
import { lookupMaterialByCode } from '@/api/modules/inventory'
import type { ProcessRouteVO } from '@/api/modules/process'
import { getSupplierCenterSnapshot } from '@/api/modules/supplier'
import { useUserStore } from '@/stores/user'
import request,{unwrapResponse} from '@/api/request'
import type {RouteNode} from '@/api/modules/masterData'

const props = withDefaults(defineProps<{
  modelValue: boolean
  routes: BomRoute[]
  processRoutes?: ProcessRouteVO[]
  loading?: boolean
  moneyReadable?: boolean
  moneyEditable?: boolean
}>(), {
  processRoutes: () => [],
  moneyReadable: true,
  moneyEditable: true
})
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'save', value: BomRoute[]): void
}>()

type EditableBomRoute = BomRoute & { selectedColorIds: number[] }
const user = useUserStore()
const canReadMoney = computed(() => props.moneyReadable && user.hasPermission('finance:read'))
const canWriteMoney = computed(() => props.moneyEditable && canReadMoney.value && user.hasPermission('finance:write'))

const routeDrafts = ref<EditableBomRoute[]>([emptyRoute()])
const activeRouteIndex = ref(0)
const routeDraft = computed<EditableBomRoute>(() => routeDrafts.value[activeRouteIndex.value] || routeDrafts.value[0] || emptyRoute())
const colorCodes = ref<CodeItem[]>([])
const lookupLoadingCodes = ref<Set<string>>(new Set())
const supplierOptions = ref<string[]>([])
const supplierLoading = ref(false)
const routeNodes=ref<RouteNode[]>([])
watch(()=>[props.modelValue,routeDraft.value.processId,routeDraft.value.originalHash],async([visible,id,original])=>{routeNodes.value=[];if(visible&&Number(id)>0&&original){try{const graph=unwrapResponse<{nodes:RouteNode[]}>(await request.get(`/process-routes/${id}/graph`));if(Number(id)===routeDraft.value.processId)routeNodes.value=graph.nodes}catch{/* Existing linear-only projects may have no source graph. */}}},{immediate:true})

function emptyRoute(): EditableBomRoute {
  return {
    processId: 0,
    routeCode: '',
    routeName: '',
    sharedBomGroupCode: '',
    routeVariantNo: 'BASE',
    variantName: '基础用料',
    colors: [],
    colorItems: [],
    selectedColorIds: [],
    items: []
  }
}

function cloneRoutes(routes: BomRoute[]) {
  if (!routes.length) return [emptyRoute()]
  return routes.map(source => ({
    ...(JSON.parse(JSON.stringify(source)) as BomRoute),
    items: source.items.map(normalizeItem),
    selectedColorIds: (source.colorItems || []).map(color => color.codeItemId)
  }))
}

function addVariant() {
  const source = routeDraft.value
  if (!source) return
  const variant = {
    ...(JSON.parse(JSON.stringify(source)) as BomRoute),
    productBomRouteId: undefined,
    sourceProductBomRouteId: source.productBomRouteId || null,
    routeVariantNo: `C${String(routeDrafts.value.length).padStart(2, '0')}`,
    variantName: `${source.variantName || '基础用料'}副本`,
    colors: [],
    colorItems: [],
    items: source.items.map(normalizeItem),
    selectedColorIds: []
  }
  routeDrafts.value.push(variant)
  activeRouteIndex.value = routeDrafts.value.length - 1
}

watch(() => props.modelValue, async (visible) => {
  if (visible) {
    routeDrafts.value = cloneRoutes(props.routes)
    activeRouteIndex.value = 0
    seedSupplierOptions(routeDrafts.value)
    await loadSupplierOptionsFromInventory()
  }
}, { immediate: true })

watch(() => props.processRoutes, () => {
  if (!routeDraft.value.processId && props.processRoutes.length) {
    applyProcessRoute(props.processRoutes[0].processId)
  }
})

function applyProcessRoute(processId: number) {
  const selected = props.processRoutes.find(route => route.processId === processId)
  if (!selected) return
  routeDraft.value.processId = selected.processId
  routeDraft.value.routeCode = selected.processCode
  routeDraft.value.routeName = selected.processName
}

function addItem() {
  routeDraft.value.items.push({
    lineNo: routeDraft.value.items.length + 1,
    itemCode: '',
    itemName: '',
    quantity: 0,
    quantityExact: '',
    unit: '',
    lossRate: 0,
    ...(canWriteMoney.value ? { unitCost: null, lineCost: null, currencyCode: null } : {}),
    supplierName: null,
    materialSource: 'inventory',
    unmatchedFlag: 0
  })
}

function removeItem(index: number) {
  routeDraft.value.items.splice(index, 1)
  resequenceItems()
}

function removeVariant() {
  if (routeDrafts.value.length <= 1) return
  routeDrafts.value.splice(activeRouteIndex.value, 1)
  activeRouteIndex.value = Math.max(0, Math.min(activeRouteIndex.value, routeDrafts.value.length - 1))
}

function resequenceItems() {
  routeDraft.value.items.forEach((item, index) => {
    item.lineNo = index + 1
  })
}

function normalizeItem(item: BomItem): BomItem {
  const unitCost = item.unitCost ?? 0
  const quantity = item.quantity ?? 0
  return {
    ...item,
    supplierName: item.supplierName || null,
    ...(canWriteMoney.value ? { currencyCode: item.currencyCode ?? null, lineCost: item.lineCost ?? null } : {}),
    materialSource: item.materialSource || (item.unmatchedFlag === 1 ? 'manual' : 'inventory'),
    unmatchedFlag: item.unmatchedFlag ?? (item.materialSource === 'manual' ? 1 : 0)
  }
}

function updateColors(ids: number[]) {
  routeDraft.value.colorItems = colorCodes.value
    .filter(color => ids.includes(color.codeItemId))
    .map(color => ({ codeItemId: color.codeItemId, codeValue: color.codeValue, codeName: color.codeName }))
  routeDraft.value.colors = routeDraft.value.colorItems.map(color => color.codeName)
}

function seedSupplierOptions(routes: BomRoute[]) {
  const values = new Set<string>()
  routes.forEach(route => route.items.forEach(item => {
    if (item.supplierName?.trim()) values.add(item.supplierName.trim())
  }))
  supplierOptions.value = Array.from(values)
}

function mergeSupplierOptions(values: Array<string | null | undefined>) {
  const suppliers = new Set(supplierOptions.value)
  values.forEach((value) => {
    const supplier = value?.trim()
    if (supplier) suppliers.add(supplier)
  })
  supplierOptions.value = Array.from(suppliers)
}

async function loadSupplierOptionsFromInventory() {
  supplierLoading.value = true
  try {
    const snapshot = await getSupplierCenterSnapshot()
    mergeSupplierOptions(
      snapshot.suppliers
        .filter((supplier) => supplier.status !== 'inactive')
        .map((supplier) => supplier.supplierName)
    )
  } catch (error) {
    console.warn('Inventory supplier snapshot load failed', error)
    ElMessage.warning('供应商资料加载失败，已保留当前 BOM 行供应商')
  } finally {
    supplierLoading.value = false
  }
}

function addSupplierOption(value?: string | null) {
  mergeSupplierOptions([value])
}

function roundCost(value: number) {
  return Number.isFinite(value) ? Number(value.toFixed(6)) : 0
}

function refreshLineCost(item: BomItem) {
  if (!canWriteMoney.value) return
  item.lineCost = item.unitCost==null?null:roundCost((item.quantity || 0) * item.unitCost)
}

async function lookupItem(item: BomItem) {
  const code = item.itemCode?.trim()
  if (!code) return
  const loadingKey = `${item.lineNo}-${code}`
  lookupLoadingCodes.value = new Set(lookupLoadingCodes.value).add(loadingKey)
  try {
    const result = await lookupMaterialByCode(code)
    if (result.matched) {
      item.inventoryId = result.inventoryId ?? null
      item.itemCode = result.inventoryCode || code
      item.itemName = result.inventoryName || item.itemName
      item.specification = result.specification || item.specification
      item.unit = result.unit || item.unit
      item.supplierName = result.supplierName || item.supplierName
      addSupplierOption(item.supplierName)
      if (canWriteMoney.value) {
        item.unitCost = result.unitCost ?? item.unitCost ?? 0
        item.currencyCode = result.currencyCode || item.currencyCode || 'CNY'
      }
      item.materialSource = 'inventory'
      item.unmatchedFlag = 0
      item.lookupMessage = ''
      refreshLineCost(item)
      return
    }
    item.inventoryId = null
    item.materialSource = 'manual'
    item.unmatchedFlag = 1
    item.lookupMessage = result.message || '物料编码未匹配到物料库，可先人工录入候选 BOM'
    ElMessage.warning(item.lookupMessage)
  } catch (error) {
    item.materialSource = 'manual'
    item.unmatchedFlag = 1
    item.lookupMessage = error instanceof Error ? error.message : '物料编码查询失败，可先人工录入'
    ElMessage.warning(item.lookupMessage)
  } finally {
    const next = new Set(lookupLoadingCodes.value)
    next.delete(loadingKey)
    lookupLoadingCodes.value = next
  }
}

function isLookupLoading(item: BomItem) {
  const code = item.itemCode?.trim()
  return Boolean(code && lookupLoadingCodes.value.has(`${item.lineNo}-${code}`))
}

function save() {
  if (routeDrafts.value.some(item => !item.processId || !item.colors.length)) {
    ElMessage.warning('请选择绑定工艺路线和适用颜色')
    return
  }
  if (routeDrafts.value.some(item => props.processRoutes.length
    && !props.processRoutes.some(process => process.processId === item.processId))) {
    ElMessage.warning('BOM 只能绑定当前产品下已有的工艺路线')
    return
  }
  if (routeDrafts.value.some(item => !item.items.length || item.items.some((bomItem) =>
    !bomItem.itemName.trim()
    || !bomItem.unit.trim()
    || Number(bomItem.quantityExact??bomItem.quantity) <= 0
    || (bomItem.quantityExact!=null&&!/^\d{1,16}(\.\d{1,8})?$/.test(bomItem.quantityExact))
    || (routeNodes.value.some(n=>n.nodeCode)&&!bomItem.nodeId)
    || (bomItem.lossRate || 0) < 0
    || (bomItem.lossRate || 0) > 1
    || (canWriteMoney.value && ((bomItem.unitCost || 0) < 0 || (bomItem.lineCost || 0) < 0))
  ))) {
    ElMessage.warning('请检查物料名称、单位、数量、损耗率、单价和单个成本')
    return
  }
  routeDrafts.value.forEach(item => item.items.forEach((bomItem, index) => { bomItem.lineNo = index + 1 }))
  emit('save', routeDrafts.value)
}

onMounted(async () => {
  colorCodes.value = await getEnabledColorCodes()
})
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="维护候选 BOM 明细"
    size="min(920px, 94vw)"
    @close="emit('update:modelValue', false)"
  >
    <section class="route-editor">
      <div class="variant-toolbar">
        <el-radio-group v-model="activeRouteIndex" size="small">
          <el-radio-button v-for="(draft, index) in routeDrafts" :key="draft.productBomRouteId || draft.routeVariantNo || index" :value="index">
            {{ draft.variantName || draft.routeVariantNo || `副本 ${index + 1}` }}
          </el-radio-button>
        </el-radio-group>
        <div class="variant-toolbar__actions">
          <el-button data-test="route-variant-copy" :icon="Plus" @click="addVariant">复制当前 BOM</el-button>
          <el-button v-if="routeDrafts.length > 1" :icon="Delete" @click="removeVariant">删除副本</el-button>
        </div>
      </div>
      <div class="route-editor__header">
        <label class="route-editor__field">
          <span>绑定工艺路线</span>
          <el-select
            v-model="routeDraft.processId"
            data-test="route-select"
            filterable
            placeholder="请选择绑定工艺路线"
            @change="applyProcessRoute"
          >
            <el-option
              v-for="route in processRoutes"
              :key="route.processId"
              :label="`${route.processCode} - ${route.processName}`"
              :value="route.processId"
            />
          </el-select>
        </label>
        <label class="route-editor__field">
          <span>副本名称</span>
          <el-input v-model="routeDraft.variantName" placeholder="例如：红色用料" />
        </label>
        <label class="route-editor__field">
          <span>路线编码</span>
          <el-input :model-value="routeDraft.routeCode" readonly />
        </label>
        <label class="route-editor__field">
          <span>路线名称</span>
          <el-input :model-value="routeDraft.routeName" readonly />
        </label>
      </div>
      <div class="route-editor__color-row">
        <label class="route-editor__field">
          <span>适用颜色</span>
          <el-select
            v-model="routeDraft.selectedColorIds"
            data-test="route-color-select"
            class="route-editor__color-select"
            multiple
            filterable
            clearable
            placeholder="请选择该 BOM 副本适用的颜色"
            popper-class="bom-color-select-popper"
            @change="updateColors"
          >
            <el-option
              v-for="color in colorCodes"
              :key="color.codeItemId"
              :label="`${color.codeValue} - ${color.codeName}`"
              :value="color.codeItemId"
            />
          </el-select>
        </label>
        <el-button data-test="route-item-add" :icon="Plus" @click="addItem">添加物料</el-button>
      </div>

      <p class="route-editor__hint">用量相对 BOM 基准产量。来源配方按款号和投料节点保存；同一物料在不同节点可分别用料。金额未知时留空，不默认币种。</p>
      <p v-if="routeDraft.articleCodes">明确适用 {{routeDraft.articleCodes.length}} 个制造品目；通用料仍受此范围约束。</p>
      <el-alert v-if="!canWriteMoney" :title="canReadMoney ? '内部金额只读；可保存用料与数量，金额由财务维护，不会被零值覆盖。' : '当前账号无内部金额查看与编辑权限；用料维护不提交价格或成本。'" type="info" :closable="false" />

      <div class="item-table-wrap">
        <table class="item-table">
          <thead>
            <tr>
              <th>NO</th>
              <th>物料编码</th>
              <th>物料名称</th>
              <th>规格</th>
              <th>数量</th>
              <th>单位</th>
              <th>供应商</th>
              <th v-if="canReadMoney">单价</th>
              <th v-if="canReadMoney">单个成本</th>
              <th>损耗率</th>
              <th>备注</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!routeDraft.items.length">
              <td :colspan="canReadMoney ? 12 : 10" class="item-table__empty">暂无物料明细</td>
            </tr>
            <tr v-for="(item, itemIndex) in routeDraft.items" :key="item.productBomItemId || itemIndex">
              <td data-label="行号" data-test="route-item-line-no" class="line-no-cell">{{ itemIndex + 1 }}</td>
              <td data-label="组件编码">
                <el-input v-model="item.itemCode" data-test="route-item-code" :disabled="isLookupLoading(item)" @blur="lookupItem(item)" />
                <el-tag v-if="item.unmatchedFlag === 1" class="manual-tag" type="warning" size="small">未匹配</el-tag>
              </td>
              <td data-label="组件名称／投料节点"><el-input v-model="item.itemName" data-test="route-item-name" /><small class="complete-value">{{item.itemName}}</small><el-select v-if="routeNodes.some(n=>n.nodeCode)" v-model="item.nodeId" placeholder="明确投料节点" filterable><el-option v-for="n in routeNodes" :key="n.id" :value="n.id" :label="`${n.stepNo} · ${n.name}`"/></el-select><small v-if="item.nodeCode">{{item.nodeCode}}</small></td>
              <td data-label="规格／适用范围"><el-input v-model="item.specification"/><small v-if="item.articleCodes==null">通用物料</small><small v-else>专用范围：{{item.articleCodes.join('、')||'空范围（无SKU适用）'}}</small></td>
              <td data-label="精确用量"><el-input :model-value="item.quantityExact??String(item.quantity)" data-test="route-item-quantity" inputmode="decimal" placeholder="精确用量，最多8位小数" @update:model-value="item.quantityExact=$event;item.quantity=Number($event);refreshLineCost(item)" /></td>
              <td data-label="单位"><el-input v-model="item.unit" data-test="route-item-unit" /></td>
              <td data-label="供应商">
                <el-select
                  v-model="item.supplierName"
                  data-test="route-item-supplier"
                  class="supplier-select"
                  filterable
                  clearable
                  :loading="supplierLoading"
                  placeholder="未选择"
                >
                  <el-option
                    v-for="supplier in supplierOptions"
                    :key="supplier"
                    :label="supplier"
                    :value="supplier"
                  />
                </el-select>
              </td>
              <td v-if="canReadMoney" data-label="原单价"><el-input-number v-model="item.unitCost" data-test="route-item-unit-cost" :disabled="!canWriteMoney" :controls="false" :min="0" :precision="4" @change="refreshLineCost(item)" /></td>
              <td v-if="canReadMoney" data-label="单个成本"><el-input-number v-model="item.lineCost" data-test="route-item-line-cost" :disabled="!canWriteMoney" :controls="false" :min="0" :precision="4" /></td>
              <td data-label="损耗率"><el-input-number v-model="item.lossRate" data-test="route-item-loss-rate" :controls="false" :min="0" :max="1" :precision="4" /></td>
              <td data-label="备注"><el-input v-model="item.remark" type="textarea" :rows="1" /></td>
              <td data-label="操作"><el-button :icon="Delete" circle title="移除物料" @click="removeItem(itemIndex)" /></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button data-test="route-editor-save" type="primary" :loading="loading" @click="save">保存路线与 BOM</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.route-editor { display: grid; gap: 10px;container-type:inline-size;min-width:0 }
.variant-toolbar, .variant-toolbar__actions { display: flex; align-items: center; gap: 8px; }
.variant-toolbar { justify-content: space-between; }
.route-editor__header {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
.route-editor__field { display: grid; min-width: 0; gap: 4px; color: var(--plm-color-text-secondary); font-size: 12px; }
.route-editor__field > span { line-height: 18px; }
.route-editor__color-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 8px; }
.route-editor__color-select { width: 100%; }
.route-editor__hint { margin: 0; color: var(--plm-color-text-secondary); font-size: 13px; }
.item-table-wrap { min-width:0; }
.item-table { width: 100%; border-collapse: collapse; table-layout: fixed; }
.item-table th, .item-table td { padding: 6px; border: 1px solid var(--el-border-color-lighter); text-align: left; }
.item-table th { color: var(--plm-color-text-secondary); background: var(--el-fill-color-light); font-size: 12px; font-weight: 600; }
.item-table th:nth-child(1) { width: 54px; }
.item-table th:nth-child(2), .item-table th:nth-child(4), .item-table th:nth-child(7) { width: 140px; }
.item-table th:nth-child(5), .item-table th:nth-child(8), .item-table th:nth-child(9), .item-table th:nth-child(10) { width: 120px; }
.item-table th:nth-child(6) { width: 90px; }
.item-table th:nth-child(11) { width: 150px; }
.item-table th:last-child { width: 52px; }
.line-no-cell { color: var(--plm-color-text-secondary); text-align: center !important; font-weight: 600; }
.item-table__empty { color: var(--plm-color-text-secondary); text-align: center !important; }
.manual-tag { margin-top: 4px; }
.supplier-select { width: 100%; }
.item-table :deep(.el-input-number) { width: 100%; }
.item-table :deep(.el-input-number .el-input__wrapper) { width: 100%; }
.item-table :deep(.el-input-number__decrease),
.item-table :deep(.el-input-number__increase) { display: none; }
.item-table :deep(.el-input-number .el-input__wrapper) { padding-left: 11px; padding-right: 11px; }
.item-table :deep(.el-input-number .el-input__inner) { text-align: left; }
:global(.bom-color-select-popper .el-select-dropdown__wrap) { max-height: 260px; }
.complete-value,.item-table small{display:block;font-size:12px;overflow-wrap:anywhere;white-space:pre-wrap}.item-table small{color:var(--el-text-color-secondary)}
@container(max-width:1500px){.item-table,.item-table tbody{display:block}.item-table thead{display:none}.item-table tr{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px;padding:8px 0;border-bottom:1px solid var(--el-border-color)}.item-table td{display:block;min-width:0;border:0;padding:3px;overflow-wrap:anywhere}.item-table td:before{content:attr(data-label);display:block;font-size:12px;color:var(--el-text-color-secondary);margin-bottom:4px}.item-table td:nth-child(3){grid-column:span 2}.item-table td:first-child{grid-column:1/-1;text-align:left!important;background:var(--el-fill-color-light)}}
@container(max-width:620px){.item-table tr{grid-template-columns:repeat(2,minmax(0,1fr))}.item-table td:nth-child(3){grid-column:1/-1}}
@media (max-width: 900px) {
  .route-editor__header { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 620px) {
  .route-editor__header, .route-editor__color-row { grid-template-columns: 1fr; }
}
</style>
