<script setup lang="ts">
import { useVillageJoinRequestHistory } from '~/composables/village/useVillageJoinRequestHistory'

definePageMeta({ middleware: 'auth' })

const { requests, page, size, meta, loading, error, load } = useVillageJoinRequestHistory()
const { formatDateTime } = useDatetime()

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  size.value = event.rows
}
</script>

<template>
  <div class="mx-auto max-w-3xl" data-testid="my-village-join-requests">
    <PageHeader :title="$t('village.myJoinRequests.title')" back-to="/my" />
    <p class="mb-4 text-sm text-surface-500">{{ $t('village.myJoinRequests.description') }}</p>
    <PageLoading v-if="loading" size="40px" />
    <DashboardErrorState
      v-else-if="error"
      :error="error"
      :message="$t('village.myJoinRequests.fetchError')"
      @retry="load"
    />
    <div v-else class="flex flex-col gap-3">
      <SectionCard
        v-for="request in requests"
        :key="request.id"
        :data-testid="`my-village-join-request-${request.id}`"
      >
        <div class="flex items-center justify-between gap-3">
          <span>{{ $t(`village.subjectType.${request.subjectType}`) }}</span>
          <Tag :value="$t(`village.joinRequest.${request.status.toLowerCase()}`)" />
        </div>
        <dl class="mt-3 grid gap-2 text-sm">
          <div :data-testid="`my-village-join-request-village-${request.id}`">
            <dt class="text-surface-500">{{ $t('village.myJoinRequests.village') }}</dt>
            <dd class="flex flex-wrap items-center gap-2">
              <span class="font-semibold break-words">{{
                request.villageName ?? $t('village.myJoinRequests.unknownVillage')
              }}</span>
              <Tag
                v-if="request.villageState !== 'ACTIVE'"
                severity="secondary"
                :value="$t(`village.myJoinRequests.villageState.${request.villageState}`)"
              />
            </dd>
          </div>
          <div>
            <dt class="text-surface-500">{{ $t('village.myJoinRequests.submittedAt') }}</dt>
            <dd>{{ formatDateTime(request.createdAt) }}</dd>
          </div>
          <div v-if="request.message">
            <dt class="text-surface-500">{{ $t('village.joinRequest.message') }}</dt>
            <dd class="whitespace-pre-wrap break-words">{{ request.message }}</dd>
          </div>
          <div v-if="request.reviewComment">
            <dt class="text-surface-500">{{ $t('village.joinRequest.reviewComment') }}</dt>
            <dd class="whitespace-pre-wrap break-words">{{ request.reviewComment }}</dd>
          </div>
        </dl>
      </SectionCard>
      <DashboardEmptyState
        v-if="requests.length === 0"
        icon="pi-file"
        :message="$t('village.myJoinRequests.empty')"
      />
      <Paginator
        v-if="meta.total > size"
        :first="page * size"
        :rows="size"
        :total-records="meta.total"
        @page="onPage"
      />
    </div>
  </div>
</template>
