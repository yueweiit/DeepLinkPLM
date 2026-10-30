<script setup lang="ts">
import { t as uiText } from '@/locales'

import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { operationTasks, operationTarget, type OperationKind } from '@/api/modules/partnerOperations'
import { partnerError } from '@/utils/partnerError'
import type { PartnerRow } from '@/types/partner'
import { operationLabel } from './presentation'
const router = useRouter(), route = useRoute(), rows = ref<PartnerRow[]>([]), error = ref(''), loading = ref(false)
let generation = 0
async function refresh() { const ticket = ++generation; loading.value = true; error.value = ''; try { const result = await operationTasks(); if (ticket === generation) rows.value = result } catch (e) { if (ticket === generation) error.value = partnerError(e) } finally { if (ticket === generation) loading.value = false } }
onMounted(refresh); onBeforeUnmount(() => { generation++ }); defineExpose({ refresh })
</script>
<template><details class="partner-task-panel" open><summary>{{ uiText("合作业务待办 · ") }}{{ rows.length }}{{ uiText(" 条（最多显示最近100条，按岗位及对象范围）") }}</summary><el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="refresh">{{ uiText("重新加载") }}</el-button></el-alert><div class="task-records" v-loading="loading"><p v-if="!rows.length && !loading" class="hint">{{ uiText("当前没有待办理的合作业务。") }}</p><article v-for="row in rows" :key="row.id"><el-button link type="primary" @click="router.push(operationTarget(row.kind as OperationKind,row.id,route.fullPath))">{{ row.name }} · {{ row.code }}</el-button><span>{{ uiText(operationLabel(row.status)) }}</span></article></div></details></template>
<style scoped>.partner-task-panel{flex:none;border:1px solid #e3eaf3;border-radius:7px;padding:8px 12px;background:#fff;min-width:0}summary{cursor:pointer;font-size:13px;line-height:1.8;color:#506781}.task-records{max-height:210px;overflow:auto}article{display:flex;gap:10px;align-items:center;justify-content:space-between;flex-wrap:wrap;padding:6px 0;border-top:1px solid #edf1f6;font-size:13px;overflow-wrap:anywhere}article :deep(.el-button){height:auto;white-space:normal;text-align:left;line-height:1.6;max-width:100%;min-width:0}.hint{font-size:13px;color:#708297}</style>
