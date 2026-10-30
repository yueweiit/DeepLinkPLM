<script setup lang="ts">
import { t as uiText } from '@/locales'

import {ref} from 'vue'
import PartnerFilePreview from './PartnerFilePreview.vue'
import {fileBlob} from '@/api/modules/partner'
import {useBlobPreview} from '@/composables/useBlobPreview'
import {attachmentPreviewKey} from '@/utils/authorizedBlobQueue'
// A caller that owns an outer button opts out of the inner retry control.
const props=withDefaults(defineProps<{id:number;allowRetry?:boolean;previewable?:boolean;previewFiles?:{id:number;name?:string}[]}>(),{allowRetry:true,previewable:false})
const previewOpen=ref(false)
const {url,loading,error,retry,imageFailed}=useBlobPreview(()=>{
 const id=props.id
 return id?{key:attachmentPreviewKey(id),fetch:(signal:AbortSignal)=>fileBlob(id,true,{signal})}:undefined
})
function open(event:MouseEvent){if(!props.previewable)return;event.stopPropagation();if(error.value){void retry();return}if(url.value)previewOpen.value=true}
</script>
<template><span class="partner-photo" :aria-busy="loading" :title="error||uiText('产品参考图')"><component :is="previewable?'button':'span'" class="photo-content" :type="previewable?'button':undefined" :aria-label="previewable?uiText('预览产品图片'):undefined" @click="open"><img v-if="url" :src="url" alt="产品参考图" @error="imageFailed"/><span v-else class="photo-state" role="status">{{loading?uiText('加载中…'):error?uiText('图片失败'):uiText('暂无图片')}}<button v-if="error&&allowRetry&&!previewable" type="button" class="photo-retry" @click.stop="retry">{{ uiText("重试") }}</button><small v-else-if="error">{{ uiText("打开重试") }}</small></span></component></span><PartnerFilePreview v-if="previewOpen" :file="{id}" :files="previewFiles" @close="previewOpen=false"/></template>
<style scoped>.photo-content{display:flex;align-items:center;justify-content:center;width:100%;height:100%;padding:0;border:0;background:transparent;color:inherit}.photo-content:is(button){cursor:pointer}</style>
<style scoped>.partner-photo{width:52px;height:52px;display:inline-flex;align-items:center;justify-content:center;border:1px solid #e5eaf2;background:white;border-radius:6px;flex-shrink:0;overflow:hidden;box-sizing:border-box}.partner-photo img{width:100%;height:100%;object-fit:contain}.photo-state{display:flex;flex-direction:column;align-items:center;gap:2px;font-size:10px;line-height:1.25;color:#64748b;text-align:center}.photo-state small{font-size:9px}.photo-retry{border:0;background:none;padding:0 2px;color:#2468c9;font-size:11px;cursor:pointer}</style>
