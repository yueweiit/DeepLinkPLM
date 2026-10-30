<script setup lang="ts">
import { t as uiText } from '@/locales'

import type { DevelopmentMaterial } from "@/api/modules/productResearch";
defineOptions({ name: "ResearchSnapshot" });
defineProps<{ value: unknown; depth?: number }>();
defineEmits<{ view: [DevelopmentMaterial] }>();
const labels: Record<string, string> = {
  supplierAssignments:'供应商分工',engineeringBasis:'已确认工程方案',engineering:'供应商与工程方案',internalName:'内部承担方',role:'承担职责',scope:'供货／制作范围',mode:'承担方式',
  reviewRecords:"设计／T0评审",reviewSetup:"测试及试产要求",reviewDate:"实际评审日期",reviewerUserId:"实际评审人编号",reviewerName:"实际评审人",gate:"评审节点",summary:"评审说明",checklist:"检查项目",structure:"结构检查",assembly:"装配检查",manufacturability:"制造可行性",moldCycle:"模具制作／改模轮次",setupVersion:"验证标准版本",planBasis:"采用计划范围",tests:"测试项目",minValue:"最低实测值",measuredValue:"实测值",actual:"实际结果",sampleQty:"样本数",tester:"测试人",date:"测试日期",plannedQty:"试产计划数量",minYieldRate:"最低良率%",fullInspection:"要求全检",yieldRate:"实际良率%",defectSummary:"不良分类与处理",voidReason:"作废原因",voidedBy:"作废人",voidedAt:"作废时间",voided:"已作废",unitCostVersionId:"采用单件成本版本",unitCostVersions:"单件成本历史",t0Reviews:"采用T0评审",previousReviewId:"前次评审",deleted:"已删除草稿",
  plan: "开发计划", planVersion: "计划版本", launchDate: "计划上市日期", method: "开发／测试方式", needsMold: "需要开模", sampleDate: "样品计划完成", moldDate: "模具计划完成", supplyDate: "供货准备日期",
  promotions: "推广记录", presales: "预售记录", channel: "渠道", target: "账号／合作方／客户", plannedDate: "计划日期", promisedDate: "承诺交期", phoneSnapshot: "当时机型", recordVersion: "记录版本", before: "调整前", after: "调整后", ledgerSnapshot: "当时原台账", updatedBy: "操作人", updatedAt: "操作时间", node: "节点", nodeVersion: "节点版本", saved: "原模具保存结果", development: "实际开发节点", last: "上次已确认节点", noMold: "无需开模依据", molds: "模具依据", sampleRoundId: "采用样品", draft: "未确认草稿", basis: "本次依据", channels: "实际上架／开售渠道", supplyNote: "供货说明", cost: "成本登记", type: "类型", id: "记录编号", expenseId: "关联费用", budgetId: "关联预算", action: "办理动作", ownerName: "当时负责人", phones: "当时机型", note: "实际说明", result: "实际结果", flow: "流程版本",
  productId: "项目",
  productName: "产品名称",
  productCode: "项目编码",
  requirements: "开发要求",
  model: "原机型记录",
  phoneModelIds: "适配机型编号",

  brandName: "品牌",
  modelName: "机型",
  ownerUserId: "负责人编号",
  pendingReason: "机型待定原因",
  targetCost: "目标成本",
  currency: "币种",
  costUnit: "计价口径",
  notes: "备注",
  changeReason: "变更原因",
  references: "采用资料版本",
  kind: "记录类型",
  roundNo: "轮次",
  roundId: "记录编号",
  recordDate: "实际日期",
  title: "名称",

  issues: "问题",
  changes: "修改",
  retestResult: "复验结果",
  status: "状态",
  details: "本轮事实",
  sourceRoundId: "来源记录",
  feedbackRoundId: "原问题来源",
  feedbackSnapshot: "原反馈与当时资料",
  fromStage: "退回前阶段",
  targetStage: "退回阶段",
  source: "反馈来源",
  baseSampleRoundId: "原样品编号",
  completedByRoundId: "后续处理轮次",
  compatibleRequirementVersions: "要求不变的历史版本",
  previousRoundId: "前轮记录",
  requirementVersion: "需求版本",
  revisionNo: "修订版本",
  makerName: "制作方",
  supplierCode: "供应商编码",
  supplierName: "供应商",
  quoteEventId: "采用报价编号",
  adoptedQuote: "当时报价",
  moldMode: "开模方式",
  moldSetId: "模具编号",
  applicationId: "开模申请编号",
  designSummary: "设计依据",
  testContent: "测试内容",
  actualResult: "实际结果",
  blockingIssues: "仍有阻碍问题",
  producedQty: "生产数量",
  inspectedQty: "检验数量",
  defectQty: "不良数量",
  inspectionMethod: "检验方式",
  batchNo: "批号",
  confirmedAt: "确认时间",
  confirmedBy: "确认人",
  createdAt: "记录时间",
  createdBy: "登记人",
  data: "记录内容",
  stage: "交接阶段",

  releaseDraft: "发布清单",
  pilotRoundId: "采用试产",
  confirmationDate: "确认日期",
  sampleNote: "确认样说明",
  sampleLocation: "存放位置",
  documentationOnly: "仅资料更正",
  documentationReason: "原验证仍适用的依据",
  moldSnapshot: "当时模具信息",
  inspectionMode: "补检方式",
  inspectionScope: "检验范围",
  releaseId: "正式包编号",
  packageNo: "正式包序号",
  versionNo: "版本",
  legacyVersionNo: "原版本标记",
  restricted: "此资料无查看权限",
  responsibleSnapshot: "当时补齐责任人",
  publishedAt: "发布时间",
  publishedBy: "发布人",
  productSnapshot: "发布时产品信息",
  roundsSnapshot: "发布时开发记录",
  missingItems: "发布时缺口",
  exceptionReason: "例外原因",
  supplementDueDate: "原补齐日期",
  responsibleUserId: "责任人编号",
  message: "事项",
  code: "标识",
  recipient: "接收方／接收人",
  actualDate: "实际日期",

  reason: "原因",
  impact: "影响范围",
  technical: "涉及技术变更",


  gapCode: "对应缺口",
  dueDate: "新补齐日期",
  amount: "报价金额",
  unit: "计价口径",
  url: "参考网址",
  content: "当时内容",
  name: "名称",
  items: "BOM明细",
  operations: "工序",
  header: "版本信息",
  routes: "路线",
  colors: "颜色",
  item_name: "物料名称",
  item_code: "物料编码",
  quantity: "用量",
  uom_code: "单位",
  process_name: "工序名称",
  quality_requirement: "质量要求",
  sequence_no: "顺序",
  version_no: "版本",
  previousEventId: "上一版本记录",
  transferEventId: "对应移交",
  requirement: "确认需求",
  revision: "修订依据",
};
const values: Record<string, string> = {
  internal:'内部制作',component:'部件',processing:'加工',finished_product:'成品供货',mold:'模具',
  own_mold: "自主开模", supplier: "供应商开发", direct: "直接拿样", making: "制作", testing: "验模", rework: "改模", ready: "验模通过", complete: "完成", blocked: "遇阻", ecommerce: "电商矩阵", mcn: "合作 MCN", wholesale: "线下批发", chain: "线下连锁", reserved: "预订", cancelled: "已取消", done: "已完成", working: "进行中", launch_v1: "开发上市流程 V1",
  sample: "样品",
  test: "测试",
  pilot: "试产",
  pending: "待验证",
  passed: "通过",
  failed: "未通过",
  draft: "草稿",
  confirmed: "已确认",
  none: "无需开模",
  existing: "已有模具",
  new: "需要开模",
  released: "已发布",
  locked: "已锁定",
  frozen: "已冻结",
};
function label(key: string) {
  return labels[key] || key;
}
function scalar(value: unknown) {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "boolean") return value ? "是" : "否";
  return values[String(value)] || String(value);
}
function isMaterial(v: unknown): v is DevelopmentMaterial {
  return (
    !!v && typeof v === "object" && "type" in v && "id" in v && "name" in v
  );
}
function fields(v: object) {
  return Object.entries(v).filter(
    ([k]) =>
      ![
        "deleted_flag",
        "requestId",
        "request_id",
        "attachment",
        "checksum",
        "detailsJson",
        "referencesJson",
        "version",
      ].includes(k),
  );
}
</script>
<template>
  <div class="snapshot">
    <template v-if="isMaterial(value)"
      ><div class="material">
        <strong>{{ value.name }} · {{ value.versionNo }}</strong
        ><el-button link type="primary" @click="$emit('view', value)"
          >{{ uiText("查看当时版本") }}</el-button
        >
      </div>
      <ResearchSnapshot
        v-if="value.content"
        :value="value.content"
        :depth="(depth || 0) + 1"
        @view="$emit('view', $event)" /></template
    ><template v-else-if="Array.isArray(value)"
      ><div v-for="(row, i) in value" :key="i" class="item">
        <ResearchSnapshot
          :value="row"
          :depth="(depth || 0) + 1"
          @view="$emit('view', $event)"
        />
      </div>
      <span v-if="!value.length" class="muted">{{ uiText("无") }}</span></template
    ><template v-else-if="value && typeof value === 'object'"
      ><div v-for="[k, v] in fields(value)" :key="k" class="field">
        <span class="label">{{ uiText(label(k)) }}</span
        ><div v-if="k === 'supplierAssignments' && Array.isArray(v)" class="supplier-list">
          <div v-for="(assignment, i) in v" :key="i" class="supplier-assignment">
            <strong>{{ assignment.mode === 'internal' ? assignment.internalName || uiText('内部承担方待补') : assignment.supplierName || uiText('供应商') }}<template v-if="assignment.supplierCode"> · {{ assignment.supplierCode }}</template></strong>
            <span>{{ scalar(assignment.role) }} · {{ assignment.scope || uiText('范围待补') }}</span>
            <span class="muted">{{ uiText("采用依据：") }}{{ assignment.basis || uiText('待补') }}</span>
          </div>
          <span v-if="!v.length" class="muted">{{ uiText("无") }}</span>
        </div><el-collapse v-else-if="v && typeof v === 'object' && (depth || 0) > 0"
          ><el-collapse-item :title='uiText("展开查看")'
            ><ResearchSnapshot
              :value="v"
              :depth="(depth || 0) + 1"
              @view="$emit('view', $event)" /></el-collapse-item></el-collapse
        ><ResearchSnapshot
          v-else-if="v && typeof v === 'object'"
          :value="v"
          :depth="(depth || 0) + 1"
          @view="$emit('view', $event)"
        /><span v-else class="text">{{ scalar(v) }}</span>
      </div></template
    ><span v-else>{{ scalar(value) }}</span>
  </div>
</template>
<style scoped>
.snapshot {
  min-width: 0;
  font-size: 12px;
}
.supplier-list,
.supplier-assignment {
  display: grid;
  gap: 4px;
  min-width: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.supplier-assignment + .supplier-assignment {
  border-top: 1px solid #eef2f7;
  padding-top: 6px;
}
.field {
  display: grid;
  grid-template-columns: minmax(100px, 135px) minmax(0, 1fr);
  gap: 10px;
  border-bottom: 1px solid #eef2f7;
  padding: 9px 0;
}
.label,
.muted {
  color: #748399;
  font-size: 12px;
}
.text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.item {
  padding: 7px;
  border: 1px solid #e7edf4;
  border-radius: 5px;
  margin-bottom: 6px;
}
.material {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px;
}
.snapshot:deep(.el-collapse-item__header) {
  height: 30px;
  font-size: 12px;
}
.snapshot:deep(.el-collapse-item__content) {
  padding-bottom: 8px;
}
@media (max-width: 600px) {
  .field {
    grid-template-columns: 1fr;
    gap: 4px;
  }
}
</style>
