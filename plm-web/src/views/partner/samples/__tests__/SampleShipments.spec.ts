import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {createRouter,createMemoryHistory} from 'vue-router'
import ElementPlus,{ElInput,ElInputNumber,ElSelect} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {useUserStore} from '@/stores/user'
import Shipments from '../SampleShipments.vue'
const api=vi.hoisted(()=>Object.fromEntries(['sampleShipments','sampleShipment','sampleIdentities','sampleIdentity','sampleShipmentOptions','saveSampleShipment','deleteSampleShipment','sampleShipmentAction','createSampleIdentity'].map(key=>[key,vi.fn()])))
const source=vi.hoisted(()=>({head:vi.fn(),row:vi.fn(),options:vi.fn()}));vi.mock('@/api/modules/sampleManagement',()=>api)
vi.mock('@/api/modules/partner',()=>({newRequestId:()=>crypto.randomUUID(),partnerOptions:source.options}))
vi.mock('@/api/modules/partnerSelections',()=>({selectionDetail:source.head,selectionRow:source.row}))
vi.mock('../../PartnerFiles.vue',()=>({default:{name:'PartnerFiles',template:'<div>原授权图片附件组件</div>'}}))
vi.mock('../../selection/SelectionReceiptDialog.vue',()=>({default:{name:'SelectionReceiptDialog',props:['id','version','rows','supplierShipmentId'],template:'<div>原批量实际收样表</div>'}}))
vi.mock('../SampleShipmentActionDialog.vue',()=>({default:{name:'SampleShipmentActionDialog',props:['shipment','action'],template:'<div>实际动作表</div>'}}))
const shipment=(status='draft')=>({id:9,code:'SJ-000009',name:'供应商寄样5件',version:1,status,direction:'supplier_inbound',selectionId:7,supplierCode:'SUP-X',rows:[{rowId:'parcel-1',selectionRowId:'row-1',name:'透明壳',specName:'A40',qty:5,receivedGood:3,receivedDamaged:0,confirmedLoss:0,inTransit:2}],totals:{planned:5,dispatched:status==='draft'?0:5,receivedGood:status==='draft'?0:3,receivedDamaged:0,confirmedLoss:0,inTransit:status==='draft'?0:2},receiptContext:{selectionId:7,supplierShipmentId:9,rows:[{rowId:'parcel-1',selectionRowId:'row-1',remainingQty:2}]},events:[{id:1,action:'dispatched',data:{date:'2026-10-27',tracking:'TRACK-9'}}]})
let wrapper:VueWrapper
beforeEach(()=>{vi.clearAllMocks();setActivePinia(createPinia());const user=useUserStore();user.token='engineer';user.profile={userId:1} as never;user.permissions=['samples:write'];source.options.mockResolvedValue({suppliers:[{code:'SUP-X',name:'真实供应商'}]});api.sampleShipments.mockResolvedValue({content:[shipment()],page:1,size:50,totalElements:1,totalPages:1});api.sampleShipment.mockResolvedValue(shipment());api.sampleShipmentOptions.mockResolvedValue({receivers:[{id:81,name:'墨西哥工程师'}]});api.sampleIdentities.mockResolvedValue({content:[]});api.saveSampleShipment.mockImplementation(async data=>({...shipment(),...data,id:9,version:1}));source.head.mockResolvedValue({id:7,version:3,name:'原选品目录'});source.row.mockResolvedValue({rowId:'row-1',rowVersion:2,name:'透明壳',supplierCode:'SUP-X',modelText:'A40',phoneModelIds:[40],catalogStyleId:3})})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML=''})
async function start(path='/partner-samples?view=shipments'){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/samples',component:Shipments},{path:'/partner-samples',component:Shipments},{path:'/partner-selections',component:{template:'<div>原选品目录</div>'}}]});await router.push(path);wrapper=mount({template:'<router-view/>'},{global:{plugins:[router,ElementPlus],stubs:{Teleport:true,ElSelect:{props:['modelValue','placeholder'],emits:['update:modelValue','change'],template:'<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value);$emit(\'change\',$event.target.value)"><slot/></select>'},ElOption:{props:['value','label'],template:'<option :value="value">{{label}}</option>'}}},attachTo:document.body});await flushPromises();return router}
const click=async(text:string)=>{await wrapper.findAll('button').find(b=>b.text()===text)!.trigger('click');await flushPromises()}
describe('supplier source and sample parcel UI',()=>{
 it('starts an inbound record from its original selected source without a pre-existing SY or a physical receipt',async()=>{const router=await start('/partner-samples?view=shipments&newShipment=supplier_inbound&selectionId=7&selectionRowIds=row-1');expect(wrapper.findAllComponents(ElInput).some(i=>i.props('modelValue')==='原选品目录 · 供应商寄样')).toBe(true);const quantity=wrapper.findAllComponents(ElInput).find(i=>String(i.props('ariaLabel')||'').startsWith('拟寄数量'))!;expect(quantity.props('modelValue')).toBe('');quantity.vm.$emit('update:modelValue','5');await click('保存草稿');expect(api.saveSampleShipment).toHaveBeenCalledWith(expect.objectContaining({direction:'supplier_inbound',selectionId:7,supplierCode:'SUP-X',origin:'',destination:'',rows:[expect.objectContaining({selectionRowId:'row-1',qty:5})]}),undefined);expect(api.createSampleIdentity).not.toHaveBeenCalled();expect(api.sampleShipmentAction).not.toHaveBeenCalled();expect(router.currentRoute.value.query.newShipment).toBeUndefined();expect(router.currentRoute.value.query.selectionRowIds).toBeUndefined();expect(router.currentRoute.value.query.shipmentId).toBe('9');expect(wrapper.findComponent({name:'SelectionReceiptDialog'}).exists()).toBe(false)})
 it('takes the top inbound entry to the original selection instead of requiring an existing master code',async()=>{const router=await start();await click('从选品目录登记寄样');expect(router.currentRoute.value.path).toBe('/partner-selections')})
 it('opens original real-receipt component with source rows and supplier shipment link after actual dispatch',async()=>{api.sampleShipment.mockResolvedValue(shipment('partially_received'));await start();await click('查看／办理');expect(wrapper.text()).toContain('完好实收 3');expect(wrapper.text()).toContain('剩余在途 2');expect(wrapper.text()).toContain('实际寄出');await click('登记国内实际收样');expect(wrapper.getComponent({name:'SelectionReceiptDialog'}).props()).toMatchObject({id:7,version:3,supplierShipmentId:9,rows:[expect.objectContaining({rowId:'row-1'})]})})
 it('uses searchable real receiver names for Mexico signing while allowing a separately written actual recipient',async()=>{await start('/partner-samples?view=shipments&newShipment=mexico_outbound');const selector=wrapper.findAllComponents(ElSelect).find(s=>s.props('placeholder')==='选择系统用户')!;expect(selector.exists()).toBe(true);expect(wrapper.text()).toContain('系统用户（选填）');expect(wrapper.text()).not.toContain('系统签收账号 ID');selector.vm.$emit('update:modelValue',81);await click('保存草稿');expect(api.saveSampleShipment).toHaveBeenCalledWith(expect.objectContaining({receiverUserId:81}),undefined)})
 it('reopens a saved shipment by ID without recreating a new inbound draft after refresh',async()=>{await start('/partner-samples?view=shipments&shipmentId=9');expect(api.sampleShipment).toHaveBeenCalledWith(9);expect(wrapper.find('.shipment-form').exists()).toBe(false);expect(wrapper.text()).toContain('SJ-000009')})
 it('cleans one-time draft-entry parameters when an unmodified new draft is closed',async()=>{const router=await start('/partner-samples?view=shipments&newShipment=mexico_outbound');await click('取消');expect(router.currentRoute.value.query.newShipment).toBeUndefined();expect(wrapper.findAll('button').find(b=>b.text()==='保存草稿')?.isVisible()).not.toBe(true)})

 it('prefills every selected identity, its only available position and a meaningful automatic shipment title',async()=>{api.sampleIdentity.mockImplementation(async(id:number)=>({id,sampleIdentityId:id,sampleCode:'SY-'+id,name:'实际样品'+id,specName:'手机 紫色',balances:{available:2},positions:[{name:'中国工程柜',available:2}]}));const router=await start('/partner-samples?view=shipments&newShipment=mexico_outbound&sampleIdentityIds=71,72');expect(api.sampleIdentity).toHaveBeenCalledWith(71);expect(api.sampleIdentity).toHaveBeenCalledWith(72);expect(wrapper.findAll('.draft-row')).toHaveLength(2);expect(wrapper.text()).not.toContain('记录名称');expect(wrapper.text()).toContain('寄样单标题（自动生成，可修改）');await click('保存草稿');expect(api.saveSampleShipment).toHaveBeenCalledWith(expect.objectContaining({name:'样品发往墨西哥 · 2种样品',origin:'中国样品间',destination:'墨西哥样品间',rows:[expect.objectContaining({sampleIdentityId:71,location:'中国工程柜',qty:1}),expect.objectContaining({sampleIdentityId:72,location:'中国工程柜',qty:1})]}),undefined);expect(router.currentRoute.value.query.sampleIdentityIds).toBeUndefined()})

 it('retains manually changed outbound rooms when the saved draft is reopened instead of applying new-draft defaults again',async()=>{
  await start('/partner-samples?view=shipments&newShipment=mexico_outbound')
  const inputs=wrapper.findAllComponents(ElInput)
  inputs.find(input=>input.props('modelValue')==='中国样品间')!.vm.$emit('update:modelValue','中国工程室 B柜')
  inputs.find(input=>input.props('modelValue')==='墨西哥样品间')!.vm.$emit('update:modelValue','墨西哥验证室')
  await click('保存草稿')
  const saved={...shipment(),...api.saveSampleShipment.mock.calls[0][0],id:9,version:2}
  expect(saved).toMatchObject({direction:'mexico_outbound',origin:'中国工程室 B柜',destination:'墨西哥验证室'})
  wrapper.unmount();document.body.innerHTML='';api.sampleShipment.mockResolvedValue(saved)
  await start('/partner-samples?view=shipments&shipmentId=9');await click('编辑草稿')
  const reopened=wrapper.findAllComponents(ElInput).map(input=>input.props('modelValue'))
  expect(reopened).toContain('中国工程室 B柜');expect(reopened).toContain('墨西哥验证室')
  await click('保存草稿')
  expect(api.saveSampleShipment).toHaveBeenLastCalledWith(expect.objectContaining({origin:'中国工程室 B柜',destination:'墨西哥验证室',version:2}),9)
 })

 it.each(['dispatched','partially_received'])('lets the real Mexico receiver sign a scoped %s parcel without domestic options or private panels',async status=>{
  const user=useUserStore();user.profile={userId:81,roleCode:'mexico'} as never;user.permissions=['pilot:tasks','task:arrival','task:acceptance','file:read']
  const parcel={...shipment(status),direction:'mexico_outbound',minimal:true,origin:'私有中国源',rows:[{...shipment(status).rows[0],location:'国内柜不可见',receivedBatches:[{sampleCode:'私有SP批次'}]}],events:[{id:99,action:'save',time:'私有事件日期',data:{note:'私有事件说明'}}]}
  api.sampleShipments.mockResolvedValue({content:[parcel],totalElements:1});api.sampleShipment.mockResolvedValue(parcel)
  await start('/samples?view=shipments&newShipment=supplier_inbound&selectionId=7&selectionRowIds=row-1');await click('查看／办理')
  expect(api.sampleShipments).toHaveBeenCalledWith(expect.objectContaining({direction:'mexico_outbound'}));expect(api.sampleShipmentOptions).not.toHaveBeenCalled();expect(source.options).not.toHaveBeenCalled();expect(source.head).not.toHaveBeenCalled();expect(api.sampleIdentities).not.toHaveBeenCalled()
  const buttons=wrapper.findAll('button').map(button=>button.text())
  for(const label of ['编辑草稿','删除草稿','登记实际寄出','记录物流节点','补填／修改物流号','登记国内实际收样','确认在途短少','从选品目录登记寄样','新建发墨草稿'])expect(buttons).not.toContain(label)
  for(const text of ['私有中国源','国内柜不可见','私有SP批次','私有事件日期','私有事件说明','实际流转记录'])expect(wrapper.text()).not.toContain(text)
  expect(wrapper.findComponent({name:'PartnerFiles'}).exists()).toBe(false);expect(wrapper.findAll('select').some(select=>select.attributes('placeholder')==='全部方向')).toBe(false)
  await click('登记实际签收');expect(wrapper.getComponent({name:'SampleShipmentActionDialog'}).props()).toMatchObject({action:'receive',shipment:expect.objectContaining({id:9,minimal:true})});expect(api.sampleShipmentAction).not.toHaveBeenCalled()
 })
 it('does not offer receiving for a completed local parcel or an account without arrival permission',async()=>{
  const user=useUserStore();user.profile={userId:81,roleCode:'mexico'} as never;user.permissions=['pilot:tasks','task:arrival','task:acceptance','file:read']
  api.sampleShipment.mockResolvedValue({...shipment('received'),direction:'mexico_outbound'});await start('/samples?view=shipments&shipmentId=9');expect(wrapper.findAll('button').map(button=>button.text())).not.toContain('登记实际签收')
  wrapper.unmount();user.permissions=['pilot:tasks','task:acceptance','file:read'];api.sampleShipment.mockResolvedValue({...shipment('dispatched'),direction:'mexico_outbound'});await start('/samples?view=shipments&shipmentId=9');expect(wrapper.findAll('button').map(button=>button.text())).not.toContain('登记实际签收')
 })
 it('keeps a denied receiver detail closed and does not replace the server receiver gate',async()=>{
  const user=useUserStore();user.profile={userId:81,roleCode:'mexico'} as never;user.permissions=['pilot:tasks','task:arrival','task:acceptance','file:read']
  api.sampleShipment.mockRejectedValue(new Error('仅可查看本人待收包裹'));await start('/samples?view=shipments&shipmentId=99')
  expect(wrapper.text()).toContain('仅可查看本人待收包裹');expect(wrapper.find('.shipment-detail').exists()).toBe(false);expect(wrapper.findAll('button').map(button=>button.text())).not.toContain('登记实际签收')
 })

 it('starts a manually added Mexico row at one but preserves the user clearing it',async()=>{
  await start('/partner-samples?view=shipments&newShipment=mexico_outbound');await click('＋ 添加样品')
  const quantity=wrapper.findAllComponents(ElInput).find(i=>String(i.props('ariaLabel')||'').startsWith('拟寄数量'))!
  expect(quantity.props('modelValue')).toBe('1');quantity.vm.$emit('update:modelValue','');await flushPromises();await click('保存草稿')
  expect(api.saveSampleShipment.mock.calls[0][0].rows).toEqual([expect.objectContaining({qty:null})])
 })
 it('does not reapply new-row defaults over existing draft quantities or saved blanks',async()=>{
  api.sampleShipment.mockResolvedValue({...shipment(),direction:'mexico_outbound',rows:[{rowId:'old-one',sampleIdentityId:71,qty:7},{rowId:'old-blank',sampleIdentityId:72,qty:null}]})
  await start('/partner-samples?view=shipments&shipmentId=9');await click('编辑草稿')
  const quantities=wrapper.findAllComponents(ElInput).filter(i=>String(i.props('ariaLabel')||'').startsWith('拟寄数量'))
  expect(quantities.map(i=>i.props('modelValue'))).toEqual(['7','']);await click('保存草稿')
  expect(api.saveSampleShipment.mock.calls[0][0].rows.map((r:{qty:number|null})=>r.qty)).toEqual([7,null])
 })
 it('keeps editable draft logistics prominent and reopens them without claiming a dispatch date',async()=>{
  await start('/partner-samples?view=shipments&newShipment=mexico_outbound')
  expect(wrapper.find('.shipment-form input[aria-label="运单／物流号（选填，可后补）"]').exists()).toBe(true)
  expect(wrapper.find('.shipment-extra input[aria-label="运单／物流号（选填，可后补）"]').exists()).toBe(false)
  wrapper.findAllComponents(ElInput).find(i=>i.props('ariaLabel')==='运单／物流号（选填，可后补）')!.vm.$emit('update:modelValue','DRAFT-TRACK')
  wrapper.findAllComponents(ElInput).find(i=>i.props('ariaLabel')==='承运方（选填）')!.vm.$emit('update:modelValue','承运商')
  await click('保存草稿');const saved={...shipment(),...JSON.parse(JSON.stringify(api.saveSampleShipment.mock.calls[0][0])),id:9,version:2}
  expect(saved.dispatch).toEqual({tracking:'DRAFT-TRACK',carrier:'承运商'});expect(api.sampleShipmentAction).not.toHaveBeenCalled()
  wrapper.unmount();document.body.innerHTML='';api.sampleShipment.mockResolvedValue(saved)
  await start('/partner-samples?view=shipments&shipmentId=9');await click('编辑草稿');await click('保存草稿')
  expect(api.saveSampleShipment).toHaveBeenLastCalledWith(expect.objectContaining({dispatch:{tracking:'DRAFT-TRACK',carrier:'承运商'},version:2}),9)
 })
 it.each(['dispatched','partially_received','received'])('offers tracking completion for %s without dispatching again',async status=>{
  api.sampleShipment.mockResolvedValue({...shipment(status),dispatch:{date:'2026-10-27',tracking:''}})
  await start('/partner-samples?view=shipments&shipmentId=9');expect(wrapper.text()).toContain('物流号待补')
  await click('补填／修改物流号');expect(wrapper.getComponent({name:'SampleShipmentActionDialog'}).props('action')).toBe('tracking')
  expect(api.sampleShipmentAction).not.toHaveBeenCalled();expect(wrapper.findAll('button').some(b=>b.text()==='登记实际寄出')).toBe(false)
 })
 it('keeps logistics actions unavailable to a read-only account',async()=>{
  useUserStore().permissions=[];api.sampleShipment.mockResolvedValue(shipment('received'))
  await start('/partner-samples?view=shipments&shipmentId=9')
  expect(wrapper.findAll('button').some(b=>b.text()==='补填／修改物流号')).toBe(false)
 })

 it.each([
  {location:'CN',site:'CN',expected:'中国样品间未设库位'},
  {location:'中国工程室 A柜',site:'CN',locationId:81,locationCode:'A001-A0001-001-001-0001',expected:'A001-A0001-001-001-0001 · 中国工程室 A柜'}
 ])('renders the dispatch source position independently of the product name for $location',async({expected,...position})=>{
  const name='本地合成18 Pro Max样品',parcel=shipment('dispatched')
  api.sampleShipment.mockResolvedValue({...parcel,direction:'mexico_outbound',rows:[{...parcel.rows[0],name,...position}]})
  await start('/partner-samples?view=shipments&shipmentId=9')
  const line=wrapper.get('.detail-line'),facts=line.get('span').text()
  expect(line.get('strong').text()).toContain(name)
  expect(facts).toContain('取出位置 '+expected)
  expect(facts).not.toContain('取出位置 '+name)
 })

})
