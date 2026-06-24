<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})
</script>

<template>
  <div>
    <!-- pageTransition(out-in) は単一要素ルートを要求するため、コンポーネント単体ルートを <div> で包む。 -->
    <TodoStatusLabelManagement scope="me" :can-edit="true" />
  </div>
</template>
