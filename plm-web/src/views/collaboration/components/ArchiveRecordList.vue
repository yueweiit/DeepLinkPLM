<script setup lang="ts">
import type { UnwrapNestedRefs } from 'vue'
import type { CollaborationRow } from '@/types/collaboration'
import type { useArchiveLedger } from '../composables/useArchiveLedger'
import { archiveLinked,archiveModelName,archivePlatformName,statusNames } from '@/utils/collaboration'
import CollaborationCell from './CollaborationCell.vue'
import ArchiveRowMenu from './ArchiveRowMenu.vue'
defineProps<{ledger:UnwrapNestedRefs<ReturnType<typeof useArchiveLedger>>;height:number}>()
const emit=defineEmits<{action:[command:string,row:CollaborationRow]}>()
</script>
<template>
  <div class="archive-records" role="list" aria-label="协作档案" :style="{height:`${height}px`}">
    <article v-for="row in ledger.rows" :key="row.itemId" class="archive-record" role="listitem">
      <header>
        <button class="record-identity" @click="emit('action',archiveLinked(row)?'phone':'associate',row)"><strong>{{archivePlatformName(row)}}</strong> · {{archiveModelName(row)}}</button>
        <ArchiveRowMenu :row="row" :dirty="ledger.drafts.rows.has(`item:${row.itemId}`)" @action="emit('action',$event,row)"><slot name="row-actions" :row="row" :ledger="ledger" /></ArchiveRowMenu>
      </header>
      <div class="record-meta"><span>原型号编码 {{row.linkedPhoneModelCode || row.phoneModelCode || '—'}}</span><button @click="emit('action','associate',row)">{{archiveLinked(row)?'已关联':'待关联'}}</button><span>{{statusNames[row.status]}}</span><button @click="emit('action','references',row)">项目 {{row.projectCount}}</button></div>
      <div class="record-fields">
        <label v-for="f in ledger.fields" :key="f.key"><span>{{f.key==='brandId'?'手机品牌':f.label}}</span><div class="ledger-cell"><CollaborationCell :field="f" :value="ledger.value(row,f.key)" :editable="ledger.editable(f)" :dirty="ledger.dirty(row,f.key)" :brands="ledger.brands" :brand-name="row.brandName || row.brand" :brand-source="row.brandSource" compact expandable @preview="ledger.preview(row,f,$event)" @edit="ledger.edit(row,f,$event)" @paste="ledger.paste(row,f,$event)" @expand="ledger.openEditor(row,f)" /></div></label>
      </div>
    </article>
    <el-empty v-if="!ledger.rows.length" description="暂无档案" />
  </div>
</template>
<style scoped>
.archive-records{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,470px),1fr));align-content:start;gap:6px;overflow:auto;min-width:0;padding:1px;box-sizing:border-box}
.archive-record{min-width:0;border:1px solid #dce4ee;border-radius:4px;padding:6px;background:#fff}header{display:flex;align-items:flex-start;gap:4px}.record-identity{flex:1;min-width:0;border:0;background:none;text-align:left;font:inherit;color:#3674c1;white-space:normal;overflow-wrap:anywhere;padding:2px 0;line-height:18px}.record-meta{display:flex;flex-wrap:wrap;align-items:center;gap:2px 8px;color:#637387;line-height:20px}.record-meta button{border:0;background:none;color:#3674c1;padding:0;font:inherit;cursor:pointer}.record-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:2px 6px;margin-top:3px}.record-fields label{display:flex;align-items:center;gap:4px;min-width:0}.record-fields label>span{flex:none;width:48px;color:#637387;font-size:12px;line-height:16px}.ledger-cell{flex:1;min-width:0}.record-fields :deep(.plan-cell){height:auto;min-height:28px}.record-fields :deep(.brand-trigger){padding:3px 4px;font-size:12px}.record-fields :deep(.brand-value small){font-size:12px;line-height:14px}.record-fields :deep(.readonly-value){white-space:normal;overflow-wrap:anywhere;text-overflow:clip}
@container (max-width:360px){.record-fields{grid-template-columns:1fr}}
</style>
