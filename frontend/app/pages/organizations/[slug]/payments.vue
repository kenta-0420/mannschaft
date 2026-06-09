<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })
const route = useRoute()
const orgId = String(route.params.id)
</script>

<template>
  <div>
    <PageHeader title="支払い管理" />
    <PaymentAdminPanel scope-type="organization" :scope-id="orgId" />
  </div>
</template>
