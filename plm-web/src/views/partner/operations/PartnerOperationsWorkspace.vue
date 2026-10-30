<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageContainer from '@/components/PageContainer/index.vue'
import PartnerOperationsPanel from './PartnerOperationsPanel.vue'
import PartnerOperationDetail from './PartnerOperationDetail.vue'
import { operationTitles } from './presentation'
import type { OperationKind } from '@/api/modules/partnerOperations'
const route = useRoute(), router = useRouter()
const kind = computed(() => String(route.params.operationKind || 'fulfilment') as OperationKind)
const valid = computed(() => Object.prototype.hasOwnProperty.call(operationTitles, kind.value))
const id = computed(() => Number(route.params.operationId || 0))
function back() { const from = String(route.query.from || ''); void router.push(from.startsWith('/') && !from.startsWith('//') ? from : '/partner-fulfilments') }
</script>
<template><PageContainer :title="valid ? operationTitles[kind] : uiText('记录类型无效')" class="operations-workspace"><template #actions><el-button v-if="id || route.query.from" @click="back">{{ uiText("返回来源") }}</el-button><el-button v-if="!id" @click="router.push('/partner-sales')">{{ uiText("历史销售记录") }}</el-button></template><PartnerOperationDetail v-if="valid && id" :key="`${kind}-${id}`" :kind="kind" :id="id"/><PartnerOperationsPanel v-else-if="valid" kind="fulfilment"/></PageContainer></template>
<style scoped>.operations-workspace{height:100%;min-height:0;min-width:0;display:flex;flex-direction:column;gap:8px}.operations-workspace :deep(>.page-panel){padding:10px 14px;flex:none}.operations-workspace :deep(>.operations-panel){flex:1;min-height:0}.operations-workspace :deep(.operations-panel>.records){flex:1;max-height:none}.operations-workspace :deep(.page-panel-header){gap:8px;flex-wrap:wrap;flex-direction:row;align-items:center}.operations-workspace :deep(.toolbar-actions){margin-top:0;min-width:0}.operations-workspace :deep(.page-panel-title){font-size:17px;margin:0}.operations-workspace :deep(.page-panel-header h2){font-size:17px;overflow-wrap:anywhere}</style>
