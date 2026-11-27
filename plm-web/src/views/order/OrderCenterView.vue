<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useMediaQuery } from '@vueuse/core'
import { Filter, Search } from '@element-plus/icons-vue'
import { getOrders, type OrderStatus, type OrderVO } from '@/api/modules/order'
import FixedTableViewport from '@/components/FixedTableViewport/index.vue'
import PageContainer from '@/components/PageContainer/index.vue'

const filters = reactive({ progress: 'all', source: '', keyword: '' })
const mobileFiltersExpanded = ref(false)
const rows = ref<OrderVO[]>([])
const loading = ref(false)
const phoneLayout = useMediaQuery('(max-width: 900px)')
const filteredRows = computed(() => rows.value.filter(row => filters.progress === 'all' || (filters.progress === 'in_progress' ? ['draft', 'confirmed', 'in_production'].includes(row.status) : ['completed', 'closed'].includes(row.status))))
const statusNames: Record<OrderStatus, string> = { draft: '草稿', confirmed: '已确认', in_production: '进行中', completed: '已完成', closed: '已关闭' }
const typeNames = { customer_requirement: '客户订单', market_requirement: '市场需求' }
async function load() { loading.value = true; try { rows.value = (await getOrders({ page: 1, size: 100, keyword: filters.keyword || undefined, source: filters.source || undefined })).content } finally { loading.value = false } }
watch(() => [filters.source, filters.keyword], load)
onMounted(load)
</script>

<template>
  <PageContainer :title='uiText("需求订单")'>
    <section class="page-panel order-toolbar order-toolbar--desktop">
      <el-select v-model="filters.progress" class="order-toolbar__select"><el-option :label='uiText("全部")' value="all" /><el-option :label='uiText("进行中订单")' value="in_progress" /><el-option :label='uiText("历史订单")' value="history" /></el-select>
      <el-select v-model="filters.source" clearable class="order-toolbar__select" :placeholder='uiText("订单类型")'><el-option :label='uiText("客户订单")' value="customer" /><el-option :label='uiText("市场需求")' value="market" /></el-select>
      <el-input v-model="filters.keyword" clearable class="order-toolbar__search" :placeholder='uiText("搜索钉钉审批编号 / 订单号 / 手机型号 / 产品 / 类型")' />
    </section>
    <section class="page-panel order-toolbar order-toolbar--mobile">
      <el-input v-model="filters.keyword" clearable :placeholder='uiText("搜索订单 / 手机型号 / 产品")' @keyup.enter="load" />
      <el-button type="primary" :icon="Search" :aria-label='uiText("查询")' :title='uiText("查询")' @click="load" />
      <el-button :type="mobileFiltersExpanded || filters.progress !== 'all' || !!filters.source ? 'primary' : 'default'" :icon="Filter" :aria-label='uiText("更多筛选")' :title='uiText("更多筛选")' :aria-expanded="mobileFiltersExpanded" @click="mobileFiltersExpanded=!mobileFiltersExpanded" />
      <div v-if="mobileFiltersExpanded" class="order-toolbar__advanced"><el-select v-model="filters.progress" :aria-label='uiText("订单进度")'><el-option :label='uiText("全部")' value="all" /><el-option :label='uiText("进行中订单")' value="in_progress" /><el-option :label='uiText("历史订单")' value="history" /></el-select><el-select v-model="filters.source" clearable :placeholder='uiText("订单类型")'><el-option :label='uiText("客户订单")' value="customer" /><el-option :label='uiText("市场需求")' value="market" /></el-select></div>
    </section>
    <section class="page-panel">
      <div class="toolbar-row"><div><h3 class="section-title">{{ uiText("订单列表") }}</h3><p class="page-panel-desc">{{ uiText("项目状态与需求订单状态同步更新。") }}</p></div><el-tag>{{ filteredRows.length }}{{ uiText(" 条") }}</el-tag></div>
      <FixedTableViewport v-if="!phoneLayout" v-slot="{ tableHeight }" :refresh-key="filteredRows">
      <el-table v-loading="loading" :data="filteredRows" :height="tableHeight" border stripe size="small" :empty-text='uiText("当前筛选条件下没有需求订单")'>
        <el-table-column prop="dingTalkApprovalNo" :label='uiText("钉钉审批编号")' min-width="170" />
        <el-table-column prop="orderCode" :label='uiText("订单号")' min-width="170" />
        <el-table-column :label='uiText("手机型号")' min-width="140"><template #default="{ row }">{{ row.projectType === 'product_line' ? '--' : (row.phoneModel || '--') }}</template></el-table-column>
        <el-table-column :label='uiText("类型")' width="120"><template #default="{ row }">{{ typeNames[row.orderType as keyof typeof typeNames] || row.orderType }}</template></el-table-column>
        <el-table-column prop="productName" :label='uiText("关联产品")' min-width="180" />
        <el-table-column :label='uiText("状态")' width="110"><template #default="{ row }"><el-tag>{{ statusNames[row.status as OrderStatus] }}</el-tag></template></el-table-column>
        <el-table-column prop="createdAt" :label='uiText("时间")' min-width="150" />
        <el-table-column :label='uiText("操作")' width="90" fixed="right"><template #default><el-button link type="primary">{{ uiText("更多") }}</el-button></template></el-table-column>
      </el-table>
      </FixedTableViewport>
      <div v-else v-loading="loading" class="order-cards">
        <el-empty v-if="!loading && !filteredRows.length" :description='uiText("当前筛选条件下没有需求订单")' />
        <article v-for="row in filteredRows" :key="row.orderId" class="order-card">
          <header>
            <strong>{{ row.orderCode || row.dingTalkApprovalNo }}</strong>
            <el-tag size="small">{{ statusNames[row.status] }}</el-tag>
          </header>
          <p class="order-card__product">{{ row.productName }}</p>
          <dl>
            <div><dt>{{ uiText("手机型号") }}</dt><dd>{{ row.projectType === 'product_line' ? '--' : (row.phoneModel || '--') }}</dd></div>
            <div><dt>{{ uiText("订单类型") }}</dt><dd>{{ typeNames[row.orderType] }}</dd></div>
            <div><dt>{{ uiText("钉钉审批编号") }}</dt><dd>{{ row.dingTalkApprovalNo || '--' }}</dd></div>
            <div><dt>{{ uiText("创建时间") }}</dt><dd>{{ row.createdAt?.replace('T', ' ') || '--' }}</dd></div>
          </dl>
        </article>
      </div>
    </section>
  </PageContainer>
