<script setup lang="ts">
import type { ClosurePreviewItem } from '~/composables/useEmergencyClosureApi'

defineProps<{
  loading: boolean
  done: boolean
  items: ClosurePreviewItem[]
  formatPreviewDateTime: (slotDate: string, startTime: string, endTime: string) => string
  statusLabel: (status: string) => string
  statusSeverity: (status: string) => string
}>()

const emit = defineEmits<{
  'check': []
}>()
</script>

<template>
  <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
    <div class="mb-3 flex items-center justify-between">
      <h3 class="font-semibold text-surface-700 dark:text-surface-200">{{ $t('emergency_closure.section.preview') }}</h3>
      <Button
        :label="$t('emergency_closure.button.check_preview')"
        icon="pi pi-search"
        size="small"
        severity="info"
        outlined
        :loading="loading"
        @click="emit('check')"
      />
    </div>

    <div v-if="loading">
      <Skeleton v-for="i in 3" :key="i" height="2.5rem" class="mb-2" />
    </div>

    <template v-else-if="done">
      <p class="mb-2 text-sm font-medium">
        <span v-if="items.length > 0" class="text-primary-600 dark:text-primary-400">
          {{ $t('emergency_closure.message.notify_count', { count: items.length }) }}
        </span>
        <span v-else class="text-surface-400">
          {{ $t('emergency_closure.message.no_reservations') }}
        </span>
      </p>

      <div v-if="items.length > 0" class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b border-surface-200 dark:border-surface-600">
              <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.patient_name') }}</th>
              <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.datetime') }}</th>
              <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.status') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in items"
              :key="item.reservationId"
              class="border-b border-surface-100 last:border-0 dark:border-surface-700"
            >
              <td class="py-2 pr-4">{{ item.userDisplayName }}</td>
              <td class="py-2 pr-4 text-surface-600 dark:text-surface-300">
                {{ formatPreviewDateTime(item.slotDate, item.startTime, item.endTime) }}
              </td>
              <td class="py-2">
                <Tag :value="statusLabel(item.status)" :severity="statusSeverity(item.status)" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <p v-else class="text-sm text-surface-400">
      {{ $t('emergency_closure.hint.press_check_preview') }}
    </p>
  </section>
</template>
