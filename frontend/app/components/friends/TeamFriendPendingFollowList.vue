<script setup lang="ts">
/** 相互成立前のフォローを管理する。伏せ字の相手は取消だけを提供する。 */
import type { PendingTeamFollowView } from '~/types/friends'

const props = defineProps<{ teamId: string }>()
const emit = defineEmits<{ refresh: []; refollow: [slug: string] }>()
const { t } = useI18n()
const { listPendingFollows, unfollow } = useFriendTeamsApi()
const { confirmAction } = useConfirmDialog()
const { handleApiError } = useErrorHandler()
const { showUndoToast } = useUndoToast()
const notification = useNotification()
const follows = ref<PendingTeamFollowView[]>([])
const loading = ref(true)
const loadError = shallowRef<unknown>(null)
const { page, rows: pageSize, totalRecords: totalElements, loadWithPageCorrection } = usePagination()

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const response = await loadWithPageCorrection((page, size) => listPendingFollows(props.teamId, { page, size }))
    follows.value = response.data ?? []
    totalElements.value = response.pagination?.totalElements ?? 0
  }
  catch (error) {
    follows.value = []
    totalElements.value = 0
    loadError.value = error
  }
  finally {
    loading.value = false
  }
}

async function onPageChange(event: { page: number }) {
  page.value = event.page
  await load()
}

function cancelFollow(follow: PendingTeamFollowView) {
  confirmAction({
    message: t('friends.pending.cancel_confirm'),
    header: t('friends.pending.cancel'),
    onAccept: async () => {
      try {
        await unfollow(props.teamId, follow.targetTeamId!, { pastForwardHandling: 'KEEP' })
        if (!follow.nameHidden && follow.targetTeamSlug) {
          const slug = follow.targetTeamSlug
          showUndoToast({
            summary: t('friends.pending.cancel_success'),
            undoLabel: t('friends.pending.refollow'),
            severity: 'success',
            onUndo: () => emit('refollow', slug),
          })
        }
        else {
          notification.success(t('friends.pending.cancel_success'))
        }
        await load()
        emit('refresh')
      }
      catch (error) {
        const apiError = error as { statusCode?: number; data?: { error?: { code?: string } } }
        if (apiError.statusCode === 404 && apiError.data?.error?.code === 'SOCIAL_103') {
          await load()
          emit('refresh')
          return
        }
        handleApiError(error)
      }
    },
  })
}

watch(() => props.teamId, () => {
  page.value = 0
  void load()
})
onMounted(() => { void load() })
defineExpose({ refresh: load })
</script>

<template>
  <section class="flex min-w-0 flex-col gap-3" data-testid="pending-follow-list">
    <h2 class="text-lg font-semibold">{{ t('friends.pending.title') }}</h2>
    <PageLoading v-if="loading" size="40px" />
    <DashboardErrorState v-else-if="loadError" testid="pending-follow-error" :error="loadError" @retry="load" />
    <template v-else>
      <template v-if="follows.length">
        <SectionCard
          v-for="follow in follows"
          :key="follow.followId"
          class="flex min-w-0 flex-wrap items-center justify-between gap-3 !p-3"
          data-testid="pending-follow-row"
        >
          <div class="min-w-0 flex-1 break-words">
            <NuxtLink
              v-if="!follow.nameHidden && follow.targetTeamSlug"
              :to="`/teams/${follow.targetTeamSlug}`"
              class="inline-flex min-h-11 min-w-11 max-w-full items-center font-medium text-primary [overflow-wrap:anywhere] hover:underline"
              data-testid="pending-follow-team-link"
            >{{ follow.targetTeamName }}</NuxtLink>
            <span v-else data-testid="pending-follow-hidden">{{ t('friends.pending.hidden_team') }}</span>
            <p v-if="follow.followedBack" class="mt-1 text-sm text-surface-600 dark:text-surface-300" data-testid="pending-follow-recovery">
              {{ t(follow.nameHidden ? 'friends.pending.recovery_hidden' : 'friends.pending.recovery_public') }}
            </p>
          </div>
          <Button
            :label="t('friends.pending.cancel')"
            severity="danger"
            :outlined="!follow.followedBack"
            class="min-h-11 !h-auto !whitespace-normal"
            data-testid="pending-follow-cancel"
            @click="cancelFollow(follow)"
          />
        </SectionCard>
        <Paginator
          v-if="totalElements > pageSize"
          :rows="pageSize"
          :first="page * pageSize"
          :total-records="totalElements"
          @page="onPageChange"
        />
      </template>
      <DashboardEmptyState v-else icon="pi pi-user-plus" :message="t('friends.pending.empty')" data-testid="pending-follow-empty" />
    </template>
  </section>
</template>
