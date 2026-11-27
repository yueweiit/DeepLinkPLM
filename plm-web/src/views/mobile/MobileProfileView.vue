<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, SwitchButton } from '@element-plus/icons-vue'
import { changeOwnPassword } from '@/api/modules/auth'
import LanguageSwitcher from '@/components/LanguageSwitcher/index.vue'
import { t } from '@/locales'
import { useUserStore } from '@/stores/user'
import { confirmLogout } from '@/utils/confirmLogout'

const user = useUserStore()
const router = useRouter()
const profile = computed(() => user.profile)
const displayName = computed(() => profile.value?.userName || t('PLM 用户'))
const initial = computed(() => displayName.value.trim().slice(0, 1).toLocaleUpperCase())
const passwordDialog = ref(false)
const saving = ref(false)
const confirmMismatch = ref(false)
const formRef = ref<FormInstance>()
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const passwordRules: FormRules = {
  currentPassword: [{ required: true, message: t('请输入当前密码'), trigger: 'blur' }],
  newPassword: [
    { required: true, message: t('请输入新密码'), trigger: 'blur' },
    { min: 12, message: t('新密码至少12位'), trigger: 'blur' },
    { max: 72, message: t('请输入72字节以内的新密码'), trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value && value === passwordForm.currentPassword) callback(new Error(t('新密码不能与当前密码相同')))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

watch([() => passwordForm.newPassword, () => passwordForm.confirmPassword], () => {
  if (confirmMismatch.value && passwordForm.newPassword === passwordForm.confirmPassword) confirmMismatch.value = false
})

function openPasswordDialog() {
  Object.assign(passwordForm, { currentPassword: '', newPassword: '', confirmPassword: '' })
  confirmMismatch.value = false
  passwordDialog.value = true
}

function resetPasswordForm() {
  Object.assign(passwordForm, { currentPassword: '', newPassword: '', confirmPassword: '' })
  confirmMismatch.value = false
  formRef.value?.clearValidate()
}

async function submitPasswordChange() {
  if (passwordForm.confirmPassword !== passwordForm.newPassword) {
    confirmMismatch.value = true
    return
  }
  confirmMismatch.value = false
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    await changeOwnPassword({ currentPassword: passwordForm.currentPassword, newPassword: passwordForm.newPassword })
  } catch {
    return
  } finally {
    saving.value = false
  }

  passwordDialog.value = false
  ElMessage.success(t('密码修改成功，请重新登录'))
  try {
    await user.logout()
  } finally {
    await router.replace('/login')
  }
}

async function logout() {
  await confirmLogout(async () => {
    const result = await user.logout()
    await router.replace('/login')
    if (result.serverRevoked) ElMessage.success(t('退出成功'))
    else ElMessage.warning(t('本机已退出，服务端注销未确认'))
  })
}
</script>

<template>
  <section class="mobile-profile" :aria-label="t('个人中心')">
    <header class="mobile-profile__identity">
      <span class="mobile-profile__avatar" aria-hidden="true">{{ initial }}</span>
      <div class="mobile-profile__identity-copy">
        <span class="mobile-profile__brand">DEEPLINK PLM</span>
        <h1>{{ displayName }}</h1>
        <p>{{ t(profile?.roleName || '角色未分配') }}<span aria-hidden="true"> · </span>{{ t(profile?.department || '未分配部门') }}</p>
      </div>
    </header>

    <section class="mobile-profile__section" aria-labelledby="mobile-profile-account">
      <div class="mobile-profile__section-heading">
        <h2 id="mobile-profile-account">{{ t('账号信息') }}</h2>
      </div>
      <dl class="mobile-profile__details">
        <div><dt>{{ t('姓名') }}</dt><dd>{{ displayName }}</dd></div>
        <div><dt>{{ t('部门') }}</dt><dd>{{ t(profile?.department || '未分配部门') }}</dd></div>
        <div><dt>{{ t('角色') }}</dt><dd>{{ t(profile?.roleName || '角色未分配') }}</dd></div>
      </dl>
    </section>

    <section class="mobile-profile__settings" :aria-label="t('个人中心')">
      <div class="mobile-profile__setting-row" aria-labelledby="mobile-profile-language">
        <div class="mobile-profile__section-copy">
          <h2 id="mobile-profile-language">{{ t('语言设置') }}</h2>
          <p>{{ t('选择界面显示语言') }}</p>
        </div>
        <LanguageSwitcher />
      </div>
      <div class="mobile-profile__setting-row mobile-profile__security" aria-labelledby="mobile-profile-security">
        <div class="mobile-profile__section-copy">
          <h2 id="mobile-profile-security">{{ t('账号安全') }}</h2>
          <p>{{ t('修改密码可更新当前账号的登录凭据') }}</p>
        </div>
      </div>
    </section>

    <div class="mobile-profile__actions">
      <el-button class="mobile-profile__password" type="primary" :icon="Lock" @click="openPasswordDialog">{{ t('修改密码') }}</el-button>
      <el-button class="mobile-profile__logout" :icon="SwitchButton" @click="logout">{{ t('退出登录') }}</el-button>
    </div>

    <el-dialog
      v-model="passwordDialog"
      class="mobile-profile__password-dialog"
      :title="t('修改登录密码')"
      width="460px"
      :close-on-click-modal="false"
      destroy-on-close
      @closed="resetPasswordForm"
    >
      <el-form ref="formRef" :model="passwordForm" :rules="passwordRules" label-position="top" @submit.prevent>
        <el-form-item :label="t('当前登录密码')" prop="currentPassword">
          <el-input v-model="passwordForm.currentPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item :label="t('新密码')" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item :label="t('确认新密码')" prop="confirmPassword" :error="confirmMismatch ? t('两次输入的密码不一致') : ''">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <p v-if="confirmMismatch" class="mobile-profile__password-error">{{ t('两次输入的密码不一致') }}</p>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialog = false">{{ t('取消') }}</el-button>
        <el-button type="primary" :loading="saving" @click="submitPasswordChange">{{ t('保存新密码') }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.mobile-profile{display:flex;width:100%;max-width:720px;min-height:calc(100dvh - 170px);flex-direction:column;gap:14px;margin:0 auto;color:#24343d}
.mobile-profile__identity{display:flex;min-width:0;align-items:center;gap:16px;padding:18px;border:1px solid #d6e5df;border-left:4px solid #287469;border-radius:8px;background:#eaf3f0}
.mobile-profile__avatar{display:grid;flex:0 0 58px;width:58px;height:58px;place-items:center;border:1px solid #c6ddd5;border-radius:50%;background:#fff;color:#236b61;font-size:22px;font-weight:650}
.mobile-profile__identity-copy{min-width:0}
.mobile-profile__brand{display:block;margin-bottom:4px;color:#517b73;font-size:10px;font-weight:700;line-height:1.2}
.mobile-profile h1{margin:0;color:#1f3d3b;font-size:20px;line-height:1.3;font-weight:650;overflow-wrap:anywhere}
.mobile-profile__identity p{margin:5px 0 0;color:#617a74;font-size:12px;line-height:1.45;overflow-wrap:anywhere}
.mobile-profile__section,.mobile-profile__settings{padding:16px;border:1px solid #e1e9e6;border-radius:8px;background:#fff}
.mobile-profile h2{margin:0;color:#293b43;font-size:14px;line-height:1.4;font-weight:650}
.mobile-profile__section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}
.mobile-profile__section-heading>span{color:#7d8c88;font-size:11px}
.mobile-profile__details{margin:10px 0 0}
.mobile-profile__details div{display:grid;grid-template-columns:minmax(76px,30%) minmax(0,1fr);gap:12px;padding:11px 0;border-top:1px solid #edf1ef}
.mobile-profile__details dt{color:#71807b;font-size:12px}
.mobile-profile__details dd{min-width:0;margin:0;color:#293b43;font-size:13px;font-weight:550;overflow-wrap:anywhere}
.mobile-profile__setting-row{display:flex;min-width:0;align-items:center;justify-content:space-between;gap:14px}
.mobile-profile__setting-row+.mobile-profile__setting-row{margin-top:14px;padding-top:14px;border-top:1px solid #edf1ef}
.mobile-profile__section-copy{min-width:0}
.mobile-profile__section-copy p{margin:4px 0 0;color:#71807b;font-size:12px;line-height:1.4;overflow-wrap:anywhere}
.mobile-profile__setting-row :deep(.language-switcher){width:112px;max-width:112px;min-height:38px}
.mobile-profile__actions{display:grid;grid-template-columns:minmax(0,1fr);gap:8px;margin-top:0;padding-top:0}
.mobile-profile__actions :deep(.el-button){width:100%;min-width:0;min-height:48px;margin:0;font-weight:600}
.mobile-profile__password{border-color:#236b61;background:#236b61}
.mobile-profile__password:hover,.mobile-profile__password:focus-visible{border-color:#1d5a52;background:#1d5a52}
.mobile-profile__logout{border-color:#efcdcd;background:#fff;color:#bd3434}
.mobile-profile__logout:hover,.mobile-profile__logout:focus-visible{border-color:#dca8a8;background:#fff3f2;color:#a62e2e}
.mobile-profile__password-error{margin:-14px 0 12px;color:#c53b3b;font-size:12px;line-height:1.4}
@media(max-width:359px){.mobile-profile__identity{align-items:flex-start;padding:14px}.mobile-profile__actions{grid-template-columns:1fr}.mobile-profile__setting-row{align-items:flex-start;flex-direction:column}.mobile-profile__setting-row :deep(.language-switcher){width:100%;max-width:100%}}
</style>
