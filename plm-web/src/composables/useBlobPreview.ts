import { onScopeDispose, ref, watch } from 'vue'
import { authorizedBlobs, type BlobLease } from '@/utils/authorizedBlobQueue'

export interface BlobPreviewSource { key: string; fetch: (signal: AbortSignal) => Promise<Blob> }

/** Shared lifecycle for authenticated photos and full-size attachment previews. */
export function useBlobPreview(source: () => BlobPreviewSource | undefined, options: { imageOnly?: boolean; priority?: boolean } = {}) {
  const url = ref(''), type = ref(''), loading = ref(false), error = ref('')
  let generation = 0, disposed = false, lease: BlobLease | undefined
  function clear() {
    generation++; lease?.release(); lease = undefined
    if (url.value) URL.revokeObjectURL(url.value)
    url.value = ''; type.value = ''; loading.value = false; error.value = ''
  }
  async function load(force = false) {
    clear()
    if (disposed) return
    const current = source()
    if (!current) return
    const seq = generation
    if (force) authorizedBlobs.invalidate(current.key)
    loading.value = true
    const acquired = authorizedBlobs.acquire(current.key, current.fetch, options.priority)
    lease = acquired
    try {
      const blob = await acquired.promise
      if (disposed || seq !== generation) return
      if (options.imageOnly !== false && !blob.type.startsWith('image/')) {
        authorizedBlobs.invalidate(current.key)
        throw new Error('未能读取图片，请重试')
      }
      type.value = blob.type; url.value = URL.createObjectURL(blob)
    } catch (cause) {
      if (!disposed && seq === generation) {
        error.value = (cause as { code?: string })?.code === 'ECONNABORTED'
          ? '加载超时，请重试' : '加载失败，请重试'
      }
    } finally {
      acquired.release()
      if (seq === generation) { loading.value = false; lease = undefined }
    }
  }
  function imageFailed(event?: Event) {
    const image = event?.target
    if (image instanceof HTMLImageElement && (image.currentSrc || image.src) !== url.value) return
    const current = source()
    if (current) authorizedBlobs.invalidate(current.key)
    if (url.value) URL.revokeObjectURL(url.value)
    url.value = ''; loading.value = false; error.value = '图片无法显示，请重试'
  }
  watch(() => source()?.key, () => { void load() }, { immediate: true })
  onScopeDispose(() => { disposed = true; clear() })
  return { url, type, loading, error, retry: () => load(true), clear, imageFailed }
}
