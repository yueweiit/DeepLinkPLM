<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { confirmPartnerListing, operationCapabilities, operationTarget } from '@/api/modules/partnerOperations'
import { newRequestId } from '@/api/modules/partner'
import { partnerError } from '@/utils/partnerError'
import type { PartnerRow, BusinessData } from '@/types/partner'
import PartnerOperationsPanel from './PartnerOperationsPanel.vue'
import PartnerSpecSelect from '../components/PartnerSpecSelect.vue'
const props = defineProps<{ product: PartnerRow }>()
const route = useRoute(), router = useRouter(), canConfirm = ref(false), specIds = ref<number[]>([]), supplierCode = ref(''), error = ref(''), busy = ref(false)
let requestId = newRequestId()
void operationCapabilities().then(c => { canConfirm.value = c.listing }).catch(e => { error.value = partnerError(e) })
const suppliers = computed(() => Array.from(new Map((props.product.specs || []).flatMap((s: BusinessData) => (s.sources || []).map((p: BusinessData) => [p.supplierCode, p] as const))).values()) as BusinessData[])
async function confirm() { busy.value = true; error.value = ''; try { const row = await confirmPartnerListing(props.product.id, { version: props.product.version, requestId, specIds: specIds.value, supplierCode: supplierCode.value }); requestId = newRequestId(); void router.push(operationTarget('listing', row.id, route.fullPath)) } catch (e) { error.value = partnerError(e) } finally { busy.value = false } }
</script>
<template><section class="listing-panel"><el-alert :title='uiText("已上架老商品保留原状态；缺少检验依据不自动认定合格。本地只保存确认，CRM／商城正式外写尚未接通。")' type="info" :closable="false"/><p>{{ uiText("新上架按所选规格核对：名称、介绍、图片、最新样品质检、工厂签字及原台账对照样品。未定售价按询价，不因未定价或参考周期为空卡住准备。") }}</p><el-form v-if="canConfirm" label-position="top"><el-form-item :label='uiText("本次采用供应商")'><el-select v-model="supplierCode" filterable><el-option v-for="s in suppliers" :key="s.supplierCode" :value="s.supplierCode" :label="s.supplierName || s.supplierCode"/></el-select></el-form-item><el-form-item :label='uiText("本次明确可售规格（未选不自动覆盖）")'><PartnerSpecSelect v-model="specIds" :product-id="product.id" :supplier-code="supplierCode || undefined" :aria-label='uiText("本次明确可售规格")'/></el-form-item><el-button type="primary" :loading="busy" @click="confirm">{{ uiText("确认本次上架资料") }}</el-button></el-form><el-alert v-if="error" :title="error" type="error" :closable="false"/><PartnerOperationsPanel kind="listing" :parent-id="product.id"/></section></template>
<style scoped>.listing-panel{display:flex;flex-direction:column;gap:12px;background:#fff;border:1px solid #e5ebf2;border-radius:7px;padding:12px;min-width:0}.listing-panel p{font-size:14px;line-height:1.7}.listing-panel :deep(.el-select){width:100%}</style>
