<script setup lang="ts">
import type { OrgTeamGroupList } from '~/composables/useOrgTeamMembershipApi'

/**
 * F01.2.1 8-C — チームグループ管理 view（member-teams?view=groups）。
 *
 * グループの作成・改名・並び替え・削除と、加盟チームへの割当（単体・一括）をまとめる。
 * 組織 ADMIN・管理レンズ ON のときだけ親（member-teams）が描画する。
 *
 * グループ一覧 API が 409（ORG_067）を返すのはグループ機能が off の組織。その場合は管理 UI を出さず、
 * 組織設定（加盟の設定）へ誘導するカードを出す。取得失敗（それ以外）は機能 off や空に見せずエラー表示にする。
 */
const props = defineProps<{
  orgSlug: string
}>()

const { t } = useI18n()
const { listTeamGroupList } = useOrgTeamMembershipApi()

const list = ref<OrgTeamGroupList | null>(null)
const loading = ref(true)
const loadFailed = ref(false)

/** 一覧を取得する。初回以外はスピナーに切り替えず、画面を保ったまま差し替える。 */
async function load() {
  loadFailed.value = false
  try {
    list.value = await listTeamGroupList(props.orgSlug)
  }
  catch {
    // 取得失敗は「機能 off」「グループなし」に見せず、エラー表示にする（症状を隠さない）。
    list.value = null
    loadFailed.value = true
  }
  finally {
    loading.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <div data-testid="member-teams-groups">
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 2rem; height: 2rem" />
    </div>

    <div v-else-if="loadFailed" class="space-y-3" data-testid="groups-load-error">
      <Message severity="error">
        {{ t('orgMemberTeams.groups_load_error') }}
      </Message>
      <Button :label="t('orgMemberTeams.groups.retry')" icon="pi pi-refresh" size="small" outlined @click="load" />
    </div>

    <div
      v-else-if="list === null"
      class="flex flex-col items-start gap-3 rounded-lg border border-dashed border-surface-300 p-6 dark:border-surface-600"
      data-testid="groups-disabled-card"
    >
      <p class="text-sm text-surface-700 dark:text-surface-200">
        {{ t('teamGroup.disabled_cta') }}
      </p>
      <NuxtLink
        :to="`/organizations/${orgSlug}/settings/team-affiliation`"
        class="inline-flex items-center gap-2 rounded-md border border-primary-300 bg-primary-50 px-3 py-2 text-sm font-medium text-primary-700 transition-colors hover:bg-primary-100 focus:outline-none focus:ring-2 focus:ring-primary-400"
        data-testid="groups-disabled-link"
      >
        <i class="pi pi-cog" aria-hidden="true" />
        {{ t('orgMemberTeams.groups.open_settings') }}
      </NuxtLink>
    </div>

    <div v-else class="space-y-8">
      <p class="text-sm text-surface-600 dark:text-surface-300" data-testid="groups-unassigned-count">
        {{ t('teamGroup.unassigned_count', { count: list.unassignedTeamCount }) }}
      </p>
      <MemberTeamsGroupList
        :org-slug="orgSlug"
        :groups="list.groups"
        :limit="list.limit"
        @changed="load"
      />
      <MemberTeamsGroupAssignments
        :org-slug="orgSlug"
        :groups="list.groups"
        @changed="load"
      />
    </div>
  </div>
</template>
