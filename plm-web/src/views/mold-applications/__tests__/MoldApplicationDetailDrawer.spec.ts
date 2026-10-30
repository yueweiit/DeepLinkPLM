import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {createMemoryHistory,createRouter} from 'vue-router'
import ElementPlus from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import type {MoldApplication,MoldOptions} from '@/api/modules/moldApplications'
import {useUserStore} from '@/stores/user'
const api=vi.hoisted(()=>Object.fromEntries(['getMoldApplication','getMoldOptions','submitMoldApplication','saveMoldApplication','withdrawMoldApplication','archiveMoldFiles','syncMoldApplication','retryMoldProvision','resolveMoldExecution','deleteMoldDraft'].map(name=>[name,vi.fn()])))
vi.mock('@/api/modules/moldApplications',async original=>({...await original<typeof import('@/api/modules/moldApplications')>(),...api}))
import MoldApplicationDetailDrawer from '../components/MoldApplicationDetailDrawer.vue'

const rejected=(values:Partial<MoldApplication>={})=>({applicationId:88,version:4,approvalNo:'APP-REFUSED-88',title:'新品手机申请',sourceSystem:'dingtalk',instanceId:'original-instance',status:'completed',businessStatus:'rejected',remoteStatus:'COMPLETED',remoteResult:'refuse',model:'Honor 600 lite',tipo:'原审批产品',productLineId:42,projectId:null,itemId:null,moldSetId:null,approvalGranted:false,form:{model:'Honor 600 lite'},files:[],activities:[{activityId:1,type:'EXECUTE_TASK_NORMAL',actorId:'verified-id',actorName:'周汉琴',occurredAt:'2026-10-29T10:00:00+08:00',payload:{operation_result:'REFUSE',remark:'这个型号不好卖'}}],...values}) as MoldApplication
const capabilities={processCode:'research-process',syncRunning:false,controls:[],configs:[],productLines:[],originators:[],canCreate:false,canConvert:false,administrator:false,canSubmit:false,writeEnabled:false,centralCodeEnabled:false} as MoldOptions
interface DrawerApi{open(id:number,context?:{projectId?:number;initial?:MoldApplication}):Promise<void>;close():void}
let view:VueWrapper
async function start(options?:MoldOptions){
 const pinia=createPinia();setActivePinia(pinia);const user=useUserStore();user.token='engineer';user.permissions=['*'];user.profile={userId:7,userName:'工程',roleName:'工程',department:'研发'}
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/product-research',component:{template:'<div/>'}},{path:'/mold-applications',component:{template:'<div/>'}},{path:'/projects',component:{template:'<div/>'}}]});await router.push('/product-research?step=drawing&productId=42');await router.isReady()
 view=mount(MoldApplicationDetailDrawer,{props:{projectId:42,...(options?{options}:{})},global:{plugins:[pinia,router,ElementPlus],stubs:{ElDrawer:{props:['modelValue','title'],template:'<section v-if="modelValue" role="dialog" :aria-label="title"><slot/><slot name="footer"/></section>'},ElDialog:{props:['modelValue'],template:'<section v-if="modelValue"><slot/><slot name="footer"/></section>'}}}})
 await flushPromises();return {router,drawer:view.vm as unknown as DrawerApi}
}
beforeEach(()=>{vi.clearAllMocks();localStorage.clear();api.getMoldOptions.mockResolvedValue(capabilities);api.getMoldApplication.mockResolvedValue(rejected())})
afterEach(()=>{view?.unmount()})
describe('shared approval drawer in the original research page',()=>{
 it('loads only on explicit open and retains the original refusal without navigating or writing',async()=>{
  const {router,drawer}=await start();expect(api.getMoldApplication).not.toHaveBeenCalled();expect(api.getMoldOptions).not.toHaveBeenCalled()
  await drawer.open(88,{projectId:42});await flushPromises()
  expect(view.get('[role=dialog]').text()).toContain('APP-REFUSED-88');expect(view.text()).toContain('这个型号不好卖');expect(view.text()).toContain('已拒绝')
  expect(router.currentRoute.value.fullPath).toBe('/product-research?step=drawing&productId=42')
  for(const name of ['submitMoldApplication','saveMoldApplication','withdrawMoldApplication','archiveMoldFiles','syncMoldApplication','retryMoldProvision','resolveMoldExecution','deleteMoldDraft'])expect(api[name]).not.toHaveBeenCalled()
  drawer.close();await flushPromises();expect(view.find('[role=dialog]').exists()).toBe(false)
 })
 it('reuses an already loaded exact detail and parent capabilities instead of fetching a list or detail twice',async()=>{
  const {drawer}=await start(capabilities);await drawer.open(88,{projectId:42,initial:rejected()});await flushPromises()
  expect(view.text()).toContain('APP-REFUSED-88');expect(api.getMoldApplication).not.toHaveBeenCalled();expect(api.getMoldOptions).not.toHaveBeenCalled()
 })
 it('keeps an error inside the drawer and retries the same application on the current page',async()=>{
  api.getMoldApplication.mockRejectedValueOnce(new Error('审批读取暂时失败'))
  const {router,drawer}=await start();await drawer.open(88,{projectId:42});await flushPromises();expect(view.text()).toContain('审批读取暂时失败')
  await view.findAll('button').find(button=>button.text()==='重试读取审批详情')!.trigger('click');await flushPromises()
  expect(api.getMoldApplication).toHaveBeenNthCalledWith(2,88);expect(view.text()).toContain('APP-REFUSED-88');expect(router.currentRoute.value.path).toBe('/product-research')
 })
 it('does not render a response belonging to another project',async()=>{
  api.getMoldApplication.mockResolvedValue(rejected({productLineId:99,approvalNo:'OTHER-PROJECT-SECRET'}))
  const {drawer}=await start();await drawer.open(88,{projectId:42});await flushPromises()
  expect(view.text()).toContain('该申请不属于当前新品');expect(view.text()).not.toContain('OTHER-PROJECT-SECRET')
 })
 it('discards a response arriving after the account changes',async()=>{
  let finish!:(value:MoldApplication)=>void;api.getMoldApplication.mockReturnValueOnce(new Promise(resolve=>{finish=resolve}))
  const {drawer}=await start();const pending=drawer.open(88,{projectId:42});await flushPromises();useUserStore().token='other-login';await flushPromises();finish(rejected());await pending;await flushPromises()
  expect(view.text()).not.toContain('APP-REFUSED-88');expect(view.find('[role=dialog]').exists()).toBe(false)
 })
 it('discards a response after closing or switching the original research project',async()=>{
  let finish!:(value:MoldApplication)=>void;api.getMoldApplication.mockReturnValueOnce(new Promise(resolve=>{finish=resolve}))
  const {drawer}=await start();const pending=drawer.open(88,{projectId:42});await flushPromises();await view.setProps({projectId:43});finish(rejected());await pending;await flushPromises()
  expect(view.text()).not.toContain('APP-REFUSED-88');expect(view.find('[role=dialog]').exists()).toBe(false)
 })
 it.each([
  {currentHandlers:[],remoteStatus:'RUNNING',businessStatus:'awaiting_approval' as const,terminal:false},
  {currentHandlers:[],remoteStatus:'COMPLETED',businessStatus:'rejected' as const,terminal:true},
  {currentHandlers:undefined,remoteStatus:'RUNNING',businessStatus:'rejected' as const,terminal:true},
 ])('never promotes retained RUNNING activity to a current handler for $businessStatus/$currentHandlers',async state=>{
  const row=rejected({...state,activities:[...rejected().activities!,{activityId:2,type:'task',occurredAt:null,actorId:'old-account-999',actorName:'过期处理人',taskId:'old-running-task',payload:{task_status:'RUNNING'}}]});
  const before=JSON.stringify(row.activities);api.getMoldApplication.mockResolvedValue(row);
  const {drawer}=await start();await drawer.open(88,{projectId:42});await flushPromises();
  expect(view.text()).toContain('审批时间线');expect(view.text()).toContain('这个型号不好卖');expect(view.text()).toContain('当前任务');expect(view.findAll('.handler-evidence')).toHaveLength(0);
  expect(view.text()).not.toContain('过期处理人');expect(view.text()).not.toContain('old-running-task');expect(view.text()).not.toContain('old-account-999');expect(JSON.stringify(row.activities)).toBe(before);
 })
})
