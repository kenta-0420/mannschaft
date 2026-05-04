<script setup lang="ts">
/**
 * F09.8 Phase D: コルクボードのカードを D&D 可能にするコンポーネント。
 *
 * 設計書 §4 / FRONTEND_CODING_CONVENTION.md §9 に準拠した実装。
 *
 * 実装方針:
 *  - `@vueuse/core` の `useDraggable` でポインタ操作を捕捉。
 *  - ドラッグ中は CSS の `left/top` を内部 ref から反映してリアルタイム移動
 *    （API は呼ばない）。
 *  - `pointerup` 相当のタイミング（`isDragging` の true → false 変化）で
 *    親へ `update:position` を発火し、親が `batch-position` API を呼ぶ。
 *  - `card.isPinned === true` または `canEdit === false` のときは
 *    `useDraggable` を初期化せず、視覚的にロック表示する（カーソル変化なし）。
 *
 * カード本体の見た目（カラーバー / アイコン / プレビュー / OGP / Snapshot 等）は
 * 既存の `pages/corkboard/[id].vue` から忠実に切り出している。
 *
 * 親コンポーネント側の責務:
 *  - 操作系（編集 / 削除 / アーカイブ / ピン止め）は emit を受けて処理する。
 *  - `update:position` を受けたら `useCorkboardApi.batchUpdateCardPositions()` を呼び、
 *    失敗時はローカル state を元に戻す。
 */
import { useDraggable } from '@vueuse/core'
import type { CorkboardCardDetail, CorkboardColor } from '~/types/corkboard'

interface Props {
  card: CorkboardCardDetail
  /** 親ボード ID（テスト ID 生成や API 呼び出しに利用される想定で props として受け取る） */
  boardId: number
  /** ボード編集権限（共有ボードでは edit_policy + ロール判定の結果を親が渡す） */
  canEdit: boolean
  /** ピン止め操作可否（個人ボード所有者のみ true） */
  canPin: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  /** ドラッグ完了時。親は API を呼んで永続化する。 */
  (e: 'update:position', cardId: number, positionX: number, positionY: number): void
  /** カード操作メニューからの emit。親が既存処理を実行する。 */
  (e: 'edit', card: CorkboardCardDetail): void
  (e: 'delete', card: CorkboardCardDetail): void
  (e: 'archive', card: CorkboardCardDetail): void
  (e: 'pin', card: CorkboardCardDetail): void
}>()

const { t } = useI18n()

// ----- D&D 制御 -----
/**
 * ドラッグ可否。
 * - ピン止めカードは移動不可（設計書 §4 batch-position の `is_pinned=true` 無視仕様）
 * - 編集権限が無いユーザー（共有ボードで edit_policy=ADMIN_ONLY + 非 ADMIN 等）も移動不可
 */
const isDraggable = computed<boolean>(
  () => !props.card.isPinned && props.canEdit,
)

/** ドラッグ操作にバインドする要素 ref */
const cardEl = ref<HTMLElement | null>(null)

/**
 * useDraggable の戻り値:
 *   - x, y: ポインタ位置をボード基準（initialValue 起点）の絶対座標で返す
 *   - isDragging: ドラッグ中フラグ
 *
 * `disabled` が `true` の間はイベントハンドラが発火せず、x/y は initialValue で固定される。
 */
const { x, y, isDragging } = useDraggable(cardEl, {
  initialValue: { x: props.card.positionX, y: props.card.positionY },
  preventDefault: true,
  disabled: computed(() => !isDraggable.value),
  // ドラッグ範囲の参照要素は親（ボードキャンバス）。
  // 渡さなくても document 全体にイベントが飛ぶが、CSS 配置上は親キャンバス内で動かす想定。
})

/**
 * カードの表示位置。
 * - ドラッグ中は useDraggable の x/y をそのまま反映（リアルタイム追従）。
 * - ドラッグしていないときは props.card.positionX/Y を表示。
 *   （親が batch-position 成功後に props を更新すれば自然に反映される）
 */
const displayX = computed<number>(() =>
  isDragging.value ? x.value : props.card.positionX,
)
const displayY = computed<number>(() =>
  isDragging.value ? y.value : props.card.positionY,
)

/**
 * ドラッグ終了で親へ通知。
 * 過剰イベント抑止のため、座標が変化していない場合は emit しない。
 */
watch(isDragging, (dragging, wasDragging) => {
  if (wasDragging && !dragging) {
    const nx = Math.max(0, Math.round(x.value))
    const ny = Math.max(0, Math.round(y.value))
    if (nx === props.card.positionX && ny === props.card.positionY) {
      return
    }
    emit('update:position', props.card.id, nx, ny)
  }
})

