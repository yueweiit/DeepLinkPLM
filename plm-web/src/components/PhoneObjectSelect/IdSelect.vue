<script setup lang="ts">
import {t as uiText} from '@/locales'

import { computed, ref, watch } from 'vue'
import { useUserStore } from '@/stores/user'
import PhoneObjectSelect from './index.vue'
import { localObjectProvider } from './localProvider'
import type { PhoneObjectOption, PhoneObjectProvider } from './types'
const props = withDefaults(defineProps<{ modelValue?: number | null; options?: PhoneObjectOption[]; provider?: PhoneObjectProvider; type: string; label: string; disabled?: boolean; scopeKey?: string; phoneFirst?: boolean; placeholder?: string }>(), { options: () => [], phoneFirst: true })
const emit = defineEmits<{ 'update:modelValue': [number | null]; selected: [PhoneObjectOption | undefined] }>()
const known = ref<PhoneObjectOption>()
const user = useUserStore()
watch(() => [user.token, props.scopeKey], () => { known.value = undefined }, { flush: 'sync' })
const selected = computed(() => props.modelValue ? [props.options.find(r => r.id === props.modelValue) || (known.value?.id === props.modelValue ? known.value : { type: props.type, id: props.modelValue, code: `#${props.modelValue}`, name: `${props.label} #${props.modelValue}` })] : [])
const local = localObjectProvider(() => props.options)
function confirm(rows: PhoneObjectOption[]) { known.value = rows[0]; emit('update:modelValue',rows[0]?.id || null); emit('selected',rows[0]) }
</script>
<template><PhoneObjectSelect :model-value="selected" :provider="provider || local" :kinds="[{key:type,type,label,phoneFirst}]" :multiple="false" :disabled="disabled" :scope-key="scopeKey" :aria-label="label" :placeholder="placeholder || uiText('请选择品牌、手机型号及{value0}',{value0:(label)})" @update:model-value="confirm"/></template>
