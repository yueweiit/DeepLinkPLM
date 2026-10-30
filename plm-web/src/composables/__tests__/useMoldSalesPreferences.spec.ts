import {defineComponent,nextTick} from 'vue'
import {mount} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {beforeEach,describe,expect,it,vi} from 'vitest'
import {useUserStore} from '@/stores/user'
import {moldSalesPreferenceKey,useMoldSalesPreferences} from '../useMoldSalesPreferences'
function setup(scope='sales'){const pinia=createPinia();setActivePinia(pinia);const user=useUserStore();user.token='token-a';user.profile={userId:12} as never;let prefs!:ReturnType<typeof useMoldSalesPreferences>;const wrapper=mount(defineComponent({setup(){prefs=useMoldSalesPreferences(scope);return()=>null}}),{global:{plugins:[pinia]}});return {user,prefs,wrapper}}
beforeEach(()=>{vi.unstubAllGlobals();localStorage.clear();vi.restoreAllMocks()})
describe('sales display preferences',()=>{
 it('preserves an explicit empty product selection after remount rather than using defaults',()=>{const first=setup();first.prefs.chooseProducts([]);first.prefs.chooseView('detail');first.wrapper.unmount();const next=setup();expect(next.prefs.savedProductLineIds.value).toEqual([]);expect(next.prefs.view.value).toBe('detail');next.wrapper.unmount()})
 it('keeps separate users and production scope isolated and resets in-memory preferences at logout',async()=>{const {user,prefs,wrapper}=setup();prefs.chooseProducts([51]);prefs.chooseView('detail');localStorage.setItem(moldSalesPreferenceKey(13),JSON.stringify({version:1,view:'matrix',productLineIds:[52]}));user.token='token-b';user.profile={userId:13} as never;await nextTick();expect(prefs.savedProductLineIds.value).toEqual([52]);expect(prefs.view.value).toBe('matrix');user.token='';user.profile=null;await nextTick();expect(prefs.savedProductLineIds.value).toBeUndefined();expect(prefs.view.value).toBe('matrix');wrapper.unmount();const production=setup('production');expect(production.prefs.savedProductLineIds.value).toBeUndefined();production.wrapper.unmount()})
 it('does not use an anonymous shared key before the real account identity has restored',async()=>{const {user,prefs,wrapper}=setup();user.profile=null;await nextTick();prefs.chooseProducts([99]);expect(localStorage.length).toBe(0);user.profile={userId:12} as never;await nextTick();expect(prefs.savedProductLineIds.value).toBeUndefined();wrapper.unmount()})
 it('retains unavailable IDs until explicit user edit and rejects malformed stored identities',()=>{localStorage.setItem(moldSalesPreferenceKey(12),JSON.stringify({version:1,view:'matrix',productLineIds:[51,999]}));const a=setup();expect(a.prefs.savedProductLineIds.value).toEqual([51,999]);a.wrapper.unmount();localStorage.setItem(moldSalesPreferenceKey(12),JSON.stringify({version:1,view:'matrix',productLineIds:['51',-1]}));const b=setup();expect(b.prefs.savedProductLineIds.value).toBeUndefined();b.wrapper.unmount()})
 it('continues operating when browser storage is unavailable',()=>{const {prefs,wrapper}=setup();vi.stubGlobal('localStorage',{getItem(){throw new Error('blocked')},setItem(){throw new Error('blocked')}});prefs.chooseProducts([51]);expect(prefs.savedProductLineIds.value).toEqual([51]);expect(prefs.storageAvailable.value).toBe(false);wrapper.unmount();vi.unstubAllGlobals()})
 it('remembers historical sales columns beside real product IDs without inventing business identities',async()=>{const first=setup();first.prefs.chooseProductKeys(['product:48','history:AB:airbag-pintura'],[48]);first.wrapper.unmount();const next=setup();expect(next.prefs.savedProductKeys.value).toEqual(['product:48','history:AB:airbag-pintura']);expect(next.prefs.savedProductLineIds.value).toEqual([48]);next.user.token='';next.user.profile=null;await nextTick();expect(next.prefs.savedProductKeys.value).toBeUndefined();next.wrapper.unmount()})
 it('defaults old preferences to code order and isolates a saved sort by real user',async()=>{
  localStorage.setItem(moldSalesPreferenceKey(12),JSON.stringify({version:1,view:'detail',productLineIds:[51]}));const {prefs,user,wrapper}=setup();
  expect(prefs.sort.value).toBe('phoneCodeDesc');prefs.chooseSort('openedDateDesc');expect(prefs.savedProductLineIds.value).toEqual([51]);
  user.token='other';user.profile={userId:13} as never;await nextTick();expect(prefs.sort.value).toBe('phoneCodeDesc');
  user.token='original';user.profile={userId:12} as never;await nextTick();expect(prefs.sort.value).toBe('openedDateDesc');wrapper.unmount();
 })

})
