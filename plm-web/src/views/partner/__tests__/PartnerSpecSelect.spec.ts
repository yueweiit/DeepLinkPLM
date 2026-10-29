import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus, { ElButton, ElCheckbox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useUserStore } from '@/stores/user'
import PartnerSpecSelect from '../components/PartnerSpecSelect.vue'
import type { BusinessData } from '@/types/partner'
const api=vi.hoisted(()=>({operationSpecOptions:vi.fn(),operationSpecPhones:vi.fn()}))
vi.mock('@/api/modules/partnerOperations',()=>api)
const brands=[{brandId:1,name:'Apple'},{brandId:2,name:'Samsung'}]
const phone=(brandId:number)=>({phoneModelId:brandId*100,brandId,brandName:brandId===1?'Apple':'Samsung',modelName:brandId===1?'iPhone 17 Pro Max':'Galaxy S26',phoneModelCode:brandId===1?'0185':'1211'})
const row=(id:number,brandId=1)=>({id,code:`PS-${id}`,name:`规格 ${id}`,productId:7,productName:'透明保护壳',phoneModels:[phone(brandId)],details:{phoneModelIds:[brandId*100],sources:[{supplierCode:'SUP-A',currency:'CNY',priceCents:800}]}})
let wrapper:VueWrapper|undefined,catalog:BusinessData[]
beforeEach(()=>{vi.clearAllMocks();setActivePinia(createPinia());useUserStore().token='spec-test';catalog=[row(5001),row(5002,2)]
 api.operationSpecOptions.mockImplementation(async q=>{const values=catalog.filter(r=>(!q.ids||q.ids.split(',').map(Number).includes(r.id))&&(!q.brandId||r.phoneModels[0].brandId===q.brandId)&&(!q.phoneModelId||r.phoneModels[0].phoneModelId===q.phoneModelId));return {content:values.slice((q.page-1)*q.size,q.page*q.size),totalElements:values.length,brands,productLines:[]}})
 api.operationSpecPhones.mockImplementation(async q=>{const phones=[...new Map(catalog.map(r=>[r.phoneModels[0].phoneModelId,{...r.phoneModels[0],referenceCount:1}])).values()].filter(p=>!q.brandId||p.brandId===q.brandId);return {content:phones,totalElements:phones.length,unlinkedCount:0,brands,productLines:[]}})
})
afterEach(()=>{wrapper?.unmount();wrapper=undefined;document.body.innerHTML=''})
async function start(props:Record<string,unknown>={}){wrapper=mount(PartnerSpecSelect,{props:{modelValue:[],...props},attachTo:document.body,global:{plugins:[ElementPlus],stubs:{Teleport:true}}});await flushPromises()}
async function open(){await wrapper!.get('.reference-trigger').trigger('click');await flushPromises()}
async function choosePhone(id:number){await wrapper!.get(`[data-phone-id="${id}"]`).trigger('click');await flushPromises()}
async function button(text:string){const b=wrapper!.findAllComponents(ElButton).find(b=>b.text()===text)!;expect(b,text).toBeTruthy();await b.trigger('click');await flushPromises()}
async function toggle(code:string){const b=wrapper!.findAllComponents(ElCheckbox).find(b=>b.text().includes(code))!;expect(b).toBeTruthy();b.vm.$emit('change',true);await flushPromises()}
describe('commercial specification phone browser',()=>{
 it('loads lazily through its own permission endpoint and saves spec IDs rather than the browsing phone ID',async()=>{
  await start({supplierCode:'SUP-A',productId:7,activeOnly:true,multiple:false});expect(api.operationSpecOptions).not.toHaveBeenCalled();expect(api.operationSpecPhones).not.toHaveBeenCalled();await open()
  expect(api.operationSpecPhones).toHaveBeenLastCalledWith(expect.objectContaining({supplierCode:'SUP-A',productId:7,activeOnly:true,page:1,size:50}));await choosePhone(100);await toggle('PS-5001');expect(wrapper!.emitted('update:modelValue')).toBeUndefined()
  await button('完成选择（1）');expect(wrapper!.emitted('update:modelValue')?.at(-1)).toEqual([[5001]]);expect(wrapper!.emitted('selected')?.at(-1)).toEqual([[catalog[0]]])
 })
 it('preserves cross-brand temporary selections and cancel restores the original saved spec',async()=>{
  await start({modelValue:[5001]});expect(wrapper!.get('.reference-trigger').text()).toContain('规格 5001');await open();await wrapper!.get('[data-brand-id="2"]').trigger('click');await flushPromises();await choosePhone(200);await toggle('PS-5002');await button('取消');expect(wrapper!.emitted('update:modelValue')).toBeUndefined()
  await open();expect(wrapper!.get('.selection-heading').text()).toContain('1 项');await wrapper!.get('[data-brand-id="2"]').trigger('click');await flushPromises();await choosePhone(200);await toggle('PS-5002');await button('完成选择（2）');expect(wrapper!.emitted('update:modelValue')?.at(-1)).toEqual([[5001,5002]])
 })
 it('resolves an out-of-page existing spec and supplies its original phone and supplier metadata without changing the form',async()=>{
  await start({modelValue:[5002],supplierCode:'SUP-A'});expect(api.operationSpecOptions).toHaveBeenCalledWith(expect.objectContaining({ids:'5002',supplierCode:'SUP-A',size:100}));expect(wrapper!.emitted('resolved')?.at(-1)).toEqual([[catalog[1]]]);expect(wrapper!.emitted('selected')).toBeUndefined();expect(wrapper!.emitted('update:modelValue')).toBeUndefined()
 })
 it('closes on supplier changes, discards stale requests and only shows the new supplier scope',async()=>{
  let finish!:(value:unknown)=>void;api.operationSpecPhones.mockImplementationOnce(()=>new Promise(resolve=>finish=resolve));await start({supplierCode:'SUP-A'});await open();await wrapper!.setProps({supplierCode:'SUP-B'});finish({content:[{...phone(1),referenceCount:99}],totalElements:1,unlinkedCount:0,brands,productLines:[]});await flushPromises();expect(wrapper!.find('.reference-panel').exists()).toBe(false)
  await open();expect(api.operationSpecPhones).toHaveBeenLastCalledWith(expect.objectContaining({supplierCode:'SUP-B'}));expect(wrapper!.text()).not.toContain('99 条资料');expect(wrapper!.emitted('update:modelValue')).toBeUndefined()
 })
 it('keeps old confirmed values when reading fails and supports an explicit retry',async()=>{
  api.operationSpecOptions.mockRejectedValueOnce(Error('暂时不可用'));await start({modelValue:[5001]});expect(wrapper!.text()).toContain('暂时不可用');await button('重试');expect(wrapper!.get('.reference-trigger').text()).toContain('规格 5001');expect(wrapper!.emitted('update:modelValue')).toBeUndefined()
 })
})
