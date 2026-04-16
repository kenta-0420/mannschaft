<script setup lang="ts">
/**
 * F01.5 フレンドフォルダ メンバー管理パネル（スライドオーバー風 Dialog）。
 *
 * Phase 1 の制約:
 * - バックエンドに「フォルダ内メンバー一覧取得」API はまだ存在しない。
 *   そのため本パネルでは {@code team_friends} 一覧を全量取得し、
 *   画面内でローカルに「フォルダに追加済み」フラグを持たせて操作する
 *   （= 重複追加は 409 で検知して UI 側で表示）。
 * - Phase 2 でメンバー一覧取得 API が追加されたら、開いた時点で取得してステートを
 *   厳密に初期化する形に差し替える。
 *
 * UX:
 * - 上段: 「追加可能なフレンド」リスト（未追加）
 * - 下段: 「登録中のフレンド」リスト（追加済み）— Phase 1 は開いた後に追加した分のみ
 *   表示される暫定仕様
 * - 追加/削除は楽観更新。409（重複追加）時はトースト表示してロールバック
 */
import type { TeamFriendFolderView } from '~/types/friendFolders'
import type { TeamFriendView } from '~/types/friends'

const props = defineProps<{
  modelValue: boolean
  teamId: number
  folder: TeamFriendFolderView | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'updated': []
}>()

const { t } = useI18n()
const { listFriends } = useFriendTeamsApi()
const { addFolderMember, removeFolderMember } = useFriendFoldersApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

// ----- 状態 -----
/** 自チームのフレンド全量 */
const friends = ref<TeamFriendView[]>([])
/** フォルダに追加済みと判定された teamFriendId の集合（本パネル起動後の追加分のみ追跡） */
const memberIds = ref<Set<number>>(new Set<number>())
const loading = ref(false)
const busy = ref<Set<number>>(new Set<number>())

// ----- 表示データ -----
const currentMembers = computed(() =>
  friends.value.filter((f) => memberIds.value.has(f.teamFriendId)),
)

const availableFriends = computed(() =>
  friends.value.filter((f) => !memberIds.value.has(f.teamFriendId)),
)

// ----- エラー判定 -----
function is409(error: unknown): boolean {
  const apiError = error as { statusCode?: number; status?: number }
  return apiError?.statusCode === 409 || apiError?.status === 409
}

// ----- フレンド一覧ロード -----
async function loadFriends() {
  loading.value = true
  try {
    // Phase 1: 実質 20 件程度を想定。pageSize を大きめに取得
    const response = await listFriends(props.teamId, { page: 0, size: 100 })
    friends.value = response.data
  }
  catch (error) {
    handleApiError(error)
  }
  finally {
    loading.value = false
  }
}

// ----- 追加操作 -----
async function handleAdd(friend: TeamFriendView) {
  if (!props.folder) return
  if (busy.value.has(friend.teamFriendId)) return
  busy.value.add(friend.teamFriendId)
  // 楽観更新
  memberIds.value.add(friend.teamFriendId)
  try {
    await addFolderMember(props.teamId, props.folder.id, {
      teamFriendId: friend.teamFriendId,
    })
    notification.success(t('folders.messages.member_added'))
    emit('updated')
  }
  catch (error) {
    // 失敗時はロールバック
    memberIds.value.delete(friend.teamFriendId)
    if (is409(error)) {
      notification.warn(t('dialog.error'), t('folders.messages.duplicate_member'))
      // 既に登録されていたのでフラグは true に戻す
      memberIds.value.add(friend.teamFriendId)
    }
    else {
      handleApiError(error)
    }
  }
  finally {
    busy.value.delete(friend.teamFriendId)
  }
}

// ----- 削除操作 -----
async function handleRemove(friend: TeamFriendView) {
  if (!props.folder) return
  if (busy.value.has(friend.teamFriendId)) return
  busy.value.add(friend.teamFriendId)
  // 楽観更新
  memberIds.value.delete(friend.teamFriendId)
  try {
    await removeFolderMember(props.teamId, props.folder.id, friend.teamFriendId)
    notification.success(t('folders.messages.member_removed'))
    emit('updated')
  }
  catch (error) {
    // 失敗時はロールバック
    memberIds.value.add(friend.teamFriendId)
    handleApiError(error)
  }
  finally {
    busy.value.delete(friend.teamFriendId)
  }
}

// ----- 閉じる -----
function handleClose() {
  visible.value = false
}

// ----- 開いたときにロード・閉じたときにクリア -----
watch(
  () => props.modelValue,
  (open) => {
    if (open && props.folder) {
      memberIds.value = new Set<number>()
      void loadFriends()
    }
  },
)
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="folder
      ? t('folders.members.title') + ' — ' + folder.name
      : t('folders.members.title')"
    :modal="true"
    :style="{ width: '520px', maxHeight: '85vh' }"
  >
    <PageLoading v-if="loading" size="36px" />

    <div v-else class="flex flex-col gap-4">
      <!-- 現在のメンバー（Phase 1: 本パネル起動後に追加した分のみ表示） -->
      <section>
        <h3 class="mb-2 text-sm font-semibold text-surface-700 dark:text-surface-200">
          {{ t('folders.members.current') }}
          <span class="ml-1 text-xs font-normal text-surface-400">
            ({{ currentMembers.length }})
          </span>
        </h3>
        <div v-if="currentMembers.length === 0" class="py-3 text-center text-sm text-surface-400">
          {{ t('folders.members.empty') }}
        </div>
        <ul v-else class="flex flex-col gap-2">
          <li
            v-for="friend in currentMembers"
            :key="friend.teamFriendId"
            class="flex items-center justify-between gap-3 rounded-md border border-surface-200 bg-surface-0 p-2 dark:border-surface-700 dark:bg-surface-800"
          >
            <span class="truncate text-sm">
              {{ friend.friendTeamName ?? t('friends.list.visibility_private') }}
            </span>
            <Button
              :label="t('folders.actions.remove_member')"
              icon="pi pi-times"
              size="small"
              text
              severity="danger"
              :loading="busy.has(friend.teamFriendId)"
              @click="handleRemove(friend)"
            />
          </li>
        </ul>
      </section>

      <!-- 追加可能なフレンド -->
      <section>
        <h3 class="mb-2 text-sm font-semibold text-surface-700 dark:text-surface-200">
          {{ t('folders.members.available') }}
          <span class="ml-1 text-xs font-normal text-surface-400">
            ({{ availableFriends.length }})
          </span>
        </h3>
        <div v-if="availableFriends.length === 0" class="py-3 text-center text-sm text-surface-400">
          {{ t('folders.members.no_friends') }}
        </div>
        <ul v-else class="flex flex-col gap-2">
          <li
            v-for="friend in availableFriends"
            :key="friend.teamFriendId"
            class="flex items-center justify-between gap-3 rounded-md border border-surface-200 bg-surface-0 p-2 dark:border-surface-700 dark:bg-surface-800"
          >
            <span class="truncate text-sm">
              {{ friend.friendTeamName ?? t('friends.list.visibility_private') }}
            </span>
            <Button
              :label="t('folders.actions.add_member')"
              icon="pi pi-plus"
              size="small"
              :loading="busy.has(friend.teamFriendId)"
              @click="handleAdd(friend)"
            />
          </li>
        </ul>
      </section>
    </div>

    <template #footer>
      <Button :label="t('button.close')" @click="handleClose" />
    </template>
  </Dialog>
</template>
