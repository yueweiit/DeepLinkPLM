import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {createRouter,createMemoryHistory} from 'vue-router'
import ElementPlus,{ElCheckbox,ElPagination,ElSelect,ElInput} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {useUserStore} from '@/stores/user'
import Entry from '../SampleManagementEntry.vue'
const api=vi.hoisted(()=>({query:vi.fn(),flat:vi.fn(),detail:vi.fn(),options:vi.fn(),locations:vi.fn()}));vi.mock('@/api/modules/sampleManagement',()=>({querySampleIdentities:api.query,sampleIdentities:api.flat,sampleIdentity:api.detail,sampleIdentityOptions:api.options,sampleLocationOptions:api.locations}))
const exporting=vi.hoisted(()=>({build:vi.fn(),save:vi.fn()}))
vi.mock('@/utils/sampleLabelExport',()=>({buildSampleLabelWorkbook:exporting.build}))
vi.mock('@/utils/file',async importOriginal=>({...await importOriginal<typeof import('@/utils/file')>(),saveBlob:exporting.save}))
vi.mock('../../PartnerFiles.vue',()=>({default:{name:'PartnerFiles',props:['kind','id','files','canWrite','canWriteMoney','modelValue'],template:'<div class="aggregate-files"><span v-for="file in files">{{file.name}} · {{file.version}}</span></div>'}}))
vi.mock('../../PartnerPhoto.vue',()=>({default:{name:'PartnerPhoto',props:{id:Number,sampleIdentityId:Number,previewable:Boolean},template:'<span class="partner-photo"/>'}}))
vi.mock('../SampleLocationSetDialog.vue',()=>({default:{name:'SampleLocationSetDialog',props:['samples'],emits:['close','saved'],template:'<div>统一设置实际位置</div>'}}))
vi.mock('../SampleLocationManager.vue',()=>({default:{name:'SampleLocationManager',emits:['close'],template:'<div>同页位置目录维护</div>'}}))
vi.mock('../../PartnerWorkspace.vue',()=>({default:{template:'<div class="legacy-sample-workspace">原实收／批次／流转组件</div>'}}))
vi.mock('../SampleShipments.vue',()=>({default:{name:'SampleShipments',props:['domain'],template:'<div>原样品寄样物流入口</div>'}}))
vi.mock('../SampleBatchActionDialog.vue',()=>({default:{name:'SampleBatchActionDialog',props:['action','samples'],template:'<div>批量实际办理</div>'}}))
vi.mock('../SampleReceiptCorrectionDialog.vue',()=>({default:{name:'SampleReceiptCorrectionDialog',props:['action','samples'],emits:['close','saved'],template:'<div>原登记更正</div>'}}))
vi.mock('../SampleColorCorrectionDialog.vue',()=>({default:{name:'SampleColorCorrectionDialog',props:['sample'],template:'<div>颜色更正办理</div>'}}))
vi.mock('../SampleLabelPrint.vue',()=>({default:{name:'SampleLabelPrint',props:['modelValue','samples'],template:'<div v-if="modelValue" class="test-print">打印预览</div>'}}))
const balances={onhand:5,available:5,borrowed:0,retained:0,damaged:0,inTransit:0,positions:[{name:'工程现场',site:'CN',onhand:5,available:5,retained:0,damaged:0}]}
const identity=()=>({id:71,code:'SY-000071',sampleIdentityId:71,sampleCode:'SY-000071',name:'透明壳',specName:'OPPO A40 黑色',domain:'partner',version:1,sourceType:'catalog',files:[{id:401,name:'实际样品照片.png',type:'image/png',version:'V2',size:1}],balances,positions:balances.positions,batches:[{id:91,code:'SP-000091',version:1,receivedQty:3,balances:{...balances,onhand:3,available:3}},{id:92,code:'SP-000092',version:1,receivedQty:2,balances:{...balances,onhand:2,available:2}}]})

