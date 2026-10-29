import request,{unwrapResponse} from '../request'
import type {CustomerDetail,CustomerSummary} from '@/types/customer'
import type {BusinessData,PartnerPage} from '@/types/partner'
export async function getCustomerList(){return unwrapResponse<CustomerSummary[]>(await request.get('/customers'))}
export async function getCustomerPage(params:BusinessData){return unwrapResponse<PartnerPage>(await request.get('/customers',{params}))}
export async function getCustomerDetail(id:number){return unwrapResponse<CustomerDetail>(await request.get('/customers/'+id))}
export async function createCustomer(payload:Partial<CustomerDetail>){return unwrapResponse<CustomerDetail>(await request.post('/customers',payload))}
export async function updateCustomer(id:number,payload:Partial<CustomerDetail>){return unwrapResponse<CustomerDetail>(await request.put('/customers/'+id,payload))}
export async function getCustomerReferences(id:number|string){return unwrapResponse<import('@/types/masterDataLifecycle').MasterDataInspection>(await request.get(`/customers/${id}/references`))}
export async function customerLifecycle(id:number|string,action:import('@/types/masterDataLifecycle').MasterDataLifecycleAction,payload:{version:number;requestId:string;reason:string}){return unwrapResponse(await request.post(`/customers/${id}/actions/${action}`,payload))}
