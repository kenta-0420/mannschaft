<script setup lang="ts">
/**
 * F09.8.1 Phase 5: マイコルクボード 専用ページ。
 *
 * URL: /my/corkboard
 * 設計書 §6.1（専用ページ部分） / §6.3 / §6.4 / §6.5 に準拠。
 *
 * 機能:
 *  - 個人ボード横断ピン止めカード一覧（cursor ページネーション、limit=50）
 *  - 📌 ボタンでピン止め解除（PATCH .../pin）
 *  - 編集ボタンでメモ（user_note）編集モーダル
 *  - 🗑 ボタンでカード論理削除（DELETE .../cards/{cardId}）
 *  - 「+ 新規ピン」ボタンで /corkboard 一覧へ誘導
 *  - ボードによる絞り込み（クライアント側）
 *  - 「もっと読み込む」で nextCursor を使った追加取得
 *
 * 参照型は WidgetMyCorkboard.vue と共通の `pinnedCard.ts` を流用。
 * UI は中央寄せ最大幅 960px、カードは 1 列縦並び（メモ全文の可読性優先）。
 *
 * 権限喪失カードは「閲覧権限なし」表示し、ナビゲーションは無効化、
 * 自発的にピン止め解除できるよう 📌 ボタンは押下可とする（ウィジェットと同じ流儀）。
 */
import { useToast } from 'primevue/usetoast'
import type { PinnedCardItem, PinnedCardListResponse } from '~/types/pinnedCard'

definePageMeta({ middleware: 'auth' })

const { t, locale } = useI18n()
const api = useApi()
const toast = useToast()
const { captureQuiet } = useErrorReport()
const { confirmAction } = useConfirmDialog()

// ----- 状態 -----
const items = ref<PinnedCardItem[]>([])
const nextCursor = ref<string | null>(null)
const totalCount = ref(0)
const loading = ref(true)
const loadingMore = ref(false)
const error = ref<string | null>(null)
/** 絞り込み中のボード ID（null = 全ボード） */
const selectedBoardFilter = ref<number | null>(null)

// 編集モーダル
const editTarget = ref<PinnedCardItem | null>(null)
const editingNote = ref('')
const editSaving = ref(false)
const editDialogVisible = computed({
  get: () => editTarget.value !== null,
  set: (v: boolean) => {
    if (!v) {
      editTarget.value = null
      editingNote.value = ''
    }
  },
})

const PAGE_LIMIT = 50

