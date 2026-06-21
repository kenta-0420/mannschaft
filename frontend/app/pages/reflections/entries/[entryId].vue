<script setup lang="ts">
/**
 * F06.5 エントリ詳細（§7 #8）。
 *
 * - マスク中（isMasked=true）は本文非表示・想起テスト導線（recall へ誘導）。
 * - 非マスク（当日 or 開示済み）は本文表示・編集・ブログ輸出導線。
 */
import type { ReflectionEntryResponse } from '~/types/reflection'
import { useReflectionStructuredContent, type JsonNode } from '~/composables/useReflectionStructuredContent'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const reflectionApi = useReflectionApi()
const router = useRouter()
const route = useRoute()
const { toStructured } = useReflectionStructuredContent()

const entryId = computed(() => route.params.entryId as string)

const loading = ref(true)
const entry = ref<ReflectionEntryResponse | null>(null)

const dialogVisible = ref(false)
const exportDialogVisible = ref(false)
// ReflectionEntryResponse は exported_blog_post_id を露出しないため、輸出成功をセッション内で記録する。
const exported = ref(false)

const structured = computed(() =>
  entry.value?.structuredContent
    ? toStructured(entry.value.structuredContent as unknown as JsonNode)
    : null,
)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await reflectionApi.getEntry(entryId.value)
    entry.value = res.data
  }
  catch {
    notification.error(t('reflection.common.load_failed'))
  }
  finally {
    loading.value = false
  }
}

function goRecall() {
  router.push(`/reflections/recall?entry=${entryId.value}`)
}

function openEdit() {
  dialogVisible.value = true
}

function onSaved(updated: ReflectionEntryResponse) {
  entry.value = updated
  dialogVisible.value = false
}

function onExported() {
  exportDialogVisible.value = false
  exported.value = true
}
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-6">
    <div class="mb-5 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded :aria-label="t('reflection.recall.back_to_entry')" @click="router.back()" />
      <h1 class="flex-1 text-xl font-bold">{{ entry?.targetDate ?? t('reflection.title') }}</h1>
    </div>

    <div v-if="loading" class="space-y-3">
      <Skeleton height="120px" />
    </div>

    <template v-else-if="entry">
      <!-- マスク中：本文非表示＋想起テスト導線 -->
      <div v-if="entry.isMasked" class="rounded-xl border border-amber-200 bg-amber-50 p-6 text-center dark:border-amber-700/50 dark:bg-amber-900/20">
        <i class="pi pi-eye-slash mb-3 text-3xl text-amber-500" />
        <p class="mb-1 font-medium">{{ entry.maskedHint?.themeTitle }}</p>
        <p class="mb-4 text-sm text-surface-500">{{ t('reflection.entry.masked_notice') }}</p>
        <Button :label="t('reflection.recall.heading')" icon="pi pi-bolt" @click="goRecall" />
      </div>

      <!-- 非マスク：本文＋編集／輸出 -->
      <template v-else>
        <div class="mb-4 flex items-center justify-end gap-2">
          <span v-if="exported" class="inline-flex items-center gap-1 text-xs text-surface-500">
            <i class="pi pi-bookmark" />{{ t('reflection.entry.exported_badge') }}
          </span>
          <Button :label="t('reflection.entry.edit')" icon="pi pi-pencil" size="small" severity="secondary" outlined @click="openEdit" />
          <Button
            :label="t('reflection.export.button')"
            icon="pi pi-upload"
            size="small"
            :disabled="exported"
            @click="exportDialogVisible = true"
          />
        </div>

        <div class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800">
          <ReflectionStructuredView v-if="structured" :content="structured" />
        </div>
      </template>
    </template>

    <ReflectionEntryDialog
      v-if="entry && !entry.isMasked && entry.themeId"
      v-model:visible="dialogVisible"
      :theme-id="entry.themeId"
      :target-date="entry.targetDate ?? ''"
      :entry="entry"
      @saved="onSaved"
    />

    <ReflectionExportDialog
      v-if="entry"
      v-model:visible="exportDialogVisible"
      :entry-id="entryId"
      @exported="onExported"
    />
  </div>
</template>
