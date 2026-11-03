import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {createMemoryHistory,createRouter} from 'vue-router'
import ElementPlus,{ElCheckbox,ElButton,ElInputNumber} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import ReceiptDialog from '../selection/SelectionReceiptDialog.vue'
import PhoneModelMultiSelect from '@/components/PhoneModelMultiSelect/index.vue'
import {setLanguage,t} from '@/locales'
import {useUserStore} from '@/stores/user'
import {newSelectionRow} from '@/utils/partnerSelections'
import type {CompatiblePhone,ReferenceQuery} from '@/types/molds'
import type {SelectionReceiptInput} from '@/types/partnerSelections'

const api=vi.hoisted(()=>({preview:vi.fn(),save:vi.fn(),brands:vi.fn(),phones:vi.fn(),options:vi.fn(),locations:vi.fn(),custodians:vi.fn(),candidates:vi.fn(),identities:vi.fn()}))
vi.mock('@/api/modules/phoneReference',()=>({getPhoneSelectionBrands:api.brands,getPhoneModels:api.phones,getPhoneModelOptions:api.options}))
vi.mock('@/api/modules/partnerSelections',()=>({previewSelectionReceipts:api.preview,saveSelectionReceipts:api.save}))
vi.mock('@/api/modules/sampleManagement',()=>({sampleLocationOptions:api.locations,sampleIdentityCandidates:api.candidates,sampleIdentities:api.identities}))
vi.mock('@/api/modules/samples',()=>({getSampleCustodians:api.custodians}))
const phone=(id:number,name:string,code:string,status='enabled'):CompatiblePhone=>({id,phoneModelId:id,brandId:1,brandName:'Apple',modelName:name,phoneModelCode:code,aliases:[],codeItemId:id+100,codeLinkStatus:'linked',status})
const catalogue=[phone(18,'iPhone 18 Pro Max','1450'),phone(17,'iPhone 17 Pro Max','1145'),phone(11,'iPhone 11','0052','disabled')]
let wrapper:VueWrapper|undefined
beforeEach(()=>{
 vi.clearAllMocks();sessionStorage.clear();setActivePinia(createPinia());useUserStore().token='legitimate-ui-fixture';useUserStore().profile={userId:71,userName:'国内工程',roleName:'工程',department:'工程'}
 api.brands.mockResolvedValue([{id:1,brandId:1,name:'Apple',status:'enabled'}]);api.phones.mockImplementation(async(query:ReferenceQuery)=>{const content=catalogue.filter(phone=>!query.brandId||phone.brandId===query.brandId);return{content,totalElements:content.length,page:query.page,size:query.size,totalPages:1}});api.options.mockImplementation(async(ids:number[])=>catalogue.filter(phone=>ids.includes(phone.phoneModelId)))
 api.locations.mockResolvedValue({content:[],hasMore:false,size:50});api.custodians.mockResolvedValue({content:[{userId:71,name:'国内工程'}]});api.candidates.mockResolvedValue({content:[],canCreate:true,requiresReview:false});api.identities.mockResolvedValue({content:[]})
 api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({id:7,version:3,canSave:input.rows.every(row=>!!row.phoneModelId&&!!row.colorName&&Number(row.qty)>0),messages:[],rows:input.rows.map(row=>({...row,name:'未注明款',catalogStyleLabel:'CP-000001',supplierCode:'SUP-XD',receiptPhoneScopeMode:'unspecified',receiptColorMode:'at_receipt',phones:[],sampleColors:['黑色'],colors:[],refs:[],sourceScopeToken:'original-not-provided-source',messages:row.phoneModelId?[]:['请选择实收型号'],canSave:!!row.phoneModelId}))}))
 api.save.mockImplementation(async(_id,input:SelectionReceiptInput)=>({id:7,version:4,batchCount:1,batches:[{clientRowId:input.rows[0].clientRowId,rowId:'row-1',id:41,code:'SP-000041',name:'未注明款',phoneModelId:input.rows[0].phoneModelId,phoneName:catalogue.find(phone=>phone.phoneModelId===input.rows[0].phoneModelId)?.modelName,colorName:input.rows[0].colorName,colorId:8,qty:input.rows[0].qty}]}))
})
afterEach(()=>{setLanguage('zh-CN');wrapper?.unmount();wrapper=undefined;document.body.innerHTML='';sessionStorage.clear()})
async function start(){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div/>'}}]});await router.push('/partner-selections?id=7');await router.isReady();const row={...newSelectionRow(),rowId:'row-1',rowVersion:2,catalogStyleId:1,name:'未注明款',supplierCode:'SUP-XD'};wrapper=mount(ReceiptDialog,{props:{id:7,version:3,rows:[row],from:'/partner-selections?id=7'},attachTo:document.body,global:{plugins:[router,ElementPlus]}});await flushPromises()}
function picker(){return wrapper!.findAllComponents(PhoneModelMultiSelect).find(picker=>picker.props('ariaLabel')==='未注明款实收型号')!}
async function button(text:string){const found=wrapper!.findAllComponents(ElButton).find(button=>button.text()===text);expect(found,`button ${text}`).toBeDefined();await found!.trigger('click');await flushPromises()}
async function choose(id:number){const check=picker().findAllComponents(ElCheckbox).find(check=>check.text().includes(catalogue.find(phone=>phone.phoneModelId===id)!.phoneModelCode!))!;await check.get('input').setValue(true);await flushPromises()}

describe('unspecified receipt reuses the real phone library picker',()=>{
 it('browses existing brands and descending model results, rejects disabled identities, cancels temporary choice and saves only a manually confirmed model',async()=>{
  await start();expect(picker().props()).toMatchObject({modelValue:[],multiple:false});expect(picker().props('allowedIds')).toBeUndefined();expect(api.phones).not.toHaveBeenCalled();expect(api.save).not.toHaveBeenCalled()
  await picker().get('.phone-model-trigger').trigger('click');await flushPromises();expect(api.brands).toHaveBeenCalledTimes(1);document.querySelector<HTMLButtonElement>('button[data-brand-id="1"]')!.click();await flushPromises();expect(api.phones).toHaveBeenLastCalledWith({page:1,size:50,keyword:'',selectionSearch:true,brandId:1})
  const choices=picker().findAllComponents(ElCheckbox);expect(choices.map(choice=>choice.text().slice(0,4))).toEqual(['1450','1145','0052']);expect(choices[2].props('disabled')).toBe(true);expect(document.querySelector('.results-toolbar')?.textContent).not.toContain('全选当前筛选结果')
  await choose(18);expect(picker().props('modelValue')).toEqual([]);await button('取消');expect(picker().props('modelValue')).toEqual([]);expect(api.save).not.toHaveBeenCalled()
  await picker().get('.phone-model-trigger').trigger('click');await flushPromises();await choose(17);const confirm=picker().findAllComponents(ElButton).find(button=>button.text().startsWith('完成选择'))!;await confirm.trigger('click');await flushPromises();expect(picker().props('modelValue')).toEqual([17]);expect(picker().find('.phone-model-trigger').text()).toContain('1145');expect(wrapper!.findAllComponents(ElInputNumber).some(input=>input.props('modelValue')===2)).toBe(true)
  await button('保存实际收样');expect(api.save).toHaveBeenCalledTimes(1);expect(api.save.mock.calls[0][1]).toMatchObject({location:'CN',ownerUserId:71,rows:[{rowId:'row-1',rowVersion:2,phoneModelId:17,colorName:'黑色',qty:2,sourceScopeToken:'original-not-provided-source'}]});expect(api.save.mock.calls[0][1].rows[0]).not.toHaveProperty('phoneModelIds')
 })
 it('translates the unspecified-supplier hint while retaining original style and colour values',async()=>{
  setLanguage('es');await start();expect(document.body.textContent).toContain('El proveedor no indicó modelos. Selecciónelos según las muestras físicas');expect(document.body.textContent).toContain('未注明款');expect(wrapper!.findComponent({name:'ElAutocomplete'}).props('modelValue')).toBe('黑色');expect(t('供应商明确未提供型号范围，请按实际收到的样品选择真实型号')).toContain('El proveedor indicó expresamente');expect(api.save).not.toHaveBeenCalled()
 })

})
