<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})
</script>

<template>
  <TodoStatusLabelManagement scope="me" :can-edit="true" />
</template>
