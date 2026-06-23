<script setup lang="ts">
/**
 * F04.11 統合通知インボックス — 一覧コンポーネント。
 *
 * 設計書: docs/features/F04.11_notification_inbox/04_security_operations.md §4 UX
 * 手本: NotificationList.vue / WidgetNotices.vue のタブ UI
 *
 * 機能:
 * - 状態タブ（受信箱 / スヌーズ中 / 保管庫）
 * - 緊急度 / 種類のサブフィルタ（チップ）
 * - 各行に snooze（プリセット選択）/ archive / unarchive / ラベル付与 アイコン
 * - priority バッジ + sourceType アイコン + ラベルチップ
 * - オフセットページング（「もっと読む」）
 * - 楽観更新はストア側で実施
 * - Phase 2: ラベル絞り込み、bulk 選択モード、ラベル管理
 */
import type { InboxLabel, InboxPriority, InboxSourceType, InboxStateFilter, SuggestedLabel } from '~/types/inbox'
import {
  priorityI18nKey,
  prioritySeverity,
  sourceTypeI18nKey,
  sourceTypeIcon,
  suggestionKeyI18nKey,
} from '~/composables/useInboxApi'
import type { InboxSnoozePreset } from '~/stores/useInboxStore'

const { t } = useI18n()
const inboxStore = useInboxStore()
const notification = useNotification()
const { captureQuiet } = useErrorReport()
const { relativeTime } = useRelativeTime()

// ─────────────────────────────────────────────
// ラベル管理パネル
// ─────────────────────────────────────────────

/** ラベル管理パネルの表示フラグ。 */
const showLabelManager = ref(false)

function toggleLabelManager() {
  showLabelManager.value = !showLabelManager.value
}

// ─────────────────────────────────────────────
// bulk 選択モード
// ─────────────────────────────────────────────

/** bulk スヌーズ用プリセット選択パネル表示フラグ。 */
const showBulkSnoozePanel = ref(false)
/** bulk ラベル付与用ラベル選択パネル表示フラグ。 */
const showBulkLabelPanel = ref(false)

function toggleBulkSnoozePanel() {
  showBulkSnoozePanel.value = !showBulkSnoozePanel.value
  showBulkLabelPanel.value = false
}

function toggleBulkLabelPanel() {
  showBulkLabelPanel.value = !showBulkLabelPanel.value
  showBulkSnoozePanel.value = false
}

async function onBulkArchive() {
  const result = await inboxStore.runBulk('ARCHIVE')
  if (result) {
    notification.success(
      t('inbox.bulk.result', { processed: result.processed, skipped: result.skipped }),
    )
  } else {
    notification.error(t('common.error.unknown'))
  }
}

async function onBulkSnooze(preset: InboxSnoozePreset) {
  const snoozedUntil = inboxStore.computeSnoozeUntil(preset)
  showBulkSnoozePanel.value = false
  const result = await inboxStore.runBulk('SNOOZE', { snoozedUntil })
  if (result) {
    notification.success(
      t('inbox.bulk.result', { processed: result.processed, skipped: result.skipped }),
    )
  } else {
    notification.error(t('common.error.unknown'))
  }
}

async function onBulkAddLabel(label: InboxLabel) {
  showBulkLabelPanel.value = false
  const result = await inboxStore.runBulk('LABEL_ADD', { labelId: label.id })
  if (result) {
    notification.success(
      t('inbox.bulk.result', { processed: result.processed, skipped: result.skipped }),
    )
  } else {
    notification.error(t('common.error.unknown'))
  }
}

// ─────────────────────────────────────────────
// スヌーズオーバーレイ
// ─────────────────────────────────────────────

/** スヌーズプリセット選択パネルの表示対象アイテム ID。 */
const snoozeTargetId = ref<string | null>(null)

/**
 * プリセット選択後にスヌーズを実行する。
 */
async function onSnoozePreset(
  sourceType: InboxSourceType,
  sourceId: number,
  preset: InboxSnoozePreset,
) {
  const snoozedUntil = inboxStore.computeSnoozeUntil(preset)
  snoozeTargetId.value = null
  try {
    const ok = await inboxStore.snooze(sourceType, sourceId, snoozedUntil)
    if (ok) {
      notification.success(t('inbox.action.snoozed'))
    } else {
      notification.error(t('common.error.unknown'))
    }
  } catch (error) {
    captureQuiet(error, { context: 'InboxList: スヌーズ実行' })
  }
}

