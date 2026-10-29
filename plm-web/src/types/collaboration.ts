export type PlanView = 'market' | 'delivery' | 'production'
export type ImportKind = PlanView | 'schedule'
export type FieldGroup = 'market' | 'engineering' | 'logistics' | 'mexico' | 'production'
export type EntityType = 'item' | 'mold_set'
export interface PhoneSetCompatibility { moldSetId:number; pcConfirmed:boolean; tpuConfirmed:boolean; pcEvidence:string|null; tpuEvidence:string|null }
export interface PhoneCompatibility { phoneModelId:number; setIds:number[]; pcConfirmed:boolean; tpuConfirmed:boolean; evidence:string; setCompatibilities?:PhoneSetCompatibility[] }
export interface MoldApplicationPhone {phoneModelId:number;brandId?:number|null;brandName:string;modelName:string;phoneModelCode:string|null;codeItemId?:number|null}
export interface MoldApplicationLink {applicationId:number;approvalNo:string|null;progressStatus:string;provisioningStatus:string;executionIntent:string;suspended:boolean;standardPhone?:MoldApplicationPhone|null}
export type CellValue = string | number | boolean | number[] | PhoneCompatibility[] | null
export interface PlanField { key: string; label: string; group: FieldGroup | 'finance'; kind: string; maxLength: number }
export interface ProjectReference { projectId: number; productCode: string; productName: string; color: string | null; status: string }
export interface MoldScheduleTask { moldSetId: number; assetNumber: string; styleName: string; compatibleModels: string; taskKey: string; title: string; group: string; ownerUserId: number | null; dueDate: string | null; overdue: boolean; dueField?: string; overdueDays?: number; itemId?: number; category?: 'milestone'|'missing'|'review'; inputFields?: string[]; supportFields?: string[] }
export interface CollaborationRow {
  masterDataStatus?:'active'|'inactive'; masterDataVersion?:number; setMasterDataStatus?:'active'|'inactive'; setMasterDataVersion?:number;
  [key: string]: unknown
  itemId: number; productLineId: number | null; styleName: string; brand: string | null
  brandId?: number | null; brandName?: string | null; brandIds?: number[]; brandSource?: string; brandConflict?: boolean
  compatibleModels: string; phoneModelCode: string | null; phonePrice: number | null; priceCurrency: string | null
  productLineName?: string | null; phoneModelCodeItemId?: number | null; phoneModelCodeName?: string | null
  linkedPhoneModelCode?: string | null; phoneModelLinkStatus?: 'linked'|'disabled'|'unlinked'|'missing'
  salesPlatform: string | null; referenceUrl: string | null; notes: string | null
  pcTotal: number | null; tpuTotal: number | null; finishedTotal: number | null; productionNotes: string | null
  projectCount: number; projects: ProjectReference[]; moldSetCount: number; status: string; updatedAt: string
  moldSetId?: number; setNo?: number; setName?: string; openedDate?: string | null
  plannedMoldDeliveryDate?: string | null; actualShipDate?: string | null; plannedFactoryArrivalDate?: string | null
  actualMexicoArrivalDate?: string | null; arrivalConfirmedBasisDate?: string | null
  suggestedFactoryArrivalDate?: string | null; arrivalNeedsReview?: boolean
  pcStatus?: string; tpuStatus?: string; pcMoldCodeId?: number | null; tpuMoldCodeId?: number | null
  assetNumber?: string; pcAssetNumber?: string; tpuAssetNumber?: string; sourceMoldSetId?: number | null
  manufacturerName?: string | null; ownerUserId?: number | null; ownerName?: string | null; deliveryStatus?: string
  pcOriginalMoldCode?: string | null; tpuOriginalMoldCode?: string | null
  pcMoldCode?: string | null; tpuMoldCode?: string | null; pcCatalogMoldCode?: string | null; tpuCatalogMoldCode?: string | null
  pcInventoryId?: number | null; tpuInventoryId?: number | null; pcInventoryName?: string | null; tpuInventoryName?: string | null
  pcCurrentLocation?: string | null; tpuCurrentLocation?: string | null; pcLocationDetail?: string | null; tpuLocationDetail?: string | null
  pcFactoryArrivalDate?: string | null; tpuFactoryArrivalDate?: string | null
  pcAcceptanceStatus?: string; tpuAcceptanceStatus?: string; pcAcceptanceDate?: string | null; tpuAcceptanceDate?: string | null
  pcAcceptanceIssues?: string | null; tpuAcceptanceIssues?: string | null
  tasks?: MoldScheduleTask[]; overdueTasks?: MoldScheduleTask[]
  members?:{phoneModelId:number;modelName:string;brandName?:string;phoneModelCode?:string|null}[]
  applicationLinks?:MoldApplicationLink[]; schedulePaused?:boolean; applicationPlanned?:boolean
}
export interface ProductLineOption { productLineId: number; productName: string; productCode: string; status: string }
export interface CollaborationOptions {
  productLines: ProductLineOption[]; itemFields: PlanField[]; moldFields: PlanField[]
  writableGroups: FieldGroup[]; administrator: boolean; userId: number
  users?: { userId: number; username: string; displayName: string }[]
}
export interface PlanQuery { projectId?:number; view: PlanView; page: number; size: number; keyword?: string; productLineId?: number; status?: string; mineOnly?: boolean; itemId?: number; moldSetId?: number; location?: string }
export interface CellChange { field: string; before: CellValue; after: CellValue; reason?: string }
export interface RowPatch { entityType: EntityType; id: number; changes: CellChange[]; expectedValues?: Record<string,CellValue> }
export interface CellConflict { field: string; label: string; before: CellValue; current: CellValue; attempted: CellValue; expectedOnly?: boolean }
export interface RowResult { entityType: EntityType; id: number; status: 'saved' | 'invalid' | 'conflict' | 'forbidden'; message: string; conflicts: CellConflict[]; row: CollaborationRow | null }
export interface PlanHistory { historyId: number; entityType: EntityType; entityId: number; fieldName: string; before: unknown; after: unknown; reason: string | null; actorName: string; changedAt: string }
export interface PlanUser { userId: number; username: string; displayName: string; departmentName: string | null; allPermissions: boolean }
export interface AssignmentSettings { groups: Record<FieldGroup, number[]>; users: PlanUser[] }
export interface ProjectOption { projectId: number; productLineId: number; productCode: string; productName: string; model: string; phoneModelCode: string | null; color: string | null; status: string; linkedItemId: number | null }
export interface MoldCodeOption { moldCodeId: number; productId: number; moldCode: string; moldName: string | null; keyCode: string | null }
export interface ImportRow {
  rowNo: number; sheetName: string; raw: Record<string, string>; values: Record<string, CellValue>; before: Record<string, CellValue>
  itemId: number | null; moldSetId: number | null; productLineId: number | null
  styleName: string; brand: string; compatibleModels: string; newMoldSet: boolean; alreadyImported: boolean; issues: string[]
}
export interface ImportPreview { importToken: string; kind: ImportKind; fileName: string; rows: ImportRow[]; total: number; needsReview: number }
export interface ImportDecision extends Omit<ImportRow, 'raw' | 'issues' | 'alreadyImported'> { skip: boolean; reviewReason: string; confirmCompatibility?: boolean }
