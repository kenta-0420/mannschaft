<script setup lang="ts">
import type { TeamApplicationForm } from '~/composables/useTeamAffiliationApi'

/**
 * F01.2.1 8-A — チームから組織への加盟申請ダイアログ。
 *
 * 設計書: docs/features/F01.2.1_org_team_groups.md §13「申請ダイアログ」
 *
 * 流れ: チーム選択（`myTeams`。`affiliationStatus` が NONE 以外は選べず理由を表示）
 *   → 案内文 → グループ選択（REQUIRED は必須・未選択では送信不可 / OPTIONAL は「選ばない」を含む / OFF は出さない）
 *   → 添え書き → 送信。
 *
 * 申請ボタンを出すか否かは呼び出し側（TeamAffiliationApplyButton）が eligibility API で決める。
 * 本ダイアログは「開かれた」ときにだけ申請フォームの内容を取得する。
 */
const props = defineProps<{
  orgSlug: string
}>()

const visible = defineModel<boolean>('visible', { default: false })

const emit = defineEmits<{
  applied: []
}>()

const { t } = useI18n()
const toast = useToast()
const { getApplicationForm, applyToOrganization } = useTeamAffiliationApi()

const form = ref<TeamApplicationForm | null>(null)
const loading = ref(false)
const loadFailed = ref(false)
const submitting = ref(false)
const submitFailed = ref(false)

/** BE の添え書き上限（TeamOrgAffiliationService.MESSAGE_MAX_CODE_POINTS = 500。設計書 §10.2）。 */
const MESSAGE_MAX = 500
// BE はコードポイントで数える（絵文字は 1 文字）。String.length / maxlength は UTF-16 単位で
// 絵文字を 2 と数えるため使わない。超過時は切り詰めず、カウンターを赤にして送信不可にする。
const messageLength = computed(() => [...message.value].length)

const selectedTeamSlug = ref<string | null>(null)
const selectedGroupId = ref<string | null>(null)
const message = ref('')

const groupMode = computed(() => form.value?.groupMode ?? 'OFF')
const groups = computed(() => form.value?.groups ?? [])

/** 申請済み・加盟済み・招待中などのチームは選べない（理由をラベルに添える）。 */
const teamOptions = computed(() =>
  (form.value?.myTeams ?? []).map((team) => {
    const status = team.affiliationStatus ?? 'NONE'
    const selectable = status === 'NONE'
    return {
      slug: team.slug ?? '',
      label: selectable ? (team.name ?? '') : `${team.name ?? ''}（${t(`teamAffiliation.apply.status.${status}`)}）`,
      disabled: !selectable,
    }
  }),
)

const groupOptions = computed(() => {
  const options = groups.value.map(g => ({ id: g.id ?? '', name: g.name ?? '' }))
  // OPTIONAL のときだけ「選ばない」を選択肢に含める。REQUIRED は必ず選ばせる。
  return groupMode.value === 'OPTIONAL'
    ? [{ id: '', name: t('teamAffiliation.apply.group_none') }, ...options]
    : options
})

const groupRequiredButMissing = computed(
  () => groupMode.value === 'REQUIRED' && !selectedGroupId.value,
)

// フォームが取得できていない（取得中・取得失敗）ときは送信させない。groupMode の既定 OFF で
// グループ必須判定が素通りするのを防ぐ。
const canSubmit = computed(
  () =>
    !!form.value
    && !loadFailed.value
    && !!selectedTeamSlug.value
    && !groupRequiredButMissing.value
    && messageLength.value <= MESSAGE_MAX
    && !submitting.value
    && !loading.value,
)

