<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import PhoneModelMultiSelect from '@/components/PhoneModelMultiSelect/index.vue'
import PartnerPhoto from '../PartnerPhoto.vue'
import SupplierRangePanel from '@/views/supplier/SupplierRangePanel.vue'
import type {PartnerFile} from '@/types/partner'
import type {SelectionRow,SelectionRangeSnapshot} from '@/types/partnerSelections'
import {changeSelectionSupplier,developmentLabels,pairCoverage,selectionRowFiles} from '@/utils/partnerSelections'
import {resolveSupplierRanges,supplierColors,supplierRanges,type SupplierColor,type SupplierRange} from '@/api/modules/supplierRanges'
import {partnerError} from '@/utils/partnerError'
const props=defineProps<{row:SelectionRow;files:PartnerFile[];libraryFiles?:PartnerFile[];suppliers:{code:string;name:string;shortCode?:string}[];canWrite:boolean;canReadCost:boolean;context?:'selection'|'catalog'}>()
const modelRanges=ref<SupplierRange[]>([]),colorRanges=ref<SupplierRange[]>([]),colors=ref<SupplierColor[]>([]),scopeOpen=ref(false),error=ref(''),scopeResult=ref(''),pairPhones=ref<number[]>([]),pairColor=ref<number>(),pairState=ref<'available'|'unavailable'|'unverified'>('unverified'),pairEvidence=ref('')
const libraryOpen=ref(false),libraryKeyword=ref(''),libraryPage=ref(1),librarySize=6
const fileMetadata=computed(()=>[...new Map([...(props.libraryFiles||[]),...props.files,...(props.row.fileDetails||[])].map(file=>[file.id,file])).values()])
const adoptedFiles=computed(()=>selectionRowFiles(props.row,fileMetadata.value))
const libraryMatches=computed(()=>{const keyword=libraryKeyword.value.trim().toLocaleLowerCase();return fileMetadata.value.filter(file=>!props.row.refs.some(ref=>ref.id===file.id)&&(!keyword||(file.name+' '+file.version).toLocaleLowerCase().includes(keyword)))})
const visibleLibrary=computed(()=>libraryMatches.value.slice((libraryPage.value-1)*librarySize,libraryPage.value*librarySize))
const sampleQuantity=computed<number|undefined>({get(){const value:unknown=props.row.sampleQty;if(value===undefined||value===null||value==='')return undefined;const quantity=Number(value);return Number.isFinite(quantity)?quantity:undefined},set(value){props.row.sampleQty=value??undefined}})
const hasPhoneScopeEvidence=computed(()=>Boolean(props.row.modelText?.trim()||props.row.phoneModelIds.length||props.row.availablePhoneModelIds.length||props.row.unavailablePhoneModelIds.length||props.row.rangeRefs.some(ref=>ref.type==='model')||props.row.rangeSnapshots?.some(snapshot=>snapshot.type==='model')||props.row.pairings.length||props.row.selectedPairs.length||props.row.sourcePhoneModelIds?.length))
function setPhoneScopeDeclaration(checked:boolean){if(props.context!=='catalog'||!props.canWrite||checked&&hasPhoneScopeEvidence.value)return;props.row.phoneScopeDeclaration=checked?'not_provided':''}
watch(()=>props.row.rowId,()=>{libraryOpen.value=false;libraryKeyword.value='';libraryPage.value=1})
watch(libraryKeyword,()=>{libraryPage.value=1})
watch(()=>libraryMatches.value.length,total=>{libraryPage.value=Math.min(libraryPage.value,Math.max(1,Math.ceil(total/librarySize)))})
const coverage=computed(()=>pairCoverage(props.row)),fields=[{key:'name',label:'款式名称 *'},{key:'series',label:'系列'},{key:'supplierItemCode',label:'供方款号'},{key:'material',label:'材质／配置'},{key:'specification',label:'规格原文'},{key:'thickness',label:'厚度'},{key:'features',label:'工艺／功能与特点'},{key:'leadTimeNote',label:'参考生产周期'},{key:'color',label:'供应商颜色原文'},{key:'modelText',label:'型号原文'},{key:'moq',label:'起订条件原文'},{key:'packaging',label:'包装原文'},{key:'sourcePosition',label:'来源文件／工作表／行号'},{key:'sameStyleKey',label:'工程确认的同款分组'},{key:'developmentEvidence',label:'开发先后核对依据'}] as const
const changedScopes=computed(()=>props.row.rangeSnapshots?.filter(snapshot=>snapshot.versionChanged)||[])
function acknowledge(snapshot:SelectionRangeSnapshot,checked:boolean){props.row.rangeVersionAcknowledgements=(props.row.rangeVersionAcknowledgements||[]).filter(item=>item.id!==snapshot.id);if(checked)props.row.rangeVersionAcknowledgements.push({id:snapshot.id,version:snapshot.version,currentVersion:snapshot.currentVersion})}
watch(()=>props.row.supplierCode,()=>{modelRanges.value=[];colorRanges.value=[];colors.value=[];scopeResult.value='';if(props.row.supplierCode){void loadRanges('model');void loadRanges('color');void loadColors()}},{immediate:true})
async function loadRanges(type:'model'|'color',keyword=''){try{const data=await supplierRanges(props.row.supplierCode,{page:1,size:20,type,keyword});if(type==='model')modelRanges.value=data.content;else colorRanges.value=data.content}catch(e){error.value=partnerError(e)}}
const searchModelRanges=(query:string)=>loadRanges('model',query),searchColorRanges=(query:string)=>loadRanges('color',query)
async function loadColors(keyword=''){try{colors.value=(await supplierColors(props.row.supplierCode,{page:1,size:20,keyword})).content}catch(e){error.value=partnerError(e)}}
const rangeValue=(type:'model'|'color')=>props.row.rangeRefs.find(ref=>ref.type===type)?.reference||''
function setRange(type:'model'|'color',reference:string){props.row.rangeRefs=props.row.rangeRefs.filter(ref=>ref.type!==type);const item=(type==='model'?modelRanges.value:colorRanges.value).find(range=>range.code===reference);if(reference)props.row.rangeRefs.push({type,reference,version:item?.version});props.row.rangeVersionAcknowledgements=[];scopeResult.value='范围引用已改变，请重新核对。不会自动生成供货组合。'}
async function checkRefs(){try{const result=await resolveSupplierRanges(props.row.supplierCode,props.row.rangeRefs,props.row.styleKey,props.row.configKey);scopeResult.value=result.errors.length?`存在待核对项：${JSON.stringify(result.errors)}`:`已解析 ${result.snapshots.length} 项范围；具体成员及版本将在确认时冻结。引用范围仍不代表全部型号颜色可供。`;props.row.rangeSnapshots=result.snapshots as SelectionRangeSnapshot[];props.row.rangeVersionAcknowledgements=[]}catch(e){error.value=partnerError(e)}}
const pairKey=(phoneModelId:number,colorId:number)=>`${phoneModelId}:${colorId}`
const hasPair=(phoneModelId:number,colorId:number)=>props.row.selectedPairs.some(pair=>pairKey(pair.phoneModelId,pair.colorId)===pairKey(phoneModelId,colorId))
function selectPair(phoneModelId:number,colorId:number,checked:boolean){props.row.selectedPairs=props.row.selectedPairs.filter(pair=>pairKey(pair.phoneModelId,pair.colorId)!==pairKey(phoneModelId,colorId));if(checked){props.row.selectedPairs.push({phoneModelId,colorId});props.row.phoneModelIds=[...new Set([...props.row.phoneModelIds,phoneModelId])]}}
function addPairs(){if(!props.canWrite||!props.canReadCost||!pairColor.value||!pairPhones.value.length)return;for(const phoneModelId of pairPhones.value){const existing=props.row.pairings.find(pair=>pair.phoneModelId===phoneModelId&&pair.colorId===pairColor.value);const value={supplierCode:props.row.supplierCode,styleKey:props.row.styleKey||props.row.sameStyleKey,configKey:props.row.configKey||props.row.specification,quoteVersion:props.row.quoteVersion,phoneModelId,colorId:pairColor.value,state:pairState.value,evidence:pairEvidence.value};if(existing)Object.assign(existing,value);else props.row.pairings.push(value)}pairPhones.value=[]}
function adopt(id:number,checked:boolean){if(!props.canWrite)return;props.row.refs=props.row.refs.filter(ref=>ref.id!==id);const file=fileMetadata.value.find(file=>file.id===id);if(checked&&file)props.row.refs.push({id,type:'file',name:file.name,version:file.version})}
function addPriceOption(){if(!props.canWrite||!props.canReadCost)return;(props.row.priceOptions||=[]).push({label:'',unitPrice:'',currency:'',priceNote:'',moq:'',packaging:''})}
function removePriceOption(index:number){if(!props.canWrite||!props.canReadCost)return;props.row.priceOptions?.splice(index,1)}
const phoneName=(id:number)=>props.row.phones?.find(phone=>phone.phoneModelId===id)?.modelName||`手机 #${id}`
const colorName=(id:number)=>props.row.pairings.find(pair=>pair.colorId===id&&pair.colorSnapshot?.name)?.colorSnapshot?.name||colors.value.find(color=>color.id===id)?.name||'原色资料待加载'
</script>
<template>
 <el-form class="selection-row-editor" label-position="top" :disabled="!canWrite" size="small">
  <el-alert v-if="!canWrite" title="本行已确认或当前只读，原依据保留；新报价请在供应商目录录入，新一轮选样从目录选择。" type="info" :closable="false"/>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <section class="evidence adopted-evidence">
   <h3>本行图片与附件 <small>（{{adoptedFiles.length}} 份）</small></h3>
   <p v-if="!adoptedFiles.length">本行暂无关联资料；可从下方资料库选择，或在工作台上传后采用。</p>
   <div v-else class="evidence-grid">
    <article v-for="item in adoptedFiles" :key="item.ref.id" class="evidence-file">
     <PartnerPhoto v-if="item.file?.type?.startsWith('image/')" :id="item.ref.id" :allow-retry="true"/>
     <div class="evidence-info"><span>{{item.file?.name||item.ref.name||'已关联资料 #'+item.ref.id}} · {{item.file?.version||item.ref.version||'版本待加载'}}</span><small v-if="!item.file">资料信息待加载，关联已保留</small><el-checkbox v-if="canWrite" :model-value="true" @change="adopt(item.ref.id,Boolean($event))">本行采用</el-checkbox></div>
    </article>
   </div>
   <details v-if="canWrite" class="other-materials" @toggle="libraryOpen=($event.target as HTMLDetailsElement).open">
    <summary>从资料库选择其他资料</summary>
    <div v-if="libraryOpen" class="material-library">
     <p>仅当前选品最近可访问的 {{fileMetadata.length}} 份资料，并非全部资料库；选择不会改变其他行的关联。</p>
     <el-input v-model="libraryKeyword" clearable aria-label="搜索其他资料" placeholder="搜索资料名称或版本"/>
     <div class="evidence-grid library-grid"><article v-for="file in visibleLibrary" :key="file.id" class="evidence-file"><PartnerPhoto v-if="file.type?.startsWith('image/')" :id="file.id" :allow-retry="true"/><div class="evidence-info"><span>{{file.name}} · {{file.version}}</span><el-checkbox :model-value="false" @change="adopt(file.id,Boolean($event))">采用到本行</el-checkbox></div></article></div>
     <p v-if="!libraryMatches.length">没有其他符合条件的资料。</p>
     <el-pagination v-if="libraryMatches.length>librarySize" v-model:current-page="libraryPage" :page-size="librarySize" :total="libraryMatches.length" :pager-count="5" layout="total,prev,pager,next"/>
    </div>
   </details>
  </section>
  <section class="common-fields">
   <h3>常用资料</h3>
   <div class="field-grid">
    <el-form-item label="款式名称 *"><el-input v-model="row.name" type="textarea" autosize/></el-form-item>
    <el-form-item label="供应商 *"><el-select :model-value="row.supplierCode" filterable clearable @update:model-value="changeSelectionSupplier(row,String($event||''))"><el-option v-for="supplier in suppliers" :key="supplier.code" :value="supplier.code" :label="supplier.name"><span>{{supplier.name}}</span><small v-if="supplier.shortCode" class="supplier-option-code">{{supplier.shortCode}}</small></el-option></el-select></el-form-item>
    <el-form-item label="供方款号"><el-input v-model="row.supplierItemCode" type="textarea" autosize/></el-form-item>
    <el-form-item label="供应商颜色原文"><el-input v-model="row.color" type="textarea" autosize/><el-button v-if="canWrite&&row.supplierCode" link type="primary" @click="scopeOpen=true">维护供应商原色</el-button></el-form-item>
    <el-form-item class="full-width" label="型号原文"><el-input v-model="row.modelText" type="textarea" autosize/><el-checkbox v-if="context==='catalog'" class="phone-scope-declaration" :model-value="row.phoneScopeDeclaration==='not_provided'" :disabled="!canWrite||hasPhoneScopeEvidence&&row.phoneScopeDeclaration!=='not_provided'" @change="setPhoneScopeDeclaration(Boolean($event))">已核对原件，供应商确实未注明型号</el-checkbox></el-form-item>
    <el-form-item class="full-width" label="本次所选型号"><PhoneModelMultiSelect v-model="row.phoneModelIds" :members="row.sourcePhones||row.phones" :allowed-ids="row.sourcePhoneModelIds" :disabled="!canWrite"/><small v-if="row.sourcePhoneModelIds">{{row.catalogSourceSnapshot?.phoneScopeMode==='unspecified'?'供应商未注明型号；本行可调整此前选择，新增请用增选规格。':'仅从本款供应商来源范围内选择；不修改供应商原始资料。'}}</small></el-form-item>
    <template v-if="canReadCost">
     <el-form-item label="数值单价"><el-input v-model="row.unitPrice" inputmode="decimal" placeholder="明确数值；复杂报价填原文"/></el-form-item>
     <el-form-item label="币种"><el-select v-model="row.currency" clearable filterable allow-create placeholder="按来源依据" @change="row.currencySource=row.currency?'explicit':'historical_unknown'"><el-option v-for="currency in ['CNY','USD','MXN','EUR','HKD']" :key="currency" :value="currency" :label="currency"/></el-select><small v-if="row.currencySource==='business_default'">供应商报价业务默认CNY；原始记录另保留。</small></el-form-item><el-form-item label="计价单位"><el-input v-model="row.priceUnit" placeholder="件、套等"/></el-form-item>
    </template>
    <el-form-item label="起订数量"><el-input v-model="row.moqQuantity" inputmode="decimal" placeholder="未知留空"/></el-form-item><el-form-item label="起订单位"><el-input v-model="row.moqUnit" placeholder="个、件、套"/></el-form-item><el-form-item label="起订口径"><el-select v-model="row.moqScope" clearable><el-option label="整款" value="style"/><el-option label="每型号" value="model"/><el-option label="每颜色" value="color"/><el-option label="每型号每颜色" value="model_color"/></el-select></el-form-item><el-form-item label="起订补充条件"><el-input v-model="row.moqNote" type="textarea" autosize/></el-form-item>
    <el-form-item v-if="context!=='catalog'" label="拟拿样合计（本行）"><el-input-number v-model="sampleQuantity" :min="0" :precision="0"/><small>计划数量，不代表实收，也不平均分配到规格。</small></el-form-item>
    <el-form-item class="full-width" label="补充说明"><el-input v-model="row.notes" type="textarea" autosize/></el-form-item>
   </div>
  </section>
  <details class="advanced-section">
   <summary>款式补充：系列、材质、结构、报价与包装</summary>
   <div class="field-grid advanced-body"><el-form-item v-for="field in fields.filter(field=>['series','material','specification','thickness','features','leadTimeNote','packaging'].includes(field.key))" :key="field.key" :label="field.label"><el-input v-model="row[field.key]" :aria-label="field.label" type="textarea" autosize/></el-form-item><el-form-item v-if="canReadCost" class="full-width" label="报价原文"><el-input v-model="row.priceNote" type="textarea" autosize/></el-form-item><el-form-item v-if="canReadCost" class="full-width" label="样品费及退抵约定"><el-input v-model="row.sampleFeeNote" aria-label="样品费及退抵约定" type="textarea" autosize placeholder="按本次实际约定记录，未知可留空"/></el-form-item></div>
   <section v-if="canReadCost" class="selection-price-options">
    <header><h3>配置／包装的多种报价</h3><el-button :disabled="!canWrite" @click="addPriceOption">添加报价选项</el-button></header>
    <p>仅有一档报价时用常用资料里的单价；配置、包装或起订条件不同，再分别填写。币种不明留空。</p>
    <article v-for="(option,index) in row.priceOptions||[]" :key="index" class="selection-price-option"><header><strong>报价 {{index+1}}</strong><el-button :disabled="!canWrite" link type="danger" @click="removePriceOption(index)">移除此报价</el-button></header><div class="field-grid">
     <el-form-item label="适用配置／包装"><el-input v-model="option.label" :aria-label="`报价${index+1}适用配置`" type="textarea" autosize/></el-form-item>
     <el-form-item label="数值单价"><el-input v-model="option.unitPrice" :aria-label="`报价${index+1}单价`" inputmode="decimal" placeholder="只填明确数值"/></el-form-item>
     <el-form-item label="币种"><el-select v-model="option.currency" :aria-label="`报价${index+1}币种`" clearable filterable allow-create placeholder="未知留空"><el-option v-for="currency in ['CNY','USD','MXN','EUR','HKD']" :key="currency" :value="currency" :label="currency"/></el-select></el-form-item>
     <el-form-item label="计价单位"><el-input v-model="option.priceUnit" :aria-label="`报价${index+1}计价单位`"/></el-form-item><el-form-item label="起订数量"><el-input v-model="option.moqQuantity" :aria-label="`报价${index+1}起订数量`" inputmode="decimal"/></el-form-item><el-form-item label="起订单位"><el-input v-model="option.moqUnit" :aria-label="`报价${index+1}起订单位`"/></el-form-item><el-form-item label="起订口径"><el-select v-model="option.moqScope" :aria-label="`报价${index+1}起订口径`" clearable><el-option label="整款" value="style"/><el-option label="每型号" value="model"/><el-option label="每颜色" value="color"/><el-option label="每型号每颜色" value="model_color"/></el-select></el-form-item><el-form-item label="起订补充"><el-input v-model="option.moqNote" :aria-label="`报价${index+1}起订补充`" type="textarea" autosize/></el-form-item>
     <el-form-item label="本档包装"><el-input v-model="option.packaging" :aria-label="`报价${index+1}包装`" type="textarea" autosize/></el-form-item>
     <el-form-item label="本档报价说明"><el-input v-model="option.priceNote" :aria-label="`报价${index+1}说明`" type="textarea" autosize/></el-form-item>
    </div></article>
   </section>
  </details>
  <p v-if="changedScopes.length" class="range-change-notice">引用范围有版本变化，请在下方展开核对后再确认。</p>
  <details class="advanced-section supply-scope">
   <summary>型号、颜色范围与实际供货配对 <small>（按需核对，不自动组合）</small></summary>
   <div class="advanced-body">
    <header><h3>本供应商的范围</h3><el-button v-if="row.supplierCode&&canWrite" link type="primary" @click="scopeOpen=true">维护范围后返回</el-button></header>
    <p>期望型号不等于可供；范围也不自动拼接型号 × 颜色。更换供应商会清除原范围与配对结论，原文和期望型号保留。</p>
    <div class="field-grid"><el-form-item v-for="type in (['model','color'] as const)" :key="type" :label="type==='model'?'型号范围（名称或编码）':'颜色范围（名称或编码）'"><el-select :model-value="rangeValue(type)" filterable remote allow-create clearable :remote-method="type==='model'?searchModelRanges:searchColorRanges" :disabled="!row.supplierCode" @update:model-value="setRange(type,String($event||''))"><el-option v-for="range in type==='model'?modelRanges:colorRanges" :key="range.id" :value="range.code" :label="range.name+' · '+range.code+' · V'+range.version"/></el-select></el-form-item></div>
    <el-button :disabled="!row.supplierCode||!row.rangeRefs.length" @click="checkRefs">核对范围与版本</el-button><p v-if="scopeResult" role="status">{{scopeResult}}</p>
    <section v-for="snapshot in changedScopes" :key="snapshot.id" class="range-version-diff"><strong>{{snapshot.name||snapshot.code}}：引用 V{{snapshot.version}}，当前 V{{snapshot.currentVersion}}</strong><p>新版增加：{{JSON.stringify(snapshot.memberChanges?.added||[])}}</p><p>新版移除：{{JSON.stringify(snapshot.memberChanges?.removed||[])}}</p><el-checkbox :model-value="row.rangeVersionAcknowledgements?.some(item=>item.id===snapshot.id&&item.currentVersion===snapshot.currentVersion)" @change="acknowledge(snapshot,Boolean($event))">我已核对差异，按当前引用的旧版本确认，不自动扩大范围</el-checkbox></section>
    <h3>实际供货配对</h3>
    <p v-if="!canReadCost">报价及供货依据仅有成本维护权限可修改；仍可从下方已记录的配对中选择本次型号和颜色。</p><div v-if="canReadCost" class="pair-add"><PhoneModelMultiSelect v-model="pairPhones" :members="row.sourcePhones||row.phones" :allowed-ids="row.phoneModelIds" :disabled="!canWrite||!row.supplierCode" aria-label="新增配对的手机型号"/><el-select v-model="pairColor" filterable remote :remote-method="loadColors" placeholder="选本供应商的一个原色" :disabled="!row.supplierCode"><el-option v-for="color in colors" :key="color.id" :value="color.id" :label="color.name+' · '+(color.supplierColorCode||color.code)"/></el-select><el-select v-model="pairState"><el-option label="待核实" value="unverified"/><el-option label="已明确可供" value="available"/><el-option label="已明确不供" value="unavailable"/></el-select><el-input v-model="pairEvidence" placeholder="供方确认依据／说明"/><el-button :disabled="!pairColor||!pairPhones.length" @click="addPairs">记录所选型号与此颜色的配对</el-button></div>
    <div class="pair-list"><article v-for="pair in row.pairings" :key="pairKey(pair.phoneModelId,pair.colorId)"><el-checkbox v-if="context!=='catalog'" :model-value="hasPair(pair.phoneModelId,pair.colorId)" :disabled="pair.state==='unavailable'" @change="selectPair(pair.phoneModelId,pair.colorId,Boolean($event))">{{phoneName(pair.phoneModelId)}} · {{colorName(pair.colorId)}}</el-checkbox><span v-else>{{phoneName(pair.phoneModelId)}} · {{colorName(pair.colorId)}}</span><el-select v-if="canReadCost" v-model="pair.state" size="small"><el-option label="待核实" value="unverified"/><el-option label="明确可供" value="available"/><el-option label="明确不供" value="unavailable"/></el-select><el-input v-if="canReadCost" v-model="pair.evidence" size="small" placeholder="本配对依据"/><span v-else class="pair-readonly-state">{{pair.state==='available'?'明确可供':pair.state==='unavailable'?'明确不供':'待核实'}}</span></article><p v-if="!row.pairings.length">尚未记录精确配对；可先存草稿，确认选样前再核实。</p></div>
    <p v-if="context!=='catalog'">本次选择：可供 {{coverage.available}} · 不供 {{coverage.unavailable}} · 待核实 {{coverage.unverified}}；未勾选的不自动算选品。</p>
   </div>
  </details>
  <details class="advanced-section">
   <summary>开发先后与来源</summary>
   <div class="field-grid advanced-body">
    <el-form-item v-for="field in fields.filter(field=>['sourcePosition','sameStyleKey','developmentEvidence'].includes(field.key))" :key="field.key" :label="field.label"><el-input v-model="row[field.key]" type="textarea" autosize/></el-form-item>
    <el-form-item label="开发先后确认"><el-select v-model="row.developmentStatus"><el-option v-for="(label,value) in developmentLabels" :key="value" :value="value" :label="label"/></el-select></el-form-item>
    <el-form-item label="已核实开发日期"><el-date-picker v-model="row.developedOn" type="date" value-format="YYYY-MM-DD" clearable/></el-form-item>
   </div>
  </details>
  <section v-if="row.catalogStyleCode||row.catalogStyleLabel||row.productCode||row.sampleBatches?.length" class="existing-identities"><h3>目录款式与实收批次</h3><p v-if="row.catalogStyleCode||row.catalogStyleLabel">目录款式 {{row.catalogStyleLabel||row.catalogStyleCode}}</p><p v-if="row.productCode">经营档案 {{row.productCode}}</p><p v-for="batch in row.sampleBatches" :key="batch.id">{{batch.displayLabel||batch.label||batch.code}} · {{batch.receivedAt||batch.date||'实收日期待补'}} · {{batch.qty??'数量见台账'}}</p><p v-if="!row.sampleBatches?.length">未实际收样，不生成样品批次号。</p></section>
 </el-form>
 <el-dialog v-model="scopeOpen" title="供应商型号与颜色范围" width="min(1000px,96vw)" append-to-body><SupplierRangePanel v-if="scopeOpen" :supplier-code="row.supplierCode" :can-write="canWrite" initial-tab="colors" :initial-color-text="row.color"/><template #footer><el-button @click="scopeOpen=false;loadRanges('model');loadRanges('color');loadColors()">返回本行（保留本行填写）</el-button></template></el-dialog>
