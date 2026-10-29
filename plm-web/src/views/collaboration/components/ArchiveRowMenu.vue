<script setup lang="ts">
import { MoreFilled } from '@element-plus/icons-vue'
import type { CollaborationRow } from '@/types/collaboration'
defineProps<{row:CollaborationRow;dirty:boolean}>()
const emit=defineEmits<{action:[command:string,row:CollaborationRow]}>()
</script>
<template>
  <el-dropdown trigger="click" @command="emit('action',$event,row)">
    <el-button size="small" text :icon="MoreFilled" :aria-label="`操作 ${row.compatibleModels}`" />
    <template #dropdown><el-dropdown-menu>
      <slot :row="row" />
      <el-dropdown-item command="platform" :disabled="!row.productLineId">产品平台资料</el-dropdown-item>
      <el-dropdown-item command="phone">品牌 / 兼容手机资料</el-dropdown-item>
      <el-dropdown-item command="molds">模具台账</el-dropdown-item>
      <el-dropdown-item command="schedule">开模时间管控</el-dropdown-item>
      <el-dropdown-item command="references">项目 / 文件 / 物流</el-dropdown-item>
      <el-dropdown-item command="history">修改历史</el-dropdown-item>
      <el-dropdown-item v-if="dirty" command="reason">更正 / 核对说明</el-dropdown-item>
    </el-dropdown-menu></template>
  </el-dropdown>
</template>
