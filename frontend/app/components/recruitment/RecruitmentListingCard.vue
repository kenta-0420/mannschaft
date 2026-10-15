<script setup lang="ts">
import { computed } from 'vue'
import type { RecruitmentListingSummaryResponse } from '~/types/recruitment'

interface Props {
  listing: RecruitmentListingSummaryResponse
}
const props = defineProps<Props>()

const { t } = useI18n()

const remaining = computed(() => Math.max(0, props.listing.capacity - props.listing.confirmedCount))

const statusLabel = computed(() => t(`recruitment.status.${props.listing.status.toLowerCase()}`))

const statusSeverity = computed(() => {
  switch (props.listing.status) {
    case 'OPEN':
      return 'success'
    case 'FULL':
      return 'warn'
    case 'CLOSED':
    case 'COMPLETED':
      return 'secondary'
    case 'CANCELLED':
    case 'AUTO_CANCELLED':
      return 'danger'
    default:
      return 'info'
  }
})
</script>

<template>
  <div class="flex flex-col gap-2 rounded border border-gray-200 p-4 hover:shadow-md">
    <div class="flex items-start justify-between gap-2">
      <h3 class="text-lg font-semibold">
        {{ listing.title }}
      </h3>
      <Tag :value="statusLabel" :severity="statusSeverity" />
    </div>
    <div class="text-sm text-gray-600">
      <i class="pi pi-calendar mr-1" />
      {{ listing.startAt }} 〜 {{ listing.endAt }}
    </div>
    <div v-if="listing.location" class="text-sm text-gray-600">
      <i class="pi pi-map-marker mr-1" />
      {{ listing.location }}
    </div>
    <div class="flex items-center gap-3 text-sm">
      <span>{{ t('recruitment.label.participants') }}: {{ listing.confirmedCount }} / {{ listing.capacity }}</span>
      <span v-if="remaining > 0" class="text-green-700">
        {{ t('recruitment.label.remainingCapacity') }}: {{ remaining }}
      </span>
      <span v-if="listing.waitlistCount > 0" class="text-orange-700">
        {{ t('recruitment.label.waitlistCount') }}: {{ listing.waitlistCount }}
      </span>
    </div>
    <div v-if="listing.paymentEnabled && listing.price != null" class="text-sm font-semibold">
      ¥{{ listing.price.toLocaleString() }}
    </div>
  </div>
</template>
