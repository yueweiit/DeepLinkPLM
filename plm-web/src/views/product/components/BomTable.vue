<script setup lang="ts">
import { t as uiText } from '@/locales'

import type { ProductBomItem } from '@/types/product'
import { formatAmount } from '@/utils/format'

defineProps<{ rows: ProductBomItem[]; canViewCost?: boolean }>()
</script>

<template>
  <el-table :data="rows" border stripe>
    <el-table-column prop="inventoryCode" :label='uiText("物料编码")' min-width="180" />
    <el-table-column prop="inventoryName" :label='uiText("物料名称")' min-width="220" />
    <el-table-column prop="quantity" :label='uiText("用量")' width="100" />
    <el-table-column prop="stockUom" :label='uiText("单位")' width="90" />
    <el-table-column prop="supplierName" :label='uiText("主供应来源")' min-width="180" />
    <el-table-column v-if="canViewCost" :label='uiText("单价")' width="120">
      <template #default="{ row }">{{ formatAmount(row.unitCost) }}</template>
    </el-table-column>
  </el-table>
</template>
