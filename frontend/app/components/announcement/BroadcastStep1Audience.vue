<script setup lang="ts">
import type { AnnouncementScopeType } from '~/types/announcement'
import type {
  AnnouncementTemplate,
  AnnouncementTemplateRequest,
  BroadcastGroupRange,
  BroadcastTargetMode,
  BroadcastTargetRole,
  WizardFormState,
} from '~/types/announcement_broadcast'
import type { AudiencePreview, OrgTeamGroup } from '~/composables/useBroadcastAudience'
import { buildAudienceFields, hasAudienceSelection } from '~/utils/broadcastAudience'

const props = defineProps<{
  modelValue: WizardFormState
  scopeType: AnnouncementScopeType
  scopeId: string
  isAdmin?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: WizardFormState]
  next: []
}>()

/** 宛先プレビューのデバウンス（F01.2.1 §8.4） */
const PREVIEW_DEBOUNCE_MS = 300

const { t } = useI18n()
const notification = useNotification()
const { getTeamsInOrg } = useOrganizationApi()
const { createTemplate, saving } = useAnnouncementTemplates(props.scopeType, props.scopeId)
const { fetchTeamGroups, previewAudience } = useBroadcastAudience(props.scopeId)

const isOrg = computed(() => props.scopeType === 'ORGANIZATION')

/** 宛先を決める項目。これらが変わると、テンプレートの応答待ち・テンプレートによる宛先復元は無効になる */
const AUDIENCE_KEYS = ['targetMode', 'targetTeamIds', 'targetGroupIds', 'targetGroupRange', 'includeUnassigned', 'templateId'] as const

/**
 * テンプレート適用（onTemplateApply）が書き換えるフォーム項目。ここを唯一の定義とし、
 * 世代を進める条件はこの集合から導く（適用中にこれらを利用者が変えたら、遅れて返った適用は捨てる）。
 * onTemplateApply の next に項目を足したら、ここにも足すこと。
 */
const TEMPLATE_WRITTEN_KEYS = [...AUDIENCE_KEYS, 'targetRole', 'selectedChannel', 'templateEmptied'] as const satisfies readonly (keyof WizardFormState)[]

/** 宛先が変わるたびに進める世代。遅れて返ったテンプレート確認の応答が、新しい選択を上書きしないための判定に使う */
let audienceGen = 0

/** 削除で個別グループが全部除外されたテンプレート（BE の emptiedByRemoval）。フォーム状態に持つので、ステップ2から戻っても失われない */
const templateEmptied = computed(() => props.modelValue.templateEmptied)

function update(patch: Partial<WizardFormState>) {
  const touchesAudience = AUDIENCE_KEYS.some(k => k in patch)
  if (TEMPLATE_WRITTEN_KEYS.some(k => k in patch)) {
    audienceGen++
  }
  const next = { ...props.modelValue, ...patch }
  if (touchesAudience && !('templateEmptied' in patch)) next.templateEmptied = false
  // 宛先を手で変えたら、テンプレートの宛先が復元されないよう templateId を外す
  // （「すべてのチーム」など宛先項目が空になると、BE は templateId だけの送信をテンプレート解決とみなす）。
  if (touchesAudience && !('templateId' in patch)) next.templateId = null
  emit('update:modelValue', next)
}

const targetRole = computed({
  get() {
    return props.modelValue.targetRole
  },
  set(value: BroadcastTargetRole) {
    update({ targetRole: value })
  },
})

const targetRoleOptions: { label: string; value: BroadcastTargetRole }[] = [
  { label: t('announcement.target_role_members_only'), value: 'MEMBERS_AND_ABOVE' },
  { label: t('announcement.target_role_supporters_and_above'), value: 'SUPPORTERS_AND_ABOVE' },
  { label: t('announcement.target_role_public'), value: 'PUBLIC' },
]

// ── 宛先の選び方（すべて / チームを選ぶ / チームグループで選ぶ） ──