</template>
<style scoped>
.selection-price-options{min-width:0}.selection-price-options>header,.selection-price-option>header{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}.selection-price-options h3{margin:0!important}.selection-price-option{border:1px solid #e2eaf4;border-radius:5px;padding:10px;margin-top:8px;min-width:0}.selection-price-option>header{font-size:13px;margin-bottom:8px}.selection-price-options :deep(.el-button){white-space:normal;height:auto;line-height:1.5;max-width:100%}
.selection-row-editor{min-width:0;container-type:inline-size}
.selection-row-editor h3{font-size:14px;margin:0 0 7px}
.selection-row-editor p,.selection-row-editor small{font-size:12px;line-height:1.6;color:#65758a}
.selection-row-editor p{margin:4px 0 9px}
.supplier-option-code{margin-left:10px;color:#7d8da0;font-size:11px}
.field-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px}
.field-grid>*{min-width:0}
.full-width{grid-column:1/-1}
.selection-row-editor :deep(.el-form-item){margin-bottom:10px}
.selection-row-editor :deep(.el-select),.selection-row-editor :deep(.el-date-editor){width:100%;max-width:100%}
.selection-row-editor :deep(.el-form-item__content>small){display:block;flex-basis:100%}
.phone-scope-declaration{height:auto;min-width:0;margin-right:0;margin-top:5px}
.phone-scope-declaration :deep(.el-checkbox__label){white-space:normal;overflow-wrap:anywhere;line-height:1.6}
.selection-row-editor :deep(textarea){overflow:hidden;line-height:1.6;overflow-wrap:anywhere}
.evidence{border:1px solid #dce5f0;border-radius:6px;padding:10px 12px;margin:0 0 14px;min-width:0}
.evidence-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:7px 12px}
.evidence-file{display:flex;align-items:center;gap:8px;min-width:0}
.evidence-file :deep(.partner-photo){width:64px;height:64px;flex:none}
.evidence-info{display:flex;flex:1;flex-direction:column;gap:2px;min-width:0}
.evidence-info>span{font-size:12px;line-height:1.5;overflow-wrap:anywhere}
.evidence-info :deep(.el-checkbox){height:auto;min-width:0;margin-right:0}
.evidence-info :deep(.el-checkbox__label),.pair-list :deep(.el-checkbox__label){white-space:normal;overflow-wrap:anywhere;line-height:1.6}
.other-materials{margin-top:9px;border-top:1px solid #e8edf4;padding-top:7px}
.other-materials>summary,.advanced-section>summary{cursor:pointer;font-size:13px;line-height:1.6;color:#34597d}
.material-library>.el-input{margin-bottom:9px}
.material-library :deep(.el-pagination){margin-top:8px;max-width:100%;flex-wrap:wrap;justify-content:flex-end}
.advanced-section{border-top:1px solid #e2eaf4;margin-top:8px;padding:9px 0 0;min-width:0}
.advanced-body{padding-top:10px}
.supply-scope header{display:flex;align-items:center;gap:8px;justify-content:space-between;flex-wrap:wrap}
.supply-scope h3{margin-bottom:6px}
.range-change-notice{color:#9a6412!important}
.range-version-diff{border-left:2px solid #d9a441;padding-left:9px;margin:9px 0}
.pair-add{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:7px;margin-bottom:10px}
.pair-list article{display:grid;grid-template-columns:minmax(0,1.2fr) 120px minmax(0,1fr);gap:8px;align-items:center;padding:5px 0}
.pair-list .el-checkbox{height:auto;min-width:0}
.pair-list article>*{min-width:0}
.existing-identities{margin-top:12px}
@container(max-width:740px){.evidence-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.pair-list article{grid-template-columns:minmax(0,1fr) 120px}.pair-list article>.el-input{grid-column:1/-1}}
@container(max-width:540px){.field-grid,.pair-add,.evidence-grid{grid-template-columns:minmax(0,1fr)}}
</style>
