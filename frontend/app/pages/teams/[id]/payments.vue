<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = String(route.params.id)
</script>

<template>
  <div>
    <div class="mb-4"><PageHeader :title="$t('payment.admin.pageTitle')" /></div>
    <PaymentAdminPanel scope-type="team" :scope-id="teamId" />
  </div>
</template>