/** チームグループ機能が使えるか（組織のグループ機能 off では 409 ORG_067 が返り false になる） */
const groupsEnabled = ref(false)
const groups = ref<OrgTeamGroup[]>([])
const unassignedTeamCount = ref(0)
const groupsLoadError = ref<string | null>(null)

const targetMode = computed<BroadcastTargetMode>({
  get() {
    return props.modelValue.targetMode
  },
  set(mode) {
    if (mode === props.modelValue.targetMode) return
    resetRangeInputs()
    templateNotice.value = null
    update({
      targetMode: mode,
      // 他モードの選択は持ち越さない（併用は BE が 400 BROADCAST_011 で拒否する）
      targetTeamIds: mode === 'TEAMS' ? [] : null,
      targetGroupIds: [],
      targetGroupRange: null,
      includeUnassigned: false,
    })
  },
})

/** 組織配下のチーム一覧（「チームを選ぶ」用。id と name のみ利用） */
const orgTeams = ref<Array<{ id: string; name: string }>>([])

watchEffect(async () => {
  if (isOrg.value) {
    const res = await getTeamsInOrg(props.scopeId)
    orgTeams.value = res.data.map(team => ({ id: String(team.id), name: team.name }))
  }
})

let groupsReady: Promise<void> = Promise.resolve()

async function loadGroups() {
  groupsLoadError.value = null
  try {
    const res = await fetchTeamGroups()
    groupsEnabled.value = res.enabled
    groups.value = res.groups
    unassignedTeamCount.value = res.unassignedTeamCount
  }
  catch (e: unknown) {
    // 409 ORG_067 以外の失敗は「機能 off」と区別して見せる（握りつぶさない）
    const { code, message } = readApiError(e)
    groupsLoadError.value = message ? (code ? `${message} (${code})` : message) : t('announcement.groups_load_failed')
    groupsEnabled.value = true
  }
}

if (isOrg.value) {
  groupsReady = loadGroups()
}

/** 個別チームのチェック状態 */
function isTeamChecked(teamId: string): boolean {
  return props.modelValue.targetTeamIds?.includes(teamId) ?? false
}

function toggleTeam(teamId: string, checked: boolean) {
  const current = props.modelValue.targetTeamIds ?? []
  update({ targetTeamIds: checked ? [...current, teamId] : current.filter(id => id !== teamId) })
}

function isGroupChecked(groupId: string): boolean {
  return props.modelValue.targetGroupIds.includes(groupId)
}

function toggleGroup(groupId: string, checked: boolean) {
  const current = props.modelValue.targetGroupIds
  update({ targetGroupIds: checked ? [...current, groupId] : current.filter(id => id !== groupId) })
}

const includeUnassigned = computed({
  get() {
    return props.modelValue.includeUnassigned
  },
  set(value: boolean) {
    update({ includeUnassigned: value })
  },
})

// ── 範囲（並び順で 〜以前 / 〜以降 / A〜B） ──

type RangeKind = 'UP_TO' | 'FROM' | 'BETWEEN'

const rangeKind = ref<RangeKind | null>(null)
const rangeFrom = ref<string | null>(null)
const rangeTo = ref<string | null>(null)

const rangeKindOptions = computed(() => [
  { label: t('announcement.target_range_kind_up_to'), value: 'UP_TO' },
  { label: t('announcement.target_range_kind_from'), value: 'FROM' },
  { label: t('announcement.target_range_kind_between'), value: 'BETWEEN' },
])

const groupOptions = computed(() => groups.value.map(g => ({ label: g.name ?? '', value: g.id ?? '' })))

function resetRangeInputs() {
  rangeKind.value = null
  rangeFrom.value = null
  rangeTo.value = null
}

