<script setup lang="ts">
import type { ScopeType } from '~/types/scopeFolder'

/**
 * F15.3 ヘッダー用スコープ（チーム/組織）ナビゲーション ドロップダウン。
 *
 * 設計書 F15.3 §7.2 に準拠。
 * - `scopeType` Prop により basePath を切り替え（TEAM → /teams, ORGANIZATION → /organizations）
 * - チームドロップダウンが組織パスへ遷移することは絶対にない
 * - URL クエリ `?folder=` をソース・オブ・トゥルースとして使用
 * - a11y: aria-haspopup / aria-expanded / role=menu 対応
 * - PrimeVue Popover によるワイドフライアウトパネルで表示（overflow-x クリッピングを回避）
 */

interface Props {
  scopeType: ScopeType
  label: string
}

const props = defineProps<Props>()

const router = useRouter()
const { t } = useI18n()
const foldersStore = useScopeFoldersStore()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()

const popoverRef = ref()
const isPopoverOpen = ref(false)
const showCreateDialog = ref(false)

/** scopeType に応じたベースパス。チーム→/teams, 組織→/organizations。 */
const basePath = computed<string>(() =>
  props.scopeType === 'TEAM' ? '/teams' : '/organizations',
)

/** scopeType に応じたフォルダ一覧（未分類含む）。 */
const folders = computed(() => foldersStore.foldersFor(props.scopeType))

/** 未分類以外のユーザー作成フォルダ。 */
const customFolders = computed(() => foldersStore.customFoldersFor(props.scopeType))

/** 未分類フォルダ。 */
const defaultFolder = computed(() => foldersStore.defaultFolderFor(props.scopeType))

/** scopeType に応じた個別スコープ（直接ジャンプ用）。 */
interface NavScopeItem {
  id: number
  name: string
  nickname1: string | null
}

const myScopes = computed<NavScopeItem[]>(() => {
  if (props.scopeType === 'TEAM') {
    return teamStore.myTeams.map(team => ({
      id: team.id,
      name: team.name,
      nickname1: team.nickname1,
    }))
  }
  return orgStore.myOrganizations.map(org => ({
    id: org.id,
    name: org.name,
    nickname1: org.nickname1,
  }))
})

/**
 * フォルダ内アイテム件数（未読件数ではなく item 件数）。
 */
function folderItemCount(folderId: number): number {
  const f = folders.value.find(x => x.id === folderId)
  return f?.itemScopeIds.length ?? 0
}

function toggle(event: MouseEvent) {
  popoverRef.value?.toggle(event)
}

function close() {
  popoverRef.value?.hide()
}

/** 「すべて」へ遷移（タブ=すべて）。 */
function goAll() {
  router.push(basePath.value)
  close()
}

/** フォルダクリックで `?folder=<id>` を付けて遷移。 */
function goFolder(folderId: number) {
  router.push({ path: basePath.value, query: { folder: String(folderId) } })
  close()
}

/** 未分類フォルダへ遷移（`?folder=default`）。 */
function goDefault() {
  router.push({ path: basePath.value, query: { folder: 'default' } })
  close()
}

/** 個別スコープへ直接ジャンプ。 */
function goScope(scopeId: number) {
  router.push(`${basePath.value}/${scopeId}`)
  close()
}

/** 「フォルダ管理」（ハブ画面の管理タブ）へ遷移。 */
function goManage() {
  router.push({ path: basePath.value, query: { folder: 'manage' } })
  close()
}

/** 新規フォルダ作成ダイアログを直接開く。 */
function goCreateNew() {
  close()
  showCreateDialog.value = true
}

/** フォルダ保存後にフォルダ一覧を再フェッチ。 */
async function onFolderSaved() {
  await foldersStore.fetchAll(props.scopeType)
}

/**
 * 初期ロード: フォルダ一覧をフェッチ。
 * ストア状態が空ならフェッチ、既に持っていればスキップ（タブ切替時の高速化）。
 */
async function ensureFoldersLoaded() {
  if (folders.value.length === 0) {
    try {
      await foldersStore.fetchAll(props.scopeType)
    }
    catch {
      // 取得失敗してもメニュー操作は阻害しない（個別ジャンプ・「すべて」は使える）
    }
  }
}

function onPopoverShow() {
  isPopoverOpen.value = true
  ensureFoldersLoaded()
}

function onPopoverHide() {
  isPopoverOpen.value = false
}
</script>