const pageOf=(content:ReturnType<typeof identity>[],totalElements=content.length,page=1)=>({content,page,size:50,totalElements,totalPages:Math.ceil(totalElements/50)})
let wrapper:VueWrapper
beforeEach(()=>{
 vi.clearAllMocks();setActivePinia(createPinia())
 const user=useUserStore();user.token='u1';user.profile={userId:1,userName:'工程师',canMaintainEngineeringData:true} as never;user.permissions=['partner:view','samples:write']
 api.flat.mockReset().mockResolvedValue(pageOf([identity()]))
 api.query.mockReset().mockResolvedValue({content:[identity()],totalElements:1})
 api.detail.mockResolvedValue(identity())
 api.options.mockResolvedValue({suppliers:[{supplierCode:'S-1',supplierName:'历史供应商',shortName:'旧供方'}]});api.locations.mockResolvedValue({content:[]})
 exporting.build.mockReset().mockResolvedValue(new Blob(['xlsx']))
})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML=''})
async function start(path='/partner-samples'){const router=createRouter({history:createMemoryHistory(),routes:['/samples','/partner-samples','/model-samples','/research-samples'].map(path=>({path,component:Entry,meta:{partnerKind:'samples',sampleDomain:path==='/model-samples'?'model':path==='/partner-samples'?'partner':path==='/research-samples'?'research':undefined}})).concat([{path:'/partner-selections',component:{template:'<div/>'} as never,meta:{} as never}])});await router.push(path);wrapper=mount({template:'<router-view/>'},{global:{plugins:[router,ElementPlus],stubs:{Teleport:true,ElSelect:{name:'ElSelect',props:['modelValue','placeholder','ariaLabel','disabled'],emits:['update:modelValue','change'],template:'<select :aria-label="ariaLabel" :disabled="disabled" :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value);$emit(\'change\',$event.target.value)"><slot/></select>'},ElOption:{props:['value','label'],template:'<option :value="value">{{label}}</option>'}}},attachTo:document.body});await flushPromises();return router}
const allCheckbox=()=>wrapper.findAllComponents(ElCheckbox).find(value=>value.attributes('aria-label')==='全选本页样品')!
const exportButton=()=>wrapper.findAll('button').find(button=>button.text().startsWith('批量导出标签'))!
describe('stable same-type sample entry',()=>{

 it('renders individual codes in one scoped page request and shares only matching style captions',async()=>{
  const rows=['豆沙粉色','玫红色','米白色','黑色'].map((colorName,i)=>({...identity(),sampleIdentityId:i+1,sampleCode:'YYP-'+(110+i),groupKey:'identity:'+i,name:'天眼阶梯',phoneName:'Samsung Galaxy A57 5G',colorName}))
  api.flat.mockResolvedValue(pageOf(rows));await start('/research-samples')
  expect(api.flat).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({domain:'research',page:1,size:50}))
  expect(api.query).not.toHaveBeenCalled();expect(api.options).not.toHaveBeenCalled()
  expect(wrapper.findAll('.identity-row')).toHaveLength(4);expect(wrapper.findAll('.style-caption')).toHaveLength(1)
  expect(wrapper.findAll('.code-link').map(b=>b.text())).toEqual(rows.map(r=>r.sampleCode))
  expect(wrapper.findAll('.cn').map(cell=>cell.text())).toEqual(Array(4).fill('中国5'))
  expect(wrapper.text()).not.toContain('工程现场');expect(wrapper.find('.selection-actions').exists()).toBe(false)
 })

 it('shows separate photos for distinct catalog styles with identical display names',async()=>{
  const rows=[11,12].map(id=>({...identity(),sampleIdentityId:id,sampleCode:'SY-'+id,groupKey:'catalog:'+id+':S',photo:{sampleIdentityId:id,id:id+200}}))
  api.flat.mockResolvedValue(pageOf(rows));await start()
  expect(wrapper.findAll('.style-caption')).toHaveLength(2)
  expect(wrapper.findAllComponents({name:'PartnerPhoto'}).map(photo=>photo.props('id'))).toEqual([211,212])
 })
 it('discards stale list and full-selection responses when switching domain',async()=>{
  const second={...identity(),sampleIdentityId:72,sampleCode:'SY-000072',name:'新款'}
  let finishList!:(v:unknown)=>void,finishSelection!:(v:unknown)=>void
  api.flat.mockResolvedValueOnce(pageOf([identity()],70)).mockImplementationOnce(()=>new Promise(r=>{finishList=r})).mockResolvedValue(pageOf([second]))
  api.query.mockImplementationOnce(()=>new Promise(r=>{finishSelection=r}))
  const router=await start();allCheckbox().vm.$emit('change',true);await flushPromises()
  await wrapper.findAll('button').find(b=>b.text().startsWith('全选当前筛选结果'))!.trigger('click')
  wrapper.getComponent(ElPagination).vm.$emit('current-change',2);await flushPromises()
  await router.push('/research-samples');await flushPromises()
  finishSelection({content:[identity()]});finishList(pageOf([identity()]));await flushPromises()
  expect(wrapper.get('.code-link').text()).toBe(second.sampleCode);expect(wrapper.find('.selection-actions').exists()).toBe(false)
 })
 it('keeps the active search, selection and detail when navigating or updating the same profile',async()=>{
  const router=await start('/research-samples')
  await wrapper.getComponent(ElInput).find('input').setValue('透明壳')
  await wrapper.getComponent(ElInput).find('input').trigger('keyup.enter');await flushPromises()
  allCheckbox().vm.$emit('change',true);await flushPromises()
  const reads=api.flat.mock.calls.length
  await wrapper.get('.code-link').trigger('click');await flushPromises()
  const user=useUserStore();user.profile={...user.profile!,userName:'同一账号'}
  await flushPromises();expect(wrapper.get('.identity-detail h2').text()).toBe('透明壳')
  await router.push('/research-samples');await flushPromises()
  expect(wrapper.getComponent(ElInput).props('modelValue')).toBe('透明壳')
  expect(wrapper.get('.selection-actions').text()).toContain('已选 1 种样品');expect(api.flat).toHaveBeenCalledTimes(reads)
 })
 it('waits for the complete filtered selection before enabling any bulk operation',async()=>{
  api.flat.mockResolvedValue(pageOf([identity()],193));await start();allCheckbox().vm.$emit('change',true);await flushPromises();exporting.build.mockRejectedValueOnce(new Error('临时导出失败'));await exportButton().trigger('click');await flushPromises()
  let finish!:(value:unknown)=>void;api.query.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
  await wrapper.findAll('button').find(button=>button.text()==='全选当前筛选结果（193种）')!.trigger('click');await flushPromises()
  const buttons=[...wrapper.get('.selection-actions').findAll('button'),wrapper.findAll('button').find(button=>button.text()==='重试导出')!];expect(buttons.length).toBeGreaterThan(5)
  for(const button of buttons){expect((button.element as HTMLButtonElement).disabled).toBe(true);(button.element as HTMLButtonElement).click()}
  await flushPromises();expect(wrapper.findComponent({name:'SampleLocationSetDialog'}).exists()).toBe(false);expect(wrapper.findComponent({name:'SampleReceiptCorrectionDialog'}).exists()).toBe(false);expect(exporting.build).toHaveBeenCalledTimes(1)
  const rows=Array.from({length:193},(_,index)=>({...identity(),sampleIdentityId:index+1,groupKey:'style-71'}))
  finish({content:rows,totalElements:193});await flushPromises()
  expect(wrapper.text()).toContain('已选 193 种样品');expect(wrapper.get('.selection-actions').findAll('button').every(button=>!(button.element as HTMLButtonElement).disabled)).toBe(true)
  await wrapper.findAll('button').find(button=>button.text()==='批量设置位置')!.trigger('click');await flushPromises()
  expect(wrapper.getComponent({name:'SampleLocationSetDialog'}).props('samples')).toHaveLength(193)
 })

 it.each([183,201,500])('selects all %i filtered identities from the contextual toolbar',async count=>{
  const rows=Array.from({length:count},(_,i)=>({...identity(),sampleIdentityId:i+1}))
  api.flat.mockResolvedValue(pageOf(rows.slice(0,50),count));api.query.mockResolvedValue({content:rows})
  await start();allCheckbox().vm.$emit('change',true);await flushPromises()
  await wrapper.findAll('button').find(b=>b.text().startsWith('全选当前筛选结果'))!.trigger('click');await flushPromises()
  expect(api.query).toHaveBeenCalledExactlyOnceWith({filters:expect.objectContaining({domain:'partner',receiptStatus:'active'})})
  await wrapper.findAll('button').find(b=>b.text().startsWith('批量删除登记'))!.trigger('click')
  expect(wrapper.getComponent({name:'SampleReceiptCorrectionDialog'}).props('samples')).toHaveLength(count)
 })
 it('retains prior selection on a failed read, offers one retry, and clears only when filters are applied',async()=>{
  api.flat.mockResolvedValue(pageOf([identity()],51));await start();allCheckbox().vm.$emit('change',true);await flushPromises();api.query.mockRejectedValueOnce(new Error('批量读取失败'));
  await wrapper.findAll('button').find(b=>b.text()==='全选当前筛选结果（51种）')!.trigger('click');await flushPromises();expect(wrapper.text()).toContain('已选 1 种样品');expect(wrapper.findAll('.el-alert--error')).toHaveLength(1);
  await wrapper.findAll('button').find(b=>b.text()==='重试')!.trigger('click');await flushPromises();expect(wrapper.text()).not.toContain('批量读取失败');expect(wrapper.text()).toContain('已选 1 种样品');
  wrapper.getComponent(ElInput).vm.$emit('update:modelValue','新条件');await flushPromises();expect(wrapper.text()).toContain('已选 1 种样品');await wrapper.get('input.el-input__inner').trigger('keyup.enter');await flushPromises();expect(wrapper.text()).not.toContain('已选 1 种样品');expect(api.flat).toHaveBeenLastCalledWith(expect.objectContaining({keyword:'新条件'}))
 })
 it('shows the current catalog name and metadata separately from the actual received colour and unchanged same-type stock',async()=>{const current={...identity(),name:'凡影精孔手机壳',productName:'凡影精孔手机壳',recordedName:'凡影大孔手机壳',colorName:'实收黑色',currentCatalog:{name:'凡影精孔手机壳',color:'目录蓝色',material:'PC',unitPrice:'0.00',currency:'CNY',available:false}};api.flat.mockResolvedValue(pageOf([current]));api.detail.mockResolvedValue(current);await start();expect(wrapper.get('.style-caption').text()).toContain('凡影精孔手机壳');expect(wrapper.get('.style-caption').text()).not.toContain('凡影大孔手机壳');await wrapper.get('.code-link').trigger('click');await flushPromises();expect(wrapper.get('.identity-detail').text()).toContain('实收黑色');expect(wrapper.get('.positions tbody tr td:last-child').text()).toBe('5');expect(wrapper.get('.catalog-current-info').text()).toContain('当时名称凡影大孔手机壳');expect(wrapper.get('.catalog-current-info').text()).toContain('目录蓝色');expect(wrapper.get('.catalog-current-info').text()).toContain('0.00 CNY');expect(wrapper.get('.catalog-current-info').text()).toContain('来源产品已删除')})
 it('opens research receipt in its own original project workflow without sending engineers to cooperative selections',async()=>{const router=await start('/research-samples');expect(wrapper.get('.scope-switch [aria-pressed=true]').text()).toBe('新品研发');expect(wrapper.findAll('button').some(b=>b.text()==='从选品目录登记收样')).toBe(false);await wrapper.findAll('button').find(b=>b.text()==='登记收样')!.trigger('click');await flushPromises();expect(router.currentRoute.value.path).toBe('/research-samples');expect(router.currentRoute.value.query.new).toBe('1');expect(wrapper.find('.legacy-sample-workspace').exists()).toBe(true)})
 it('keeps model receipt focused on product and phone while preserving the shared batch ledger',async()=>{const router=await start('/model-samples');expect(wrapper.get('.scope-switch [aria-pressed=true]').text()).toBe('型号研发');expect(wrapper.findAll('button').some(b=>b.text()==='从选品目录登记收样')).toBe(false);await wrapper.findAll('button').find(b=>b.text()==='登记收样')!.trigger('click');await flushPromises();expect(router.currentRoute.value.path).toBe('/model-samples');expect(router.currentRoute.value.query.new).toBe('1');expect(wrapper.find('.legacy-sample-workspace').exists()).toBe(true)})
 it('keeps the original SP detail and ledger routes while removing only the duplicate top navigation button',async()=>{await start();expect(wrapper.get('.view-switch').text()).not.toContain('收样批次／原流转');wrapper.unmount();vi.clearAllMocks();for(const path of ['/samples?id=91','/model-samples?new=1','/partner-samples?view=batches','/samples?projectId=7','/research-samples?projectId=7','/samples?customerId=9&customerContext=9','/model-samples?modelProductId=51']){await start(path);expect(wrapper.find('.legacy-sample-workspace').exists()).toBe(true);expect(api.flat).not.toHaveBeenCalled();wrapper.unmount();vi.clearAllMocks()}})
 it('uses the same supplier-readable code in the list, print labels and export without generating a new identity',async()=>{const current={...identity(),sampleCode:'SY-XD-000071',originalSampleCode:'SY-000071'};api.flat.mockResolvedValue({content:[current],page:1,size:50,totalElements:1,totalPages:1});await start();expect(wrapper.text()).toContain('SY-XD-000071');allCheckbox().vm.$emit('change',true);await flushPromises();await wrapper.findAll('button').find(b=>b.text().startsWith('批量打印标签'))!.trigger('click');expect(wrapper.getComponent({name:'SampleLabelPrint'}).props('samples')).toEqual([expect.objectContaining({sampleIdentityId:71,sampleCode:'SY-XD-000071'})]);await exportButton().trigger('click');await flushPromises();expect(exporting.build).toHaveBeenCalledWith([expect.objectContaining({sampleIdentityId:71,sampleCode:'SY-XD-000071'})],window.location.origin,expect.any(Function),expect.any(Function));expect(current.code).toBe('SY-000071');expect(api.detail).not.toHaveBeenCalled()})
 it('opens original batches for borrowing and preserves the same sample identity',async()=>{const router=await start('/partner-samples?identityId=71');await wrapper.findAll('button').find(b=>b.text()==='借出')!.trigger('click');await flushPromises();expect(wrapper.text()).toContain('选择本次办理的原收样批次');await wrapper.findAll('button').find(b=>b.text()==='办理本批')!.trigger('click');await flushPromises();expect(router.currentRoute.value.query.id).toBe('91');expect(router.currentRoute.value.query.action).toBe('loan');expect(wrapper.find('.legacy-sample-workspace').exists()).toBe(true)})
 it('does not expose physical write actions to a reader',async()=>{useUserStore().permissions=['partner:view'];await start('/partner-samples?identityId=71');expect(wrapper.findAll('button').map(b=>b.text())).not.toContain('借出');expect(wrapper.text()).toContain('打印／补打二维码标签')})
 it('discards a pending old-account inventory projection after logout',async()=>{let finish!:(v:unknown)=>void;api.flat.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}));await start();useUserStore().token='';await flushPromises();finish({content:[identity()],totalElements:1});await flushPromises();expect(wrapper.text()).not.toContain('SY-000071')})
 it('shows authorized aggregate photos and file versions directly while directing uploads to a real original receipt batch',async()=>{const router=await start('/partner-samples?identityId=71');const files=wrapper.getComponent({name:'PartnerFiles'});expect(files.props()).toMatchObject({kind:'sample-identities',canWrite:false,canWriteMoney:false,files:[expect.objectContaining({id:401,version:'V2'})]});expect(wrapper.text()).toContain('实际样品照片.png · V2');await wrapper.findAll('button').find(b=>b.text()==='上传到原收样批次')!.trigger('click');await flushPromises();expect(wrapper.text()).toContain('选择图片附件归属');await wrapper.findAll('button').find(b=>b.text()==='进入本批附件')!.trigger('click');await flushPromises();expect(router.currentRoute.value.query.id).toBe('91');expect(router.currentRoute.value.query.action).toBeUndefined()})
 it('selects every visible row, shows partial selection and can clear the entire page',async()=>{api.flat.mockResolvedValue({content:[identity(),{...identity(),id:72,sampleIdentityId:72,sampleCode:'SY-000072'}],page:1,size:50,totalElements:2,totalPages:1});await start();const all=()=>wrapper.findAllComponents(ElCheckbox).find(c=>c.attributes('aria-label')==='全选本页样品')!;allCheckbox().vm.$emit('change',true);await flushPromises();expect(wrapper.text()).toContain('已选 2 种样品');const row=wrapper.findAllComponents(ElCheckbox).find(c=>c.attributes('aria-label')==='选择SY-000071')!;row.vm.$emit('change',false);await flushPromises();expect(all().props('indeterminate')).toBe(true);all().vm.$emit('change',false);await flushPromises();expect(wrapper.findAll('button').some(b=>b.text().startsWith('批量打印标签'))).toBe(false)})
 it('carries all selected real identities into a single Mexico shipment rather than just one selected row',async()=>{api.flat.mockResolvedValue({content:[identity(),{...identity(),id:72,sampleIdentityId:72,sampleCode:'SY-000072'}],totalElements:2});const router=await start();allCheckbox().vm.$emit('change',true);await flushPromises();await wrapper.findAll('button').find(b=>b.text()==='批量发往墨西哥')!.trigger('click');await flushPromises();expect(router.currentRoute.value.query).toMatchObject({view:'shipments',newShipment:'mexico_outbound',sampleIdentityIds:'71,72'})})
 it('starts bulk loan or return with the same selected identities and exposes the colour correction on a catalogue type',async()=>{await start();allCheckbox().vm.$emit('change',true);await flushPromises();await wrapper.findAll('button').find(b=>b.text()==='批量借出')!.trigger('click');expect(wrapper.getComponent({name:'SampleBatchActionDialog'}).props()).toMatchObject({action:'loan',samples:[expect.objectContaining({sampleIdentityId:71})]});wrapper.unmount();const router=await start('/partner-samples?identityId=71');await wrapper.findAll('button').find(b=>b.text()==='更正颜色')!.trigger('click');expect(wrapper.getComponent({name:'SampleColorCorrectionDialog'}).props('sample').sampleIdentityId).toBe(71);expect(router.currentRoute.value.query.identityId).toBe('71')})
 it.each(['/partner-samples','/model-samples','/research-samples'])('offers batch movement of the same selected samples on %s',async(path)=>{
  await start(path);const button=()=>wrapper.findAll('button').find(b=>b.text()==='精细移库')!
  expect(button()).toBeUndefined();allCheckbox().vm.$emit('change',true);await flushPromises();await button().trigger('click');expect(wrapper.getComponent({name:'SampleBatchActionDialog'}).props()).toMatchObject({action:'move',samples:[expect.objectContaining({sampleIdentityId:71})]})
 })
 it('exports only selected existing sample labels and keeps the print action available',async()=>{
  await start();expect(exportButton()).toBeUndefined()
  allCheckbox().vm.$emit('change',true);await flushPromises();await exportButton().trigger('click');await flushPromises()
  expect(exporting.build).toHaveBeenCalledWith([expect.objectContaining({sampleIdentityId:71,sampleCode:'SY-000071',name:'透明壳',specName:'OPPO A40 黑色'})],window.location.origin,expect.any(Function),expect.any(Function))
  expect(exporting.save).toHaveBeenCalledWith(expect.any(Blob),expect.stringMatching(/^样品标签-\d{8}\.xlsx$/))
  expect(wrapper.findAll('button').some(button=>button.text().startsWith('批量打印标签'))).toBe(true);expect(api.detail).not.toHaveBeenCalled()
 })
 it('retains selections across pages and exports each selected real identity once',async()=>{
  const second={...identity(),id:72,sampleIdentityId:72,sampleCode:'SY-000072',name:'护盾手机壳'}
  api.flat.mockResolvedValueOnce({content:[identity()],page:1,size:50,totalElements:51,totalPages:2}).mockResolvedValueOnce({content:[second],page:2,size:50,totalElements:51,totalPages:2})
  await start();allCheckbox().vm.$emit('change',true);await flushPromises();
  wrapper.getComponent(ElPagination).vm.$emit('update:currentPage',2);wrapper.getComponent(ElPagination).vm.$emit('current-change',2);await flushPromises();expect(wrapper.findAll('.identity-row')).toHaveLength(1);expect(wrapper.text()).toContain('已选 1 种样品')
  allCheckbox().vm.$emit('change',true);await flushPromises();await exportButton().trigger('click');await flushPromises()
  expect(exporting.build.mock.calls[0]![0].map((r:{sampleIdentityId:number})=>r.sampleIdentityId)).toEqual([71,72]);expect(exporting.save).toHaveBeenCalledTimes(1)
 })
 it('shows export failure and retries without losing checked samples',async()=>{
  exporting.build.mockRejectedValueOnce(new Error('文件生成失败'));await start();allCheckbox().vm.$emit('change',true);await flushPromises();await exportButton().trigger('click');await flushPromises()
  expect(wrapper.text()).toContain('文件生成失败');expect(allCheckbox().props('modelValue')).toBe(true);expect(exporting.save).not.toHaveBeenCalled()
  await wrapper.findAll('button').find(button=>button.text()==='重试导出')!.trigger('click');await flushPromises();expect(exporting.build).toHaveBeenCalledTimes(2);expect(exporting.save).toHaveBeenCalledTimes(1)
 })
 it('prevents repeated export clicks while generating one snapshot',async()=>{
  let finish!:(blob:Blob)=>void;exporting.build.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
  await start();allCheckbox().vm.$emit('change',true);await flushPromises();await exportButton().trigger('click');await flushPromises()
  expect(exportButton().attributes('disabled')).toBeDefined();(exportButton().element as HTMLButtonElement).click();await flushPromises();expect(exporting.build).toHaveBeenCalledTimes(1)
  finish(new Blob(['xlsx']));await flushPromises();expect(exporting.save).toHaveBeenCalledTimes(1);expect(exportButton().attributes('disabled')).toBeUndefined()
 })
 it('does not download a pending old-account export after logout',async()=>{
  let finish!:(blob:Blob)=>void;exporting.build.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}));await start();allCheckbox().vm.$emit('change',true);await flushPromises();await exportButton().trigger('click');await flushPromises();useUserStore().token='';finish(new Blob(['old']));await flushPromises();expect(exporting.save).not.toHaveBeenCalled();expect(wrapper.text()).not.toContain('SY-000071')
 })
 it('discards a pending export if the user changes or the page is closed',async()=>{
  for(const action of ['actor','unmount']){
   let finish!:(blob:Blob)=>void;exporting.build.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}));await start();allCheckbox().vm.$emit('change',true);await flushPromises();await exportButton().trigger('click');await flushPromises()
   if(action==='actor')useUserStore().profile={userId:2,userName:'另一工程师'} as never;else wrapper.unmount()
   finish(new Blob(['old']));await flushPromises();expect(exporting.save).not.toHaveBeenCalled();if(action==='actor')wrapper.unmount()
  }
 })
 it('lets authorized readers export the same labels they can already view and print',async()=>{
  useUserStore().permissions=['partner:view'];await start();allCheckbox().vm.$emit('change',true);await flushPromises();await exportButton().trigger('click');await flushPromises();expect(exporting.save).toHaveBeenCalledTimes(1)
 })


 it('queries the same filters for a page and cross-page selection, including zero-stock history',async()=>{
  const zero={...identity(),positions:[],balances:{...balances,onhand:0,borrowed:5}}
  api.flat.mockResolvedValue(pageOf([zero],52))
  await start('/partner-samples?supplierCode=S-1')
  expect(api.flat).toHaveBeenCalledWith(expect.objectContaining({supplierCode:'S-1',receiptStatus:'active'}))
  await wrapper.findAll('button').find(b=>b.text().startsWith('筛选'))!.trigger('click')
  expect(api.options).toHaveBeenCalledOnce()
  const status=wrapper.findAllComponents(ElSelect).find(v=>v.props('ariaLabel')==='收样登记状态')!
  status.vm.$emit('update:modelValue','voided');await flushPromises()
  allCheckbox().vm.$emit('change',true);await flushPromises()
  await wrapper.findAll('button').find(b=>b.text().startsWith('全选当前筛选结果'))!.trigger('click');await flushPromises()
  expect(api.flat).toHaveBeenLastCalledWith(expect.objectContaining({supplierCode:'S-1',receiptStatus:'voided',page:1}))
  expect(api.query).toHaveBeenLastCalledWith({filters:expect.objectContaining({supplierCode:'S-1',receiptStatus:'voided'})})
  expect(wrapper.get('.cn').text()).toContain('0')
 })
 it('retains location options after a profile refresh and shows only the selected country',async()=>{
  api.locations.mockResolvedValue({content:[{id:14,name:'工程室',site:'CN'}]})
  await start()
  await wrapper.findAll('button').find(b=>b.text().startsWith('筛选'))!.trigger('click');await flushPromises()
  const locations=wrapper.get('select[aria-label="库位筛选"]')
  expect(locations.text()).toContain('工程室')
  const user=useUserStore();user.profile={...user.profile!,userName:'同一账号'}
  await flushPromises();expect(locations.text()).toContain('工程室')
  const site=wrapper.findAllComponents(ElSelect).find(v=>v.props('ariaLabel')==='站点筛选')!
  site.vm.$emit('update:modelValue','CN');await flushPromises()
  expect(wrapper.find('.cn').exists()).toBe(true);expect(wrapper.find('.mx').exists()).toBe(false)
  site.vm.$emit('update:modelValue','MX');await flushPromises()
  expect(wrapper.find('.cn').exists()).toBe(false);expect(wrapper.find('.mx').exists()).toBe(true)
 })
 it('offers viewing and labels on shared read-only samples while hiding mutation actions',async()=>{
  const row={...identity(),ledgerReadOnly:true}
  api.flat.mockResolvedValue(pageOf([row]));api.detail.mockResolvedValue(row)
  await start();allCheckbox().vm.$emit('change',true);await flushPromises()
  expect(wrapper.get('.selection-actions').text()).toContain('批量打印标签')
  expect(wrapper.get('.selection-actions').text()).not.toContain('批量设置位置')
  await wrapper.get('.code-link').trigger('click');await flushPromises()
  expect(wrapper.get('.detail-actions').text()).toContain('打印')
  for(const action of ['设置位置','改数量','删除登记','借出'])expect(wrapper.get('.detail-actions').text()).not.toContain(action)
 })
 it('renders authorized style photos and sums each country from actual positions',async()=>{
  const pos=(site:string,onhand:number)=>({name:'库位',site,onhand,available:onhand,retained:0,damaged:0})
  const sample={...identity(),positions:[pos('CN',3),pos('CN',5),pos('MX',2)],photo:{sampleIdentityId:71,id:401}}
  api.flat.mockResolvedValue({content:[sample],totalElements:1,totalPages:1});api.detail.mockResolvedValue(sample)
  await start();expect(wrapper.get('.cn').text()).toContain('8');expect(wrapper.get('.mx').text()).toContain('2')
  expect(wrapper.getComponent({name:'PartnerPhoto'}).props()).toMatchObject({id:401,sampleIdentityId:71,previewable:true})
  await wrapper.get('.code-link').trigger('click');await flushPromises();expect(wrapper.get('.positions tbody').findAll('tr')).toHaveLength(3)
  await wrapper.findAll('button').find(b=>b.text()==='设置位置')!.trigger('click')
  expect(wrapper.getComponent({name:'SampleLocationSetDialog'}).props('samples')).toEqual([expect.objectContaining({sampleIdentityId:71})])
 })
 it('fixes Mexico to its local view while allowing local location work without opening loans, domestic shipments or old batches',async()=>{const user=useUserStore();user.profile={userId:2,roleCode:'mexico'} as never;user.permissions=['task:arrival'];await start('/samples?view=batches&id=91&identityId=71');expect(wrapper.find('.legacy-sample-workspace').exists()).toBe(false);expect(api.flat).toHaveBeenLastCalledWith(expect.objectContaining({site:'MX'}));expect(wrapper.text()).toContain('当地数量');expect(wrapper.text()).not.toContain('中国');expect(wrapper.text()).not.toContain('SP-000091');expect(wrapper.findComponent({name:'PartnerFiles'}).exists()).toBe(false);const buttons=wrapper.findAll('button').map(value=>value.text());for(const text of ['借出','归还','寄样物流','收样批次／原流转','发往墨西哥'])expect(buttons).not.toContain(text);expect(buttons).toContain('位置管理');expect(buttons).toContain('设置位置');expect(buttons).toContain('精细移库')})
 it('opens personal receiving through the existing shipments tab with the real Mexico permission set',async()=>{
  const user=useUserStore();user.profile={userId:81,roleCode:'mexico'} as never;user.permissions=['pilot:tasks','task:arrival','task:acceptance','file:read']
  const router=await start('/samples');expect(wrapper.text()).toContain('本人待签收')
  await wrapper.findAll('button').find(button=>button.text()==='本人待签收')!.trigger('click');await flushPromises()
  expect(router.currentRoute.value.query.view).toBe('shipments');expect(wrapper.findComponent({name:'SampleShipments'}).exists()).toBe(true)
  expect(wrapper.find('.legacy-sample-workspace').exists()).toBe(false);expect(wrapper.text()).not.toContain('收样批次／原流转');expect(user.hasPermission('partner:view')).toBe(false)
 })

 it('exposes receipt correction on each expanded real sample without treating its summary group as an identity',async()=>{await start();expect(wrapper.get('.ledger-table').text()).not.toContain('改数量');await wrapper.get('.code-link').trigger('click');await flushPromises();const actions=wrapper.get('.detail-actions');await actions.findAll('button').find(button=>button.text()==='改数量')!.trigger('click');const dialog=wrapper.getComponent({name:'SampleReceiptCorrectionDialog'});expect(dialog.props()).toMatchObject({action:'correct_receipt',samples:[expect.objectContaining({sampleIdentityId:71,sampleCode:'SY-000071'})]});expect(dialog.props('samples')[0]).not.toHaveProperty('sampleCount');dialog.vm.$emit('close');await flushPromises();await actions.findAll('button').find(button=>button.text()==='删除登记')!.trigger('click');expect(wrapper.getComponent({name:'SampleReceiptCorrectionDialog'}).props('action')).toBe('void_receipt')})
 it('passes exact cross-page real samples for receipt deletion and clears only successfully handled selections',async()=>{const second={...identity(),id:72,sampleIdentityId:72,sampleCode:'SY-000072'};api.flat.mockResolvedValueOnce({content:[identity()],totalElements:51}).mockResolvedValueOnce({content:[second],totalElements:51}).mockResolvedValue({content:[second],totalElements:51});await start();allCheckbox().vm.$emit('change',true);await flushPromises();wrapper.getComponent(ElPagination).vm.$emit('update:currentPage',2);wrapper.getComponent(ElPagination).vm.$emit('current-change',2);await flushPromises();allCheckbox().vm.$emit('change',true);await flushPromises();await wrapper.findAll('button').find(button=>button.text()==='批量删除登记（2）')!.trigger('click');const dialog=wrapper.getComponent({name:'SampleReceiptCorrectionDialog'});expect(dialog.props('samples').map((row:{sampleIdentityId:number})=>row.sampleIdentityId)).toEqual([71,72]);dialog.vm.$emit('saved',[71]);await flushPromises();expect(wrapper.text()).toContain('已选 1 种样品');expect(wrapper.findComponent({name:'SampleReceiptCorrectionDialog'}).exists()).toBe(false)})
 it('does not expose receipt correction or deletion to read-only users',async()=>{useUserStore().permissions=['partner:view'];await start();const names=wrapper.findAll('button').map(button=>button.text());expect(names).not.toContain('改数量');expect(names).not.toContain('删除登记');expect(names.some(name=>name.startsWith('批量删除登记'))).toBe(false)})
 it('keeps domestic receipt correction and receipt history filters out of the Mexico local view',async()=>{const user=useUserStore();user.profile={userId:81,roleCode:'mexico'} as never;user.permissions=['pilot:tasks','task:arrival','task:acceptance','file:read'];await start('/samples?identityId=71');expect(wrapper.findAllComponents(ElSelect).some(value=>value.props('ariaLabel')==='收样登记状态')).toBe(false);const names=wrapper.findAll('button').map(button=>button.text());for(const name of ['改数量','删除登记'])expect(names).not.toContain(name);expect(names.some(name=>name.startsWith('批量删除登记'))).toBe(false);expect(wrapper.findComponent({name:'SampleReceiptCorrectionDialog'}).exists()).toBe(false);expect(api.flat).toHaveBeenCalledWith(expect.objectContaining({site:'MX',receiptStatus:'active'}))})
 it('displays effective receipt quantities and retained voided history from the same original batch',async()=>{const original=identity(),batch={...original.batches[0],effectiveReceivedQty:0,receiptStatus:'voided',canCorrectReceipt:false,receiptChangeBlockedReason:'登记已作废'};api.detail.mockResolvedValue({...original,batches:[batch]});await start('/samples?identityId=71');expect(wrapper.text()).toContain('实收 0');expect(wrapper.text()).toContain('已删除登记，原记录保留');expect(wrapper.text()).toContain('SP-000091');expect(wrapper.findAll('button').some(button=>button.text()==='原记录／图片附件／更正')).toBe(true)})
})
