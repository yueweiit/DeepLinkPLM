<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowRight,
  Box,
  Checked,
  Coin,
  CollectionTag,
  Connection,
  DataAnalysis,
  DocumentChecked,
  Files,
  Grid,
  Management,
  Money,
  PriceTag,
  Search,
  Setting,
  Shop,
  ShoppingBag,
  Tools,
  User,
  Van
} from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { t } from '@/locales'
import { canAccessLocalSamples } from '@/utils/sampleLocation'
import { mobileEntryLocation, mobileTasksPath } from '@/utils/mobileNavigation'
import MobileProfileView from './MobileProfileView.vue'

type MobileEntry = {
  title: string
  description: string
  path: string
  permission?: string
  permissions?: string[]
  icon: typeof DocumentChecked
}

type MobileGroup = { title: string; entries: MobileEntry[] }

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const app = useAppStore()
const keyword = ref('')
const moreView = computed(() => route.path === '/mobile' && route.query.view === 'more')
const profileView = computed(() => route.path === '/mobile' && route.query.view === 'profile')
const displayName = computed(() => user.profile?.userName || t('PLM 用户'))

const moduleGroups: MobileGroup[] = [
  {
    title: '工作台',
    entries: [
      { title: '首页总览', description: '项目、待办与风险概览', path: '/dashboard', permission: 'dashboard:view', icon: Grid },
      { title: '审批待办', description: '处理业务审批任务', path: '/approval-tasks?view=tasks', permission: 'approval:view', icon: Checked },
      { title: '项目中心', description: '查看进行中项目和归档资料', path: '/projects', permission: 'project:view', icon: Management },
      { title: '样品管理', description: '查询和管理业务样品', path: '/samples', permission: 'partner:view', icon: Box }
    ]
  },
  {
    title: '研发与订单',
    entries: [
      { title: '新品研发', description: '查看研发项目和需求立项', path: '/product-research', permission: 'project:view', icon: Management },
      { title: '研发费用', description: '查看项目研发费用记录', path: '/product-research-costs', permission: 'finance:read', icon: Money },
      { title: '渠道推广', description: '查看新品推广计划', path: '/product-research-promotions', permission: 'project:view', icon: ShoppingBag },
      { title: '预售管理', description: '查看新品预售进度', path: '/product-research-presales', permission: 'project:view', icon: DocumentChecked },
      { title: '新建项目', description: '创建新品研发项目', path: '/products/create', permission: 'project:view', icon: CollectionTag },
      { title: '产品平台与型号', description: '查询产品平台和型号资料', path: '/projects?tab=archived&archiveView=product', permission: 'product:view', icon: Box },
      { title: 'SKU 资料', description: '查询产品 SKU 资料', path: '/projects?tab=archived&archiveView=sku', permission: 'product:view', icon: PriceTag },
      { title: '需求订单', description: '查看客户订单和市场需求', path: '/orders', permission: 'order:view', icon: DocumentChecked },
      { title: '测试管理', description: '查看测试任务和结果', path: '/production-orders', permission: 'production-order:view', icon: Tools }
    ]
  },
  {
    title: '供应商与样品',
    entries: [
      { title: '供应商产品目录', description: '查询供应商产品和规格', path: '/partner-products', permission: 'partner:view', icon: ShoppingBag },
      { title: '销售跟进', description: '查看合作产品销售进度', path: '/partner-sales', permission: 'partner:view', icon: PriceTag },
      { title: '委外与交付', description: '跟进委外业务和交付', path: '/partner-fulfilments', permission: 'partner:view', icon: Van },
      { title: '选品目录', description: '处理合作选品资料', path: '/partner-selections', permission: 'partner:view', icon: Shop },
      { title: '上架产品', description: '查询上架产品和状态', path: '/partner-listings', permission: 'partner:view', icon: PriceTag },
      { title: '样品扫码', description: '扫描样品标签进入记录', path: '/sample-scan', permission: 'partner:view', icon: Search },
      { title: '客户管理', description: '查询客户资料', path: '/customers', permission: 'customer:view', icon: User },
      { title: '供应商管理', description: '查询供应商资料', path: '/suppliers', permission: 'supplier:view', icon: Van }
    ]
  },
  {
    title: '模具与工程',
    entries: [
      { title: '开模申请', description: '查看市场线索和申请信息', path: '/market-watch', permission: 'collaboration:view', icon: Tools },
      { title: '开模申请进度', description: '跟进申请状态和资料', path: '/mold-applications', permission: 'collaboration:view', icon: DocumentChecked },
      { title: '开模时间管控', description: '查看计划和实际节点', path: '/mold-schedules', permission: 'schedule:read', icon: Grid },
      { title: '模具管理', description: '查询模具身份和实物台账', path: '/molds', permission: 'collaboration:view', icon: Tools },
      { title: '共模映射', description: '查看模具与机型关系', path: '/mold-share-mappings', permission: 'collaboration:view', icon: Connection },
      { title: '累计产量', description: '查看累计生产数据', path: '/production-totals', permission: 'collaboration:view', icon: DataAnalysis },
      { title: 'BOM 管理', description: '查询产品结构和用料', path: '/bom', permission: 'product:view', icon: Box },
      { title: '工艺管理', description: '查看产品工艺资料', path: '/processes', permission: 'process:view', icon: Setting },
      { title: '物料库存', description: '查询物料库存', path: '/inventories', permission: 'inventory:view', icon: Box },
      { title: '机台工位', description: '查看工位资料', path: '/workstations', permission: 'workstation:view', icon: Tools },
      { title: '质量管理', description: '查看质量记录', path: '/quality', permission: 'quality:view', icon: Checked },
      { title: '图纸与 3D 文件', description: '查询已授权的工程文件', path: '/files', permission: 'file:read', icon: Files }
    ]
  },
  {
    title: '费用与报表',
    entries: [
      { title: '模具成本预算', description: '查看模具预算', path: '/development-budgets', permission: 'finance:read', icon: Coin },
      { title: '开发费用归集', description: '查看开发费用记录', path: '/development-expenses', permission: 'finance:read', icon: Money },
      { title: '报表中心', description: '查看项目周期和经营分析', path: '/reports', permission: 'report:view', icon: DataAnalysis }
    ]
  },
  {
    title: '基础资料',
    entries: [
      { title: '颜色编码', description: '查询产品颜色资料', path: '/code-center', permission: 'product:view', icon: CollectionTag },
      { title: '手机编码', description: '查询手机型号编码', path: '/phone-model-codes', permission: 'collaboration:view', icon: Box }
    ]
  },
  {
    title: '系统管理',
    entries: [
      { title: '账号与权限', description: '管理系统账号', path: '/system/users', permission: 'pilot:admin', icon: User },
      { title: '角色管理', description: '维护系统角色', path: '/system/roles', permission: 'pilot:admin', icon: Management },
      { title: '系统日志', description: '查看系统操作记录', path: '/system/operation-log', permissions: ['project:view', 'admin:log'], icon: Files },
      { title: '协作职责', description: '管理协作岗位职责', path: '/system/assignments', permission: 'pilot:admin', icon: User },
      { title: '系统对接', description: '查看外部系统连接配置', path: '/system/integrations', permission: 'pilot:admin', icon: Connection },
      { title: '试点范围配置', description: '管理试点业务范围', path: '/system/trials', permission: 'pilot:admin', icon: Setting },
      { title: '数据导入', description: '导入业务基础数据', path: '/system/import', permission: 'admin:import', icon: Files }
    ]
  }
]

