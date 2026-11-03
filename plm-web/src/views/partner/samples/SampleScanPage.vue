<script setup lang="ts">
import { t as uiText } from '@/locales'

import {onBeforeUnmount,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {useUserStore} from '@/stores/user'
import {scanSample} from '@/api/modules/sampleManagement'
import type {SampleResolution} from '@/types/sampleManagement'
const route=useRoute(),router=useRouter(),user=useUserStore(),code=ref(String(route.query.code||'')),data=ref<SampleResolution>(),loading=ref(false),error=ref('')
let epoch=0,disposed=false
async function load(){const value=String(route.query.code||'').trim(),ticket=++epoch,auth=user.token;data.value=undefined;error.value='';if(!value){error.value='请扫描标签或输入样品码';return}loading.value=true;try{const result=await scanSample(value);if(ticket!==epoch||auth!==user.token||disposed)return;if(result.type==='identity'){const id=result.sampleIdentityId||result.identity?.sampleIdentityId;if(id){await router.replace({path:'/samples',query:{identityId:id}});return}}if(result.type==='legacy_batch'&&result.batchId){await router.replace({path:'/samples',query:{view:'batches',id:result.batchId}});return}data.value=result}catch(e){if(ticket===epoch&&auth===user.token)error.value=e instanceof Error?e.message:'无法读取此码，或当前账号无权查看'}finally{if(ticket===epoch)loading.value=false}}
function search(){void router.replace({path:'/sample-scan',query:{code:code.value.trim()}});if(String(route.query.code||'')===code.value.trim())void load()}
watch(()=>route.query.code,()=>{code.value=String(route.query.code||'');void load()},{immediate:true})
watch(()=>user.token,()=>{++epoch;data.value=undefined;error.value='';loading.value=false})
onBeforeUnmount(()=>{disposed=true;++epoch})
</script>
<template><section class="sample-scan"><header><h1>{{uiText('样品扫码')}}</h1><el-input v-model="code" size="small" clearable maxlength="80" :placeholder="uiText('输入样品码')" :aria-label="uiText('样品码')" @keyup.enter="search"/><el-button size="small" type="primary" @click="search">{{uiText('核对')}}</el-button></header><div class="scan-content" v-loading="loading"><el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load">{{uiText('重试')}}</el-button></el-alert><p v-else-if="data">{{uiText('当前账号没有可办理的样品记录。')}}</p></div></section></template>
<style scoped>.sample-scan{height:100%;min-height:0;display:flex;flex-direction:column;gap:12px;font-size:12px;min-width:0}header{display:flex;align-items:center;gap:8px;flex-wrap:wrap}h1{font-size:16px;margin:0;color:#243e5d}header .el-input{max-width:320px}.scan-content{flex:1;min-height:0;overflow:auto}.sample-scan :deep(.el-input__wrapper){min-height:28px;font-size:12px}</style>
