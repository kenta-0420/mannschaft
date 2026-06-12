<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
</script>

<template>
  <SignageManager scope-type="TEAM" :scope-id="teamSlug" />
</template>
