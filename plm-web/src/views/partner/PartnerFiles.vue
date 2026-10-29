<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {useElementSize} from '@vueuse/core'
import {ElMessage} from 'element-plus'
import {uploadPartnerFile,fileBlob,saveBlob} from '@/api/modules/partner'
import type {PartnerKind,PartnerFile,BusinessData} from '@/types/partner'
import PartnerPhoto from './PartnerPhoto.vue'
import {useBlobPreview} from '@/composables/useBlobPreview'
import {attachmentPreviewKey} from '@/utils/authorizedBlobQueue'
const props=defineProps<{kind:PartnerKind|'partner-selections'|'sample-shipments'|'sample-identities';id:number;files:PartnerFile[];modelValue:BusinessData[];canWrite:boolean;canWriteMoney:boolean;compact?:boolean;beforeUpload?:()=>Promise<boolean>}>()
const emit=defineEmits<{(e:'update:modelValue',v:BusinessData[]):void;(e:'changed'):void;(e:'uploading',v:boolean):void}>()
const writable=computed(()=>props.canWrite&&props.kind!=='sample-identities')
const busy=ref(false),purpose=ref('material'),previous=ref<number>(),version=ref('V1'),reason=ref(''),previewOpen=ref(false),previewFile=ref<PartnerFile>()
const {url:preview,type:previewType,loading:previewLoading,error:previewError,retry:retryPreview,clear:clearPreview,imageFailed}=useBlobPreview(()=>{
 const id=previewOpen.value?previewFile.value?.id:undefined
 return id?{key:attachmentPreviewKey(id),fetch:(signal:AbortSignal)=>fileBlob(id,true,{signal})}:undefined
},{imageOnly:false,priority:true})
const filesRoot=ref<HTMLElement>(),{width}=useElementSize(filesRoot)
const settingsOpen=ref(false),page=ref(1),pageSize=computed(()=>props.compact?(width.value>0&&width.value<=440?1:width.value>0&&width.value<=740?2:3):6),visibleFiles=computed(()=>props.files.slice((page.value-1)*pageSize.value,page.value*pageSize.value))
watch(()=>props.id,()=>{page.value=1;settingsOpen.value=false})
watch([()=>props.files.length,pageSize],([total])=>{page.value=Math.min(page.value,Math.max(1,Math.ceil(total/pageSize.value)))})
function closePreview(){previewOpen.value=false;previewFile.value=undefined;clearPreview()}
function adopt(file:PartnerFile,checked:boolean){const ids=props.modelValue.filter(x=>x.id!==file.id);if(checked)ids.push({id:file.id,type:'file',name:file.name,version:file.version});emit('update:modelValue',ids)}
async function upload(file:File){const kind=props.kind,id=props.id;if(!writable.value||kind==='sample-identities')return false;busy.value=true;emit('uploading',true);try{if(props.beforeUpload&&!await props.beforeUpload())return false;await uploadPartnerFile(kind,id,file,{purpose:purpose.value==='material'&&file.type.startsWith('image/')?'photo':purpose.value,previous:previous.value,version:version.value,reason:reason.value});page.value=Math.ceil((props.files.length+1)/pageSize.value);emit('changed');ElMessage.success('资料已上传；采用哪些版本请核对后保存')}finally{busy.value=false;emit('uploading',false)}return false}
async function drop(event:DragEvent){if(!writable.value||busy.value)return;for(const file of Array.from(event.dataTransfer?.files??[])){try{await upload(file)}catch{ElMessage.error('上传未完成；已上传资料保留，请重试未完成的文件');break}}}
function open(file:PartnerFile){previewFile.value=file;previewOpen.value=true}
async function download(file:PartnerFile){saveBlob(await fileBlob(file.id,false),file.name)}
watch(()=>props.id,closePreview)
</script>
<template><section ref="filesRoot" class="partner-files" :class="{'partner-files--compact':compact}" @dragover.prevent @drop.prevent="drop"><header><h3>图片与附件 <small>（{{files.length}} 份）</small></h3><template v-if="writable"><small class="drop-hint">可拖入上传</small><el-upload :show-file-list="false" :before-upload="upload" :disabled="busy" accept=".png,.jpg,.jpeg,.webp"><el-button size="small" :loading="busy">上传图片</el-button></el-upload><el-upload :show-file-list="false" :before-upload="upload" :disabled="busy"><el-button size="small" :loading="busy">上传附件</el-button></el-upload><el-button v-if="compact" size="small" link :aria-expanded="settingsOpen" @click="settingsOpen=!settingsOpen">{{settingsOpen?'收起设置':'上传设置'}}</el-button></template><slot name="actions"/></header>
 <p v-if="!files.some(file=>file.type?.startsWith('image/'))" class="no-photo">{{writable?'暂无图片；请在本处上传首张实际图片。已有附件名称、版本在下方直接展示。':'暂无图片；已有附件名称、版本在下方直接展示。'}}</p>
 <div v-if="visibleFiles.length" class="file-grid"><div v-for="file in visibleFiles" :key="file.id" class="file-row"><PartnerPhoto v-if="file.type?.startsWith('image/')" :id="file.id" allow-retry/><div class="file-content"><span class="file-name">{{file.name}} · {{file.version}}</span><div class="file-actions"><el-checkbox v-if="writable" :model-value="modelValue.some(x=>x.id===file.id)" @change="adopt(file,Boolean($event))">采用</el-checkbox><el-button link @click="open(file)">预览</el-button><el-button link @click="download(file)">下载</el-button></div></div></div></div>
 <el-pagination v-if="files.length>pageSize" v-model:current-page="page" :page-size="pageSize" :total="files.length" :pager-count="5" layout="prev,pager,next"/>
 <div v-if="writable&&(!compact||settingsOpen)" class="file-tools"><el-select v-model="purpose" aria-label="上传资料类别" size="small"><el-option label="产品／质量资料" value="material"/><el-option label="参考图片" value="photo"/><el-option v-if="canWriteMoney" label="金额／结算凭据" value="financial"/></el-select></div>
 <details v-if="writable&&(!compact||settingsOpen)" :open="compact"><summary>上传新版</summary><div class="file-tools"><el-select v-model="previous" filterable clearable placeholder="选择原资料"><el-option v-for="file in files" :key="file.id" :label="file.name+' · '+file.version" :value="file.id"/></el-select><el-input v-model="version" placeholder="版本号"/><el-input v-model="reason" placeholder="修改原因"/></div></details>
 <el-dialog :model-value="previewOpen" :title="previewFile?.name||'图片与附件预览'" width="min(850px,94vw)" append-to-body @close="closePreview"><div v-if="previewLoading" class="preview-state" role="status" aria-live="polite">正在加载预览，请稍候…</div><div v-else-if="previewError" class="preview-state" role="alert"><span>{{previewError}}</span><el-button type="primary" @click="retryPreview">重新加载</el-button></div><img v-else-if="preview&&previewType.startsWith('image/')" :src="preview" :alt="previewFile?.name" class="preview-image" @error="imageFailed"/><iframe v-else-if="preview&&(previewType.includes('pdf')||previewType.startsWith('text/'))" :src="preview" class="preview-frame"/><el-empty v-else description="此格式请下载查看"/></el-dialog>
 </section></template>
