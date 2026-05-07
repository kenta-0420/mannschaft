<script setup lang="ts">
/**
 * F12.5 Phase 2-E — タイムラインビュー（仮想スクロール対応）。
 *
 * <p>vue-virtual-scroller の DynamicScroller でアイテム高さ可変の仮想化を実現。
 * スクロールが下端付近に達すると親に {@code load-more} を emit し、
 * cursor ベースの追加読み込みをトリガーする。</p>
 */
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
import type { TimelineItem } from '~/types/error-report'

const props = defineProps<{
  items: TimelineItem[]
  loading?: boolean
  hasMore?: boolean
}>()

const emit = defineEmits<{
  'load-more': []
}>()

const { t } = useI18n()

function formatDate(value: string): string {
  return new Date(value).toLocaleString()
}

function actorLabel(item: TimelineItem): string {
  if (item.actorName) return item.actorName
  if (item.systemActor) return t('error_report.timeline.system_actor')
  return t('error_report.timeline.deleted_actor')
}

function activityLabel(item: TimelineItem): string {
  if (!item.activityType) return ''
  return t(`error_report.timeline.activity.${item.activityType}`)
}

function describeMetadata(item: TimelineItem): string {
  if (!item.metadata) return ''
  const from = item.metadata.from
  const to = item.metadata.to
  if (from === undefined && to === undefined) return ''
  return `${from ?? '-'} → ${to ?? '-'}`
}

/**
 * DynamicScroller 用に key-field を持つアイテム配列に変換する。
 * occurredAt + index を結合した一意キーを付与する。
 */
interface KeyedTimelineItem extends TimelineItem {
  _key: string
}

const keyedItems = computed<KeyedTimelineItem[]>(() =>
  props.items.map((item, index) => ({
    ...item,
    _key: `${item.type}-${item.occurredAt}-${index}`,
  })),
)

/**
 * スクロール末端到達を検知して load-more を emit する。
 * RecycleScroller / DynamicScroller の `scroll-end` イベントを利用する。
 */
function onScrollEnd() {
  if (props.hasMore && !props.loading) {
    emit('load-more')
  }
}
</script>

<template>
  <section class="space-y-3">
    <div v-if="!loading && items.length === 0" class="py-6 text-center text-sm text-surface-500">
      {{ t('error_report.timeline.no_items') }}
    </div>
    <DynamicScroller
      v-else
      :items="keyedItems"
      :min-item-size="80"
      :buffer="200"
      key-field="_key"
      class="max-h-[70vh]"
      @scroll-end="onScrollEnd"
    >
      <template #default="{ item, index, active }: { item: KeyedTimelineItem; index: number; active: boolean }">
        <DynamicScrollerItem
          :item="item"
          :active="active"
          :data-index="index"
          :size-dependencies="[item.content, item.metadata]"
          :data-active="active"
        >
          <div
            class="mb-2 flex gap-3 rounded-lg border border-surface-200 bg-surface-0 p-3 text-sm dark:border-surface-700 dark:bg-surface-800"
          >
            <div class="flex shrink-0 flex-col items-center pt-1">
              <i
                v-if="item.type === 'OCCURRENCE'"
                class="pi pi-bolt text-orange-500"
                aria-hidden="true"
              />
              <i v-else class="pi pi-pencil text-blue-500" aria-hidden="true" />
            </div>
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-baseline gap-2">
                <span class="text-xs font-semibold">
                  <template v-if="item.type === 'OCCURRENCE'">
                    {{ t('error_report.timeline.occurrence') }}
                  </template>
                  <template v-else>{{ activityLabel(item) }}</template>
                </span>
                <span class="text-xs text-surface-500">{{ formatDate(item.occurredAt) }}</span>
                <span v-if="item.type === 'ACTIVITY'" class="text-xs text-surface-500">
                  by {{ actorLabel(item) }}
                </span>
              </div>
              <div
                v-if="item.type === 'OCCURRENCE'"
                class="mt-1 break-all text-xs text-surface-600 dark:text-surface-300"
              >
                {{ item.pageUrl }}
              </div>
              <div v-else class="mt-1 space-y-1">
                <div v-if="item.content" class="whitespace-pre-wrap text-sm">
                  {{ item.content }}
                </div>
                <div v-if="describeMetadata(item)" class="text-xs text-surface-500">
                  {{ describeMetadata(item) }}
                </div>
              </div>
            </div>
          </div>
        </DynamicScrollerItem>
      </template>
    </DynamicScroller>
    <div v-if="hasMore && !loading" class="text-center">
      <Button
        :label="t('error_report.timeline.load_more')"
        size="small"
        text
        @click="emit('load-more')"
      />
    </div>
    <div v-if="loading" class="py-2 text-center text-xs text-surface-500">
      <i class="pi pi-spin pi-spinner mr-1" aria-hidden="true" />
      …
    </div>
  </section>
</template>
