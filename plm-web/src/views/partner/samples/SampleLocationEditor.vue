<script setup lang="ts">
import {computed,reactive,ref,watch} from 'vue'
import {ElMessageBox} from 'element-plus'
import {t as uiText} from '@/locales'
import {useUserStore} from '@/stores/user'
import {newRequestId} from '@/api/modules/partner'
import {saveSampleLocation} from '@/api/modules/sampleManagement'
import type {SampleLocationOption,SampleLocationParts,SampleLocationSite,SampleLocationInputData} from '@/types/sampleManagement'
import {sampleLocationCode,sampleLocationError,sampleLocationParts,sampleLocationSites,sampleLocationAccess} from '@/utils/sampleLocation'
import {useSampleSubmission} from './useSampleSubmission'
import SampleLocationInput from './SampleLocationInput.vue'

const props=defineProps<{location?:SampleLocationOption;initialSite?:SampleLocationSite;siteLocked?:boolean;useAfterSave?:boolean}>()
const emit=defineEmits<{close:[];saved:[value:SampleLocationOption]}>(),user=useUserStore()
const {busy,error,unknown,pending,submit}=useSampleSubmission('location-editor:'+(props.location?.id||'new'))
const form=reactive({site:props.location?.site||props.initialSite||'CN' as SampleLocationSite,name:props.location?.name||'',locationCode:props.location?.locationCode||'',locationParts:sampleLocationParts(props.location?.locationParts,props.location?.locationCode) as Partial<SampleLocationParts>,status:props.location?.deleted?'active':props.location?.status||'active'})
if(pending.value)Object.assign(form,pending.value)
const requestId=String(pending.value?.requestId||newRequestId()),dirty=ref(false)
const access=computed(()=>sampleLocationAccess(user)),canWrite=computed(()=>access.value.canWrite&&props.location?.canWrite!==false)
if(access.value.localOnly)form.site='MX'
watch(form,()=>{dirty.value=true},{deep:true})
watch(()=>user.token,()=>emit('close'))
async function close(){if(busy.value||unknown.value)return;if(dirty.value)try{await ElMessageBox.confirm(uiText('放弃尚未保存的位置填写？'),uiText('保留填写'),{confirmButtonText:uiText('放弃'),cancelButtonText:uiText('继续填写')})}catch{return}emit('close')}
async function save(){
 if(!canWrite.value)return
 if(!unknown.value){const issue=sampleLocationError(form.locationCode,form.locationParts)||(!form.name.trim()&&!form.locationCode.trim()?uiText('请填写位置名称或完整位置编码'):'');if(issue){error.value=uiText(issue);return}}
 const result=await submit(()=>({requestId,...(props.location?{version:props.location.version}:{}),site:form.site,name:form.name.trim(),locationCode:sampleLocationCode(sampleLocationParts(form.locationParts,form.locationCode)),locationParts:sampleLocationParts(form.locationParts,form.locationCode),status:form.status}),data=>saveSampleLocation(data as unknown as SampleLocationInputData,props.location?.id))
 if(result){dirty.value=false;emit('saved',result)}
}
</script>
<template>
 <el-dialog class="sample-location-editor" :model-value="true" :title="uiText(location?'修改位置':'新增位置')" width="min(620px,96vw)" append-to-body :close-on-click-modal="false" :before-close="close">
  <el-form label-position="top" :disabled="busy||unknown||!canWrite">
   <el-form-item :label="uiText('站点')"><el-select v-model="form.site" :disabled="siteLocked||access.localOnly||!!location" :aria-label="uiText('位置站点')"><el-option v-for="site in sampleLocationSites.filter(value=>!access.localOnly||value.value==='MX')" :key="site.value" :value="site.value" :label="uiText(site.label)"/></el-select></el-form-item>
   <el-form-item :label="uiText('位置名称')"><SampleLocationInput v-model="form.name" v-model:location-code="form.locationCode" v-model:location-parts="form.locationParts" :allow-catalog="false" placeholder="例如：中国样品间" aria-label="位置说明"/></el-form-item>
   <el-form-item v-if="location" :label="uiText('可选状态')"><el-select v-model="form.status" :aria-label="uiText('位置可选状态')"><el-option value="active" :label="uiText('可选')"/><el-option value="inactive" :label="uiText('停用')"/></el-select></el-form-item>
   <p class="location-note">{{uiText('位置码选填，可先填写位置名称；修改目录不改变样品数量和历史。')}}</p>
  </el-form>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <template #footer><el-button :disabled="busy||unknown" @click="close">{{uiText('取消')}}</el-button><el-button type="primary" :loading="busy" :disabled="!canWrite" @click="save">{{uiText(unknown?'核对本次保存结果':useAfterSave?'保存并使用':'保存位置')}}</el-button></template>
 </el-dialog>
</template>
<style scoped>.sample-location-editor .el-select{width:100%}.location-note{font-size:12px;line-height:1.6;color:#75869a}:global(.sample-location-editor){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-block:20px;overflow:hidden}:global(.sample-location-editor>.el-dialog__body){min-height:0;overflow:auto}</style>
