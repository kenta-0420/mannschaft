<script setup lang="ts">
const { t } = useI18n()
const { myDisclosedEvaluations, loadingInbox } = useAttendanceDisclosure()

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString()
}

function formatRate(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`
}
</script>

<template>
  <div class="disclosed-requirement-inbox" data-testid="disclosed-requirement-inbox">
    <PageLoading v-if="loadingInbox" />

    <template v-else>
      <div
        v-if="myDisclosedEvaluations.length === 0"
        class="text-surface-400 text-sm py-8 text-center"
        data-testid="disclosed-inbox-empty"
      >
        {{ $t('school.disclosure.inbox.empty') }}
      </div>

      <ul v-else class="flex flex-col gap-4" data-testid="disclosed-inbox-list">
        <li
          v-for="item in myDisclosedEvaluations"
          :key="item.evaluationId"
          class="border border-surface-200 dark:border-surface-700 rounded-xl p-4 bg-surface-0 dark:bg-surface-900"
          data-testid="disclosed-inbox-item"
        >
          <div class="flex items-center justify-between mb-2">
            <h3 class="font-semibold text-base m-0">
              {{ item.ruleName }}
            </h3>
            <span class="text-xs text-surface-400">
              {{ formatDate(item.disclosedAt) }}
            </span>
          </div>

          <!-- WITH_NUMBERS: 数値を含めて開示 -->
          <template v-if="item.mode === 'WITH_NUMBERS'">
            <div class="flex gap-4 mb-2 text-sm">
              <div v-if="item.currentRate !== undefined" class="flex flex-col">
                <span class="text-surface-500 text-xs">
                  {{ $t('school.disclosure.inbox.currentRate') }}
                </span>
                <span class="font-medium">{{ formatRate(item.currentRate) }}</span>
              </div>
              <div v-if="item.remainingAllowedDays !== undefined" class="flex flex-col">
                <span class="text-surface-500 text-xs">
                  {{ $t('school.evaluation.remainingAllowedAbsences') }}
                </span>
                <span class="font-medium">
                  {{ $t('school.disclosure.inbox.remainingAllowed', { days: item.remainingAllowedDays }) }}
                </span>
              </div>
            </div>
          </template>

          <!-- WITHOUT_NUMBERS: 数値を伏せて開示 -->
          <template v-else-if="item.mode === 'WITHOUT_NUMBERS'">
            <p class="text-sm text-amber-600 dark:text-amber-400 mb-2">
              {{ $t('school.disclosure.inbox.contactTeacher') }}
            </p>
          </template>

          <!-- MEETING_REQUEST_ONLY: 面談予約のみ -->
          <template v-else-if="item.mode === 'MEETING_REQUEST_ONLY'">
            <p class="text-sm text-red-600 dark:text-red-400 font-medium mb-2">
              {{ $t('school.disclosure.inbox.meetingRequest') }}
            </p>
          </template>

          <!-- 担任メッセージ -->
          <p
            v-if="item.message"
            class="text-sm text-surface-600 dark:text-surface-400 mt-2 border-t border-surface-200 dark:border-surface-700 pt-2"
          >
            {{ item.message }}
          </p>
        </li>
      </ul>
    </template>
  </div>
</template>