<style scoped>
.file-content{flex:1;min-width:0}.file-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.file-actions :deep(.el-checkbox){height:24px;margin-right:0}.file-content .file-name{display:block;min-width:0;line-height:1.5}.file-actions :deep(.el-button+.el-button){margin-left:0}.preview-state{min-height:180px;display:flex;align-items:center;justify-content:center;gap:12px;flex-wrap:wrap;color:#64748b}
.partner-files--compact{padding:0;line-height:1.5}.partner-files--compact header{gap:6px}.partner-files--compact h3{font-size:13px}.partner-files--compact .file-grid{grid-template-columns:repeat(3,minmax(0,1fr));gap:6px 10px;margin-top:5px}.partner-files--compact .file-row{flex-wrap:nowrap;padding:3px 6px;border:1px solid #e8edf4;border-radius:5px}.partner-files--compact .no-photo{margin:3px 0 0}.partner-files--compact :deep(.partner-photo){width:48px;height:48px}.partner-files--compact :deep(.el-pagination){margin-top:3px;justify-content:flex-end}.partner-files--compact .file-actions :deep(.el-button){font-size:12px}
@container(max-width:740px){.partner-files--compact .file-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@container(max-width:440px){.partner-files--compact .file-grid{grid-template-columns:minmax(0,1fr)}}
</style>
<style scoped>.partner-files{padding:4px 0;line-height:1.7;min-width:0;container-type:inline-size}header{display:flex;gap:8px;align-items:center;flex-wrap:wrap}h3{font-size:14px;margin:0;flex:1}small,.no-photo{font-size:12px;color:#708297}.no-photo{margin:6px 0}summary{cursor:pointer;color:#60748f;font-size:12px}.file-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px 12px}.file-row,.file-tools{display:flex;gap:7px;align-items:center;flex-wrap:wrap;padding:5px 0;min-width:0}.file-name{flex:1;min-width:90px;overflow-wrap:anywhere;font-size:13px}.file-tools>*{max-width:100%;min-width:0}.file-tools .el-select{width:min(230px,100%)}.file-tools .el-input{max-width:220px}.preview-image{display:block;max-width:100%;max-height:65vh;object-fit:contain;margin:auto}.preview-frame{width:100%;height:65vh;border:0}@container(max-width:500px){.file-grid{grid-template-columns:minmax(0,1fr)}}</style>
