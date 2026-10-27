<script setup lang="ts">
import type { MemberResponse } from '~/types/member'

/** 個別交代依頼専用の候補選択。所属チームのslugを解決し全候補を取得する。 */
const props = defineProps<{ visible: boolean; teamId: string }>()
const selectedUserIds = defineModel<number[]>({ required: true })
const { t } = useI18n()
const { getAllMembers } = useTeamApi()
const teamStore = useTeamStore()
const { handleApiError } = useErrorHandler()
const members = ref<MemberResponse[]>([])
const membersLoading = ref(false)
const membersError = shallowRef<unknown>()
const membersFailed = ref(false)
let membersRequestId = 0

watch(
  () => [props.visible, props.teamId] as const,
  () => {
    void loadMembers()
  },
  { immediate: true, flush: 'sync' },
)

function resetMembers() {
  membersRequestId++
  members.value = []
  selectedUserIds.value = []
  membersError.value = undefined
  membersFailed.value = false
  membersLoading.value = false
}

onBeforeUnmount(resetMembers)

async function loadMembers() {
  resetMembers()
  if (!props.visible) return
  const requestId = membersRequestId
  const teamId = props.teamId
  const isCurrent = () => requestId === membersRequestId && props.visible && props.teamId === teamId
  membersLoading.value = true
  try {
    let team = teamStore.myTeams.find((team) => String(team.id) === teamId)
    if (!team?.slug) {
      const result = await teamStore.fetchMyTeamsWithResult()
      if (!isCurrent()) return
      if (!result.ok) throw result.error
      team = teamStore.myTeams.find((team) => String(team.id) === teamId)
    }
    // 成功した所属一覧に対象が無い場合も、数値IDをslugとして送らない。
    if (!team?.slug) throw { statusCode: 404 }
    if (!isCurrent()) return
    const res = await getAllMembers(team.slug)
    if (!isCurrent()) return
    members.value = res.data
  } catch (error) {
    if (!isCurrent()) return
    membersError.value = error
    membersFailed.value = true
    handleApiError(error)
  } finally {
    if (isCurrent()) membersLoading.value = false
  }
}
</script>

<template>
  <div>
    <label class="mb-2 block text-sm font-medium text-surface-700 dark:text-surface-300">
      {{ t('shift.swap.targetUsers.label') }}
    </label>
    <PageLoading v-if="membersLoading" />
    <DashboardErrorState
      v-else-if="membersFailed"
      :error="membersError"
      :show-retry="true"
      testid="swap-members-error"
      @retry="loadMembers"
    />
    <DashboardEmptyState
      v-else-if="members.length === 0"
      :message="t('shift.swap.targetUsers.empty')"
    />
    <div
      v-else
      class="max-h-48 overflow-y-auto rounded-lg border border-surface-200 dark:border-surface-700"
    >
      <label
        v-for="member in members"
        :key="member.userId"
        class="flex min-h-11 cursor-pointer items-center gap-3 px-3 py-2 transition-colors hover:bg-surface-50 dark:hover:bg-surface-800"
      >
        <Checkbox
          v-model="selectedUserIds"
          :value="member.userId"
          :input-id="`member-${member.userId}`"
        />
        <img
          v-if="member.avatarUrl"
          :src="member.avatarUrl"
          class="h-8 w-8 rounded-full object-cover"
          alt=""
        >
        <span
          v-else
          class="flex h-8 w-8 items-center justify-center rounded-full bg-surface-200 text-xs font-bold text-surface-600 dark:bg-surface-700 dark:text-surface-400"
        >
          {{ member.displayName.charAt(0) }}
        </span>
        <span class="min-w-0 break-words text-sm text-surface-800 dark:text-surface-200">
          {{ member.displayName }}
        </span>
      </label>
    </div>
    <p v-if="selectedUserIds.length > 0" class="mt-1.5 text-xs text-surface-500">
      {{ t('shift.swap.targetUsers.selectedCount', { count: selectedUserIds.length }) }}
    </p>
  </div>
</template>
