<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, ref, watch } from 'vue'
import { usePagination } from '@/composables/usePagination'
import type { BusinessData } from '@/types/partner'

const props = defineProps<{
  productId: number
  specs: BusinessData[]
  canWrite: boolean
  canReadMoney: boolean
  deleting?: string
  formatMoney: (value: unknown, currency?: string) => string
}>()
const emit = defineEmits<{ supplier: [code: string]; sample: [spec: BusinessData]; quote: [spec: BusinessData]; delete: [spec: BusinessData] }>()
const keyword = ref('')
const { currentPage, pageSize, offset, resetPage, getPagedRows } = usePagination(20)
const filtered = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase()
  if (!query) return props.specs
  return props.specs.filter(spec => [
    spec.name, spec.code, spec.specification, spec.websiteSku,
    ...(spec.phones || []).flatMap((phone: BusinessData) => [phone.brandName, phone.modelName, phone.displayName, phone.phoneModelCode]),
    ...(spec.sources || []).flatMap((source: BusinessData) => [source.supplierName, source.supplierCode, source.supplierItemCode])
  ].some(value => value != null && String(value).toLocaleLowerCase().includes(query)))
})
const rows = computed(() => getPagedRows(filtered.value))
const showActions = computed(() => props.canWrite)
watch([keyword, pageSize], resetPage, { flush: 'sync' })
watch(() => props.productId, () => { keyword.value = ''; resetPage() })
watch(() => filtered.value.length, total => {
  currentPage.value = Math.min(currentPage.value, Math.max(1, Math.ceil(total / pageSize.value)))
})
</script>

<template>
  <section class="product-specs" :aria-label='uiText("产品规格与供货来源")'>
    <header class="spec-toolbar">
      <div><h2>{{ uiText("规格与供货") }}</h2><span class="secondary">{{ uiText("共 ") }}{{ specs.length }}{{ uiText(" 个规格") }}<template v-if="keyword.trim()">{{ uiText(" · 匹配 ") }}{{ filtered.length }}{{ uiText(" 个") }}</template></span></div>
      <el-input v-model="keyword" clearable :placeholder='uiText("搜索型号、SKU、编码或供方")' :aria-label='uiText("搜索产品规格")' />
    </header>
    <div class="spec-scroll" tabindex="0" :aria-label='uiText("当前页规格列表")' :class="{ 'has-actions': showActions }">
      <div class="spec-head" aria-hidden="true"><span>{{ uiText("型号 / 规格") }}</span><span>{{ uiText("规格编号 / 网站 SKU") }}</span><span>{{ uiText("供货来源") }}</span><span v-if="showActions">{{ uiText("办理") }}</span></div>
      <article v-for="spec in rows" :key="spec.id" class="spec-row" :data-spec-id="spec.id">
        <div><span class="cell-label">{{ uiText("型号 / 规格") }}</span><strong>{{ spec.name || spec.specification || uiText('规格待补') }}</strong></div>
        <div class="spec-codes"><span><span class="cell-label">{{ uiText("编号：") }}</span>{{ spec.code || uiText('规格编号未记录') }}</span><small>{{ uiText("网站 SKU：") }}{{ spec.websiteSku || uiText('待补') }}</small></div>
        <div class="spec-sources">
          <span class="cell-label">{{ uiText("供方：") }}</span>
          <div v-for="(source, index) in spec.sources || []" :key="source.supplierCode || index" class="spec-source">
            <el-button v-if="source.supplierCode" link type="primary" @click="emit('supplier', source.supplierCode)">{{ source.supplierName || source.supplierCode }}</el-button>
            <span v-else>{{ source.supplierName || uiText('供方待补') }}</span>
            <small>{{ uiText("货号：") }}{{ source.supplierItemCode || uiText('待补') }}</small>
            <small v-if="canReadMoney">{{ uiText("参考：") }}{{ formatMoney(source.priceCents, source.currency) }}</small>
          </div>
          <span v-if="!spec.sources?.length" class="secondary">{{ uiText("待补供应商") }}</span>
        </div>
        <div v-if="showActions" class="spec-actions">
          <template v-if="spec.sources?.length"><el-button size="small" @click="emit('sample', spec)">{{ uiText("登记收样") }}</el-button><el-button size="small" @click="emit('quote', spec)">{{ uiText("供方询报价") }}</el-button></template>
          <el-button link type="danger" :disabled="Boolean(deleting)" @click="emit('delete', spec)">{{ uiText("删除规格") }}</el-button>
        </div>
      </article>
      <el-empty v-if="!filtered.length" :description="specs.length ? uiText('没有匹配的规格，请更换关键词') : uiText('可先保存名称，需要时再补规格与供方')"><el-button v-if="keyword" @click="keyword = ''">{{ uiText("清除搜索") }}</el-button></el-empty>
    </div>
    <footer class="spec-pager">
      <span class="secondary" aria-live="polite">{{ filtered.length ? `${offset + 1}–${Math.min(offset + pageSize, filtered.length)}` : '0' }} / {{ filtered.length }}{{ uiText(" 个规格 · 第 ") }}{{ currentPage }} / {{ Math.max(1, Math.ceil(filtered.length / pageSize)) }}{{ uiText(" 页") }}</span>
      <div class="pager-controls">
        <el-select v-model="pageSize" :aria-label='uiText("每页规格数")'><el-option :value="20" :label='uiText("20 条 / 页")'/><el-option :value="50" :label='uiText("50 条 / 页")'/></el-select>
        <el-pagination v-model:current-page="currentPage" :page-size="pageSize" :total="filtered.length" :pager-count="5" layout="prev, pager, next" />
      </div>
    </footer>
  </section>
