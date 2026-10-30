<script setup lang="ts">
import { computed } from 'vue'
import { t as uiText } from '@/locales'
import { moldBusinessStatusNames, type MoldApplication, type ResearchCodingInput } from '@/api/modules/moldApplications'

const props=defineProps<{
  applications:MoldApplication[]; inputs:Record<number,ResearchCodingInput>
  editable:boolean;busy:boolean;canCreate:boolean;canPrepare:boolean
}>()
const emit=defineEmits<{
  'update:inputs':[value:Record<number,ResearchCodingInput>]
  create:[];edit:[id:number];progress:[id:number];archive:[application:MoldApplication];'other-phone':[]
}>()
// Match ProductBusinessCodeGenerator's complete identifiers and text boundaries; approval prose is not a code.
const completeMoldCode=/^M[A-Z]{2}[0-9]{6}$/i
const moldCodeInText=/(?<![A-Z0-9_-])M[A-Z]{2}[0-9]{6}(?![A-Z0-9_-])/gi
function displayMoldCodes(application:MoldApplication){
  const structured=application.researchCoding?.generatedMoldCodes
  // An explicit structured result, including [], cannot be replaced by an older reservation or approval text.
  if(structured!==undefined)return Array.isArray(structured)?[...new Set(structured.map(code=>code.trim().toUpperCase()).filter(code=>completeMoldCode.test(code)))]:[]
  const legacy=(application.generatedCodes||'').replace(/\\n/g,'\n').replace(/\\r/g,'\r').replace(/\\t/g,'\t')
  return [...new Set([...legacy.matchAll(moldCodeInText)].map(match=>match[0].toUpperCase()))]
}
const rows=computed(()=>props.applications.map(application=>{
  const coding=application.researchCoding
  const molds=displayMoldCodes(application)
  const products=coding?.generatedProductCodes||[]
  return {application,coding,molds,products,input:props.inputs[application.applicationId]||{}}
}))
function status(application:MoldApplication){return moldBusinessStatusNames[application.businessStatus||application.status as keyof typeof moldBusinessStatusNames]||uiText('审批待核对')}
function update(application:MoldApplication,field:keyof ResearchCodingInput,value:string|string[]){
  emit('update:inputs',{...props.inputs,[application.applicationId]:{...props.inputs[application.applicationId],[field]:value}})
}
function outcome(application:MoldApplication){
  if(application.executionStatus==='paused')return uiText('执行已暂停')
  if(application.executionStatus==='terminated')return uiText('执行已终止')
  if(['rejected','withdrawn','terminated'].includes(application.businessStatus||application.status))return ''
  if(application.provisioningError)return application.provisioningError
  if(application.researchCoding?.status==='ready')return uiText('已自动生成并关联档案')
  if(application.researchCoding?.missingFields?.length)return ''
  if(application.approvalGranted&&application.authorization)return uiText('正在自动建档')
  return application.status==='draft'?uiText('沿用原草稿，完善后提交'):uiText('Tiffany 同意后自动生成编码')
}
function productLabel(application:MoldApplication,code:string){
  const product=application.researchCoding?.products?.find(product=>(product.code||product.finishedProductCode)===code)
  return product?.colorName?`${uiText('成品')} · ${product.colorName}`:uiText('产品编码')
}
</script>

