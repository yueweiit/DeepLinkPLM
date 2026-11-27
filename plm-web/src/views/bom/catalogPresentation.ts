import { currentLanguage, t } from '@/locales'
import type { CurrentSku, EngineeringState, ScopedRecipe } from '@/api/modules/masterData'

export type BomIdentity = Pick<CurrentSku,'sourceId'|'seriesName'|'seriesCode'|'modelName'|'itemCode'|'colorNameZh'|'colorNameEs'|'colorShortCode'> & {seriesNameEs?:string}

export function productLabel(row:Pick<BomIdentity,'seriesName'|'seriesCode'|'seriesNameEs'>) {
  return (currentLanguage.value === 'es' && row.seriesNameEs) || row.seriesName || row.seriesCode
}
export function colorLabel(row:BomIdentity) {
  return (currentLanguage.value === 'es' && row.colorNameEs) || row.colorNameZh || row.colorShortCode || '—'
}
export function decimalLabel(raw:string | number | null | undefined) {
  if (raw == null || raw === '') return '—'
  const value = String(raw)
  return /^-?\d+\.\d+$/.test(value) ? value.replace(/0+$/, '').replace(/\.$/, '') : value
}
// MES combines the source languages in one name. Keep technical abbreviations that are part of a single name.
export function materialLabel(name:string) {
  const pair = name?.match(/^(.+[\p{Script=Han}）)])\s+([A-Za-zÁÉÍÓÚÑáéíóúñÜü][^\p{Script=Han}]+)$/u)
  if (!pair || !(/[a-záéíóúñü]/.test(pair[2]) || /[A-Z]{2,}\s+[A-Z]{2,}/.test(pair[2]))) return name
  return currentLanguage.value === 'es' ? pair[2] : pair[1]
}
export function versionLabel(r:ScopedRecipe) { return r.displayVersion || r.versionNo || r.sourceVersion }
export function stateLabel(state:EngineeringState) {
  return t(({pending:'待工程确认',released:'工程已确认',history:'历史版本',conflict:'配方冲突'} as const)[state])
}
export function preferredRecipe<T extends {state:EngineeringState}>(recipes:T[]) {
  return recipes.find(r=>r.state==='conflict') || recipes.find(r=>r.state==='pending') || recipes.find(r=>r.state==='released') || recipes[0]
}
