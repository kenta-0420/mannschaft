<script setup lang="ts">
/**
 * F06.5 Phase 3: アーカイブ閲覧ページ（§12・AC-42/43）。
 *
 * 学年×学期×教科のフォルダツリー（listArchiveFolders）を表示し、
 * フォルダクリック or 検索フォームで searchArchive → 結果一覧（ページング）。
 * 各テーマから詳細ページ（/reflections/themes/[id]）へ遷移。
 *
 * 導線: /reflections/themes の PageHeader #actions アイコン経由。
 */
import type {
  ReflectionThemeResponse,
  ArchiveFolderResponse,
  ArchiveSearchParams,
} from '~/types/reflection'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const reflectionApi = useReflectionApi()
const router = useRouter()
const confirm = useConfirm()

// ─── フォルダ一覧 ────────────────────────────────────────────────────────────

const foldersLoading = ref(true)
const folders = ref<ArchiveFolderResponse[]>([])

// ─── 検索条件 ────────────────────────────────────────────────────────────────

const searchParams = ref<ArchiveSearchParams>({
  academicYear: null,
  termLabel: null,
  subjectName: null,
  keyword: null,
  archived: true,
  page: 0,
  size: 20,
})

// ─── 検索結果 ────────────────────────────────────────────────────────────────

const searchLoading = ref(false)
const themes = ref<ReflectionThemeResponse[]>([])
const totalElements = ref(0)
const totalPages = ref(0)
const currentPage = ref(0)

// ─── 復元 ────────────────────────────────────────────────────────────────────

const restoringId = ref<string | null>(null)

// ─── 初期ロード ──────────────────────────────────────────────────────────────

onMounted(async () => {
  await Promise.all([loadFolders(), doSearch()])
})

async function loadFolders() {
  foldersLoading.value = true
  try {
    const res = await reflectionApi.listArchiveFolders()
    folders.value = res.data ?? []
  }
  catch {
    notification.error(t('reflection.common.load_failed'))
  }
  finally {
    foldersLoading.value = false
  }
}

async function doSearch(page = 0) {
  searchLoading.value = true
  currentPage.value = page
  try {
    const res = await reflectionApi.searchArchive({ ...searchParams.value, page })
    const paged = res.data
    themes.value = paged?.content ?? []
    totalElements.value = paged?.totalElements ?? 0
    totalPages.value = paged?.totalPages ?? 0
  }
  catch {
    notification.error(t('reflection.common.load_failed'))
  }
  finally {
    searchLoading.value = false
  }
}

function applyFolder(folder: ArchiveFolderResponse) {
  searchParams.value.academicYear = folder.academicYear
  searchParams.value.termLabel = folder.termLabel
  searchParams.value.subjectName = folder.subjectName
  searchParams.value.keyword = null
  doSearch(0)
}

function clearFilter() {
  searchParams.value = {
    academicYear: null,
    termLabel: null,
    subjectName: null,
    keyword: null,
    archived: true,
    page: 0,
    size: 20,
  }
  doSearch(0)
}

function onPageChange(event: { page: number }) {
  doSearch(event.page)
}

// ─── 復元 ────────────────────────────────────────────────────────────────────

function confirmRestore(theme: ReflectionThemeResponse) {
  confirm.require({
    message: t('reflection.archive.confirm.restore'),
    header: t('reflection.archive.action.restore'),
    icon: 'pi pi-refresh',
    rejectLabel: t('reflection.common.cancel'),
    acceptLabel: t('reflection.archive.action.restore'),
    accept: () => doRestore(theme),
  })
}

async function doRestore(theme: ReflectionThemeResponse) {
  if (!theme.id) return
  restoringId.value = theme.id
  try {
    await reflectionApi.restoreTheme(theme.id)
    notification.success(t('reflection.archive.action.restore') + ' ✓')
    await Promise.all([loadFolders(), doSearch(currentPage.value)])
  }
  catch {
    notification.error(t('reflection.entry.save_failed'))
  }
  finally {
    restoringId.value = null
  }
}

// ─── 一括アーカイブ ──────────────────────────────────────────────────────────