const quickEntries = computed<MobileEntry[]>(() => {
  const taskPath = mobileTasksPath(user)
  return [
    ...(taskPath ? [{ title: '我的待办', description: '跟进分配给我的业务任务', path: taskPath, permission: 'pilot:tasks', icon: Checked }] : []),
    { title: '新品研发', description: '查看研发项目和需求立项', path: '/product-research', permission: 'project:view', icon: Management },
    { title: '选品目录', description: '查看选品资料和处理进度', path: '/partner-selections', permission: 'partner:view', icon: Shop },
    { title: '样品管理', description: '查询样品和扫码登记', path: '/samples', permission: 'partner:view', icon: Box },
    { title: '开模申请', description: '查看市场线索和申请信息', path: '/market-watch', permission: 'collaboration:view', icon: Tools },
    { title: '需求订单', description: '查看客户订单和市场需求', path: '/orders', permission: 'order:view', icon: DocumentChecked }
  ]
})

const visibleQuickEntries = computed(() => quickEntries.value.filter(canOpen))
const visibleGroups = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase()
  const taskPath = mobileTasksPath(user)
  return moduleGroups.map(group => {
    const entries = group.title === '工作台' && taskPath
      ? [{ title: '我的待办', description: '跟进分配给我的业务任务', path: taskPath, permission: 'pilot:tasks', icon: Checked }, ...group.entries]
      : group.entries
    return {
      ...group,
      entries: entries.filter(entry => canOpen(entry)
        && (!query || [entry.title, entry.description, t(entry.title), t(entry.description)].join(' ').toLocaleLowerCase().includes(query)))
    }
  }).filter(group => group.entries.length > 0)
})
const showCatalog = computed(() => moreView.value || Boolean(keyword.value.trim()))
const hasResults = computed(() => showCatalog.value ? visibleGroups.value.length > 0 : visibleQuickEntries.value.length > 0)

