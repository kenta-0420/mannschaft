<script setup lang="ts">
import type { components } from '~/types/generated/index'
import { useTeamLeagueTransfer } from '~/composables/tournament/useTeamLeagueTransfer'

definePageMeta({ layout: 'team', middleware: 'auth' })

type LeagueTransferResponse = components['schemas']['LeagueTransferResponse']

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)
const notification = useNotification()

const { getTeamTransfers } = useTeamLeagueTransfer(teamSlug)

const transfers = ref<LeagueTransferResponse[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getTeamTransfers()
    transfers.value = res.data ?? []
  } catch {
    notification.error(t('transfer.empty_history'))
  } finally {
    loading.value = false
  }
}

function statusLabel(status?: string): string {
  switch (status) {
    case 'DISPATCHED': return t('transfer.status_dispatched')
    case 'PLACED': return t('transfer.status_placed')
    case 'DECLINED': return t('transfer.status_declined')
    case 'CANCELLED': return t('transfer.status_cancelled')
    default: return status ?? '-'
  }
}

function statusClass(status?: string): string {
  switch (status) {
    case 'DISPATCHED': return 'bg-blue-100 text-blue-700'
    case 'PLACED': return 'bg-green-100 text-green-700'
    case 'DECLINED': return 'bg-red-100 text-red-700'
    case 'CANCELLED': return 'bg-surface-100 text-surface-500'
    default: return 'bg-surface-100'
  }
}

function directionLabel(direction?: string): string {
  if (direction === 'PROMOTION') return t('transfer.direction_promotion')
  if (direction === 'RELEGATION') return t('transfer.direction_relegation')
  return direction ?? '-'
}

function directionClass(direction?: string): string {
  if (direction === 'PROMOTION') return 'text-green-600'
  if (direction === 'RELEGATION') return 'text-orange-600'
  return ''
}

onMounted(load)
</script>

<template>
  <div class="p-4 max-w-3xl mx-auto">
    <h1 class="text-2xl font-bold mb-6">
      {{ $t('transfer.history_title') }}
    </h1>

    <div v-if="loading" class="text-center py-12 text-surface-500">
      <i class="pi pi-spin pi-spinner text-2xl" />
    </div>

    <div v-else-if="transfers.length === 0" class="text-center py-12 text-surface-400">
      <i class="pi pi-arrow-right-arrow-left text-4xl mb-3 block opacity-30" />
      <p>{{ $t('transfer.empty_history') }}</p>
    </div>

    <div v-else class="space-y-3">
      <div
        v-for="tr in transfers"
        :key="tr.id"
        class="p-4 border border-surface-200 rounded-lg"
      >
        <div class="flex items-center justify-between gap-4">
          <div class="space-y-1">
            <div class="flex items-center gap-2">
              <span
                class="px-2 py-0.5 rounded text-xs font-medium"
                :class="statusClass(tr.status)"
              >{{ statusLabel(tr.status) }}</span>
              <span
                class="text-sm font-semibold"
                :class="directionClass(tr.direction)"
              >{{ directionLabel(tr.direction) }}</span>
              <span v-if="tr.season" class="text-sm text-surface-500">{{ tr.season }}</span>
            </div>
            <div class="text-sm text-surface-600">
              <span v-if="tr.finalRank">{{ $t('transfer.rank') }}: {{ tr.finalRank }}位</span>
              <span v-if="tr.fromOrganizationId" class="ml-3">
                Org #{{ tr.fromOrganizationId }} → Org #{{ tr.toOrganizationId }}
              </span>
            </div>
            <div v-if="tr.message" class="text-sm text-surface-500 italic">{{ tr.message }}</div>
            <div v-if="tr.createdAt" class="text-xs text-surface-400">
              {{ $t('transfer.created_at') }}: {{ new Date(tr.createdAt).toLocaleDateString() }}
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
