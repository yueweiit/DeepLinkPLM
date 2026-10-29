import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { h } from 'vue';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';
import ElementPlus, { ElSelect } from 'element-plus';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import UnitCostSheet from '../components/UnitCostSheet.vue';
import { useUserStore } from '@/stores/user';
import { researchAction, previewUnitCost, type ProductResearchDetail, type UnitCostSheet as Sheet } from '@/api/modules/productResearch';

vi.mock('@/api/modules/productResearch', () => ({researchAction:vi.fn(),previewUnitCost:vi.fn()}));
let wrapper:VueWrapper;
const sourceDraft=():Sheet=>({title:'来源核算草稿',currency:'',taxBasis:'source',bomId:10,routeId:31,
  sourceOutputQty:10,references:[{type:'bom',id:10}],totals:{base:null},complete:false,missing:['核算币种待确认'],
  rows:[{id:'material',category:'material',name:'来源物料',mode:'usage',unitPrice:null,quantity:null,
    sourceQuantity:0.00000001,sourceOutputQty:10,warning:'请核对单件用量'}]});
async function start(permissions=['finance:read','finance:write']) {
  const pinia=createPinia();setActivePinia(pinia);useUserStore().permissions=permissions;
  const detail={productId:42,status:'developing',canWrite:true,workflow:{version:3,unitCostDraft:sourceDraft()},materials:[],bomRoutes:[]} as unknown as ProductResearchDetail;
  vi.mocked(previewUnitCost).mockResolvedValue(sourceDraft());
  vi.mocked(researchAction).mockResolvedValue({...detail,workflow:{...detail.workflow,unitCostDraft:{...sourceDraft(),currency:'USD'}}});
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{render:()=>h(UnitCostSheet,{detail})}}]});
  await router.push('/');await router.isReady();wrapper=mount(RouterView,{global:{plugins:[pinia,router,ElementPlus]}});await flushPromises();
  return wrapper.findAllComponents(ElSelect).find(s=>s.props('placeholder')==='币种待确认')!;
}
beforeEach(()=>vi.clearAllMocks());afterEach(()=>{wrapper?.unmount();vi.restoreAllMocks();});
it('keeps an imported unknown currency visible and explains batch output instead of labelling it CNY',async()=>{
  const currency=await start();expect(currency.props('modelValue')).toBe('');expect(currency.props('placeholder')).toBe('币种待确认');
  expect(wrapper.text()).toContain('来源 BOM 基准产量：10');expect(wrapper.text()).toContain('不将批量用量直接当成单件成本');expect(wrapper.text()).toContain('核算币种待确认');
  expect(wrapper.find('[data-label="单件成本（币种待确认）"]').exists()).toBe(true);expect(wrapper.find('[data-label="人民币单价"]').exists()).toBe(false);
});
it('lets finance explicitly choose currency while retaining unknown usage and original batch evidence on save',async()=>{
  const currency=await start();currency.vm.$emit('update:modelValue','USD');await flushPromises();
  await wrapper.findAll('button').find(b=>b.text()==='保存成本草稿')!.trigger('click');await flushPromises();
  expect(researchAction).toHaveBeenCalledWith(42,'unit_cost_save',3,expect.objectContaining({currency:'USD',sourceOutputQty:10,rows:[expect.objectContaining({quantity:null,sourceQuantity:0.00000001,sourceOutputQty:10})]}));
});
it('does not turn engineering or read access into financial maintenance authority',async()=>{
  const currency=await start(['finance:read','bom:write','bom:publish']);expect(currency.find('input').attributes('disabled')).toBeDefined();
  expect(wrapper.findAll('button').some(b=>b.text()==='保存成本草稿'||b.text()==='确认成本版本')).toBe(false);expect(researchAction).not.toHaveBeenCalled();
});
