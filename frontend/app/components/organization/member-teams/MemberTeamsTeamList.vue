<script setup lang="ts">
import type { OrgTeamGroup } from '~/composables/useOrgTeamMembershipApi'
import type { OrgTeam } from '~/types/organization'
import { createLatestGuard } from '~/utils/latestRequest'
import { getApiErrorCode, getLoadErrorHttpStatus } from '~/utils/loadError'
import { ALL_GROUPS_VALUE, UNASSIGNED_GROUP_VALUE } from '~/utils/memberTeamsView'

/**
 * F01.2.1 8-C — 所属チーム一覧（member-teams 既存の一覧）のグループ列・絞り込み・割当（設計書 §13）。
 *
 * - グループ列と「すべて／未分類／各グループ」の絞り込みは、グループを見られる閲覧者（組織 MEMBER 以上）に出す。
 *   チーム一覧は親シェルが持つ `teams`（BE が teamGroup を付けて返す）を絞るだけで、一覧を別経路で取り直さない。
 * - 割当 UI（選択・単体・一括）は `canAssign`（組織 ADMIN かつ管理レンズ ON）のときだけ出す。
 * - グループ機能 off（一覧 409）・グループを見られない閲覧者（403）は従来の一覧（OrgTeamGrid）のまま。
 * - 一括割当は 1 回 500 件まで・1 分 20 回まで（BE の制限。超えると 429）。全部か無しかで反映される。
 * - 割当が成立したら `changed` を発火し、親に一覧を取り直させる。
 */
const props = withDefaults(defineProps<{
  orgSlug: string
  teams: OrgTeam[]
  canAssign: boolean
  /** 親が所属チーム一覧を再取得している間は割当操作を止める（古い所属で操作しない）。 */
  refreshing?: boolean
  /** 所属チーム一覧の取得に失敗している（「該当チームなし」と区別してエラーと再試行を出す）。 */
  loadFailed?: boolean
  /** 常駐シェルが持つ一覧を同期する。view 切替で本一覧が消えても送信済み除名の結果を反映する。 */
  refreshAfterRemoval?: (orgSlug: string) => Promise<void>
}>(), {
  refreshing: false,
  loadFailed: false,
  refreshAfterRemoval: undefined,
})

const emit = defineEmits<{
  changed: []
  retry: []
}>()

const BULK_MAX = 500

const { t } = useI18n()
const toast = useToast()
const { listTeamGroupList, assignTeamGroup, bulkAssignTeamGroup } = useOrgTeamMembershipApi()

const groups = ref<OrgTeamGroup[] | null>(null)
const groupsFailed = ref(false)
const filter = ref<string>(ALL_GROUPS_VALUE)
const selected = ref<Set<string>>(new Set())
const bulkTarget = ref<string>(UNASSIGNED_GROUP_VALUE)
const busy = ref(false)
/** 自分の割当中、または親の再取得中は操作できない。 */
const locked = computed(() => busy.value || props.refreshing)

async function synchronizeRemoval(orgSlug: string) {
  if (props.refreshAfterRemoval) await props.refreshAfterRemoval(orgSlug)
  else emit('changed')
}
type AssignErrorKind = 'generic' | 'rate-limited' | 'group-missing' | 'team-missing' | 'too-many'
const errorKind = ref<AssignErrorKind | null>(null)

const guard = createLatestGuard()

/** グループ一覧を取る。組織の切替や連続操作で応答が前後しても、最新の要求の応答だけを反映する。 */
async function loadGroups() {
  const isLatest = guard.next()
  groupsFailed.value = false
  try {
    const list = await listTeamGroupList(props.orgSlug)
    if (!isLatest()) return
    groups.value = list?.groups ?? null
  }
  catch (e) {
    if (!isLatest()) return
    groups.value = null
    // 403 = グループを見られない閲覧者（BE の契約。組織 MEMBER 以上のみ）。それ以外の失敗は隠さず表示する。
    groupsFailed.value = getLoadErrorHttpStatus(e) !== 403
  }
}

onMounted(() => void loadGroups())
watch(() => props.orgSlug, () => {
  groups.value = null
  filter.value = ALL_GROUPS_VALUE
  selected.value = new Set()
  void loadGroups()
})

const targetOptions = computed(() => [
  { id: UNASSIGNED_GROUP_VALUE, name: t('teamGroup.unassigned') },
  ...(groups.value ?? []).map(g => ({ id: g.id ?? '', name: g.name ?? '' })),
])

const filterOptions = computed(() => [
  { id: ALL_GROUPS_VALUE, name: t('teamGroup.filter_all') },
  ...targetOptions.value,
])