async function load() {
  loading.value = true
  loadFailed.value = false
  submitFailed.value = false
  // 開くたびに前回の選択を持ち越さない（取得失敗時に古い選択のまま送信できてしまうため）。
  selectedTeamSlug.value = null
  selectedGroupId.value = null
  message.value = ''
  form.value = null
  try {
    form.value = await getApplicationForm(props.orgSlug)
    // 申請できるチームが1つだけなら選択の手間を省く。
    const selectable = teamOptions.value.filter(o => !o.disabled)
    selectedTeamSlug.value = selectable.length === 1 ? selectable[0]!.slug : null
  }
  catch {
    // 取得失敗は空フォームに見せず、エラー表示にする（症状を隠さない）。
    form.value = null
    loadFailed.value = true
  }
  finally {
    loading.value = false
  }
}

watch(
  visible,
  (open) => {
    if (open) void load()
  },
  { immediate: true },
)

async function submit() {
  if (!canSubmit.value || !selectedTeamSlug.value) return
  submitting.value = true
  submitFailed.value = false
  try {
    await applyToOrganization(selectedTeamSlug.value, {
      organizationSlug: props.orgSlug,
      // OFF のときや「選ばない」のときは groupId を送らない（BE が TEAM_072 で拒否するため）。
      groupId: groupMode.value !== 'OFF' && selectedGroupId.value ? selectedGroupId.value : undefined,
      message: message.value.trim() || undefined,
    })
    toast.add({ severity: 'success', summary: t('teamAffiliation.apply.success'), life: 4000 })
    emit('applied')
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
    :header="t('teamAffiliation.apply.dialog_title', { org: form?.organization?.name ?? orgSlug })"
    class="w-full max-w-lg"
    data-testid="team-affiliation-apply-dialog"
  >
    <div v-if="loading" class="py-6 text-center text-sm text-surface-500">
      <ProgressSpinner style="width: 2rem; height: 2rem" />
    </div>

    <Message v-else-if="loadFailed" severity="error" data-testid="apply-load-error">
      {{ t('teamAffiliation.apply.load_error') }}
    </Message>

    <div v-else-if="form" class="space-y-4">
      <p
        v-if="form.guidance"
        class="whitespace-pre-line rounded-lg bg-surface-50 p-3 text-sm text-surface-700 dark:bg-surface-800 dark:text-surface-200"
        data-testid="apply-guidance"
      >
        {{ form.guidance }}
      </p>

      <div>
        <label class="mb-1 block text-sm font-medium" for="apply-team-select">
          {{ t('teamAffiliation.apply.select_team') }}
        </label>
        <Select
          v-model="selectedTeamSlug"
          input-id="apply-team-select"
          :options="teamOptions"
          option-label="label"
          option-value="slug"
          option-disabled="disabled"
          class="w-full"
          data-testid="apply-team-select"
        />
      </div>

      <div v-if="groupMode !== 'OFF'">
        <label class="mb-1 block text-sm font-medium" for="apply-group-select">
          {{ t('teamAffiliation.apply.select_group') }}
          <span v-if="groupMode === 'REQUIRED'" class="text-red-500" aria-hidden="true">*</span>
        </label>
        <Select
          v-model="selectedGroupId"
          input-id="apply-group-select"
          :options="groupOptions"
          option-label="name"
          option-value="id"
          class="w-full"
          data-testid="apply-group-select"
        />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium" for="apply-message">
          {{ t('teamAffiliation.apply.message') }}
        </label>
        <Textarea
          id="apply-message"
          v-model="message"
          rows="3"
          class="w-full"
          data-testid="apply-message"
        />
        <p
          class="mt-1 text-right text-xs"
          :class="messageLength > MESSAGE_MAX ? 'text-red-500' : 'text-surface-400'"
          data-testid="apply-message-count"
        >
          {{ messageLength }} / {{ MESSAGE_MAX }}
        </p>
      </div>

      <Message v-if="submitFailed" severity="error" data-testid="apply-error">
        {{ t('teamAffiliation.apply.submit_error') }}
      </Message>
    </div>

    <template #footer>
      <Button
        :label="t('button.close')"
        icon="pi pi-times"
        text
        @click="visible = false"
      />
      <Button
        :label="t('teamAffiliation.apply.submit')"
        icon="pi pi-send"
        :loading="submitting"
        :disabled="!canSubmit"
        data-testid="apply-submit"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
