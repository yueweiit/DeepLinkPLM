<script setup lang="ts">
import {computed,defineAsyncComponent,onBeforeUnmount,ref} from 'vue'
import {useFormDisabled} from 'element-plus'
import {useUserStore} from '@/stores/user'
import {t as uiText} from '@/locales'
import type {SampleLocationParts,SampleLocationChoice,SampleLocationOption,SampleLocationSite} from '@/types/sampleManagement'
import {sampleLocationCode,sampleLocationError,sampleLocationParts,sampleLocationSegments,samplePositionKey,sampleLocationLabel,sampleLocationSites,sampleLocationAccess} from '@/utils/sampleLocation'
import {useSampleLocationOptions} from './useSampleLocationOptions'

const SampleLocationEditor=defineAsyncComponent(()=>import('./SampleLocationEditor.vue')),editorOpen=ref(false)
const props=withDefaults(defineProps<{modelValue?:string;locationId?:number;site?:SampleLocationSite|'';siteLocked?:boolean;locationCode?:string;locationParts?:Partial<SampleLocationParts>;positions?:SampleLocationChoice[];placeholder?:string;ariaLabel?:string;disabled?:boolean;allowCatalog?:boolean;codeRequired?:boolean;compact?:boolean}>(),{modelValue:'',locationCode:'',placeholder:'填写实际存放地点',ariaLabel:'实际存放位置',allowCatalog:true,compact:false,disabled:undefined})
const emit=defineEmits<{'update:modelValue':[value:string];'update:locationId':[value:number|undefined];'update:site':[value:SampleLocationSite];'update:locationCode':[value:string];'update:locationParts':[value:SampleLocationParts]}>()
const formDisabled=useFormDisabled()
const user=useUserStore(),access=computed(()=>sampleLocationAccess(user)),currentSite=computed(()=>access.value.localOnly?'MX':props.site||'CN')
function changeSite(value:SampleLocationSite){
 const specific=!!props.locationId||!!props.locationCode||Object.values(props.locationParts||{}).some(Boolean)||!!selectedCatalogRow.value
 if(specific||['CN','MX'].includes(props.modelValue.trim()))emit('update:modelValue',value)
 chosen.value=undefined;emit('update:site',value);emit('update:locationId',undefined);emit('update:locationCode','');emit('update:locationParts',sampleLocationParts());void catalog.load({site:value})
}
function updateName(value:string){emit('update:modelValue',value);emit('update:locationId',undefined)}
const parts=computed(()=>sampleLocationParts(props.locationParts,props.locationCode))
const code=computed(()=>sampleLocationCode(parts.value)),error=computed(()=>sampleLocationError(props.locationCode,props.locationParts))
const catalog=useSampleLocationOptions(),chosen=ref<SampleLocationOption>(),catalogExpanded=ref(false)
const selectedCatalogRow=computed(()=>{const rows=[...catalog.rows.value,...(chosen.value?[chosen.value]:[])];return rows.find(row=>props.locationId?row.id===props.locationId:row.site===currentSite.value&&row.name===props.modelValue&&(row.locationCode||'')===props.locationCode)})
const selectedCatalog=computed(()=>selectedCatalogRow.value?.id)
const shownCatalogRows=computed(()=>catalogExpanded.value?catalog.rows.value.filter(row=>row.site===currentSite.value):selectedCatalogRow.value?[selectedCatalogRow.value]:[])
async function created(row:SampleLocationOption){editorOpen.value=false;chosen.value=row;await catalog.reload();emit('update:locationId',row.id);emit('update:site',row.site);emit('update:modelValue',row.name);emit('update:locationCode',row.locationCode||'');emit('update:locationParts',sampleLocationParts(row.locationParts,row.locationCode))}
function selectCatalog(id:number|undefined){if(!id)return;const row=catalog.rows.value.find(row=>row.id===id)||(chosen.value?.id===id?chosen.value:undefined);if(!row)return;chosen.value=row;emit('update:locationId',row.id);emit('update:site',row.site);emit('update:modelValue',row.name);emit('update:locationCode',row.locationCode||'');emit('update:locationParts',sampleLocationParts(row.locationParts,row.locationCode))}
const compactName=computed(()=>!props.locationId&&props.modelValue===currentSite.value?currentSite.value==='CN'?'':sampleLocationLabel({name:props.modelValue,site:currentSite.value}):props.modelValue)
function updateCompactName(value:string){chosen.value=undefined;emit('update:modelValue',value.trim()?value:currentSite.value);emit('update:locationId',undefined);emit('update:locationCode','');emit('update:locationParts',sampleLocationParts())}
async function suggestLocations(keyword:string,done:(items:{value:string;id:number}[])=>void){await catalog.load({site:currentSite.value,...(keyword.trim()?{keyword:keyword.trim()}:{})});done(catalog.rows.value.filter(row=>row.site===currentSite.value).map(row=>({value:sampleLocationLabel(row),id:row.id})))}
let searchTimer:ReturnType<typeof setTimeout>|undefined
function focusCatalog(){catalogExpanded.value=true;void catalog.load({site:currentSite.value})}
function openCatalog(open:boolean){catalogExpanded.value=open;if(open)void catalog.load({site:currentSite.value})}
function searchCatalog(keyword:string){clearTimeout(searchTimer);searchTimer=setTimeout(()=>void catalog.load({site:currentSite.value,...(keyword.trim()?{keyword:keyword.trim()}:{})}),250)}
onBeforeUnmount(()=>clearTimeout(searchTimer))
const filteredPositions=computed(()=>(props.positions||[]).filter(position=>!position.site||position.site===currentSite.value))
const selectedPosition=computed(()=>{const match=filteredPositions.value.find(position=>props.locationId!==undefined?position.locationId===props.locationId:position.name===props.modelValue&&(position.locationCode||'')===props.locationCode);return match?samplePositionKey(match):props.modelValue})
function selectPosition(value:string){const match=filteredPositions.value.find(position=>samplePositionKey(position)===value);emit('update:locationId',match?.locationId);if(match?.site)emit('update:site',match.site);emit('update:modelValue',match?.name??value);emit('update:locationCode',match?.locationCode||'');emit('update:locationParts',sampleLocationParts(match?.locationParts,match?.locationCode))}
function updatePart(key:keyof SampleLocationParts,value:string){emit('update:locationId',undefined);const next={...parts.value,[key]:value};emit('update:locationParts',next);emit('update:locationCode',sampleLocationCode(next))}
function updateCode(value:string){emit('update:locationId',undefined);emit('update:locationCode',value);emit('update:locationParts',sampleLocationParts(undefined,value))}
</script>
<template>
 <div class="sample-location-input">
  <template v-if="compact&&allowCatalog">
   <div class="location-compact"><el-autocomplete :model-value="compactName" :fetch-suggestions="suggestLocations" :debounce="250" clearable fit-input-width popper-class="sample-location-compact-options" :highlight-first-item="false" :disabled="formDisabled" :aria-label="uiText(ariaLabel)" :placeholder="uiText('搜索库位或直接填写位置说明')" @update:model-value="updateCompactName(String($event??''))" @select="selectCatalog(Number($event.id))"/><el-button v-if="access.canWrite" link size="small" :disabled="formDisabled" @click="editorOpen=true">{{uiText('新增库位并使用')}}</el-button></div>
   <small v-if="catalog.more.value">{{uiText('显示前50项，请输入位置码或说明继续搜索')}}</small><small v-if="catalog.error.value" class="location-code-error">{{catalog.error.value}} <el-button link size="small" :disabled="formDisabled" @click="catalog.reload">{{uiText('重试')}}</el-button></small>
  </template>
  <template v-else>
  <div v-if="allowCatalog" class="location-catalog"><el-select :model-value="currentSite" :disabled="formDisabled||siteLocked||access.localOnly" :aria-label="uiText('站点')" @update:model-value="changeSite"><el-option v-for="value in sampleLocationSites.filter(value=>!access.localOnly||value.value==='MX')" :key="value.value" :value="value.value" :label="uiText(value.label)"/></el-select><el-select :model-value="selectedCatalog" filterable remote :remote-method="searchCatalog" :loading="catalog.loading.value" :disabled="formDisabled" :placeholder="uiText('选择已登记位置，自动填写')" :aria-label="uiText('选择已登记位置')" @focus="focusCatalog" @visible-change="openCatalog" @update:model-value="selectCatalog"><el-option v-if="catalogExpanded&&chosen&&!shownCatalogRows.some(row=>row.id===chosen?.id)" :value="chosen.id" :label="sampleLocationLabel(chosen)"/><el-option v-for="row in shownCatalogRows" :key="row.id" :value="row.id" :label="sampleLocationLabel(row)"/><template #empty><span>{{uiText(catalog.loading.value?'加载中…':'暂无数据')}}</span></template></el-select><el-button v-if="access.canWrite" link size="small" :disabled="formDisabled" @click="editorOpen=true">{{uiText('新增库位并使用')}}</el-button><small v-if="catalog.more.value">{{uiText('显示前50项，请输入位置码或说明继续搜索')}}</small><small v-if="catalog.error.value" class="location-code-error">{{catalog.error.value}} <el-button link size="small" :disabled="formDisabled" @click="catalog.reload">{{uiText('重试')}}</el-button>{{uiText('仍可在下方手填位置')}}</small></div>
  <el-select v-if="filteredPositions.length" :model-value="selectedPosition" filterable allow-create default-first-option :disabled="formDisabled" :placeholder="uiText(placeholder)" :aria-label="uiText(ariaLabel)" @update:model-value="selectPosition"><el-option v-for="position in filteredPositions" :key="samplePositionKey(position)" :value="samplePositionKey(position)" :label="sampleLocationLabel(position)"/></el-select>
  <el-input v-else :model-value="modelValue" :disabled="formDisabled" :placeholder="uiText(placeholder)" :aria-label="uiText(ariaLabel)" @update:model-value="updateName"/>
  <template v-if="!allowCatalog"><div class="location-code-heading">{{uiText(codeRequired?'位置编码 *':'位置编码（选填）')}}</div>
  <el-input :model-value="locationCode" :disabled="formDisabled" placeholder="A001-A0001-001-001-0001" :aria-label="uiText('粘贴或输入完整位置码')" @update:model-value="updateCode"/>
  <div class="location-segments" role="group" :aria-label="uiText(ariaLabel)+' · '+uiText('位置编码')">
   <label v-for="segment in sampleLocationSegments" :key="segment.key"><span>{{uiText(segment.label)}}</span><el-input :model-value="parts[segment.key]" :disabled="formDisabled" :placeholder="segment.example" :aria-label="uiText(ariaLabel)+' · '+uiText('位置编码')+' · '+uiText(segment.label)" @update:model-value="updatePart(segment.key,$event)"/></label>
  </div>
  <div v-if="code" class="location-code-preview">{{uiText('完整位置码：')}}<strong>{{code}}</strong></div>
  </template></template><small v-if="allowCatalog&&locationCode" class="location-code-preview">{{locationCode}}</small><div v-if="error" class="location-code-error" role="alert">{{uiText(error)}}</div><SampleLocationEditor v-if="editorOpen" :initial-site="currentSite" :site-locked="siteLocked" use-after-save @close="editorOpen=false" @saved="created"/>
 </div>
