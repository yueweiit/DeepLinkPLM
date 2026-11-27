<script setup lang="ts">
import { computed } from 'vue'
import type { MesOperation, MesRoute } from '@/api/modules/process'
import { t } from '@/locales'
import { operationCategory, operationName, processValue, routeName, routeProductKey, routeProductName } from '../processPresentation'

const props = defineProps<{ operation: MesOperation; routes: MesRoute[] }>()
defineEmits<{ close: []; route: [route: MesRoute] }>()
const requirements = computed(() => [
  ['质检',props.operation.source.requires_qc],['报工',props.operation.source.requires_report],
  ['用料',props.operation.source.requires_material],['绑定设备',props.operation.source.requires_machine]
] as const)
function referenceLabel(route: MesRoute) {
  const name = routeName(route)
  const peers = props.routes.filter(r => routeProductKey(r)===routeProductKey(route) && routeName(r)===name)
  if (peers.length===1) return name
  const duplicateVersion=peers.filter(r=>r.source.version===route.source.version).length>1
  return [name,route.source.version,duplicateVersion && route.sourceCode].filter(Boolean).join(' · ')
}
</script>
<template>
  <el-dialog :model-value="true" :title="operationName(operation)" width="min(460px, 95vw)" append-to-body align-center destroy-on-close @update:model-value="!$event && $emit('close')">
    <template #header><h2>{{operationName(operation)}}</h2><small>{{t('工序定义')}}<template v-if="operation.source.version"> · {{operation.source.version}}</template></small></template>
    <dl class="meta">
      <template v-if="operation.source.execution_mode"><dt>{{t('执行方式')}}</dt><dd>{{processValue(operation.source.execution_mode)}}</dd></template>
      <template v-if="operation.source.requires_scheduling!=null || operation.source.scheduling_mode"><dt>{{t('排程方式')}}</dt><dd>{{processValue(operation.source.requires_scheduling===false ? false : operation.source.scheduling_mode ?? operation.source.requires_scheduling)}}</dd></template>
    </dl>
    <dl class="requirements"><div v-for="[key,value] in requirements" :key="key"><dt>{{t(key)}}</dt><dd><i v-if="value===true" aria-hidden="true">✓ </i>{{processValue(value)}}</dd></div></dl>
    <details v-if="routes.length"><summary>{{t('引用路线')}} ({{routes.length}})</summary><div class="references"><button v-for="route in routes" :key="route.objectId" type="button" @click="$emit('route',route)"><small>{{routeProductName(route)}}</small> {{referenceLabel(route)}}</button></div></details>
    <details><summary>{{t('工序资料')}}</summary><dl class="meta"><dt>{{t('类别')}}</dt><dd>{{operationCategory(operation)}}</dd><dt>{{t('工序编码')}}</dt><dd><code>{{operation.sourceCode}}</code></dd><dt>{{t('MES 状态')}}</dt><dd>{{processValue(operation.source.status)}}</dd></dl></details>
  </el-dialog>
</template>
<style scoped>
h2{margin:0;font-size:18px}small,dt{color:var(--el-text-color-secondary)}dd{margin:0;overflow-wrap:anywhere}dl{margin:0}.meta{display:grid;grid-template-columns:auto minmax(0,1fr);gap:6px 16px}
.requirements{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));margin:14px 0}.requirements div{display:flex;justify-content:space-between;align-items:center;gap:6px;border-block-start:1px solid var(--el-border-color-light);padding:9px 0}.requirements div:nth-child(odd){padding-inline-end:10px}.requirements div:nth-child(even){border-inline-start:1px solid var(--el-border-color-light);padding-inline-start:10px}.requirements dd{white-space:nowrap}i{font-style:normal;color:var(--el-color-success)}
details{border-top:1px solid var(--el-border-color-light);padding-block:6px}summary{cursor:pointer;width:fit-content}details dl,.references{margin-block:8px}.references{display:grid;gap:3px}button{padding:4px 0;background:transparent;border:0;color:var(--el-color-primary);font:inherit;text-align:start;cursor:pointer}.references small{margin-inline-end:6px}code{overflow-wrap:anywhere}
@media(pointer:coarse){button,summary{min-height:44px}}
</style>
