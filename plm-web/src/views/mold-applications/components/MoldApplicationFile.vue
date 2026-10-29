<script setup lang="ts">
import {computed,ref} from 'vue'
import {downloadMoldFile,type MoldFile} from '@/api/modules/moldApplications'
import {useBlobPreview} from '@/composables/useBlobPreview'
import {useUserStore} from '@/stores/user'
import {openBlobPreview,saveBlob} from '@/utils/file'
const props=defineProps<{applicationId:number;file:MoldFile;active:boolean}>()
const user=useUserStore(),working=ref(false),actionError=ref('')
const image=computed(()=>props.file.contentType?.startsWith('image/'))
const archived=computed(()=>props.file.downloadStatus==='downloaded')
const preview=useBlobPreview(()=>props.active&&user.token&&image.value&&archived.value?{key:`/mold-applications/${props.applicationId}/files/${props.file.attachmentId}`,fetch:signal=>downloadMoldFile(props.applicationId,props.file.attachmentId,signal)}:undefined)
async function fileAction(view=false){
 if(working.value||!archived.value||!props.active)return;
 const auth=user.token,id=props.applicationId,file=props.file;
 working.value=true;actionError.value='';
 try{const blob=await downloadMoldFile(id,file.attachmentId);if(auth!==user.token||id!==props.applicationId||file.attachmentId!==props.file.attachmentId||!props.active)return;if(view)openBlobPreview(blob);else saveBlob(blob,file.fileName);}
 catch(error){if(auth===user.token&&id===props.applicationId&&props.active)actionError.value=error instanceof Error?error.message:'文件读取失败，请重试';}
 finally{working.value=false;}
}
</script>
<template>
 <article class="application-file" :aria-label="file.fileName">
  <div v-if="image&&archived" class="file-picture">
   <el-image v-if="preview.url.value" :src="preview.url.value" :preview-src-list="[preview.url.value]" fit="contain" :alt="file.fileName" @error="preview.imageFailed"/>
   <span v-else-if="preview.loading.value">图片加载中…</span>
   <div v-else-if="preview.error.value"><span>{{preview.error.value}}</span><el-button link @click="preview.retry">重试图片</el-button></div>
  </div>
  <div class="file-description"><strong>{{file.fileName}}</strong><small>{{archived?'已归档':'待上传完成'}}</small><div class="file-actions"><el-button v-if="archived&&(image||file.contentType==='application/pdf')" link :loading="working" @click="fileAction(true)">预览</el-button><el-button v-if="archived" link :loading="working" @click="fileAction()">下载</el-button></div><p v-if="actionError" role="alert">{{actionError}}</p></div>
 </article>
</template>
<style scoped>
.application-file{display:flex;align-items:center;gap:10px;border:1px solid var(--el-border-color-lighter);padding:8px;min-width:0;font-size:12px}.file-picture{width:112px;height:84px;flex:0 0 112px;display:flex;align-items:center;justify-content:center;background:var(--el-fill-color-light);text-align:center}.file-picture .el-image{width:100%;height:100%}.file-description{min-width:0;overflow-wrap:anywhere}.file-description strong{font-weight:500}.file-description small{display:block;color:var(--el-text-color-secondary);margin-top:4px}.file-actions{display:flex;gap:8px}.file-actions .el-button+.el-button{margin-left:0}.file-description p{color:var(--el-color-danger);margin:4px 0}
</style>
