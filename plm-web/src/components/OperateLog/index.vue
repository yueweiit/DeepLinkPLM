<script setup lang="ts">
import { t as uiText } from '@/locales'

import type { OperateLogEntry } from '@/types/common'
import { formatDateTime } from '@/utils/format'

defineProps<{
  logs: OperateLogEntry[]
}>()
</script>

<template>
  <el-empty v-if="!logs.length" :description='uiText("暂无操作日志")' />
  <el-table v-else :data="logs" border stripe>
    <el-table-column :label='uiText("时间")' min-width="170">
      <template #default="{ row }">{{ formatDateTime(row.time) }}</template>
    </el-table-column>
    <el-table-column prop="operator" :label='uiText("操作人")' width="140" />
    <el-table-column prop="action" :label='uiText("操作内容")' min-width="260" />
    <el-table-column :label='uiText("级别")' width="100">
      <template #default="{ row }">
        <el-tag :type="row.level === 'danger' ? 'danger' : 'info'" effect="light">
          {{ row.level === 'danger' ? uiText('高敏') : uiText('普通') }}
        </el-tag>
      </template>
    </el-table-column>
  </el-table>
</template>
