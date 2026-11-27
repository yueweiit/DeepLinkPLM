<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { t as uiText } from '@/locales'
import { catalogListingOptions, confirmCatalogListing } from '@/api/modules/partnerOperations'
import { newRequestId } from '@/api/modules/partner'
import type { CatalogListingOptions, CatalogListingRequest } from '@/types/partnerLifecycle'
import type { SelectionRow } from '@/types/partnerSelections'
import { partnerError } from '@/utils/partnerError'
import PartnerPhoto from '../PartnerPhoto.vue'

const props = defineProps<{ selectionId: number; rows: SelectionRow[] }>()
const emit = defineEmits<{ close: []; saved: [] }>()
type Item = { row: SelectionRow; options?: CatalogListingOptions; loading: boolean; error: string; selected: string[]; photos: number[]; name: string; introduction: string; included: boolean; saved: boolean; requestId: string; payload?: CatalogListingRequest }
const items = reactive<Item[]>(props.rows.map(row => ({ row, loading: true, error: '', selected: [], photos: [], name: row.name, introduction: '', included: true, saved: false, requestId: newRequestId() })))
const busy = ref(false)
let disposed = false
onBeforeUnmount(() => { disposed = true })
const key = (pair: { phoneModelId: number; colorId: number }) => `${pair.phoneModelId}:${pair.colorId}`
const valid = (item: Item) => !!item.options && !!item.name.trim() && !!item.introduction.trim() && !!item.photos.length && !!item.selected.length && item.selected.every(value => item.options!.pairs.some(pair => key(pair) === value && pair.eligible))
const pending = computed(() => items.filter(item => item.included && !item.saved))
const canSubmit = computed(() => !busy.value && items.every(item => !item.loading) && pending.value.length > 0 && pending.value.every(item => item.payload || valid(item)))
const savedCount = computed(() => items.filter(item => item.saved).length)
async function load(item: Item) {
  if (item.payload || busy.value) return
  item.loading = true; item.error = ''
  try {
    if (!item.row.confirmed || item.row.discarded || !item.row.catalogStyleId) throw new Error(uiText('本款未确认、已取消或缺少目录来源'))
    const result = await catalogListingOptions(props.selectionId, item.row.rowId)
    if (disposed) return
    item.options = result; item.name = result.name; item.photos = result.photos.map(photo => photo.id)
  } catch (error) { if (!disposed) item.error = partnerError(error) }
  finally { if (!disposed) item.loading = false }
}
// Keep source reads bounded for a cross-page selection.
async function loadAll() { for (const item of items) { if (disposed) return; await load(item) } }
void loadAll()
async function submit() {
  if (!canSubmit.value) return
  busy.value = true
  const queue = [...pending.value]
  try {
    for (const item of queue) {
      if (!item.payload) {
        const data = item.options!
        item.payload = {
          selectionId: props.selectionId, selectionRowId: item.row.rowId,
          selectionVersion: data.selectionVersion, selectionRowVersion: data.selectionRowVersion,
          selectedPairs: data.pairs.filter(pair => item.selected.includes(key(pair))).map(({ phoneModelId, colorId }) => ({ phoneModelId, colorId })),
          productLinkMode: 'create', name: item.name.trim(), introduction: item.introduction.trim(),
          refs: data.photos.filter(photo => item.photos.includes(photo.id)).map(photo => ({ ...photo, type: 'file' })), requestId: item.requestId
        }
      }
      item.error = ''
      try { await confirmCatalogListing(item.payload); item.saved = true; emit('saved') }
      catch (error) { item.error = partnerError(error) }
    }
  } finally { busy.value = false }
}
async function close() {
  if (busy.value) return
  if (items.some(item => !item.saved && (item.selected.length || item.introduction.trim() || item.payload))) {
    try { await ElMessageBox.confirm(uiText('关闭后未保存内容将丢弃，已保存的款式不受影响。'), uiText('关闭批量上架'), { confirmButtonText: uiText('关闭'), cancelButtonText: uiText('继续填写') }) }
    catch { return }
  }
  emit('close')
}
</script>

