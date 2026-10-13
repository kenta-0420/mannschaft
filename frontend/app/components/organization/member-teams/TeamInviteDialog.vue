<script setup lang="ts">
import type { OrgTeamGroup } from '~/composables/useOrgTeamMembershipApi'
import type { PublicTeamSearchResult } from '~/types/public'
import { UNASSIGNED_GROUP_VALUE } from '~/utils/memberTeamsView'

/**
 * F01.2.1 8-B — 組織からチームへの加盟招待ダイアログ（公開チーム検索）。
 *
 * 設計書: docs/features/F01.2.1_org_team_groups.md §13
 * 流れ: キーワードで公開チームを検索 → チームを選ぶ → （グループ機能 on なら）グループ → 添え書き → 招待。
 * 招待できるのは公開チームのみ（検索 API が visibility=PUBLIC だけを返す）。
 */
const props = defineProps<{
  orgSlug: string
}>()

const visible = defineModel<boolean>('visible', { default: false })

const emit = defineEmits<{
  invited: []
}>()

const { t } = useI18n()
const toast = useToast()
const { inviteTeam, listTeamGroups } = useOrgTeamMembershipApi()
const { searchPublicTeams } = usePublicApi()

/** BE の添え書き上限（コードポイント）。 */
const MESSAGE_MAX = 500
const SEARCH_SIZE = 20

const keyword = ref('')
const results = ref<PublicTeamSearchResult[]>([])
const searched = ref(false)
const searching = ref(false)
const searchFailed = ref(false)
const selectedSlug = ref<string | null>(null)
const groups = ref<OrgTeamGroup[] | null>(null)
const groupsLoadFailed = ref(false)
const selectedGroup = ref<string>(UNASSIGNED_GROUP_VALUE)
const message = ref('')
const submitting = ref(false)
const submitFailed = ref(false)

const messageLength = computed(() => [...message.value].length)
const canSearch = computed(() => keyword.value.trim().length > 0 && !searching.value)
const canSubmit = computed(
  () => !!selectedSlug.value && messageLength.value <= MESSAGE_MAX && !submitting.value,
)

const groupOptions = computed(() => [
  { id: UNASSIGNED_GROUP_VALUE, name: t('teamAffiliation.review.unassigned') },
  ...(groups.value ?? []).map(g => ({ id: g.id ?? '', name: g.name ?? '' })),
])

async function loadGroups() {
  groups.value = null
  groupsLoadFailed.value = false
  try {
    groups.value = await listTeamGroups(props.orgSlug)
  }
  catch {
    // グループは任意項目なので招待自体は続けられるが、取得失敗は画面に出す（無かったことにしない）。
    groupsLoadFailed.value = true
    groups.value = null
  }
}

watch(
  visible,
  (open) => {
    if (!open) return
    keyword.value = ''
    results.value = []
    searched.value = false
    searchFailed.value = false
    selectedSlug.value = null
    selectedGroup.value = UNASSIGNED_GROUP_VALUE
    message.value = ''
    submitFailed.value = false
    void loadGroups()
  },
  { immediate: true },
)

async function search() {
  if (!canSearch.value) return
  searching.value = true
  searchFailed.value = false
  selectedSlug.value = null
  try {
    const page = await searchPublicTeams({ keyword: keyword.value.trim(), page: 0, size: SEARCH_SIZE })
    results.value = page.content
    searched.value = true
  }
  catch {
    // 検索失敗は「0 件」に見せず、エラーを出す。
    results.value = []
    searched.value = false
    searchFailed.value = true
  }
  finally {
    searching.value = false
  }
}

