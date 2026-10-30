<script setup lang="ts">
import { t as uiText } from '@/locales'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import type { CodeItem, CodeItemPayload } from '@/api/modules/code'
import { getPhoneSelectionBrands } from '@/api/modules/phoneReference'
import { useUserStore } from '@/stores/user'
import type { CompatiblePhone, PhoneBrand } from '@/types/molds'
import { phoneMemberName } from '@/utils/phoneMembers'
import PhoneModelMultiSelect from '@/components/PhoneModelMultiSelect/index.vue'

const props = defineProps<{ modelValue: boolean; item?: CodeItem | null; phone?: CompatiblePhone; loading?: boolean; error?: string; codeType?: 'color' | 'phone_model' }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void; (event: 'save', payload: CodeItemPayload): void }>()
const user = useUserStore()
const form = reactive<CodeItemPayload>({ codeType: 'color', codeValue: '', codeName: '', codeNameZh: '', sortOrder: 0 })
const mode = ref<'existing' | 'new'>('existing'), brandId = ref<number>(), phoneIds = ref<number[]>([]), selectedPhone = ref<CompatiblePhone>(), fullName = ref('')
const brands = ref<PhoneBrand[]>([]), brandsLoading = ref(false), brandError = ref('')
let generation = 0, lastSuggestedName = ''
const isPhone = computed(() => form.codeType === 'phone_model')
const registering = computed(() => isPhone.value && !props.item)
const typeLabel = computed(() => isPhone.value ? '手机型号编码' : '颜色编码')
const valid = computed(() => Boolean(form.codeName.trim()) && form.codeName.trim().length <= 128 && (!props.item || Boolean(form.reason?.trim()))
  && (isPhone.value ? /^[0-9]{4}$/.test(form.codeValue) : Boolean(form.codeValue.trim()))
  && (!registering.value || Boolean(brandId.value) && !brandsLoading.value && !brandError.value
    && (mode.value === 'existing' ? phoneIds.value.length === 1 && !!selectedPhone.value : Boolean(fullName.value.trim()))))
async function loadBrands() {
  const epoch = ++generation, token = user.token
  brandsLoading.value = true; brandError.value = ''
  try {
    const result = await getPhoneSelectionBrands()
    if (epoch === generation && token === user.token && props.modelValue) brands.value = result.filter(b => b.status === 'enabled')
  } catch {
    if (epoch === generation && token === user.token) brandError.value = '品牌读取失败，请重试'
  } finally {
    if (epoch === generation && token === user.token) brandsLoading.value = false
  }
}
function suggestName(value: string) {
  value = value.length <= 128 ? value : ''
  if (!form.codeName.trim() || form.codeName === lastSuggestedName) form.codeName = value
  lastSuggestedName = value
}
function selectPhone(phones: CompatiblePhone[]) {
  selectedPhone.value = phones[0]
  if (phones[0]) suggestName(phones[0].factoryName || phones[0].modelName)
}
function clearPhone() { phoneIds.value = []; selectedPhone.value = undefined }
function save() {
  if (!valid.value || props.loading) return
  const payload = { ...form, codeName: form.codeName.trim() }
  if (registering.value) payload.phoneRegistration = { brandId: brandId.value!, ...(mode.value === 'existing' ? { phoneModelId: phoneIds.value[0] } : { modelName: fullName.value.trim() }) }
  else delete payload.phoneRegistration
  emit('save', payload)
}
watch(() => [props.modelValue, props.item, props.phone], () => {
  generation++
  if (!props.modelValue) return
  Object.assign(form, { codeType: props.item?.codeType || props.codeType || 'color', codeValue: props.item?.codeValue || '', codeName: props.item?.codeName || '', codeNameZh: props.item?.codeNameZh || '', sortOrder: props.item?.sortOrder || 0, version: props.item?.version, reason: '' })
  delete form.phoneRegistration
  mode.value = 'existing'; brandId.value = props.phone?.brandId; selectedPhone.value = props.phone
  phoneIds.value = props.phone ? [props.phone.phoneModelId] : []; fullName.value = ''; lastSuggestedName = ''
  brands.value = []; brandError.value = ''; brandsLoading.value = false
  if (props.phone) suggestName(props.phone.factoryName || props.phone.modelName)
  if (registering.value) void loadBrands()
}, { immediate: true })
watch(() => user.token, () => { generation++; brands.value = []; brandError.value = ''; clearPhone() })
onBeforeUnmount(() => generation++)
</script>

