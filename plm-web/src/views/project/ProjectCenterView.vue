<script setup lang="ts">
import { t as uiText } from '@/locales'

import ProductMasterDataDialog from './components/ProductMasterDataDialog.vue'
import ProductSalesCatalog from '@/components/ProductionMetadata/ProductSalesCatalog.vue'
import CurrentProductCatalog from './components/CurrentProductCatalog.vue'
import {getMasterDataStatus,type MasterDataStatus} from '@/api/modules/masterData'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

import { getProductPresentation } from '@/api/modules/foundation'
import ProductProductionControl from '@/components/ProductionMetadata/ProductProductionControl.vue'
import ProductProductionList from '@/components/ProductionMetadata/ProductProductionList.vue'
import ProductionStatusFilter from '@/components/ProductionMetadata/ProductionStatusFilter.vue'
import {productionStatusName} from '@/utils/productionStatus'
import MetadataDialog from '@/components/ProductionMetadata/MetadataDialog.vue'
import {useProductionMetadataStore} from '@/stores/productionMetadata'

import type {ProductionMetadataTarget,ProductionStatus} from '@/types/production'
import { getProductDetail as getProductBasicDetail, updateProductBasicInfo } from '@/api/modules/product'
import {
  abandonProject,
  archiveProject,
  checkProjectReleaseGate,
  confirmTimelineNode,
  freezeProject,
  getProjectDetail,
  getProjects,
  getProjectTimeline,
  publishProject,
  returnTimelineNode,
  saveMoldTransferExpress,
  type ProductReleaseGateCheckVO,
  type MoldTransferExpressVO,
  type TimelineDetailVO
} from '@/api/modules/project'
import { commitImport, previewImport, type ImportPreviewVO } from '@/api/modules/importExport'
import FilePreview from '@/components/FilePreview/index.vue'
import ProjectWorkbench from './ProjectWorkbench.vue'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'
import PageContainer from '@/components/PageContainer/index.vue'
import FixedTableViewport from '@/components/FixedTableViewport/index.vue'
import StatusTag from '@/components/StatusTag/index.vue'
import ProjectBomPanel from './components/ProjectBomPanel.vue'
import ProjectCostPanel from './components/ProjectCostPanel.vue'
import ProjectProcessRoutePanel from './components/ProjectProcessRoutePanel.vue'
import ProjectReleaseGatePanel from './components/ProjectReleaseGatePanel.vue'
import ProductionConfirmationDialog from './components/ProductionConfirmationDialog.vue'
import TimelineAttachmentPanel from './components/TimelineAttachmentPanel.vue'
import type { CommonStatus } from '@/types/common'
import type { BomCompareRow, ProductBomItemRow, ProductDetailPresentation, ProductTimelineNode, ProductionDocumentPreviewFile, SkuProcessRouteRow } from '@/types/foundation'
import type { ProductBasicInfo, ProductSummary } from '@/types/product'
import { formatAmount, formatDate } from '@/utils/format'
import { toArchivedProductRoute } from '@/utils/projectRoute'
import { findCurrentTimelineStep, mapTimelineStages, mapTimelineSteps, type TimelineStageView } from '@/utils/timelineAdapter'

const foundationRevision=ref(0),foundationSourceMode=ref(false),foundationMasterPanel=ref<{mayLeave:()=>Promise<boolean>}>()
async function toggleFoundationSource(){if(!foundationSourceMode.value&&foundationMasterPanel.value&&!await foundationMasterPanel.value.mayLeave())return;foundationSourceMode.value=!foundationSourceMode.value}
const foundationMasterHome=computed(()=>String(route.query.tab)==='archived'&&String(route.query.archiveView)==='product'&&!route.query.productId&&!route.query.skuId&&!route.query.history)
const productMasterVisible=ref(false),productMasterCreate=ref(false),productMasterId=ref<number>()
function openProductMaintenance(id?:number,create=false){productMasterId.value=id;productMasterCreate.value=create;productMasterVisible.value=true}
const masterDataStatus=ref<MasterDataStatus>(),legacyArchive=ref(false)
onMounted(async()=>{try{masterDataStatus.value=await getMasterDataStatus()}catch{/* Older/offline backend keeps the explicit legacy view. */}})

type ProjectQuickTag = 'archived_product' | 'archived_sku'
type ArchiveView = 'overview' | 'product' | 'sku'
type DetailSectionKey =
  | 'basic'
  | 'current_node'
  | 'project_flow'
  | 'bom_manage'
  | 'process_detail'
  | 'materials'
  | 'business'
  | 'quality'
  | 'cost'
  | 'bom'
  | 'process'
  | 'production_docs'
type SkuPageStage = 'product-home' | 'sku-list'
type ProductFlowStageStatus = ProductTimelineNode['status']
type ProcessDetailFilter = 'all' | 'key' | 'changed'
type ProductProcessDetailTag = 'key' | 'changed' | 'outsourced'
type TimelinePresentationNode = ProductTimelineNode & { confirmed?: boolean }

interface ProjectTimelineNode {
  nodeKey: string
  stageCode?: string
  title: string
  phase: string
  gate?: boolean
  hint: string
  childStepNos: number[]
  childNodes: string[]
  count: number
}

type ProjectTimelineDefinition = Omit<ProjectTimelineNode, 'count'>

interface ProductFlowStageNode {
  stepNo: number
  nodeKey: string
  nodeCode?: string
  nodeName: string
  status: ProductFlowStageStatus
  actionLabel?: string
  documentCount?: number
  visualStatus?: string
  processConfirmation?: boolean
}

interface ProductFlowStage {
  stageKey: string
  stageName: string
  phaseName: string
  status: ProductFlowStageStatus
  summary: string
  receiverRole?: string
  receiverUserName?: string
  receivedAt?: string
  promoterRole?: string
  promoterUserName?: string
  promotedAt?: string
  nextAction?: string
  riskNote?: string
  childNodes: ProductFlowStageNode[]
}

interface ProductProcessDetailRow {
  sequenceNo: number
  processCode: string
  processName: string
  processType: string
  workstationName?: string | null
  supplierName?: string | null
  confirmerName?: string
  confirmerRole?: string
  processParamSummary?: string
  qualityRequirement?: string
  unitProcessCost?: number
  tags: ProductProcessDetailTag[]
}

interface SkuProjectFlowRow {
  seqNo: number
  stageKey: string
  stageName: string
  nodeKey: string
  nodeName: string
  phaseName: string
  status: ProductFlowStageStatus
  experienceSummary: string
  receiverRole?: string
  receiverUserName?: string
  receivedAt?: string
  promoterRole?: string
  promoterUserName?: string
  promotedAt?: string
  nextAction?: string
  riskNote?: string
  gateLabel?: string
  documentCount?: number
}

const salesCatalogVisible=ref(false)
const router = useRouter()
const user = useUserStore()
const route = useRoute()

const loading = ref(false)
const rows = ref<ProductSummary[]>([])
const keyword = ref('')
const importVisible = ref(false)
const importType = ref<'product' | 'sku'>('product')
const archiveImportFile = ref<File | null>(null)
const archiveImportInputKey = ref(0)
const archiveImportPreview = ref<ImportPreviewVO | null>(null)
const archiveImportLoading = ref(false)

const detailVisible = ref(false)
const productionConfirmationVisible = ref(false)
const productionConfirmationMode = ref<'operations' | 'colors'>('operations')
const productionConfirmationDefaultRouteId = ref<number | null>(null)
const routeDetailLoading = ref(false)
const processConfirmationNodeKeys = new Set([
  'PRODUCT_LINE_PROCESS_CONFIRM',
  'MODEL_VARIANT_PROCESS_CONFIRM'
])

function isProcessConfirmationNode(node: Pick<ProductFlowStageNode, 'nodeKey' | 'nodeCode' | 'nodeName'>) {
  const key = node.nodeKey || node.nodeCode || ''
  return processConfirmationNodeKeys.has(key)
    || key.endsWith('_PROCESS_CONFIRM_STEP')
    || node.nodeName.includes('敲定投产工序')
    || node.nodeName.includes('敲定工序')
}

function openProductionConfirmation(mode: 'operations' | 'colors', defaultProductBomRouteId?: number | null) {
  productionConfirmationMode.value = mode
  productionConfirmationDefaultRouteId.value = defaultProductBomRouteId || null
  productionConfirmationVisible.value = true
}
const detailTarget = ref<ProductSummary | null>(null)
const productionFilter=ref<ProductionStatus|''>(''),productionEditor=ref(false),productionTargets=ref<ProductionMetadataTarget[]>([]),productionPermissions=useProductionMetadataStore()
function productionParams(){return productionFilter.value?{productionStatus:productionFilter.value}:{}}
const selectedProductionRows=ref<ProductSummary[]>([])
const skuParentRows=ref<ProductSummary[]>([])
function editProductionBatch(){if(!selectedProductionRows.value.length||selectedProductionRows.value.length>100)return;productionTargets.value=selectedProductionRows.value.map(r=>({id:r.productId,label:r.productName,productionStatus:r.productionStatus||'unknown'}));productionEditor.value=true}
function openAssociatedBusiness(path:string) {if(detailTarget.value)void router.push({path:path==='market-metadata'?'/market-watch':path,query:{projectId:detailTarget.value.productId,...(path==='market-metadata'?{panel:'market-data'}:{})}})}
const detailLoading = ref(false)
const detailPresentation = ref<ProductDetailPresentation | null>(null)
const basicInfoEditing = ref(false)
const basicInfoSaving = ref(false)
const basicInfoExtra = ref<Partial<ProductBasicInfo> | null>(null)
const basicInfoForm = reactive({
  productName: '',
  seriesName: '',
  ownerUserName: '',
  model: '',
  color: '',
  material: '',
  packageType: '',
  surfaceProcess: '',
  coreProcess: '',
  composition: '',
  expectedReleaseDate: '',
  expectedArrivalAt: '',
  actualArrivalAt: '',
  networkType: '',
  holeType: '',
  mobileFunction: '',
  tipo: '',
  priority: '',
  manufacturingLocation: '',
  moldMarking: '',
  referenceUrl: '',
  requirementType: '',
  customerRequirement: ''
})
const detailBomVersion = ref('')
const timelineActionLoading = ref<false | 'confirm' | 'advance' | 'return'>(false)
const detailLifecycleLoading = ref<false | 'freeze' | 'publish' | 'archive' | 'abandon'>(false)
const timelineCurrentConfirmed = ref<boolean | null>(null)
const timelineStarted = ref<boolean | null>(null)
const timelineStartBlockReason = ref<string | null>(null)
const timelineCompleted = ref(false)
const timelineLastAction = ref<string | null>(null)
const timelineLastReason = ref<string | null>(null)
const timelineLastOperatedAt = ref<string | null>(null)
const timelineLastOperatorUserName = ref<string | null>(null)
const timelineFlowStages = ref<ProductFlowStage[] | null>(null)
const moldTransferExpress = ref<MoldTransferExpressVO | null>(null)
const moldTransferLoading = ref(false)
const moldTransferForm = reactive({
  trackingNo: '',
  shippedAt: ''
})
const activeDetailSection = ref<DetailSectionKey>('basic')
const activeProductFlowStageKey = ref('')
const activeSkuFlowStageKey = ref('')
const processDetailFilter = ref<ProcessDetailFilter>('all')
const processDetailKeyword = ref('')
const skuFlowTableExpanded = ref<string[]>([])

/* 生产资料预览 */
const productionPreviewVisible = ref(false)
const activeProductionDoc = ref<ProductionDocumentPreviewFile | null>(null)

const skuPageStage = ref<SkuPageStage>('product-home')
const skuActiveProductId = ref<number | null>(null)
const skuKeyword = ref('')

const productDetailSections = [
  { key: 'current_node' as const, label: '当前节点' },
  { key: 'basic' as const, label: '基础信息' },
  { key: 'project_flow' as const, label: '项目流程' },
  { key: 'bom_manage' as const, label: 'BOM管理' },
  { key: 'cost' as const, label: '成本管理' },
  { key: 'process_detail' as const, label: '工序明细' },
  { key: 'materials' as const, label: '资料区' },
  { key: 'business' as const, label: '商务区' },
  { key: 'quality' as const, label: '质量区' }
]

const skuDetailSections = [
  { key: 'basic' as const, label: '基础信息' },
  { key: 'cost' as const, label: '成本' },
  { key: 'bom' as const, label: '当前版本 BOM' },
  { key: 'process' as const, label: '工艺路线' },
  { key: 'project_flow' as const, label: '项目流程' },
  { key: 'production_docs' as const, label: '生产资料' }
]

const projectQuickTags = [
  { label: '已归档（产品）', value: 'archived_product' },
  { label: '已归档（SKU）', value: 'archived_sku' }
] as const

const newProductLineTimeline: ProjectTimelineDefinition[] = [
  { nodeKey: 'initiation', title: '立项确认', phase: '立项阶段', gate: true, hint: '确认需求、成本、周期和投入边界。', childStepNos: [1, 2], childNodes: ['产品立项', '确认立项'] },
  { nodeKey: 'design', title: '设计确认', phase: '设计验证阶段', hint: '收敛图纸、外观、结构和供应商可制造性。', childStepNos: [3, 4], childNodes: ['画图查看', '供应商确认外观图纸'] },
  { nodeKey: 'tooling', title: '开模试模', phase: '开模阶段', gate: true, hint: '完成开模申请、模具制作和试模验证。', childStepNos: [5, 6, 7], childNodes: ['申请开模', '制作模具', '测试模具'] },
  { nodeKey: 'sampling-process', title: '样品与工艺', phase: '样品 / 工艺定型阶段', gate: true, hint: '签样、工艺、组件、红样、黄样和生产资料整理。', childStepNos: [8, 9, 10, 11, 12, 13, 14, 15, 16], childNodes: ['签样确认', '加工艺', '敲定工序', '整理生产资料', '黄样'] },
  { nodeKey: 'pilot-mx', title: '小批与 MX 验证', phase: '市场验证阶段', gate: true, hint: '验证产线、物流、MX 端承接和小批量跑通。', childStepNos: [17, 18, 19, 20, 21], childNodes: ['小批量测试', '运模', 'MX 验收', '测试验证', 'MX 小批量测试'] },
  { nodeKey: 'launch', title: '投产决策', phase: '投产发布阶段', gate: true, hint: '根据验证结果决定投产或回退。', childStepNos: [22], childNodes: ['投产决策'] }
]

const modelVariantTimeline: ProjectTimelineDefinition[] = [
  { nodeKey: 'ext-confirm', title: '立项确认', phase: '立项阶段', gate: true, hint: '确认新型号需求来源和父产品关系。', childStepNos: [1, 2], childNodes: ['产品立项', '确认立项'] },
  { nodeKey: 'diff-design', title: '设计确认', phase: '设计验证阶段', hint: '确认图纸、外观和供应商可制造性差异。', childStepNos: [3, 4], childNodes: ['画图查看', '供应商确认外观图纸'] },
  { nodeKey: 'mold-branch', title: '开模试模', phase: '开模阶段', gate: true, hint: '完成开模申请、模具制作和试模验证。', childStepNos: [5, 6, 7], childNodes: ['申请开模', '制作模具', '测试模具'] },
  { nodeKey: 'diff-verify', title: '样品与工艺', phase: '样品 / 工艺定型阶段', gate: true, hint: '确认签样、工艺、组件、测试和生产资料。', childStepNos: [8, 9, 10, 11, 12, 13, 14, 15, 16], childNodes: ['签样确认', '加工艺', '敲定工序', '确认组件', '确认组件成品', '最终外观确认样', '红样测试', '整理生产资料', '黄样'] },
  { nodeKey: 'variant-pilot', title: '小批与 MX 验证', phase: '市场验证阶段', gate: true, hint: '完成小批量测试和运模移交，运模确认后 PLM 进入完成/归档/移交状态。', childStepNos: [17, 18], childNodes: ['小批量测试', '运模 / 移交 MX'] }
]