/** 端がそろった範囲だけをモデルへ反映する（端が未選択の間は範囲を送らない） */
function pushRange() {
  let range: BroadcastGroupRange | null = null
  if (rangeKind.value === 'UP_TO' && rangeTo.value) {
    range = { fromGroupId: null, toGroupId: rangeTo.value }
  }
  else if (rangeKind.value === 'FROM' && rangeFrom.value) {
    range = { fromGroupId: rangeFrom.value, toGroupId: null }
  }
  else if (rangeKind.value === 'BETWEEN' && rangeFrom.value && rangeTo.value) {
    range = { fromGroupId: rangeFrom.value, toGroupId: rangeTo.value }
  }
  update({ targetGroupRange: range })
}

function onRangeKind(value: RangeKind | null) {
  rangeKind.value = value
  pushRange()
}

function onRangeFrom(value: string | null) {
  rangeFrom.value = value
  pushRange()
}

function onRangeTo(value: string | null) {
  rangeTo.value = value
  pushRange()
}

/** 外部（テンプレート適用）から範囲が入ったとき、入力欄をそれに合わせる */
watch(
  () => props.modelValue.targetGroupRange,
  (range) => {
    if (!range) return
    rangeFrom.value = range.fromGroupId
    rangeTo.value = range.toGroupId
    rangeKind.value = range.fromGroupId && range.toGroupId ? 'BETWEEN' : range.toGroupId ? 'UP_TO' : 'FROM'
  },
  { immediate: true },
)

// ── 宛先プレビュー（300ms デバウンス・F01.2.1 §8.4） ──

const preview = ref<AudiencePreview | null>(null)
const previewError = ref<string | null>(null)
const previewPending = ref(false)
let previewTimer: ReturnType<typeof setTimeout> | null = null
let previewSeq = 0

function clearPreviewTimer() {
  if (previewTimer !== null) {
    clearTimeout(previewTimer)
    previewTimer = null
  }
}

function describeError(e: unknown): string {
  const { code, message } = readApiError(e)
  if (message) return code ? `${message} (${code})` : message
  return t('announcement.preview_failed')
}

async function runPreview() {
  const seq = ++previewSeq
  const s = props.modelValue
  try {
    // 削除で空になったテンプレートは、宛先を明示せず templateId だけで解決させる（BE の emptiedByRemoval）
    const audience = templateEmptied.value && s.templateId !== null
      ? { templateId: s.templateId }
      : buildAudienceFields(s)
    const result = await previewAudience({
      // チャネル未選択（ステップ1）では送らない。push の可否は実チャネルが分かるときだけ使う
      channel: s.selectedChannel ?? undefined,
      targetRole: s.targetRole,
      ...audience,
    })
    if (seq !== previewSeq) return
    preview.value = result
    previewError.value = null
  }
  catch (e: unknown) {
    if (seq !== previewSeq) return
    preview.value = null
    previewError.value = describeError(e)
  }
  finally {
    if (seq === previewSeq) previewPending.value = false
  }
}

function schedulePreview() {
  clearPreviewTimer()
  previewSeq++
  const s = props.modelValue
  if (!isOrg.value || s.targetMode === 'ALL' || !(hasAudienceSelection(s) || templateEmptied.value)) {
    // 宛先を絞らない・未選択のときはプレビュー結果を持ち越さない
    preview.value = null
    previewError.value = null
    previewPending.value = false
    return
  }
  previewPending.value = true
  previewTimer = setTimeout(() => {
    previewTimer = null
    void runPreview()
  }, PREVIEW_DEBOUNCE_MS)
}

watch(
  () => JSON.stringify([
    props.modelValue.targetMode,
    props.modelValue.targetRole,
    props.modelValue.selectedChannel,
    props.modelValue.templateId,
    templateEmptied.value,
    buildAudienceFields(props.modelValue),
  ]),
  schedulePreview,
  { immediate: true },
)

onBeforeUnmount(clearPreviewTimer)

const resolvedEmpty = computed(
  () => preview.value !== null && preview.value.resolvedTeamCount === 0 && preview.value.directMemberCount === 0,
)

