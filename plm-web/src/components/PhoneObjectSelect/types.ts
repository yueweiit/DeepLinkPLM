/** Read-only browsing metadata. The selected ID always belongs to the business object, not the phone. */
export interface ObjectPhone {
  phoneModelId: number | null
  brandId: number
  brandName: string
  modelName: string
  phoneModelCode?: string | null
  aliases?: string[]
}
export interface PhoneObjectOption {
  type: string
  id: number
  partType?: 'ALL' | 'PC' | 'TPU'
  name: string
  code: string
  subtitle?: string
  productType?: string
  color?: string
  productLineId?: number
  productLineName?: string
  phoneModelCode?: string | null
  modelName?: string
  phoneModels?: ObjectPhone[]
  disabled?: boolean
  disabledReason?: string
  data?: Record<string, any>
}
export interface PhoneObjectKind { key: string; type: string; productType?: string; label: string; phoneFirst?: boolean }
export interface PhoneObjectQuery {
  type?: string; productType?: string; keyword?: string; brandId?: number; phoneModelId?: number
  productLineId?: number; unlinkedPhone?: boolean; page: number; size: number
}
export interface PhoneObjectPage<T> {
  content: T[]; totalElements: number
  brands: { brandId: number; name: string }[]
  productLines: { productLineId: number; name: string }[]
}
export interface ObjectPhoneGroup extends ObjectPhone { phoneModelId: number; referenceCount: number }
export interface PhoneObjectProvider {
  page(query: PhoneObjectQuery): Promise<PhoneObjectPage<PhoneObjectOption>>
  phones(query: PhoneObjectQuery): Promise<PhoneObjectPage<ObjectPhoneGroup> & { unlinkedCount: number }>
}
export const objectKey = (row: PhoneObjectOption) => `${row.type}:${row.id}`
export const selectionKey = (row: PhoneObjectOption) => `${objectKey(row)}:${row.partType || 'ALL'}`
