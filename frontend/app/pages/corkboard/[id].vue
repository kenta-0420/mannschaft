<script setup lang="ts">
/**
 * F09.8 Phase B: コルクボード詳細ページ。
 *
 * 1 ファイルで個人 / チーム / 組織の 3 スコープ全対応する。
 * URL: /corkboard/{boardId}?scope=personal|team|organization&scopeId=N
 *
 * 設計書 §3 corkboard_cards / §4 GET /api/v1/corkboards/{id} に準拠。
 *
 * Phase B 実装範囲:
 *  - ヘッダー (戻る + ボード名 + scope バッジ)
 *  - サブヘッダー (ボード説明はバックエンド DTO に未含なので非表示。カード件数・セクション件数のみ)
 *  - カード一覧の絶対配置レンダリング (positionX / positionY を CSS top/left へ反映)
 *  - セクションは折りたたみ可能な領域として表示 (localStorage で状態保持)
 *  - 参照カードの「参照元削除済み」バッジ
 *  - URL カードの OGP サムネイル小サイズ表示
 *
 * Phase C 追加:
 *  - 「+ 新規カード」ボタン → CardEditorModal を create モードで開く
 *  - 各カードに編集 / 削除 / アーカイブ操作（ホバー時に表示するメニュー）
 *
 * Phase B 範囲外（後続 Phase で実装）:
 *  - D&D 位置移動 (Phase D)
 *  - セクション CRUD (Phase E)
 *  - WebSocket リアルタイム同期 (Phase F)
 *  - OGP プレビューの大画面詳細 (Phase G)
 *
 * バックエンドはスコープ別パスでのみ詳細 API を提供しているため、
 * フロント側はクエリパラメータで scope 情報を受け取り `getBoardDetail()` で振り分ける。
 * 設計書 §4 の `GET /api/v1/corkboards/{id}` (scope-agnostic) は Phase A では未提供。
 *
 * 注意: 組織 (ORGANIZATION) ボードの詳細 API は Phase A で未実装のため、
 *       現状 organization scope では 404 が返る可能性がある。フロント側は
 *       将来実装される前提で配線のみしておく。
 */
import { useToast } from 'primevue/usetoast'
import type {
  CorkboardDetail,
  CorkboardCardDetail,
  CorkboardGroupDetail,
  CorkboardScope,
  CorkboardColor,
} from '~/types/corkboard'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const {
  getBoardDetail,
  getBoardDetailByBoardId,
  deleteCard: apiDeleteCard,
  archiveCard: apiArchiveCard,
  togglePinCard: apiTogglePinCard,
  deleteGroup: apiDeleteGroup,
  addCardToGroup: apiAddCardToGroup,
  removeCardFromGroup: apiRemoveCardFromGroup,
} = useCorkboardApi()
const { captureQuiet } = useErrorReport()
const toast = useToast()
const { confirmAction } = useConfirmDialog()
const authStore = useAuthStore()

// ----- ルートパラメータ -----
const boardId = computed<number>(() => {
  const raw = route.params.id
  const v = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(v) ? v : 0
})

/** scope クエリは大文字小文字どちらでも受け取り、内部表現は大文字 */
const scopeParam = computed<CorkboardScope | null>(() => {
  const raw = String(route.query.scope ?? '').toUpperCase()
  if (raw === 'PERSONAL' || raw === 'TEAM' || raw === 'ORGANIZATION') {
    return raw
  }
  return null
})

const scopeIdParam = computed<number | null>(() => {
  const raw = route.query.scopeId
  if (raw == null) return null
  const v = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(v) ? v : null
})

// ----- 状態 -----
const board = ref<CorkboardDetail | null>(null)
const loading = ref(true)
const errorMessage = ref<string | null>(null)

/** セクションごとの折りたたみ状態（localStorage と同期） */
const collapsedSections = ref<Record<number, boolean>>({})

const storageKey = computed(() => `corkboard:collapse:${boardId.value}`)

function loadCollapsedState() {
  if (typeof window === 'undefined') return
  try {
    const raw = window.localStorage.getItem(storageKey.value)
    if (!raw) return
    const parsed: unknown = JSON.parse(raw)
    if (parsed && typeof parsed === 'object') {
      const obj: Record<number, boolean> = {}
      for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
        const id = Number(k)
        if (Number.isFinite(id) && typeof v === 'boolean') {
          obj[id] = v
        }
      }
      collapsedSections.value = obj
    }
  } catch {
    // localStorage が壊れている等は無視
  }
}

function persistCollapsedState() {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(
      storageKey.value,
      JSON.stringify(collapsedSections.value),
    )
  } catch {
    // QuotaExceeded 等は無視
  }
}

function toggleSection(sectionId: number) {
  collapsedSections.value = {
    ...collapsedSections.value,
    [sectionId]: !collapsedSections.value[sectionId],
  }
  persistCollapsedState()
}

function isSectionCollapsed(section: CorkboardGroupDetail): boolean {
  // localStorage に値があればそれを優先、無ければ DTO の isCollapsed を初期値とする
  const local = collapsedSections.value[section.id]
  if (typeof local === 'boolean') return local
  return section.isCollapsed
}