const timelineStageCodeByKey: Record<string, string> = {
  initiation: 'PRODUCT_LINE_INIT_CONFIRM',
  design: 'PRODUCT_LINE_DESIGN_CONFIRM',
  tooling: 'PRODUCT_LINE_MOLD_TRIAL',
  'sampling-process': 'PRODUCT_LINE_SAMPLE_PROCESS',
  'pilot-mx': 'PRODUCT_LINE_SMALL_BATCH_MX',
  launch: 'PRODUCT_LINE_PRODUCTION_DECISION',
  'ext-confirm': 'MODEL_VARIANT_INIT_CONFIRM',
  'diff-design': 'MODEL_VARIANT_DESIGN_CONFIRM',
  'mold-branch': 'MODEL_VARIANT_MOLD_TRIAL',
  'diff-verify': 'MODEL_VARIANT_SAMPLE_PROCESS',
  'variant-pilot': 'MODEL_VARIANT_SMALL_BATCH_MX'
}

const archiveView = computed<ArchiveView>(() => {
  const value = String(route.query.archiveView || 'overview')
  return ['overview', 'product', 'sku'].includes(value) ? (value as ArchiveView) : 'overview'
})

const projectQuickTag = computed<ProjectQuickTag>(()=>archiveView.value==='sku'?'archived_sku':'archived_product')

const filteredRows = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  if (!search) return rows.value
  return rows.value.filter((item) =>
    [item.productCode, item.productName, item.seriesName, item.ownerUserName, item.model, item.color]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(search))
  )
})

function isAbandonedProject(item: ProductSummary) {
  return item.lockStatus === 'abandoned' || Boolean(item.abandonedAt)
}

function formatScalarOrArray(value: unknown) {
  if (Array.isArray(value)) {
    const values = value.map((item) => String(item || '').trim()).filter(Boolean)
    return values.length ? values.join('、') : '--'
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed || trimmed === '--') return '--'
    if (trimmed.startsWith('[')) {
      try {
        const parsed = JSON.parse(trimmed)
        if (Array.isArray(parsed)) {
          const values = parsed.map((item) => String(item || '').trim()).filter(Boolean)
          return values.length ? values.join('、') : '--'
        }
      } catch {
        return trimmed
      }
    }
    return trimmed
  }
  return value == null ? '--' : String(value)
}

function formatOwnerName(value?: string | null) {
  const trimmed = String(value || '').trim()
  return trimmed && trimmed !== '--' ? trimmed : '待分配'
}

function normalizeProjectDetailRoute(target: ProductSummary) {
  if (!isFoundation.value || !['released', 'archived'].includes(target.status) || String(route.query.tab || '') === 'archived') return
  router.push({
    path: route.path,
    query: {
      ...route.query,
      tab: 'archived',
      archiveView: target.productType === 'model_variant' ? 'sku' : 'product',
      productId: String(target.productId)
    }
  })
}

const archivedProjects = computed(() =>
  filteredRows.value.filter((item) => !isAbandonedProject(item) && ['released', 'archived'].includes(item.status))
)
const archivedProductRows = computed(() => archivedProjects.value.filter((item) => item.productType === 'product_line'))
const archivedSkuRows = computed(() =>
  archivedProjects.value.filter((item) => item.productType === 'model_variant' || item.productType === 'sku' || Boolean(item.parentProductId))
)
const skuProductCards = computed(() =>
  (productionFilter.value?skuParentRows.value:archivedProductRows.value).filter((product) => getSkuCountForProduct(product) > 0)
)

const skuActiveProduct = computed(() =>
  skuProductCards.value.find((item) => item.productId === skuActiveProductId.value) || null
)

const skuCurrentSkuRows = computed(() => {
  const search = skuKeyword.value.trim().toLowerCase()
  if (!skuActiveProductId.value) return []
  return archivedSkuRows.value.filter((item) => {
    const activeProductCode = skuActiveProduct.value?.productCode || ''
    const activeProductLineCode = skuActiveProduct.value ? getDisplayProductLineCode(skuActiveProduct.value) : ''
    const belongsTo =
      item.parentProductId === skuActiveProductId.value ||
      (Boolean(activeProductCode) &&
        (item.productCode.startsWith(activeProductCode) || Boolean(item.finishedProductCode?.startsWith(activeProductCode)))) ||
      (Boolean(activeProductLineCode) &&
        (item.productCode.startsWith(activeProductLineCode) || Boolean(item.finishedProductCode?.startsWith(activeProductLineCode))))
    const keywordMatched =
      !search ||
      item.productCode.toLowerCase().includes(search) ||
      getDisplaySkuCode(item).toLowerCase().includes(search) ||
      item.productName.toLowerCase().includes(search) ||
      item.model.toLowerCase().includes(search) ||
      item.color.toLowerCase().includes(search)
    return belongsTo && keywordMatched
  })
})

const currentModuleCount=computed(()=>archiveView.value==='sku'?archivedSkuRows.value.length:archivedProductRows.value.length)
const currentModuleTitle=computed(()=>archiveView.value==='sku'?'已归档（SKU）':'已归档（产品）')
const currentModuleDescription=computed(()=>archiveView.value==='sku'?'历史 SKU 按产品查询，不计入开发项目数。':'历史产品平台与型号资料；正式开发项目在工作台项目中心查询。')

const detailBomItems = computed<ProductBomItemRow[]>(() => {
  if (!detailPresentation.value || !detailBomVersion.value) return []
  return detailPresentation.value.bomItemsByVersion[detailBomVersion.value] || []
})

const isProductDetailDialog = computed(() => detailTarget.value?.productType === 'product_line')
const hasDetailColorSummary = computed(() =>
  Boolean(
    isProductDetailDialog.value &&
    detailTarget.value?.colorSummary &&
    ((detailTarget.value.colorSummary.skuColors?.length || 0) > 0 ||
      (detailTarget.value.colorSummary.productionColors?.length || 0) > 0)
  )
)
const detailDialogTitle = computed(() => {
  if (detailTarget.value?.productType === 'product_line') return '产品详情'
  if (detailTarget.value?.productType === 'model_variant') return '新型号详情'
  return 'SKU 详情'
})
const detailObjectLabel = computed(() => {
  if (detailTarget.value?.productType === 'product_line') return '产品'
  if (detailTarget.value?.productType === 'model_variant') return '新型号'
  return 'SKU'
})

const activeDetailSections = computed(() =>
  (isProductDetailDialog.value ? productDetailSections : skuDetailSections).filter(section=>section.key!=='cost'||user.hasPermission('finance:read'))
)

const activeProductFlowNode = computed<TimelinePresentationNode | null>(() => {
  const nodes = (detailPresentation.value?.timeline || []) as TimelinePresentationNode[]
  return nodes.find((item) => item.status === 'current') || nodes[nodes.length - 1] || null
})

const currentTimelineConfirmed = computed(() => Boolean(
  timelineCompleted.value || (activeProductFlowNode.value?.confirmed ?? timelineCurrentConfirmed.value)
))

const currentTimelineActionLabel = computed(() => {
  if (!activeProductFlowNode.value) return '暂无当前节点'
  if (timelineCompleted.value || activeProductFlowNode.value.status === 'completed') return '已完成'
  return currentTimelineConfirmed.value ? '可推进到下一节点' : '等待确认当前节点'
})

const canAdvanceCurrentTimelineNode = computed(() => {
  if (!activeProductFlowNode.value) return false
  if (activeProductFlowNode.value.status !== 'current') return false
  const nodes = detailPresentation.value?.timeline || []
  const currentIndex = nodes.findIndex((node) => node.nodeKey === activeProductFlowNode.value?.nodeKey)
  return currentTimelineConfirmed.value && currentIndex >= 0 && currentIndex < nodes.length - 1
})

function stageMatchesNode(stage: ProjectTimelineDefinition, node: ProductTimelineNode, stepNo: number) {
  const expectedStageCode = stage.stageCode || timelineStageCodeByKey[stage.nodeKey]
  if (expectedStageCode && node.stageCode) return node.stageCode === expectedStageCode
  const phaseMatched = node.phaseName ? node.phaseName === stage.phase : false
  const nameMatched = stage.childNodes.some((childName) => node.nodeName.includes(childName) || childName.includes(node.nodeName))
  const stepMatched = !node.phaseName && !nameMatched && stage.childStepNos.includes(stepNo)
  return phaseMatched || nameMatched || stepMatched
}

function buildFlowStages(timelineNodes: ProductTimelineNode[], definitions: ProjectTimelineDefinition[]) {
  return definitions.map((stage, stageIndex) => {
    const childNodes = timelineNodes
      .map((node, index) => ({ node, stepNo: index + 1 }))
      .filter(({ node, stepNo }) => stageMatchesNode(stage, node, stepNo))

    const representativeNode =
      childNodes.find(({ node }) => node.status === 'current')?.node ||
      childNodes.find(({ node }) => node.status === 'rejected')?.node ||
      childNodes[childNodes.length - 1]?.node ||
      null

    const currentStageIndex = definitions.findIndex((item) =>
      timelineNodes.some((node, index) => node.status === 'current' && stageMatchesNode(item, node, index + 1))
    )

    let status: ProductFlowStageStatus = 'pending'
    if (childNodes.some(({ node }) => node.status === 'rejected')) {
      status = 'rejected'
    } else if (childNodes.some(({ node }) => node.status === 'current')) {
      status = 'current'
    } else if (childNodes.length && childNodes.every(({ node }) => node.status === 'completed')) {
      status = 'completed'
    } else if (currentStageIndex >= 0 && stageIndex < currentStageIndex) {
      status = 'completed'
    }

    return {
      stageKey: stage.nodeKey,
      stageName: stage.title,
      phaseName: stage.phase,
      status,
      summary: representativeNode?.experienceSummary || representativeNode?.summary || stage.hint,
      receiverRole: representativeNode?.receiverRole,
      receiverUserName: representativeNode?.receiverUserName,
      receivedAt: representativeNode?.receivedAt || representativeNode?.actualDate,
      promoterRole: representativeNode?.promoterRole || representativeNode?.ownerRole,
      promoterUserName: representativeNode?.promoterUserName,
      promotedAt: representativeNode?.promotedAt || representativeNode?.actualDate,
      nextAction: representativeNode?.nextAction || (status === 'completed' ? '已完成' : '待推进'),
      riskNote: representativeNode?.riskNote,
      childNodes:
        childNodes.length > 0
          ? childNodes.map(({ node, stepNo }) => ({
              stepNo,
              nodeKey: node.nodeKey,
              nodeCode: node.nodeKey,
              nodeName: node.nodeName,
              status: node.status,
              actionLabel: node.nextAction,
              processConfirmation: isProcessConfirmationNode({
                nodeKey: node.nodeKey,
                nodeCode: node.nodeKey,
                nodeName: node.nodeName
              })
            }))
          : stage.childNodes.map((nodeName, index) => ({
              stepNo: stage.childStepNos[index] || index + 1,
              nodeKey: `${stage.nodeKey}-${index}`,
              nodeCode: `${stage.nodeKey}-${index}`,
              nodeName,
              status: (status === 'completed' ? 'completed' : 'pending') as ProductFlowStageStatus,
              processConfirmation: isProcessConfirmationNode({
                nodeKey: `${stage.nodeKey}-${index}`,
                nodeCode: `${stage.nodeKey}-${index}`,
                nodeName
              })
            }))
    }
  })
}

const productFlowStages = computed<ProductFlowStage[]>(() => {
  if (timelineFlowStages.value?.length) return timelineFlowStages.value
  const timelineNodes = detailPresentation.value?.timeline || []
  return buildFlowStages(timelineNodes, newProductLineTimeline)
})

const isMoldTransferNode = computed(() =>
  activeProductFlowNode.value?.nodeKey === 'PRODUCT_LINE_MOLD_TRANSFER' ||
  activeProductFlowNode.value?.nodeKey === 'MODEL_VARIANT_MOLD_TRANSFER'
)

const activeProductFlowStage = computed(() => {
  return (
    productFlowStages.value.find((stage) => stage.stageKey === activeProductFlowStageKey.value) ||
    productFlowStages.value.find((stage) => stage.status === 'current') ||
    productFlowStages.value[0] ||
    null
  )
})

const skuProjectFlowStages = computed<ProductFlowStage[]>(() => {
  if (timelineFlowStages.value?.length) return timelineFlowStages.value
  const timelineNodes = detailPresentation.value?.timeline || []
  return buildFlowStages(timelineNodes, modelVariantTimeline)
})

const skuProjectFlowRows = computed<SkuProjectFlowRow[]>(() => {
  const timelineNodes = detailPresentation.value?.timeline || []
  return timelineNodes.map((node, index) => {
    const stage = modelVariantTimeline.find((item) => stageMatchesNode(item, node, index + 1))
    return {
      seqNo: index + 1,
      stageKey: stage?.nodeKey || node.phaseName || node.nodeKey,
      stageName: stage?.title || node.phaseName || '新型号线流程',
      nodeKey: node.nodeKey,
      nodeName: node.nodeName,
      phaseName: node.phaseName || stage?.phase || '--',
      status: node.status,
      experienceSummary: node.experienceSummary || node.summary || '--',
      receiverRole: node.receiverRole,
      receiverUserName: node.receiverUserName,
      receivedAt: node.receivedAt || node.actualDate,
      promoterRole: node.promoterRole || node.ownerRole,
      promoterUserName: node.promoterUserName,
      promotedAt: node.promotedAt || node.actualDate,
      nextAction: node.nextAction,
      riskNote: node.riskNote,
      gateLabel: node.gateLabel,
      documentCount: node.documentCount
    }
  })
})

const currentSkuProjectFlowRow = computed(() =>
  skuProjectFlowRows.value.find((row) => row.status === 'current') || skuProjectFlowRows.value[0] || null
)

const activeSkuFlowStage = computed(() => {
  return (
    skuProjectFlowStages.value.find((stage) => stage.stageKey === activeSkuFlowStageKey.value) ||
    skuProjectFlowStages.value.find((stage) => stage.status === 'current') ||
    skuProjectFlowStages.value[0] ||
    null
  )
})

const canEditDetailTarget = computed(() => {
  if (!detailTarget.value) return false
  return detailTarget.value.lockStatus !== 'abandoned'
})

const canFreezeDetailProject = computed(() =>
  Boolean(detailTarget.value && detailTarget.value.status !== 'archived')
)
const canPublishDetailProject = computed(() =>
  Boolean(isProductDetailDialog.value && detailTarget.value && !['released', 'archived'].includes(detailTarget.value.status))
)
const canStopDetailProject = computed(() =>
  Boolean(detailTarget.value && !['released', 'archived'].includes(detailTarget.value.status))
)
const canArchiveDetailProject = computed(() =>
  Boolean(detailTarget.value?.status !== 'archived')
)

const activeBomVersionSummary = computed<BomCompareRow | null>(() => {
  return (
    detailPresentation.value?.bomCompareRows.find((item) => item.versionNo === detailBomVersion.value) ||
    null
  )
})

const processDetailRows = computed<ProductProcessDetailRow[]>(() =>
  (detailPresentation.value?.processRoutes || []).map((row) => ({
    sequenceNo: row.sequenceNo,
    processCode: row.processCode,
    processName: row.processName,
    processType: row.processType,
    workstationName: row.workstationName,
    supplierName: row.supplierName,
    confirmerName: getProcessConfirmerName(row),
    confirmerRole: getProcessConfirmerRole(row),
    processParamSummary: row.summary || row.inventoryName || '--',
    qualityRequirement: row.qualityRequirement || '--',
    unitProcessCost: getProcessCost(row),
    tags: getProcessTags(row)
  }))
)

const filteredProcessDetailRows = computed(() => {
  const keyword = processDetailKeyword.value.trim().toLowerCase()
  return processDetailRows.value.filter((row) => {
    const filterMatched =
      processDetailFilter.value === 'all' ||
      row.tags.includes(processDetailFilter.value === 'key' ? 'key' : 'changed')
    const keywordMatched =
      !keyword ||
      row.processCode.toLowerCase().includes(keyword) ||
      row.processName.toLowerCase().includes(keyword) ||
      String(row.workstationName || '').toLowerCase().includes(keyword) ||
      String(row.supplierName || '').toLowerCase().includes(keyword)
    return filterMatched && keywordMatched
  })
})

const productionDocuments = computed(() =>
  (detailPresentation.value?.documents || []).filter((item) => item.stageKey === 'production' || item.category === '生产资料')
)

