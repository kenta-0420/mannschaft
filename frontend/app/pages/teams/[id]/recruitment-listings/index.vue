<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { RecruitmentListingSummaryResponse } from '~/types/recruitment'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const api = useRecruitmentApi()
const { error } = useNotification()

const teamId = computed(() => Number(route.params.id))
const listings = ref<RecruitmentListingSummaryResponse[]>([])
const loading = ref(false)
const status = ref<string | undefined>(undefined)

async function load() {
  loading.value = true
  try {
    const result = await api.listTeamListings(teamId.value, { status: status.value })
    listings.value = result.data
  }
  catch (e) {
    error(String(e))
  }
  finally {
    loading.value = false
  }
}

function goToCreate() {
  router.push(`/teams/${teamId.value}/recruitment-listings/new`)
}

function goToDetail(id: number) {
  router.push(`/recruitment-listings/${id}`)
}

onMounted(() => load())
</script>

<template>
  <div class="container mx-auto max-w-4xl p-4">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="t('recruitment.page.teamRecruitmentListings')" />
      <Button
        :label="t('recruitment.action.create')"
        icon="pi pi-plus"
        @click="goToCreate"
      />
    </div>

    <div v-if="loading" class="flex justify-center p-8">
      <ProgressSpinner />
    </div>

    <DashboardEmptyState
      v-else-if="listings.length === 0"
      icon="pi pi-users"
      :message="t('recruitment.label.noListings')"
    />

    <div v-else class="flex flex-col gap-3">
      <div
        v-for="listing in listings"
        :key="listing.id"
        class="cursor-pointer"
        @click="goToDetail(listing.id)"
      >
        <RecruitmentListingCard :listing="listing" />
      </div>
    </div>
  </div>
</template>
