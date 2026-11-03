import {t as uiText} from '@/locales'
import type {SampleLocationParts,SamplePosition,SampleLocationSite} from '@/types/sampleManagement'

export const sampleLocationSites=[{value:'CN',label:'中国'},{value:'MX',label:'墨西哥'}] as const

export const sampleLocationSegments=[
  {key:'area',label:'区域',example:'A001'},
  {key:'rack',label:'货架编码',example:'A0001'},
  {key:'shelf',label:'货架层号',example:'001'},
  {key:'box',label:'收纳盒号',example:'001'},
  {key:'sequence',label:'收纳盒序号',example:'0001'},
] as const
export type SampleLocationValue={location?:string;locationId?:number;site?:SampleLocationSite;locationCode?:string;locationParts?:Partial<SampleLocationParts>}

export function sampleLocationParts(parts?:Partial<SampleLocationParts>,code?:string):SampleLocationParts{
  const values=String(code||'').split('-')
  return Object.fromEntries(sampleLocationSegments.map(({key},index)=>[key,String(parts?.[key]??(values.length===5?values[index]:'')??'').trim()])) as unknown as SampleLocationParts
}
export function sampleLocationCode(parts:Partial<SampleLocationParts>):string{
  const values=sampleLocationSegments.map(({key})=>String(parts[key]||'').trim())
  return values.every(Boolean)?values.join('-'):''
}
export function sampleLocationError(code?:string,parts?:Partial<SampleLocationParts>):string{
  const values=sampleLocationParts(parts,code),filled=sampleLocationSegments.filter(({key})=>values[key])
  if(!filled.length&&!String(code||'').trim())return ''
  if(filled.length!==5)return '位置编码请填写完整的区域、货架编码、货架层号、收纳盒号和收纳盒序号'
  if(sampleLocationSegments.some(({key})=>/[\s\u0000-\u001f\u007f-\u009f-]/.test(values[key])))return '位置编码各段不能包含空白或连接符'
  if(code&&code.trim()!==sampleLocationCode(values))return '位置编码与五段内容不一致，请重新核对'
  return ''
}
export function sampleLocationPayload(value:SampleLocationValue){
  const location=String(value.location||'').trim(),parts=sampleLocationParts(value.locationParts,value.locationCode),code=sampleLocationCode(parts)
  return {...(value.locationId!==undefined?{locationId:value.locationId}:{}),...(value.site?{site:value.site}:{}),location,...(code?{locationCode:code,locationParts:parts}:{})}
}
export function samplePositionKey(position:Pick<SamplePosition,'name'|'positionKey'|'locationCode'|'locationId'|'site'>):string{
  return position.positionKey||(position.locationId?'location:'+position.locationId:'')||(position.site?JSON.stringify([position.site,position.name,position.locationCode||'']):'')||(position.locationCode?JSON.stringify([position.name,position.locationCode]):position.name)
}
export function sampleLocationLabel(position:{name?:string;location?:string;locationCode?:string;site?:SampleLocationSite;locationId?:number}):string{
  const original=position.name??position.location,name=original==='CN'||(!original&&!position.locationId&&position.site==='CN')?uiText('中国样品间未设库位'):original==='MX'||(!original&&!position.locationId&&position.site==='MX')?uiText('墨西哥样品间未设库位'):original
  return [position.locationCode,name].filter(Boolean).join(' · ')
}
export function useSamplePosition(target:SampleLocationValue,position:SampleLocationValue&{name?:string}){
  Object.assign(target,{locationId:position.locationId,site:position.site,location:position.name??position.location??'',locationCode:position.locationCode||'',locationParts:sampleLocationParts(position.locationParts,position.locationCode)})
}

type SampleLocationUser={profile?:{roleCode?:string}|null;hasPermission:(permission:string)=>boolean}
export function canAccessLocalSamples(user:SampleLocationUser){return user.profile?.roleCode==='mexico'&&user.hasPermission('task:arrival')}
export function sampleLocationAccess(user:SampleLocationUser){
 const localOnly=user.profile?.roleCode==='mexico'
 return {localOnly,canWrite:localOnly?canAccessLocalSamples(user):user.hasPermission('samples:write')}
}
