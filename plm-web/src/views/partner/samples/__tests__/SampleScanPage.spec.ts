import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {createRouter,createMemoryHistory} from 'vue-router'
import ElementPlus from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {useUserStore} from '@/stores/user'
import Scan from '../SampleScanPage.vue'
const api=vi.hoisted(()=>({scan:vi.fn()}));vi.mock('@/api/modules/sampleManagement',()=>({scanSample:api.scan}))
vi.mock('../SampleShipmentActionDialog.vue',()=>({default:{name:'SampleShipmentActionDialog',props:['shipment','action'],template:'<div>本人实际签收表</div>'}}))
let wrapper:VueWrapper
beforeEach(()=>{vi.clearAllMocks();setActivePinia(createPinia());useUserStore().token='receiver';useUserStore().profile={userId:8,roleCode:'mexico'} as never})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML=''})
async function start(){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/sample-scan',component:Scan},{path:'/samples',component:{template:'<div>已授权样品详情</div>'}}]});await router.push('/sample-scan?code=SY-000071');wrapper=mount({template:'<router-view/>'},{global:{plugins:[router,ElementPlus]},attachTo:document.body});await flushPromises();return router}
describe('authorized sample scan',()=>{
 it('opens the domestic canonical type rather than exposing an internal receipt code as a new identity',async()=>{api.scan.mockResolvedValue({type:'identity',sampleIdentityId:71,sampleCode:'SY-000071'});const router=await start();expect(router.currentRoute.value.path).toBe('/samples');expect(router.currentRoute.value.query.identityId).toBe('71')})
 it('shows only the assigned minimal parcel rows to a Mexico receiver and keeps cost extras out of the view',async()=>{api.scan.mockResolvedValue({type:'assigned_shipments',sampleCode:'SY-000071',cost:999999,shipments:[{id:8,code:'SS-8',name:'本人待收',status:'dispatched',direction:'mexico_outbound',version:1,destination:'墨西哥办公室',rows:[{rowId:'r1',sampleCode:'SY-000071',name:'透明壳',qty:3}],totals:{inTransit:3}}]});await start();expect(api.scan).toHaveBeenCalledExactlyOnceWith('SY-000071');expect(wrapper.text()).toContain('本人待收');expect(wrapper.text()).not.toContain('999999');await wrapper.findAll('button').find(b=>b.text()==='登记本次实际签收')!.trigger('click');expect(wrapper.getComponent({name:'SampleShipmentActionDialog'}).props('action')).toBe('receive')})
 it('does not show a receive form for unknown or unauthorized codes',async()=>{api.scan.mockRejectedValue(new Error('当前账号无权查看或没有待收记录'));await start();expect(wrapper.text()).toContain('无权查看');expect(wrapper.findComponent({name:'SampleShipmentActionDialog'}).exists()).toBe(false)})
 it('clears assigned parcel data when the login changes',async()=>{api.scan.mockResolvedValue({type:'assigned_shipments',sampleCode:'SY-000071',shipments:[]});await start();useUserStore().token='other';await flushPromises();expect(wrapper.text()).not.toContain('SY-000071')})
})
