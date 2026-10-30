import { beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(),patch:vi.fn() }))
vi.mock('../../request', () => ({ default: api, unwrapResponse: <T>(response: { data: { data: T } }) => response.data.data }))
import { addCatalogSelection, catalogStyle, catalogStyles, catalogSuppliers, catalogSources, createCatalogSource, previewCatalogSelection, previewCatalogSourcePurpose, changeCatalogSourcePurpose,catalogStyleIdentities,previewCatalogIdentities,saveCatalogIdentities,catalogSourceRows,patchCatalogSourceRows,previewCatalogModelSheet,saveCatalogModelSheet,uploadCatalogModelSheetFile,updateCatalogStyle } from '../partnerCatalog'
beforeEach(() => { vi.clearAllMocks(); api.get.mockResolvedValue({ data: { data: {} } }); api.post.mockResolvedValue({ data: { data: {} } });api.patch.mockResolvedValue({data:{data:{}}}) })
describe('supplier-scoped catalog API', () => {
  it('previews and confirms one versioned legacy source model sheet without sending an entire source or creating products',async()=>{const data={supplierCode:'PJ',sourceKind:'selection' as const,recordId:3,rowId:'one-style',rowVersion:7,recordVersion:9,entries:[{supplierIndex:'001',rawBrandGroup:'OPPO',rawModelText:'原A/原B',rawStatus:'试模',rawNote:'待确认交期'}],modelSheetRefs:[{id:81}]};await previewCatalogModelSheet(data);expect(api.post).toHaveBeenLastCalledWith('/partner-catalog/styles/model-sheet-preview',data);await saveCatalogModelSheet({...data,requestId:'same-save',previewToken:'signed'});expect(api.post).toHaveBeenLastCalledWith('/partner-catalog/styles/model-sheets',{...data,requestId:'same-save',previewToken:'signed'});expect(api.post.mock.calls.every(call=>!call[0].includes('partner-products')&&!call[0].includes('import-jobs'))).toBe(true)})
  it('uploads a scoped model-sheet photo with identity, strong checksum and stable retry request rather than opening legacy whole-sheet imports',async()=>{const identity={supplierCode:'PJ',sourceKind:'selection' as const,recordId:3,rowId:'one-style',rowVersion:7,recordVersion:9,requestId:'same-image',sha256:'a'.repeat(64)},file=new File(['real png'],'本款型号图.png',{type:'image/png'});await uploadCatalogModelSheetFile(identity,file);const [url,body]=api.post.mock.calls[0];expect(url).toBe('/partner-catalog/styles/model-sheet-file');expect(body).toBeInstanceOf(FormData);for(const [key,value]of Object.entries(identity))expect(body.get(key)).toBe(String(value));expect(body.get('file')).toBe(file);expect(body.get('purpose')).toBeNull()})
  it('searches stable CP identities and separates their signed preview from the explicit metadata save',async()=>{await catalogStyleIdentities({supplierCode:'XD',keyword:'CP-000018',page:2,size:20});expect(api.get).toHaveBeenLastCalledWith('/partner-catalog/style-identities',{params:{supplierCode:'XD',keyword:'CP-000018',page:2,size:20}});expect(api.post).not.toHaveBeenCalled();const data={supplierCode:'XD',items:[{sourceKind:'selection' as const,recordId:2,rowId:'original',rowVersion:3,recordVersion:5,catalogStyleId:18}]};await previewCatalogIdentities(data);expect(api.post).toHaveBeenLastCalledWith('/partner-catalog/styles/identity-preview',data);await saveCatalogIdentities({...data,reason:'工程明确同款',requestId:'mapping',previewToken:'signed'});expect(api.post).toHaveBeenLastCalledWith('/partner-catalog/styles/identities',{...data,reason:'工程明确同款',requestId:'mapping',previewToken:'signed'})})
  it('writes online candidate rows to the existing SQ line owner rather than to selections or products',async()=>{await catalogSourceRows(8,{supplierCode:'XD',keyword:'真实款',page:81,size:30});expect(api.get).toHaveBeenLastCalledWith('/partner-catalog/styles',{params:{supplierCode:'XD',keyword:'真实款',page:81,size:30,sourceRecordId:8,sourceKind:'quote'}});const data={version:3,requestId:'source-line',operations:[{rowId:'stable',rowVersion:1,changes:{name:'真实款',moqQuantity:20,moqUnit:'个',moqScope:'model_color'}}]};await patchCatalogSourceRows(8,data);expect(api.patch).toHaveBeenCalledWith('/partner-operations/supplier_quote/8/lines',data);expect(api.post).not.toHaveBeenCalled()})
  it('pages suppliers and their styles with a required supplier identity', async () => {
    await catalogSuppliers({ q: '熊大', page: 2, size: 24 })
    expect(api.get).toHaveBeenCalledWith('/partner-catalog/suppliers', { params: { q: '熊大', page: 2, size: 24 } })
    await catalogStyles({ supplierCode: 'SUP/1', keyword: '0185', phoneModelId: 17, page: 81, size: 30 })
    expect(api.get).toHaveBeenLastCalledWith('/partner-catalog/styles', { params: { supplierCode: 'SUP/1', keyword: '0185', phoneModelId: 17, page: 81, size: 30 } })
  })
  it('uses stable source row identity rather than a display row number', async () => {
    await catalogStyle('SUP/1', { sourceKind: 'quote', recordId: 44, rowId: 'row/with space' })
    expect(api.get).toHaveBeenCalledWith('/partner-catalog/styles/quote/44/row%2Fwith%20space', { params: { supplierCode: 'SUP/1' } })
    const changes={changes:{name:'凡影精孔手机壳',color:'蓝色',material:'PC',unitPrice:'0.00'},rowVersion:3,recordVersion:8,requestId:'rename-style'};await updateCatalogStyle('SUP/1',{sourceKind:'selection',recordId:44,rowId:'row/with space'},changes);expect(api.patch).toHaveBeenCalledWith('/partner-catalog/styles/selection/44/row%2Fwith%20space',changes,{params:{supplierCode:'SUP/1'}})
  })
  it('adds only versioned source references to the existing selection workflow', async () => {
    const data = { supplierCode: 'SUP/1', targetSelectionId: 77, targetVersion: 4, requestId: 'same-idempotency-key', previewToken: 'signed', items: [{ sourceKind: 'quote' as const, recordId: 44, rowId: 'stable-row', rowVersion: 8, recordVersion: 10, phoneModelIds: [17,18] }] }
    await addCatalogSelection(data)
    expect(api.post).toHaveBeenCalledWith('/partner-catalog/selection-additions', data)
    expect(api.post.mock.calls[0][0]).not.toContain('partner-products')
  })
  it('creates and pages supplier source records without creating a selection sheet',async()=>{
    await catalogSources({supplierCode:'SUP/1',page:2,size:20,purpose:'historical'})
    expect(api.get).toHaveBeenLastCalledWith('/partner-catalog/sources',{params:{supplierCode:'SUP/1',page:2,size:20,purpose:'historical'}})
    const data={name:'供应商本次目录',supplierCode:'SUP/1',requestId:'source-request'};await createCatalogSource(data)
    expect(api.post).toHaveBeenLastCalledWith('/partner-catalog/sources',data)
  })
  it('previews explicit models separately from the write and requires deliberate per-source purpose confirmation',async()=>{
    const data={supplierCode:'SUP/1',selectionName:'我方本次',items:[{sourceKind:'quote' as const,recordId:44,rowId:'stable',rowVersion:1,recordVersion:2,phoneModelIds:[18]}]}
    await previewCatalogSelection(data);expect(api.post).toHaveBeenLastCalledWith('/partner-catalog/selection-additions/preview',data)
    const purpose={version:5,purpose:'catalog_source' as const,reason:'核对原件为全量候选'}
    await previewCatalogSourcePurpose(4,purpose);expect(api.post).toHaveBeenLastCalledWith('/partner-catalog/sources/4/purpose-preview',purpose)
    await changeCatalogSourcePurpose(4,{...purpose,previewToken:'confirmed-diff',requestId:'same-purpose-id'});expect(api.post).toHaveBeenLastCalledWith('/partner-catalog/sources/4/purpose',{...purpose,previewToken:'confirmed-diff',requestId:'same-purpose-id'})
  })
  it('uses the same preview and write endpoints for a supplier directory intent without selecting a guessed target',async()=>{const input={supplierCode:'SUP/1',targetMode:'supplier_directory' as const,items:[{sourceKind:'quote' as const,recordId:44,rowId:'stable-row',rowVersion:8,recordVersion:10,phoneModelIds:[17,18],sampleQty:2}]};await previewCatalogSelection(input);expect(api.post).toHaveBeenLastCalledWith('/partner-catalog/selection-additions/preview',input);const command={...input,requestId:'same-direct-command',previewToken:'signed-direct'};await addCatalogSelection(command);await addCatalogSelection(command);expect(api.post.mock.calls.slice(1)).toEqual([['/partner-catalog/selection-additions',command],['/partner-catalog/selection-additions',command]]);expect(api.get).not.toHaveBeenCalled();expect(command).not.toHaveProperty('targetSelectionId');expect(command).not.toHaveProperty('selectionName');expect(command.items[0].sampleQty).toBe(2)})

})
