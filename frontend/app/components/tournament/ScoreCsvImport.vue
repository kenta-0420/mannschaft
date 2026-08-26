<script setup lang="ts">
/**
 * F08.7 順位UI Wave2: CSV によるスコア一括取込 UI（管理者向け）。
 *
 * BE importScores は multipart/form-data の @RequestParam("file") を受け、
 * CSV（matchId,homeScore,awayScore[,...]）をパースして batchUpdateScores を実行する。
 * 取込成功後は親へ通知し、グリッド／順位表を再取得させる。
 *
 * テンプレートは現在の節の matchId 入りひな形をダウンロードできる（記入の手間を減らす）。
 * 取込失敗（不正フォーマット 400・権限 403/404・サーバ 500）は症状を隠さず i18n で提示する。
 */
import { downloadBlob } from '~/utils/downloadBlob'
import {
  buildCsvTemplate,
  extractStatus,
  isForbiddenError,
  type ScoreEntryRow,
} from '~/utils/tournamentScoreEntry'

const props = defineProps<{
  orgId: string
  tournamentId: number
  divisionId: number
  matchdayId: number
  /** テンプレート生成用の現在の入力行（matchId 一覧）。 */
  rows: ScoreEntryRow[]
}>()

const emit = defineEmits<{
  /** CSV 取込が成功した（親でグリッド／順位表を再取得する用）。 */
  (e: 'imported'): void
}>()

const { t } = useI18n()
const notification = useNotification()
const { importScores } = useTournamentBracket()

const selectedFile = ref<File | null>(null)
const importing = ref(false)

function onFileSelect(event: Event) {
  const target = event.target as HTMLInputElement
  selectedFile.value = target.files?.[0] ?? null
}

function onDownloadTemplate() {
  const csv = buildCsvTemplate(props.rows)
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  downloadBlob(blob, `score-template-${props.tournamentId}-${props.matchdayId}.csv`)
}

async function onImport() {
  if (importing.value || !selectedFile.value) return
  const formData = new FormData()
  formData.append('file', selectedFile.value)
  importing.value = true
  try {
    await importScores(
      props.orgId,
      props.tournamentId,
      props.divisionId,
      props.matchdayId,
      formData,
    )
    notification.success(t('tournament.scoreEntry.csv.imported'))
    selectedFile.value = null
    emit('imported')
  } catch (e) {
    const status = extractStatus(e)
    if (status === 400) {
      notification.error(t('tournament.scoreEntry.csv.invalidFormat'))
    } else if (isForbiddenError(e)) {
      notification.error(t('tournament.scoreEntry.forbidden'))
    } else {
      notification.error(t('tournament.scoreEntry.csv.importFailed'))
    }
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <div class="rounded-lg border border-surface-200 p-4" data-testid="score-csv-import">
    <h3 class="mb-1 text-sm font-semibold">{{ $t('tournament.scoreEntry.csv.title') }}</h3>
    <p class="mb-3 text-xs text-surface-500">{{ $t('tournament.scoreEntry.csv.formatHint') }}</p>

    <div class="flex flex-wrap items-center gap-2">
      <input
        type="file"
        accept=".csv,text/csv"
        class="text-xs"
        data-testid="score-csv-file-input"
        @change="onFileSelect"
      >
      <Button
        :label="$t('tournament.scoreEntry.csv.import')"
        icon="pi pi-upload"
        size="small"
        :disabled="!selectedFile"
        :loading="importing"
        data-testid="score-csv-import-button"
        @click="onImport"
      />
      <Button
        :label="$t('tournament.scoreEntry.csv.downloadTemplate')"
        icon="pi pi-download"
        size="small"
        outlined
        severity="secondary"
        data-testid="score-csv-template-button"
        @click="onDownloadTemplate"
      />
    </div>
  </div>
</template>