/** 宛先を絞ったのに該当が無い・確認中・確認に失敗した間は「次へ」を押せない（AC-H06） */
const nextDisabled = computed(() => {
  if (!isOrg.value || props.modelValue.targetMode === 'ALL') return false
  if (!(hasAudienceSelection(props.modelValue) || templateEmptied.value)) return true
  return previewPending.value || previewError.value !== null || preview.value === null || resolvedEmpty.value
})

/** push の可否はチャネルごとに違う。実チャネルが決まっているときだけ注記を出す（未選択の仮判定は出さない） */
const pushDisabledNote = computed(
  () => preview.value !== null && preview.value.pushEnabled === false && props.modelValue.selectedChannel !== null,
)

// ── テンプレート ──

/** 'removed' = 削除済みグループを除外した / 'range_invalid' = 範囲の端が削除済み（範囲を選び直す） */
const templateNotice = ref<'removed' | 'range_invalid' | null>(null)

function templateHasGroupItems(template: AnnouncementTemplate): boolean {
  return (template.targetGroupIds?.length ?? 0) > 0
    || template.targetGroupRange != null
    || template.includeUnassigned === true
}

async function onTemplateApply(template: AnnouncementTemplate) {
  templateNotice.value = null
  // 世代はグループ一覧の待機より前に採番する（待機中に宛先を手で変えたら、この適用は捨てる）
  const gen = ++audienceGen
  // グループ一覧（機能 on/off の判定を含む）が揃う前に適用すると、グループ付きテンプレートを誤って「すべて」にしてしまう
  await groupsReady
  if (gen !== audienceGen) return
  const hasGroups = isOrg.value && groupsEnabled.value && templateHasGroupItems(template)
  const mode: BroadcastTargetMode = template.targetTeamIds != null ? 'TEAMS' : hasGroups ? 'GROUPS' : 'ALL'
  const next: WizardFormState = {
    ...props.modelValue,
    targetRole: template.targetRole,
    targetMode: mode,
    targetTeamIds: mode === 'TEAMS' ? template.targetTeamIds : null,
    targetGroupIds: mode === 'GROUPS' ? (template.targetGroupIds ?? []) : [],
    targetGroupRange: mode === 'GROUPS' ? (template.targetGroupRange ?? null) : null,
    includeUnassigned: mode === 'GROUPS' && template.includeUnassigned === true,
    templateEmptied: false,
    // preferred_channel が設定されていればステップ2のチャネル初期選択に使う
    selectedChannel: template.preferredChannel ?? props.modelValue.selectedChannel,
    // グループ付きなのに画面で扱えない（機能 off）ときは templateId を残さない（BE が旧宛先を復元してしまう）
    templateId: mode === 'ALL' && templateHasGroupItems(template) ? null : template.id,
  }
  emit('update:modelValue', next)
  if (mode !== 'GROUPS') return

  // テンプレートはグループの指定だけを保存している。削除済みグループの除外（警告）と
  // 範囲の端の削除（BROADCAST_013）は、サーバーに templateId で解決させて知る（F01.2.1 §8.6）。
  // 応答待ちの間に宛先・テンプレートが変わったら（gen が進む）、古い応答は捨てる。
  try {
    const result = await previewAudience({
      channel: next.selectedChannel ?? undefined,
      targetRole: next.targetRole,
      templateId: template.id,
    })
    if (gen !== audienceGen) return
    if ((result.warnings ?? []).some(w => w.startsWith('TEMPLATE_GROUPS_REMOVED'))) {
      templateNotice.value = 'removed'
      const alive = new Set(groups.value.map(g => g.id))
      const kept = next.targetGroupIds.filter(id => alive.has(id))
      const emptied = kept.length === 0 && next.targetGroupRange === null && !next.includeUnassigned
      // 全部除外されても BE は直属メンバー宛てとして許可する。選択が空に見えても次へ進めるようにする
      emit('update:modelValue', { ...props.modelValue, targetGroupIds: kept, templateEmptied: emptied })
    }
  }
  catch (e: unknown) {
    if (gen !== audienceGen) return
    if (readApiError(e).code === 'BROADCAST_013') {
      // 範囲の端が削除済み: 範囲を空にして選び直させる。templateId を外し、再送でも 013 に戻らないようにする
      templateNotice.value = 'range_invalid'
      resetRangeInputs()
      emit('update:modelValue', { ...props.modelValue, targetGroupRange: null, templateId: null })
      return
    }
    previewError.value = describeError(e)
  }
}

