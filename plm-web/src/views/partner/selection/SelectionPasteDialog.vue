<script setup lang="ts">
import {computed,nextTick,onMounted,ref} from 'vue'
import {useResizeObserver} from '@vueuse/core'
import {t as uiText} from '@/locales'
import type {SelectionRow} from '@/types/partnerSelections'
import {fillableColumns,frozenSelectionRow,parseSelectionTsv,rectangularChanges} from '@/utils/partnerSelections'

const props=defineProps<{rows:SelectionRow[];busy:boolean;saveError:string;fieldLabel:(field:string)=>string;canEdit:(row:SelectionRow)=>boolean}>()
const emit=defineEmits<{close:[];apply:[changes:{rowId:string;changes:Partial<SelectionRow>}[]]}>()
const original=props.rows.map(row=>fillableColumns.map(key=>String(row[key]??'')))
const cells=ref(original.map(row=>[...row])),activeRow=ref(Math.max(0,props.rows.findIndex(row=>!locked(row)))),activeColumn=ref(0)
const grid=ref<HTMLElement>(),error=ref(''),reviewed=ref<{rowId:string;changes:Partial<SelectionRow>}[]>([])
const differences=computed(()=>cells.value.map((row,index)=>row.map((value,column)=>value!==original[index][column]?value:'')))
const pendingCount=computed(()=>differences.value.filter(row=>row.some(value=>value.trim())).length)
const hasEdits=computed(()=>cells.value.some((row,index)=>row.some((value,column)=>value!==original[index][column])))
function locked(row:SelectionRow){return !props.canEdit(row)||row.discarded||frozenSelectionRow(row)}
function resizeCells(){void nextTick(()=>grid.value?.querySelectorAll<HTMLTextAreaElement>('textarea').forEach(cell=>{cell.style.height='0px';cell.style.height=Math.max(66,cell.scrollHeight)+'px'}))}
function invalidate(){reviewed.value=[];error.value='';resizeCells()}
function focusCell(){void nextTick(()=>grid.value?.querySelector<HTMLTextAreaElement>(`[data-row="${activeRow.value}"][data-column="${activeColumn.value}"]`)?.focus())}
function updateCell(row:number,column:number,event:Event){cells.value[row][column]=(event.target as HTMLTextAreaElement).value;invalidate()}
function paste(event:ClipboardEvent,row:number,column:number){
 event.preventDefault();invalidate()
 try{
  const matrix=parseSelectionTsv(event.clipboardData?.getData('text/plain')||'','\t',true)
  if(!matrix.some(values=>values.some(value=>value.trim())))return
  if(row+matrix.length>props.rows.length)throw new Error('粘贴超出本页已有行，请缩小范围；新增款式请从供应商产品目录选择。')
  rectangularChanges(matrix,fillableColumns[column])
  if(matrix.some((values,index)=>locked(props.rows[row+index])&&values.some(value=>value.trim())))throw new Error('目标中包含不可修改的行，本次粘贴未应用。')
  matrix.forEach((values,index)=>values.forEach((value,offset)=>{if(value.trim())cells.value[row+index][column+offset]=value}))
  activeRow.value=row;activeColumn.value=column
 }catch(e){error.value=e instanceof Error?e.message:String(e)}
}
function check(){
 invalidate()
 try{
  const changes=rectangularChanges(differences.value,'name')
  if(changes.some((value,index)=>Object.keys(value).length&&locked(props.rows[index])))throw new Error('目标中包含不可修改的行，本次内容未应用。')
  reviewed.value=changes.flatMap((value,index)=>Object.keys(value).length?[{rowId:props.rows[index].rowId,changes:value}]:[])
  if(!reviewed.value.length)error.value='请先粘贴或修改表格内容。'
 }catch(e){error.value=e instanceof Error?e.message:String(e)}
}
function reset(){cells.value=original.map(row=>[...row]);invalidate()}
useResizeObserver(grid,resizeCells)
onMounted(()=>{resizeCells();focusCell()})
</script>

