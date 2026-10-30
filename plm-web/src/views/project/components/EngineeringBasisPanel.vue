<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed } from 'vue';
import { materialKey, type DevelopmentMaterial, type EngineeringSummary, type ResearchSupplierOption, type SupplierAssignment } from '@/api/modules/productResearch';

const props=defineProps<{
  assignments:SupplierAssignment[]; selected:string[]; materials:DevelopmentMaterial[];
  suppliers:ResearchSupplierOption[]; summary?:EngineeringSummary;
  editable:boolean; assignmentsEditable:boolean; canSelectSupplier:boolean;
  canConfirm:boolean; canManageSupplier:boolean; busy:boolean; reason:string; suppliersRestricted?:boolean;
  productSpecificCode?:string; canMaintainProductIdentity?:boolean;
  persistedAssignments?:SupplierAssignment[]; canChangeSupplier?:boolean;
}>();
const emit=defineEmits<{
  'update:assignments':[SupplierAssignment[]]; 'update:selected':[string[]]; 'update:reason':[string];
  confirm:[changed:boolean]; engineering:['bom'|'process']; suppliers:[]; identity:[]; locate:[string]; changeSupplier:[SupplierAssignment];
}>();
const roles=[{value:'mold',label:'模具'},{value:'component',label:'部件'},{value:'processing',label:'加工'},{value:'finished_product',label:'成品供货'}];
const kinds=['bom','process'] as const;
const roleName=(value:string)=>roles.find(r=>r.value===value)?.label||value;
const statusName=(value:string)=>({draft:'工程草稿',active:'正式',released:'正式',locked:'正式／已锁定',frozen:'已冻结'} as Record<string,string>)[value]||value;
function update(index:number,patch:Partial<SupplierAssignment>){emit('update:assignments',props.assignments.map((a,i)=>i===index?{...a,...patch}:a));}
function add(){emit('update:assignments',[...props.assignments,{mode:props.canSelectSupplier?'supplier':'internal',role:'component',scope:'',basis:''}]);}
function remove(index:number){emit('update:assignments',props.assignments.filter((_,i)=>i!==index));}
function select(kind:'bom'|'process',value:unknown){
  const selected=props.selected.filter(key=>!key.startsWith(`${kind}:`));
  if(value)selected.push(String(value));
  emit('update:selected',selected);
}
function assignmentFacts(rows:SupplierAssignment[]=[]){
  // Compare the server's canonical fact shape; display order and supplier labels are not edits.
  const clean=(value:unknown)=>String(value??'').trim();
  const facts=rows.map(a=>({mode:clean(a.mode),supplierCode:a.mode==='internal'?'':clean(a.supplierCode),internalName:a.mode==='supplier'?'':clean(a.internalName),role:clean(a.role),scope:clean(a.scope),basis:clean(a.basis)}));
  const key=(a:typeof facts[number])=>[a.role,a.scope,a.mode,a.supplierCode,a.internalName].join('|');
  return facts.sort((a,b)=>key(a)<key(b)?-1:key(a)>key(b)?1:0);
}
const localChanged=computed(()=>!!props.summary?.confirmed&&(
  JSON.stringify(assignmentFacts(props.assignments))!==JSON.stringify(assignmentFacts(props.summary.basis?.supplierAssignments))||
  JSON.stringify(props.selected.filter(key=>/^(bom|process):/.test(key)).sort())!==JSON.stringify((props.summary.basis?.references||[]).filter(m=>kinds.includes(m.type as typeof kinds[number])).map(materialKey).sort())
));
const current=computed(()=>props.summary?.current&&!localChanged.value);
const gaps=computed(()=>props.summary?.checks.filter(item=>!item.complete)||[]);
const supplierChanged=computed(()=>(props.persistedAssignments||[]).some(old=>old.mode==='supplier'&&old.supplierCode&&!props.assignments.some(row=>row.mode==='supplier'&&row.supplierCode===old.supplierCode&&(!String(old.scope||'').trim()||row.role===old.role&&String(row.scope||'').trim()===String(old.scope||'').trim()))));
function canReplace(assignment:SupplierAssignment){return props.canChangeSupplier&&assignment.mode==='supplier'&&!!assignment.supplierCode&&(props.persistedAssignments||[]).some(row=>row.mode==='supplier'&&row.supplierCode===assignment.supplierCode&&row.role===assignment.role&&row.scope===assignment.scope);}
</script>

