import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getMarketWatchConfig } from '@/api/modules/marketWatch'
import { getMoldApplicationConfig } from '@/api/modules/moldApplications'

export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false)
  const mobileNavigationOpen = ref(false)
  function toggleMobileNavigation() { mobileNavigationOpen.value = !mobileNavigationOpen.value }
  function closeMobileNavigation() { mobileNavigationOpen.value = false }
  const phoneWatchEnabled = ref(false)
  const phoneWatchConfigLoaded = ref(false)
  const moldApplicationEnabled = ref(false)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function setSidebarCollapsed(value: boolean) {
    sidebarCollapsed.value = value
  }

  async function loadFeatureConfig(force = false) {
    if (phoneWatchConfigLoaded.value && !force) return
    try {
      const config = await getMarketWatchConfig()
      phoneWatchEnabled.value = config.enabled
    } catch {
      phoneWatchEnabled.value = false
    } finally {
      phoneWatchConfigLoaded.value = true
    }
    try { moldApplicationEnabled.value=(await getMoldApplicationConfig()).enabled }
    catch { moldApplicationEnabled.value=false }
  }

  return {
    sidebarCollapsed,
    mobileNavigationOpen,
    toggleMobileNavigation,
    closeMobileNavigation,
    phoneWatchEnabled,
    phoneWatchConfigLoaded,
    moldApplicationEnabled,
    toggleSidebar,
    setSidebarCollapsed,
    loadFeatureConfig
  }
})
