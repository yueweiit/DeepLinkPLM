<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getApprovalTasks } from '@/api/modules/approval'
import { getScheduleTasks, type ScheduleTask } from '@/api/modules/schedule'
import { useCachedRequest } from '@/composables/useCachedRequest'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'
import { useUserStore } from '@/stores/user'
import FixedTableViewport from '@/components/FixedTableViewport/index.vue'
import MoldTaskList from '@/views/schedule/components/MoldTaskList.vue'
import { moldScheduleTaskRoute } from '@/utils/collaboration'
import PartnerTaskPanel from '@/views/partner/operations/PartnerTaskPanel.vue'

defineOptions({ name: 'MyTasksView' })
const router = useRouter(), user = useUserStore(), workspace = useCollaborationWorkspace(), page = ref(1), size = ref(50), approvals = ref<unknown[]>([]), approvalError = ref('')
const partnerTasks=ref<InstanceType<typeof PartnerTaskPanel>>()
const request = useCachedRequest(getScheduleTasks), tasks = computed(() => request.hasCurrentData.value ? request.data.value?.content || [] : []), total = computed(() => request.data.value?.totalElements || 0)
let active = true, mounted = false, generation = 0
async function load(force = false) { if (user.token && user.hasPermission('schedule:read')) await request.execute({ page: page.value, size: size.value, mineOnly: true, category: 'all' }, force) }
async function refresh() { await load(true); const ticket = ++generation; approvalError.value = ''; if (user.hasPermission('approval:view')) try { const result = await getApprovalTasks(); if (ticket === generation && user.token) approvals.value = result } catch { if (ticket === generation) approvalError.value = '审批待办查询失败' } }
function open(task: ScheduleTask) { void router.push(moldScheduleTaskRoute({ moldSetId: task.moldSetId, taskKey: task.taskKey })) }
watch(size, () => { page.value = 1; void load() })
watch(() => workspace.revision, () => { request.clear(); if (active && mounted) void load() }, { flush: 'sync' })
watch(() => user.token, () => { generation++; request.clear(); request.data.value = undefined; request.hasCurrentData.value = false; approvals.value = []; approvalError.value = '' }, { flush: 'sync' })
onMounted(async () => { await refresh(); mounted = true }); onActivated(() => { active = true; if (mounted) void load() }); onDeactivated(() => { active = false; request.invalidate() }); onBeforeUnmount(() => generation++)
</script>

<template>
  <section class="tasks-page compact-ledger">
    <header><h2>{{ uiText("我的待办") }}</h2><span>{{ uiText("按真实账号、负责人及职责分配；未保存草稿不会完成任务") }}</span><el-button size="small" @click="refresh();partnerTasks?.refresh()">{{ uiText("刷新") }}</el-button></header>
    <el-alert v-if="request.error.value || approvalError" :title="request.error.value || approvalError" type="error" :closable="false" />
    <PartnerTaskPanel v-if="user.hasPermission('partner:view')" ref="partnerTasks"/>
    <FixedTableViewport v-if="user.hasPermission('schedule:read')" fill><template #default="{tableHeight}"><MoldTaskList :tasks="tasks" :loading="request.loading.value" :height="Math.max(100,tableHeight-40)" :total="total" :page="page" :size="size" @select="open" @page="page=$event;load()" @size="size=$event" /></template></FixedTableViewport>
    <details v-if="user.hasPermission('approval:view')"><summary>{{ uiText("流程审批待办 ") }}{{ approvals.length }}</summary><pre>{{ JSON.stringify(approvals,null,2) }}</pre></details>
  </section>
</template>

<style scoped>
.tasks-page{display:flex;flex-direction:column;gap:6px;width:100%;min-width:0;height:100%;min-height:0}header{display:flex;align-items:center;gap:6px;min-height:28px;flex-wrap:wrap}header>span{flex:1;min-width:160px;color:#76879d;font-size:12px}h2{margin:0;font-size:16px;line-height:22px}pre{white-space:pre-wrap}details{flex:none;font-size:12px}summary{cursor:pointer;color:#5a7390}
</style>
