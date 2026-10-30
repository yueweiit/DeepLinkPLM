import type { PartnerFile } from './partner'
import type { CompatiblePhone } from './molds'

export type DevelopmentStatus = 'unknown' | 'claimed' | 'preferred' | 'alternative' | 'disputed'
export interface SelectionReference { id:number; type:string; name?:string; version?:string }
export interface SupplierRangeReference { type:'model'|'color'; reference:string; version?:number }
export interface SelectionRangeSnapshot {id:number;name?:string;code?:string;version:number;currentVersion:number;versionChanged?:boolean;memberChanges?:{added:unknown[];removed:unknown[]};[key:string]:unknown}
export interface SupplyPair { supplierCode?:string; styleKey?:string; configKey?:string; quoteVersion?:string; phoneModelId:number; colorId:number; state:'available'|'unavailable'|'unverified'; evidence?:string; colorSnapshot?:{id:number;name:string;supplierColorCode?:string} }
export interface SelectionSampleBatch { id:number; code:string; label?:string; displayLabel?:string; receivedAt?:string; date?:string; qty?:number }
export interface SupplierQuoteFields { unitPrice?:string|number|null;currency?:string;currencySource?:'explicit'|'business_default'|'historical_unknown';priceUnit?:string;moqQuantity?:string|number|null;moqUnit?:string;moqScope?:string;moqNote?:string }
export interface SupplierPriceOption extends SupplierQuoteFields { label?:string;priceNote?:string;moq?:string;packaging?:string }
export interface SelectionRow {
  currentCatalog?:Record<string,unknown>;recordedName?:string
  rowId:string; rowVersion?:number;canReviseSelection?:boolean;selectionChangeBlockedReason?:string; selected:boolean; name:string; series:string; supplierCode:string; supplierItemCode:string
  material:string; specification:string; color:string; modelText:string; phoneModelIds:number[]
  phoneScopeDeclaration?:'not_provided'|''
  availablePhoneModelIds:number[]; unavailablePhoneModelIds:number[]; supplyScopeNote:string
  unitPrice?:string|number; currency:string; priceNote:string; moq:string; packaging:string; sampleQty?:number
  notes:string; sourcePosition:string; sameStyleKey:string; developmentStatus:DevelopmentStatus
  thickness?:string;features?:string;leadTimeNote?:string;sampleFeeNote?:string;priceOptions?:SupplierPriceOption[]
  developedOn:string; developmentEvidence:string; refs:SelectionReference[]; targetSpecId?:number
  targetColorMappingAcknowledged?:boolean
  productId?:number; specId?:number; confirmed?:boolean; confirmedAt?:string; discarded?:boolean; confirmationSnapshot?:unknown
  phones?:CompatiblePhone[]; availablePhones?:CompatiblePhone[]; unavailablePhones?:CompatiblePhone[]
  rangeRefs:SupplierRangeReference[]; rangeSnapshots?:SelectionRangeSnapshot[]; pairings:SupplyPair[]
  rangeVersionAcknowledgements?:{id:number;version:number;currentVersion:number}[]
  selectedPairs:{phoneModelId:number;colorId:number}[]; supplierColorIds?:number[]; supplierColors?:{id:number;name:string;supplierColorCode?:string}[];newSupplierColors?:string[]; styleKey?:string; configKey?:string; quoteVersion?:string
  productCode?:string; sampleBatches?:SelectionSampleBatch[]
  fileDetails?:PartnerFile[];sampleBatchCodes?:string[]
  specLinks?:{specId:number;productId:number;phoneModelId:number;colorId:number;specName:string}[];specIds?:number[]
  _images?:{id:string;name:string;sha256:string;sourceRow:number;sourceSheet:string}[]
  sourcePhoneModelIds?:number[]
  sourcePhones?:CompatiblePhone[]
  catalogSourceSnapshot?:Record<string,unknown>
  catalogStyleId?:number;catalogStyleCode?:string;catalogStyleLabel?:string
  currencySource?:SupplierQuoteFields['currencySource'];priceUnit?:string;moqQuantity?:string|number|null;moqUnit?:string;moqScope?:string;moqNote?:string
}
export interface SelectionEvent { id:number; action:string; operator?:string; time:string }
export interface SelectionSheet {
  id:number; version:number; name:string; code?:string; status?:string; rows:SelectionRow[]
  files:PartnerFile[]; refs:SelectionReference[]; events:SelectionEvent[]; canWrite?:boolean; canReadCost?:boolean
  sourceOriginals?:PartnerFile[]
  sourceOriginalCount?:number
  catalogSource?:boolean
  purpose?:string
}
export interface SelectionPage { content:SelectionSheet[]; totalElements:number; page:number; size:number; totalPages:number }
export interface SelectionSave { name:string; rows?:SelectionRow[]; refs:SelectionReference[]; version:number; requestId:string }
export interface SelectionRowPage { content:SelectionRow[]; totalElements:number; page:number; size:number; totalPages?:number }
export interface SelectionFilter { q?:string; supplierCode?:string; state?:string; page:number; size:number }
export interface SelectionMutation { version:number; requestId:string; operations:{op:'upsert'|'exclude'|'clear';rowId:string;rowVersion?:number;changes?:Partial<SelectionRow>}[] }
export interface SelectionGroup { groupId:string; rowIds:string[]; productId?:number; productName?:string; targetSpecId?:number }
export interface SelectionConfirmation {version:number;requestId:string;rowIds:string[];groups?:SelectionGroup[]}
export interface SelectionConfirmationRow {rowId:string;name:string;catalogStyleId?:number;catalogStyleCode?:string;catalogStyleLabel?:string;phoneModelIds?:number[];phones?:CompatiblePhone[];selectedPairs?:{phoneModelId:number;colorId:number;colorSnapshot?:SupplyPair['colorSnapshot']}[];pairings?:SupplyPair[];productCode?:string;specId?:number;targetSpecId?:number;messages:string[];canConfirm:boolean}
export interface SelectionConfirmationPreview { rows?:SelectionConfirmationRow[]; groups?:SelectionGroup[]; issues?:{rowId?:string;message:string}[]; errors?:string[]; canConfirm?:boolean; errorCount?:number; [key:string]:unknown }
export interface SelectionReceiptInputRow {clientRowId:string;rowId:string;rowVersion:number;phoneModelId?:number;colorId?:number;colorName?:string;qty?:number;sampleIdentityId?:number;identityReason?:string}
export interface SelectionReceiptInput {version:number;requestId:string;date:string;location:string;ownerName:string;supplierShipmentId?:number;rows:SelectionReceiptInputRow[]}
export interface SelectionReceiptPreviewRow extends SelectionReceiptInputRow {name:string;catalogStyleId?:number;catalogStyleLabel?:string;supplierCode?:string;phones:CompatiblePhone[];colors:{id:number;name:string;code?:string;supplierColorCode?:string;styleKey?:string;configKey?:string}[];colorResolution?:'new'|'matched'|'ambiguous'|'missing';resolvedColorId?:number;refs:({id:number;name?:string;type?:string;contentType?:string})[];phoneName?:string;colorName?:string;sampleIdentityId?:number;sampleCode?:string;messages:string[];canSave:boolean}
export interface SelectionReceiptPreview {id:number;version:number;canSave:boolean;messages:string[];rows:SelectionReceiptPreviewRow[]}
export interface SelectionReceiptResult {id:number;version:number;batchCount:number;batches:{clientRowId:string;rowId:string;id:number;code:string;displayLabel?:string;name:string;phoneModelId:number;phoneName:string;colorId:number;colorName:string;qty:number;supplierName?:string;sampleIdentityId?:number;sampleCode?:string}[]}
export interface SelectionFileJob { id:string; kind?:'import'|'export'; status:string; sheets?:{name:string;index:number}[]; selectedSheets?:{name:string;supplierCode:string;supplierConfirmed?:boolean;confirmedBy?:string;confirmedAt?:string}[]; stats?:{rowCount?:number;errorCount?:number;imageCount?:number;unassignedImageCount?:number}; error?:string; message?:string; [key:string]:unknown }
export interface SelectionStagedImage {id:string;rowId?:string;sourceSheet:string;sourceRow:number;name:string;sha256:string}
export interface SelectionUpload { id:string;chunkSize:number;partCount:number;status:string;receivedParts:number[];partHashes?:Record<string,string>;attachmentId?:number }
export interface SelectionStagedRow {rowId:string;rowNumber:number;sourceSheet:string;action:SelectionImportAction;values:Partial<SelectionRow>;original?:Partial<SelectionRow>;messages:string[];version:number}
export type SelectionImportAction = 'new'|'update'|'same'|'conflict'

export interface SelectionTransition {version:number;requestId:string;action:'edit'|'cancel'|'restore';rows:{rowId:string;rowVersion:number}[];reason?:string}
