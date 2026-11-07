<script setup lang="ts">
import type { FriendRequestSource, FriendRequestTarget, IncomingTeamFollowView } from '~/types/friends'
const props = defineProps<{ teamId: string; sourceTeam: FriendRequestSource }>()
const emit = defineEmits<{ refresh: []; respond: [target: FriendRequestTarget] }>()
const { t } = useI18n()
const { formatDateTime } = useDatetime()
const { listIncomingFollows } = useFriendTeamsApi()
const follows = ref<IncomingTeamFollowView[]>([])
const loading = ref(false)
const error = shallowRef<unknown>(null)
const { page, rows, totalRecords } = usePagination()
let sequence = 0
async function load(corrected = false): Promise<void> {
  const current = ++sequence
  const id = props.teamId
  const requested = page.value
  const valid = () => current === sequence && props.teamId === id && page.value === requested
  loading.value = true
  error.value = null
  try {
    const result = await listIncomingFollows(id, { page: requested, size: rows.value })
    if (!valid()) return
    if (!result.data.length && requested > 0 && result.pagination.totalElements > 0 && !corrected) {
      page.value = Math.max(0, result.pagination.totalPages - 1)
      await load(true)
      return
    }
    follows.value = result.data
    totalRecords.value = result.pagination.totalElements
    if (!totalRecords.value) page.value = 0
  }
  catch (cause) { if (valid()) error.value = cause }
  finally { if (sequence === current) loading.value = false }
}
async function onPage(event: { page: number }) { page.value = event.page; await load() }
watch(() => props.teamId, () => { page.value = 0; void load() })
onMounted(() => { void load() })
onBeforeUnmount(() => { sequence += 1 })
defineExpose({ refresh: load })
</script>
<template>
  <section class="flex min-w-0 flex-col gap-3" data-testid="incoming-follow-list">
    <h2 class="text-lg font-semibold">{{ t('friends.request.incoming') }}</h2>
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" testid="incoming-follow-error" @retry="load()" />
    <template v-else>
      <SectionCard v-for="follow in follows" :key="follow.followId" class="min-w-0 !p-3" data-testid="incoming-follow-row">
        <div class="flex min-w-0 flex-wrap items-center justify-between gap-3">
          <div class="min-w-0 flex-1 break-words">
            <template v-if="!follow.nameHidden && follow.sourceTeamSlug && follow.sourceTeamName">
              <NuxtLink :to="'/public/teams/' + follow.sourceTeamSlug" class="inline-flex min-h-11 items-center text-primary [overflow-wrap:anywhere]" data-testid="incoming-follow-link">{{ follow.sourceTeamName }} ({{ follow.sourceTeamSlug }})</NuxtLink>
              <p>{{ follow.sourceTeamName }} → {{ sourceTeam.teamName }}</p>
            </template>
            <p v-else data-testid="incoming-follow-hidden">{{ t('friends.pending.hidden_team') }} → {{ sourceTeam.teamName }}</p>
            <time :datetime="follow.followedAt" class="text-sm text-surface-500">{{ formatDateTime(follow.followedAt) }}</time>
          </div>
          <Button v-if="!follow.nameHidden && follow.sourceTeamSlug && follow.sourceTeamName" :label="t('friends.request.respond')" class="min-h-11 !h-auto !whitespace-normal" data-testid="incoming-follow-respond" @click="emit('respond', { teamSlug: follow.sourceTeamSlug, teamName: follow.sourceTeamName })" />
        </div>
      </SectionCard>
      <DashboardEmptyState v-if="!follows.length" :message="t('friends.request.incoming_empty')" data-testid="incoming-follow-empty" />
      <Paginator v-if="totalRecords > rows" :rows="rows" :first="page * rows" :total-records="totalRecords" @page="onPage" />
    </template>
  </section>
</template>
