<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useUserStore } from '@/stores/user'
import { getReferenceOptions, referenceKey, REFERENCE_TYPE_LABELS, type ReferenceOption } from '@/api/modules/attachment'
import ReferenceObjectSelect from './ReferenceObjectSelect.vue'
const props = defineProps<{ modelValue: ReferenceOption[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [ReferenceOption[]] }>()
const user = useUserStore()
const recommended = ref<ReferenceOption[]>([]), chosenRecommendations = ref<string[]>([])
function remove(key: string) { if (props.disabled) return; emit('update:modelValue', props.modelValue.filter(r => referenceKey(r) !== key)) }
function part(ref: ReferenceOption, scope: 'ALL' | 'PC' | 'TPU') {
  if (props.disabled) return
  const updated = props.modelValue.map(r => referenceKey(r) === referenceKey(ref) ? { ...r, partType: scope } : r)
  emit('update:modelValue', updated.filter((r, index) => updated.findIndex(v => referenceKey(v) === referenceKey(r)) === index))
}
let recommendationGeneration = 0
watch(() => props.modelValue.find(r => r.type === 'product')?.id, async id => {
  const ticket = ++recommendationGeneration, token = user.token; recommended.value = []; chosenRecommendations.value = []
  if (!id) return
  try { const found = await getReferenceOptions(undefined, '', id); if (ticket === recommendationGeneration && token === user.token) recommended.value = found.filter(r => !props.modelValue.some(v => referenceKey(v) === referenceKey(r))) }
  catch { /* Recommendations are optional; required selectors retain their own errors. */ }
})
function addRecommendations() {
  if (props.disabled) return
  const additions = recommended.value.filter(r => chosenRecommendations.value.includes(referenceKey(r)))
  emit('update:modelValue', [...props.modelValue, ...additions].filter((r, i, all) => all.findIndex(v => referenceKey(v) === referenceKey(r)) === i)); chosenRecommendations.value = []
}
watch(() => user.token, () => { recommendationGeneration++; recommended.value = []; chosenRecommendations.value = [] })
onBeforeUnmount(() => { recommendationGeneration++ })
</script>
<template>
  <div class="reference-picker">
    <div class="reference-field"><span>关联产品、型号、SKU 或模具</span><ReferenceObjectSelect :model-value="modelValue" :disabled="disabled" aria-label="选择文件关联对象" @update:model-value="emit('update:modelValue', $event)" /></div>
    <div class="selected-references" v-if="modelValue.length">
      <div v-for="r in modelValue" :key="referenceKey(r)" class="selected-reference"><div><span class="kind">{{ REFERENCE_TYPE_LABELS[r.type] }}</span> {{ r.name }} · {{ r.code }}<small>{{ r.subtitle }}</small></div><el-select popper-class="document-reference-options" v-if="r.type === 'mold_set'" :model-value="r.partType" :disabled="disabled" aria-label="实物部件范围" @update:model-value="(scope: 'ALL'|'PC'|'TPU') => part(r, scope)"><el-option label="整套" value="ALL"/><el-option label="PC" value="PC"/><el-option label="TPU" value="TPU"/></el-select><el-button link :disabled="disabled" @click="remove(referenceKey(r))" :aria-label="`移除${r.name}`">移除</el-button></div>
    </div>
    <details v-if="recommended.length" class="recommendations"><summary>可关联的相关对象（{{ recommended.length }}）</summary><el-checkbox-group v-model="chosenRecommendations"><el-checkbox v-for="r in recommended" :key="referenceKey(r)" :value="referenceKey(r)">{{ REFERENCE_TYPE_LABELS[r.type] }} · {{ r.name }} · {{ r.code }}</el-checkbox></el-checkbox-group><el-button size="small" :disabled="disabled||!chosenRecommendations.length" @click="addRecommendations">添加选中的推荐对象</el-button></details>
    <p class="reference-hint">至少选择一个对象；一份文件可关联多个产品、SKU 或模具。实物套号可指定整套、PC 或 TPU。</p>
  </div>
</template>
<style scoped>
.reference-picker{display:flex;flex-direction:column;gap:12px;min-width:0;font-size:12px}.reference-field{display:flex;flex-direction:column;gap:6px}.reference-field>span{font-weight:600;color:#4d6078}.reference-field>.el-select{width:100%}.selected-references{border:1px solid #e3e9f0;border-radius:4px;padding:0 8px}.selected-reference{display:flex;align-items:center;gap:8px;border-bottom:1px solid #edf0f5;padding:6px 0}.selected-reference:last-child{border-bottom:0}.selected-reference>div{flex:1;min-width:0;overflow-wrap:anywhere}.selected-reference small{display:block;color:#7d8b9b;font-size:12px;line-height:16px}.selected-reference>.el-select{width:86px;flex:none}.kind{color:#728298}.reference-hint{margin:0;font-size:12px;color:#718197;line-height:18px}.recommendations{padding:8px;background:#f7faff;line-height:20px}.recommendations summary{cursor:pointer}.recommendations :deep(.el-checkbox){display:flex;height:auto;min-height:28px;white-space:normal}.recommendations :deep(.el-checkbox__label){white-space:normal;overflow-wrap:anywhere}.reference-picker :deep(.el-input__wrapper),.reference-picker :deep(.el-select__wrapper){min-height:28px;font-size:12px}
</style>

<style>
.document-reference-options .el-select-dropdown__item{height:auto;min-height:34px;white-space:normal;line-height:18px;padding-top:6px;padding-bottom:6px;overflow-wrap:anywhere}.document-reference-options .el-select-dropdown__item small{font-size:12px;color:#7b8c9f;line-height:16px}.document-reference-options .el-select-dropdown__wrap{max-height:300px}
</style>