const visibleTeams = computed(() => {
  if (filter.value === ALL_GROUPS_VALUE) return props.teams
  if (filter.value === UNASSIGNED_GROUP_VALUE) return props.teams.filter(team => !team.teamGroup)
  return props.teams.filter(team => team.teamGroup?.id === filter.value)
})

/** 絞り込みを変えたら選択を外す（見えないチームを一括割当に含めない）。 */
function onFilterChange(value: string | null) {
  filter.value = value ?? ALL_GROUPS_VALUE
  selected.value = new Set()
}

// 一覧の更新（割当後の再取得など）で消えたチームの選択も外す。
watch(visibleTeams, (list) => {
  const slugs = new Set(list.map(team => team.slug))
  const kept = [...selected.value].filter(s => slugs.has(s))
  if (kept.length !== selected.value.size) selected.value = new Set(kept)
})

// 絞り込み先・割当先のグループが消えたら既定へ戻す。
watch(groups, (list) => {
  if (list === null) return
  if (filter.value !== ALL_GROUPS_VALUE && filter.value !== UNASSIGNED_GROUP_VALUE
    && !list.some(g => g.id === filter.value)) {
    onFilterChange(ALL_GROUPS_VALUE)
  }
  if (bulkTarget.value !== UNASSIGNED_GROUP_VALUE && !list.some(g => g.id === bulkTarget.value)) {
    bulkTarget.value = UNASSIGNED_GROUP_VALUE
  }
})

function toGroupId(value: string | null): string | null {
  return !value || value === UNASSIGNED_GROUP_VALUE ? null : value
}

function groupLabel(team: OrgTeam): string {
  return team.teamGroup?.name ?? t('teamGroup.unassigned')
}

function currentValue(team: OrgTeam): string {
  return team.teamGroup?.id ?? UNASSIGNED_GROUP_VALUE
}

function toggle(slug: string, on: boolean) {
  const next = new Set(selected.value)
  if (on) next.add(slug)
  else next.delete(slug)
  selected.value = next
}

const allSelected = computed(() => visibleTeams.value.length > 0 && selected.value.size === visibleTeams.value.length)

function toggleAll(on: boolean) {
  selected.value = on ? new Set(visibleTeams.value.map(team => team.slug)) : new Set()
}

const tooMany = computed(() => selected.value.size > BULK_MAX)

function classify(e: unknown): AssignErrorKind {
  if (getLoadErrorHttpStatus(e) === 429) return 'rate-limited'
  const code = getApiErrorCode(e)
  if (code === 'ORG_064') return 'group-missing'
  if (code === 'TEAM_070' || code === 'ORG_069') return 'team-missing'
  return 'generic'
}

/** 失敗後、グループやチームが消えていた類・機能 off は、チーム一覧もグループ一覧も取り直す。 */
async function onFailure(e: unknown) {
  errorKind.value = classify(e)
  if (errorKind.value === 'group-missing' || errorKind.value === 'team-missing' || getApiErrorCode(e) === 'ORG_067') {
    emit('changed')
    await loadGroups()
  }
}

async function assignOne(team: OrgTeam, value: string | null) {
  if (locked.value || (value ?? UNASSIGNED_GROUP_VALUE) === currentValue(team)) return
  busy.value = true
  errorKind.value = null
  try {
    await assignTeamGroup(props.orgSlug, team.slug, toGroupId(value))
    toast.add({ severity: 'success', summary: t('orgMemberTeams.groups.assigned'), life: 3000 })
    emit('changed')
  }
  catch (e) {
    await onFailure(e)
  }
  finally {
    busy.value = false
  }
}

