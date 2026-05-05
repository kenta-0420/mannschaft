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
 * Phase D 追加:
 *  - カード描画を `DraggableCard` コンポーネントへ切り出し
 *  - `useDraggable` による D&D 位置移動 + `batch-position` API 連動
 *  - ピン止めカード / 編集権限のないボードでは D&D 不可
 *
 * Phase B 範囲外（後続 Phase で実装）:
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
 *
 * --- リファクタリング履歴 ---
 * Phase 4-α/β/γ/δ: 各 Composable へのロジック分離
 *  - useCorkboardDetail: ボード取得・ルートパラメータ・ナビ・権限・バッジ
 *  - useCorkboardCardManagement: カード CRUD モーダル・削除・アーカイブ
 *  - useCorkboardDragDrop: D&D 位置更新・ボード描画領域サイズ
 *  - useCorkboardSectionManagement: セクション CRUD・折りたたみ・カード紐付け
 *  - useCorkboardPinManagement: ピン止め・付箋メモ
 *  - useCorkboardWebSocketSync: WebSocket 購読管理・eventType 別局所更新
 */
import type { CorkboardCardDetail, CorkboardGroupDetail } from '~/types/corkboard'
import { useCorkboardDetail } from '~/composables/useCorkboardDetail'
import { useCorkboardCardManagement } from '~/composables/useCorkboardCardManagement'
import { useCorkboardDragDrop } from '~/composables/useCorkboardDragDrop'
import { useCorkboardSectionManagement } from '~/composables/useCorkboardSectionManagement'
import { useCorkboardPinManagement } from '~/composables/useCorkboardPinManagement'
import { useCorkboardWebSocketSync } from '~/composables/useCorkboardWebSocketSync'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const authStore = useAuthStore()

// ----- ボード詳細・基本情報 -----
const {
  board,
  loading,
  errorMessage,
  boardId,
  scope: scopeParam,
  scopeId: scopeIdParam,
  load,
  goBack,
  canEdit,
  canPin,
  scopeLabel,
  scopeBadgeClass,
  boardBackgroundClass,
} = useCorkboardDetail(t)

onMounted(load)
// boardId / scope / scopeId のいずれかが変わったときに再取得する（後方互換 URL 対応）
watch([boardId, scopeParam, scopeIdParam], load)

// ----- セクション分割 computed -----
const cards = computed<CorkboardCardDetail[]>(() => board.value?.cards ?? [])
const sections = computed<CorkboardGroupDetail[]>(() => board.value?.groups ?? [])

// ----- セクション編集権限 -----
/**
 * セクション編集権限。
 * 個人ボード (`PERSONAL`) かつ `ownerId === currentUserId` のみ true。
 * チーム / 組織ボードは将来 Phase で BoardPermission API を経由して判定する。
 */
const canEditSection = computed<boolean>(() => {
  if (!board.value) return false
  if (board.value.scopeType !== 'PERSONAL') return false
  const me = authStore.currentUser?.id
  return me != null && board.value.ownerId === me
})

// ----- カード CRUD・アーカイブ管理 -----
const {
  editorMode,
  editorTarget,
  editorVisible,
  editorDefaultPosition,
  openCreate,
  openEdit,
  confirmDelete,
  toggleArchive,
} = useCorkboardCardManagement(board, boardId, t)

/** モーダル保存成功時: ボード詳細を再取得して最新化する。 */
async function onCardSaved() {
  await load()
}

// ----- D&D 位置更新・ボード描画領域 -----
const {
  boardContentSize,
  onPositionChange,
} = useCorkboardDragDrop(board, boardId, t)

// ----- セクション管理 -----
const {
  loadCollapsedState,
  toggleSection,
  isSectionCollapsed,
  sectionEditorMode,
  sectionEditorTarget,
  sectionEditorVisible,
  openCreateSection,
  openEditSection,
  confirmDeleteSection,
  popoverTargetCard,
  getCardSectionId,
  addCardToSection,
  removeCardFromSection,
} = useCorkboardSectionManagement(board, boardId, t)

// ボードロード後に localStorage の折りたたみ状態を復元する
watch(board, (newBoard) => {
  if (newBoard) loadCollapsedState()
})

/** モーダル保存成功 → ボード詳細を再取得。 */
async function onSectionSaved() {
  await load()
}

/**
 * セクション選択ポップオーバーの ref。
 * 1 つの `<Popover>` インスタンスを使い回す。
 */
const sectionMenuPopover = ref<{
  toggle: (e: Event) => void
  show: (e: Event) => void
  hide: () => void
} | null>(null)

function openSectionMenu(event: Event, card: CorkboardCardDetail) {
  popoverTargetCard.value = card
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

// ----- ピン止め・付箋メモ管理 -----
const {
  pinPopoverVisible,
  pinPopoverTargetCard,
  togglePin,
  onPinNoteConfirm,
} = useCorkboardPinManagement(board, boardId, t)

// ----- WebSocket リアルタイム同期 -----
const { setReloadFn } = useCorkboardWebSocketSync(board, boardId, t)

// フルリロード関数を注入（eventType ごとのフォールバック用）
setReloadFn(() => { void load() })
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
          v-if="canEdit"
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

        <!-- F09.8 Phase D + Phase E: カード一覧（DraggableCard で D&D + セクション紐付け） -->
        <DraggableCard
          v-for="card in cards"
          :key="`card-${card.id}`"
          :card="card"
          :board-id="boardId"
          :can-edit="canEdit"
          :can-pin="canPin"
          :can-edit-section="canEditSection"
          :current-section-id="getCardSectionId(card)"
          :available-sections="sections"
          @update:position="onPositionChange"
          @edit="openEdit"
          @delete="confirmDelete"
          @archive="toggleArchive"
          @pin="togglePin"
          @section-menu-open="openSectionMenu"
        />
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

    <!-- F09.8 件3' (V9.098): ピン止め時付箋メモエディタ -->
    <PinNoteEditorPopover
      v-if="pinPopoverTargetCard"
      v-model:visible="pinPopoverVisible"
      :default-color="pinPopoverTargetCard.colorLabel"
      :initial-user-note="pinPopoverTargetCard.userNote"
      :testid-suffix="pinPopoverTargetCard.id"
      @confirm="onPinNoteConfirm"
      @cancel="pinPopoverTargetCard = null"
    />

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
