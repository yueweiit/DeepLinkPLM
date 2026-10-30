import type { SupplierBusinessCategory } from '@/types/supplier'

export const SUPPLIER_BUSINESS_CATEGORIES = [
  { value: 'mold', label: '开模供应商' },
  { value: 'partner_selection', label: '合作选品供应商' },
] as const

interface SupplierChoice { supplierCode: string; businessCategories?: SupplierBusinessCategory[]; status?: string; needsReview?: boolean }
export const supplierBusinessLabel = (categories: SupplierBusinessCategory[] = []) => categories.map(value => SUPPLIER_BUSINESS_CATEGORIES.find(category => category.value === value)?.label || value).join('、') || '未分类'
export const canSelectMoldSupplier = (supplier: SupplierChoice) => supplier.businessCategories?.includes('mold') === true && supplier.status !== 'inactive' && !supplier.needsReview

/** Keep the saved choice readable without offering it for new mold assignments. */
export function moldSupplierOptions<T extends SupplierChoice>(suppliers: T[], selectedCode?: string) {
  return suppliers.filter(supplier => canSelectMoldSupplier(supplier) || supplier.supplierCode === selectedCode)
}
