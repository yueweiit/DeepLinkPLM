import { describe, expect, it } from 'vitest'
import { supplierPriceSummary } from '../partnerPrice'

describe('shared supplier quotation presentation',()=>{
  it('shows multiple actual options without taking the first, lowest or sum as the whole price',()=>{
    const source={unitPrice:'99.00',currency:'USD',priceOptions:[{label:'裸壳',unitPrice:'2.40',currency:'CNY'},{label:'OPP包装加价',unitPrice:'0.10',currency:'CNY'}]}
    expect(supplierPriceSummary(source)).toBe('多种报价')
    expect(source.priceOptions.map(option=>option.unitPrice)).toEqual(['2.40','0.10'])
  })
  it('uses the one actual configuration price and currency, not stale generic fields',()=>{
    expect(supplierPriceSummary({unitPrice:'99.00',currency:'USD',priceOptions:[{label:'支架配置',unitPrice:'12.50',currency:'CNY'}]})).toBe('12.50 CNY')
  })
  it('does not borrow a generic currency when the selected quotation does not identify one',()=>{
    expect(supplierPriceSummary({currency:'USD',priceOptions:[{label:'裸壳',unitPrice:'2.40',currency:''}]})).toBe('2.40 · 币种未注明')
  })
  it('retains exact old single-price values, including zero, without changing money units',()=>{
    expect(supplierPriceSummary({unitPrice:'12.50',currency:'CNY'})).toBe('12.50 CNY')
    expect(supplierPriceSummary({unitPrice:0,currency:'USD'})).toBe('0 USD')
  })
  it('keeps unknown prices empty while pointing to genuine quotation conditions',()=>{
    expect(supplierPriceSummary({unitPrice:null,currency:''})).toBe('未报价')
    expect(supplierPriceSummary({unitPrice:'',priceNote:'需要按包装报价'})).toBe('见报价条件')
  })
})
