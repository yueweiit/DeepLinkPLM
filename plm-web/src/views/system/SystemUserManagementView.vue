<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useElementSize } from '@vueuse/core'
import { ElMessage } from 'element-plus'
import { getSystemUsers, getSystemRoles, saveSystemUser, getSystemScopeOptions } from '@/api/modules/system'
import PageContainer from '@/components/PageContainer/index.vue'
import PhoneObjectIdSelect from '@/components/PhoneObjectSelect/IdSelect.vue'
import type { PhoneObjectOption, ObjectPhone } from '@/components/PhoneObjectSelect/types'
import FixedTableViewport from '@/components/FixedTableViewport/index.vue'
import type { PilotUser, PilotUserInput, PilotRoleDefinition, PilotEntity } from '@/types/pilot'
import { pilotRoleNames } from '@/types/pilot'
import { languages, normalizeLanguage, t } from '@/locales'
import { saveUserLanguage } from '@/api/modules/pilot'
import { useUserStore } from '@/stores/user'
const userStore = useUserStore()
const listElement=ref<HTMLElement|null>(null)
const {width:listWidth}=useElementSize(listElement)
const compact=computed(()=>listWidth.value>0&&listWidth.value<1000)
const languageSaving = ref<number|null>(null)
async function changeUserLanguage(user:PilotUser,event:Event){
 const select=event.target as HTMLSelectElement,language=normalizeLanguage(select.value),before=normalizeLanguage(user.preferredLanguage)
 select.value=before
 if(language===before)return
 languageSaving.value=user.userId
 try{const saved=await saveUserLanguage(user.userId,language,before);user.preferredLanguage=saved.preferredLanguage;if(userStore.profile?.userId===user.userId)await userStore.restore();ElMessage.success(t('language.saved'))}
 catch{ElMessage.error(t('language.saveFailed'))}
 finally{languageSaving.value=null}
}
const users=ref<PilotUser[]>([]),roles=ref<PilotRoleDefinition[]>([]),loading=ref(false),saving=ref(false),drawer=ref(false),keyword=ref(''),editing=ref<number|null>(null)
watch(() => userStore.profile?.preferredLanguage, language => {
 const current = users.value.find(user => user.userId === userStore.profile?.userId)
 if (current) current.preferredLanguage = normalizeLanguage(language)
})
const error=ref(''),scopeType=ref<PilotEntity>('mold'),scopeSearch=ref(''),scopeId=ref<number|null>(null),scopeOptions=ref<{id:number;label:string;phoneModels?:ObjectPhone[]}[]>([])
const form=reactive<PilotUserInput>({username:'',displayName:'',departmentName:'',roleCode:'domestic',status:'active',allObjects:true,dingtalkUserId:'',permissions:[],fieldGroups:[],scopes:[],password:'',beforeVersion:null})
const filtered=computed(()=>users.value.filter(u=>[u.username,u.displayName,u.departmentName].join(' ').toLowerCase().includes(keyword.value.trim().toLowerCase())))
const administrator=computed(()=>form.roleCode==='administrator')
const external=computed(()=>['supplier','mexico'].includes(form.roleCode))
const rolePermissionOptions=computed(()=>roles.value.find(r=>r.roleCode===form.roleCode)?.permissions||[])
const groups:Record<string,string>={market:'市场',engineering:'工程',logistics:'物流',mexico:'墨西哥',production:'产量'}
const entityNames:Record<string,string>={item:'协作档案',mold:'模具套',product:'产品项目',file:'文件附件',partner:'合作产品／销售／样品',customer:'客户',supplier_profile:'供应商'}
const phoneScopeObjects=computed<PhoneObjectOption[]>(()=>scopeOptions.value.map(o=>({type:scopeType.value,id:o.id,name:o.label,code:`#${o.id}`,phoneModels:o.phoneModels||[]})))
async function load(){loading.value=true;error.value='';try{[users.value,roles.value]=await Promise.all([getSystemUsers(),getSystemRoles()])}catch{error.value='真实账号加载失败，请重试'}finally{loading.value=false}}
function setRole(){form.permissions=[...rolePermissionOptions.value];form.allObjects=!external.value;form.fieldGroups=administrator.value?Object.keys(groups):form.roleCode==='supplier'?['engineering','logistics']:form.roleCode==='mexico'?['mexico']:form.roleCode==='domestic'?['market','engineering','logistics','production']:[]}
function open(user?:PilotUser){editing.value=user?.userId||null;Object.assign(form,{username:user?.username||'',displayName:user?.displayName||'',departmentName:user?.departmentName||'',roleCode:user?.allPermissions?'administrator':user?.roleCode||'domestic',status:user?.status||'active',allObjects:user?.allObjects??true,dingtalkUserId:user?.dingtalkUserId||'',permissions:[...(user?.permissions||[])],fieldGroups:[...(user?.fieldGroups||[])],scopes:user?.scopes.map(s=>({...s}))||[],password:'',beforeVersion:user?.version||null});if(administrator.value||!user?.roleCode)setRole();scopeId.value=null;scopeOptions.value=[];drawer.value=true}
async function searchScopes(){scopeOptions.value=await getSystemScopeOptions(scopeType.value,scopeSearch.value)}
function addScope(){if(scopeId.value&&!form.scopes.some(s=>s.entityType===scopeType.value&&s.entityId===scopeId.value))form.scopes.push({entityType:scopeType.value,entityId:scopeId.value})}
async function save(){if(!form.username.trim()||!form.displayName.trim()||!form.departmentName.trim())return ElMessage.warning(uiText('请填写账号、姓名和部门'));if(!editing.value&&form.password.length<12)return ElMessage.warning(uiText('新账号密码至少12位'));saving.value=true;try{await saveSystemUser(editing.value,{...form,allObjects:external.value?false:form.allObjects});form.password='';drawer.value=false;await load();ElMessage.success(uiText('真实账号与授权已保存'))}catch{error.value='保存失败或授权已被他人修改，请核对后重试'}finally{saving.value=false}}
onMounted(load)
</script>
<template>
 <PageContainer :title='uiText("账号与权限")' :description='uiText("复用真实登录账号；固定试点角色、字段职责、独立金额操作及逐对象授权。")'>
  <template #actions><el-button @click="load">{{ uiText("刷新") }}</el-button><el-button type="primary" @click="open()">{{ uiText("新增真实账号") }}</el-button></template>
  <el-alert v-if="error" :title="uiText(error)" type="error" :closable="false"/>
  <el-input v-model="keyword" :placeholder='uiText("搜索真实账号 / 姓名 / 部门")' clearable/>
  <section ref="listElement" class="users-list">
  <FixedTableViewport v-if="!compact" v-slot="{tableHeight}" :refresh-key="filtered"><el-table :data="filtered" :height="tableHeight" v-loading="loading" border>
   <el-table-column prop="username" :label="t('登录账号')" min-width="140"/><el-table-column prop="displayName" :label="t('姓名')" min-width="140"/><el-table-column prop="departmentName" :label="t('部门')" min-width="130"/>
   <el-table-column :label="t('language.default')" min-width="120"><template #default="{row}"><select :value="row.preferredLanguage||'zh-CN'" :aria-label="t('language.default')+' '+row.displayName" :disabled="languageSaving===row.userId" @change="changeUserLanguage(row,$event)"><option v-for="language in languages" :key="language.value" :value="language.value">{{language.label}}</option></select></template></el-table-column>
   <el-table-column :label='uiText("固定角色")' width="140"><template #default="{row}">{{row.allPermissions?uiText('管理员'):row.roleCode?uiText(pilotRoleNames[row.roleCode as keyof typeof pilotRoleNames]):uiText('历史兼容账号')}}</template></el-table-column>
   <el-table-column :label='uiText("启用状态")' width="100"><template #default="{row}">{{uiText(row.status==='active'?'启用':'停用')}}</template></el-table-column><el-table-column :label='uiText("对象范围")' min-width="190"><template #default="{row}">{{row.allObjects||row.allPermissions?uiText('全部授权对象'):row.scopes.length+uiText(' 个指定对象')}}</template></el-table-column>
   <el-table-column :label='uiText("操作")' width="140" fixed="right"><template #default="{row}"><el-button link type="primary" @click="open(row)">{{ uiText("编辑 / 授权") }}</el-button></template></el-table-column>
  </el-table></FixedTableViewport>
  <div v-else class="users-cards" v-loading="loading">
   <article v-for="user in filtered" :key="user.userId" class="user-card">
    <div class="user-card__heading"><div><strong>{{user.displayName}}</strong><span>{{user.username}} · {{user.departmentName}}</span></div><el-button link type="primary" @click="open(user)">{{uiText('编辑 / 授权')}}</el-button></div>
    <div class="user-card__meta"><span>{{user.allPermissions?uiText('管理员'):user.roleCode?uiText(pilotRoleNames[user.roleCode]):uiText('历史兼容账号')}}</span><span>{{uiText(user.status==='active'?'启用':'停用')}}</span><span>{{user.allObjects||user.allPermissions?uiText('全部授权对象'):user.scopes.length+uiText(' 个指定对象')}}</span></div>
    <label class="user-card__language">{{t('language.default')}}<select :value="user.preferredLanguage||'zh-CN'" :aria-label="t('language.default')+' '+user.displayName" :disabled="languageSaving===user.userId" @change="changeUserLanguage(user,$event)"><option v-for="language in languages" :key="language.value" :value="language.value">{{language.label}}</option></select></label>
   </article>
   <el-empty v-if="!loading&&!filtered.length" :description="uiText('暂无数据')"/>
  </div>
  </section>
  <el-drawer v-model="drawer" :title="editing?uiText('编辑真实账号与授权'):uiText('新增真实账号')" size="min(1000px,95vw)" :close-on-click-modal="false">
   <el-form label-position="top" class="account-form">
    <el-form-item :label='uiText("登录账号（创建后固定）")'><el-input v-model="form.username" :disabled="!!editing" autocomplete="off"/></el-form-item>
    <el-form-item :label='uiText("姓名")'><el-input v-model="form.displayName"/></el-form-item><el-form-item :label='uiText("部门 / 公司")'><el-input v-model="form.departmentName"/></el-form-item>
    <el-form-item :label='uiText("固定角色")'><el-select v-model="form.roleCode" @change="setRole"><el-option v-for="r in roles" :key="r.roleCode" :value="r.roleCode" :label="uiText(pilotRoleNames[r.roleCode])"/></el-select></el-form-item>
    <el-alert v-if="administrator" class="wide" type="info" :closable="false" :title='uiText("管理员拥有全部对象、所有字段、全部金额操作及账号配置权限；要收权请先切换为其他固定角色。")'/>
    <el-form-item :label='uiText("启用 / 停用")'><el-select v-model="form.status"><el-option value="active" :label='uiText("启用")'/><el-option value="inactive" :label='uiText("停用（已有会话亦失效）")'/></el-select></el-form-item>
    <el-form-item :label="editing?uiText('重设密码（留空不修改）'):uiText('初始密码（至少12位）')"><el-input v-model="form.password" type="password" show-password autocomplete="new-password"/></el-form-item>
    <el-form-item :label='uiText("真实钉钉 UserID（管理员核对映射）")'><el-input v-model="form.dingtalkUserId"/></el-form-item>
    <el-form-item v-if="!external" :label='uiText("对象范围")'><el-checkbox v-model="form.allObjects" :disabled="administrator">{{administrator?uiText('全部对象（管理员固定）'):uiText('全部对象（国内 / 财务）')}}</el-checkbox></el-form-item>
    <el-form-item :label='uiText("独立操作权限")' class="wide"><el-checkbox-group v-model="form.permissions" :disabled="administrator"><el-checkbox v-for="p in rolePermissionOptions" :key="p" :value="p">{{p}}</el-checkbox></el-checkbox-group><small>{{administrator?uiText('管理员的 * 表示全部权限，不能通过取消勾选收权。'):uiText('金额查看、编辑、确认、导出可分别取消，不能超出固定角色。')}}</small></el-form-item>
    <el-form-item :label='uiText("字段填写职责")' class="wide"><el-checkbox-group v-model="form.fieldGroups" :disabled="administrator"><el-checkbox v-for="(label,g) in groups" :key="g" :value="g">{{uiText(label)}}</el-checkbox></el-checkbox-group><small>{{administrator?uiText('管理员可填写所有字段组，固定角色切换后才可配置较小职责范围。'):uiText('供应商工程 / 物流；墨西哥仅签收验收，仍受手机动作白名单限制。')}}</small></el-form-item>
    <el-form-item :label='uiText("指定对象授权（附件须逐份开放）")' class="wide"><div class="scope-picker"><el-select v-model="scopeType" :disabled="administrator" @change="scopeOptions=[];scopeId=null"><el-option v-for="(label,v) in entityNames" :key="v" :value="v" :label="uiText(label)"/></el-select><el-input v-model="scopeSearch" :disabled="administrator" :placeholder='uiText("搜索名称或编码")' @keyup.enter="searchScopes"/><el-button :disabled="administrator" @click="searchScopes">{{ uiText("查询") }}</el-button></div><div class="scope-picker"><PhoneObjectIdSelect v-if="['item','mold','product'].includes(scopeType)" v-model="scopeId" :disabled="administrator" :options="phoneScopeObjects" :type="scopeType" :label='uiText("授权业务对象")' :scope-key="scopeType"/><el-select v-else v-model="scopeId" :disabled="administrator" :placeholder='uiText("选择真实对象")' filterable><el-option v-for="o in scopeOptions" :key="o.id" :value="o.id" :label="o.label"/></el-select><el-button :disabled="administrator" @click="addScope">{{ uiText("加入授权") }}</el-button></div><el-tag v-for="(s,index) in form.scopes" :key="s.entityType+'-'+s.entityId" :closable="!administrator" @close="form.scopes.splice(index,1)">{{uiText(entityNames[s.entityType])}} #{{s.entityId}}</el-tag><small v-if="administrator">{{ uiText("管理员访问全部对象及附件，已有指定对象记录不限制其访问。") }}</small></el-form-item>
   </el-form><template #footer><el-button @click="drawer=false;form.password=''">{{ uiText("取消未保存修改") }}</el-button><el-button type="primary" :loading="saving" @click="save">{{ uiText("保存真实账号与授权") }}</el-button></template>
  </el-drawer>
 </PageContainer>
