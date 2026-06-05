<script setup lang="ts">
import type { HandoverPack } from '~/types/repairPlanHandover'

const props = defineProps<{
  scopeType: string
  scopeId: string
  isAdmin: boolean
}>()

const { t } = useI18n()
const notification = useNotification()
const { formatDateTime } = useDatetime()
const { listPacks, getDownloadUrl, deletePack, generatePack } = useHandoverPackApi(
  props.scopeType,
  props.scopeId,
)

const packs = ref<HandoverPack[]>([])
const loading = ref(false)
const downloadingId = ref<string | null>(null)
const deletingId = ref<string | null>(null)
const regeneratingId = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    packs.value = await listPacks()
  } catch {
    notification.error(t('common.fetch_failed'))
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 親から再ロードをトリガーできるよう公開。 */
defineExpose({ reload: load })

async function handleDownload(pack: HandoverPack) {
  downloadingId.value = pack.id
  try {
    const res = await getDownloadUrl(pack.id)
    window.open(res.downloadUrl, '_blank', 'noopener,noreferrer')
  } catch {
    notification.error(t('common.fetch_failed'))
  } finally {
    downloadingId.value = null
  }
}

async function handleDelete(pack: HandoverPack) {
  deletingId.value = pack.id
  try {
    await deletePack(pack.id)
    packs.value = packs.value.filter((p) => p.id !== pack.id)
  } catch {
    notification.error(t('common.save_failed'))
  } finally {
    deletingId.value = null
  }
}

async function handleRegenerate(pack: HandoverPack) {
  regeneratingId.value = pack.id
  try {
    const newPack = await generatePack({ termId: pack.termId, piiLevel: pack.piiLevel, memo: pack.memo ?? undefined })
    // 一覧の先頭に追加
    packs.value = [newPack, ...packs.value]
  } catch {
    notification.error(t('common.save_failed'))
  } finally {
    regeneratingId.value = null
  }
}

function formatFileSize(bytes: number | null): string {
  if (bytes === null) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDate(iso: string | null): string {
  if (!iso) return '-'
  return formatDateTime(iso)
}

const statusSeverityMap: Record<string, 'info' | 'success' | 'danger'> = {
  GENERATING: 'info',
  READY: 'success',
  FAILED: 'danger',
}
</script>

<template>
  <SectionCard :title="$t('repair_plan.handover.history.title')">
    <PageLoading v-if="loading" />

    <DashboardEmptyState
      v-else-if="packs.length === 0"
      icon="pi pi-inbox"
      :message="$t('common.no_data')"
    />

    <div v-else class="overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-surface-200 text-left text-xs text-surface-500 dark:border-surface-700 dark:text-surface-400">
            <th class="pb-2 pr-4 font-medium">
              {{ $t('repair_plan.handover.history.col_generated_at') }}
            </th>
            <th class="pb-2 pr-4 font-medium">
              {{ $t('repair_plan.handover.history.col_status') }}
            </th>
            <th class="pb-2 pr-4 font-medium">
              {{ $t('repair_plan.handover.history.col_pii_level') }}
            </th>
            <th class="pb-2 pr-4 font-medium">
              {{ $t('repair_plan.handover.history.col_file_size') }}
            </th>
            <th class="pb-2 font-medium">
              {{ $t('repair_plan.handover.history.col_actions') }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="pack in packs"
            :key="pack.id"
            class="border-b border-surface-100 dark:border-surface-800"
          >
            <td class="py-2 pr-4 text-surface-700 dark:text-surface-300">
              {{ formatDate(pack.generatedAt) }}
            </td>
            <td class="py-2 pr-4">
              <Tag
                :severity="statusSeverityMap[pack.status]"
                :value="$t(`repair_plan.handover.status.${pack.status.toLowerCase()}`)"
              />
            </td>
            <td class="py-2 pr-4 text-surface-600 dark:text-surface-400">
              {{ pack.piiLevel === 'ANONYMIZED' ? $t('repair_plan.handover.builder.pii_anonymized') : $t('repair_plan.handover.builder.pii_standard') }}
            </td>
            <td class="py-2 pr-4 text-surface-600 dark:text-surface-400">
              {{ formatFileSize(pack.fileSizeBytes) }}
            </td>
            <td class="py-2">
              <div class="flex flex-wrap gap-2">
                <!-- READY: ダウンロード -->
                <Button
                  v-if="pack.status === 'READY'"
                  :label="$t('repair_plan.handover.builder.download_button')"
                  icon="pi pi-download"
                  size="small"
                  severity="secondary"
                  :loading="downloadingId === pack.id"
                  @click="handleDownload(pack)"
                />
                <!-- FAILED: 再生成 -->
                <Button
                  v-if="pack.status === 'FAILED'"
                  :label="$t('repair_plan.handover.builder.regenerate_button')"
                  icon="pi pi-refresh"
                  size="small"
                  severity="warn"
                  :loading="regeneratingId === pack.id"
                  @click="handleRegenerate(pack)"
                />
                <!-- ADMIN: 削除 -->
                <Button
                  v-if="isAdmin"
                  icon="pi pi-trash"
                  size="small"
                  severity="danger"
                  text
                  :loading="deletingId === pack.id"
                  :aria-label="$t('button.delete')"
                  @click="handleDelete(pack)"
                />
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </SectionCard>
</template>
