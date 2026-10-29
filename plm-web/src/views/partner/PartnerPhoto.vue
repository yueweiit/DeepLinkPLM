<script setup lang="ts">
import {fileBlob} from '@/api/modules/partner'
import {useBlobPreview} from '@/composables/useBlobPreview'
import {attachmentPreviewKey} from '@/utils/authorizedBlobQueue'
// A caller that owns an outer button opts out of the inner retry control.
const props=withDefaults(defineProps<{id:number;allowRetry?:boolean}>(),{allowRetry:true})
const {url,loading,error,retry,imageFailed}=useBlobPreview(()=>{
 const id=props.id
 return id?{key:attachmentPreviewKey(id),fetch:(signal:AbortSignal)=>fileBlob(id,true,{signal})}:undefined
})
</script>
<template><span class="partner-photo" :aria-busy="loading" :title="error||'产品参考图'"><img v-if="url" :src="url" alt="产品参考图" @error="imageFailed"/><span v-else class="photo-state" role="status">{{loading?'加载中…':error?'图片失败':'暂无图片'}}<button v-if="error&&allowRetry" type="button" class="photo-retry" @click.stop="retry">重试</button><small v-else-if="error">打开重试</small></span></span></template>
<style scoped>.partner-photo{width:52px;height:52px;display:inline-flex;align-items:center;justify-content:center;border:1px solid #e5eaf2;background:white;border-radius:6px;flex-shrink:0;overflow:hidden;box-sizing:border-box}.partner-photo img{width:100%;height:100%;object-fit:contain}.photo-state{display:flex;flex-direction:column;align-items:center;gap:2px;font-size:10px;line-height:1.25;color:#64748b;text-align:center}.photo-state small{font-size:9px}.photo-retry{border:0;background:none;padding:0 2px;color:#2468c9;font-size:11px;cursor:pointer}</style>
