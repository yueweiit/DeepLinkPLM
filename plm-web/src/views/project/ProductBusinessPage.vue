<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from "vue";
import PhoneObjectIdSelect from '@/components/PhoneObjectSelect/IdSelect.vue'
import { getResearchReferencePage, getResearchReferencePhones } from '@/api/modules/productResearch'
import type { PhoneObjectProvider } from '@/components/PhoneObjectSelect/types'
import { useRoute, useRouter } from "vue-router";
import { useUserStore } from "@/stores/user";
import {
  getResearchBusiness,
  type ResearchBusinessRow,
} from "@/api/modules/productResearch";
import {
  researchBusiness,
  researchBusinessRoute,
} from "@/utils/researchNavigation";
import { PRODUCT_RESEARCH_STAGES, launchStage } from "@/constants/productResearch";
const projectProvider:PhoneObjectProvider={page:getResearchReferencePage,phones:getResearchReferencePhones}
const ProjectWorkspace = defineAsyncComponent(
  () => import("./ProductLaunchView.vue"),
);
const route = useRoute(),
  router = useRouter(),
  user = useUserStore();
const business = computed(() =>
  researchBusiness(String(route.meta.researchBusiness))!,
);
const selected = computed(() => Number(route.query.productId) || 0);
const keyword = ref(""),
  status = ref("all"),
  page = ref(1),
  total = ref(0),
  loading = ref(false),
  error = ref("");
const rows = ref<ResearchBusinessRow[]>([]),
  summary = ref({ recordCount: 0, projectCount: 0, quantity: 0 });
let generation = 0;
const channels: Record<string, string> = {
  ecommerce: "电商矩阵",
  mcn: "MCN 合作",
  wholesale: "线下批发",
  chain: "线下连锁",
};
const statuses: Record<string, string> = {
  pending: "待开展",
  working: "进行中",
  done: "已完成",
  cancelled: "已取消",
  reserved: "意向预订",
  confirmed: "已确认",
  fulfilled: "已履行",
};
const stateOptions = computed(() =>
  business.value.kind === "promotion"
    ? ["pending", "working", "done", "cancelled"]
    : ["reserved", "confirmed", "cancelled", "fulfilled"],
);
async function load() {
  const current = ++generation;
  loading.value = true;
  error.value = "";
  rows.value=[];total.value=0;summary.value={recordCount:0,projectCount:0,quantity:0};
  try {
    const result = await getResearchBusiness({
      kind: business.value.kind,
      keyword: keyword.value,
      status: status.value,
      page: page.value,
      size: 50,
    });
    if (current !== generation) return;
    rows.value = result.content;
    total.value = result.totalElements;
    summary.value = { quantity: 0, ...result.summary };
  } catch (e) {
    if (current === generation)
      error.value = e instanceof Error ? e.message : "载入失败";
  } finally {
    if (current === generation) loading.value = false;
  }
}
function open(projectId: number, stage?: number) {
  void router.push(
    researchBusinessRoute(business.value.kind, projectId, stage),
  );
}
function reset() {
  page.value = 1;
  void load();
}
watch(
  () => [business.value.kind, selected.value],
  () => {
    if (!selected.value) {
      status.value = "all";
      page.value = 1;
      void load();
    }
  },
  { immediate: true },
);
</script>
<template>
  <ProjectWorkspace v-if="selected" />
  <section v-else class="business-page" v-loading="loading">
    <header class="toolbar">
      <h2>{{ business.title }}</h2>
      <el-button @click="router.push('/product-research')"
        >返回新品研发</el-button
      >
    </header>
    <div class="filters">
      <el-input
        v-model="keyword"
        clearable
        placeholder="搜索新品名称、编码或业务内容"
        @keyup.enter="reset"
        @clear="reset"
      />
      <el-select
        v-if="business.kind !== 'cost'"
        v-model="status"
        aria-label="业务状态"
        @change="reset"
        ><el-option label="全部状态" value="all" /><el-option
          v-for="s in stateOptions"
          :key="s"
          :value="s"
          :label="statuses[s]"
      /></el-select>
      <el-button @click="reset">搜索</el-button>
      <PhoneObjectIdSelect :model-value="null" :provider="projectProvider" :phone-first="false" type="product" label="选择新品项目" placeholder="选择新品项目，查看或登记" @update:model-value="$event && open($event)"/>
    </div>
    <div class="summary">
      {{ summary.projectCount }} 个项目 · {{ total }}
      {{ business.kind === "cost" ? "个成本档案" : "条记录"
      }}<span v-if="business.kind === 'presale'">
        · 有效 {{ summary.quantity }} 件（不含已取消）</span
      ><span class="muted">{{
        business.kind === "cost"
          ? "单件核算沿用BOM成本；预算和费用来自原台账，按币种分别显示。"
          : "记录与新品项目共用数据；进入项目可办理、查看资料和历史。"
      }}</span>
    </div>
    <div class="records">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <article
        v-for="r in rows"
        :key="`${r.productId}:${r.record?.id || 0}`"
        class="business-card"
      >
        <div class="project">
          <el-button
            link
            type="primary"
            @click="
              router.push({
                path: '/product-research',
                query: { productId: r.productId, step: launchStage(r.currentStepNo).key },
              })
            "
            >{{ r.productName }}</el-button
          ><span
            >{{ r.productCode }} ·
            {{
              PRODUCT_RESEARCH_STAGES[(r.currentStepNo || 1) - 1]?.name
            }}</span
          ><span v-if="r.launchDate">计划上市 {{ r.launchDate }}</span>
        </div>
        <div v-if="business.kind === 'cost'" class="facts">
          <span>单件核算：{{r.hasUnitCostDraft?'有草稿':'未录草稿'}} · 已确认 {{r.unitCostVersionCount||0}} 版</span>
          <span
            >原预算 {{ r.budgetCount }} 笔 · 原费用
            {{ r.expenseCount }} 笔</span
          >
          <span
            v-for="g in r.budgets?.currencyGroups"
            :key="`b${g.currencyCode}${g.budgetKind}`"
            >{{ g.currencyCode }}
            {{ g.budgetKind === "platform" ? "项目总预算" : "模具分项预算" }}
            {{ g.budgetTotal ?? "待定" }}</span
          >
          <span
            v-for="g in r.expenses?.currencyGroups"
            :key="`e${g.currencyCode}`"
            >{{ g.currencyCode }} · 确认申报 {{ g.declaredTotal ?? "待定" }} ·
            ERP净额 {{ g.erpPostedNet ?? "待定" }}</span
          >
        </div>
        <div v-else-if="r.record" class="facts">
          <strong
            >{{ channels[r.record.channel] || r.record.channel }} ·
            {{ r.record.target }}</strong
          >
          <template v-if="business.kind === 'promotion'"
            ><span>{{ r.record.action }} · 计划 {{ r.record.plannedDate }}</span
            ><span
              v-if="
                user.hasPermission('finance:read') &&
                (r.record.budgetId || r.record.expenseId)
              "
              >关联{{ r.record.budgetId ? `预算 #${r.record.budgetId}` : "" }}
              {{
                r.record.expenseId ? `费用 #${r.record.expenseId}` : ""
              }}</span
            ></template
          >
          <template v-else
            ><span
              >{{
                r.record.phoneSnapshot?.modelName || r.model || "待核对机型"
              }}
              · {{ r.record.quantity }} 件 · 承诺
              {{ r.record.promisedDate }}</span
            ></template
          >
          <span
            >{{ statuses[r.record.status] || r.record.status }} · 记录版本
            {{ r.record.recordVersion }}</span
          ><el-tag
            v-if="r.record.planVersion !== r.currentPlanVersion"
            type="warning"
            >计划已调整，需核对</el-tag
          >
        </div>
        <div class="actions">
          <el-button
            type="primary"
            link
            @click="open(r.productId, r.currentStepNo)"
            >{{
              business.kind === "cost" ? "查看成本与来源" : "查看／办理"
            }}</el-button
          >
        </div>
      </article>
      <el-empty
        v-if="!loading && !error && !rows.length"
        :description="
          keyword || status !== 'all'
            ? '没有符合条件的记录'
            : '暂无记录。选择已有新品项目后登记；没有项目时先到需求立项新建。'
        "
      />
    </div>
    <el-pagination
      v-model:current-page="page"
      :total="total"
      :page-size="50"
      layout="prev,pager,next,total"
      @current-change="load"
    />
  </section>
