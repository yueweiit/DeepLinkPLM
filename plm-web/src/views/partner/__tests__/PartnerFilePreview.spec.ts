import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import ElementPlus,{ElDialog} from 'element-plus'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import PartnerFilePreview from '../PartnerFilePreview.vue'
import {authorizedBlobs} from '@/utils/authorizedBlobQueue'
import {useUserStore} from '@/stores/user'
const api=vi.hoisted(()=>({fileBlob:vi.fn(),saveBlob:vi.fn(),metadata:vi.fn()}))
vi.mock('@/api/modules/partner',()=>({fileBlob:api.fileBlob,saveBlob:api.saveBlob}))
vi.mock('@/api/modules/attachment',()=>({getAttachmentPreviewMetadata:api.metadata}))
let wrapper:VueWrapper,pinia:ReturnType<typeof createPinia>,sequence=0
const files=[{id:71,name:'86bb0360-3202-4e76-9885-32aaa7c123ef.png',version:'V3',type:'image/png',size:21},{id:72,name:'second-original.png',version:'V2',type:'image/png',size:22}]
const originalCreate=Object.getOwnPropertyDescriptor(URL,'createObjectURL'),originalRevoke=Object.getOwnPropertyDescriptor(URL,'revokeObjectURL')
const create=vi.fn(()=>`blob:gallery-${++sequence}`),revoke=vi.fn()
const photo=()=>new Blob(['image'],{type:'image/png'})
const button=(text:string)=>[...document.body.querySelectorAll('button')].find(item=>item.textContent?.trim()===text)!
function start(props:Record<string,unknown>={}){wrapper=mount(PartnerFilePreview,{props:{file:files[1],files,title:'支架手机壳',...props},global:{plugins:[pinia,ElementPlus]}});return wrapper}
beforeEach(()=>{vi.clearAllMocks();api.metadata.mockReset().mockImplementation((id:number)=>Promise.resolve({attachmentId:id,previewable:true,previewStatus:'ready',previewType:'image'}));api.fileBlob.mockReset().mockResolvedValue(photo());sequence=0;authorizedBlobs.clear();pinia=createPinia();setActivePinia(pinia);useUserStore().token='qa-gallery';localStorage.setItem('plm_token','qa-gallery');Object.defineProperty(URL,'createObjectURL',{configurable:true,value:create});Object.defineProperty(URL,'revokeObjectURL',{configurable:true,value:revoke})})
afterEach(async()=>{wrapper?.unmount();authorizedBlobs.clear();await flushPromises();if(originalCreate)Object.defineProperty(URL,'createObjectURL',originalCreate);if(originalRevoke)Object.defineProperty(URL,'revokeObjectURL',originalRevoke);localStorage.clear()})
it('loads the clicked second file directly and uses the business title and counter instead of an original UUID filename',async()=>{
 start();await flushPromises();expect(api.metadata).toHaveBeenCalledWith(72,{signal:expect.any(AbortSignal)});expect(api.fileBlob).toHaveBeenCalledWith(72,true,{signal:expect.any(AbortSignal)})
 expect(wrapper.getComponent(ElDialog).props('title')).toBe('支架手机壳 · 2 / 2');expect(document.body.querySelector('.preview-image')).not.toBeNull();expect(document.body.querySelector('.preview-file-info')?.hasAttribute('open')).toBe(false)
 expect(document.body.querySelector('.original-file-name')?.textContent).toBe('second-original.png');expect(document.body.textContent).toContain('V2')
})
it('navigates images by their real IDs, releases the previous URL and preserves the supplied files',async()=>{
 const before=JSON.stringify(files);start();await flushPromises();const previous=document.body.querySelector('.preview-image')!.getAttribute('src')
 button('上一张').click();await flushPromises();expect(api.fileBlob).toHaveBeenLastCalledWith(71,true,{signal:expect.any(AbortSignal)});expect(revoke).toHaveBeenCalledWith(previous)
 expect(wrapper.getComponent(ElDialog).props('title')).toBe('支架手机壳 · 1 / 2');expect(button('上一张').disabled).toBe(true);expect(JSON.stringify(files)).toBe(before)
})
it('keeps a clicked image preview open during loading and retries a network failure in the same layer',async()=>{
 let resolve!:(blob:Blob)=>void;api.fileBlob.mockReturnValueOnce(new Promise<Blob>(yes=>{resolve=yes}));start();await flushPromises()
 expect(document.body.textContent).toContain('正在加载预览，请稍候');expect(wrapper.findAllComponents(ElDialog)).toHaveLength(1)
 resolve(photo());await flushPromises();(document.body.querySelector('.preview-image') as HTMLImageElement).dispatchEvent(new Event('error'));await flushPromises()
 expect(document.body.textContent).toContain('图片无法显示，请重试');button('重新加载').click();await flushPromises()
 expect(api.fileBlob).toHaveBeenCalledTimes(2);expect(document.body.querySelector('.preview-image')).not.toBeNull();expect(wrapper.findAllComponents(ElDialog)).toHaveLength(1)
})
it('ignores a late image after switching and retains the currently selected picture',async()=>{
 let resolve!:(blob:Blob)=>void;api.fileBlob.mockReturnValueOnce(new Promise<Blob>(yes=>{resolve=yes}));start();await flushPromises();const signal=api.fileBlob.mock.calls[0][2].signal as AbortSignal
 button('上一张').click();await flushPromises();expect(signal.aborted).toBe(true);const src=document.body.querySelector('.preview-image')!.getAttribute('src')
 resolve(photo());await flushPromises();expect(document.body.querySelector('.preview-image')!.getAttribute('src')).toBe(src);expect(create).toHaveBeenCalledTimes(1)
})
it('shows original information on demand and downloads using the unchanged original name',async()=>{
 const original=new Blob(['original image'],{type:'image/png'});api.fileBlob.mockImplementation((id:number,preview:boolean)=>Promise.resolve(preview?photo():original));start({file:files[0]});await flushPromises()
 const summary=document.body.querySelector('.preview-file-info>summary') as HTMLElement;summary.click();await flushPromises();expect(document.body.querySelector('.original-file-name')?.textContent).toBe(files[0].name)
 button('下载原文件').click();await flushPromises();expect(api.fileBlob).toHaveBeenLastCalledWith(71,false,{signal:expect.any(AbortSignal)});expect(api.saveBlob).toHaveBeenCalledWith(original,files[0].name)
})
it('retains permission failures without silently falling back to binary preview or download',async()=>{
 api.metadata.mockRejectedValueOnce(Error('无权查看此附件'));start();await flushPromises();expect(document.body.textContent).toContain('无权查看此附件');expect(api.fileBlob).not.toHaveBeenCalled();expect(button('下载原文件')).toBeUndefined()
})
it('renders a single text attachment through the same renderer without image navigation',async()=>{
 const file={id:90,name:'说明.txt',version:'V1',type:'text/plain',size:12};api.metadata.mockResolvedValueOnce({attachmentId:90,previewable:true,previewStatus:'ready',previewType:'text'});api.fileBlob.mockResolvedValueOnce(new Blob(['instructions'],{type:'text/plain'}));start({file,files:[file],title:'附件预览'});await flushPromises()
 expect(wrapper.getComponent(ElDialog).props('title')).toBe('附件预览 · 1 / 1');expect(document.body.querySelector('.preview-frame')).not.toBeNull();expect(document.body.querySelector('.preview-nav')).toBeNull()
})