const detailDocumentStats = computed(() => {
  const documents = detailPresentation.value?.documents || []
  return {
    total: documents.length,
    production: productionDocuments.value.length,
    frozen: documents.filter((item) => item.status === '已冻结' || item.status === '已归档').length
  }
})

function getProjectTypeLabel(row: ProductSummary) {
  if (row.productType === 'product_line') return '新产品线'
  if (row.productType === 'sku') return 'SKU'
  return '新型号线'
}

function getRecentUpdate(row: ProductSummary) {
  return formatDate(row.releasedAt || undefined)
}

function getSkuCountForProduct(product: ProductSummary) {
  const productLineCode = getDisplayProductLineCode(product)
  return archivedSkuRows.value.filter(
    (item) =>
      item.parentProductId === product.productId ||
      item.productCode.startsWith(product.productCode) ||
      Boolean(item.finishedProductCode?.startsWith(product.productCode)) ||
      item.productCode.startsWith(productLineCode) ||
      Boolean(item.finishedProductCode?.startsWith(productLineCode))
  ).length
}

function getDisplayProductLineCode(product: ProductSummary) {
  const productCode = product.productCode || ''
  if (product.productType !== 'product_line' || !productCode.startsWith('PRD-')) {
    return productCode
  }
  const productSpecificCode = product.productSpecificCode?.trim().toUpperCase()
  return productSpecificCode ? `N${productSpecificCode}4030` : productCode
}

function getDisplaySkuCode(product: ProductSummary) {
  return product.finishedProductCode || product.productCode || ''
}

function getDetailDisplayCode(product: ProductSummary) {
  if (product.productType === 'sku') return getDisplaySkuCode(product)
  if (product.productType === 'product_line') return getDisplayProductLineCode(product)
  return product.productCode
}

function getColorUsageLabel(color: { colorCode?: string | null; colorName: string }) {
  return color.colorCode ? `${color.colorCode} ${color.colorName}` : color.colorName
}

function selectProjectQuickTag(tag: ProjectQuickTag) {
  router.push({path:'/projects',query:{tab:'archived',archiveView:tag==='archived_sku'?'sku':'product'}})
}

function openProduct(productId: number) {
  const target = rows.value.find((item) => item.productId === productId)
  if (target) {
    openDetail(target)
    return
  }
  router.push(toArchivedProductRoute(productId))
}

function openArchiveImport(type: 'product' | 'sku') {
  importType.value = type
  resetArchiveImportState()
  importVisible.value = true
}

function resetArchiveImportState() {
  archiveImportFile.value = null
  archiveImportInputKey.value += 1
  archiveImportPreview.value = null
  archiveImportLoading.value = false
}

function closeArchiveImport() {
  importVisible.value = false
  resetArchiveImportState()
}

function handleArchiveImportFileChange(event: Event) {
  archiveImportFile.value = (event.target as HTMLInputElement).files?.[0] || null
  archiveImportPreview.value = null
}

async function submitArchiveImport() {
  if (!archiveImportFile.value) {
    ElMessage.warning(uiText('请先选择要导入的数据文件'))
    return
  }

  archiveImportLoading.value = true
  try {
    const preview = await previewImport('product', archiveImportFile.value)
    archiveImportPreview.value = preview

    if (preview.failCount > 0) {
      ElMessage.warning(uiText('导入预览发现 {value0} 条异常，请修正文件后重新导入',{value0:(preview.failCount)}))
      return
    }

    const result = await commitImport(preview.importToken)
    if(result.successCount>0)projectWorkspace.changed()
    archiveImportPreview.value = result
    if (result.failCount > 0) {
      ElMessage.warning(uiText('导入完成，成功 {value0} 条，失败 {value1} 条',{value0:(result.successCount),value1:(result.failCount)}))
    } else {
      ElMessage.success(uiText('导入成功，共导入 {value0} 条数据',{value0:(result.successCount)}))
      closeArchiveImport()
    }
    await loadData()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    archiveImportLoading.value = false
  }
}

function skuOpenSkuList(productId: number) {
  skuActiveProductId.value = productId
  skuKeyword.value = ''
  skuPageStage.value = 'sku-list'
}

function skuBackToProductHome() {
  skuActiveProductId.value = null
  skuKeyword.value = ''
  skuPageStage.value = 'product-home'
}

function getTimelineActionLabel(action?: string | null) {
  if (action === 'confirm') return '已确认当前节点'
  if (action === 'advance') return '已推进到下一节点'
  if (action === 'return') return '已退回处理'
  return '暂无动作'
}

function getErrorMessage(error: unknown) {
  if (typeof error === 'object' && error && 'response' in error) {
    const response = (error as { response?: { data?: { message?: string } } }).response
    if (response?.data?.message) return response.data.message
  }
  if (error instanceof Error) return error.message
  return '操作失败，请稍后重试'
}

function mapTimelineToPresentationNodes(timeline: TimelineDetailVO): TimelinePresentationNode[] {
  return mapTimelineSteps(timeline).map((step) => ({
    nodeKey: step.nodeKey,
    nodeName: step.stepName,
    status: step.status,
    ownerRole: step.source.ownerRole,
    summary: step.source.summary,
    nextAction: step.source.nextAction,
    riskNote: step.source.riskNote,
    gateLabel: step.source.gateLabel,
    detailLines: step.source.detailLines,
    receiverRole: step.source.receiverRole,
    receiverUserName: step.source.receiverUserName,
    receivedAt: step.source.receivedAt,
    promoterRole: step.source.promoterRole,
    promoterUserName: step.source.promoterUserName,
    promotedAt: step.source.promotedAt,
    experienceSummary: step.source.experienceSummary,
    documentCount: step.documentCount,
    phaseName: step.phaseName,
    stageCode: step.stageCode,
    stageName: step.stageName,
    requiredFileCategory: step.requiredFileCategory,
    confirmed: step.confirmed,
    canAdvance: step.status === 'current' && step.confirmed,
    canReject: step.status === 'current'
  }))
}

function mapTimelineStageToProductFlowStage(stage: TimelineStageView): ProductFlowStage {
  const current = stage.steps.find((step) => step.isCurrent)
    || stage.steps.find((step) => step.status === 'rejected')
    || stage.steps[stage.steps.length - 1]
  return {
    stageKey: stage.stageCode,
    stageName: stage.stageName,
    phaseName: stage.phaseName,
    status: stage.status,
    summary: current?.source.experienceSummary || current?.source.summary || stage.stageName,
    receiverRole: current?.source.receiverRole,
    receiverUserName: current?.source.receiverUserName,
    receivedAt: current?.source.receivedAt,
    promoterRole: current?.source.promoterRole || current?.source.ownerRole,
    promoterUserName: current?.source.promoterUserName,
    promotedAt: current?.source.promotedAt,
    nextAction: current?.source.nextAction || (stage.status === 'completed' ? '已完成' : '待推进'),
    riskNote: current?.source.riskNote,
    childNodes: stage.steps.map((step) => ({
      stepNo: step.stepNo,
      nodeKey: step.nodeKey,
      nodeCode: step.nodeKey,
      nodeName: step.stepName,
      status: step.status,
      actionLabel: step.source.nextAction,
      documentCount: step.documentCount,
      visualStatus: step.visualStatus,
      processConfirmation: isProcessConfirmationNode({
        nodeKey: step.nodeKey,
        nodeCode: step.nodeKey,
        nodeName: step.stepName
      })
    }))
  }
}

function applyTimelineMetadata(timeline: TimelineDetailVO) {
  timelineStarted.value = timeline.started
  timelineStartBlockReason.value = timeline.startBlockReason || null
  timelineCompleted.value = Boolean(timeline.timelineCompleted)
  timelineCurrentConfirmed.value = timeline.currentConfirmed ?? null
  timelineLastAction.value = timeline.lastAction || null
  timelineLastReason.value = timeline.lastReason || null
  timelineLastOperatedAt.value = timeline.lastOperatedAt || null
  timelineLastOperatorUserName.value = timeline.lastOperatorUserName || null
  applyMoldTransferExpress(timeline.moldTransferExpress || null)
}

function applyMoldTransferExpress(express: MoldTransferExpressVO | null) {
  moldTransferExpress.value = express
  moldTransferForm.trackingNo = express?.trackingNo || ''
  moldTransferForm.shippedAt = express?.shippedAt || ''
}

function buildPresentationFallback(row: ProductSummary): ProductDetailPresentation {
  return {
    productId: row.productId,
    title: row.productName,
    flowLabel: row.productType === 'model_variant' ? '新型号线' : '新产品线',
    currentNode: row.currentStage || '--',
    nextNode: row.nextAction || '--',
    summary: `${row.productCode} / ${row.seriesName || '--'}`,
    costPanel: { showEstimated: false, actualTotal: 0, actualLines: [] },
    timeline: [],
    bomCompareRows: [],
    bomItems: [],
    bomItemsByVersion: {},
    toolingSummary: { totalCount: 0, availableCount: 0, trialCount: 0, toolingNames: [] },
    materialCategories: [],
    suppliers: [],
    documents: [],
    qualityRecords: [],
    processRoutes: []
  }
}

async function refreshProjectTimeline(projectId: number) {
  if (!detailPresentation.value) return
  const timeline = await getProjectTimeline(projectId)
  applyTimelineMetadata(timeline)
  const nodes = mapTimelineToPresentationNodes(timeline)
  const currentNode = nodes.find((node) => node.status === 'current')
  const displayNode = currentNode || (timeline.timelineCompleted ? nodes[nodes.length - 1] : null)
  const currentStep = findCurrentTimelineStep(timeline)
  timelineFlowStages.value = mapTimelineStages(timeline).map(mapTimelineStageToProductFlowStage)
  detailPresentation.value = {
    ...detailPresentation.value,
    currentNode: displayNode?.nodeName || timeline.currentNode || detailPresentation.value.currentNode,
    nextNode: displayNode?.nextAction || (timeline.timelineCompleted ? '已完成' : detailPresentation.value.nextNode),
    timeline: timeline.started === false ? [] : nodes.length ? nodes : detailPresentation.value.timeline
  }
  if (detailTarget.value) {
    detailTarget.value = {
      ...detailTarget.value,
      currentStage: displayNode?.nodeName || detailTarget.value.currentStage,
      currentStepNo: timeline.currentStepNo
    }
  }
  activeProductFlowStageKey.value = currentStep?.stageCode || displayNode?.stageCode || timelineFlowStages.value[0]?.stageKey || activeProductFlowStageKey.value
  activeSkuFlowStageKey.value = currentStep?.stageCode || displayNode?.stageCode || timelineFlowStages.value[0]?.stageKey || activeSkuFlowStageKey.value
}

async function handleM4AttachmentChanged() {
  if (!detailTarget.value) return
  await refreshProjectTimeline(detailTarget.value.productId)
}

async function refreshDetailAfterChildChange() {
  projectWorkspace.changed()
  if (!detailTarget.value) return
  try {
    const projectDetail = await getProjectDetail(detailTarget.value.productId)
    detailTarget.value = { ...detailTarget.value, ...projectDetail }
    await loadData()
  } catch {
    // 子页面已完成保存，详情刷新失败时保留当前页面数据。
  }
}

async function handleLifecycleChanged() {
  projectWorkspace.changed()
  if (!detailTarget.value) return
  await refreshProjectTimeline(detailTarget.value.productId)
  await loadData()
}

async function handleProductionConfirmationConfirmed() {
  if (!detailTarget.value) return
  const projectId = detailTarget.value.productId
  const shouldCompleteModelVariant = productionConfirmationMode.value === 'colors'
    && detailTarget.value.productType === 'model_variant'
    && activeProductFlowNode.value?.nodeKey === 'MODEL_VARIANT_MOLD_TRANSFER'
  try {
    if (shouldCompleteModelVariant) {
      timelineActionLoading.value = 'confirm'
      await confirmTimelineNode(projectId, 'MODEL_VARIANT_MOLD_TRANSFER', '投产颜色已确认，移交并创建 SKU')
      projectWorkspace.changed()
      ElMessage.success(uiText('新型号已完成，正在移交钉钉审批'))
    }
    await refreshDetailAfterChildChange()
    await refreshProjectTimeline(projectId)
  } catch (error) {
    ElMessage.error(shouldCompleteModelVariant
      ? uiText('SKU 已创建，但最终节点确认失败：{value0}',{value0:(getErrorMessage(error))})
      : getErrorMessage(error))
  } finally {
    timelineActionLoading.value = false
  }
}

function applyDetailLifecycleResult(result: { status: ProductSummary['status']; lockStatus?: string | null; releasedAt?: string | null }) {
  if (!detailTarget.value) return
  detailTarget.value = {
    ...detailTarget.value,
    status: result.status,
    lockStatus: result.lockStatus ?? detailTarget.value.lockStatus,
    releasedAt: result.releasedAt ?? detailTarget.value.releasedAt
  }
}

async function handleDetailFreezeProject() {
  if (!detailTarget.value || detailLifecycleLoading.value || !canFreezeDetailProject.value) return
  try {
    await ElMessageBox.confirm(uiText('冻结会锁定当前 Product 资料状态，用于发布前版本留痕。'), uiText('冻结项目版本'), {
      confirmButtonText: uiText('冻结'),
      cancelButtonText: uiText('取消'),
      type: 'warning'
    })
    detailLifecycleLoading.value = 'freeze'
    const result = await freezeProject(detailTarget.value.productId, { reason: '项目详情头部冻结' })
    applyDetailLifecycleResult(result)
    await handleLifecycleChanged()
    ElMessage.success(uiText('项目版本已冻结'))
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(getErrorMessage(error))
  } finally {
    detailLifecycleLoading.value = false
  }
}

async function handleDetailPublishProject() {
  if (!detailTarget.value || detailLifecycleLoading.value || !canPublishDetailProject.value) return
  try {
    const gate = await checkProjectReleaseGate(detailTarget.value.productId)
    const blocking = Boolean(gate.blocking || (!gate.passed && !gate.confirmRequired))
    if (blocking) {
      ElMessage.error(uiText('当前流程尚未满足发布条件，请先完成基础流程'))
      return
    }
    await ElMessageBox.confirm(
      buildReleaseConfirmationMessage(gate),
      gate.confirmRequired ? uiText('确认带风险发布') : uiText('发布 Product'),
      {
      confirmButtonText: uiText('发布'),
      cancelButtonText: uiText('取消'),
      type: 'warning'
      }
    )
    detailLifecycleLoading.value = 'publish'
    const result = await publishProject(detailTarget.value.productId, {
      reason: '项目详情头部发布',
      riskConfirmed: Boolean(gate.confirmRequired)
    })
    applyDetailLifecycleResult(result)
    await handleLifecycleChanged()
    ElMessage.success(uiText('Product 已发布'))
  } catch (error) {
    const gateFromError = getReleaseGateFromError(error)
    if (gateFromError) {
      ElMessage.error(
        getReleaseGateErrorCode(error) === 40308
          ? uiText('检测到发布资料风险，请确认后重试')
          : uiText('当前流程尚未满足发布条件')
      )
      return
    }
    if (error !== 'cancel' && error !== 'close') ElMessage.error(getErrorMessage(error))
  } finally {
    detailLifecycleLoading.value = false
  }
}

function getReleaseGateFromError(error: unknown): ProductReleaseGateCheckVO | null {
  const responseData = (error as { response?: { data?: { code?: number; data?: ProductReleaseGateCheckVO } } })
    ?.response?.data
  if ((responseData?.code === 40307 || responseData?.code === 40308) && responseData.data) return responseData.data
  return null
}

function getReleaseGateErrorCode(error: unknown) {
  return (error as { response?: { data?: { code?: number } } })?.response?.data?.code
}

function buildReleaseConfirmationMessage(gate: ProductReleaseGateCheckVO) {
  const risks = (gate.missingItems || [])
    .filter((item) => item.severity === 'warning' || !item.severity)
    .map((item) => `• ${item.message}`)
    .join('\n')
  if (!risks) return '发布后 Product 将进入 released 状态，已发布版本不允许直接编辑。'
  return `当前产品存在资料缺口，发布后仍会进入 released 状态：\n${risks}\n\n确认继续发布？`
}

