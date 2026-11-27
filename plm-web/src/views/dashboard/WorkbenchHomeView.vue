<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { t } from '@/locales'
import { useUserStore } from '@/stores/user'
import { homeCount, type HomeItem, type HomeSection, type HomeSource, type SourceState } from '@/api/modules/workbenchHome'
import { useWorkbenchHome } from './useWorkbenchHome'
import { workbenchHomeRoute } from './workbenchHomeRoutes'

defineOptions({ name: 'WorkbenchHomeView' })
const user = useUserStore(), router = useRouter()
const { section, source, page, size, scope, keyword, summary, result, loading, summaryLoading, error, summaryError, refresh, select, selectSource } = useWorkbenchHome()
const sections: { key: HomeSection; label: string; detail: string }[] = [
  { key: 'tasks', label: 'home.tasks', detail: 'home.tasksCoverage' },
  { key: 'approvals', label: 'home.approvals', detail: 'home.approvalsCoverage' },
  { key: 'projects', label: 'home.projects', detail: 'home.projectsCoverage' },
  { key: 'exceptions', label: 'home.exceptions', detail: 'home.exceptionsCoverage' }
]
const choices = computed<HomeSource[]>(() => section.value === 'approvals' ? ['generic', 'mold_application'] : section.value === 'exceptions' ? ['schedule', 'research', 'mold_application'] : [source.value])
const count = (key: HomeSection) => homeCount(summary.value?.[key])
const sourceText = (value: HomeSource) => t(`home.source.${value}`)
const stateText = (value: SourceState) => t(`home.state.${value}`)
const approvalText = (value?: string) => value ? t(`home.approval.${value}`) : t('home.unknown')
const shortcuts = computed(() => [
  { label: 'home.researchEntry', path: '/product-research', permission: 'project:view' },
  { label: '项目中心', path: '/projects?tab=in_progress', permission: 'project:view' },
  { label: '开模时间管控', path: '/mold-schedules', permission: 'schedule:read' },
  { label: '文件中心', path: '/files', permission: 'file:read' }
].filter(item => user.hasPermission(item.permission)))
function open(item: HomeItem) { const route = workbenchHomeRoute(item); if (route) void router.push(route) }
function search() { page.value = 1 }
</script>

