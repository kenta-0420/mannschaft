<script setup lang="ts">
/**
 * F09.8 Phase G: URL カードの OGP プレビュー表示コンポーネント。
 *
 * 設計書 §3 / §6 に基づき、URL カード作成時に Phase A バックエンドが
 * 非同期取得した `ogTitle` / `ogImageUrl` / `ogDescription` を表示する。
 *
 * 設計判断:
 *  - DTO のフィールド名 (camelCase: ogTitle / ogImageUrl) を正、
 *    snake_case (og_title / og_image_url) も後方互換で受ける。
 *  - 画像は最大 80x80 のサムネイル、タイトルとサイドバイサイドで配置。
 *  - 画像読み込みに失敗した場合はテキストのみフォールバック表示。
 *  - 画像 / OGP 情報の有無に応じてレイアウトが破綻しないようにする。
 *  - 親側 (`pages/corkboard/[id].vue` / `WidgetMyCorkboard.vue` /
 *    `pages/my/corkboard.vue`) で URL カードに対してのみマウントする
 *    前提なので、`cardType === 'URL'` のチェックは行わない。
 *
 * 使い方:
 *   <CardOgpPreview :card="card" size="sm" />
 *
 * size:
 *   - sm: ボード詳細・ウィジェット用（h-10 / 横長）
 *   - md: 専用ページ用（h-16 / 通常）
 */
import type { CorkboardCardDetail } from '~/types/corkboard'
import type { PinnedCardItem } from '~/types/pinnedCard'

interface OgpFields {
  ogTitle?: string | null
  ogImageUrl?: string | null
  ogDescription?: string | null
  url?: string | null
}

interface Props {
  /**
   * OGP 情報を含むカード本体、またはピン止めウィジェット用 PinnedCardItem。
   * URL カード以外の混入を許容する設計だが、表示は OGP 情報がある時のみ。
   */
  card: CorkboardCardDetail | PinnedCardItem | OgpFields
  /** サイズプリセット */
  size?: 'sm' | 'md'
}

const props = withDefaults(defineProps<Props>(), {
  size: 'sm',
})

const { t } = useI18n()

/**
 * `card` から OGP 情報を抽出する。
 * - `CorkboardCardDetail` は `ogTitle` / `ogImageUrl` / `ogDescription` / `url` を持つ。
 * - `PinnedCardItem` は `reference.ogTitle` / `reference.ogImageUrl` / `reference.url` を持つ。
 *   `ogDescription` は持たない。
 */
const ogp = computed<OgpFields>(() => {
  const c = props.card as Record<string, unknown>
  // PinnedCardItem 形式（reference ネスト）
  const ref = c.reference as Record<string, unknown> | null | undefined
  if (ref && (ref.ogTitle != null || ref.ogImageUrl != null || ref.url != null)) {
    return {
      ogTitle: (ref.ogTitle as string | null | undefined) ?? null,
      ogImageUrl: (ref.ogImageUrl as string | null | undefined) ?? null,
      ogDescription: null,
      url: (ref.url as string | null | undefined) ?? null,
    }
  }
  // CorkboardCardDetail 形式
  return {
    ogTitle: (c.ogTitle as string | null | undefined) ?? null,
    ogImageUrl: (c.ogImageUrl as string | null | undefined) ?? null,
    ogDescription: (c.ogDescription as string | null | undefined) ?? null,
    url: (c.url as string | null | undefined) ?? null,
  }
})

const hasAny = computed<boolean>(
  () => Boolean(ogp.value.ogTitle || ogp.value.ogImageUrl || ogp.value.ogDescription),
)

/** 画像読み込み失敗時の状態保持 */
const imageFailed = ref(false)

watch(
  () => ogp.value.ogImageUrl,
  () => {
    imageFailed.value = false
  },
)

const showImage = computed<boolean>(() => Boolean(ogp.value.ogImageUrl) && !imageFailed.value)

const imageAlt = computed<string>(() => {
  if (ogp.value.ogTitle) {
    return t('corkboard.ogp.imageAlt', { title: ogp.value.ogTitle })
  }
  return t('corkboard.ogp.imageAltFallback')
})

const sizeClasses = computed<{ root: string; image: string; title: string }>(() => {
  if (props.size === 'md') {
    return {
      root: 'gap-3',
      image: 'h-16 w-16',
      title: 'text-sm',
    }
  }
  return {
    root: 'gap-2',
    image: 'h-10 w-10',
    title: 'text-xs',
  }
})

function onImageError() {
  imageFailed.value = true
}
</script>

<template>
  <div
    v-if="hasAny"
    class="corkboard-ogp-preview flex items-center rounded border border-surface-200 bg-surface-50 p-1.5 dark:border-surface-700 dark:bg-surface-800/60"
    :class="sizeClasses.root"
  >
    <!-- サムネイル -->
    <img
      v-if="showImage"
      :src="ogp.ogImageUrl ?? ''"
      :alt="imageAlt"
      class="shrink-0 rounded object-cover"
      :class="sizeClasses.image"
      loading="lazy"
      referrerpolicy="no-referrer"
      @error="onImageError"
    />
    <!-- 画像なしフォールバック（テキストのみで成立させるためのアイコン枠） -->
    <span
      v-else
      class="inline-flex shrink-0 items-center justify-center rounded bg-surface-200 text-surface-500 dark:bg-surface-700 dark:text-surface-400"
      :class="sizeClasses.image"
      :aria-label="t('corkboard.ogp.noImage')"
    >
      <i class="pi pi-image text-sm" aria-hidden="true" />
    </span>
    <!-- テキスト -->
    <div class="flex min-w-0 flex-1 flex-col gap-0.5">
      <p
        v-if="ogp.ogTitle"
        class="truncate font-medium text-surface-800 dark:text-surface-100"
        :class="sizeClasses.title"
      >
        {{ ogp.ogTitle }}
      </p>
      <p
        v-if="ogp.ogDescription"
        class="line-clamp-2 text-[11px] text-surface-500 dark:text-surface-400"
      >
        {{ ogp.ogDescription }}
      </p>
      <p
        v-else-if="ogp.url"
        class="truncate text-[11px] text-surface-400"
      >
        {{ ogp.url }}
      </p>
    </div>
  </div>
</template>
