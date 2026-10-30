import type { CompatiblePhone } from './molds'
import type { PartnerFile } from './partner'
import type { SelectionReference, SelectionRangeSnapshot, SupplierPriceOption, SupplierRangeReference, SupplyPair } from './partnerSelections'

export interface CatalogSupplier {
  id?: number
  supplierCode: string
  supplierName: string
  shortCode?: string
  status?: string
  styleCount: number
}

export interface CatalogPage<T> {
  content: T[]
  totalElements: number
  page: number
  size: number
  totalPages: number
  canWrite: boolean
  canReadCost: boolean
  supplier?: CatalogSupplier
}

export interface CatalogIdentity {
  sourceKind: 'quote' | 'selection'
  recordId: number
  rowId: string
  rowVersion: number
  recordVersion: number
}

export type CatalogQuoteOption = SupplierPriceOption
export type CatalogPhoneScopeMode = 'supplier' | 'unspecified' | 'unresolved'

export interface CatalogModelEntryInput {
  supplierIndex: string | number
  rawBrandGroup: string
  rawModelText: string
  rawStatus: string
  rawNote: string
  matchText?: string
  matchNote?: string
  phoneModelIds?: number[]
}
export interface CatalogModelEntry extends CatalogModelEntryInput {
  phoneModelIds: number[]
  phones?: CompatiblePhone[]
  issues?: string[]
  matchState?: string
  matchMethod?: string
  automaticPhoneModelIds?: number[]
  automaticIssues?: string[]
}
export interface CatalogModelSheetInput extends CatalogIdentity {
  supplierCode: string
  entries: CatalogModelEntryInput[]
  modelSheetRefs: { id: number }[]
}
export interface CatalogModelSheetState {
  supplierModelEntries: CatalogModelEntry[]
  modelSheetRefs: { id: number }[]
  phoneModelIds: number[]
}
export interface CatalogModelSheetPreview {
  canSave: boolean
  previewToken: string
  entries: CatalogModelEntry[]
  messages: string[]
  summary: { entryCount: number; matchedEntryCount: number; manualEntryCount?: number; automaticIssueEntryCount?: number; unresolvedEntryCount: number; phoneCount: number }
  before?: CatalogModelSheetState
  after?: CatalogModelSheetState
  preservedIndependentPhoneModelIds?: number[]
  removedPhoneModelIds?: number[]
}
export interface CatalogModelSheetResult { style: CatalogStyle; replayed: boolean; changed: boolean }

export interface CatalogStyle extends CatalogIdentity {
  discarded?: boolean
  sourceCode: string
  sourceName: string
  recordVersion: number
  supplierCode: string
  supplierName: string
  name: string
  supplierItemCode?: string
  series?: string
  modelText?: string
  phoneModelIds: number[]
  phoneScopeMode?: CatalogPhoneScopeMode
  unavailablePhoneModelIds?: number[]
  phones: CompatiblePhone[]
  phoneMatchIssues?: string[]
  phoneMatchSummary?: { state: string; matchedCount: number; codeGroupCount: number; unresolvedCount: number; sourceText?: string }
  supplierModelEntries?: CatalogModelEntry[]
  modelSheetRefs?: SelectionReference[]
  modelSheetFileDetails?: PartnerFile[]
  canMaintainModelSheet?: boolean
  color?: string
  supplierColorIds?:number[]
  supplierColors?:{id:number;name:string;supplierColorCode?:string}[]
  material?: string
  specification?: string
  thickness?: string
  features?: string
  leadTimeNote?: string
  sampleFeeNote?: string
  moq?: string
  packaging?: string
  notes?: string
  refs: SelectionReference[]
  fileDetails: PartnerFile[]
  styleKey?: string
  configKey?: string
  quoteVersion?: string
  sourcePosition?: string
  sourceSheet?: string
  sourceVersion?: string
  confirmed?: boolean
  productId?: number
  productCode?: string
  unitPrice?: string | number | null
  currency?: string
  priceNote?: string
  priceOptions?: CatalogQuoteOption[]
  canWrite?: boolean
  canReadCost?: boolean
  sourceOriginals?: PartnerFile[]
  rangeRefs?: SupplierRangeReference[]
  rangeSnapshots?: SelectionRangeSnapshot[]
  pairings?: SupplyPair[]
  sourcePurpose?: string
  catalogSource?: boolean
  catalogStyleId?: number
  catalogStyleCode?: string
  catalogStyleLabel?: string
  currencySource?: 'explicit' | 'business_default' | 'historical_unknown'
  priceUnit?: string
  moqQuantity?: string | number | null
  moqUnit?: string
  moqScope?: string
  moqNote?: string
}

export interface CatalogSource {
  id: number
  code: string
  name: string
  kind: 'supplier_quote' | 'selection'
  sourceKind: 'quote' | 'selection'
  supplierCode?: string
  supplierName?: string
  version: number
  rowCount: number
  purpose?: string
  catalogSource?: boolean
  canWrite?: boolean
  files?: PartnerFile[]
  readOnly?: boolean
  canImport?: boolean
}

export interface CatalogPurposePreview {
  id: number
  code: string
  name: string
  kind: string
  version: number
  rowCount: number
  purpose: string
  targetPurpose: 'catalog_source'
  canChange: boolean
  messages: string[]
  previewToken: string
}

export interface CatalogSelectionItem extends CatalogIdentity {
  sampleQty?: number
  phoneModelIds: number[]
  selectedPairs?: { phoneModelId: number; colorId: number }[]
}

export interface CatalogAddition {
  supplierCode: string
  targetMode?: 'supplier_directory'
  requestId: string
  targetSelectionId?: number
  targetVersion?: number
  selectionName?: string
  items: CatalogSelectionItem[]
  previewToken?: string
}

export interface CatalogAdditionPreview {
  targetSelectionId?: number
  targetVersion?: number
  targetName?: string
  canAdd: boolean
  previewToken: string
  items: (CatalogSelectionItem & { name: string; phones?: CompatiblePhone[]; phoneScopeMode?: CatalogPhoneScopeMode; addedPhoneModelIds: number[]; reusedPhoneModelIds: number[]; messages: string[]; action?: 'new' | 'merge' | 'reused' })[]
  addedCount: number
  reusedCount: number
  newPhoneCount: number
  updatedCount?: number
  messages: string[]
}

export interface CatalogAdditionResult {
  selection: { id: number; code: string; name: string; version: number }
  rowIds: string[]
  addedCount: number
  reusedCount: number
  replayed: boolean
  updatedCount?: number
  newPhoneCount?: number
}

export interface CatalogCodeOption {id:number;code:string;label?:string;name:string;supplierCode:string;supplierName:string}
export interface CatalogIdentityChoice extends CatalogIdentity {catalogStyleId?:number}
export interface CatalogIdentityPreview {supplierCode:string;items:(CatalogIdentityChoice & {name:string;catalogStyleCode?:string;catalogStyleLabel?:string;action:'new'|'link'|'unchanged';beforeFields:Record<string,unknown>;normalizedFields:Record<string,unknown>;messages:string[]})[];canConfirm:boolean;previewToken:string;messages:string[]}