async function assignBulk() {
  if (locked.value || selected.value.size === 0 || tooMany.value) return
  // 画面に見えているチームだけを送る（選択が古くなっていても、見えないチームを巻き込まない）。
  const visible = new Set(visibleTeams.value.map(team => team.slug))
  const slugs = [...selected.value].filter(s => visible.has(s))
  if (slugs.length === 0) return
  busy.value = true
  errorKind.value = null
  try {
    const result = await bulkAssignTeamGroup(props.orgSlug, toGroupId(bulkTarget.value), slugs)
    toast.add({
      severity: 'success',
      summary: t('orgMemberTeams.groups.bulk_assigned', { count: result.updatedCount }),
      life: 3000,
    })
    selected.value = new Set()
    emit('changed')
  }
  catch (e) {
    await onFailure(e)
  }
  finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <Message v-if="groupsFailed" severity="error" class="mt-4" data-testid="teamlist-groups-error">
      {{ t('orgMemberTeams.groups_load_error') }}
    </Message>

    <!-- 取得失敗は「該当チームなし」に見せない。選択は保持し、回復すれば続けられる。 -->
    <div
      v-if="loadFailed"
      class="mt-4 space-y-3"
      data-testid="teamlist-load-error"
    >
      <Message severity="error">
        {{ t('orgMemberTeams.teams_load_error') }}
      </Message>
      <Button
        :label="t('orgMemberTeams.groups.retry')"
        icon="pi pi-refresh"
        size="small"
        outlined
        :loading="refreshing"
        data-testid="teamlist-retry"
        @click="emit('retry')"
      />
    </div>

    <OrgTeamGrid v-else-if="groups === null" :teams="teams">
      <template v-if="canAssign" #actions="{ team }">
        <TeamAffiliationRemoveButton
          :key="`${orgSlug}/${team.slug}`"
          :org-slug="orgSlug"
          :team="team"
          :can-remove="canAssign"
          :disabled="locked || loadFailed"
          :refresh-after-removal="synchronizeRemoval"
          @reload="emit('retry')"
        />
      </template>
    </OrgTeamGrid>

    <section v-else class="mt-4" data-testid="member-teams-team-list">
      <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 class="text-base font-semibold">
          {{ t('orgMemberTeams.groups.assign_title') }}
        </h2>
        <Select
          :model-value="filter"
          :options="filterOptions"
          option-label="name"
          option-value="id"
          :aria-label="t('orgMemberTeams.groups.filter_label')"
          data-testid="teamlist-filter"
          @update:model-value="onFilterChange"
        />
      </div>

      <Message v-if="errorKind" severity="error" data-testid="assign-error" :data-error-kind="errorKind">
        {{ t(`orgMemberTeams.groups.assign_error.${errorKind}`, { max: BULK_MAX }) }}
      </Message>

      <p
        v-if="visibleTeams.length === 0"
        class="py-6 text-center text-sm text-surface-500"
        data-testid="teamlist-empty"
      >
        {{ t('orgMemberTeams.groups.assign_empty') }}
      </p>

      <template v-else>
        <div
          v-if="canAssign"
          class="mb-3 flex flex-wrap items-center gap-3 rounded-lg bg-surface-50 p-3 dark:bg-surface-800"
        >
          <label class="flex items-center gap-2 text-sm">
            <Checkbox
              :model-value="allSelected"
              :disabled="locked"
              binary
              data-testid="team-select-all"
              @update:model-value="toggleAll(!!$event)"
            />
            {{ t('orgMemberTeams.groups.select_all') }}
          </label>
          <span class="text-xs text-surface-500" data-testid="bulk-selected-count">
            {{ t('orgMemberTeams.groups.selected_count', { count: selected.size }) }}
          </span>
          <Select
            v-model="bulkTarget"
            :options="targetOptions"
            option-label="name"
            option-value="id"
            :aria-label="t('teamGroup.assign')"
            data-testid="bulk-target"
          />
          <Button
            :label="t('teamGroup.bulk_assign')"
            icon="pi pi-check"
            size="small"
            :loading="busy"
            :disabled="selected.size === 0 || tooMany || locked"
            data-testid="bulk-submit"
            @click="assignBulk"
          />
          <span v-if="tooMany" class="text-xs text-red-600" data-testid="bulk-too-many">
            {{ t('orgMemberTeams.groups.assign_error.too-many', { max: BULK_MAX }) }}
          </span>
        </div>

        <ul class="divide-y divide-surface-200 dark:divide-surface-700">
          <li
            v-for="team in visibleTeams"
            :key="team.slug"
            class="flex flex-wrap items-center gap-3 py-2"
            data-testid="team-row"
            :data-team-slug="team.slug"
          >
            <Checkbox
              v-if="canAssign"
              :model-value="selected.has(team.slug)"
              :disabled="locked"
              binary
              :aria-label="team.name"
              :data-testid="`team-select-${team.slug}`"
              @update:model-value="toggle(team.slug, !!$event)"
            />
            <NuxtLink
              :to="`/teams/${team.slug}`"
              class="min-w-0 flex-1 truncate font-medium hover:underline"
            >
              {{ team.nickname1 || team.name }}
            </NuxtLink>
            <span class="text-xs text-surface-500" data-testid="team-group-label">{{ groupLabel(team) }}</span>
            <Select
              v-if="canAssign"
              :model-value="currentValue(team)"
              :options="targetOptions"
              option-label="name"
              option-value="id"
              :disabled="locked"
              :aria-label="t('teamGroup.assign')"
              :data-testid="`team-assign-${team.slug}`"
              @update:model-value="assignOne(team, $event)"
            />
            <TeamAffiliationRemoveButton
              :key="`${orgSlug}/${filter}/${team.slug}`"
              :org-slug="orgSlug"
              :team="team"
              :can-remove="canAssign"
              :disabled="locked || loadFailed"
              :refresh-after-removal="synchronizeRemoval"
              @reload="emit('retry')"
            />
          </li>
        </ul>
      </template>
    </section>
  </div>
</template>
