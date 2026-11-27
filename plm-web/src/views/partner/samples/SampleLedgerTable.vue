<script setup lang="ts">
import {computed,ref} from 'vue'
import {t} from '@/locales'
import {productColorSwatches as colors} from '@/utils/productColorSwatches'
import type {SampleIdentity,SampleLocationSite} from '@/types/sampleManagement'
import PartnerPhoto from '../PartnerPhoto.vue'

const props=defineProps<{
  rows: SampleIdentity[]
  selected: Map<number,SampleIdentity>
  busy: boolean
  localOnly: boolean
  site?: SampleLocationSite
}>()
const emit=defineEmits<{
  open: [sample:SampleIdentity]
  check: [sample:SampleIdentity,checked:boolean]
  checkPage: [checked:boolean]
}>()

const checkedCount=computed(()=>props.rows.filter(s=>props.selected.has(s.sampleIdentityId)).length)
const scrollArea=ref<HTMLElement>()
defineExpose({scrollToTop(){if(scrollArea.value)scrollArea.value.scrollTop=0}})
const showChina=computed(()=>!props.localOnly&&props.site!=='MX')
const showMexico=computed(()=>props.localOnly||props.site!=='CN')
const groups=computed(()=>{
  const result=new Map<string,{key:string;name:string;phone:string;supplierName?:string;supplierProfileShortName?:string;photo?:SampleIdentity['photo'];rows:SampleIdentity[]}>()
  for(const sample of props.rows){
    const name=sample.productName || sample.name
    const phone=sample.phoneName || sample.specName || ''
    // Only share a printed caption. Quantities, checkboxes and actions always belong to one identity.
    const styleKey=sample.groupKey?.startsWith('identity:')?undefined:sample.groupKey
    const key=JSON.stringify([sample.domain,styleKey,sample.supplierCode || sample.supplierName,name,phone,sample.phoneModelId])
    const group=result.get(key)
    if(group){
      group.rows.push(sample)
      group.photo ??= sample.photo
      group.supplierName ||= sample.supplierName
      group.supplierProfileShortName ||= sample.supplierProfileShortName
    }else result.set(key,{key,name,phone,supplierName:sample.supplierName,supplierProfileShortName:sample.supplierProfileShortName,photo:sample.photo,rows:[sample]})
  }
  return [...result.values()]
})
function inStock(sample:SampleIdentity,site:'CN'|'MX'){
  const positions=sample.positions ?? sample.balances.positions
  if(!positions?.length || positions.some(p=>!p.site && p.onhand>0))return sample.balances.onhand===0?'0':'—'
  return positions.reduce((sum,p)=>sum+(p.site===site?p.onhand:0),0).toLocaleString()
}

</script>
<template>
  <div ref="scrollArea" class="ledger-scroll">
    <table class="ledger-table" :aria-label="t('样品台账')">
      <thead><tr>
        <th class="pick"><el-checkbox :model-value="rows.length>0&&checkedCount===rows.length" :indeterminate="checkedCount>0&&checkedCount<rows.length" :disabled="busy||!rows.length" :aria-label="t('全选本页样品')" @change="emit('checkPage',Boolean($event))"/><span class="narrow">{{t('全选本页样品')}}</span></th>
        <th>{{t('款式 / 型号')}}</th><th>{{t('供应商')}}</th><th>{{t('颜色')}}</th><th>{{t('样品码')}}</th>
        <th v-if="showChina" class="num">{{t('中国在库')}}</th><th v-if="showMexico" class="num">{{t(localOnly?'当地数量':'墨西哥在库')}}</th><th class="end"></th>
      </tr></thead>
      <tbody v-for="group in groups" :key="group.key">
        <tr v-for="(sample,index) in group.rows" :key="sample.sampleIdentityId" class="identity-row" :class="{picked:selected.has(sample.sampleIdentityId)}" @click="emit('open',sample)">
          <td class="pick" @click.stop><el-checkbox :model-value="selected.has(sample.sampleIdentityId)" :disabled="busy" :aria-label="t('选择')+sample.sampleCode" @change="emit('check',sample,Boolean($event))"/></td>
          <td v-if="index===0" class="style-cell" :rowspan="group.rows.length" @click.stop>
            <div class="style-caption"><PartnerPhoto v-if="group.photo" :id="group.photo.id" :sample-identity-id="group.photo.sampleIdentityId" lazy previewable/><div><strong>{{group.name}}</strong><small v-if="group.phone">{{group.phone}}</small></div></div>
          </td>
          <td v-if="index===0" class="supplier-cell" :rowspan="group.rows.length" :title="group.supplierName" @click.stop><span class="narrow">{{t('供应商')}} · </span><span class="supplier-label">{{group.supplierProfileShortName||group.supplierName||sample.supplierCode||t('待核对')}}</span></td>
          <td class="color"><span v-if="sample.colorName&&colors[sample.colorName]" class="swatch" :style="{backgroundColor:colors[sample.colorName]}" aria-hidden="true"></span>{{sample.colorName || '—'}}<small v-if="sample.sampleVersion">{{sample.sampleVersion}}</small></td>
          <td class="code"><button type="button" class="code-link" @click.stop="emit('open',sample)">{{sample.sampleCode}}</button></td>
          <td v-if="showChina" class="num cn"><span class="narrow">{{t('中国')}} </span>{{inStock(sample,'CN')}}</td>
          <td v-if="showMexico" class="num mx"><span class="narrow">{{t(localOnly?'在库':'墨西哥')}} </span>{{localOnly?sample.balances.onhand.toLocaleString():inStock(sample,'MX')}}</td>
          <td class="end"><button type="button" class="detail-link" :aria-label="t('详情')+' '+sample.sampleCode" @click.stop="emit('open',sample)">›</button></td>
        </tr>
      </tbody>
    </table>
    <el-empty v-if="!busy&&!rows.length" :description="t('暂无匹配样品')"/>
  </div>
