import type { AxiosRequestConfig } from 'axios'
import request, { unwrapResponse } from '@/api/request'

export type HomeSection = 'tasks' | 'approvals' | 'projects' | 'exceptions'
export type HomeSource = 'research' | 'schedule' | 'generic' | 'mold_application'
export type SourceState = 'ok' | 'restricted' | 'disabled' | 'not_integrated' | 'stale' | 'error' | 'identity_unbound'
export interface SourceCount { source: HomeSource; state: SourceState; total: number | null; readAt: string; sourceUpdatedAt: string | null }
export interface HomeItem {
  key: string; source: HomeSource; target: 'research' | 'schedule' | 'approval_list' | 'mold_application'
  businessId: string; title: string; code?: string; projectId?: number; projectName?: string
  stage?: number; stageKey?: string; stageName?: string; revision?: boolean
  ownerName?: string; ownerUserId?: number; confirmationOwnerId?: number; assigneeId?: string
  dueDate?: string; overdue?: boolean; nextAction?: string; blocker?: string
  moldSetId?: number; taskKey?: string; applicationId?: number; approvalGranted?: boolean; businessStatus?: string; approvalFlowState?: string
  sourceUpdatedAt?: string; sourceVersion?: string
}
export interface HomePage {
  content: HomeItem[]; page: number; size: number; totalElements: number | null; totalPages: number | null
  source: HomeSource; sourceState: SourceState; readAt: string; sourceUpdatedAt: string | null
}
export type HomeSummary = Record<HomeSection, SourceCount[]>
export interface HomeQuery { source: HomeSource; scope: 'mine' | 'visible'; page: number; size: number; keyword?: string }
export async function getHomeSummary(config: AxiosRequestConfig = {}): Promise<HomeSummary> {
  return unwrapResponse(await request.get('/workbench/home/summary', { ...config, suppressErrorToast: true }))
}
export async function getHomePage(section: HomeSection, params: HomeQuery, config: AxiosRequestConfig = {}): Promise<HomePage> {
  return unwrapResponse(await request.get(`/workbench/home/${section}`, { ...config, params, suppressErrorToast: true }))
}

/** Counts from different source pages must never be mistaken for a complete global count. */
export function homeCount(sources?: SourceCount[]): { total: number | null; partial: boolean } {
  if (!sources?.length) return { total: null, partial: false }
  const known = sources.filter(s => s.state === 'ok' && s.total !== null)
  return { total: known.length ? known.reduce((sum, s) => sum + s.total!, 0) : null, partial: known.length !== sources.length }
}
