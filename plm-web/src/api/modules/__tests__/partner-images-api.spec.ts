import {beforeEach,describe,expect,it,vi} from 'vitest'
const api=vi.hoisted(()=>({get:vi.fn()}))
vi.mock('../../request',async()=>({...await vi.importActual<typeof import('../../request')>('../../request'),default:api}))
import {fileBlob} from '../partner'
import {catalogSourceFileClient,selectionImportImage} from '../partnerSelections'
beforeEach(()=>{vi.clearAllMocks();api.get.mockResolvedValue({data:new Blob(['image'],{type:'image/png'})})})
describe('authenticated preview request options',()=>{
 it('keeps formal and staged previews as authenticated Blobs with bounded long-read timeout and cancellation',async()=>{
  const signal=new AbortController().signal
  await fileBlob(27,true,{signal});expect(api.get).toHaveBeenCalledWith('/attachments/27/preview',{responseType:'blob',timeout:120000,suppressErrorToast:true,signal,isAuthSessionCurrent:expect.any(Function)})
  await selectionImportImage(2,'real-job','image/position',{signal});expect(api.get).toHaveBeenLastCalledWith('/partner-selections/2/import-jobs/real-job/images/image%2Fposition',{responseType:'blob',timeout:120000,suppressErrorToast:true,signal,isAuthSessionCurrent:expect.any(Function)})
  await catalogSourceFileClient.selectionImportImage(2,'real-job','image/position',{signal});expect(api.get).toHaveBeenLastCalledWith('/partner-catalog/sources/2/import-jobs/real-job/images/image%2Fposition',{responseType:'blob',timeout:120000,suppressErrorToast:true,signal,isAuthSessionCurrent:expect.any(Function)})
 })
 it('does not change ordinary download behavior or mute its errors',async()=>{
  await fileBlob(27,false);expect(api.get).toHaveBeenCalledWith('/attachments/27/download',{responseType:'blob'})
 })
})