</template>

<style scoped>
.product-specs{display:flex;flex-direction:column;flex:1;min-height:0;min-width:0;border:1px solid var(--el-border-color-lighter);border-radius:8px;background:var(--el-bg-color);container-type:inline-size;font-size:13px}
.spec-toolbar{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:6px 10px;flex-shrink:0;border-bottom:1px solid var(--el-border-color-lighter)}
.spec-toolbar>div:first-child{display:flex;align-items:center;gap:10px;flex-wrap:wrap;min-width:0}
h2{margin:0;font-size:14px;font-weight:600;white-space:nowrap}
.spec-toolbar>.el-input{width:320px;max-width:50%;min-width:150px}
.secondary,small{font-size:12px;color:var(--el-text-color-secondary);line-height:1.5}
.spec-scroll{flex:1;min-height:0;overflow:auto;overscroll-behavior:contain}
.spec-head,.spec-row{display:grid;grid-template-columns:minmax(0,1.8fr) minmax(0,1fr) minmax(0,1fr);gap:10px;padding:5px 10px}
.has-actions .spec-head,.has-actions .spec-row{grid-template-columns:minmax(0,1.8fr) minmax(0,1fr) minmax(0,.9fr) 190px}
.spec-head{position:sticky;top:0;z-index:1;background:#f7f9fc;color:var(--el-text-color-secondary);font-size:12px}
.spec-row{border-bottom:1px solid var(--el-border-color-lighter);line-height:1.6;align-items:start}
.spec-row:last-child{border-bottom:0}
.spec-row>div{min-width:0;overflow-wrap:anywhere}
.spec-row strong{font-weight:500}
.spec-codes{display:flex;flex-wrap:wrap;gap:2px 10px;align-items:baseline}
small{display:block}
.spec-source+.spec-source{margin-top:7px}
.spec-actions{display:flex;align-items:center;gap:4px;flex-wrap:wrap}
.spec-actions :deep(.el-button+.el-button){margin-left:0}
.spec-actions :deep(.el-button.is-link){padding-inline:0;white-space:nowrap}
.spec-actions :deep(.el-button.is-link>span){white-space:nowrap}
.product-specs :deep(.el-button){white-space:normal;height:auto;line-height:1.5;max-width:100%;text-align:left}
.product-specs :deep(.el-button>span){min-width:0;overflow-wrap:anywhere;white-space:normal}
.cell-label{display:none}
.spec-pager{display:flex;justify-content:space-between;align-items:center;gap:6px;flex-wrap:wrap;padding:5px 10px;border-top:1px solid var(--el-border-color-lighter);flex-shrink:0}
.pager-controls{display:flex;align-items:center;gap:8px;min-width:0;flex-wrap:wrap}
.pager-controls>.el-select{width:112px}
@container(max-width:900px){
 .has-actions .spec-head,.has-actions .spec-row{grid-template-columns:minmax(0,1.8fr) minmax(0,1.1fr) minmax(0,.8fr) 68px}
 .spec-actions{flex-direction:column;align-items:flex-start}
}
@container(max-width:640px){
 .spec-head{display:none}
 .spec-row,.has-actions .spec-row{grid-template-columns:minmax(0,1fr) minmax(0,.6fr) 62px;gap:3px 8px;padding:5px 8px}
 .spec-row>div:first-child{grid-column:1/-1}
 .cell-label{display:inline;font-size:12px;color:var(--el-text-color-secondary)}
 .spec-sources .spec-source{display:inline-block;vertical-align:top}
 .spec-sources .spec-source+.spec-source{display:block}
 .spec-row>div:first-child .cell-label{display:none}
 .spec-actions{flex-direction:row;flex-wrap:wrap}
 .spec-toolbar{flex-wrap:wrap}
 .spec-toolbar>.el-input{width:100%;max-width:100%;min-width:0}
 .spec-pager{gap:6px}
}
@container(max-width:360px){
 .spec-row,.has-actions .spec-row{grid-template-columns:minmax(0,1fr)}
 .spec-pager{padding:8px}
 .pager-controls{width:100%;gap:4px;justify-content:space-between}
 .pager-controls>.el-select{width:100px}
 .spec-pager :deep(.el-pager){display:none}
}
@container(max-width:460px){.spec-pager :deep(.el-pager){display:none}.pager-controls>.el-select{width:100px}.pager-controls{gap:4px}}
</style>
