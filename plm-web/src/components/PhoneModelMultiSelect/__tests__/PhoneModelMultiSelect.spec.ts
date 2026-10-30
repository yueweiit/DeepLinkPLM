import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import ElementPlus,{ElButton,ElCheckbox,ElPagination} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import PhoneModelMultiSelect from '../index.vue'
import {phoneSeries,selectionSearchTerm,matchesPhone} from '../selection'
import {useUserStore} from '@/stores/user'
import type {CompatiblePhone,ReferenceQuery} from '@/types/molds'
const api=vi.hoisted(()=>({getPhoneModels:vi.fn(),getPhoneModelOptions:vi.fn(),getPhoneSelectionBrands:vi.fn()}))
vi.mock('@/api/modules/phoneReference',()=>api)
const phone=(id:number,brandId:number,modelName:string,code:string):CompatiblePhone=>({id,phoneModelId:id,brandId,brandName:brandId===1?'Apple':'Samsung',modelName,phoneModelCode:code,aliases:[],codeItemId:id+5000,codeLinkStatus:'linked',status:'enabled'})
const a=phone(101,1,'iPhone 17 Pro Max','1218'),b=phone(102,1,'iPhone 17 Pro','1217'),c=phone(103,1,'iPhone 16 Pro Max','1064'),s=phone(201,2,'Galaxy S26 Ultra','1212')
let catalog:CompatiblePhone[],w:VueWrapper|undefined
beforeEach(()=>{vi.clearAllMocks();setActivePinia(createPinia());useUserStore().token='picker-test';catalog=[a,b,c,s];api.getPhoneSelectionBrands.mockResolvedValue([{id:1,brandId:1,name:'Apple',status:'enabled'},{id:2,brandId:2,name:'Samsung',status:'enabled'}]);api.getPhoneModelOptions.mockImplementation(async(ids:number[])=>catalog.filter(p=>ids.includes(p.phoneModelId)));api.getPhoneModels.mockImplementation(async(q:ReferenceQuery)=>{let rows=catalog.filter(p=>!q.brandId||p.brandId===q.brandId);if(q.keyword==='17 Pro Max')rows=[a];return {content:rows.slice((q.page-1)*q.size,q.page*q.size),totalElements:rows.length}})})
afterEach(()=>{w?.unmount();w=undefined;document.body.innerHTML='';vi.restoreAllMocks()})
async function start(props:Record<string,unknown>={}){w=mount(PhoneModelMultiSelect,{props:{modelValue:[],...props},attachTo:document.body,global:{plugins:[ElementPlus],stubs:{Teleport:true}}});await flushPromises();return w}
async function open(){await w!.get('.phone-model-trigger').trigger('click');await flushPromises()}
async function button(name:string){const found=w!.findAllComponents(ElButton).find(v=>v.text()===name);expect(found,`button ${name}`).toBeTruthy();await found!.trigger('click');await flushPromises()}
async function brand(id:number){await w!.get(`button[data-brand-id="${id}"]`).trigger('click');await flushPromises()}
async function toggle(code:string){const box=w!.findAllComponents(ElCheckbox).find(v=>v.text().includes(code));expect(box,`phone ${code}`).toBeTruthy();box!.vm.$emit('change',!box!.props('modelValue'));await flushPromises()}
const chosen=()=>w!.get('.selected-heading').text()
const confirm=()=>button(w!.findAllComponents(ElButton).find(v=>v.text().startsWith('完成选择'))!.text())