const bulkArchiving = ref(false)

function confirmBulkArchive(folder: ArchiveFolderResponse) {
  confirm.require({
    message: t('reflection.archive.confirm.archive'),
    header: t('reflection.archive.bulk_archive_button'),
    icon: 'pi pi-inbox',
    rejectLabel: t('reflection.common.cancel'),
    acceptLabel: t('reflection.archive.bulk_archive_button'),
    accept: () => doBulkArchive(folder),
  })
}

async function doBulkArchive(folder: ArchiveFolderResponse) {
  bulkArchiving.value = true
  try {
    const res = await reflectionApi.bulkArchive({
      academicYear: folder.academicYear,
      termLabel: folder.termLabel,
      subjectName: folder.subjectName,
    })
    const count = res.data?.archivedCount ?? 0
    notification.success(`${t('reflection.archive.bulk_archive_button')} ✓ (${count}件)`)
    await Promise.all([loadFolders(), doSearch(currentPage.value)])
  }
  catch {
    notification.error(t('reflection.entry.save_failed'))
  }
  finally {
    bulkArchiving.value = false
  }
}

// ─── フォルダ表示ラベル生成 ──────────────────────────────────────────────────

function folderLabel(folder: ArchiveFolderResponse): string {
  const year = folder.academicYear != null ? String(folder.academicYear) : t('reflection.archive.folder.no_year')
  const term = folder.termLabel ?? t('reflection.archive.folder.no_term')
  const subject = folder.subjectName ?? t('reflection.archive.folder.no_subject')
  return `${year} / ${term} / ${subject}`
}

// ─── active filter badge ─────────────────────────────────────────────────────

