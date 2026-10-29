import { describe,expect,it } from 'vitest'
import { localObjectProvider } from '../localProvider'
import type { PhoneObjectOption } from '../types'
const a={phoneModelId:1,brandId:10,brandName:'Apple',modelName:'iPhone 17 Pro Max',phoneModelCode:'0185',aliases:['17PM']}
const b={phoneModelId:2,brandId:20,brandName:'Samsung',modelName:'Galaxy S26 5G',phoneModelCode:'1211',aliases:['SM S26']}
const query={page:1,size:50}
describe('existing authorized business options',()=>{
 it('keeps common-object metadata intact while its brand directory only lists that brand',async()=>{
  const source=[{type:'item',id:9,name:'共模原文',code:'1144',phoneModels:[a,b]}];const p=localObjectProvider(()=>source)
  expect((await p.phones({...query,brandId:10})).content.map(p=>p.phoneModelId)).toEqual([1]);expect((await p.page({...query,brandId:10})).content[0]!.phoneModels).toEqual([a,b])
 })
 it('searches aliases, preserves leading zeros and pages without rendering/selecting the entire collection',async()=>{
  const source:PhoneObjectOption[]=Array.from({length:1010},(_,i)=>({type:'sku',id:i+1,name:`原始颜色 ${i}`,code:`SKU-${i}`,phoneModels:[a]}));const p=localObjectProvider(()=>source)
  const result=await p.page({...query,page:21,keyword:'17PM 0185'});expect(result.totalElements).toBe(1010);expect(result.content).toHaveLength(10);expect(result.content[0]!.phoneModels?.[0]?.phoneModelCode).toBe('0185');expect((await p.page({...query,keyword:'Samsung'})).content).toEqual([])
 })
 it('retains unlinked original combinations instead of assigning the sole available phone with the same code',async()=>{
  const source=[{type:'item',id:9,name:'A / B 4G 原文',code:'0185',phoneModels:[{...a,phoneModelId:null,modelName:'A / B 4G 原文'}]}];const p=localObjectProvider(()=>source)
  expect(await p.phones(query)).toMatchObject({totalElements:0,unlinkedCount:1});expect((await p.page({...query,unlinkedPhone:true,brandId:10})).content).toHaveLength(1);expect((await p.page({...query,phoneModelId:1})).content).toEqual([])
 })
})
