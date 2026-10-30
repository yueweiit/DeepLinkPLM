import {describe,it,expect} from 'vitest'
import {phoneMemberName,phoneFactoryName} from '../phoneMembers'

describe('current phone display names',()=>{
  it('uses the supplied full name for business views and the factory spelling for factory views',()=>{
    const phone={brandName:'HONOR',modelName:'HR 600 LITE 5G',displayName:'Honor 600 Lite 5G',factoryName:'HR 600 LITE 5G',originalCodeName:'HR 600 LITE 5G'}
    expect(phoneMemberName(phone)).toBe('Honor 600 Lite 5G')
    expect(phoneFactoryName(phone)).toBe('HR 600 LITE 5G')
    expect(phone.modelName).toBe('HR 600 LITE 5G')
  })
  it('retains historical values when an older response has no naming projection',()=>{
    const phone={brandName:'Samsung',modelName:'Galaxy A17 4G/5G',originalCodeName:'SM Galaxy A17 4G/5G'}
    expect(phoneMemberName(phone)).toBe('Samsung Galaxy A17 4G/5G')
    expect(phoneFactoryName(phone)).toBe('SM Galaxy A17 4G/5G')
  })
})