async function handleDetailArchiveProject() {
  if (!detailTarget.value || detailLifecycleLoading.value || !canArchiveDetailProject.value) return
  try {
    const { value } = await ElMessageBox.prompt(uiText('请输入归档原因，便于后续追溯。'), uiText('归档项目'), {
      confirmButtonText: uiText('归档'),
      cancelButtonText: uiText('取消'),
      inputPlaceholder: uiText('例如：发布版本停用，进入历史归档')
    })
    detailLifecycleLoading.value = 'archive'
    const result = await archiveProject(detailTarget.value.productId, { reason: String(value || '').trim() || '项目详情头部归档' })
    applyDetailLifecycleResult(result)
    await handleLifecycleChanged()
    ElMessage.success(uiText('项目已归档'))
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(getErrorMessage(error))
  } finally {
    detailLifecycleLoading.value = false
  }
}

async function handleDetailStopProject() {
  if (!detailTarget.value || detailLifecycleLoading.value || !canStopDetailProject.value) return
  try {
    const { value } = await ElMessageBox.prompt(uiText('请输入停止原因。停止后项目会进入已停止/归档，并同步关闭关联 Order。'), uiText('停止项目'), {
      confirmButtonText: uiText('确认停止'),
      cancelButtonText: uiText('取消'),
      inputPlaceholder: uiText('例如：客户取消需求，项目停止推进'),
      inputValidator: (value) => Boolean(String(value || '').trim()) || uiText('请填写停止原因')
    })
    detailLifecycleLoading.value = 'abandon'
    const result = await abandonProject(detailTarget.value.productId, { reason: String(value || '').trim() })
    applyDetailLifecycleResult(result)
    await handleLifecycleChanged()
    ElMessage.success(uiText('项目已停止并归档'))
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(getErrorMessage(error))
  } finally {
    detailLifecycleLoading.value = false
  }
}

async function saveCurrentMoldTransferExpress() {
  if (!detailTarget.value || !activeProductFlowNode.value) return
  if (!moldTransferForm.trackingNo.trim()) {
    ElMessage.warning(uiText('请填写快递单号'))
    return
  }
  moldTransferLoading.value = true
  try {
    const saved = await saveMoldTransferExpress(detailTarget.value.productId, activeProductFlowNode.value.nodeKey, {
      trackingNo: moldTransferForm.trackingNo.trim(),
      shippedAt: moldTransferForm.shippedAt || undefined
    })
    applyMoldTransferExpress(saved)
    await refreshProjectTimeline(detailTarget.value.productId)
    ElMessage.success(uiText('运模快递单号已保存'))
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    moldTransferLoading.value = false
  }
}

async function handleConfirmCurrentNode() {
  if (!detailTarget.value || !activeProductFlowNode.value || timelineActionLoading.value) return
  try {
    const { value } = await ElMessageBox.prompt(uiText('可填写确认备注，留空则只确认当前节点。'), uiText('确认当前节点'), {
      confirmButtonText: uiText('确认'),
      cancelButtonText: uiText('取消'),
      inputPlaceholder: uiText('例如：资料已检查，进入可推进状态')
    })
    timelineActionLoading.value = 'confirm'
    await confirmTimelineNode(detailTarget.value.productId, activeProductFlowNode.value.nodeKey, String(value || ''))
    projectWorkspace.changed()
    await refreshProjectTimeline(detailTarget.value.productId)
    await loadData()
    ElMessage.success(uiText('节点已确认'))
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(getErrorMessage(error))
  } finally {
    timelineActionLoading.value = false
  }
}

async function handleAdvanceCurrentNode() {
  if (!detailTarget.value || !activeProductFlowNode.value || timelineActionLoading.value) return
  if (!canAdvanceCurrentTimelineNode.value) {
    ElMessage.warning(currentTimelineConfirmed.value ? uiText('最后一个节点不能继续推进') : uiText('请先确认当前节点'))
    return
  }
  try {
    await ElMessageBox.confirm(uiText('推进后项目会进入下一节点，新节点会回到未确认状态。'), uiText('推进下一节点'), {
      confirmButtonText: uiText('推进'),
      cancelButtonText: uiText('取消'),
      type: 'warning'
    })
    timelineActionLoading.value = 'confirm'
    await confirmTimelineNode(detailTarget.value.productId, activeProductFlowNode.value.nodeKey)
    projectWorkspace.changed()
    await refreshProjectTimeline(detailTarget.value.productId)
    await loadData()
    ElMessage.success(uiText('已推进到下一节点'))
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(getErrorMessage(error))
  } finally {
    timelineActionLoading.value = false
  }
}

async function handleReturnCurrentNode(returnToPrevious: boolean) {
  if (!detailTarget.value || !activeProductFlowNode.value || timelineActionLoading.value) return
  try {
    const { value } = await ElMessageBox.prompt(
      returnToPrevious ? uiText('请输入退回上一节点的原因。') : uiText('请输入退回当前节点修改的原因。'),
      returnToPrevious ? uiText('退回上一节点') : uiText('退回当前节点修改'),
      {
        confirmButtonText: uiText('退回'),
        cancelButtonText: uiText('取消'),
        inputPlaceholder: uiText('必须填写，例如：图纸资料需补充'),
        inputValidator: (value) => Boolean(String(value || '').trim()),
        inputErrorMessage: uiText('请填写退回原因')
      }
    )
    timelineActionLoading.value = 'return'
    await returnTimelineNode(
      detailTarget.value.productId,
      activeProductFlowNode.value.nodeKey,
      String(value || ''),
      returnToPrevious
    )
    projectWorkspace.changed()
    await refreshProjectTimeline(detailTarget.value.productId)
    await loadData()
    ElMessage.success(uiText('已退回处理'))
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(getErrorMessage(error))
  } finally {
    timelineActionLoading.value = false
  }
}

function handleReturnCommand(command: string | number | object) {
  handleReturnCurrentNode(command === 'previous')
}

async function openDetail(row: ProductSummary) {
  detailPresentation.value = null
  detailBomVersion.value = ''
  detailTarget.value = row
  basicInfoEditing.value = false
  basicInfoExtra.value = null
  applyBasicInfoToForm(buildBasicInfoFromTarget())
  const routeSection = String(route.query.section || '')
  activeDetailSection.value = activeDetailSections.value.some((section) => section.key === routeSection)
    ? (routeSection as DetailSectionKey)
    : 'project_flow'
  activeProductFlowStageKey.value = ''
  activeSkuFlowStageKey.value = ''
  processDetailFilter.value = 'all'
  processDetailKeyword.value = ''
  timelineCurrentConfirmed.value = null
  timelineStarted.value = null
  timelineStartBlockReason.value = null
  timelineCompleted.value = false
  timelineLastAction.value = null
  timelineLastReason.value = null
  timelineLastOperatedAt.value = null
  timelineLastOperatorUserName.value = null
  timelineFlowStages.value = null
  detailVisible.value = true
  detailLoading.value = true
  try {
    try {
      const projectDetail = await getProjectDetail(row.productId)
      if (projectDetail.developmentMode==='launch_v1') {
        detailVisible.value = false
        await router.replace({ path: '/product-research', query: { productId: String(row.productId) } })
        return
      }
      detailTarget.value = { ...row, ...projectDetail }
      normalizeProjectDetailRoute(detailTarget.value)
    } catch {
      detailTarget.value = row
      normalizeProjectDetailRoute(detailTarget.value)
    }
    let presentation = buildPresentationFallback(row)
    try {
      presentation = await getProductPresentation(row.productId)
    } catch {
      presentation = buildPresentationFallback(row)
    }
    detailPresentation.value = presentation
    const timeline = await getProjectTimeline(row.productId)
    applyTimelineMetadata(timeline)
    const nodes = mapTimelineToPresentationNodes(timeline)
    const currentNode = nodes.find((node) => node.status === 'current')
    const displayNode = currentNode || (timeline.timelineCompleted ? nodes[nodes.length - 1] : null)
    const currentStep = findCurrentTimelineStep(timeline)
    timelineFlowStages.value = mapTimelineStages(timeline).map(mapTimelineStageToProductFlowStage)
    detailPresentation.value = {
      ...presentation,
      currentNode: displayNode?.nodeName || timeline.currentNode || presentation.currentNode,
      nextNode: displayNode?.nextAction || (timeline.timelineCompleted ? '已完成' : presentation.nextNode),
      timeline: timeline.started === false ? [] : nodes.length ? nodes : presentation.timeline
    }
    activeProductFlowStageKey.value = currentStep?.stageCode || displayNode?.stageCode || timelineFlowStages.value[0]?.stageKey || ''
    activeSkuFlowStageKey.value = currentStep?.stageCode || displayNode?.stageCode || timelineFlowStages.value[0]?.stageKey || ''
    detailBomVersion.value =
      detailPresentation.value.defaultBomVersion ||
      detailPresentation.value.bomCompareRows.find((item) => item.statusLabel === '当前')?.versionNo ||
      detailPresentation.value.bomCompareRows[0]?.versionNo ||
      ''
  } catch {
    detailPresentation.value = null
    detailBomVersion.value = ''
  } finally {
    detailLoading.value = false
  }
}

function openDetailEdit() {
  if (!detailTarget.value || !canEditDetailTarget.value) return
  activeDetailSection.value = 'project_flow'
  basicInfoEditing.value = true
  void loadBasicInfoForEdit()
}

function getBomRemark(row: ProductBomItemRow) {
  if (row.changeType === 'new') return '新增物料'
  if (row.changeType === 'replace') return '替代料'
  if (row.changeType === 'remove') return '本版本移除'
  if (row.changeType === 'inherit') return '沿用父产品'
  return '--'
}

function getBomLineCost(row: ProductBomItemRow) {
  return Number(row.unitCost || 0) * Number(row.quantity || 0)
}

function getDocumentStatusType(status?: string) {
  if (status === '已冻结' || status === '已归档') return 'success'
  if (status === '草稿') return 'warning'
  return 'info'
}

function getFlowStatusLabel(status: ProductFlowStageStatus) {
  if (status === 'completed') return '已完成'
  if (status === 'current') return '进行中'
  if (status === 'rejected') return '已驳回'
  return '待开始'
}

function getFlowStatusType(status: ProductFlowStageStatus) {
  if (status === 'completed') return 'success'
  if (status === 'current') return 'warning'
  if (status === 'rejected') return 'danger'
  return 'info'
}

function getProcessTypeLabel(type: string) {
  if (type === 'quality_gate') return '质量门禁'
  if (type === 'packaging') return '包装'
  if (type === 'outsourced') return '外协'
  return '工序'
}

function getProcessConfirmerName(row: SkuProcessRouteRow) {
  if (row.processType === 'quality_gate') return '王质'
  if (row.supplierName) return '李采'
  if (row.processName.includes('包装')) return '陈包'
  return '刘浩'
}

function applyBasicInfoToForm(info: Partial<ProductBasicInfo>) {
  basicInfoForm.productName = info.productName || ''
  basicInfoForm.seriesName = info.seriesName || ''
  basicInfoForm.ownerUserName = info.ownerUserName || detailTarget.value?.ownerUserName || 'system'
  basicInfoForm.model = info.model || ''
  basicInfoForm.color = info.color || ''
  basicInfoForm.material = info.material || ''
  basicInfoForm.packageType = info.packageType || ''
  basicInfoForm.surfaceProcess = info.surfaceProcess || ''
  basicInfoForm.coreProcess = info.coreProcess || ''
  basicInfoForm.composition = info.composition || ''
  basicInfoForm.expectedReleaseDate = info.expectedReleaseDate || ''
  basicInfoForm.expectedArrivalAt = info.expectedArrivalAt || ''
  basicInfoForm.actualArrivalAt = info.actualArrivalAt || ''
  basicInfoForm.networkType = info.networkType || ''
  basicInfoForm.holeType = info.holeType || ''
  basicInfoForm.mobileFunction = info.mobileFunction || ''
  basicInfoForm.tipo = info.tipo || ''
  basicInfoForm.priority = info.priority || ''
  basicInfoForm.manufacturingLocation = info.manufacturingLocation || ''
  basicInfoForm.moldMarking = info.moldMarking || ''
  basicInfoForm.referenceUrl = info.referenceUrl || ''
  basicInfoForm.requirementType = info.requirementType || ''
  basicInfoForm.customerRequirement = info.customerRequirement || ''
}

function buildBasicInfoFromTarget(): Partial<ProductBasicInfo> {
  const target = detailTarget.value
  if (!target) return {}
  return {
    productName: target.productName,
    seriesName: target.seriesName,
    ownerUserName: target.ownerUserName,
    model: target.model,
    color: target.color,
    material: target.material,
    expectedArrivalAt: target.expectedArrivalAt || null,
    actualArrivalAt: target.actualArrivalAt || null
  }
}

async function loadBasicInfoForEdit() {
  if (!detailTarget.value) return
  const fallback = buildBasicInfoFromTarget()
  applyBasicInfoToForm({ ...fallback, ...(basicInfoExtra.value || {}) })
  try {
    const detail = await getProductBasicDetail(detailTarget.value.productId)
    basicInfoExtra.value = detail.basicInfo
    applyBasicInfoToForm({ ...fallback, ...detail.basicInfo })
  } catch {
    // 项目详情已有基础字段，完整 Product 详情暂时不可用时仍允许编辑当前可见信息。
  }
}

function applySavedBasicInfo(info: ProductBasicInfo) {
  basicInfoExtra.value = info
  if (!detailTarget.value) return
  detailTarget.value = {
    ...detailTarget.value,
    productName: info.productName || detailTarget.value.productName,
    seriesName: info.seriesName || detailTarget.value.seriesName,
    model: info.model || detailTarget.value.model,
    color: info.color || detailTarget.value.color,
    material: info.material || detailTarget.value.material,
    expectedArrivalAt: info.expectedArrivalAt || detailTarget.value.expectedArrivalAt,
    actualArrivalAt: info.actualArrivalAt || detailTarget.value.actualArrivalAt
  }
}

async function saveBasicInfo() {
  if (!detailTarget.value || basicInfoSaving.value) return
  if (!basicInfoForm.productName.trim()) {
    ElMessage.warning(uiText('{value0}名称不能为空',{value0:(detailObjectLabel.value)}))
    return
  }
  basicInfoSaving.value = true
  try {
    const saved = await updateProductBasicInfo(detailTarget.value.productId, {
      productName: basicInfoForm.productName,
      seriesName: basicInfoForm.seriesName,
      ownerUserName: basicInfoForm.ownerUserName || detailTarget.value.ownerUserName || 'system',
      model: basicInfoForm.model,
      color: basicInfoForm.color,
      material: basicInfoForm.material,
      packageType: basicInfoForm.packageType,
      surfaceProcess: basicInfoForm.surfaceProcess,
      coreProcess: basicInfoForm.coreProcess,
      composition: basicInfoForm.composition,
      expectedReleaseDate: basicInfoForm.expectedReleaseDate,
      expectedArrivalAt: basicInfoForm.expectedArrivalAt || null,
      actualArrivalAt: basicInfoForm.actualArrivalAt || null,
      networkType: basicInfoForm.networkType,
      holeType: basicInfoForm.holeType,
      mobileFunction: basicInfoForm.mobileFunction,
      tipo: basicInfoForm.tipo,
      priority: basicInfoForm.priority,
      manufacturingLocation: basicInfoForm.manufacturingLocation,
      moldMarking: basicInfoForm.moldMarking,
      referenceUrl: basicInfoForm.referenceUrl,
      requirementType: basicInfoForm.requirementType,
      customerRequirement: basicInfoForm.customerRequirement
    })
    applySavedBasicInfo(saved.basicInfo)
    basicInfoEditing.value = false
    await refreshDetailAfterChildChange()
    applySavedBasicInfo(saved.basicInfo)
    ElMessage.success(uiText('基础信息已保存'))
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    basicInfoSaving.value = false
  }
}

function cancelBasicInfoEdit() {
  basicInfoEditing.value = false
  applyBasicInfoToForm({ ...buildBasicInfoFromTarget(), ...(basicInfoExtra.value || {}) })
}

