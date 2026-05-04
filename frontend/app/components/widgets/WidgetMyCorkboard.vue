<script setup lang="ts">
/**
 * F09.8.1 Phase 4: マイコルクボード ウィジェット。
 *
 * 個人ボード横断で `is_pinned = true` のカードを縦スクロール一覧表示する。
 * 設計書 §6.1 / §6.3 / §6.4 / §6.5 に準拠。
 *
 * - データ取得: GET /api/v1/users/me/corkboards/pinned-cards?limit=20
 * - 状態別 UX: ローディング（スケルトン）/ エラー（再試行）/ 空 / 正常
 * - クリック挙動: 参照可カードはナビゲート、URL カードは別タブ、権限喪失はトースト
 * - 権限喪失カードは「ピン止めを外す」ボタンで自発的クリーンアップ可能
 *
 * このコンポーネントは `ScopeDashboard.vue` の DashboardWidgetCard 内側に
 * データウィジェットとして埋め込まれる前提（外側で既にカード枠が付くため、
 * ここではプレーン div を返す。WidgetSurveyResults と同じ流儀）。
 */
import { useToast } from 'primevue/usetoast'
import type { PinnedCardItem, PinnedCardListResponse } from '~/types/pinnedCard'

const { t } = useI18n()
const api = useApi()
const toast = useToast()
const { captureQuiet } = useErrorReport()

const items = ref<PinnedCardItem[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const totalCount = ref(0)

const isEmpty = computed(() => items.value.length === 0)

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await api<{ data: PinnedCardListResponse }>(
      '/api/v1/users/me/corkboards/pinned-cards?limit=20',
    )
    items.value = res.data.items ?? []
    totalCount.value = res.data.totalCount ?? 0
  } catch (e) {
    captureQuiet(e, { context: 'WidgetMyCorkboard: ピン止めカード取得' })
    error.value = t('dashboard.myCorkboard.loadingError')
    items.value = []
  } finally {
    loading.value = false
  }
}

/** 色ラベル → 左端カラーバーの Tailwind クラス */
const colorBarClass: Record<string, string> = {
  YELLOW: 'bg-yellow-400',
  BLUE: 'bg-blue-400',
  GREEN: 'bg-green-400',
  RED: 'bg-red-400',
  ORANGE: 'bg-orange-400',
  PURPLE: 'bg-purple-400',
  PINK: 'bg-pink-400',
  GRAY: 'bg-gray-400',
}

function colorClassFor(label: string | null): string {
  if (!label) return 'bg-surface-300'
  return colorBarClass[label.toUpperCase()] ?? 'bg-surface-300'
}

/** 参照型に応じたアイコンを返す */
function iconFor(item: PinnedCardItem): string {
  if (item.cardType === 'URL' || item.reference?.type === 'URL') return 'pi pi-link'
  if (item.cardType === 'MEMO') return 'pi pi-pencil'
  if (item.cardType === 'SECTION_HEADER') return 'pi pi-tag'
  const refType = item.reference?.type
  switch (refType) {
    case 'TIMELINE_POST':
      return 'pi pi-comments'
    case 'BULLETIN_THREAD':
      return 'pi pi-megaphone'
    case 'BLOG_POST':
      return 'pi pi-book'
    case 'CHAT_MESSAGE':
      return 'pi pi-inbox'
    case 'FILE':
    case 'DOCUMENT':
      return 'pi pi-file'
    case 'TEAM':
      return 'pi pi-users'
    case 'ORGANIZATION':
      return 'pi pi-building'
    case 'EVENT':
      return 'pi pi-calendar'
    default:
      return 'pi pi-bookmark'
  }
}

