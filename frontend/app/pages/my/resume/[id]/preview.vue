<script setup lang="ts">
/**
 * F01.10 マイページ履歴書・職務経歴書 — プレビューページ。
 *
 * クエリパラメータ:
 * - type: 'rirekisho' | 'shokumukeirekisho'
 * - format: 'pdf' | 'excel'
 *
 * /preview エンドポイントを呼び出して返ってきた byte を
 * iframe（PDF）または blob URL（Excel）で表示する。
 * 正式出力（ダウンロード）ボタンで /export エンドポイントを呼ぶ。
 */
import type { DocumentType, OutputFormat } from '~/types/resume'

const { t } = useI18n()
const route = useRoute()
const { previewResume, exportResume } = useResumeApi()
const { success, error } = useNotification()

definePageMeta({ middleware: 'auth' })

const resumeId = computed(() => route.params.id as string)
const type = computed(() => (route.query.type as DocumentType) ?? 'rirekisho')
const format = computed(() => (route.query.format as OutputFormat) ?? 'pdf')

useHead({
  title: () => `${t('common.resume.preview')} — ${typeLabel.value}`,
})

// === ラベル ===
const typeLabel = computed(() =>
  type.value === 'rirekisho'
    ? t('common.resume.rirekisho')
    : t('common.resume.shokumukeirekisho'),
)
const formatLabel = computed(() =>
  format.value === 'pdf' ? 'PDF' : 'Excel',
)

// === プレビュー状態 ===
const previewLoading = ref(true)
const previewError = ref(false)
const previewBlobUrl = ref<string | null>(null)
const exportLoading = ref(false)

// 書類種別選択
const previewTypeOptions = [
  { label: t('common.resume.rirekisho'), type: 'rirekisho' as DocumentType },
  { label: t('common.resume.shokumukeirekisho'), type: 'shokumukeirekisho' as DocumentType },
]

// === プレビュー生成 ===
async function loadPreview() {
  previewLoading.value = true
  previewError.value = false

  // 既存の blob URL を解放
  if (previewBlobUrl.value) {
    URL.revokeObjectURL(previewBlobUrl.value)
    previewBlobUrl.value = null
  }

  try {
    const blob = await previewResume(resumeId.value, type.value, format.value)
    previewBlobUrl.value = URL.createObjectURL(blob)
  }
  catch (e) {
    previewError.value = true
    error(t('common.resume.previewError'), String(e))
  }
  finally {
    previewLoading.value = false
  }
}

// === 正式出力 ===
async function handleExport() {
  exportLoading.value = true
  try {
    const res = await exportResume(resumeId.value, type.value, format.value)
    const a = document.createElement('a')
    a.href = res.data.downloadUrl
    a.download = res.data.fileName
    a.click()
    success(t('common.resume.exportSuccess'))
  }
  catch (e) {
    error(t('common.resume.exportError'), String(e))
  }
  finally {
    exportLoading.value = false
  }
}

// blob URL のクリーンアップ
onBeforeUnmount(() => {
  if (previewBlobUrl.value) {
    URL.revokeObjectURL(previewBlobUrl.value)
  }
})

// クエリが変わったら再取得
watch([type, format], loadPreview)

onMounted(loadPreview)
</script>

