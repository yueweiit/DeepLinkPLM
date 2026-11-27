<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useElementSize, useResizeObserver } from '@vueuse/core'
import { Box, Close, Operation, Printer } from '@element-plus/icons-vue'
import type { ProcessGraph, MesOperation } from '@/api/modules/process'
import type { RouteEdge, RouteNode } from '@/api/modules/masterData'
import { currentLanguage, t } from '@/locales'
import { routeTopology, hasCondition } from '../processLayout'
import { graphNodeName, maintenanceParameters, processValue } from '../processPresentation'

const props = defineProps<{ graph: ProcessGraph; operations?: MesOperation[]; maintenance?: boolean }>()
const emit = defineEmits<{ definition: [operation: MesOperation] }>()
const area = ref<HTMLElement>(), board = ref<HTMLElement>(), sheet = ref<HTMLElement>()
const selectedId = ref<number>()
const { width } = useElementSize(area)
const topology = computed(() => routeTopology(props.graph))
const vertical = computed(() => width.value > 0 && width.value <= 590)
const draw = computed(() => topology.value.diagram && (!vertical.value || topology.value.lanes <= 2))
const chosen = computed(() => topology.value.byId.get(selectedId.value!))
const byId = computed(() => new Map(props.operations?.map(op => [op.objectId,op])))
const byCode = computed(() => new Map(props.operations?.map(op => [op.sourceCode,op])))
function definition(node: RouteNode) {
  return node.operationId == null ? byCode.value.get(node.operationCode) : byId.value.get(node.operationId)
}
function nodeLabel(node: RouteNode) { return graphNodeName(node,definition(node)) }
function label(id: number) {
  const node = topology.value.byId.get(id)
  return node ? nodeLabel(node) : '—'
}
function edgeText(edge: RouteEdge) {
  return [edge.relationType === 'finish_to_start' ? '' : processValue(edge.relationType),edge.label,hasCondition(edge) ? t('有条件') : ''].filter(Boolean).join(' · ')
}
const grid = computed(() => {
  const {stages,lanes} = topology.value
  return {
    gridTemplateColumns:'repeat(' + (vertical.value ? 2 * lanes : stages) + ',minmax(0,1fr))',
    gridTemplateRows:'repeat(' + (vertical.value ? stages : 2 * lanes - 1) + ',minmax(' + (vertical.value ? 44 : 16) + 'px,auto))'
  }
})
function place(node: RouteNode) {
  const slot = topology.value.slots.get(node.id)!
  return {gridColumn:vertical.value ? slot.lane + ' / span 2' : String(slot.stage),gridRow:String(vertical.value ? slot.stage : slot.lane)}
}
const selectedOp = computed(() => chosen.value && definition(chosen.value))
const params = computed(() => {
  const n = chosen.value
  if (!n) return []
  const c = n.config || {}
  const fields: Array<[string,unknown]> = [
    ['产出类型',c.outputType ?? c.output_type],['产出基码',c.outputBaseCode ?? c.output_base_code],
    ['用料',c.needMaterialIssue],['排程',c.schedulable],['绑定设备',c.needEquipment],
    ['节点编码',n.nodeCode],['本地节点',n.localNodeCode],['工序编码',selectedOp.value?.sourceCode || n.operationCode]
  ]
  const compact = fields.filter(([,value]) => value != null && value !== '').map(([key,value]) => [t(key),processValue(value)])
  return props.maintenance ? compact.concat(maintenanceParameters(c)) : compact
})
const links = ref<Array<{id:number;path:string;tip:string;active:boolean}>>([])
function measure() {
  if (!board.value || !draw.value) { links.value = []; return }
  const rect = board.value.getBoundingClientRect()
  const bounds = new Map(Array.from(board.value.querySelectorAll<HTMLElement>('[data-step]'),el => [Number(el.dataset.step),el.getBoundingClientRect()]))
  links.value = props.graph.edges.flatMap(edge => {
    const a = bounds.get(edge.fromNodeId), b = bounds.get(edge.toNodeId)
    if (!a || !b) return []
    let path: string, tip: string
    if (vertical.value) {
      const x = b.left+b.width/2-rect.left, y = b.top-rect.top-2
      path = 'M '+(a.left+a.width/2-rect.left)+' '+(a.bottom-rect.top)+' V '+(y-10)+' H '+x+' V '+y
      tip = 'M '+(x-3)+' '+(y-5)+' L '+x+' '+y+' l 3 -5'
    } else {
      const x = b.left-rect.left-2, y = b.top+b.height/2-rect.top
      path = 'M '+(a.right-rect.left)+' '+(a.top+a.height/2-rect.top)+' H '+(x-13)+' V '+y+' H '+x
      tip = 'M '+(x-5)+' '+(y-3)+' L '+x+' '+y+' l -5 3'
    }
    return [{id:edge.id,path,tip,active:edge.toNodeId===selectedId.value || edge.fromNodeId===selectedId.value}]
  }).sort((a,b) => Number(a.active)-Number(b.active))
}
useResizeObserver(board,measure)
watch([topology,draw,width,currentLanguage,selectedId],measure,{flush:'post',immediate:true})
watch(() => props.graph,() => { selectedId.value = undefined })
async function selectNode(id: number) {
  selectedId.value = id
  await nextTick()
  const head = sheet.value?.querySelector<HTMLElement>('header')
  head?.querySelector('button')?.focus({preventScroll:true})
  head?.scrollIntoView?.({block:'nearest',inline:'nearest'})
}
async function closeNode() {
  const id = selectedId.value
  selectedId.value = undefined
  await nextTick()
  area.value?.querySelector<HTMLElement>('[data-step="'+id+'"]')?.focus()
}
</script>