// props.card.positionX/Y が外部から変わった場合（API 失敗時のロールバック等）、
// useDraggable 内部の x/y を追従させる。
watch(
  () => [props.card.positionX, props.card.positionY] as const,
  ([nx, ny]) => {
    if (!isDragging.value) {
      x.value = nx
      y.value = ny
    }
  },
)

// ----- 表示ヘルパ（[id].vue から切り出し） -----
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

const cardSize = computed<{ width: number; height: number }>(() => {
  const size = (props.card.cardSize ?? 'MEDIUM').toUpperCase()
  if (props.card.cardType === 'SECTION_HEADER') {
    return { width: 320, height: 40 }
  }
  if (size === 'SMALL') return { width: 150, height: 100 }
  if (size === 'LARGE') return { width: 300, height: 200 }
  return { width: 200, height: 150 }
})

function previewText(card: CorkboardCardDetail): string {
  const raw = card.body ?? card.contentSnapshot ?? card.title ?? ''
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

/**
 * aria-label にロック理由を含める（スクリーンリーダ向け）。
 * 通常: 「メモ: タイトル」
 * ピン止め時: 「メモ: タイトル, ピン止め中（移動不可）」
 * 編集権限なし: 「メモ: タイトル, 編集権限なし（移動不可）」
 */
const ariaLabel = computed<string>(() => {
  const base = t('corkboard.ariaCard', {
    cardType: cardTypeLabel(props.card.cardType),
    title: props.card.title ?? props.card.body?.slice(0, 30) ?? '',
  })
  if (props.card.isPinned) {
    return `${base}, ${t('corkboard.dnd.pinned')}`
  }
  if (!props.canEdit) {
    return `${base}, ${t('corkboard.dnd.locked')}`
  }
  return base
})
</script>

<template>
  <article
    ref="cardEl"
    role="article"
    :aria-label="ariaLabel"
    :data-testid="`corkboard-card-${card.id}`"
    :data-draggable="isDraggable ? 'true' : 'false'"
    :data-dragging="isDragging ? 'true' : 'false'"
    class="corkboard-card group absolute flex overflow-hidden rounded-md border border-surface-200 bg-surface-0 shadow-sm dark:border-surface-700 dark:bg-surface-800"
    :class="[
      card.isArchived ? 'opacity-60' : '',
      isDraggable ? 'cursor-move' : 'cursor-default',
      isDragging ? 'select-none shadow-lg ring-2 ring-primary/40' : '',
    ]"
    :style="{
      left: displayX + 'px',
      top: displayY + 'px',
      width: cardSize.width + 'px',
      height: cardSize.height + 'px',
      zIndex: isDragging ? 1000 : (card.zIndex ?? 1),
      touchAction: isDraggable ? 'none' : 'auto',
    }"
    :tabindex="0"
  >
    <!-- F09.8 Phase C: カード操作メニュー（ホバー / フォーカス時に表示）-->
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
        @pointerdown.stop
        @click.stop="emit('edit', card)"
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
          card.isPinned ? t('corkboard.ariaCardUnpin') : t('corkboard.ariaCardPin')
        "
        :aria-pressed="card.isPinned"
        :title="card.isPinned ? t('corkboard.actions.unpinCard') : t('corkboard.actions.pinCard')"
        :data-testid="`corkboard-card-pin-button-${card.id}`"
        @pointerdown.stop
        @click.stop="emit('pin', card)"
      >
        <i
          class="pi"
          :class="card.isPinned ? 'pi-bookmark-fill' : 'pi-bookmark'"
          aria-hidden="true"
        />
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
        @pointerdown.stop
        @click.stop="emit('archive', card)"
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
        @pointerdown.stop
        @click.stop="emit('delete', card)"
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
      <!-- ヘッダ: カード種別アイコン + タイトル + ピンマーク + ロックアイコン -->
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
        <!-- ピン止めマーク（移動不可表示も兼ねる） -->
        <i
          v-if="card.isPinned"
          class="pi pi-bookmark-fill text-[10px] text-amber-500"
          :title="t('corkboard.dnd.pinned')"
          aria-hidden="true"
          :data-testid="`corkboard-card-lock-icon-${card.id}`"
        />
        <!-- 編集権限なしのロック表示（ピン止めではないが移動不可な場合） -->
        <i
          v-else-if="!canEdit"
          class="pi pi-lock text-[10px] text-surface-400"
          :title="t('corkboard.dnd.locked')"
          aria-hidden="true"
          :data-testid="`corkboard-card-lock-icon-${card.id}`"
        />
      </div>

      <!-- 本文プレビュー（MEMO のみ。REFERENCE / URL は専用コンポーネントで描画） -->
      <p
        v-if="card.cardType !== 'SECTION_HEADER' && card.cardType !== 'REFERENCE' && card.cardType !== 'URL'"
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
</template>
