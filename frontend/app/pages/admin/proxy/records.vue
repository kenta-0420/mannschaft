<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const { t } = useI18n()
useHead({ title: () => t('proxy.record.title') })
</script>

<template>
  <ProxyAdminPage mode="records" />
</template>
