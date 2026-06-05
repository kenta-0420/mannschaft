<script setup lang="ts">
/**
 * PDF / Excel 出力ボタン（F09.13 Phase 1-ε）。
 *
 * - props.packageId が指定された場合: 単独パッケージのエクスポート
 * - 未指定の場合: 一覧エクスポート（filter で絞り込み可能）
 *
 * SplitButton + メニューで「PDF / Excel」を選択。
 * Blob を取得 → Content-Disposition から filename を抽出 → ダウンロード。
 */
import type {
  ScopeName,
  WorkPackageExportFormat,
  WorkPackageListFilter,
} from '~/types/property'

const props = defineProps<{
  scope: ScopeName
  scopeId: string
  packageId?: number | null
  filter?: WorkPackageListFilter
  size?: 'small' | 'large' | undefined
}>()

const { t } = useI18n()
const { error: showError } = useNotification()
const api = usePropertyWorkPackageApi(props.scope, props.scopeId)

const isLoading = ref(false)

async function downloadAs(format: WorkPackageExportFormat) {
  if (isLoading.value) return
  isLoading.value = true
  try {
    let blob: Blob
    if (props.packageId) {
      blob = await api.exportSingle(props.packageId, format)
    } else {
      blob = await api.exportList(format, props.filter ?? {})
    }
    const url = URL.createObjectURL(blob)
    const ext = format === 'pdf' ? 'pdf' : 'xlsx'
    const fallback = props.packageId
      ? `property-history-${props.packageId}.${ext}`
      : `property-history.${ext}`
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = fallback
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
    URL.revokeObjectURL(url)
  } catch {
    showError(t('property.errors.exportFailed'))
  } finally {
    isLoading.value = false
  }
}

const items = computed(() => [
  {
    label: t('property.exportPdf'),
    icon: 'pi pi-file-pdf',
    command: () => downloadAs('pdf'),
  },
  {
    label: t('property.exportExcel'),
    icon: 'pi pi-file-excel',
    command: () => downloadAs('xlsx'),
  },
])

const buttonLabel = computed(() => {
  return props.packageId ? t('property.exportPdf') : t('property.exportList')
})
</script>

<template>
  <SplitButton
    :label="buttonLabel"
    :model="items"
    :loading="isLoading"
    icon="pi pi-download"
    severity="secondary"
    :size="size"
    data-testid="property-export-button"
    @click="downloadAs('pdf')"
  />
</template>