// ─────────────────────────────────────────────
// アーカイブ / 解除
// ─────────────────────────────────────────────

async function onArchive(sourceType: InboxSourceType, sourceId: number) {
  try {
    const ok = await inboxStore.archive(sourceType, sourceId)
    if (ok) {
      notification.success(t('inbox.action.archived'))
    }
  } catch (error) {
    captureQuiet(error, { context: 'InboxList: アーカイブ' })
  }
}

async function onUnarchive(sourceType: InboxSourceType, sourceId: number) {
  try {
    const ok = await inboxStore.unarchive(sourceType, sourceId)
    if (ok) {
      notification.success(t('inbox.action.unarchived'))
    }
  } catch (error) {
    captureQuiet(error, { context: 'InboxList: アーカイブ解除' })
  }
}

async function onUnsnooze(sourceType: InboxSourceType, sourceId: number) {
  try {
    const ok = await inboxStore.unsnooze(sourceType, sourceId)
    if (ok) {
      notification.success(t('inbox.action.unsnoozed'))
    }
  } catch (error) {
    captureQuiet(error, { context: 'InboxList: スヌーズ解除' })
  }
}

/** スヌーズパネル以外クリックで閉じる共通処理。 */
function onSnoozeToggle(id: string) {
  snoozeTargetId.value = snoozeTargetId.value === id ? null : id
}

// ─────────────────────────────────────────────
// タブ
// ─────────────────────────────────────────────

interface TabDef {
  key: InboxStateFilter
  i18nKey: string
  count: number
}

const tabs = computed<TabDef[]>(() => [
  { key: 'INBOX', i18nKey: 'inbox.tab.inbox', count: inboxStore.inboxCount },
  { key: 'SNOOZED', i18nKey: 'inbox.tab.snoozed', count: inboxStore.snoozedCount },
  { key: 'ARCHIVED', i18nKey: 'inbox.tab.archived', count: inboxStore.archivedCount },
])

async function onTabChange(tab: InboxStateFilter) {
  snoozeTargetId.value = null
  showLabelManager.value = false
  await inboxStore.switchTab(tab)
}

// ─────────────────────────────────────────────
// サブフィルタ（緊急度 / 種類）
// ─────────────────────────────────────────────

const allPriorities: InboxPriority[] = ['URGENT', 'HIGH', 'NORMAL', 'LOW']
const allSourceTypes: InboxSourceType[] = [
  'NOTIFICATION',
  'ANNOUNCEMENT',
  'MENTION',
  'CONFIRMABLE',
  'TODO_DUE',
]

/** 緊急度チップのトグル。 */
async function togglePriority(priority: InboxPriority) {
  const current = inboxStore.priorityFilter.slice()
  const idx = current.indexOf(priority)
  if (idx >= 0) {
    current.splice(idx, 1)
  } else {
    current.push(priority)
  }
  await inboxStore.setPriorityFilter(current)
}

/** 種類チップのトグル。 */
async function toggleSourceType(sourceType: InboxSourceType) {
  const current = inboxStore.sourceTypeFilter.slice()
  const idx = current.indexOf(sourceType)
  if (idx >= 0) {
    current.splice(idx, 1)
  } else {
    current.push(sourceType)
  }
  await inboxStore.setSourceTypeFilter(current)
}

/** ラベルフィルタのトグル（同じラベルを再クリックで解除）。 */
async function toggleLabelFilter(labelId: string) {
  const next = inboxStore.labelFilter === labelId ? null : labelId
  await inboxStore.setLabelFilter(next)
}

// ─────────────────────────────────────────────
// 自動ラベリング提案（wave3b）
// ─────────────────────────────────────────────

/**
 * 提案チップのタップで suggestApply を呼ぶ。
 * labelName は i18n 解決済み文字列を渡す（BE は表示名を持たない）。
 */
async function onSuggestionApply(
  sourceType: InboxSourceType,
  sourceId: number,
  suggestion: SuggestedLabel,
) {
  const labelName = t(suggestionKeyI18nKey(suggestion.suggestionKey))
  const ok = await inboxStore.suggestApply(sourceType, sourceId, suggestion, labelName)
  if (ok) {
    notification.success(t('inbox.label.assigned'))
  } else {
    // _handleError が error をセットし、ここではトーストを出す
    notification.error(t('common.error.unknown'))
  }
}

