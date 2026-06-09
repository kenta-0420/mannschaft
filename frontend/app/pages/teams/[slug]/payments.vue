<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamSlug = String(route.params.slug)
</script>

<template>
  <div>
    <div class="mb-4"><PageHeader title="支払い管理" /></div>
    <PaymentAdminPanel scope-type="team" :scope-id="teamSlug" />
  </div>
</template>
