export type PartnerKind = 'partner-products' | 'partner-sales' | 'samples'
export type SampleSource = 'self' | 'partner' | 'catalog'
export type SettlementMode = 'net' | 'gross'
export type SampleAction = 'loan'|'return'|'move'|'retain'|'gift'|'consume'|'scrap'|'loss'|'count'|'undo'
export type SalesAction = 'quote_confirm'|'order_confirm'|'agreement'|'receipt'|'refund'|'supplierpay'|'supplierrefund'|'feereceipt'|'feerefund'|'offsetreverse'|'ship'|'reship'|'returngoods'|'cancel'|'shipreverse'|'amend'|'close'|'reopen'
/** Historic engineering payloads vary by workflow; IDs and actions remain typed at the API boundary. */
export type BusinessData = Record<string, any>
export interface PartnerRow extends BusinessData { id:number; code:string; name:string; status:string; version:number }
export interface PartnerPage { content:PartnerRow[]; totalElements:number; page:number; size:number; totalPages:number; statistics?:{status:string;count:number}[] }
export interface PartnerOptions { specs:BusinessData[]; suppliers:BusinessData[]; customers:BusinessData[]; internalSkus?:BusinessData[]; canReadMoney:boolean;canWriteMoney:boolean;canConfirmMoney:boolean }
export interface PartnerFile {id:number;name:string;version:string;type:string;size:number}
export interface FieldOption { value:string|number; label:string }
export interface BusinessField {key:string;label:string;type?:'text'|'textarea'|'number'|'money'|'date'|'select';options?:FieldOption[];required?:boolean;more?:boolean}
export interface ImportPreview { token:string;kind:string; rows:BusinessData[];errorCount:number }
