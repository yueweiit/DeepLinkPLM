<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, onBeforeUnmount, ref, useId, watch } from "vue";
import { checkResearchDesign, checkResearchStage, type DesignDraftInput, type StageReadinessInput, type StageReadiness } from "@/api/modules/productResearch";
import { PRODUCT_RESEARCH_STAGES } from "@/constants/productResearch";
import { partnerError } from "@/utils/partnerError";

const props = defineProps<{ productId: number; version: number; stage: number; draft: DesignDraftInput | StageReadinessInput; writable: boolean; busy?: boolean }>();
const emit = defineEmits<{ locate: [target: string]; checked: [StageReadiness | null] }>();
const result = ref<StageReadiness | null>(null), checking = ref(false), error = ref("");
const expanded = ref(false), detailsId = `stage-check-details-${useId()}`;
const stageName=computed(()=>PRODUCT_RESEARCH_STAGES[props.stage-1]?.name||'当前步骤');
const destination=computed(()=>props.stage<8?uiText('进入{stage}',{stage:uiText(PRODUCT_RESEARCH_STAGES[props.stage]?.name||'')}):uiText('完成发模'));

const missing = computed(() => result.value?.checks.filter(item => !item.complete&&item.blocking!==false) || []);
const followups = computed(() => result.value?.checks.filter(item => !item.complete&&item.blocking===false) || []);
let timer: ReturnType<typeof setTimeout> | undefined, generation = 0;
async function checkNow(reveal = false) {
  clearTimeout(timer);
  const request = ++generation;
  checking.value = true;
  error.value = "";
  try {
    const checked = await (props.stage===1?checkResearchDesign(props.productId, props.draft as DesignDraftInput):checkResearchStage(props.productId, props.draft as StageReadinessInput));
    if (request === generation) { result.value = checked; if(reveal && !checked.ready)expanded.value=true; emit('checked',checked); }
    return checked;
  } catch (failure) {
    if (request === generation) { error.value = partnerError(failure); result.value = null; emit('checked',null); }
    throw failure;
  } finally {
    if (request === generation) checking.value = false;
  }
}
watch(() => [props.productId, props.version, props.stage, props.draft], () => {
  ++generation;
  checking.value = true;
  clearTimeout(timer);
  timer = setTimeout(() => { void checkNow().catch(() => {}); }, 350);
}, { deep: true, immediate: true });
onBeforeUnmount(() => { ++generation; clearTimeout(timer); });
watch(() => [props.productId, props.stage], () => { expanded.value = false; });
defineExpose({ checkNow });
</script>

