<script setup lang="ts">
/**
 * F01.5 フレンドフォルダ一覧。
 *
 * 役割:
 * - {@link useFriendFoldersApi.listFolders} でフォルダ一覧を取得し、
 *   {@link FriendFolderCard} を map 表示する。
 * - 空状態は {@link DashboardEmptyState} を使用。
 * - ローディング中は {@link PageLoading} を使用。
 *
 * Props:
 *   teamId  — 自チーム ID
 *   canEdit — ADMIN / MANAGE_FRIEND_TEAMS 保持 DEPUTY_ADMIN のみ true（カードに伝搬）
 *
 * Emits:
 *   refresh        — 親へリフレッシュが行われたことを通知（フォルダ数表示再取得等に使う）
 *   edit           — フォルダ編集要求（親で編集ダイアログを開く）
 *   delete         — フォルダ削除要求（親で削除確認ダイアログを出す）
 *   manageMembers  — フォルダメンバー管理パネル起動要求
 *
 * Expose:
 *   refresh  — 親からリロード要求するためのメソッド
 *   folders  — 現在のフォルダ一覧（親のフォルダ数計算用）
 */
import type { TeamFriendFolderView } from '~/types/friendFolders'

const props = defineProps<{
  teamId: number
  canEdit: boolean
}>()

const emit = defineEmits<{
  refresh: []
  edit: [folder: TeamFriendFolderView]
  delete: [folderId: number]
  manageMembers: [folder: TeamFriendFolderView]
}>()

const { t } = useI18n()
const { listFolders } = useFriendFoldersApi()
const { handleApiError } = useErrorHandler()

const folders = ref<TeamFriendFolderView[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    folders.value = await listFolders(props.teamId)
  }
  catch (error) {
    handleApiError(error)
  }
  finally {
    loading.value = false
  }
}

async function refresh() {
  await load()
  emit('refresh')
}

watch(
  () => props.teamId,
  () => {
    void load()
  },
)

onMounted(() => {
  void load()
})

defineExpose({ refresh, folders })
</script>

<template>
  <div class="flex flex-col gap-3">
    <PageLoading v-if="loading" size="40px" />

    <template v-else>
      <template v-if="folders.length > 0">
        <FriendFolderCard
          v-for="folder in folders"
          :key="folder.id"
          :folder="folder"
          :can-edit="canEdit"
          @edit="(f) => emit('edit', f)"
          @delete="(id) => emit('delete', id)"
          @manage-members="(f) => emit('manageMembers', f)"
        />
      </template>
      <DashboardEmptyState
        v-else
        icon="pi pi-folder-open"
        :message="t('folders.list.empty')"
      />
    </template>
  </div>
</template>
