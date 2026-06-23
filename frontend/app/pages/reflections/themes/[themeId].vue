<script setup lang="ts">
/**
 * F06.5 テーマ詳細＝配下エントリ一覧（§7 #3/#6・マスク適用）。
 *
 * エントリは BE 側でマスク適用済み（isMasked=true は structuredContent=null・maskedHint のみ）。
 * マスク中エントリは想起テスト導線、非マスクは編集導線を出す。
 *
 * Phase 3 追加（§12）:
 * - アーカイブ/復元ボタン（PageHeader の #actions）。
 * - アーカイブ中バナー（アーカイブ済みテーマには復元導線を表示）。
 */
import type { ReflectionThemeResponse, ReflectionEntryResponse } from '~/types/reflection'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const reflectionApi = useReflectionApi()
const router = useRouter()
const route = useRoute()
const confirm = useConfirm()

const themeId = computed(() => route.params.themeId as string)

const loading = ref(true)
const archiving = ref(false)
const theme = ref<ReflectionThemeResponse | null>(null)
const entries = ref<ReflectionEntryResponse[]>([])

// Phase 3: アーカイブ済みかどうか
const isArchived = computed(() => !!theme.value?.archivedAt)

// 当日エントリ作成ダイアログ
const dialogVisible = ref(false)
const dialogEntry = ref<ReflectionEntryResponse | null>(null)

const today = computed(() => {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [themeRes, entriesRes] = await Promise.all([
      reflectionApi.getTheme(themeId.value),
      reflectionApi.listEntries(themeId.value),
    ])
    theme.value = themeRes.data
    entries.value = entriesRes.data ?? []
  }
  catch {
    notification.error(t('reflection.common.load_failed'))
  }
  finally {
    loading.value = false
  }
}

function openEntry(entry: ReflectionEntryResponse) {
  if (entry.isMasked) {
    router.push(`/reflections/recall?entry=${entry.id}`)
    return
  }
  router.push(`/reflections/entries/${entry.id}`)
}

function createToday() {
  dialogEntry.value = null
  dialogVisible.value = true
}

function onSaved() {
  dialogVisible.value = false
  load()
}

// Phase 3: アーカイブ確認
function confirmArchive() {
  confirm.require({
    message: t('reflection.archive.confirm.archive'),
    header: t('reflection.archive.action.archive'),
    icon: 'pi pi-archive',
    rejectLabel: t('reflection.common.cancel'),
    acceptLabel: t('reflection.archive.action.archive'),
    accept: doArchive,
  })
}

async function doArchive() {
  if (!theme.value?.id) return
  archiving.value = true
  try {
    const res = await reflectionApi.archiveTheme(theme.value.id)
    theme.value = res.data
    notification.success(t('reflection.archive.action.archive') + ' ✓')
    // アーカイブ後はテーマ一覧へ戻る（一覧から除外されるため）
    router.push('/reflections/themes')
  }
  catch {
    notification.error(t('reflection.entry.save_failed'))
  }
  finally {
    archiving.value = false
  }
}

// Phase 3: 復元確認
function confirmRestore() {
  confirm.require({
    message: t('reflection.archive.confirm.restore'),
    header: t('reflection.archive.action.restore'),
    icon: 'pi pi-refresh',
    rejectLabel: t('reflection.common.cancel'),
    acceptLabel: t('reflection.archive.action.restore'),
    accept: doRestore,
  })
}

async function doRestore() {
  if (!theme.value?.id) return
  archiving.value = true
  try {
    const res = await reflectionApi.restoreTheme(theme.value.id)
    theme.value = res.data
    notification.success(t('reflection.archive.action.restore') + ' ✓')
  }
  catch {
    notification.error(t('reflection.entry.save_failed'))
  }
  finally {
    archiving.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-6">
    <PageHeader
      :title="theme?.title ?? t('reflection.title')"
      back-to="/reflections/themes"
      :back-label="t('reflection.nav.themes')"
      class="flex-wrap justify-between"
    >
      <template #actions>
        <!-- Phase 3: アーカイブ/復元ボタン -->
        <Button
          v-if="!isArchived"
          v-tooltip.bottom="t('reflection.archive.action.archive')"
          icon="pi pi-inbox"
          text
          rounded
          severity="secondary"
          :loading="archiving"
          :aria-label="t('reflection.archive.action.archive')"
          @click="confirmArchive"
        />
        <Button
          v-else
          v-tooltip.bottom="t('reflection.archive.action.restore')"
          icon="pi pi-refresh"
          text
          rounded
          severity="info"
          :loading="archiving"
          :aria-label="t('reflection.archive.action.restore')"
          @click="confirmRestore"
        />
      </template>
      <Button v-if="!isArchived" :label="t('reflection.entry.create')" icon="pi pi-plus" size="small" @click="createToday" />
    </PageHeader>

    <!-- Phase 3: アーカイブ済みバナー -->
    <div
      v-if="isArchived"
      class="mb-4 flex items-center gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2 text-sm text-amber-700 dark:border-amber-700 dark:bg-amber-900/20 dark:text-amber-400"
    >
      <i class="pi pi-inbox" />
      <span>{{ t('reflection.archive.label') }}</span>
      <Button
        :label="t('reflection.archive.action.restore')"
        size="small"
        text
        class="ml-auto"
        @click="confirmRestore"
      />
    </div>

    <div v-if="loading" class="space-y-3">
      <Skeleton height="56px" />
      <Skeleton height="56px" />
    </div>

    <div v-else-if="entries.length === 0" class="rounded-xl border border-dashed border-surface-300 p-8 text-center dark:border-surface-600">
      <p class="mb-3 text-sm text-surface-500">{{ t('reflection.entry.empty') }}</p>
      <Button v-if="!isArchived" :label="t('reflection.entry.create')" icon="pi pi-plus" @click="createToday" />
    </div>

    <div v-else class="space-y-2">
      <div
        v-for="entry in entries"
        :key="entry.id"
        class="flex cursor-pointer items-center gap-3 rounded-xl border border-surface-200 bg-surface-0 p-3 dark:border-surface-700 dark:bg-surface-800"
        @click="openEntry(entry)"
      >
        <div class="min-w-0 flex-1">
          <span class="text-sm font-medium">{{ entry.targetDate }}</span>
          <div class="mt-1 flex items-center gap-2">
            <span v-if="entry.isMasked" class="inline-flex items-center gap-1 text-xs text-amber-600">
              <i class="pi pi-eye-slash" />{{ t('reflection.today.masked_badge') }}
            </span>
            <span v-else class="inline-flex items-center gap-1 text-xs text-green-600">
              <i class="pi pi-check-circle" />{{ t('reflection.today.has_entry') }}
            </span>
          </div>
        </div>
        <i class="pi pi-chevron-right text-surface-400" />
      </div>
    </div>

    <ReflectionEntryDialog
      v-model:visible="dialogVisible"
      :theme-id="themeId"
      :target-date="today"
      :entry="dialogEntry"
      @saved="onSaved"
    />
    <ConfirmDialog />
  </div>
</template>
