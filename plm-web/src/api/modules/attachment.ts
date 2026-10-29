import request, { unwrapPage, unwrapResponse, type PageResponse } from '../request'

/* ========== 时间轴节点附件（文档 7.6） ========== */

export interface AttachmentVO {
  attachmentId: number
  ownerObjectType: string
  ownerObjectId: number
  projectId?: number | null
  projectCode?: string | null
  projectName?: string | null
  timelineNodeKey?: string | null
  timelineStageCode?: string | null
  timelineStageName?: string | null
  timelineStepCode?: string | null
  timelineStepName?: string | null
  fileCategory: string
  fileName: string
  originalFileName: string
  fileExt: string
  contentType?: string | null
  fileSize: number
  checksum: string
  storageType: string
  storageKey: string
  previewable?: boolean
  previewType?: string | null
  previewStatus?: string | null
  previewUrl?: string | null
  downloadUrl?: string | null
  previewErrorMessage?: string | null
  versionNo: string
  status: string
  remark?: string | null
  familyId?: number | null
  changeReason?: string | null
  releasedAt?: string | null
  releasedBy?: string | number | null
  releasedByUserId?: number | null
  collaborationItemId?: number | null
  moldSetId?: number | null
  derivedPdfAttachmentId?: number | null
  publicationReferencesJson?: string | null
  createdAt: string
  createdBy: string
}

export interface AttachmentPreviewVO {
  attachmentId: number
  previewable: boolean
  previewType: string
  previewStatus: string
  previewUrl: string
  downloadUrl: string
  message?: string | null
}

export interface TimelineAttachmentMetadata {
  fileCategory: string
  versionNo?: string
  remark?: string
}

export interface FileCenterQuery {
  keyword?: string
  projectId?: number
  nodeKey?: string
  fileCategory?: string
  page?: number
  size?: number
}

/** POST /api/v1/projects/{projectId}/timeline/{nodeKey}/attachments */
export function uploadTimelineAttachment(
  projectId: number,
  nodeKey: string,
  file: File,
  metadata: TimelineAttachmentMetadata
) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('fileCategory', metadata.fileCategory)
  if (metadata.versionNo) formData.append('versionNo', metadata.versionNo)
  if (metadata.remark) formData.append('remark', metadata.remark)

  return request
    .post(`/projects/${projectId}/timeline/${nodeKey}/attachments`, formData)
    .then((response) => unwrapResponse<AttachmentVO>(response))
}

/** POST /api/v1/projects/{projectId}/attachments */
export function uploadProjectAttachment(
  projectId: number,
  file: File,
  metadata: TimelineAttachmentMetadata
) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('fileCategory', metadata.fileCategory)
  if (metadata.versionNo) formData.append('versionNo', metadata.versionNo)
  if (metadata.remark) formData.append('remark', metadata.remark)

  return request
    .post(`/projects/${projectId}/attachments`, formData)
    .then((response) => unwrapResponse<AttachmentVO>(response))
}


/** POST /api/v1/products/{productId}/attachments */
export function uploadProductAttachment(
  productId: number,
  file: File,
  metadata: TimelineAttachmentMetadata
) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('fileCategory', metadata.fileCategory)
  if (metadata.versionNo) formData.append('versionNo', metadata.versionNo)
  if (metadata.remark) formData.append('remark', metadata.remark)

  return request
    .post(`/products/${productId}/attachments`, formData)
    .then((response) => unwrapResponse<AttachmentVO>(response))
}

/** GET /api/v1/products/{productId}/attachments */
export async function getProductAttachments(productId: number, fileCategory?: string): Promise<AttachmentVO[]> {
  const response = await request.get(`/products/${productId}/attachments`, {
    params: fileCategory ? { fileCategory } : undefined
  })
  return unwrapResponse<AttachmentVO[]>(response)
}
/** GET /api/v1/projects/{projectId}/timeline/{nodeKey}/attachments */
export async function getTimelineAttachments(projectId: number, nodeKey: string): Promise<AttachmentVO[]> {
  const response = await request.get(`/projects/${projectId}/timeline/${nodeKey}/attachments`)
  return unwrapResponse<AttachmentVO[]>(response)
}