<template>
  <section ref="area" class="process-graph" :aria-label="t('工序关系')">
    <h3>{{t('工序关系')}}</h3>
    <div v-if="draw" ref="board" class="flow" :class="{vertical}" :style="grid">
      <svg aria-hidden="true"><g v-for="line in links" :key="line.id" :class="{active:line.active}"><path :d="line.path"/><path :d="line.tip"/></g></svg>
      <button v-for="node in topology.nodes" :key="node.id" type="button" class="step" :style="place(node)" :data-step="node.id" :aria-pressed="selectedId===node.id" @click="selectNode(node.id)">
        <el-icon aria-hidden="true"><Box v-if="definition(node)?.source.category==='组装包装'"/><Printer v-else-if="definition(node)?.source.category==='打印'"/><Operation v-else/></el-icon>{{nodeLabel(node)}}
      </button>
    </div>
    <table v-else-if="topology.nodes.length" class="deps">
      <thead><tr><th scope="col">{{t('工序')}}</th><th scope="col">{{t('前置工序')}}</th></tr></thead>
      <tbody><tr v-for="node in topology.nodes" :key="node.id">
        <td><button type="button" class="link" :data-step="node.id" :aria-pressed="selectedId===node.id" @click="selectNode(node.id)">{{nodeLabel(node)}}</button></td>
        <td><span v-for="e in topology.previous.get(node.id)" :key="e.id" class="dependency">{{label(e.fromNodeId)}}<small v-if="edgeText(e)"> · {{edgeText(e)}}</small></span><span v-if="!topology.previous.get(node.id)?.length">{{t('起始工序')}}</span></td>
      </tr></tbody>
    </table>
    <el-empty v-else :description="t('暂无工序')" :image-size="42"/>
    <section v-if="chosen" ref="sheet" class="step-sheet" :aria-label="nodeLabel(chosen)">
      <header><h3>{{nodeLabel(chosen)}}</h3><div><button v-if="selectedOp" type="button" class="link" @click="emit('definition',selectedOp)">{{t('工序定义')}}</button>
        <el-button circle size="small" :icon="Close" :aria-label="t('关闭节点详情')" @click="closeNode"/></div></header>
      <dl>
        <template v-if="topology.previous.get(chosen.id)?.length"><dt>{{t('前置工序')}}</dt><dd><button v-for="e in topology.previous.get(chosen.id)" :key="e.id" class="link" type="button" @click="selectNode(e.fromNodeId)">{{label(e.fromNodeId)}}<small v-if="edgeText(e)"> · {{edgeText(e)}}</small></button></dd></template>
        <template v-if="topology.next.get(chosen.id)?.length"><dt>{{t('后续工序')}}</dt><dd><button v-for="e in topology.next.get(chosen.id)" :key="e.id" class="link" type="button" @click="selectNode(e.toNodeId)">{{label(e.toNodeId)}}<small v-if="edgeText(e)"> · {{edgeText(e)}}</small></button></dd></template>
        <template v-if="chosen.config?.equipmentType"><dt>{{t('设备')}}</dt><dd>{{processValue(chosen.config.equipmentType)}}</dd></template>
      </dl>
      <details v-if="params.length" :key="chosen.id"><summary>{{t('节点参数')}}</summary><dl><template v-for="([key,value],i) in params" :key="i"><dt>{{key}}</dt><dd>{{value}}</dd></template></dl></details>
    </section>
  </section>
</template>
<style scoped>
.process-graph{min-width:0;container-type:inline-size;color:var(--el-text-color-primary)}
h3{font-size:14px;margin:0;font-weight:600}.flow{position:relative;display:grid;align-items:center;column-gap:clamp(22px,5cqw,62px);margin-top:12px}
.flow.vertical{column-gap:5px;row-gap:22px}.flow>svg{position:absolute;inset:0;width:100%;height:100%;overflow:visible;pointer-events:none}
.flow g{fill:none;stroke:var(--el-text-color-placeholder);stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}.flow g.active{stroke:var(--el-color-primary)}
.step{position:relative;display:flex;align-items:center;gap:7px;min-width:0;min-height:44px;padding:8px 12px;border:1px solid var(--el-border-color);border-radius:5px;background:var(--el-bg-color);font:inherit;font-size:13px;text-align:start;color:var(--el-text-color-regular);overflow-wrap:anywhere;cursor:pointer}
.step:is(:hover,:focus-visible,[aria-pressed=true]){color:var(--el-color-primary);background:var(--el-color-primary-light-9);border-color:var(--el-color-primary)}
.link{background:none;border:0;padding:2px 0;color:var(--el-color-primary);font:inherit;text-align:start;overflow-wrap:anywhere;cursor:pointer}.link:hover,.link[aria-pressed=true]{text-decoration:underline}
.step-sheet{background:var(--el-fill-color-light);margin-top:14px;border-radius:4px;padding:12px}.step-sheet header,.step-sheet header>div{display:flex;align-items:center;gap:12px}.step-sheet header{justify-content:space-between}.step-sheet header>div{flex:none}
dl{display:grid;grid-template-columns:auto minmax(0,1fr);gap:5px 14px;font-size:13px;margin:8px 0}dd{margin:0;overflow-wrap:anywhere}dd button{margin-inline-end:12px}dt,small{color:var(--el-text-color-secondary)}
summary{width:fit-content;cursor:pointer;font-size:13px}.deps{margin-top:10px;border-collapse:collapse;width:100%;table-layout:fixed}.deps th,.deps td{padding:8px;text-align:start;border:1px solid var(--el-border-color-light);overflow-wrap:anywhere}.deps th{background:var(--el-fill-color-light);font-size:12px}.dependency{display:block}
@media(pointer:coarse){.link,summary,.step-sheet .el-button{min-height:44px}.step-sheet .el-button{min-width:44px}}
</style>
