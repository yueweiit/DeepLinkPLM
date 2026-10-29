<script setup lang="ts">
import {ref,watch,onBeforeUnmount} from 'vue'
import {useUserStore} from '@/stores/user'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {createCodeItem,updateCodeItem,inspectCodeItem,codeItemAction,type CodeItem,type CodeItemPayload} from '@/api/modules/code'
import MasterDataLifecycleDialog from '@/components/MasterDataLifecycleDialog.vue'
import CodeItemDialog from './CodeItemDialog.vue'
const props=defineProps<{codeType:'color'|'phone_model';editable:boolean}>()
const emit=defineEmits<{changed:[]}>()
const user=useUserStore(),router=useRouter(),editing=ref<CodeItem|null>(null),editor=ref(false),busy=ref(false),detail=ref<CodeItem>(),action=ref<'delete'|'deactivate'|'activate'>('delete'),target=ref<CodeItem>(),failure=ref('')
let generation=0
function open(item?:CodeItem){if(busy.value)return;failure.value='';editing.value=item||null;editor.value=true}
function show(item:CodeItem){detail.value=item}
function lifecycle(item:CodeItem,next:'delete'|'deactivate'|'activate'){target.value=item;action.value=next}
async function save(payload:CodeItemPayload){if(busy.value||!props.editable)return;const epoch=++generation,token=user.token;busy.value=true;failure.value='';try{const saved=editing.value?await updateCodeItem(editing.value.codeItemId,payload):await createCodeItem(payload);if(epoch!==generation||token!==user.token)return;if(detail.value?.codeItemId===saved.codeItemId)detail.value=saved;editor.value=false;emit('changed');ElMessage.success('编码已保存')}catch(e){if(epoch!==generation||token!==user.token)return;failure.value=e instanceof Error?e.message:'保存失败，请重试'}finally{if(epoch===generation&&token===user.token)busy.value=false}}
watch(()=>user.token,()=>{generation++;editing.value=null;editor.value=false;detail.value=undefined;target.value=undefined;failure.value='';busy.value=false})
watch(()=>props.editable,v=>{if(!v){generation++;editor.value=false;target.value=undefined;busy.value=false}})
onBeforeUnmount(()=>generation++)
function completed(){target.value=undefined;detail.value=undefined;emit('changed')}
defineExpose({open,show,lifecycle})
</script>
<template>
 <el-alert v-if="failure&&!editor" :title="failure" type="error" :closable="false"/>
 <CodeItemDialog v-model="editor" :item="editing" :code-type="codeType" :loading="busy" :error="failure" @save="save"/>
 <el-drawer :model-value="!!detail" title="编码详情" size="min(640px,94vw)" append-to-body @close="detail=undefined"><template v-if="detail"><h3>{{detail.codeValue}} · {{detail.codeName}}</h3><dl><dt>中文名称</dt><dd>{{detail.codeNameZh||'—'}}</dd><dt>资料状态</dt><dd>{{detail.status==='enabled'?'启用':'停用'}}</dd><dt>排序</dt><dd>{{detail.sortOrder}}</dd></dl><div v-if="editable" class="actions"><el-button @click="open(detail)">编辑</el-button><el-button @click="lifecycle(detail,detail.status==='enabled'?'deactivate':'activate')">{{detail.status==='enabled'?'停用':'启用'}}</el-button><el-button type="danger" plain @click="lifecycle(detail,'delete')">删除</el-button></div></template></el-drawer>
 <MasterDataLifecycleDialog v-if="target" :record="{id:target.codeItemId,name:target.codeName,code:target.codeValue}" :kind-label="codeType==='color'?'颜色编码':'手机编码'" :action="action" :editable="editable" :context="`code:${target.codeItemId}`" :load="inspectCodeItem" :perform="codeItemAction" @close="target=undefined" @completed="completed" @open="router.push($event)"/>
</template>
<style scoped>h3{font-size:16px}dt{font-weight:650;color:#294363;margin-top:16px}dd{margin:4px 0;overflow-wrap:anywhere}.actions{display:flex;gap:8px;flex-wrap:wrap}</style>
