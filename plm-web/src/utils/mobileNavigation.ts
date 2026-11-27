type MobileNavigationUser = {
  profile?: { roleCode?: string | null } | null
  hasPermission(permission: string): boolean
}

export type MobileRouteLocation = {
  path: string
  query: Record<string, string>
}

const mobileResearchPaths = [
  '/projects',
  '/product-research',
  '/product-research-costs',
  '/product-research-promotions',
  '/product-research-presales',
  '/products/create',
  '/orders',
  '/production-orders',
  '/mold-applications',
  '/market-watch',
  '/mold-schedules',
  '/molds',
  '/mold-share-mappings',
  '/production-totals',
  '/bom',
  '/processes',
  '/inventories',
  '/workstations',
  '/quality',
  '/files'
]

const mobilePartnerPaths = [
  '/samples',
  '/sample-scan',
  '/model-samples',
  '/research-samples',
  '/partner-samples',
  '/customers',
  '/suppliers'
]

function normalizeQuery(query: Record<string, unknown>): Record<string, string | string[]> {
  return Object.fromEntries(Object.entries(query).map(([key, value]) => [
    key,
    Array.isArray(value) ? value.map(String) : String(value)
  ]))
}

function sameQuery(left: Record<string, unknown>, right: Record<string, unknown>) {
  const a = normalizeQuery(left)
  const b = normalizeQuery(right)
  const aKeys = Object.keys(a).sort()
  const bKeys = Object.keys(b).sort()
  if (aKeys.length !== bKeys.length || aKeys.some((key, index) => key !== bKeys[index])) return false
  return aKeys.every(key => {
    const leftValue = a[key]
    const rightValue = b[key]
    return Array.isArray(leftValue) || Array.isArray(rightValue)
      ? JSON.stringify(Array.isArray(leftValue) ? leftValue : [leftValue]) === JSON.stringify(Array.isArray(rightValue) ? rightValue : [rightValue])
      : leftValue === rightValue
  })
}

export function parseMobileLocation(raw: unknown): MobileRouteLocation | undefined {
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//')) return undefined
  try {
    const url = new URL(raw, 'http://mobile.local')
    const query: Record<string, string> = {}
    url.searchParams.forEach((value, key) => { query[key] = value })
    return { path: url.pathname, query }
  } catch {
    return undefined
  }
}

export function mobileLocationString(location: MobileRouteLocation) {
  const query = new URLSearchParams(location.query).toString()
  return query ? `${location.path}?${query}` : location.path
}

export function mobileTasksPath(user: MobileNavigationUser): string | undefined {
  const roleCode = user.profile?.roleCode || ''
  if (['supplier', 'mexico'].includes(roleCode)) {
    return user.hasPermission('pilot:tasks') ? '/pilot/tasks' : undefined
  }
  if (user.hasPermission('pilot:tasks')) {
    return user.hasPermission('schedule:read') ? '/my-tasks' : '/pilot/tasks'
  }
  return user.hasPermission('approval:view') ? '/approval-tasks?view=tasks' : undefined
}

export function mobileNavigationRoots(user: MobileNavigationUser): MobileRouteLocation[] {
  const roots: MobileRouteLocation[] = [
    { path: '/mobile', query: {} }
  ]
  const tasks = mobileTasksPath(user)
  if (tasks) roots.push(parseMobileLocation(tasks)!)
  if (user.hasPermission('project:view')) {
    roots.push({ path: '/projects', query: {} })
  } else if (user.hasPermission('collaboration:view')) {
    roots.push({ path: '/molds', query: {} })
  }
  if (user.hasPermission('partner:view')) {
    roots.push({ path: '/partner-products', query: {} })
  } else if (user.profile?.roleCode === 'mexico' && user.hasPermission('task:arrival')) {
    roots.push({ path: '/samples', query: {} })
  }
  roots.push({ path: '/mobile', query: { view: 'profile' } })
  return roots
}

export function isMobileNavigationRoot(path: string, query: Record<string, unknown>, user: MobileNavigationUser) {
  return mobileNavigationRoots(user).some(root => root.path === path && sameQuery(root.query, query))
}

export function mobileBottomRootForPath(path: string, query: Record<string, unknown>, user: MobileNavigationUser): MobileRouteLocation | undefined {
  if (path === '/mobile') {
    if (String(query.view || '') === 'profile') return { path: '/mobile', query: { view: 'profile' } }
    if (String(query.view || '') === 'more') return undefined
    return { path: '/mobile', query: {} }
  }

  const roots = mobileNavigationRoots(user)
  const taskRoot = roots.find(root => ['/my-tasks', '/pilot/tasks', '/approval-tasks'].includes(root.path))
  if (taskRoot && ['/my-tasks', '/pilot/tasks', '/approval-tasks'].includes(path)) return taskRoot

  const researchRoot = roots.find(root => root.path === '/projects' || root.path === '/molds')
  if (researchRoot && (path.startsWith('/product-research-') || mobileResearchPaths.includes(path))) return researchRoot

  const partnerRoot = roots.find(root => root.path === '/partner-products' || root.path === '/samples')
  if (partnerRoot && (path.startsWith('/partner-') || mobilePartnerPaths.includes(path))) return partnerRoot

  if (path === '/dashboard') return roots[0]
  return undefined
}

export function mobileEntryLocation(path: string, source: string, user: MobileNavigationUser): MobileRouteLocation {
  const target = parseMobileLocation(path) || { path, query: {} }
  if (isMobileNavigationRoot(target.path, target.query, user)) return target
  const sourceLocation = parseMobileLocation(source)
  return sourceLocation
    ? { path: target.path, query: { ...target.query, mobileFrom: mobileLocationString(sourceLocation) } }
    : target
}
