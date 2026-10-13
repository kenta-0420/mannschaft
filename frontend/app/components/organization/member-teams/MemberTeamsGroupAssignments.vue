<script setup lang="ts">
import type { GroupTeamsFilter, OrgGroupedTeam, OrgTeamGroup } from '~/composables/useOrgTeamMembershipApi'
import { getApiErrorCode, getLoadErrorHttpStatus } from '~/utils/loadError'
import { ALL_GROUPS_VALUE, UNASSIGNED_GROUP_VALUE } from '~/utils/memberTeamsView'

/**
 * F01.2.1 8-C — 加盟チームのグループ列・絞り込み（すべて / 未分類 / グループ）と、単体・一括の割当。
 *
 * 一括割当は 1 回 500 件まで・1 分 20 回まで（BE の制限。超えると 429）。全部か無しかで反映される。
 * 割当が成立したら一覧を取り直し、`changed` を発火して親にグループの件数を取り直させる。
 */
const props = defineProps<{
  orgSlug: string
  groups: OrgTeamGroup[]
}>()

const emit = defineEmits<{
  changed: []
}>()

const BULK_MAX = 500

const { t } = useI18n()
const toast = useToast()
const { listGroupTeams, assignTeamGroup, bulkAssignTeamGroup } = useOrgTeamMembershipApi()

