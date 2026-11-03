<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,ref,watch} from 'vue'
import {fileBlob,saveBlob} from '@/api/modules/partner'
import {getAttachmentPreviewMetadata,type AttachmentPreviewVO} from '@/api/modules/attachment'
import {useBlobPreview} from '@/composables/useBlobPreview'
import {useUserStore} from '@/stores/user'
import {attachmentPreviewKey} from '@/utils/authorizedBlobQueue'
import {partnerError} from '@/utils/partnerError'
interface PreviewFile {id:number;name?:string;version?:string}
const props=defineProps<{file:PreviewFile;files?:PreviewFile[];title?:string}>()
const emit=defineEmits<{close:[]}>()
const index=ref(0),items=computed(()=>props.files?.length?props.files:[props.file]),current=computed(()=>items.value[index.value]||props.file)
watch(()=>props.file.id,id=>{index.value=Math.max(0,items.value.findIndex(file=>file.id===id))},{immediate:true})
const heading=computed(()=>`${props.title||uiText('图片预览')} · ${index.value+1} / ${items.value.length}`)
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
  if(downloading.value||!metadata.value||metadata.value.attachmentId!==current.value.id||!controller)return
  const file=current.value,request=controller;downloading.value=true;downloadError.value=''
  try{const original=await fileBlob(file.id,false,{signal:request.signal});if(!request.signal.aborted)saveBlob(original,file.name||'download')}
  catch(cause){if(!request.signal.aborted)downloadError.value=partnerError(cause)}
  finally{if(!request.signal.aborted)downloading.value=false}
}
</script>
<template>
 <el-dialog :model-value="true" :title="heading" width="min(1200px,96vw)" top="16px" append-to-body class="partner-file-preview" @close="emit('close')">
  <nav v-if="items.length>1" class="preview-nav" :aria-label="uiText('切换预览资料')"><el-button :disabled="index===0" @click="index--">{{ uiText('上一张') }}</el-button><span aria-live="polite">{{index+1}} / {{items.length}}</span><el-button :disabled="index===items.length-1" @click="index++">{{ uiText('下一张') }}</el-button></nav>
  <div class="preview-stage" :aria-busy="loading">
   <div v-if="loading" class="preview-state" role="status">{{ uiText('正在加载预览，请稍候…') }}</div>
   <div v-else-if="error" class="preview-state" role="alert"><span>{{error}}</span><el-button type="primary" @click="retry">{{ uiText('重新加载') }}</el-button></div>
   <div v-else-if="unsupported" class="preview-state"><span>{{ uiText('此格式请下载查看') }}</span><el-button type="primary" :loading="downloading" @click="download">{{ uiText('下载原文件') }}</el-button><span v-if="downloadError" role="alert">{{ uiText('原件下载失败') }}：{{downloadError}}</span></div>
   <img v-else-if="url&&type.startsWith('image/')" :src="url" :alt="props.title||uiText('产品图片')" class="preview-image" @error="imageFailed"/>
   <iframe v-else-if="url&&(type.includes('pdf')||type.startsWith('text/'))" :src="url" class="preview-frame" :title="uiText('附件预览')"/>
   <el-empty v-else :description="uiText('此格式请下载查看')"/>
  </div>
  <details class="preview-file-info"><summary>{{ uiText('原文件信息') }}</summary><div><span class="original-file-name">{{current.name||uiText('原文件')}}</span><span v-if="current.version">{{ uiText('版本') }} {{current.version}}</span><el-button v-if="metadata&&!unsupported&&!error" link :loading="downloading" @click="download">{{ uiText('下载原文件') }}</el-button><span v-if="downloadError&&!unsupported" role="alert">{{ uiText('原件下载失败') }}：{{downloadError}}</span></div></details>
 </el-dialog>
</template>
<style scoped>
.preview-nav{display:flex;justify-content:center;align-items:center;gap:12px;flex:none;font-size:13px}.preview-stage{display:flex;align-items:center;justify-content:center;min-height:0;height:min(70dvh,760px);max-height:calc(100dvh - 190px);background:var(--el-fill-color-lighter);border-radius:4px}.preview-image{display:block;width:100%;height:100%;object-fit:contain}.preview-frame{width:100%;height:100%;border:0;background:#fff}.preview-state{width:100%;min-height:180px;display:flex;align-items:center;justify-content:center;gap:12px;flex-wrap:wrap;color:var(--el-text-color-secondary);padding:12px;line-height:20px}.preview-file-info{flex:none;font-size:12px;color:var(--el-text-color-secondary)}.preview-file-info summary{cursor:pointer;line-height:24px}.preview-file-info>div{display:flex;align-items:center;flex-wrap:wrap;gap:5px 12px}.original-file-name{overflow-wrap:anywhere;min-width:0}.preview-file-info [role=alert]{color:var(--el-color-danger)}:global(.partner-file-preview){max-height:calc(100dvh - 32px);display:flex;flex-direction:column;margin-bottom:16px}:global(.partner-file-preview>.el-dialog__body){display:flex;flex-direction:column;gap:8px;min-height:0;overflow:auto}:global(.partner-file-preview .el-dialog__title){font-size:15px;overflow-wrap:anywhere}@media(max-width:600px){.preview-stage{height:65dvh}.preview-nav{gap:8px}.preview-file-info>div{align-items:flex-start;flex-direction:column}}
</style>