<template>
 <el-dialog :model-value="true" :title="uiText('Excel矩形粘贴／向下填充')" class="selection-paste-dialog" width="min(1280px,96vw)" top="24px" append-to-body :close-on-click-modal="false" :before-close="()=>!busy&&emit('close')">
  <div class="paste-content">
   <div class="paste-toolbar">
    <strong class="active-position">{{uiText('当前填写：')}}{{activeRow+1}}{{uiText(' 行')}} · {{uiText(fieldLabel(fillableColumns[activeColumn]))}}</strong>
    <el-button :disabled="busy||!hasEdits" @click="reset">{{uiText('重置本次填写')}}</el-button>
   </div>
   <p class="paste-hint">{{uiText('选中单元格后粘贴 Excel，可直接修改；空白保留原值。仅填写本页已有选样行。')}}</p>
   <div ref="grid" class="paste-grid">
    <table :aria-label="uiText('选品矩形粘贴表格')">
     <colgroup><col class="number-column"><col class="style-column"><col v-for="field in fillableColumns" :key="field"></colgroup>
     <thead><tr><th scope="col">{{uiText('行')}}</th><th scope="col">{{uiText('原款式')}}</th><th v-for="(field,column) in fillableColumns" :key="field" scope="col" :class="{'active-heading':activeColumn===column}">{{uiText(fieldLabel(field))}}</th></tr></thead>
     <tbody><tr v-for="(row,index) in rows" :key="row.rowId" :class="{'locked-row':locked(row)}">
      <th scope="row" class="row-number">{{index+1}}</th><th scope="row" class="original-style">{{row.name||uiText('未命名行')}}<small v-if="locked(row)">{{uiText('不可修改')}}</small></th>
      <td v-for="(field,column) in fillableColumns" :key="field" :class="{'active-cell':activeRow===index&&activeColumn===column,'changed-cell':!!differences[index][column].trim()}">
       <span v-if="locked(row)">{{original[index][column]||'—'}}</span>
       <textarea v-else :value="cells[index][column]" :data-row="index" :data-column="column" :aria-label="String(index+1)+' · '+uiText(fieldLabel(field))" :disabled="busy" rows="3" :placeholder="original[index][column]?uiText('空白保留原值'):''" @focus="activeRow=index;activeColumn=column" @input="updateCell(index,column,$event)" @paste="paste($event,index,column)"/>
      </td>
     </tr></tbody>
    </table>
   </div>
   <div v-if="error||saveError" class="paste-error" role="alert">{{uiText(error||saveError)}}</div>
   <div class="paste-summary" role="status">{{reviewed.length?uiText('已核对，待应用 '):uiText('本次修改 ')}}{{reviewed.length||pendingCount}}{{uiText(' 行')}}<span>{{uiText('应用后仍需保存本页增量。')}}</span></div>
  </div>
  <template #footer><el-button :disabled="busy" @click="emit('close')">{{uiText('取消')}}</el-button><el-button :disabled="busy||!pendingCount" @click="check">{{uiText('核对粘贴')}}</el-button><el-button type="primary" :disabled="busy||!reviewed.length" @click="emit('apply',reviewed)">{{uiText('应用本次内容')}}</el-button></template>
 </el-dialog>
</template>

<style scoped>
.paste-content{display:flex;flex-direction:column;gap:8px;min-height:0;height:min(560px,calc(100dvh - 190px));font-size:12px}
.paste-toolbar{display:flex;gap:8px;align-items:center;justify-content:space-between;flex-wrap:wrap;flex:none}
.active-position{font-weight:600;color:#175fba;background:#edf5ff;border-radius:4px;padding:6px 9px}
.paste-hint{margin:0;color:#61758c;line-height:1.5;flex:none}
.paste-grid{flex:1;min-height:0;overflow:auto;border:1px solid #c7d5e4;border-radius:4px;background:white}
table{width:100%;border-collapse:separate;border-spacing:0;table-layout:fixed;font-size:12px}
.number-column{width:32px}.style-column{width:120px}
th,td{border-right:1px solid #d5dfeb;border-bottom:1px solid #d5dfeb;overflow-wrap:anywhere;text-align:left;vertical-align:top}
th:last-child,td:last-child{border-right:0}
thead{position:sticky;top:0;z-index:1}thead th{background:#edf3fa;color:#294969;font-weight:600;line-height:1.5;padding:7px 4px}
.row-number{background:#f5f8fc;color:#718197;text-align:center;padding:8px 2px;font-weight:400}
.original-style{background:#f8fafc;font-weight:500;color:#334e6a;padding:6px;line-height:1.5}
.original-style small{display:block;color:#8590a0;font-weight:400}
td{padding:0;color:#23374e}
td textarea{display:block;box-sizing:border-box;width:100%;min-width:0;min-height:66px;border:0;background:transparent;padding:6px 4px;resize:none;overflow:hidden;outline:none;font:inherit;line-height:1.5;color:inherit;overflow-wrap:anywhere}
.active-heading{background:#dbeaff;color:#175fba}.active-cell{outline:2px solid #3785ed;outline-offset:-2px;background:#f4f9ff}
.changed-cell{background:#eaf5ff}.changed-cell textarea{color:#145fb3}
.locked-row td{background:#f4f5f7;color:#8a94a2}.locked-row td span{display:block;padding:6px 4px;line-height:1.5}
.paste-error{color:#c33535;font-size:12px;line-height:1.5;flex:none}
.paste-summary{display:flex;gap:12px;flex-wrap:wrap;color:#245583;font-weight:600;flex:none}.paste-summary span{color:#718197;font-weight:400}
@media(max-width:1000px){.style-column{width:94px}.number-column{width:26px}}
</style>

<style>
.selection-paste-dialog{display:flex;flex-direction:column;max-height:calc(100dvh - 48px);margin-bottom:24px}
.selection-paste-dialog>.el-dialog__header,.selection-paste-dialog>.el-dialog__footer{flex:none}
.selection-paste-dialog>.el-dialog__body{min-height:0;overflow:hidden}
.selection-paste-dialog .el-dialog__title{font-size:16px;font-weight:600}
</style>