</template>
<style scoped>
.users-list{flex:1;min-height:0;display:flex;flex-direction:column}
.users-cards{overflow-y:auto;display:grid;gap:10px;align-content:start}
.user-card{padding:12px;background:var(--el-bg-color);border:1px solid var(--el-border-color-lighter);border-radius:8px;overflow-wrap:anywhere}
.user-card__heading{display:flex;gap:10px;align-items:flex-start;justify-content:space-between}
.user-card__heading strong,.user-card__heading span{display:block}
.user-card__heading span,.user-card__meta{color:var(--el-text-color-secondary);font-size:13px}
.user-card__meta{display:flex;gap:6px 14px;flex-wrap:wrap;margin:8px 0}
.user-card__language{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.users-list select{font:inherit;min-height:30px;padding:3px 8px;border:1px solid var(--el-border-color);border-radius:4px;color:var(--el-text-color-primary);background:var(--el-bg-color)}
.account-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px 24px}.wide{grid-column:1/-1}.scope-picker{display:flex;gap:8px;width:100%;margin-bottom:10px}.scope-picker .el-select{min-width:180px;flex:1}.el-tag{margin:4px}.el-checkbox-group{display:flex;gap:8px;flex-wrap:wrap}small{display:block;color:#64748b;line-height:1.6}@media(max-width:650px){.account-form{grid-template-columns:1fr}.scope-picker{flex-wrap:wrap}}</style>
