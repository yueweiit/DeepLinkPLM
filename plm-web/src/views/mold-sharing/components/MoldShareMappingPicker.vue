<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {t as uiText} from '@/locales'
import {getMoldShareMappings,type MoldShareMapping} from '@/api/modules/moldSharing'
const props=defineProps<{productLineId:number;sourcePhoneModelId:number;holeType:string;structureDescription:string}>()
const emit=defineEmits<{close:[];selected:[MoldShareMapping[]]}>()
const rows=ref<MoldShareMapping[]>([]),total=ref(0),page=ref(1),keyword=ref(''),busy=ref(false),error=ref('')
const groups=computed(()=>{const result=new Map<string,MoldShareMapping[]>();for(const row of rows.value){const key=`${row.referencePhoneModelId}:${row.manufacturingRelationId}`;result.set(key,[...(result.get(key)||[]),row])}return [...result.values()]})
async function load(){busy.value=true;error.value='';try{const result=await getMoldShareMappings({...props,keyword:keyword.value,status:'active',page:page.value,size:100});rows.value=result.content;total.value=result.totalElements}catch(e){error.value=e instanceof Error?e.message:'共模映射读取失败'}finally{busy.value=false}}
async function selectGroup(group:MoldShareMapping[]){
 if(busy.value||!group[0])return
 busy.value=true;error.value=''
 try{
  // Search and page boundaries may hide another material. Resolve only after loading the entire reference group.
  const complete:MoldShareMapping[]=[];let current=1,count=0
  do{const result=await getMoldShareMappings({...props,referencePhoneModelId:group[0].referencePhoneModelId,status:'active',page:current++,size:100});if(!result.content.length)throw new Error('共模映射已变化，请重新查询');complete.push(...result.content);count=result.totalElements}while(complete.length<count)
  emit('selected',complete.filter(row=>row.manufacturingRelationId===group[0]!.manufacturingRelationId))
 }catch(e){error.value=e instanceof Error?e.message:'共模映射读取失败'}finally{busy.value=false}
}
onMounted(load)
</script>
<template><el-dialog :model-value="true" :title="uiText('选择共用模具的其他机型')" width="min(820px,96vw)" append-to-body :close-on-click-modal="false" @close="emit('close')"><p>{{uiText('成品继续使用自身手机码；仅模具引用下列基准码。采用时核对全部实物部件。')}}</p><div class="tools"><el-input v-model="keyword" :placeholder="uiText('搜索机型或模具编码')" @keyup.enter="page=1;load()"/><el-button @click="page=1;load()">{{uiText('查询')}}</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false"/><div v-loading="busy" class="results"><article v-for="group in groups" :key="group[0]!.id"><strong>{{group[0]!.referencePhoneName}} · {{group[0]!.referencePhoneCode}}</strong><p>{{uiText('自身机型')}}：{{group[0]!.sourcePhoneName}} · {{group[0]!.sourcePhoneCode}}</p><p>{{group.map(row=>`${row.materialCode==='10'?'TPU':'PC'} ${row.moldCode} · V${row.version}`).join(' / ')}}</p><p>{{uiText('实物套')}} #{{group[0]!.moldSetId}} · {{group[0]!.evidence}}</p><el-button type="primary" @click="selectGroup(group)">{{uiText('选择并核对整套')}}</el-button></article><p v-if="!rows.length&&!busy">{{uiText('暂无同产品、同孔型、同结构的有效共模映射，请在基础资料维护。')}}</p></div><el-pagination layout="total,prev,pager,next" :total="total" :current-page="page" :page-size="100" @current-change="page=$event;load()"/></el-dialog></template>
<style scoped>.tools{display:flex;gap:8px}.results{max-height:55vh;overflow:auto;font-size:12px}.results article{padding:12px 0;border-bottom:1px solid var(--el-border-color-light);overflow-wrap:anywhere}</style>
