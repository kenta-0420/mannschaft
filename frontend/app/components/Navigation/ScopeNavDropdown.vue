<script setup lang="ts">
import type { ScopeType } from '~/types/scopeFolder'

/**
 * F15.3 ヘッダー用スコープ（チーム/組織）ナビゲーション ドロップダウン。
 *
 * 設計書 F15.3 §7.2 に準拠。
 * - `scopeType` Prop により basePath を切り替え（TEAM → /teams, ORGANIZATION → /organizations）
 * - チームドロップダウンが組織パスへ遷移することは絶対にない
 * - URL クエリ `?folder=` をソース・オブ・トゥルースとして使用
 * - a11y: aria-haspopup / aria-expanded / role=menu / 矢印キー / Esc 対応
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

const isOpen = ref(false)
const buttonRef = ref<HTMLButtonElement | null>(null)
const menuRef = ref<HTMLUListElement | null>(null)

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

function open() {
  isOpen.value = true
  // 開いた直後に最初のメニュー項目へフォーカス
  nextTick(() => {
    const first = menuRef.value?.querySelector<HTMLElement>('[role="menuitem"]')
    first?.focus()
  })
}

function close() {
  isOpen.value = false
  buttonRef.value?.focus()
}

function toggle() {
  if (isOpen.value) {
    close()
  }
  else {
    open()
  }
}

function handleEsc() {
  if (isOpen.value) {
    close()
  }
}

/** 矢印キーで menuitem 間を移動。 */
function focusSibling(current: HTMLElement, direction: 1 | -1) {
  const items = Array.from(
    menuRef.value?.querySelectorAll<HTMLElement>('[role="menuitem"]:not([disabled])') ?? [],
  )
  if (items.length === 0) return
  const idx = items.indexOf(current)
  const nextIdx = (idx + direction + items.length) % items.length
  items[nextIdx]?.focus()
}

function onKeydownItem(e: KeyboardEvent) {
  const target = e.currentTarget as HTMLElement
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    focusSibling(target, 1)
  }
  else if (e.key === 'ArrowUp') {
    e.preventDefault()
    focusSibling(target, -1)
  }
  else if (e.key === 'Escape') {
    e.preventDefault()
    close()
  }
}

/** ドロップダウン外クリックで閉じる。 */
const containerRef = ref<HTMLElement | null>(null)

function onDocClick(e: MouseEvent) {
  if (!isOpen.value) return
  const target = e.target as Node
  if (containerRef.value && !containerRef.value.contains(target)) {
    isOpen.value = false
  }
}

onMounted(() => {
  if (import.meta.client) {
    document.addEventListener('click', onDocClick)
    document.addEventListener('keydown', onKeydownDoc)
  }
})

onBeforeUnmount(() => {
  if (import.meta.client) {
    document.removeEventListener('click', onDocClick)
    document.removeEventListener('keydown', onKeydownDoc)
  }
})

function onKeydownDoc(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    handleEsc()
  }
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

