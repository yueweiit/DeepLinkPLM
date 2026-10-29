import {describe,expect,it} from 'vitest'
import {moldSetPhones} from '../moldPresentation'
import type {CollaborationRow} from '@/types/collaboration'

describe('physical set phone identity',()=>{
  const row={moldSetId:2,compatibleModels:'原始手机快照',members:[{phoneModelId:11,brandName:'Apple',modelName:'Apple iPhone 18',phoneModelCode:'0018'},{phoneModelId:12,brandName:'Samsung',modelName:'S26',phoneModelCode:'0026'}],compatiblePhones:[{phoneModelId:11,setIds:[1],pcConfirmed:true,tpuConfirmed:true,evidence:'原套'},{phoneModelId:12,setIds:[2],pcConfirmed:true,tpuConfirmed:false,evidence:'待TPU'}]} as unknown as CollaborationRow
  it('shows only phones applicable to this physical set and retains leading zeroes',()=>{expect(moldSetPhones(row)).toBe('Samsung S26 · 0026（适配待确认）');expect(moldSetPhones({...row,moldSetId:1})).toBe('Apple iPhone 18 · 0018')})
  it('does not use a master member or old model snapshot as this sets confirmed coverage',()=>{expect(moldSetPhones({...row,moldSetId:3})).toBe('本套尚未关联适用手机')})
  it('shows the exact source application phone as pending adaptation without inventing compatible membership',()=>{const applicationLinks=[{applicationId:8,standardPhone:{phoneModelId:11,brandName:'Apple',modelName:'iPhone 18',phoneModelCode:'0018'}}] as import('@/types/collaboration').MoldApplicationLink[];expect(moldSetPhones({...row,moldSetId:3,compatiblePhones:[],applicationLinks})).toBe('Apple iPhone 18 · 0018（来源申请，适配待确认）');expect(moldSetPhones({...row,moldSetId:1,applicationLinks})).toBe('Apple iPhone 18 · 0018');expect(row.compatiblePhones).toHaveLength(2)})
  it('uses per-set confirmations rather than an old aggregate confirmation',()=>{const phone=row.compatiblePhones as import('@/types/collaboration').PhoneCompatibility[];expect(moldSetPhones({...row,moldSetId:1,compatiblePhones:[{...phone[0],setCompatibilities:[{moldSetId:1,pcConfirmed:true,tpuConfirmed:false,pcEvidence:'PC已确认',tpuEvidence:null},{moldSetId:2,pcConfirmed:true,tpuConfirmed:true,pcEvidence:'PC',tpuEvidence:'TPU'}]}]})).toBe('Apple iPhone 18 · 0018（适配待确认）')})
  it('uses a compact review label without truncating the complete historical model',()=>{const original='Xiaomi Redmi Note 17 Pro / Redmi Note 17 Pro Max';expect(moldSetPhones({...row,compatiblePhones:[],compatibleModels:original})).toBe(`待核对：${original}`)})
})
