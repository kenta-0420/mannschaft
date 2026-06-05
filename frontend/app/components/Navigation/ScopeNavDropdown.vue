<script setup lang="ts">
import type { ScopeType } from '~/types/scopeFolder'

/**
 * F15.3 ヘッダー用スコープ（チーム/組織）ナビゲーション ドロップダウン。
 *
 * 設計書 F15.3 §7.2 に準拠。
 * - `scopeType` Prop により basePath を切り替え（TEAM → /teams, ORGANIZATION → /organizations）
 * - チームドロップダウンが組織パスへ遷移することは絶対にない
 * - URL クエリ `?folder=` をソース・オブ・トゥルースとして使用
 * - フォルダ行クリックでサブリスト展開（インライン展開）。外部リンクアイコンでハブ遷移。
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

/** 展開中のフォルダID（null = すべて折りたたみ）。 */
const expandedFolderId = ref<number | null>(null)

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
  role: string
}

const myScopes = computed<NavScopeItem[]>(() => {
  if (props.scopeType === 'TEAM') {
    return teamStore.myTeams.map(team => ({
      id: team.id,
      name: team.name,
      nickname1: team.nickname1,
      role: team.role,
    }))
  }
  return orgStore.myOrganizations.map(org => ({
    id: org.id,
    name: org.name,
    nickname1: org.nickname1,
    role: org.role,
  }))
})

/** メンバー（SUPPORTER 以外）スコープ一覧。 */
const memberScopes = computed(() =>
  myScopes.value.filter(s => s.role !== 'SUPPORTER'),
)

/** サポータースコープ一覧。 */
const supporterScopes = computed(() =>
  myScopes.value.filter(s => s.role === 'SUPPORTER'),
)

/** 「すべて（一覧）」行の展開状態。 */
const showAllExpanded = ref(false)

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

/** 「すべて（一覧）」行クリック → ドロップダウン内でメンバー/サポーター別リストをインライン展開トグル。 */
function goAll() {
  showAllExpanded.value = !showAllExpanded.value
}

/** 「すべて」ハブ画面への遷移（外部リンクアイコン用）。 */
function goAllHub() {
  router.push(basePath.value)
  close()
}

/** フォルダ行クリック → 展開/折りたたみトグル（ハブへは遷移しない）。 */
function toggleFolder(folderId: number) {
  expandedFolderId.value = expandedFolderId.value === folderId ? null : folderId
}

/** フォルダ外部リンクアイコンクリックで `?folder=<id>` を付けてハブへ遷移。 */
function goFolder(folderId: number) {
  router.push({ path: basePath.value, query: { folder: String(folderId) } })
  close()
}

/** フォルダ内のスコープ一覧を返す。 */
function scopesInFolder(folder: { itemScopeIds: number[] }): NavScopeItem[] {
  return myScopes.value.filter(s => folder.itemScopeIds.includes(s.id))
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
  // フォルダ一覧と個別スコープ一覧を並列フェッチ
  // ストアが空のままだと myScopes computed が空になりジャンプリンクが表示されないため
  const scopeFetch = props.scopeType === 'TEAM'
    ? teamStore.fetchMyTeams()
    : orgStore.fetchMyOrganizations()
  Promise.all([ensureFoldersLoaded(), scopeFetch]).catch(() => {
    // フェッチ失敗してもメニュー操作は阻害しない
  })
}