function canOpen(entry: MobileEntry) {
  const localSamples = entry.path === '/samples' && canAccessLocalSamples(user)
  if (entry.path === '/samples' && !user.hasPermission('partner:view') && !localSamples) return false
  if (entry.path === '/sample-scan' && !user.hasPermission('partner:view')) return false
  if (entry.permission && !user.hasPermission(entry.permission) && !localSamples) return false
  if (entry.permissions?.some(permission => !user.hasPermission(permission))) return false
  if (entry.path === '/market-watch' && !app.phoneWatchEnabled && !app.moldApplicationEnabled) return false
  if (entry.path === '/mold-applications' && !app.moldApplicationEnabled) return false
  return true
}

function go(path: string) {
  const source = moreView.value ? '/mobile?view=more' : '/mobile'
  void router.push(mobileEntryLocation(path, source, user))
}

function openAllFunctions() {
  keyword.value = ''
  void router.push({ path: '/mobile', query: { view: 'more' } })
}

onMounted(() => {
  if (user.hasPermission('collaboration:view')) void app.loadFeatureConfig()
})

watch(() => route.query.view, () => {
  keyword.value = ''
})
</script>

<template>
  <main v-if="profileView" class="mobile-home mobile-home--profile" :aria-label="t('个人中心')">
    <MobileProfileView />
  </main>
  <main v-else class="mobile-home" :class="{ 'mobile-home--more': moreView }" :aria-label="t(moreView ? '全部业务' : 'PLM 移动工作台')">
    <header v-if="!moreView && !keyword.trim()" class="mobile-home__welcome">
      <div class="mobile-home__welcome-copy">
        <p class="mobile-home__eyebrow">DeepLink PLM</p>
        <h1>{{ t('你好，') }}{{ displayName }}</h1>
        <p v-if="user.profile?.roleName" class="mobile-home__role">{{ t(user.profile.roleName) }}</p>
      </div>
      <el-button class="mobile-home__all-button" circle :aria-label="t('全部业务')" :title="t('全部业务')" @click="openAllFunctions">
        <el-icon><Grid /></el-icon>
      </el-button>
    </header>

    <div class="mobile-home__search">
      <el-input v-model="keyword" clearable :prefix-icon="Search" :placeholder="t('搜索业务模块')" :aria-label="t('搜索业务模块')" />
    </div>

    <section v-if="!showCatalog && visibleQuickEntries.length" class="mobile-home__section" aria-labelledby="quick-entry-title">
      <div class="mobile-home__section-title">
        <h2 id="quick-entry-title">{{ t('常用功能') }}</h2>
        <el-button text type="primary" @click="openAllFunctions">{{ t('全部业务') }}<el-icon><ArrowRight /></el-icon></el-button>
      </div>
      <div class="mobile-home__quick-grid">
        <button v-for="entry in visibleQuickEntries" :key="entry.path" class="mobile-home__quick-entry" type="button" @click="go(entry.path)">
          <span class="mobile-home__quick-icon"><el-icon><component :is="entry.icon" /></el-icon></span>
          <strong>{{ t(entry.title) }}</strong>
        </button>
      </div>
    </section>

    <div v-if="showCatalog" class="mobile-home__modules">
      <section v-for="group in visibleGroups" :key="group.title" class="mobile-home__section" :aria-label="t(group.title)">
        <div class="mobile-home__section-title"><h2>{{ t(group.title) }}</h2></div>
        <nav class="mobile-home__module-list" :aria-label="t(group.title)">
          <button v-for="entry in group.entries" :key="entry.path" class="mobile-home__module" type="button" @click="go(entry.path)">
            <span class="mobile-home__module-icon"><el-icon><component :is="entry.icon" /></el-icon></span>
            <span class="mobile-home__module-copy">
              <strong>{{ t(entry.title) }}</strong>
              <small>{{ t(entry.description) }}</small>
            </span>
            <el-icon class="mobile-home__arrow"><ArrowRight /></el-icon>
          </button>
        </nav>
      </section>
      <el-empty v-if="!hasResults" :description="t('没有可显示的业务模块')" />
    </div>
    <el-empty v-else-if="!hasResults" :description="t('暂无常用功能')" />
  </main>
