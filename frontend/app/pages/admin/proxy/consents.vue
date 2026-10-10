<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const { t } = useI18n()
useHead({ title: () => t('proxy.admin.consentsTitle') })
</script>

<template>
  <ProxyAdminPage mode="consents" />
</template>
