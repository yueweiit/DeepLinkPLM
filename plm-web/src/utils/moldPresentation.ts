import type { CollaborationRow,PhoneCompatibility } from '@/types/collaboration'
import { phoneMemberName } from './phoneMembers'
import { phoneSetCompatibilities } from './phoneCompatibility'

const milestoneClasses: Record<string,string> = { openedDate: 'milestone-opening', actualShipDate: 'milestone-shipping', actualMexicoArrivalDate: 'milestone-mexico' }
export function moldDateClass(field: string) { return milestoneClasses[field] || '' }
/** Compatibility belongs to a phone and explicitly selected physical sets. */
export function moldSetPhones(row:CollaborationRow) {
  const relations=Array.isArray(row.compatiblePhones)?row.compatiblePhones as PhoneCompatibility[]:[]
  const selected=relations.filter(phone=>phoneSetCompatibilities(phone).some(s=>s.moldSetId===row.moldSetId))
  const names=selected.map(phone=>{
    const member=row.members?.find(item=>item.phoneModelId===phone.phoneModelId)
    const name=member?phoneMemberName({brandName:member.brandName||'',modelName:member.modelName}):`手机资料 #${phone.phoneModelId}`
    const set=phoneSetCompatibilities(phone).find(s=>s.moldSetId===row.moldSetId)!
    return `${name}${member?.phoneModelCode?` · ${member.phoneModelCode}`:''}${set.pcConfirmed&&set.tpuConfirmed&&set.pcEvidence?.trim()&&set.tpuEvidence?.trim()?'':'（适配待确认）'}`
  })
  const applicableIds=new Set(selected.map(phone=>phone.phoneModelId))
  const sources=new Map((row.applicationLinks||[]).flatMap(link=>link.standardPhone?[[link.standardPhone.phoneModelId,link.standardPhone] as const]:[]))
  for(const phone of sources.values())if(!applicableIds.has(phone.phoneModelId))names.push(`${phoneMemberName(phone)}${phone.phoneModelCode?` · ${phone.phoneModelCode}`:''}（来源申请，适配待确认）`)
  return names.join(' / ') || (relations.length?'本套尚未关联适用手机':row.compatibleModels?`待核对：${row.compatibleModels}`:'本套适用手机待核对')
}
// Saved server tasks are authoritative; drafts and browser timezone never infer delay.
export function moldOverdueCell({ row, column }: { row: CollaborationRow; column: { property: string } }) {
  return column.property && row.overdueTasks?.some(task => task.overdue && task.dueField === column.property) ? 'is-overdue' : ''
}
