import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import ElementPlus from 'element-plus'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {setLanguage} from '@/locales'
import ProjectCostAttribution from '../components/ProjectCostAttribution.vue'
import {researchAction,getProductResearchDetail,type ProductResearchDetail} from '@/api/modules/productResearch'
vi.mock('@/api/modules/productResearch',()=>({researchAction:vi.fn(),getProductResearchDetail:vi.fn()}))
const pro='11111111-1111-4111-8111-111111111111',max='22222222-2222-4222-8222-222222222222'
const detail=()=>({productId:42,workflow:{version:9,unitCostDraft:{title:'合成核算',currency:'CNY',totals:{manufacturing:3,base:6.4}},costScopes:[{id:pro,name:'Pro',phoneModelIds:[1]},{id:max,name:'Pro Max',phoneModelIds:[2],unitCostDraft:{title:'原目标草稿'}},{id:'inactive',name:'已停用',inactive:true}]}}as unknown as ProductResearchDetail)
let wrapper:VueWrapper
const select=()=>wrapper.getComponent({name:'ElSelect'})
const button=(name:string)=>wrapper.findAll('button').find(b=>b.text()===name)!
beforeEach(()=>{setLanguage('zh-CN');vi.clearAllMocks()})
afterEach(()=>{wrapper?.unmount();setLanguage('zh-CN')})
async function start(d=detail(),writable=true){wrapper=mount(ProjectCostAttribution,{props:{detail:d,sourceScopeId:'',writable},global:{plugins:[ElementPlus]}});await flushPromises()}
it('requires an explicit target and shows original saved prices without offering an occupied or inactive group',async()=>{
 await start();expect(select().props('modelValue')).toBeNull();expect(button('保存归属').attributes('disabled')).toBeDefined();expect(wrapper.text()).toContain('6.4');expect(wrapper.findAllComponents({name:'ElOption'}).map(o=>o.props('label'))).toEqual(['Pro','Pro Max · 已有草稿']);expect(wrapper.findAllComponents({name:'ElOption'})[1].props('disabled')).toBe(true);expect(researchAction).not.toHaveBeenCalled()
})
it('submits only the chosen identities through the original versioned action and switches to the returned target',async()=>{
 await start();await select().setValue(pro);vi.mocked(researchAction).mockResolvedValueOnce(detail());await button('保存归属').trigger('click');await flushPromises();expect(researchAction).toHaveBeenCalledWith(42,'unit_cost_assign_scope',9,{costScopeId:'',targetCostScopeId:pro},expect.any(String));expect(wrapper.emitted('assigned')).toEqual([[pro]]);expect(wrapper.emitted('updated')).toHaveLength(1)
})
it('replays the same captured request after an unknown save result without changing the target or version',async()=>{
 await start();await select().setValue(pro);vi.mocked(researchAction).mockRejectedValueOnce(new Error('保存超时'));await button('保存归属').trigger('click');await flushPromises();const first=vi.mocked(researchAction).mock.calls[0];expect(wrapper.text()).toContain('保存超时');expect(select().props('disabled')).toBe(true);const newer=detail();newer.workflow.version=10;await wrapper.setProps({detail:newer});vi.mocked(researchAction).mockResolvedValueOnce(newer);await button('重试本次归属').trigger('click');await flushPromises();expect(vi.mocked(researchAction).mock.calls[1]).toEqual(first)
})
it('can reload a failed intent and does not allow another move if the source draft has already moved',async()=>{
 await start();await select().setValue(pro);vi.mocked(researchAction).mockRejectedValueOnce(new Error('待核对'));await button('保存归属').trigger('click');await flushPromises();const moved=detail();delete moved.workflow.unitCostDraft;vi.mocked(getProductResearchDetail).mockResolvedValueOnce(moved);await button('重新载入').trigger('click');await flushPromises();expect(getProductResearchDetail).toHaveBeenCalledWith(42);await wrapper.setProps({detail:moved});expect(wrapper.text()).toContain('没有可归属的成本草稿');expect(wrapper.findAll('button').some(b=>b.text()==='保存归属')).toBe(false);expect(researchAction).toHaveBeenCalledOnce()
})
it('keeps a read-only user from submitting or changing the target',async()=>{await start(detail(),false);expect(select().props('disabled')).toBe(true);expect(button('保存归属').attributes('disabled')).toBeDefined();expect(researchAction).not.toHaveBeenCalled()})
it('ignores a late financial response after leaving its project or login context',async()=>{
 await start();await select().setValue(pro);let resolve!:(d:ProductResearchDetail)=>void;vi.mocked(researchAction).mockImplementationOnce(()=>new Promise(r=>resolve=r));await button('保存归属').trigger('click');wrapper.unmount();resolve(detail());await flushPromises();expect(wrapper.emitted('updated')).toBeUndefined();expect(wrapper.emitted('assigned')).toBeUndefined()
})
it('uses the existing Spanish dictionary for attribution controls and server errors without translating product names',async()=>{
 setLanguage('es');await start();expect(wrapper.text()).toContain('Asignar costo');expect(wrapper.text()).toContain('合成核算');expect(button('Guardar asignación')).toBeDefined();expect(select().props('placeholder')).toBe('Elegir Pro, Pro Max u otra especificación');await select().setValue(pro);vi.mocked(researchAction).mockRejectedValueOnce(new Error('目标已有成本草稿，请先查看目标草稿；本次不会覆盖'));await button('Guardar asignación').trigger('click');await flushPromises();expect(wrapper.get('[role="alert"]').text()).toContain('El destino ya tiene un borrador');expect(button('Reintentar esta asignación')).toBeDefined();expect(wrapper.text()).not.toContain('设置归属')
})
