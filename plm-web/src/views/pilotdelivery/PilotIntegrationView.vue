<script setup lang="ts">
import { t as uiText } from '@/locales'

import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { getIntegrationConfiguration, getDeliveryTasks, retryDeliveryTask, type DeliveryTask, type IntegrationConfiguration } from '@/api/modules/pilotDelivery'
const router = useRouter(), loading = ref(false), error = ref(''), page = ref(1), total = ref(0), status = ref(''), rows = ref<DeliveryTask[]>([])
const config = ref<IntegrationConfiguration | null>(null)
const statuses: Record<string, string> = { pending: '待发送', sending: '发送中', succeeded: '已取得业务回执', failed: '失败待重试', not_configured: '接入未配置' }
async function load() { loading.value = true; error.value = ''; try { const [a, b] = await Promise.all([getIntegrationConfiguration(), getDeliveryTasks(page.value, status.value || undefined)]); config.value = a; rows.value = b.content; total.value = b.totalElements } catch (e) { error.value = e instanceof Error ? e.message : '查询失败' } finally { loading.value = false } }
async function retry(row: DeliveryTask) { await retryDeliveryTask(row.task_id); ElMessage.success(uiText('已恢复待发送；实际成功以业务回执为准')); await load() }
onMounted(load)
</script>
<template>
 <section class="integration-page" v-loading="loading">
  <header><div><h2>{{ uiText("系统对接") }}</h2><p>{{ uiText("正式 BOM 发布与 ERP 同步分开；失败任务持久保留，重试不生成新版本。") }}</p></div><el-button @click="load">{{ uiText("刷新") }}</el-button><el-button @click="router.push('/system/trials')">{{ uiText("试点范围与启动条件") }}</el-button></header>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <div class="connection-state"><span>{{ uiText("外发开关：") }}{{ config?.enabled ? uiText('已启用') : uiText('未启用') }}</span><span>{{ uiText("ERP BOM发送：") }}{{ config?.erpConfigured ? uiText('配置已提供，仍需联调') : uiText('尚未配置') }}</span><span>{{ uiText("ERP费用只读：") }}{{ config?.erpExpensesConfigured ? uiText('配置已提供，仍需联调') : uiText('尚未配置') }}</span><span>{{ uiText("钉钉通知：") }}{{ config?.dingtalkConfigured ? uiText('配置已提供，仍需联调') : uiText('尚未配置') }}</span></div>
  <el-alert :title="config?.message || uiText('凭据由运行环境提供，不在页面和代码中保存。')" type="info" :closable="false" />
  <el-select v-model="status" clearable :placeholder='uiText("全部发送状态")' @change="page=1;load()"><el-option v-for="(label,key) in statuses" :key="key" :label="label" :value="key" /></el-select>
   <div class="integration-table-desktop">
   <el-table :data="rows" border height="calc(100vh - 365px)" :empty-text='uiText("暂无同步任务。草稿不会进入队列；正式版本审核发布后生成持久任务。")'>
   <el-table-column prop="event_id" :label='uiText("稳定业务事件")' min-width="240" fixed="left"/><el-table-column prop="kind" :label='uiText("对接类型")' width="160"/><el-table-column :label='uiText("状态")' min-width="180"><template #default="{row}">{{ statuses[row.status] || row.status }}</template></el-table-column><el-table-column prop="attempts" :label='uiText("尝试次数")' width="105"/><el-table-column prop="last_error" :label='uiText("最近错误")' min-width="230"/><el-table-column prop="updated_at" :label='uiText("更新时间")' min-width="200"/><el-table-column :label='uiText("操作")' width="100" fixed="right"><template #default="{row}"><el-button v-if="['failed','not_configured'].includes(row.status)" link @click="retry(row)">{{ uiText("重试") }}</el-button></template></el-table-column>
   </el-table>
   </div>
   <div class="integration-list-mobile" :aria-label="uiText('系统对接任务')">
    <article v-for="row in rows" :key="row.task_id" class="integration-card">
      <header><strong>{{row.event_id}}</strong><el-tag size="small" :type="row.status==='succeeded'?'success':['failed','not_configured'].includes(row.status)?'danger':'info'">{{statuses[row.status]||row.status}}</el-tag></header>
      <dl><div><dt>{{uiText('对接类型')}}</dt><dd>{{row.kind}}</dd></div><div><dt>{{uiText('尝试次数')}}</dt><dd>{{row.attempts}}</dd></div><div><dt>{{uiText('更新时间')}}</dt><dd>{{row.updated_at}}</dd></div><div v-if="row.last_error" class="integration-error"><dt>{{uiText('最近错误')}}</dt><dd>{{row.last_error}}</dd></div></dl>
      <el-button v-if="['failed','not_configured'].includes(row.status)" link type="primary" @click="retry(row)">{{uiText('重试')}}</el-button>
    </article>
    <el-empty v-if="!rows.length&&!loading" :description='uiText("暂无同步任务。草稿不会进入队列；正式版本审核发布后生成持久任务。")'/>
   </div>
   <el-pagination v-model:current-page="page" :total="total" :page-size="50" layout="total,prev,pager,next" @current-change="load"/>
 </section>
</template>
 <style scoped>
 .integration-page{display:flex;flex-direction:column;gap:16px;width:100%;min-width:0}.integration-page>header,.connection-state{display:flex;align-items:center;gap:20px;flex-wrap:wrap}.integration-page>header>div{flex:1}h2{margin:0}p{color:#64748b;margin:8px 0}.connection-state{padding:18px;background:#f1f5f9;border-radius:8px}.el-select{width:230px}.integration-table-desktop{min-width:0}.integration-list-mobile{display:none}.integration-card{padding:10px 0;border-bottom:1px solid #e2e8f0;overflow-wrap:anywhere}.integration-card>header{display:flex;align-items:flex-start;justify-content:space-between;gap:8px}.integration-card>header strong{min-width:0;font-size:13px}.integration-card dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin:9px 0}.integration-card dt{color:#64748b;font-size:11px}.integration-card dd{margin:2px 0 0;line-height:1.5}.integration-card .integration-error{grid-column:1/-1}.integration-card .integration-error dd{color:#a43f3f}.integration-card>.el-button{padding:0;height:auto;min-height:28px}
 @media(max-width:767px){.integration-page{gap:10px;padding:8px}.integration-page>header{gap:6px}.integration-page>header>div{flex-basis:100%}.integration-page>header .el-button{flex:1;min-width:0}.connection-state{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;padding:10px;font-size:12px;line-height:1.5}.integration-page>.el-select{width:100%;max-width:none}.integration-table-desktop{display:none}.integration-list-mobile{display:block;min-height:0;overflow:auto;border-top:1px solid #e2e8f0}.integration-page :deep(.el-pagination){justify-content:flex-start;flex-wrap:wrap}}
 </style>
