<script setup lang="ts">
import { computed, onMounted } from 'vue'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = computed(() => Number(route.params.id))

const { loadMyDisclosedEvaluations } = useAttendanceDisclosure()

onMounted(async () => {
  await loadMyDisclosedEvaluations()
})
</script>

<template>
  <div class="flex flex-col min-h-screen" data-testid="disclosed-inbox-page">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton :to="`/teams/${teamId}`" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.disclosure.inbox.title') }}
      </h1>
    </header>

    <main class="flex-1 p-4 max-w-2xl mx-auto w-full">
      <DisclosedRequirementInbox />
    </main>
  </div>
</template>
