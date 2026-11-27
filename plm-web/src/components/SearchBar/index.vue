<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, reactive, ref, watch } from 'vue'
import { Filter, RefreshLeft, Search } from '@element-plus/icons-vue'

import type { SearchField } from '@/types/common'
import SearchFieldControl from './SearchFieldControl.vue'

const props = defineProps<{
  fields: SearchField[]
  modelValue?: Record<string, unknown>
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: Record<string, unknown>): void
  (event: 'search', value: Record<string, unknown>): void
  (event: 'reset'): void
}>()

const form = reactive<Record<string, unknown>>({})
const mobileFiltersExpanded = ref(false)
const activeMobileFilterCount = computed(() =>
  props.fields.slice(1).filter((field) => {
    const value = form[field.prop]
    return Array.isArray(value) ? value.length > 0 : value !== undefined && value !== null && String(value).trim() !== ''
  }).length
)

function resetLocalForm(source?: Record<string, unknown>) {
  Object.keys(form).forEach((key) => delete form[key])
  props.fields.forEach((field) => {
    form[field.prop] = source?.[field.prop] ?? ''
  })
}

watch(
  () => props.modelValue,
  (value) => resetLocalForm(value),
  { immediate: true, deep: true }
)

function handleSearch() {
  mobileFiltersExpanded.value = false
  const payload = { ...form }
  emit('update:modelValue', payload)
  emit('search', payload)
}

function handleReset() {
  resetLocalForm()
  mobileFiltersExpanded.value = false
  emit('update:modelValue', { ...form })
  emit('reset')
}
</script>

<template>
  <section class="search-bar page-panel">
    <el-form :model="form" inline class="search-form search-form--desktop">
      <el-form-item v-for="field in fields" :key="field.prop" :label="uiText(field.label)">
        <SearchFieldControl v-model="form[field.prop]" :field="field" />
      </el-form-item>

      <div v-if="$slots.extra" class="search-form__extra">
        <slot name="extra" />
      </div>

      <el-form-item class="search-form__actions">
        <el-button type="primary" @click="handleSearch">{{ uiText("搜索") }}</el-button>
        <el-button @click="handleReset">{{ uiText("重置") }}</el-button>
      </el-form-item>
    </el-form>
    <el-form :model="form" class="search-form search-form--mobile" @submit.prevent>
      <el-form-item v-if="fields[0]" :label="uiText(fields[0].label)" class="search-form__primary-field">
        <SearchFieldControl v-model="form[fields[0].prop]" :field="fields[0]" />
      </el-form-item>
      <el-button
        class="search-form__mobile-icon search-form__mobile-search"
        type="primary"
        :icon="Search"
        :aria-label="uiText('搜索')"
        :title="uiText('搜索')"
        @click="handleSearch"
      />
      <el-button
        v-if="fields.length > 1"
        class="search-form__mobile-icon search-form__mobile-filter"
        :type="activeMobileFilterCount ? 'primary' : 'default'"
        :icon="Filter"
        :aria-label="uiText('更多筛选')"
        :title="uiText('更多筛选')"
        :aria-expanded="mobileFiltersExpanded"
        @click="mobileFiltersExpanded = !mobileFiltersExpanded"
      >
        <span v-if="activeMobileFilterCount" class="search-form__filter-count">{{ activeMobileFilterCount }}</span>
      </el-button>
      <el-button
        v-else
        class="search-form__mobile-icon search-form__mobile-reset"
        :icon="RefreshLeft"
        :aria-label="uiText('重置')"
        :title="uiText('重置')"
        @click="handleReset"
      />
      <div v-if="mobileFiltersExpanded" class="search-form__mobile-advanced">
        <el-form-item v-for="field in fields.slice(1)" :key="field.prop" :label="uiText(field.label)">
          <SearchFieldControl v-model="form[field.prop]" :field="field" />
        </el-form-item>
        <div v-if="$slots.extra" class="search-form__extra">
          <slot name="extra" />
        </div>
        <div class="search-form__mobile-advanced-actions">
          <el-button @click="handleReset">{{ uiText('重置') }}</el-button>
          <el-button type="primary" @click="handleSearch">{{ uiText('搜索') }}</el-button>
        </div>
      </div>
    </el-form>
  </section>
</template>

<style scoped>
.search-bar {
  padding-bottom: 4px;
}

.search-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
}

.search-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.search-form__extra {
  display: flex;
  align-items: center;
  margin-left: auto;
  margin-right: 12px;
  margin-bottom: 12px;
}

.search-form__actions {
  margin-left: 0;
}

.search-form--mobile {
  display: none;
}

@media (max-width: 767px) {
  .search-bar {
    padding: 10px;
  }

  .search-form--desktop {
    display: none;
  }

  .search-form--mobile {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 40px 40px;
    align-items: center;
    width: 100%;
    flex-wrap: initial;
    gap: 6px;
    margin: 0;
  }

  .search-form__primary-field {
    width: 100%;
    min-width: 0;
    margin: 0;
  }

  .search-form__primary-field :deep(.el-form-item__label) {
    display: none;
  }

  .search-form__primary-field :deep(.el-form-item__content),
  .search-form__primary-field :deep(.el-input),
  .search-form__primary-field :deep(.el-select),
  .search-form__primary-field :deep(.el-date-editor) {
    width: 100%;
    min-width: 0;
    max-width: 100%;
    margin-left: 0;
  }

  .search-form__mobile-icon {
    position: relative;
    width: 40px;
    min-height: 40px;
    height: 40px;
    margin: 0;
    padding: 0;
  }

  .search-form__mobile-icon :deep(.el-icon) {
    margin: 0;
  }

  .search-form__filter-count {
    position: absolute;
    top: -5px;
    right: -5px;
    display: grid;
    min-width: 16px;
    height: 16px;
    place-items: center;
    padding: 0 3px;
    border-radius: 9px;
    background: var(--el-color-danger);
    color: #fff;
    font-size: 10px;
    line-height: 1;
  }

  .search-form__mobile-advanced {
    display: grid;
    grid-column: 1 / -1;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px 12px;
    width: 100%;
    min-width: 0;
    padding-top: 10px;
    border-top: 1px solid var(--el-border-color-lighter);
  }

  .search-form__mobile-advanced :deep(.el-form-item) {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    width: 100%;
    min-width: 0;
    margin: 0;
  }

  .search-form__mobile-advanced :deep(.el-form-item__label) {
    display: block;
    width: auto !important;
    height: auto;
    padding: 0 0 4px;
    line-height: 1.4;
    text-align: left;
  }

  .search-form__mobile-advanced :deep(.el-form-item__content),
  .search-form__mobile-advanced :deep(.el-input),
  .search-form__mobile-advanced :deep(.el-select),
  .search-form__mobile-advanced :deep(.el-date-editor) {
    width: 100% !important;
    min-width: 0;
    max-width: 100%;
    margin-left: 0 !important;
  }

  .search-form__mobile-advanced .search-form__extra {
    grid-column: 1 / -1;
    width: 100%;
    margin: 0;
  }

  .search-form__mobile-advanced-actions {
    display: flex;
    grid-column: 1 / -1;
    justify-content: flex-end;
    gap: 8px;
  }

  .search-form__mobile-advanced-actions :deep(.el-button) {
    min-width: 78px;
  }
}
</style>