const hasFilter = computed(() =>
  searchParams.value.academicYear != null
  || !!searchParams.value.termLabel
  || !!searchParams.value.subjectName
  || !!searchParams.value.keyword,
)
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-6">
    <PageHeader
      :title="t('reflection.archive.folder.title')"
      back-to="/reflections/themes"
      :back-label="t('reflection.nav.themes')"
    />

    <!-- フォルダ一覧 -->
    <SectionCard class="mb-6">
      <template #header>
        <h2 class="text-sm font-semibold text-surface-600 dark:text-surface-300">
          {{ t('reflection.archive.folder.title') }}
        </h2>
      </template>

      <div v-if="foldersLoading" class="space-y-2">
        <Skeleton height="40px" />
        <Skeleton height="40px" />
      </div>

      <div v-else-if="folders.length === 0" class="py-4 text-center text-sm text-surface-500">
        {{ t('reflection.archive.search.empty') }}
      </div>

      <div v-else class="space-y-1">
        <div
          v-for="(folder, i) in folders"
          :key="i"
          class="group flex cursor-pointer items-center justify-between rounded-lg px-3 py-2 hover:bg-surface-100 dark:hover:bg-surface-700"
          @click="applyFolder(folder)"
        >
          <div class="flex items-center gap-2 text-sm">
            <i class="pi pi-folder text-surface-400" />
            <span>{{ folderLabel(folder) }}</span>
            <span class="rounded bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500 dark:bg-surface-700">
              {{ t('reflection.archive.folder.theme_count', { n: folder.themeCount }) }}
            </span>
          </div>
          <Button
            v-tooltip.top="t('reflection.archive.bulk_archive_button')"
            icon="pi pi-inbox"
            text
            rounded
            size="small"
            severity="secondary"
            class="opacity-0 group-hover:opacity-100"
            :loading="bulkArchiving"
            :aria-label="t('reflection.archive.bulk_archive_button')"
            @click.stop="confirmBulkArchive(folder)"
          />
        </div>
      </div>
    </SectionCard>

    <!-- 検索フォーム -->
    <SectionCard class="mb-6">
      <template #header>
        <h2 class="text-sm font-semibold text-surface-600 dark:text-surface-300">
          {{ t('reflection.archive.search.placeholder') }}
        </h2>
      </template>

      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-xs font-medium text-surface-500">{{ t('reflection.theme.academic_year_label') }}</label>
          <InputNumber
            v-model="searchParams.academicYear"
            :placeholder="t('reflection.theme.academic_year_placeholder')"
            :use-grouping="false"
            :min="1900"
            :max="2100"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium text-surface-500">{{ t('reflection.theme.term_label_label') }}</label>
          <InputText
            v-model="searchParams.termLabel"
            :placeholder="t('reflection.theme.term_label_placeholder')"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium text-surface-500">{{ t('reflection.theme.subject_link_label') }}</label>
          <InputText
            v-model="searchParams.subjectName"
            :placeholder="t('reflection.theme.subject_link_placeholder')"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium text-surface-500">{{ t('reflection.archive.search.placeholder') }}</label>
          <InputText
            v-model="searchParams.keyword"
            :placeholder="t('reflection.archive.search.placeholder')"
            class="w-full"
          />
        </div>
      </div>

      <div class="mt-3 flex gap-2">
        <Button :label="t('button.search')" icon="pi pi-search" size="small" @click="doSearch(0)" />
        <Button
          v-if="hasFilter"
          :label="t('reflection.common.cancel')"
          icon="pi pi-times"
          size="small"
          severity="secondary"
          text
          @click="clearFilter"
        />
      </div>
    </SectionCard>

    <!-- 検索結果 -->
    <div v-if="searchLoading" class="space-y-2">
      <Skeleton height="64px" />
      <Skeleton height="64px" />
    </div>

    <SectionCard v-else-if="themes.length === 0" class="text-center">
      <p class="text-sm text-surface-500">{{ t('reflection.archive.search.empty') }}</p>
    </SectionCard>

    <div v-else class="space-y-2">
      <p class="text-xs text-surface-500">{{ totalElements }} 件</p>
      <div
        v-for="theme in themes"
        :key="theme.id"
        class="flex items-center gap-3 rounded-xl border border-surface-200 bg-surface-0 p-3 dark:border-surface-700 dark:bg-surface-800"
      >
        <div
          class="min-w-0 flex-1 cursor-pointer"
          @click="router.push(`/reflections/themes/${theme.id}`)"
        >
          <div class="flex items-center gap-2">
            <span class="truncate text-sm font-medium">{{ theme.title }}</span>
            <span class="rounded bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500 dark:bg-surface-700">
              {{ t(`reflection.source_type.${theme.sourceType}`) }}
            </span>
            <span
              v-if="theme.academicYear || theme.termLabel"
              class="rounded bg-blue-50 px-1.5 py-0.5 text-xs text-blue-600 dark:bg-blue-900/30 dark:text-blue-400"
            >
              {{ [theme.academicYear, theme.termLabel].filter(Boolean).join(' ') }}
            </span>
          </div>
          <p v-if="theme.description" class="mt-0.5 truncate text-xs text-surface-500">{{ theme.description }}</p>
          <p v-if="theme.archivedAt" class="mt-0.5 text-xs text-surface-400">
            <i class="pi pi-inbox mr-1" />{{ theme.archivedAt ? new Date(theme.archivedAt).toLocaleDateString() : '' }}
          </p>
        </div>
        <div class="flex flex-shrink-0 items-center gap-1">
          <Button
            v-tooltip.top="t('reflection.archive.action.restore')"
            icon="pi pi-refresh"
            text
            rounded
            severity="info"
            size="small"
            :loading="restoringId === theme.id"
            :aria-label="t('reflection.archive.action.restore')"
            @click="confirmRestore(theme)"
          />
          <Button
            icon="pi pi-chevron-right"
            text
            rounded
            severity="secondary"
            size="small"
            :aria-label="t('reflection.theme.open')"
            @click="router.push(`/reflections/themes/${theme.id}`)"
          />
        </div>
      </div>

      <!-- ページング -->
      <div v-if="totalPages > 1" class="mt-4 flex justify-center">
        <Paginator
          :rows="searchParams.size ?? 20"
          :total-records="totalElements"
          :first="currentPage * (searchParams.size ?? 20)"
          @page="onPageChange"
        />
      </div>
    </div>
  </div>
</template>
