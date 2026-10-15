<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const proxyDeskStore = useProxyDeskStore()

onMounted(() => {
  proxyDeskStore.restoreFromStorage()
})
</script>

<template>
  <div>
    <h1 class="mb-4 text-2xl font-bold">
      {{ t('proxy.title') }}
    </h1>
    <p class="mb-6 text-surface-500">
      {{ t('proxy.description') }}
    </p>
    <ProxyInputDeskPanel />
  </div>
</template>
