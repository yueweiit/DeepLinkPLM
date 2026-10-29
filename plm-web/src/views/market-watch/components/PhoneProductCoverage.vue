<script setup lang="ts">
import { computed } from 'vue'
import type { MarketPhoneModel } from '@/types/marketWatch'

const props = defineProps<{ phone: MarketPhoneModel; compact?: boolean; statusLabel?: string }>()
const emit = defineEmits<{ details: []; product: [id: number] }>()
const products = computed(() => props.phone.productCoverage || [])
const missing = computed(() => products.value.filter(p => p.status === 'to_apply'))
const groups = computed(() => [
  { key: 'molded', title: '已开模', icon: '✓', products: products.value.filter(p => p.status === 'molded') },
  { key: 'active', title: '申请 / 开模中', icon: '◷', products: products.value.filter(p => ['applied', 'making'].includes(p.status)) },
  { key: 'to_apply', title: '未开模', icon: '＋', products: missing.value },
  { key: 'unresolved', title: '身份待补', icon: '!', products: products.value.filter(p => !['molded', 'applied', 'making', 'to_apply'].includes(p.status)) },
].filter(group => group.products.length))
const names: Record<string, string> = { molded: '已开模', applied: '已申请', making: '开模中', to_apply: '未开模', identity_unresolved: '身份待补' }
const total = computed(() => props.phone.coverageTotal ?? (props.phone.productCoverage ? products.value.length : undefined))
const totalLabel = computed(() => total.value === undefined ? '待加载' : total.value)
const opened = computed(() => props.phone.openedProductCount || 0)
const missingNames = computed(() => missing.value.map(p => p.productLineName).join('、'))
</script>

<template>
  <div v-if="compact" class="coverage-brief">
    <button class="coverage-state" :class="phone.moldStatus" @click="emit('details')">{{ statusLabel }} · {{ opened }}/{{ totalLabel }} 款</button>
    <button class="gap-summary" @click="emit('details')">
      <span v-if="missing.length && missing.length < 4">未开：{{ missingNames }}</span>
      <span v-else-if="missing.length">未开 {{ missing.length }} 款<span v-if="opened"> · 已开 {{ opened }} 款</span></span>
      <span v-else-if="phone.makingProductCount">开模中 {{ phone.makingProductCount }} 款</span>
      <span v-else-if="phone.appliedProductCount">有申请 {{ phone.appliedProductCount }} 款</span>
      <span v-else>{{ total === 0 ? '当前无在售产品' : total !== undefined && opened === total ? '在售产品已覆盖' : '查看产品覆盖与依据' }}</span>
    </button>
  </div>
  <section v-else class="product-coverage-panel" aria-label="在售产品开模覆盖">
    <header class="coverage-heading"><div><h4>在售产品开模覆盖</h4><p>已开 <strong>{{ opened }}</strong> / {{ totalLabel }} 款<span v-if="missing.length"> · 还缺 {{ missing.length }} 款</span></p></div><span class="coverage-state" :class="phone.wholePhoneStatus">{{ statusLabel }}</span></header>
    <div class="coverage-track" aria-hidden="true"><span v-for="p in products" :key="p.productLineId" :class="p.status"/></div>
    <div v-if="groups.length" class="coverage-groups">
      <section v-for="group in groups" :key="group.key" class="product-group" :class="group.key">
        <h5><span>{{ group.icon }} {{ group.title }}</span><b>{{ group.products.length }}</b></h5>
        <button v-for="p in group.products" :key="p.productLineId" class="product-card" :class="p.status" @click="emit('product', p.productLineId)">
          <strong>{{ p.productLineName }}</strong><span class="product-status">{{ names[p.status] || '资料待补' }}</span>
          <small v-if="p.reusable">已有明确可复用套</small><small v-else-if="p.status === 'molded'">{{ p.skuCount ? `已有 ${p.skuCount} 个 SKU / 产品依据` : '查看开模完成依据' }}</small><small v-else-if="p.applicationCount">{{ p.applicationCount }} 条申请 · 查看进度</small><small v-else>可申请此产品</small>
        </button>
      </section>
    </div>
    <p v-else-if="total === 0" class="coverage-note">当前无在售产品；产品正式上市后将自动纳入覆盖。</p>
    <p v-else-if="total === undefined" class="coverage-note">在售产品覆盖数量待加载，请刷新后查看。</p>
    <p v-else class="coverage-note">先关联标准手机，即可查看当前在售产品的开模覆盖。</p>
    <p class="coverage-note">开模依据与实物可复用分别记录；共模及 PC / TPU 适配见具体套。</p>
    <slot/>
  </section>
