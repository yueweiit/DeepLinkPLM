import {describe,expect,it} from 'vitest'
import type {MarketPhoneMold} from '@/types/marketWatch'
import {moldReviewActionName,moldReviewTarget} from '@/utils/phoneCoverage'

const mold:MarketPhoneMold={itemId:45,moldSetId:67,moldName:'17 / 18 共模',styleName:'骑士',setNo:1,physicalCode:'MS-000067',engravedCode:'1144',projectId:33,pcCode:'MFA201144',tpuCode:'MFA101144',pcState:'unknown',tpuState:'confirmed',state:'needs_review',reusable:false,compatibilityConfirmed:true,pcConfirmed:true,tpuConfirmed:true,evidence:null,pcEvidence:'本套PC贴合记录',tpuEvidence:'本套TPU贴合记录',plannedDeliveryDate:null,hasRegisteredSets:true,registeredSets:1}

describe('shared phone mold review navigation',()=>{
  it.each([
    {name:'partial fit',patch:{tpuConfirmed:false,tpuEvidence:null},tab:'master',action:'核对本套适配'},
    {name:'missing per-set evidence',patch:{pcEvidence:'',reviewReasons:[{code:'pc_fit_evidence_missing',message:'PC缺少本套依据',action:'complete_fit_evidence'}]},tab:'master',action:'核对本套适配'},
    {name:'existing sets without an applicable selection',patch:{moldSetId:null},tab:'master',action:'选择适用套'},
    {name:'no registered physical set',patch:{moldSetId:null,hasRegisteredSets:false,registeredSets:0},tab:'sets',action:'登记已有实物'},
    {name:'verified reusable set',patch:{reusable:true},tab:'master',action:'查看共模'},
    {name:'old confirmed fit projection with shared evidence',patch:{pcEvidence:undefined,tpuEvidence:undefined,evidence:'旧兼容关系确认依据'},tab:'sets',action:'核对实物资料'},
    {name:'server physical-only reasons on an older projection',patch:{pcEvidence:undefined,tpuEvidence:undefined,reviewReasons:[{code:'pc_physical_unconfirmed',message:'PC实物状态尚未核对',action:'confirm_physical'}]},tab:'sets',action:'核对实物资料'},
    {name:'physical facts still need review after fit is confirmed',patch:{reviewReasons:[{code:'pc_physical_unconfirmed',message:'PC实物状态尚未核对',action:'confirm_physical'}]},tab:'sets',action:'核对实物资料'}
  ])('routes $name to the existing editor while retaining the exact archive and set',({patch,tab,action})=>{
    const record={...mold,...patch} as MarketPhoneMold
    expect(moldReviewTarget(record,18)).toEqual({path:'/molds',query:{itemId:45,phoneModelId:18,tab,...(record.moldSetId?{moldSetId:67}:{})}})
    expect(moldReviewActionName(record,true)).toBe(action)
    expect(moldReviewActionName(record,false)).toBe('查看模具资料')
  })
})
