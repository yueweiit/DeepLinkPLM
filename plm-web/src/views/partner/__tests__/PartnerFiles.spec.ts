import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {ref} from 'vue'
import {createPinia,setActivePinia} from 'pinia'
import ElementPlus,{ElCheckbox,ElDialog,ElPagination} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import PartnerFiles from '../PartnerFiles.vue'
import PartnerFilePreview from '../PartnerFilePreview.vue'
import {authorizedBlobs} from '@/utils/authorizedBlobQueue'
import {useUserStore} from '@/stores/user'
const api=vi.hoisted(()=>({uploadPartnerFile:vi.fn(),fileBlob:vi.fn(),saveBlob:vi.fn(),metadata:vi.fn()}))
const measuredWidth=ref(980)
vi.mock('@vueuse/core',()=>({useElementSize:()=>({width:measuredWidth})}))
vi.mock('@/api/modules/partner',()=>api)
vi.mock('@/api/modules/attachment',()=>({getAttachmentPreviewMetadata:api.metadata}))
vi.mock('../PartnerPhoto.vue',()=>({default:{props:['id'],template:'<img class="partner-photo" :data-file-id="id" alt="已有图片"/>'}}))
let wrapper:VueWrapper
const files=Array.from({length:7},(_,n)=>({id:n+1,name:`原附件-${n+1}.png`,version:`V${n+1}`,type:n%2?'application/pdf':'image/png',size:128}))
const originalCreate=Object.getOwnPropertyDescriptor(URL,'createObjectURL'),originalRevoke=Object.getOwnPropertyDescriptor(URL,'revokeObjectURL')
const create=vi.fn(()=>`blob:preview-${Math.random()}`),revoke=vi.fn()
function start(props:Record<string,unknown>={}){wrapper=mount(PartnerFiles,{props:{kind:'partner-products',id:7,files,modelValue:[{id:7,type:'file',name:'原附件-7.png',version:'V7'}],canWrite:true,canWriteMoney:false,compact:true,...props},global:{plugins:[ElementPlus]}});return wrapper}
beforeEach(()=>{authorizedBlobs.clear();setActivePinia(createPinia());useUserStore().token='qa-preview';localStorage.setItem('plm_token','qa-preview');api.metadata.mockReset().mockImplementation((attachmentId:number)=>Promise.resolve({attachmentId,previewable:true,previewType:'image',previewStatus:'ready'}));Object.defineProperty(URL,'createObjectURL',{configurable:true,value:create});Object.defineProperty(URL,'revokeObjectURL',{configurable:true,value:revoke})})
afterEach(async()=>{wrapper?.unmount();authorizedBlobs.clear();await flushPromises();measuredWidth.value=980;vi.clearAllMocks();if(originalCreate)Object.defineProperty(URL,'createObjectURL',originalCreate);if(originalRevoke)Object.defineProperty(URL,'revokeObjectURL',originalRevoke)})
describe('compact visible product materials',()=>{
 it('shows real thumbnails, names and versions directly, with pagination instead of an unbounded material wall',async()=>{
  start();expect(wrapper.findAll('.file-row')).toHaveLength(3);expect(wrapper.get('.file-name').text()).toBe('原附件-1.png · V1');expect(wrapper.get('[data-file-id="1"]').attributes('alt')).toBe('已有图片')
  const pager=wrapper.getComponent(ElPagination);expect(pager.props('total')).toBe(7);expect(pager.props('pageSize')).toBe(3)
  pager.vm.$emit('update:current-page',3);await flushPromises();expect(wrapper.findAll('.file-row')).toHaveLength(1);expect(wrapper.text()).toContain('原附件-7.png · V7')
  await wrapper.setProps({files:files.slice(0,2)});await flushPromises();expect(wrapper.text()).toContain('原附件-1.png · V1')
 })
 it('expands only low-frequency upload settings and preserves adopted IDs outside the material page',async()=>{
  start();expect(wrapper.find('[aria-label="上传资料类别"]').exists()).toBe(false)
  await wrapper.findAll('button').find(b=>b.text()==='上传设置')!.trigger('click');expect(wrapper.find('[aria-label="上传资料类别"]').exists()).toBe(true);expect(wrapper.text()).toContain('上传新版')
  wrapper.getComponent(ElCheckbox).vm.$emit('change',true);await flushPromises();expect(wrapper.emitted('update:modelValue')?.[0][0]).toEqual([{id:7,type:'file',name:'原附件-7.png',version:'V7'},{id:1,type:'file',name:'原附件-1.png',version:'V1'}]);expect(api.uploadPartnerFile).not.toHaveBeenCalled()
 })
 it('keeps read-only file evidence visible without exposing adoption or upload controls',()=>{
  start({canWrite:false});expect(wrapper.text()).toContain('原附件-1.png · V1');expect(wrapper.text()).toContain('预览');expect(wrapper.text()).toContain('下载');expect(wrapper.findComponent(ElCheckbox).exists()).toBe(false);expect(wrapper.text()).not.toContain('上传设置');expect(wrapper.text()).not.toContain('上传图片')
 })
 it('uses an honest read-only image empty state without asking a catalog viewer to upload',()=>{
  start({canWrite:false,files:[{id:91,name:'本款参数.pdf',version:'V1',type:'application/pdf',size:99}]});expect(wrapper.get('.no-photo').text()).toContain('暂无图片');expect(wrapper.get('.no-photo').text()).not.toContain('请在本处上传');expect(wrapper.text()).toContain('本款参数.pdf')
 })
 it('shows identity-level aggregate attachments read-only and never invents a SY upload owner',async()=>{start({kind:'sample-identities',id:71,canWrite:true});expect(wrapper.text()).toContain('原附件-1.png · V1');expect(wrapper.text()).toContain('预览');expect(wrapper.text()).toContain('下载');expect(wrapper.text()).not.toContain('上传图片');expect(wrapper.findComponent(ElCheckbox).exists()).toBe(false);const file=new File(['data'],'新图片.png',{type:'image/png'});await wrapper.get('.partner-files').trigger('drop',{dataTransfer:{files:[file]}});expect(api.uploadPartnerFile).not.toHaveBeenCalled()})
 it('keeps the existing six-file mode and version fields for other callers',()=>{
  start({compact:false});expect(wrapper.findAll('.file-row')).toHaveLength(6);expect(wrapper.getComponent(ElPagination).props('pageSize')).toBe(6);expect(wrapper.find('[aria-label="上传资料类别"]').exists()).toBe(true)
 })
 it('keeps compact materials to one visible row as the available width changes',async()=>{
  start();measuredWidth.value=620;await flushPromises();expect(wrapper.findAll('.file-row')).toHaveLength(2);expect(wrapper.getComponent(ElPagination).props('total')).toBe(7)
  measuredWidth.value=350;await flushPromises();expect(wrapper.findAll('.file-row')).toHaveLength(1);expect(wrapper.getComponent(ElPagination).props('pageSize')).toBe(1)
 })
 it('reuses the upload endpoint for dropped images and rejects drops in read-only mode',async()=>{
  start();const file=new File(['image'],'现场实拍.png',{type:'image/png'});api.uploadPartnerFile.mockResolvedValue({id:99});await wrapper.get('.partner-files').trigger('drop',{dataTransfer:{files:[file]}});await flushPromises()
  expect(api.uploadPartnerFile).toHaveBeenCalledWith('partner-products',7,file,expect.objectContaining({purpose:'photo',version:'V1'}));expect(wrapper.emitted('changed')).toHaveLength(1)
  await wrapper.setProps({canWrite:false});await wrapper.get('.partner-files').trigger('drop',{dataTransfer:{files:[file]}});expect(api.uploadPartnerFile).toHaveBeenCalledTimes(1)
 })
})
describe('full-size preview feedback',()=>{
 it('treats Office as an unsupported format and downloads the exact original only on request',async()=>{
  const file={id:901,name:'莫凡-现有两行精确补图.xlsx',version:'V1',type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',size:272099}
  api.metadata.mockResolvedValueOnce({attachmentId:901,previewable:false,previewType:'office',previewStatus:'unsupported'})
  const original=new Blob(['original workbook'],{type:file.type});api.fileBlob.mockResolvedValue(original)
  start({files:[file]});await wrapper.findAll('button').find(button=>button.text()==='预览')!.trigger('click');await flushPromises()
  expect(api.metadata).toHaveBeenCalledWith(901,{signal:expect.any(AbortSignal)});expect(api.fileBlob).not.toHaveBeenCalled();expect(document.body.textContent).toContain('此格式请下载查看');expect(document.body.textContent).not.toContain('重新加载')
  ;[...document.body.querySelectorAll('button')].find(button=>button.textContent?.includes('下载原文件'))!.click();await flushPromises()
  expect(api.fileBlob).toHaveBeenCalledWith(901,false,{signal:expect.any(AbortSignal)});expect(api.saveBlob).toHaveBeenCalledWith(original,file.name)
 })
 it('keeps an unsupported original download failure actionable without retrying preview',async()=>{
  api.metadata.mockResolvedValueOnce({attachmentId:901,previewable:false,previewType:'office',previewStatus:'unsupported'})
  api.fileBlob.mockRejectedValueOnce(new Error('下载连接中断')).mockResolvedValueOnce(new Blob(['original workbook']))
  start({files:[{id:901,name:'原件.xlsx',version:'V1',type:'application/octet-stream'}]});await wrapper.findAll('button').find(button=>button.text()==='预览')!.trigger('click');await flushPromises()
  const download=()=>[...document.body.querySelectorAll('button')].find(button=>button.textContent?.includes('下载原文件'))!
  download().click();await flushPromises();expect(document.body.textContent).toContain('原件下载失败：下载连接中断');expect(api.saveBlob).not.toHaveBeenCalled()
  download().click();await flushPromises();expect(api.fileBlob.mock.calls.every(call=>call[1]===false)).toBe(true);expect(api.saveBlob).toHaveBeenCalledTimes(1)
 })
 it('keeps metadata permission errors as errors and never falls back to preview or download',async()=>{
  api.metadata.mockRejectedValueOnce(new Error('无权查看此附件')).mockResolvedValueOnce({attachmentId:901,previewable:false,previewType:'office',previewStatus:'unsupported'})
  start({files:[{id:901,name:'原件.xlsx',version:'V1',type:'application/octet-stream'}]});await wrapper.findAll('button').find(button=>button.text()==='预览')!.trigger('click');await flushPromises()
  expect(document.body.textContent).toContain('无权查看此附件');expect(document.body.textContent).not.toContain('下载原文件');expect(api.fileBlob).not.toHaveBeenCalled()
  ;[...document.body.querySelectorAll('button')].find(button=>button.textContent?.includes('重新加载'))!.click();await flushPromises()
  expect(api.metadata).toHaveBeenCalledTimes(2);expect(api.fileBlob).not.toHaveBeenCalled();expect(document.body.textContent).toContain('此格式请下载查看')
 })
 it('ignores a replaced metadata response and only previews the newly selected file',async()=>{
  let resolve!:(value:unknown)=>void;api.metadata.mockReturnValueOnce(new Promise(yes=>{resolve=yes}));api.fileBlob.mockResolvedValue(new Blob(['image'],{type:'image/png'}))
  start({files:[{id:901,name:'旧原件.xlsx',version:'V1',type:'application/octet-stream'},{id:902,name:'当前图片.png',version:'V1',type:'image/png'}]});await wrapper.findAll('button').find(button=>button.text()==='预览')!.trigger('click');await flushPromises()
  const signal=api.metadata.mock.calls[0][1].signal as AbortSignal
  ;[...document.body.querySelectorAll('button')].find(button=>button.textContent?.includes('下一张'))!.click();await flushPromises();expect(signal.aborted).toBe(true)
  resolve({attachmentId:901,previewable:false,previewType:'office'});await flushPromises();expect(document.body.querySelector('.preview-image')).not.toBeNull();expect(document.body.textContent).not.toContain('下载原文件');expect(api.fileBlob.mock.calls.every(call=>call[0]===902)).toBe(true)
 })
 it('aborts metadata on close and does not start the late binary preview',async()=>{
  let resolve!:(value:unknown)=>void;api.metadata.mockReturnValueOnce(new Promise(yes=>{resolve=yes}))
  start();await wrapper.findAll('button').find(button=>button.text()==='预览')!.trigger('click');await flushPromises();const signal=api.metadata.mock.calls[0][1].signal as AbortSignal
  wrapper.getComponent(ElDialog).vm.$emit('close');await flushPromises();expect(signal.aborted).toBe(true)
  resolve({attachmentId:1,previewable:true,previewType:'image'});await flushPromises();expect(api.fileBlob).not.toHaveBeenCalled();expect(create).not.toHaveBeenCalled()
 })
 it('discards metadata after logout without starting another account request',async()=>{
  let resolve!:(value:unknown)=>void;api.metadata.mockReturnValueOnce(new Promise(yes=>{resolve=yes}))
  start();await wrapper.findAll('button').find(button=>button.text()==='预览')!.trigger('click');await flushPromises();const signal=api.metadata.mock.calls[0][1].signal as AbortSignal
  useUserStore().token='';localStorage.removeItem('plm_token');await flushPromises();expect(signal.aborted).toBe(true)
  resolve({attachmentId:1,previewable:true,previewType:'image'});await flushPromises();expect(api.metadata).toHaveBeenCalledTimes(1);expect(api.fileBlob).not.toHaveBeenCalled()
 })
 it('cancels an original download when its preview closes and never saves a late response',async()=>{
  let resolve!:(blob:Blob)=>void;api.metadata.mockResolvedValueOnce({attachmentId:901,previewable:false,previewType:'office',previewStatus:'unsupported'});api.fileBlob.mockReturnValueOnce(new Promise<Blob>(yes=>{resolve=yes}))
  start({files:[{id:901,name:'原件.xlsx',version:'V1',type:'application/octet-stream'}]});await wrapper.findAll('button').find(button=>button.text()==='预览')!.trigger('click');await flushPromises()
  ;[...document.body.querySelectorAll('button')].find(button=>button.textContent?.includes('下载原文件'))!.click();await flushPromises();const signal=api.fileBlob.mock.calls[0][2].signal as AbortSignal
  wrapper.getComponent(ElDialog).vm.$emit('close');await flushPromises();expect(signal.aborted).toBe(true)
  resolve(new Blob(['original workbook']));await flushPromises();expect(api.saveBlob).not.toHaveBeenCalled()
 })
 it.each(['application/pdf','text/plain'])('keeps the existing supported %s document preview',async(type)=>{
  api.fileBlob.mockResolvedValue(new Blob(['document'],{type}));start();await wrapper.findAll('button').find(button=>button.text()==='预览')!.trigger('click');await flushPromises()
  expect(document.body.querySelector('.preview-frame')?.getAttribute('src')).toMatch(/^blob:preview-/);expect(document.body.textContent).not.toContain('下载原文件')
 })
 it('opens the dialog immediately while a slow original is loading, before any Blob arrives',async()=>{
  let resolve!:(blob:Blob)=>void;api.fileBlob.mockReturnValue(new Promise<Blob>(yes=>{resolve=yes}))
  start();await wrapper.findAll('button').find(button=>button.text()==='预览')!.trigger('click')
  expect(wrapper.getComponent(ElDialog).props('modelValue')).toBe(true);expect(document.body.textContent).toContain('正在加载预览，请稍候')
  resolve(new Blob(['image'],{type:'image/png'}));await flushPromises();expect(document.body.querySelector('.preview-image')?.getAttribute('src')).toMatch(/^blob:preview-/)
 })
 it('keeps a failed dialog visible and allows an explicit retry instead of a silent empty click',async()=>{
  api.fileBlob.mockRejectedValueOnce({code:'ECONNABORTED'}).mockResolvedValueOnce(new Blob(['image'],{type:'image/png'}))
  start();await wrapper.findAll('button').find(button=>button.text()==='预览')!.trigger('click');await flushPromises()
  expect(wrapper.getComponent(ElDialog).props('modelValue')).toBe(true);expect(document.body.textContent).toContain('加载超时，请重试')
  const retry=[...document.body.querySelectorAll('button')].find(button=>button.textContent?.includes('重新加载'))!;retry.click();await flushPromises()
  expect(api.fileBlob).toHaveBeenCalledTimes(2);expect(document.body.querySelector('.preview-image')).not.toBeNull()
 })
 it('cancels a closed preview and does not display or allocate its late image URL',async()=>{
  let resolve!:(blob:Blob)=>void;api.fileBlob.mockReturnValue(new Promise<Blob>(yes=>{resolve=yes}))
  start();await wrapper.findAll('button').find(button=>button.text()==='预览')!.trigger('click');await flushPromises()
  const signal=api.fileBlob.mock.calls[0][2].signal as AbortSignal
  wrapper.getComponent(ElDialog).vm.$emit('close');await flushPromises();expect(signal.aborted).toBe(true);expect(wrapper.findComponent(PartnerFilePreview).exists()).toBe(false)
  resolve(new Blob(['image'],{type:'image/png'}));await flushPromises();expect(create).not.toHaveBeenCalled()
 })
 it('closes the old object preview on business-id change and releases a displayed URL',async()=>{
  api.fileBlob.mockResolvedValue(new Blob(['image'],{type:'image/png'}));start();await wrapper.findAll('button').find(button=>button.text()==='预览')!.trigger('click');await flushPromises()
  const src=document.body.querySelector('.preview-image')!.getAttribute('src');await wrapper.setProps({id:8});await flushPromises()
  expect(wrapper.findComponent(PartnerFilePreview).exists()).toBe(false);expect(revoke).toHaveBeenCalledWith(src)
 })
})
