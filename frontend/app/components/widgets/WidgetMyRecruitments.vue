<script setup lang="ts">
import type { RecruitmentMyListingItem } from '~/types/recruitment'

const { getMyListings } = useRecruitmentApi()
const { captureQuiet } = useErrorReport()

const items = ref<RecruitmentMyListingItem[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await getMyListings()
    items.value = res.data.slice(0, 5)
  }
  catch (error) {
    captureQuiet(error, { context: 'WidgetMyRecruitments: 参加予定取得' })
    items.value = []
  }
  finally {
    loading.value = false
  }
}

function statusSeverity(status: string) {
  switch (status) {
    case 'CONFIRMED': return 'success'
    case 'WAITLISTED': return 'warn'
    case 'APPLIED': return 'info'
    default: return 'secondary'
  }
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    :title="$t('recruitment.widget.myRecruitmentsTitle')"
    icon="pi pi-ticket"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <div v-if="items.length > 0" class="space-y-3">
      <NuxtLink
        v-for="item in items"
        :key="item.id"
        :to="`/recruitment-listings/${item.listingId}`"
        class="flex items-center justify-between rounded-lg bg-surface-50 p-3 transition-colors hover:bg-surface-100 dark:bg-surface-700/50 dark:hover:bg-surface-700"
      >
        <div>
          <p class="text-sm font-medium">
            {{ $t('recruitment.label.listing') }} #{{ item.listingId }}
          </p>
          <p v-if="item.waitlistPosition != null" class="mt-0.5 text-xs text-orange-600">
            {{ $t('recruitment.label.waitlistPosition', { n: item.waitlistPosition }) }}
          </p>
        </div>
        <Tag
          :value="$t(`recruitment.participantStatus.${item.status.toLowerCase()}`)"
          :severity="statusSeverity(item.status)"
          rounded
        />
      </NuxtLink>
    </div>
    <DashboardEmptyState
      v-else
      icon="pi pi-ticket"
      :message="$t('recruitment.widget.myRecruitmentsEmpty')"
    />
    <div class="mt-3 text-right">
      <NuxtLink to="/me/recruitment-listings" class="text-xs text-primary hover:underline">
        {{ $t('recruitment.widget.viewAll') }} <i class="pi pi-external-link text-[10px]" />
      </NuxtLink>
    </div>
  </DashboardWidgetCard>
</template>
