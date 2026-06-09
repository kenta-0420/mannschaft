<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = String(route.params.id)
</script>

<template>
  <SignageManager scope-type="TEAM" :scope-id="teamId" />
</template>
