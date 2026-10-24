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
const confirmed = ref(false)
const isSelf = computed(() => source.value?.teamSlug === target.value?.teamSlug)
const canSubmit = computed(() => !!source.value && !!target.value && !isSelf.value && !loading.value && !error.value)
const sourceId = computed(() => source.value ? String(source.value.teamId) : props.teamId)

watch([source, target], () => { if (!busy.value) confirmed.value = false })
watch(visible, value => { if (!value) { chosenTarget.value = null; confirmed.value = false } })
async function onPage(event: { page: number }) { page.value = event.page; await sources.load() }
async function onForbidden() {
  confirmed.value = false
  if (needsSource.value) { selected.value = null; await sources.load() }
}
function onFollowed(response: FollowTeamResponse) { emit('success', response); visible.value = false }
function onAlreadyFollowing() { emit('alreadyFollowing'); confirmed.value = false }
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
          <Select id="friend-request-source" v-model="selected" :options="candidates" option-label="teamName" :disabled="busy" class="w-full" data-testid="friend-request-source">
            <template #option="{ option }"><span class="break-words">{{ option.teamName }} ({{ option.teamSlug }})</span></template>
            <template #value><span v-if="selected" class="break-words">{{ selected.teamName }} ({{ selected.teamSlug }})</span><span v-else>{{ t('friends.request.choose_source') }}</span></template>
          </Select>
          <p v-if="totalRecords === 1" data-testid="friend-source-auto">{{ t('friends.request.single_source') }}</p>
          <Paginator v-if="totalRecords > rows" :first="page * rows" :rows="rows" :total-records="totalRecords" :disabled="busy" @page="onPage" />
        </template>
      </template>
      <template v-else>
        <p class="break-words" data-testid="friend-request-fixed-source">{{ t('friends.request.source') }}: {{ sourceTeam?.teamName ?? teamId }} ({{ sourceTeam?.teamSlug ?? teamId }})</p>
        <p v-if="targetTeam" class="break-words">{{ t('friends.request.target') }}: {{ targetTeam.teamName }} ({{ targetTeam.teamSlug }})</p>
        <FriendTargetPicker v-else :key="visible ? 'open' : 'closed'" v-model="chosenTarget" :team-id="sourceTeam?.teamSlug ?? teamId" :initial-slug="initialSlug" :disabled="busy" />
      </template>
      <p v-if="isSelf" role="alert">{{ t('friends.errors.self_follow') }}</p>
      <p v-if="canSubmit" class="break-words" data-testid="friend-request-direction">{{ source?.teamName }} ({{ source?.teamSlug }}) → {{ target?.teamName }} ({{ target?.teamSlug }})</p>
      <p v-if="confirmed" role="status">{{ t('friends.request.confirm') }}</p>
    </div>
    <template #footer>
      <Button :label="t('friend_feed.forward.cancel')" text :disabled="busy" class="min-h-11" @click="visible = false" />
      <Button v-if="!confirmed" :label="t('friends.request.review')" :disabled="!canSubmit || busy" class="min-h-11" data-testid="friend-request-review" @click="confirmed = true" />
      <TeamFriendFollowButton v-else :team-id="sourceId" :target-team-slug="target?.teamSlug ?? ''" :disabled="!canSubmit" class="min-h-11" data-testid="friend-follow-submit" @busy="busy = $event" @followed="onFollowed" @forbidden="onForbidden" @refresh="emit('refresh')" @already-following="onAlreadyFollowing" />
    </template>
  </Dialog>
</template>
