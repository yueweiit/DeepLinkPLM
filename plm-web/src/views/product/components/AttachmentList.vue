<script setup lang="ts">
import { t as uiText } from '@/locales'

import type { ProductAttachment } from '@/types/product'
import { formatDateTime } from '@/utils/format'

defineProps<{ rows: ProductAttachment[]; editable?: boolean }>()
</script>

<template>
  <el-table :data="rows" border stripe>
    <el-table-column prop="fileName" :label='uiText("文件名")' min-width="240" />
    <el-table-column prop="fileCategory" :label='uiText("类型")' width="140" />
    <el-table-column prop="versionNo" :label='uiText("版本")' width="90" />
    <el-table-column prop="uploadedBy" :label='uiText("上传人")' width="120" />
    <el-table-column :label='uiText("上传时间")' width="180">
      <template #default="{ row }">{{ formatDateTime(row.uploadedAt) }}</template>
    </el-table-column>
    <el-table-column :label='uiText("状态")' width="120">
      <template #default="{ row }"><el-tag effect="light">{{ row.status }}</el-tag></template>
    </el-table-column>
  </el-table>
</template>