// ----- API 呼び出し -----
async function load(append = false) {
  if (append) {
    loadingMore.value = true
  } else {
    loading.value = true
    items.value = []
    nextCursor.value = null
  }
  error.value = null
  try {
    const params = new URLSearchParams()
    params.set('limit', String(PAGE_LIMIT))
    if (append && nextCursor.value) {
      params.set('cursor', nextCursor.value)
    }
    const res = await api<{ data: PinnedCardListResponse }>(
      `/api/v1/users/me/corkboards/pinned-cards?${params.toString()}`,
    )
    const fetched = res.data.items ?? []
    if (append) {
      items.value = [...items.value, ...fetched]
    } else {
      items.value = fetched
    }
    nextCursor.value = res.data.nextCursor ?? null
    totalCount.value = res.data.totalCount ?? 0
  } catch (e) {
    captureQuiet(e, { context: 'MyCorkboardPage: ピン止めカード取得' })
    error.value = t('corkboard.loadError')
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

async function loadMore() {
  if (!nextCursor.value || loadingMore.value) return
  await load(true)
}

// ----- 絞り込み -----
/** 取得済みカードから一意なボード一覧を抽出（dropdown オプション用） */
const boardOptions = computed(() => {
  const seen = new Map<number, string>()
  for (const item of items.value) {
    if (!seen.has(item.corkboardId)) {
      seen.set(item.corkboardId, item.corkboardName ?? `#${item.corkboardId}`)
    }
  }
  const opts: Array<{ value: number | null; label: string }> = [
    { value: null, label: t('corkboard.allBoards') },
  ]
  for (const [id, name] of seen) {
    opts.push({ value: id, label: name })
  }
  return opts
})

const filteredItems = computed(() => {
  if (selectedBoardFilter.value === null) return items.value
  return items.value.filter((c) => c.corkboardId === selectedBoardFilter.value)
})

// ----- 操作: ピン解除 / 編集 / 削除 -----
async function unpin(item: PinnedCardItem) {
  try {
    await api(
      `/api/v1/corkboards/${item.corkboardId}/cards/${item.cardId}/pin`,
      { method: 'PATCH', body: { isPinned: false } },
    )
    items.value = items.value.filter((c) => c.cardId !== item.cardId)
    totalCount.value = Math.max(0, totalCount.value - 1)
    toast.add({
      severity: 'success',
      summary: t('corkboard.unpinSuccess'),
      life: 2500,
    })
  } catch (e) {
    captureQuiet(e, { context: 'MyCorkboardPage: ピン解除失敗' })
    toast.add({
      severity: 'error',
      summary: t('corkboard.unpinError'),
      life: 3500,
    })
  }
}

function openEdit(item: PinnedCardItem) {
  editTarget.value = item
  editingNote.value = item.userNote ?? ''
}

async function saveEdit() {
  const target = editTarget.value
  if (!target) return
  editSaving.value = true
  try {
    // 既存 PUT /api/v1/corkboards/{boardId}/cards/{cardId} を流用。
    // user_note のみ編集可（設計書 §6.1）。
    await api(
      `/api/v1/corkboards/${target.corkboardId}/cards/${target.cardId}`,
      { method: 'PUT', body: { userNote: editingNote.value } },
    )
    // ローカル側も同期
    const idx = items.value.findIndex((c) => c.cardId === target.cardId)
    if (idx >= 0) {
      const current = items.value[idx]
      if (current) {
        items.value[idx] = { ...current, userNote: editingNote.value }
      }
    }
    toast.add({
      severity: 'success',
      summary: t('corkboard.saveSuccess'),
      life: 2500,
    })
    editTarget.value = null
    editingNote.value = ''
  } catch (e) {
    captureQuiet(e, { context: 'MyCorkboardPage: メモ保存失敗' })
    toast.add({
      severity: 'error',
      summary: t('corkboard.saveError'),
      life: 3500,
    })
  } finally {
    editSaving.value = false
  }
}

function confirmDelete(item: PinnedCardItem) {
  confirmAction({
    message: t('corkboard.deleteConfirm'),
    onAccept: () => doDelete(item),
  })
}

async function doDelete(item: PinnedCardItem) {
  try {
    await api(
      `/api/v1/corkboards/${item.corkboardId}/cards/${item.cardId}`,
      { method: 'DELETE' },
    )
    items.value = items.value.filter((c) => c.cardId !== item.cardId)
    totalCount.value = Math.max(0, totalCount.value - 1)
    toast.add({
      severity: 'success',
      summary: t('corkboard.deleteSuccess'),
      life: 2500,
    })
  } catch (e) {
    captureQuiet(e, { context: 'MyCorkboardPage: カード削除失敗' })
    toast.add({
      severity: 'error',
      summary: t('corkboard.deleteError'),
      life: 3500,
    })
  }
}

// ----- カードクリック挙動（ウィジェットと同じ流儀） -----
function onCardClick(item: PinnedCardItem) {
  if (item.cardType === 'URL' && item.reference?.url) {
    window.open(item.reference.url, '_blank', 'noopener')
    return
  }
  if (item.reference && !item.reference.isAccessible) {
    toast.add({
      severity: 'warn',
      summary: t('corkboard.navigationFailed'),
      life: 3500,
    })
    return
  }
  if (item.reference?.navigateTo) {
    navigateTo(item.reference.navigateTo)
  }
}

function onCardKeydown(event: KeyboardEvent, item: PinnedCardItem) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    onCardClick(item)
  }
}

// ----- 表示ヘルパ -----
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

function formatDateTime(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  try {
    return new Intl.DateTimeFormat(locale.value, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    }).format(d)
  } catch {
    return d.toLocaleString()
  }
}

function ariaLabelFor(item: PinnedCardItem): string {
  const typeLabel = item.cardType
  const titleLabel = item.title || item.reference?.snapshotTitle || ''
  const boardLabel = item.corkboardName || ''
  return `${typeLabel}: ${titleLabel} (${boardLabel})`
}

onMounted(() => load(false))
</script>

