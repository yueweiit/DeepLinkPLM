<script setup lang="ts">
import {ref,watch,defineAsyncComponent} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {getProductResearchDetail} from '@/api/modules/productResearch'
const Launch=defineAsyncComponent(()=>import('./ProductLaunchView.vue'))
const route=useRoute(),router=useRouter(),loading=ref(false),error=ref('')
let generation=0
watch(()=>[route.query.productId,route.query.flow],async()=>{
 const current=++generation;error.value=''
 if(route.query.flow==='lightweight'){await router.replace('/product-research');return}
 loading.value=true
 try{
  const id=Number(route.query.productId)
  if(id>0 && (await getProductResearchDetail(id)).developmentMode!=='launch_v1'){
    if(current===generation)await router.replace('/product-research')
  }
 }catch(e){if(current===generation)error.value=e instanceof Error?e.message:'项目载入失败'}
 finally{if(current===generation)loading.value=false}
},{immediate:true})
</script>
<template><div v-if="loading" v-loading="true" style="height:100%"/><div v-else-if="error"><el-alert :title="error" type="error" :closable="false"/><el-button @click="router.replace('/product-research')">返回新品研发</el-button></div><Launch v-else/></template>