const teams = ref<OrgGroupedTeam[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const filter = ref<string>(ALL_GROUPS_VALUE)
const selected = ref<Set<string>>(new Set())
const bulkTarget = ref<string>(UNASSIGNED_GROUP_VALUE)
const busy = ref(false)
const errorKind = ref<'generic' | 'rate-limited' | 'group-missing' | 'team-missing' | 'too-many' | null>(null)

const targetOptions = computed(() => [
  { id: UNASSIGNED_GROUP_VALUE, name: t('teamGroup.unassigned') },
  ...props.groups.map(g => ({ id: g.id ?? '', name: g.name ?? '' })),
])

const filterOptions = computed(() => [
  { id: ALL_GROUPS_VALUE, name: t('teamGroup.filter_all') },
  ...targetOptions.value,
])

function toFilter(value: string | null): GroupTeamsFilter {
  if (!value || value === ALL_GROUPS_VALUE) return {}
  if (value === UNASSIGNED_GROUP_VALUE) return { unassigned: true }
  return { teamGroupId: value }
}

function toGroupId(value: string | null): string | null {
  return !value || value === UNASSIGNED_GROUP_VALUE ? null : value
}

async function load() {
  loadFailed.value = false
  try {
    teams.value = await listGroupTeams(props.orgSlug, toFilter(filter.value))
    // 一覧から消えたチームの選択は外す（見えない選択を一括割当に含めない）。
    const slugs = new Set(teams.value.map(team => team.slug ?? ''))
    selected.value = new Set([...selected.value].filter(s => slugs.has(s)))
  }
  catch {
    // 取得失敗は「チームなし」に見せず、エラー表示にする（症状を隠さない）。
    teams.value = []
    loadFailed.value = true
  }
  finally {
    loading.value = false
  }
}

function onFilterChange(value: string | null) {
  filter.value = value ?? ALL_GROUPS_VALUE
  void load()
}

// グループの追加・改名・削除でラベルが変わるので、id と名前の並びが変わったら取り直す（件数の変化では取り直さない）。
const groupSignature = computed(() => props.groups.map(g => `${g.id}:${g.name}`).join('|'))
watch(groupSignature, () => {
  if (filter.value !== ALL_GROUPS_VALUE && filter.value !== UNASSIGNED_GROUP_VALUE
    && !props.groups.some(g => g.id === filter.value)) {
    filter.value = ALL_GROUPS_VALUE
  }
  if (bulkTarget.value !== UNASSIGNED_GROUP_VALUE && !props.groups.some(g => g.id === bulkTarget.value)) {
    bulkTarget.value = UNASSIGNED_GROUP_VALUE
  }
  void load()
})

function groupLabel(team: OrgGroupedTeam): string {
  return team.teamGroup?.name ?? t('teamGroup.unassigned')
}

function currentValue(team: OrgGroupedTeam): string {
  return team.teamGroup?.id ?? UNASSIGNED_GROUP_VALUE
}

function toggle(slug: string, on: boolean) {
  const next = new Set(selected.value)
  if (on) next.add(slug)
  else next.delete(slug)
  selected.value = next
}

const allSelected = computed(() => teams.value.length > 0 && selected.value.size === teams.value.length)

function toggleAll(on: boolean) {
  selected.value = on ? new Set(teams.value.map(team => team.slug ?? '')) : new Set()
}

const tooMany = computed(() => selected.value.size > BULK_MAX)

function classify(e: unknown): NonNullable<typeof errorKind.value> {
  if (getLoadErrorHttpStatus(e) === 429) return 'rate-limited'
  const code = getApiErrorCode(e)
  if (code === 'ORG_064') return 'group-missing'
  if (code === 'TEAM_070' || code === 'ORG_069') return 'team-missing'
  return 'generic'
}

/** 失敗後、グループやチームが消えていた類は親にも取り直させる。 */
async function onFailure(e: unknown) {
  errorKind.value = classify(e)
  if (errorKind.value === 'group-missing' || errorKind.value === 'team-missing' || getApiErrorCode(e) === 'ORG_067') {
    emit('changed')
    await load()
  }
}

async function assignOne(team: OrgGroupedTeam, value: string | null) {
  const slug = team.slug
  if (!slug || busy.value || (value ?? UNASSIGNED_GROUP_VALUE) === currentValue(team)) return
  busy.value = true
  errorKind.value = null
  try {
    await assignTeamGroup(props.orgSlug, slug, toGroupId(value))
    toast.add({ severity: 'success', summary: t('orgMemberTeams.groups.assigned'), life: 3000 })
    await load()
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
  if (busy.value || selected.value.size === 0 || tooMany.value) return
  busy.value = true
  errorKind.value = null
  try {
    const result = await bulkAssignTeamGroup(props.orgSlug, toGroupId(bulkTarget.value), [...selected.value])
    toast.add({
      severity: 'success',
      summary: t('orgMemberTeams.groups.bulk_assigned', { count: result.updatedCount }),
      life: 3000,
    })
    selected.value = new Set()
    await load()
    emit('changed')
  }
  catch (e) {
    await onFailure(e)
  }
  finally {
    busy.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <section data-testid="member-teams-group-assignments">
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
        data-testid="assign-filter"
        @update:model-value="onFilterChange"
      />
    </div>

    <Message v-if="errorKind" severity="error" data-testid="assign-error" :data-error-kind="errorKind">
      {{ t(`orgMemberTeams.groups.assign_error.${errorKind}`) }}
    </Message>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 2rem; height: 2rem" />
    </div>

    <Message v-else-if="loadFailed" severity="error" data-testid="assign-load-error">
      {{ t('orgMemberTeams.groups.assign_load_error') }}
    </Message>

    <p
      v-else-if="teams.length === 0"
      class="py-6 text-center text-sm text-surface-500"
      data-testid="assign-empty"
    >
      {{ t('orgMemberTeams.groups.assign_empty') }}
    </p>

    <template v-else>
      <div class="mb-3 flex flex-wrap items-center gap-3 rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
        <label class="flex items-center gap-2 text-sm">
          <Checkbox
            :model-value="allSelected"
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
          :disabled="selected.size === 0 || tooMany || busy"
          data-testid="bulk-submit"
          @click="assignBulk"
        />
        <span v-if="tooMany" class="text-xs text-red-600" data-testid="bulk-too-many">
          {{ t('orgMemberTeams.groups.assign_error.too-many', { max: BULK_MAX }) }}
        </span>
      </div>

      <ul class="divide-y divide-surface-200 dark:divide-surface-700">
        <li
          v-for="team in teams"
          :key="team.slug"
          class="flex flex-wrap items-center gap-3 py-2"
          data-testid="team-row"
        >
          <Checkbox
            :model-value="selected.has(team.slug ?? '')"
            binary
            :aria-label="team.name"
            :data-testid="`team-select-${team.slug}`"
            @update:model-value="toggle(team.slug ?? '', !!$event)"
          />
          <span class="min-w-0 flex-1 truncate font-medium">{{ team.name }}</span>
          <span class="text-xs text-surface-500" data-testid="team-group-label">{{ groupLabel(team) }}</span>
          <Select
            :model-value="currentValue(team)"
            :options="targetOptions"
            option-label="name"
            option-value="id"
            :disabled="busy"
            :aria-label="t('teamGroup.assign')"
            :data-testid="`team-assign-${team.slug}`"
            @update:model-value="assignOne(team, $event)"
          />
        </li>
      </ul>
    </template>
  </section>
</template>
