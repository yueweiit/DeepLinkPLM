import {mount,flushPromises} from '@vue/test-utils'
import {createPinia} from 'pinia'
import {createMemoryHistory,createRouter} from 'vue-router'
import ElementPlus from 'element-plus'
import {describe,it,expect,vi} from 'vitest'
import Page from '../ProductBusinessPage.vue'
import {getResearchBusiness,getProductResearch} from '@/api/modules/productResearch'
vi.mock('../ProductLaunchView.vue',()=>({__esModule:true,default:{template:'<div>项目办理</div>'}}))
vi.mock('@/api/modules/productResearch',()=>({getResearchBusiness:vi.fn(),getProductResearch:vi.fn(),getResearchReferencePage:vi.fn(),getResearchReferencePhones:vi.fn()}))
describe('independent project business directory',()=>{
 it.each([['查看／办理','/product-research-promotions'],['防摔支架壳','/product-research']])('shows cross-project relations and opens the same project through %s',async(label,path)=>{
  vi.mocked(getProductResearch).mockResolvedValue({content:[],totalElements:0} as any)
  vi.mocked(getResearchBusiness).mockResolvedValue({content:[{productId:42,productCode:'RD42',productName:'防摔支架壳',currentStepNo:4,currentPlanVersion:2,record:{id:1,channel:'mcn',target:'合作机构',action:'视频素材',plannedDate:'2026-11-01',status:'working',recordVersion:2,planVersion:1}}],totalElements:1,summary:{recordCount:1,projectCount:1}} as any)
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/product-research-promotions',component:Page,meta:{researchBusiness:'promotion'}},{path:'/product-research',component:{template:'<div/>'}}]})
  await router.push('/product-research-promotions');await router.isReady()
  const wrapper=mount({template:'<router-view/>'},{global:{plugins:[createPinia(),router,ElementPlus]}});await flushPromises()
  expect(getResearchBusiness).toHaveBeenCalledWith(expect.objectContaining({kind:'promotion',page:1,status:'all'}))
  expect(wrapper.text()).toContain('防摔支架壳');expect(wrapper.text()).toContain('合作机构');expect(wrapper.text()).toContain('计划已调整，需核对')
  await wrapper.findAll('button').find(b=>b.text()===label)!.trigger('click');await flushPromises()
  expect(router.currentRoute.value.query).toEqual({productId:'42',step:'sample-confirm'});
  expect(router.currentRoute.value.path).toBe(path);wrapper.unmount()
 })
})
