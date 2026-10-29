import {mount,flushPromises} from '@vue/test-utils'
import ElementPlus from 'element-plus'
import {createPinia,setActivePinia} from 'pinia'
import {createRouter,createMemoryHistory} from 'vue-router'
import {beforeEach,describe,expect,it,vi} from 'vitest'
const api=vi.hoisted(()=>({getCodeItems:vi.fn(),createCodeItem:vi.fn(),updateCodeItem:vi.fn(),inspectCodeItem:vi.fn(),codeItemAction:vi.fn(),previewCodeImport:vi.fn(),commitCodeImport:vi.fn(),getCollaborationOptions:vi.fn()}))
vi.mock('@/api/modules/code',()=>api)
vi.mock('@/api/modules/collaboration',()=>({getCollaborationOptions:api.getCollaborationOptions}))
import CodeCenterView from '../CodeCenterView.vue'
import CodeMaintenancePanel from '../components/CodeMaintenancePanel.vue'
import {useUserStore} from '@/stores/user'
import CodeItemDialog from '../components/CodeItemDialog.vue'
import MasterDataLifecycleDialog from '@/components/MasterDataLifecycleDialog.vue'
const row={codeItemId:2,codeType:'color',codeValue:'02',codeName:'Negro',status:'enabled' as const,sortOrder:2,version:0}
async function start(){const pinia=createPinia();setActivePinia(pinia);const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{template:'<div />'}}]});await router.push('/');await router.isReady();const v=mount(CodeCenterView,{global:{plugins:[pinia,router,ElementPlus]}});await flushPromises();return v}
describe('CodeCenterView',()=>{
 beforeEach(()=>{vi.clearAllMocks();api.getCollaborationOptions.mockResolvedValue({writableGroups:['engineering']});api.getCodeItems.mockResolvedValue({content:[row],page:1,size:50,totalElements:351,totalPages:8});api.inspectCodeItem.mockResolvedValue({id:2,name:'Negro',code:'02',status:'enabled',version:0,canDelete:true,canDeactivate:true,canActivate:false,hasRestrictedReferences:false,references:[]})})
 it('uses server pagination rather than silently stopping at 200 and opens lifecycle inspection',async()=>{const v=await start();expect(api.getCodeItems).toHaveBeenCalledWith(expect.objectContaining({codeType:'color',size:50}));const panel=v.getComponent(CodeMaintenancePanel);panel.vm.lifecycle(row,'delete');await flushPromises();expect(api.inspectCodeItem).toHaveBeenCalledWith(2);expect(v.getComponent(MasterDataLifecycleDialog).props('perform')).toBe(api.codeItemAction);v.unmount()})
 it('provides create/import and detail/edit/delete/stop routes through the reusable panel',async()=>{const v=await start();expect(v.find('[data-test="create-code-item"]').exists()).toBe(true);expect(v.find('[data-test="import-code-items"]').exists()).toBe(true);const text=v.text();expect(text).toContain('详情');expect(text).toContain('编辑');expect(text).toContain('停用');expect(text).toContain('删除');await v.get('[data-test="create-code-item"]').trigger('click');await flushPromises();expect(v.getComponent(CodeMaintenancePanel).findComponent({name:'CodeItemDialog'}).props('modelValue')).toBe(true);v.unmount()})
 it('closes the detail drawer after deactivation and reopens the refreshed disabled record',async()=>{
  const v=await start(),panel=v.getComponent(CodeMaintenancePanel)
  panel.vm.show(row);await flushPromises()
  const drawer=panel.getComponent({name:'ElDrawer'})
  expect(drawer.props('modelValue')).toBe(true)
  await drawer.findAllComponents({name:'ElButton'}).find(button=>button.text()==='停用')!.trigger('click')
  await flushPromises()
  const lifecycle=panel.getComponent(MasterDataLifecycleDialog)
  lifecycle.getComponent({name:'ElInput'}).vm.$emit('update:modelValue','验收错建颜色，停用保留历史')
  const disabled={...row,status:'disabled' as const,version:1}
  api.codeItemAction.mockResolvedValue(disabled)
  api.getCodeItems.mockResolvedValue({content:[disabled],page:1,size:50,totalElements:351,totalPages:8})
  await flushPromises()
  await lifecycle.findAllComponents({name:'ElButton'}).find(button=>button.text()==='确认停用')!.trigger('click')
  await flushPromises()
  expect(api.codeItemAction).toHaveBeenCalledWith(2,'deactivate',expect.objectContaining({version:0,reason:'验收错建颜色，停用保留历史',requestId:expect.any(String)}))
  expect(drawer.props('modelValue')).toBe(false)
  expect(panel.findComponent(MasterDataLifecycleDialog).exists()).toBe(false)
  expect(panel.emitted('changed')).toHaveLength(1)
  expect(api.getCodeItems).toHaveBeenCalledTimes(2)
  panel.vm.show(disabled);await flushPromises()
  expect(drawer.props('modelValue')).toBe(true)
  expect(document.body.textContent).toMatch(/资料状态\s*停用/)
  v.unmount()
 })
 it('does not show write or import buttons to a reader',async()=>{api.getCollaborationOptions.mockResolvedValue({writableGroups:[]});const v=await start();expect(v.find('[data-test="create-code-item"]').exists()).toBe(false);expect(v.find('[data-test="import-code-items"]').exists()).toBe(false);expect(v.text()).toContain('详情');expect(v.text()).not.toContain('删除');v.unmount()})
 it('keeps query errors visible with retry instead of showing an empty success',async()=>{api.getCodeItems.mockRejectedValueOnce(new Error('查询失败，重试'));const v=await start();expect(v.text()).toContain('查询失败，重试');expect(v.text()).toContain('重试');v.unmount()})
 it('ignores an earlier login response after identity changes while a code save is pending',async()=>{const v=await start();const panel=v.getComponent(CodeMaintenancePanel);panel.vm.open();await flushPromises();let finish!:(value:typeof row)=>void;api.createCodeItem.mockReturnValue(new Promise(resolve=>{finish=resolve}));panel.getComponent(CodeItemDialog).vm.$emit('save',{codeType:'color',codeValue:'03',codeName:'临时颜色',sortOrder:0});await flushPromises();useUserStore().token='another-login';await flushPromises();finish({...row,codeItemId:3});await flushPromises();expect(panel.emitted('changed')).toBeUndefined();expect(panel.getComponent(CodeItemDialog).props('modelValue')).toBe(false);v.unmount()})

})
