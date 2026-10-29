<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Picture } from '@element-plus/icons-vue';
import { materialKey, type DevelopmentMaterial } from '@/api/modules/productResearch';
import { ENGINEERING_UPLOAD_ACCEPT, fileCategoryLabel, formatFileSize } from '@/utils/file';
import DocumentPreview from '@/views/file/components/DocumentPreview.vue';
const props=defineProps<{materials:DevelopmentMaterial[];selected:string[];editable:boolean;selectable:boolean;busy:boolean;uploadStageName?:string;hideEngineering?:boolean}>();
const emit=defineEmits<{'update:selected':[string[]];upload:[DevelopmentMaterial];files:[File[]];currentStage:[];view:[DevelopmentMaterial];download:[DevelopmentMaterial];remove:[DevelopmentMaterial];engineering:[string];refresh:[]}>();
const filter=ref('all');
const fileInput=ref<HTMLInputElement|null>(null),dragDepth=ref(0);
const canUpload=computed(()=>props.editable&&!props.busy);
watch(canUpload,()=>{dragDepth.value=0;});
function pickFiles(){if(canUpload.value)fileInput.value?.click();}
function selectedFiles(event:Event){const input=event.target as HTMLInputElement;const files=Array.from(input.files||[]);input.value='';if(canUpload.value&&files.length)emit('files',files);}
function isFileDrag(event:DragEvent){return !!(event.dataTransfer?.files.length||Array.from(event.dataTransfer?.types||[]).includes('Files'));}
function dragEnter(event:DragEvent){if(!isFileDrag(event))return;event.preventDefault();if(canUpload.value)dragDepth.value++;}
function dragOver(event:DragEvent){if(!isFileDrag(event))return;event.preventDefault();if(event.dataTransfer)event.dataTransfer.dropEffect=canUpload.value?'copy':'none';}
function dragLeave(){dragDepth.value=Math.max(0,dragDepth.value-1);}
function dropFiles(event:DragEvent){if(!isFileDrag(event))return;event.preventDefault();dragDepth.value=0;const files=Array.from(event.dataTransfer?.files||[]);if(canUpload.value&&files.length)emit('files',files);}

