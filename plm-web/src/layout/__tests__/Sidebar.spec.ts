import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import Sidebar from '../Sidebar.vue'
import { getMoldApplicationConfig } from '@/api/modules/moldApplications'

vi.mock('@/api/modules/marketWatch', () => ({
  getMarketWatchConfig: vi.fn().mockResolvedValue({ enabled: false })
}))
vi.mock('@/api/modules/moldApplications',()=>({getMoldApplicationConfig:vi.fn().mockResolvedValue({enabled:false})}))

let wrapper: VueWrapper | undefined

afterEach(() => {wrapper?.unmount();vi.mocked(getMoldApplicationConfig).mockResolvedValue({enabled:false})})

async function start(permissions = ['*'], path = '/molds', roleCode = '') {
  const pinia = createPinia()
  setActivePinia(pinia)
  useUserStore().permissions = permissions
  useUserStore().profile = {userId:1,userName:'测试',roleName:'测试',department:'测试',roleCode}
  const router: Router = createRouter({
    history: createMemoryHistory(),
    routes: ['/model-samples','/research-samples','/partner-samples','/partner-selections','/partner-products','/partner-listings','/partner-sales','/partner-fulfilments','/partner-operations/:kind/:id','/samples','/customers','/product-research-costs','/product-research-promotions','/product-research-presales','/product-research','/mold-applications','/molds','/files','/projects','/market-data','/production-totals','/mold-delivery-tasks','/dashboard','/my-tasks','/pilot/tasks','/mold-schedules','/development-budgets','/development-expenses','/production-orders','/orders','/market-watch','/bom','/processes','/inventories','/suppliers','/phone-model-codes','/code-center','/reports','/system/users','/system/operation-log','/approval-tasks','/system/assignments','/system/fields','/system/integrations','/system/trials','/system/import'].map(path => ({
      path, component: { template: '<div />' }
    }))
  })
  await router.push(path)
  await router.isReady()
  wrapper = mount(Sidebar, {
    global: { plugins: [pinia, router], stubs: { ElIcon: { template: '<span><slot /></span>' } } }
  })
  await flushPromises()
  return { wrapper, router }
}

