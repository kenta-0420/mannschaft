<script setup lang="ts">
import type { FollowTeamResponse, FriendRequestSource, FriendRequestTarget } from '~/types/friends'

const props = defineProps<{
  teamId: string
  modelValue: boolean
  initialSlug?: string
  sourceTeam?: FriendRequestSource
  targetTeam?: FriendRequestTarget
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'success': [response: FollowTeamResponse]
  'alreadyFollowing': []
  'refresh': []
}>()
const { t } = useI18n()
const friendApi = useFriendTeamsApi()
const statusError = shallowRef<unknown>(null)
const refreshedCounts = ref<{ friends: number; pending: number; incoming: number } | null>(null)
let statusSequence = 0
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const chosenTarget = ref<FriendRequestTarget | null>(null)
const target = computed(() => props.targetTeam ?? chosenTarget.value)
const candidateTarget = computed(() => props.targetTeam?.teamSlug ?? '')
const needsSource = computed(() => !!props.targetTeam && !props.sourceTeam)
const candidateOpen = computed(() => props.modelValue && needsSource.value)
const sources = useFriendRequestSources(candidateTarget, candidateOpen)
const { candidates, selected, loading, error, page, rows, totalRecords } = sources
const source = computed(() => needsSource.value ? selected.value : props.sourceTeam)
const busy = ref(false)
const sourceRevoked = ref(false)
const confirmed = ref(false)
const isSelf = computed(() => source.value?.teamSlug === target.value?.teamSlug)
const canSubmit = computed(() => !!source.value && !!target.value && !isSelf.value && !loading.value && !error.value && !sourceRevoked.value)
const sourceId = computed(() => source.value ? String(source.value.teamId) : props.teamId)

watch([source, target], () => { if (!busy.value) confirmed.value = false })
watch(visible, value => { if (!value) { sourceRevoked.value = false; refreshedCounts.value = null; statusError.value = null; statusSequence += 1; chosenTarget.value = null; confirmed.value = false } })
async function onPage(event: { page: number }) {
  if (busy.value) return
  page.value = event.page
  await sources.load()
}
function onSourceChange(value: FriendRequestSource | null) {
  if (!busy.value) selected.value = value
}
async function onForbidden() {
  busy.value = false
  confirmed.value = false
  if (needsSource.value) { selected.value = null; await sources.load(false, false) }
  else sourceRevoked.value = true
}
function onFollowed(response: FollowTeamResponse) { emit('success', response); visible.value = false }
function onAlreadyFollowing() { busy.value = false; emit('alreadyFollowing'); confirmed.value = false; void refreshState() }
async function refreshState() {
  emit('refresh')
  if (!needsSource.value || !source.value) return
  const id = String(source.value.teamId)
  const current = ++statusSequence
  const valid = () => current === statusSequence && props.modelValue && sourceId.value === id
  statusError.value = null
  try {
    const [friends, pending, incoming] = await Promise.all([friendApi.listFriends(id, { page: 0, size: 1 }), friendApi.listPendingFollows(id, { page: 0, size: 1 }), friendApi.listIncomingFollows(id, { page: 0, size: 1 })])
    if (valid()) refreshedCounts.value = { friends: friends.pagination.totalElements, pending: pending.pagination?.totalElements ?? 0, incoming: incoming.pagination.totalElements }
  }
  catch (cause) { if (valid()) statusError.value = cause }
}
onBeforeUnmount(() => { statusSequence += 1 })
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('friends.request.title')" modal class="w-full max-w-lg" :closable="!busy" :close-on-escape="!busy">
    <div class="flex min-w-0 flex-col gap-4">
      <template v-if="needsSource && targetTeam">
        <p class="break-words">{{ t('friends.request.target') }}: {{ targetTeam.teamName }} ({{ targetTeam.teamSlug }})</p>
        <PageLoading v-if="loading" />
        <DashboardErrorState v-else-if="error" :error="error" testid="friend-source-error" @retry="sources.load()" />
        <DashboardEmptyState v-else-if="totalRecords === 0" :message="t('friends.request.no_source')" />
        <template v-else>
          <label for="friend-request-source">{{ t('friends.request.source') }}</label>
          <Select input-id="friend-request-source" :model-value="selected" :options="candidates" option-label="teamName" :disabled="busy" class="w-full" data-testid="friend-request-source" @update:model-value="onSourceChange">
            <template #option="{ option }"><span class="break-words">{{ option.teamName }} ({{ option.teamSlug }})</span></template>
            <template #value><span v-if="selected" class="break-words">{{ selected.teamName }} ({{ selected.teamSlug }})</span><span v-else>{{ t('friends.request.choose_source') }}</span></template>
          </Select>
          <p v-if="totalRecords === 1 && selected" data-testid="friend-source-auto">{{ t('friends.request.single_source') }}</p>
          <Paginator v-if="totalRecords > rows" :first="page * rows" :rows="rows" :total-records="totalRecords" @page="onPage" />
        </template>
      </template>
      <template v-else>
        <p class="break-words" data-testid="friend-request-fixed-source">{{ t('friends.request.source') }}: {{ sourceTeam?.teamName ?? teamId }} ({{ sourceTeam?.teamSlug ?? teamId }})</p>
        <p v-if="targetTeam" class="break-words">{{ t('friends.request.target') }}: {{ targetTeam.teamName }} ({{ targetTeam.teamSlug }})</p>
        <FriendTargetPicker v-else :key="visible ? 'open' : 'closed'" v-model="chosenTarget" :team-id="sourceTeam?.teamSlug ?? teamId" :initial-slug="initialSlug" :disabled="busy" />
      </template>
      <p v-if="sourceRevoked" role="alert">{{ t('friends.errors.permission_denied') }}</p>
      <p v-if="isSelf" role="alert">{{ t('friends.errors.self_follow') }}</p>
      <p v-if="canSubmit" class="break-words" data-testid="friend-request-direction">{{ source?.teamName }} ({{ source?.teamSlug }}) → {{ target?.teamName }} ({{ target?.teamSlug }})</p>
      <DashboardErrorState v-if="statusError" :error="statusError" @retry="refreshState" />
      <p v-if="refreshedCounts" role="status">{{ t('friends.request.status_counts', refreshedCounts) }}</p>
      <NuxtLink v-if="refreshedCounts && source" :to="'/teams/' + source.teamSlug + '/nav'" class="inline-flex min-h-11 items-center text-primary">{{ t('friends.request.open_lists') }}</NuxtLink>
      <p v-if="confirmed" role="status">{{ t('friends.request.confirm') }}</p>
    </div>
    <template #footer>
      <Button :label="t('friend_feed.forward.cancel')" text :disabled="busy" class="min-h-11" @click="visible = false" />
      <Button v-if="!confirmed" :label="t('friends.request.review')" :disabled="!canSubmit || busy" class="min-h-11" data-testid="friend-request-review" @click="confirmed = true" />
      <TeamFriendFollowButton v-else :team-id="sourceId" :target-team-slug="target?.teamSlug ?? ''" :disabled="!canSubmit" class="min-h-11" data-testid="friend-follow-submit" @busy="busy = $event" @followed="onFollowed" @forbidden="onForbidden" @refresh="refreshState" @already-following="onAlreadyFollowing" />
    </template>
  </Dialog>
</template>
