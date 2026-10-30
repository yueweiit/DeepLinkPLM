<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

import { getProductPresentation } from '@/api/modules/foundation'
import { deleteAttachment, downloadAttachment, getProductAttachments, uploadProductAttachment, type AttachmentVO } from '@/api/modules/attachment'
import { getProductList } from '@/api/modules/product'
import FilePreview from '@/components/FilePreview/index.vue'
import FixedTableViewport from '@/components/FixedTableViewport/index.vue'
import PageContainer from '@/components/PageContainer/index.vue'
import ProjectFlowPanel from '@/components/ProjectFlowPanel/index.vue'
import StatusTag from '@/components/StatusTag/index.vue'
import ProductProductionList from '@/components/ProductionMetadata/ProductProductionList.vue'
import ProductionStatusFilter from '@/components/ProductionMetadata/ProductionStatusFilter.vue'
import ProductProductionControl from '@/components/ProductionMetadata/ProductProductionControl.vue'
import MetadataDialog from '@/components/ProductionMetadata/MetadataDialog.vue'
import {useProductionMetadataStore} from '@/stores/productionMetadata'
import {productionStatusName} from '@/utils/productionStatus'
import type {ProductionMetadataTarget,ProductionStatus} from '@/types/production'
import type {
  FoundationProductRef,
  ProductBomItemRow,
  ProductDetailPresentation,
  ProductDocumentSummary,
  ProductTimelineNode,
  ProductionDocumentPreviewFile,
  SkuProcessRouteRow
} from '@/types/foundation'
import type { ProductSummary } from '@/types/product'
import { formatAmount, formatDate } from '@/utils/format'
import { toInProgressProjectRoute } from '@/utils/projectRoute'

interface SkuDisplayRow extends FoundationProductRef {
  productSpecificCode?: string | null
  phoneModelCode?: string | null
  colorCode?: string | null
  finishedProductCode?: string | null
  displaySkuCode: string
  displayProductLineCode: string
  sampleImageUrl?: string
  sampleImageName?: string
  stockUom?: string
  projectSource?: string
}

type ProductListItem = ProductSummary & {
  createdAt?: string | null
  updatedAt?: string | null
  createdBy?: string | null
}

type SkuPageStage = 'product-home' | 'sku-list'
type SkuDetailSectionKey = 'basic' | 'cost' | 'bom' | 'process' | 'project_flow' | 'production_docs'

const router = useRouter()
const loading = ref(false)
const rows = ref<SkuDisplayRow[]>([])
const pageStage = ref<SkuPageStage>('product-home')
const activeProductId = ref<number | null>(null)
const keyword = ref('')
const selectedSkuIds = ref<number[]>([])
const productionFilter=ref<ProductionStatus|''>(''),productionEditor=ref(false),productionTargets=ref<ProductionMetadataTarget[]>([]),productionPermissions=useProductionMetadataStore()
function editProductionBatch(){const selected=rows.value.filter(r=>selectedSkuIds.value.includes(r.productId));if(!selected.length||selected.length>100)return;productionTargets.value=selected.map(r=>({id:r.productId,label:r.productName,productionStatus:r.productionStatus||'unknown'}));productionEditor.value=true}
const previewImageVisible = ref(false)
const previewImageUrl = ref('')
const sampleImageMap = ref<Record<number, AttachmentVO[]>>({})
const sampleImageUrls = ref<Record<number, string>>({})
const sampleUploadFile = ref<File | null>(null)
const sampleUploadRemark = ref('')
const sampleUploadLoading = ref(false)
const sampleDeleteLoading = ref<number | null>(null)
const createSkuVisible = ref(false)
const deleteLoading = ref(false)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailSku = ref<SkuDisplayRow | null>(null)
const detailPresentation = ref<ProductDetailPresentation | null>(null)
const detailBomVersion = ref('')
const activeDetailSection = ref<SkuDetailSectionKey>('basic')

/* 生产资料预览 */
const productionPreviewVisible = ref(false)
const activeProductionDoc = ref<ProductionDocumentPreviewFile | null>(null)

const skuDetailSections = [
  { key: 'basic' as const, label: '基础信息' },
  { key: 'cost' as const, label: '成本' },
  { key: 'bom' as const, label: '当前版本 BOM' },
  { key: 'process' as const, label: '工艺路线' },
  { key: 'project_flow' as const, label: '项目流程' },
  { key: 'production_docs' as const, label: '生产资料' }
]

