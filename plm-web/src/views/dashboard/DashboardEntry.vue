<script setup lang="ts">
import { defineAsyncComponent } from 'vue'
const Dashboard = defineAsyncComponent(() => import.meta.env.VITE_WORKBENCH_HOME_V2 === 'true'
  ? import('./WorkbenchHomeView.vue') : import('./DashboardView.vue'))
</script>
<template><Dashboard /></template>
