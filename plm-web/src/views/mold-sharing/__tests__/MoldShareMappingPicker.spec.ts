import {mount,flushPromises} from '@vue/test-utils'
import {describe,expect,it,vi} from 'vitest'
const api=vi.hoisted(()=>({getMoldShareMappings:vi.fn()}))
vi.mock('@/api/modules/moldSharing',()=>api)
import Picker from '../components/MoldShareMappingPicker.vue'

describe('mold mapping selection',()=>{
 it('loads all materials for the same reference and relation even when search and pagination hide a part',async()=>{
  const tpu={id:1,version:1,referencePhoneModelId:17,manufacturingRelationId:7,materialCode:'10',moldCode:'MBA10J1121',referencePhoneName:'17 Pro',sourcePhoneName:'18 Pro'},pc={...tpu,id:2,materialCode:'20',moldCode:'MBA20J1121'},other={...pc,id:3,manufacturingRelationId:8};
  api.getMoldShareMappings.mockResolvedValueOnce({content:[tpu],totalElements:1}).mockResolvedValueOnce({content:[tpu],totalElements:1}).mockResolvedValueOnce({content:[tpu],totalElements:3}).mockResolvedValueOnce({content:[pc,other],totalElements:3});
  const view=mount(Picker,{props:{productLineId:50,sourcePhoneModelId:18,holeType:'precision',structureDescription:''},global:{stubs:{ElDialog:{template:'<section><slot/></section>'},ElButton:{template:'<button><slot/></button>'},ElInput:{props:['modelValue'],emits:['update:modelValue'],template:'<input :value="modelValue" @input="$emit(\'update:modelValue\',$event.target.value)"/>'},ElAlert:true,ElPagination:true},directives:{loading:()=>{}}}});
  await flushPromises();await view.get('input').setValue('MBA10J1121');await view.findAll('button')[0]!.trigger('click');await flushPromises();await view.findAll('button')[1]!.trigger('click');await flushPromises();
  expect(api.getMoldShareMappings.mock.calls[2]![0]).toMatchObject({referencePhoneModelId:17,page:1});expect(api.getMoldShareMappings.mock.calls[2]![0]).not.toHaveProperty('keyword');expect(api.getMoldShareMappings.mock.calls[3]![0].page).toBe(2);
  expect(view.emitted('selected')).toEqual([[[tpu,pc]]]);view.unmount();
 })
})