describe('PhoneModelMultiSelect',()=>{
  it('queries only pending identities when assigning a code without broadening normal business pickers',async()=>{
    await start({unassignedCode:true,brandId:1,multiple:false});await open()
    expect(api.getPhoneModels).toHaveBeenLastCalledWith({page:1,size:50,keyword:'',selectionSearch:true,brandId:1,unassignedCode:true})
    await w!.setProps({unassignedCode:false});await button('取消');await open()
    expect(api.getPhoneModels).toHaveBeenLastCalledWith({page:1,size:50,keyword:'',selectionSearch:true,brandId:1})
  })
  it('starts as one compact trigger, hydrates only the preview and collapses additional saved IDs',async()=>{
    await start({modelValue:[a.phoneModelId,b.phoneModelId,c.phoneModelId]})
    expect(w!.find('.phone-model-panel').exists()).toBe(false);expect(w!.findAll('.phone-model-trigger .el-tag')).toHaveLength(2)
    expect(w!.get('.phone-model-trigger').text()).toContain('+1 个');expect(w!.text()).toContain('1218')
    expect(api.getPhoneModelOptions).toHaveBeenCalledExactlyOnceWith([101,102]);expect(api.getPhoneModels).not.toHaveBeenCalled()
  })
  it('keeps temporary choices separate, cancels them and confirms only real unique IDs',async()=>{
    await start({modelValue:[103]});await open();await toggle('1218');await toggle('1218');await toggle('1218')
    expect(chosen()).toContain('2 个');expect(w!.emitted('update:modelValue')).toBeUndefined()
    await button('取消');expect(w!.emitted('update:modelValue')).toBeUndefined();await open();expect(chosen()).toContain('1 个')
    await toggle('1218');await confirm();expect(w!.emitted('update:modelValue')?.at(-1)).toEqual([[101,103]])
    expect(w!.emitted('selected')?.at(-1)).toEqual([[a,c]])
    await w!.setProps({modelValue:[101,103]});await open()
    expect(w!.findAllComponents(ElCheckbox).find(v=>v.text().includes('1218'))?.props('modelValue')).toBe(true)
  })
  it('retains previous-brand selections while continuously checking models in another brand',async()=>{
    await start();await open();await brand(1);await toggle('1218');await brand(2);await toggle('1212')
    expect(chosen()).toContain('2 个');expect(w!.get('.selected-tags').text()).toContain('iPhone 17');expect(w!.get('.selected-tags').text()).toContain('S26')
    expect(w!.get('button[data-brand-id="1"]').text()).toContain('1');expect(w!.get('button[data-brand-id="2"]').text()).toContain('1')
    expect(w!.get('.phone-model-trigger').attributes('aria-expanded')).toBe('true');await confirm()
    expect(w!.emitted('update:modelValue')?.at(-1)).toEqual([[101,201]])
  })
  it('debounces cross-brand shorthand search without modifying a selected phone',async()=>{
    await start({modelValue:[201]});await open();api.getPhoneModels.mockClear()
    await w!.get('input[aria-label="搜索手机品牌及型号"]').setValue('17P');await w!.get('input[aria-label="搜索手机品牌及型号"]').setValue('17PM')
    expect(api.getPhoneModels).not.toHaveBeenCalled();await vi.waitFor(()=>expect(api.getPhoneModels).toHaveBeenCalledTimes(1),{timeout:1000});await flushPromises()
    expect(api.getPhoneModels).toHaveBeenLastCalledWith({page:1,size:50,keyword:'17 Pro Max',selectionSearch:true})
    expect(w!.findAll('.model-checkbox')).toHaveLength(1);expect(chosen()).toContain('1 个');expect(w!.emitted('update:modelValue')).toBeUndefined()
  })
  it('selects and cancels a whole series while preserving models outside that series',async()=>{
    await start({modelValue:[201]});await open()
    const group=()=>w!.findAll('.model-series').find(v=>v.attributes('aria-label')==='Apple iPhone 17 系列')!
    await group().get('button').trigger('click');await flushPromises();expect(chosen()).toContain('3 个')
    expect(group().get('button').text()).toContain('取消本组');await group().get('button').trigger('click');await flushPromises()
    expect(chosen()).toContain('1 个');await confirm();expect(w!.emitted('update:modelValue')?.at(-1)).toEqual([[201]])
  })
  it('prevents an unfiltered database-wide select-all and limits an explicit brand select-all',async()=>{
    await start({modelValue:[201]});await open();const all=()=>w!.findAllComponents(ElCheckbox).find(v=>v.text()==='全选当前筛选结果')!
    expect(all().props('disabled')).toBe(true);all().vm.$emit('change',true);await flushPromises();expect(chosen()).toContain('1 个')
    await brand(1);expect(all().props('disabled')).toBe(false);all().vm.$emit('change',true);await flushPromises();expect(chosen()).toContain('4 个')
    all().vm.$emit('change',false);await flushPromises();expect(chosen()).toContain('1 个')
  })
  it('loads an entire filtered series across pages but renders at most 50 results and 20 selected tags',async()=>{
    catalog=Array.from({length:151},(_,i)=>phone(1000+i,1,`iPhone 17 Pro Max version ${i}`,String(1000+i)))
    await start();await open();expect(w!.findAll('.model-checkbox')).toHaveLength(50)
    await w!.get('.model-series .series-heading button').trigger('click');await flushPromises()
    expect(chosen()).toContain('151 个');expect(w!.findAll('.model-checkbox')).toHaveLength(50);expect(w!.findAll('.selected-tags .el-tag')).toHaveLength(20)
    expect(api.getPhoneModels).toHaveBeenCalledWith({page:2,size:100,keyword:'',selectionSearch:true,brandId:1})
    await button('下一页已选');expect(w!.get('.selected-tags').text()).toContain('1020')
    w!.getComponent(ElPagination).vm.$emit('current-change',4);await flushPromises();expect(w!.findAll('.model-checkbox')).toHaveLength(1)
    await confirm();expect((w!.emitted('update:modelValue')?.at(-1)?.[0] as number[])).toHaveLength(151)
  })
  it('does not partially select when a later batch fails and can retry without duplicate accumulation',async()=>{
    catalog=Array.from({length:120},(_,i)=>phone(1000+i,1,`iPhone 17 Pro Max ${i}`,String(1000+i)))
    api.getPhoneModels.mockImplementation(async(q:ReferenceQuery)=>{if(q.page===2&&q.size===100)throw new Error('第二页失败');return {content:catalog.slice((q.page-1)*q.size,q.page*q.size),totalElements:120}})
    await start({modelValue:[201],members:[s]});await open();await brand(1)
    w!.findAllComponents(ElCheckbox).find(v=>v.text()==='全选当前筛选结果')!.vm.$emit('change',true);await flushPromises()
    expect(chosen()).toContain('1 个');expect(w!.text()).toContain('第二页失败');expect(w!.emitted('update:modelValue')).toBeUndefined()
    api.getPhoneModels.mockImplementation(async(q:ReferenceQuery)=>({content:catalog.slice((q.page-1)*q.size,q.page*q.size),totalElements:120}))
    w!.findAllComponents(ElCheckbox).find(v=>v.text()==='全选当前筛选结果')!.vm.$emit('change',true);await flushPromises();expect(chosen()).toContain('121 个')
  })
  it('rejects a changed result count during bulk loading instead of silently selecting the wrong scope',async()=>{
    catalog=Array.from({length:120},(_,i)=>phone(1000+i,1,`Model ${i}`,String(1000+i)))
    api.getPhoneModels.mockImplementation(async(q:ReferenceQuery)=>({content:catalog.slice((q.page-1)*q.size,q.page*q.size),totalElements:q.page===2?121:120}))
    await start();await open();await brand(1);w!.findAllComponents(ElCheckbox).find(v=>v.text()==='全选当前筛选结果')!.vm.$emit('change',true);await flushPromises()
    expect(chosen()).toContain('0 个');expect(w!.text()).toContain('结果在读取中发生变化')
  })
  it('ignores stale brand responses and keeps the current search scope',async()=>{
    let finish!:(v:unknown)=>void
    api.getPhoneModels.mockImplementation((q:ReferenceQuery)=>q.brandId===1?new Promise(resolve=>finish=resolve):Promise.resolve({content:[s],totalElements:1}))
    await start();await open();await brand(1);expect(w!.text()).toContain('正在读取型号');await brand(2)
    finish({content:[a],totalElements:1});await flushPromises()
    expect(w!.get('.model-scroll').text()).toContain('S26');expect(w!.get('.model-scroll').text()).not.toContain('iPhone')
  })
  it('keeps explicit allowed IDs and fixed-brand single selection inside the business scope',async()=>{
    await start({allowedIds:[101,201],members:catalog});await open();expect(w!.findAll('.model-checkbox')).toHaveLength(2)
    w!.findAllComponents(ElCheckbox).find(v=>v.text()==='全选当前筛选结果')!.vm.$emit('change',true);await flushPromises();await confirm()
    expect(w!.emitted('update:modelValue')?.at(-1)).toEqual([[101,201]]);expect(api.getPhoneModels).not.toHaveBeenCalled()
    await w!.setProps({modelValue:[201],multiple:false,brandId:1});await open();expect(w!.find('button[data-brand-id="2"]').exists()).toBe(false)
    await toggle('1218');expect(chosen()).toContain('1 个');await confirm();expect(w!.emitted('update:modelValue')?.at(-1)).toEqual([[101]])
  })
  it('searches maintained brand and model aliases inside a restricted project without broadening its IDs',async()=>{
    catalog=[{...a,brandAliases:['苹果'],aliases:['IP17PM'],searchTexts:['Apple','iPhone 17 Pro Max','苹果','IP17PM']},s];await start({allowedIds:[101],members:catalog});await open()
    await w!.get('input[aria-label="搜索手机品牌及型号"]').setValue('苹果 IP17PM');await vi.waitFor(()=>expect(w!.get('.model-scroll').text()).toContain('iPhone 17 Pro Max'),{timeout:1000});await flushPromises()
    expect(w!.findAll('.model-checkbox')).toHaveLength(1);expect(api.getPhoneModels).not.toHaveBeenCalled()
  })
  it('retains unavailable saved IDs but does not add unavailable phones or disable production-stopped reminders',async()=>{
    catalog=[{...a,status:'disabled',phoneModelCode:'0001'},{...b,codeLinkStatus:'missing'},{...c,productionStatus:'discontinued'}]
    await start({modelValue:[101]});await open();expect(w!.get('.selected-tags').text()).toContain('0001')
    expect(w!.findAllComponents(ElCheckbox).find(v=>v.text().includes('1217'))?.props('disabled')).toBe(true)
    expect(w!.findAllComponents(ElCheckbox).find(v=>v.text().includes('1064'))?.props('disabled')).toBe(false)
    await toggle('1064');await confirm();expect(w!.emitted('update:modelValue')?.at(-1)).toEqual([[101,103]])
  })
  it('shows empty and failed queries separately, retries and preserves saved IDs',async()=>{
    api.getPhoneModels.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({content:[],totalElements:0})
    await start({modelValue:[101]});await open();expect(w!.text()).toContain('型号读取失败');expect(chosen()).toContain('1 个')
    await button('重试型号');expect(w!.text()).toContain('暂无可选型号');expect(chosen()).toContain('1 个')
  })
  it('clears temporary choices only and restores confirmed choices on cancel',async()=>{
    await start({modelValue:[101,201]});await open();await button('清空全部');expect(chosen()).toContain('0 个');await button('取消');await open();expect(chosen()).toContain('2 个')
    await button('清空全部');await confirm();expect(w!.emitted('update:modelValue')?.at(-1)).toEqual([[]])
  })
  it('opens from the keyboard, focuses search and returns focus to the trigger on Escape',async()=>{
    await start();await w!.get('.phone-model-trigger').trigger('keydown',{key:'ArrowDown'});await flushPromises()
    expect(w!.get('.phone-model-trigger').attributes('aria-expanded')).toBe('true')
    expect((document.activeElement as HTMLElement).getAttribute('aria-label')).toBe('搜索手机品牌及型号')
    await w!.get('.phone-model-panel').trigger('keydown',{key:'Escape'});await flushPromises();expect(w!.emitted('update:modelValue')).toBeUndefined()
    expect(document.activeElement).toBe(w!.get('.phone-model-trigger').element)
  })
  it('blocks external selection conflicts and readonly or logged-out confirmation',async()=>{
    await start();await open();await toggle('1218');await w!.setProps({modelValue:[201]});await flushPromises()
    expect(w!.text()).toContain('外部选择已变化');await confirm();expect(w!.emitted('update:modelValue')).toBeUndefined()
    await button('取消');await w!.setProps({disabled:true});await open();expect(w!.get('.phone-model-trigger').attributes('disabled')).toBeDefined()
    await w!.setProps({disabled:false});await open();useUserStore().token='';await flushPromises()
    expect(w!.find('.phone-model-panel').exists()).toBe(false);expect(w!.get('.phone-model-trigger').text()).not.toContain('iPhone')
  })
  it('preserves missing saved IDs and requires resolving or explicitly clearing them before confirmation',async()=>{
    await start({modelValue:[999]});await open();expect(w!.text()).toContain('部分型号资料缺失');expect(w!.get('.selected-tags').text()).toContain('型号 #999')
    expect(w!.findAllComponents(ElButton).find(v=>v.text()==='完成选择（1）')!.props('disabled')).toBe(true)
    await button('清空全部');await confirm();expect(w!.emitted('update:modelValue')?.at(-1)).toEqual([[]])
  })
  it('discards an old-login query before reopening the picker under a different login',async()=>{
    let finish!:(v:unknown)=>void
    api.getPhoneModels.mockReturnValueOnce(new Promise(resolve=>finish=resolve)).mockResolvedValue({content:[s],totalElements:1})
    await start();await open();useUserStore().token='';await flushPromises();useUserStore().token='new-login';await flushPromises();await open()
    finish({content:[a],totalElements:1});await flushPromises();expect(w!.get('.model-scroll').text()).toContain('S26');expect(w!.get('.model-scroll').text()).not.toContain('iPhone')
  })
  it('limits very large bulk scopes while keeping paginated browsing and existing choices intact',async()=>{
    api.getPhoneModels.mockResolvedValue({content:Array.from({length:50},(_,i)=>phone(1000+i,1,`Model ${i}`,String(1000+i))),totalElements:10001})
    await start({modelValue:[201],members:[s]});await open();await brand(1)
    w!.findAllComponents(ElCheckbox).find(v=>v.text()==='全选当前筛选结果')!.vm.$emit('change',true);await flushPromises()
    expect(w!.text()).toContain('超过 10000 个');expect(chosen()).toContain('1 个');expect(w!.findAll('.model-checkbox')).toHaveLength(50)
  })
  it('discards an in-flight bulk selection when the panel is canceled',async()=>{
    catalog=Array.from({length:120},(_,i)=>phone(1000+i,1,`Model ${i}`,String(1000+i)));let finish!:(v:unknown)=>void
    api.getPhoneModels.mockImplementation((q:ReferenceQuery)=>q.size===100?new Promise(resolve=>finish=resolve):Promise.resolve({content:catalog.slice(0,50),totalElements:120}))
    await start();await open();await brand(1);w!.findAllComponents(ElCheckbox).find(v=>v.text()==='全选当前筛选结果')!.vm.$emit('change',true);await flushPromises()
    await button('取消');finish({content:catalog.slice(0,100),totalElements:120});await flushPromises();expect(w!.emitted('update:modelValue')).toBeUndefined()
  })
})
describe('selection display and shorthand',()=>{
  it('expands search shorthand without changing phone data and preserves explicit series fields',()=>{expect(selectionSearchTerm('IP17PM')).toBe('iPhone 17 Pro Max');expect(selectionSearchTerm('17PM')).toBe('17 Pro Max');expect(selectionSearchTerm('Samsung S26')).toBe('Samsung S26');expect(selectionSearchTerm('0001')).toBe('0001');expect(phoneSeries(a).name).toBe('iPhone 17 系列');expect(phoneSeries(phone(88,2,'8 Pro','0088')).name).toBe('8 系列');expect(phoneSeries({...a,seriesId:88,seriesName:'正式维护系列'})).toEqual({key:'1:series:88',name:'正式维护系列'})})
  it('distinguishes model generations from code substrings and uses the backend series search projection',()=>{
    expect(matchesPhone({...a,modelName:'iPhone 18 Pro',phoneModelCode:'1217'},'iPhone 17')).toBe(false)
    expect(matchesPhone({...a,modelName:'iPad Pro 12.9 (2017)',brandAliases:['iPhone'],searchTexts:['Apple','iPad Pro 12.9 (2017)']},'iPhone 17')).toBe(false)
    expect(matchesPhone({...a,modelName:'IP17 Air',searchTexts:['Apple','IP17 Air','iPhone']},'iPhone 17')).toBe(true)
    expect(matchesPhone({...s,modelName:'SM S266'},'Samsung S26')).toBe(false)
    expect(matchesPhone({...a,modelName:'iPhone 13/14/15'},'iPhone 14')).toBe(true)
    expect(matchesPhone({...a,modelName:'iPad Pro 12.9 (2017)'},'iPad 12.9')).toBe(true)
    expect(matchesPhone({...a,modelName:'iPhone 18 Pro',phoneModelCode:'1217'},'1217')).toBe(true)
  })
  it('keeps combined models, hole descriptions, network versions and leading zeros intact',()=>{const original=phone(3,1,'iPhone 17 Pro / 18 Pro 4G、5G Gran agujero','0001'),snapshot=JSON.stringify(original);expect(phoneSeries(original).name).toContain('组合型号');expect(JSON.stringify(original)).toBe(snapshot)})
})
