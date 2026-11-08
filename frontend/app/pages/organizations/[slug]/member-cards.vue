<script setup lang="ts">
definePageMeta({ layout: 'default', middleware: 'auth' })
const route = useRoute()
</script>

<template>
  <MemberCardManagement scope-type="organization" :slug="String(route.params.slug)" />
</template>
