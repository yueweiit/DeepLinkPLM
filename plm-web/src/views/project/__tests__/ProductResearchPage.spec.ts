import {mount,flushPromises} from '@vue/test-utils'
import {createMemoryHistory,createRouter} from 'vue-router'
import ElementPlus from 'element-plus'
import {describe,it,expect,vi} from 'vitest'
import Page from '../ProductResearchPage.vue'
vi.mock('../ProductLaunchView.vue',()=>({__esModule:true,default:{template:'<div>新品研发列表</div>'}}))
vi.mock('@/api/modules/productResearch',()=>({getProductResearchDetail:vi.fn().mockResolvedValue({developmentMode:'launch_v1'})}))
describe('retired workflow navigation',()=>{
 it('replaces a retired workflow link with the active new-product list',async()=>{
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/product-research',component:Page}]});await router.push('/product-research?flow=lightweight&productId=9');await router.isReady()
  const wrapper=mount({template:'<router-view/>'},{global:{plugins:[router,ElementPlus]}});await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/product-research');expect(wrapper.text()).toContain('新品研发列表');wrapper.unmount()
 })
})
