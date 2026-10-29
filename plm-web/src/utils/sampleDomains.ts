import type {SampleDomain,SampleKind} from '@/types/samples'

export const sampleDomains:Record<SampleDomain,string>={model:'型号研发',research:'新品研发',partner:'合作选品',unclassified:'历史来源待补'}
export const sampleKinds:Record<SampleKind,string>={phone:'手机真机',dummy:'模型机',pc:'PC 部件样品',tpu:'TPU 部件样品',case:'组装手机壳样品',prototype:'研发打样',supplier:'供方样品',unspecified:'历史类别未注明'}
export const sampleRoutes:Record<Exclude<SampleDomain,'unclassified'>,string>={model:'/model-samples',research:'/research-samples',partner:'/partner-samples'}
export const sampleDomainHelp:Record<Exclude<SampleDomain,'unclassified'>,string>={
  model:'按产品和手机型号管理实际样品；可追溯来源项目、具体模具套及借还记录。',
  research:'按研发项目、实际打样轮次及采用版本查样品；验证与复验沿用原研发记录。',
  partner:'按供应商款式、所选规格及实际收样批次管理；质检、签字和选品决定分别保留。',
}
export function sampleDomain(value:unknown):SampleDomain{return Object.prototype.hasOwnProperty.call(sampleDomains,String(value))?value as SampleDomain:'unclassified'}
export function sampleKindLabel(value:unknown){return sampleKinds[value as SampleKind]||sampleKinds.unspecified}
export function sampleReceiptLabel(sample:Record<string,unknown>){return sample.sourceType==='model'?'型号样品':sample.sourceType==='catalog'?'目录选样样品':sampleKindLabel(sample.sampleKind)}
export function sampleRoute(value:unknown){const d=sampleDomain(value);return d==='unclassified'?'/samples':sampleRoutes[d]}
export function researchSampleProgress(value?:{status?:string;result?:string;voided?:boolean}){
  return !value?'原轮次进度暂不可查看':value.voided?'原轮次已作废':value.status!=='confirmed'?'验证待完成':value.result==='passed'?'本轮验证通过':value.result==='failed'?'本轮需改进':'验证待完成'
}