const files=computed(()=>props.materials.filter(m=>m.type==='file'));
const images=computed(()=>files.value.filter(m=>m.attachment&&['jpg','jpeg','png','webp'].includes(m.attachment.fileExt.toLowerCase())));
const rows=computed(()=>props.materials.filter(m=>filter.value==='all'||(filter.value==='images'?images.value.includes(m):filter.value==='files'?m.type==='file'&&!images.value.includes(m):['bom','process'].includes(m.type))));
function choose(m:DevelopmentMaterial,checked:unknown){const key=materialKey(m);emit('update:selected',checked?[...new Set([...props.selected,key])]:props.selected.filter(k=>k!==key));}
const status=(s:string)=>({draft:'草稿',released:'正式',locked:'已锁定',frozen:'已冻结',reviewing:'评审中'} as Record<string,string>)[s]||s;
</script>
<template>
 <aside class="research-materials stage-materials" :class="{'is-dragging':dragDepth>0}" aria-label="本阶段图片与附件" data-readiness-target="drawings" @dragenter="dragEnter" @dragover="dragOver" @dragleave="dragLeave" @drop="dropFiles">
  <input v-if="editable" ref="fileInput" class="local-file-input" type="file" multiple :accept="ENGINEERING_UPLOAD_ACCEPT" :disabled="busy" aria-label="选择本地图片或附件" @change="selectedFiles"/>
  <header class="section-heading"><div><h3>图片与附件</h3><span>{{files.length}} 个文件版本 · {{images.length}} 张图片 · 本阶段采用 {{selected.length}} 份</span></div><div class="material-actions"><el-button link :disabled="busy" @click="emit('refresh')">刷新</el-button><el-button v-if="editable" data-readiness-focus="upload" :disabled="busy" type="primary" @click="pickFiles">从本地上传</el-button><el-button v-else-if="uploadStageName" type="primary" :disabled="busy" @click="emit('currentStage')">前往{{uploadStageName}}上传</el-button><el-button v-if="!hideEngineering" link :disabled="busy" @click="emit('engineering','bom')">{{editable?'维护／确认 BOM':'查看 BOM'}}</el-button><el-button v-if="!hideEngineering" link :disabled="busy" @click="emit('engineering','process')">{{editable?'维护工艺路线':'查看工艺'}}</el-button></div></header>
  <slot name="notice"/>
  <p v-if="!editable" class="upload-readonly-note">{{uploadStageName?`本页仅查看，上传请前往当前阶段“${uploadStageName}”。`:'当前资料只读；图片和附件仍可预览、下载。'}}</p>
  <div class="material-body">
   <div class="material-images">
    <div v-if="images.length" class="image-gallery"><article v-for="m in images.slice(0,4)" :key="m.id" class="image-tile"><DocumentPreview :file="m.attachment!" compact/><div class="image-caption"><span>{{m.name}} · {{m.versionNo}}</span><el-button link :disabled="busy" :aria-label="`查看大图 ${m.name} ${m.versionNo}`" @click="emit('view',m)">查看大图</el-button></div></article><p v-if="images.length>4">共 {{images.length}} 张图片，完整版本保留在资料清单。</p></div>
    <div v-if="!images.length" class="image-placeholder"><el-icon :size="22"><Picture/></el-icon><span>暂无图片</span></div>
   </div>
   <section class="material-versions" aria-label="附件与工程资料版本">
    <el-radio-group v-model="filter" class="material-filter" size="small" aria-label="资料类型"><el-radio-button value="all">全部资料</el-radio-button><el-radio-button value="images">图片</el-radio-button><el-radio-button value="files">附件</el-radio-button><el-radio-button v-if="!hideEngineering" value="engineering">BOM／工艺</el-radio-button></el-radio-group>
    <p v-if="!selectable" class="muted">采用版本在对应评审中选择；已确认依据继续保留。</p>
    <article v-for="m in rows" :key="materialKey(m)" class="material-row">
     <el-checkbox :model-value="selected.includes(materialKey(m))" :disabled="!selectable||busy" @change="choose(m,$event)"><strong>{{m.name}}</strong><span> · {{m.versionNo||'版本待补'}}</span></el-checkbox>
     <div class="material-meta">{{m.category?fileCategoryLabel(m.category):m.type==='bom'?'BOM':m.type==='process'?'工艺':'链接'}} · {{status(m.status)}}<span v-if="m.attachment"> · {{formatFileSize(m.attachment.fileSize)}}</span></div>
     <div class="row-actions"><el-button link :disabled="busy" @click="emit('view',m)">预览／详情</el-button><el-button v-if="m.type==='file'" link :disabled="busy" @click="emit('download',m)">下载</el-button><el-button v-if="editable&&m.type==='file'" link :disabled="busy" @click="emit('upload',m)">上传新版</el-button><el-button v-if="editable&&m.type==='file'&&m.status==='draft'" link type="danger" :disabled="busy" @click="emit('remove',m)">删除草稿</el-button></div>
    </article>
    <el-empty v-if="!rows.length" :image-size="32" :description="materials.length?'当前类型暂无资料':'暂无附件，可直接上传。'"/>
   </section>
  </div>
  <div v-if="editable" class="material-drop-zone" role="button" :tabindex="busy?-1:0" :aria-disabled="busy" aria-label="拖拽或从本地上传图片及附件" @click="pickFiles" @keydown.enter.prevent="pickFiles" @keydown.space.prevent="pickFiles"><el-icon :size="16"><Picture/></el-icon><strong>{{dragDepth?'松开以选择图片或附件':'将图片或附件拖到这里'}}</strong><span>或点击“从本地上传”</span><span class="upload-limit">每次最多 20 份 · 单份不超过 200 MB</span></div>
 </aside>
