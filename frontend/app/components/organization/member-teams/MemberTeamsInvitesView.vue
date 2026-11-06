<script setup lang="ts">
import type { TeamOrgAffiliation } from '~/composables/useOrgTeamMembershipApi'
import { formatAffiliationDate } from '~/utils/memberTeamsView'

/**
 * F01.2.1 8-B — 組織が送った加盟招待の一覧（取消）と、チームを招待するダイアログの入口。
 *
 * 組織 ADMIN・管理レンズ ON のときだけ親（member-teams）が描画する。
 * 取消は相手チームの受信招待が消える操作なので、確認を挟む。
 */
const props = defineProps<{
  orgSlug: string
}>()

const { t, locale } = useI18n()
const toast = useToast()
const { listInvites, cancelInvite } = useOrgTeamMembershipApi()

const PAGE_SIZE = 20

const items = ref<TeamOrgAffiliation[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const page = ref(0)
const totalPages = ref(1)

const showInvite = ref(false)
const cancelTarget = ref<TeamOrgAffiliation | null>(null)
const showCancelConfirm = ref(false)
const cancelling = ref(false)
const cancelFailed = ref(false)

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    const result = await listInvites(props.orgSlug, page.value, PAGE_SIZE)
    items.value = result.items
    totalPages.value = result.totalPages
    if (result.items.length === 0 && page.value > 0) {
      page.value -= 1
    }
  }
  catch {
    // 取得失敗は「招待なし」に見せず、エラー表示にする（症状を隠さない）。
    items.value = []
    loadFailed.value = true
  }
  finally {
    loading.value = false
  }
}

watch(page, () => void load())

function askCancel(invite: TeamOrgAffiliation) {
  cancelTarget.value = invite
  cancelFailed.value = false
  showCancelConfirm.value = true
}

async function confirmCancel() {
  const slug = cancelTarget.value?.team?.slug
  if (!slug) return
  cancelling.value = true
  cancelFailed.value = false
  try {
    await cancelInvite(props.orgSlug, slug)
    showCancelConfirm.value = false
    toast.add({ severity: 'success', summary: t('orgMemberTeams.invites.cancelled'), life: 3000 })
    await load()
  }
  catch {
    // 失敗は確認ダイアログ内に表示して残す（取り消せたように見せない）。
    cancelFailed.value = true
  }
  finally {
    cancelling.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <div data-testid="member-teams-invites">
    <div class="mb-4 flex justify-end">
      <Button
        :label="t('teamAffiliation.invite.button')"
        icon="pi pi-user-plus"
        size="small"
        data-testid="invite-open"
        @click="showInvite = true"
      />
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 2rem; height: 2rem" />
    </div>

    <Message v-else-if="loadFailed" severity="error" data-testid="invites-load-error">
      {{ t('orgMemberTeams.invites.load_error') }}
    </Message>

    <p
      v-else-if="items.length === 0"
      class="py-6 text-center text-sm text-surface-500"
      data-testid="invites-empty"
    >
      {{ t('orgMemberTeams.invites.empty') }}
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
            {{ invite.team?.name }}
          </p>
          <p v-if="invite.teamGroup?.name" class="text-xs text-surface-500">
            {{ t('orgMemberTeams.invites.group', { group: invite.teamGroup.name }) }}
          </p>
          <p v-if="invite.requestedAt" class="text-xs text-surface-500">
            {{ t('orgMemberTeams.invites.invited_at', { date: formatAffiliationDate(invite.requestedAt, locale) }) }}
          </p>
          <p v-if="invite.expiresAt" class="text-xs text-surface-500">
            {{ t('orgMemberTeams.invites.expires_at', { date: formatAffiliationDate(invite.expiresAt, locale) }) }}
          </p>
          <p
            v-if="invite.message"
            class="whitespace-pre-line rounded-lg bg-surface-50 p-2 text-sm text-surface-700 dark:bg-surface-800 dark:text-surface-200"
          >
            {{ invite.message }}
          </p>
        </div>
        <Button
          :label="t('teamAffiliation.invite.cancel')"
          icon="pi pi-times"
          size="small"
          severity="danger"
          outlined
          :data-testid="`invite-cancel-${invite.team?.slug}`"
          @click="askCancel(invite)"
        />
      </li>
    </ul>

    <MemberTeamsPager v-model:page="page" :total-pages="totalPages" />

    <TeamInviteDialog
      v-model:visible="showInvite"
      :org-slug="orgSlug"
      @invited="load"
    />

    <Dialog
      v-model:visible="showCancelConfirm"
      modal
      :header="t('orgMemberTeams.invites.cancel_confirm_title')"
      class="w-full max-w-md"
    >
      <div class="space-y-3 text-sm">
        <p>{{ t('orgMemberTeams.invites.cancel_confirm', { team: cancelTarget?.team?.name ?? '' }) }}</p>
        <Message v-if="cancelFailed" severity="error" data-testid="invite-cancel-error">
          {{ t('orgMemberTeams.invites.cancel_error') }}
        </Message>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" text @click="showCancelConfirm = false" />
        <Button
          :label="t('teamAffiliation.invite.cancel')"
          severity="danger"
          :loading="cancelling"
          data-testid="invite-cancel-confirm"
          @click="confirmCancel"
        />
      </template>
    </Dialog>
  </div>
</template>
