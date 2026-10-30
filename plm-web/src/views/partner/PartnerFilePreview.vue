<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,ref,watch} from 'vue'
import {fileBlob,saveBlob} from '@/api/modules/partner'
import {getAttachmentPreviewMetadata,type AttachmentPreviewVO} from '@/api/modules/attachment'
import {useBlobPreview} from '@/composables/useBlobPreview'
import {useUserStore} from '@/stores/user'
import {attachmentPreviewKey} from '@/utils/authorizedBlobQueue'
import {partnerError} from '@/utils/partnerError'
const props=defineProps<{file:{id:number;name?:string};files?:{id:number;name?:string}[]}>()
const emit=defineEmits<{close:[]}>()
const index=ref(0),items=computed(()=>props.files?.length?props.files:[props.file]),current=computed(()=>items.value[index.value]||props.file)
watch(()=>props.file.id,id=>{index.value=Math.max(0,items.value.findIndex(file=>file.id===id))},{immediate:true})
const user=useUserStore(),metadata=ref<AttachmentPreviewVO>(),metadataLoading=ref(false),metadataError=ref(''),metadataRetry=ref(0),downloading=ref(false),downloadError=ref('')
let controller:AbortController|undefined
watch(()=>[current.value.id,user.token,metadataRetry.value] as const,async([id,token],_,onCleanup)=>{
  const request=new AbortController();controller=request;onCleanup(()=>request.abort())
  metadata.value=undefined;metadataError.value='';downloadError.value='';downloading.value=false;metadataLoading.value=!!token
  if(!token)return
  try{const result=await getAttachmentPreviewMetadata(id,{signal:request.signal});if(!request.signal.aborted)metadata.value=result}
  catch(cause){if(!request.signal.aborted)metadataError.value=partnerError(cause)}
  finally{if(!request.signal.aborted)metadataLoading.value=false}
},{immediate:true})
const blob=useBlobPreview(()=>{
  const id=current.value.id
  if(!metadata.value?.previewable||metadata.value.attachmentId!==id)return
  return {key:attachmentPreviewKey(id),fetch:(signal:AbortSignal)=>fileBlob(id,true,{signal})}
},{imageOnly:false,priority:true})
const {url,type,imageFailed}=blob,loading=computed(()=>metadataLoading.value||blob.loading.value),error=computed(()=>metadataError.value||blob.error.value)
const unsupported=computed(()=>metadata.value?.previewable===false)
function retry(){if(metadataError.value)metadataRetry.value++;else void blob.retry()}
async function download(){
  if(downloading.value||!unsupported.value||!controller)return
  const file=current.value,request=controller;downloading.value=true;downloadError.value=''
  try{const original=await fileBlob(file.id,false,{signal:request.signal});if(!request.signal.aborted)saveBlob(original,file.name||'download')}
  catch(cause){if(!request.signal.aborted)downloadError.value=partnerError(cause)}
  finally{if(!request.signal.aborted)downloading.value=false}
}
</script>
<template><el-dialog :model-value="true" :title="current.name||uiText('图片与附件预览')" width="min(1000px,96vw)" top="20px" append-to-body class="partner-file-preview" @close="emit('close')"><nav v-if="items.length>1" class="preview-nav" :aria-label='uiText("切换预览资料")'><el-button :disabled="index===0" @click="index--">{{ uiText("上一张") }}</el-button><span>{{index+1}} / {{items.length}}</span><el-button :disabled="index===items.length-1" @click="index++">{{ uiText("下一张") }}</el-button></nav><div v-if="loading" class="preview-state" role="status">{{ uiText("正在加载预览，请稍候…") }}</div><div v-else-if="error" class="preview-state" role="alert"><span>{{error}}</span><el-button type="primary" @click="retry">{{ uiText("重新加载") }}</el-button></div><div v-else-if="unsupported" class="preview-state"><span>{{ uiText("此格式请下载查看") }}</span><el-button type="primary" :loading="downloading" @click="download">{{ uiText("下载原文件") }}</el-button><span v-if="downloadError" role="alert">{{ uiText("原件下载失败") }}：{{downloadError}}</span></div><img v-else-if="url&&type.startsWith('image/')" :src="url" :alt="current.name||'产品图片'" class="preview-image" @error="imageFailed"/><iframe v-else-if="url&&(type.includes('pdf')||type.startsWith('text/'))" :src="url" class="preview-frame"/><el-empty v-else :description='uiText("此格式请下载查看")'/></el-dialog></template>
<style scoped>.preview-nav{display:flex;justify-content:center;align-items:center;gap:12px;margin-bottom:8px;font-size:12px}.preview-image{display:block;max-width:100%;max-height:calc(100dvh - 180px);object-fit:contain;margin:auto}.preview-frame{width:100%;height:65vh;border:0}.preview-state{min-height:180px;display:flex;align-items:center;justify-content:center;gap:12px;flex-wrap:wrap;color:var(--el-text-color-secondary)}:global(.partner-file-preview){max-height:calc(100dvh - 40px);display:flex;flex-direction:column;margin-bottom:20px}:global(.partner-file-preview>.el-dialog__body){min-height:0;overflow:auto}</style>
