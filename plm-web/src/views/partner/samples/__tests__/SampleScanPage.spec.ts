import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {createRouter,createMemoryHistory} from 'vue-router'
import ElementPlus from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {useUserStore} from '@/stores/user'
import Scan from '../SampleScanPage.vue'
const api=vi.hoisted(()=>({scan:vi.fn()}));vi.mock('@/api/modules/sampleManagement',()=>({scanSample:api.scan}))
let wrapper:VueWrapper
beforeEach(()=>{vi.clearAllMocks();setActivePinia(createPinia());useUserStore().token='engineer';useUserStore().profile={userId:8,roleCode:'engineer'} as never})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML=''})
async function start(code='SY-000071'){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/sample-scan',component:Scan},{path:'/samples',component:{template:'<div>已授权样品详情</div>'}}]});await router.push({path:'/sample-scan',query:{code}});wrapper=mount({template:'<router-view/>'},{global:{plugins:[router,ElementPlus]},attachTo:document.body});await flushPromises();return router}
describe('authorized sample scan redirects',()=>{
 it.each(['engineer','mexico'])('opens the registry-projected canonical identity for %s without a second sample projection',async role=>{useUserStore().profile={userId:8,roleCode:role} as never;api.scan.mockResolvedValue({type:'identity',identity:{sampleIdentityId:71,sampleCode:'SY-000071'}});const router=await start();expect(api.scan).toHaveBeenCalledExactlyOnceWith('SY-000071');expect(router.currentRoute.value.path).toBe('/samples');expect(router.currentRoute.value.query.identityId).toBe('71');expect(wrapper.text()).toBe('已授权样品详情')})
 it('preserves the domestic legacy batch link through its explicit original-batch view',async()=>{api.scan.mockResolvedValue({type:'legacy_batch',batchId:91});const router=await start('SP-000091');expect(router.currentRoute.value.path).toBe('/samples');expect(router.currentRoute.value.query).toMatchObject({view:'batches',id:'91'})})
 it('shows the permission failure and retries the same scanned code through the registry endpoint',async()=>{api.scan.mockRejectedValueOnce(new Error('当前账号无权查看此样品')).mockResolvedValue({type:'identity',sampleIdentityId:71});const router=await start();expect(wrapper.text()).toContain('无权查看');expect(router.currentRoute.value.path).toBe('/sample-scan');await wrapper.findAll('button').find(button=>button.text()==='重试')!.trigger('click');await flushPromises();expect(api.scan).toHaveBeenNthCalledWith(2,'SY-000071');expect(router.currentRoute.value.query.identityId).toBe('71')})
 it('ignores a pending scan response after the login changes',async()=>{let finish!:(value:unknown)=>void;api.scan.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}));const router=await start();useUserStore().token='other-account';await flushPromises();finish({type:'identity',sampleIdentityId:71});await flushPromises();expect(router.currentRoute.value.path).toBe('/sample-scan');expect(wrapper.text()).not.toContain('已授权样品详情')})
 it('requires a scan code before querying the registry',async()=>{await start('');expect(api.scan).not.toHaveBeenCalled();expect(wrapper.text()).toContain('请扫描标签或输入样品码')})
})
