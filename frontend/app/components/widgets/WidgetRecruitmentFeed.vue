<script setup lang="ts">
import type { RecruitmentFeedItem } from '~/types/recruitment'

const { getMyFeed } = useRecruitmentApi()
const { captureQuiet } = useErrorReport()

const items = ref<RecruitmentFeedItem[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await getMyFeed()
    items.value = res.data.slice(0, 5)
  }
  catch (error) {
    captureQuiet(error, { context: 'WidgetRecruitmentFeed: フィード取得' })
    items.value = []
  }
  finally {
    loading.value = false
  }
}

function formatDate(dateStr: string): string {
  const d = new Date(dateStr)
  return d.toLocaleDateString('ja-JP', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    :title="$t('recruitment.widget.feedTitle')"
    icon="pi pi-megaphone"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <div v-if="items.length > 0" class="space-y-3">
      <NuxtLink
        v-for="item in items"
        :key="item.id"
        :to="`/recruitment-listings/${item.id}`"
        class="block rounded-lg bg-surface-50 p-3 transition-colors hover:bg-surface-100 dark:bg-surface-700/50 dark:hover:bg-surface-700"
      >
        <p class="text-sm font-medium">
          {{ item.title }}
        </p>
        <p class="mt-1 text-xs text-surface-500">
          <i class="pi pi-calendar mr-1" />{{ formatDate(item.startAt) }}
          <span v-if="item.location" class="ml-2">
            <i class="pi pi-map-marker mr-1" />{{ item.location }}
          </span>
        </p>
        <p class="mt-1 text-xs text-surface-400">
          <i class="pi pi-users mr-1" />{{ item.confirmedCount }}/{{ item.capacity }}
          <span v-if="item.paymentEnabled" class="ml-2">
            <i class="pi pi-wallet mr-1" />¥{{ item.price?.toLocaleString() }}
          </span>
        </p>
      </NuxtLink>
    </div>
    <DashboardEmptyState
      v-else
      icon="pi pi-megaphone"
      :message="$t('recruitment.widget.feedEmpty')"
    />
    <div class="mt-3 text-right">
      <NuxtLink to="/me/recruitment-feed" class="text-xs text-primary hover:underline">
        {{ $t('recruitment.widget.viewAll') }} <i class="pi pi-external-link text-[10px]" />
      </NuxtLink>
    </div>
  </DashboardWidgetCard>
</template>