<template>
  <section class="research-coding" :aria-label="uiText('LEMOS开模申请')" data-readiness-target="mold-authorization">
    <header><h3>{{uiText('开模申请与编码')}}</h3><el-button v-if="canCreate" type="primary" :disabled="busy||!canPrepare" @click="applications.length?emit('other-phone'):emit('create')">{{uiText(applications.length?'选择其他型号':'发起LEMOS开模申请')}}</el-button></header>
    <p v-if="!applications.length" class="coding-note">{{uiText('Tiffany 同意后，自动生成 M 码和 N 码。')}}</p>
    <article v-for="row in rows" :key="row.application.applicationId" class="coding-application">
      <div class="research-application-row">
        <span><strong>{{row.application.approvalNo||uiText('申请 #{value0}',{value0:row.application.applicationId})}}</strong> · {{row.application.standardPhone?.modelName||row.application.model||uiText(row.application.phoneModelId?'标准手机资料待读取':'标准手机待补充')}}</span>
        <el-button v-if="row.application.status==='draft'&&row.application.canEditDraft&&canCreate" link type="primary" :disabled="busy" @click="emit('edit',row.application.applicationId)">{{uiText('继续填写')}}</el-button>
        <el-button v-else link type="primary" :disabled="busy" @click="emit('progress',row.application.applicationId)">{{uiText(row.application.terminal?'查看原审批':'查看审批进度')}}</el-button>
      </div>
      <div class="coding-status"><strong :class="{'is-approved':row.application.approvalGranted}">{{uiText(status(row.application))}}</strong><span>{{outcome(row.application)}}</span></div>
      <p v-if="row.application.refusal" class="coding-error">{{row.application.refusal.reason||uiText('审批意见未提供')}}</p>
      <div v-if="row.molds.length||row.products.length||row.coding?.productCodePrefix" class="code-results">
        <div v-for="code in row.molds" :key="`m-${code}`"><code>{{code}}</code><span>{{uiText(code.slice(3,5)==='10'?'TPU 模具':code.slice(3,5)==='20'?'PC 模具':'模具')}}</span></div>
        <div v-for="code in row.products" :key="`n-${code}`"><code>{{code}}</code><span>{{productLabel(row.application,code)}}</span></div>
        <div v-if="!row.products.length&&row.coding?.productCodePrefix"><code>{{row.coding.productCodePrefix}}</code><span>{{uiText('产品编码前缀')}}</span></div>
        <el-button v-if="row.application.itemId" link type="primary" @click="emit('archive',row.application)">{{uiText('查看模具档案')}}</el-button>
      </div>
      <el-form v-if="row.coding?.missingFields?.some(key=>['materialCodes','operationCode'].includes(key))" class="coding-missing" label-position="top" :disabled="busy||!editable||!row.application.canMaintainResearchCoding">
        <el-form-item v-if="row.coding.missingFields.includes('materialCodes')" :label="uiText('待补：模具材质')" :data-readiness-target="`coding-material-${row.application.applicationId}`"><el-checkbox-group :model-value="row.input.materialCodes||row.coding.materialCodes||[]" @update:model-value="update(row.application,'materialCodes',$event as string[])"><el-checkbox value="10">TPU</el-checkbox><el-checkbox value="20">PC</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item v-if="row.coding.missingFields.includes('operationCode')" :label="uiText('待补：产品工序')" :data-readiness-target="`coding-operation-${row.application.applicationId}`"><el-select :model-value="row.input.operationCode||row.coding.operationCode||''" :placeholder="uiText('选择已有工序')" @update:model-value="update(row.application,'operationCode',$event)"><el-option v-for="operation in row.coding.operationOptions||[]" :key="operation.code" :value="operation.code" :label="`${operation.code} · ${operation.name}`"/></el-select></el-form-item>
        <span class="coding-note">{{uiText('保存后自动续办。')}}</span>
      </el-form>
      <p v-for="message in row.coding?.missingMessages||[]" :key="message" class="coding-error">{{uiText(message)}}</p>
    </article>
  </section>
</template>

<style scoped>
.research-coding{margin-top:20px;padding-top:18px;border-top:1px solid var(--el-border-color-lighter);min-width:0}
header,.research-application-row,.coding-status{display:flex;align-items:center;justify-content:space-between;gap:6px 12px;flex-wrap:wrap;min-width:0}
header{margin-bottom:12px}h3{font-size:14px;font-weight:700;margin:0;color:var(--research-label-ink,var(--el-text-color-primary))}
.coding-application+.coding-application{border-top:1px solid var(--el-border-color-lighter);padding-top:14px;margin-top:14px}
.research-application-row>span{min-width:0;overflow-wrap:anywhere}.coding-status{justify-content:flex-start;margin:8px 0 10px;color:var(--el-text-color-secondary)}
.coding-status strong{color:var(--el-text-color-primary)}.coding-status .is-approved{color:var(--el-color-success)}
.coding-status span{min-width:0;overflow-wrap:anywhere}
.code-results{min-width:0}.code-results>div{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap;padding:7px 0;border-bottom:1px solid var(--el-border-color-lighter)}
.code-results code{font-size:12px;color:var(--el-text-color-primary);overflow-wrap:anywhere}.code-results span,.coding-note{font-size:12px;color:var(--el-text-color-secondary)}
.coding-missing{margin-top:12px;padding:12px;background:var(--el-color-warning-light-9);border-left:3px solid var(--el-color-warning);min-width:0}
.coding-missing :deep(.el-select){width:100%}.coding-missing :deep(.el-checkbox-group){padding:4px 9px}.coding-error{color:var(--el-color-warning-dark-2);font-size:12px;margin:6px 0;overflow-wrap:anywhere}
</style>
