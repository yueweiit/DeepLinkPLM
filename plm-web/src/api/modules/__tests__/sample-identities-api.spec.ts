import {describe,expect,it,vi} from 'vitest'
const http=vi.hoisted(()=>({post:vi.fn(),get:vi.fn()}))
vi.mock('../../request',async()=>({...await vi.importActual<typeof import('../../request')>('../../request'),default:http}))
import {querySampleIdentities} from '../sampleManagement'

describe('shared sample selection read',()=>{
 it('sends a whole 500-ID read once, returns all identities and leaves errors to the caller',async()=>{
  const content=Array.from({length:500},(_,i)=>({sampleIdentityId:i+1,sampleCode:'SY-SF-'+String(i+1).padStart(6,'0')}));const data={ids:content.map(row=>row.sampleIdentityId),details:true};http.post.mockResolvedValue({data:{code:0,data:{content,totalElements:500}}});
  expect(await querySampleIdentities(data)).toEqual({content,totalElements:500});expect(http.post).toHaveBeenCalledExactlyOnceWith('/sample-identities/query',data,{suppressErrorToast:true});expect(http.get).not.toHaveBeenCalled()
 })
})
