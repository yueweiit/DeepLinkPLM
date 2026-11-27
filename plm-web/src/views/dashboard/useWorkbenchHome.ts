import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue'
import { useUserStore } from '@/stores/user'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'
import { getHomePage, getHomeSummary, type HomePage, type HomeSection, type HomeSource, type HomeSummary } from '@/api/modules/workbenchHome'

export function useWorkbenchHome() {
  const user = useUserStore(), workspace = useCollaborationWorkspace()
  const section = ref<HomeSection>('tasks'), page = ref(1), size = ref(20), scope = ref<'mine' | 'visible'>('mine'), keyword = ref('')
  const sources = reactive<Record<HomeSection, HomeSource>>({ tasks: 'schedule', projects: 'research', approvals: 'generic', exceptions: 'schedule' })
  const source = computed(() => sources[section.value])
  const summary = ref<HomeSummary>(), result = ref<HomePage>(), loading = ref(false), summaryLoading = ref(false)
  const error = ref(false), summaryError = ref(false)
  let active = true, mounted = false, generation = 0, summaryGeneration = 0
  let listAbort: AbortController | undefined, summaryAbort: AbortController | undefined
  function invalidate() { generation++; summaryGeneration++; listAbort?.abort(); summaryAbort?.abort(); loading.value = false; summaryLoading.value = false }
  async function loadList() {
    if (!active || !user.token) return
    listAbort?.abort(); listAbort = new AbortController()
    const ticket = ++generation, token = user.token
    const current = () => active && ticket === generation && token === user.token
    loading.value = true; error.value = false; result.value = undefined
    try {
      const value = await getHomePage(section.value, { source: source.value, scope: scope.value, page: page.value, size: size.value, keyword: keyword.value || undefined },
        { signal: listAbort.signal, isAuthSessionCurrent: current })
      if (current()) result.value = value
    } catch { if (current()) error.value = true }
    finally { if (current()) loading.value = false }
  }
  async function loadSummary() {
    if (!active || !user.token) return
    summaryAbort?.abort(); summaryAbort = new AbortController()
    const ticket = ++summaryGeneration, token = user.token
    const current = () => active && ticket === summaryGeneration && token === user.token
    summaryLoading.value = true; summaryError.value = false; summary.value = undefined
    try {
      const value = await getHomeSummary({ signal: summaryAbort.signal, isAuthSessionCurrent: current })
      if (current()) summary.value = value
    } catch { if (current()) summaryError.value = true }
    finally { if (current()) summaryLoading.value = false }
  }
  async function refresh() { await Promise.allSettled([loadSummary(), loadList()]) }
  function select(value: HomeSection) { section.value = value; page.value = 1; keyword.value = '' }
  function selectSource(value: HomeSource) { sources[section.value] = value; page.value = 1; keyword.value = '' }
  watch([section, source, page, size, scope, keyword], () => { if (mounted && active) void loadList() })
  watch(() => user.token, () => {
    invalidate(); result.value = undefined; summary.value = undefined; error.value = false; summaryError.value = false; page.value = 1
    if (mounted && active && user.token) void refresh()
  }, { flush: 'sync' })
  watch(() => workspace.revision, () => { if (mounted && active) void refresh() })
  onMounted(() => { mounted = true; void refresh() })
  onActivated(() => { if (!active) { active = true; void refresh() } })
  onDeactivated(() => { active = false; invalidate() })
  onBeforeUnmount(() => { active = false; invalidate() })
  return { section, source, page, size, scope, keyword, summary, result, loading, summaryLoading, error, summaryError, refresh, select, selectSource }
}
