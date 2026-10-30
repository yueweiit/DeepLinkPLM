import { afterEach, describe, expect, it } from 'vitest'
import { computed } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { currentLanguage, normalizeLanguage, setLanguage, t } from '@/locales'
import ResearchMoldCoding from '@/views/project/components/ResearchMoldCoding.vue'
import type { MoldApplication } from '@/api/modules/moldApplications'

afterEach(()=>setLanguage('zh-CN'))
describe('interface languages',()=>{
 it('reactively translates existing semantic keys and interface text without replacing unknown source data',()=>{
  const label=computed(()=>t('common.save'))
  expect(label.value).toBe('保存')
  setLanguage('es')
  expect(label.value).toBe('Guardar')
  expect(t('供应商产品目录')).toBe('Catálogo de proveedores')
  expect(t('Apple iPhone 18 Pro Max / 0185')).toBe('Apple iPhone 18 Pro Max / 0185')
  expect(t('熊大原件-黑色.png')).toBe('熊大原件-黑色.png')
  expect(currentLanguage.value).toBe('es')
  expect(document.documentElement.lang).toBe('es')
  expect(localStorage.getItem('plm_language')).toBe('es')
 })
 it('uses Chinese for unsupported or absent legacy account preferences',()=>{
  expect(normalizeLanguage(null)).toBe('zh-CN');expect(normalizeLanguage('en')).toBe('zh-CN');expect(normalizeLanguage('es')).toBe('es')
 })
 it('renders the new coding section in Spanish while preserving source models and generated identities',async()=>{
  setLanguage('es')
  const view=mount(ResearchMoldCoding,{props:{applications:[] as MoldApplication[],inputs:{},editable:true,busy:false,canCreate:true,canPrepare:true},global:{plugins:[ElementPlus]}})
  try {
   expect(view.text()).toContain('Solicitud de molde y códigos')
   expect(view.text()).toContain('Los códigos M y N se generan cuando Tiffany aprueba.')
   expect(view.text()).toContain('Crear solicitud de molde LEMOS')
   const application={applicationId:88,status:'submitted',version:3,model:'Samsung Galaxy A17 5G / A26 5G',canMaintainResearchCoding:true,
    researchCoding:{status:'pending',missingFields:['materialCodes','operationCode'],generatedMoldCodes:['MHA100030','MHA200030'],productCodePrefix:'NHA',operationOptions:[{code:'4020',name:'工序原名'}]}} as MoldApplication
   await view.setProps({applications:[application]})
   expect(view.text()).toContain('Elegir otro modelo')
   expect(view.text()).toContain('Pendiente: material del molde')
   expect(view.text()).toContain('Pendiente: proceso del producto')
   expect(view.text()).toContain('El trámite continúa automáticamente al guardar.')
   expect(view.text()).toContain('Molde de TPU')
   expect(view.text()).toContain('Molde de PC')
   expect(view.text()).toContain('Prefijo del código de producto')
   expect(view.text()).toContain(application.model)
   expect(view.findAll('code').map(value=>value.text())).toEqual(['MHA100030','MHA200030','NHA'])
   await view.setProps({applications:[{...application,executionStatus:'paused'}]})
   expect(view.text()).toContain('Ejecución suspendida')
   expect(view.text()).not.toContain('开模申请与编码')
  } finally {view.unmount()}
 })
})
