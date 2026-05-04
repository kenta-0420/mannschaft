<script setup lang="ts">
/**
 * F09.8 Phase G: REFERENCE カードのスナップショット表示コンポーネント。
 *
 * 設計書 §3 / §6 / §10 に基づき、参照カードの `contentSnapshot` 等を表示する。
 *
 * 設計判断:
 *  - バックエンド DTO `CorkboardCardResponse` は `contentSnapshot` のみを持ち、
 *    設計書の `snapshot_title` / `snapshot_excerpt` は未提供。よって本コンポーネントでは
 *    タイトルは `card.title`、抜粋は `card.contentSnapshot` を優先表示する。
 *    `PinnedCardItem` 形式（reference.snapshotTitle / reference.snapshotExcerpt 持ち）
 *    にも対応するため、props 抽出ロジックで両形式から拾う。
 *  - `isRefDeleted = true` の場合: 「参照元削除済み」バッジを目立つ赤色で表示し、
 *    snapshot のタイトル / 抜粋を保持して表示（元コンテンツが消えても情報維持）。
 *  - `ref_updated_at` と現在の元 updated_at を比較する仕組みは、
 *    バックエンドが現状そのフラグを返さない（コンテキスト API も無い）ため、
 *    このコンポーネントでは比較済みフラグを props 経由で受けるオプショナル設計とする
 *    (`isRefStale`)。Phase G 時点ではフラグを設定する API が存在しないので、
 *    親側からは渡されない＝バッジは出ない、が標準動作。
 *
 * 使い方:
 *   <CardSnapshot :card="card" />
 *   <CardSnapshot :card="pinnedCardItem" :is-ref-stale="someBoolean" />
 */
import type { CorkboardCardDetail } from '~/types/corkboard'
import type { PinnedCardItem } from '~/types/pinnedCard'

interface SnapshotFields {
  /** 参照スナップショットのタイトル（PinnedCardItem.reference.snapshotTitle 由来） */
  snapshotTitle?: string | null
  /** 参照スナップショットの本文抜粋 */
  snapshotExcerpt?: string | null
  /** 参照元削除済みフラグ */
  isRefDeleted?: boolean | null
  /** カード自身が持つタイトル（snapshot 不在時のフォールバック） */
  title?: string | null
}

interface Props {
  /** 参照カード本体、または PinnedCardItem。 */
  card: CorkboardCardDetail | PinnedCardItem | SnapshotFields
  /**
   * 元の内容が更新されているか（オプショナル）。
   * バックエンドが比較フラグを返す API を提供できれば渡す。
   */
  isRefStale?: boolean
  /** コンパクト表示（ボード詳細カード内向け） */
  compact?: boolean
  /**
   * 削除済みバッジに付与するテスト用 data-testid。
   * E2E 用に親側 (`pages/corkboard/[id].vue`) から
   * `card-snapshot-deleted-badge-{cardId}` を渡す。
   */
  deletedBadgeTestid?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  isRefStale: false,
  compact: true,
  deletedBadgeTestid: null,
})

const { t } = useI18n()

/**
 * `card` から snapshot 情報を抽出する。
 * - `CorkboardCardDetail`: `contentSnapshot` / `title` / `isRefDeleted` を持つ。
 * - `PinnedCardItem`: `reference.snapshotTitle` / `reference.snapshotExcerpt` /
 *   `reference.isDeleted` を持つ。
 */
const snapshot = computed<{
  title: string | null
  excerpt: string | null
  isDeleted: boolean
}>(() => {
  const c = props.card as Record<string, unknown>
  const ref = c.reference as Record<string, unknown> | null | undefined

  // PinnedCardItem 形式優先
  if (ref) {
    return {
      title:
        (ref.snapshotTitle as string | null | undefined) ??
        (c.title as string | null | undefined) ??
        null,
      excerpt: (ref.snapshotExcerpt as string | null | undefined) ?? null,
      isDeleted: Boolean(ref.isDeleted),
    }
  }

  // CorkboardCardDetail 形式
  return {
    title:
      (c.snapshotTitle as string | null | undefined) ??
      (c.title as string | null | undefined) ??
      null,
    excerpt:
      (c.snapshotExcerpt as string | null | undefined) ??
      (c.contentSnapshot as string | null | undefined) ??
      null,
    isDeleted: Boolean(c.isRefDeleted),
  }
})

const previewExcerpt = computed<string | null>(() => {
  const raw = snapshot.value.excerpt
  if (!raw) return null
  // ボード詳細では本文プレビューを別表示しているケースもあるが、
  // CardSnapshot 単体で使われた時のために 200 文字で切る。
  if (raw.length <= 200) return raw
  return raw.slice(0, 200) + '…'
})

const hasContent = computed<boolean>(
  () => Boolean(snapshot.value.title || snapshot.value.excerpt),
)
</script>

<template>
  <div class="corkboard-card-snapshot flex flex-col gap-1">
    <!-- 参照元削除済みバッジ（最優先） -->
    <span
      v-if="snapshot.isDeleted"
      class="inline-flex items-center gap-1 self-start rounded bg-red-100 px-1.5 py-0.5 text-[10px] font-medium text-red-700 dark:bg-red-900/40 dark:text-red-200"
      :aria-label="t('corkboard.snapshot.refDeletedAria')"
      :data-testid="props.deletedBadgeTestid ?? undefined"
    >
      <i class="pi pi-trash text-[9px]" aria-hidden="true" />
      {{ t('corkboard.snapshot.refDeleted') }}
    </span>

    <!-- 参照元の更新検知バッジ（バックエンドから比較フラグが渡された時のみ） -->
    <span
      v-if="!snapshot.isDeleted && props.isRefStale"
      class="inline-flex items-center gap-1 self-start rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-medium text-amber-800 dark:bg-amber-900/40 dark:text-amber-200"
      :aria-label="t('corkboard.snapshot.refUpdatedAria')"
    >
      <i class="pi pi-refresh text-[9px]" aria-hidden="true" />
      {{ t('corkboard.snapshot.refUpdated') }}
    </span>

    <!-- snapshot タイトル -->
    <p
      v-if="snapshot.title"
      class="truncate font-medium text-surface-800 dark:text-surface-100"
      :class="props.compact ? 'text-xs' : 'text-sm'"
    >
      {{ snapshot.title }}
    </p>

    <!-- snapshot 抜粋 -->
    <p
      v-if="previewExcerpt"
      class="whitespace-pre-wrap text-surface-600 dark:text-surface-300"
      :class="props.compact ? 'line-clamp-3 text-[11px]' : 'line-clamp-5 text-xs'"
    >
      {{ previewExcerpt }}
    </p>

    <!-- snapshot が空（タイトルも本文も無い）の場合の説明 -->
    <p
      v-if="!hasContent"
      class="text-[11px] italic text-surface-400"
    >
      {{ t('corkboard.snapshot.noContent') }}
    </p>
  </div>
</template>
