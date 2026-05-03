<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = computed(() => Number(route.params.id))

const { noticeList, loading, processing, loadTeamNotices, acknowledge, apply } =
  useFamilyAttendanceNotice(teamId)

const today = new Date().toISOString().slice(0, 10)
const selectedDate = ref(today)

async function onDateChange(): Promise<void> {
  await loadTeamNotices(selectedDate.value)
}

async function onAcknowledge(noticeId: number): Promise<void> {
  await acknowledge(noticeId)
}

async function onApply(noticeId: number): Promise<void> {
  await apply(noticeId)
}

onMounted(async () => {
  await loadTeamNotices(selectedDate.value)
})
</script>

<template>
  <div class="flex flex-col min-h-screen">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton :to="`/teams/${teamId}/school-attendance`" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.familyNotice.title') }}
      </h1>
    </header>

    <main class="flex-1 p-4 max-w-2xl mx-auto w-full">
      <!-- 日付選択 -->
      <div class="mb-4">
        <label class="text-sm text-surface-500 mb-1 block">
          {{ $t('school.familyNotice.attendanceDate') }}
        </label>
        <InputText
          v-model="selectedDate"
          type="date"
          class="w-full"
          @change="onDateChange"
        />
      </div>

      <!-- 未確認件数バッジ -->
      <div
        v-if="noticeList && noticeList.unacknowledgedCount > 0"
        class="mb-4 rounded-lg bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-700 px-4 py-2 text-sm text-yellow-800 dark:text-yellow-200 font-medium"
      >
        {{ $t('school.familyNotice.unacknowledged', { count: noticeList.unacknowledgedCount }) }}
      </div>

      <PageLoading v-if="loading" />

      <template v-else>
        <TeacherInboxNoticeList
          :records="noticeList?.records ?? []"
          :processing="processing"
          @acknowledge="onAcknowledge"
          @apply="onApply"
        />
      </template>
    </main>
  </div>
</template>
