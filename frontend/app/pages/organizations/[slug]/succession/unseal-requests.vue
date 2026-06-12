<script setup lang="ts">
import type { UnsealRequestResponse } from '~/types/succession'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgSlug = String(route.params.slug)
const { t } = useI18n()
const { getRequest } = useUnsealRequestApi()

const listRef = ref<{ refresh: () => void } | null>(null)
const selectedId = ref<string | null>(null)
const selectedRequest = ref<UnsealRequestResponse | null>(null)
const loadingDetail = ref(false)

async function onSelect(id: string) {
  selectedId.value = id
  loadingDetail.value = true
  try {
    const res = await getRequest(orgSlug, id)
    selectedRequest.value = res.data
  }
  finally {
    loadingDetail.value = false
  }
}

function onRefresh() {
  selectedRequest.value = null
  selectedId.value = null
  listRef.value?.refresh()
}
</script>

<template>
  <div class="flex flex-col gap-6 p-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">{{ t('succession.unseal.title') }}</h1>
    </div>

    <div class="grid grid-cols-1 xl:grid-cols-3 gap-6">
      <div class="xl:col-span-2">
        <UnsealRequestList
          ref="listRef"
          :org-id="orgSlug"
          @select="onSelect"
        />
      </div>

      <div v-if="selectedRequest" class="bg-surface-0 dark:bg-surface-800 rounded-lg border border-surface-200 dark:border-surface-700">
        <UnsealRequestDetail
          :org-id="orgSlug"
          :request="selectedRequest"
          @refresh="onRefresh"
        />
      </div>
      <div v-else-if="loadingDetail" class="flex items-center justify-center p-8">
        <LoadingBounce />
      </div>
      <div v-else class="flex items-center justify-center p-8 text-surface-400">
        {{ t('succession.unseal.selectPrompt') }}
      </div>
    </div>
  </div>
</template>
