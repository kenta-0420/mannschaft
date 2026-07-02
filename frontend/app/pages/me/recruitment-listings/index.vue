<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { RecruitmentParticipantResponse } from '~/types/recruitment'

const { t } = useI18n()
const api = useRecruitmentApi()
const { error } = useNotification()
const router = useRouter()

const participations = ref<RecruitmentParticipantResponse[]>([])
const loading = ref(false)
const showGuide = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await api.listMyActiveParticipations()
    participations.value = result.data
  }
  catch {
    error(t('recruitment.label.loadError'))
  }
  finally {
    loading.value = false
  }
}

function statusLabel(s: string) {
  return t(`recruitment.participantStatus.${s.toLowerCase()}`)
}

function goToListing(id: number) {
  router.push(`/recruitment-listings/${id}`)
}

onMounted(() => load())
</script>

<template>
  <div class="container mx-auto max-w-3xl p-4">
    <PageHeader :title="t('recruitment.page.myRecruitmentListings')" help @help="showGuide = true" />

    <PageLoading v-if="loading" />

    <DashboardEmptyState
      v-else-if="participations.length === 0"
      icon="pi pi-calendar-plus"
      :message="t('recruitment.label.noListings')"
    />

    <div v-else class="flex flex-col gap-3">
      <SectionCard
        v-for="p in participations"
        :key="p.id"
        class="flex items-center justify-between transition-shadow hover:shadow-md"
      >
        <div class="flex flex-col">
          <div class="text-sm text-surface-500">
            {{ t('recruitment.listingLabel', { id: p.listingId }) }}
          </div>
          <div class="mt-1">
            <Tag :value="statusLabel(p.status)" />
            <span v-if="p.waitlistPosition != null" class="ml-2 text-sm text-amber-700 dark:text-amber-400">
              #{{ p.waitlistPosition }}
            </span>
          </div>
        </div>
        <Button
          :label="t('recruitment.action.viewDetails')"
          icon="pi pi-arrow-right"
          severity="secondary"
          @click="goToListing(p.listingId)"
        />
      </SectionCard>
    </div>

    <RecruitmentListingsGuideModal v-model:visible="showGuide" />
  </div>
</template>
