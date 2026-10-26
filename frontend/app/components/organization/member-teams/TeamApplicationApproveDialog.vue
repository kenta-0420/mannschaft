<script setup lang="ts">
import type { OrgTeamGroup, TeamOrgAffiliation } from '~/composables/useOrgTeamMembershipApi'
import { UNASSIGNED_GROUP_VALUE } from '~/utils/memberTeamsView'

/**
 * F01.2.1 8-B — 加盟申請の承認ダイアログ。
 *
 * 設計書: docs/features/F01.2.1_org_team_groups.md §13「承認ダイアログ」
 *
 * 希望グループを初期選択したドロップダウン（「未分類」を含む）と「承認」ボタン。
 * 変えた場合は「希望: {A} → 承認時: {B}」と表示し、`overrideGroup=true` と選んだ `groupId`
 * （未分類なら省略 = null）を送る。希望のままなら `overrideGroup=false` を送る。
 * グループ機能が off の組織（一覧が null）ではグループ欄を出さず、`overrideGroup=false` で承認する。
 */
const props = defineProps<{
  orgSlug: string
  application: TeamOrgAffiliation
}>()

const visible = defineModel<boolean>('visible', { default: false })

const emit = defineEmits<{
  approved: []
}>()

const { t } = useI18n()
const toast = useToast()
const { approveApplication, listTeamGroups } = useOrgTeamMembershipApi()

const groups = ref<OrgTeamGroup[] | null>(null)
const loading = ref(false)
const groupsLoadFailed = ref(false)
const submitting = ref(false)
const submitFailed = ref(false)
const selectedGroup = ref<string>(UNASSIGNED_GROUP_VALUE)

const requestedGroupValue = computed(() => props.application.teamGroup?.id ?? UNASSIGNED_GROUP_VALUE)

const groupOptions = computed(() => [
  { id: UNASSIGNED_GROUP_VALUE, name: t('teamAffiliation.review.unassigned') },
  ...(groups.value ?? []).map(g => ({ id: g.id ?? '', name: g.name ?? '' })),
])

const showGroupSelect = computed(() => groups.value !== null)
const changed = computed(() => showGroupSelect.value && selectedGroup.value !== requestedGroupValue.value)

function groupName(value: string): string {
  return groupOptions.value.find(o => o.id === value)?.name ?? t('teamAffiliation.review.unassigned')
}

async function load() {
  loading.value = true
  groupsLoadFailed.value = false
  submitFailed.value = false
  groups.value = null
  selectedGroup.value = requestedGroupValue.value
  try {
    groups.value = await listTeamGroups(props.orgSlug)
  }
  catch {
    // 取得失敗は「グループなし」に見せず、エラーを出す。希望のままの承認（overrideGroup=false）だけは可能。
    groupsLoadFailed.value = true
    groups.value = null
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
  if (submitting.value || loading.value) return
  submitting.value = true
  submitFailed.value = false
  try {
    const id = props.application.id
    if (id === undefined) throw new Error('application id is missing')
    await approveApplication(
      props.orgSlug,
      id,
      changed.value
        ? {
            overrideGroup: true,
            groupId: selectedGroup.value === UNASSIGNED_GROUP_VALUE ? undefined : selectedGroup.value,
          }
        : { overrideGroup: false },
    )
    toast.add({ severity: 'success', summary: t('teamAffiliation.review.approved'), life: 3000 })
    emit('approved')
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
    :header="t('teamAffiliation.review.approve_dialog_title', { team: application.team?.name ?? '' })"
    class="w-full max-w-lg"
    data-testid="team-application-approve-dialog"
  >
    <div v-if="loading" class="py-6 text-center">
      <ProgressSpinner style="width: 2rem; height: 2rem" />
    </div>

    <div v-else class="space-y-4">
      <p
        v-if="application.message"
        class="whitespace-pre-line rounded-lg bg-surface-50 p-3 text-sm text-surface-700 dark:bg-surface-800 dark:text-surface-200"
      >
        {{ application.message }}
      </p>

      <Message v-if="groupsLoadFailed" severity="error" data-testid="approve-groups-load-error">
        {{ t('orgMemberTeams.groups_load_error') }}
      </Message>

      <div v-if="showGroupSelect">
        <label class="mb-1 block text-sm font-medium" for="approve-group-select">
          {{ t('teamAffiliation.review.approve_group') }}
        </label>
        <Select
          v-model="selectedGroup"
          input-id="approve-group-select"
          :options="groupOptions"
          option-label="name"
          option-value="id"
          class="w-full"
          data-testid="approve-group-select"
        />
        <p
          v-if="changed"
          class="mt-1 text-xs text-amber-600 dark:text-amber-400"
          data-testid="approve-group-change"
        >
          {{ t('teamAffiliation.review.requested_group', { requested: groupName(requestedGroupValue), decided: groupName(selectedGroup) }) }}
        </p>
      </div>

      <Message v-if="submitFailed" severity="error" data-testid="approve-error">
        {{ t('orgMemberTeams.approve.error') }}
      </Message>
    </div>

    <template #footer>
      <Button :label="t('button.close')" icon="pi pi-times" text @click="visible = false" />
      <Button
        :label="t('teamAffiliation.review.approve')"
        icon="pi pi-check"
        :loading="submitting"
        :disabled="loading || submitting"
        data-testid="approve-submit"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