const productCards = computed(() =>
  rows.value.reduce<SkuDisplayRow[]>((acc, item) => {
    if (item.productType === 'product_line') {
      if (!acc.some((row) => row.productId === item.productId)) acc.push(item)
      return acc
    }
    if (item.parentProductId && !acc.some((row) => row.productId === item.parentProductId)) {
      acc.push({
        ...item,
        productId: item.parentProductId,
        parentProductId: null,
        productCode: item.seriesName || item.productCode,
        displayProductLineCode: item.displayProductLineCode,
        productName: item.seriesName || item.productName,
        productionStatus:'unknown',
        productType: 'product_line'
      })
    }
    return acc
  }, [])
)

const activeProduct = computed(() => productCards.value.find((item) => item.productId === activeProductId.value) || null)

const currentSkuRows = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  if (!activeProductId.value) return []

  return rows.value.filter((item) => {
    if (item.productType === 'product_line') return false

    const belongsToProduct =
      item.parentProductId === activeProductId.value ||
      (activeProduct.value && item.seriesName === activeProduct.value.seriesName)

    const keywordMatched =
      !search ||
      item.productCode.toLowerCase().includes(search) ||
      item.displaySkuCode.toLowerCase().includes(search) ||
      item.productName.toLowerCase().includes(search) ||
      item.model.toLowerCase().includes(search) ||
      item.color.toLowerCase().includes(search)

    return belongsToProduct && keywordMatched
  })
})

const detailBomItems = computed<ProductBomItemRow[]>(() => {
  if (!detailPresentation.value || !detailBomVersion.value) return []
  return detailPresentation.value.bomItemsByVersion[detailBomVersion.value] || []
})

const detailBomIsDefaultVersion = computed(
  () => Boolean(detailBomVersion.value) && detailBomVersion.value === detailPresentation.value?.defaultBomVersion
)

const detailProcessRoutes = computed<SkuProcessRouteRow[]>(() => {
  return detailPresentation.value?.processRoutes || []
})

const detailTimelineNodes = computed<ProductTimelineNode[]>(() => {
  return detailPresentation.value?.timeline || []
})

function openProductionDocPreview(doc: ProductDocumentSummary) {
  activeProductionDoc.value = {
    fileId: doc.fileId || '',
    fileName: doc.fileName,
    category: doc.category,
    versionNo: doc.versionNo,
    owner: doc.owner,
    updatedAt: doc.updatedAt,
    status: doc.status,
    previewUrl: doc.previewUrl,
    downloadUrl: doc.downloadUrl
  }
  productionPreviewVisible.value = true
}

function getSkuCount(product: FoundationProductRef) {
  return rows.value.filter(
    (item) =>
      item.productType !== 'product_line' &&
      (item.parentProductId === product.productId || item.seriesName === product.seriesName)
  ).length
}

function getSkuUnit(row: SkuDisplayRow) {
  return row.stockUom || 'pcs'
}

