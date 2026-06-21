<script setup lang="ts">
/**
 * F06.5 テーマ詳細＝配下エントリ一覧（§7 #3/#6・マスク適用）。
 *
 * エントリは BE 側でマスク適用済み（isMasked=true は structuredContent=null・maskedHint のみ）。
 * マスク中エントリは想起テスト導線、非マスクは編集導線を出す。
 */
import type { ReflectionThemeResponse, ReflectionEntryResponse } from '~/types/reflection'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const reflectionApi = useReflectionApi()
const router = useRouter()
const route = useRoute()

const themeId = computed(() => route.params.themeId as string)

const loading = ref(true)
const theme = ref<ReflectionThemeResponse | null>(null)
const entries = ref<ReflectionEntryResponse[]>([])

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
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-6">
    <div class="mb-5 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded :aria-label="t('reflection.nav.themes')" @click="router.push('/reflections/themes')" />
      <h1 class="min-w-0 flex-1 truncate text-xl font-bold">{{ theme?.title ?? t('reflection.title') }}</h1>
      <Button :label="t('reflection.entry.create')" icon="pi pi-plus" size="small" @click="createToday" />
    </div>

    <div v-if="loading" class="space-y-3">
      <Skeleton height="56px" />
      <Skeleton height="56px" />
    </div>

    <div v-else-if="entries.length === 0" class="rounded-xl border border-dashed border-surface-300 p-8 text-center dark:border-surface-600">
      <p class="mb-3 text-sm text-surface-500">{{ t('reflection.entry.empty') }}</p>
      <Button :label="t('reflection.entry.create')" icon="pi pi-plus" @click="createToday" />
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
  </div>
</template>
