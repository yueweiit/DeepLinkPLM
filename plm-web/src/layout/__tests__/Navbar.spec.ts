import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Navbar from '../Navbar.vue'
import { PRODUCT_RESEARCH_STAGES } from '@/constants/productResearch'

vi.mock('@/composables/useClientUpdate',()=>({useClientUpdate:()=>({available:{value:false},refresh:vi.fn()})}))
let wrapper:VueWrapper
afterEach(()=>wrapper?.unmount())
async function start(query:string){
 const pinia=createPinia();setActivePinia(pinia)
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/product-research',name:'ProductResearch',component:{template:'<div/>'}},{path:'/projects',meta:{breadcrumb:['工作台','项目中心']},component:{template:'<div/>'}}]})
 await router.push('/product-research'+query);await router.isReady();wrapper=mount(Navbar,{global:{plugins:[pinia,router,ElementPlus]}});await flushPromises();return router
}
describe('research breadcrumb follows the same step identity as the project',()=>{
 it.each(PRODUCT_RESEARCH_STAGES)('shows $name for the canonical $key link',async step=>{
  await start(`?productId=42&step=${step.key}`);expect(wrapper.findAll('.el-breadcrumb__inner').map(v=>v.text())).toEqual(['新品研发',step.name])
 })
 it.each([[2,'模具设计'],[3,'开模'],[4,'试产']])('maps legacy stage %s through the shared compatibility mapping',async(stage,label)=>{
  await start(`?productId=42&stage=${stage}`);expect(wrapper.findAll('.el-breadcrumb__inner').map(v=>v.text())).toEqual(['新品研发',label])
 })
 it('shows the unified project directory and preserves other configured breadcrumbs',async()=>{
  const router=await start('');expect(wrapper.findAll('.el-breadcrumb__inner').map(v=>v.text())).toEqual(['新品研发','新品项目']);await router.push('/projects');await flushPromises();expect(wrapper.findAll('.el-breadcrumb__inner').map(v=>v.text())).toEqual(['工作台','项目中心'])
 })
})
