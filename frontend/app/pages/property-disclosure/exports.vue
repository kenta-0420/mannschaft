<script setup lang="ts">
/**
 * 重要事項説明書（参考） 出力履歴一覧（F09.14 Phase 2-β-5）。
 *
 * URL: /property-disclosure/exports?organizationId=N
 *
 * - DataTable: createdAt / templateCodeSnapshot / outputFormat / sha256（短縮） / アクション
 * - ダウンロードボタン → GET /{exportId}/download → presigned URL → window.open
 * - SHA-256 検証失敗時（DISCLOSURE_010 / 503）はトーストでエラー通知
 */
import dayjs from 'dayjs'
import type { DisclosureExport } from '~/types/disclosure'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const { error: showError } = useNotification()
const { userTimezone } = useDatetime()

const organizationId = computed<string>(() => {
  const raw = route.query.organizationId
  return raw ? String(Array.isArray(raw) ? raw[0] : raw) : ''
})

const api = computed(() => useDisclosureApi(organizationId.value))

/**
 * 権限解決用の組織 **slug**。
 *
 * `useRoleAccess` が叩く `GET /api/v1/organizations/{id}/me/permissions` は
 * **slug 専用**で、数値 ID を渡すと 404 ORG_001 を返す（実測）。
 * 一方この画面のクエリ `?organizationId=N` は数値（`property-disclosure/index.vue` が
 * `Number(organizationId)` を前提に組み立てている）。
 * そのまま渡していたため権限取得が常に失敗し、`isAdmin` が恒常的に false となって
 * **「保管期限延長」ボタンが誰にも表示されなかった**。
 * ここで `/api/v1/me/organizations` を引いて数値 ID → slug を解決してから渡す。
 */
const organizationSlug = ref<string>('')

async function resolveOrganizationSlug(numericId: string): Promise<void> {
  const res = await useApi()<{ data: Array<{ id: number; slug: string }> }>('/api/v1/me/organizations')
  organizationSlug.value = (res.data ?? []).find((o) => String(o.id) === numericId)?.slug ?? ''
}

// ADMIN のみ「保管延長」ボタンを表示する（バックエンドでも認可される）
const { isAdmin, loadPermissions } = useRoleAccess('organization', organizationSlug)
watch(
  () => organizationId.value,
  async (id) => {
    if (!id) return
    // 解決に失敗しても画面は一覧まで描画する（症状を隠さずログに残し、権限依存 UI だけ出さない）。
    try {
      await resolveOrganizationSlug(id)
    }
    catch (e) {
      console.warn('[disclosure/exports] 組織 slug の解決に失敗しました（権限依存 UI は非表示になります）', e)
      organizationSlug.value = ''
      return
    }
    if (organizationSlug.value) loadPermissions()
  },
  { immediate: true },
)

// 保管期限延長ダイアログ制御
const extendDialogOpen = ref(false)
const extendTargetExport = ref<DisclosureExport | null>(null)

function openExtendDialog(item: DisclosureExport) {
  extendTargetExport.value = item
  extendDialogOpen.value = true
}

function onExtended(updated: DisclosureExport) {
  // 一覧の expiresAt を即時反映
  const idx = exports_.value.findIndex(e => e.exportId === updated.exportId)
  if (idx >= 0) {
    const cur = exports_.value[idx]
    if (cur) exports_.value[idx] = { ...cur, expiresAt: updated.expiresAt }
  }
}

const exports_ = ref<DisclosureExport[]>([])
const totalElements = ref(0)
const loading = ref(false)
const page = ref(0)
const size = ref(20)

async function load() {
  if (organizationId.value === '') return
  loading.value = true
  try {
    const res = await api.value.listExports({ page: page.value, size: size.value })
    exports_.value = res.data
    totalElements.value = res.meta?.total ?? res.data.length
  } catch {
    showError(t('disclosure.errors.loadFailed'))
  } finally {
    loading.value = false
  }
}

watch(() => organizationId.value, () => {
  page.value = 0
  load()
})

watch(page, () => load())

onMounted(load)

async function download(item: DisclosureExport) {
  try {
    const refreshed = await api.value.getExportDownloadUrl(item.exportId)
    if (refreshed.downloadUrl) {
      window.open(refreshed.downloadUrl, '_blank', 'noopener,noreferrer')
    } else {
      showError(t('disclosure.errors.downloadFailed'))
    }
  } catch (err) {
    const status = (err as { response?: { status?: number } })?.response?.status
    if (status === 503) {
      showError(t('disclosure.errors.tampered'))
    } else {
      showError(t('disclosure.errors.downloadFailed'))
    }
  }
}

