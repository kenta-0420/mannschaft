<script setup lang="ts">
import type { WeeklySummary } from '~/types/actionMemo'

/**
 * F02.5 行動メモ 週次まとめ閲覧画面（Phase 3）。
 *
 * <p>設計書 §11 #11 の確定判断「新規 API は作らない。F06.1 BlogPost API を流用」に従い、
 * {@code GET /api/v1/blog/posts?visibility=PRIVATE} から取得した非公開ブログ一覧の中から
 * タイトルが「週次ふりかえり: 」で始まる記事だけを抽出して表示する。</p>
 *
 * <p>各記事はカード形式で表示し、タイトル・対象期間・本文プレビュー（200文字）を含む。
 * 「詳細を見る」ボタンで全文表示モーダルを開く（既存 BlogPost 詳細ページは
 * 編集画面のみでビューア画面がないため、本画面内のモーダルで実装）。</p>
 */

definePageMeta({ middleware: 'auth' })

const router = useRouter()
const { t } = useI18n()
const store = useActionMemoStore()

/** 詳細モーダル表示中の記事 */
const detailSummary = ref<WeeklySummary | null>(null)
const detailModalOpen = ref(false)

/** 本文プレビューの最大文字数 */
const PREVIEW_LENGTH = 200

function bodyPreview(body: string): string {
  if (body.length <= PREVIEW_LENGTH) return body
  return body.slice(0, PREVIEW_LENGTH)
}

function isBodyTruncated(body: string): boolean {
  return body.length > PREVIEW_LENGTH
}

function openDetail(summary: WeeklySummary) {
  detailSummary.value = summary
  detailModalOpen.value = true
}

function closeDetail() {
  detailModalOpen.value = false
  detailSummary.value = null
}

function goBack() {
  router.push('/action-memo')
}

async function onRetry() {
  await store.fetchWeeklySummaries(0)
}

async function onLoadMore() {
  if (store.weeklyPage + 1 < store.weeklyTotalPages) {
    await store.fetchWeeklySummaries(store.weeklyPage + 1)
  }
}

const hasMore = computed(() => store.weeklyPage + 1 < store.weeklyTotalPages)

onMounted(async () => {
  await store.fetchWeeklySummaries(0)
})
</script>

