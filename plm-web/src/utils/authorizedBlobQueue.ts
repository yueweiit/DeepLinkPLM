export interface BlobLease { promise: Promise<Blob>; release: () => void }
type BlobFetcher = (signal: AbortSignal) => Promise<Blob>
interface QueuedBlob {
  key: string; epoch: number; controller: AbortController; consumers: Set<symbol>
  fetch: BlobFetcher; promise: Promise<Blob>; resolve: (blob: Blob) => void; reject: (error: unknown) => void
  running: boolean
}

const cancelled = () => new DOMException('图片请求已取消', 'AbortError')

/** Session-private, bounded Blob cache. URLs belong to each component, never this cache. */
export function createAuthorizedBlobQueue(options: {
  concurrency?: number; maxBytes?: number; maxEntries?: number; ttl?: number; scope?: () => string
} = {}) {
  const concurrency = Math.max(1, options.concurrency ?? 2)
  const maxBytes = options.maxBytes ?? 48 * 1024 * 1024, maxEntries = options.maxEntries ?? 40
  const ttl = options.ttl ?? 5 * 60_000, getScope = options.scope ?? (() => '')
  const cache = new Map<string, { blob: Blob; at: number }>(), pending = new Map<string, QueuedBlob>()
  let queue: QueuedBlob[] = [], active = 0, bytes = 0, epoch = 0, scope = getScope()

  function evict(key: string) {
    const old = cache.get(key)
    if (old) { bytes -= old.blob.size; cache.delete(key) }
  }
  function clear() {
    epoch++; cache.clear(); bytes = 0
    for (const job of pending.values()) {
      job.controller.abort()
      if (!job.running) job.reject(cancelled())
    }
    pending.clear(); queue = []
  }
  function syncScope() {
    const next = getScope()
    if (scope !== next) { clear(); scope = next }
  }
  function remember(key: string, blob: Blob) {
    if (blob.size > maxBytes || maxEntries < 1) return
    evict(key); cache.set(key, { blob, at: Date.now() }); bytes += blob.size
    while (cache.size > maxEntries || bytes > maxBytes) evict(cache.keys().next().value!)
  }
  function pump() {
    syncScope()
    while (active < concurrency && queue.length) {
      const job = queue.shift()!
      if (!job.consumers.size || job.controller.signal.aborted) continue
      job.running = true; active++
      void Promise.resolve().then(() => {
        // A queued task must never start with a replacement login's Authorization.
        syncScope()
        if (job.epoch !== epoch || job.controller.signal.aborted) throw cancelled()
        return job.fetch(job.controller.signal)
      }).then(blob => {
        syncScope()
        if (job.epoch !== epoch || job.controller.signal.aborted) throw cancelled()
        if (!blob || typeof blob.type !== 'string' || blob.size < 1) throw new Error('图片内容为空，请重试')
        remember(job.key, blob); job.resolve(blob)
      }).catch(job.reject).finally(() => {
        if (pending.get(job.key) === job) pending.delete(job.key)
        active--; pump()
      })
    }
  }
  function acquire(key: string, fetch: BlobFetcher, priority = false): BlobLease {
    syncScope()
    const cached = cache.get(key)
    if (cached && Date.now() - cached.at < ttl) {
      cache.delete(key); cache.set(key, cached)
      return { promise: Promise.resolve(cached.blob), release() {} }
    }
    evict(key)
    let job = pending.get(key)
    if (!job) {
      let resolve!: QueuedBlob['resolve'], reject!: QueuedBlob['reject']
      const promise = new Promise<Blob>((yes, no) => { resolve = yes; reject = no })
      job = { key, epoch, fetch, promise, resolve, reject, controller: new AbortController(), consumers: new Set(), running: false }
      pending.set(key, job)
      if (priority) queue.unshift(job); else queue.push(job)
    } else if (priority && !job.running) {
      queue = queue.filter(value => value !== job); queue.unshift(job)
    }
    const consumer = Symbol(); job.consumers.add(consumer)
    const owned = job
    let released = false
    pump()
    return { promise: job.promise, release() {
      if (released) return
      released = true; owned.consumers.delete(consumer)
      // One unmounted thumbnail must not cancel another viewer of the same file.
      if (!owned.consumers.size && pending.get(key) === owned) {
        pending.delete(key); owned.controller.abort()
        if (!owned.running) { queue = queue.filter(value => value !== owned); owned.reject(cancelled()) }
      }
    } }
  }
  return { acquire, invalidate(key: string) { syncScope(); evict(key) }, clear }
}

// Identity is kept only inside this module: never written to cache keys, DOM or logs.
export const authorizedBlobs = createAuthorizedBlobQueue({ scope: () => localStorage.getItem('plm_token') || '' })
export const attachmentPreviewKey = (id: number) => `/attachments/${id}/preview`