function getProcessConfirmerRole(row: SkuProcessRouteRow) {
  if (row.processType === 'quality_gate') return '品质确认'
  if (row.supplierName) return '采购确认'
  if (row.processName.includes('包装')) return '包装确认'
  return '工程确认'
}

function getProcessCost(row: SkuProcessRouteRow) {
  if (row.processType === 'quality_gate') return 0.4
  if (row.processName.includes('包装')) return 0.3
  if (row.processName.includes('喷') || row.processName.includes('表面')) return 1.2
  if (row.supplierName) return 1.6
  return 0.8
}

function getProcessTags(row: SkuProcessRouteRow): ProductProcessDetailTag[] {
  const tags: ProductProcessDetailTag[] = []
  if (row.processType === 'quality_gate' || row.processName.includes('磁铁') || row.processName.includes('镜面')) tags.push('key')
  if (row.processName.includes('改') || row.processName.includes('黑色') || row.processName.includes('表面')) tags.push('changed')
  if (row.supplierName) tags.push('outsourced')
  return tags
}

function openProductionDocPreview(file: { fileId?: string; fileName: string; category: string; versionNo: string; owner?: string; updatedAt: string; status?: string; previewUrl?: string; downloadUrl?: string }) {
  activeProductionDoc.value = {
    fileId: file.fileId || '',
    fileName: file.fileName,
    category: file.category,
    versionNo: file.versionNo,
    owner: file.owner,
    updatedAt: file.updatedAt,
    status: file.status,
    previewUrl: file.previewUrl,
    downloadUrl: file.downloadUrl
  }
  productionPreviewVisible.value = true
}

let loadGeneration=0
const isFoundation = computed(()=>['product','sku'].includes(String(route.query.archiveView||'')))
const projectWorkspace=useCollaborationWorkspace()
async function loadData() {
  if(!isFoundation.value)return
  const epoch=++loadGeneration,token=user.token
  selectedProductionRows.value=[]
  loading.value = true
  try {
    const archivedProductParams = { page: 1, size: 200, status: 'archived', productType: 'product_line',...productionParams() }
    const releasedProductParams = { page: 1, size: 200, status: 'released', productType: 'product_line',...productionParams() }
    const archivedSkuParams = { page: 1, size: 200, status: 'archived', productType: 'model_variant',...productionParams() }
    const results = await Promise.allSettled([
      loadAllProjectPages(releasedProductParams),
      loadAllProjectPages(archivedProductParams),
      archiveView.value==='sku'?loadAllProjectPages(archivedSkuParams):Promise.resolve([])
    ])
    const parents=productionFilter.value?await Promise.all([loadAllProjectPages({page:1,size:200,status:'archived',productType:'product_line'}),loadAllProjectPages({page:1,size:200,status:'released',productType:'product_line'})]):[]
    if(epoch!==loadGeneration||token!==user.token)return
    skuParentRows.value=parents.flat()
    const mergedRows = new Map<number, ProductSummary>()
    results.forEach((result) => {
      if (result.status === 'fulfilled' && Array.isArray(result.value)) {
        result.value.forEach((item) => mergedRows.set(item.productId, item))
      }
    })
    rows.value = Array.from(mergedRows.values())
  } finally {
    if(epoch===loadGeneration)loading.value = false
  }
}

async function loadAllProjectPages(params: { page: number; size: number; status?: string; productType?: string }) {
  const size = params.size
  const values: ProductSummary[] = []
  for (let page = params.page; page <= 30; page += 1) {
    const pageRows = await getProjects({ ...params, page, size })
    if (!Array.isArray(pageRows) || pageRows.length === 0) break
    values.push(...pageRows)
    if (pageRows.length < size) break
  }
  return values
}

watch(
  () => route.query.archiveView,
  () => {
    skuPageStage.value = 'product-home'
    skuActiveProductId.value = null
    if(isFoundation.value)void loadData()
  }
)

watch(
  () => [route.query.archiveView, route.query.productId, route.query.skuId, route.query.section, rows.value.length],
  async () => {
    if(!isFoundation.value&&!route.query.section){detailVisible.value=false;return}
    const productId = Number(route.query.productId || 0)
    if (productId) {
      const target = (archiveView.value === 'product' ? archivedProductRows.value : rows.value).find((item) => item.productId === productId)
        || rows.value.find((item) => item.productId === productId)
      if (target) {
        openDetail(target)
      } else if (!routeDetailLoading.value && detailTarget.value?.productId !== productId) {
        routeDetailLoading.value = true
        try {
          const detail = await getProjectDetail(productId)
          normalizeProjectDetailRoute(detail)
          openDetail(detail)
        } catch (error) {
          ElMessage.error(getErrorMessage(error))
        } finally {
          routeDetailLoading.value = false
        }
      }
      return
    }

    const skuId = Number(route.query.skuId || 0)
    if (skuId && archiveView.value === 'sku') {
      const target = archivedSkuRows.value.find((item) => item.productId === skuId) || rows.value.find((item) => item.productId === skuId)
      if (target) openDetail(target)
    }
  },
  { immediate: true }
)

watch([projectQuickTag,skuPageStage],()=>{selectedProductionRows.value=[]})
watch(()=>user.token,()=>{loadGeneration++;rows.value=[];skuParentRows.value=[];selectedProductionRows.value=[];productionTargets.value=[];productionEditor.value=false})
watch(isFoundation,()=>{if(isFoundation.value)void loadData()})
onMounted(()=>{void productionPermissions.ensure();void loadData()})
</script>