<template>
  <el-dialog :model-value="modelValue" :title="registering&&phone ? uiText('分配手机编码') : uiText('{value0}{value1}',{value0:item?'编辑':'新增',value1:typeLabel})" width="min(580px,94vw)" :close-on-click-modal="false" :close-on-press-escape="!loading" :show-close="!loading" @close="emit('update:modelValue',false)">
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <el-alert v-if="brandError" :title="uiText(brandError)" type="error" :closable="false"><el-button link @click="loadBrands">{{uiText('重试')}}</el-button></el-alert>
    <el-form label-position="top" :disabled="loading">
      <template v-if="registering">
        <el-form-item :label="uiText('品牌')" required><el-select v-model="brandId" filterable :disabled="!!phone||brandsLoading" :loading="brandsLoading" :aria-label="uiText('编码所属品牌')" :placeholder="uiText('选择已有品牌')" @change="clearPhone"><el-option v-for="brand in brands" :key="brand.brandId" :value="brand.brandId" :label="brand.name"/></el-select></el-form-item>
        <el-radio-group v-if="!phone" v-model="mode" class="phone-mode" :aria-label="uiText('型号来源')" @change="clearPhone"><el-radio-button value="existing">{{uiText('关联已有型号')}}</el-radio-button><el-radio-button value="new">{{uiText('新增型号')}}</el-radio-button></el-radio-group>
        <el-form-item v-if="mode==='existing'" :label="uiText('完整型号')" required>
          <div v-if="phone" class="existing-phone">{{phoneMemberName(phone)}}</div>
          <PhoneModelMultiSelect v-else v-model="phoneIds" :members="selectedPhone?[selectedPhone]:[]" :brand-id="brandId" :disabled="!brandId||loading" :multiple="false" unassigned-code :aria-label="uiText('选择待分配型号')" :placeholder="uiText('选择已有的待分配型号')" @selected="selectPhone"/>
        </el-form-item>
        <el-form-item v-else :label="uiText('完整型号')" required><el-input v-model="fullName" maxlength="256" :aria-label="uiText('完整型号名称')" :placeholder="uiText('例如 Honor 600 Lite 5G')" @input="suggestName(fullName.trim())"/></el-form-item>
      </template>
      <div class="code-form-grid">
        <el-form-item v-if="!isPhone" :label="uiText('编码类型')"><el-input :model-value="uiText(typeLabel)" disabled/></el-form-item>
        <el-form-item :label="isPhone?uiText('型号编码（四位数字）'):uiText('颜色编码值')" required><el-input v-model="form.codeValue" :disabled="!!item" :maxlength="isPhone?4:64" :aria-label="isPhone?uiText('四位手机编码'):uiText('颜色编码值')" :placeholder="isPhone?uiText('例如 0001，创建后不可修改'):uiText('例如 02')"/></el-form-item>
      </div>
      <el-form-item :label="isPhone?uiText('工厂简称'):uiText('颜色原名称')" required><el-input v-model="form.codeName" maxlength="128" :aria-label="isPhone?uiText('工厂简称'):uiText('颜色原名称')" :placeholder="isPhone?uiText('例如 HR 600 LITE 5G'):uiText('例如 Negro')"/></el-form-item>
      <p v-if="registering" class="phone-note">{{uiText('保存后即可在选品、开模中选用。')}}</p>
      <el-form-item v-if="!isPhone" :label="uiText('颜色中文名称')"><el-input v-model="form.codeNameZh" maxlength="128" :placeholder="uiText('例如 黑色')"/></el-form-item>
      <el-form-item v-if="!isPhone" :label="uiText('排序')"><el-input-number v-model="form.sortOrder" :min="0"/></el-form-item>
      <el-form-item v-if="item" :label="uiText('修改原因')" required><el-input v-model="form.reason" type="textarea" :rows="2" maxlength="1000"/></el-form-item>
    </el-form>
    <template #footer><el-button :disabled="loading" @click="emit('update:modelValue',false)">{{uiText('取消')}}</el-button><el-button data-test="save-code-item" type="primary" :loading="loading" :disabled="!valid||loading" @click="save">{{registering?uiText('保存并关联'):uiText('保存')}}</el-button></template>
  </el-dialog>
</template>
<style scoped>
.code-form-grid{display:grid;grid-template-columns:1fr;gap:10px}.el-select{width:100%}.phone-mode{margin-bottom:16px}.phone-note{font-size:12px;color:var(--el-text-color-secondary);line-height:1.5;margin:0 0 16px}.existing-phone{width:100%;padding:7px 10px;box-sizing:border-box;border:1px solid var(--el-border-color);border-radius:4px;overflow-wrap:anywhere}
</style>
