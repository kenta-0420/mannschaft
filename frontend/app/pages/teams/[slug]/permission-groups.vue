<script setup lang="ts">
/**
 * チーム詳細「権限グループ」タブ（永続シェル配下の子ルート）。F01.2.1 8-D（設計書 §3.2）。
 * ヘッダ・タブは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 *
 * 権限グループの一覧を、組織側と同じ `OrgPermissionGroupList`（スコープ非依存）で表示する。
 * `MANAGE_ORG_AFFILIATION` を含むグループには印が付く。グループの割当は、メンバー一覧
 * （/teams/{slug}/members）の各メンバーの操作メニュー「権限グループ」から行う。
 *
 * 画面を出すのはチーム ADMIN・管理レンズ ON のときだけ。それ以外が直打ちした場合は親シェルの
 * 滞在ガードが `/teams/{slug}` へ戻し（AC-K03）、ガードが効く前も一覧 API は呼ばない。
 */
import { useTeamShellContext } from '~/composables/useTeamShellContext'
import { useTeamMembers, type TeamPermissionGroup } from '~/composables/team/useTeamMembers'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const { t } = useI18n()
const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const { canManagePermissionGroups, team } = useTeamShellContext()
const { getPermissionGroups } = useTeamMembers()

const groups = ref<TeamPermissionGroup[]>([])
const showForm = ref(false)
const editingGroup = ref<TeamPermissionGroup | null>(null)

function openCreate() {
  editingGroup.value = null
  showForm.value = true
}

function openEdit(group: { id: number }) {
  editingGroup.value = groups.value.find(g => g.id === group.id) ?? null
  showForm.value = true
}
const loading = ref(false)
const loadFailed = ref(false)

/** 最後の要求の応答だけを反映する（チーム切替・再試行で古い応答が新しい表示を上書きしない）。 */
let requestSeq = 0

async function load() {
  if (!canManagePermissionGroups.value) return
  const seq = ++requestSeq
  loading.value = true
  loadFailed.value = false
  try {
    const res = await getPermissionGroups(teamSlug.value)
    if (seq !== requestSeq) return
    groups.value = res.data
  }
  catch {
    if (seq !== requestSeq) return
    // 取得失敗は「グループなし」に見せず、エラー表示にする（症状を隠さない）。
    groups.value = []
    loadFailed.value = true
  }
  if (seq === requestSeq) loading.value = false
}

onMounted(() => void load())
watch([canManagePermissionGroups, teamSlug], () => void load())
onBeforeUnmount(() => {
  requestSeq++
})
</script>

<template>
  <div v-if="canManagePermissionGroups" class="mt-4 space-y-4">
    <PageHeader
      :title="t('teamAffiliations.permission_groups.title')"
      size="sm"
      :back-to="`/teams/${teamSlug}`"
    />
    <SectionCard>
      <p class="mb-2 text-sm text-surface-500">
        {{ t('teamAffiliations.permission_groups.hint') }}
      </p>
      <div class="mb-2 flex flex-wrap items-center justify-between gap-2">
        <NuxtLink
          :to="`/teams/${teamSlug}/members`"
          class="text-sm text-primary-600 hover:underline"
          data-testid="permission-groups-go-members"
        >
          {{ t('teamAffiliations.permission_groups.go_members') }}
        </NuxtLink>
        <Button
          :label="t('teamAffiliations.permission_groups.create')"
          icon="pi pi-plus"
          size="small"
          data-testid="permission-group-create"
          @click="openCreate"
        />
      </div>
      <div v-if="loading" class="flex justify-center py-8">
        <ProgressSpinner style="width: 2rem; height: 2rem" />
      </div>
      <div v-else-if="loadFailed" class="space-y-3" data-testid="permission-groups-load-error">
        <Message severity="error">
          {{ t('teamAffiliations.permission_groups.load_error') }}
        </Message>
        <Button
          :label="t('common.retry')"
          icon="pi pi-refresh"
          size="small"
          outlined
          data-testid="permission-groups-retry"
          @click="load"
        />
      </div>
      <OrgPermissionGroupList v-else :groups="groups" editable @edit="openEdit" />
    </SectionCard>
    <TeamPermissionGroupFormDialog
      v-model:visible="showForm"
      :team-slug="teamSlug"
      :team-id="team?.numericId ?? null"
      :group="editingGroup"
      @saved="load"
    />
  </div>
</template>
