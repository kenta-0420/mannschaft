<script setup lang="ts">
import type { ClosureHistoryItem, ClosureConfirmationItem } from '~/composables/useEmergencyClosureApi'

defineProps<{
  loading: boolean
  items: ClosureHistoryItem[]
  expandedClosureId: number | null
  confirmationsMap: Record<number, ClosureConfirmationItem[]>
  confirmationsLoading: boolean
  formatDate: (iso: string) => string
  confirmedCount: (closureId: number) => number
  totalCount: (closureId: number) => number
}>()

const emit = defineEmits<{
  'reload': []
  'toggle-confirmations': [closureId: number]
}>()
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
        @click="emit('reload')"
      />
    </div>

    <div v-if="loading">
      <Skeleton v-for="i in 2" :key="i" height="2.5rem" class="mb-2" />
    </div>
    <div v-else-if="items.length > 0" class="overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-surface-200 dark:border-surface-600">
            <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.sent_at') }}</th>
            <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.section.period') }}</th>
            <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.reason') }}</th>
            <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.count') }}</th>
            <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.confirmation_status') }}</th>
          </tr>
        </thead>
        <tbody>
          <template
            v-for="item in items"
            :key="item.id"
          >
            <tr class="border-b border-surface-100 dark:border-surface-700">
              <td class="py-2 pr-4 text-surface-600 dark:text-surface-300">
                {{ new Date(item.createdAt).toLocaleString('ja-JP') }}
              </td>
              <td class="py-2 pr-4">
                <div>
                  {{ item.startDate === item.endDate ? formatDate(item.startDate) : `${formatDate(item.startDate)}〜${formatDate(item.endDate)}` }}
                </div>
                <div v-if="item.startTime && item.endTime" class="text-xs text-surface-500">
                  {{ item.startTime }}〜{{ item.endTime }}
                </div>
              </td>
              <td class="py-2 pr-4">{{ item.reason }}</td>
              <td class="py-2 pr-4">
                <Tag :value="`${item.notifiedCount}件`" severity="info" />
              </td>
              <td class="py-2">
                <button
                  class="inline-flex items-center gap-1 rounded text-xs text-primary-600 hover:underline dark:text-primary-400"
                  @click="emit('toggle-confirmations', item.id)"
                >
                  <i v-if="confirmationsLoading && expandedClosureId === item.id" class="pi pi-spin pi-spinner text-xs" />
                  <template v-else>
                    <span v-if="confirmationsMap[item.id]">
                      {{ $t('emergency_closure.message.confirmed_count', { confirmed: confirmedCount(item.id), total: totalCount(item.id) }) }}
                    </span>
                    <span v-else>{{ $t('emergency_closure.button.view_confirmations') }}</span>
                    <i :class="expandedClosureId === item.id ? 'pi pi-chevron-up' : 'pi pi-chevron-down'" class="text-xs" />
                  </template>
                </button>
              </td>
            </tr>
            <tr v-if="expandedClosureId === item.id">
              <td colspan="5" class="px-4 pb-3 pt-1">
                <div v-if="confirmationsMap[item.id]" class="rounded-md border border-surface-200 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-800">
                  <p class="mb-2 text-xs font-semibold text-surface-500">{{ $t('emergency_closure.section.confirmations') }}</p>
                  <table class="w-full text-xs">
                    <thead>
                      <tr class="border-b border-surface-200 dark:border-surface-600">
                        <th class="pb-1 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.patient_name') }}</th>
                        <th class="pb-1 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.appointment_at') }}</th>
                        <th class="pb-1 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.confirmed_header') }}</th>
                        <th class="pb-1 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.reminder') }}</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="conf in confirmationsMap[item.id]"
                        :key="conf.userId"
                        class="border-b border-surface-100 last:border-0 dark:border-surface-700"
                      >
                        <td class="py-1 pr-4">{{ conf.userDisplayName }}</td>
                        <td class="py-1 pr-4 text-surface-500">
                          {{ new Date(conf.appointmentAt).toLocaleString('ja-JP', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }}
                        </td>
                        <td class="py-1 pr-4">
                          <span v-if="conf.confirmed" class="inline-flex items-center gap-1 text-green-600 dark:text-green-400">
                            <i class="pi pi-check-circle" /> {{ $t('emergency_closure.status.confirmed') }}
                          </span>
                          <span v-else class="inline-flex items-center gap-1 text-red-500">
                            <i class="pi pi-times-circle" /> {{ $t('emergency_closure.status.unconfirmed') }}
                          </span>
                        </td>
                        <td class="py-1">
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
    <DashboardEmptyState v-else icon="pi pi-inbox" :message="$t('emergency_closure.message.no_history')" />
  </section>
</template>
