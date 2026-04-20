<script setup lang="ts">
import type { CommitteeDistributionLog } from '~/types/committee'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const committeeId = Number(route.params.id)
const committeeApi = useCommitteeApi()
const { handleApiError } = useErrorHandler()

const distributions = ref<CommitteeDistributionLog[]>([])
const loading = ref(true)

async function loadDistributions() {
  loading.value = true
  try {
    const res = await committeeApi.listDistributions(committeeId)
    distributions.value = res.data
  } catch (err) {
    handleApiError(err, 'listDistributions')
    distributions.value = []
  } finally {
    loading.value = false
  }
}

function formatDate(dateStr: string): string {
  const d = new Date(dateStr)
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(async () => {
  await loadDistributions()
})
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader :title="$t('committee.distributions.title')" />
    </div>

    <SectionCard v-if="distributions.length === 0">
      <DashboardEmptyState icon="pi pi-list" :message="$t('committee.distributions.empty')" />
    </SectionCard>

    <SectionCard v-else>
      <div class="divide-y">
        <div
          v-for="log in distributions"
          :key="log.id"
          class="py-3 px-1"
        >
          <div class="flex items-start justify-between gap-4">
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 flex-wrap">
                <span v-if="log.customTitle" class="font-medium text-gray-900">
                  {{ log.customTitle }}
                </span>
                <Tag :value="log.contentType" severity="info" class="text-xs" />
                <Tag :value="log.targetScope" severity="secondary" class="text-xs" />
              </div>
              <p v-if="log.customTitle" class="mt-1 text-sm text-gray-600">
                {{ log.customTitle }}
              </p>
              <div class="mt-1 flex items-center gap-3 text-xs text-gray-500">
                <span>
                  {{ $t('committee.distributions.confirmation_mode') }}: {{ log.confirmationMode }}
                </span>
                <span v-if="log.announcementEnabled">
                  <i class="pi pi-megaphone mr-1" />{{ $t('committee.distributions.announced') }}
                </span>
              </div>
            </div>
            <div class="text-sm text-gray-500 shrink-0">
              {{ formatDate(log.createdAt) }}
            </div>
          </div>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