<template>
  <div class="mx-auto max-w-[960px] px-4">
    <!-- ヘッダー: < 戻る | 📌 マイコルクボード | + 新規ピン -->
    <div class="mb-4 flex flex-wrap items-center justify-between gap-2">
      <div class="flex items-center gap-3">
        <NuxtLink
          to="/my"
          class="inline-flex items-center gap-1 text-sm text-surface-500 hover:text-primary"
        >
          <i class="pi pi-arrow-left text-xs" />
          {{ t('corkboard.back') }}
        </NuxtLink>
        <h1 class="flex items-center gap-2 text-xl font-bold">
          <span aria-hidden="true">📌</span>
          {{ t('corkboard.pageTitle') }}
        </h1>
      </div>
      <NuxtLink to="/corkboard">
        <Button
          :label="t('corkboard.addNewPin')"
          icon="pi pi-plus"
          size="small"
          severity="secondary"
        />
      </NuxtLink>
    </div>

    <!-- ツールバー: 全件数 / 並び順 / 絞り込み -->
    <div
      class="mb-3 flex flex-wrap items-center gap-3 rounded-lg border border-surface-200 bg-surface-50 px-3 py-2 text-xs text-surface-600 dark:border-surface-700 dark:bg-surface-800/50 dark:text-surface-300"
    >
      <span class="font-semibold">
        {{ t('corkboard.totalCount', { count: totalCount }) }}
      </span>
      <span class="opacity-60">|</span>
      <span class="inline-flex items-center gap-1">
        <i class="pi pi-sort-amount-down text-[10px]" />
        {{ t('corkboard.sortByPinnedAt') }}
      </span>
      <span class="opacity-60">|</span>
      <label class="inline-flex items-center gap-1">
        <span>{{ t('corkboard.filterByBoard') }}:</span>
        <select
          v-model="selectedBoardFilter"
          class="rounded border border-surface-300 bg-surface-0 px-1 py-0.5 text-xs dark:border-surface-600 dark:bg-surface-900"
        >
          <option v-for="opt in boardOptions" :key="String(opt.value)" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>
      </label>
    </div>

    <!-- ローディング -->
    <PageLoading v-if="loading" size="40px" />

    <!-- エラー -->
    <div v-else-if="error" class="flex flex-col items-center gap-2 py-10 text-center">
      <i class="pi pi-exclamation-triangle text-2xl text-orange-400" />
      <p class="text-sm text-surface-500">{{ error }}</p>
      <Button
        :label="t('dashboard.myCorkboard.retry')"
        icon="pi pi-refresh"
        size="small"
        text
        @click="load(false)"
      />
    </div>

    <!-- 空 -->
    <div
      v-else-if="items.length === 0"
      class="flex flex-col items-center gap-2 py-12 text-center"
    >
      <i class="pi pi-bookmark text-4xl text-surface-300" />
      <p class="text-sm text-surface-500">
        {{ t('dashboard.myCorkboard.empty') }}
      </p>
      <p class="text-xs text-surface-400">
        {{ t('corkboard.addNewPinHint') }}
      </p>
    </div>

    <!-- カード一覧（1列縦並び） -->
    <ul v-else class="flex flex-col gap-3">
      <li
        v-for="item in filteredItems"
        :key="item.cardId"
        role="article"
        :aria-label="ariaLabelFor(item)"
        class="flex overflow-hidden rounded-lg border border-surface-200 bg-surface-0 dark:border-surface-700 dark:bg-surface-800"
        :class="item.reference && !item.reference.isAccessible ? 'opacity-70' : ''"
      >
        <!-- カラーラベル -->
        <span
          class="w-1.5 shrink-0"
          :class="colorClassFor(item.colorLabel)"
          aria-hidden="true"
        />

        <div class="flex min-w-0 flex-1 flex-col gap-2 p-4">
          <!-- 上段: アイコン + タイトル + 操作ボタン群 -->
          <div class="flex items-start justify-between gap-3">
            <button
              type="button"
              class="flex min-w-0 flex-1 items-start gap-2 text-left"
              :aria-label="t('corkboard.ariaNavigate')"
              :disabled="!!(item.reference && !item.reference.isAccessible) && item.cardType !== 'URL'"
              @click="onCardClick(item)"
              @keydown="onCardKeydown($event, item)"
            >
              <i :class="iconFor(item)" class="mt-0.5 shrink-0 text-sm text-primary" />
              <div class="min-w-0 flex-1">
                <p
                  v-if="item.title || item.reference?.snapshotTitle"
                  class="truncate text-sm font-semibold text-surface-800 dark:text-surface-100"
                >
                  {{ item.title || item.reference?.snapshotTitle }}
                </p>
                <p
                  v-if="item.cardType === 'URL' && item.reference?.url"
                  class="truncate text-xs text-surface-500"
                >
                  {{ item.reference.url }}
                </p>
              </div>
            </button>

            <!-- 操作ボタン群 -->
            <div class="flex shrink-0 items-center gap-1">
              <Button
                icon="pi pi-bookmark-fill"
                severity="secondary"
                text
                rounded
                size="small"
                :aria-label="t('corkboard.ariaUnpin')"
                :title="t('corkboard.unpin')"
                @click="unpin(item)"
              />
              <Button
                icon="pi pi-pencil"
                severity="secondary"
                text
                rounded
                size="small"
                :aria-label="t('corkboard.ariaEdit')"
                :title="t('corkboard.editTitle')"
                @click="openEdit(item)"
              />
              <Button
                icon="pi pi-trash"
                severity="danger"
                text
                rounded
                size="small"
                :aria-label="t('corkboard.ariaDelete')"
                :title="t('corkboard.deleteConfirm')"
                @click="confirmDelete(item)"
              />
            </div>
          </div>

          <!-- 本文 / メモ -->
          <p
            v-if="item.userNote"
            class="whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-200"
          >
            「{{ item.userNote }}」
          </p>
          <p
            v-else-if="item.body"
            class="whitespace-pre-wrap text-sm text-surface-600 dark:text-surface-300"
          >
            {{ item.body }}
          </p>

          <!-- F09.8 Phase G: URL カードの OGP プレビュー -->
          <CardOgpPreview
            v-if="
              item.cardType === 'URL' &&
              (item.reference?.ogTitle || item.reference?.ogImageUrl)
            "
            :card="item"
            size="md"
          />

          <!-- F09.8 Phase G: REFERENCE カードのスナップショット表示 -->
          <CardSnapshot
            v-else-if="
              item.cardType === 'REFERENCE' &&
              (item.reference?.snapshotTitle || item.reference?.snapshotExcerpt || item.reference?.isDeleted)
            "
            :card="item"
            :compact="false"
          />

          <!-- 権限喪失バッジ -->
          <div
            v-if="item.reference && !item.reference.isAccessible"
            class="inline-flex items-center gap-1 text-xs text-orange-600 dark:text-orange-400"
          >
            <i class="pi pi-eye-slash text-[10px]" />
            {{ t('corkboard.noAccessLabel') }}
          </div>

          <!-- 削除済み参照バッジ（REFERENCE 以外。REFERENCE は CardSnapshot 側で出す） -->
          <div
            v-else-if="item.cardType !== 'REFERENCE' && item.reference?.isDeleted"
            class="inline-flex items-center gap-1 text-xs text-surface-400"
          >
            <i class="pi pi-trash text-[10px]" />
            {{ t('corkboard.deletedRefLabel') }}
          </div>

          <!-- メタ: ボード | ピン止め日時 -->
          <div class="flex flex-wrap items-center gap-3 text-[11px] text-surface-400">
            <span v-if="item.corkboardName">
              {{ t('corkboard.boardLabel', { boardName: item.corkboardName }) }}
            </span>
            <span aria-hidden="true">|</span>
            <span>
              {{ t('corkboard.pinnedAtLabel', { datetime: formatDateTime(item.pinnedAt) }) }}
            </span>
          </div>
        </div>
      </li>
    </ul>

    <!-- もっと読み込む -->
    <div v-if="!loading && !error && nextCursor" class="mt-6 flex justify-center">
      <Button
        :label="t('corkboard.loadMore')"
        icon="pi pi-chevron-down"
        size="small"
        :loading="loadingMore"
        @click="loadMore"
      />
    </div>

    <!-- 編集モーダル -->
    <Dialog
      v-model:visible="editDialogVisible"
      modal
      :header="t('corkboard.editTitle')"
      :style="{ width: '480px' }"
      :closable="!editSaving"
    >
      <div class="flex flex-col gap-3">
        <label class="text-sm font-medium">{{ t('corkboard.editUserNoteLabel') }}</label>
        <Textarea
          v-model="editingNote"
          :placeholder="t('corkboard.editUserNotePlaceholder')"
          rows="4"
          autoResize
          class="w-full"
        />
      </div>
      <template #footer>
        <Button
          :label="t('button.cancel')"
          severity="secondary"
          text
          :disabled="editSaving"
          @click="editDialogVisible = false"
        />
        <Button
          :label="t('button.save')"
          :loading="editSaving"
          @click="saveEdit"
        />
      </template>
    </Dialog>

    <!-- 確認ダイアログ（削除用、useConfirmDialog 経由） -->
    <ConfirmDialog />
  </div>
</template>
