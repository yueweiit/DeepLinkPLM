<script setup lang="ts">
import {onMounted,ref,watch} from 'vue'
import {t as uiText} from '@/locales'
import {createPhoneModel,getPhoneSelectionBrands} from '@/api/modules/phoneReference'
import {useUserStore} from '@/stores/user'
import type {CompatiblePhone,PhoneBrand} from '@/types/molds'

const props=defineProps<{brandId?:number|null;modelName?:string}>()
const emit=defineEmits<{created:[CompatiblePhone];cancel:[]}>()
const user=useUserStore(),brand=ref(props.brandId||undefined),name=ref(props.modelName||''),brands=ref<PhoneBrand[]>([])
const busy=ref(false),loading=ref(false),error=ref('')
async function loadBrands(){
  const auth=user.token;loading.value=true;error.value=''
  try{const rows=await getPhoneSelectionBrands();if(auth===user.token)brands.value=rows.filter(row=>row.status==='enabled')}
  catch(e){if(auth===user.token)error.value=e instanceof Error?e.message:'品牌读取失败，请重试'}
  finally{if(auth===user.token)loading.value=false}
}
async function save(){
  if(!brand.value||!name.value.trim()||busy.value)return
  const auth=user.token;busy.value=true;error.value=''
  try{const phone=await createPhoneModel({brandId:brand.value,modelName:name.value.trim()});if(auth===user.token)emit('created',phone)}
  catch(e){if(auth===user.token)error.value=e instanceof Error?e.message:'型号保存失败，请重试'}
  finally{if(auth===user.token)busy.value=false}
}
watch(()=>user.token,()=>emit('cancel'))
onMounted(loadBrands)
</script>
<template>
  <section class="phone-create-form" :aria-label="uiText('新增手机型号')">
    <el-alert v-if="error" :title="uiText(error)" type="error" :closable="false"/>
    <el-form label-position="top" :disabled="busy" @submit.prevent="save">
      <el-form-item :label="uiText('手机品牌')" required><el-select v-model="brand" filterable :teleported="false" :loading="loading" :aria-label="uiText('手机品牌')"><el-option v-for="row in brands" :key="row.brandId" :value="row.brandId" :label="row.name"/></el-select><el-button v-if="error&&!brands.length" link @click="loadBrands">{{uiText('重试品牌')}}</el-button></el-form-item>
      <el-form-item :label="uiText('型号名称')" required><el-input v-model="name" :aria-label="uiText('型号名称')" maxlength="256" :placeholder="uiText('例如 iPhone 18 Pro Max')" @keydown.enter.prevent="save"/></el-form-item>
    </el-form>
    <small>{{uiText('仅新建手机资料，编码待分配；不会新建模具、SKU或库存。')}}</small>
    <div class="phone-create-actions"><el-button :disabled="busy" @click="emit('cancel')">{{uiText('取消')}}</el-button><el-button type="primary" :loading="busy" :disabled="!brand||!name.trim()||loading" @click="save">{{uiText('保存资料')}}</el-button></div>
  </section>
</template>
<style scoped>
.phone-create-form{font-size:12px;min-width:0}.phone-create-form .el-form{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,2fr);gap:12px}.phone-create-form :deep(.el-form-item){margin-bottom:8px}.phone-create-form :deep(.el-select){width:100%}.phone-create-form :deep(.el-input__wrapper),.phone-create-form :deep(.el-select__wrapper){min-height:28px}.phone-create-form small{display:block;font-size:12px;color:var(--el-text-color-secondary);line-height:1.5}.phone-create-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:8px}.phone-create-actions :deep(.el-button){height:28px;font-size:12px;margin:0}@media(max-width:520px){.phone-create-form .el-form{grid-template-columns:1fr;gap:0}}
</style>