/** カードクリック時のナビゲーション処理。設計書 §6.1 のクリック挙動に準拠。 */
function onCardClick(item: PinnedCardItem) {
  // URL カードは外部リンクなので別タブで開く（noopener 必須・§2-3 セキュリティ精査）
  if (item.cardType === 'URL' && item.reference?.url) {
    window.open(item.reference.url, '_blank', 'noopener')
    return
  }
  // 参照を持つカードのうち権限喪失カードはトースト警告
  if (item.reference && !item.reference.isAccessible) {
    toast.add({
      severity: 'warn',
      summary: t('dashboard.myCorkboard.navigationFailed'),
      life: 3500,
    })
    return
  }
  // 参照可カードはナビゲート先へ遷移
  if (item.reference?.navigateTo) {
    navigateTo(item.reference.navigateTo)
    return
  }
  // MEMO / SECTION_HEADER などナビゲート先を持たないカードは何もしない（編集は専用ページで）
}

/** Enter / Space キー対応（A11y §6.4） */
function onCardKeydown(event: KeyboardEvent, item: PinnedCardItem) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    onCardClick(item)
  }
}

/** 権限喪失カードの「ピン止めを外す」処理。 */
async function unpin(item: PinnedCardItem) {
  try {
    await api(
      `/api/v1/corkboards/${item.corkboardId}/cards/${item.cardId}/pin`,
      { method: 'PATCH', body: { isPinned: false } },
    )
    items.value = items.value.filter((c) => c.cardId !== item.cardId)
    totalCount.value = Math.max(0, totalCount.value - 1)
  } catch (e) {
    captureQuiet(e, { context: 'WidgetMyCorkboard: ピン解除失敗' })
    toast.add({
      severity: 'error',
      summary: t('dashboard.myCorkboard.loadingError'),
      life: 3500,
    })
  }
}

/** スクリーンリーダー向け aria-label（type: title (boardName)） */
function ariaLabelFor(item: PinnedCardItem): string {
  const typeLabel = item.cardType
  const titleLabel = item.title || item.reference?.snapshotTitle || ''
  const boardLabel = item.corkboardName || ''
  return `${typeLabel}: ${titleLabel} (${boardLabel})`
}

onMounted(load)
</script>

