<script setup lang="ts">
import type { TeamOrgAffiliation } from '~/composables/useOrgTeamMembershipApi'
import { formatAffiliationDate } from '~/utils/memberTeamsView'

/**
 * F01.2.1 8-D — 組織から受け取った加盟招待の一覧と、承諾・辞退（設計書 §6.5・AC-D02/D03）。
 *
 * チームの加盟操作者にだけ親（affiliations）が描画する。辞退は組織が一定期間再招待できなくなる操作
 * なので確認ダイアログを挟み、「今後この組織からの招待を受け付けない」を選べる。
 * 承諾・辞退に失敗したとき（既に処理済み・取消済みなど）は、エラーを出して一覧を取り直す。
 */
const props = defineProps<{
  teamSlug: string
}>()

const emit = defineEmits<{
  /** 一覧の取得が成功した（total は件数バッジ用）。 */
  loaded: [total: number]
  /** 承諾・辞退が成立した（所属組織の再取得などに使う）。 */
  changed: []
}>()

const { t, locale } = useI18n()
const toast = useToast()
const { listInvites, acceptInvite, declineInvite } = useTeamAffiliationApi()

const PAGE_SIZE = 20

const { items, loading, loadFailed, page, totalPages, total, load, reset } = useLatestPagedList<TeamOrgAffiliation>(
  p => listInvites(props.teamSlug, p, PAGE_SIZE),
)

watch(loading,(isLoading) => {
  if (!isLoading && !loadFailed.value) emit('loaded', total.value)
})

const busyId = ref<number | null>(null)
const actionFailed = ref(false)

const declineTarget = ref<TeamOrgAffiliation | null>(null)
const showDecline = ref(false)
const blockFuture = ref(false)
const declineFailed = ref(false)

watch(() => props.teamSlug, () => reset())

async function accept(invite: TeamOrgAffiliation) {
  if (busyId.value !== null || invite.id === undefined) return
  busyId.value = invite.id
  actionFailed.value = false
  try {
    await acceptInvite(props.teamSlug, invite.id)
    toast.add({ severity: 'success', summary: t('teamAffiliations.invites.accepted'), life: 3000 })
    emit('changed')
  }
  catch {
    // 失敗は明示する。処理済み・取消済みの古い行を残さないよう、一覧は取り直す。
    actionFailed.value = true
  }
  finally {
    busyId.value = null
  }
  await load()
}

function askDecline(invite: TeamOrgAffiliation) {
  declineTarget.value = invite
  blockFuture.value = false
  declineFailed.value = false
  showDecline.value = true
}

async function confirmDecline() {
  const id = declineTarget.value?.id
  if (id === undefined || busyId.value !== null) return
  busyId.value = id
  declineFailed.value = false
  let succeeded = false
  try {
    await declineInvite(props.teamSlug, id, blockFuture.value)
    showDecline.value = false
    toast.add({ severity: 'success', summary: t('teamAffiliations.invites.declined'), life: 3000 })
    emit('changed')
    succeeded = true
  }
  catch {
    // 失敗は確認ダイアログ内に表示して残す（辞退できたように見せない）。
    declineFailed.value = true
  }
  finally {
    busyId.value = null
  }
  if (succeeded || declineFailed.value) await load()
}

onMounted(() => void load())
</script>

<template>
  <div data-testid="team-affiliations-invites">
    <Message v-if="actionFailed" severity="error" class="mb-3" data-testid="invite-action-error">
      {{ t('teamAffiliations.invites.action_error') }}
    </Message>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 2rem; height: 2rem" />
    </div>

    <Message v-else-if="loadFailed" severity="error" data-testid="invites-load-error">
      {{ t('teamAffiliations.invites.load_error') }}
    </Message>

    <p
      v-else-if="items.length === 0"
      class="py-6 text-center text-sm text-surface-500"
      data-testid="invites-empty"
    >
      {{ t('teamAffiliations.invites.empty') }}
    </p>

    <ul v-else class="divide-y divide-surface-200 dark:divide-surface-700">
      <li
        v-for="invite in items"
        :key="invite.id"
        class="flex flex-wrap items-start justify-between gap-3 py-3"
        data-testid="invite-row"
      >
        <div class="min-w-0 flex-1 space-y-1">
          <p class="font-medium">
            {{ invite.organization?.name }}
          </p>
          <p v-if="invite.teamGroup?.name" class="text-xs text-surface-500">
            {{ t('teamAffiliations.invites.group', { group: invite.teamGroup.name }) }}
          </p>
          <p v-if="invite.requestedAt" class="text-xs text-surface-500">
            {{ t('teamAffiliations.invites.invited_at', { date: formatAffiliationDate(invite.requestedAt, locale) }) }}
          </p>
          <p v-if="invite.expiresAt" class="text-xs text-surface-500">
            {{ t('teamAffiliations.invites.expires_at', { date: formatAffiliationDate(invite.expiresAt, locale) }) }}
          </p>
          <p
            v-if="invite.message"
            class="whitespace-pre-line rounded-lg bg-surface-50 p-2 text-sm text-surface-700 dark:bg-surface-800 dark:text-surface-200"
          >
            {{ invite.message }}
          </p>
        </div>
        <div class="flex gap-2">
          <Button
            :label="t('teamAffiliations.invites.accept')"
            icon="pi pi-check"
            size="small"
            :loading="busyId === invite.id"
            :disabled="busyId !== null"
            :data-testid="`invite-accept-${invite.id}`"
            @click="accept(invite)"
          />
          <Button
            :label="t('teamAffiliations.invites.decline')"
            icon="pi pi-times"
            size="small"
            severity="danger"
            outlined
            :disabled="busyId !== null"
            :data-testid="`invite-decline-${invite.id}`"
            @click="askDecline(invite)"
          />
        </div>
      </li>
    </ul>

    <MemberTeamsPager v-model:page="page" :total-pages="totalPages" />

    <Dialog
      v-model:visible="showDecline"
      modal
      :header="t('teamAffiliations.invites.decline_title', { org: declineTarget?.organization?.name ?? '' })"
      class="w-full max-w-md"
    >
      <div class="space-y-3 text-sm">
        <p>{{ t('teamAffiliations.invites.decline_hint') }}</p>
        <label class="flex items-center gap-2">
          <Checkbox v-model="blockFuture" binary data-testid="invite-decline-block" />
          <span>{{ t('teamAffiliations.invites.decline_block') }}</span>
        </label>
        <Message v-if="declineFailed" severity="error" data-testid="invite-decline-error">
          {{ t('teamAffiliations.invites.decline_error') }}
        </Message>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" text @click="showDecline = false" />
        <Button
          :label="t('teamAffiliations.invites.decline')"
          severity="danger"
          :loading="busyId !== null"
          :disabled="busyId !== null"
          data-testid="invite-decline-confirm"
          @click="confirmDecline"
        />
      </template>
    </Dialog>
  </div>
</template>
