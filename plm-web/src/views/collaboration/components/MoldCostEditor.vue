<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,onBeforeUnmount,reactive,ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {useUserStore} from '@/stores/user'
import {getMoldCost,saveMoldCost,type ActualCostAmounts,type MoldCostEditor} from '@/api/modules/moldSales'

const props=defineProps<{modelValue:boolean;groupId?:number;canWrite:boolean}>()
const emit=defineEmits<{ 'update:modelValue':[value:boolean]; saved:[] }>()
const user=useUserStore(),snapshot=ref<MoldCostEditor>(),loading=ref(false),saving=ref(false),error=ref(''),reason=ref('')
const fields=[['manufacturingCost','制模费'],['freightCost','运费'],['customsCost','清关费'],['otherCost','其他费用']] as const
const amounts=reactive<Record<keyof ActualCostAmounts,string>>({manufacturingCost:'',freightCost:'',customsCost:'',otherCost:''})
let epoch=0,disposed=false
const editable=computed(()=>props.canWrite&&snapshot.value?.canWrite===true)
const busy=computed(()=>loading.value||saving.value)
const money=(amount:number)=>new Intl.NumberFormat('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2}).format(amount)
const date=(value:string)=>new Date(value).toLocaleString('zh-CN',{hour12:false,timeZone:'Asia/Shanghai'})
// Store input as decimal text; sum integer cents rather than adding binary fractions.
function cents(value:string){const text=value.trim();if(!/^\d+(?:\.\d{1,2})?$/.test(text))return null;const [whole,fraction='']=text.split('.');const result=Number(whole)*100+Number(fraction.padEnd(2,'0'));return Number.isSafeInteger(result)?result:null}
const totalPreview=computed(()=>{const values=fields.map(([key])=>cents(amounts[key])),sum=values.reduce<number>((total,value)=>total+(value||0),0);return values.some(v=>v===null)||!Number.isSafeInteger(sum)?null:sum/100})
function reset(){snapshot.value=undefined;reason.value='';error.value='';for(const [key] of fields)amounts[key]=''}
function apply(next:MoldCostEditor){snapshot.value=next;reason.value='';for(const [key] of fields)amounts[key]=next.actual?next.actual[key].toFixed(2):''}
async function load(){if(!props.modelValue||!props.groupId)return;const id=props.groupId,ticket=++epoch,token=user.token;reset();loading.value=true;try{const next=await getMoldCost(id);if(!disposed&&ticket===epoch&&token===user.token&&props.modelValue&&id===props.groupId)apply(next)}catch(e){if(ticket===epoch&&token===user.token)error.value=e instanceof Error?e.message:'成本读取失败，请重试'}finally{if(ticket===epoch)loading.value=false}}
function close(){if(busy.value)return;++epoch;reset();emit('update:modelValue',false)}
function updateOpen(value:boolean){if(!value)close()}
async function save(){
 if(busy.value||!editable.value||!snapshot.value||!props.groupId)return
 const parsed=fields.map(([key,label])=>({key,label,cents:cents(amounts[key])})),invalid=parsed.find(v=>v.cents===null)
 if(invalid){error.value=`请填写${invalid.label}，金额须为非负数，最多两位小数；无费用请填 0`;return}
 if(totalPreview.value===null){error.value='金额过大，请核对本组费用';return}
 if(!reason.value.trim()){error.value='请填写本次维护原因';return}
 const id=props.groupId,ticket=++epoch,token=user.token,baseline=snapshot.value
 saving.value=true;error.value=''
 try{const next=await saveMoldCost(id,{version:baseline.version,groupToken:baseline.groupToken,amounts:Object.fromEntries(parsed.map(v=>[v.key,v.cents!/100])) as unknown as ActualCostAmounts,reason:reason.value.trim()});if(disposed||ticket!==epoch||token!==user.token||!props.modelValue||id!==props.groupId)return;apply(next);ElMessage.success(uiText('实际成本已保存'));emit('saved')}
 catch(e){if(ticket===epoch&&token===user.token)error.value=e instanceof Error?`${e.message}。输入已保留，请核对后再保存；如资料已被他人修改，请关闭后重新打开。`:'保存失败，输入已保留，请重试'}
 finally{if(ticket===epoch)saving.value=false}
}
watch([()=>props.modelValue,()=>props.groupId],()=>{++epoch;loading.value=false;saving.value=false;if(props.modelValue)void load();else reset()},{immediate:true})
watch(()=>user.token,()=>{++epoch;loading.value=false;saving.value=false;reset();emit('update:modelValue',false)})
watch(()=>props.canWrite,value=>{if(!value){++epoch;loading.value=false;saving.value=false;reset();emit('update:modelValue',false)}})
onBeforeUnmount(()=>{disposed=true;++epoch})
</script>
<template>
 <el-dialog :model-value="modelValue" :title='uiText("模具实际成本")' width="min(720px,96vw)" top="20px" class="mold-cost-editor" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="updateOpen">
  <div v-loading="loading" class="cost-editor-content">
   <el-alert v-if="error" :title="error" type="error" :closable="false"/>
   <section v-if="snapshot" class="cost-snapshot">
    <h3>{{snapshot.styleName}} · {{snapshot.modelNames.join(' / ')}}</h3>
    <p class="scope-note">{{ uiText("填写本组全部模具的实际总金额（人民币），含运费等支出，不是每套单价。未发生的费用请填 0。预算不会自动当作实际成本。") }}</p>
    <p v-if="snapshot.budget" class="budget-reference">{{ uiText("预算参考：¥") }}{{money(snapshot.budget.totalCost)}}</p>
    <el-form v-if="editable" label-position="top" @submit.prevent="save">
     <div class="amount-grid"><el-form-item v-for="[key,label] in fields" :key="key" :label="label+uiText('（元）')" required><el-input v-model="amounts[key]" :aria-label="label+uiText('（元）')" :disabled="saving" inputmode="decimal" :placeholder='uiText("无费用填 0")'/><small v-if="key==='otherCost'">{{ uiText("含燃油附加等其他支出") }}</small></el-form-item></div>
     <div class="actual-total">{{ uiText("实际成本合计 ") }}<strong>{{totalPreview===null?uiText('待填写完整'):'¥'+money(totalPreview)}}</strong></div>
     <el-form-item :label='uiText("本次维护原因")' required><el-input v-model="reason" :aria-label='uiText("本次维护原因")' type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:5}" :disabled="saving" :placeholder='uiText("例如：按工厂结算单补充制模费及实际运费")'/></el-form-item>
    </el-form>
    <dl v-else-if="snapshot.actual" class="actual-values"><template v-for="[key,label] in fields" :key="key"><dt>{{label}}</dt><dd>¥{{money(snapshot.actual[key])}}</dd></template><dt>{{ uiText("实际成本合计") }}</dt><dd>¥{{money(snapshot.actual.totalCost)}}</dd></dl>
    <p v-else class="scope-note">{{ uiText("尚未维护实际成本") }}</p>
    <section v-if="snapshot.history.length" class="cost-history"><h4>{{ uiText("修改记录") }}</h4><article v-for="record in snapshot.history" :key="record.version"><div><strong>¥{{money(record.amounts.totalCost)}}</strong><span>{{record.actorName}} · {{date(record.changedAt)}}</span></div><p>{{ uiText("制模 ¥") }}{{money(record.amounts.manufacturingCost)}}{{ uiText(" · 运费 ¥") }}{{money(record.amounts.freightCost)}}{{ uiText(" · 清关 ¥") }}{{money(record.amounts.customsCost)}}{{ uiText(" · 其他 ¥") }}{{money(record.amounts.otherCost)}}</p><p>{{record.reason}}</p></article></section>
   </section>
   <el-button v-else-if="!loading" @click="load">{{ uiText("重试读取") }}</el-button>
  </div>
  <template #footer><el-button :disabled="busy" @click="close">{{ uiText("关闭") }}</el-button><el-button v-if="editable" type="primary" :loading="saving" :disabled="loading" @click="save">{{ uiText("保存实际成本") }}</el-button></template>
 </el-dialog>
