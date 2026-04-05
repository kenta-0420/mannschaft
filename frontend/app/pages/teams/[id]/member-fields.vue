<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
</script>

<template>
  <div class="p-4">
    <div class="flex items-center justify-between mb-4">
      <h1 class="text-xl font-bold">メンバーフィールド管理</h1>
    </div>
    <MemberFieldsManager scope-type="TEAM" :scope-id="teamId" />
  </div>
</template>
