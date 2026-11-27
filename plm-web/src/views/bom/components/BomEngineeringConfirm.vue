<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { t } from '@/locales'
import { confirmCurrentBomVersion } from '@/api/modules/bom'
import { getBomConfirmationScope, type BomConfirmationScope, type BomScopeArticle, type ScopedRecipe } from '@/api/modules/masterData'
import { productColorSwatches } from '@/utils/productColorSwatches'
import { colorLabel, materialLabel, productLabel, versionLabel, type BomIdentity } from '../catalogPresentation'

const props=defineProps<{row:BomIdentity;recipe:ScopedRecipe;ambiguous?:boolean}>()
const emit=defineEmits<{close:[];confirmed:[]}>()
const scope=ref<BomConfirmationScope>(),error=ref(''),busy=ref(false),loading=ref(true)
let active=true
const version=computed(()=>versionLabel(props.recipe))
function grouped(items:BomScopeArticle[]) {
  const codes=new Map<string,Set<string>>()
  for(const item of items) {
    if(!codes.has(item.code)) codes.set(item.code,new Set())
    if(item.model) codes.get(item.code)!.add(item.model)
  }
  return [...codes]
}
const sections=computed(()=>{
  if(!scope.value)return []
  const s=scope.value
  const result=[{removed:false,count:s.count,rows:grouped(s.articles),label:t('适用范围 · {n} 个品目',{n:s.count})}]
  if(s.removedCount)result.push({removed:true,count:s.removedCount,rows:grouped(s.removedArticles),label:t('{n} 个原有品目将移出此配方',{n:s.removedCount})})
  return result
})
async function preview() {
  error.value='';loading.value=true;scope.value=undefined
  try { const result=await getBomConfirmationScope(props.row.sourceId,props.recipe.bomId,props.recipe.bomRouteId);if(active)scope.value=result }
  catch(e){if(active)error.value=e instanceof Error?e.message:t('读取失败')}
  finally{if(active)loading.value=false}
}
async function confirm() {
  if(busy.value || !scope.value?.count) return
  busy.value=true;error.value=''
  try {
    const current=await getBomConfirmationScope(props.row.sourceId,props.recipe.bomId,props.recipe.bomRouteId)
    if(!active)return
    if(current.revision!==scope.value.revision){scope.value=current;error.value=t('适用范围或正式版本已变化，请重新核对');return}
    await confirmCurrentBomVersion(props.recipe.bomId,current.revision)
    if(active){ElMessage.success(t('工程版本已确认'));emit('confirmed')}
  }
  catch(e){if(active){scope.value=undefined;error.value=e instanceof Error?e.message:t('确认失败，请刷新')}}
  finally{if(active)busy.value=false}
}
onMounted(preview);onUnmounted(()=>{active=false})
</script>
<template>
  <el-dialog :model-value="true" append-to-body :title="t('工程确认')" width="min(460px,94vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="!$event && !busy && emit('close')">
    <div class="confirm-target">
      <div><strong>{{productLabel(row)}}</strong><span><i v-if="productColorSwatches[row.colorNameZh]" :style="{backgroundColor:productColorSwatches[row.colorNameZh]}" aria-hidden="true"></i>{{colorLabel(row)}}</span><b>BOM {{version}}</b></div>
      <p>{{row.modelName}}</p><code>{{row.itemCode}}</code><p v-if="ambiguous" class="confirm-route" :title="recipe.bomCode">{{materialLabel(recipe.routeName)}} · {{recipe.internalBomCode}}</p>
    </div>
    <div v-if="loading" class="scope-loading">{{t('正在读取适用范围')}}</div>
    <template v-if="scope">
      <details v-for="part in sections" :key="String(part.removed)" :class="{withdrawn:part.removed}"><summary>{{part.label}}</summary>
        <div class="scope-list"><div v-for="[code,models] in part.rows" :key="code"><code>{{code}}</code><span><span v-for="m in models" :key="m">{{m}}</span></span></div></div>
        <span v-if="part.rows.length<part.count">{{t('可见 {n} 个品目',{n:part.rows.length})}}</span>
      </details>
      <p v-if="scope.count">{{scope.previousVersions.length?t('整份配方及关联工艺将由 {old} 切换到 {version}。后续修改需复制新版。',{old:scope.previousVersions.join(' / '),version}):t('整份配方及关联工艺将以 {version} 生效。后续修改需复制新版。',{version})}}</p>
      <p v-else>{{t('此版本缺少适用范围')}}</p>
    </template>
    <el-alert v-if="error" :title="error" :closable="false" type="error"><el-button link @click="preview">{{t('重试')}}</el-button></el-alert>
    <template #footer><el-button :disabled="busy" @click="emit('close')">{{t('取消')}}</el-button><el-button type="primary" :disabled="!scope?.count" :loading="busy" @click="confirm">{{t('确认 {version}',{version})}}</el-button></template>
  </el-dialog>
</template>
<style scoped>
.confirm-target{border-left:3px solid var(--el-color-primary);background:var(--el-fill-color-light);padding:12px 14px;border-radius:4px}.confirm-target>div{display:flex;gap:5px 12px;align-items:center;flex-wrap:wrap}.confirm-target strong{font-size:17px}.confirm-target b{margin-left:auto;color:var(--el-color-primary)}.confirm-target p{margin:5px 0 1px}code{font-size:12px;color:var(--el-text-color-secondary);overflow-wrap:anywhere}i{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:5px;box-shadow:inset 0 0 0 1px var(--el-border-color)}details,.scope-loading{margin:12px 0}.withdrawn summary{color:var(--el-color-warning-dark-2)}summary{cursor:pointer;width:fit-content}.scope-list{max-height:175px;overflow:auto}.scope-list>div{display:flex;gap:6px 10px;flex-wrap:wrap;padding:3px 0}.scope-list span span{display:block}p{margin:8px 0 0}
</style>
