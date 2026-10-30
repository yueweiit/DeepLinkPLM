<script setup lang="ts">
import { t as uiText } from '@/locales'

import { Box } from '@element-plus/icons-vue'
import PartnerPhoto from './PartnerPhoto.vue'
import type { PartnerRow } from '@/types/partner'

defineProps<{ rows: PartnerRow[]; label: (value: unknown) => string; canWrite?: boolean; deleting?: string }>()
const emit = defineEmits<{ open: [row: PartnerRow]; delete: [row: PartnerRow] }>()

function suppliers(row: PartnerRow): string {
  const names = new Map<string, string>()
  for (const spec of row.specs || []) {
    for (const source of spec.sources || []) {
      const key = source.supplierCode || source.supplierName
      if (key && !names.has(key)) names.set(key, source.supplierName || source.supplierCode)
    }
  }
  return [...names.values()].join('、') || '待补供应商'
}

const listingNames: Record<string, string> = { listed: '已上架', unlisted: '已下架', unrecorded: '未记录' }
</script>

<template>
  <div class="product-catalog" :aria-label='uiText("合作产品目录")'>
    <div class="product-head" aria-hidden="true">
      <span>{{ uiText("产品 / 编号") }}</span><span>{{ uiText("规格") }}</span><span>{{ uiText("供货来源") }}</span><span>{{ uiText("业务状态") }}</span><span>{{ uiText("操作") }}</span>
    </div>
    <article v-for="row in rows" :key="row.id" class="product-row" :data-product-id="row.id">
      <div class="product-identity">
        <PartnerPhoto v-if="row.photoId" :id="row.photoId" />
        <span v-else class="product-placeholder" aria-hidden="true"><el-icon><Box /></el-icon></span>
        <div>
          <el-button link type="primary" class="product-name" @click="emit('open', row)">{{ row.name }}</el-button>
          <p class="secondary">{{ row.code }}<template v-if="row.version">{{ uiText(" · 资料 V") }}{{ row.version }}</template></p>
        </div>
      </div>
      <div class="product-spec-count">
        <span class="cell-label">{{ uiText("规格") }}</span>
        <el-button link type="primary" @click="emit('open', row)"><strong>{{ row.specs?.length || 0 }}</strong>{{ uiText(" 个规格") }}</el-button>
      </div>
      <div class="product-suppliers"><span class="cell-label">{{ uiText("供货来源") }}</span>{{ suppliers(row) }}</div>
      <div class="product-status">
        <div><span class="status-label">{{ uiText("选品") }}</span><el-tag size="small" :type="row.status === 'adopted' ? 'success' : 'info'">{{ uiText(label(row.status)) }}</el-tag></div>
        <div><span class="status-label">{{ uiText("网站") }}</span><span :class="{ 'is-listed': row.listing === 'listed' }">{{ listingNames[String(row.listing)] || uiText('未记录') }}</span></div>
      </div>
      <div class="product-actions"><el-button link type="primary" @click="emit('open', row)">{{ uiText("查看详情") }}</el-button><el-button v-if="canWrite" link type="danger" :disabled="Boolean(deleting)" @click="emit('delete', row)">{{ uiText("删除产品") }}</el-button></div>
    </article>
  </div>
</template>

<style scoped>
.product-catalog{container-type:inline-size;font-size:13px;min-width:0}
.product-head,.product-row{display:grid;grid-template-columns:minmax(0,1.5fr) 100px minmax(0,1fr) 120px 118px;align-items:center;gap:16px;padding:10px 16px}
.product-head{position:sticky;top:0;z-index:1;background:#f7f9fc;color:var(--el-text-color-secondary);font-size:12px;border-bottom:1px solid var(--el-border-color-lighter)}
.product-row{min-height:80px;border-bottom:1px solid var(--el-border-color-lighter)}
.product-row:last-child{border-bottom:0}
.product-row:hover{background:#fafcff}
.product-row>div,.product-identity>div{min-width:0;overflow-wrap:anywhere}
.product-identity{display:flex;align-items:center;gap:12px}
.product-identity :deep(.partner-photo),.product-placeholder{width:40px;height:40px;flex-shrink:0;border-radius:6px}
.product-placeholder{display:grid;place-items:center;background:#f0f4fa;color:#94a3b8;font-size:21px}
.product-name{font-size:14px;font-weight:600}
.secondary{font-size:12px;color:var(--el-text-color-secondary);margin:5px 0 0;line-height:1.5}
.product-spec-count strong{font-size:15px;margin-right:4px}
.product-status>div{display:flex;gap:7px;align-items:center;min-height:24px}
.status-label,.cell-label{font-size:12px;color:var(--el-text-color-secondary)}
.cell-label{display:none}
.is-listed{color:var(--el-color-success-dark-2)}
.product-actions{justify-self:end;display:flex;gap:8px;flex-wrap:wrap}.product-actions :deep(.el-button+.el-button){margin-left:0}
.product-catalog :deep(.el-button){white-space:normal;height:auto;line-height:1.6;text-align:left;overflow-wrap:anywhere;max-width:100%;padding:2px 0}
.product-catalog :deep(.el-button>span){min-width:0;white-space:normal;overflow-wrap:anywhere}
@container(max-width:850px){
 .product-head{display:none}
 .product-row{grid-template-columns:minmax(0,1.5fr) minmax(0,.6fr) 110px auto;gap:8px 12px;padding:12px}
 .product-suppliers{grid-column:1/-1;grid-row:2;font-size:12px;color:var(--el-text-color-regular);padding-left:52px}
 .product-suppliers .cell-label{display:inline;margin-right:8px}
}
@container(max-width:570px){
 .product-row{grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:8px 12px}
 .product-identity{grid-column:1/-1}
 .product-spec-count{padding-left:52px}
 .product-status{grid-column:2;grid-row:2/4}
 .product-suppliers{grid-column:1/-1;grid-row:4;padding-left:0}
 .product-actions{grid-column:1;grid-row:3;justify-self:start;padding-left:52px}
}
@container(max-width:300px){
 .product-row{grid-template-columns:minmax(0,1fr)}
 .product-status,.product-suppliers,.product-actions{grid-column:1;grid-row:auto;padding-left:0}
 .product-status{display:flex;gap:16px;flex-wrap:wrap}
 .product-spec-count{padding-left:0}
}
</style>