<template>
  <div class="relative inline-block" :data-testid="`scope-nav-dropdown-${scopeType}`">
    <ScopeFolderEditDialog
      v-model:visible="showCreateDialog"
      :scope-type="scopeType"
      @saved="onFolderSaved"
    />
    <button
      type="button"
      class="flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap text-surface-600 transition-colors hover:bg-surface-100"
      aria-haspopup="menu"
      :aria-expanded="isPopoverOpen ? 'true' : 'false'"
      :aria-label="label"
      :data-testid="`scope-nav-dropdown-toggle-${scopeType}`"
      @click="toggle"
    >
      <i :class="props.scopeType === 'TEAM' ? 'pi pi-users' : 'pi pi-building'" />
      {{ label }}
      <i class="pi pi-chevron-down text-xs" :class="{ 'rotate-180': isPopoverOpen }" />
    </button>

    <Popover
      ref="popoverRef"
      @show="onPopoverShow"
      @hide="onPopoverHide"
    >
      <div class="flex flex-col" style="min-width: 520px; max-width: 700px">
        <!-- メインコンテンツ: 2カラムレイアウト -->
        <div class="flex gap-0 divide-x divide-surface-200">
          <!-- 左カラム: フォルダ一覧 -->
          <div class="flex-1 min-w-0 py-2">
            <div class="px-4 py-1.5 text-xs font-semibold uppercase tracking-wider text-surface-400">
              {{ t('scopeFolder.nav.foldersSection') }}
            </div>

            <!-- すべて（一覧） -->
            <button
              role="menuitem"
              type="button"
              class="flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
              @click="goAll"
            >
              <i class="pi pi-list text-base text-surface-500" />
              <span class="flex-1">{{ t('scopeFolder.nav.allList') }}</span>
            </button>

            <!-- ユーザー作成フォルダ一覧 -->
            <button
              v-for="folder in customFolders"
              :key="`folder-${folder.id}`"
              role="menuitem"
              type="button"
              class="flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
              :data-testid="`scope-nav-dropdown-folder-${folder.id}`"
              @click="goFolder(folder.id)"
            >
              <span
                class="inline-block h-3 w-3 shrink-0 rounded-full"
                :style="folder.color ? { backgroundColor: folder.color } : { backgroundColor: '#9CA3AF' }"
                aria-hidden="true"
              />
              <i
                v-if="folder.icon"
                :class="['pi', folder.icon, 'text-base text-surface-500']"
                aria-hidden="true"
              />
              <span class="flex-1 truncate">{{ folder.name }}</span>
              <span class="shrink-0 text-xs text-surface-400">
                {{ folderItemCount(folder.id) }}
              </span>
            </button>

            <!-- 未分類フォルダ -->
            <button
              v-if="defaultFolder"
              role="menuitem"
              type="button"
              class="flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
              @click="goDefault"
            >
              <span
                class="inline-block h-3 w-3 shrink-0 rounded-full bg-surface-300"
                aria-hidden="true"
              />
              <span class="flex-1 truncate">{{ t('scopeFolder.untagged') }}</span>
              <span class="shrink-0 text-xs text-surface-400">
                {{ folderItemCount(defaultFolder.id) }}
              </span>
            </button>
          </div>

          <!-- 右カラム: 直接ジャンプ（スコープが存在する場合のみ表示） -->
          <div v-if="myScopes.length > 0" class="flex-1 min-w-0 py-2">
            <div class="px-4 py-1.5 text-xs font-semibold uppercase tracking-wider text-surface-400">
              {{ t('scopeFolder.nav.quickJumpSection') }}
            </div>

            <button
              v-for="scope in myScopes"
              :key="`scope-${scope.id}`"
              role="menuitem"
              type="button"
              class="flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
              :data-testid="`scope-nav-dropdown-scope-${scope.id}`"
              @click="goScope(scope.id)"
            >
              <i class="pi pi-arrow-right text-xs text-surface-400" aria-hidden="true" />
              <span class="flex-1 truncate">{{ scope.nickname1 || scope.name }}</span>
            </button>
          </div>
        </div>

        <!-- フッター: アクションボタン -->
        <div class="flex items-center gap-1 border-t border-surface-200 px-4 py-2">
          <button
            type="button"
            class="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm text-surface-600 hover:bg-surface-100 focus:outline-none"
            @click="goAll"
          >
            <i class="pi pi-list text-xs" aria-hidden="true" />
            {{ t('scopeFolder.nav.showAll') }}
          </button>

          <div class="ml-auto flex items-center gap-1">
            <button
              type="button"
              class="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm text-primary hover:bg-primary-50 focus:outline-none"
              @click="goCreateNew"
            >
              <i class="pi pi-plus text-xs" aria-hidden="true" />
              {{ t('scopeFolder.nav.createNew') }}
            </button>

            <button
              type="button"
              class="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm text-surface-600 hover:bg-surface-100 focus:outline-none"
              @click="goManage"
            >
              <i class="pi pi-cog text-xs text-surface-500" aria-hidden="true" />
              {{ t('scopeFolder.nav.manage') }}
            </button>
          </div>
        </div>
      </div>
    </Popover>
  </div>
</template>