</template>
<style scoped>
.research-materials{min-width:0;border-bottom:1px solid var(--el-border-color-lighter);background:var(--el-bg-color);font-size:12px;container-type:inline-size;padding:18px 0;margin:0}
.research-materials>header{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:8px;margin-bottom:12px;padding:0;border-bottom:0}
header>div:first-child{display:flex;align-items:baseline;gap:10px;flex-wrap:wrap;min-width:0}
header h3{font-size:14px;font-weight:700;line-height:1.5;margin:0}
header span,.muted,.material-meta,.image-gallery>p{color:var(--el-text-color-secondary);overflow-wrap:anywhere}
.material-actions,.row-actions{display:flex;align-items:center;gap:6px;flex-wrap:wrap}
.material-body{display:grid;grid-template-columns:minmax(88px,.6fr) minmax(0,2fr);gap:16px;align-items:start;padding:0}
.material-images,.material-versions{min-width:0}
.material-images:has(.image-placeholder){max-width:88px}
.material-body:has(.image-placeholder){grid-template-columns:88px minmax(0,1fr)}
.image-gallery{display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:8px}
.image-tile{min-width:0;border:1px solid var(--el-border-color-lighter);border-radius:4px;overflow:hidden}
.image-gallery :deep(.document-preview--compact){height:120px}
.image-gallery :deep(.document-preview--compact img){height:120px;max-height:120px}
.image-caption{padding:4px 6px;display:flex;gap:4px;align-items:flex-start;flex-wrap:wrap;line-height:18px;overflow-wrap:anywhere}
.image-caption>span{flex:1;min-width:80px;color:var(--el-text-color-regular)}
.image-caption>.el-button{padding:0;min-height:18px}
.image-gallery>p{grid-column:1/-1;margin:0}
.image-placeholder{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:7px;min-height:88px;height:88px;width:88px;padding:8px;background:var(--el-fill-color-extra-light);border:1px dashed var(--el-border-color);border-radius:4px;text-align:center;color:var(--el-text-color-secondary);line-height:20px}
.material-filter{display:flex;flex-wrap:wrap;margin-bottom:6px}
.material-filter :deep(.el-radio-button__inner){font-size:12px;padding:5px 8px}
.material-row{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:2px 12px;padding:7px 0;border-bottom:1px solid var(--el-border-color-lighter);min-width:0;overflow-wrap:anywhere}
.material-row>.el-checkbox{height:auto;min-height:22px;max-width:100%;white-space:normal;align-items:flex-start}
.material-row :deep(.el-checkbox__label){white-space:normal;overflow-wrap:anywhere;line-height:18px}
.material-row :deep(.el-checkbox__input){margin-top:3px}
.material-row :deep(.el-checkbox__label strong){font-weight:600}
.material-meta{display:flex;flex-wrap:wrap;grid-column:1;grid-row:2;margin:0 0 0 22px}
.row-actions{grid-column:2;grid-row:1/3;justify-content:flex-end;align-self:center}
.research-materials :deep(.el-button){font-size:12px;min-height:28px;height:auto;margin-left:0}
.local-file-input{display:none}
.material-drop-zone{display:flex;align-items:center;justify-content:center;flex-wrap:wrap;gap:6px 10px;min-height:36px;padding:6px 10px;margin-top:12px;border:1px dashed var(--el-border-color);border-radius:4px;background:var(--el-fill-color-extra-light);color:var(--el-text-color-secondary);cursor:pointer;text-align:center;line-height:20px}
.material-drop-zone strong{font-weight:500;color:var(--el-text-color-regular)}
.material-drop-zone:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}
.material-drop-zone[aria-disabled=true]{cursor:default;opacity:.65}
.upload-limit{font-size:12px}
.upload-readonly-note{margin:0 0 12px;padding:8px 10px;background:var(--el-fill-color-extra-light);color:var(--el-text-color-secondary)}
.research-materials.is-dragging{outline:2px dashed var(--el-color-primary);outline-offset:-2px}
.research-materials.is-dragging .material-drop-zone{border-color:var(--el-color-primary);background:var(--el-color-primary-light-9);color:var(--el-color-primary)}
@container(max-width:750px){.material-body{grid-template-columns:minmax(0,1fr)}.image-gallery{grid-template-columns:repeat(2,minmax(0,1fr))}.material-row{grid-template-columns:minmax(0,1fr)}.row-actions{grid-column:1;grid-row:auto;justify-content:flex-start;margin-left:22px}}
@container(max-width:420px){.material-body:has(.image-placeholder){grid-template-columns:minmax(0,1fr)}.image-placeholder{min-height:48px;height:48px;flex-direction:row;width:100%}.material-images:has(.image-placeholder){max-width:none}}
</style>
