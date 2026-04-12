<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
</script>

<template>
  <div>
    <PageHeader title="支払い管理" />
    <PaymentAdminPanel scope-type="organization" :scope-id="orgId" />
  </div>
</template>
