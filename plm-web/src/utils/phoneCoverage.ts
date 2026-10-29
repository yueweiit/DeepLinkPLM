import type { MarketPhoneCoverageState, MarketPhoneMold } from '@/types/marketWatch'

export const phoneCoverageNames:Record<MarketPhoneCoverageState,string>={
  uncovered:'无记录',catalogued:'已有产品',draft:'草稿',applying:'申请中',planned:'待开工',making:'制作中',
  partial:'部分适配',confirmed:'已有实物',reusable:'可复用',paused:'已暂停',terminated:'已终止',needs_review:'待核对'
}

function moldReviewEntry(mold:MarketPhoneMold):{tab:'master'|'sets';name:string} {
  if(mold.reusable)return {tab:'master',name:'查看共模'}
  const reasons=mold.reviewReasons||[]
  if(mold.hasRegisteredSets===false||mold.registeredSets===0||reasons.some(reason=>reason.action==='register_existing'))return {tab:'sets',name:'登记已有实物'}
  if(!mold.moldSetId)return {tab:'master',name:'选择适用套'}
  const pcEvidence=mold.pcEvidence===undefined?mold.evidence:mold.pcEvidence,tpuEvidence=mold.tpuEvidence===undefined?mold.evidence:mold.tpuEvidence
  const missingEvidence=mold.reviewReasons===undefined&&(!pcEvidence?.trim()||!tpuEvidence?.trim())
  if(!mold.compatibilityConfirmed||!mold.pcConfirmed||!mold.tpuConfirmed||missingEvidence||reasons.some(reason=>['select_set','confirm_fit','complete_fit_evidence'].includes(reason.action)))return {tab:'master',name:'核对本套适配'}
  return {tab:'sets',name:'核对实物资料'}
}

/** Navigate to the existing editor for the missing facts; an exact set never changes the fit tab. */
export function moldReviewTarget(mold:MarketPhoneMold,phoneModelId?:number|null) {
  return {path:'/molds',query:{itemId:mold.itemId,phoneModelId:phoneModelId||undefined,tab:moldReviewEntry(mold).tab,...(mold.moldSetId?{moldSetId:mold.moldSetId}:{})}}
}

export function moldReviewActionName(mold:MarketPhoneMold,canMaintain:boolean) {
  return canMaintain?moldReviewEntry(mold).name:'查看模具资料'
}
