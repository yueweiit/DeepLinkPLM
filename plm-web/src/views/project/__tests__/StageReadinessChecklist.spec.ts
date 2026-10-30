import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { checkResearchDesign, checkResearchStage, type DesignDraftInput, type StageReadiness } from '@/api/modules/productResearch';
import StageReadinessChecklist from '../components/StageReadinessChecklist.vue';
vi.mock('@/api/modules/productResearch',()=>({checkResearchDesign:vi.fn(),checkResearchStage:vi.fn()}));
let wrapper:VueWrapper;
const ready:StageReadiness={version:1,ready:true,checks:[]};
const draft:DesignDraftInput={profile:{productName:'隔离验收新品',model:'',requirements:'透明PC',targetCost:null,expectedDeliveryDate:null},plan:{method:'supplier',needsMold:true,phoneModelIds:[]}};
beforeEach(()=>{vi.useFakeTimers();vi.mocked(checkResearchDesign).mockReset();vi.mocked(checkResearchStage).mockReset();});
afterEach(()=>{wrapper?.unmount();vi.useRealTimers();});
function start(){wrapper=mount(StageReadinessChecklist,{attachTo:document.body,props:{productId:42,version:1,draft:structuredClone(draft),stage:1,writable:true},global:{plugins:[ElementPlus]}});}
it('checks the latest unsaved input after typing settles and emits the existing field target',async()=>{
 vi.mocked(checkResearchDesign).mockResolvedValue({version:1,ready:false,checks:[{code:'OWNER',title:'项目负责人',target:'owner',complete:false,message:'请选择有效负责人'}]});
 start();await wrapper.setProps({draft:{...structuredClone(draft),profile:{...draft.profile,requirements:'修改后的结构要求'}}});
 await vi.advanceTimersByTimeAsync(350);await flushPromises();
 expect(wrapper.text()).toContain('待办 1 项');expect(wrapper.get('.check-summary').text()).toBe('项目负责人');expect(wrapper.get('.single-check-message').text()).toBe('请选择有效负责人');expect(wrapper.find('button[aria-controls]').exists()).toBe(false);
 expect(checkResearchDesign).toHaveBeenCalledTimes(1);expect(checkResearchDesign).toHaveBeenCalledWith(42,expect.objectContaining({profile:expect.objectContaining({requirements:'修改后的结构要求'})}));
 await wrapper.setProps({busy:true});expect((wrapper.get('button[aria-label="补齐项目负责人"]').element as HTMLButtonElement).disabled).toBe(true);await wrapper.setProps({busy:false});
 await wrapper.get('button[aria-label="补齐项目负责人"]').trigger('click');expect(wrapper.emitted('locate')).toEqual([['owner']]);
});
it('ignores an earlier response and clears a stale ready result when the current check fails, then retries',async()=>{
 let resolveOld!:(value:StageReadiness)=>void;
 vi.mocked(checkResearchDesign).mockReturnValueOnce(new Promise(resolve=>{resolveOld=resolve;})).mockResolvedValueOnce(ready).mockRejectedValueOnce(new Error('查询失败')).mockResolvedValueOnce(ready);
 start();await vi.advanceTimersByTimeAsync(350);
 await wrapper.setProps({version:2});await vi.advanceTimersByTimeAsync(350);await flushPromises();
 resolveOld({version:1,ready:false,checks:[{code:'OWNER',title:'旧请求',target:'owner',complete:false,message:'过期'}]});await flushPromises();
 expect(wrapper.text()).toContain('已满足进入画3D产品图的条件');expect(wrapper.text()).not.toContain('旧请求');
 await wrapper.setProps({version:3});await vi.advanceTimersByTimeAsync(350);await flushPromises();
 expect(wrapper.get('[role="alert"]').text()).toContain('查询失败');expect(wrapper.text()).not.toContain('已满足进入画3D产品图的条件');
 await wrapper.get('button').trigger('click');await flushPromises();expect(wrapper.text()).toContain('已满足进入画3D产品图的条件');
});

