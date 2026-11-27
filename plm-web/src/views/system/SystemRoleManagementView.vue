<script setup lang="ts">
import { t as uiText } from '@/locales'

import { onMounted, ref } from 'vue'
import { getSystemRoles } from '@/api/modules/system'
import type { PilotRoleDefinition } from '@/types/pilot'
import { pilotRoleNames } from '@/types/pilot'
import PageContainer from '@/components/PageContainer/index.vue'
const roles=ref<PilotRoleDefinition[]>([]),error=ref('')
onMounted(async()=>{try{roles.value=await getSystemRoles()}catch{error.value='固定角色查询失败'}})
</script>
<template>
  <PageContainer :title='uiText("试点固定角色")' :description='uiText("一期不创建任意角色。在账号与权限页配置真实账号、对象范围及角色内独立权限。")'>
    <el-alert v-if="error" :title="error" type="error" />
    <el-table :data="roles" border class="role-table">
      <el-table-column :label='uiText("角色")' width="160">
        <template #default="{ row }">{{ uiText(pilotRoleNames[row.roleCode as keyof typeof pilotRoleNames]) }}</template>
      </el-table-column>
      <el-table-column :label='uiText("可授权操作")'>
        <template #default="{ row }"><el-tag v-for="permission in row.permissions" :key="permission" class="permission">{{ permission }}</el-tag></template>
      </el-table-column>
    </el-table>
    <section class="role-cards" aria-label="角色及权限">
      <article v-for="role in roles" :key="role.roleCode" class="role-card">
        <h2>{{ uiText(pilotRoleNames[role.roleCode as keyof typeof pilotRoleNames]) }}</h2>
        <div class="role-card__permissions">
          <el-tag v-for="permission in role.permissions" :key="permission" effect="plain">{{ permission }}</el-tag>
        </div>
      </article>
    </section>
  </PageContainer>
</template>
<style scoped>
.permission{margin:5px}
.role-cards{display:none}
.role-card{padding:12px;border:1px solid var(--el-border-color-lighter);border-radius:8px;background:var(--el-bg-color)}
.role-card h2{margin:0 0 10px;font-size:15px}
.role-card__permissions{display:flex;flex-wrap:wrap;gap:6px}
@media(max-width:680px){.role-table{display:none}.role-cards{display:grid;gap:10px}}
</style>
