<script setup lang="ts">
import type { ClosureHistoryItem, ClosureConfirmationItem } from '~/composables/useEmergencyClosureApi'
import type { EmergencyClosureConnectionState } from '~/composables/useEmergencyClosureLive'

defineProps<{
  loading: boolean
  items: ClosureHistoryItem[]
  /** 履歴の取得に失敗した。空配列の「履歴なし」と区別して失敗を表示する。 */
  loadError?: boolean
  expandedClosureId: number | null
  /** 展開中の臨時休業 1 件の確認状況（リアルタイム購読の結果）。 */
  confirmations: ClosureConfirmationItem[]
  /** 展開中の臨時休業の確認済み件数（配信の最新確定値）。 */
  confirmedCount: number
  /** 展開中の臨時休業の総件数（配信の最新確定値）。 */
  totalCount: number
  confirmationsLoading: boolean
  /** 確認状況パネルの接続状態（LIVE/再接続中/オフライン/拒否）。 */
  connectionState: EmergencyClosureConnectionState
}>()

const emit = defineEmits<{
  'reload': []
  'toggle-confirmations': [closureId: number]
}>()

const { formatDateTime, formatCalendarDayRange, formatTimeRange } = useDatetime()
</script>

<template>
  <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
    <div class="mb-3 flex items-center justify-between">
      <h3 class="font-semibold text-surface-700 dark:text-surface-200">{{ $t('emergency_closure.section.history') }}</h3>
      <Button
        icon="pi pi-refresh"
        text
        rounded
        size="small"
        :loading="loading"
        data-testid="closure-history-reload"
        @click="emit('reload')"
      />
    </div>

    <div v-if="loading">
      <Skeleton v-for="i in 2" :key="i" height="2.5rem" class="mb-2" />
    </div>
    <p
      v-else-if="loadError"
      data-testid="closure-history-error"
      class="rounded-md bg-red-50 px-4 py-3 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-400"
    >
      <i class="pi pi-exclamation-triangle mr-2" />
      {{ $t('emergency_closure.error.history_failed') }}
    </p>
    <template v-else-if="items.length > 0">
      <!-- md 未満: カード表示（CMP-261104-0336）。表と同じデータ・同じ操作を縦積みで出す。 -->
      <ul class="space-y-3 md:hidden" data-testid="closure-history-cards">
        <!-- text-spacing-trim:space-all: Chromium の約物詰め（全角約物が隣り合う箇所の幅を詰める既定動作）を止める。
             詰めると文字は収まっても scrollWidth が詰める前の幅を返し、見た目と計測がずれるため。 -->
        <li
          v-for="item in items"
          :key="item.id"
          data-testid="closure-history-card"
          class="rounded-md border border-surface-200 p-3 text-sm [text-spacing-trim:space-all] dark:border-surface-700"
        >
          <dl class="min-w-0 space-y-2">
            <div>
              <dt class="text-xs font-medium text-surface-500">{{ $t('emergency_closure.section.period') }}</dt>
              <dd data-field="period" class="break-words [overflow-wrap:anywhere]">
                <div>{{ formatCalendarDayRange(item.startDate, item.endDate) }}</div>
                <div v-if="item.startTime && item.endTime" class="text-xs text-surface-500">
                  {{ formatTimeRange(item.startTime, item.endTime) }}
                </div>
              </dd>
            </div>
            <div>
              <dt class="text-xs font-medium text-surface-500">{{ $t('emergency_closure.table.sent_at') }}</dt>
              <dd data-field="sent-at" class="break-words text-surface-600 [overflow-wrap:anywhere] dark:text-surface-300">{{ formatDateTime(item.createdAt) }}</dd>
            </div>
            <div>
              <dt class="text-xs font-medium text-surface-500">{{ $t('emergency_closure.table.reason') }}</dt>
              <dd data-field="reason" class="break-words [overflow-wrap:anywhere]">{{ item.reason }}</dd>
            </div>
            <div>
              <dt class="text-xs font-medium text-surface-500">{{ $t('emergency_closure.table.count') }}</dt>
              <dd data-field="count" class="break-words [overflow-wrap:anywhere]">
                <Tag :value="$t('emergency_closure.table.count_value', { count: item.sentCount })" severity="info" />
              </dd>
            </div>
          </dl>
          <button
            type="button"
            data-testid="closure-card-toggle"
            class="mt-2 inline-flex min-h-11 min-w-11 items-center justify-center gap-1 rounded text-sm text-primary-600 hover:underline dark:text-primary-400"
            @click="emit('toggle-confirmations', item.id)"
          >
            <i v-if="confirmationsLoading && expandedClosureId === item.id" class="pi pi-spin pi-spinner text-xs" />
            <template v-else>
              <span v-if="expandedClosureId === item.id">
                {{ $t('emergency_closure.message.confirmed_count', { confirmed: confirmedCount, total: totalCount }) }}
              </span>
              <span v-else>{{ $t('emergency_closure.button.view_confirmations') }}</span>
              <i :class="expandedClosureId === item.id ? 'pi pi-chevron-up' : 'pi pi-chevron-down'" class="text-xs" />
            </template>
          </button>
          <div
            v-if="expandedClosureId === item.id && !confirmationsLoading"
            data-testid="closure-card-confirmations"
            class="mt-2 rounded-md border border-surface-200 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-800"
          >
            <div class="mb-2 flex flex-wrap items-center gap-2">
              <p class="text-xs font-semibold text-surface-500">{{ $t('emergency_closure.section.confirmations') }}</p>
              <span
                class="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[0.65rem] font-medium"
                :class="connectionState === 'LIVE'
                  ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                  : connectionState === 'DENIED'
                    ? 'bg-surface-200 text-surface-500 dark:bg-surface-700'
                    : 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400'"
              >
                <i class="text-[0.6rem]" :class="connectionState === 'LIVE' ? 'pi pi-circle-fill' : 'pi pi-circle'" />
                {{ $t(`emergency_closure.connection.${connectionState.toLowerCase()}`) }}
              </span>
            </div>
            <ul class="space-y-2 text-xs">
              <li
                v-for="conf in confirmations"
                :key="conf.userId"
                data-testid="closure-card-confirmation"
                class="border-b border-surface-100 pb-2 last:border-0 last:pb-0 dark:border-surface-700"
              >
                <p data-field="name" class="break-words font-medium [overflow-wrap:anywhere]">{{ conf.userFullName || $t('emergency_closure.label.unknown_user') }}</p>
                <p data-field="appointment" class="text-surface-500">{{ formatDateTime(conf.appointmentAt) }}</p>
                <p class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
                  <span v-if="conf.confirmed" data-field="status" class="inline-flex items-center gap-1 text-green-600 dark:text-green-400">
                    <i class="pi pi-check-circle" /> {{ $t('emergency_closure.status.confirmed') }}
                  </span>
                  <span v-else data-field="status" class="inline-flex items-center gap-1 text-red-500">
                    <i class="pi pi-times-circle" /> {{ $t('emergency_closure.status.unconfirmed') }}
                  </span>
                  <span v-if="conf.reminderSent" data-field="reminder" class="text-amber-500">{{ $t('emergency_closure.status.reminder_sent') }}</span>
                  <span v-else data-field="reminder" class="text-surface-400">{{ $t('emergency_closure.status.reminder_not_sent') }}</span>
                </p>
              </li>
            </ul>
          </div>
        </li>
      </ul>
    <div class="hidden overflow-x-auto md:block" data-testid="closure-history-table">
      <table class="w-full min-w-[40rem] text-sm">
        <thead>
          <tr class="border-b border-surface-200 dark:border-surface-600">
            <th class="whitespace-nowrap pb-2 pr-4 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.sent_at') }}</th>
            <th class="whitespace-nowrap pb-2 pr-4 text-left font-medium text-surface-500">{{ $t('emergency_closure.section.period') }}</th>
            <th class="whitespace-nowrap pb-2 pr-4 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.reason') }}</th>
            <th class="whitespace-nowrap pb-2 pr-4 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.count') }}</th>
            <th class="whitespace-nowrap pb-2 pr-4 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.confirmation_status') }}</th>
          </tr>
        </thead>
        <tbody>
          <template
            v-for="item in items"
            :key="item.id"
          >
            <tr class="border-b border-surface-100 dark:border-surface-700">
              <td class="whitespace-nowrap py-2 pr-4 text-surface-600 dark:text-surface-300">
                {{ formatDateTime(item.createdAt) }}
              </td>
              <td class="min-w-[10rem] py-2 pr-4">
                <div>
                  {{ formatCalendarDayRange(item.startDate, item.endDate) }}
                </div>
                <div v-if="item.startTime && item.endTime" class="text-xs text-surface-500">
                  {{ formatTimeRange(item.startTime, item.endTime) }}
                </div>
              </td>
              <td class="min-w-[14rem] break-words py-2 pr-4">{{ item.reason }}</td>
              <td class="min-w-[10rem] py-2 pr-4">
                <Tag :value="$t('emergency_closure.table.count_value', { count: item.sentCount })" severity="info" class="whitespace-nowrap" />
              </td>
              <td class="py-2">
                <button
                  class="inline-flex min-h-11 min-w-11 items-center justify-center gap-1 whitespace-nowrap rounded text-xs text-primary-600 hover:underline dark:text-primary-400"
                  @click="emit('toggle-confirmations', item.id)"
                >
                  <i v-if="confirmationsLoading && expandedClosureId === item.id" class="pi pi-spin pi-spinner text-xs" />
                  <template v-else>
                    <span v-if="expandedClosureId === item.id">
                      {{ $t('emergency_closure.message.confirmed_count', { confirmed: confirmedCount, total: totalCount }) }}
                    </span>
                    <span v-else>{{ $t('emergency_closure.button.view_confirmations') }}</span>
                    <i :class="expandedClosureId === item.id ? 'pi pi-chevron-up' : 'pi pi-chevron-down'" class="text-xs" />
                  </template>
                </button>
              </td>
            </tr>
            <tr v-if="expandedClosureId === item.id">
              <td colspan="5" class="px-4 pb-3 pt-1">
                <div v-if="!confirmationsLoading" class="rounded-md border border-surface-200 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-800">
                  <div class="mb-2 flex flex-wrap items-center gap-2">
                    <p class="text-xs font-semibold text-surface-500">{{ $t('emergency_closure.section.confirmations') }}</p>
                    <!-- リアルタイム接続状態インジケーター（患者の確認に応じて自動更新する旨を可視化） -->
                    <span
                      class="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[0.65rem] font-medium"
                      :class="connectionState === 'LIVE'
                        ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                        : connectionState === 'DENIED' || connectionState === 'UNAUTHENTICATED'
                          ? 'bg-surface-200 text-surface-500 dark:bg-surface-700'
                          : 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400'"
                    >
                      <i
                        class="text-[0.6rem]"
                        :class="connectionState === 'LIVE' ? 'pi pi-circle-fill' : 'pi pi-circle'"
                      />
                      {{ $t(`emergency_closure.connection.${connectionState.toLowerCase()}`) }}
                    </span>
                  </div>
                  <table class="w-full min-w-[26rem] text-xs">
                    <thead>
                      <tr class="border-b border-surface-200 dark:border-surface-600">
                        <th class="whitespace-nowrap pb-1 pr-4 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.patient_name') }}</th>
                        <th class="whitespace-nowrap pb-1 pr-4 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.appointment_at') }}</th>
                        <th class="whitespace-nowrap pb-1 pr-4 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.confirmed_header') }}</th>
                        <th class="whitespace-nowrap pb-1 pr-4 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.reminder') }}</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="conf in confirmations"
                        :key="conf.userId"
                        class="border-b border-surface-100 last:border-0 dark:border-surface-700"
                      >
                        <td class="whitespace-nowrap py-1 pr-4">{{ conf.userFullName || $t('emergency_closure.label.unknown_user') }}</td>
                        <td class="whitespace-nowrap py-1 pr-4 text-surface-500">
                          {{ formatDateTime(conf.appointmentAt) }}
                        </td>
                        <td class="whitespace-nowrap py-1 pr-4">
                          <span v-if="conf.confirmed" class="inline-flex items-center gap-1 text-green-600 dark:text-green-400">
                            <i class="pi pi-check-circle" /> {{ $t('emergency_closure.status.confirmed') }}
                          </span>
                          <span v-else class="inline-flex items-center gap-1 text-red-500">
                            <i class="pi pi-times-circle" /> {{ $t('emergency_closure.status.unconfirmed') }}
                          </span>
                        </td>
                        <td class="whitespace-nowrap py-1">
                          <span v-if="conf.reminderSent" class="text-amber-500">{{ $t('emergency_closure.status.reminder_sent') }}</span>
                          <span v-else class="text-surface-400">{{ $t('emergency_closure.status.reminder_not_sent') }}</span>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
    </template>
    <DashboardEmptyState v-else icon="pi pi-inbox" :message="$t('emergency_closure.message.no_history')" />
  </section>
</template>