</template>
<style scoped>
.business-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  gap: 10px;
  container-type: inline-size;
  container-name: business;
}
.toolbar,
.filters,
.summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}
.toolbar h2 {
  font-size: 18px;
  margin: 0;
  flex: 1;
  overflow-wrap: anywhere;
}
.filters .el-input {
  flex: 1 1 220px;
  width: auto;
  min-width: 0;
}
.filters .el-select {
  flex: 0 1 250px;
  width: 250px;
  max-width: 100%;
  min-width: 0;
}
.filters .el-button,
.toolbar .el-button {
  margin: 0;
  height: auto;
  min-height: 30px;
  white-space: normal;
}
.summary {
  font-size: 13px;
}
.muted {
  color: #64748b;
}
.records {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
}
.business-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 2fr) auto;
  gap: 12px;
  padding: 12px 8px;
  border-bottom: 1px solid #e2e8f0;
  background: #fff;
  overflow-wrap: anywhere;
}
.project,
.facts {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  min-width: 0;
}
.project span {
  font-size: 12px;
  color: #64748b;
}
.project .el-button {
  white-space: normal;
  height: auto;
  line-height: 1.5;
  text-align: left;
  max-width: 100%;
}
.actions {
  display: flex;
  align-items: center;
}
.business-card .el-tag {
  height: auto;
  white-space: normal;
}
.el-pagination {
  max-width: 100%;
  flex-wrap: wrap;
}
@container business (max-width:650px) {
  .business-card {
    grid-template-columns: minmax(0, 1fr);
  }
  .filters .el-select {
    flex: 1 1 200px;
  }
  .summary .muted {
    width: 100%;
  }
  .actions {
    justify-content: flex-start;
  }
}
@media (max-width: 500px) {
  .business-page {
    overflow-y: auto;
  }
  .records {
    min-height: 280px;
  }
}
</style>
