<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,ref,watch} from 'vue'
import {downloadMoldFile,type MoldFile} from '@/api/modules/moldApplications'
import {useBlobPreview} from '@/composables/useBlobPreview'
import {useUserStore} from '@/stores/user'
import {saveBlob} from '@/utils/file'
const props=defineProps<{applicationId:number;file:MoldFile;active:boolean;compact?:boolean}>()
const user=useUserStore(),working=ref(false),actionError=ref(''),previewOpen=ref(false)
const image=computed(()=>props.file.contentType?.startsWith('image/'))
const archived=computed(()=>props.file.downloadStatus==='downloaded')
const archiveStatus=computed(()=>archived.value?'已归档':props.file.downloadStatus==='pending_upload'?'本地文件待上传完成':props.file.downloadStatus==='download_failed'?'原审批附件归档失败':'原审批附件待归档')
const fileSource=()=>props.active&&user.token&&archived.value?{key:`/mold-applications/${props.applicationId}/files/${props.file.attachmentId}`,fetch:(signal:AbortSignal)=>downloadMoldFile(props.applicationId,props.file.attachmentId,signal)}:undefined
const preview=useBlobPreview(()=>image.value?fileSource():undefined)
const fullPreview=useBlobPreview(()=>previewOpen.value?fileSource():undefined,{imageOnly:false,priority:true})
watch(()=>user.token,()=>{preview.clear();if(user.token&&props.active)void preview.retry()})
watch(()=>[user.token,props.active,props.applicationId,props.file.attachmentId],()=>{previewOpen.value=false})
async function fileAction(){
 if(working.value||!archived.value||!props.active)return;
 const auth=user.token,id=props.applicationId,file=props.file;
 working.value=true;actionError.value='';
 try{const blob=await downloadMoldFile(id,file.attachmentId);if(auth!==user.token||id!==props.applicationId||file.attachmentId!==props.file.attachmentId||!props.active)return;saveBlob(blob,file.fileName);}
 catch(error){if(auth===user.token&&id===props.applicationId&&props.active)actionError.value=error instanceof Error?error.message:'文件读取失败，请重试';}
 finally{working.value=false;}
}
</script>
<template>
 <article class="application-file" :class="{'application-file--compact':compact}" :aria-label="file.fileName">
  <div v-if="image&&archived" class="file-picture">
   <el-image v-if="preview.url.value" :src="preview.url.value" :preview-src-list="[preview.url.value]" fit="contain" :alt="file.fileName" @error="preview.imageFailed"/>
   <span v-else-if="preview.loading.value">{{ uiText("图片加载中…") }}</span>
   <div v-else-if="preview.error.value"><span>{{uiText(preview.error.value)}}</span><el-button link @click="preview.retry">{{ uiText("重试图片") }}</el-button></div>
  </div>
  <div class="file-description"><strong>{{file.fileName}}</strong><small>{{uiText(archiveStatus)}}</small><p v-if="!archived&&file.archiveError" role="alert">{{file.archiveError}}</p><div class="file-actions"><el-button v-if="archived&&(image||file.contentType==='application/pdf')" link @click="previewOpen=true">{{ uiText("预览") }}</el-button><el-button v-if="archived" link :loading="working" @click="fileAction()">{{ uiText("下载") }}</el-button></div><p v-if="actionError" role="alert">{{uiText(actionError)}}</p></div>
  <el-dialog v-model="previewOpen" :title="file.fileName" width="min(960px,94vw)" align-center append-to-body destroy-on-close>
   <div class="file-preview-body"><el-image v-if="fullPreview.url.value&&image" :src="fullPreview.url.value" :alt="file.fileName" fit="contain" @error="fullPreview.imageFailed"/><iframe v-else-if="fullPreview.url.value" :src="fullPreview.url.value" :title="file.fileName"/><span v-else-if="fullPreview.loading.value">{{ uiText("预览加载中…") }}</span><div v-else-if="fullPreview.error.value" role="alert"><span>{{uiText(fullPreview.error.value)}}</span><el-button link @click="fullPreview.retry">{{ uiText("重试预览") }}</el-button></div></div>
   <template #footer><el-button @click="previewOpen=false">{{ uiText("关闭") }}</el-button><el-button :loading="working" @click="fileAction()">{{ uiText("下载") }}</el-button></template>
  </el-dialog>
 </article>
</template>
<style scoped>
.application-file{display:flex;align-items:center;gap:10px;border:1px solid var(--el-border-color-lighter);padding:8px;min-width:0;font-size:12px}.file-picture{width:112px;height:84px;flex:0 0 112px;display:flex;align-items:center;justify-content:center;background:var(--el-fill-color-light);text-align:center}.file-picture .el-image{width:100%;height:100%}.file-description{min-width:0;overflow-wrap:anywhere}.file-description strong{font-weight:500}.file-description small{display:block;color:var(--el-text-color-secondary);margin-top:4px}.file-actions{display:flex;gap:8px}.file-actions .el-button+.el-button{margin-left:0}.file-description p{color:var(--el-color-danger);margin:4px 0}
.application-file--compact{flex-direction:column;align-items:stretch;gap:6px}.application-file--compact .file-picture{width:100%;height:128px;flex:0 0 128px}.application-file--compact .file-description{width:100%}
.file-preview-body{display:flex;align-items:center;justify-content:center;min-height:180px;max-height:70vh}.file-preview-body>.el-image{width:100%;height:65vh}.file-preview-body>iframe{width:100%;height:65vh;border:0}.file-preview-body>[role=alert]{color:var(--el-color-danger)}
</style>
