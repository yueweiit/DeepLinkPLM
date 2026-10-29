import { mount,flushPromises } from '@vue/test-utils'
import { createPinia,setActivePinia } from 'pinia'
import ElementPlus,{ElUpload} from 'element-plus'
import { afterEach,expect,it,vi } from 'vitest'
import {useUserStore} from '@/stores/user'
import Dialog from '../PartnerImportDialog.vue'
import OperationSpecPicker from '../operations/OperationSpecPicker.vue'
import SharedCompatiblePhoneSelector from '@/views/mold/components/SharedCompatiblePhoneSelector.vue'
const api=vi.hoisted(()=>({partnerOptions:vi.fn(),importPreview:vi.fn(),importMatches:vi.fn(),importCommit:vi.fn(),templateDownload:vi.fn(),newRequestId:()=> 'import-test'}))
vi.mock('@/api/modules/partner',()=>api)
vi.mock('@/api/modules/partnerOperations',()=>({operationSpecOptions:vi.fn().mockResolvedValue({content:[],brands:[],productLines:[]}),operationSpecPhones:vi.fn()}))
vi.mock('@/views/mold/components/SharedCompatiblePhoneSelector.vue',()=>({default:{name:'SharedCompatiblePhoneSelector',props:['modelValue'],template:'<div />'}}))
vi.mock('../PartnerFacts.vue',()=>({default:{template:'<div />'}}))
let wrapper:ReturnType<typeof mount>|undefined
afterEach(()=>{wrapper?.unmount();wrapper=undefined;document.body.innerHTML='';vi.clearAllMocks()})
it.each(['partner-products','partner-sales'] as const)('%s preserves business IDs and its appropriate supplier scope before import confirmation',async(kind)=>{
 setActivePinia(createPinia());useUserStore().token='import-test'
 api.partnerOptions.mockResolvedValue({specs:[],suppliers:[{code:'SUP-A',name:'供方'}],customers:[]})
 const row={rowNumber:2,values:{'商品名称':'现有保护壳','规格':'原文'},action:'resolve',errors:['待匹配'],supplierCode:'SUP-A',phoneModelIds:[]}
 api.importPreview.mockResolvedValue({token:'original-import',rows:[row],errorCount:1});api.importMatches.mockResolvedValue({token:'original-import',rows:[{...row,specId:5001,phoneModelIds:[101,102],errors:[]}],errorCount:0})
 wrapper=mount(Dialog,{props:{modelValue:false,kind},attachTo:document.body,global:{plugins:[ElementPlus],stubs:{ElDialog:{props:['modelValue'],template:'<section v-if="modelValue"><slot/><slot name="footer"/></section>'}}}})
 await wrapper.setProps({modelValue:true});await flushPromises();await wrapper.getComponent(ElUpload).props('beforeUpload')!(new File(['data'],'source.csv') as never);await flushPromises()
 const picker=wrapper.getComponent(OperationSpecPicker);expect(picker.props('supplierCode')).toBe(kind==='partner-sales'?'SUP-A':undefined);picker.vm.$emit('update:modelValue',5001);picker.vm.$emit('selected',{id:5001,details:{phoneModelIds:[101,102]}});await flushPromises()
 if(kind==='partner-products')expect(wrapper.getComponent(SharedCompatiblePhoneSelector).props('modelValue')).toEqual([101,102])
 await wrapper.findAll('button').find(b=>b.text()==='确认导入')!.trigger('click');await flushPromises()
 expect(api.importMatches).toHaveBeenCalledWith(kind,'original-import',[{rowNumber:2,supplierCode:'SUP-A',customerId:undefined,specId:5001,...(kind==='partner-products'?{phoneModelIds:[101,102]}:{})}]);expect(api.importCommit).not.toHaveBeenCalled()
})
