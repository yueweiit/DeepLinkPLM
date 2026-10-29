import type {CompatiblePhone} from '@/types/molds'
import {phoneMemberName} from '@/utils/phoneMembers'
import {productionHint} from '@/utils/productionStatus'

/** Query shorthand only. It never creates, merges or renames a phone identity. */
export function selectionSearchTerm(value:string) {
  return value.normalize('NFKC').trim().split(/\s+/).map(token=>{
    if(/^ip$/i.test(token))return 'iPhone'
    const short=token.match(/^(ip(?:hone)?)?(\d{1,2})(pm|promax|pro|max|plus|ultra|p)?$/i)
    if(!short || (!short[1] && !short[3]))return token
    const suffix=short[3]?.toLowerCase()
    return [short[1]?'iPhone':'',short[2],suffix==='pm'||suffix==='promax'?'Pro Max':suffix==='p'?'Pro':suffix || ''].filter(Boolean).join(' ')
  }).join(' ')
}
const compact=(value:string)=>value.toLocaleLowerCase().replace(/(\p{Nd})[^\p{L}\p{N}]+(?=\p{Nd})/gu,'$1#').replace(/[^\p{L}\p{N}#]/gu,'')
export function matchesPhone(phone:CompatiblePhone,term:string) {
  const fields=(phone.searchTexts || [phone.brandName,phoneMemberName(phone),phone.phoneModelCode,...phone.brandAliases || [],...phone.aliases || []]).filter((v):v is string=>!!v).map(compact)
  return selectionSearchTerm(term).split(/\s+/).every(token=>{
    const value=compact(token)
    if(!value)return true
    const pattern=(/^\p{Nd}/u.test(value)?'(^|[^\\p{Nd}])':'')+value+(/\p{Nd}$/u.test(value)?'([^\\p{Nd}]|$)':'')
    return fields.some(field=>new RegExp(pattern,'u').test(field))
  })
}
export function unavailablePhone(phone:CompatiblePhone) {
  if(phone.status==='disabled')return '手机已停用'
  if(phone.brandStatus==='disabled')return '品牌已停用'
  if(phone.codeLinkStatus==='missing')return '原编码关联待核对'
  if(phone.codeLinkStatus==='disabled' || phone.codeStatus==='disabled')return '原编码已停用'
  return ''
}
export function phoneSelectionLabel(phone:CompatiblePhone) {
  const reason=unavailablePhone(phone)
  return `${phone.phoneModelCode || '编码待分配'} · ${phoneMemberName(phone)}${productionHint(phone.productionStatus)}${reason?' · '+reason:''}`
}
/** Existing data has no series table. These are display groups, never business identities. */
export function phoneSeries(phone:Pick<CompatiblePhone,'brandId'|'brandName'|'modelName'|'seriesId'|'seriesName'>) {
  if(phone.seriesName)return {key:`${phone.brandId}:series:${phone.seriesId ?? phone.seriesName}`,name:phone.seriesName}
  const original=phone.modelName.trim()
  if(/[／/,，、\n]/.test(original))return {key:`${phone.brandId}:combined`,name:'组合型号（原文保留）'}
  const brand=phone.brandName.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')
  const name=original.replace(new RegExp(`^${brand}\\s+`,'i'),'')
  const base=(name.match(/^([^\d]*[\p{L}]\s*\d{1,3})(?!\d)/u)?.[1] || name.match(/^(\d{1,3})(?!\d)/)?.[1])?.trim()
  return {key:`${phone.brandId}:${(base || 'other').toLowerCase()}`,name:base ? `${base} 系列` : '其他型号'}
}
export interface PhoneModelMultiSelectProps {
  modelValue:number[]
  members?:CompatiblePhone[]
  disabled?:boolean
  brandId?:number
  multiple?:boolean
  allowedIds?:number[]
  placeholder?:string
  ariaLabel?:string
  previewCount?:number
}
