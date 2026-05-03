<script setup lang="ts">
import { computed, ref } from 'vue'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = computed(() => Number(route.params.id))

const currentYear = new Date().getFullYear()
const selectedYear = ref(currentYear)

const yearOptions = Array.from({ length: 5 }, (_, i) => ({
  value: currentYear - 2 + i,
  label: String(currentYear - 2 + i),
}))
</script>

<template>
  <div class="flex flex-col min-h-screen">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton :to="`/teams/${teamId}`" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.homeroom.title') }}
      </h1>
    </header>

    <main class="flex-1 p-4 max-w-2xl mx-auto w-full">
      <!-- 年度選択 -->
      <div class="mb-6">
        <label class="text-sm text-surface-500 mb-1 block">
          {{ $t('school.homeroom.academicYear') }}
        </label>
        <Select
          v-model="selectedYear"
          :options="yearOptions"
          option-label="label"
          option-value="value"
          class="w-36"
        />
      </div>

      <HomeroomAssignmentPanel
        :team-id="teamId"
        :academic-year="selectedYear"
      />
    </main>
  </div>
</template>
