<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })
const route = useRoute()
const orgSlug = String(route.params.slug)
</script>

<template>
  <div>
    <PageHeader title="支払い管理" />
    <PaymentAdminPanel scope-type="organization" :scope-id="orgSlug" />
  </div>
</template>
