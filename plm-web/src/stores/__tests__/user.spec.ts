import { AxiosHeaders, type AxiosResponse } from 'axios'
import { createPinia, disposePinia, setActivePinia, type Pinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { unwrapResponse, type ApiResponse } from '@/api/request'
import { useUserStore } from '../user'
import { currentLanguage, setLanguage } from '@/locales'
import { useCollaborationWorkspace } from '../collaborationWorkspace'
import { useFinanceWorkspace } from '../financeWorkspace'
import { editFinanceCell } from '@/views/development/financeDrafts'
import type { CollaborationRow } from '@/types/collaboration'
const auth = vi.hoisted(() => ({ loginByPassword:vi.fn(),loginByDingTalkCode:vi.fn(),getProfileByToken:vi.fn(),logout:vi.fn(),saveOwnLanguage:vi.fn() }))
vi.mock('@/api/modules/auth', () => auth)
const session = (token:string) => ({ token,profile:{userId:1,userName:token,roleName:'测试',department:'测试'},permissions:['finance:write'] })
function expired(token:string):AxiosResponse<ApiResponse<null>> { return {data:{code:40101,message:'会话失效',data:null},status:200,statusText:'OK',headers:new AxiosHeaders(),config:{headers:new AxiosHeaders({Authorization:`Bearer ${token}`})}} }
let pinia:Pinia
beforeEach(() => { vi.clearAllMocks();localStorage.clear();pinia=createPinia();setActivePinia(pinia);auth.loginByPassword.mockResolvedValue(session('old'));auth.logout.mockResolvedValue(undefined) })
afterEach(() => { disposePinia(pinia);localStorage.clear();window.location.hash='';setLanguage('zh-CN') })
describe('one real Pinia session invalidation', () => {
  it.each([502,503,'network'])('retains the same session and unsaved work on temporary profile failure %s, then retries restoration',async status=>{
    const user=useUserStore();await user.login({username:'test',password:'test-only'})
    const collaboration=useCollaborationWorkspace(),finance=useFinanceWorkspace().forEntity('budget')
    collaboration.drafts.set({itemId:1,notes:null} as CollaborationRow,'notes','unsaved-original')
    editFinanceCell(finance.drafts,1,{budgetId:1,currencyCode:'CNY',moldCost:null},'moldCost','0')
    const failure=status==='network'?new Error('Network Error'):{response:{status}}
    auth.getProfileByToken.mockRejectedValueOnce(failure)
    await expect(user.restore()).rejects.toBe(failure)
    expect(user.token).toBe('old');expect(localStorage.getItem('plm_token')).toBe('old')
    expect(user.profile?.userName).toBe('old');expect(user.permissions).toEqual(['finance:write'])
    expect(collaboration.drafts.rows.size).toBe(1);expect(finance.drafts.size).toBe(1)
    auth.getProfileByToken.mockResolvedValue({...session('old'),profile:{...session('old').profile,userName:'恢复后的同一账号'}})
    await user.restore();expect(user.profile?.userName).toBe('恢复后的同一账号')
    expect(user.token).toBe('old');expect(collaboration.drafts.rows.size).toBe(1);expect(finance.drafts.size).toBe(1)
  })
  it('still invalidates a real current-session typed 401 during profile restoration',async()=>{
    const user=useUserStore();await user.login({username:'test',password:'test-only'})
    auth.getProfileByToken.mockImplementation(async()=>unwrapResponse(expired('old')))
    await expect(user.restore()).rejects.toThrow('会话失效')
    expect(user.token).toBe('');expect(user.profile).toBeNull();expect(localStorage.getItem('plm_token')).toBeNull()
  })
  it('uses each account default and saves a language switch without clearing its business drafts',async()=>{
    setLanguage('es');const user=useUserStore();await user.login({username:'test',password:'test-only'})
    expect(currentLanguage.value).toBe('zh-CN')
    const collaboration=useCollaborationWorkspace()
    collaboration.drafts.set({itemId:1,notes:null}as CollaborationRow,'notes','unsaved-original')
    auth.saveOwnLanguage.mockResolvedValue({userId:1,preferredLanguage:'es'})
    await user.changeLanguage('es')
    expect(auth.saveOwnLanguage).toHaveBeenCalledWith('es','zh-CN')
    expect(currentLanguage.value).toBe('es');expect(user.profile?.preferredLanguage).toBe('es')
    expect(collaboration.drafts.rows.size).toBe(1);expect(user.token).toBe('old')
    auth.saveOwnLanguage.mockRejectedValue(new Error('conflict'))
    await expect(user.changeLanguage('zh-CN')).rejects.toThrow('conflict')
    expect(currentLanguage.value).toBe('es');expect(collaboration.drafts.rows.size).toBe(1)
    auth.loginByPassword.mockResolvedValue({...session('other'),profile:{...session('other').profile,preferredLanguage:'zh-CN'}})
    await user.login({username:'other',password:'test-only'});expect(currentLanguage.value).toBe('zh-CN')
  })
  it('restores the server language and ignores a prior account language response after another login',async()=>{
    const user=useUserStore();await user.login({username:'test',password:'test-only'})
    auth.getProfileByToken.mockResolvedValue({...session('old'),profile:{...session('old').profile,preferredLanguage:'es'}})
    await user.restore();expect(currentLanguage.value).toBe('es')
    let resolve!:(result:{userId:number;preferredLanguage:'zh-CN'})=>void
    auth.saveOwnLanguage.mockReturnValue(new Promise(r=>{resolve=r}));const pending=user.changeLanguage('zh-CN')
    auth.loginByPassword.mockResolvedValue({...session('new'),profile:{...session('new').profile,preferredLanguage:'es'}})
    await user.login({username:'new',password:'test-only'});resolve({userId:1,preferredLanguage:'zh-CN'});await pending
    expect(user.token).toBe('new');expect(currentLanguage.value).toBe('es');expect(user.profile?.preferredLanguage).toBe('es')
  })
  it('clears profile, permissions and both existing draft workspaces on a current-session 401',async()=>{
    const user=useUserStore();await user.login({username:'test',password:'test-only'})
    const collaboration=useCollaborationWorkspace(),finance=useFinanceWorkspace().forEntity('budget')
    collaboration.drafts.set({itemId:1,notes:null} as CollaborationRow,'notes','previous-session')
    editFinanceCell(finance.drafts,1,{budgetId:1,currencyCode:'CNY',moldCost:null},'moldCost','0')
    window.location.hash='/molds';expect(()=>unwrapResponse(expired('old'))).toThrow('会话失效')
    expect(user.token).toBe('');expect(user.profile).toBeNull();expect(user.permissions).toEqual([]);expect(user.isLoggedIn).toBe(false);expect(localStorage.getItem('plm_token')).toBeNull()
    expect(collaboration.drafts.rows.size).toBe(0);expect(finance.drafts.size).toBe(0);expect(window.location.hash).toBe('#/login?redirect=%2Fmolds')
    auth.loginByPassword.mockResolvedValue(session('again'));await user.login({username:'test',password:'test-only'})
    window.location.hash='/dashboard';expect(()=>unwrapResponse(expired('again'))).toThrow();expect(user.isLoggedIn).toBe(false);expect(window.location.hash).toContain('#/login?redirect=')
  })
  it('preserves a later login and its drafts when old 401, restore or logout responses arrive',async()=>{
    const user=useUserStore();await user.login({username:'test',password:'test-only'})
    let rejectRestore!:(error:Error)=>void
    auth.getProfileByToken.mockReturnValue(new Promise((_resolve,reject)=>{rejectRestore=reject}))
    const restoring=user.restore();auth.loginByPassword.mockResolvedValue(session('new'));await user.login({username:'test',password:'test-only'})
    const finance=useFinanceWorkspace().forEntity('budget');editFinanceCell(finance.drafts,1,{budgetId:1,currencyCode:'CNY',moldCost:null},'moldCost','0')
    window.location.hash='/dashboard';expect(()=>unwrapResponse(expired('old'))).toThrow();rejectRestore(new Error('old restore expired'));await expect(restoring).rejects.toThrow()
    expect(user.token).toBe('new');expect(user.profile?.userName).toBe('new');expect(user.permissions).toEqual(['finance:write']);expect(finance.drafts.size).toBe(1);expect(window.location.hash).toBe('#/dashboard')
    let rejectLogout!:(error:Error)=>void
    auth.logout.mockReturnValue(new Promise((_resolve,reject)=>{rejectLogout=reject}));const loggingOut=user.logout()
    auth.loginByPassword.mockResolvedValue(session('newest'));await user.login({username:'test',password:'test-only'})
    rejectLogout(new Error('old logout expired'));await expect(loggingOut).rejects.toThrow()
    expect(user.token).toBe('newest');expect(user.profile?.userName).toBe('newest');expect(localStorage.getItem('plm_token')).toBe('newest')
  })
})
