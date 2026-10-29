<script setup lang="ts">
import { computed } from 'vue'
import type { BusinessData } from '@/types/partner'
import OperationEvidence from './OperationEvidence.vue'
import { operationLabel } from './presentation'
const props = defineProps<{ record: BusinessData }>()
const result = computed(() => props.record.resultSnapshot || {})
</script>
<template><section class="inspection-result"><h3>{{ record.name }}</h3><p>{{ record.code }} · {{ result.factory }} · {{ result.date }}</p><p><strong>{{ operationLabel(result.result) }}</strong> · 检验人 {{ result.inspector }}（自行确认，无另行审核）</p><h4>本次明确适用范围</h4><ul><li v-for="scope in result.scope || []" :key="scope.catalogSpecKey||scope.specId">{{ scope.specName }} · 实际检查 {{ scope.checkedQty }} 件<template v-if="scope.releaseQty !== undefined"> · 本次可放行 {{ scope.releaseQty }} / 覆盖 {{scope.coveredQty??scope.qty}} 件（原批 {{scope.qty}} 件）</template></li></ul><p class="hint">未列型号、规格或批次不自动算通过；工厂签字不替代合格结论。</p><h4>实际检查内容与结果</h4><p class="text">{{ result.checks }}</p><h4>完整问题清单</h4><p class="text">{{ result.issues || '本次未记录问题' }}</p><template v-if="result.rectification"><h4>整改／换样与本次复检说明</h4><p class="text">{{ result.rectification }}</p></template><p v-if="result.isolation">合格部分隔离依据：{{ result.isolation }}</p><p v-if="result.qualityEvidence">对照样品／质量依据：{{ result.qualityEvidence }}</p><OperationEvidence :references="result.refs || []"/><p v-if="record.factorySignature">工厂代表 {{ record.factorySignature.name }} · {{ record.factorySignature.position }} · {{ record.factorySignature.signedAt }}</p></section></template>
<style scoped>.inspection-result{min-width:0;overflow-wrap:anywhere}h3{font-size:16px;margin:0 0 8px}h4{font-size:14px;margin:14px 0 5px}p,li{font-size:14px;line-height:1.7;margin:5px 0}.text{white-space:pre-wrap}.hint{font-size:13px;color:#708297}.pictures{display:flex;gap:8px;flex-wrap:wrap}</style>
