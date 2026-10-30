<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {t as uiText} from '@/locales'
import {materialKey,type DevelopmentMaterial,type MaterialReference} from '@/api/modules/productResearch'
import {product3dKind,type Product3dKind} from '@/utils/product3dMaterials'
const props=defineProps<{materials:DevelopmentMaterial[];selected:string[];savedReferences:MaterialReference[];uploadable:boolean;selectable:boolean;busy:boolean}>()
const emit=defineEmits<{'update:selected':[string[]];upload:[Product3dKind];view:[DevelopmentMaterial]}>()
const open=ref(false),draft=ref<string[]>([])
const candidates=computed(()=>props.materials.filter(material=>product3dKind(material)))
const groups=computed(()=>[{kind:'source' as const,name:'3D源文件',files:candidates.value.filter(material=>product3dKind(material)==='source')},{kind:'render' as const,name:'3D效果图（工程师指定）',files:candidates.value.filter(material=>product3dKind(material)==='render')}])
const saved=computed(()=>new Set(props.savedReferences.map(materialKey)))
function state(material:DevelopmentMaterial){const key=materialKey(material),selected=props.selected.includes(key);return saved.value.has(key)?selected?'已保存采用':'本次待保存取消':selected?'本次待保存采用':'已上传未采用'}
function choose(){if(props.busy||!props.selectable)return;draft.value=props.selected.filter(key=>candidates.value.some(file=>materialKey(file)===key));open.value=true}
function apply(){if(props.busy||!props.selectable)return;const available=new Set(candidates.value.map(materialKey));emit('update:selected',[...props.selected.filter(key=>!available.has(key)),...draft.value.filter(key=>available.has(key))]);open.value=false}
watch(()=>props.selectable,value=>{if(!value)open.value=false})
</script>
<template>
 <section class="product-3d-materials" data-readiness-target="product3d" :aria-label="uiText('3D产品图资料')">
  <header><div><h3>{{uiText('3D产品图')}}</h3><span>{{uiText('可后补，不影响本步')}}</span></div><div class="three-d-actions"><el-button v-if="uploadable" data-readiness-focus="product3d-upload" :disabled="busy" @click="emit('upload','source')">{{uiText('上传3D源文件')}}</el-button><el-button v-if="uploadable" :disabled="busy" @click="emit('upload','render')">{{uiText('上传3D效果图')}}</el-button><el-button :disabled="busy||!selectable||!candidates.length" @click="choose">{{uiText('选择已有3D资料')}}</el-button></div></header>
  <p class="three-d-note">{{uiText('源文件与效果图分开登记；效果图须由工程师确认用途，不替代CAD源文件或样品实拍。')}}</p>
  <div v-for="group in groups" :key="group.kind" class="three-d-group" :data-3d-kind="group.kind"><strong>{{uiText(group.name)}}</strong><ul v-if="group.files.length"><li v-for="file in group.files" :key="file.id" :data-file="file.id"><button type="button" class="file-version" @click="emit('view',file)">{{file.name}} · {{file.versionNo}}</button><span :class="{'is-pending':state(file).includes('待保存')}">{{uiText(state(file))}}</span></li></ul><span v-else class="muted">{{uiText('尚未上传，可后补')}}</span></div>
  <p class="three-d-note">{{uiText('本次选择需保存草稿或完成本步后才会采用；普通图片和原审批附件不会自动作为3D产品图。')}}</p>
  <el-dialog v-model="open" :title="uiText('选择已有3D资料')" width="min(680px,94vw)" append-to-body destroy-on-close><p>{{uiText('只列出明确分类的3D源文件和工程师指定效果图，按真实文件版本选择。')}}</p><el-checkbox-group v-model="draft" :disabled="busy||!selectable"><section v-for="group in groups" :key="group.kind" class="three-d-choices"><h4>{{uiText(group.name)}}</h4><el-checkbox v-for="file in group.files" :key="file.id" :value="materialKey(file)">{{file.name}} · {{file.versionNo}}</el-checkbox><p v-if="!group.files.length">{{uiText('暂无合格资料')}}</p></section></el-checkbox-group><template #footer><el-button :disabled="busy" @click="open=false">{{uiText('取消')}}</el-button><el-button type="primary" :disabled="busy||!selectable" @click="apply">{{uiText('确认本次选择')}}</el-button></template></el-dialog>
 </section>
</template>
<style scoped>
.product-3d-materials{font-size:12px;padding:10px 0;margin-bottom:12px;border-bottom:1px solid var(--el-border-color-lighter);min-width:0}.product-3d-materials header,.product-3d-materials header>div,.three-d-actions{display:flex;align-items:center;flex-wrap:wrap;gap:6px 10px}.product-3d-materials header{justify-content:space-between}.product-3d-materials h3{font-size:14px;margin:0}.three-d-actions .el-button+.el-button{margin-left:0}.three-d-note,.muted,.three-d-group li>span,.product-3d-materials header span{color:var(--el-text-color-secondary)}.three-d-note{margin:6px 0;line-height:18px}.three-d-group{display:flex;gap:6px 12px;align-items:baseline;margin:8px 0;min-width:0}.three-d-group>strong{min-width:130px;flex-shrink:0;font-weight:600}.three-d-group ul{padding:0;margin:0;list-style:none;min-width:0}.three-d-group li{display:flex;align-items:baseline;flex-wrap:wrap;gap:4px 10px;line-height:22px;overflow-wrap:anywhere}.file-version{font:inherit;color:var(--el-color-primary);border:0;background:none;padding:0;text-align:left;cursor:pointer;overflow-wrap:anywhere}.three-d-group .is-pending{color:var(--el-color-warning)}.three-d-choices h4{margin:8px 0}.three-d-choices .el-checkbox{display:flex;height:auto;min-height:28px;white-space:normal}.three-d-choices :deep(.el-checkbox__label){white-space:normal;overflow-wrap:anywhere}
@container(max-width:600px){.three-d-group{flex-direction:column;gap:4px}}
</style>
