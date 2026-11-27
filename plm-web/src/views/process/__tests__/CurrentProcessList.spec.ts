import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MesOperation, MesRoute, ProcessGraph } from '@/api/modules/process'
import { setLanguage } from '@/locales'
import Workspace from '../components/CurrentProcessList.vue'
import Graph from '../components/ProcessGraphView.vue'
import Definition from '../components/ProcessDefinitionDialog.vue'
import { routeTopology } from '../processLayout'

const api=vi.hoisted(()=>({list:vi.fn(),graph:vi.fn()}))
vi.mock('@/api/modules/process',async original=>({...await original<object>(),getCurrentProcesses:api.list,getCurrentProcessGraph:api.graph}))
const route: MesRoute={objectId:81,productId:10,sourceCode:'R-DYE-V2',seriesName:'亮甲 2.0',seriesNameEs:'Rainbow 2.0',routeStatus:'locked',operationIds:[7,7,9],
  source:{product_code:'NBA4030',route_name:'亮甲 2.0 染色-无UV打印 RAINBOW 2.0 teñido, sin impresión UV',status:'published',version:'V2'}}
const variant: MesRoute={...route,objectId:82,sourceCode:'R-CLEAR',source:{...route.source,route_name:'透明款'}}
const different: MesRoute={...variant,productId:11,objectId:83,seriesName:'幻甲',sourceCode:'R-STANDARD',source:{product_code:'NDN4030',route_name:'幻甲标准路线'},operationIds:[]}
const dye: MesOperation={objectId:7,sourceCode:'DYE',source:{operation_name:'PC 染色',operation_name_es:'Teñido PC',category:'染色',category_es:'Teñido',version:'V1',requires_qc:true,requires_machine:false,requires_report:true}}
const assembly: MesOperation={objectId:9,sourceCode:'PACK',source:{operation_name:'组装和打包',version:'V1'}}
const data: ProcessGraph={nodes:[
  {id:91,stepNo:10,nodeCode:'front',name:'前盖染色',operationCode:'local-1',operationId:7,config:{equipmentType:'染色线',needEquipment:true}},
  {id:92,stepNo:20,nodeCode:'rear',name:'后盖染色',operationCode:'local-1',operationId:7},
  {id:93,stepNo:30,nodeCode:'pack',name:'组装和打包',operationCode:'local-2',operationId:9}
],edges:[
  {id:101,edgeNo:1,fromNodeId:91,toNodeId:93,relationType:'finish_to_start'},
  {id:102,edgeNo:2,fromNodeId:92,toNodeId:93,relationType:'finish_to_start'}
]}
let wrapper: VueWrapper
const router = () => createRouter({history:createMemoryHistory(),routes:[{path:'/processes',component:{template:'<div/>'}}]})
beforeEach(()=>{vi.clearAllMocks();api.list.mockResolvedValue({routes:[route,variant,different],operations:[dye,assembly]});api.graph.mockResolvedValue(data)})
afterEach(()=>{wrapper?.unmount();setLanguage('zh-CN');document.body.innerHTML=''})
async function start(query:Record<string,string>={}) {
  const r=router()
  await r.push({path:'/processes',query})
  wrapper=mount(Workspace,{attachTo:document.body,global:{plugins:[r,ElementPlus],stubs:{ProjectProcessRoutePanel:true}}})
  await flushPromises()
  return r
}
async function choose(key:number) {await wrapper.get('.row-name[data-key="'+key+'"]').trigger('click');await flushPromises()}
describe('current process workspace',()=>{
  it('starts with products and carries an exact route search down one level',async()=>{
    await start()
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    expect(api.graph).not.toHaveBeenCalled()
    await wrapper.get('.el-input input').setValue('r-dye')
    await choose(10)
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
    expect(wrapper.get('.row-name').text()).toBe('染色 · 无 UV 打印')
    await choose(81)
    expect(api.graph).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.route-head').text()).toContain('MES V2')
    expect(wrapper.get('.route-meta').text()).toContain('已锁定')
    await wrapper.get('.path button').trigger('click');await flushPromises()
    expect(wrapper.get('.el-input input').element.value).toBe('r-dye')
  })
  it('preserves distinct instances and separates a route equipment override from its operation template',async()=>{
    const router=await start({processProduct:'10',processRoute:'81'})
    expect(wrapper.findAll('[data-step]')).toHaveLength(3)
    await wrapper.get('[data-step="93"]').trigger('click')
    expect(wrapper.get('.step-sheet dl').text()).toContain('前盖染色后盖染色')
    await wrapper.get('.step-sheet dd button').trigger('click')
    expect(wrapper.get('.step-sheet').text()).toContain('染色线')
    expect(wrapper.get('.step-sheet details').text()).toContain('绑定设备需要')
    await wrapper.get('.step-sheet header .link').trigger('click');await flushPromises()
    const modal=document.querySelector('.el-dialog')!
    expect(modal.textContent).toContain('工序定义 · V1')
    expect(modal.textContent).toContain('绑定设备无需')
    expect(wrapper.getComponent(Definition).props('routes')).toHaveLength(2)
    expect(document.querySelector('pre')).toBeNull()
    await router.push({query:{processProduct:'10'}});await flushPromises()
    expect(wrapper.findComponent(Definition).exists()).toBe(false)
  })
  it('counts distinct routes per definition, uses localized names, and navigates to a reference',async()=>{
    setLanguage('es')
    const router=await start({processTab:'operations'})
    expect(wrapper.get('.row-name').text()).toBe('Teñido PC')
    expect(wrapper.get('tbody .numeric').text()).toBe('2')
    await choose(7)
    await (document.querySelector('.references button') as HTMLButtonElement).click();await flushPromises()
    expect(router.currentRoute.value.query.processRoute).toBe('81')
    expect(wrapper.get('.route-head').text()).toContain('teñido')
    expect(wrapper.findComponent(Definition).exists()).toBe(false)
  })
  it('never fetches a route outside the visible product or swaps in a late response',async()=>{
    const r=await start({processProduct:'11',processRoute:'81'})
    expect(api.graph).not.toHaveBeenCalled()
    let finish!:(g:ProcessGraph)=>void
    api.graph.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
    await r.push({query:{processProduct:'10',processRoute:'81'}});await flushPromises()
    await r.push({query:{processProduct:'10',processRoute:'82'}});await flushPromises()
    finish({nodes:[],edges:[]});await flushPromises()
    expect(wrapper.get('.route-head').text()).toContain('透明款')
    expect(wrapper.findAll('[data-step]')).toHaveLength(3)
  })
  it('keeps special edge labels and structured maintenance parameters accessible without displaying JSON',async()=>{
    const graph={nodes:data.nodes.map(n=>({...n,config:{custom:{threshold:0,nested:['A','B']}}})),edges:data.edges.map(e=>({...e,label:'检验合格',condition:{qc:true}}))}
    expect(routeTopology(graph).diagram).toBe(false)
    wrapper=mount(Graph,{props:{graph,maintenance:true},global:{plugins:[ElementPlus]}})
    expect(wrapper.get('.deps').text()).toContain('检验合格')
    await wrapper.get('[data-step="91"]').trigger('click')
    expect(wrapper.get('details').text()).toContain('custom / threshold0')
    expect(wrapper.get('details').text()).toContain('A、B')
    expect(wrapper.find('pre').exists()).toBe(false)
  })
})
