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
 * - 各行に snooze（プリセット選択）/ archive / unarchive アイコン
 * - priority バッジ + sourceType アイコン
 * - オフセットページング（「もっと読む」）
 * - 楽観更新はストア側で実施
 */
import type { InboxPriority, InboxSourceType, InboxStateFilter } from '~/types/inbox'
import {
  priorityI18nKey,
  prioritySeverity,
  sourceTypeI18nKey,
  sourceTypeIcon,
} from '~/composables/useInboxApi'
import type { InboxSnoozePreset } from '~/stores/useInboxStore'

const { t } = useI18n()
const inboxStore = useInboxStore()
const notification = useNotification()
const { captureQuiet } = useErrorReport()
const { relativeTime } = useRelativeTime()

// ─────────────────────────────────────────────
// スヌーズオーバーレイ
// ─────────────────────────────────────────────

/** スヌーズプリセット選択パネルの表示対象アイテム ID。 */
const snoozeTargetId = ref<string | null>(null)

/**
 * スヌーズアイコンクリック → プリセット選択パネルを表示する。
 */
function openSnoozePanel(id: string) {
  snoozeTargetId.value = snoozeTargetId.value === id ? null : id
}

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
      notification.success(t('inbox.action.snooze') + ' ✓')
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
      notification.success(t('inbox.action.archive') + ' ✓')
    }
  } catch (error) {
    captureQuiet(error, { context: 'InboxList: アーカイブ' })
  }
}

async function onUnarchive(sourceType: InboxSourceType, sourceId: number) {
  try {
    const ok = await inboxStore.unarchive(sourceType, sourceId)
    if (ok) {
      notification.success(t('inbox.action.unarchive') + ' ✓')
    }
  } catch (error) {
    captureQuiet(error, { context: 'InboxList: アーカイブ解除' })
  }
}

async function onUnsnooze(sourceType: InboxSourceType, sourceId: number) {
  try {
    const ok = await inboxStore.unsnooze(sourceType, sourceId)
    if (ok) {
      notification.success(t('inbox.action.unsnooze') + ' ✓')
    }
  } catch (error) {
    captureQuiet(error, { context: 'InboxList: スヌーズ解除' })
  }
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
  await Promise.all([inboxStore.fetchSummary(), inboxStore.fetchInbox()])
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
    <!-- 状態タブ -->
    <div
      role="tablist"
      :aria-label="t('inbox.title')"
      class="mb-4 flex gap-1 border-b border-surface-200 dark:border-surface-700"
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
            : 'border-surface-300 text-surface-600 hover:border-primary'
        "
        @click="togglePriority(priority)"
      >
        {{ t(priorityI18nKey(priority)) }}
      </button>
    </div>

    <!-- サブフィルタ：種類チップ -->
    <div class="mb-4 flex flex-wrap items-center gap-2">
      <span class="text-xs text-surface-500">{{ t('inbox.filter.source') }}:</span>
      <button
        v-for="sourceType in allSourceTypes"
        :key="sourceType"
        type="button"
        class="flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs transition-colors"
        :class="
          inboxStore.sourceTypeFilter.includes(sourceType)
            ? 'border-primary bg-primary/10 text-primary'
            : 'border-surface-300 text-surface-600 hover:border-primary'
        "
        @click="toggleSourceType(sourceType)"
      >
        <i :class="sourceTypeIcon(sourceType)" class="text-[0.65rem]" />
        <span>{{ t(sourceTypeI18nKey(sourceType)) }}</span>
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
        <!-- ソース種類アイコン -->
        <div class="mt-1 shrink-0">
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
              <NuxtLink
                v-if="item.actionUrl"
                :to="item.actionUrl"
                class="line-clamp-1 text-sm font-medium hover:text-primary"
              >
                {{ item.title }}
              </NuxtLink>
              <p v-else class="line-clamp-1 text-sm font-medium">{{ item.title }}</p>

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
            </div>

            <!-- アクションボタン群 -->
            <div class="flex shrink-0 items-center gap-1">
              <!-- スヌーズボタン（ARCHIVED でなければ表示） -->
              <div v-if="item.state !== 'ARCHIVED'" class="relative">
                <Button
                  icon="pi pi-clock"
                  text
                  size="small"
                  :title="t('inbox.action.snooze')"
                  :aria-label="t('inbox.action.snooze')"
                  :data-testid="`inbox-snooze-btn-${item.id}`"
                  @click.stop="openSnoozePanel(item.id)"
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
