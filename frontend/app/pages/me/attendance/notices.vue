<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { FamilyAttendanceNoticeRequest, FamilyNoticeStatus } from '~/types/school'

definePageMeta({
  middleware: 'auth',
})

const { submitting, myNotices, historyLoading, submitNotice, loadMyNotices } =
  useFamilyAttendanceNoticeForm()

const { t } = useI18n()

// 対象生徒・チームは実際にはユーザーのコンテキストから取得する
// ここではルートパラメータまたはクエリから受け取る設計とする
const route = useRoute()
const teamId = ref(Number(route.query.teamId ?? 0))
const studentUserId = ref(Number(route.query.studentUserId ?? 0))

const showForm = ref(true)

// 履歴取得範囲: 過去30日
const today = new Date()
const fromDate = new Date(today)
fromDate.setDate(fromDate.getDate() - 30)
const fromStr = fromDate.toISOString().slice(0, 10)
const toStr = today.toISOString().slice(0, 10)

async function onSubmit(payload: FamilyAttendanceNoticeRequest): Promise<void> {
  const ok = await submitNotice(payload)
  if (ok) {
    showForm.value = false
    await loadMyNotices(fromStr, toStr)
  }
}

function statusLabel(status: FamilyNoticeStatus): string {
  return t(`school.familyNotice.status.${status}`)
}

function statusClass(status: FamilyNoticeStatus): string {
  switch (status) {
    case 'PENDING':
      return 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200'
    case 'ACKNOWLEDGED':
      return 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200'
    case 'APPLIED':
      return 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200'
    default:
      return 'bg-surface-100 text-surface-600 dark:bg-surface-800 dark:text-surface-300'
  }
}

onMounted(async () => {
  await loadMyNotices(fromStr, toStr)
})
</script>

<template>
  <div class="flex flex-col min-h-screen">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton to="/me" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.familyNotice.title') }}
      </h1>
    </header>

    <main class="flex-1 p-4 max-w-2xl mx-auto w-full">
      <!-- 連絡フォーム -->
      <div
        class="mb-6 rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 p-4"
      >
        <div class="flex items-center justify-between mb-4">
          <h2 class="text-base font-semibold m-0">
            {{ $t('school.familyNotice.submit') }}
          </h2>
          <button
            type="button"
            class="text-sm text-primary-500 hover:underline"
            @click="showForm = !showForm"
          >
            {{ showForm ? $t('common.close') : $t('common.open') }}
          </button>
        </div>

        <template v-if="showForm">
          <FamilyAbsenceNoticeForm
            :team-id="teamId"
            :student-user-id="studentUserId"
            :submitting="submitting"
            @submit="onSubmit"
          />
        </template>
      </div>

      <!-- 送信履歴 -->
      <div>
        <h2 class="text-base font-semibold mb-3">
          {{ $t('school.familyNotice.history') }}
        </h2>

        <PageLoading v-if="historyLoading" />

        <template v-else>
          <div v-if="myNotices.length === 0" class="text-center text-surface-400 py-8">
            {{ $t('school.familyNotice.noNotices') }}
          </div>

          <div v-else class="flex flex-col gap-3">
            <div
              v-for="notice in myNotices"
              :key="notice.id"
              class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 p-4"
            >
              <div class="flex items-center justify-between mb-1">
                <span class="font-medium text-surface-800 dark:text-surface-100">
                  {{ notice.attendanceDate }}
                  — {{ $t(`school.familyNotice.noticeType.${notice.noticeType}`) }}
                </span>
                <span
                  class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                  :class="statusClass(notice.status)"
                >
                  {{ statusLabel(notice.status) }}
                </span>
              </div>
              <div v-if="notice.reason" class="text-sm text-surface-500 dark:text-surface-400">
                {{ $t(`school.familyNotice.reason.${notice.reason}`) }}
              </div>
              <div v-if="notice.reasonDetail" class="text-sm text-surface-400 italic mt-1">
                {{ notice.reasonDetail }}
              </div>
            </div>
          </div>
        </template>
      </div>
    </main>
  </div>
</template>