</template>

<style scoped>
.mobile-home{min-height:100%;padding:16px 12px 20px;background:#f4f7f6;color:#24343d}
.mobile-home__welcome,.mobile-home__search,.mobile-home__section,.mobile-home__modules{width:100%;max-width:760px;margin-left:auto;margin-right:auto}
.mobile-home__welcome{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px}
.mobile-home__welcome-copy{min-width:0}
.mobile-home__eyebrow{margin:0 0 5px;color:#5e756f;font-size:12px}
.mobile-home h1{margin:0;font-size:21px;line-height:1.3;font-weight:650}
.mobile-home__role{margin:5px 0 0;color:#72827d;font-size:12px}
.mobile-home__all-button{flex:0 0 42px;width:42px;height:42px;border-color:#d9e3df;background:#fff;color:#256b64}
.mobile-home__search{margin-bottom:22px}
.mobile-home__search :deep(.el-input__wrapper){min-height:44px;border-radius:8px}
.mobile-home__section{margin-bottom:24px}
.mobile-home__section-title{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:10px}
.mobile-home h2{margin:0;font-size:16px;line-height:1.4;font-weight:650}
.mobile-home__section-title .el-button{flex:none;padding:4px 0;font-size:12px}
.mobile-home__section-title .el-icon{margin-left:4px}
.mobile-home__quick-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}
.mobile-home__quick-entry{display:flex;min-width:0;height:96px;flex-direction:column;align-items:center;justify-content:center;gap:8px;padding:8px 4px;border:1px solid #e2e9e6;border-radius:8px;background:#fff;color:inherit;cursor:pointer;-webkit-tap-highlight-color:transparent}
.mobile-home__quick-entry:active,.mobile-home__module:active{background:#f4f8f6}
.mobile-home__quick-entry strong{display:flex;min-height:30px;align-items:center;justify-content:center;max-width:100%;font-size:12px;line-height:1.35;text-align:center;overflow-wrap:anywhere}
.mobile-home__quick-icon{display:grid;flex:0 0 34px;width:34px;height:34px;place-items:center;border-radius:8px;background:#eaf3f0;color:#26736a;font-size:18px}
.mobile-home__quick-entry:nth-child(3n + 2) .mobile-home__quick-icon{background:#edf2fa;color:#426caa}
.mobile-home__quick-entry:nth-child(3n) .mobile-home__quick-icon{background:#fbf0e9;color:#b66539}
.mobile-home__modules{scroll-margin-top:12px}
.mobile-home__module-list{display:grid;grid-template-columns:repeat(auto-fill,minmax(98px,1fr));gap:8px}
.mobile-home__module{display:flex;min-width:0;min-height:100px;flex-direction:column;align-items:center;justify-content:center;gap:8px;padding:9px 5px;border:1px solid #e2e9e6;border-radius:8px;background:#fff;color:inherit;text-align:center;cursor:pointer;-webkit-tap-highlight-color:transparent}
.mobile-home__module-icon{display:grid;flex:0 0 34px;width:34px;height:34px;place-items:center;border-radius:8px;background:#eaf3f0;color:#26736a;font-size:17px}
.mobile-home__module:nth-child(3n + 2) .mobile-home__module-icon{background:#edf2fa;color:#426caa}
.mobile-home__module:nth-child(3n) .mobile-home__module-icon{background:#fbf0e9;color:#b66539}
.mobile-home__module-copy{display:flex;min-width:0;max-width:100%;flex-direction:column;gap:3px}
.mobile-home__module strong{font-size:12px;line-height:1.35;font-weight:600;overflow-wrap:anywhere}
.mobile-home__module small,.mobile-home__arrow{display:none}
.mobile-home--more{padding-top:12px}
.mobile-home--profile{display:flex;flex-direction:column;padding-top:10px;padding-bottom:12px}
@media(min-width:560px){
 .mobile-home{padding-right:24px;padding-left:24px}
 .mobile-home__quick-grid{grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}
 .mobile-home__module-list{grid-template-columns:repeat(auto-fill,minmax(112px,1fr));gap:10px}
}
@media(max-width:359px){
 .mobile-home{padding-right:8px;padding-left:8px}
 .mobile-home__quick-entry strong{font-size:11px}
}
</style>
