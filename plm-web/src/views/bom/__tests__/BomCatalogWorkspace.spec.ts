import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {createMemoryHistory,createRouter} from 'vue-router'
import ElementPlus from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {useUserStore} from '@/stores/user'
import type {BomCatalogColor,ScopedMaterial,ScopedRecipe} from '@/api/modules/masterData'
import BomCatalogWorkspace from '../components/BomCatalogWorkspace.vue'
import BomCatalogDrawer from '../components/BomCatalogDrawer.vue'
import {decimalLabel,materialLabel} from '../catalogPresentation'
import {setLanguage} from '@/locales'

const api=vi.hoisted(()=>({products:vi.fn(),groups:vi.fn(),detail:vi.fn(),scope:vi.fn(),publish:vi.fn(),options:vi.fn()}))
vi.mock('@/api/modules/masterData',async original=>({...await original<object>(),getCurrentBomProducts:api.products,getCurrentBomGroups:api.groups,getCurrentSku:api.detail,getBomConfirmationScope:api.scope,getCurrentCatalogOptions:api.options}))
vi.mock('@/api/modules/bom',async original=>({...await original<object>(),confirmCurrentBomVersion:api.publish}))
vi.mock('@/stores/productionMetadata',()=>({useProductionMetadataStore:()=>({ensure:async()=>{},canMaintain:true})}))
const plum:BomCatalogColor={sourceId:'purple',objectId:9,objectType:'product',itemCode:'SKU01',seriesCode:'SER',seriesName:'亮甲 2.0',modelName:'POCO X8 Pro',modelCode:'1297',colorNameZh:'紫色',colorShortCode:'01',recipes:[{bomId:201,bomRouteId:301,version:'V2',state:'pending',routeCode:'PACK'}]}
const black:BomCatalogColor={...plum,sourceId:'black',itemCode:'SKU02',colorNameZh:'黑色',colorShortCode:'02',recipes:[]}
const twin:BomCatalogColor={...plum,sourceId:'other-member',modelName:'Redmi Note 17 PRO MAX'}
const label=(id:number,nodeId:number,raw:string):ScopedMaterial=>({id,lineNo:id,inventoryId:id,childCode:'MAT'+id,childName:'标签纸 Papel para etiquetas',quantity:raw,unit:'张',nodeId,nodeCode:'N'+nodeId,nodeName:'组装和打包',operationCode:'PACK',articleSpecific:false})
const candidate:ScopedRecipe={bomId:201,bomRouteId:301,projectId:8,processId:5,bomCode:'B-1',internalBomCode:'PLM-B-1',outputQty:'1.00000000',versionNo:'MES-V2-1-rev',displayVersion:'V2',sourceVersion:'V2',status:'frozen',engineeringState:'pending',parentCode:'SER',parentName:'亮甲 2.0',routeCode:'PACK',routeName:'组装和打包',items:[label(1,1,'1.00000000'),label(2,1,'0.20000000'),label(3,2,'22.13000000')],graph:{nodes:[],edges:[]}}
const older:ScopedRecipe={...candidate,bomId:202,bomRouteId:302,displayVersion:'V1',engineeringState:'released',status:'released'}
const payload=(recipes:ScopedRecipe[])=>({source:{},objectType:'product',objectId:9,manufacturing:recipes})
let wrapper:VueWrapper
async function start(){
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{template:'<div/>'}}]})
  await router.push('/')
  wrapper=mount(BomCatalogWorkspace,{attachTo:document.body,global:{plugins:[ElementPlus,router],stubs:{ProductProductionControl:true,ProjectBomPanel:true}}})
  await flushPromises()
}
const buttons=()=>[...document.querySelectorAll<HTMLButtonElement>('button')]
const press=async(label:string)=>{const b=buttons().find(b=>b.textContent?.trim()===label);expect(b).toBeTruthy();b!.click();await flushPromises()}
const enterProduct=async()=>{await wrapper.get('.product-list tbody tr').trigger('click');await flushPromises()}
const openFirst=async()=>{await enterProduct();await wrapper.get('.model-list tbody tr').trigger('click');await flushPromises()}
describe('BOM color catalog',()=>{
  beforeEach(()=>{vi.resetAllMocks();setActivePinia(createPinia());useUserStore().permissions=['bom:publish']
    api.products.mockResolvedValue({content:[{key:'series-1',seriesName:'亮甲 2.0',seriesCode:'SER',modelCount:2}],totalPages:1,totalElements:1})
    api.groups.mockResolvedValue({content:[{key:'poco',colors:[plum,black]},{key:'redmi',colors:[twin]}],totalPages:1,totalElements:2})
    api.detail.mockResolvedValue(payload([candidate,older]));api.scope.mockResolvedValue({revision:'preview-1',removedCount:1,removedArticles:[{code:'OLDSKU'}],count:2,articles:[{code:'SKU01',model:'POCO X8 Pro'},{code:'SKU02',model:'A'}],previousVersions:['V1']});api.publish.mockResolvedValue({})
  })
  afterEach(()=>{wrapper?.unmount();document.body.innerHTML='';setLanguage('zh-CN')})
  it('groups colors by source family and opens the specific source even when item codes coincide',async()=>{
    await start();expect(wrapper.findAll('.product-list tbody tr')).toHaveLength(1);expect(wrapper.text()).not.toContain(plum.modelName)
    expect(api.groups).not.toHaveBeenCalled();expect(api.detail).not.toHaveBeenCalled()
    await enterProduct();expect(wrapper.findAll('.model-list tbody tr')).toHaveLength(2)
    expect(api.groups.mock.calls[0][0].productKey).toBe('series-1');expect(wrapper.findAll('.chip')).toHaveLength(3)
    await wrapper.findAll('.model-list tbody tr')[1].trigger('click');await flushPromises()
    expect(api.detail).toHaveBeenCalledWith('other-member',true);expect(document.querySelector('.identity')?.textContent).toContain(twin.modelName)
    expect(api.options).not.toHaveBeenCalled()
  })
  it('opens the clicked color directly and restores both list levels on return',async()=>{
    await start();await enterProduct();await wrapper.findAll('.chip')[1].trigger('click');await flushPromises()
    expect(api.detail).toHaveBeenCalledWith('black',true)
    wrapper.getComponent(BomCatalogDrawer).vm.$emit('close');await flushPromises()
    expect(wrapper.find('.model-list').exists()).toBe(true);expect(wrapper.findComponent(BomCatalogDrawer).exists()).toBe(false)
    await wrapper.get('.path button').trigger('click');await flushPromises()
    expect(wrapper.find('.product-list').exists()).toBe(true);expect(api.products).toHaveBeenCalledTimes(1)
  })
  it('groups material by node identity, preserves precision and folds only the requested node',async()=>{
    const withMissingUnit={...candidate,items:candidate.items.map((item,i)=>i===0?{...item,unit:null,unitRaw:'张：hoja'}:item)}
    api.detail.mockResolvedValue(payload([withMissingUnit,older]))
    await start();await openFirst()
    const entries=[...document.querySelectorAll('.bom-materials tbody')]
    expect(entries).toHaveLength(2);expect(entries[0].querySelector('th')?.rowSpan).toBe(2)
    expect(entries[0].textContent).toContain('0.2 张');expect(entries[1].textContent).toContain('22.13 张');expect(entries[0].textContent).not.toContain('Papel')
    entries[0].querySelector('button')!.click();await flushPromises();expect(entries[0].textContent).toContain('2 项物料');expect(entries[1].textContent).toContain('22.13 张')
  })
  it('shows exact target and whole-recipe scope, cancels without POST, then submits the selected BOM once',async()=>{
    await start();await openFirst();await press('工程确认 V2')
    expect(document.querySelector('.confirm-target')?.textContent).toContain('亮甲 2.0紫色BOM V2');expect(document.querySelector('.confirm-target')?.textContent).toContain('SKU01')
    expect(api.scope).toHaveBeenCalledWith('purple',201,301);expect(document.body.textContent).toContain('适用范围 · 2 个品目')
    expect(document.body.textContent).toContain('1 个原有品目将移出此配方')
    await press('取消');expect(api.publish).not.toHaveBeenCalled()
    await press('工程确认 V2');await press('确认 V2')
    expect(api.publish.mock.calls).toEqual([[201,'preview-1']]);expect(api.groups).toHaveBeenCalledTimes(2);expect(api.detail).toHaveBeenCalledTimes(2)
  })
  it('requires a fresh review when the published version or covered articles change',async()=>{
    await start();await openFirst();await press('工程确认 V2')
    api.scope.mockResolvedValue({revision:'preview-2',count:1,articles:[{code:'SKU02',model:'A'}],previousVersions:['V1']})
    await press('确认 V2');expect(api.publish).not.toHaveBeenCalled();expect(document.body.textContent).toContain('适用范围或正式版本已变化')
    await press('确认 V2');expect(api.publish).toHaveBeenCalledWith(201,'preview-2')
  })
  it('prevents a slower color response from replacing the current identity and honors read-only access',async()=>{
    let finish!:(v:unknown)=>void
    api.detail.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve})).mockResolvedValueOnce(payload([older]))
    useUserStore().permissions=[];await start();await openFirst()
    wrapper.findComponent(BomCatalogDrawer).findAllComponents({name:'ElSelect'})[0].vm.$emit('change','black')
    await flushPromises();finish(payload([candidate]));await flushPromises()
    expect(document.querySelector('.identity')?.textContent).toContain('SKU02');expect(document.querySelector('.version-line')?.textContent).toContain('V1')
    expect(buttons().some(b=>b.textContent?.includes('工程确认 V2'))).toBe(false);expect(api.publish).not.toHaveBeenCalled()
  })
  it('retains integer zeros, significant decimals and current-language material names',()=>{
    expect(decimalLabel('100.00000000')).toBe('100');expect(decimalLabel('0.00000120')).toBe('0.0000012')
    expect(materialLabel('注塑 PC 部件')).toBe('注塑 PC 部件')
    setLanguage('es');expect(materialLabel('标签纸 Papel para etiquetas')).toBe('Papel para etiquetas')
  })
})
