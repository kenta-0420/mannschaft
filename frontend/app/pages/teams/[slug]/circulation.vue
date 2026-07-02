<script setup lang="ts">
import type { CirculationDocumentListItem } from '~/types/circulation'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const loading = ref(true)

// 一覧コンポーネントの参照（押印後の一覧再取得に使う）。
const listRef = ref<{ refresh: () => void } | null>(null)

// 詳細モーダルの状態。
const detailVisible = ref(false)
const selectedDocumentId = ref<number | null>(null)
// 詳細 EP（/teams/{teamId}/circulations/{documentId}）は数値 teamId を要求するため、
// 選択 item の scopeId（数値チーム ID）を渡す。表示名解決は slug でメンバー一覧を引く。
const selectedTeamId = ref<string>(teamSlug)

function onSelect(item: CirculationDocumentListItem) {
  selectedDocumentId.value = item.id
  selectedTeamId.value = String(item.scopeId)
  detailVisible.value = true
}

// 作成モーダルの状態。
const createVisible = ref(false)

function onCreate() {
  createVisible.value = true
}

// 作成 + 開始が完了したら一覧を再取得する（新しい回覧が「回覧中」で出る）。
function onCreated() {
  listRef.value?.refresh()
}

function onStamped() {
  listRef.value?.refresh()
}

onMounted(async () => {
  try {
    await loadPermissions()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <div class="mb-4 flex items-center gap-3">
      <PageHeader title="回覧板" />
    </div>
    <CirculationList
      ref="listRef"
      scope-type="TEAM"
      :scope-id="teamSlug"
      :can-manage="isAdminOrDeputy"
      @select="onSelect"
      @create="onCreate"
    />
    <CirculationDetailModal
      v-model:visible="detailVisible"
      :document-id="selectedDocumentId"
      :team-id="selectedTeamId"
      :team-slug="teamSlug"
      @stamped="onStamped"
    />
    <CirculationCreateModal
      v-model:visible="createVisible"
      :team-slug="teamSlug"
      @created="onCreated"
    />
  </div>
</template>
