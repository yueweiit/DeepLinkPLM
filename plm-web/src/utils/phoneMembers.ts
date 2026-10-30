import type { CompatiblePhone } from '@/types/molds'
/** Current display identity only; never rewrite historical model or code snapshots. */
export function phoneMemberName(phone:Pick<CompatiblePhone,'brandName'|'modelName'|'displayName'>){
  if(phone.displayName)return phone.displayName
  const model=phone.modelName.trim(),brand=phone.brandName.trim(),head=model.toLowerCase(),prefix=brand.toLowerCase()
  return head===prefix||[' ','-',':','/'].some(separator=>head.startsWith(prefix+separator))?model:`${brand} ${model}`.trim()
}
export function phoneFactoryName(phone:Pick<CompatiblePhone,'factoryName'|'originalCodeName'|'modelName'>){
  return phone.factoryName || phone.originalCodeName || phone.modelName
}
