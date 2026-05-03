<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { RecruitmentFeedItem } from '~/types/recruitment'

const api = useRecruitmentApi()
const { error } = useNotification()
const router = useRouter()

const feedItems = ref<RecruitmentFeedItem[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await api.getMyFeed()
    feedItems.value = result.data
  }
  catch (e) {
    error(String(e))
  }
  finally {
    loading.value = false
  }
}

function goToListing(id: number) {
  router.push(`/recruitment-listings/${id}`)
}

function formatDate(dateStr: string): string {
  const d = new Date(dateStr)
  return d.toLocaleDateString('ja-JP', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

onMounted(() => load())
</script>

<template>
  <div class="container mx-auto max-w-3xl p-4">
    <PageHeader :title="$t('recruitment.page.myFeed')" />
    <p class="mb-6 text-sm text-surface-500">
      {{ $t('recruitment.label.feedDescription') }}
    </p>

    <div v-if="loading" class="flex justify-center p-8">
      <ProgressSpinner />
    </div>

    <div
      v-else-if="feedItems.length === 0"
      class="rounded border border-dashed p-8 text-center text-gray-500"
    >
      {{ $t('recruitment.label.noFeedItems') }}
    </div>

    <div v-else class="flex flex-col gap-4">
      <div
        v-for="item in feedItems"
        :key="item.id"
        class="cursor-pointer rounded-lg border border-surface-200 p-4 shadow-sm transition-shadow hover:shadow-md dark:border-surface-700"
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
      </div>
    </div>
  </div>
</template>
