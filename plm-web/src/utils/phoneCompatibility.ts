import type { PhoneCompatibility, PhoneSetCompatibility } from '@/types/collaboration'

/** Old item-wide evidence is read only as a historical projection until migrated. */
export function phoneSetCompatibilities(relation:PhoneCompatibility):PhoneSetCompatibility[] {
  return relation.setCompatibilities ?? relation.setIds.map(moldSetId=>({moldSetId,pcConfirmed:relation.pcConfirmed,tpuConfirmed:relation.tpuConfirmed,pcEvidence:relation.evidence||null,tpuEvidence:relation.evidence||null}))
}
export function withPhoneSetCompatibilities(relation:PhoneCompatibility,sets:PhoneSetCompatibility[]):PhoneCompatibility {
  return {...relation,setCompatibilities:sets,setIds:sets.map(s=>s.moldSetId),pcConfirmed:sets.length>0&&sets.every(s=>s.pcConfirmed),tpuConfirmed:sets.length>0&&sets.every(s=>s.tpuConfirmed),evidence:[...new Set(sets.flatMap(s=>[s.pcEvidence,s.tpuEvidence]).filter(Boolean))].join('；')}
}
