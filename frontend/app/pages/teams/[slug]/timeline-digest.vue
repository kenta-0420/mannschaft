<script setup lang="ts">
import type { DigestSummaryResponse } from '~/types/timeline-digest'
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const { team } = useTeamShellContext()
// URLと一致したシェルの内部IDだけを送る。未解決時に前スコープのIDを使わない。
const numericScopeId = computed(() => {
  const id = team.value?.numericId
  return team.value?.id === teamSlug.value && id != null && Number.isSafeInteger(id) && id > 0
    ? id
    : null
})
const { listDigests, deleteDigest, publishDigest, regenerateDigest } = useTimelineDigestApi()
const notification = useNotification()
const { t } = useI18n()
const { formatDate } = useDatetime()

const digests = ref<DigestSummaryResponse[]>([])
const loading = ref(true)
const loadFailed = ref(false)
let requestSeq = 0
const showGenerateDialog = ref(false)
const showConfigDialog = ref(false)

const styleLabels = computed(
  (): Record<string, string> => ({
    SUMMARY: t('timeline_digest.style_summary'),
    NARRATIVE: t('timeline_digest.style_narrative'),
    HIGHLIGHTS: t('timeline_digest.style_highlights'),
    TEMPLATE: t('timeline_digest.style_template'),
  }),
)

async function loadDigests() {
  const seq = ++requestSeq
  const scopeId = numericScopeId.value
  loading.value = true
  loadFailed.value = false
  if (scopeId == null) return
  try {
    const res = await listDigests('TEAM', scopeId)
    if (seq !== requestSeq || numericScopeId.value !== scopeId) return
    digests.value = res.data
  } catch {
    if (seq !== requestSeq || numericScopeId.value !== scopeId) return
    loadFailed.value = true
    notification.error(t('timeline_digest.error_load_list'))
  } finally {
    if (seq === requestSeq && numericScopeId.value === scopeId) loading.value = false
  }
}

async function onPublish(digest: DigestSummaryResponse) {
  try {
    await publishDigest(digest.id, {})
    notification.success(t('timeline_digest.success_publish'))
    await loadDigests()
  } catch {
    notification.error(t('timeline_digest.error_publish'))
  }
}

async function onRegenerate(digest: DigestSummaryResponse) {
  try {
    await regenerateDigest(digest.id)
    notification.success(t('timeline_digest.success_regenerate'))
    await loadDigests()
  } catch {
    notification.error(t('timeline_digest.error_regenerate'))
  }
}

async function onDelete(digest: DigestSummaryResponse) {
  try {
    await deleteDigest(digest.id)
    notification.success(t('timeline_digest.success_delete'))
    await loadDigests()
  } catch {
    notification.error(t('timeline_digest.error_delete'))
  }
}

function onGenerated() {
  loadDigests()
}

watch(
  [teamSlug, numericScopeId],
  () => {
    digests.value = []
    showGenerateDialog.value = false
    showConfigDialog.value = false
    void loadDigests()
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  requestSeq++
})
</script>

<template>
  <PageLoading v-if="!numericScopeId" />
  <div v-else>
    <div class="mb-6 flex items-center justify-between">
      <h1 class="text-2xl font-bold">{{ $t('timeline_digest.title') }}</h1>
      <div class="flex gap-2">
        <Button
          :label="$t('timeline_digest.button_config')"
          icon="pi pi-cog"
          severity="secondary"
          outlined
          @click="showConfigDialog = true"
        />
        <Button
          :label="$t('timeline_digest.button_generate')"
          icon="pi pi-bolt"
          @click="showGenerateDialog = true"
        />
      </div>
    </div>

    <DashboardErrorState v-if="loadFailed" @retry="loadDigests" />
    <DataTable v-else :value="digests" :loading="loading" data-key="id" striped-rows :rows="20">
      <template #empty>
        <div class="py-8 text-center text-surface-500">{{ $t('timeline_digest.empty') }}</div>
      </template>

      <Column :header="$t('timeline_digest.column_period')">
        <template #body="{ data }">
          {{ formatDate(data.periodStart) }} 〜 {{ formatDate(data.periodEnd) }}
        </template>
      </Column>

      <Column :header="$t('timeline_digest.column_style')">
        <template #body="{ data }">
          {{ styleLabels[data.digestStyle] ?? data.digestStyle }}
        </template>
      </Column>

      <Column :header="$t('timeline_digest.column_status')">
        <template #body="{ data }">
          <DigestStatusBadge :status="data.status" />
        </template>
      </Column>

      <Column :header="$t('timeline_digest.column_post_count')">
        <template #body="{ data }">
          {{ data.postCount }}
        </template>
      </Column>

      <Column :header="$t('timeline_digest.column_created_at')">
        <template #body="{ data }">
          {{ formatDate(data.createdAt) }}
        </template>
      </Column>

      <Column :header="$t('timeline_digest.column_actions')" style="width: 180px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              v-if="data.status === 'GENERATED'"
              v-tooltip.top="$t('timeline_digest.tooltip_publish')"
              icon="pi pi-upload"
              size="small"
              severity="primary"
              text
              rounded
              @click="onPublish(data)"
            />
            <Button
              v-tooltip.top="$t('timeline_digest.tooltip_regenerate')"
              icon="pi pi-refresh"
              size="small"
              severity="secondary"
              text
              rounded
              @click="onRegenerate(data)"
            />
            <Button
              v-tooltip.top="$t('timeline_digest.tooltip_delete')"
              icon="pi pi-trash"
              size="small"
              severity="danger"
              text
              rounded
              @click="onDelete(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <DigestGenerateDialog
      :key="numericScopeId"
      v-model:visible="showGenerateDialog"
      scope-type="TEAM"
      :scope-id="numericScopeId"
      @generated="onGenerated"
    />

    <Dialog
      v-model:visible="showConfigDialog"
      modal
      :header="$t('timeline_digest.dialog_config_title')"
      :style="{ width: '480px' }"
    >
      <DigestConfigForm :key="numericScopeId" scope-type="TEAM" :scope-id="numericScopeId" />
    </Dialog>
  </div>
</template>
