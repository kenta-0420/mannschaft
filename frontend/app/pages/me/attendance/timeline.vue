<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'

definePageMeta({
  middleware: 'auth',
})

const { timeline, loading, loadTimeline } = useStudentTimeline()

const today = new Date().toISOString().slice(0, 10)
const selectedDate = ref(today)

watch(selectedDate, () => {
  void loadTimeline(selectedDate.value)
})

onMounted(() => {
  void loadTimeline(selectedDate.value)
})
</script>

<template>
  <div class="flex flex-col min-h-screen">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton to="/me" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.timeline.title') }}
      </h1>
    </header>

    <main class="flex-1 p-4 max-w-2xl mx-auto w-full">
      <!-- 日付選択 -->
      <div class="mb-4">
        <label class="text-sm text-surface-500 mb-1 block">
          {{ $t('school.timeline.date') }}
        </label>
        <InputText
          v-model="selectedDate"
          type="date"
          class="w-full md:w-48"
        />
      </div>

      <PageLoading v-if="loading" />

      <div v-else-if="!timeline" class="text-center text-surface-400 text-sm py-12">
        {{ $t('school.statistics.noData') }}
      </div>

      <StudentTimelineCard v-else :timeline="timeline" />
    </main>
  </div>
</template>
