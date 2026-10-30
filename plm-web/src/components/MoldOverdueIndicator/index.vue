<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed } from 'vue'
import type { CollaborationRow } from '@/types/collaboration'
const props = defineProps<{ row: CollaborationRow }>()
const tasks = computed(() => (props.row.overdueTasks || []).filter(task => task.overdue))
</script>
<template>
  <el-popover v-if="tasks.length" trigger="click" placement="bottom" :width="330">
    <template #reference><button class="overdue-badge" :aria-label="uiText('查看 {value0} 逾期节点',{value0:(row.assetNumber)})" :title="uiText('{value0} 项已过计划期限，待完成或待确认',{value0:(tasks.length)})">{{ uiText("逾期") }}</button></template>
    <div class="overdue-details" :data-overdue-mold="row.moldSetId"><strong>{{ uiText("已保存计划 · ") }}{{ tasks.length }}{{ uiText(" 项逾期") }}</strong><p v-for="task in tasks" :key="task.taskKey"><span>{{ task.title }}</span><small>{{ uiText("计划 ") }}{{ task.dueDate }}<template v-if="task.overdueDays">{{ uiText(" · 逾期 ") }}{{ task.overdueDays }}{{ uiText(" 天") }}</template></small></p><p class="saved-note">{{ uiText("按已保存记录判断；历史漏填请核对，草稿保存后更新。") }}</p></div>
  </el-popover>
</template>
<style scoped>
.overdue-badge{border:1px solid #edcccc;background:#fff1f0;color:#ad4444;border-radius:3px;padding:1px 4px;font:inherit;font-size:12px;line-height:16px;white-space:nowrap;cursor:pointer}.overdue-details{font-size:12px;line-height:1.6;color:#52667f}.overdue-details p{margin:8px 0}.overdue-details span,.overdue-details small{display:block}.overdue-details small{font-size:12px;color:#ad4444}.overdue-details .saved-note{color:#7b8b9d;border-top:1px solid #e8edf3;padding-top:6px;margin-bottom:0}
</style>