const templateId = computed({
  get() {
    return props.modelValue.templateId
  },
  set(value: number | null) {
    update({ templateId: value })
  },
})

// ── 現在の範囲をテンプレートとして保存（ADMIN / DEPUTY_ADMIN のみ表示） ──
const templateSelector = ref<{ refresh: () => Promise<void> } | null>(null)
const showSaveForm = ref(false)
const newTemplateName = ref('')

function openSaveForm() {
  newTemplateName.value = ''
  showSaveForm.value = true
}

function cancelSaveForm() {
  showSaveForm.value = false
  newTemplateName.value = ''
}

async function saveAsTemplate() {
  const name = newTemplateName.value.trim()
  if (name.length === 0) return

  const fields = buildAudienceFields(props.modelValue)
  const request: AnnouncementTemplateRequest = {
    name,
    targetRole: props.modelValue.targetRole,
    ...fields,
    preferredChannel: props.modelValue.selectedChannel,
  }

  try {
    await createTemplate(request)
    notification.success(t('announcement.template_save_success'))
    showSaveForm.value = false
    newTemplateName.value = ''
    // 選択リストへ即時反映するため、セレクター側の一覧を再取得する
    await templateSelector.value?.refresh()
  } catch {
    // createTemplate は失敗時に文字列を throw する（上限超過・認可エラー等）
    notification.error(t('announcement.template_save_failed'))
  }
}
</script>