<template>
  <div class="flex h-screen flex-col">
    <!-- ツールバー -->
    <div class="flex flex-shrink-0 items-center justify-between gap-3 border-b border-surface-200 bg-surface-0 px-4 py-2 dark:border-surface-700 dark:bg-surface-900">
      <!-- 戻るボタン -->
      <NuxtLink
        :to="`/my/resume/${resumeId}`"
        class="flex items-center gap-1 text-sm text-surface-500 hover:text-primary"
      >
        <i class="pi pi-arrow-left" />
        {{ t('common.resume.backToEditor') }}
      </NuxtLink>

      <!-- 書類種別切替 -->
      <div class="flex flex-wrap items-center gap-2">
        <div class="flex rounded-lg border border-surface-200 dark:border-surface-700">
          <NuxtLink
            v-for="opt in previewTypeOptions"
            :key="opt.type"
            :to="`/my/resume/${resumeId}/preview?type=${opt.type}&format=${format}`"
            class="px-3 py-1.5 text-sm transition-colors"
            :class="type === opt.type
              ? 'bg-primary text-white rounded-lg'
              : 'text-surface-600 hover:bg-surface-100 dark:text-surface-300 dark:hover:bg-surface-800'"
          >
            {{ opt.label }}
          </NuxtLink>
        </div>

        <!-- フォーマット切替 -->
        <div class="flex rounded-lg border border-surface-200 dark:border-surface-700">
          <NuxtLink
            :to="`/my/resume/${resumeId}/preview?type=${type}&format=pdf`"
            class="px-3 py-1.5 text-sm transition-colors"
            :class="format === 'pdf'
              ? 'bg-primary text-white rounded-lg'
              : 'text-surface-600 hover:bg-surface-100 dark:text-surface-300 dark:hover:bg-surface-800'"
          >
            PDF
          </NuxtLink>
          <NuxtLink
            :to="`/my/resume/${resumeId}/preview?type=${type}&format=excel`"
            class="px-3 py-1.5 text-sm transition-colors"
            :class="format === 'excel'
              ? 'bg-primary text-white rounded-lg'
              : 'text-surface-600 hover:bg-surface-100 dark:text-surface-300 dark:hover:bg-surface-800'"
          >
            Excel
          </NuxtLink>
        </div>

        <!-- 正式出力ボタン -->
        <Button
          :label="`${formatLabel}ダウンロード（${typeLabel}）`"
          icon="pi pi-download"
          size="small"
          :loading="exportLoading"
          @click="handleExport"
        />

        <!-- 再読み込み -->
        <Button
          icon="pi pi-refresh"
          size="small"
          text
          rounded
          :aria-label="t('common.resume.preview')"
          :loading="previewLoading"
          @click="loadPreview"
        />
      </div>
    </div>

    <!-- プレビュー本体 -->
    <div class="flex-1 overflow-hidden bg-surface-100 dark:bg-surface-800">
      <!-- ローディング -->
      <div v-if="previewLoading" class="flex h-full items-center justify-center">
        <div class="flex flex-col items-center gap-3 text-surface-500">
          <i class="pi pi-spin pi-spinner text-4xl" />
          <span>{{ t('common.resume.previewLoading') }}</span>
        </div>
      </div>

      <!-- エラー -->
      <div v-else-if="previewError" class="flex h-full items-center justify-center">
        <div class="flex flex-col items-center gap-4 text-center">
          <i class="pi pi-exclamation-circle text-4xl text-red-400" />
          <p class="text-surface-600">{{ t('common.resume.previewError') }}</p>
          <Button
            :label="t('common.resume.preview')"
            icon="pi pi-refresh"
            @click="loadPreview"
          />
        </div>
      </div>

      <!-- PDF プレビュー（iframe） -->
      <iframe
        v-else-if="previewBlobUrl && format === 'pdf'"
        :src="previewBlobUrl"
        class="h-full w-full border-0"
        :title="`${typeLabel} プレビュー`"
      />

      <!-- Excel プレビュー（ダウンロード案内） -->
      <div
        v-else-if="previewBlobUrl && format === 'excel'"
        class="flex h-full items-center justify-center"
      >
        <div class="flex flex-col items-center gap-4 rounded-xl bg-surface-0 p-8 shadow dark:bg-surface-900">
          <i class="pi pi-file-excel text-5xl text-green-600" />
          <p class="text-lg font-semibold">{{ typeLabel }} Excel</p>
          <p class="text-sm text-surface-500">Excel ファイルのプレビューはブラウザで表示できません</p>
          <a
            :href="previewBlobUrl"
            :download="`${typeLabel}.xlsx`"
            class="no-underline"
          >
            <Button
              :label="t('common.resume.downloadExcel')"
              icon="pi pi-download"
            />
          </a>
        </div>
      </div>
    </div>
  </div>
</template>