</template>
<style scoped>
:global(.el-dialog.mold-cost-editor){max-height:calc(100dvh - 40px);display:flex;flex-direction:column;margin:20px auto}:global(.mold-cost-editor .el-dialog__header),:global(.mold-cost-editor .el-dialog__footer){flex:none}:global(.mold-cost-editor .el-dialog__body){min-height:0;overflow:auto}
.cost-editor-content{min-height:80px;min-width:0}.cost-editor-content h3{font-size:15px;line-height:22px;margin:0 0 8px;overflow-wrap:anywhere}.scope-note,.budget-reference{font-size:13px;line-height:20px;color:#63748b;margin:6px 0 10px}.amount-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px}.amount-grid :deep(.el-form-item){min-width:0;margin-bottom:12px}.amount-grid small{font-size:12px;color:#63748b}.actual-total{display:flex;justify-content:space-between;gap:10px;flex-wrap:wrap;border-block:1px solid #e2e8ef;padding:10px 0;margin-bottom:12px}.actual-total strong{font-size:17px}.cost-history{margin-top:16px}.cost-history h4{margin:0;font-size:14px}.cost-history article{padding:9px 0;border-bottom:1px solid #e2e8ef;font-size:13px;overflow-wrap:anywhere}.cost-history article>div{display:flex;justify-content:space-between;gap:4px 12px;flex-wrap:wrap}.cost-history article span{color:#63748b;font-size:12px}.cost-history p{margin:4px 0}.actual-values{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:8px}.actual-values dd{margin:0;text-align:right}@media(max-width:550px){.amount-grid{grid-template-columns:minmax(0,1fr)}}
</style>
