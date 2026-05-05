/**
 * F09.8 corkboard-detail: コルクボード詳細ページの中核ロジックを抽出した Composable。
 *
 * 以下のブロックを `[id].vue` から切り出し、単体テスト可能な形に整理する:
 *  - ルートパラメータ解析（boardId / scopeParam / scopeIdParam）
 *  - ボード詳細取得（load 関数、scope 別 API 振り分け）
 *  - ナビゲーション（goBack 関数）
 *  - 権限判定（canEdit / canPin の computed）
 *  - スコープバッジ表示（scopeLabel / scopeBadgeClass）
 *  - 背景スタイル（boardBackgroundClass）
 *
 * Phase 別担当:
 *  - WebSocket リアルタイム同期（Phase F / 別足軽）は含まない
 *  - セクション管理・カード CRUD・ピン止めロジック（別足軽）は含まない
 */
import type { CorkboardDetail, CorkboardScope } from '~/types/corkboard'

export function useCorkboardDetail() {
  const route = useRoute()
  const router = useRouter()
  const { t } = useI18n()
  const { getBoardDetail, getBoardDetailByBoardId } = useCorkboardApi()
  const { captureQuiet } = useErrorReport()
  const authStore = useAuthStore()

  // ----- ルートパラメータ -----

  /** URL の `[id]` セグメントを数値に変換する */
  const boardId = computed<number>(() => {
    const raw = route.params.id
    const v = Number(Array.isArray(raw) ? raw[0] : raw)
    return Number.isFinite(v) ? v : 0
  })

  /**
   * `?scope=` クエリを大文字に正規化して返す。
   * 不正値（PERSONAL / TEAM / ORGANIZATION 以外）は `null` を返す。
   */
  const scopeParam = computed<CorkboardScope | null>(() => {
    const raw = String(route.query.scope ?? '').toUpperCase()
    if (raw === 'PERSONAL' || raw === 'TEAM' || raw === 'ORGANIZATION') {
      return raw
    }
    return null
  })

  /** `?scopeId=` クエリを数値に変換する。未設定または不正値は `null`。 */
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

  // ----- データ取得 -----

  /**
   * ボード詳細を取得する。
   *
   * - scope クエリあり → scope 別パス（後方互換）
   * - scope クエリなし → scope-agnostic API（`GET /api/v1/corkboards/{boardId}`）
   *
   * PERSONAL 以外の scope は scopeId 必須。
   */
  async function load() {
    loading.value = true
    errorMessage.value = null

    if (!boardId.value) {
      errorMessage.value = t('corkboard.boardNotFound')
      loading.value = false
      return
    }

    if (scopeParam.value && scopeParam.value !== 'PERSONAL' && scopeIdParam.value == null) {
      errorMessage.value = t('corkboard.scopeMissing')
      loading.value = false
      return
    }

    try {
      const res = scopeParam.value
        ? await getBoardDetail(scopeParam.value, scopeIdParam.value, boardId.value)
        : await getBoardDetailByBoardId(boardId.value)
      board.value = res.data
    } catch (e) {
      captureQuiet(e, { context: 'useCorkboardDetail: ボード取得失敗' })
      errorMessage.value = t('corkboard.boardLoadError')
      board.value = null
    } finally {
      loading.value = false
    }
  }

  // ----- ナビゲーション -----

  /**
   * 前のページへ戻る。
   * ヒストリーがあれば `router.back()`、なければコルクボード一覧へ。
   */
  function goBack() {
    if (window.history.length > 1) {
      router.back()
    } else {
      router.push('/corkboard')
    }
  }

  // ----- 権限判定 -----

  /**
   * ボードの編集権限。
   * バックエンドの `CorkboardPermissionService#canEdit` と同じロジックで算出された
   * `viewerCanEdit` フラグを DTO から直接参照する。
   */
  const canEdit = computed<boolean>(() => board.value?.viewerCanEdit ?? false)

  /**
   * ピン止め操作の可否。
   * 個人ボード (`PERSONAL`) かつ `ownerId === currentUserId` のときのみ true。
   */
  const canPin = computed<boolean>(() => {
    if (!board.value) return false
    if (board.value.scopeType !== 'PERSONAL') return false
    const me = authStore.currentUser?.id
    return me != null && board.value.ownerId === me
  })

  // ----- スコープバッジ -----

  /**
   * スコープのラベル文字列（ i18n キー経由）。
   * board が未取得の場合は scopeParam クエリにフォールバック。
   */
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

  /**
   * スコープバッジの Tailwind クラス文字列。
   * board が未取得の場合は scopeParam クエリにフォールバック。
   */
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

  // ----- 背景スタイル -----

  /**
   * ボードの背景に対応する Tailwind クラス文字列。
   * board が null の場合は CORK（デフォルト）にフォールバック。
   */
  const boardBackgroundClass = computed<string>(() => {
    const style = (board.value?.backgroundStyle ?? 'CORK').toUpperCase()
    switch (style) {
      case 'WHITE':
        return 'bg-white dark:bg-surface-900'
      case 'DARK':
        return 'bg-surface-800 dark:bg-surface-950'
      case 'CORK':
      default:
        return 'bg-amber-50 dark:bg-amber-900/30 corkboard-cork-texture'
    }
  })

  return {
    // 状態
    board,
    loading,
    errorMessage,
    // ルートパラメータ
    boardId,
    scope: scopeParam,
    scopeId: scopeIdParam,
    // 操作
    load,
    goBack,
    // 権限
    canEdit,
    canPin,
    // 表示ヘルパ
    scopeLabel,
    scopeBadgeClass,
    boardBackgroundClass,
  }
}