function getProjectSource(row: SkuDisplayRow) {
  return row.projectSource || row.customerName || '内部研发'
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

function openSkuList(productId: number) {
  activeProductId.value = productId
  selectedSkuIds.value = []
  keyword.value = ''
  pageStage.value = 'sku-list'
}

function backToProductHome() {
  activeProductId.value = null
  selectedSkuIds.value = []
  keyword.value = ''
  pageStage.value = 'product-home'
}

function handleSkuSelectionChange(selectedRows: SkuDisplayRow[]) {
  selectedSkuIds.value = selectedRows.map((row) => row.productId)
}

function getSkuSampleImages(row: Pick<SkuDisplayRow,'productId'> | null | undefined) {
  if (!row) return []
  return sampleImageMap.value[row.productId] || []
}

function getSkuSampleImageUrl(row: Pick<SkuDisplayRow,'productId'|'sampleImageUrl'> | null | undefined) {
  if (!row) return ''
  return sampleImageUrls.value[row.productId] || row.sampleImageUrl || ''
}

function getSampleImageCount(row: Pick<SkuDisplayRow,'productId'> | null | undefined) {
  return getSkuSampleImages(row).length
}

function openPreview(url?: string) {
  previewImageUrl.value = url || ''
  previewImageVisible.value = true
}

async function openAttachmentPreview(attachment: AttachmentVO) {
  const url = sampleImageUrls.value[attachment.ownerObjectId]
  if (url && getSkuSampleImages(detailSku.value)[0]?.attachmentId === attachment.attachmentId) {
    openPreview(url)
    return
  }
  const blob = await downloadAttachment(attachment.attachmentId)
  const objectUrl = URL.createObjectURL(blob)
  openPreview(objectUrl)
}

async function refreshSkuSampleImages(productId: number) {
  const images = await getProductAttachments(productId, 'sample_image')
  sampleImageMap.value = { ...sampleImageMap.value, [productId]: images }
  if (!images.length) {
    sampleImageUrls.value = { ...sampleImageUrls.value, [productId]: '' }
    return
  }
  const blob = await downloadAttachment(images[0].attachmentId)
  sampleImageUrls.value = { ...sampleImageUrls.value, [productId]: URL.createObjectURL(blob) }
}

async function refreshVisibleSampleImages() {
  const imageRows = rows.value.filter((item) => item.productType !== 'product_line')
  await Promise.all(imageRows.slice(0, 40).map((item) => refreshSkuSampleImages(item.productId).catch(() => undefined)))
}

function handleSampleUploadFileChange(event: Event) {
  sampleUploadFile.value = (event.target as HTMLInputElement).files?.[0] || null
}

async function submitSampleImageUpload() {
  if (!detailSku.value) return
  if (!sampleUploadFile.value) {
    ElMessage.warning(uiText('请选择要上传的示例照片'))
    return
  }
  sampleUploadLoading.value = true
  try {
    await uploadProductAttachment(detailSku.value.productId, sampleUploadFile.value, {
      fileCategory: 'sample_image',
      remark: sampleUploadRemark.value.trim() || 'SKU示例照片'
    })
    sampleUploadFile.value = null
    sampleUploadRemark.value = ''
    await refreshSkuSampleImages(detailSku.value.productId)
    ElMessage.success(uiText('示例照片已上传'))
  } finally {
    sampleUploadLoading.value = false
  }
}

async function removeSampleImage(attachment: AttachmentVO) {
  await ElMessageBox.confirm(uiText('确认删除“{value0}”吗？',{value0:(attachment.originalFileName || attachment.fileName)}), uiText('删除示例照片'), {
    confirmButtonText: uiText('删除'),
    cancelButtonText: uiText('取消'),
    type: 'warning'
  })
  sampleDeleteLoading.value = attachment.attachmentId
  try {
    await deleteAttachment(attachment.attachmentId)
    await refreshSkuSampleImages(attachment.ownerObjectId)
    ElMessage.success(uiText('示例照片已删除'))
  } finally {
    sampleDeleteLoading.value = null
  }
}

function deleteSelectedSkus() {
  deleteLoading.value = true
  try {
    rows.value = rows.value.filter((row) => !selectedSkuIds.value.includes(row.productId))
    selectedSkuIds.value = []
  } finally {
    deleteLoading.value = false
  }
}

async function openSkuDetail(row: SkuDisplayRow) {
  detailSku.value = row
  activeDetailSection.value = 'basic'
  detailVisible.value = true
  detailLoading.value = true

  try {
    const presentation = await getProductPresentation(row.productId)
    detailPresentation.value = presentation
    detailBomVersion.value = presentation.defaultBomVersion || presentation.bomCompareRows[0]?.versionNo || ''
  } catch {
    detailPresentation.value = null
    detailBomVersion.value = ''
  } finally {
    detailLoading.value = false
  }
}

function resolveDisplaySkuCode(product: ProductListItem) {
  return product.finishedProductCode || product.productCode || ''
}

function resolveDisplayProductLineCode(product: ProductListItem) {
  const productCode = product.productCode || ''
  if (product.productType !== 'product_line' || !productCode.startsWith('PRD-')) {
    return productCode
  }
  const productSpecificCode = product.productSpecificCode?.trim().toUpperCase()
  return productSpecificCode ? `N${productSpecificCode}4030` : productCode
}

function toSkuDisplayRow(product: ProductListItem): SkuDisplayRow {
  return {
    productId: product.productId,
    parentProductId: product.parentProductId ?? null,
    productCode: product.productCode || '',
    productSpecificCode: product.productSpecificCode || null,
    phoneModelCode: product.phoneModelCode || null,
    colorCode: product.colorCode || null,
    finishedProductCode: product.finishedProductCode || null,
    displaySkuCode: resolveDisplaySkuCode(product),
    displayProductLineCode: resolveDisplayProductLineCode(product),
    productName: product.productName || '',
    productType: product.productType || 'model_variant',
    seriesName: product.seriesName || '--',
    model: product.model || '--',
    color: product.color || '--',
    customerName: product.customerName || '',
    versionNo: product.versionNo || 'A',
    status: product.status || 'draft',
    productionStatus:product.productionStatus||'unknown',
    currentStage: product.currentStage || product.status || '--',
    estimatedCost: product.estimatedCost || 0,
    actualCost: product.actualCost || 0,
    lastActiveAt: product.updatedAt || product.createdAt || '',
    createdAt: product.createdAt || '',
    stockUom: 'pcs',
    projectSource: product.createdBy || product.customerName || '历史存档'
  }
}

async function loadData() {
  selectedSkuIds.value=[]
  const productionParams=productionFilter.value?{productionStatus:productionFilter.value}:{}
  loading.value = true
  try {
    const results = await Promise.allSettled([
      getProductList({ page: 1, size: 200, productType: 'product_line' }),
      getProductList({ page: 1, size: 200, productType: 'model_variant',...productionParams }),
      getProductList({ page: 1, size: 200, productType: 'sku',...productionParams }),
      getProductList({ page: 1, size: 200, status: 'released',...productionParams }),
      getProductList({ page: 1, size: 200, status: 'archived',...productionParams })
    ])
    const mergedProducts = new Map<number, ProductListItem>()
    results.forEach((result) => {
      if (result.status === 'fulfilled') {
        result.value.forEach((item) => mergedProducts.set(item.productId, item))
      }
    })
    rows.value = Array.from(mergedProducts.values()).map(toSkuDisplayRow)
    await refreshVisibleSampleImages()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(()=>{void productionPermissions.ensure();void loadData()})
</script>

<template>
  <PageContainer
    class="production-sku-page"
    :title='uiText("SKU 管理")'
    :description='uiText("先按产品卡片定位，再进入该产品下的 SKU 列表进行管理。")'
  >
    <section v-if="pageStage === 'product-home'" class="page-panel sku-product-home">
      <div class="toolbar-row sku-product-home__head">
        <div>
          <h3 class="section-title">{{ uiText("产品入口") }}</h3>
          <p class="page-panel-desc">{{ uiText("SKU 作为 Product 的型号、颜色和版本视图展示，不作为独立根对象。") }}</p>
        </div>
        <el-button type="primary" @click="router.push(toInProgressProjectRoute())">{{ uiText("新项目") }}</el-button>
      </div>

      <div class="sku-product-grid" v-loading="loading">
        <button
          v-for="product in productCards"
          :key="product.productId"
          class="sku-product-card"
          type="button"
          @click="openSkuList(product.productId)"
        >
          <div class="sku-product-card__image">
            <span>{{ product.seriesName }}</span>
          </div>
          <div class="toolbar-row">
            <strong>{{ product.productName }}</strong>
            <StatusTag :status="product.status" object-type="product" /><el-tag size="small">{{ uiText("生产：") }}{{productionStatusName(product.productionStatus)}}</el-tag>
          </div>
          <p class="subtle-text">{{ product.displayProductLineCode }}</p>
          <p class="sku-product-card__series">{{ product.seriesName }}</p>
          <div class="sku-product-card__meta">
            <span>{{ getSkuCount(product) }}{{ uiText(" 个 SKU") }}</span>
            <span class="subtle-text">{{ uiText("更新 ") }}{{ formatDate(product.lastActiveAt) }}</span>
          </div>
        </button>
      </div>
    </section>

    <section v-else class="page-panel sku-list-panel" v-loading="loading">
      <div class="toolbar-row sku-list-panel__head">
        <div>
          <el-button link type="primary" @click="backToProductHome">{{ uiText("返回产品卡片") }}</el-button>
          <h3 class="section-title">{{ activeProduct?.productName }}{{ uiText(" / SKU 列表") }}</h3>
          <p class="page-panel-desc">{{ uiText("查看当前产品下的 SKU 信息、示例图、版本状态和详情资料。") }}</p>
        </div>

        <div class="sku-list-panel__actions">
          <ProductionStatusFilter v-model="productionFilter" @change="loadData"/><el-button v-if="productionPermissions.canMaintain" size="small" :disabled="!selectedSkuIds.length||selectedSkuIds.length>100" @click="editProductionBatch">{{ uiText("批量维护 (") }}{{selectedSkuIds.length}})</el-button>
          <el-input
            v-model="keyword"
            clearable
            class="sku-list-panel__search"
            :placeholder='uiText("搜索编码 / 名称 / 型号 / 颜色")'
          />
          <el-button type="primary" @click="createSkuVisible = true">{{ uiText("新增 SKU") }}</el-button>
          <el-button
            type="danger"
            :disabled="!selectedSkuIds.length"
            :loading="deleteLoading"
            @click="deleteSelectedSkus"
          >{{ uiText(" 删除 SKU ") }}</el-button>
        </div>
      </div>

      <ProductProductionList :rows="currentSkuRows" sku selectable @selection-change="handleSkuSelectionChange($event as SkuDisplayRow[])" @detail="openSkuDetail" @changed="loadData"><template #image="{row}"><div v-if="getSkuSampleImageUrl(row)" class="sku-image-cell"><el-image :src="getSkuSampleImageUrl(row)" fit="cover" style="width:40px;height:40px"/><el-button size="small" link @click="openPreview(getSkuSampleImageUrl(row))">{{ uiText("查看示例图 (") }}{{getSampleImageCount(row)}})</el-button></div></template></ProductProductionList>
    </section>

    <el-dialog v-model="detailVisible" :title='uiText("SKU 详情")' width="1080px" destroy-on-close>
      <div v-loading="detailLoading" class="sku-detail-dialog">
        <nav class="sku-detail-breadcrumb" :aria-label='uiText("SKU 详情导航")'>
          <span class="sku-detail-breadcrumb__root">{{ uiText("SKU 详情") }}</span><el-button size="small" v-if="detailSku" @click="router.push({path:'/files',query:{referenceType:detailSku.productType === 'sku' ? 'sku' : 'product',referenceId:detailSku.productId,referenceName:detailSku.productName,referenceCode:detailSku.displaySkuCode}})">{{ uiText("查看关联图纸") }}</el-button>
          <template v-for="section in skuDetailSections" :key="section.key">
            <span class="sku-detail-breadcrumb__separator">/</span>
            <button
              class="sku-detail-breadcrumb__item"
              :class="{ 'is-active': activeDetailSection === section.key }"
              type="button"
              @click="activeDetailSection = section.key"
            >
              {{ uiText(String((section.label) ?? '')) }}
            </button>
          </template>
        </nav>

        <section v-show="activeDetailSection === 'basic'" class="sku-detail-section">
          <h4 class="section-title">{{ uiText("基础信息") }}</h4>
          <div class="sku-detail-grid">
            <div class="info-card"><span class="subtle-text">{{ uiText("SKU 编码") }}</span><strong>{{ detailSku?.displaySkuCode }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("SKU 名称") }}</span><strong>{{ detailSku?.productName }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("Product 编码") }}</span><strong>{{ detailSku?.productCode }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("产品线") }}</span><strong>{{ detailSku?.seriesName }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("型号") }}</span><strong>{{ detailSku?.model }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("手机型号编码") }}</span><strong>{{ detailSku?.phoneModelCode || '--' }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("单位") }}</span><strong>{{ detailSku ? getSkuUnit(detailSku) : '--' }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("项目来源") }}</span><strong>{{ detailSku ? getProjectSource(detailSku) : '--' }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("颜色") }}</span><strong>{{ detailSku?.color }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("颜色编码") }}</span><strong>{{ detailSku?.colorCode || '--' }}</strong></div>
            <div class="info-card"><span class="subtle-text">{{ uiText("版本") }}</span><strong>{{ detailSku?.versionNo }}</strong></div>
            <div class="info-card">
              <span class="subtle-text">{{ uiText("状态") }}</span>
              <StatusTag v-if="detailSku" :status="detailSku.status" object-type="product" /><ProductProductionControl v-if="detailSku" :product-id="detailSku.productId" :model-value="detailSku.productionStatus||'unknown'" :label="detailSku.productName" @update:model-value="detailSku.productionStatus=$event" @changed="loadData"/>
            </div>
          </div>

          <div class="sku-sample-panel">
            <div class="sku-detail-section__head">
              <div>
                <h5>{{ uiText("示例照片") }}</h5>
                <p class="page-panel-desc">{{ uiText("照片以 Product 附件保存，文件分类为 sample_image。") }}</p>
              </div>
              <el-tag effect="light">{{ getSampleImageCount(detailSku) }}{{ uiText(" 张") }}</el-tag>
            </div>

            <div class="sku-sample-upload">
              <input data-test="sku-sample-upload-file" type="file" accept="image/jpeg,image/png,image/webp" @change="handleSampleUploadFileChange" />
              <el-input v-model="sampleUploadRemark" clearable maxlength="255" :placeholder='uiText("照片备注")' />
              <el-button type="primary" :loading="sampleUploadLoading" @click="submitSampleImageUpload">{{ uiText("上传照片") }}</el-button>
            </div>

            <div v-if="getSkuSampleImages(detailSku).length" class="sku-sample-gallery">
              <article v-for="image in getSkuSampleImages(detailSku)" :key="image.attachmentId" class="sku-sample-gallery__item">
                <button type="button" class="sku-sample-gallery__preview" @click="openAttachmentPreview(image)">
                  <el-image v-if="sampleImageUrls[image.ownerObjectId] && getSkuSampleImages(detailSku)[0]?.attachmentId === image.attachmentId" :src="sampleImageUrls[image.ownerObjectId]" fit="cover" />
                  <span v-else>{{ image.originalFileName || image.fileName }}</span>
                </button>
                <div class="sku-sample-gallery__meta">
                  <strong>{{ image.originalFileName || image.fileName }}</strong>
                  <span class="subtle-text">{{ image.remark || uiText('无备注') }}</span>
                </div>
                <el-button link type="danger" :loading="sampleDeleteLoading === image.attachmentId" @click="removeSampleImage(image)">{{ uiText("删除") }}</el-button>
              </article>
            </div>
            <el-empty v-else :description='uiText("暂无示例照片")' />
          </div>
        </section>

        <section v-show="activeDetailSection === 'cost'" class="sku-detail-section">
          <h4 class="section-title">{{ uiText("成本") }}</h4>
          <div class="sku-detail-cost-grid">
            <div class="info-card">
              <span class="subtle-text">{{ uiText("实际成本") }}</span>
              <strong>{{ formatAmount(detailPresentation?.costPanel.actualTotal || 0) }}</strong>
            </div>
            <div v-if="detailPresentation?.costPanel.showEstimated" class="info-card">
              <span class="subtle-text">{{ uiText("预计成本") }}</span>
              <strong>{{ formatAmount(detailPresentation?.costPanel.estimatedTotal || 0) }}</strong>
            </div>
            <div class="info-card">
              <span class="subtle-text">{{ uiText("当前阶段") }}</span>
              <strong>{{ detailSku?.currentStage }}</strong>
            </div>
          </div>
        </section>

        <section v-show="activeDetailSection === 'bom'" class="sku-detail-section">
          <div class="sku-detail-section__head">
            <div>
              <div class="sku-detail-title-row">
                <h4 class="section-title">{{ uiText("当前版本 BOM") }}</h4>
                <el-tag v-if="detailBomIsDefaultVersion" type="success" effect="light" size="small">{{ uiText("正式版本") }}</el-tag>
              </div>
              <p class="page-panel-desc">{{ uiText("BOM 作为 Product 的版本化资料展示，不作为独立根对象。") }}</p>
            </div>
            <el-select v-model="detailBomVersion" class="sku-detail-version-select" :placeholder='uiText("选择 BOM 版本")'>
              <el-option
                v-for="row in detailPresentation?.bomCompareRows || []"
                :key="row.versionNo"
                :label="row.versionNo"
                :value="row.versionNo"
              />
            </el-select>
          </div>

          <el-table :data="detailBomItems" border stripe>
            <el-table-column prop="inventoryCode" :label='uiText("物料编码")' min-width="150" />
            <el-table-column prop="inventoryName" :label='uiText("物料名称")' min-width="180" />
            <el-table-column prop="quantity" :label='uiText("用量")' width="90" />
            <el-table-column prop="stockUom" :label='uiText("单位")' width="90" />
            <el-table-column prop="supplierName" :label='uiText("供应商")' min-width="150" />
            <el-table-column :label='uiText("备注 / 替代料提示")' min-width="170">
              <template #default="{ row }">{{ getBomRemark(row) }}</template>
            </el-table-column>
            <el-table-column :label='uiText("成本")' width="130">
              <template #default="{ row }">{{ formatAmount(getBomLineCost(row)) }}</template>
            </el-table-column>
          </el-table>
        </section>

        <section v-show="activeDetailSection === 'process'" class="sku-detail-section">
          <div class="sku-detail-section__head">
            <div>
              <h4 class="section-title">{{ uiText("工艺路线") }}</h4>
              <p class="page-panel-desc">{{ uiText("工艺路线由 Process 承接，以工序明细表展示当前 SKU 的完整工序、关联模具和检验要求。") }}</p>
            </div>
            <el-tag type="primary" effect="light">{{ uiText("共 ") }}{{ detailProcessRoutes.length }}{{ uiText(" 道工序") }}</el-tag>
          </div>

          <el-table v-if="detailProcessRoutes.length" :data="detailProcessRoutes" border stripe size="small">
            <el-table-column :label='uiText("顺序")' width="60">
              <template #default="{ row }">{{ row.sequenceNo }}</template>
            </el-table-column>
            <el-table-column prop="processCode" :label='uiText("工序编码")' min-width="150" />
            <el-table-column prop="processName" :label='uiText("工序名称")' min-width="130" />
            <el-table-column :label='uiText("工序类型")' width="110">
              <template #default="{ row }">
                <el-tag :type="row.processType === 'quality_gate' ? 'danger' : 'primary'" effect="light" size="small">
                  {{ row.processType === 'quality_gate' ? uiText('质量门禁') : uiText('加工工序') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label='uiText("模具 / 治具")' min-width="160">
              <template #default="{ row }">
                <template v-if="row.inventoryCode">
                  <div>{{ row.inventoryCode }}</div>
                  <span class="subtle-text" style="font-size:12px">{{ row.inventoryName }}</span>
                </template>
                <span v-else class="subtle-text">--</span>
              </template>
            </el-table-column>
            <el-table-column :label='uiText("执行位置")' min-width="130">
              <template #default="{ row }">{{ row.workstationName || '--' }}</template>
            </el-table-column>
            <el-table-column :label='uiText("供应商")' min-width="120">
              <template #default="{ row }">{{ row.supplierName || '--' }}</template>
            </el-table-column>
            <el-table-column :label='uiText("质量要求")' min-width="180">
              <template #default="{ row }">{{ row.qualityRequirement || '--' }}</template>
            </el-table-column>
            <el-table-column :label='uiText("产出类型")' width="100">
              <template #default="{ row }">{{ row.outputType || '--' }}</template>
            </el-table-column>
            <el-table-column prop="summary" :label='uiText("说明")' min-width="200" />
          </el-table>
          <el-empty v-else :description='uiText("暂无工艺路线数据")' />
        </section>

        <section v-show="activeDetailSection === 'project_flow'" class="sku-detail-section">
          <div class="sku-detail-section__head">
            <div>
              <h4 class="section-title">{{ uiText("项目流程") }}</h4>
              <p class="page-panel-desc">{{ uiText("展示当前 SKU 对应 Product 的项目流程节点、接收人与推动记录。") }}</p>
            </div>
          </div>

          <ProjectFlowPanel
            v-if="detailTimelineNodes.length"
            :nodes="detailTimelineNodes"
            :compact="true"
            :max-nodes="8"
          />
          <el-empty v-else :description='uiText("暂无项目流程数据")' />
        </section>

        <section v-show="activeDetailSection === 'production_docs'" class="sku-detail-section">
          <div class="sku-detail-section__head">
            <div>
              <h4 class="section-title">{{ uiText("生产资料") }}</h4>
              <p class="page-panel-desc">{{ uiText("展示归档或当前版本关联的图纸、SOP、SIP、检验标准和客户确认件。") }}</p>
            </div>
          </div>

          <el-table v-if="detailPresentation?.documents?.length" :data="detailPresentation.documents" border stripe size="small">
            <el-table-column :label='uiText("资料类型")' width="120">
              <template #default="{ row }">{{ row.category }}</template>
            </el-table-column>
            <el-table-column :label='uiText("资料编码")' width="140">
              <template #default="{ row }">{{ row.fileId || '--' }}</template>
            </el-table-column>
            <el-table-column prop="fileName" :label='uiText("资料名称")' min-width="200" />
            <el-table-column prop="versionNo" :label='uiText("版本")' width="90" />
            <el-table-column :label='uiText("状态")' width="100">
              <template #default="{ row }">
                <el-tag v-if="row.status" size="small" effect="light" :type="row.status === '已冻结' ? 'success' : row.status === '已归档' ? 'info' : 'warning'">
                  {{ row.status }}
                </el-tag>
                <span v-else class="subtle-text">--</span>
              </template>
            </el-table-column>
            <el-table-column :label='uiText("负责人")' width="110">
              <template #default="{ row }">{{ row.owner || '--' }}</template>
            </el-table-column>
            <el-table-column :label='uiText("所属阶段")' width="120">
              <template #default="{ row }">{{ row.stageLabel || row.category }}</template>
            </el-table-column>
            <el-table-column :label='uiText("更新时间")' width="130">
              <template #default="{ row }">{{ formatDate(row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column :label='uiText("操作")' width="130" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openProductionDocPreview(row)">{{ uiText("预览") }}</el-button>
                <el-button link type="primary" size="small">{{ uiText("下载") }}</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else :description='uiText("暂无生产资料")' />
        </section>
      </div>
      <!-- 生产资料预览 -->
      <FilePreview
        v-model="productionPreviewVisible"
        :file="activeProductionDoc"
      />
    </el-dialog>

    <el-dialog v-model="previewImageVisible" :title='uiText("SKU 示例图")' width="640px">
      <el-image v-if="previewImageUrl" :src="previewImageUrl" fit="contain" class="sku-preview-image" />
      <p v-else class="subtle-text empty-preview">{{ uiText("暂无示例图") }}</p>
    </el-dialog>
    <MetadataDialog v-model="productionEditor" kind="product" :items="productionTargets" @saved="selectedSkuIds=[];loadData()"/>
  </PageContainer>
</template>

<style scoped>
.production-filter{width:156px}
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
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
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
.sku-image-cell,
.sku-detail-section__head,
.sku-detail-title-row {
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

.sku-list-panel__actions {
  flex-shrink: 0;
}

.sku-list-panel__search {
  width: 260px;
}

.sku-image-cell__thumb {
  width: 52px;
  height: 52px;
  overflow: hidden;
  font-size: 11px;
}

.sku-image-cell__thumb :deep(.el-image),
.sku-image-cell__thumb :deep(img) {
  width: 100%;
  height: 100%;
}

.sku-image-cell__actions {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.sku-sample-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 4px;
}

.sku-sample-panel h5 {
  margin: 0;
  font-size: 14px;
}

.sku-sample-upload {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(220px, 1.2fr) auto;
  gap: 10px;
  align-items: center;
}

.sku-sample-upload input[type="file"] {
  min-height: 32px;
}

.sku-sample-gallery {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.sku-sample-gallery__item {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  padding: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}

.sku-sample-gallery__preview {
  display: grid;
  place-items: center;
  width: 72px;
  height: 72px;
  overflow: hidden;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
  color: #64748b;
  cursor: pointer;
  font-size: 12px;
}

.sku-sample-gallery__preview :deep(.el-image),
.sku-sample-gallery__preview :deep(img) {
  width: 100%;
  height: 100%;
}

.sku-sample-gallery__meta {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.sku-sample-gallery__meta strong,
.sku-sample-gallery__meta span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sku-detail-dialog {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.sku-detail-breadcrumb {
  display: flex;
  align-items: center;
  gap: 0;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.2);
}

.sku-detail-breadcrumb__root {
  margin-right: 6px;
  color: #0f172a;
  font-size: 14px;
  font-weight: 600;
}

.sku-detail-breadcrumb__separator {
  margin: 0 4px;
  color: #94a3b8;
  font-size: 13px;
}

.sku-detail-breadcrumb__item {
  padding: 4px 8px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #64748b;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.16s, color 0.16s;
}

.sku-detail-breadcrumb__item:hover {
  background: #f1f5f9;
  color: #334155;
}

.sku-detail-breadcrumb__item.is-active {
  background: #eff6ff;
  color: #1d4ed8;
  font-weight: 600;
}

.sku-detail-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sku-detail-section__head {
  align-items: flex-start;
  justify-content: space-between;
}

.sku-detail-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.sku-detail-cost-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.sku-detail-version-select {
  width: 170px;
}

.sku-preview-image {
  width: 100%;
  max-height: 70vh;
}

.empty-preview {
  padding: 40px;
  text-align: center;
}

@media (max-width: 1400px) {
  .sku-product-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1100px) {
  .sku-product-grid,
  .sku-detail-grid,
  .sku-detail-cost-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .sku-product-grid,
  .sku-detail-grid,
  .sku-detail-cost-grid {
    grid-template-columns: 1fr;
  }

  .sku-list-panel__actions,
  .sku-detail-section__head {
    width: 100%;
    flex-direction: column;
    align-items: stretch;
  }

  .sku-list-panel__search,
  .sku-detail-version-select {
    width: 100%;
  }
}
</style>

<style scoped>
.production-sku-page{height:100%;min-height:0;min-width:0;gap:8px}.production-sku-page :deep(>header.page-panel){padding:8px 10px;flex:none}.production-sku-page :deep(.page-panel-header){margin:0}.production-sku-page :deep(.page-panel-title){font-size:16px}.production-sku-page :deep(.page-panel-desc){margin:0;font-size:12px}
.production-sku-page .sku-product-home{flex:1;min-height:0;overflow-y:auto;padding:8px}.production-sku-page .sku-list-panel{flex:1;min-height:0;display:flex;flex-direction:column;gap:6px;padding:8px}.production-sku-page .sku-list-panel__head{flex:none;flex-wrap:wrap;gap:6px}.production-sku-page .sku-list-panel__actions{flex-direction:row;flex-wrap:wrap;gap:6px;align-items:center}.production-sku-page .sku-list-panel__search{flex:1 1 180px;min-width:0;width:auto;height:28px}
</style>