</template>

<style scoped>
.coverage-brief{min-width:0;display:flex;flex-direction:column;align-items:flex-start;gap:1px}.coverage-state{display:inline-flex;align-items:center;max-width:100%;box-sizing:border-box;background:#f1f3f6;color:#566478;border:1px solid #dce2ea;border-radius:4px;padding:0 5px;font:inherit;font-weight:600;line-height:16px;text-align:left;white-space:normal;overflow-wrap:anywhere}.coverage-brief button{cursor:pointer}.coverage-state.molded{background:#e8f6ee;border-color:#bfe4cf;color:#18734a}.coverage-state.partially_molded{background:#eef5ff;border-color:#c9dcfa;color:#2560ac}.coverage-state.making{background:#e9f3ff;border-color:#bcd7fa;color:#2463b4}.coverage-state.applied{background:#f1edff;border-color:#d9cff8;color:#7052a6}.coverage-state.to_apply{background:#fff5e5;border-color:#f4d7a2;color:#a36915}.gap-summary{border:0;padding:0;background:transparent;color:#6f7683;font:inherit;line-height:16px;text-align:left;white-space:normal;overflow-wrap:anywhere}.product-coverage-panel{border:1px solid #e0e7ef;border-radius:8px;padding:14px;background:#fff;min-width:0;container-type:inline-size}.coverage-heading{display:flex;align-items:center;justify-content:space-between;gap:8px}.coverage-heading h4{margin:0;font-size:13px}.coverage-heading p{margin:5px 0 0;color:#66758a}.coverage-heading strong{font-size:18px;color:#193b2d}.coverage-track{display:flex;gap:4px;height:5px;margin:12px 0}.coverage-track span{flex:1;border-radius:2px;background:#dfe5ed}.coverage-track .molded{background:#43a375}.coverage-track .to_apply{background:#e5c78e}.coverage-track .applied{background:#a58bd0}.coverage-track .making{background:#5b97dc}.coverage-groups{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,180px),1fr));gap:10px}.product-group{min-width:0;background:#f8fafc;border-radius:6px;padding:8px}.product-group.molded{background:#f0f8f3}.product-group.to_apply{background:#fff8ee}.product-group.active{background:#f0f5fc}.product-group h5{display:flex;justify-content:space-between;align-items:center;margin:0 0 7px;color:#566478;font-size:12px}.product-group.molded h5{color:#21754f}.product-group.to_apply h5{color:#986512}.product-group.active h5{color:#3264a5}.product-group h5 b{background:white;border-radius:10px;min-width:20px;text-align:center;line-height:20px}.product-card{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:3px 6px;width:100%;margin:0 0 5px;padding:8px;border:1px solid #dce7df;border-radius:5px;background:#fff;text-align:left;font:inherit;cursor:pointer;color:#233c30;min-width:0}.product-card:last-child{margin-bottom:0}.product-card:hover{border-color:#70a1d8;box-shadow:0 1px 4px #233c3010}.product-card.to_apply{border-color:#eddfc5;color:#835d22}.product-card.applied,.product-card.making{border-color:#d7e2f1;color:#3264a5}.product-card strong{font-weight:600;overflow-wrap:anywhere}.product-status{font-size:12px}.product-card small{grid-column:1/-1;color:#7c8795;font-size:12px;line-height:16px}.coverage-note{color:#7c8795;font-size:12px;line-height:18px;margin:10px 0 0}
@container(min-width:650px){.product-group{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px;align-content:start}.product-group h5{grid-column:1/-1;margin:0}.product-group .product-card{margin:0}.coverage-groups{align-items:start}}
</style>