it.each([
 [2,'进入3D打样','drawings'],[3,'进入样品确认','sample'],[4,'进入模具设计','review:design'],
 [5,'进入开模','mold-links'],[6,'进入试产','mold:7'],[7,'进入发模','review:pilot'],[8,'完成发模','shipment:7'],
])('shows the destination and existing edit target for step %s through the shared component',async(stage,destination,target)=>{
 vi.mocked(checkResearchStage).mockResolvedValue({version:1,ready:false,checks:[{code:'GAP',title:'实际结果待补',target:target as string,complete:false,message:'请完成实际资料'}]});
 wrapper=mount(StageReadinessChecklist,{attachTo:document.body,props:{productId:42,version:1,stage:stage as number,draft:{stage:stage as number},writable:false},global:{plugins:[ElementPlus]}});
 await vi.advanceTimersByTimeAsync(350);await flushPromises();
 expect(checkResearchStage).toHaveBeenCalledWith(42,{stage});expect(checkResearchDesign).not.toHaveBeenCalled();
 expect(wrapper.attributes('aria-label')).toContain(destination);expect(wrapper.get('.check-summary').text()).toBe('实际结果待补');
 await wrapper.get('button[aria-label="查看实际结果待补"]').trigger('click');expect(wrapper.emitted('locate')?.[0]).toEqual([target]);
});


it('shows the first of multiple server gaps immediately and expands the same full checklist for other locations',async()=>{
 vi.mocked(checkResearchDesign).mockResolvedValue({version:1,ready:false,checks:[{code:'OWNER',title:'项目负责人确认立项',target:'owner',complete:false,message:'请由负责人确认当前需求'},{code:'REQ',title:'开发需求',target:'requirements',complete:false,message:'请补充产品需求'}]});
 start();await vi.advanceTimersByTimeAsync(350);await flushPromises();expect(wrapper.get('.check-summary').text()).toContain('项目负责人确认立项');expect(wrapper.get('.check-summary').text()).toContain('其余 1 项');await wrapper.get('button[aria-label="补齐项目负责人确认立项"]').trigger('click');expect(wrapper.emitted('locate')).toEqual([['owner']]);expect(wrapper.get('.check-details').isVisible()).toBe(false);
 await wrapper.get('button[aria-controls]').trigger('click');expect(wrapper.get('.check-details').isVisible()).toBe(true);await wrapper.get('button[aria-label="补齐开发需求"]').trigger('click');expect(wrapper.emitted('locate')).toEqual([['owner'],['requirements']]);await wrapper.get('button[aria-controls]').trigger('click');expect(wrapper.get('.check-details').isVisible()).toBe(false);
});

it.each([2,3])('shows deferred 3D drawing as a nonblocking follow-up with the original materials destination in step %s',async(stage)=>{
 const checked:StageReadiness={version:1,ready:true,checks:[{code:stage===2?'PRODUCT_3D_DRAWING':'PROTOTYPE_DRAWING',title:'采用的3D产品图版本',target:'drawings',complete:false,blocking:false,message:'3D图可后补，不影响本步；可在图片与附件上传并采用真实3D图版本'}]};vi.mocked(checkResearchStage).mockResolvedValue(checked);wrapper=mount(StageReadinessChecklist,{attachTo:document.body,props:{productId:42,version:1,stage,draft:{stage},writable:true},global:{plugins:[ElementPlus]}});await vi.advanceTimersByTimeAsync(350);await flushPromises();expect(wrapper.text()).toContain('已满足');expect(wrapper.text()).not.toContain('待办 1 项');expect(wrapper.get('[aria-label="可后补资料"]').text()).toContain('3D图可后补，不影响本步');expect(wrapper.emitted('checked')?.[0]).toEqual([checked]);await wrapper.get('button[aria-label="补充采用的3D产品图版本"]').trigger('click');expect(wrapper.emitted('locate')).toEqual([['drawings']]);
});
it('keeps required fields in the blocking count while the drawing reminder stays visible and read-only',async()=>{
 vi.mocked(checkResearchDesign).mockResolvedValue({version:1,ready:false,checks:[{code:'OWNER',title:'项目负责人',target:'owner',complete:false,message:'请选择有效负责人'},{code:'PRODUCT_3D_DRAWING',title:'3D产品图',target:'drawings',complete:false,blocking:false,message:'3D图可后补，不影响本步'}]});start();await wrapper.setProps({writable:false});await vi.advanceTimersByTimeAsync(350);await flushPromises();expect(wrapper.text()).toContain('待办 1 项');expect(wrapper.get('.check-summary').text()).toBe('项目负责人');expect(wrapper.get('[aria-label="可后补资料"]').text()).toContain('3D图可后补');await wrapper.get('button[aria-label="查看3D产品图"]').trigger('click');expect(wrapper.emitted('locate')).toEqual([['drawings']]);
});
