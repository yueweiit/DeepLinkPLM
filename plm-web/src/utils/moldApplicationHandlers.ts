import type {MoldApplication} from '@/api/modules/moldApplications'

/** A raw account ID is audit evidence, not a person's display name. */
export function moldHandlerName(handler:NonNullable<MoldApplication['currentHandlers']>[number]) {
  const name=handler.name?.trim(),id=handler.userId?.trim()
  return name&&name!==id?name:'处理人待核对'
}
export function moldHandlerNames(row:MoldApplication) {
  return row.currentHandlers?.length?row.currentHandlers.map(moldHandlerName).join(' / '):'暂无运行任务'
}
