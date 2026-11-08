<script setup lang="ts">
import type { TeamOrgAffiliation } from '~/composables/useOrgTeamMembershipApi'

/**
 * F01.2.1 8-B — 加盟申請の拒否ダイアログ。
 *
 * 設計書: docs/features/F01.2.1_org_team_groups.md §13「拒否ダイアログ」
 * 理由（任意）と「今後このチームからの申請を受け付けない」チェック。
 * チェックなしは 30 日の冷却、ありは無期限ブロック（BE が記録）。
 */
const props = defineProps<{
  orgSlug: string
  application: TeamOrgAffiliation
}>()

const visible = defineModel<boolean>('visible', { default: false })

const emit = defineEmits<{
  rejected: []
}>()

const { t } = useI18n()
const toast = useToast()
const { rejectApplication } = useOrgTeamMembershipApi()

/** BE の理由上限（RejectTeamApplicationRequest。500 コードポイント）。 */
const REASON_MAX = 500

const reason = ref('')
const block = ref(false)
const submitting = ref(false)
const submitFailed = ref(false)

// BE はコードポイントで数える（絵文字は 1 文字）ため、String.length は使わない。
const reasonLength = computed(() => [...reason.value].length)
const canSubmit = computed(() => reasonLength.value <= REASON_MAX && !submitting.value)

watch(
  visible,
  (open) => {
    if (open) {
      reason.value = ''
      block.value = false
      submitFailed.value = false
    }
  },
  { immediate: true },
)

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  submitFailed.value = false
  try {
    const id = props.application.id
    if (id === undefined) throw new Error('application id is missing')
    await rejectApplication(props.orgSlug, id, {
      reason: reason.value.trim() || undefined,
      block: block.value,
    })
    toast.add({ severity: 'success', summary: t('teamAffiliation.review.rejected'), life: 3000 })
    emit('rejected')
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
    :header="t('orgMemberTeams.reject.title', { team: application.team?.name ?? '' })"
    class="w-full max-w-lg"
    data-testid="team-application-reject-dialog"
  >
    <div class="space-y-4">
      <p class="text-sm text-surface-600 dark:text-surface-300">
        {{ t('orgMemberTeams.reject.hint') }}
      </p>

      <div>
        <label class="mb-1 block text-sm font-medium" for="reject-reason">
          {{ t('teamAffiliation.review.reject_reason') }}
        </label>
        <Textarea
          id="reject-reason"
          v-model="reason"
          rows="3"
          class="w-full"
          data-testid="reject-reason"
        />
        <p
          class="mt-1 text-right text-xs"
          :class="reasonLength > REASON_MAX ? 'text-red-500' : 'text-surface-400'"
          data-testid="reject-reason-count"
        >
          {{ reasonLength }} / {{ REASON_MAX }}
        </p>
      </div>

      <div class="flex items-center gap-2">
        <Checkbox
          v-model="block"
          binary
          input-id="reject-block"
          data-testid="reject-block"
        />
        <label class="text-sm" for="reject-block">
          {{ t('teamAffiliation.review.reject_block') }}
        </label>
      </div>

      <Message v-if="submitFailed" severity="error" data-testid="reject-error">
        {{ t('orgMemberTeams.reject.error') }}
      </Message>
    </div>

    <template #footer>
      <Button :label="t('button.close')" icon="pi pi-times" text @click="visible = false" />
      <Button
        :label="t('teamAffiliation.review.reject')"
        icon="pi pi-ban"
        severity="danger"
        :loading="submitting"
        :disabled="!canSubmit"
        data-testid="reject-submit"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