/** 新規フォルダ作成 — 親へイベントを emit してモーダル表示を委譲（暫定: ハブへ遷移）。 */
function goCreateNew() {
  // 簡易実装: ハブの管理タブへ誘導しユーザー自身で作成してもらう。
  // 設計書 §7.2 では「モーダル」だが、F15.3 Phase 2-B 範囲では既存
  // `ScopeFolderEditDialog` をハブで開く運用に倒し、別 Phase で改善する。
  router.push({ path: basePath.value, query: { folder: 'manage', create: '1' } })
  close()
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

watch(isOpen, async (opened) => {
  if (opened) {
    await ensureFoldersLoaded()
  }
})
</script>

<template>
  <div ref="containerRef" class="relative inline-block" :data-testid="`scope-nav-dropdown-${scopeType}`">
    <button
      ref="buttonRef"
      type="button"
      class="flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap text-surface-600 transition-colors hover:bg-surface-100"
      aria-haspopup="menu"
      :aria-expanded="isOpen ? 'true' : 'false'"
      :aria-label="label"
      :data-testid="`scope-nav-dropdown-toggle-${scopeType}`"
      @click="toggle"
      @keydown.enter.prevent="toggle"
      @keydown.space.prevent="toggle"
      @keydown.down.prevent="open"
      @keydown.esc.prevent="handleEsc"
    >
      <i :class="props.scopeType === 'TEAM' ? 'pi pi-users' : 'pi pi-building'" />
      {{ label }}
      <i class="pi pi-chevron-down text-xs" :class="{ 'rotate-180': isOpen }" />
    </button>

    <ul
      v-if="isOpen"
      ref="menuRef"
      role="menu"
      :aria-label="label"
      class="absolute left-0 z-50 mt-1 max-h-96 w-72 overflow-y-auto rounded-lg border border-surface-200 bg-surface-0 py-2 shadow-lg"
    >
      <!-- すべて（一覧） -->
      <li role="none">
        <button
          role="menuitem"
          type="button"
          class="flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
          @click="goAll"
          @keydown="onKeydownItem"
        >
          <i class="pi pi-list text-base text-surface-500" />
          <span class="flex-1">{{ t('scopeFolder.nav.allList') }}</span>
        </button>
      </li>

      <!-- 区切り線 -->
      <li role="separator" class="my-1 border-t border-surface-200" />

      <!-- ユーザー作成フォルダ一覧 -->
      <li
        v-for="folder in customFolders"
        :key="`folder-${folder.id}`"
        role="none"
      >
        <button
          role="menuitem"
          type="button"
          class="flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
          :data-testid="`scope-nav-dropdown-folder-${folder.id}`"
          @click="goFolder(folder.id)"
          @keydown="onKeydownItem"
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
          <span class="shrink-0 text-xs text-surface-500">
            ({{ folderItemCount(folder.id) }})
          </span>
        </button>
      </li>

      <!-- 未分類フォルダ -->
      <li v-if="defaultFolder" role="none">
        <button
          role="menuitem"
          type="button"
          class="flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
          @click="goDefault"
          @keydown="onKeydownItem"
        >
          <span
            class="inline-block h-3 w-3 shrink-0 rounded-full bg-surface-300"
            aria-hidden="true"
          />
          <span class="flex-1 truncate">{{ t('scopeFolder.untagged') }}</span>
          <span class="shrink-0 text-xs text-surface-500">
            ({{ folderItemCount(defaultFolder.id) }})
          </span>
        </button>
      </li>

      <!-- 区切り線（直接ジャンプセクションの上） -->
      <li v-if="myScopes.length > 0" role="separator" class="my-1 border-t border-surface-200" />

      <!-- 直接ジャンプリスト -->
      <li
        v-for="scope in myScopes"
        :key="`scope-${scope.id}`"
        role="none"
      >
        <button
          role="menuitem"
          type="button"
          class="flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
          :data-testid="`scope-nav-dropdown-scope-${scope.id}`"
          @click="goScope(scope.id)"
          @keydown="onKeydownItem"
        >
          <i class="pi pi-arrow-right text-xs text-surface-400" aria-hidden="true" />
          <span class="flex-1 truncate">{{ scope.nickname1 || scope.name }}</span>
        </button>
      </li>

      <!-- 区切り線（操作セクションの上） -->
      <li role="separator" class="my-1 border-t border-surface-200" />

      <!-- 新規フォルダ作成 -->
      <li role="none">
        <button
          role="menuitem"
          type="button"
          class="flex w-full items-center gap-3 px-4 py-2 text-left text-sm text-primary hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
          @click="goCreateNew"
          @keydown="onKeydownItem"
        >
          <i class="pi pi-plus" aria-hidden="true" />
          <span class="flex-1">{{ t('scopeFolder.nav.createNew') }}</span>
        </button>
      </li>

      <!-- フォルダ管理 -->
      <li role="none">
        <button
          role="menuitem"
          type="button"
          class="flex w-full items-center gap-3 px-4 py-2 text-left text-sm hover:bg-surface-100 focus:bg-surface-100 focus:outline-none"
          @click="goManage"
          @keydown="onKeydownItem"
        >
          <i class="pi pi-cog text-surface-500" aria-hidden="true" />
          <span class="flex-1">{{ t('scopeFolder.nav.manage') }}</span>
        </button>
      </li>
    </ul>
  </div>
</template>