describe('mold ledger navigation', () => {
  it('keeps the three catalogue entries ordered and highlights listing evidence under the listed catalogue',async()=>{const {wrapper,router}=await start(['*'],'/partner-listings');const group=wrapper.get('.sidebar__group.is-active-group');expect(group.findAll('.sidebar__item').map(item=>item.text())).toEqual(['供应商产品目录','选品目录','样品管理','上架产品目录','委外与交付']);await router.push('/partner-operations/listing/88');await flushPromises();expect(wrapper.findAll('.sidebar__item.is-active').map(item=>item.text())).toEqual(['上架产品目录'])})
  it('opens partner products independently and keeps samples under workbench',async()=>{const {wrapper,router}=await start(['*'],'/partner-products');expect(wrapper.get('.sidebar__group.is-active-group .sidebar__group-label').text()).toBe('合作产品');expect(wrapper.get('.sidebar__item.is-active').text()).toBe('供应商产品目录');await router.push('/samples?id=11');await flushPromises();expect(wrapper.get('.sidebar__group.is-active-group .sidebar__group-label').text()).toBe('工作台');expect(wrapper.get('.sidebar__item.is-active').text()).toBe('样品管理')})

  it('keeps model and new product research as parallel groups and highlights the detail URL', async () => {
    const { wrapper, router } = await start(['*'], '/product-research?productId=42')
    const groups = wrapper.findAll('.sidebar__group')
    expect(groups.map(group => group.get('.sidebar__group-label').text())).toEqual(expect.arrayContaining(['型号研发', '新品研发']))
    expect(groups.find(group => group.get('.sidebar__group-label').text() === '型号研发')!.text()).not.toContain('新品研发')
    expect(wrapper.get('.sidebar__group.is-active-group .sidebar__group-label').text()).toBe('新品研发')
    expect(wrapper.get('.sidebar__item.is-active').text()).toBe('新品项目')
    expect(wrapper.findAll('.sidebar__group.is-active-group .sidebar__item').map(item => item.text())).toEqual(['新品项目','样品管理','项目成本','渠道推广','预售管理'])
    await wrapper.get('.sidebar__item.is-active').trigger('click')
    expect(router.currentRoute.value.path).toBe('/product-research')
    await router.push('/product-research?stage=2&productId=42'); await flushPromises()
    expect(wrapper.findAll('.sidebar__item.is-active').map(item => item.text())).toEqual(['新品项目'])
    await wrapper.findAll('.sidebar__item').find(item => item.text() === '新品项目')!.trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ stage: '2', productId:'42' })
  })

  it('keeps project context between independent business pages and stage pages',async()=>{
    const {wrapper,router}=await start(['*'],'/product-research?productId=42&stage=4');
    await wrapper.findAll('.sidebar__item').find(b=>b.text()==='渠道推广')!.trigger('click');await flushPromises();
    expect(router.currentRoute.value.fullPath).toBe('/product-research-promotions?productId=42&stage=4');
    expect(wrapper.get('.sidebar__item.is-active').text()).toBe('渠道推广');
    await wrapper.findAll('.sidebar__item').find(b=>b.text()==='新品项目')!.trigger('click');await flushPromises();
    expect(router.currentRoute.value.query).toEqual({stage:'4',productId:'42'});
  });
  it('leaves a legacy URL without carrying its flow into new menus',async()=>{
    const {wrapper,router}=await start(['*'],'/product-research?productId=9&flow=lightweight');
    await wrapper.findAll('.sidebar__group-trigger').find(b=>b.text()==='新品研发')!.trigger('click');await flushPromises();
    await wrapper.findAll('.sidebar__item').find(b=>b.text()==='新品项目')!.trigger('click');await flushPromises();
    expect(router.currentRoute.value.query).toEqual({});
  });
  it('opens the basic-data group for the mold URL and keeps one mold entry', async () => {
    const { wrapper, router } = await start()
    const active = wrapper.get('.sidebar__group.is-active-group')
    expect(active.get('.sidebar__group-label').text()).toBe('基础资料')
    expect(active.get('.sidebar__item.is-active').text()).toBe('模具管理')
    expect(wrapper.findAll('.sidebar__item').filter(item => item.text() === '模具管理')).toHaveLength(1)
    expect(wrapper.findAll('.sidebar__item').filter(item => item.text() === '手机编码')).toHaveLength(1)
    await wrapper.findAll('.sidebar__group-trigger').find(button=>button.text()==='系统管理')!.trigger('click')
    await wrapper.findAll('.sidebar__item').find(button=>button.text()==='系统日志')!.trigger('click');await flushPromises()
    expect(router.currentRoute.value.path).toBe('/system/operation-log');expect(wrapper.get('.sidebar__item.is-active').text()).toBe('系统日志')
  })

  it('keeps one schedule entry without independent delivery, testing or demand menus', async () => {
    const { wrapper } = await start(['*'], '/production-totals')
    const active = wrapper.get('.sidebar__group.is-active-group')
    expect(active.get('.sidebar__group-label').text()).toBe('型号研发')
    expect(active.text()).not.toContain('市场资料');expect(active.text()).toContain('累计产量');expect(active.text()).not.toContain('模具交付待办');expect(active.text()).not.toContain('测试管理');expect(active.text()).not.toContain('需求订单');expect(active.text()).toContain('开模时间管控');expect(active.text()).not.toContain('协作计划')
    expect(active.text()).not.toContain('模具管理')
  })

  it('prioritizes tasks and shared samples without moving project center out of workbench',async()=>{
    const {wrapper}=await start(['*'],'/projects')
    const active=wrapper.get('.sidebar__group.is-active-group')
    expect(active.get('.sidebar__group-label').text()).toBe('工作台')
    expect(active.findAll('.sidebar__item').map(i=>i.text())).toEqual(['首页总览','我的待办','样品管理','项目中心'])
    expect(active.get('.sidebar__item.is-active').text()).toBe('项目中心')
  })
  it('replaces only the customer-sales menu with supplier execution and retains deep-link highlighting',async()=>{
    const {wrapper,router}=await start(['partner:view'],'/partner-fulfilments')
    expect(wrapper.get('.sidebar__group.is-active-group .sidebar__group-label').text()).toBe('合作产品')
    expect(wrapper.findAll('.sidebar__group.is-active-group .sidebar__item').map(i=>i.text())).toEqual(['供应商产品目录','选品目录','样品管理','上架产品目录','委外与交付'])
    await router.push('/partner-selections?id=19');await flushPromises()
    expect(wrapper.get('.sidebar__group.is-active-group .sidebar__group-label').text()).toBe('合作产品')
    expect(wrapper.get('.sidebar__item.is-active').text()).toBe('选品目录')
    await wrapper.get('.sidebar__item.is-active').trigger('click');await flushPromises()
    expect(router.currentRoute.value.path).toBe('/partner-selections')
    await router.push('/partner-operations/inspection/42?from=/samples');await flushPromises()
    expect(wrapper.get('.sidebar__item.is-active').text()).toBe('委外与交付')
    await router.push('/partner-sales?id=8');await flushPromises()
    expect(wrapper.get('.sidebar__item.is-active').text()).toBe('委外与交付')
    expect(wrapper.findAll('.sidebar__item').some(i=>i.text()==='销售跟进')).toBe(false)
  })
  it('uses independent phone navigation without changing the desktop sidebar preference',async()=>{
    const {wrapper}=await start(['partner:view'],'/partner-fulfilments');const app=useAppStore();app.sidebarCollapsed=true;await flushPromises();expect(wrapper.classes()).toContain('sidebar--collapsed');
    app.mobileNavigationOpen=true;await flushPromises();expect(wrapper.classes()).not.toContain('sidebar--collapsed');expect(app.sidebarCollapsed).toBe(true);expect(wrapper.text()).toContain('委外与交付');
    app.closeMobileNavigation();await flushPromises();expect(wrapper.classes()).toContain('sidebar--collapsed');expect(app.sidebarCollapsed).toBe(true)
  })

  it('retains the mold URL and existing collaboration permission', async () => {
    const { wrapper, router } = await start(['collaboration:view'], '/market-data')
    await wrapper.findAll('.sidebar__group-trigger').find(button => button.text() === '基础资料')!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/market-data')
    await wrapper.findAll('.sidebar__item').find(button => button.text() === '模具管理')!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/molds')
    expect(wrapper.get('.sidebar__item.is-active').text()).toBe('模具管理')
  })

  it('does not expose molds without the original collaboration permission', async () => {
    const { wrapper } = await start(['product:view'], '/files')
    expect(wrapper.text()).not.toContain('模具管理')
    expect(wrapper.text()).not.toContain('手机编码')
    expect(wrapper.text()).not.toContain('系统日志')
  })
  it('opens the independent phone-code module from basic data and keeps its deep-link highlight',async()=>{
    const {wrapper,router}=await start(['collaboration:view'],'/molds')
    await wrapper.findAll('.sidebar__item').find(button=>button.text()==='手机编码')!.trigger('click');await flushPromises()
    expect(router.currentRoute.value.path).toBe('/phone-model-codes')
    await router.push('/phone-model-codes?tab=review&keyword=0064');await flushPromises()
    expect(wrapper.get('.sidebar__group.is-active-group .sidebar__group-label').text()).toBe('基础资料')
    expect(wrapper.get('.sidebar__item.is-active').text()).toBe('手机编码')
  })
  it('highlights exactly one query report and defaults direct report links to schedule',async()=>{
    const {wrapper,router}=await start(['*'],'/reports?view=finance')
    expect(wrapper.findAll('.sidebar__item.is-active').map(b=>b.text())).toEqual(['预算与实际'])
    await router.push('/reports');await flushPromises()
    expect(wrapper.findAll('.sidebar__item.is-active').map(b=>b.text())).toEqual(['项目周期'])
  })
  it('shows external accounts only their working phone-task entry',async()=>{
    const {wrapper}=await start(['pilot:tasks','task:ship'],'/pilot/tasks','supplier')
    expect(wrapper.findAll('.sidebar__group-label').map(b=>b.text())).toEqual(['工作台'])
    expect(wrapper.get('.sidebar__item.is-active').text()).toBe('我的待办')
    expect(wrapper.text()).not.toContain('费用管理')
  })
  it('keeps platform archives separate from the general project-center highlight',async()=>{
    const {wrapper}=await start(['*'],'/projects?tab=archived&archiveView=product')
    expect(wrapper.get('.sidebar__group.is-active-group .sidebar__group-label').text()).toBe('基础资料')
    expect(wrapper.get('.sidebar__item.is-active').text()).toBe('产品平台与型号')
  })
  it('shows mold applications only when both the feature and collaboration visibility are granted',async()=>{
    vi.mocked(getMoldApplicationConfig).mockResolvedValue({enabled:true});
    const {wrapper:shown,router}=await start(['collaboration:view'],'/market-data');expect(shown.text()).toContain('开模申请');await shown.findAll('.sidebar__item').find(b=>b.text()==='开模申请')!.trigger('click');await flushPromises();expect(router.currentRoute.value.path).toBe('/market-watch');expect(shown.findAll('.sidebar__item').map(b=>b.text()).slice(0,3)).toEqual(['开模申请','开模申请进度','累计产量']);await shown.findAll('.sidebar__item').find(b=>b.text()==='开模申请进度')!.trigger('click');await flushPromises();expect(router.currentRoute.value.path).toBe('/mold-applications');shown.unmount();
    const {wrapper:hidden}=await start(['product:view'],'/files');expect(hidden.text()).not.toContain('开模申请');hidden.unmount();
    vi.mocked(getMoldApplicationConfig).mockResolvedValue({enabled:false});const {wrapper:disabled}=await start(['collaboration:view'],'/market-data');expect(disabled.text()).not.toContain('开模申请');
  })
})