</template>
<style scoped>
.ledger-scroll{min-height:0;flex:1;overflow:auto;scrollbar-gutter:stable}
.ledger-table{width:100%;border-collapse:separate;border-spacing:0;text-align:left;color:#293d55;font-size:13px}
th,td{padding:8px 12px;border-bottom:1px solid #e8edf3}
th{position:sticky;top:0;z-index:1;background:#f5f7fa;font-size:12px;font-weight:600;color:#52657b;white-space:nowrap}
td{height:55px;background:white}
.pick{width:40px;padding-inline:12px 3px}.style-cell{width:29%;min-width:140px}.supplier-cell{width:clamp(84px,8vw,128px)}.supplier-label{display:inline-block;max-width:clamp(84px,8vw,128px);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;vertical-align:bottom}.style-caption{display:flex;align-items:center;gap:10px;overflow-wrap:anywhere}
.style-caption :deep(.partner-photo){width:34px;height:34px}.style-caption strong{font-size:14px;font-weight:600}
small{display:block;margin-top:2px;color:#62758b;font-size:12px}.swatch{display:inline-block;width:10px;height:10px;border-radius:50%;box-shadow:inset 0 0 0 1px #78849460;margin-right:6px;vertical-align:baseline}
.code-link,.detail-link{background:none;border:0;cursor:pointer}.code-link{font:13px/1.4 ui-monospace,Menlo,Consolas,monospace;text-align:left;color:#2064b5;padding:7px 0;overflow-wrap:anywhere}.code-link:hover{text-decoration:underline}
.num{text-align:right;font-variant-numeric:tabular-nums}.end{width:34px;padding-inline:2px 10px}.detail-link{color:#5b7189;font-size:20px;min-width:26px}
.identity-row:hover td:not([rowspan]){background:#f8fafd}.identity-row.picked td:not([rowspan]){background:#edf4ff}.narrow{display:none}
.identity-row td:not([rowspan],.pick){cursor:pointer}
@container (width > 660px) {
  th:not(:last-child), td:not(:last-child) {
    border-inline-end: 1px solid var(--plm-color-border);
  }
}
@container(max-width:660px){
  .ledger-table,.ledger-table thead,.ledger-table tbody{display:block;width:100%}
  thead tr{display:block}thead th{display:none}thead th.pick{display:flex;position:static;width:100%;align-items:center;gap:7px;padding:3px 10px}
  .identity-row{display:grid;grid-template-columns:24px minmax(0,1fr) auto 25px;align-items:center;border-bottom:1px solid #e5ebf1;padding:0 9px}
  td{display:block;border:0;height:auto;padding:3px 2px;background:transparent}
  .style-cell,.supplier-cell{grid-column:1/-1;width:auto;min-width:0;margin:0 -9px;background:#f5f7fa}
  .style-cell{grid-row:1;padding:8px 10px 3px}.supplier-cell{grid-row:2;padding:0 10px 6px;color:#586c81;font-size:12px}
  .style-caption :deep(.partner-photo){width:28px;height:28px}.style-caption strong{font-size:13px}
  .identity-row .pick{grid-column:1;grid-row:3/5;width:auto;padding:0}
  .color{grid-column:2;grid-row:3;min-width:0}.code{grid-column:2;grid-row:4}.code-link{padding:2px 0 5px}
  .num{grid-column:3;font-size:12px}.cn{grid-row:3}.mx{grid-row:4}.end{grid-column:4;grid-row:3/5;width:auto;padding:0}.narrow{display:inline}
  .identity-row.picked{background:#edf4ff}.identity-row td:not([rowspan]){background:transparent}
}
</style>
