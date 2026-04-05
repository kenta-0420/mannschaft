<script setup lang="ts">
import type { DigestSummaryResponse } from '~/types/timeline-digest'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = computed(() => Number(route.params.id))
const { listDigests, deleteDigest, publishDigest, regenerateDigest } = useTimelineDigestApi()
const notification = useNotification()

const digests = ref<DigestSummaryResponse[]>([])
const loading = ref(false)
const showGenerateDialog = ref(false)
const showConfigDialog = ref(false)

const styleLabels: Record<string, string> = {
  SUMMARY: '要約',
  NARRATIVE: 'ナラティブ',
  HIGHLIGHTS: 'ハイライト',
  TEMPLATE: 'テンプレート',
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ja-JP', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

async function loadDigests() {
  loading.value = true
  try {
    const res = await listDigests()
    digests.value = res.data
  } catch {
    notification.error('ダイジェスト一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function onPublish(digest: DigestSummaryResponse) {
  try {
    await publishDigest(digest.id, {})
    notification.success('ダイジェストを公開しました')
    await loadDigests()
  } catch {
    notification.error('公開に失敗しました')
  }
}

async function onRegenerate(digest: DigestSummaryResponse) {
  try {
    await regenerateDigest(digest.id)
    notification.success('再生成を開始しました')
    await loadDigests()
  } catch {
    notification.error('再生成に失敗しました')
  }
}

async function onDelete(digest: DigestSummaryResponse) {
  try {
    await deleteDigest(digest.id)
    notification.success('ダイジェストを削除しました')
    await loadDigests()
  } catch {
    notification.error('削除に失敗しました')
  }
}

function onGenerated() {
  loadDigests()
}

onMounted(loadDigests)
</script>

<template>
  <div>
    <div class="mb-6 flex items-center justify-between">
      <h1 class="text-2xl font-bold">タイムラインダイジェスト</h1>
      <div class="flex gap-2">
        <Button
          label="設定"
          icon="pi pi-cog"
          severity="secondary"
          outlined
          @click="showConfigDialog = true"
        />
        <Button
          label="生成"
          icon="pi pi-bolt"
          @click="showGenerateDialog = true"
        />
      </div>
    </div>

    <DataTable
      :value="digests"
      :loading="loading"
      data-key="id"
      striped-rows
      :rows="20"
    >
      <template #empty>
        <div class="py-8 text-center text-surface-500">ダイジェストがありません</div>
      </template>

      <Column header="期間">
        <template #body="{ data }">
          {{ formatDate(data.periodStart) }} 〜 {{ formatDate(data.periodEnd) }}
        </template>
      </Column>

      <Column header="スタイル">
        <template #body="{ data }">
          {{ styleLabels[data.digestStyle] ?? data.digestStyle }}
        </template>
      </Column>

      <Column header="ステータス">
        <template #body="{ data }">
          <DigestStatusBadge :status="data.status" />
        </template>
      </Column>

      <Column header="投稿数">
        <template #body="{ data }">
          {{ data.postCount }}
        </template>
      </Column>

      <Column header="作成日">
        <template #body="{ data }">
          {{ formatDate(data.createdAt) }}
        </template>
      </Column>

      <Column header="アクション" style="width: 180px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              v-if="data.status === 'GENERATED'"
              icon="pi pi-upload"
              size="small"
              severity="primary"
              text
              rounded
              v-tooltip.top="'公開'"
              @click="onPublish(data)"
            />
            <Button
              icon="pi pi-refresh"
              size="small"
              severity="secondary"
              text
              rounded
              v-tooltip.top="'再生成'"
              @click="onRegenerate(data)"
            />
            <Button
              icon="pi pi-trash"
              size="small"
              severity="danger"
              text
              rounded
              v-tooltip.top="'削除'"
              @click="onDelete(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <DigestGenerateDialog
      v-model:visible="showGenerateDialog"
      scope-type="ORGANIZATION"
      :scope-id="orgId"
      @generated="onGenerated"
    />

    <Dialog
      v-model:visible="showConfigDialog"
      modal
      header="ダイジェスト設定"
      :style="{ width: '480px' }"
    >
      <DigestConfigForm
        scope-type="ORGANIZATION"
        :scope-id="orgId"
      />
    </Dialog>
  </div>
</template>