</template>
<style scoped>
.location-compact{display:flex;align-items:center;gap:8px;min-width:0}.location-compact>.el-autocomplete{flex:1;min-width:0}.location-compact>.el-button{flex:none;white-space:normal;height:auto;line-height:1.5}.location-compact~small{display:block;line-height:1.5}
:global(.sample-location-compact-options .el-autocomplete-suggestion__list li){white-space:normal;overflow-wrap:anywhere;line-height:1.5;padding-block:6px}
.sample-location-input{width:100%;min-width:0;font-size:12px;line-height:1.5;container-type:inline-size}.sample-location-input>.el-select,.location-catalog>.el-select{width:100%}.location-catalog{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:6px}.location-catalog>.el-select:first-child{width:100px;flex:none}.location-catalog>.el-select:nth-child(2){flex:1;min-width:180px}.location-catalog small{flex-basis:100%}.location-catalog small{display:block;line-height:1.5}.location-code-heading{margin-top:7px;color:#64788f}.location-segments{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:5px}.location-segments label{min-width:0;display:flex;flex-direction:column;gap:3px}.location-segments label>span{font-size:12px;color:#64788f;white-space:normal;line-height:1.4}.location-code-preview{margin-top:5px;overflow-wrap:anywhere;color:#59728f}.location-code-preview strong{font-weight:600;color:#334e6d}.location-code-error{margin-top:4px;color:var(--el-color-danger);line-height:1.5}.sample-location-input :deep(.el-input__wrapper){min-height:28px;font-size:12px;padding-inline:7px}.sample-location-input :deep(.el-input__inner){min-width:0}@container(max-width:420px){.location-segments{grid-template-columns:repeat(3,minmax(0,1fr))}}
</style>
