<script setup lang="ts">
import { ref, onMounted } from 'vue'
import dayjs from 'dayjs'
import type { RecruitmentFeedItem } from '~/types/recruitment'

const api = useRecruitmentApi()
const { error } = useNotification()
const router = useRouter()
const { t } = useI18n()
const { userTimezone } = useDatetime()

const feedItems = ref<RecruitmentFeedItem[]>([])
const loading = ref(false)
const showGuide = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await api.getMyFeed()
    feedItems.value = result.data
  }
  catch {
    error(t('recruitment.label.loadError'))
  }
  finally {
    loading.value = false
  }
}

function goToListing(id: number) {
  router.push(`/recruitment-listings/${id}`)
}

function formatDate(dateStr: string): string {
  return dayjs.tz(dateStr, userTimezone.value).format('YYYY年M月D日 HH:mm')
}

onMounted(() => load())
</script>

<template>
  <div class="container mx-auto max-w-3xl p-4">
    <PageHeader :title="$t('recruitment.page.myFeed')" help @help="showGuide = true" />
    <p class="mb-6 text-sm text-surface-500">
      {{ $t('recruitment.label.feedDescription') }}
    </p>

    <PageLoading v-if="loading" />

    <DashboardEmptyState
      v-else-if="feedItems.length === 0"
      icon="pi pi-megaphone"
      :message="$t('recruitment.label.noFeedItems')"
    />

    <div v-else class="flex flex-col gap-4">
      <SectionCard
        v-for="item in feedItems"
        :key="item.id"
        class="cursor-pointer transition-shadow hover:shadow-md"
        @click="goToListing(item.id)"
      >
        <div class="mb-2 flex items-start justify-between gap-2">
          <h2 class="text-base font-semibold">
            {{ item.title }}
          </h2>
          <Tag
            :value="$t(`recruitment.status.${item.status.toLowerCase()}`)"
            :severity="item.status === 'OPEN' ? 'success' : 'secondary'"
            rounded
          />
        </div>

        <div class="space-y-1 text-sm text-surface-600 dark:text-surface-400">
          <div v-if="item.location" class="flex items-center gap-1">
            <i class="pi pi-map-marker" />
            {{ item.location }}
          </div>
          <div class="flex items-center gap-1">
            <i class="pi pi-calendar" />
            {{ formatDate(item.startAt) }}
          </div>
          <div class="flex items-center gap-2">
            <span>
              <i class="pi pi-users" />
              {{ item.confirmedCount }} / {{ item.capacity }}
              {{ $t('recruitment.label.participants') }}
            </span>
            <span v-if="item.paymentEnabled">
              <i class="pi pi-wallet" />
              ¥{{ item.price?.toLocaleString() }}
            </span>
          </div>
        </div>

        <div class="mt-2 text-right text-xs text-surface-400">
          {{ $t('recruitment.label.postedAt') }} {{ formatDate(item.createdAt) }}
        </div>
      </SectionCard>
    </div>

    <RecruitmentFeedGuideModal v-model:visible="showGuide" />
  </div>
</template>
