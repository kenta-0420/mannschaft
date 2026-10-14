<script setup lang="ts">
import type { TeamOrgAffiliation } from '~/composables/useOrgTeamMembershipApi'
import { formatAffiliationDate } from '~/utils/memberTeamsView'

/**
 * F01.2.1 8-D — チームが組織へ出した加盟申請（申請中）の一覧と取下げ（設計書 §6.4）。
 *
 * チームの加盟操作者にだけ親（affiliations）が描画する。取下げは申請を取り消す操作なので確認を挟む。
 * 既に承認・拒否されて消えた行を取り下げようとしたとき（404/409）は、エラーを出して一覧を取り直す。
 */
const props = defineProps<{
  teamSlug: string
}>()

const emit = defineEmits<{
  changed: []
}>()

const { t, locale } = useI18n()
const toast = useToast()
const { listApplications, withdrawApplication } = useTeamAffiliationApi()

const PAGE_SIZE = 20

const { items, loading, loadFailed, page, totalPages, load, reset } = useLatestPagedList<TeamOrgAffiliation>(
  p => listApplications(props.teamSlug, p, PAGE_SIZE),
)

watch(() => props.teamSlug, () => reset())

const withdrawTarget = ref<TeamOrgAffiliation | null>(null)
const showWithdraw = ref(false)
const withdrawing = ref(false)
const withdrawFailed = ref(false)

function askWithdraw(application: TeamOrgAffiliation) {
  withdrawTarget.value = application
  withdrawFailed.value = false
  showWithdraw.value = true
}

async function confirmWithdraw() {
  const id = withdrawTarget.value?.id
  if (id === undefined || withdrawing.value) return
  withdrawing.value = true
  withdrawFailed.value = false
  try {
    await withdrawApplication(props.teamSlug, id)
    showWithdraw.value = false
    toast.add({ severity: 'success', summary: t('teamAffiliations.applications.withdrawn'), life: 3000 })
    emit('changed')
  }
  catch {
    // 失敗は確認ダイアログ内に表示して残す（取り下げられたように見せない）。
    withdrawFailed.value = true
  }
  finally {
    withdrawing.value = false
  }
  await load()
}

onMounted(() => void load())
</script>

<template>
  <div data-testid="team-affiliations-applications">
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 2rem; height: 2rem" />
    </div>

    <Message v-else-if="loadFailed" severity="error" data-testid="applications-load-error">
      {{ t('teamAffiliations.applications.load_error') }}
    </Message>

    <p
      v-else-if="items.length === 0"
      class="py-6 text-center text-sm text-surface-500"
      data-testid="applications-empty"
    >
      {{ t('teamAffiliations.applications.empty') }}
    </p>

    <ul v-else class="divide-y divide-surface-200 dark:divide-surface-700">
      <li
        v-for="application in items"
        :key="application.id"
        class="flex flex-wrap items-start justify-between gap-3 py-3"
        data-testid="application-row"
      >
        <div class="min-w-0 flex-1 space-y-1">
          <p class="font-medium">
            {{ application.organization?.name }}
          </p>
          <p v-if="application.teamGroup?.name" class="text-xs text-surface-500">
            {{ t('teamAffiliations.applications.group', { group: application.teamGroup.name }) }}
          </p>
          <p v-if="application.requestedAt" class="text-xs text-surface-500">
            {{ t('teamAffiliations.applications.requested_at', { date: formatAffiliationDate(application.requestedAt, locale) }) }}
          </p>
          <p v-if="application.expiresAt" class="text-xs text-surface-500">
            {{ t('teamAffiliations.applications.expires_at', { date: formatAffiliationDate(application.expiresAt, locale) }) }}
          </p>
          <p
            v-if="application.message"
            class="whitespace-pre-line rounded-lg bg-surface-50 p-2 text-sm text-surface-700 dark:bg-surface-800 dark:text-surface-200"
          >
            {{ application.message }}
          </p>
        </div>
        <Button
          :label="t('teamAffiliations.applications.withdraw')"
          icon="pi pi-times"
          size="small"
          severity="danger"
          outlined
          :data-testid="`application-withdraw-${application.id}`"
          @click="askWithdraw(application)"
        />
      </li>
    </ul>

    <MemberTeamsPager v-model:page="page" :total-pages="totalPages" />

    <Dialog
      v-model:visible="showWithdraw"
      modal
      :header="t('teamAffiliations.applications.withdraw_confirm_title')"
      class="w-full max-w-md"
    >
      <div class="space-y-3 text-sm">
        <p>{{ t('teamAffiliations.applications.withdraw_confirm', { org: withdrawTarget?.organization?.name ?? '' }) }}</p>
        <Message v-if="withdrawFailed" severity="error" data-testid="application-withdraw-error">
          {{ t('teamAffiliations.applications.withdraw_error') }}
        </Message>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" text @click="showWithdraw = false" />
        <Button
          :label="t('teamAffiliations.applications.withdraw')"
          severity="danger"
          :loading="withdrawing"
          :disabled="withdrawing"
          data-testid="application-withdraw-confirm"
          @click="confirmWithdraw"
        />
      </template>
    </Dialog>
  </div>
</template>
