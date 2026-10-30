<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {sampleDomain,sampleDomains,sampleReceiptLabel,researchSampleProgress} from '@/utils/sampleDomains'
import {useUserStore} from '@/stores/user'
import type {BusinessData} from '@/types/partner'
import {launchStage} from '@/constants/productResearch'
const props=defineProps<{sample:BusinessData}>(),route=useRoute(),router=useRouter(),user=useUserStore()
const domain=computed(()=>sampleDomain(props.sample.sampleDomain))
const project=computed(()=>props.sample.sourceSnapshot?.project)
function source(){
 const purpose=props.sample.sourceSnapshot?.round?.details?.samplePurpose;
 const step=purpose==='prototype_3d'?launchStage(3):purpose==='mold_trial'?launchStage(6):undefined;
 void router.push({path:domain.value==='research'?'/product-research':'/projects',query:{productId:props.sample.projectId,...(domain.value==='research'&&step?{step:step.key}:{}),from:route.fullPath}})
}
</script>
<template>
 <section class="sample-source-summary" :aria-label='uiText("样品业务来源")'>
  <div><el-tag size="small">{{uiText(sampleDomains[domain])}}</el-tag><strong>{{uiText(sampleReceiptLabel(sample))}}</strong><span v-if="sample.sampleVersion">{{ uiText("版本 ") }}{{sample.sampleVersion}}</span></div>
  <template v-if="sample.sourceType==='model'">
   <p>{{ uiText("所属产品：") }}{{sample.sourceSnapshot?.product?.name}} · {{sample.sourceSnapshot?.phone?.modelName}}{{ uiText(" · 手机编码 ") }}{{sample.sourceSnapshot?.phone?.phoneModelCode||uiText('待分配')}}</p>
   <p>{{ uiText("实际来源：") }}{{sample.provenance}}<span v-if="sample.assetNumber">{{ uiText(" · 原物料／资产编号 ") }}{{sample.assetNumber}}</span></p>
   <p v-if="sample.projectId">{{ uiText("来源项目：") }}{{project?.name}}<span v-if="sample.moldSetId">{{ uiText(" · 第 ") }}{{sample.sourceSnapshot?.moldSet?.setNo}}{{ uiText(" 套 · ") }}{{sample.trialBatch}}</span></p>
  </template>
  <p v-if="sample.sourceType==='device'">{{sample.sourceSnapshot?.phone?.modelName}}{{ uiText(" · 手机编码 ") }}{{sample.sourceSnapshot?.phone?.phoneModelCode||uiText('待分配')}} · {{sample.provenance}}<span v-if="sample.assetNumber">{{ uiText(" · 原物料／资产编号 ") }}{{sample.assetNumber}}</span></p>
  <p v-if="sample.sourceType==='trial'">{{project?.name}}{{ uiText(" · 第 ") }}{{sample.sourceSnapshot?.moldSet?.setNo}}{{ uiText(" 套 · ") }}{{sample.trialBatch}}<span v-if="sample.sourceSnapshot?.moldSet?.engravedCode">{{ uiText(" · 原刻码 ") }}{{sample.sourceSnapshot.moldSet.engravedCode}}</span></p>
  <p v-if="domain==='research'&&sample.roundId">{{project?.name}} · S{{sample.sourceSnapshot?.round?.no}} · {{sample.sourceSnapshot?.round?.title}}</p>
  <p v-if="domain==='research'">{{ uiText("原研发进度：") }}{{uiText(researchSampleProgress(sample.researchProgress))}}</p>
  <p v-if="sample.sourceType==='trial'"><span v-for="part in sample.sourceSnapshot?.parts||[]" :key="part.partType">{{part.partType}}{{ uiText(" 原刻码：") }}{{part.code||uiText('待补')}}　</span></p><p v-if="sample.purpose">{{ uiText("用途：") }}{{sample.purpose}}</p>
  <el-button v-if="sample.projectId&&user.hasPermission('project:view')" link type="primary" @click="source">{{domain==='research'?uiText('查看原打样与验证'):uiText('查看项目、模具及验模进度')}}</el-button>
  <el-button v-if="sample.moldSetId&&user.hasPermission('schedule:read')" link type="primary" @click="router.push({path:'/mold-schedules',query:{moldSetId:sample.moldSetId,projectId:sample.projectId,from:route.fullPath}})">{{ uiText("查看本套排程") }}</el-button><p v-if="domain==='model'" class="muted">{{ uiText("本处记录实物与借还；适配、验模结论继续在原项目办理，收样不会自动变更模具完成状态或累计产量。") }}</p>
 </section>
</template>
<style scoped>.sample-source-summary{padding:8px 10px;background:var(--el-fill-color-light);border:1px solid var(--el-border-color-lighter);border-radius:4px;font-size:12px;overflow-wrap:anywhere}.sample-source-summary>div{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.sample-source-summary p{margin:5px 0;line-height:1.6}.muted{color:var(--el-text-color-secondary)}</style>
