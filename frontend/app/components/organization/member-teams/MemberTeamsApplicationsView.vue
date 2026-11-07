<script setup lang="ts">
import type { TeamOrgAffiliation } from '~/composables/useOrgTeamMembershipApi'
import { formatAffiliationDate } from '~/utils/memberTeamsView'

/**
 * F01.2.1 8-B — 組織側の加盟申請一覧（承認・拒否）。
 *
 * 組織 ADMIN・管理レンズ ON のときだけ親（member-teams）が描画する。BE も組織 ADMIN 専用で 403 を返す。
 * 承認・拒否のあとは一覧を取り直し、親へ changed を通知する（件数バッジの更新用）。
 */
const props = defineProps<{
  orgSlug: string
}>()

const emit = defineEmits<{
  /** 一覧を取得できた。total は未処理の申請件数。 */
  loaded: [total: number]
  /** 承認・拒否が成立した。 */
  changed: []
}>()

const { t, locale } = useI18n()
const { listApplications } = useOrgTeamMembershipApi()

const PAGE_SIZE = 20

const items = ref<TeamOrgAffiliation[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const page = ref(0)
const totalPages = ref(1)

const target = ref<TeamOrgAffiliation | null>(null)
const showApprove = ref(false)
const showReject = ref(false)

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    const result = await listApplications(props.orgSlug, page.value, PAGE_SIZE)
    items.value = result.items
    totalPages.value = result.totalPages
    emit('loaded', result.total)
    // 最後のページの最後の1件を処理した場合は、1つ前のページへ戻る。
    if (result.items.length === 0 && page.value > 0) {
      page.value -= 1
    }
  }
  catch {
    // 取得失敗は「申請なし」に見せず、エラー表示にする（症状を隠さない）。
    items.value = []
    loadFailed.value = true
  }
  finally {
    loading.value = false
  }
}

watch(page, () => void load())

function openApprove(application: TeamOrgAffiliation) {
  target.value = application
  showApprove.value = true
}

function openReject(application: TeamOrgAffiliation) {
  target.value = application
  showReject.value = true
}

async function onProcessed() {
  await load()
  emit('changed')
}

onMounted(() => void load())
</script>

<template>
  <div data-testid="member-teams-applications">
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 2rem; height: 2rem" />
    </div>

    <Message v-else-if="loadFailed" severity="error" data-testid="applications-load-error">
      {{ t('orgMemberTeams.applications.load_error') }}
    </Message>

    <p
      v-else-if="items.length === 0"
      class="py-6 text-center text-sm text-surface-500"
      data-testid="applications-empty"
    >
      {{ t('orgMemberTeams.applications.empty') }}
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
            {{ application.team?.name }}
          </p>
          <p v-if="application.requestedBy?.displayName" class="text-xs text-surface-500">
            {{ t('orgMemberTeams.applications.requested_by', { name: application.requestedBy.displayName }) }}
          </p>
          <p v-if="application.teamGroup?.name" class="text-xs text-surface-500">
            {{ t('orgMemberTeams.applications.requested_group', { group: application.teamGroup.name }) }}
          </p>
          <p v-if="application.requestedAt" class="text-xs text-surface-500">
            {{ t('orgMemberTeams.applications.requested_at', { date: formatAffiliationDate(application.requestedAt, locale) }) }}
          </p>
          <p
            v-if="application.message"
            class="whitespace-pre-line rounded-lg bg-surface-50 p-2 text-sm text-surface-700 dark:bg-surface-800 dark:text-surface-200"
          >
            {{ application.message }}
          </p>
        </div>
        <div class="flex shrink-0 gap-2">
          <Button
            :label="t('teamAffiliation.review.approve')"
            icon="pi pi-check"
            size="small"
            :data-testid="`application-approve-${application.id}`"
            @click="openApprove(application)"
          />
          <Button
            :label="t('teamAffiliation.review.reject')"
            icon="pi pi-ban"
            size="small"
            severity="danger"
            outlined
            :data-testid="`application-reject-${application.id}`"
            @click="openReject(application)"
          />
        </div>
      </li>
    </ul>

    <MemberTeamsPager v-model:page="page" :total-pages="totalPages" />

    <TeamApplicationApproveDialog
      v-if="target"
      v-model:visible="showApprove"
      :org-slug="orgSlug"
      :application="target"
      @approved="onProcessed"
    />
    <TeamApplicationRejectDialog
      v-if="target"
      v-model:visible="showReject"
      :org-slug="orgSlug"
      :application="target"
      @rejected="onProcessed"
    />
  </div>
</template>
