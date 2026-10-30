<script setup lang="ts">
import { t as uiText } from '@/locales'

import type { ProductVersionRecord } from '@/types/product'
import { formatAmount, formatDateTime } from '@/utils/format'

defineProps<{ rows: ProductVersionRecord[] }>()
</script>

<template>
  <el-table :data="rows" border stripe>
    <el-table-column prop="versionNo" :label='uiText("版本号")' width="100" />
    <el-table-column prop="bomVersion" :label='uiText("BOM 版本")' width="120" />
    <el-table-column :label='uiText("发布时间")' width="180">
      <template #default="{ row }">{{ formatDateTime(row.releasedAt) }}</template>
    </el-table-column>
    <el-table-column prop="releasedBy" :label='uiText("发布人")' width="120" />
    <el-table-column :label='uiText("预估成本")' width="130">
      <template #default="{ row }">{{ formatAmount(row.estimatedCost) }}</template>
    </el-table-column>
    <el-table-column :label='uiText("实际成本")' width="130">
      <template #default="{ row }">{{ formatAmount(row.actualCost ?? null) }}</template>
    </el-table-column>
    <el-table-column prop="changeSummary" :label='uiText("变更说明")' min-width="260" />
  </el-table>
</template>
