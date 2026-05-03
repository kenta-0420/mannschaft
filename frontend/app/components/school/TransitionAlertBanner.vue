<script setup lang="ts">
import { ref } from 'vue'
import type { TransitionAlertResponse } from '~/types/school'

const props = defineProps<{
  alerts: TransitionAlertResponse[]
  teamId: number
}>()

const emit = defineEmits<{
  resolved: [alertId: number]
}>()

const selectedAlert = ref<TransitionAlertResponse | null>(null)
const showModal = ref(false)

function openResolveModal(alert: TransitionAlertResponse): void {
  selectedAlert.value = alert
  showModal.value = true
}

function onResolved(alertId: number): void {
  showModal.value = false
  selectedAlert.value = null
  emit('resolved', alertId)
}
</script>

<template>
  <div v-if="props.alerts.length > 0" data-testid="transition-alert-banner" class="transition-alert-banner">
    <div
      v-for="alert in props.alerts"
      :key="alert.id"
      :data-testid="'transition-alert-item-' + alert.id"
      class="flex items-start justify-between gap-3 px-4 py-3 mb-2 rounded-lg border"
      :class="{
        'bg-red-50 border-red-300 dark:bg-red-950 dark:border-red-700': alert.alertLevel === 'NORMAL',
        'bg-red-100 border-red-500 dark:bg-red-900 dark:border-red-500': alert.alertLevel === 'URGENT',
      }"
    >
      <div class="flex items-start gap-2 min-w-0">
        <!-- 緊急バッジ -->
        <span
          v-if="alert.alertLevel === 'URGENT'"
          class="shrink-0 inline-flex items-center px-2 py-0.5 rounded text-xs font-bold bg-red-600 text-white"
        >
          {{ $t('school.transitionAlert.alertLevel.URGENT') }}
        </span>
        <span
          v-else
          class="shrink-0 inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-red-200 text-red-800 dark:bg-red-800 dark:text-red-100"
        >
          {{ $t('school.transitionAlert.alertLevel.NORMAL') }}
        </span>

        <!-- アラートメッセージ -->
        <p class="text-sm text-red-900 dark:text-red-100 m-0">
          {{
            $t('school.transitionAlert.message', {
              studentName: String(alert.studentUserId),
              previousPeriod: alert.previousPeriodNumber,
              currentPeriod: alert.currentPeriodNumber,
            })
          }}
        </p>
      </div>

      <!-- 解決済みバッジ / 解決ボタン -->
      <div class="shrink-0">
        <span
          v-if="alert.resolved"
          class="inline-flex items-center px-2 py-1 rounded text-xs font-medium bg-surface-200 text-surface-600 dark:bg-surface-700 dark:text-surface-300"
        >
          {{ $t('school.transitionAlert.resolved') }}
        </span>
        <Button
          v-else
          size="small"
          severity="danger"
          :data-testid="'transition-alert-resolve-' + alert.id"
          :label="$t('school.transitionAlert.resolve')"
          @click="openResolveModal(alert)"
        />
      </div>
    </div>

    <!-- 解決モーダル -->
    <TransitionAlertResolveModal
      v-if="selectedAlert"
      v-model:visible="showModal"
      :alert="selectedAlert"
      :team-id="props.teamId"
      @resolved="onResolved"
    />
  </div>
</template>
