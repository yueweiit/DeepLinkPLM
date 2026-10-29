import { mount,flushPromises,type VueWrapper } from '@vue/test-utils'
import { createPinia,setActivePinia } from 'pinia'
import { createRouter,createMemoryHistory } from 'vue-router'
import ElementPlus,{ElFormItem,ElSelect} from 'element-plus'
import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest'
const products=vi.hoisted(()=>({createProduct:vi.fn(),getProductDetail:vi.fn(),getProductPage:vi.fn(),updateProduct:vi.fn(),updateProductBasicInfo:vi.fn()}))
const archives=vi.hoisted(()=>({getCollaborationItem:vi.fn(),linkCollaborationProjects:vi.fn()}))
const metadata=vi.hoisted(()=>({getSalesCatalog:vi.fn()}))
vi.mock('@/api/modules/productionMetadata',()=>metadata)
const reference=vi.hoisted(()=>({getPhoneCompatibleMolds:vi.fn()}))
vi.mock('@/api/modules/product',()=>products)
vi.mock('@/api/modules/collaboration',()=>archives)
vi.mock('@/api/modules/phoneReference',()=>reference)
import ProductEdit from '../ProductEdit.vue'
import { useUserStore } from '@/stores/user'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'
let wrapper:VueWrapper|undefined
const phone={phoneModelId:72,id:72,brandId:5,brandName:'Apple',modelName:'Apple iPhone 18 Pro',aliases:[],codeItemId:317,phoneModelCode:'1217',codeLinkStatus:'linked',status:'enabled'}
const mold={itemId:45,moldSetId:67,phoneModelId:72,modelName:phone.modelName,moldName:'Apple iPhone 17 Pro / iPhone 18 Pro',styleName:'骑士',engravedCode:'1144',physicalCode:'MS-000067',setNo:1,parts:[{partType:'PC',code:'MFA201144'},{partType:'TPU',code:'MFA101144'}]}
beforeEach(()=>{vi.clearAllMocks();metadata.getSalesCatalog.mockResolvedValue([{id:50,salesStatus:'on_sale'},{id:47,salesStatus:'off_sale'}]);products.getProductPage.mockResolvedValue({content:[{productId:47,productName:'清水套',productCode:'NLT',seriesName:'清水套'},{productId:50,productName:'骑士',productCode:'NFA4020',seriesName:'骑士',material:'PC+TPU',customerName:''}]});products.createProduct.mockResolvedValue({productId:88,productCode:'PRD-88'});reference.getPhoneCompatibleMolds.mockResolvedValue([mold]);archives.getCollaborationItem.mockResolvedValue({itemId:45});archives.linkCollaborationProjects.mockResolvedValue(undefined)})
afterEach(()=>{wrapper?.unmount();wrapper=undefined})
async function start(path='/products/create') {const pinia=createPinia();setActivePinia(pinia);useUserStore().token='project-test';const router=createRouter({history:createMemoryHistory(),routes:['/products/create','/products/:id/edit','/market-watch','/projects/:id','/product-research'].map(path=>({path,component:{template:'<div />'}}))});await router.push(path);await router.isReady();wrapper=mount(ProductEdit,{global:{plugins:[ElementPlus,pinia,router],stubs:{SharedCompatiblePhoneSelector:{name:'SharedCompatiblePhoneSelector',props:['modelValue','disabled'],emits:['update:modelValue','selected'],template:'<div data-test="phone-member-picker" />'},ElForm:{props:['model','rules'],methods:{validate:()=>Promise.resolve(true)},template:'<form><slot /></form>'},ElSegmented:{props:['modelValue','options'],emits:['update:modelValue'],template:'<div><button v-for="option in options" type="button" @click="$emit(\'update:modelValue\',option.value)">{{option.label}}</button></div>'},ElSelect:{props:['modelValue'],emits:['update:modelValue'],template:'<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value)"><slot /></select>'},ElOption:{props:['value','label'],template:'<option :value="value">{{label}}</option>'}}}});await flushPromises();return router}
describe('project standard-phone selection',()=>{
  it.each([['stage=4','pilot'],['step=mold-design','mold-design'],['step=ship','ship']])('fills a missing research code and returns through %s without resaving legacy stages or costs',async(query,step)=>{
    products.getProductDetail.mockResolvedValue({basicInfo:{productCode:'PRD-42',productName:'透甲2.0',productType:'product_line',productFlowMode:'new_product_line',status:'released',productSpecificCode:null,currentStage:'小批量试产',currentStepNo:4,expectedArrivalAt:'2026-11-01T10:00:00'},costBreakdown:[],testItems:[]});
    products.updateProduct.mockResolvedValue({});
    const router=await start(`/products/42/edit?source=research&${query}`);
    expect(router.currentRoute.value.path).toBe('/products/42/edit');expect(wrapper!.find('input[aria-label="两位产品特定编码"]').exists()).toBe(true);
    expect(wrapper!.text()).not.toContain('完整 22 步开发流程');
    await wrapper!.get('input[aria-label="两位产品特定编码"]').setValue('TG');
    await wrapper!.findAll('button').find(b=>b.text()==='保存产品特定编码')!.trigger('click');await flushPromises();
    expect(products.updateProduct).toHaveBeenCalledWith(42,{productSpecificCode:'TG'});expect(products.updateProductBasicInfo).not.toHaveBeenCalled();
    expect(router.currentRoute.value).toMatchObject({path:'/product-research',query:{productId:'42',step}});expect(useCollaborationWorkspace().revision).toBe(1);
  })
  it('keeps an existing product code readonly and rejects malformed initial codes without a write',async()=>{
    const detail={basicInfo:{productCode:'PRD-42',productName:'透甲2.0',productType:'product_line',productFlowMode:'new_product_line',status:'developing',productSpecificCode:'TG'},costBreakdown:[],testItems:[]};
    products.getProductDetail.mockResolvedValue(detail);await start('/products/42/edit?source=research&stage=1');
    expect(wrapper!.get('input[aria-label="两位产品特定编码"]').element).toHaveProperty('readOnly',true);expect(wrapper!.findAll('button').some(b=>b.text()==='保存产品特定编码')).toBe(false);
    wrapper!.unmount();wrapper=undefined;products.getProductDetail.mockResolvedValue({...detail,basicInfo:{...detail.basicInfo,productSpecificCode:null}});await start('/products/42/edit?source=research&stage=1');
    await wrapper!.get('input[aria-label="两位产品特定编码"]').setValue('T1');await wrapper!.findAll('button').find(b=>b.text()==='保存产品特定编码')!.trigger('click');await flushPromises();expect(products.updateProduct).not.toHaveBeenCalled();
  })
  it('keeps the selected phone code and links the confirmed archive without creating another mold',async()=>{
    await start();await wrapper!.findAll('button').find(b=>b.text()==='新型号线')!.trigger('click');await flushPromises()
    wrapper!.findAllComponents(ElSelect).find(s=>s.find('option[value="50"]').exists())!.vm.$emit('update:modelValue',50);await flushPromises()
    wrapper!.findComponent({name:'SharedCompatiblePhoneSelector'}).vm.$emit('selected',[phone]);await flushPromises()
    wrapper!.findAllComponents(ElSelect).find(s=>s.find('option[value="67"]').exists())!.vm.$emit('update:modelValue',67);await flushPromises()
    const model=wrapper!.findAllComponents(ElFormItem).find(item=>item.props('label')==='适配机型')!
    expect(model.get('input').element).toHaveProperty('value','Apple iPhone 18 Pro');expect(wrapper!.text()).toContain('原刻码 1144')
    await wrapper!.findAll('button').find(b=>b.text().includes('保存'))!.trigger('click');await flushPromises()
    expect(products.createProduct).toHaveBeenCalledWith(expect.objectContaining({phoneModelId:72,phoneModelCode:'1217',model:'Apple iPhone 18 Pro',parentProductId:50,moldAction:'none'}))
    expect(archives.linkCollaborationProjects).toHaveBeenCalledWith(45,[88],true,undefined,true)
    expect(useCollaborationWorkspace().revision).toBe(2)
    expect(wrapper!.find('option[value="47"]').exists()).toBe(false)
  })
  it('leaves existing project identity untouched and does not expose the new-member picker',async()=>{
    const detail={basicInfo:{phoneModelId:72 as number|null,status:'developing',productCode:'OLD',productName:'原项目',productFlowMode:'new_model_variant',productType:'model_variant',seriesName:'骑士',model:'Old phone name snapshot',phoneModelCode:'1217',color:'',material:'PC+TPU',versionNo:'A',estimatedCost:0,parentProductName:'骑士'},costBreakdown:[],testItems:[]};products.getProductDetail.mockResolvedValue(detail);await start('/products/7/edit');
    expect(wrapper!.find('[data-test="phone-member-picker"]').exists()).toBe(false);const model=wrapper!.findAllComponents(ElFormItem).find(item=>item.props('label')==='适配机型')!.get('input');expect(model.element).toHaveProperty('readOnly',true);expect(model.element).toHaveProperty('value','Old phone name snapshot');
    expect(reference.getPhoneCompatibleMolds).not.toHaveBeenCalled();expect(products.updateProduct).not.toHaveBeenCalled();expect(archives.linkCollaborationProjects).not.toHaveBeenCalled();
    wrapper!.unmount();wrapper=undefined;detail.basicInfo.phoneModelId=null;products.getProductDetail.mockResolvedValue(detail);await start('/products/7/edit');expect(wrapper!.findAllComponents(ElFormItem).find(item=>item.props('label')==='适配机型')!.get('input').element).toHaveProperty('readOnly',false);
  })
  it('invalidates related views when a project was saved even if linking must be retried',async()=>{
    await start();await wrapper!.findAll('button').find(b=>b.text()==='新型号线')!.trigger('click');await flushPromises()
    wrapper!.findAllComponents(ElSelect).find(s=>s.find('option[value="50"]').exists())!.vm.$emit('update:modelValue',50);await flushPromises()
    wrapper!.findComponent({name:'SharedCompatiblePhoneSelector'}).vm.$emit('selected',[phone]);await flushPromises()
    wrapper!.findAllComponents(ElSelect).find(s=>s.find('option[value="67"]').exists())!.vm.$emit('update:modelValue',67);await flushPromises()
    archives.linkCollaborationProjects.mockRejectedValueOnce(new Error('重试关联'))
    const save=()=>wrapper!.findAll('button').find(b=>b.text().includes('保存'))!.trigger('click')
    await save();await flushPromises();expect(useCollaborationWorkspace().revision).toBe(1)
    await save();await flushPromises();expect(products.createProduct).toHaveBeenCalledTimes(1);expect(archives.linkCollaborationProjects).toHaveBeenCalledTimes(2);expect(useCollaborationWorkspace().revision).toBe(2)
  })
  it('refreshes on-sale choices without losing the selected phone or submitting a retired product',async()=>{
    await start();await wrapper!.findAll('button').find(b=>b.text()==='新型号线')!.trigger('click');await flushPromises()
    wrapper!.findAllComponents(ElSelect).find(s=>s.find('option[value="50"]').exists())!.vm.$emit('update:modelValue',50);await flushPromises()
    wrapper!.findComponent({name:'SharedCompatiblePhoneSelector'}).vm.$emit('selected',[phone]);await flushPromises()
    metadata.getSalesCatalog.mockResolvedValue([{id:50,salesStatus:'off_sale'},{id:47,salesStatus:'off_sale'}]);useCollaborationWorkspace().changed();await flushPromises()
    expect(wrapper!.get('option[value="50"]').attributes('disabled')).toBeDefined()
    expect(wrapper!.findAllComponents(ElFormItem).find(item=>item.props('label')==='适配机型')!.get('input').element).toHaveProperty('value','Apple iPhone 18 Pro')
    await wrapper!.findAll('button').find(b=>b.text().includes('保存'))!.trigger('click');await flushPromises();expect(products.createProduct).not.toHaveBeenCalled()
  })

})