async function submit() {
  if (!canSubmit.value || !selectedSlug.value) return
  submitting.value = true
  submitFailed.value = false
  try {
    await inviteTeam(props.orgSlug, {
      teamSlug: selectedSlug.value,
      message: message.value.trim() || undefined,
      groupId:
        groups.value !== null && selectedGroup.value !== UNASSIGNED_GROUP_VALUE
          ? selectedGroup.value
          : undefined,
    })
    toast.add({ severity: 'success', summary: t('orgMemberTeams.invite_dialog.success'), life: 3000 })
    emit('invited')
    visible.value = false
  }
  catch {
    // 失敗はダイアログ内に表示して残す（閉じて成功に見せない）。
    submitFailed.value = true
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('orgMemberTeams.invite_dialog.title')"
    class="w-full max-w-lg"
    data-testid="team-invite-dialog"
  >
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium" for="invite-search-input">
          {{ t('orgMemberTeams.invite_dialog.search_label') }}
        </label>
        <form class="flex gap-2" @submit.prevent="search">
          <InputText
            id="invite-search-input"
            v-model="keyword"
            class="flex-1"
            :placeholder="t('orgMemberTeams.invite_dialog.search_placeholder')"
            data-testid="invite-search-input"
          />
          <Button
            type="button"
            :label="t('orgMemberTeams.invite_dialog.search')"
            icon="pi pi-search"
            :loading="searching"
            :disabled="!canSearch"
            data-testid="invite-search-submit"
            @click="search"
          />
        </form>
      </div>

      <Message v-if="searchFailed" severity="error" data-testid="invite-search-error">
        {{ t('orgMemberTeams.invite_dialog.search_error') }}
      </Message>

      <p
        v-else-if="searched && results.length === 0"
        class="text-sm text-surface-500"
        data-testid="invite-search-empty"
      >
        {{ t('orgMemberTeams.invite_dialog.no_results') }}
      </p>

      <ul v-if="results.length > 0" class="max-h-60 space-y-1 overflow-y-auto" data-testid="invite-results">
        <li v-for="team in results" :key="team.slug">
          <button
            type="button"
            class="flex w-full items-center justify-between rounded-md border px-3 py-2 text-left text-sm transition-colors focus:outline-none focus:ring-2 focus:ring-primary-400"
            :class="selectedSlug === team.slug
              ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/20'
              : 'border-surface-200 hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-800'"
            :aria-pressed="selectedSlug === team.slug"
            :data-testid="`invite-result-${team.slug}`"
            @click="selectedSlug = team.slug"
          >
            <span class="font-medium">{{ team.name }}</span>
            <span class="text-xs text-surface-500">
              {{ t('orgMemberTeams.invite_dialog.member_count', { count: team.memberCount }) }}
            </span>
          </button>
        </li>
      </ul>

      <Message v-if="groupsLoadFailed" severity="error" data-testid="invite-groups-load-error">
        {{ t('orgMemberTeams.groups_load_error') }}
      </Message>

      <div v-if="groups !== null">
        <label class="mb-1 block text-sm font-medium" for="invite-group-select">
          {{ t('orgMemberTeams.invite_dialog.group') }}
        </label>
        <Select
          v-model="selectedGroup"
          input-id="invite-group-select"
          :options="groupOptions"
          option-label="name"
          option-value="id"
          class="w-full"
          data-testid="invite-group-select"
        />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium" for="invite-message">
          {{ t('teamAffiliation.apply.message') }}
        </label>
        <Textarea
          id="invite-message"
          v-model="message"
          rows="3"
          class="w-full"
          data-testid="invite-message"
        />
        <p
          class="mt-1 text-right text-xs"
          :class="messageLength > MESSAGE_MAX ? 'text-red-500' : 'text-surface-400'"
        >
          {{ messageLength }} / {{ MESSAGE_MAX }}
        </p>
      </div>

      <Message v-if="submitFailed" severity="error" data-testid="invite-error">
        {{ t('orgMemberTeams.invite_dialog.error') }}
      </Message>
    </div>

    <template #footer>
      <Button :label="t('button.close')" icon="pi pi-times" text @click="visible = false" />
      <Button
        :label="t('orgMemberTeams.invite_dialog.submit')"
        icon="pi pi-send"
        :loading="submitting"
        :disabled="!canSubmit"
        data-testid="invite-submit"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
