import type { RouteLocationRaw } from 'vue-router'
import type { HomeItem } from '@/api/modules/workbenchHome'
import { PRODUCT_RESEARCH_STAGES } from '@/constants/productResearch'
import { moldScheduleTaskRoute } from '@/utils/collaboration'

export function workbenchHomeRoute(item: HomeItem): RouteLocationRaw | null {
  switch (item.target) {
    case 'research': {
      const stage = PRODUCT_RESEARCH_STAGES.find(s => s.key === item.stageKey)
      return item.projectId && stage ? { path: '/product-research', query: { productId: item.projectId, step: stage.key } } : null
    }
    case 'schedule': return item.moldSetId && item.taskKey ? moldScheduleTaskRoute({ moldSetId: item.moldSetId, taskKey: item.taskKey }) : null
    case 'approval_list': return { path: '/approval-tasks' }
    case 'mold_application': return item.applicationId ? { path: '/mold-applications', query: { applicationId: item.applicationId } } : null
    default: return null
  }
}
