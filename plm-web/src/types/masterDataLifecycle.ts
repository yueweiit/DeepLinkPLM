export type MasterDataLifecycleAction = 'delete' | 'deactivate' | 'activate'
export interface MasterDataIdentity { id: number | string; name: string; code?: string }
export interface MasterDataInspection extends MasterDataIdentity {
  status: string
  version: number
  canDelete: boolean
  canDeactivate: boolean
  canActivate: boolean
  hasRestrictedReferences: boolean
  references: {kind: string; label: string; count: number; items: {id: number | string; label: string; targetPath?: string; history?: boolean; needsReview?: boolean; matchBasis?: string}[]}[]
  blockedReason?: string
}
export interface MasterDataLifecycleInput { version: number; requestId: string; reason: string }
