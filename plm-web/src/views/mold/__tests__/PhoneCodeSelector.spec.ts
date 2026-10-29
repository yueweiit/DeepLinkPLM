import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus, { ElSelect, ElOption, ElButton } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const api=vi.hoisted(()=>({brands:vi.fn(),codes:vi.fn()}))
vi.mock('@/api/modules/phoneReference',()=>({getPhoneSelectionBrands:api.brands}))
vi.mock('@/api/modules/code',()=>({getCodeItems:api.codes}))
import PhoneCodeSelector from '../components/PhoneCodeSelector.vue'
import { useUserStore } from '@/stores/user'
let wrapper:VueWrapper|undefined
const first={codeItemId:1,codeValue:'0001',codeName:'IP 6 / 6S',status:'enabled',codeType:'phone_model',sortOrder:0}
const latest={...first,codeItemId:2,codeValue:'1297',codeName:'XM POCO X8 Pro'}
beforeEach(()=>{vi.resetAllMocks();setActivePinia(createPinia());useUserStore().token='test-code-picker';api.brands.mockResolvedValue([{id:1,brandId:1,name:'Apple',status:'enabled'},{id:2,brandId:2,name:'Xiaomi',status:'enabled'}]);api.codes.mockResolvedValue({content:[latest,first],totalElements:2})})
afterEach(()=>{wrapper?.unmount();wrapper=undefined;document.body.innerHTML=''})
async function start(props:Record<string,unknown>={}){wrapper=mount(PhoneCodeSelector,{props:{modelValue:null,...props},global:{plugins:[ElementPlus]}});await flushPromises();return wrapper}
const brand=()=>wrapper!.getComponent<typeof ElSelect>('.code-brand')
const model=()=>wrapper!.getComponent<typeof ElSelect>('.code-model')
describe('brand-first historical phone code selection',()=>{
  it('filters existing code groups by brand before showing choices and emits only the original code item ID',async()=>{
    await start();expect(model().props('disabled')).toBe(true);expect(api.codes).not.toHaveBeenCalled()
    brand().vm.$emit('update:modelValue',1);await flushPromises()
    expect(api.codes).toHaveBeenCalledWith({codeType:'phone_model',status:'enabled',page:1,size:100,keyword:'',brandId:1})
    const options=model().findAllComponents(ElOption);expect(options.map(o=>o.props('label'))).toEqual(['1297 · XM POCO X8 Pro','0001 · IP 6 / 6S'])
    expect(wrapper!.emitted('update:modelValue')).toBeUndefined()
    model().vm.$emit('update:modelValue',1);await flushPromises();expect(wrapper!.emitted('update:modelValue')).toEqual([[1]])
  })
  it('retains the original combined model and leading zeros when switching brands, including disabled history',async()=>{
    await start({modelValue:1,members:[{...first,status:'disabled'}]})
    expect(model().findAllComponents(ElOption)[0].props('label')).toContain('0001 · IP 6 / 6S')
    brand().vm.$emit('update:modelValue',2);await flushPromises();expect(model().props('modelValue')).toBe(1)
    expect(wrapper!.emitted('update:modelValue')).toBeUndefined()
    model().vm.$emit('update:modelValue',undefined);await flushPromises();expect(wrapper!.emitted('update:modelValue')).toEqual([[null]])
  })
  it('offers an explicit unclassified history scope with pagination and keeps a failed page retryable',async()=>{
    api.codes.mockResolvedValueOnce({content:[latest],totalElements:2}).mockRejectedValueOnce(new Error('timeout')).mockResolvedValueOnce({content:[first],totalElements:2})
    await start();brand().vm.$emit('update:modelValue',0);await flushPromises()
    expect(api.codes).toHaveBeenLastCalledWith(expect.objectContaining({unclassified:true}));expect(api.codes.mock.calls[0][0]).not.toHaveProperty('brandId')
    await wrapper!.findAllComponents(ElButton).find(b=>b.text().includes('加载更多'))!.trigger('click');await flushPromises()
    expect(wrapper!.get('[role="alert"]').text()).toContain('读取失败')
    await wrapper!.findAllComponents(ElButton).find(b=>b.text()==='重试读取编码')!.trigger('click');await flushPromises()
    expect(api.codes).toHaveBeenLastCalledWith(expect.objectContaining({page:2,unclassified:true}))
    expect(model().findAllComponents(ElOption).map(o=>o.props('value'))).toEqual([2,1])
    expect(wrapper!.emitted('update:modelValue')).toBeUndefined()
  })
  it('uses the already selected maintenance brand and inherits a disabled parent form',async()=>{
    wrapper=mount({components:{PhoneCodeSelector},template:'<el-form disabled><PhoneCodeSelector :model-value="1" :brand-id="1"/></el-form>'},{global:{plugins:[ElementPlus]}});await flushPromises()
    expect(wrapper.find('.code-brand').exists()).toBe(false);expect(model().props('disabled')).toBe(true)
    expect(api.brands).not.toHaveBeenCalled();expect(api.codes).toHaveBeenCalledWith(expect.objectContaining({brandId:1}))
    model().vm.$emit('update:modelValue',2);await flushPromises();expect(wrapper.getComponent(PhoneCodeSelector).emitted('update:modelValue')).toBeUndefined()
  })
  it('does not accept a delayed previous-brand response and hides all reference labels on logout',async()=>{
    let finish!:(value:unknown)=>void
    api.codes.mockReturnValueOnce(new Promise(resolve=>finish=resolve)).mockResolvedValueOnce({content:[latest],totalElements:1})
    await start({modelValue:1,members:[first]});brand().vm.$emit('update:modelValue',1);await flushPromises();brand().vm.$emit('update:modelValue',2);await flushPromises()
    finish({content:[{...first,codeItemId:88}],totalElements:1});await flushPromises()
    expect(model().findAllComponents(ElOption).map(o=>o.props('value'))).toEqual([2,1])
    useUserStore().token='';await flushPromises();expect(wrapper!.find('.phone-code-selector').exists()).toBe(false)
  })
})