<template>
  <div @click.stop>
    <!-- ローディング: スケルトン 3 件 -->
    <div v-if="loading" class="space-y-2">
      <Skeleton v-for="i in 3" :key="i" height="4rem" />
    </div>

    <!-- エラー -->
    <div v-else-if="error" class="flex flex-col items-center gap-2 py-6">
      <i class="pi pi-exclamation-triangle text-2xl text-orange-400" />
      <p class="text-sm text-surface-500">{{ error }}</p>
      <Button
        :label="t('dashboard.myCorkboard.retry')"
        icon="pi pi-refresh"
        size="small"
        text
        @click="load"
      />
    </div>

    <!-- 空 -->
    <div
      v-else-if="isEmpty"
      class="flex flex-col items-center gap-2 py-8 text-center"
    >
      <i class="pi pi-bookmark text-3xl text-surface-300" />
      <p class="text-sm text-surface-500">
        {{ t('dashboard.myCorkboard.empty') }}
      </p>
      <p class="text-xs text-surface-400">
        {{ t('dashboard.myCorkboard.emptyHint') }}
      </p>
    </div>

    <!-- 正常: カード一覧 -->
    <ul v-else class="space-y-2">
      <li
        v-for="item in items"
        :key="item.cardId"
        class="relative flex overflow-hidden rounded-lg border border-surface-200 bg-surface-0 transition-colors dark:border-surface-700 dark:bg-surface-800"
        :class="
          item.reference && !item.reference.isAccessible
            ? 'opacity-60'
            : 'cursor-pointer hover:bg-surface-50 dark:hover:bg-surface-700/50'
        "
        :role="item.reference && !item.reference.isAccessible ? undefined : 'button'"
        :tabindex="item.reference && !item.reference.isAccessible ? -1 : 0"
        :aria-label="ariaLabelFor(item)"
        :aria-disabled="item.reference && !item.reference.isAccessible ? 'true' : undefined"
        @click="onCardClick(item)"
        @keydown="onCardKeydown($event, item)"
      >
        <!-- カラーラベル（左端の色帯） -->
        <span
          class="w-1.5 shrink-0"
          :class="colorClassFor(item.colorLabel)"
          :aria-label="
            item.colorLabel
              ? t('dashboard.myCorkboard.colorLabelAria', { color: item.colorLabel })
              : undefined
          "
        />

        <div class="flex min-w-0 flex-1 flex-col gap-1 p-3">
          <!-- ヘッダー行: アイコン + タイトル -->
          <div class="flex items-start gap-2">
            <i :class="iconFor(item)" class="mt-0.5 shrink-0 text-sm text-primary" />
            <div class="min-w-0 flex-1">
              <p
                v-if="item.title || item.reference?.snapshotTitle"
                class="truncate text-sm font-medium text-surface-700 dark:text-surface-200"
              >
                {{ item.title || item.reference?.snapshotTitle }}
              </p>
              <p
                v-if="item.userNote"
                class="line-clamp-2 text-xs text-surface-500 dark:text-surface-400"
              >
                「{{ item.userNote }}」
              </p>
              <p
                v-else-if="item.body"
                class="line-clamp-2 text-xs text-surface-500 dark:text-surface-400"
              >
                {{ item.body }}
              </p>
              <p
                v-else-if="item.cardType === 'URL' && item.reference?.url && !(item.reference?.ogTitle || item.reference?.ogImageUrl)"
                class="truncate text-xs text-surface-400"
              >
                {{ item.reference.url }}
              </p>
            </div>
          </div>

          <!-- F09.8 Phase G: URL カードの OGP プレビュー（取得済みの時のみ） -->
          <CardOgpPreview
            v-if="
              item.cardType === 'URL' &&
              (item.reference?.ogTitle || item.reference?.ogImageUrl)
            "
            :card="item"
            size="sm"
          />

          <!-- F09.8 Phase G: REFERENCE カードのスナップショット表示（削除済み・抜粋） -->
          <CardSnapshot
            v-else-if="
              item.cardType === 'REFERENCE' &&
              (item.reference?.snapshotTitle || item.reference?.snapshotExcerpt || item.reference?.isDeleted)
            "
            :card="item"
            compact
          />

          <!-- 権限喪失バッジ -->
          <div
            v-if="item.reference && !item.reference.isAccessible"
            class="flex flex-wrap items-center gap-2 text-xs text-orange-600 dark:text-orange-400"
          >
            <span class="inline-flex items-center gap-1">
              <i class="pi pi-eye-slash text-[10px]" />
              {{ t('dashboard.myCorkboard.noAccess') }}
            </span>
            <button
              type="button"
              class="rounded border border-orange-300 px-2 py-0.5 text-[11px] text-orange-600 transition-colors hover:bg-orange-50 dark:border-orange-700 dark:text-orange-300 dark:hover:bg-orange-900/30"
              @click.stop="unpin(item)"
            >
              {{ t('dashboard.myCorkboard.unpinAction') }}
            </button>
          </div>

          <!-- 削除済み参照バッジ（REFERENCE 以外。REFERENCE は CardSnapshot 側で出す） -->
          <div
            v-else-if="item.cardType !== 'REFERENCE' && item.reference?.isDeleted"
            class="text-xs text-surface-400"
          >
            <i class="pi pi-trash mr-1 text-[10px]" />
            {{ t('dashboard.myCorkboard.deletedRef') }}
          </div>

          <!-- メタ情報: from: ボード名 -->
          <p v-if="item.corkboardName" class="text-[11px] text-surface-400">
            {{ t('dashboard.myCorkboard.from', { boardName: item.corkboardName }) }}
          </p>
        </div>
      </li>
    </ul>

    <!-- 全件リンク（footer） -->
    <div
      v-if="!loading && !error && !isEmpty"
      class="mt-3 text-right"
    >
      <NuxtLink
        to="/my/corkboard"
        class="text-xs text-primary hover:underline"
      >
        {{ t('dashboard.myCorkboard.viewAll') }}
        <i class="pi pi-external-link text-[10px]" />
      </NuxtLink>
    </div>
  </div>
</template>