/** GET /api/v1/file-center/attachments */
export async function getFileCenterAttachments(params: FileCenterQuery = {}): Promise<PageResponse<AttachmentVO>> {
  const response = await request.get('/file-center/attachments', { params })
  return unwrapPage<AttachmentVO>(response)
}

/** GET /api/v1/attachments/{attachmentId} */
export async function getAttachmentDetail(attachmentId: number): Promise<AttachmentVO> {
  const response = await request.get(`/attachments/${attachmentId}`)
  return unwrapResponse<AttachmentVO>(response)
}

/** GET /api/v1/attachments/{attachmentId}/preview/metadata */
export async function getAttachmentPreviewMetadata(attachmentId: number): Promise<AttachmentPreviewVO> {
  const response = await request.get(`/attachments/${attachmentId}/preview/metadata`)
  return unwrapResponse<AttachmentPreviewVO>(response)
}

/** GET /api/v1/attachments/{attachmentId}/preview */
export async function previewAttachment(attachmentId: number): Promise<Blob> {
  const response = await request.get<Blob>(`/attachments/${attachmentId}/preview`, { responseType: 'blob' })
  return response.data
}

/** GET /api/v1/attachments/{attachmentId}/download */
export async function downloadAttachment(attachmentId: number): Promise<Blob> {
  const response = await request.get<Blob>(`/attachments/${attachmentId}/download`, { responseType: 'blob' })
  return response.data
}

/** DELETE /api/v1/attachments/{attachmentId} */
export async function deleteAttachment(attachmentId: number) {
  const response = await request.delete(`/attachments/${attachmentId}`)
  return unwrapResponse<void>(response)
}

export interface AttachmentPublication {
  reason: string
  itemId: number | null
  moldSetId: number | null
  pdfAttachmentId: number | null
  references?: DocumentReference[]
}
export async function getAttachmentVersions(id: number): Promise<AttachmentVO[]> {
  return unwrapResponse(await request.get(`/attachments/${id}/versions`))
}
export async function uploadAttachmentVersion(id: number, file: File, versionNo: string, reason: string): Promise<AttachmentVO> {
  const form = new FormData(); form.append('file', file); form.append('versionNo', versionNo); form.append('reason', reason)
  return unwrapResponse(await request.post(`/attachments/${id}/versions`, form))
}
export async function publishAttachmentVersion(id: number, payload: AttachmentPublication): Promise<AttachmentVO> {
  return unwrapResponse(await request.post(`/attachments/${id}/release`, payload))
}


