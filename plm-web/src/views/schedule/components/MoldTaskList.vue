<script setup lang="ts">
import { computed, ref } from 'vue'
import { useResizeObserver } from '@vueuse/core'
import type { ScheduleTask } from '@/api/modules/schedule'
import { groupNames } from '@/utils/collaboration'
import type { FieldGroup } from '@/types/collaboration'
const props = withDefaults(defineProps<{ tasks: ScheduleTask[]; loading?: boolean; height?: number | string; selected?: string; total: number; page: number; size: number }>(), { loading: false, height: '100%' })
const emit = defineEmits<{ select: [task: ScheduleTask]; page: [page: number]; size: [size: number] }>()
const container=ref<HTMLElement>(),width=ref(1200),narrow=computed(()=>width.value<980)
useResizeObserver(container,entries=>{width.value=entries[0]?.contentRect.width||1200})
const key = (task: ScheduleTask) => `${task.moldSetId}:${task.taskKey}`
function status(task: ScheduleTask) { return task.overdue ? `延期 ${task.overdueDays || 1} 天` : task.category === 'review' ? '待核对' : task.category === 'missing' ? '待补填' : '待完成' }
function rowClass({ row }: { row: ScheduleTask }) { return key(row) === props.selected ? 'selected-task' : '' }
function select(row: ScheduleTask) { emit('select', row) }
</script>

<template>
  <div ref="container" class="mold-task-list">
    <div v-if="narrow" class="task-records" v-loading="loading" aria-label="待办列表">
      <article v-for="task in tasks" :key="key(task)" :data-task-id="key(task)" class="task-record" :class="{'selected-task':key(task)===selected}">
        <header><button class="task-link" @click="emit('select',task)">{{task.assetNumber}} · {{task.title}}</button><el-tag size="small" :type="task.overdue?'danger':'info'">{{status(task)}}</el-tag></header>
        <p>{{task.styleName}} · {{task.compatibleModels}}</p>
        <div class="record-meta"><span>{{groupNames[task.group as FieldGroup]||task.group}}</span><span :class="{'overdue-date':task.overdue}">{{task.dueDate||'无独立期限'}}</span><el-button size="small" link @click="emit('select',task)">处理</el-button></div>
      </article>
      <p v-if="!tasks.length&&!loading" class="empty-tasks">暂无符合条件的待办</p>
    </div>
    <el-table v-else class="compact-table" :data="tasks" :height="height" :row-key="key" border v-loading="loading" empty-text="暂无符合条件的待办" :row-class-name="rowClass" @row-click="select">
      <el-table-column label="实物编号" width="124" fixed="left"><template #default="{row}"><button class="task-link" :title="row.assetNumber" @click.stop="emit('select', row)">{{ row.assetNumber }}</button></template></el-table-column>
      <el-table-column prop="styleName" label="款式" width="88" />
      <el-table-column prop="compatibleModels" label="兼容型号" min-width="180" />
      <el-table-column prop="title" label="待完成事项" min-width="178" />
      <el-table-column label="职责" width="84"><template #default="{row}">{{ groupNames[row.group as FieldGroup] || row.group }}</template></el-table-column>
      <el-table-column label="期限" width="110"><template #default="{row}"><span :class="{'overdue-date':row.overdue}">{{ row.dueDate || '无独立期限' }}</span></template></el-table-column>
      <el-table-column label="状态" width="92" fixed="right"><template #default="{row}"><el-tag :type="row.overdue?'danger':'info'">{{ status(row) }}</el-tag></template></el-table-column>
    </el-table>
    <el-pagination :current-page="page" :page-size="size" :page-sizes="[20,50,100]" :total="total" :layout="narrow?'total,prev,pager,next':'total,sizes,prev,pager,next'" :pager-count="narrow?5:7" size="small" @update:current-page="emit('page',$event)" @update:page-size="emit('size',$event)" />
  </div>
</template>

<style scoped>
.mold-task-list{display:flex;flex-direction:column;min-height:0;min-width:0;height:100%;gap:6px}.mold-task-list :deep(.el-table){flex:1;min-height:0}.mold-task-list :deep(.cell){white-space:normal;overflow-wrap:anywhere;word-break:normal;line-height:18px}.mold-task-list :deep(.selected-task td),.selected-task{background:#edf4fc}.el-pagination{flex:none;min-height:28px;max-width:100%;flex-wrap:wrap}.task-link{border:0;padding:0;color:#376aaf;background:transparent;text-align:left;font:inherit;cursor:pointer;max-width:100%;white-space:normal;overflow-wrap:anywhere}.overdue-date{color:#b54444}.task-records{flex:1;min-height:0;min-width:0;overflow:auto}.task-record{padding:6px;border-bottom:1px solid #e2e9f1;font-size:12px;line-height:18px;overflow-wrap:anywhere}.task-record header,.record-meta{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.task-record header .task-link{flex:1;min-width:140px;font-weight:500}.task-record p{margin:3px 0;color:#637b94;white-space:normal}.record-meta .el-button{margin-left:auto}.empty-tasks{color:#7c8fa2;text-align:center;padding:15px}
</style>
