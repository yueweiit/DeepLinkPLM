import {mount} from '@vue/test-utils'
import ElementPlus,{ElCheckboxGroup,ElSelect} from 'element-plus'
import {describe,it,expect} from 'vitest'
import ResearchMoldCoding from '../components/ResearchMoldCoding.vue'
import type {MoldApplication} from '@/api/modules/moldApplications'

// The producer returns a project summary, without detail-only form, remote payload or activity fields.
const app=(values:Partial<MoldApplication>={})=>({applicationId:88,approvalNo:null,title:null,status:'submitted',businessStatus:'awaiting_approval',moldSetId:null,phoneModelId:30,model:'Samsung Galaxy A17 5G / A26 5G',version:3,productLineId:42,projectId:null,itemId:null,generatedCodes:null,provisioningStatus:'pending',provisioningError:null,executionStatus:'pending',approvalGranted:false,authorization:null,canEditDraft:false,files:[],canArchiveFiles:false,researchCoding:null,canMaintainResearchCoding:false,...values}) as MoldApplication
const authorization={approverUserId:'16693147192083157833',approverName:'周汉琴',taskId:'task-88'}
function render(applications:MoldApplication[],editable=true){return mount(ResearchMoldCoding,{props:{applications,inputs:{},editable,busy:false,canCreate:editable,canPrepare:editable},global:{plugins:[ElementPlus]}})}
describe('minimal step-five automatic coding',()=>{
 it('opens the existing application entry with no separate generate or confirm action',async()=>{
  const view=render([])
  expect(view.text()).toContain('Tiffany 同意后，自动生成 M 码和 N 码。')
  const buttons=view.findAll('button');expect(buttons.map(b=>b.text())).toEqual(['发起LEMOS开模申请'])
  await buttons[0]!.trigger('click');expect(view.emitted('create')).toEqual([[]]);view.unmount()
 })
 it('keeps approval pending and only emits the original progress navigation',async()=>{
  const view=render([app()])
  expect(view.text()).toContain('待 Tiffany 审核');expect(view.find('.coding-missing').exists()).toBe(false)
  expect(view.findAll('code')).toHaveLength(0)
  await view.findAll('button').find(button=>button.text()==='查看审批进度')!.trigger('click');expect(view.emitted('progress')).toEqual([[88]])
  expect(view.emitted('update:inputs')).toBeUndefined();view.unmount()
 })
 it('shows persisted M and N results without asking the engineer to confirm them',()=>{
  const view=render([app({businessStatus:'approved',approvalGranted:true,executionStatus:'active',authorization,researchCoding:{status:'ready',missingFields:[],generatedMoldCodes:['MHA100030','MHA200030'],generatedProductCodes:['NHA4020003002']}})])
  expect(view.findAll('code').map(c=>c.text())).toEqual(['MHA100030','MHA200030','NHA4020003002'])
  expect(view.text()).toContain('已自动生成并关联档案');expect(view.text()).not.toContain('4030')
  expect(view.findAll('button').map(b=>b.text())).toEqual(['选择其他型号','查看审批进度']);view.unmount()
 })
 it('does not present legacy explanations or a literal null as mold codes or change their original text',async()=>{
  for(const generatedCodes of ['✅ 型号匹配(1298) / 但工艺关键词[亮甲3.0]未匹配到对应模具','null']){
   for(const researchCoding of [null,{status:'pending' as const,missingFields:[],generatedMoldCodes:[]}]){
   const original=app({generatedCodes,researchCoding})
   const view=render([original])
   expect(view.findAll('code')).toHaveLength(0);expect(view.find('.code-results').exists()).toBe(false)
   expect(original.generatedCodes).toBe(generatedCodes)
   await view.findAll('button').find(button=>button.text()==='查看审批进度')!.trigger('click');expect(view.emitted('progress')).toEqual([[88]]);view.unmount()
   }
  }
 })
 it.each([{researchCoding:null},{researchCoding:{status:'pending' as const,missingFields:[]}}])('extracts complete legacy codes only when structured identities are absent: $researchCoding',({researchCoding})=>{
  const generatedCodes='已知 mha100030 / MHA100030，MHA200052\\nMFA100052； / ,，， MBA10 NHA100030 LLT101199 XMHA100030 MHA1000309 _MHA100030 MHA100030_X MHA100030-X MHA100030suffix null'
  const original=app({generatedCodes,researchCoding})
  const view=render([original])
  expect(view.findAll('code').map(code=>code.text())).toEqual(['MHA100030','MHA200052','MFA100052'])
  expect(original.generatedCodes).toBe(generatedCodes);view.unmount()
 })
 it('keeps an explicit empty or invalid structured result authoritative over legacy reserved codes',()=>{
  for(const generatedMoldCodes of [[],['未匹配模具','说明 MHA100030','MBA10']]){
   const view=render([app({generatedCodes:'MHA100030 / MHA200030',researchCoding:{status:'pending',missingFields:[],generatedMoldCodes}})])
   expect(view.findAll('code')).toHaveLength(0);expect(view.find('.code-results').exists()).toBe(false);view.unmount()
  }
 })
 it('prefers valid structured complete identities and rejects structured prefixes or prose',()=>{
  const view=render([app({generatedCodes:'MBA101199',researchCoding:{status:'ready',missingFields:[],generatedMoldCodes:['mfa100052','MFA100052','未匹配模具','MBA10','XMHA100030','MHA10003099','说明 MHA100030',' MHA200030 ']}})])
  expect(view.findAll('code').map(code=>code.text())).toEqual(['MFA100052','MHA200030']);view.unmount()
 })
 it('shows only the missing process selector and leaves saving to the existing page action',async()=>{
  const view=render([app({businessStatus:'approved',canMaintainResearchCoding:true,researchCoding:{status:'pending',missingFields:['operationCode'],generatedMoldCodes:['MHA100030'],operationOptions:[{code:'4020',name:'已配置最终工序'}]}})])
  expect(view.text()).toContain('待补：产品工序');expect(view.text()).not.toContain('待补：模具材质')
  expect(view.findAll('button').map(b=>b.text())).toEqual(['选择其他型号','查看审批进度'])
  await view.getComponent(ElSelect).vm.$emit('update:modelValue','4020')
  expect(view.emitted('update:inputs')).toEqual([[{88:{operationCode:'4020'}}]])
  expect(view.findAll('code').map(c=>c.text())).toEqual(['MHA100030']);view.unmount()
 })
 it('uses the two fixed material identities and blocks editing without application maintenance permission',async()=>{
  const view=render([app({canMaintainResearchCoding:false,researchCoding:{status:'pending',missingFields:['materialCodes']}})])
  expect(view.text()).toContain('待补：模具材质');expect(view.findAll('input[type=checkbox]').every(i=>(i.element as HTMLInputElement).disabled)).toBe(true)
  expect(view.getComponent(ElCheckboxGroup).props('modelValue')).toEqual([]);view.unmount()
 })
 it('labels a product prefix honestly instead of presenting it as a complete SKU',()=>{
  const view=render([app({researchCoding:{status:'ready',missingFields:[],productCodePrefix:'NHA',generatedProductCodes:[]}})])
  expect(view.findAll('code').map(c=>c.text())).toEqual(['NHA']);expect(view.text()).toContain('产品编码前缀')
  expect(view.findAllComponents(ElSelect)).toHaveLength(0);view.unmount()
 })
 it.each([
  {businessStatus:'withdrawn',executionStatus:'paused',label:'已撤回',outcome:'执行已暂停'},
  {businessStatus:'terminated',executionStatus:'terminated',label:'已终止',outcome:'执行已终止'},
  {businessStatus:'rejected',executionStatus:'pending',label:'已拒绝',outcome:''},
 ] as const)('renders stopped execution from the real summary contract: $businessStatus/$executionStatus',async value=>{
  const view=render([app({businessStatus:value.businessStatus,executionStatus:value.executionStatus,authorization,researchCoding:{status:'ready',missingFields:[],generatedMoldCodes:['MHA100030']}})])
  expect(view.text()).toContain(value.label);if(value.outcome)expect(view.text()).toContain(value.outcome)
  expect(view.text()).not.toContain('已自动生成并关联档案');expect(view.text()).not.toContain('正在自动建档');expect(view.find('.is-approved').exists()).toBe(false)
  expect(view.text()).toContain('MHA100030');await view.findAll('button').find(button=>button.text()==='查看审批进度')!.trigger('click');expect(view.emitted('progress')).toEqual([[88]]);view.unmount()
 })
 it('shows an actual provisioning conflict even while a process field is still missing',()=>{
  const view=render([app({businessStatus:'approved',approvalGranted:true,authorization,executionStatus:'active',provisioningStatus:'blocked',provisioningError:'已有模具套，请核对原申请意图',researchCoding:{status:'pending',missingFields:['operationCode']}})])
  expect(view.text()).toContain('已有模具套，请核对原申请意图');expect(view.text()).toContain('待补：产品工序');view.unmount()
 })
 it('gives a current provisioning failure priority over retained generated identities',()=>{
  const view=render([app({businessStatus:'approved',approvalGranted:true,authorization,executionStatus:'active',provisioningStatus:'blocked',provisioningError:'自动建档失败，请工程核对目标后重试',researchCoding:{status:'ready',missingFields:[],generatedMoldCodes:['MHA100030']}})])
  expect(view.text()).toContain('自动建档失败，请工程核对目标后重试');expect(view.text()).toContain('MHA100030');expect(view.text()).not.toContain('已自动生成并关联档案');view.unmount()
 })
 it('shows actual approved work in progress from the summary authorization',()=>{
  const view=render([app({businessStatus:'approved',approvalGranted:true,authorization,executionStatus:'pending',researchCoding:{status:'pending',missingFields:[]}})])
  expect(view.text()).toContain('正在自动建档');expect(view.find('.is-approved').exists()).toBe(true);view.unmount()
 })
 it('offers another phone independently of viewing a refused application',async()=>{
  const view=render([app({businessStatus:'rejected',refusal:{reason:'这个型号不好卖',actorName:'周汉琴',actorId:'verified',occurredAt:'2026-10-29T10:00:00+08:00'}})])
  expect(view.text()).toContain('已拒绝');expect(view.text()).toContain('这个型号不好卖')
  await view.findAll('button').find(button=>button.text()==='选择其他型号')!.trigger('click')
  expect(view.emitted('other-phone')).toEqual([[]]);expect(view.emitted('create')).toBeUndefined();expect(view.emitted('progress')).toBeUndefined();view.unmount()
 })

})