export type ReferenceType = 'product' | 'sku' | 'item' | 'mold_code' | 'source_code' | 'mold_set'
export interface DocumentReference { type: ReferenceType; id: number; partType: 'ALL' | 'PC' | 'TPU' }
export interface ReferencePhone { phoneModelId: number | null; brandId: number; brandName: string; modelName: string; phoneModelCode?: string | null }
export interface ReferenceOption extends DocumentReference {
  code: string; name: string; subtitle?: string; productType?: string; color?: string
  productLineId?: number; productLineName?: string; phoneModelCode?: string; modelName?: string; phoneModels?: ReferencePhone[]
}
export interface ReferenceBrowserQuery {
  type?: ReferenceType; productType?: 'product_line' | 'model_variant'; keyword?: string
  brandId?: number; phoneModelId?: number; productLineId?: number; projectOnly?: boolean; unlinkedPhone?: boolean; page: number; size: number
}
export interface ReferencePhoneGroup extends ReferencePhone { phoneModelId: number; referenceCount: number }
export interface ReferencePhonePage extends Omit<ReferenceBrowserPage, 'content'> { content: ReferencePhoneGroup[]; unlinkedCount: number }
export interface ReferenceBrowserPage extends PageResponse<ReferenceOption> {
  brands: { brandId: number; name: string }[]; productLines: { productLineId: number; name: string }[]
}
export interface DocumentRow {
  familyId: number; latestVersion: AttachmentVO; formalVersion: AttachmentVO | null
  versionCount: number; references: ReferenceOption[]; referenceUpdatedAt: string; updatedAt?: string; updatedBy?: string
}
export type DocumentStatus = 'all' | 'released' | 'draft' | 'unlinked'
export interface DocumentQuery extends FileCenterQuery {
  status?: DocumentStatus; referenceType?: ReferenceType; referenceId?: number; partType?: string
  uploader?: string; dateFrom?: string; dateTo?: string
}
export interface DocumentPage extends PageResponse<DocumentRow> { counts: Record<DocumentStatus, number> }
export interface ReferenceHistory { historyId: number; beforeReferences: string; afterReferences: string; reason: string; actorName: string; changedAt: string }
export const referenceKey = (r: DocumentReference) => `${r.type}:${r.id}:${r.partType || 'ALL'}`
export const referenceIdentity = (r: DocumentReference): DocumentReference => ({ type: r.type, id: r.id, partType: r.partType || 'ALL' })
export const REFERENCE_TYPE_LABELS: Record<ReferenceType, string> = { product: '产品／型号', sku: 'SKU', item: '模具档案', mold_code: '模具主档编码', source_code: '来源模具编码', mold_set: '实物模具' }
export async function getDocuments(query: DocumentQuery): Promise<DocumentPage> {
  return unwrapResponse(await request.get('/file-center/documents', { params: query }))
}
export async function getReferenceOptions(type?: ReferenceType | 'project', keyword?: string, parentId?: number): Promise<ReferenceOption[]> {
  return unwrapResponse(await request.get('/file-center/reference-options', { params: { type, keyword, parentId } }))
}
export async function getReferencePage(query: ReferenceBrowserQuery): Promise<ReferenceBrowserPage> {
  return unwrapResponse(await request.get('/file-center/reference-options/page', { params: query }))
}
export async function getReferencePhonePage(query: ReferenceBrowserQuery): Promise<ReferencePhonePage> {
  return unwrapResponse(await request.get('/file-center/reference-options/phones', { params: query }))
}
export async function uploadDocument(file: File, metadata: TimelineAttachmentMetadata & { references: DocumentReference[]; projectId?: number }): Promise<AttachmentVO> {
  const form = new FormData(); form.append('file', file); form.append('fileCategory', metadata.fileCategory)
  form.append('versionNo', metadata.versionNo || 'V1'); form.append('references', JSON.stringify(metadata.references))
  if (metadata.projectId) form.append('projectId', String(metadata.projectId))
  if (metadata.remark) form.append('remark', metadata.remark)
  return unwrapResponse(await request.post('/file-center/documents', form))
}
export async function getDocumentReferences(id: number): Promise<ReferenceOption[]> { return unwrapResponse(await request.get(`/file-center/documents/${id}/references`)) }
export async function saveDocumentReferences(id: number, references: DocumentReference[], before: DocumentReference[], reason: string): Promise<ReferenceOption[]> {
  return unwrapResponse(await request.put(`/file-center/documents/${id}/references`, { references, before, reason, append: false }))
}
export async function appendDocumentReferences(familyIds: number[], references: DocumentReference[], reason: string): Promise<void> {
  return unwrapResponse(await request.post('/file-center/documents/batch-references', { familyIds, references, reason }))
}
export async function getReferenceHistory(id: number): Promise<ReferenceHistory[]> { return unwrapResponse(await request.get(`/file-center/documents/${id}/reference-history`)) }
export async function getDocumentPdfOptions(id: number): Promise<AttachmentVO[]> { return unwrapResponse(await request.get(`/file-center/documents/${id}/pdf-options`)) }
