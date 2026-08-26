<script setup lang="ts">
/**
 * F17.2 Wave2 ⑦ 村史（行事アーカイブ）タブ — 村詳細 / 村史タブ（永続シェル子）
 *
 * 設計書: docs/features/F17.2_village_events_activation.md §7（村史の定義整理・2026-07-21 マスター裁定確定）
 *
 * 「村史」タブのラベル（`village.tab.chronicle`）は据え置きだが、指す中身は
 * 旧・月次統計（`VillageChronicleEntity`）から **行事アーカイブ**（`village_event_archives`。
 * 祭・歳時記・寄合の記録）へ差し替える（§7.1 確定）。
 *
 * 読み取り Controller（`GET /api/v1/villages/{villageId}/event-archives`・
 * `VillageEventArchiveController`）は BE 追補 #2448 で main 済み。型は
 * `app/types/village.ts` の手書き型を `village.contract.ts` で生成型
 * （`Schemas['VillageEventArchiveResponse']`）と SameKeys 照合登録済み。
 *
 * 永続シェル方式（SPA）: 村データ・権限・VillageHeader・アクションは親
 * `pages/villages/[id].vue` に集約。本ファイルは村史パネル本体（読み取り専用）のみ。
 */
import type {
  VillageEventArchiveResponse,
  VillageEventArchiveSourceType,
} from '~/types/village'
import { useVillageContext } from '~/composables/useVillageContext'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const villageId = computed(() => String(route.params.id))
const { t } = useI18n()
const villageApi = useVillageApi()
const { handleApiError } = useErrorHandler()
const { relativeTime } = useRelativeTime()

// 村史は読み取り専用パネル。閲覧可否は BE 側で村掲示板と同一の認可により判定され、
// 権限が無ければ 403 が返る。コンテキストは provide 存在の保証として参照する。
useVillageContext()

type SourceTypeFilter = VillageEventArchiveSourceType | 'ALL'

const ARCHIVE_PAGE_SIZE = 20

const archives = ref<VillageEventArchiveResponse[]>([])
const archivesLoading = ref(false)
const archivesLoadingMore = ref(false)
const archivesPage = ref(0)
const archivesHasMore = ref(false)
const sourceTypeFilter = ref<SourceTypeFilter>('ALL')

const sourceTypeFilterTabs: { value: SourceTypeFilter, i18nKey: string }[] = [
  { value: 'ALL', i18nKey: 'village.archive.filterAll' },
  { value: 'FESTIVAL', i18nKey: 'village.archive.sourceType.festival' },
  { value: 'CALENDAR_EVENT', i18nKey: 'village.archive.sourceType.calendarEvent' },
  { value: 'MEETUP', i18nKey: 'village.archive.sourceType.meetup' },
]

function sourceTypeLabel(sourceType: VillageEventArchiveSourceType): string {
  switch (sourceType) {
    case 'FESTIVAL':
      return t('village.archive.sourceType.festival')
    case 'CALENDAR_EVENT':
      return t('village.archive.sourceType.calendarEvent')
    case 'MEETUP':
      return t('village.archive.sourceType.meetup')
  }
}

async function loadArchives(opts: { reset: boolean }) {
  const page = opts.reset ? 0 : archivesPage.value + 1
  const loadingRef = opts.reset ? archivesLoading : archivesLoadingMore
  loadingRef.value = true
  try {
    const fetched = await villageApi.listEventArchives(villageId.value, {
      sourceType: sourceTypeFilter.value === 'ALL' ? undefined : sourceTypeFilter.value,
      page,
      size: ARCHIVE_PAGE_SIZE,
    })
    archives.value = opts.reset ? fetched : [...archives.value, ...fetched]
    archivesPage.value = page
    // BE はページ総数を返さない前提のため、直前ページが size 丁度ならまだ続きがあるとみなす。
    archivesHasMore.value = fetched.length === ARCHIVE_PAGE_SIZE
  }
  catch (error) {
    if (opts.reset) archives.value = []
    handleApiError(error, t('village.archive.loadFailed'))
  }
  finally {
    loadingRef.value = false
  }
}

function setSourceTypeFilter(value: SourceTypeFilter) {
  sourceTypeFilter.value = value
  void loadArchives({ reset: true })
}

function loadMoreArchives() {
  if (archivesLoadingMore.value || !archivesHasMore.value) return
  void loadArchives({ reset: false })
}

onMounted(() => {
  void loadArchives({ reset: true })
})
</script>

<template>
  <div class="mx-auto max-w-4xl p-4 sm:p-6">
    <div class="mb-4">
      <h2 class="text-xl font-bold">
        {{ t('village.archive.title') }}
      </h2>
      <p class="text-sm text-surface-500">
        {{ t('village.archive.subtitle') }}
      </p>
    </div>

    <div class="mb-4 flex gap-2 overflow-x-auto pb-1">
      <Button
        v-for="tab in sourceTypeFilterTabs"
        :key="tab.value"
        :label="t(tab.i18nKey)"
        size="small"
        :severity="sourceTypeFilter === tab.value ? 'primary' : 'secondary'"
        :outlined="sourceTypeFilter !== tab.value"
        @click="setSourceTypeFilter(tab.value)"
      />
    </div>

    <div v-if="archivesLoading" class="text-center py-12 text-surface-500">
      <i class="pi pi-spin pi-spinner text-2xl" />
    </div>
    <DashboardEmptyState
      v-else-if="archives.length === 0"
      icon="pi pi-book"
      :message="t('village.archive.empty')"
    />
    <div v-else class="flex flex-col gap-3">
      <div
        v-for="a in archives"
        :key="a.id"
        class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
      >
        <div class="flex gap-3">
          <div
            v-if="a.thumbnailUrl"
            class="h-16 w-16 shrink-0 overflow-hidden rounded bg-surface-100 dark:bg-surface-800"
          >
            <img :src="a.thumbnailUrl" :alt="a.title" class="h-full w-full object-cover" >
          </div>
          <div class="min-w-0 flex-1">
            <div class="flex items-center justify-between gap-2 flex-wrap">
              <h3 class="font-semibold">
                {{ a.title }}
              </h3>
              <Tag :value="sourceTypeLabel(a.sourceType)" severity="secondary" />
            </div>
            <p v-if="a.summary" class="mt-1 whitespace-pre-wrap text-sm text-surface-600 dark:text-surface-300">
              {{ a.summary }}
            </p>
            <p class="mt-1 text-xs text-surface-400">
              {{ t('village.archive.archivedAt') }}: {{ relativeTime(a.archivedAt) }}
            </p>
          </div>
        </div>
      </div>

      <Button
        v-if="archivesHasMore"
        :label="t('village.lobby.loadMore')"
        text
        size="small"
        :loading="archivesLoadingMore"
        class="self-center"
        @click="loadMoreArchives"
      />
    </div>
  </div>
</template>
