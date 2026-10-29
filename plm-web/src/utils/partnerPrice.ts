import type { SupplierPriceOption } from '@/types/partnerSelections'

export interface SupplierPriceSource {
  unitPrice?: string | number | null
  currency?: string
  priceNote?: string
  priceOptions?: SupplierPriceOption[]
}

/** Display supplier quotations without picking a minimum or adding configuration prices. */
export function supplierPriceSummary(row: SupplierPriceSource): string {
  if (row.priceOptions && row.priceOptions.length > 1) return '多种报价'
  const quote = row.priceOptions?.length === 1 ? row.priceOptions[0] : row
  if (quote.unitPrice === undefined || quote.unitPrice === null || quote.unitPrice === '') return quote.priceNote || row.priceNote ? '见报价条件' : '未报价'
  return `${quote.unitPrice}${quote.currency ? ` ${quote.currency}` : ' · 币种未注明'}`
}

/** Structured columns deliberately do not join price, currency, MOQ or sample plans. */
export function supplierPriceValue(row: SupplierPriceSource): string {
  if (row.priceOptions && row.priceOptions.length > 1) return '多种报价'
  const quote = row.priceOptions?.length === 1 ? row.priceOptions[0] : row
  return quote.unitPrice === undefined || quote.unitPrice === null || quote.unitPrice === '' ? (quote.priceNote || row.priceNote ? '见条件' : '未报价') : String(quote.unitPrice)
}
export const supplierCurrency = (row: SupplierPriceSource) => row.priceOptions && row.priceOptions.length > 1 ? '分档查看' : (row.priceOptions?.length === 1 ? row.priceOptions[0].currency : row.currency) || '未确认'
export const moqScopeLabel = (scope?:string) => ({style:'整款',model:'每型号',color:'每颜色',model_color:'每型号每颜色',per_style:'整款',per_model:'每型号',per_color:'每颜色',per_model_color:'每型号每颜色'} as Record<string,string>)[scope||''] || scope || '未确认'