<template>
  <section class="engineering-basis" :aria-label='uiText("供应商与工程方案")' data-readiness-target="engineering">
    <header class="section-heading"><div><h3>{{ uiText("供应商与工程方案") }}</h3><el-tag size="small" :type="current?'success':'warning'">{{current?uiText('当前方案已确认'):summary?.confirmed?uiText('方案已变化，待重新确认'):uiText('尚未集中确认')}}</el-tag><span v-if="summary?.basis?.confirmedAt" class="muted">{{summary.basis.confirmedBy}} · {{summary.basis.confirmedAt}}</span></div><el-button v-if="canManageSupplier" link :disabled="busy" @click="emit('suppliers')">{{ uiText("维护供应商资料 ↗") }}</el-button></header>
    <div class="supplier-heading"><strong>{{ uiText("产品特定编码：") }}{{productSpecificCode||uiText('待分配')}}</strong><el-button v-if="canMaintainProductIdentity" link :disabled="busy" @click="emit('identity')">{{ uiText("维护产品编码 ↗") }}</el-button></div>
    <div class="supplier-heading"><strong>{{ uiText("供应商分工") }}</strong><el-button v-if="assignmentsEditable" :disabled="busy" link type="primary" @click="add">{{ uiText("添加供方") }}</el-button><span class="muted"></span></div>
    <p v-if="suppliersRestricted" role="status">{{ uiText("供应商分工包含当前无权查看的资料，原关系保留；请有权人员办理供方变更。") }}</p>
    <p v-if="!assignments.length" class="empty-assignment">{{ uiText("尚未登记供应商分工或内部制作安排，可先保存草稿。") }}</p>
    <article v-for="(assignment,index) in assignments" :key="index" class="supplier-assignment" :aria-label="uiText('供方分工 {value0}',{value0:(index+1)})">
      <template v-if="assignmentsEditable">
        <el-form label-position="top" :disabled="busy" class="assignment-fields">
          <el-form-item :label='uiText("承担方式")'><el-select :model-value="assignment.mode" :aria-label="uiText('分工{value0}承担方式',{value0:(index+1)})" @update:model-value="update(index,{mode:$event,supplierCode:undefined,internalName:undefined})"><el-option :label='uiText("外部供应商")' value="supplier" :disabled="!canSelectSupplier"/><el-option :label='uiText("内部制作")' value="internal"/></el-select></el-form-item>
          <el-form-item v-if="assignment.mode==='supplier'" :label='uiText("供应商")'><el-select :model-value="assignment.supplierCode" filterable clearable :disabled="!canSelectSupplier" :aria-label="uiText('分工{value0}供应商',{value0:(index+1)})" :placeholder='uiText("选择已有供应商")' @update:model-value="update(index,{supplierCode:$event})"><el-option v-for="supplier in suppliers" :key="supplier.supplierCode" :label="`${supplier.supplierName} · ${supplier.supplierCode}`" :value="supplier.supplierCode"/></el-select></el-form-item>
          <el-form-item v-else :label='uiText("内部承担部门／人员")'><el-input :model-value="assignment.internalName" :aria-label="uiText('分工{value0}内部承担方',{value0:(index+1)})" :placeholder='uiText("如工程部／模具车间")' @update:model-value="update(index,{internalName:$event})"/></el-form-item>
          <el-form-item :label='uiText("承担职责")'><el-select :model-value="assignment.role" :aria-label="uiText('分工{value0}职责',{value0:(index+1)})" @update:model-value="update(index,{role:$event})"><el-option v-for="role in roles" :key="role.value" :label="role.label" :value="role.value"/></el-select></el-form-item>
          <el-form-item :label='uiText("供货／制作范围")'><el-input :model-value="assignment.scope" :aria-label="uiText('分工{value0}范围',{value0:(index+1)})" :placeholder='uiText("如 PC 背板、TPU 包边、组装")' @update:model-value="update(index,{scope:$event})"/></el-form-item>
          <el-form-item :label='uiText("采用依据")' class="assignment-basis"><el-input :model-value="assignment.basis" :aria-label="uiText('分工{value0}依据',{value0:(index+1)})" type="textarea" :autosize="{minRows:2}" :placeholder='uiText("报价、样品验证或内部制作依据")' @update:model-value="update(index,{basis:$event})"/></el-form-item>
        </el-form><el-button link type="danger" :disabled="busy" :aria-label="uiText('删除分工{value0}',{value0:(index+1)})" @click="remove(index)">{{ uiText("删除") }}</el-button>
      </template>
      <template v-else><strong>{{assignment.mode==='internal'?assignment.internalName||uiText('内部承担方待补'):suppliers.find(s=>s.supplierCode===assignment.supplierCode)?.supplierName||assignment.supplierCode||uiText('供应商待补')}}</strong><span>{{roleName(assignment.role)}} · {{assignment.scope||uiText('范围待补')}}</span><p>{{assignment.basis||uiText('采用依据待补')}}</p></template>
      <el-button v-if="canReplace(assignment)" link type="primary" :disabled="busy" @click="emit('changeSupplier',assignment)">{{ uiText("更换供应商") }}</el-button>
    </article>
    <el-form v-if="assignmentsEditable&&supplierChanged" label-position="top"><el-form-item :label='uiText("供应商更换原因")' required><el-input :model-value="reason" :disabled="busy" type="textarea" :autosize="{minRows:2}" :aria-label='uiText("供应商更换原因")' :placeholder='uiText("如原供方无法满足结构、工艺或交付要求，说明本次调整原因")' @update:model-value="emit('update:reason',$event)"/></el-form-item></el-form>
    <el-form class="engineering-versions" label-position="top">
      <el-form-item v-for="kind in kinds" :key="kind" :label="kind==='bom'?uiText('采用 BOM 版本'):uiText('采用工艺路线版本')" :data-readiness-target="`engineering-${kind}`">
        <el-select :model-value="selected.find(key=>key.startsWith(`${kind}:`))||''" clearable :disabled="!editable||busy" :aria-label="kind==='bom'?uiText('集中采用BOM版本'):uiText('集中采用工艺版本')" :placeholder='uiText("选择当前产品的具体版本")' @update:model-value="select(kind,$event)"><el-option v-for="material in materials.filter(m=>m.type===kind)" :key="materialKey(material)" :value="materialKey(material)" :label="uiText('{value0} · {value1} · {value2}',{value0:(material.name),value1:(material.versionNo||'版本待补'),value2:(statusName(material.status))})"/></el-select>
        <el-button link :disabled="busy" @click="emit('engineering',kind)">{{editable?uiText('维护／确认'):uiText('查看')}}{{kind==='bom'?' BOM':uiText('工艺路线')}}</el-button>
      </el-form-item>
    </el-form>
    <div v-if="gaps.length" class="engineering-gaps" role="status"><strong>{{ uiText("保存资料后的待补事项") }}</strong><div v-for="gap in gaps" :key="gap.code"><span>{{gap.message}}</span><el-button link :disabled="busy" @click="emit('locate',gap.target)">{{ uiText("去补齐") }}</el-button></div></div>
    <el-form v-if="canConfirm" class="engineering-confirm" label-position="top"><el-form-item v-if="!supplierChanged" :label="summary?.confirmed?uiText('工程方案变更原因'):uiText('工程方案确认说明')"><el-input :model-value="reason" :disabled="busy" :aria-label="summary?.confirmed?uiText('工程方案变更原因'):uiText('工程方案确认说明')" :placeholder="summary?.confirmed?uiText('方案变化后请说明本次变更原因'):uiText('确认说明（首次可不填）')" @update:model-value="emit('update:reason',$event)"/></el-form-item><el-button type="primary" :disabled="busy" @click="emit('confirm',!current)">{{ uiText("保存并确认工程方案") }}</el-button></el-form>
  </section>
