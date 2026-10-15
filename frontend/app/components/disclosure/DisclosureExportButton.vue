<script setup lang="ts">
/**
 * 重説書ドラフトの出力（PDF / Excel）ボタン（F09.14 Phase 2-β-5）。
 *
 * - SplitButton: メイン=PDF、ドロップダウン=Excel
 * - POST /disclosure-drafts/{id}/export?format=... → DisclosureExport を取得
 * - downloadUrl で window.open（presigned URL は短期有効）
 * - warnings 配列があれば Toast で通知
 */
import type {
  DisclosureExport,
  DisclosureOutputFormat,
} from '~/types/disclosure'

const props = defineProps<{
  organizationId: number
  draftId: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  exported: [result: DisclosureExport]
}>()

const { t } = useI18n()
const { error: showError, success: showSuccess, warn: showWarn } = useNotification()

const isLoading = ref(false)
const api = computed(() => useDisclosureApi(String(props.organizationId)))

async function exportAs(format: DisclosureOutputFormat) {
  if (isLoading.value || props.disabled) return
  isLoading.value = true
  try {
    const result = await api.value.exportDraft(props.draftId, format)

    // 警告があれば最初に通知
    if (result.warnings && result.warnings.length > 0) {
      showWarn(
        t('disclosure.warnings.title'),
        result.warnings.join('\n'),
      )
    } else {
      showSuccess(t('disclosure.saved'))
    }

    // presigned URL で別タブダウンロード
    if (result.downloadUrl) {
      window.open(result.downloadUrl, '_blank', 'noopener,noreferrer')
    } else {
      showError(t('disclosure.errors.downloadFailed'))
    }

    emit('exported', result)
  } catch (err) {
    const status = (err as { response?: { status?: number } })?.response?.status
    if (status === 503) {
      showError(t('disclosure.errors.tampered'))
    } else {
      showError(t('disclosure.errors.exportFailed'))
    }
  } finally {
    isLoading.value = false
  }
}

const items = computed(() => [
  {
    label: t('disclosure.exportPdf'),
    icon: 'pi pi-file-pdf',
    command: () => exportAs('PDF'),
  },
  {
    label: t('disclosure.exportExcel'),
    icon: 'pi pi-file-excel',
    command: () => exportAs('EXCEL'),
  },
])
</script>

<template>
  <SplitButton
    :label="t('disclosure.exportPdf')"
    :model="items"
    :loading="isLoading"
    :disabled="disabled"
    icon="pi pi-download"
    severity="secondary"
    data-testid="disclosure-export-button"
    @click="exportAs('PDF')"
  />
</template>
