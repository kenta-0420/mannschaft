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
 * Phase B 範囲外（後続 Phase で実装）:
 *  - カード CRUD UI (Phase C)
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
const { getBoardDetail, getBoardDetailByBoardId } = useCorkboardApi()
const { captureQuiet } = useErrorReport()

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
</script>

<template>
  <div class="mx-auto max-w-[1400px] px-4 pb-6">
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
          class="absolute rounded-lg border-2 border-dashed border-surface-300 bg-surface-0/40 dark:bg-surface-900/30"
          :style="{
            left: section.positionX + 'px',
            top: section.positionY + 'px',
            width: section.width + 'px',
            height: isSectionCollapsed(section) ? '40px' : section.height + 'px',
            transition: 'height 0.18s ease-out',
          }"
          role="region"
          :aria-label="section.name"
        >
          <button
            type="button"
            class="flex w-full items-center justify-between gap-2 rounded-t-lg bg-surface-0/70 px-3 py-1.5 text-left text-xs font-semibold dark:bg-surface-800/70"
            :aria-expanded="!isSectionCollapsed(section)"
            :aria-label="
              isSectionCollapsed(section)
                ? t('corkboard.expandSection')
                : t('corkboard.collapseSection')
            "
            @click="toggleSection(section.id)"
          >
            <span class="inline-flex items-center gap-1">
              <i
                class="pi text-[10px]"
                :class="isSectionCollapsed(section) ? 'pi-chevron-right' : 'pi-chevron-down'"
              />
              {{ section.name }}
            </span>
          </button>
        </div>

        <!-- カード一覧 -->
        <article
          v-for="card in cards"
          :key="`card-${card.id}`"
          role="article"
          :aria-label="ariaLabelFor(card)"
          class="absolute flex overflow-hidden rounded-md border border-surface-200 bg-surface-0 shadow-sm dark:border-surface-700 dark:bg-surface-800"
          :style="{
            left: card.positionX + 'px',
            top: card.positionY + 'px',
            width: cardSizePixels(card).width + 'px',
            height: cardSizePixels(card).height + 'px',
            zIndex: card.zIndex ?? 1,
          }"
          :tabindex="0"
        >
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

            <!-- 本文プレビュー -->
            <p
              v-if="card.cardType !== 'SECTION_HEADER'"
              class="line-clamp-3 whitespace-pre-wrap text-[11px] text-surface-600 dark:text-surface-300"
            >
              {{ previewText(card) }}
            </p>

            <!-- URL カード OGP サムネイル（小サイズで表示） -->
            <img
              v-if="card.cardType === 'URL' && card.ogImageUrl"
              :src="card.ogImageUrl"
              :alt="t('corkboard.ogImageAlt')"
              class="mt-1 h-10 w-full rounded object-cover"
              loading="lazy"
            />

            <!-- 参照元削除済みバッジ -->
            <span
              v-if="card.cardType === 'REFERENCE' && card.isRefDeleted"
              class="mt-auto inline-flex items-center gap-1 self-start rounded bg-red-100 px-1.5 py-0.5 text-[10px] text-red-700 dark:bg-red-900/40 dark:text-red-200"
            >
              <i class="pi pi-trash text-[9px]" />
              {{ t('corkboard.refDeletedBadge') }}
            </span>
          </div>
        </article>
      </div>
    </div>
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