</template>

<style scoped>
.engineering-basis{font-size:12px;padding:18px 0;background:var(--el-bg-color);border-top:1px solid var(--el-border-color-lighter);margin:18px 0 0;min-width:0;container-type:inline-size}
.engineering-basis header,.engineering-basis header>div,.supplier-heading{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.engineering-basis header{justify-content:space-between}
.engineering-basis h3{font-size:14px;font-weight:700;margin:0}
.muted,.empty-assignment{color:var(--el-text-color-secondary)}
.engineering-basis p{margin:8px 0;line-height:1.6;overflow-wrap:anywhere}
.supplier-heading{padding:10px 0 6px}
.supplier-assignment{border-bottom:1px solid var(--el-border-color-lighter);padding:12px 0;min-width:0}
.supplier-assignment>p{width:100%;margin:0}
.assignment-fields,.engineering-versions{min-width:0;display:grid;grid-template-columns:minmax(0,1fr);gap:0}
.assignment-fields :deep(.el-select),.engineering-versions :deep(.el-select){width:100%}
.engineering-versions{margin-top:16px}
.engineering-gaps{background:var(--el-color-warning-light-9);padding:8px 10px;margin-top:10px}
.engineering-gaps>div{display:flex;justify-content:space-between;gap:8px;align-items:center;line-height:1.7}
.engineering-confirm{margin-top:16px}
.engineering-basis :deep(.el-button){font-size:12px;margin-left:0}
</style>