<template>
  <PageContainer
    :class="{'production-project-page':isFoundation,'workbench-project-page':!isFoundation}"
    :title="isFoundation?uiText('产品平台与型号'):uiText('项目中心')"
  >
    <div v-if="isFoundation" class="foundation-maintenance-toolbar"><el-button v-if="foundationMasterHome" size="small" @click="toggleFoundationSource">{{foundationSourceMode?uiText('返回产品基础资料'):uiText('来源型号目录（CRM／MES）')}}</el-button><el-button v-if="!foundationMasterHome" size="small" @click="openProductMaintenance()">{{ uiText("产品资料：查看／编辑／停用／删除") }}</el-button><el-button v-if="!foundationMasterHome&&(user.profile?.canMaintainEngineeringData||user.hasPermission('*'))" size="small" type="primary" @click="openProductMaintenance(undefined,true)">{{ uiText("新增产品资料") }}</el-button></div>
    <ProductMasterDataDialog v-if="productMasterVisible" v-model="productMasterVisible" :initial-id="productMasterId" :create="productMasterCreate" @changed="foundationRevision++;loadData()"/>
    <ProductMasterDataDialog v-if="foundationMasterHome&&!foundationSourceMode" ref="foundationMasterPanel" :model-value="true" embedded @changed="foundationRevision++"/>
    <ProjectWorkbench v-else-if="!isFoundation"/>
    <CurrentProductCatalog :key="foundationRevision" v-else-if="masterDataStatus?.ready&&!legacyArchive&&!route.query.history&&!route.query.productId&&!route.query.skuId" :mode="archiveView==='sku'?'sku':'product'" :administrator="masterDataStatus.administrator" @history="legacyArchive=true" @maintenance="id=>openProductMaintenance(id)"/>
    <template v-else>
    <el-button v-if="masterDataStatus?.ready" size="small" @click="legacyArchive=false;router.replace({path:'/projects',query:{tab:'archived',archiveView}})">{{ uiText("返回本期资料（CRM／MES）") }}</el-button>
    <section class="project-filter-bar" :aria-label='uiText("项目筛选")'>
      <nav class="project-tag-bar" :aria-label='uiText("项目视图")'>
        <button
          v-for="tag in projectQuickTags"
          :key="tag.value"
          class="project-tag-bar__item"
          :class="{ 'is-active': projectQuickTag === tag.value }"
          type="button"
          @click="selectProjectQuickTag(tag.value)"
        >
          {{ tag.label }}
        </button>
      </nav>
      <el-input v-model="keyword" clearable class="project-filter-search" :placeholder='uiText("搜索项目编码 / 名称 / 系列 / 负责人")' />
    </section>

    <section class="project-module-panel">
      <header class="project-module-panel__header">
        <div>
          <div class="project-current-heading">
            <strong role="heading" aria-level="2">{{ uiText(String((currentModuleTitle) ?? '')) }}</strong>
            <span class="project-current-count">{{ currentModuleCount }}{{ uiText(" 个") }}</span>
          </div>
          <p class="project-module-panel__desc">{{ currentModuleDescription }}</p>
        </div>
        <div class="project-module-panel__actions"><el-button v-if="productionPermissions.canMaintain" size="small" @click="salesCatalogVisible=true">{{ uiText("在售产品与审批别名") }}</el-button>
          <ProductionStatusFilter v-model="productionFilter" @change="loadData"/>
          <el-button v-if="productionPermissions.canMaintain&&selectedProductionRows.length" size="small" :disabled="selectedProductionRows.length>100" @click="editProductionBatch">{{ uiText("批量维护 (") }}{{selectedProductionRows.length}})</el-button><small v-if="selectedProductionRows.length>100">{{ uiText("每批最多100条") }}</small>
          <el-button v-if="projectQuickTag === 'archived_product'" type="primary" @click="openArchiveImport('product')">{{ uiText("导入数据") }}</el-button>
          <el-button v-if="projectQuickTag === 'archived_sku'" type="primary" @click="openArchiveImport('sku')">{{ uiText("导入数据") }}</el-button>
        </div>
      </header>

      <div class="project-module-panel__body">
          <template v-if="archiveView === 'product'">
            <section class="project-list-shell" v-loading="loading">
              <ProductProductionList :rows="archivedProductRows"  :selectable="productionPermissions.canMaintain" @selection-change="selectedProductionRows=$event as ProductSummary[]" @detail="openDetail" @changed="loadData"/>
            </section>
          </template>

          <template v-else>
            <section v-if="skuPageStage === 'product-home'" class="page-panel sku-product-home">
              <div class="toolbar-row sku-product-home__head">
                <div>
                  <h3 class="section-title">{{ uiText("SKU 管理") }}</h3>
                  <p class="page-panel-desc">{{ uiText("先按产品卡片定位，再进入该产品下的 SKU 列表。") }}</p>
                </div>
                <el-button type="primary" @click="openArchiveImport('sku')">{{ uiText("导入数据") }}</el-button>
              </div>
              <div class="sku-product-grid" v-loading="loading">
                <button
                  v-for="product in skuProductCards"
                  :key="product.productId"
                  class="sku-product-card"
                  type="button"
                  @click="skuOpenSkuList(product.productId)"
                >
                  <div class="sku-product-card__image"><span>{{ product.seriesName }}</span></div>
                  <div class="toolbar-row">
                    <strong>{{ product.productName }}</strong>
                    <StatusTag :status="product.status" object-type="product" /><el-tag size="small">{{ uiText("生产：") }}{{productionStatusName(product.productionStatus)}}</el-tag>
                  </div>
                  <p class="subtle-text">{{ getDisplayProductLineCode(product) }}</p>
                  <p class="sku-product-card__series">{{ product.seriesName }}</p>
                  <div class="sku-product-card__meta">
                    <span>{{ getSkuCountForProduct(product) }}{{ uiText(" 个 SKU") }}</span>
                    <span class="subtle-text">{{ getRecentUpdate(product) }}</span>
                  </div>
                </button>
              </div>
            </section>

            <section v-else class="page-panel sku-list-panel" v-loading="loading">
              <div class="toolbar-row sku-list-panel__head">
                <div>
                  <el-button link type="primary" @click="skuBackToProductHome">{{ uiText("返回产品卡片") }}</el-button>
                  <h3 class="section-title">{{ skuActiveProduct?.productName }}{{ uiText(" / SKU 列表") }}</h3>
                  <p class="page-panel-desc">{{ uiText("查看当前产品下的 SKU 信息、版本状态和归档资料。") }}</p>
                </div>
                <div class="sku-list-panel__actions"><el-button v-if="productionPermissions.canMaintain" size="small" @click="salesCatalogVisible=true">{{ uiText("在售产品与审批别名") }}</el-button>
                  <el-input v-model="skuKeyword" clearable class="sku-list-panel__search" :placeholder='uiText("搜索编码 / 名称 / 型号 / 颜色")' />
                  <el-button type="primary" @click="openArchiveImport('sku')">{{ uiText("导入数据") }}</el-button>
                </div>
              </div>
              <ProductProductionList :rows="skuCurrentSkuRows" sku :code="getDisplaySkuCode" :selectable="productionPermissions.canMaintain" @selection-change="selectedProductionRows=$event as ProductSummary[]" @detail="openDetail" @changed="loadData"/>
            </section>
          </template>
      </div>
    </section>

    </template>
    <el-dialog v-model="detailVisible" width="1080px" destroy-on-close>
      <template #header>
        <div class="detail-dialog-header">
          <div>
            <strong class="detail-dialog-header__title">
              {{ uiText(String((detailDialogTitle) ?? '')) }}
            </strong>
            <div class="detail-dialog-header__meta" v-if="detailTarget">
              <span>{{ getDetailDisplayCode(detailTarget) }}</span>
              <span v-if="!isProductDetailDialog">{{ uiText("型号 ") }}{{ detailTarget.model }} / {{ detailTarget.color }}</span>
              <span>{{ uiText("系列 ") }}{{ detailTarget.seriesName }}</span>
              <span>{{ uiText("版本 ") }}{{ detailTarget.versionNo }}</span>
            </div>
          </div>
          <div class="detail-dialog-header__actions"><el-button v-if="isFoundation&&detailTarget" size="small" @click="openProductMaintenance(detailTarget.productId)">{{ uiText("资料维护／引用") }}</el-button>
            <el-dropdown v-if="isProductDetailDialog && detailTarget" trigger="click" @command="openAssociatedBusiness">
              <el-button size="small">{{ uiText("关联业务") }}</el-button>
              <template #dropdown><el-dropdown-menu><el-dropdown-item v-if="user.hasPermission('collaboration:view')" command="market-metadata">{{ uiText("市场资料维护") }}</el-dropdown-item><el-dropdown-item v-if="user.hasPermission('collaboration:view')" command="/production-totals">{{ uiText("累计产量") }}</el-dropdown-item><el-dropdown-item v-if="user.hasPermission('collaboration:view')" command="/molds">{{ uiText("模具台账") }}</el-dropdown-item><el-dropdown-item v-if="user.hasPermission('schedule:read')" command="/mold-schedules">{{ uiText("开模时间管控") }}</el-dropdown-item></el-dropdown-menu></template>
            </el-dropdown>
            <StatusTag :status="detailTarget?.status || 'draft'" object-type="product" />
            <ProductProductionControl v-if="detailTarget" :product-id="detailTarget.productId" :model-value="detailTarget.productionStatus||'unknown'" :label="detailTarget.productName" @update:model-value="detailTarget.productionStatus=$event" @changed="loadData"/>
            <template v-if="detailTarget">
              <el-button
                size="small"
                :loading="detailLifecycleLoading === 'freeze'"
                :disabled="Boolean(detailLifecycleLoading) || !canFreezeDetailProject"
                @click="handleDetailFreezeProject"
              >{{ uiText(" 冻结项目 ") }}</el-button>
              <el-button
                v-if="isProductDetailDialog"
                size="small"
                type="primary"
                :loading="detailLifecycleLoading === 'publish'"
                :disabled="Boolean(detailLifecycleLoading) || !canPublishDetailProject"
                @click="handleDetailPublishProject"
              >{{ uiText(" 发布 Product ") }}</el-button>
              <el-button
                size="small"
                type="danger"
                plain
                :loading="detailLifecycleLoading === 'abandon'"
                :disabled="Boolean(detailLifecycleLoading) || !canStopDetailProject"
                @click="handleDetailStopProject"
              >{{ uiText(" 停止项目 ") }}</el-button>
              <el-button
                size="small"
                :loading="detailLifecycleLoading === 'archive'"
                :disabled="Boolean(detailLifecycleLoading) || !canArchiveDetailProject"
                @click="handleDetailArchiveProject"
              >{{ uiText(" 归档项目 ") }}</el-button>
            </template>
          </div>
        </div>
      </template>
      <div v-if="detailTarget" v-loading="detailLoading" class="detail-dialog">
        <nav class="detail-breadcrumb" :aria-label='uiText("详情分区")'>
          <button
            v-for="section in activeDetailSections"
            :key="section.key"
            class="detail-breadcrumb__item"
            :class="{ 'is-active': activeDetailSection === section.key }"
            type="button"
            @click="activeDetailSection = section.key"
          >
            {{ uiText(String((section.label) ?? '')) }}
          </button>
        </nav>

        <section v-if="activeDetailSection === 'project_flow'" class="detail-section">
          <el-alert
            v-if="timelineStarted === false"
            type="info"
            :closable="false"
            show-icon
            :title="timelineStartBlockReason || uiText('当前项目时间轴尚未启动。')"
          />
          <template v-if="timelineStarted !== false">
          <div class="product-node-hero">
            <div>
              <p class="subtle-text">{{ uiText("当前节点") }}</p>
              <h4 class="product-node-hero__title">{{ activeProductFlowNode?.nodeName || detailTarget.currentStage }}</h4>
              <p class="page-panel-desc">{{ activeProductFlowNode?.experienceSummary || activeProductFlowNode?.summary || detailPresentation?.summary || uiText('暂无当前节点说明。') }}</p>
            </div>
            <div class="product-node-hero__actions">
              <StatusTag :status="detailTarget.status" object-type="product" />
            </div>
          </div>

          <div class="timeline-action-panel">
            <div class="timeline-action-panel__status">
              <el-tag :type="currentTimelineConfirmed ? 'success' : 'warning'" effect="light">
                {{ currentTimelineConfirmed ? uiText('已确认') : uiText('未确认') }}
              </el-tag>
              <span>{{ uiText(String((currentTimelineActionLabel) ?? '')) }}</span>
            </div>
            <div class="timeline-action-panel__buttons">
              <el-button
                v-if="activeProductFlowNode && !currentTimelineConfirmed"
                data-test="project-timeline-confirm"
                type="primary"
                :loading="timelineActionLoading === 'confirm'"
                :disabled="Boolean(timelineActionLoading)"
                @click="handleConfirmCurrentNode"
              >{{ uiText(" 确认当前节点 ") }}</el-button>
              <el-button
                v-if="activeProductFlowNode && currentTimelineConfirmed && canAdvanceCurrentTimelineNode"
                data-test="project-timeline-advance"
                type="primary"
                :loading="timelineActionLoading === 'confirm'"
                :disabled="Boolean(timelineActionLoading) || !canAdvanceCurrentTimelineNode"
                @click="handleAdvanceCurrentNode"
              >{{ uiText(" 推进下一节点 ") }}</el-button>
              <el-dropdown
                v-if="activeProductFlowNode"
                data-test="project-timeline-return"
                :disabled="Boolean(timelineActionLoading)"
                @command="handleReturnCommand"
              >
                <el-button :loading="timelineActionLoading === 'return'">{{ uiText("退回") }}</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="current">{{ uiText("退回当前节点修改") }}</el-dropdown-item>
                    <el-dropdown-item command="previous">{{ uiText("退回上一节点") }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <section v-if="isMoldTransferNode" class="mold-transfer-panel">
            <div class="detail-section__head">
              <div>
                <h4 class="section-title">{{ uiText("运模快递登记") }}</h4>
                <p class="page-panel-desc">{{ uiText("当前运模节点只登记快递单号和发运时间，不再查询或展示物流轨迹。") }}</p>
              </div>
              <el-tag effect="light" :type="moldTransferExpress?.trackingNo ? 'success' : 'warning'">
                {{ moldTransferExpress?.trackingNo ? uiText('已登记单号') : uiText('待登记单号') }}
              </el-tag>
            </div>
            <div class="mold-transfer-form mold-transfer-form--simple">
              <el-input v-model="moldTransferForm.trackingNo" :placeholder='uiText("快递单号")' maxlength="128" />
              <el-date-picker
                v-model="moldTransferForm.shippedAt"
                type="datetime"
                value-format="YYYY-MM-DDTHH:mm:ss"
                :placeholder='uiText("发运时间")'
              />
              <el-button type="primary" :loading="moldTransferLoading" @click="saveCurrentMoldTransferExpress">{{ uiText("保存") }}</el-button>
            </div>
            <div v-if="moldTransferExpress?.trackingNo" class="mold-transfer-summary">
              <div class="info-card">
                <span class="subtle-text">{{ uiText("快递单号") }}</span>
                <strong>{{ moldTransferExpress.trackingNo }}</strong>
              </div>
              <div class="info-card">
                <span class="subtle-text">{{ uiText("发运时间") }}</span>
                <strong>{{ moldTransferExpress.shippedAt ? formatDate(moldTransferExpress.shippedAt, 'YYYY-MM-DD HH:mm') : '--' }}</strong>
              </div>
            </div>
          </section>

          <div class="detail-grid">
            <div class="info-card">
              <span class="subtle-text">{{ uiText("确认状态") }}</span>
              <strong>{{ currentTimelineConfirmed ? uiText('已确认') : uiText('未确认') }}</strong>
            </div>
            <div class="info-card">
              <span class="subtle-text">{{ uiText("最近动作") }}</span>
              <strong>{{ uiText(String((getTimelineActionLabel(timelineLastAction)) ?? '')) }}</strong>
              <span class="subtle-text">{{ timelineLastOperatedAt ? formatDate(timelineLastOperatedAt) : '--' }}</span>
            </div>
            <div class="info-card">
              <span class="subtle-text">{{ uiText("最近操作人") }}</span>
              <strong>{{ timelineLastOperatorUserName || '--' }}</strong>
            </div>
            <div class="info-card">
              <span class="subtle-text">{{ uiText("最近退回原因") }}</span>
              <strong>{{ timelineLastReason || '--' }}</strong>
            </div>
            <div class="info-card">
              <span class="subtle-text">{{ uiText("下一步动作") }}</span>
              <strong>{{ activeProductFlowNode?.nextAction || detailTarget.nextAction || detailPresentation?.nextNode || '--' }}</strong>
            </div>
            <div class="info-card">
              <span class="subtle-text">{{ uiText("接收人 / 时间") }}</span>
              <strong>{{ activeProductFlowNode?.receiverUserName || activeProductFlowNode?.receiverRole || '--' }}</strong>
              <span class="subtle-text">{{ activeProductFlowNode?.receivedAt || '--' }}</span>
            </div>
            <div class="info-card">
              <span class="subtle-text">{{ uiText("推动人 / 时间") }}</span>
              <strong>{{ activeProductFlowNode?.promoterUserName || activeProductFlowNode?.promoterRole || activeProductFlowNode?.ownerRole || '--' }}</strong>
              <span class="subtle-text">{{ activeProductFlowNode?.promotedAt || (activeProductFlowNode?.status === 'current' ? uiText('推进中') : '--') }}</span>
            </div>
            <div class="info-card">
              <span class="subtle-text">{{ uiText("下一接收方") }}</span>
              <strong>{{ activeProductFlowNode?.nextReceiverUserName || activeProductFlowNode?.nextReceiverRole || '--' }}</strong>
            </div>
          </div>

          <div v-if="activeProductFlowNode?.riskNote || activeProductFlowNode?.detailLines?.length" class="product-node-checks">
            <el-alert v-if="activeProductFlowNode?.riskNote" type="warning" show-icon :closable="false" :title="activeProductFlowNode.riskNote" />
            <ul v-if="activeProductFlowNode?.detailLines?.length" class="node-check-list">
              <li v-for="line in activeProductFlowNode.detailLines" :key="line">{{ line }}</li>
            </ul>
          </div>

          <ProjectReleaseGatePanel
            v-if="isProductDetailDialog"
            :project-id="detailTarget.productId"
            :product-status="detailTarget.status"
            @changed="handleLifecycleChanged"
          />
          </template>
        </section>

        <section v-if="activeDetailSection === 'basic'" class="detail-section">
          <div class="detail-section__head">
            <div>
              <h4 class="section-title">{{ uiText("基础信息") }}</h4>
            </div>
            <div v-if="canEditDetailTarget" class="toolbar-actions">
              <template v-if="basicInfoEditing">
                <el-button :disabled="basicInfoSaving" @click="cancelBasicInfoEdit">{{ uiText("取消") }}</el-button>
                <el-button type="primary" :loading="basicInfoSaving" @click="saveBasicInfo">{{ uiText("保存") }}</el-button>
              </template>
              <el-button v-else type="primary" plain size="small" @click="openDetailEdit">{{ uiText("编辑基础信息") }}</el-button>
            </div>
          </div>

          <el-form v-if="basicInfoEditing" label-position="top" class="basic-info-form">
            <div class="detail-grid">
              <el-form-item :label="uiText('{value0}名称',{value0:(detailObjectLabel)})">
                <el-input v-model="basicInfoForm.productName" />
              </el-form-item>
              <el-form-item :label='uiText("系列")'>
                <el-input v-model="basicInfoForm.seriesName" />
              </el-form-item>
              <el-form-item :label='uiText("负责人")'>
                <el-input v-model="basicInfoForm.ownerUserName" />
              </el-form-item>
              <el-form-item :label='uiText("型号")'>
                <el-input v-model="basicInfoForm.model" />
              </el-form-item>
              <el-form-item :label='uiText("颜色")'>
                <el-input v-model="basicInfoForm.color" />
              </el-form-item>
              <el-form-item :label='uiText("材质")'>
                <el-input v-model="basicInfoForm.material" />
              </el-form-item>
              <el-form-item :label='uiText("包装方式")'>
                <el-input v-model="basicInfoForm.packageType" />
              </el-form-item>
              <el-form-item :label='uiText("表面工艺")'>
                <el-input v-model="basicInfoForm.surfaceProcess" />
              </el-form-item>
              <el-form-item :label='uiText("预计发布时间")'>
                <el-date-picker v-model="basicInfoForm.expectedReleaseDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
              </el-form-item>
              <el-form-item :label='uiText("预计到达时间")'>
                <el-date-picker v-model="basicInfoForm.expectedArrivalAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
              </el-form-item>
              <el-form-item :label='uiText("实际到达时间")'>
                <el-date-picker v-model="basicInfoForm.actualArrivalAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
              </el-form-item>
              <template v-if="detailTarget.productType === 'model_variant'">
                <el-form-item label="4G/5G">
                  <el-input v-model="basicInfoForm.networkType" />
                </el-form-item>
                <el-form-item :label='uiText("大孔或精孔")'>
                  <el-input v-model="basicInfoForm.holeType" />
                </el-form-item>
                <el-form-item :label='uiText("Tipo 类型")'>
                  <el-input v-model="basicInfoForm.tipo" />
                </el-form-item>
                <el-form-item :label='uiText("紧急度")'>
                  <el-input v-model="basicInfoForm.priority" />
                </el-form-item>
                <el-form-item :label='uiText("制造地")'>
                  <el-input v-model="basicInfoForm.manufacturingLocation" />
                </el-form-item>
                <el-form-item :label='uiText("模具印字")'>
                  <el-input v-model="basicInfoForm.moldMarking" />
                </el-form-item>
                <el-form-item :label='uiText("订单类型")'>
                  <el-select v-model="basicInfoForm.requirementType" clearable style="width: 100%">
                    <el-option :label='uiText("客户订单")' value="customer_requirement" />
                    <el-option :label='uiText("市场需求")' value="market_requirement" />
                  </el-select>
                </el-form-item>
                <el-form-item :label='uiText("钉钉链接")'>
                  <el-input v-model="basicInfoForm.referenceUrl" />
                </el-form-item>
                <el-form-item :label='uiText("手机功能")'>
                  <el-input v-model="basicInfoForm.mobileFunction" type="textarea" :rows="3" />
                </el-form-item>
                <el-form-item :label='uiText("客户要求")'>
                  <el-input v-model="basicInfoForm.customerRequirement" type="textarea" :rows="3" />
                </el-form-item>
              </template>
              <el-form-item :label='uiText("核心工艺")'>
                <el-input v-model="basicInfoForm.coreProcess" type="textarea" :rows="3" />
              </el-form-item>
              <el-form-item :label='uiText("组成说明")'>
                <el-input v-model="basicInfoForm.composition" type="textarea" :rows="3" />
              </el-form-item>
            </div>
          </el-form>

          <div v-else class="detail-grid">
            <div class="info-card"><span class="subtle-text">{{ detailObjectLabel }}{{ uiText("编码") }}</span><strong>{{ getDetailDisplayCode(detailTarget) }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ detailObjectLabel }}{{ uiText("名称") }}</span><strong>{{ detailTarget.productName }}</strong></div>
            <div v-if="detailTarget.productType === 'sku'" class="info-card"><span class="subtle-text">{{ uiText("Product 编码") }}</span><strong>{{ detailTarget.productCode }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("系列") }}</span><strong>{{ detailTarget.seriesName }}</strong></div>
            <div v-if="isProductDetailDialog" class="info-card"><span class="subtle-text">{{ uiText("产品类型") }}</span><strong>{{ uiText(String((getProjectTypeLabel(detailTarget)) ?? '')) }}</strong></div>
            <div v-if="detailTarget.productType === 'model_variant' || detailTarget.productType === 'sku'" class="info-card"><span class="subtle-text">{{ uiText("产品特定编码") }}</span><strong>{{ detailTarget.productSpecificCode || '--' }}</strong></div>
            <div v-if="detailTarget.productType === 'model_variant' || detailTarget.productType === 'sku'" class="info-card"><span class="subtle-text">{{ uiText("手机型号编码") }}</span><strong>{{ detailTarget.phoneModelCode || '--' }}</strong></div>
            <div v-if="detailTarget.productType === 'model_variant' || detailTarget.productType === 'sku'" class="info-card"><span class="subtle-text">{{ uiText("颜色编码") }}</span><strong>{{ detailTarget.colorCode || '--' }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("版本") }}</span><strong>{{ detailTarget.versionNo }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("型号") }}</span><strong>{{ detailTarget.model || '--' }}</strong></div>
            <div v-if="detailTarget.productType === 'model_variant'" class="info-card"><span class="subtle-text">{{ uiText("模具编码") }}</span><strong>{{ detailTarget.moldCodes || '--' }}</strong></div>
            <div v-if="detailTarget.productType === 'model_variant'" class="info-card"><span class="subtle-text">{{ uiText("运模时间") }}</span><strong>{{ detailTarget.moldTransferAt ? formatDate(detailTarget.moldTransferAt, 'YYYY-MM-DD HH:mm') : '--' }}</strong></div>
            <div v-if="detailTarget.productType === 'model_variant'" class="info-card"><span class="subtle-text">{{ uiText("预计到达时间") }}</span><strong>{{ detailTarget.expectedArrivalAt ? formatDate(detailTarget.expectedArrivalAt, 'YYYY-MM-DD HH:mm') : '--' }}</strong></div>
            <div v-if="detailTarget.productType === 'model_variant'" class="info-card"><span class="subtle-text">{{ uiText("实际到达时间") }}</span><strong>{{ detailTarget.actualArrivalAt ? formatDate(detailTarget.actualArrivalAt, 'YYYY-MM-DD HH:mm') : '--' }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("颜色") }}</span><strong>{{ formatScalarOrArray(detailTarget.color) }}</strong></div>
            <div v-if="isProductDetailDialog" class="info-card"><span class="subtle-text">{{ uiText("材质") }}</span><strong>{{ formatScalarOrArray(detailTarget.material) }}</strong></div>
            <div v-if="isProductDetailDialog" class="info-card"><span class="subtle-text">{{ uiText("客户来源") }}</span><strong>{{ detailTarget.customerName || uiText('内部立项') }}</strong></div>
            <div v-if="isProductDetailDialog" class="info-card"><span class="subtle-text">{{ uiText("负责人") }}</span><strong>{{ formatOwnerName(detailTarget.ownerUserName) }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("状态") }}</span><StatusTag :status="detailTarget.status" object-type="product" /></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("当前阶段") }}</span><strong>{{ detailTarget.currentStage }}</strong></div>
            <div v-if="isProductDetailDialog" class="info-card"><span class="subtle-text">{{ uiText("资料完整率") }}</span><strong>{{ Math.round(detailTarget.completionRate * 100) }}%</strong></div>
            <div v-if="isProductDetailDialog && user.hasPermission('finance:read')" class="info-card"><span class="subtle-text">{{ uiText("预算与费用") }}</span><el-button link @click="activeDetailSection='cost'">{{ uiText("按原币核对") }}</el-button></div>
            <template v-if="detailTarget.productType === 'model_variant' && basicInfoExtra">
              <div class="info-card"><span class="subtle-text">4G/5G</span><strong>{{ basicInfoExtra.networkType || '--' }}</strong></div>
              <div class="info-card"><span class="subtle-text">{{ uiText("大孔或精孔") }}</span><strong>{{ basicInfoExtra.holeType || '--' }}</strong></div>
              <div class="info-card"><span class="subtle-text">{{ uiText("Tipo 类型") }}</span><strong>{{ basicInfoExtra.tipo || '--' }}</strong></div>
              <div class="info-card"><span class="subtle-text">{{ uiText("紧急度") }}</span><strong>{{ basicInfoExtra.priority || '--' }}</strong></div>
              <div class="info-card"><span class="subtle-text">{{ uiText("制造地") }}</span><strong>{{ basicInfoExtra.manufacturingLocation || '--' }}</strong></div>
              <div class="info-card"><span class="subtle-text">{{ uiText("模具印字") }}</span><strong>{{ basicInfoExtra.moldMarking || '--' }}</strong></div>
              <div class="info-card"><span class="subtle-text">{{ uiText("客户要求") }}</span><strong>{{ basicInfoExtra.customerRequirement || '--' }}</strong></div>
            </template>
          </div>

          <section v-if="hasDetailColorSummary" class="product-color-summary">
            <div class="detail-section__head">
              <div>
                <h4 class="section-title">{{ uiText("颜色归档") }}</h4>
                <p class="page-panel-desc">{{ uiText("按真实产品线汇总 SKU 实际颜色与 BOM/生产决策颜色。") }}</p>
              </div>
            </div>
            <div class="product-color-summary__groups">
              <div class="product-color-summary__group">
                <span class="subtle-text">{{ uiText("SKU 实际颜色 ") }}{{ detailTarget.colorSummary?.skuColorCount || 0 }}{{ uiText(" 个") }}</span>
                <div class="product-color-tags">
                  <el-tag
                    v-for="color in detailTarget.colorSummary?.skuColors || []"
                    :key="`sku-${color.colorCode || color.colorName}`"
                    effect="light"
                  >
                    {{ uiText(String((getColorUsageLabel(color)) ?? '')) }} · {{ color.skuCount }} SKU
                  </el-tag>
                </div>
              </div>
              <div class="product-color-summary__group">
                <span class="subtle-text">{{ uiText("BOM/生产决策颜色 ") }}{{ detailTarget.colorSummary?.productionColorCount || 0 }}{{ uiText(" 个") }}</span>
                <div class="product-color-tags">
                  <el-tag
                    v-for="color in detailTarget.colorSummary?.productionColors || []"
                    :key="`production-${color.colorCode || color.colorName}`"
                    type="success"
                    effect="light"
                  >
                    {{ uiText(String((getColorUsageLabel(color)) ?? '')) }}
                  </el-tag>
                </div>
              </div>
            </div>
            <el-alert
              v-if="detailTarget.colorSummary?.skuOnlyColors?.length || detailTarget.colorSummary?.productionOnlyColors?.length"
              class="product-color-summary__diff"
              type="warning"
              show-icon
              :closable="false"
              :title="uiText('颜色差异：仅 SKU 层 {value0}；仅 BOM/生产决策层 {value1}',{value0:(detailTarget.colorSummary?.skuOnlyColors?.map(getColorUsageLabel).join('、') || '无'),value1:(detailTarget.colorSummary?.productionOnlyColors?.map(getColorUsageLabel).join('、') || '无')})"
            />
          </section>
        </section>

        <section v-if="isProductDetailDialog && activeDetailSection === 'project_flow' && timelineStarted !== false" class="detail-section">
          <div class="detail-section__head">
            <div>
              <h4 class="section-title">{{ uiText("项目流程") }}</h4>
              <p class="page-panel-desc">{{ uiText("展示每个环节经历了什么、由谁接收、由谁推动以及推动时间。") }}</p>
            </div>
          </div>
          <section class="product-flow-board">
            <aside class="product-flow-board__stages">
              <button
                v-for="stage in productFlowStages"
                :key="stage.stageKey"
                class="flow-stage-card"
                :class="{ 'is-active': stage.stageKey === activeProductFlowStage?.stageKey }"
                type="button"
                @click="activeProductFlowStageKey = stage.stageKey"
              >
                <span class="flow-stage-card__name">{{ stage.stageName }}</span>
                <span class="flow-stage-card__phase">{{ stage.phaseName }}</span>
                <el-tag size="small" effect="light" :type="getFlowStatusType(stage.status)">
                  {{ uiText(String((getFlowStatusLabel(stage.status)) ?? '')) }}
                </el-tag>
              </button>
            </aside>

            <main v-if="activeProductFlowStage" class="product-flow-board__detail">
              <div class="flow-stage-summary">
                <div>
                  <h4>{{ activeProductFlowStage.stageName }}</h4>
                  <p>{{ activeProductFlowStage.summary }}</p>
                </div>
                <el-tag :type="getFlowStatusType(activeProductFlowStage.status)" effect="light">
                  {{ uiText(String((getFlowStatusLabel(activeProductFlowStage.status)) ?? '')) }}
                </el-tag>
              </div>

              <div class="flow-stage-meta-grid">
                <div class="info-card">
                  <span class="subtle-text">{{ uiText("责任角色") }}</span>
                  <strong>{{ activeProductFlowStage.receiverRole || '--' }}</strong>
                </div>
                <div class="info-card">
                  <span class="subtle-text">{{ uiText("接收人 / 时间") }}</span>
                  <strong>{{ activeProductFlowStage.receiverUserName || '--' }}</strong>
                  <span class="subtle-text">{{ activeProductFlowStage.receivedAt || '--' }}</span>
                </div>
                <div class="info-card">
                  <span class="subtle-text">{{ uiText("推动人 / 时间") }}</span>
                  <strong>{{ activeProductFlowStage.promoterUserName || activeProductFlowStage.promoterRole || '--' }}</strong>
                  <span class="subtle-text">{{ activeProductFlowStage.promotedAt || '--' }}</span>
                </div>
                <div class="info-card">
                  <span class="subtle-text">{{ uiText("下一步") }}</span>
                  <strong>{{ activeProductFlowStage.nextAction || '--' }}</strong>
                </div>
                <div class="info-card">
                  <span class="subtle-text">{{ uiText("风险提示") }}</span>
                  <strong>{{ activeProductFlowStage.riskNote || uiText('暂无风险') }}</strong>
                </div>
              </div>

              <div class="flow-child-node-list">
                <div v-for="node in activeProductFlowStage.childNodes" :key="node.nodeKey" class="flow-child-node">
                  <strong>{{ uiText("第 ") }}{{ node.stepNo }}{{ uiText(" 步：") }}{{ node.nodeName }}</strong>
                  <div>
                    <span v-if="node.actionLabel" class="subtle-text">{{ uiText(String((node.actionLabel) ?? '')) }}</span>
                    <el-button v-if="node.processConfirmation || isProcessConfirmationNode(node)" size="small" type="primary" plain @click="openProductionConfirmation('operations')">{{ uiText("敲定投产工序") }}</el-button>
                    <el-button v-if="node.nodeKey === 'PRODUCT_LINE_PRODUCTION_DECISION_STEP'" size="small" type="primary" @click="openProductionConfirmation('colors')">{{ uiText("确认批量投产颜色") }}</el-button>
                    <el-tag v-if="node.documentCount" size="small" type="success" effect="light">{{ uiText(" 已上传 ") }}{{ node.documentCount }}{{ uiText(" 个 ") }}</el-tag>
                    <el-tag size="small" effect="light" :type="getFlowStatusType(node.status)">
                      {{ uiText(String((getFlowStatusLabel(node.status)) ?? '')) }}
                    </el-tag>
                  </div>
                </div>
              </div>
            </main>
          </section>
        </section>

        <section v-if="isProductDetailDialog && activeDetailSection === 'bom_manage'" class="detail-section">
          <ProjectBomPanel
            :project-id="detailTarget.productId"
          />
        </section>

        <section v-if="isProductDetailDialog && activeDetailSection === 'process_detail'" class="detail-section">
          <ProjectProcessRoutePanel
            :project-id="detailTarget.productId"
            :product-code="detailTarget.productCode"
            :product-name="detailTarget.productName"
            :product-type="detailTarget.productType"
            :product-specific-code="detailTarget.productSpecificCode || undefined"
            :phone-model-code="detailTarget.phoneModelCode || undefined"
            :color-code="detailTarget.colorCode || undefined"
            :auto-create="route.query.createProcessRoute === '1'"
          />
        </section>

        <section v-if="isProductDetailDialog && activeDetailSection === 'materials'" class="detail-section">
          <TimelineAttachmentPanel
            :project-id="detailTarget.productId"
            @changed="handleM4AttachmentChanged"
          />
        </section>

        <section v-if="isProductDetailDialog && activeDetailSection === 'business'" class="detail-section">
          <h4 class="section-title">{{ uiText("商务区") }}</h4>
          <el-button v-if="user.hasPermission('finance:read')" @click="activeDetailSection='cost'">{{ uiText("打开统一费用归集（按原币，不混加）") }}</el-button>
          <el-table :data="detailPresentation?.suppliers || []" border stripe size="small">
            <el-table-column prop="supplierName" :label='uiText("供应商")' min-width="160" />
            <el-table-column prop="role" :label='uiText("角色")' width="140" />
            <el-table-column prop="statusLabel" :label='uiText("状态")' width="120" />
            <el-table-column prop="note" :label='uiText("说明")' min-width="220" />
          </el-table>
        </section>

        <section v-if="isProductDetailDialog && activeDetailSection === 'quality'" class="detail-section">
          <h4 class="section-title">{{ uiText("质量区") }}</h4>
          <el-table :data="detailPresentation?.qualityRecords || []" border stripe size="small">
            <el-table-column prop="testItem" :label='uiText("测试项目")' min-width="150" />
            <el-table-column prop="resultLabel" :label='uiText("测试结果")' width="120" />
            <el-table-column prop="owner" :label='uiText("负责人")' width="140" />
            <el-table-column prop="testedAt" :label='uiText("测试时间")' width="130" />
            <el-table-column prop="note" :label='uiText("结论说明")' min-width="260" />
          </el-table>
          <el-empty v-if="!detailPresentation?.qualityRecords?.length" :description='uiText("暂无质量记录")' />
        </section>

        <section v-if="activeDetailSection === 'cost' && user.hasPermission('finance:read')" class="detail-section">
          <ProjectCostPanel :project-id="detailTarget.productId" @changed="refreshDetailAfterChildChange" />
        </section>

        <section v-if="activeDetailSection === 'bom'" class="detail-section">
          <ProjectBomPanel
            :project-id="detailTarget.productId"
          />
        </section>

        <section v-if="activeDetailSection === 'process'" class="detail-section">
          <ProjectProcessRoutePanel
            :project-id="detailTarget.productId"
            :product-code="detailTarget.productCode"
            :product-name="detailTarget.productName"
            :product-type="detailTarget.productType"
            :product-specific-code="detailTarget.productSpecificCode || undefined"
            :phone-model-code="detailTarget.phoneModelCode || undefined"
            :color-code="detailTarget.colorCode || undefined"
            :auto-create="route.query.createProcessRoute === '1'"
          />
        </section>

        <section v-if="!isProductDetailDialog && activeDetailSection === 'project_flow' && timelineStarted !== false" class="detail-section">
          <div class="detail-section__head">
            <div>
              <h4 class="section-title">{{ uiText("项目流程") }}</h4>
              <p class="page-panel-desc">{{ uiText("SKU 对应新型号线流程，展示差异扩展从需求确认到版本发布的接收、推动与时间记录。") }}</p>
            </div>
          </div>

          <section v-if="skuProjectFlowStages.length" class="product-flow-board">
            <aside class="product-flow-board__stages">
              <button
                v-for="stage in skuProjectFlowStages"
                :key="stage.stageKey"
                class="flow-stage-card"
                :class="{ 'is-active': stage.stageKey === activeSkuFlowStage?.stageKey }"
                type="button"
                @click="activeSkuFlowStageKey = stage.stageKey"
              >
                <span class="flow-stage-card__name">{{ stage.stageName }}</span>
                <span class="flow-stage-card__phase">{{ stage.phaseName }}</span>
                <el-tag size="small" effect="light" :type="getFlowStatusType(stage.status)">
                  {{ uiText(String((getFlowStatusLabel(stage.status)) ?? '')) }}
                </el-tag>
              </button>
            </aside>

            <main v-if="activeSkuFlowStage" class="product-flow-board__detail">
              <div class="flow-stage-summary">
                <div>
                  <h4>{{ activeSkuFlowStage.stageName }}</h4>
                  <p>{{ activeSkuFlowStage.summary }}</p>
                </div>
                <el-tag :type="getFlowStatusType(activeSkuFlowStage.status)" effect="light">
                  {{ uiText(String((getFlowStatusLabel(activeSkuFlowStage.status)) ?? '')) }}
                </el-tag>
              </div>

              <div class="flow-stage-meta-grid">
                <div class="info-card">
                  <span class="subtle-text">{{ uiText("责任角色") }}</span>
                  <strong>{{ activeSkuFlowStage.receiverRole || '--' }}</strong>
                </div>
                <div class="info-card">
                  <span class="subtle-text">{{ uiText("接收人 / 时间") }}</span>
                  <strong>{{ activeSkuFlowStage.receiverUserName || '--' }}</strong>
                  <span class="subtle-text">{{ activeSkuFlowStage.receivedAt || '--' }}</span>
                </div>
                <div class="info-card">
                  <span class="subtle-text">{{ uiText("推动人 / 时间") }}</span>
                  <strong>{{ activeSkuFlowStage.promoterUserName || activeSkuFlowStage.promoterRole || '--' }}</strong>
                  <span class="subtle-text">{{ activeSkuFlowStage.promotedAt || '--' }}</span>
                </div>
                <div class="info-card">
                  <span class="subtle-text">{{ uiText("下一步") }}</span>
                  <strong>{{ activeSkuFlowStage.nextAction || '--' }}</strong>
                </div>
                <div class="info-card">
                  <span class="subtle-text">{{ uiText("风险提示") }}</span>
                  <strong>{{ activeSkuFlowStage.riskNote || uiText('暂无风险') }}</strong>
                </div>
              </div>

              <div class="flow-child-node-list">
                <div v-for="node in activeSkuFlowStage.childNodes" :key="node.nodeKey" class="flow-child-node">
                  <strong>{{ uiText("第 ") }}{{ node.stepNo }}{{ uiText(" 步：") }}{{ node.nodeName }}</strong>
                  <div>
                    <span v-if="node.actionLabel" class="subtle-text">{{ uiText(String((node.actionLabel) ?? '')) }}</span>
                    <el-button v-if="node.processConfirmation || isProcessConfirmationNode(node)" size="small" type="primary" plain @click="openProductionConfirmation('operations')">{{ uiText("敲定投产工序") }}</el-button>
                    <el-button v-if="node.nodeKey === 'MODEL_VARIANT_MOLD_TRANSFER'" size="small" type="primary" @click="openProductionConfirmation('colors')">{{ uiText("确认批量投产并创建 SKU") }}</el-button>
                    <el-tag v-if="node.documentCount" size="small" type="success" effect="light">{{ uiText(" 已上传 ") }}{{ node.documentCount }}{{ uiText(" 个 ") }}</el-tag>
                    <el-tag size="small" effect="light" :type="getFlowStatusType(node.status)">
                      {{ uiText(String((getFlowStatusLabel(node.status)) ?? '')) }}
                    </el-tag>
                  </div>
                </div>
              </div>

              <el-collapse v-model="skuFlowTableExpanded" class="sku-flow-table-collapse">
                <el-collapse-item name="detail">
                  <template #title>
                    <span>{{ uiText("查看明细记录（") }}{{ skuProjectFlowRows.length }}{{ uiText(" 条）") }}</span>
                  </template>
                  <el-table :data="skuProjectFlowRows" border stripe size="small">
                    <el-table-column prop="seqNo" :label='uiText("顺序")' width="70" />
                    <el-table-column prop="stageName" :label='uiText("新型号线阶段")' min-width="140" />
                    <el-table-column prop="nodeName" :label='uiText("环节")' min-width="140" />
                    <el-table-column prop="phaseName" :label='uiText("阶段")' min-width="110" />
                    <el-table-column prop="experienceSummary" :label='uiText("经历内容")' min-width="200" />
                    <el-table-column :label='uiText("接收人")' width="110">
                      <template #default="{ row }">{{ row.receiverUserName || row.receiverRole || '--' }}</template>
                    </el-table-column>
                    <el-table-column :label='uiText("接收时间")' width="110">
                      <template #default="{ row }">{{ row.receivedAt || '--' }}</template>
                    </el-table-column>
                    <el-table-column :label='uiText("推动人")' width="110">
                      <template #default="{ row }">{{ row.promoterUserName || row.promoterRole || '--' }}</template>
                    </el-table-column>
                    <el-table-column :label='uiText("推动时间")' width="110">
                      <template #default="{ row }">{{ row.promotedAt || '--' }}</template>
                    </el-table-column>
                    <el-table-column :label='uiText("状态")' width="100">
                      <template #default="{ row }">
                        <el-tag :type="getFlowStatusType(row.status)" effect="light" size="small">
                          {{ uiText(String((getFlowStatusLabel(row.status)) ?? '')) }}
                        </el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column :label='uiText("下一步")' min-width="160">
                      <template #default="{ row }">{{ row.nextAction || '--' }}</template>
                    </el-table-column>
                    <el-table-column :label='uiText("资料数")' width="80">
                      <template #default="{ row }">{{ row.documentCount ?? 0 }}</template>
                    </el-table-column>
                  </el-table>
                </el-collapse-item>
              </el-collapse>
            </main>
          </section>
          <el-empty v-else :description='uiText("暂无项目流程")' />
        </section>

        <section v-if="!isProductDetailDialog && activeDetailSection === 'production_docs'" class="detail-section">
          <TimelineAttachmentPanel
            :project-id="detailTarget.productId"
            @changed="handleM4AttachmentChanged"
          />
        </section>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">{{ uiText("关闭") }}</el-button>
      </template>

      <!-- 生产资料预览 -->
      <FilePreview
        v-model="productionPreviewVisible"
        :file="activeProductionDoc"
      />
  </el-dialog>
  <ProductionConfirmationDialog
    v-if="detailTarget"
    v-model="productionConfirmationVisible"
    :project-id="detailTarget.productId"
    :mode="productionConfirmationMode"
    :default-product-bom-route-id="productionConfirmationDefaultRouteId"
    @confirmed="handleProductionConfirmationConfirmed"
  />

    <el-dialog
      v-model="importVisible"
      :title="importType === 'product' ? uiText('导入产品数据') : uiText('导入 SKU 数据')"
      width="640px"
      @closed="resetArchiveImportState"
    >
      <el-alert type="info" show-icon :closable="false" :title='uiText("用于导入历史归档数据，导入后仍按 Product 对象保存和追溯。")' />
      <div class="archive-upload">
        <input
          :key="archiveImportInputKey"
          data-test="archive-import-file"
          type="file"
          accept=".xlsx,.xls,.csv"
          :disabled="archiveImportLoading"
          @change="handleArchiveImportFileChange"
        />
        <div class="el-upload__tip">{{ uiText("支持 xlsx / xls / csv；SKU 导入仍按 Product 主数据导入，文件需包含 SKU 类型字段。") }}</div>
      </div>
      <el-alert
        v-if="archiveImportPreview"
        class="archive-import-preview"
        :type="archiveImportPreview.failCount > 0 ? 'warning' : 'success'"
        show-icon
        :closable="false"
        :title="uiText('预览结果：共 {value0} 条，成功 {value1} 条，失败 {value2} 条',{value0:(archiveImportPreview.totalCount),value1:(archiveImportPreview.successCount),value2:(archiveImportPreview.failCount)})"
      />
      <template #footer>
        <el-button :disabled="archiveImportLoading" @click="closeArchiveImport">{{ uiText("取消") }}</el-button>
        <el-button type="primary" :loading="archiveImportLoading" @click="submitArchiveImport">{{ uiText("开始导入") }}</el-button>
      </template>
    </el-dialog>
    <MetadataDialog v-model="productionEditor" kind="product" :items="productionTargets" @saved="selectedProductionRows=[];loadData()"/>
  </PageContainer>
  <ProductSalesCatalog v-model="salesCatalogVisible"/>
