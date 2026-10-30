import {DOMWrapper,mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import ElementPlus from 'element-plus'
import {createPinia,setActivePinia} from 'pinia'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import MoldApplicationFile from '../components/MoldApplicationFile.vue'
import {useUserStore} from '@/stores/user'
import {setLanguage} from '@/locales'
import {authorizedBlobs} from '@/utils/authorizedBlobQueue'
const api=vi.hoisted(()=>({downloadMoldFile:vi.fn()}))
const viewer=vi.hoisted(()=>({saveBlob:vi.fn()}))
vi.mock('@/api/modules/moldApplications',()=>api)
vi.mock('@/utils/file',()=>viewer)
let wrapper:VueWrapper
const frameSettings=(window as unknown as {happyDOM:{settings:{disableIframePageLoading:boolean}}}).happyDOM.settings
const iframeLoadingDisabled=frameSettings.disableIframePageLoading
const file=()=>({attachmentId:612,category:'attachment',fileName:'原审批结构图.png',fileSize:8,contentType:'image/png',sha256:'original-sha',downloadStatus:'downloaded'})
beforeEach(()=>{frameSettings.disableIframePageLoading=true;vi.clearAllMocks();authorizedBlobs.clear();localStorage.clear();setActivePinia(createPinia());useUserStore().token='original-login';localStorage.setItem('plm_token','original-login');api.downloadMoldFile.mockResolvedValue(new Blob(['image'],{type:'image/png'}));vi.spyOn(URL,'createObjectURL').mockReturnValue('blob:original-file');vi.spyOn(URL,'revokeObjectURL').mockImplementation(()=>{});vi.spyOn(window,'open').mockReturnValue(null)})
afterEach(()=>{setLanguage('zh-CN');wrapper?.unmount();authorizedBlobs.clear();frameSettings.disableIframePageLoading=iframeLoadingDisabled;vi.restoreAllMocks()})
async function start(value=file()){wrapper=mount(MoldApplicationFile,{props:{applicationId:92381,file:value,active:true,compact:true},global:{plugins:[ElementPlus],stubs:{ElImage:{props:['src','alt'],template:'<img :src="src" :alt="alt"/>'}}}});await flushPromises()}
it('opens the archived original inside the page when popup windows are blocked and downloads with the same original IDs',async()=>{
 await start();expect(api.downloadMoldFile).toHaveBeenCalledWith(92381,612,expect.any(AbortSignal));expect(wrapper.get('img').attributes('src')).toBe('blob:original-file');expect(wrapper.classes()).toContain('application-file--compact');expect(wrapper.text()).toContain('原审批结构图.png');
 await wrapper.findAll('button').find(b=>b.text()==='预览')!.trigger('click');await flushPromises();const dialog=new DOMWrapper(document.body).get('[role=dialog]');expect(dialog.get('img').attributes('src')).toBe('blob:original-file');expect(dialog.text()).toContain('原审批结构图.png');expect(window.open).not.toHaveBeenCalled();expect(api.downloadMoldFile).toHaveBeenCalledOnce();await dialog.findAll('button').find(b=>b.text()==='关闭')!.trigger('click');await flushPromises();expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:original-file');expect(wrapper.find('img').exists()).toBe(true);
 await wrapper.findAll('button').find(b=>b.text()==='下载')!.trigger('click');await flushPromises();expect(api.downloadMoldFile).toHaveBeenLastCalledWith(92381,612);expect(viewer.saveBlob).toHaveBeenCalledWith(expect.any(Blob),'原审批结构图.png');
 expect(wrapper.text()).not.toContain('等待下载授权');
})
it('previews an original PDF inline only when requested and keeps authenticated download available in its dialog',async()=>{
 api.downloadMoldFile.mockResolvedValue(new Blob(['pdf'],{type:'application/pdf'}));await start({...file(),fileName:'原审批测试.pdf',contentType:'application/pdf'});expect(api.downloadMoldFile).not.toHaveBeenCalled();await wrapper.findAll('button').find(b=>b.text()==='预览')!.trigger('click');await flushPromises();const dialog=new DOMWrapper(document.body).get('[role=dialog]');expect(dialog.get('iframe').attributes('src')).toBe('blob:original-file');expect(dialog.get('iframe').attributes('title')).toBe('原审批测试.pdf');expect(api.downloadMoldFile).toHaveBeenCalledWith(92381,612,expect.any(AbortSignal));expect(window.open).not.toHaveBeenCalled();await dialog.findAll('button').find(b=>b.text()==='下载')!.trigger('click');await flushPromises();expect(viewer.saveBlob).toHaveBeenCalledWith(expect.any(Blob),'原审批测试.pdf');await wrapper.setProps({active:false});await flushPromises();expect(new DOMWrapper(document.body).find('iframe').exists()).toBe(false);expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:original-file');
})
it('shows an inline preview error and retries through the original authenticated route',async()=>{
 api.downloadMoldFile.mockRejectedValueOnce(new Error('temporary failure')).mockResolvedValueOnce(new Blob(['pdf'],{type:'application/pdf'}));await start({...file(),fileName:'原审批测试.pdf',contentType:'application/pdf'});await wrapper.findAll('button').find(b=>b.text()==='预览')!.trigger('click');await flushPromises();const dialog=new DOMWrapper(document.body).get('[role=dialog]');expect(dialog.get('[role=alert]').text()).toContain('加载失败');expect(dialog.find('iframe').exists()).toBe(false);await dialog.findAll('button').find(b=>b.text()==='重试预览')!.trigger('click');await flushPromises();expect(dialog.get('iframe').attributes('src')).toBe('blob:original-file');expect(api.downloadMoldFile).toHaveBeenCalledTimes(2);expect(window.open).not.toHaveBeenCalled();
})
it('closes an inline preview and discards its pending original file response after a login change',async()=>{
 let finish!:(blob:Blob)=>void;api.downloadMoldFile.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}));await start({...file(),fileName:'原审批测试.pdf',contentType:'application/pdf'});await wrapper.findAll('button').find(b=>b.text()==='预览')!.trigger('click');await flushPromises();expect(new DOMWrapper(document.body).get('[role=dialog]').text()).toContain('预览加载中');localStorage.setItem('plm_token','new-login');useUserStore().token='new-login';await flushPromises();finish(new Blob(['old-pdf'],{type:'application/pdf'}));await flushPromises();expect(new DOMWrapper(document.body).find('iframe').exists()).toBe(false);expect(URL.createObjectURL).not.toHaveBeenCalled();expect(window.open).not.toHaveBeenCalled();
})
it('distinguishes local pending uploads, legacy remote pending files and actionable archive failures',async()=>{
 await start({...file(),downloadStatus:'pending_upload'});expect(wrapper.text()).toContain('本地文件待上传完成');expect(api.downloadMoldFile).not.toHaveBeenCalled();
 await wrapper.setProps({file:{...file(),downloadStatus:'pending_authorization'}});expect(wrapper.text()).toContain('原审批附件待归档');expect(wrapper.text()).not.toContain('本地文件待上传完成');
 await wrapper.setProps({file:{...file(),downloadStatus:'download_failed',archiveError:'原审批文件下载超时，请重试归档'}});expect(wrapper.get('[role=alert]').text()).toContain('下载超时');expect(wrapper.text()).toContain('归档失败');expect(wrapper.findAll('button')).toHaveLength(0);expect(api.downloadMoldFile).not.toHaveBeenCalled();
})
it('discards a late response from the previous login and releases the displayed image when closed',async()=>{
 let oldResolve!:(b:Blob)=>void;api.downloadMoldFile.mockImplementationOnce(()=>new Promise(resolve=>{oldResolve=resolve}));await start();
 localStorage.setItem('plm_token','new-login');useUserStore().token='new-login';await flushPromises();oldResolve(new Blob(['old-image'],{type:'image/png'}));await flushPromises();
 expect(api.downloadMoldFile).toHaveBeenCalledTimes(2);expect(URL.createObjectURL).toHaveBeenCalledTimes(1);expect(wrapper.get('img').attributes('src')).toBe('blob:original-file');await wrapper.setProps({active:false});await flushPromises();expect(wrapper.find('img').exists()).toBe(false);expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:original-file');
})

it('translates the archive status and actions without translating source filenames or refetching images',async()=>{
 await start();const calls=api.downloadMoldFile.mock.calls.length;setLanguage('es');await flushPromises();expect(wrapper.text()).toContain('Archivado');expect(wrapper.text()).toContain('Vista previa');expect(wrapper.text()).toContain('原审批结构图.png');expect(api.downloadMoldFile).toHaveBeenCalledTimes(calls);setLanguage('zh-CN');await flushPromises();expect(wrapper.text()).toContain('已归档')
})