function back() {
  navigateTo({
    path: '/property-disclosure',
    query: { organizationId: String(organizationId.value) },
  })
}

function formatDate(iso: string): string {
  if (!iso) return '-'
  const d = dayjs(iso)
  if (!d.isValid()) return iso
  return d.tz(userTimezone.value).format('YYYY/MM/DD HH:mm')
}

function shortSha(sha: string): string {
  if (!sha) return '-'
  return sha.length > 16 ? `${sha.slice(0, 8)}…${sha.slice(-8)}` : sha
}

function formatSeverity(format: string): 'info' | 'success' | 'secondary' {
  if (format === 'PDF') return 'info'
  if (format === 'EXCEL') return 'success'
  return 'secondary'
}
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <header class="flex flex-wrap items-center justify-between gap-3">
      <div>
        <Button
          icon="pi pi-arrow-left"
          :label="t('disclosure.back')"
          severity="secondary"
          text
          @click="back"
        />
        <PageHeader :title="t('disclosure.exportHistory')" class="mt-2" />
      </div>
    </header>

    <p
      v-if="organizationId === ''"
      class="rounded-md border border-dashed border-yellow-300 bg-yellow-50 p-4 text-sm text-yellow-700 dark:border-yellow-800 dark:bg-yellow-950 dark:text-yellow-200"
    >
      ?organizationId=N
    </p>

    <section v-else class="space-y-4">
      <div
        v-if="loading"
        class="rounded-md border border-surface-200 p-8 text-center text-sm text-surface-500 dark:border-surface-700"
      >
        {{ t('disclosure.loading') }}
      </div>

      <div
        v-else-if="exports_.length === 0"
        class="rounded-md border border-dashed border-surface-300 p-8 text-center text-sm text-surface-500 dark:border-surface-700"
        data-testid="disclosure-exports-empty"
      >
        {{ t('disclosure.noExports') }}
      </div>

      <DataTable
        v-else
        :value="exports_"
        striped-rows
        :data-key="'id'"
        responsive-layout="scroll"
        data-testid="disclosure-exports-table"
      >
        <Column :header="t('disclosure.fields.createdAt')" sortable>
          <template #body="{ data }: { data: DisclosureExport }">
            {{ formatDate(data.createdAt) }}
          </template>
        </Column>
        <Column field="templateCodeSnapshot" :header="t('disclosure.fields.templateCode')" />
        <Column :header="t('disclosure.fields.outputFormat')">
          <template #body="{ data }: { data: DisclosureExport }">
            <Tag
              :severity="formatSeverity(data.outputFormat)"
              :value="t(`disclosure.outputFormat.${data.outputFormat}`)"
            />
          </template>
        </Column>
        <Column :header="t('disclosure.fields.sha256')">
          <template #body="{ data }: { data: DisclosureExport }">
            <code class="text-xs">{{ shortSha(data.sha256) }}</code>
          </template>
        </Column>
        <Column :header="t('disclosure.fields.expiresAt')">
          <template #body="{ data }: { data: DisclosureExport }">
            <span data-testid="disclosure-expires-at">{{ data.expiresAt ? formatDate(data.expiresAt) : '-' }}</span>
          </template>
        </Column>
        <Column :header="''">
          <template #body="{ data }: { data: DisclosureExport }">
            <div class="flex flex-wrap gap-2">
              <Button
                icon="pi pi-download"
                :label="t('disclosure.actions.download')"
                size="small"
                severity="primary"
                :data-testid="`disclosure-download-${data.exportId}`"
                @click="download(data)"
              />
              <Button
                v-if="isAdmin"
                icon="pi pi-clock"
                :label="t('disclosure.extend_expiry')"
                size="small"
                severity="secondary"
                outlined
                :data-testid="`disclosure-extend-expiry-${data.exportId}`"
                @click="openExtendDialog(data)"
              />
            </div>
          </template>
        </Column>
      </DataTable>

      <ExtendExpiryDialog
        v-if="extendTargetExport"
        v-model:open="extendDialogOpen"
        :organization-id="organizationId"
        :export="extendTargetExport"
        @extended="onExtended"
      />

      <Paginator
        v-if="totalElements > size"
        v-model:first="page"
        :rows="size"
        :total-records="totalElements"
        :rows-per-page-options="[10, 20, 50]"
        @update:rows="(v: number) => (size = v)"
      />
    </section>
  </div>
</template>