<template>
  <el-dialog :model-value="true" :title="uiText('批量准备上架')" width="min(960px,96vw)" top="20px" class="catalog-batch-listing" append-to-body :close-on-click-modal="false" :close-on-press-escape="false" :before-close="close">
    <p class="batch-summary">{{uiText('已保存')}} {{savedCount}} / {{items.length}} · {{uiText('待提交')}} {{pending.length}}</p>
    <section v-for="item in items" :key="item.row.rowId" class="batch-listing-item" :aria-label="item.row.name" v-loading="item.loading">
      <div class="item-heading">
        <el-checkbox v-model="item.included" :disabled="busy||item.saved||!!item.payload">{{item.row.catalogStyleCode}} · {{item.row.name}}</el-checkbox>
        <el-tag v-if="item.saved" type="success">{{uiText('已保存待上架')}}</el-tag>
      </div>
      <el-alert v-if="item.error" :title="item.error" type="error" :closable="false"><el-button v-if="!item.options&&!item.payload" link :disabled="busy||item.loading" @click="load(item)">{{uiText('重试读取')}}</el-button></el-alert>
      <template v-if="item.options && item.included && !item.saved">
        <el-form label-position="top" :disabled="busy||!!item.payload">
          <el-form-item :label="uiText('本次决定经营的规格')">
            <el-checkbox-group v-model="item.selected" class="batch-pairs">
              <div v-for="pair in item.options.pairs" :key="key(pair)" class="batch-pair">
                <el-checkbox :value="key(pair)" :disabled="!pair.eligible||busy||!!item.payload">{{pair.specName}}</el-checkbox>
                <span v-if="!pair.eligible" class="pair-reason">{{pair.reason||uiText('尚缺适用质检／签字依据')}}</span>
              </div>
            </el-checkbox-group>
            <span v-if="!item.options.pairs.length">{{uiText('暂无真实收样规格')}}</span>
          </el-form-item>
          <el-form-item :label="uiText('商品名称 *')"><el-input v-model="item.name" maxlength="255"/></el-form-item>
          <el-form-item :label="uiText('简短介绍 *')"><el-input v-model="item.introduction" type="textarea" :autosize="{minRows:2,maxRows:5}"/></el-form-item>
          <el-form-item :label="uiText('商品图片 *')">
            <el-checkbox-group v-model="item.photos" class="batch-photos">
              <div v-for="photo in item.options.photos" :key="photo.id" class="batch-photo"><PartnerPhoto :id="photo.id" :allow-retry="true"/><el-checkbox :value="photo.id">{{photo.name}}</el-checkbox></div>
            </el-checkbox-group>
            <span v-if="!item.options.photos.length">{{uiText('请先补齐本款商品图片')}}</span>
          </el-form-item>
        </el-form>
      </template>
    </section>
    <p class="batch-note">{{uiText('本次按款创建合作产品档案，仅保存待上架；不创建正式 SKU，不向 CRM 发起上架。')}}</p>
    <template #footer><el-button :disabled="busy" @click="close">{{uiText('返回')}}</el-button><el-button type="primary" :loading="busy" :disabled="!canSubmit" @click="submit">{{uiText('批量保存待上架')}} ({{pending.length}})</el-button></template>
  </el-dialog>
</template>

<style scoped>
:global(.catalog-batch-listing){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-bottom:20px}
:global(.catalog-batch-listing>.el-dialog__body){min-height:0;overflow:auto}
:global(.catalog-batch-listing>.el-dialog__footer){display:flex;justify-content:flex-end;flex-wrap:wrap;gap:8px}
.batch-summary{margin:0 0 12px}.batch-listing-item{padding:16px 0;border-top:1px solid var(--el-border-color-lighter);min-height:60px}
.item-heading{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-bottom:10px}
.batch-pairs{display:flex;flex-direction:column;gap:8px;line-height:1.6;width:100%}.batch-pair{display:flex;flex-wrap:wrap;align-items:center;gap:4px 12px;line-height:1.6}
.pair-reason,.batch-note{color:var(--el-text-color-secondary);font-size:12px;overflow-wrap:anywhere}
.batch-photos{display:flex;flex-wrap:wrap;gap:8px;line-height:1.6}.batch-photo{display:flex;align-items:center;gap:8px;max-width:100%;min-width:0}
:deep(.el-checkbox){white-space:normal;height:auto;margin-right:0;max-width:100%}:deep(.el-checkbox__label){white-space:normal;overflow-wrap:anywhere;line-height:1.6}
</style>
