<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const router = useRouter()
const orgId = Number(route.params.id)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
      <h1 class="text-2xl font-bold">アンケート・投票</h1>
    </div>
    <SurveyList scope-type="ORGANIZATION" :scope-id="orgId" />
  </div>
</template>
