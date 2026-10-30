<script setup lang="ts">
import { t as uiText } from '@/locales'

import {ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import ArchiveLedger from './components/ArchiveLedger.vue'
import MoldSalesOverview from './components/MoldSalesOverview.vue'
import {productionFields} from '@/utils/collaboration'
defineOptions({name:'ProductionTotalsView'})
const router=useRouter(),tab=ref('sales'),manualOpen=ref(false),manualItemId=ref<number>()
const salesView=ref<InstanceType<typeof MoldSalesOverview>>(),productionView=ref<InstanceType<typeof MoldSalesOverview>>()
watch(tab,value=>{void (value==='sales'?salesView.value:productionView.value)?.refresh()})
function maintain(id?:number){manualItemId.value=id;manualOpen.value=true}
</script>
<template>
 <el-tabs v-model="tab" class="production-tabs">
  <el-tab-pane :label='uiText("销量与成本")' name="sales" lazy><MoldSalesOverview ref="salesView"/></el-tab-pane>
  <el-tab-pane :label='uiText("累计产量记录")' name="production" lazy><MoldSalesOverview ref="productionView" mode="production" @maintain="maintain"><template #tools><el-button size="small" @click="maintain()">{{ uiText("补录／更正产量") }}</el-button></template></MoldSalesOverview></el-tab-pane>
 </el-tabs>
 <el-drawer v-model="manualOpen" class="manual-production" :title='uiText("补录与更正产量")' size="min(1280px,96vw)">
  <p class="manual-note">{{ uiText("这里维护已有人工累计及更正历史。主页面将各项与已计入的历史及 CRM 订单数量取较大值，不重复相加。订单量是累计依据，不是实际生产记录。关闭后未保存的草稿仍保留。") }}</p>
  <ArchiveLedger class="manual-ledger" kind="production" :title='uiText("人工累计原始记录")' :initial-item-id="manualItemId" :field-keys="productionFields" @action="(_,row)=>router.push({path:'/market-watch',query:{itemId:row.itemId,panel:'market-data'}})"><template #help><p>{{ uiText("PC、TPU 和成品分别填写累计总数；空白是未知，0 是确认零产量。下调需填写原因，保留原始值与更正历史。") }}</p></template></ArchiveLedger>
 </el-drawer>
</template>
<style scoped>
.production-tabs{height:100%;min-height:0;display:flex;flex-direction:column}.production-tabs :deep(.el-tabs__header){margin:0 0 6px;flex:none}.production-tabs :deep(.el-tabs__content){flex:1;min-height:0;overflow:hidden}.production-tabs :deep(.el-tab-pane){height:100%;min-height:0}.manual-production :deep(.el-drawer__body){display:flex;flex-direction:column;gap:8px;min-height:0;overflow:hidden}.manual-note{margin:0;font-size:12px;color:#6e7d91;line-height:18px}.manual-ledger{flex:1;min-height:0}
</style>
