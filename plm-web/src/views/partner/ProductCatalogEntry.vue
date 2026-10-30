<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PartnerWorkspace from './PartnerWorkspace.vue'
import SupplierCatalogWorkspace from './catalog/SupplierCatalogWorkspace.vue'

const route = useRoute(), router = useRouter()
// Existing PP URLs and their sample / QC / listing workflow remain the same owner.
const productView = computed(() => Boolean(route.query.id) || route.query.view === 'products' || route.query.new === '1')
</script>

<template>
  <div v-if="productView" class="product-catalog-entry">
    <div class="catalog-return"><el-button link type="primary" @click="router.push('/partner-products')">{{ uiText("供应商款式") }}</el-button><span>{{ uiText("我方产品") }}</span></div>
    <PartnerWorkspace />
  </div>
  <SupplierCatalogWorkspace v-else />
</template>

<style scoped>
.product-catalog-entry{height:100%;min-height:0;min-width:0;display:flex;flex-direction:column;gap:4px}.product-catalog-entry>:last-child{flex:1;min-height:0}.catalog-return{display:flex;gap:12px;align-items:center;flex:none;font-size:12px;color:var(--el-text-color-secondary)}
</style>
