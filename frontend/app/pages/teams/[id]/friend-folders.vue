<script setup lang="ts">
/**
 * F01.5 フレンドフォルダ管理画面 — `/teams/{id}/friend-folders`。
 *
 * 権限:
 * - 認証必須（`auth` middleware）。
 * - フォルダ CRUD 操作は ADMIN、または MANAGE_FRIEND_TEAMS 保持の DEPUTY_ADMIN のみ。
 * - それ以外のロールはアクセス拒否メッセージを表示。
 *
 * UI:
 * - {@link FriendFolderList} でフォルダカード一覧を表示
 * - 「フォルダ作成」ボタン（上限到達時は disabled）
 * - 上限案内「残り{remaining}個作成可能（最大20個）」
 * - 作成/編集ダイアログ（{@link FriendFolderFormDialog}）
 * - メンバー管理パネル（{@link FriendFolderMembersPanel}）
 */
import type { TeamFriendFolderView } from '~/types/friendFolders'

definePageMeta({ middleware: 'auth' })

/** フォルダ数上限（設計書 §4） */
const FOLDER_MAX = 20

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const { t } = useI18n()
const { isAdmin, isAdminOrDeputy, can, loadPermissions } = useRoleAccess('team', teamId)

const { deleteFolder } = useFriendFoldersApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { confirmAction } = useConfirmDialog()

// ----- 権限 -----
// フォルダ管理操作: ADMIN または DEPUTY_ADMIN で MANAGE_FRIEND_TEAMS 保持
const canManage = computed(
  () => isAdmin.value || (isAdminOrDeputy.value && can('MANAGE_FRIEND_TEAMS')),
)

// ----- 一覧コンポーネント参照 -----
const listRef = ref<{
  refresh: () => Promise<void>
  folders: TeamFriendFolderView[]
} | null>(null)

// ----- 作成・編集ダイアログ -----
const formDialogOpen = ref(false)
const editingFolder = ref<TeamFriendFolderView | null>(null)

// ----- メンバー管理パネル -----
const membersPanelOpen = ref(false)
const membersPanelFolder = ref<TeamFriendFolderView | null>(null)

// ----- 残数表示 -----
const folderCount = computed(() => listRef.value?.folders?.length ?? 0)
const remaining = computed(() => Math.max(0, FOLDER_MAX - folderCount.value))
const atLimit = computed(() => folderCount.value >= FOLDER_MAX)

// ----- ハンドラ -----
function openCreateDialog() {
  if (atLimit.value) {
    notification.warn(t('dialog.error'), t('folders.messages.max_exceeded'))
    return
  }
  editingFolder.value = null
  formDialogOpen.value = true
}

function openEditDialog(folder: TeamFriendFolderView) {
  editingFolder.value = folder
  formDialogOpen.value = true
}

function openMembersPanel(folder: TeamFriendFolderView) {
  membersPanelFolder.value = folder
  membersPanelOpen.value = true
}

async function onFolderSaved() {
  await listRef.value?.refresh()
}

function handleDelete(folderId: number) {
  const target = listRef.value?.folders.find((f) => f.id === folderId)
  if (!target) return
  confirmAction({
    header: t('folders.actions.delete'),
    message: t('folders.messages.delete_confirm'),
    onAccept: async () => {
      try {
        await deleteFolder(teamId.value, folderId)
        notification.success(t('folders.messages.deleted'))
        await listRef.value?.refresh()
      }
      catch (error) {
        handleApiError(error)
      }
    },
  })
}

async function onMembersUpdated() {
  // メンバー数表示を最新化するため一覧を再取得
  await listRef.value?.refresh()
}

onMounted(() => {
  void loadPermissions()
})
</script>

<template>
  <div>
    <div class="mb-6 flex items-start justify-between gap-3">
      <div class="flex items-start gap-3">
        <BackButton />
        <div>
          <PageHeader :title="t('folders.title')" />
          <p v-if="canManage" class="text-sm text-surface-500">
            {{ t('folders.list.remaining', { remaining }) }}
          </p>
        </div>
      </div>
      <Button
        v-if="canManage"
        :label="t('folders.create')"
        icon="pi pi-plus"
        :disabled="atLimit"
        @click="openCreateDialog"
      />
    </div>

    <!-- 権限なし -->
    <div
      v-if="!canManage"
      class="rounded-lg border border-surface-200 bg-surface-0 py-12 text-center text-surface-500 dark:border-surface-700 dark:bg-surface-800"
    >
      <i class="pi pi-lock mb-3 text-3xl" aria-hidden="true" />
      <p class="text-sm">{{ t('friends.errors.permission_denied') }}</p>
    </div>

    <!-- フォルダ一覧 -->
    <FriendFolderList
      v-else
      ref="listRef"
      :team-id="teamId"
      :can-edit="canManage"
      @edit="openEditDialog"
      @delete="handleDelete"
      @manage-members="openMembersPanel"
    />

    <!-- 作成・編集ダイアログ -->
    <FriendFolderFormDialog
      v-model="formDialogOpen"
      :team-id="teamId"
      :folder="editingFolder"
      @saved="onFolderSaved"
    />

    <!-- メンバー管理パネル -->
    <FriendFolderMembersPanel
      v-model="membersPanelOpen"
      :team-id="teamId"
      :folder="membersPanelFolder"
      @updated="onMembersUpdated"
    />
  </div>
</template>