</template>

<style scoped>
.workbench-project-page{height:100%;min-height:0;gap:0}.workbench-project-page>:deep(.page-panel:first-child){display:none}

.production-filter{width:156px}.project-module-panel__actions{display:flex;flex-wrap:wrap;gap:6px;align-items:center}
.project-filter-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; padding: 8px 10px; border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; }
.project-filter-search { flex: 0 0 320px; max-width: 360px; }

.project-module-panel { overflow: hidden; border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; }
.project-module-panel__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 14px 16px; border-bottom: 1px solid #e5e7eb; background: #fff; }
.project-module-panel__body { padding: 12px 16px 16px; background: #fff; }
.project-module-panel__desc { margin: 6px 0 0; color: #64748b; font-size: 13px; }

.project-tag-bar { display: flex; flex-wrap: wrap; gap: 6px; margin: 0; padding: 0; border: 0; background: transparent; }
.flow-filter-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; padding: 10px; border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; }

.project-tag-bar__item,
.flow-filter-bar__item {
  padding: 7px 14px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
}

.project-tag-bar__item.is-active,
.flow-filter-bar__item.is-active {
  background: #eff6ff;
  color: #1d4ed8;
  font-weight: 600;
}

.list-context-bar,
.selected-node-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-radius: 8px;
  background: rgba(37, 99, 235, 0.05);
  margin-bottom: 14px;
}

