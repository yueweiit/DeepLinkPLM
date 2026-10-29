import {beforeEach,describe,it,expect,vi} from 'vitest'
const http=vi.hoisted(()=>({get:vi.fn(),post:vi.fn(),put:vi.fn()}))
vi.mock('@/api/request',()=>({default:http,unwrapResponse:(r:{data:{code:number;data:unknown;message?:string}})=>{if(r.data.code)throw Error(r.data.message);return r.data.data}}))
import {getCustomerPage,createCustomer,updateCustomer} from '../customer'
describe('customer real persistence contract',()=>{
 beforeEach(()=>vi.clearAllMocks())
 it('keeps a name-only draft and the server identity used to reopen it',async()=>{http.post.mockResolvedValue({data:{code:0,data:{customerId:42,version:1,customerName:'客户甲'}}});const input={customerName:'客户甲',requestId:'retry-key'};const result=await createCustomer(input);expect(http.post).toHaveBeenCalledWith('/customers',input);expect(result.customerId).toBe(42);http.put.mockResolvedValue({data:{code:0,data:{...result,version:2}}});await updateCustomer(result.customerId,{...result,customerName:'客户乙'});expect(http.put).toHaveBeenCalledWith('/customers/42',expect.objectContaining({version:1,customerName:'客户乙'}))})
 it('passes filters and pages to the server and preserves validation errors',async()=>{http.get.mockResolvedValue({data:{code:0,data:{content:[],totalElements:31,page:2}}});expect((await getCustomerPage({page:2,keyword:'甲'})).totalElements).toBe(31);expect(http.get).toHaveBeenCalledWith('/customers',{params:{page:2,keyword:'甲'}});http.post.mockResolvedValue({data:{code:40001,message:'客户已变化'}});await expect(createCustomer({customerName:'甲'})).rejects.toThrow('客户已变化')})
})
