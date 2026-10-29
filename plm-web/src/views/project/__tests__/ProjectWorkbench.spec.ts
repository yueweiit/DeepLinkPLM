import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectWorkbench from '../ProjectWorkbench.vue'
import { getWorkbenchProjects, getProjectBusinessOverview, type WorkbenchPage, type WorkbenchProject, type ProjectOverview } from '@/api/modules/projectWorkbench'
import { getMoldApplications } from '@/api/modules/moldApplications'
import { useUserStore } from '@/stores/user'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'
vi.mock('@/api/modules/projectWorkbench',()=>({getWorkbenchProjects:vi.fn(),getProjectBusinessOverview:vi.fn()}))
vi.mock('@/api/modules/moldApplications',()=>({getMoldApplications:vi.fn(),moldBusinessStatusNames:{draft:'草稿',approved:'Tiffany 已同意'}}))
const caps={applications:true,schedules:true,molds:true,documents:true,bom:true,process:true,finance:true}
const row={projectId:11,projectName:'骑士 18 Pro 开发',projectCode:'IT-11',projectKind:'model_extension',registeredAt:'2026-10-15',projectState:'active',productLineName:'骑士',phoneName:'iPhone 18 Pro',phoneModelCode:'0101',ownerName:'工程',expectedDeliveryDate:'2026-11-01',flowNode:'立项确认',phase:'待开工',nextStep:'确认排程',risks:[],applicationCount:1,archiveCount:1,moldSetCount:1,physicalSetCount:0} as unknown as WorkbenchProject
const page=(rows:WorkbenchProject[]=[]):WorkbenchPage=>({content:rows,page:1,size:50,totalElements:rows.length,totalPages:rows.length?1:0,stats:{active:rows.length,completed:0,stopped:0,all:rows.length},capabilities:caps,unlinkedApplications:{total:2,counts:[]}})
let wrapper:VueWrapper
async function start(path='/projects'){
 const pinia=createPinia();setActivePinia(pinia);useUserStore().token='test-session'
 const router=createRouter({history:createMemoryHistory(),routes:['/projects','/mold-schedules','/molds','/market-watch','/mold-applications','/files','/dashboard','/development-expenses'].map(path=>({path,component:{template:'<div/>'}}))});await router.push(path);await router.isReady()
 wrapper=mount(ProjectWorkbench,{global:{plugins:[pinia,router,ElementPlus],stubs:{ElDrawer:{props:['modelValue'],template:'<aside v-if="modelValue"><slot/></aside>'}}}});await flushPromises();return router
}
beforeEach(()=>{vi.clearAllMocks();vi.mocked(getWorkbenchProjects).mockResolvedValue(page());vi.mocked(getMoldApplications).mockResolvedValue({content:[],page:1,size:50,totalElements:0,totalPages:0})})
afterEach(()=>wrapper?.unmount())
describe('formal project workbench',()=>{
 it('explains an empty active view instead of displaying historical SKUs',async()=>{await start();expect(wrapper.text()).toContain('暂无进行中的正式开发项目');expect(wrapper.text()).toContain('历史归档产品');expect(getWorkbenchProjects).toHaveBeenCalledWith(expect.objectContaining({view:'active',size:50}));expect(wrapper.findAll('.project-record')).toHaveLength(0)})
 it('delegates stopped view, category and pagination to the server',async()=>{vi.mocked(getWorkbenchProjects).mockResolvedValue(page([row]));await start('/projects?tab=abandoned');expect(getWorkbenchProjects).toHaveBeenCalledWith(expect.objectContaining({view:'stopped'}));expect(wrapper.text()).toContain('0101');expect(wrapper.text()).toContain('节点：立项确认');expect(wrapper.text()).toContain('待开工')})
 it('lists unlinked applications separately and continues the same draft in the entry page',async()=>{vi.mocked(getMoldApplications).mockResolvedValue({content:[{applicationId:23,status:'draft',model:'IT Phone',businessStatus:'draft'} as never],page:1,size:50,totalElements:1,totalPages:1});const router=await start();await wrapper.findAll('.views button').find(b=>b.text().includes('未建档申请'))!.trigger('click');await flushPromises();expect(getMoldApplications).toHaveBeenCalledWith(expect.objectContaining({unlinked:true,size:50}));await wrapper.findAll('button').find(b=>b.text()==='继续草稿')!.trigger('click');await flushPromises();expect(router.currentRoute.value.path).toBe('/market-watch');expect(router.currentRoute.value.query).toEqual({applicationId:'23',edit:'1'})})
 it('opens exact set and scoped files from a business overview',async()=>{vi.mocked(getProjectBusinessOverview).mockResolvedValue({...row,capabilities:caps,applications:[],archives:[{itemId:31,moldName:'共用档案',engravedCode:'1144'}],moldSets:[{moldSetId:41,assetNumber:'MS-000041',setName:'第二套'}],childProjects:[],documentCount:2} as unknown as ProjectOverview);const router=await start('/projects?productId=11');expect(getProjectBusinessOverview).toHaveBeenCalledWith(11);expect(wrapper.text()).toContain('1144');await wrapper.findAll('button').find(b=>b.text()==='逐套排程')!.trigger('click');await flushPromises();expect(router.currentRoute.value.query).toEqual({moldSetId:'41',projectId:'11'})})
 it('invalidates summaries on saved workspace changes without clearing drafts',async()=>{await start();const state=useCollaborationWorkspace();const clear=vi.spyOn(state.drafts,'clear');state.changed();await flushPromises();expect(getWorkbenchProjects).toHaveBeenCalledTimes(2);expect(clear).not.toHaveBeenCalled()})
 it('does not display inaccessible module counts or actions',async()=>{vi.mocked(getProjectBusinessOverview).mockResolvedValue({...row,capabilities:Object.fromEntries(Object.keys(caps).map(k=>[k,false])),applications:[],archives:[],moldSets:[],childProjects:[]} as unknown as ProjectOverview);await start('/projects?productId=11');expect(wrapper.text()).toContain('暂无读取权限');expect(wrapper.findAll('button').some(b=>/图纸与文件|关联费用|项目 BOM/.test(b.text()))).toBe(false)})
})
