import {defineComponent,h} from 'vue'
import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {useClientUpdate} from '../useClientUpdate'
let w:VueWrapper|undefined,script:HTMLScriptElement
const fetchMock=vi.fn()
const app=defineComponent({setup(){const version=useClientUpdate();return()=>h('div',[h('input',{value:'未保存的手机型号'}),h('span',String(version.available.value))])}})
beforeEach(()=>{(window as unknown as {happyDOM:{settings:{disableJavaScriptFileLoading:boolean}}}).happyDOM.settings.disableJavaScriptFileLoading=true;vi.useFakeTimers();vi.setSystemTime(0);script=document.createElement('script');script.type='module';script.src='/assets/index-old.js';document.head.append(script);vi.stubGlobal('fetch',fetchMock);fetchMock.mockReset();fetchMock.mockResolvedValue({ok:true,text:async()=>'<script type="module" src="/assets/index-old.js"></script>'})})
afterEach(()=>{w?.unmount();w=undefined;script.remove();vi.unstubAllGlobals();vi.useRealTimers()})
describe('loaded client version notice',()=>{
  it('checks the published entry without replacing an unsaved form and notices a later release',async()=>{
    w=mount(app);await flushPromises();expect(fetchMock).toHaveBeenCalledTimes(1);expect(w.text()).toBe('false')
    fetchMock.mockResolvedValue({ok:true,text:async()=>'<script type="module" src="/assets/index-new.js"></script>'})
    await vi.advanceTimersByTimeAsync(60_000);await flushPromises();expect(w.text()).toBe('true');expect(w.get('input').element.value).toBe('未保存的手机型号')
    expect(fetchMock.mock.calls[0]![1]).toMatchObject({cache:'no-store'})
  })
  it('throttles focus checks and ignores unavailable or cross-origin release information',async()=>{
    w=mount(app);await flushPromises();window.dispatchEvent(new Event('focus'));await flushPromises();expect(fetchMock).toHaveBeenCalledTimes(1)
    fetchMock.mockResolvedValue({ok:true,text:async()=>'<script type="module" src="https://outside.example/assets/index-new.js"></script>'});await vi.advanceTimersByTimeAsync(60_000);await flushPromises();expect(w.text()).toBe('false')
    fetchMock.mockRejectedValue(new Error('offline'));await vi.advanceTimersByTimeAsync(60_000);await flushPromises();expect(w.get('input').element.value).toBe('未保存的手机型号');expect(w.text()).toBe('false')
  })
  it('aborts pending discovery and removes interval and listeners when leaving the app layout',async()=>{
    fetchMock.mockReturnValue(new Promise(()=>{}));w=mount(app);await flushPromises();const signal=fetchMock.mock.calls[0]![1].signal as AbortSignal
    w.unmount();w=undefined;expect(signal.aborted).toBe(true);window.dispatchEvent(new Event('focus'));await vi.advanceTimersByTimeAsync(120_000);expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