<template>
  <section class="home-page">
    <header class="home-header"><div><h2>{{ t('home.title') }}</h2><p>{{ t('home.subtitle') }}</p></div>
      <el-button :loading="loading || summaryLoading" @click="refresh">{{ t('刷新') }}</el-button>
    </header>
    <nav class="home-shortcuts" :aria-label="t('home.shortcuts')"><el-button v-for="item in shortcuts" :key="item.path" link @click="router.push(item.path)">{{ t(item.label) }}</el-button></nav>
    <el-alert v-if="summaryError" :title="t('home.summaryFailed')" type="error" :closable="false" />
    <div class="home-cards">
      <button v-for="card in sections" :key="card.key" :data-card="card.key" :class="['home-card', { selected: section === card.key }]" :aria-pressed="section === card.key" @click="select(card.key)">
        <span>{{ t(card.label) }}</span><strong>{{ summaryLoading ? '…' : count(card.key).total ?? '—' }}</strong>
        <small>{{ t(card.detail) }}</small><small v-if="!summaryLoading && count(card.key).partial">{{ t('home.partial') }}</small>
      </button>
    </div>
    <section class="home-list">
      <div class="home-toolbar">
        <h3>{{ t(sections.find(s => s.key === section)!.label) }}</h3>
        <el-radio-group v-if="choices.length > 1" :model-value="source" @update:model-value="selectSource($event as HomeSource)"><el-radio-button v-for="choice in choices" :key="choice" :value="choice">{{ sourceText(choice) }}</el-radio-button></el-radio-group>
        <el-select v-if="section === 'projects'" v-model="scope" class="scope-select" @change="search"><el-option value="mine" :label="t('home.mine')" /><el-option value="visible" :label="t('home.visible')" /></el-select>
        <el-input v-if="source === 'research' || source === 'schedule'" v-model="keyword" class="home-search" clearable :placeholder="t('home.search')" @change="search" />
      </div>
      <div class="home-source-status">
        <span>{{ sourceText(source) }}</span><span v-if="result">{{ stateText(result.sourceState) }}</span>
        <span v-if="result?.totalElements != null">{{ t('home.sourceTotal', { count: result.totalElements }) }}</span>
        <span v-if="result?.readAt">{{ t('home.readAt') }} {{ result.readAt }}</span>
      </div>
      <p v-if="source === 'mold_application'" class="home-note">{{ t('home.snapshotNote') }} {{ result?.sourceUpdatedAt || t('home.syncUnknown') }}</p>
      <p v-if="section === 'projects'" class="home-note">{{ t('home.readinessNote') }}</p>
      <el-alert v-if="error || result?.sourceState === 'error'" :title="t('home.loadFailed')" type="error" :closable="false" />
      <el-alert v-else-if="result && result.sourceState !== 'ok'" :title="stateText(result.sourceState)" :type="result.sourceState === 'stale' ? 'warning' : 'info'" :closable="false" />
      <el-table v-loading="loading" :data="result?.content || []" row-key="key" :empty-text="loading ? t('加载中') : result?.sourceState === 'ok' ? t('home.empty') : t('home.noReliableData')" stripe>
        <el-table-column :label="t('home.item')" min-width="230"><template #default="{ row }"><div class="item-title">{{ row.title }}</div><small>{{ row.code || row.projectName || row.businessId }}</small></template></el-table-column>
        <el-table-column v-if="source === 'research'" :label="t('home.stage')" min-width="145"><template #default="{ row }">{{ row.revision ? t('home.revision') : '' }} {{ row.stage }}/8 · {{ row.stageName }}</template></el-table-column>
        <el-table-column v-if="source === 'research' || source === 'schedule'" :label="t('负责人')" min-width="160"><template #default="{ row }">{{ t(row.ownerName || 'home.unassigned') }}</template></el-table-column>
        <el-table-column v-if="source === 'mold_application'" :label="t('home.approvalState')" min-width="165"><template #default="{ row }"><div>{{ approvalText(row.approvalFlowState || row.businessStatus) }}</div><el-tag v-if="row.approvalGranted" size="small" type="success">{{ t('home.authorized') }}</el-tag></template></el-table-column>
        <el-table-column :label="t('home.action')" min-width="230"><template #default="{ row }"><div>{{ t(row.nextAction || 'home.viewSource') }}</div><small v-if="row.blocker" class="blocker">{{ t(row.blocker) }}</small></template></el-table-column>
        <el-table-column v-if="source === 'research' || source === 'schedule'" :label="t(source === 'research' ? 'home.projectDue' : 'home.due')" min-width="130"><template #default="{ row }"><span :class="{ blocker: row.overdue }">{{ row.dueDate || t('home.noDue') }}</span></template></el-table-column>
        <el-table-column v-if="source === 'mold_application'" :label="t('home.sourceUpdated')" min-width="170"><template #default="{ row }">{{ row.sourceUpdatedAt || t('home.syncUnknown') }}</template></el-table-column>
        <el-table-column :label="t('操作')" width="110" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="!workbenchHomeRoute(row)" @click="open(row)">{{ t('home.go') }}</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-if="result?.totalElements != null" v-model:current-page="page" v-model:page-size="size" :page-sizes="[20, 50, 100]" :total="result.totalElements" layout="total, sizes, prev, pager, next" @size-change="page = 1" />
    </section>
  </section>
</template>

<style scoped>
.home-page{display:flex;flex-direction:column;gap:16px;padding:4px;min-width:0}.home-header{display:flex;align-items:center;justify-content:space-between;gap:16px}.home-header h2{margin:0;font-size:22px}.home-header p{margin:6px 0 0;color:#64748b;font-size:13px}.home-shortcuts{display:flex;gap:18px;flex-wrap:wrap}.home-cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.home-card{display:flex;flex-direction:column;align-items:flex-start;gap:8px;padding:18px;border:1px solid #dfe6ef;border-radius:10px;background:white;color:#334155;text-align:left;cursor:pointer}.home-card.selected{border-color:#3b82f6;background:#eff6ff}.home-card strong{font-size:30px;color:#0f172a}.home-card small,.home-note,.home-source-status,td small{font-size:12px;color:#64748b}.home-card:focus-visible{outline:2px solid #2563eb;outline-offset:2px}.home-list{min-width:0;padding:16px;background:white;border:1px solid #e2e8f0;border-radius:10px}.home-toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.home-toolbar h3{margin:0 auto 0 0;font-size:16px}.scope-select{width:180px}.home-search{width:230px}.home-source-status{display:flex;gap:14px;flex-wrap:wrap;margin:12px 0}.home-note{line-height:1.7}.item-title{font-weight:600}.blocker{color:#b45309}.el-pagination{margin-top:16px;flex-wrap:wrap}@media(max-width:1000px){.home-cards{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.home-cards{grid-template-columns:1fr}.home-toolbar{align-items:stretch}.home-search,.scope-select{width:100%}}
</style>
