import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import ElementPlus,{ElSelect} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {useUserStore} from '@/stores/user'
import Custodian from '../SampleCustodianSelect.vue'
const api=vi.hoisted(()=>({options:vi.fn()}))
vi.mock('@/api/modules/samples',()=>({getSampleCustodians:api.options}))
let wrapper:VueWrapper
const people=[{userId:7,name:'工程师',departmentName:'工程部'},{userId:8,name:'工程师',departmentName:'样品室'}]
beforeEach(()=>{vi.clearAllMocks();setActivePinia(createPinia());const user=useUserStore();user.token='actual-user';user.profile={userId:7,userName:'工程师'} as never;api.options.mockResolvedValue({content:people})})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML=''})
async function start(props:Record<string,unknown>={modelValue:7,name:'工程师'}){wrapper=mount(Custodian,{props,global:{plugins:[ElementPlus]},attachTo:document.body});await flushPromises()}
describe('sample custodians use system account identity',()=>{
 it('retains the supplied current user and selects another account by ID with its real name',async()=>{await start();const select=wrapper.getComponent(ElSelect);expect(select.props('modelValue')).toBe(7);expect(select.props('filterable')).toBe(true);expect(select.props('allowCreate')).toBe(false);const options=wrapper.findAllComponents({name:'ElOption'});expect(options.map(option=>option.props('label'))).toEqual(['工程师 · 工程部','工程师 · 样品室']);select.vm.$emit('update:modelValue',8);await flushPromises();expect(wrapper.emitted('update:modelValue')).toEqual([[8]]);expect(wrapper.emitted('update:name')).toEqual([['工程师']])})
 it('keeps the existing choice on a failed list request and reloads the list in place',async()=>{api.options.mockRejectedValueOnce(Error('offline'));await start();expect(wrapper.getComponent(ElSelect).props('modelValue')).toBe(7);expect(wrapper.get('[role="alert"]').text()).toContain('读取失败');await wrapper.find('button').trigger('click');await flushPromises();expect(api.options).toHaveBeenCalledTimes(2);expect(wrapper.find('[role="alert"]').exists()).toBe(false);expect(wrapper.emitted('update:modelValue')).toBeUndefined()})
 it('shows a historical name without assigning it to a same-name account',async()=>{await start({name:'旧保管人'});expect(wrapper.getComponent(ElSelect).props('modelValue')).toBe('legacy');expect(wrapper.emitted('update:modelValue')).toBeUndefined();wrapper.getComponent(ElSelect).vm.$emit('update:modelValue','unregistered person');await flushPromises();expect(wrapper.emitted('update:name')).toBeUndefined()})
 it('discards a late response from the previous account',async()=>{let old!:(value:{content:typeof people})=>void;api.options.mockImplementationOnce(()=>new Promise(resolve=>{old=resolve}));await start();const user=useUserStore();user.profile={userId:9,userName:'另一账号'} as never;api.options.mockResolvedValue({content:[{userId:9,name:'另一账号'}]});await flushPromises();old({content:people});await flushPromises();expect(wrapper.findAllComponents({name:'ElOption'}).map(option=>option.props('label'))).toContain('另一账号');expect(wrapper.findAllComponents({name:'ElOption'}).map(option=>option.props('label'))).not.toContain('工程师 · 样品室')})
})
