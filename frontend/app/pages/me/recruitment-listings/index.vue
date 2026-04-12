<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { RecruitmentParticipantResponse } from '~/types/recruitment'

const { t } = useI18n()
const api = useRecruitmentApi()
const { error } = useNotification()
const router = useRouter()

const participations = ref<RecruitmentParticipantResponse[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await api.listMyActiveParticipations()
    participations.value = result.data
  }
  catch (e) {
    error(String(e))
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
    <PageHeader :title="t('recruitment.page.myRecruitmentListings')" />

    <div v-if="loading" class="flex justify-center p-8">
      <ProgressSpinner />
    </div>

    <div
      v-else-if="participations.length === 0"
      class="rounded border border-dashed p-8 text-center text-gray-500"
    >
      {{ t('recruitment.label.noListings') }}
    </div>

    <div v-else class="flex flex-col gap-3">
      <div
        v-for="p in participations"
        :key="p.id"
        class="flex items-center justify-between rounded border border-gray-200 p-4 hover:shadow-md"
      >
        <div class="flex flex-col">
          <div class="text-sm text-gray-500">
            listing #{{ p.listingId }}
          </div>
          <div class="mt-1">
            <Tag :value="statusLabel(p.status)" />
            <span v-if="p.waitlistPosition != null" class="ml-2 text-sm text-orange-700">
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
      </div>
    </div>
  </div>
</template>