<template>
  <div class="flex flex-col gap-6">
    <!-- 対象ロール -->
    <div>
      <p class="mb-3 font-medium text-surface-700 dark:text-surface-300">
        {{ t('announcement.target_role_label') }}
      </p>
      <div class="flex flex-col gap-2">
        <div v-for="opt in targetRoleOptions" :key="opt.value" class="flex items-center gap-2">
          <RadioButton
            v-model="targetRole"
            :input-id="`target_role_${opt.value}`"
            :value="opt.value"
          />
          <label :for="`target_role_${opt.value}`" class="cursor-pointer">{{ opt.label }}</label>
        </div>
      </div>
    </div>

    <!-- 組織告知の場合のみ: 対象チーム選択 -->
    <div v-if="scopeType === 'ORGANIZATION'" class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <p class="mb-3 font-medium text-surface-700 dark:text-surface-300">
        {{ t('announcement.target_teams_label') }}
      </p>
      <div class="flex flex-col gap-2">
        <div class="flex items-center gap-2">
          <RadioButton
            v-model="targetMode"
            input-id="target_mode_all"
            value="ALL"
            data-testid="broadcast-target-mode-all"
          />
          <label for="target_mode_all" class="cursor-pointer font-medium">
            {{ t('announcement.target_mode_all') }}
          </label>
        </div>
        <div class="flex items-center gap-2">
          <RadioButton
            v-model="targetMode"
            input-id="target_mode_teams"
            value="TEAMS"
            data-testid="broadcast-target-mode-teams"
          />
          <label for="target_mode_teams" class="cursor-pointer font-medium">
            {{ t('announcement.target_mode_teams') }}
          </label>
        </div>
        <!-- チーム個別選択（TEAMS のときのみ表示） -->
        <template v-if="targetMode === 'TEAMS' && orgTeams.length > 0">
          <div
            v-for="team in orgTeams"
            :key="team.id"
            class="flex items-center gap-2 pl-6"
          >
            <Checkbox
              :input-id="`team_${team.id}`"
              :model-value="isTeamChecked(team.id)"
              :binary="true"
              @update:model-value="(val) => toggleTeam(team.id, val as boolean)"
            />
            <label :for="`team_${team.id}`" class="cursor-pointer text-sm">
              {{ team.name }}
            </label>
          </div>
        </template>
        <div v-if="groupsEnabled" class="flex items-center gap-2">
          <RadioButton
            v-model="targetMode"
            input-id="target_mode_groups"
            value="GROUPS"
            data-testid="broadcast-target-mode-groups"
          />
          <label for="target_mode_groups" class="cursor-pointer font-medium">
            {{ t('announcement.target_mode_groups') }}
          </label>
        </div>
      </div>

      <p
        v-if="groupsLoadError"
        class="mt-3 text-sm text-red-600 dark:text-red-400"
        role="alert"
        data-testid="broadcast-groups-error"
      >
        {{ groupsLoadError }}
      </p>

      <!-- チームグループで選ぶ: 個別チェック・範囲・未分類 -->
      <div v-if="targetMode === 'GROUPS'" class="mt-4 flex flex-col gap-4 border-t border-surface-200 pt-4 dark:border-surface-700">
        <div>
          <p class="mb-2 text-sm font-medium text-surface-700 dark:text-surface-300">
            {{ t('announcement.target_groups_label') }}
          </p>
          <div class="flex flex-col gap-2">
            <div
              v-for="g in groups"
              :key="g.id"
              class="flex items-center gap-2 pl-2"
              :data-testid="`broadcast-group-item-${g.id}`"
            >
              <Checkbox
                :input-id="`group_${g.id}`"
                :model-value="isGroupChecked(g.id ?? '')"
                :binary="true"
                @update:model-value="(val) => toggleGroup(g.id ?? '', val as boolean)"
              />
              <label :for="`group_${g.id}`" class="cursor-pointer text-sm">{{ g.name }}</label>
            </div>
          </div>
          <p class="mt-2 text-xs text-surface-500">
            {{ t('announcement.target_order_hint') }}
          </p>
        </div>

        <div>
          <p class="mb-2 text-sm font-medium text-surface-700 dark:text-surface-300">
            {{ t('announcement.target_range_label') }}
          </p>
          <div class="flex flex-wrap items-center gap-2">
            <Select
              :model-value="rangeKind"
              :options="rangeKindOptions"
              option-label="label"
              option-value="value"
              :placeholder="t('announcement.target_range_label')"
              :show-clear="true"
              class="w-40"
              data-testid="broadcast-range-kind"
              @update:model-value="onRangeKind"
            />
            <Select
              v-if="rangeKind === 'FROM' || rangeKind === 'BETWEEN'"
              :model-value="rangeFrom"
              :options="groupOptions"
              option-label="label"
              option-value="value"
              :placeholder="t('announcement.target_range_pick_group')"
              class="w-48"
              data-testid="broadcast-range-from"
              @update:model-value="onRangeFrom"
            />
            <span v-if="rangeKind === 'BETWEEN'" class="text-sm">〜</span>
            <Select
              v-if="rangeKind === 'UP_TO' || rangeKind === 'BETWEEN'"
              :model-value="rangeTo"
              :options="groupOptions"
              option-label="label"
              option-value="value"
              :placeholder="t('announcement.target_range_pick_group')"
              class="w-48"
              data-testid="broadcast-range-to"
              @update:model-value="onRangeTo"
            />
          </div>
        </div>

        <div class="flex items-center gap-2" data-testid="broadcast-include-unassigned">
          <Checkbox v-model="includeUnassigned" input-id="include_unassigned" :binary="true" />
          <label for="include_unassigned" class="cursor-pointer text-sm">
            {{ t('announcement.target_include_unassigned') }}
          </label>
        </div>

        <p class="text-xs text-surface-500">
          {{ t('announcement.target_direct_members_note') }}
        </p>
      </div>

      <!-- 宛先プレビュー（グループ・チームを絞ったとき） -->
      <div
        v-if="targetMode !== 'ALL'"
        class="mt-4 flex flex-col gap-2"
        aria-live="polite"
      >
        <p
          v-if="previewError"
          class="text-sm text-red-600 dark:text-red-400"
          role="alert"
          data-testid="broadcast-preview-error"
        >
          {{ previewError }}
        </p>
        <p
          v-else-if="preview"
          class="text-sm font-medium"
          :class="resolvedEmpty ? 'text-red-600 dark:text-red-400' : 'text-surface-700 dark:text-surface-300'"
          data-testid="broadcast-resolved-count"
        >
          {{
            resolvedEmpty
              ? t('announcement.target_resolved_empty')
              : t('announcement.target_resolved_count', { teams: preview.resolvedTeamCount, members: preview.directMemberCount })
          }}
        </p>
        <p
          v-else-if="previewPending"
          class="text-sm text-surface-500"
          data-testid="broadcast-preview-loading"
        >
          {{ t('announcement.preview_loading') }}
        </p>
        <p
          v-if="pushDisabledNote"
          class="text-sm text-amber-700 dark:text-amber-400"
          data-testid="broadcast-push-disabled-note"
        >
          {{ t('announcement.push_disabled_note') }}
        </p>
      </div>
    </div>

    <!-- テンプレートの警告（削除済みグループの除外・範囲の端の削除） -->
    <div
      v-if="templateNotice === 'removed'"
      class="rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-700 dark:bg-amber-900/20 dark:text-amber-300"
      role="status"
      data-testid="broadcast-template-warning"
    >
      {{ t('announcement.template_deleted_groups_removed') }}
    </div>
    <div
      v-if="templateNotice === 'range_invalid'"
      class="rounded-lg border border-red-300 bg-red-50 p-3 text-sm text-red-800 dark:border-red-700 dark:bg-red-900/20 dark:text-red-300"
      role="alert"
      data-testid="broadcast-template-range-invalid"
    >
      {{ t('announcement.template_range_invalid') }}
    </div>

    <!-- テンプレート選択 -->
    <div class="flex flex-col gap-3">
      <BroadcastTemplateSelector
        ref="templateSelector"
        v-model="templateId"
        :scope-type="scopeType"
        :scope-id="scopeId"
        @apply="onTemplateApply"
      />

      <!-- 現在の範囲をテンプレートとして保存（ADMIN / DEPUTY_ADMIN のみ） -->
      <div v-if="isAdmin">
        <Button
          v-if="!showSaveForm"
          :label="t('announcement.template_create')"
          icon="pi pi-save"
          severity="secondary"
          text
          size="small"
          @click="openSaveForm"
        />
        <div v-else class="flex items-center gap-2">
          <InputText
            v-model="newTemplateName"
            :placeholder="t('announcement.template_name_placeholder')"
            class="flex-1"
            :maxlength="100"
            @keyup.enter="saveAsTemplate"
          />
          <Button
            :label="$t('button.save')"
            icon="pi pi-check"
            size="small"
            :disabled="newTemplateName.trim().length === 0"
            :loading="saving"
            @click="saveAsTemplate"
          />
          <Button
            :label="$t('button.cancel')"
            severity="secondary"
            text
            size="small"
            :disabled="saving"
            @click="cancelSaveForm"
          />
        </div>
      </div>
    </div>

    <!-- ナビゲーション -->
    <div class="flex justify-end pt-2">
      <Button
        :label="$t('button.next')"
        icon="pi pi-arrow-right"
        icon-pos="right"
        :disabled="nextDisabled"
        data-testid="broadcast-step1-next"
        @click="emit('next')"
      />
    </div>
  </div>
</template>
