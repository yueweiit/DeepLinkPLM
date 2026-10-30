<script setup lang="ts">
import { t as uiText } from '@/locales'

import {onMounted,reactive,ref,watch} from 'vue'
import {useResizeObserver} from '@vueuse/core'
import {getCodeItems,type CodeItem} from '@/api/modules/code'
import {getCollaborationOptions} from '@/api/modules/collaboration'
import {useUserStore} from '@/stores/user'
import FixedTableViewport from '@/components/FixedTableViewport/index.vue'
import CodeImportDialog from './components/CodeImportDialog.vue'
import CodeMaintenancePanel from './components/CodeMaintenancePanel.vue'
const user=useUserStore(),rows=ref<CodeItem[]>([]),total=ref(0),loading=ref(false),error=ref(''),editable=ref(false),importVisible=ref(false),panel=ref<InstanceType<typeof CodeMaintenancePanel>>(),canvas=ref<HTMLElement>(),compact=ref(false)
const query=reactive({codeType:'color',keyword:'',status:'',page:1,size:50})
let generation=0
useResizeObserver(canvas,entries=>{const width=entries[0]?.contentRect.width;if(width)compact.value=width<850})
async function load(){const epoch=++generation,token=user.token;loading.value=true;error.value='';try{const result=await getCodeItems(query);if(epoch===generation&&token===user.token){rows.value=result.content;total.value=result.totalElements}}catch(e){if(epoch===generation)error.value=e instanceof Error?e.message:'查询失败，请重试'}finally{if(epoch===generation)loading.value=false}}
function search(){query.page=1;void load()}
watch(()=>user.token,()=>{generation++;rows.value=[];editable.value=false;importVisible.value=false})
onMounted(async()=>{const token=user.token;try{const options=await getCollaborationOptions();if(token===user.token)editable.value=options.writableGroups.includes('engineering')}catch{editable.value=false}void load()})
</script>
<template><main ref="canvas" class="code-center">
 <header><h2>{{ uiText("颜色编码 ") }}<small>{{ uiText("共 ") }}{{total}}{{ uiText(" 条") }}</small></h2><div v-if="editable"><el-button data-test="import-code-items" @click="importVisible=true">{{ uiText("导入 XLSX") }}</el-button><el-button data-test="create-code-item" type="primary" @click="panel?.open()">{{ uiText("新增颜色编码") }}</el-button></div></header>
 <section class="filters"><el-input v-model="query.keyword" clearable :placeholder='uiText("编码 / 原名称 / 中文名称")' @keyup.enter="search"/><el-select v-model="query.status" clearable :placeholder='uiText("全部状态")' @change="search"><el-option :label='uiText("启用")' value="enabled"/><el-option :label='uiText("停用")' value="disabled"/></el-select><el-button @click="search">{{ uiText("查询") }}</el-button></section>
 <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load">{{ uiText("重试") }}</el-button></el-alert>
 <FixedTableViewport fill :refresh-key="rows" v-slot="{tableHeight}"><div v-if="compact" class="cards" v-loading="loading"><article v-for="row in rows" :key="row.codeItemId"><strong>{{row.codeValue}} · {{row.codeName}}</strong><span>{{row.codeNameZh||'—'}} · {{row.status==='enabled'?uiText('启用'):uiText('停用')}}</span><div><el-button link @click="panel?.show(row)">{{ uiText("详情") }}</el-button><template v-if="editable"><el-button link @click="panel?.open(row)">{{ uiText("编辑") }}</el-button><el-button link @click="panel?.lifecycle(row,row.status==='enabled'?'deactivate':'activate')">{{row.status==='enabled'?uiText('停用'):uiText('启用')}}</el-button><el-button link type="danger" @click="panel?.lifecycle(row,'delete')">{{ uiText("删除") }}</el-button></template></div></article></div><el-table v-else :data="rows" :height="tableHeight" v-loading="loading"><el-table-column prop="codeValue" :label='uiText("编码")' width="90"/><el-table-column prop="codeName" :label='uiText("原名称")'/><el-table-column prop="codeNameZh" :label='uiText("中文名称")'/><el-table-column :label='uiText("状态")' width="80"><template #default="{row}">{{row.status==='enabled'?uiText('启用'):uiText('停用')}}</template></el-table-column><el-table-column :label='uiText("操作")' width="250"><template #default="{row}"><el-button link @click="panel?.show(row)">{{ uiText("详情") }}</el-button><template v-if="editable"><el-button link @click="panel?.open(row)">{{ uiText("编辑") }}</el-button><el-button :data-test="`${row.status==='enabled'?'disable':'enable'}-code-${row.codeItemId}`" link @click="panel?.lifecycle(row,row.status==='enabled'?'deactivate':'activate')">{{row.status==='enabled'?uiText('停用'):uiText('启用')}}</el-button><el-button link type="danger" @click="panel?.lifecycle(row,'delete')">{{ uiText("删除") }}</el-button></template></template></el-table-column></el-table></FixedTableViewport>
 <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :page-sizes="[20,50,100]" :total="total" layout="total,sizes,prev,pager,next" @current-change="load" @size-change="search"/>
 <CodeMaintenancePanel ref="panel" code-type="color" :editable="editable" @changed="load"/><CodeImportDialog v-if="editable" v-model="importVisible" @committed="search"/>
</main></template>
<style scoped>.code-center{display:flex;min-height:0;min-width:0;flex:1;flex-direction:column;gap:8px;font-size:12px}header,.filters{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap;flex:none}h2{font-size:16px;margin:0;font-weight:700}h2 small{font-size:12px;font-weight:400;color:#66778a}.filters .el-input{flex:1;min-width:180px}.filters .el-select{width:140px}.el-pagination{justify-content:flex-end;flex:none}.cards{height:100%;overflow:auto}.cards article{display:flex;flex-direction:column;gap:4px;padding:8px;border-bottom:1px solid #e1e6ef;overflow-wrap:anywhere}.cards span{color:#63748b}.code-center :deep(.el-button){font-size:12px;height:28px}.code-center :deep(.el-table){font-size:12px}.code-center :deep(.cell){overflow-wrap:anywhere;line-height:18px}</style>