// ----- データ取得 -----
async function load() {
  loading.value = true
  errorMessage.value = null
  if (!boardId.value) {
    errorMessage.value = t('corkboard.boardNotFound')
    loading.value = false
    return
  }
  // PERSONAL 以外の scope は scopeId 必須（後方互換: scope クエリありで遷移する旧呼び出しを維持）
  if (scopeParam.value && scopeParam.value !== 'PERSONAL' && scopeIdParam.value == null) {
    errorMessage.value = t('corkboard.scopeMissing')
    loading.value = false
    return
  }
  try {
    // F09.8 Phase A2: scope クエリが無い場合は scope-agnostic API
    // (`GET /api/v1/corkboards/{boardId}`) を使ってバックエンドに scope 解決を任せる。
    // scope クエリがある場合は従来どおり scope 別パスで取得する（後方互換）。
    const res = scopeParam.value
      ? await getBoardDetail(scopeParam.value, scopeIdParam.value, boardId.value)
      : await getBoardDetailByBoardId(boardId.value)
    board.value = res.data
    loadCollapsedState()
  } catch (e) {
    captureQuiet(e, { context: 'CorkboardDetailPage: ボード取得失敗' })
    errorMessage.value = t('corkboard.boardLoadError')
    board.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch([boardId, scopeParam, scopeIdParam], load)

// ----- ナビ -----
function goBack() {
  // ヒストリーがあれば戻る、なければ一覧へ
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/corkboard')
  }
}

// ----- 表示ヘルパ -----
const COLOR_BAR: Record<string, string> = {
  WHITE: 'bg-surface-200',
  YELLOW: 'bg-yellow-400',
  RED: 'bg-red-400',
  BLUE: 'bg-blue-400',
  GREEN: 'bg-green-400',
  PURPLE: 'bg-purple-400',
  GRAY: 'bg-gray-400',
}

function colorBarClass(color: CorkboardColor | string | null): string {
  if (!color) return 'bg-surface-200'
  return COLOR_BAR[color.toUpperCase()] ?? 'bg-surface-200'
}

const CARD_TYPE_ICON: Record<string, string> = {
  REFERENCE: 'pi-paperclip',
  MEMO: 'pi-pencil',
  URL: 'pi-link',
  SECTION_HEADER: 'pi-tag',
}

function iconClass(cardType: string | null): string {
  if (!cardType) return 'pi-th-large'
  return CARD_TYPE_ICON[cardType] ?? 'pi-th-large'
}

function cardSizePixels(card: CorkboardCardDetail): { width: number; height: number } {
  const size = (card.cardSize ?? 'MEDIUM').toUpperCase()
  if (card.cardType === 'SECTION_HEADER') {
    return { width: 320, height: 40 }
  }
  if (size === 'SMALL') return { width: 150, height: 100 }
  if (size === 'LARGE') return { width: 300, height: 200 }
  return { width: 200, height: 150 }
}

/** カード本文のプレビュー用に先頭 N 文字で切る */
function previewText(card: CorkboardCardDetail): string {
  const raw =
    card.body ??
    card.contentSnapshot ??
    card.title ??
    ''
  if (raw.length <= 100) return raw
  return raw.slice(0, 100) + '…'
}

function cardTypeLabel(cardType: string | null): string {
  switch (cardType) {
    case 'REFERENCE':
      return t('corkboard.cardTypeReference')
    case 'MEMO':
      return t('corkboard.cardTypeMemo')
    case 'URL':
      return t('corkboard.cardTypeUrl')
    case 'SECTION_HEADER':
      return t('corkboard.cardTypeSectionHeader')
    default:
      return cardType ?? ''
  }
}

function ariaLabelFor(card: CorkboardCardDetail): string {
  return t('corkboard.ariaCard', {
    cardType: cardTypeLabel(card.cardType),
    title: card.title ?? card.body?.slice(0, 30) ?? '',
  })
}

// ----- スコープバッジ -----
const scopeLabel = computed<string>(() => {
  const sc = board.value?.scopeType ?? scopeParam.value
  switch (sc) {
    case 'PERSONAL':
      return t('corkboard.scopePersonal')
    case 'TEAM':
      return t('corkboard.scopeTeam')
    case 'ORGANIZATION':
      return t('corkboard.scopeOrganization')
    default:
      return ''
  }
})

const scopeBadgeClass = computed<string>(() => {
  const sc = board.value?.scopeType ?? scopeParam.value
  switch (sc) {
    case 'PERSONAL':
      return 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-200'
    case 'TEAM':
      return 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-200'
    case 'ORGANIZATION':
      return 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-200'
    default:
      return 'bg-surface-100 text-surface-600'
  }
})

// ----- 背景 -----
const boardBackgroundClass = computed<string>(() => {
  const style = (board.value?.backgroundStyle ?? 'CORK').toUpperCase()
  switch (style) {
    case 'WHITE':
      return 'bg-white dark:bg-surface-900'
    case 'DARK':
      return 'bg-surface-800 dark:bg-surface-950'
    case 'CORK':
    default:
      // コルク風: 暖色系のテクスチャ風グラデーション
      return 'bg-amber-50 dark:bg-amber-900/30 corkboard-cork-texture'
  }
})

// ----- セクション分割 -----
/**
 * Phase A バックエンド DTO の `cards` 配列にはセクション所属情報が含まれない
 * （`section_id` フィールド未提供）。Phase B ではボード上の絶対配置を元に
 * 全カードをそのまま並べる。セクション領域も絶対配置で重ねて表示する。
 *
 * Phase E でセクション編集 UI を作るタイミングで、`corkboard_card_groups`
 * 中間テーブル経由のカード所属情報を取得する仕組みも整える。
 */
const cards = computed<CorkboardCardDetail[]>(() => board.value?.cards ?? [])
const sections = computed<CorkboardGroupDetail[]>(() => board.value?.groups ?? [])

// ----- ボード描画領域サイズ -----
/** 全カード・全セクションを内包する最小サイズ（最低 1200x800） */
const boardContentSize = computed<{ width: number; height: number }>(() => {
  let maxX = 1200
  let maxY = 800
  for (const c of cards.value) {
    const { width, height } = cardSizePixels(c)
    maxX = Math.max(maxX, c.positionX + width + 40)
    maxY = Math.max(maxY, c.positionY + height + 40)
  }
  for (const s of sections.value) {
    maxX = Math.max(maxX, s.positionX + s.width + 40)
    maxY = Math.max(maxY, s.positionY + s.height + 40)
  }
  return { width: maxX, height: maxY }
})

// ----- F09.8 Phase C: カード CRUD モーダル制御 -----

/** モーダル開閉とモード制御。`null` のとき非表示。 */
const editorMode = ref<'create' | 'edit' | null>(null)
const editorTarget = ref<CorkboardCardDetail | null>(null)
const editorVisible = computed({
  get: () => editorMode.value !== null,
  set: (v: boolean) => {
    if (!v) {
      editorMode.value = null
      editorTarget.value = null
    }
  },
})
/** create 時にモーダルへ渡す初期座標（既存カードと重ならない適度な位置）。 */
const editorDefaultPosition = computed(() => {
  // 既存カードの右下に少しずらした位置をデフォルトに（重なり回避の簡易ヒューリスティック）。
  let x = 40
  let y = 40
  for (const c of cards.value) {
    if (c.positionX + 40 > x) x = c.positionX + 40
    if (c.positionY + 40 > y) y = c.positionY + 40
  }
  // 上限カット (極端な値で画面外にならないよう)
  return { x: Math.min(x, 1000), y: Math.min(y, 600) }
})

function openCreate() {
  editorTarget.value = null
  editorMode.value = 'create'
}

function openEdit(card: CorkboardCardDetail) {
  editorTarget.value = card
  editorMode.value = 'edit'
}

/** モーダル保存成功時: ボード詳細を再取得して最新化する。 */
async function onCardSaved() {
  await load()
}

/** カード削除（確認ダイアログ → 論理削除 API → ローカル状態更新）。 */
function confirmDelete(card: CorkboardCardDetail) {
  confirmAction({
    header: t('corkboard.confirm.deleteTitle'),
    message: t('corkboard.confirm.deleteMessage'),
    onAccept: () => doDelete(card),
  })
}

async function doDelete(card: CorkboardCardDetail) {
  try {
    await apiDeleteCard(boardId.value, card.id)
    if (board.value) {
      board.value = {
        ...board.value,
        cards: board.value.cards.filter((c) => c.id !== card.id),
      }
    }
    toast.add({
      severity: 'success',
      summary: t('corkboard.toast.deleteSuccess'),
      life: 2500,
    })
  } catch (e) {
    captureQuiet(e, { context: 'CorkboardDetailPage: カード削除失敗' })
    toast.add({
      severity: 'error',
      summary: t('corkboard.toast.deleteError'),
      life: 3500,
    })
  }
}

/**
 * F09.8.1 追補: ボード詳細ページからカード単位のピン止めボタンを使う。
 *
 * - ピン操作は個人ボード (`scopeType === 'PERSONAL'`) かつ
 *   `ownerId === currentUserId` のときのみ表示する。
 * - PATCH レスポンスは `{ id, isPinned, pinnedAt }` のみで、
 *   既存カードの他フィールドはローカル state を維持しつつマージする。
 * - 上限到達 (409 `CORKBOARD_013`) の場合は専用 toast を表示。
 */
const canPin = computed<boolean>(() => {
  if (!board.value) return false
  if (board.value.scopeType !== 'PERSONAL') return false
  const me = authStore.currentUser?.id
  return me != null && board.value.ownerId === me
})

interface PinErrorPayload {
  data?: { code?: string }
  response?: { status?: number; _data?: { code?: string } }
  status?: number
  statusCode?: number
}

function isPinLimitError(e: unknown): boolean {
  if (!e || typeof e !== 'object') return false
  const err = e as PinErrorPayload
  const status = err.status ?? err.statusCode ?? err.response?.status
  const code = err.data?.code ?? err.response?._data?.code
  if (status === 409) return true
  if (code === 'CORKBOARD_013') return true
  return false
}

async function togglePin(card: CorkboardCardDetail) {
  const next = !card.isPinned
  try {
    const res = await apiTogglePinCard(boardId.value, card.id, next)
    if (board.value) {
      board.value = {
        ...board.value,
        cards: board.value.cards.map((c) =>
          c.id === card.id
            ? { ...c, isPinned: res.data.isPinned, pinnedAt: res.data.pinnedAt }
            : c,
        ),
      }
    }
    toast.add({
      severity: 'success',
      summary: next
        ? t('corkboard.toast.pinSuccess')
        : t('corkboard.toast.unpinSuccess'),
      life: 2500,
    })
  } catch (e) {
    captureQuiet(e, { context: 'CorkboardDetailPage: ピン止め切替失敗' })
    if (next && isPinLimitError(e)) {
      toast.add({
        severity: 'warn',
        summary: t('corkboard.pinLimitReached'),
        life: 4000,
      })
      return
    }
    toast.add({
      severity: 'error',
      summary: next
        ? t('corkboard.toast.pinError')
        : t('corkboard.toast.unpinError'),
      life: 3500,
    })
  }
}

// ----- F09.8 Phase E: セクション CRUD + カード紐付け -----

/**
 * セクション編集権限。
 *
 * - 個人ボード (`PERSONAL`) かつ `ownerId === currentUserId` のみ true。
 * - チーム / 組織ボードは将来 Phase で BoardPermission API を経由して判定する。
 *   現状は安全側に倒し false（編集 UI 非表示）とする。
 */
const canEditSection = computed<boolean>(() => {
  if (!board.value) return false
  if (board.value.scopeType !== 'PERSONAL') return false
  const me = authStore.currentUser?.id
  return me != null && board.value.ownerId === me
})

/**
 * セクション編集モーダルの開閉状態。
 *
 * `null` のとき非表示。create / edit を 1 つの state で制御する。
 */
const sectionEditorMode = ref<'create' | 'edit' | null>(null)
const sectionEditorTarget = ref<CorkboardGroupDetail | null>(null)
const sectionEditorVisible = computed({
  get: () => sectionEditorMode.value !== null,
  set: (v: boolean) => {
    if (!v) {
      sectionEditorMode.value = null
      sectionEditorTarget.value = null
    }
  },
})

function openCreateSection() {
  sectionEditorTarget.value = null
  sectionEditorMode.value = 'create'
}

function openEditSection(section: CorkboardGroupDetail) {
  sectionEditorTarget.value = section
  sectionEditorMode.value = 'edit'
}

/** モーダル保存成功 → ボード詳細を再取得。 */
async function onSectionSaved() {
  await load()
}

/** セクション削除（確認ダイアログ → API → 再取得）。 */
function confirmDeleteSection(section: CorkboardGroupDetail) {
  confirmAction({
    header: t('corkboard.confirm.deleteSectionTitle'),
    message: t('corkboard.confirm.deleteSectionMessage'),
    onAccept: () => doDeleteSection(section),
  })
}

async function doDeleteSection(section: CorkboardGroupDetail) {
  try {
    await apiDeleteGroup(boardId.value, section.id)
    if (board.value) {
      board.value = {
        ...board.value,
        groups: board.value.groups.filter((g) => g.id !== section.id),
      }
    }
    // ローカルの紐付け map からもセクション ID を除去（残骸防止）
    const next: Record<number, number> = {}
    for (const [k, v] of Object.entries(cardSectionMap.value)) {
      if (v !== section.id) next[Number(k)] = v
    }
    cardSectionMap.value = next
    toast.add({
      severity: 'success',
      summary: t('corkboard.toast.sectionDeleteSuccess'),
      life: 2500,
    })
  } catch (e) {
    captureQuiet(e, { context: 'CorkboardDetailPage: セクション削除失敗' })
    toast.add({
      severity: 'error',
      summary: t('corkboard.toast.sectionDeleteError'),
      life: 3500,
    })
  }
}

/**
 * カード → セクションのローカル紐付けマップ。
 *
 * バックエンド DTO (`CorkboardCardResponse`) には `sectionId` が含まれないため、
 * 「このカードが今どのセクションに属しているか」をフロントで一意に決められない。
 * Phase E では本マップに「最後に行った紐付け操作の結果」を楽観的に記録し、
 * 操作メニューの表示を切り替える MVP 仕様とする。
 *
 * 今後バックエンド DTO に `sectionId` が追加されたら、`cards` 配列から直接読む形に
 * 置き換える（このマップは廃止）。
 */
const cardSectionMap = ref<Record<number, number>>({})

function getCardSectionId(card: CorkboardCardDetail): number | null {
  const v = cardSectionMap.value[card.id]
  return typeof v === 'number' ? v : null
}

/** カードをセクションに追加 / 移動する。 */
async function addCardToSection(card: CorkboardCardDetail, sectionId: number) {
  try {
    // 既に別セクションに属していた場合は先に外す（中間テーブルの一意性は MVP では仮定しない）
    const current = getCardSectionId(card)
    if (current != null && current !== sectionId) {
      try {
        await apiRemoveCardFromGroup(boardId.value, current, card.id)
      } catch (e) {
        // 旧所属の解除失敗はログのみ（追加自体は試みる）
        captureQuiet(e, {
          context: 'CorkboardDetailPage: 旧セクションからの解除失敗',
        })
      }
    }
    await apiAddCardToGroup(boardId.value, sectionId, card.id)
    cardSectionMap.value = {
      ...cardSectionMap.value,
      [card.id]: sectionId,
    }
    toast.add({
      severity: 'success',
      summary: t('corkboard.toast.cardAddToSectionSuccess'),
      life: 2500,
    })
  } catch (e) {
    captureQuiet(e, {
      context: 'CorkboardDetailPage: カードをセクションに追加失敗',
    })
    toast.add({
      severity: 'error',
      summary: t('corkboard.toast.cardSectionChangeError'),
      life: 3500,
    })
  }
}

/** カードを現在のセクションから外す。 */
async function removeCardFromSection(card: CorkboardCardDetail) {
  const sectionId = getCardSectionId(card)
  if (sectionId == null) return
  try {
    await apiRemoveCardFromGroup(boardId.value, sectionId, card.id)
    // dynamic delete を避けるため、対象キー以外で再構築する
    const { [card.id]: _removed, ...rest } = cardSectionMap.value
    void _removed
    cardSectionMap.value = rest
    toast.add({
      severity: 'success',
      summary: t('corkboard.toast.cardRemoveFromSectionSuccess'),
      life: 2500,
    })
  } catch (e) {
    captureQuiet(e, {
      context: 'CorkboardDetailPage: カードをセクションから外す失敗',
    })
    toast.add({
      severity: 'error',
      summary: t('corkboard.toast.cardSectionChangeError'),
      life: 3500,
    })
  }
}

/**
 * セクション選択ポップオーバーで現在開いているカード。
 *
 * 1 つの `<Popover>` インスタンスを使い回し、ボタン押下時に
 * `popoverTargetCard` を切り替えてから `popover.show($event)` を呼ぶ運用。
 */
const popoverTargetCard = ref<CorkboardCardDetail | null>(null)
const sectionMenuPopover = ref<{
  toggle: (e: Event) => void
  show: (e: Event) => void
  hide: () => void
} | null>(null)

function openSectionMenu(event: Event, card: CorkboardCardDetail) {
  popoverTargetCard.value = card
  // 同じカードに対する再クリックは閉じる
  sectionMenuPopover.value?.toggle(event)
}

async function chooseSection(sectionId: number) {
  const card = popoverTargetCard.value
  sectionMenuPopover.value?.hide()
  if (!card) return
  await addCardToSection(card, sectionId)
}

async function clearSection() {
  const card = popoverTargetCard.value
  sectionMenuPopover.value?.hide()
  if (!card) return
  await removeCardFromSection(card)
}

/** カードのアーカイブ状態を切り替え。 */
async function toggleArchive(card: CorkboardCardDetail) {
  const next = !card.isArchived
  try {
    const res = await apiArchiveCard(boardId.value, card.id, next)
    // ローカル状態を最新カードで置換
    if (board.value) {
      board.value = {
        ...board.value,
        cards: board.value.cards.map((c) => (c.id === card.id ? res.data : c)),
      }
    }
    toast.add({
      severity: 'success',
      summary: next
        ? t('corkboard.toast.archiveSuccess')
        : t('corkboard.toast.unarchiveSuccess'),
      life: 2500,
    })
  } catch (e) {
    captureQuiet(e, { context: 'CorkboardDetailPage: アーカイブ切替失敗' })
    toast.add({
      severity: 'error',
      summary: next
        ? t('corkboard.toast.archiveError')
        : t('corkboard.toast.unarchiveError'),
      life: 3500,
    })
  }
}
</script>

<template>
  <div class="mx-auto max-w-[1400px] px-4 pb-6" data-testid="corkboard-detail-page">
    <!-- ヘッダー -->
    <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
      <div class="flex items-center gap-3">
        <button
          type="button"
          class="inline-flex items-center gap-1 text-sm text-surface-500 hover:text-primary"
          @click="goBack"
        >
          <i class="pi pi-arrow-left text-xs" />
          {{ t('corkboard.back') }}
        </button>
        <h1 v-if="board" class="text-xl font-bold">{{ board.name }}</h1>
        <span
          v-if="scopeLabel"
          class="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium"
          :class="scopeBadgeClass"
        >
          {{ scopeLabel }}
        </span>
      </div>
      <!-- F09.8 Phase C: 新規カード作成ボタン / Phase E: 新規セクションボタン -->
      <div v-if="board" class="flex items-center gap-2">
        <Button
          v-if="canEditSection"
          :label="t('corkboard.actions.createSection')"
          icon="pi pi-folder-plus"
          size="small"
          severity="secondary"
          outlined
          :aria-label="t('corkboard.actions.createSection')"
          data-testid="corkboard-section-create-button"
          @click="openCreateSection"
        />
        <Button
          :label="t('corkboard.actions.createCard')"
          icon="pi pi-plus"
          size="small"
          severity="primary"
          :aria-label="t('corkboard.actions.createCard')"
          data-testid="corkboard-card-create-button"
          @click="openCreate"
        />
      </div>
    </div>

    <!-- サブヘッダー -->
    <div
      v-if="board"
      class="mb-3 flex flex-wrap items-center gap-3 text-xs text-surface-500"
    >
      <span class="inline-flex items-center gap-1">
        <i class="pi pi-th-large text-[10px]" />
        {{ t('corkboard.cardCount', { count: cards.length }) }}
      </span>
      <span class="opacity-50">|</span>
      <span class="inline-flex items-center gap-1">
        <i class="pi pi-folder text-[10px]" />
        {{ t('corkboard.sectionCount', { count: sections.length }) }}
      </span>
    </div>

    <!-- ローディング -->
    <PageLoading v-if="loading" size="40px" />

    <!-- エラー -->
    <div
      v-else-if="errorMessage"
      class="flex flex-col items-center gap-2 py-12 text-center"
    >
      <i class="pi pi-exclamation-triangle text-2xl text-orange-400" />
      <p class="text-sm text-surface-500">{{ errorMessage }}</p>
      <Button
        :label="t('corkboard.retry')"
        icon="pi pi-refresh"
        size="small"
        text
        @click="load"
      />
    </div>

    <!-- 空ボード（カード 0 / セクション 0） -->
    <div
      v-else-if="board && cards.length === 0 && sections.length === 0"
      class="flex flex-col items-center gap-2 rounded-xl border border-dashed border-surface-300 py-16 text-center"
      :class="boardBackgroundClass"
    >
      <i class="pi pi-th-large text-4xl text-surface-300" />
      <p class="text-sm text-surface-500">{{ t('corkboard.emptyBoard') }}</p>
    </div>

    <!-- 本体: 絶対配置のボード -->
    <div
      v-else-if="board"
      class="corkboard-canvas-wrapper relative overflow-auto rounded-xl border border-surface-200 dark:border-surface-700"
      :class="boardBackgroundClass"
    >
      <div
        class="corkboard-canvas relative"
        :style="{
          width: boardContentSize.width + 'px',
          height: boardContentSize.height + 'px',
        }"
      >
        <!-- セクション領域（カードより下のレイヤー） -->
        <div
          v-for="section in sections"
          :key="`sec-${section.id}`"
          class="corkboard-section group/sec absolute rounded-lg border-2 border-dashed border-surface-300 bg-surface-0/40 dark:bg-surface-900/30"
          :style="{
            left: section.positionX + 'px',
            top: section.positionY + 'px',
            width: section.width + 'px',
            height: isSectionCollapsed(section) ? '40px' : section.height + 'px',
            transition: 'height 0.18s ease-out',
          }"
          role="region"
          :aria-label="section.name"
          :data-testid="`corkboard-section-${section.id}`"
        >
          <div
            class="flex w-full items-center justify-between gap-2 rounded-t-lg bg-surface-0/70 px-3 py-1.5 text-left text-xs font-semibold dark:bg-surface-800/70"
          >
            <button
              type="button"
              class="flex flex-1 items-center gap-1 text-left"
              :aria-expanded="!isSectionCollapsed(section)"
              :aria-label="
                isSectionCollapsed(section)
                  ? t('corkboard.expandSection')
                  : t('corkboard.collapseSection')
              "
              @click="toggleSection(section.id)"
            >
              <i
                class="pi text-[10px]"
                :class="isSectionCollapsed(section) ? 'pi-chevron-right' : 'pi-chevron-down'"
              />
              <span class="truncate">{{ section.name }}</span>
            </button>
            <!-- F09.8 Phase E: セクション操作ボタン（ホバー / フォーカス時） -->
            <div
              v-if="canEditSection"
              class="hidden gap-0.5 group-hover/sec:flex group-focus-within/sec:flex"
              :aria-label="t('corkboard.ariaSectionActions')"
            >
              <button
                type="button"
                class="inline-flex h-5 w-5 items-center justify-center rounded text-[10px] text-surface-600 hover:bg-surface-100 hover:text-primary dark:text-surface-300 dark:hover:bg-surface-700"
                :aria-label="t('corkboard.ariaSectionEdit')"
                :title="t('corkboard.actions.editSection')"
                :data-testid="`corkboard-section-edit-button-${section.id}`"
                @click.stop="openEditSection(section)"
              >
                <i class="pi pi-pencil" aria-hidden="true" />
              </button>
              <button
                type="button"
                class="inline-flex h-5 w-5 items-center justify-center rounded text-[10px] text-surface-600 hover:bg-red-50 hover:text-red-500 dark:text-surface-300 dark:hover:bg-red-900/30"
                :aria-label="t('corkboard.ariaSectionDelete')"
                :title="t('corkboard.actions.deleteSection')"
                :data-testid="`corkboard-section-delete-button-${section.id}`"
                @click.stop="confirmDeleteSection(section)"
              >
                <i class="pi pi-trash" aria-hidden="true" />
              </button>
            </div>
          </div>
        </div>

        <!-- カード一覧 -->
        <article
          v-for="card in cards"
          :key="`card-${card.id}`"
          role="article"
          :aria-label="ariaLabelFor(card)"
          :data-testid="`corkboard-card-${card.id}`"
          class="corkboard-card group absolute flex overflow-hidden rounded-md border border-surface-200 bg-surface-0 shadow-sm dark:border-surface-700 dark:bg-surface-800"
          :class="card.isArchived ? 'opacity-60' : ''"
          :style="{
            left: card.positionX + 'px',
            top: card.positionY + 'px',
            width: cardSizePixels(card).width + 'px',
            height: cardSizePixels(card).height + 'px',
            zIndex: card.zIndex ?? 1,
          }"
          :tabindex="0"
        >
          <!-- F09.8 Phase C: カード操作メニュー（ホバー / フォーカス時に表示） -->
          <div
            class="corkboard-card-actions absolute right-0.5 top-0.5 z-10 hidden gap-0.5 rounded bg-surface-0/95 p-0.5 shadow group-hover:flex group-focus-within:flex dark:bg-surface-800/95"
            :aria-label="t('corkboard.ariaCardActions')"
          >
            <button
              type="button"
              class="inline-flex h-5 w-5 items-center justify-center rounded text-[10px] text-surface-600 hover:bg-surface-100 hover:text-primary dark:text-surface-300 dark:hover:bg-surface-700"
              :aria-label="t('corkboard.ariaCardEdit')"
              :title="t('corkboard.actions.editCard')"
              :data-testid="`corkboard-card-edit-button-${card.id}`"
              @click.stop="openEdit(card)"
            >
              <i class="pi pi-pencil" aria-hidden="true" />
            </button>
            <!-- F09.8.1 追補: 個人ボード所有者のみピン止めボタンを表示 -->
            <button
              v-if="canPin"
              type="button"
              class="inline-flex h-5 w-5 items-center justify-center rounded text-[10px] hover:bg-surface-100 dark:hover:bg-surface-700"
              :class="
                card.isPinned
                  ? 'text-amber-500'
                  : 'text-surface-600 dark:text-surface-300 hover:text-amber-500'
              "
              :aria-label="
                card.isPinned
                  ? t('corkboard.ariaCardUnpin')
                  : t('corkboard.ariaCardPin')
              "
              :aria-pressed="card.isPinned"
              :title="
                card.isPinned
                  ? t('corkboard.actions.unpinCard')
                  : t('corkboard.actions.pinCard')
              "
              :data-testid="`corkboard-card-pin-button-${card.id}`"
              @click.stop="togglePin(card)"
            >
              <i
                class="pi"
                :class="card.isPinned ? 'pi-bookmark-fill' : 'pi-bookmark'"
                aria-hidden="true"
              />
            </button>
            <!-- F09.8 Phase E: セクションへ追加 / 移動 / 解除 メニュー -->
            <button
              v-if="canEditSection && sections.length > 0"
              type="button"
              class="inline-flex h-5 w-5 items-center justify-center rounded text-[10px] text-surface-600 hover:bg-surface-100 hover:text-primary dark:text-surface-300 dark:hover:bg-surface-700"
              :aria-label="t('corkboard.ariaCardAddToSection')"
              :title="
                getCardSectionId(card) == null
                  ? t('corkboard.actions.addToSection')
                  : t('corkboard.actions.moveToSection')
              "
              :data-testid="`corkboard-card-section-button-${card.id}`"
              @click.stop="openSectionMenu($event, card)"
            >
              <i class="pi pi-folder" aria-hidden="true" />
            </button>
            <button
              type="button"
              class="inline-flex h-5 w-5 items-center justify-center rounded text-[10px] text-surface-600 hover:bg-surface-100 hover:text-amber-500 dark:text-surface-300 dark:hover:bg-surface-700"
              :aria-label="
                card.isArchived
                  ? t('corkboard.ariaCardUnarchive')
                  : t('corkboard.ariaCardArchive')
              "
              :title="
                card.isArchived
                  ? t('corkboard.actions.unarchiveCard')
                  : t('corkboard.actions.archiveCard')
              "
              :data-testid="`corkboard-card-archive-button-${card.id}`"
              @click.stop="toggleArchive(card)"
            >
              <i
                class="pi"
                :class="card.isArchived ? 'pi-undo' : 'pi-inbox'"
                aria-hidden="true"
              />
            </button>
            <button
              type="button"
              class="inline-flex h-5 w-5 items-center justify-center rounded text-[10px] text-surface-600 hover:bg-red-50 hover:text-red-500 dark:text-surface-300 dark:hover:bg-red-900/30"
              :aria-label="t('corkboard.ariaCardDelete')"
              :title="t('corkboard.actions.deleteCard')"
              :data-testid="`corkboard-card-delete-button-${card.id}`"
              @click.stop="confirmDelete(card)"
            >
              <i class="pi pi-trash" aria-hidden="true" />
            </button>
          </div>

          <!-- カラーラベル（左端） -->
          <span
            class="w-1.5 shrink-0"
            :class="colorBarClass(card.colorLabel)"
            aria-hidden="true"
          />

          <div class="flex min-w-0 flex-1 flex-col gap-1 p-2">
            <!-- ヘッダ: カード種別アイコン + タイトル + ピン -->
            <div class="flex items-start gap-1.5">
              <i
                class="pi mt-0.5 shrink-0 text-[10px] text-surface-500"
                :class="iconClass(card.cardType)"
                aria-hidden="true"
              />
              <p
                v-if="card.title"
                class="min-w-0 flex-1 truncate text-xs font-semibold text-surface-800 dark:text-surface-100"
              >
                {{ card.title }}
              </p>
              <i
                v-if="card.isPinned"
                class="pi pi-bookmark-fill text-[10px] text-amber-500"
                aria-hidden="true"
              />
            </div>

            <!-- 本文プレビュー（MEMO / URL のみ。REFERENCE は CardSnapshot 側で出す） -->
            <p
              v-if="
                card.cardType !== 'SECTION_HEADER' &&
                card.cardType !== 'REFERENCE'
              "
              class="line-clamp-3 whitespace-pre-wrap text-[11px] text-surface-600 dark:text-surface-300"
            >
              {{ previewText(card) }}
            </p>

            <!-- F09.8 Phase G: URL カード OGP プレビュー -->
            <CardOgpPreview
              v-if="card.cardType === 'URL'"
              :card="card"
              size="sm"
              class="mt-1"
              :data-testid="`card-ogp-preview-${card.id}`"
            />

            <!-- F09.8 Phase G: REFERENCE カードのスナップショット（削除済みバッジ込み） -->
            <CardSnapshot
              v-else-if="card.cardType === 'REFERENCE'"
              :card="card"
              compact
              class="mt-1"
              :data-testid="`card-snapshot-${card.id}`"
              :deleted-badge-testid="`card-snapshot-deleted-badge-${card.id}`"
            />
          </div>
        </article>
      </div>
    </div>

    <!-- F09.8 Phase C: カード作成・編集モーダル -->
    <CardEditorModal
      v-if="board && editorMode"
      v-model:visible="editorVisible"
      :mode="editorMode"
      :board-id="board.id"
      :card="editorTarget"
      :default-position="editorDefaultPosition"
      @save="onCardSaved"
    />

    <!-- F09.8 Phase E: セクション作成・編集モーダル -->
    <SectionEditorModal
      v-if="board && sectionEditorMode"
      v-model:visible="sectionEditorVisible"
      :mode="sectionEditorMode"
      :board-id="board.id"
      :section="sectionEditorTarget"
      @save="onSectionSaved"
    />

    <!-- F09.8 Phase E: カード→セクション紐付けメニュー（Popover） -->
    <Popover ref="sectionMenuPopover" data-testid="corkboard-card-section-popover">
      <div class="flex flex-col gap-1 py-1" style="min-width: 200px">
        <p class="px-3 pb-1 text-[10px] font-semibold uppercase text-surface-500">
          {{ t('corkboard.modal.sectionMenuTitle') }}
        </p>
        <button
          v-for="section in sections"
          :key="`menusec-${section.id}`"
          type="button"
          class="flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm hover:bg-surface-100 dark:hover:bg-surface-700"
          :data-testid="`corkboard-card-section-menu-item-${section.id}`"
          @click="chooseSection(section.id)"
        >
          <i
            class="pi text-[11px]"
            :class="
              popoverTargetCard && getCardSectionId(popoverTargetCard) === section.id
                ? 'pi-check text-primary'
                : 'pi-folder text-surface-500'
            "
            aria-hidden="true"
          />
          <span class="truncate">{{ section.name }}</span>
        </button>
        <div
          v-if="popoverTargetCard && getCardSectionId(popoverTargetCard) != null"
          class="my-1 border-t border-surface-200 dark:border-surface-700"
        />
        <button
          v-if="popoverTargetCard && getCardSectionId(popoverTargetCard) != null"
          type="button"
          class="flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30"
          data-testid="corkboard-card-section-menu-clear"
          @click="clearSection"
        >
          <i class="pi pi-times text-[11px]" aria-hidden="true" />
          <span>{{ t('corkboard.actions.removeFromSection') }}</span>
        </button>
      </div>
    </Popover>

    <!-- F09.8 Phase C: 削除確認ダイアログ（useConfirmDialog 経由） -->
    <ConfirmDialog />
  </div>
</template>

<style scoped>
/* CORK 背景のテクスチャ風 */
.corkboard-cork-texture {
  background-image:
    radial-gradient(circle at 20% 30%, rgba(180, 130, 80, 0.15) 0, transparent 8%),
    radial-gradient(circle at 70% 60%, rgba(180, 130, 80, 0.12) 0, transparent 7%),
    radial-gradient(circle at 40% 80%, rgba(180, 130, 80, 0.1) 0, transparent 6%);
}

.corkboard-canvas-wrapper {
  /* デスクトップは 1:1、最大高さ 80vh で内部スクロール */
  max-height: 80vh;
}

/* モバイル: ボード全体を縮小表示（縦スクロール優先） */
@media (max-width: 640px) {
  .corkboard-canvas {
    transform: scale(0.7);
    transform-origin: top left;
  }
}
</style>
