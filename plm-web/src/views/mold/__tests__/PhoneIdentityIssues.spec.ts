import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import ElementPlus,{ElButton,ElInput,ElSelect} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
const api=vi.hoisted(()=>({getPhoneIdentityIssues:vi.fn(),resolvePhoneIdentityIssues:vi.fn(),getPhoneSelectionBrands:vi.fn(),editPhoneIdentityIssue:vi.fn(),previewPhoneBrandMerge:vi.fn(),mergePhoneBrands:vi.fn()}))
vi.mock('@/api/modules/phoneReference',()=>api)
import PhoneIdentityIssues from '../components/PhoneIdentityIssues.vue'
import {useUserStore} from '@/stores/user'
import {useCollaborationWorkspace} from '@/stores/collaborationWorkspace'
const clear={key:'code:1',sourceDigest:'a'.repeat(64),category:'missing_identity',title:'挂绳',phoneCodes:['0001'],modelNames:['挂绳'],state:'ready',canResolve:true,codeItemId:1,missingFields:[],reason:'明确挂绳',evidence:['来源原文'],editTab:'groups'}
const pending={...clear,key:'history:code_many_names:1210',category:'history',codeItemId:undefined,phoneCodes:['1210'],modelNames:['SM A57','SM A57 5G'],productNames:['骑士','幻甲'],state:'pending',historyKey:'1210',historyKind:'code_many_names',editTab:'review',missingFields:['未注明网络的记录对应4G还是5G'],reason:'需补版本依据'}
let w:VueWrapper|undefined
function page(rows:unknown[]=[clear,pending]){return {content:rows,page:1,size:50,totalElements:rows.length,totalPages:1,summary:{pending:1,ready:1,resolved:0,not_phone:0},readyItems:[{key:clear.key,sourceDigest:clear.sourceDigest}],canMaintain:true}}
beforeEach(()=>{vi.clearAllMocks();setActivePinia(createPinia());useUserStore().token='identity-test';api.getPhoneIdentityIssues.mockResolvedValue(page());api.getPhoneSelectionBrands.mockResolvedValue([{brandId:1,name:'ZTE',status:'enabled'},{brandId:2,name:'Samsung',status:'enabled'}]);api.resolvePhoneIdentityIssues.mockResolvedValue([]);api.editPhoneIdentityIssue.mockResolvedValue({})})
afterEach(()=>{w?.unmount();w=undefined;document.body.innerHTML='';vi.restoreAllMocks()})
async function start(){w=mount(PhoneIdentityIssues,{props:{visible:true},global:{plugins:[ElementPlus]}});await flushPromises();return w}
async function click(label:string){await w!.findAllComponents(ElButton).find(b=>b.text()===label)!.trigger('click');await flushPromises()}
describe('phone identity centralized maintenance',()=>{
  it('shows specific missing fields grouped by phone and product and navigates directly to existing review',async()=>{
    await start();expect(w!.text()).toContain('未注明网络的记录对应4G还是5G');expect(w!.text()).toContain('骑士、幻甲');await click('打开历史核对')
    expect(w!.emitted('navigate')).toEqual([[{tab:'review',keyword:'1210',kind:'code_many_names'}]]);expect(api.resolvePhoneIdentityIssues).not.toHaveBeenCalled()
  })
  it('processes every server-confirmed ready group beyond the current page and invalidates the existing workspace once',async()=>{
    const ready=Array.from({length:71},(_,i)=>({key:`code:${i}`,sourceDigest:'b'.repeat(64)}));api.getPhoneIdentityIssues.mockResolvedValue({...page([clear]),readyItems:ready});await start();await click('一键处理明确项（71）')
    expect(api.resolvePhoneIdentityIssues).toHaveBeenCalledWith(ready,expect.any(String));expect(useCollaborationWorkspace().revision).toBe(1);expect(w!.emitted('changed')).toHaveLength(1)
  })
  it('requires brand and evidence, preserving immutable codes and the source digest on inline correction',async()=>{
    await start();await click('补名称／品牌');await click('保存并重新核对');expect(api.editPhoneIdentityIssue).not.toHaveBeenCalled();expect(document.body.textContent).toContain('请先选择品牌')
    const dialog=w!.findAllComponents({name:'ElDialog'}).find(d=>d.props('title')==='补齐手机名称与品牌')!;dialog.getComponent(ElSelect).vm.$emit('update:modelValue',[1]);dialog.findAllComponents(ElInput)[0]!.vm.$emit('update:modelValue','ZTE Axon60/Axon70');dialog.findAllComponents(ElInput)[1]!.vm.$emit('update:modelValue','两个可信CRM来源的同一原编码');await flushPromises();await click('保存并重新核对')
    expect(api.editPhoneIdentityIssue).toHaveBeenCalledWith({codeItemId:1,sourceDigest:clear.sourceDigest,codeName:'ZTE Axon60/Axon70',brandIds:[1],reason:'两个可信CRM来源的同一原编码'});expect(useCollaborationWorkspace().revision).toBe(1)
  })
  it('retains stale-source errors without reporting success or erasing remaining issues',async()=>{
    api.resolvePhoneIdentityIssues.mockRejectedValue(new Error('资料或来源已变化，请刷新后重试'));await start();await click('一键处理明确项（1）');expect(w!.text()).toContain('资料或来源已变化');expect(w!.text()).toContain('SM A57');expect(w!.emitted('changed')).toBeUndefined()
  })
  it('does not expose maintenance actions to a read-only actor and clears data after account change',async()=>{
    api.getPhoneIdentityIssues.mockResolvedValue({...page([{...pending,canResolve:false}]),canMaintain:false});await start();expect(w!.text()).not.toContain('一键处理明确项');expect(w!.text()).not.toContain('统一重复品牌');useUserStore().token='another-user';await flushPromises();expect(w!.text()).not.toContain('SM A57')
  })
  it('opens a specific conflicting member in the existing phone editor instead of sending the user to search again',async()=>{
    api.getPhoneIdentityIssues.mockResolvedValue(page([{...pending,key:'member:838',category:'member_brand_conflict',historyKind:undefined,historyKey:undefined,focusPhoneModelId:838,phoneCodes:['1285'],editTab:'phones'}]));await start();await click('补品牌／型号');expect(w!.emitted('navigate')?.[0]?.[0]).toMatchObject({tab:'phones',keyword:'1285',phoneModelId:838})
  })
})