<template>
  <section class="stage-readiness" :class="{'is-ready':result?.ready&&!checking&&!error}" :aria-label="uiText('{value0}的缺项',{value0:(destination)})" :aria-busy="checking">
    <div class="check-heading">
      <strong v-if="checking">{{ uiText('正在检查{stage}资料…',{stage:uiText(stageName)}) }}</strong>
      <strong v-else-if="error">{{ uiText('暂时无法检查{stage}资料',{stage:uiText(stageName)}) }}</strong>
      <strong v-else-if="result?.ready" class="complete">{{ uiText('已满足{destination}的条件',{destination}) }}</strong>
      <strong v-else>{{ uiText("待办 ") }}{{ missing.length }}{{ uiText(" 项") }}</strong>
      <span v-if="!checking&&!error&&missing.length" class="check-summary">{{uiText(String((missing[0]?.title) ?? ''))}}<span v-if="missing.length>1" class="check-more-count">{{ uiText(" · 其余 ") }}{{missing.length-1}}{{ uiText(" 项") }}</span></span>
      <el-button v-if="!checking&&!error&&missing.length&&!expanded" link type="primary" :disabled="busy" :aria-label="uiText('{value0}{value1}',{value0:(writable?'补齐':'查看'),value1:(missing[0]?.title)})" @click="emit('locate',missing[0]!.target)">{{writable?uiText('去补齐'):uiText('查看位置')}} →</el-button>
      <el-button link :loading="checking" @click="checkNow().catch(() => {})">{{ uiText("重新检查") }}</el-button>
      <el-button v-if="missing.length>1" link type="primary" :aria-expanded="expanded" :aria-controls="detailsId" @click="expanded = !expanded">{{ expanded ? uiText('收起缺项') : uiText('展开缺项（{value0}）',{value0:(missing.length)}) }}</el-button>
    </div>
    <p v-if="error" class="check-error" role="alert">{{ error }}{{ uiText("，请重试。") }}</p>
    <p v-else-if="missing.length===1&&!checking" class="single-check-message">{{missing[0]?.message}}</p>
    <div v-else-if="missing.length>1" v-show="expanded" :id="detailsId" class="check-details">

    <ul class="check-items">
      <li v-for="item in missing" :key="item.code" :data-check="item.code">
        <div><strong>{{ uiText(String((item.title) ?? '')) }}</strong><span>{{ item.message }}</span></div>
        <el-button link type="primary" :disabled="busy" :aria-label="uiText('{value0}{value1}',{value0:(writable ? '补齐' : '查看'),value1:(item.title)})" @click="emit('locate', item.target)">{{ writable ? uiText('去补齐') : uiText('查看位置') }}</el-button>
      </li>
    </ul>
    </div>

    <ul v-if="!checking&&!error&&followups.length" class="check-followups" :aria-label="uiText('可后补资料')">
      <li v-for="item in followups" :key="item.code" :data-check="item.code"><div><strong>{{uiText(item.title)}}</strong><span>{{uiText('可后补，不影响本步')}}</span><p>{{uiText(item.message)}}</p></div><el-button link type="primary" :disabled="busy" :aria-label="uiText('{value0}{value1}',{value0:uiText(writable?'补充':'查看'),value1:uiText(item.title)})" @click="emit('locate',item.target)">{{writable?uiText('去补充'):uiText('查看位置')}}</el-button></li>
    </ul>
  </section>
</template>

<style scoped>
.stage-readiness{background:var(--el-color-warning-light-9);border-radius:4px;padding:6px 12px;font-size:12px;min-width:0;flex-shrink:0;container-type:inline-size}.check-heading{display:flex;align-items:center;gap:8px;flex-wrap:wrap;min-height:28px}.check-heading>strong{font-size:12px}.check-summary{font-weight:500;min-width:0;overflow-wrap:anywhere}.check-more-count,.single-check-message{color:var(--el-text-color-secondary)}.single-check-message{margin:3px 0 0}.stage-readiness.is-ready{background:var(--el-color-success-light-9)}.check-heading>.el-button{margin-left:0}.check-heading>.el-button:first-of-type{margin-left:auto}.muted,.check-items span{color:var(--el-text-color-secondary)}.complete{color:var(--el-color-success)}.check-details>p{margin:6px 0}.check-items{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px 16px;list-style:none;padding:0 4px 0 0;margin:8px 0 0;}.check-items li{display:flex;gap:8px;align-items:flex-start;min-width:0}.check-items li>div{display:flex;flex:1;flex-direction:column;gap:2px;min-width:0;overflow-wrap:anywhere}.check-items .el-button{flex-shrink:0;min-height:28px;padding:4px 0;font-size:12px}.check-error{color:var(--el-color-danger);margin:6px 0 0}.stage-readiness>p{margin:6px 0 0}
.check-followups{list-style:none;padding:0;margin:6px 0 0}.check-followups li{display:flex;align-items:center;gap:8px;padding-top:6px;border-top:1px solid var(--el-border-color-lighter)}.check-followups li>div{flex:1;min-width:0;overflow-wrap:anywhere}.check-followups strong{font-size:12px;margin-right:8px}.check-followups span,.check-followups p{color:var(--el-text-color-secondary)}.check-followups p{margin:3px 0 0}.check-followups .el-button{flex-shrink:0;font-size:12px}
@container(max-width:650px){.check-items{grid-template-columns:minmax(0,1fr)}}
@container(min-width:1300px){.check-items{grid-template-columns:repeat(3,minmax(0,1fr))}}
</style>