// ─────────────────────────────────────────────
// もっと読む
// ─────────────────────────────────────────────

async function onLoadMore() {
  await inboxStore.fetchMore()
}

// ─────────────────────────────────────────────
// 初期ロード
// ─────────────────────────────────────────────

onMounted(async () => {
  await Promise.all([inboxStore.fetchSummary(), inboxStore.fetchInbox(), inboxStore.fetchLabels()])
})

// ─────────────────────────────────────────────
// スヌーズプリセット定義
// ─────────────────────────────────────────────

interface SnoozePresetDef {
  key: InboxSnoozePreset
  i18nKey: string
}

const snoozePresets: SnoozePresetDef[] = [
  { key: 'in3h', i18nKey: 'inbox.snoozePreset.in3h' },
  { key: 'tonight', i18nKey: 'inbox.snoozePreset.tonight' },
  { key: 'tomorrowMorning', i18nKey: 'inbox.snoozePreset.tomorrowMorning' },
  { key: 'nextWeek', i18nKey: 'inbox.snoozePreset.nextWeek' },
]
</script>

<template>
  <div>
    <!-- ヘッダー（状態タブ + ラベル管理ボタン + bulk 選択トグル） -->
    <div class="mb-4 flex items-center gap-2 border-b border-surface-200 dark:border-surface-700">
      <!-- 状態タブ -->
      <div
        role="tablist"
        :aria-label="t('inbox.title')"
        class="flex flex-1 gap-1"
      >
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          role="tab"
          :aria-selected="inboxStore.currentTab === tab.key ? 'true' : 'false'"
          :data-testid="`inbox-tab-${tab.key.toLowerCase()}`"
          class="flex items-center gap-2 px-4 py-2 text-sm font-medium transition-colors"
          :class="
            inboxStore.currentTab === tab.key
              ? 'border-b-2 border-primary text-primary'
              : 'text-surface-600 hover:text-surface-900 dark:text-surface-400'
          "
          @click="onTabChange(tab.key)"
        >
          <span>{{ t(tab.i18nKey) }}</span>
          <Badge
            v-if="tab.count > 0"
            :value="tab.count"
            severity="danger"
            class="!min-w-[1.25rem] !text-[0.625rem]"
          />
        </button>
      </div>
      <!-- ラベル管理ボタン -->
      <Button
        icon="pi pi-cog"
        text
        size="small"
        :title="t('inbox.label.manage')"
        :aria-label="t('inbox.label.manage')"
        data-testid="inbox-label-manage-btn"
        @click.stop="toggleLabelManager"
      />
      <!-- bulk 選択モードトグル -->
      <Button
        :icon="inboxStore.selectionMode ? 'pi pi-times' : 'pi pi-check-square'"
        text
        size="small"
        :title="inboxStore.selectionMode ? t('inbox.bulk.exit') : t('inbox.bulk.selectMode')"
        :aria-label="inboxStore.selectionMode ? t('inbox.bulk.exit') : t('inbox.bulk.selectMode')"
        data-testid="inbox-bulk-mode-btn"
        @click="inboxStore.toggleSelectionMode()"
      />
    </div>

    <!-- ラベル管理パネル（展開表示） -->
    <div
      v-if="showLabelManager"
      class="mb-4 rounded-xl border border-surface-200 bg-surface-50 p-4 dark:border-surface-700 dark:bg-surface-800"
      data-testid="inbox-label-manager-panel"
    >
      <InboxLabelManager />
    </div>

    <!-- bulk 操作バー（選択モード時） -->
    <div
      v-if="inboxStore.selectionMode"
      class="relative mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-primary/30 bg-primary/5 px-3 py-2 dark:bg-primary/10"
      data-testid="inbox-bulk-bar"
    >
      <span class="text-xs font-medium text-primary">
        {{ t('inbox.bulk.selected', { count: inboxStore.selectedKeys.size }) }}
      </span>
      <div class="flex items-center gap-1">
        <!-- アーカイブ -->
        <Button
          :label="t('inbox.bulk.archive')"
          icon="pi pi-inbox"
          size="small"
          text
          :disabled="inboxStore.selectedKeys.size === 0"
          data-testid="inbox-bulk-archive-btn"
          @click="onBulkArchive"
        />
        <!-- スヌーズ -->
        <div class="relative">
          <Button
            :label="t('inbox.bulk.snooze')"
            icon="pi pi-clock"
            size="small"
            text
            :disabled="inboxStore.selectedKeys.size === 0"
            data-testid="inbox-bulk-snooze-btn"
            @click.stop="toggleBulkSnoozePanel"
          />
          <div
            v-if="showBulkSnoozePanel"
            class="absolute left-0 top-full z-20 min-w-[12rem] rounded-lg border border-surface-200 bg-white p-2 shadow-lg dark:border-surface-700 dark:bg-surface-900"
          >
            <button
              v-for="preset in snoozePresets"
              :key="preset.key"
              type="button"
              class="block w-full rounded px-2 py-1.5 text-left text-sm hover:bg-surface-100 dark:hover:bg-surface-800"
              @click="onBulkSnooze(preset.key)"
            >
              {{ t(preset.i18nKey) }}
            </button>
          </div>
        </div>
        <!-- ラベル付与 -->
        <div class="relative">
          <Button
            :label="t('inbox.bulk.addLabel')"
            icon="pi pi-tag"
            size="small"
            text
            :disabled="inboxStore.selectedKeys.size === 0"
            data-testid="inbox-bulk-label-btn"
            @click.stop="toggleBulkLabelPanel"
          />
          <div
            v-if="showBulkLabelPanel"
            class="absolute left-0 top-full z-20 min-w-[12rem] rounded-lg border border-surface-200 bg-white p-2 shadow-lg dark:border-surface-700 dark:bg-surface-900"
          >
            <p
              v-if="inboxStore.labels.length === 0"
              class="px-2 py-1 text-xs text-surface-400"
            >
              {{ t('inbox.label.empty') }}
            </p>
            <button
              v-for="label in inboxStore.labels"
              :key="label.id"
              type="button"
              class="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm hover:bg-surface-100 dark:hover:bg-surface-800"
              @click="onBulkAddLabel(label)"
            >
              <span
                class="inline-block h-2.5 w-2.5 rounded-full"
                :style="{ backgroundColor: label.color ?? '#94a3b8' }"
              />
              {{ label.name }}
            </button>
          </div>
        </div>
      </div>
      <!-- キャンセル -->
      <Button
        :label="t('inbox.bulk.cancel')"
        size="small"
        text
        severity="secondary"
        class="ml-auto"
        data-testid="inbox-bulk-cancel-btn"
        @click="inboxStore.toggleSelectionMode()"
      />
    </div>

    <!-- サブフィルタ：緊急度チップ -->
    <div class="mb-2 flex flex-wrap items-center gap-2">
      <span class="text-xs text-surface-500">{{ t('inbox.filter.priority') }}:</span>
      <button
        v-for="priority in allPriorities"
        :key="priority"
        type="button"
        class="rounded-full border px-2 py-0.5 text-xs transition-colors"
        :class="
          inboxStore.priorityFilter.includes(priority)
            ? 'border-primary bg-primary/10 text-primary'
            : 'border-surface-300 text-surface-600 hover:border-primary dark:border-surface-600 dark:text-surface-400'
        "
        @click="togglePriority(priority)"
      >
        {{ t(priorityI18nKey(priority)) }}
      </button>
    </div>

    <!-- サブフィルタ：種類チップ -->
    <div class="mb-2 flex flex-wrap items-center gap-2">
      <span class="text-xs text-surface-500">{{ t('inbox.filter.source') }}:</span>
      <button
        v-for="sourceType in allSourceTypes"
        :key="sourceType"
        type="button"
        class="flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs transition-colors"
        :class="
          inboxStore.sourceTypeFilter.includes(sourceType)
            ? 'border-primary bg-primary/10 text-primary'
            : 'border-surface-300 text-surface-600 hover:border-primary dark:border-surface-600 dark:text-surface-400'
        "
        @click="toggleSourceType(sourceType)"
      >
        <i :class="sourceTypeIcon(sourceType)" class="text-[0.65rem]" />
        <span>{{ t(sourceTypeI18nKey(sourceType)) }}</span>
      </button>
    </div>

    <!-- サブフィルタ：ラベル絞り込みチップ -->
    <div
      v-if="inboxStore.labels.length > 0"
      class="mb-4 flex flex-wrap items-center gap-2"
    >
      <span class="text-xs text-surface-500">{{ t('inbox.label.filterTitle') }}:</span>
      <button
        v-for="label in inboxStore.labels"
        :key="label.id"
        type="button"
        class="flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs transition-colors"
        :style="
          inboxStore.labelFilter === label.id
            ? { borderColor: label.color ?? '#94a3b8', backgroundColor: `${label.color ?? '#94a3b8'}22`, color: label.color ?? '#94a3b8' }
            : {}
        "
        :class="
          inboxStore.labelFilter !== label.id
            ? 'border-surface-300 text-surface-600 hover:border-primary dark:border-surface-600 dark:text-surface-400'
            : ''
        "
        :data-testid="`inbox-label-filter-${label.id}`"
        @click="toggleLabelFilter(label.id)"
      >
        <span
          class="inline-block h-2 w-2 rounded-full"
          :style="{ backgroundColor: label.color ?? '#94a3b8' }"
        />
        {{ label.name }}
      </button>
    </div>

    <!-- ローディング -->
    <div v-if="inboxStore.loading && inboxStore.items.length === 0" class="py-8 text-center">
      <i class="pi pi-spin pi-spinner text-2xl text-primary" />
    </div>

    <!-- 一覧 -->
    <div v-else-if="inboxStore.items.length > 0" class="divide-y divide-surface-100 dark:divide-surface-700">
      <div
        v-for="item in inboxStore.items"
        :key="item.id"
        class="flex items-start gap-3 py-3"
        :class="{ 'opacity-60': item.state === 'ARCHIVED' || item.state === 'READ' }"
      >
        <!-- bulk 選択チェックボックス（選択モード時） -->
        <div v-if="inboxStore.selectionMode" class="mt-1 shrink-0">
          <input
            type="checkbox"
            class="h-4 w-4 cursor-pointer rounded border-surface-300 accent-primary"
            :checked="inboxStore.selectedKeys.has(item.id)"
            :aria-label="item.title"
            :data-testid="`inbox-select-${item.id}`"
            @change="inboxStore.toggleSelect(item.id)"
          >
        </div>

        <!-- ソース種類アイコン（通常モード時） -->
        <div v-else class="mt-1 shrink-0">
          <i
            :class="sourceTypeIcon(item.sourceType)"
            class="text-base text-surface-500"
            :title="t(sourceTypeI18nKey(item.sourceType))"
          />
        </div>

        <!-- 本文 -->
        <div class="min-w-0 flex-1">
          <div class="flex items-start justify-between gap-2">
            <div class="min-w-0">
              <!-- タイトル -->
              <div class="flex items-center gap-2">
                <NuxtLink
                  v-if="item.actionUrl"
                  :to="item.actionUrl"
                  class="line-clamp-1 text-sm font-medium hover:text-primary"
                >
                  {{ item.title }}
                </NuxtLink>
                <p v-else class="line-clamp-1 text-sm font-medium">{{ item.title }}</p>
                <!-- Phase 3: グループ件数バッジ（groupCount > 1 のとき表示） -->
                <span
                  v-if="(item.groupCount ?? 1) > 1"
                  class="inline-flex shrink-0 items-center rounded-full bg-primary/10 px-1.5 py-0.5 text-[0.6rem] font-medium text-primary"
                  :title="t('inbox.group.badgeTitle', { n: item.groupCount })"
                  :data-testid="`inbox-group-badge-${item.id}`"
                >
                  {{ t('inbox.group.badge', { n: item.groupCount }) }}
                </span>
              </div>

              <!-- 抜粋 -->
              <p v-if="item.excerpt" class="mt-0.5 line-clamp-2 text-xs text-surface-500">
                {{ item.excerpt }}
              </p>

              <!-- メタ情報 -->
              <div class="mt-1 flex flex-wrap items-center gap-2">
                <!-- 緊急度バッジ -->
                <Badge
                  v-if="item.priority === 'URGENT' || item.priority === 'HIGH'"
                  :value="t(priorityI18nKey(item.priority))"
                  :severity="prioritySeverity(item.priority)"
                  class="!text-[0.6rem]"
                />
                <!-- スコープ -->
                <span v-if="item.scope?.name" class="text-xs text-surface-400">
                  {{ item.scope.name }}
                </span>
                <!-- 発生日時 -->
                <span class="text-xs text-surface-400">
                  {{ relativeTime(item.occurredAt) }}
                </span>
                <!-- スヌーズ解除時刻 -->
                <span v-if="item.state === 'SNOOZED' && item.snoozedUntil" class="text-xs text-amber-600">
                  <i class="pi pi-clock" /> {{ relativeTime(item.snoozedUntil) }}
                </span>
              </div>

              <!-- ラベルチップ（付与済み） -->
              <div v-if="item.labels.length > 0" class="mt-1.5 flex flex-wrap gap-1">
                <InboxLabelChip
                  v-for="label in item.labels"
                  :key="label.id"
                  :label="label"
                  removable
                  @remove="(lbl) => inboxStore.unassignLabel(item.sourceType, item.sourceId, lbl.id)"
                />
              </div>

              <!-- 提案チップ（wave3b: 点線枠・半透明で付与済みラベルと区別） -->
              <div
                v-if="item.suggestedLabels && item.suggestedLabels.length > 0"
                class="mt-1 flex flex-wrap gap-1"
                :data-testid="`inbox-suggestion-area-${item.id}`"
              >
                <InboxSuggestionChip
                  v-for="suggestion in item.suggestedLabels"
                  :key="suggestion.suggestionKey"
                  :suggestion="suggestion"
                  @apply="(s) => onSuggestionApply(item.sourceType, item.sourceId, s)"
                />
              </div>
            </div>

            <!-- アクションボタン群 -->
            <div class="flex shrink-0 items-center gap-1">
              <!-- ラベル付与ボタン -->
              <InboxLabelPicker :item="item" />

              <!-- スヌーズボタン（ARCHIVED でなければ表示） -->
              <div v-if="item.state !== 'ARCHIVED'" class="relative">
                <Button
                  icon="pi pi-clock"
                  text
                  size="small"
                  :title="t('inbox.action.snooze')"
                  :aria-label="t('inbox.action.snooze')"
                  :data-testid="`inbox-snooze-btn-${item.id}`"
                  @click.stop="onSnoozeToggle(item.id)"
                />
                <!-- スヌーズプリセットパネル -->
                <div
                  v-if="snoozeTargetId === item.id"
                  class="absolute right-0 top-full z-10 min-w-[12rem] rounded-lg border border-surface-200 bg-white p-2 shadow-lg dark:border-surface-700 dark:bg-surface-900"
                >
                  <p class="mb-1 px-2 text-xs font-medium text-surface-500">
                    {{ t('inbox.snoozeTitle') }}
                  </p>
                  <button
                    v-for="preset in snoozePresets"
                    :key="preset.key"
                    type="button"
                    class="block w-full rounded px-2 py-1.5 text-left text-sm hover:bg-surface-100 dark:hover:bg-surface-800"
                    @click="onSnoozePreset(item.sourceType, item.sourceId, preset.key)"
                  >
                    {{ t(preset.i18nKey) }}
                  </button>
                </div>
              </div>

              <!-- スヌーズ解除（SNOOZED のみ）-->
              <Button
                v-if="item.state === 'SNOOZED'"
                icon="pi pi-bell"
                text
                size="small"
                :title="t('inbox.action.unsnooze')"
                :aria-label="t('inbox.action.unsnooze')"
                :data-testid="`inbox-unsnooze-btn-${item.id}`"
                @click="onUnsnooze(item.sourceType, item.sourceId)"
              />

              <!-- アーカイブ / 解除 -->
              <Button
                v-if="item.state !== 'ARCHIVED'"
                icon="pi pi-inbox"
                text
                size="small"
                :title="t('inbox.action.archive')"
                :aria-label="t('inbox.action.archive')"
                :data-testid="`inbox-archive-btn-${item.id}`"
                @click="onArchive(item.sourceType, item.sourceId)"
              />
              <Button
                v-else
                icon="pi pi-undo"
                text
                size="small"
                :title="t('inbox.action.unarchive')"
                :aria-label="t('inbox.action.unarchive')"
                :data-testid="`inbox-unarchive-btn-${item.id}`"
                @click="onUnarchive(item.sourceType, item.sourceId)"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 空状態 -->
    <DashboardEmptyState
      v-else-if="!inboxStore.loading"
      data-testid="inbox-empty-state"
      icon="pi pi-inbox"
      :message="t('inbox.empty')"
    />

    <!-- もっと読む -->
    <div v-if="inboxStore.hasMore" class="mt-4 text-center">
      <Button
        :label="t('inbox.loadMore')"
        text
        size="small"
        :loading="inboxStore.loading"
        @click="onLoadMore"
      />
    </div>
  </div>
</template>