<template>
  <div class="mx-auto flex max-w-2xl flex-col gap-4 px-3 py-4">
    <!-- ヘッダー -->
    <header class="flex items-center gap-2">
      <button
        type="button"
        class="rounded-lg px-2 py-1 text-sm text-surface-500 hover:bg-surface-100 dark:hover:bg-surface-700"
        data-testid="action-memo-weekly-back"
        @click="goBack"
      >
        <i class="pi pi-arrow-left mr-1 text-xs" />
        {{ t('action_memo.page.back_to_memo') }}
      </button>
    </header>

    <h1
      class="text-xl font-bold"
      data-testid="action-memo-weekly-title"
    >
      {{ t('action_memo.weekly.page_title') }}
    </h1>

    <!-- ローディングスケルトン -->
    <div
      v-if="store.weeklyLoading && store.weeklySummaries.length === 0"
      class="flex flex-col gap-3"
      data-testid="action-memo-weekly-loading"
    >
      <div
        v-for="i in 3"
        :key="i"
        class="animate-pulse rounded-2xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
      >
        <div class="mb-2 h-5 w-3/4 rounded bg-surface-200 dark:bg-surface-700" />
        <div class="mb-3 h-3 w-1/3 rounded bg-surface-200 dark:bg-surface-700" />
        <div class="mb-1 h-3 w-full rounded bg-surface-200 dark:bg-surface-700" />
        <div class="mb-1 h-3 w-5/6 rounded bg-surface-200 dark:bg-surface-700" />
        <div class="h-3 w-2/3 rounded bg-surface-200 dark:bg-surface-700" />
      </div>
    </div>

    <!-- エラー表示 -->
    <div
      v-else-if="store.weeklyError && store.weeklySummaries.length === 0"
      class="flex flex-col items-center gap-3 rounded-2xl border border-rose-300 bg-rose-50 px-4 py-6 text-center dark:border-rose-800 dark:bg-rose-900/30"
      role="alert"
      data-testid="action-memo-weekly-error"
    >
      <p class="text-sm text-rose-700 dark:text-rose-200">
        {{ t('action_memo.weekly.error') }}
      </p>
      <button
        type="button"
        class="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white"
        data-testid="action-memo-weekly-retry"
        @click="onRetry"
      >
        {{ t('action_memo.weekly.retry') }}
      </button>
    </div>

    <!-- 空状態 -->
    <div
      v-else-if="!store.weeklyLoading && store.weeklySummaries.length === 0"
      class="flex flex-col items-center gap-3 rounded-2xl border border-surface-200 bg-surface-0 px-4 py-8 text-center dark:border-surface-700 dark:bg-surface-800"
      data-testid="action-memo-weekly-empty"
    >
      <i class="pi pi-calendar-times text-3xl text-surface-300 dark:text-surface-600" />
      <p class="text-sm text-surface-500 dark:text-surface-400">
        {{ t('action_memo.weekly.empty_state') }}
      </p>
      <p class="text-xs text-surface-400 dark:text-surface-500">
        {{ t('action_memo.weekly.next_summary') }}
      </p>
    </div>

    <!-- 週次まとめカード一覧 -->
    <div
      v-else
      class="flex flex-col gap-3"
      data-testid="action-memo-weekly-list"
    >
      <article
        v-for="summary in store.weeklySummaries"
        :key="summary.id"
        class="rounded-2xl border border-surface-200 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
        :data-testid="`action-memo-weekly-card-${summary.id}`"
      >
        <h2 class="mb-1 text-base font-semibold text-surface-800 dark:text-surface-100">
          {{ summary.title }}
        </h2>
        <p
          v-if="summary.period.from && summary.period.to"
          class="mb-2 text-xs text-surface-400 dark:text-surface-500"
        >
          {{ t('action_memo.weekly.period_label') }}: {{ summary.period.from }} ~ {{ summary.period.to }}
        </p>
        <p class="mb-3 whitespace-pre-line text-sm text-surface-600 dark:text-surface-300">
          {{ bodyPreview(summary.body) }}<span
            v-if="isBodyTruncated(summary.body)"
            class="text-primary"
          >{{ t('action_memo.weekly.body_preview_more') }}</span>
        </p>
        <button
          type="button"
          class="rounded-lg px-3 py-1.5 text-sm font-medium text-primary hover:bg-primary/10"
          :data-testid="`action-memo-weekly-view-detail-${summary.id}`"
          @click="openDetail(summary)"
        >
          <i class="pi pi-eye mr-1 text-xs" />
          {{ t('action_memo.weekly.view_detail') }}
        </button>
      </article>

      <!-- もっと読み込むボタン -->
      <button
        v-if="hasMore"
        type="button"
        class="rounded-lg border border-surface-200 bg-surface-0 px-4 py-2 text-sm text-surface-600 hover:bg-surface-50 dark:border-surface-700 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700"
        :disabled="store.weeklyLoading"
        data-testid="action-memo-weekly-load-more"
        @click="onLoadMore"
      >
        <i
          v-if="store.weeklyLoading"
          class="pi pi-spinner pi-spin mr-1 text-xs"
        />
        {{ store.weeklyLoading ? t('action_memo.weekly.loading') : t('action_memo.weekly.load_more') }}
      </button>
    </div>

    <!-- 詳細モーダル -->
    <Teleport to="body">
      <div
        v-if="detailModalOpen && detailSummary"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
        data-testid="action-memo-weekly-detail-modal"
        @click.self="closeDetail"
      >
        <div class="max-h-[80vh] w-full max-w-2xl overflow-y-auto rounded-2xl bg-white p-6 shadow-xl dark:bg-surface-800">
          <div class="mb-4 flex items-start justify-between gap-4">
            <h2 class="text-lg font-bold text-surface-800 dark:text-surface-100">
              {{ detailSummary.title }}
            </h2>
            <button
              type="button"
              class="shrink-0 rounded-lg p-1 text-surface-400 hover:bg-surface-100 dark:hover:bg-surface-700"
              data-testid="action-memo-weekly-detail-close"
              @click="closeDetail"
            >
              <i class="pi pi-times" />
            </button>
          </div>
          <p
            v-if="detailSummary.period.from && detailSummary.period.to"
            class="mb-3 text-xs text-surface-400 dark:text-surface-500"
          >
            {{ t('action_memo.weekly.period_label') }}: {{ detailSummary.period.from }} ~ {{ detailSummary.period.to }}
          </p>
          <div class="prose prose-sm max-w-none whitespace-pre-line text-surface-700 dark:prose-invert dark:text-surface-200">
            {{ detailSummary.body }}
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