function onPopoverHide() {
  isPopoverOpen.value = false
  expandedFolderId.value = null
  showAllExpanded.value = false
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

            <!-- すべて（一覧）→ 展開トグル -->
            <button
              role="menuitem"
              type="button"
              class="flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
              @click="goAll"
            >
              <i class="pi pi-list text-base text-surface-500" />
              <span class="flex-1">{{ t('scopeFolder.nav.allList') }}</span>
              <i
                class="pi pi-chevron-down text-xs text-surface-400 transition-transform"
                :class="{ 'rotate-180': showAllExpanded }"
                aria-hidden="true"
              />
              <!-- ハブ画面への遷移アイコン -->
              <span
                class="ml-1 flex h-5 w-5 shrink-0 items-center justify-center rounded hover:bg-surface-200"
                :title="t('scopeFolder.nav.showAll')"
                @click.stop="goAllHub"
              >
                <i class="pi pi-arrow-up-right text-xs text-surface-400" aria-hidden="true" />
              </span>
            </button>

            <!-- すべて展開時のサブリスト（メンバー/サポーター区分） -->
            <template v-if="showAllExpanded">
              <!-- メンバーセクション -->
              <template v-if="memberScopes.length > 0">
                <div class="px-4 pt-2 pb-0.5 text-xs font-semibold text-surface-400">
                  {{ t('scopeFolder.nav.sectionMember') }}
                </div>
                <button
                  v-for="scope in memberScopes"
                  :key="`all-member-${scope.id}`"
                  role="menuitem"
                  type="button"
                  class="flex w-full items-center gap-3 py-1.5 pr-4 pl-10 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
                  @click="goScope(scope.id)"
                >
                  <i class="pi pi-arrow-right text-xs text-surface-400 shrink-0" aria-hidden="true" />
                  <span class="flex-1 truncate">{{ scope.nickname1 || scope.name }}</span>
                </button>
              </template>

              <!-- サポーターセクション -->
              <template v-if="supporterScopes.length > 0">
                <div class="px-4 pt-2 pb-0.5 text-xs font-semibold text-surface-400">
                  {{ t('scopeFolder.nav.sectionSupporter') }}
                </div>
                <button
                  v-for="scope in supporterScopes"
                  :key="`all-supporter-${scope.id}`"
                  role="menuitem"
                  type="button"
                  class="flex w-full items-center gap-3 py-1.5 pr-4 pl-10 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
                  @click="goScope(scope.id)"
                >
                  <i class="pi pi-arrow-right text-xs text-surface-400 shrink-0" aria-hidden="true" />
                  <span class="flex-1 truncate">{{ scope.nickname1 || scope.name }}</span>
                </button>
              </template>

              <!-- どちらも空の場合 -->
              <p
                v-if="memberScopes.length === 0 && supporterScopes.length === 0"
                class="py-1.5 pl-10 pr-4 text-xs text-surface-400"
              >
                {{ t('scopeFolder.emptyFolder') }}
              </p>
            </template>

            <!-- ユーザー作成フォルダ一覧（展開トグル + サブリスト） -->
            <div
              v-for="folder in customFolders"
              :key="`folder-${folder.id}`"
              class="flex flex-col"
            >
              <!-- フォルダ行（展開トグル） -->
              <button
                role="menuitem"
                type="button"
                class="flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
                :data-testid="`scope-nav-dropdown-folder-${folder.id}`"
                @click="toggleFolder(folder.id)"
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
                <span class="shrink-0 text-xs text-surface-400">{{ folderItemCount(folder.id) }}</span>
                <i
                  class="pi pi-chevron-down text-xs text-surface-400 transition-transform"
                  :class="{ 'rotate-180': expandedFolderId === folder.id }"
                  aria-hidden="true"
                />
                <!-- ハブ遷移ボタン（外部リンクアイコン） -->
                <span
                  class="ml-1 flex h-5 w-5 shrink-0 items-center justify-center rounded hover:bg-surface-200"
                  :title="t('scopeFolder.nav.manage')"
                  @click.stop="goFolder(folder.id)"
                >
                  <i class="pi pi-arrow-up-right text-xs text-surface-400" aria-hidden="true" />
                </span>
              </button>

              <!-- サブリスト（展開時のみ） -->
              <template v-if="expandedFolderId === folder.id">
                <button
                  v-for="scope in scopesInFolder(folder)"
                  :key="`folder-${folder.id}-scope-${scope.id}`"
                  role="menuitem"
                  type="button"
                  class="flex w-full items-center gap-3 py-1.5 pr-4 pl-10 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
                  @click="goScope(scope.id)"
                >
                  <i class="pi pi-arrow-right text-xs text-surface-400 shrink-0" aria-hidden="true" />
                  <span class="flex-1 truncate">{{ scope.nickname1 || scope.name }}</span>
                </button>
                <!-- フォルダが空の場合 -->
                <p
                  v-if="scopesInFolder(folder).length === 0"
                  class="py-1.5 pl-10 pr-4 text-xs text-surface-400"
                >
                  {{ t('scopeFolder.emptyFolder') }}
                </p>
              </template>
            </div>

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
            @click="goAllHub"
          >
            <i class="pi pi-list text-xs" aria-hidden="true" />
            {{ t('scopeFolder.nav.showAll') }}
          </button>

          <div class="flex items-center gap-1">
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