.project-timeline-panel {
  margin: 16px 0;
  padding: 16px;
  border: 1px solid rgba(37, 99, 235, 0.18);
  border-radius: 8px;
  background: #fff;
}

.timeline-row {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
}

.timeline-node {
  display: flex;
  min-height: 180px;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border: 1px solid rgba(148, 163, 184, 0.24);
  border-radius: var(--plm-radius-base);
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s, box-shadow 0.16s;
}

.timeline-node:hover {
  border-color: #2563eb;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.1);
}

.timeline-node.is-selected {
  border-color: #2563eb;
  background: rgba(37, 99, 235, 0.05);
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.12);
}

.timeline-node.is-gate {
  border-left: 3px solid #2563eb;
}

.timeline-node.is-empty {
  opacity: 0.58;
}

.timeline-node__phase,
.timeline-node__hint,
.timeline-node__children {
  color: #64748b;
  font-size: 12px;
}

.timeline-node__children {
  color: #94a3b8;
}

.timeline-node__count {
  margin-top: auto;
  color: #2563eb;
  font-size: 13px;
  font-weight: 600;
}

.project-list-shell {
  margin-top: 14px;
}

.cell-stack,
.detail-dialog,
.detail-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sku-product-home__head {
  margin-bottom: 14px;
}

.sku-product-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.sku-product-card {
  display: flex;
  min-height: 220px;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--plm-color-border-light);
  border-radius: var(--plm-radius-base);
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s, box-shadow 0.16s, transform 0.16s;
}

.sku-product-card:hover {
  border-color: var(--plm-color-primary);
  box-shadow: var(--plm-shadow-md);
  transform: translateY(-1px);
}

.sku-product-card__image,
.sku-image-cell__thumb {
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: linear-gradient(135deg, #e9eef8 0%, #f8fafc 100%);
  color: #3b4a63;
  font-weight: 600;
}

.sku-product-card__image {
  min-height: 100px;
}

.sku-product-card__series {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.sku-product-card__meta,
.sku-list-panel__actions,
.sku-image-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.sku-product-card__meta {
  justify-content: space-between;
  margin-top: auto;
}

.sku-list-panel__head {
  align-items: flex-start;
  margin-bottom: 14px;
}

.sku-list-panel__search {
  width: 260px;
}

.sku-image-cell__thumb {
  width: 52px;
  height: 52px;
  font-size: 11px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.product-color-summary {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 18px;
}

.product-color-summary__groups {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.product-color-summary__group {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 10px;
}

.product-color-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.product-color-summary__diff {
  margin-top: 2px;
}

.detail-cost-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.product-node-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px;
  border: 1px solid rgba(37, 99, 235, 0.16);
  border-radius: 8px;
  background: #f8fafc;
}

.product-node-hero__title {
  margin: 4px 0 6px;
  color: #0f172a;
  font-size: 18px;
  line-height: 1.35;
}

.product-node-hero__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
}

.product-node-checks {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.node-check-list {
  margin: 0;
  padding: 12px 16px 12px 28px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 8px;
  background: #fff;
  color: #334155;
  line-height: 1.7;
}

.product-flow-board {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 14px;
}

.product-flow-board__stages,
.product-flow-board__detail {
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 8px;
  background: #fff;
}

.product-flow-board__stages {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
}

.product-flow-board__detail {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
}

.flow-stage-card {
  display: flex;
  min-height: 82px;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: #f8fafc;
  color: #334155;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s, background 0.16s, box-shadow 0.16s;
}

.flow-stage-card:hover,
.flow-stage-card.is-active {
  border-color: #2563eb;
  background: #eff6ff;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.1);
}

.flow-stage-card__name {
  color: #0f172a;
  font-weight: 600;
}

.flow-stage-card__phase {
  color: #64748b;
  font-size: 12px;
}

.flow-stage-summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
}

.flow-stage-summary h4 {
  margin: 0 0 6px;
  color: #0f172a;
  font-size: 17px;
}

.flow-stage-summary p {
  margin: 0;
  color: #475569;
  line-height: 1.6;
}

.flow-stage-meta-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.flow-child-node-list {
  display: grid;
  gap: 8px;
}

.flow-child-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 8px;
  background: #f8fafc;
}

.flow-child-node > div {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
}

.bom-version-select-panel {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 8px;
  background: #f8fafc;
}

.bom-summary-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: #334155;
  font-size: 13px;
}

.bom-summary-row span {
  padding: 6px 10px;
  border-radius: 6px;
  background: #eef2ff;
}

.process-detail-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.process-tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.detail-dialog-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e5e7eb;
}

.detail-dialog-header__title {
  color: #0f172a;
  font-size: 17px;
  font-weight: 600;
}

.detail-dialog-header__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin-top: 6px;
  color: #64748b;
  font-size: 13px;
}

.detail-dialog-header__actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
  flex-shrink: 0;
}

.sku-flow-table-collapse {
  margin-top: 2px;
  border-top: 1px solid rgba(148, 163, 184, 0.18);
  padding-top: 10px;
}

.detail-breadcrumb {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e5e7eb;
}

.detail-breadcrumb__item {
  padding: 4px 8px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #64748b;
  font-size: 13px;
  cursor: pointer;
}

.detail-breadcrumb__item:hover {
  background: #f8fafc;
  color: #334155;
}

.detail-breadcrumb__item.is-active {
  color: #0f172a;
  font-weight: 600;
}

.project-overview-dialog {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.project-overview-dialog__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.2);
}

.project-overview-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 18px;
  margin: 0;
}

.project-overview-grid div,
.project-overview-block {
  padding: 12px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 8px;
  background: #f8fafc;
}

.project-overview-grid dt {
  margin-bottom: 6px;
  color: #64748b;
  font-size: 12px;
}

.project-overview-grid dd {
  margin: 0;
  color: #0f172a;
  font-weight: 600;
}

.archive-upload {
  margin-top: 16px;
}

.archive-import-preview {
  margin-top: 12px;
}

.project-current-heading {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.project-current-count {
  color: #94a3b8;
  font-size: 13px;
  font-weight: 400;
}

.timeline-action-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 14px 0;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

.timeline-action-panel__status,
.timeline-action-panel__buttons {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.current-node-attachment-panel {
  margin: 14px 0;
  padding: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}

.mold-transfer-panel {
  padding: 12px 0;
  border-bottom: 1px solid #e5e7eb;
}

.mold-transfer-form {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 220px auto;
  gap: 10px;
  align-items: start;
}

.mold-transfer-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;
}

@media (max-width: 1280px) {
  .timeline-row,
  .sku-product-grid,
  .detail-grid,
  .product-color-summary__groups {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .flow-stage-meta-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .mold-transfer-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .sku-flow-stage-strip {
    grid-template-columns: repeat(3, minmax(120px, 1fr));
  }
}

@media (max-width: 768px) {
  .timeline-row,
  .sku-product-grid,
  .detail-grid,
  .product-color-summary__groups,
  .detail-cost-grid,
  .product-flow-board,
  .flow-stage-meta-grid,
  .sku-flow-stage-strip,
  .project-overview-grid {
    grid-template-columns: 1fr;
  }

  .project-filter-bar,
  .list-context-bar,
  .selected-node-bar,
  .sku-list-panel__actions,
  .flow-stage-summary,
  .flow-child-node,
  .sku-flow-current,
  .timeline-action-panel,
  .bom-version-select-panel {
    align-items: stretch;
    flex-direction: column;
  }

  .mold-transfer-form,
  .mold-transfer-summary {
    grid-template-columns: 1fr;
  }

  .project-filter-search,
  .sku-list-panel__search,
  .process-detail-tools,
  .process-detail-tools :deep(.el-input) {
    width: 100%;
    max-width: none;
  }
}
</style>

<style scoped>
.production-project-page :deep(>header.page-panel){display:none}
.production-project-page .sku-list-panel__head>div:first-child{display:flex;flex-wrap:wrap;align-items:center;gap:6px}
.production-project-page .sku-list-panel__head .section-title{margin:0;font-size:14px}
.production-project-page .sku-list-panel__head .page-panel-desc{display:none}
</style>

<style scoped>
.production-project-page{height:100%;min-height:0;min-width:0;gap:8px;container-type:inline-size}
.production-project-page :deep(>header.page-panel){padding:8px 10px;flex:none}.production-project-page :deep(.page-panel-header){margin:0}.production-project-page :deep(.page-panel-title){font-size:16px}
.production-project-page .project-filter-bar{flex:none;margin:0;padding:6px;gap:6px;flex-direction:row;flex-wrap:wrap;align-items:center}.production-project-page .project-tag-bar{gap:2px}.production-project-page .project-tag-bar__item{padding:5px 6px}.production-project-page .project-filter-search{flex:1 1 210px;min-width:0;width:auto;max-width:none;height:28px}
.production-project-page .project-module-panel{flex:1;min-height:0;display:flex;flex-direction:column}.production-project-page .project-module-panel__header{flex:none;flex-wrap:wrap;padding:6px 8px;gap:6px;align-items:center}.production-project-page .project-module-panel__desc{display:none}.production-project-page .project-module-panel__body{flex:1;min-height:0;display:flex;flex-direction:column;padding:8px;gap:8px}
.production-project-page .project-list-shell{flex:1;min-height:0;display:flex;flex-direction:column;margin:0}.production-project-page .sku-list-panel{flex:1;min-height:0;display:flex;flex-direction:column;padding:0;gap:6px}.production-project-page .sku-product-home{min-height:0;overflow-y:auto;padding:0}.production-project-page .sku-product-home__head{margin:0}.production-project-page .sku-list-panel__head{flex:none;flex-wrap:wrap;gap:6px}.production-project-page .sku-list-panel__actions{flex-wrap:wrap;gap:6px;flex-direction:row}.production-project-page .sku-list-panel__search{flex:1 1 180px;min-width:0;width:auto;height:28px}.production-project-page .page-panel-desc{margin:0;font-size:12px}.production-project-page .sku-product-grid{gap:8px}
</style>

<style scoped>
.foundation-maintenance-toolbar{display:flex;gap:6px;align-items:center;flex-wrap:wrap;flex:none;padding:0 0 6px}.foundation-maintenance-toolbar .el-button{font-size:12px;height:28px}
</style>
