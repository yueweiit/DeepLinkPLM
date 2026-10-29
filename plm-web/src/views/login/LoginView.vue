<script setup lang="ts">
import {sampleAwareLoginTarget} from '@/utils/sampleScanRoute'
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, Connection, Lock, User } from '@element-plus/icons-vue'

import { useUserStore } from '@/stores/user'
import { t } from '@/locales'
import brandLogo from '@/assets/brand/deeplinkplm-logo.png'
import { getPilotSsoStatus } from '@/api/modules/pilot'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const sso=ref({enabled:false,corpId:'',message:'正在读取钉钉免登配置'})
type DingApi={ready:(fn:()=>void)=>void;runtime:{permission:{requestAuthCode:(options:{corpId:string;onSuccess:(result:{code:string})=>void;onFail:()=>void})=>void}}}
async function dingApi():Promise<DingApi>{
 const current=(window as Window&{dd?:DingApi}).dd;if(current)return current
 await new Promise<void>((resolve,reject)=>{const script=document.createElement('script');script.src='https://g.alicdn.com/dingding/dingtalk-jsapi/2.0.0/dingtalk.open.js';script.onload=()=>resolve();script.onerror=()=>reject(new Error('钉钉SDK加载失败'));document.head.appendChild(script)})
 const dd=(window as Window&{dd?:DingApi}).dd;if(!dd)throw new Error('请在已配置的钉钉轻应用内免登');return dd
}
async function handleDingLogin(){loading.value=true;try{const dd=await dingApi();const code=await new Promise<string>((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('钉钉免登超时，请在应用内重试')),15000);dd.ready(()=>dd.runtime.permission.requestAuthCode({corpId:sso.value.corpId,onSuccess:r=>{clearTimeout(timer);resolve(r.code)},onFail:()=>{clearTimeout(timer);reject(new Error('钉钉免登失败，请使用网页登录'))}}))});await userStore.loginDingTalk(code);router.push(sampleAwareLoginTarget(userStore.profile?.roleCode,route.query.redirect,userStore.hasPermission('dashboard:view')?'/dashboard':'/pilot/tasks'))}catch(error){ElMessage.error(error instanceof Error?error.message:'钉钉免登失败')}finally{loading.value=false}}
onMounted(async()=>{try{sso.value=await getPilotSsoStatus()}catch{sso.value.message='无法读取免登配置，可使用网页登录'}})

const form = reactive({
  username: import.meta.env.DEV ? 'engineer01' : '',
  password: import.meta.env.DEV ? 'plm123456' : ''
})

const development = import.meta.env.DEV
const accounts = import.meta.env.DEV ? [
  'engineer01 / plm123456 - 工程部测试账号',
  'engineer02 / plm123456 - 工程部测试账号'
] : []

