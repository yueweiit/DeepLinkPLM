import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {defineComponent} from 'vue'
import PartnerPhoto from '../PartnerPhoto.vue'
import SelectionImportPhoto from '../selection/SelectionImportPhoto.vue'
import {authorizedBlobs} from '@/utils/authorizedBlobQueue'
const api=vi.hoisted(()=>({fileBlob:vi.fn(),selectionImportImage:vi.fn(),catalogImportImage:vi.fn()}))
vi.mock('@/api/modules/partner',()=>({fileBlob:api.fileBlob}))
vi.mock('@/api/modules/partnerSelections',()=>({selectionImportImage:api.selectionImportImage,catalogSourceFileClient:{selectionImportImage:api.catalogImportImage}}))
const photo=()=>new Blob(['image'],{type:'image/jpeg'})
function deferred(){let resolve!:(blob:Blob)=>void;const promise=new Promise<Blob>(yes=>{resolve=yes});return {promise,resolve}}
const wrappers:VueWrapper[]=[]
const originalCreate=Object.getOwnPropertyDescriptor(URL,'createObjectURL'),originalRevoke=Object.getOwnPropertyDescriptor(URL,'revokeObjectURL')
let sequence=0
const create=vi.fn(()=>`blob:photo-${++sequence}`),revoke=vi.fn()
beforeEach(()=>{authorizedBlobs.clear();vi.clearAllMocks();localStorage.clear();sequence=0;Object.defineProperty(URL,'createObjectURL',{configurable:true,value:create});Object.defineProperty(URL,'revokeObjectURL',{configurable:true,value:revoke});api.fileBlob.mockResolvedValue(photo());api.selectionImportImage.mockResolvedValue(photo())})
afterEach(async()=>{for(const wrapper of wrappers.splice(0))if(wrapper.exists())wrapper.unmount();authorizedBlobs.clear();await flushPromises();if(originalCreate)Object.defineProperty(URL,'createObjectURL',originalCreate);if(originalRevoke)Object.defineProperty(URL,'revokeObjectURL',originalRevoke)})
function startPhoto(id=7,allowRetry=true){const wrapper=mount(PartnerPhoto,{props:{id,allowRetry}});wrappers.push(wrapper);return wrapper}

describe('real authenticated photo states',()=>{
 it('keeps sized loading placeholders and downloads a shared file once for two mounted photos',async()=>{
  const pending=deferred();api.fileBlob.mockReturnValue(pending.promise)
  const a=startPhoto(),b=startPhoto();expect(a.get('.partner-photo').attributes('aria-busy')).toBe('true');expect(a.text()).toContain('加载中')
  await flushPromises();expect(api.fileBlob).toHaveBeenCalledTimes(1);expect(api.fileBlob).toHaveBeenCalledWith(7,true,{signal:expect.any(AbortSignal)})
  pending.resolve(photo());await flushPromises();expect(a.get('img').attributes('src')).not.toBe(b.get('img').attributes('src'));expect(create).toHaveBeenCalledTimes(2)
  a.unmount();expect(revoke).toHaveBeenCalledTimes(1);expect(b.find('img').exists()).toBe(true)
 })
 it('shows a retry button after timeout instead of silently removing the image',async()=>{
  api.fileBlob.mockRejectedValueOnce({code:'ECONNABORTED'});const wrapper=startPhoto();await flushPromises()
  expect(wrapper.get('.partner-photo').attributes('title')).toBe('加载超时，请重试');expect(wrapper.text()).toContain('图片失败')
  await wrapper.get('button').trigger('click');await flushPromises();expect(api.fileBlob).toHaveBeenCalledTimes(2);expect(wrapper.find('img').exists()).toBe(true)
 })
 it('does not introduce nested buttons when an existing row owns the click target',async()=>{
  api.fileBlob.mockRejectedValue(new Error('temporary'))
  const wrapper=mount(defineComponent({components:{PartnerPhoto},template:'<button aria-label="打开图片与附件"><PartnerPhoto :id="9" :allow-retry="false"/></button>'}));wrappers.push(wrapper);await flushPromises()
  expect(wrapper.findAll('button')).toHaveLength(1);expect(wrapper.text()).toContain('打开重试')
 })
 it('ignores an old id response, releases the previous URL and never creates a URL after unmount',async()=>{
  const old=deferred();api.fileBlob.mockImplementation((id:number)=>id===7?old.promise:Promise.resolve(photo()))
  const wrapper=startPhoto();await flushPromises();await wrapper.setProps({id:8});await flushPromises();const current=wrapper.get('img').attributes('src')
  old.resolve(photo());await flushPromises();expect(wrapper.get('img').attributes('src')).toBe(current);expect(create).toHaveBeenCalledTimes(1)
  wrapper.unmount();expect(revoke).toHaveBeenCalledWith(current)
  const late=deferred();api.fileBlob.mockReturnValue(late.promise);const gone=startPhoto(10);await flushPromises();gone.unmount();late.resolve(photo());await flushPromises();expect(create).toHaveBeenCalledTimes(1)
 })
 it('invalidates a decoded-bad image before manual retry and releases its object URL',async()=>{
  const wrapper=startPhoto();await flushPromises();const original=wrapper.get('img').attributes('src');await wrapper.get('img').trigger('error')
  expect(revoke).toHaveBeenCalledWith(original);expect(wrapper.find('img').exists()).toBe(false);await wrapper.get('button').trigger('click');await flushPromises();expect(api.fileBlob).toHaveBeenCalledTimes(2)
 })
 it('uses the same states for staged images, preserves the public props and retries a non-image Blob',async()=>{
  api.selectionImportImage.mockResolvedValueOnce(new Blob(['wrong'],{type:'application/json'}))
  const wrapper=mount(SelectionImportPhoto,{props:{id:2,jobId:'current-job',imageId:'source-image',name:'源图'},global:{stubs:{ElImage:{props:['src','alt'],template:'<img :src="src" :alt="alt"/>'}}}});wrappers.push(wrapper)
  expect(wrapper.text()).toContain('图片加载中');await flushPromises();expect(wrapper.text()).toContain('加载失败')
  await wrapper.get('button').trigger('click');await flushPromises();expect(api.selectionImportImage).toHaveBeenCalledTimes(2);expect(api.selectionImportImage).toHaveBeenLastCalledWith(2,'current-job','source-image',{signal:expect.any(AbortSignal)});expect(wrapper.get('img').attributes('alt')).toBe('源图')
 })
 it('keeps catalog-source images in their own authorised cache and preserves the same visible failure retry',async()=>{
  api.catalogImportImage.mockRejectedValueOnce(new Error('temporary')).mockResolvedValue(photo())
  const wrapper=mount(SelectionImportPhoto,{props:{id:2,jobId:'current-job',imageId:'source-image',name:'供应商原图',context:'catalog'},global:{stubs:{ElImage:{props:['src','alt'],template:'<img :src="src" :alt="alt"/>'}}}});wrappers.push(wrapper);await flushPromises()
  expect(wrapper.text()).toContain('加载失败');expect(api.selectionImportImage).not.toHaveBeenCalled();await wrapper.get('button').trigger('click');await flushPromises()
  expect(api.catalogImportImage).toHaveBeenCalledTimes(2);expect(wrapper.get('img').attributes('alt')).toBe('供应商原图')
 })
})
