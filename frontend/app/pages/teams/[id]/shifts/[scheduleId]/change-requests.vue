<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const scheduleId = computed(() => Number(route.params.scheduleId))

const authStore = useAuthStore()
const isAdmin = computed(() => authStore.currentUser?.role === 'ADMIN')

const { requests, isLoading, fetchRequests, review, withdraw } =
  useChangeRequest(scheduleId)

onMounted(() => fetchRequests())

async function onSubmitted(): Promise<void> {
  await fetchRequests()
}

async function onReview(id: number, decision: 'ACCEPTED' | 'REJECTED'): Promise<void> {
  await review(id, decision)
}

async function onWithdraw(id: number): Promise<void> {
  await withdraw(id)
}
</script>

<template>
  <div class="mx-auto max-w-4xl space-y-6 px-4 py-6">
    <!-- ページヘッダー -->
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-3">
        <NuxtLink
          :to="`/teams/${teamId}/shifts`"
          class="text-surface-500 hover:text-surface-700"
        >
          <i class="pi pi-arrow-left" />
        </NuxtLink>
        <PageHeader :title="$t('shift.changeRequest.title')" />
      </div>
    </div>

    <!-- 変更依頼作成フォーム -->
    <ShiftChangeRequestForm
      :schedule-id="scheduleId"
      @submitted="onSubmitted"
    />

    <!-- 変更依頼一覧 -->
    <SectionCard>
      <ShiftChangeRequestList
        :requests="requests"
        :current-user-id="authStore.currentUser?.id ?? 0"
        :is-admin="isAdmin"
        :is-loading="isLoading"
        @review="onReview"
        @withdraw="onWithdraw"
      />
    </SectionCard>
  </div>
</template>