</template>

<style scoped>
.order-toolbar{display:flex;gap:12px;flex-wrap:wrap}.order-toolbar__select{width:180px}.order-toolbar__search{max-width:420px;margin-left:auto}
.order-cards{display:flex;flex-direction:column;gap:8px;min-width:0;padding:2px}
.order-card{flex:none;min-width:0;padding:12px;border:1px solid var(--el-border-color-lighter);border-radius:6px;background:var(--el-bg-color)}
.order-card header{display:flex;justify-content:space-between;align-items:flex-start;gap:8px}
.order-card header strong,.order-card dd{min-width:0;overflow-wrap:anywhere}
.order-card__product{margin:8px 0;color:var(--el-text-color-primary);font-weight:600;overflow-wrap:anywhere}
.order-card dl{display:grid;gap:8px;margin:0}
.order-card dl>div{display:grid;grid-template-columns:100px minmax(0,1fr);gap:8px}
.order-card dt{color:var(--el-text-color-secondary)}.order-card dd{margin:0}
@media(max-width:900px){.order-toolbar{gap:8px}.order-toolbar__select,.order-toolbar__search{width:100%;max-width:none;margin-left:0}}
</style>

<style scoped>
.order-toolbar--mobile{display:none}
@media(max-width:767px){
  .order-toolbar--desktop{display:none}
  .order-toolbar--mobile{display:grid;grid-template-columns:minmax(0,1fr) 40px 40px;align-items:center;gap:6px;padding:10px}
  .order-toolbar--mobile>.el-input,.order-toolbar--mobile>.el-button{min-width:0;margin:0}
  .order-toolbar--mobile>.el-input :deep(.el-input__wrapper){min-height:40px}
  .order-toolbar--mobile>.el-button{width:40px;height:40px;min-height:40px;padding:0}
  .order-toolbar__advanced{display:grid;grid-column:1/-1;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;padding-top:8px;border-top:1px solid var(--el-border-color-lighter)}
  .order-toolbar__advanced>.el-select{width:100%;min-width:0}
}
</style>