async function handleLogin() {
  loading.value = true
  try {
    await userStore.login(form)
    ElMessage.success('登录成功')
    const home = userStore.hasPermission('dashboard:view') ? '/dashboard' : userStore.hasPermission('pilot:tasks') ? '/pilot/tasks' : userStore.hasPermission('collaboration:view') ? '/market-watch' : '/403'
    router.push(sampleAwareLoginTarget(userStore.profile?.roleCode,route.query.redirect,home))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-shell">
    <header class="login-header">
      <div class="login-identity">
        <img class="login-identity__logo" :src="brandLogo" alt="" width="44" height="44" />
        <div>
          <strong>{{ t('common.appName') }}</strong>
          <span>产品生命周期管理</span>
        </div>
      </div>
      <span class="login-header__note">连接产品的每一步</span>
    </header>

    <main class="login-main">
      <section class="login-story" aria-labelledby="login-story-title">
        <div class="login-story__eyebrow"><span /> PRODUCT LIFECYCLE MANAGEMENT</div>
        <h2 id="login-story-title">让每个好想法，<br />成为好产品。</h2>
        <p class="login-story__description">连接研发、工程与协作，<br />让产品从构想到交付，清晰可循。</p>

        <svg class="login-story__art" viewBox="0 0 600 300" fill="none" aria-hidden="true">
          <defs>
            <linearGradient id="login-plate" x1="140" y1="50" x2="430" y2="260" gradientUnits="userSpaceOnUse">
              <stop stop-color="#84DDD4" stop-opacity=".24" />
              <stop offset="1" stop-color="#159C9C" stop-opacity=".04" />
            </linearGradient>
            <linearGradient id="login-edge" x1="100" y1="80" x2="500" y2="260" gradientUnits="userSpaceOnUse">
              <stop stop-color="#AAEFE4" />
              <stop offset="1" stop-color="#2B8D91" />
            </linearGradient>
          </defs>
          <ellipse cx="304" cy="166" rx="258" ry="80" transform="rotate(-24 304 166)" stroke="#79D9D0" stroke-opacity=".12" />
          <path d="M88 193 300 77 517 191M300 77V34M88 193v44M517 191v44" stroke="#71C6C2" stroke-opacity=".22" stroke-dasharray="3 7" />
          <path d="m137 174 159-87a20 20 0 0 1 19 0l149 81a9 9 0 0 1 0 16l-159 87a20 20 0 0 1-19 0l-149-81a9 9 0 0 1 0-16Z" fill="#0F4249" stroke="#41878C" />
          <path d="m137 140 159-87a20 20 0 0 1 19 0l149 81a9 9 0 0 1 0 16l-159 87a20 20 0 0 1-19 0l-149-81a9 9 0 0 1 0-16Z" fill="#124C52" stroke="#64B6B5" stroke-opacity=".7" />
          <path d="m137 106 159-87a20 20 0 0 1 19 0l149 81a9 9 0 0 1 0 16l-159 87a20 20 0 0 1-19 0l-149-81a9 9 0 0 1 0-16Z" fill="url(#login-plate)" stroke="url(#login-edge)" stroke-width="1.5" />
          <path d="m166 111 133-73a12 12 0 0 1 12 0l122 67a6 6 0 0 1 0 11l-133 73a12 12 0 0 1-12 0l-122-67a6 6 0 0 1 0-11Z" stroke="#A1E5DE" stroke-opacity=".24" />
          <path d="m213 98 40 22 54-30 41 22m-95 8 1 28 40 22m13-80 1-28" stroke="#A1E5DE" stroke-opacity=".5" />
          <path d="m253 120 54-30 41 22" stroke="#C4FFF3" stroke-width="2" />
          <circle cx="253" cy="120" r="5" fill="#123F46" stroke="#C4FFF3" stroke-width="2" />
          <circle cx="307" cy="90" r="5" fill="#123F46" stroke="#C4FFF3" stroke-width="2" />
          <circle cx="348" cy="112" r="5" fill="#123F46" stroke="#C4FFF3" stroke-width="2" />
          <path d="M88 237h49m327 0h53M300 34h44" stroke="#71C6C2" stroke-opacity=".4" />
          <circle cx="88" cy="237" r="3" fill="#85DED3" />
          <circle cx="517" cy="237" r="3" fill="#85DED3" />
          <circle cx="300" cy="34" r="3" fill="#85DED3" />
        </svg>

        <div class="login-story__stages">
          <span><i />产品研发</span><span><i />工程协同</span><span><i />交付追溯</span>
        </div>
      </section>

      <section class="login-panel" aria-labelledby="login-title">
        <div class="login-panel__heading">
          <span class="login-panel__eyebrow">欢迎回来</span>
          <h1 id="login-title">登录工作空间</h1>
          <p>登录 {{ t('common.appName') }}，继续产品协作。</p>
        </div>

        <el-form class="login-panel__form" :model="form" label-position="top">
          <el-form-item label="用户名">
            <el-input v-model="form.username" :prefix-icon="User" placeholder="请输入用户名" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" :prefix-icon="Lock" placeholder="请输入密码" type="password" autocomplete="current-password" show-password @keyup.enter="handleLogin" />
          </el-form-item>
          <el-button class="login-panel__submit" type="primary" :loading="loading" @click="handleLogin"><span>登录</span><el-icon><ArrowRight /></el-icon></el-button>
        </el-form>

        <div class="login-panel__divider"><span>其他登录方式</span></div>
        <el-button class="login-panel__sso" :disabled="!sso.enabled" :loading="loading" @click="handleDingLogin"><el-icon><Connection /></el-icon><span>钉钉轻应用免登</span></el-button>
        <p class="login-panel__sso-status" role="status">{{sso.message}}</p>

        <div v-if="development" class="login-panel__tips">
          <strong>后端 M1 测试账号</strong>
          <ul>
            <li v-for="item in accounts" :key="item">{{ item }}</li>
          </ul>
        </div>
      </section>
    </main>

    <footer class="login-footer"><span>{{ t('common.appName') }}</span><span>产品研发协同平台</span></footer>
  </div>
</template>

<style scoped>
.login-shell {
  --login-brand: #01a1a3;
  --el-color-primary: var(--login-brand);
  --el-color-primary-light-3: #34b4b5;
  --el-color-primary-light-5: #80d0d1;
  --el-color-primary-dark-2: #018182;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  min-height: 100svh;
  padding: 32px 56px 24px;
  color: #172c34;
  background: radial-gradient(ellipse at 90% 0%, #e8f5f2 0%, transparent 42%), #f8faf9;
}

.login-header,
.login-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: min(1240px, 100%);
  margin-inline: auto;
}

.login-identity {
  display: flex;
  align-items: center;
  gap: 12px;
}

.login-identity__logo {
  display: block;
  width: 44px;
  height: 44px;
  border-radius: 12px;
}

.login-identity > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.login-identity strong {
  font-size: 20px;
  letter-spacing: -.6px;
  font-weight: 650;
}

.login-identity span,
.login-header__note {
  font-size: 12px;
  color: #7a8b90;
}

.login-header__note {
  letter-spacing: 2px;
}

.login-main {
  display: grid;
  flex: 1;
  grid-template-columns: minmax(0, 1.14fr) minmax(340px, .86fr);
  align-items: center;
  gap: 80px;
  width: min(1240px, 100%);
  margin-inline: auto;
  padding-block: 32px;
}

.login-story {
  position: relative;
  overflow: hidden;
  padding: 40px 40px 30px;
  border: 1px solid #234b50;
  border-radius: 24px;
  color: #effaf7;
  background:
    radial-gradient(ellipse at 100% 100%, #1a6367 0%, transparent 60%),
    radial-gradient(ellipse at 0% 0%, #235359 0%, transparent 65%), #102e36;
}

.login-story::before {
  position: absolute;
  inset: 0;
  content: '';
  pointer-events: none;
  opacity: .22;
  background-image: linear-gradient(#9ad2cc0a 1px, transparent 1px), linear-gradient(90deg, #9ad2cc0a 1px, transparent 1px);
  background-size: 40px 40px;
}

.login-story__eyebrow {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #95c8c6;
  font-size: 10px;
  letter-spacing: 1.6px;
  line-height: 1.5;
}

.login-story__eyebrow > span {
  width: 18px;
  height: 2px;
  background: #8fddd3;
}

.login-story h2 {
  margin: 24px 0 18px;
  font-size: clamp(32px, 3.1vw, 46px);
  font-weight: 550;
  line-height: 1.35;
  letter-spacing: -1.2px;
}

.login-story__description {
  margin: 0;
  color: #aac6c6;
  font-size: 13px;
  line-height: 1.9;
}

.login-story__art {
  display: block;
  width: calc(100% + 32px);
  height: 224px;
  margin: 6px -16px 8px;
}

.login-story__stages {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding-top: 22px;
  border-top: 1px solid #9bd4cd26;
  color: #c4dcd7;
  font-size: 12px;
}

.login-story__stages span {
  display: flex;
  align-items: center;
  gap: 8px;
}

.login-story__stages i {
  width: 5px;
  height: 5px;
  border: 1px solid #92d4c8;
  border-radius: 50%;
}

.login-panel {
  width: 100%;
  max-width: 380px;
  min-width: 0;
  justify-self: center;
  padding-block: 12px;
}

.login-panel__eyebrow {
  display: inline-block;
  margin-bottom: 12px;
  color: #21827f;
  font-size: 13px;
  letter-spacing: 2px;
}

.login-panel h1 {
  margin: 0;
  font-size: 32px;
  font-weight: 600;
  letter-spacing: -1px;
  line-height: 1.4;
}

.login-panel__heading p {
  margin: 12px 0 32px;
  font-size: 13px;
  color: #7a898f;
  line-height: 1.8;
}

.login-panel__form :deep(.el-form-item) {
  margin-bottom: 22px;
}

.login-panel__form :deep(.el-form-item__label) {
  margin-bottom: 8px;
  color: #40585f;
  font-size: 13px;
  font-weight: 500;
}

.login-panel__form :deep(.el-input__wrapper) {
  min-height: 48px;
  padding: 1px 14px;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 0 0 1px #dce6e5 inset;
  transition: box-shadow .2s;
}

.login-panel__form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #9fc9c6 inset;
}

.login-panel__form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--login-brand) inset, 0 0 0 3px #01a1a310;
}

.login-panel__form :deep(.el-input__prefix-inner) {
  color: #8aa3a7;
}

.login-panel__form :deep(.el-input__inner::placeholder) {
  color: #a0b0b3;
  font-size: 13px;
}

.login-panel__submit {
  width: 100%;
  height: 48px;
  margin-top: 4px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  --el-button-bg-color: var(--login-brand);
  --el-button-border-color: var(--el-button-bg-color);
  --el-button-hover-bg-color: #34b4b5;
  --el-button-hover-border-color: var(--el-button-hover-bg-color);
  --el-button-active-bg-color: #018182;
  --el-button-active-border-color: var(--el-button-active-bg-color);
  --el-button-disabled-bg-color: #80d0d1;
  --el-button-disabled-border-color: var(--el-button-disabled-bg-color);
  --el-button-outline-color: #80d0d1;
}

.login-panel__submit :deep(> span) {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.login-panel__divider {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-block: 28px 22px;
  color: #91a1a5;
  font-size: 12px;
}

.login-panel__divider::before,
.login-panel__divider::after {
  flex: 1;
  height: 1px;
  content: '';
  background: #e0e8e7;
}

.login-panel__sso {
  width: 100%;
  height: 46px;
  margin: 0;
  border-radius: 10px;
  --el-button-text-color: #3d686e;
  --el-button-border-color: #d9e5e3;
  --el-button-hover-text-color: #138b88;
  --el-button-hover-border-color: #90cfca;
  --el-button-hover-bg-color: #eff9f6;
  --el-button-disabled-bg-color: #f2f6f5;
  --el-button-disabled-border-color: #e1e9e7;
  --el-button-disabled-text-color: #8a9c9f;
}

.login-panel__sso .el-icon {
  margin-right: 8px;
  font-size: 16px;
}

.login-panel__sso-status {
  margin: 12px 0 0;
  color: #91a1a5;
  font-size: 12px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}

.login-panel__tips {
  margin-top: 20px;
  color: #7a898f;
  font-size: 12px;
}

.login-panel__tips ul {
  padding-left: 18px;
}

.login-footer {
  justify-content: flex-end;
  gap: 12px;
  color: #91a1a5;
  font-size: 11px;
  line-height: 1.6;
}

.login-footer > span + span {
  padding-left: 12px;
  border-left: 1px solid #d7e2df;
}

@media (max-width: 1100px) {
  .login-shell { padding-inline: 32px; }
  .login-main { gap: 44px; }
  .login-story { padding-inline: 28px; }
}

@media (max-width: 900px) {
  .login-main { grid-template-columns: minmax(0, 1fr); padding-block: 48px; }
  .login-story { display: none; }
  .login-panel { max-width: 400px; }
  .login-footer { justify-content: center; }
}

@media (max-width: 480px) {
  .login-shell { padding: 24px; }
  .login-header__note { display: none; }
  .login-identity__logo { width: 40px; height: 40px; }
  .login-identity strong { font-size: 19px; }
  .login-identity span { font-size: 11px; }
  .login-main { padding-block: 44px; }
  .login-panel h1 { font-size: 29px; }
}
</style>
