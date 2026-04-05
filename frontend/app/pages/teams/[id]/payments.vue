<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
</script>

<template>
  <div>
    <div class="mb-4"><h1 class="text-2xl font-bold">支払い管理</h1></div>
    <PaymentAdminPanel scope-type="team" :scope-id="teamId" />
  </div>
</template>
