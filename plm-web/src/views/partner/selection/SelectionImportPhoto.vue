<script setup lang="ts">
import {catalogSourceFileClient,selectionImportImage,type PartnerFileContext} from '@/api/modules/partnerSelections'
import {useBlobPreview} from '@/composables/useBlobPreview'
const props=withDefaults(defineProps<{id:number;jobId:string;imageId:string;name:string;context?:PartnerFileContext}>(),{context:'selection'})
const {url,loading,error,retry,imageFailed}=useBlobPreview(()=>{
 const {id,jobId,imageId,context}=props
 const base=context==='catalog'?'/partner-catalog/sources':'/partner-selections'
 return id&&jobId&&imageId?{key:`${base}/${id}/import-jobs/${jobId}/images/${imageId}`,fetch:(signal:AbortSignal)=>(context==='catalog'?catalogSourceFileClient.selectionImportImage:selectionImportImage)(id,jobId,imageId,{signal})}:undefined
})
</script>
<template><span class="import-photo" :aria-busy="loading"><el-image v-if="url" :src="url" :preview-src-list="[url]" preview-teleported fit="contain" :alt="name" @error="imageFailed"/><span v-else class="import-photo-placeholder" role="status">{{loading?'图片加载中…':error||name}}<button v-if="error" type="button" @click.stop="retry">重试</button></span></span></template>
<style scoped>.import-photo{width:60px;height:60px;display:inline-flex;align-items:center;justify-content:center;flex-shrink:0;border:1px solid #e4ebf4;border-radius:4px;background:white;box-sizing:border-box;overflow:hidden}.import-photo :deep(.el-image){width:100%;height:100%}.import-photo-placeholder{display:flex;flex-direction:column;gap:2px;align-items:center;font-size:10px;line-height:1.25;overflow-wrap:anywhere;padding:3px;text-align:center;color:#64748b}.import-photo-placeholder button{border:0;background:none;padding:0 2px;color:#2468c9;font-size:11px;cursor:pointer}</style>
