<script setup lang="ts">
import { ref } from 'vue'
import type { CollaborationRow } from '@/types/collaboration'
import ArchiveLedger from './components/ArchiveLedger.vue'
import MarketArchiveActions from './components/MarketArchiveActions.vue'
defineOptions({name:'MarketArchivePanel'})
const actions=ref<InstanceType<typeof MarketArchiveActions>>()
const keys=['brandId','phonePrice','priceCurrency','salesPlatform','referenceUrl','notes']
function act(command:string,row:CollaborationRow){actions.value?.act(command,row)}
</script>
<template><ArchiveLedger kind="market" title="市场资料维护" :field-keys="keys" @action="act"><template #tools="{ledger}"><MarketArchiveActions ref="actions" :ledger="ledger" /></template><template #row-actions="{row,ledger}"><el-dropdown-item v-if="ledger.options?.writableGroups.includes('market')" command="associate">关联基础资料 / 查看原始名称</el-dropdown-item><el-dropdown-item v-if="ledger.options?.writableGroups.includes('market')" command="links">关联已有项目</el-dropdown-item><el-dropdown-item v-if="!row.projectCount && ledger.options?.writableGroups.includes('market')" command="project">沿用现有立项</el-dropdown-item><el-dropdown-item v-if="ledger.options?.writableGroups.includes('engineering')" command="mold">新增模具套</el-dropdown-item></template></ArchiveLedger></template>
