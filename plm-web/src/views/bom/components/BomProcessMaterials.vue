<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'
import { currentLanguage, t } from '@/locales'
import type { ScopedRecipe, ScopedMaterial } from '@/api/modules/masterData'
import { decimalLabel, materialLabel } from '../catalogPresentation'

const props = defineProps<{recipe:ScopedRecipe}>()
const folded = ref<number[]>([])
const groups = computed(() => {
  const byNode = new Map<number,{id:number;name:string;rows:ScopedMaterial[]}>()
  for (const item of props.recipe.items) {
    const node = byNode.get(item.nodeId)
    if (node) node.rows.push(item)
    else byNode.set(item.nodeId,{id:item.nodeId,name:item.nodeName,rows:[item]})
  }
  return [...byNode.values()]
})
function toggle(id:number) {
  folded.value = folded.value.includes(id) ? folded.value.filter(x=>x!==id) : [...folded.value,id]
}
function unit(item:ScopedMaterial) {
  const [base, translated] = (item.unit || item.unitRaw || '').split(/\s*[：:]\s*/,2)
  return currentLanguage.value === 'es' ? item.unitEs || translated || base : item.unitCn || base
}
</script>
<template>
  <section class="bom-materials" :aria-label="t('工序用料')">
    <header><h3>{{t('工序用料')}}</h3><span>{{t('基准产量')}} <b>{{decimalLabel(recipe.outputQty)}}</b></span></header>
    <table v-if="groups.length">
      <colgroup><col class="node-col"><col><col class="code-col"><col class="qty-col"></colgroup>
      <thead><tr><th scope="col">{{t('工序')}}</th><th scope="col">{{t('物料')}}</th><th scope="col" class="code-col">{{t('物料编码')}}</th><th scope="col" class="qty">{{t('用量')}}</th></tr></thead>
      <tbody v-for="node in groups" :key="node.id">
        <tr v-for="(item,i) in (folded.includes(node.id)?node.rows.slice(0,1):node.rows)" :key="item.id">
          <th v-if="i===0" scope="rowgroup" :rowspan="folded.includes(node.id)?1:node.rows.length">
            <button type="button" :aria-expanded="!folded.includes(node.id)" @click="toggle(node.id)"><el-icon :class="{open:!folded.includes(node.id)}"><ArrowRight/></el-icon>{{materialLabel(node.name)}}</button>
            <small v-if="node.rows.length>1 && !folded.includes(node.id)">{{t('{n} 项',{n:node.rows.length})}}</small>
          </th>
          <template v-if="!folded.includes(node.id)">
            <td>
              <el-popover trigger="click" placement="bottom-start" :width="260">
                <template #reference><button class="material-name" type="button" :title="item.childName">{{materialLabel(item.childName)}}</button></template>
                <strong>{{item.childCode}}</strong><dl class="material-data">
                  <template v-if="item.nodeCode"><dt>{{t('工序编码')}}</dt><dd>{{item.nodeCode}}</dd></template>
                  <template v-if="item.articleSpecific"><dt>{{t('适用范围')}}</dt><dd>{{t('型号专用')}}</dd></template>
                  <template v-if="item.unitRaw"><dt>{{t('原单位')}}</dt><dd>{{item.unitRaw}}</dd></template>
                  <template v-if="item.stockUnit"><dt>{{t('库存单位')}}</dt><dd>{{item.stockUnit}}</dd></template>
                  <template v-if="item.unitProjection"><dt>{{t('MES 核验单位')}}</dt><dd>{{item.mesEffectiveUnit || item.unit}}</dd></template>
                  <template v-if="item.unitPrice!=null"><dt>{{t('原单价')}}</dt><dd>{{decimalLabel(item.unitPrice)}} {{item.currency || t('币种未提供')}}</dd></template>
                </dl>
                <code v-if="item.operationCode">{{item.operationCode}}</code>
              </el-popover>
              <code class="compact">{{item.childCode}}</code>
            </td>
            <td class="code-col"><code>{{item.childCode}}</code></td>
            <td class="qty" :title="item.unitRaw || item.unit || undefined">{{decimalLabel(item.quantity)}} {{unit(item)}}</td>
          </template>
          <template v-else><td>{{t('{n} 项物料',{n:node.rows.length})}}</td><td class="code-col"></td><td></td></template>
        </tr>
      </tbody>
    </table>
    <el-empty v-else :description="t('此品目缺少适用投料')" :image-size="42"/>
  </section>
</template>
<style scoped>
.bom-materials{container-type:inline-size}.bom-materials header{display:flex;justify-content:space-between;align-items:baseline;gap:10px;margin:0 0 10px}.bom-materials h3{margin:0;font-size:15px}.bom-materials header span,small,code{font-size:12px;color:var(--el-text-color-secondary)}header b{color:var(--el-text-color-regular);font-weight:550}
table{border-collapse:collapse;width:100%;table-layout:fixed;border:1px solid var(--el-border-color-light);font-size:13px}th,td{padding:8px 10px;border-bottom:1px solid var(--el-border-color-lighter);text-align:left;overflow-wrap:anywhere}
thead th{font-weight:500;color:var(--el-text-color-secondary);background:var(--el-fill-color-light)}tbody th{background:var(--el-fill-color-lighter);border-right:1px solid var(--el-border-color-light);vertical-align:top}tbody+tbody{border-top:1px solid var(--el-border-color-light)}
.node-col{width:25%}.code-col{width:23%}.qty-col{width:19%}.qty{text-align:right;font-variant-numeric:tabular-nums}button{display:flex;align-items:baseline;gap:6px;padding:0;border:0;font:inherit;color:inherit;background:none;text-align:left;cursor:pointer}.el-icon{font-size:11px}.el-icon.open{transform:rotate(90deg)}small{display:block;font-weight:400;margin-top:3px}.compact{display:none}.material-name:hover,.material-name:focus-visible{color:var(--el-color-primary)}.material-data{display:grid;grid-template-columns:auto 1fr;gap:4px 9px;margin:8px 0}.material-data dt{color:var(--el-text-color-secondary)}.material-data dd{margin:0;overflow-wrap:anywhere}
@container(max-width:520px){.code-col{display:none}.compact{display:block}.node-col{width:31%}.qty-col{width:23%}th,td{padding:7px 5px}}
</style>
