import type { CatalogIdentity } from './partnerCatalog'
import type { PartnerFile } from './partner'
import type { PageResponse } from '@/api/request'

export type PhoneIntakeState = 'pending' | 'resolved' | 'deferred'
export type PhoneIntakeClassification = 'missing' | 'review'
export type PhoneIntakeReviewReason = 'name' | 'variant' | 'shared_group' | 'incomplete' | 'code'
export type PhoneIntakeAction = 'create' | 'link' | 'defer' | 'reopen' | 'revise'
export interface PhoneIntakeEntryInput {
  supplierIndex: string | number
  componentText: string
  brandId?: number
  classification: PhoneIntakeClassification
  reviewReason?: PhoneIntakeReviewReason | '' | null
  reason: string
}
export interface PhoneIntakeSourceInput extends CatalogIdentity {
  supplierCode: string
  entries: PhoneIntakeEntryInput[]
}

export interface PhoneIntakeSource {
  id?: number
  supplierCode: string
  supplierName: string
  sourceKind: 'quote' | 'selection'
  recordId: number
  rowId: string
  rowVersion: number
  recordVersion: number
  sourceCode?: string
  sourceName?: string
  styleName?: string
  supplierIndex: string | number
  rawBrandGroup?: string
  rawModelText: string
  rawStatus?: string
  rawNote?: string
  componentText?: string
  modelSheetRefs?: { id: number }[]
  modelSheetFileDetails?: PartnerFile[]
  sourceDigest?: string
  sourceUnavailable?: boolean
  latest?: boolean
  createdAt?: string
}

export interface PhoneIntakeCandidate {
  id: number
  componentText: string
  modelName: string
  brandId: number | null
  brandName?: string
  classification: PhoneIntakeClassification
  reviewReason?: PhoneIntakeReviewReason | '' | null
  reason: string
  state: PhoneIntakeState
  version: number
  resolvedPhoneModelIds: number[]
  sourceCount: number
  canMaintain: boolean
  createdAt?: string
  updatedAt?: string
}
export interface PhoneIntakePage extends PageResponse<PhoneIntakeCandidate> {
  canMaintain: boolean
  summary: Record<PhoneIntakeState | PhoneIntakeClassification, number>
}
export interface PhoneIntakeDetail extends PhoneIntakeCandidate { sources: PhoneIntakeSource[] }
export interface PhoneIntakeSourcePreview {
  entries: (PhoneIntakeEntryInput & { candidateId?: number; action: 'new' | 'append' | 'unchanged' | 'blocked'; existingState?: PhoneIntakeState; source: PhoneIntakeSource; messages?: string[] })[]
  summary: { entryCount: number; newCount: number; appendCount: number; reusedCount: number }
  canSave: boolean
  previewToken: string
  messages: string[]
}
export interface PhoneIntakeOriginal {
  state: PhoneIntakeState
  classification: PhoneIntakeClassification
  reviewReason?: PhoneIntakeReviewReason | '' | null
  brandId: number | null
  componentText: string
  resolvedPhoneModelIds: number[]
}
export interface PhoneIntakeResolutionInput {
  version: number
  original: PhoneIntakeOriginal
  action: PhoneIntakeAction
  brandId?: number | null
  componentText?: string
  classification?: PhoneIntakeClassification
  reviewReason?: PhoneIntakeReviewReason | null
  modelName?: string
  aliases?: string[]
  phoneModelIds?: number[]
  reason: string
}
export interface PhoneIntakeResolutionPreview {
  canSave: boolean
  previewToken: string
  action: PhoneIntakeAction
  before: PhoneIntakeCandidate
  after: Partial<PhoneIntakeCandidate> & { state: PhoneIntakeState; resolvedPhoneModelIds: number[] }
  proposedPhone?: { brandId: number; modelName: string; aliases: string[]; codeItemId: null }
  sources: (PhoneIntakeSource & { currentRowVersion?: number; currentRecordVersion?: number; addedPhoneModelIds?: number[]; alreadyLinkedPhoneModelIds?: number[]; willUpdate: boolean; requiresNewPhone?: boolean })[]
  summary: { sourceCount: number; changedSourceCount: number }
  messages: string[]
  blockers: string[]
}
export interface PhoneIntakeResolutionResult { item: PhoneIntakeCandidate; replayed: boolean; createdPhoneModelId?: number; updatedSourceCount: number }
export interface PhoneIntakeSourceResult { items: PhoneIntakeCandidate[]; addedSourceCount: number; replayed: boolean }
