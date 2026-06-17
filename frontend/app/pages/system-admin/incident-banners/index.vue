<script setup lang="ts">
/**
 * F12.5: システム管理者向け障害告知バナー管理ページ
 *
 * SYSTEM_ADMIN がシスアド手動オーサリング方式で障害告知バナーを管理する。
 * - バナーの作成・編集・公開/非公開切替・削除
 * - 自動検知候補（suggestions）からのバナー化
 * - 保存後にBEが自動翻訳（en/zh/ko/es/de）を生成する
 * - ダイアログ内でプレビュー表示（PrimeVue Message コンポーネント）
 *
 * 公開フロー: 作成は常に「下書き」保存 → 作成後にpublishトグルがONであれば
 * createBanner後にpublishBannerを呼ぶ（一覧からも publish/unpublish ボタンで切替可）。
 *
 * API: /api/v1/system-admin/incident-banners (ROLE_SYSTEM_ADMIN)
 */
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import DatePicker from 'primevue/datepicker'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import Textarea from 'primevue/textarea'
import ToggleSwitch from 'primevue/toggleswitch'

import type {
  IncidentBannerResponse,
  IncidentBannerRequest,
  IncidentSuggestionResponse,
} from '~/composables/useIncidentBannerAdmin'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const authStore = useAuthStore()
const bannerApi = useIncidentBannerAdmin()
const notification = useNotification()
const { formatDateTime } = useDatetime()

// SYSTEM_ADMIN 権限チェック
const isAllowed = computed(() => authStore.isSystemAdmin)

// =============================================================================
// バナー一覧データ
// =============================================================================

const banners = ref<IncidentBannerResponse[]>([])
const loading = ref(false)
const currentPage = ref(0)
const pageSize = ref(20)
const totalElements = ref(0)

async function load() {
  loading.value = true
  try {
    const res = await bannerApi.fetchList(currentPage.value, pageSize.value)
    banners.value = res.data ?? []
    totalElements.value = res.meta?.totalElements ?? 0
  } catch (err) {
    console.error('incident-banners/index.vue: load failed', err)
    notification.error(t('incident_banner.load_failed'))
    banners.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

// =============================================================================
// 作成・編集 Dialog
// =============================================================================

const dialogOpen = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<string | null>(null)
const saving = ref(false)

// フォーム状態
const formMessage = ref('')
const formLevel = ref<'INFO' | 'WARNING' | 'CRITICAL'>('INFO')
const formPagePattern = ref('')
const formStartsAt = ref<Date | null>(null)
const formEndsAt = ref<Date | null>(null)
const formPublishAfterSave = ref(false)

// 編集時に取得した翻訳一覧
const existingTranslations = ref<Array<{ language?: string; message?: string }>>([])

const MESSAGE_MAX = 500

const levelOptions = [
  { value: 'INFO', label: t('incident_banner.level.INFO') },
  { value: 'WARNING', label: t('incident_banner.level.WARNING') },
  { value: 'CRITICAL', label: t('incident_banner.level.CRITICAL') },
]

const messageError = computed<string | null>(() => {
  const v = formMessage.value.trim()
  if (!v) return t('incident_banner.form.message_required')
  if (v.length > MESSAGE_MAX) return t('incident_banner.form.message_too_long')
  return null
})

const canSave = computed(() => !messageError.value && !saving.value)

/** プレビュー用: level → PrimeVue severity */
function levelToSeverity(level: string): 'info' | 'warn' | 'error' {
  if (level === 'CRITICAL') return 'error'
  if (level === 'WARNING') return 'warn'
  return 'info'
}

/** level バッジ用 severity */
function levelBadgeSeverity(level?: string): 'info' | 'warn' | 'danger' | 'secondary' {
  if (level === 'CRITICAL') return 'danger'
  if (level === 'WARNING') return 'warn'
  if (level === 'INFO') return 'info'
  return 'secondary'
}

function openCreate(prefill?: { pagePattern?: string; level?: 'INFO' | 'WARNING' | 'CRITICAL' }) {
  dialogMode.value = 'create'
  editingId.value = null
  formMessage.value = ''
  formLevel.value = prefill?.level ?? 'INFO'
  formPagePattern.value = prefill?.pagePattern ?? ''
  formStartsAt.value = null
  formEndsAt.value = null
  formPublishAfterSave.value = false
  existingTranslations.value = []
  dialogOpen.value = true
}

async function openEdit(banner: IncidentBannerResponse) {
  dialogMode.value = 'edit'
  editingId.value = banner.id ?? null
  // 原文メッセージ: ja translation か translationsが無い場合は空
  const jaTrans = banner.translations?.find(tr => tr.language === (banner.originalLanguage ?? 'ja'))
  formMessage.value = jaTrans?.message ?? ''
  formLevel.value = (banner.level as 'INFO' | 'WARNING' | 'CRITICAL') ?? 'INFO'
  formPagePattern.value = banner.pagePattern ?? ''
  formStartsAt.value = banner.startsAt ? new Date(banner.startsAt) : null
  formEndsAt.value = banner.endsAt ? new Date(banner.endsAt) : null
  formPublishAfterSave.value = false
  existingTranslations.value = banner.translations ?? []
  dialogOpen.value = true
}

function closeDialog() {
  dialogOpen.value = false
  editingId.value = null
}

/** Date を ISO8601 文字列に変換（null は undefined を返す） */
function toIsoString(d: Date | null): string | undefined {
  return d ? d.toISOString() : undefined
}

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    const req: IncidentBannerRequest = {
      message: formMessage.value.trim(),
      level: formLevel.value,
      pagePattern: formPagePattern.value.trim() || '*',
      originalLanguage: 'ja',
      startsAt: toIsoString(formStartsAt.value),
      endsAt: toIsoString(formEndsAt.value),
    }

    if (dialogMode.value === 'create') {
      const res = await bannerApi.createBanner(req)
      notification.success(t('incident_banner.create_success'))
      // 作成後すぐ公開する場合
      if (formPublishAfterSave.value && res.data?.id) {
        await bannerApi.publishBanner(res.data.id)
        notification.success(t('incident_banner.publish_success'))
      }
    } else if (editingId.value) {
      await bannerApi.updateBanner(editingId.value, req)
      notification.success(t('incident_banner.update_success'))
    }

    closeDialog()
    await load()
  } catch (err) {
    console.error('incident-banners/index.vue: save failed', err)
    notification.error(t('incident_banner.save_failed'))
  } finally {
    saving.value = false
  }
}

// =============================================================================
// 公開 / 非公開
// =============================================================================

const publishingId = ref<string | null>(null)

async function togglePublish(banner: IncidentBannerResponse) {
  if (!banner.id) return
  publishingId.value = banner.id
  try {
    if (banner.published) {
      await bannerApi.unpublishBanner(banner.id)
      notification.success(t('incident_banner.unpublish_success'))
    } else {
      await bannerApi.publishBanner(banner.id)
      notification.success(t('incident_banner.publish_success'))
    }
    await load()
  } catch (err) {
    console.error('incident-banners/index.vue: togglePublish failed', err)
    notification.error(
      banner.published
        ? t('incident_banner.unpublish_failed')
        : t('incident_banner.publish_failed'),
    )
  } finally {
    publishingId.value = null
  }
}

// =============================================================================
// 削除
// =============================================================================

const deletingId = ref<string | null>(null)

async function deleteBanner(banner: IncidentBannerResponse) {
  if (!banner.id) return
  if (!confirm(t('incident_banner.delete_confirm'))) return
  deletingId.value = banner.id
  try {
    await bannerApi.deleteBanner(banner.id)
    notification.success(t('incident_banner.delete_success'))
    await load()
  } catch (err) {
    console.error('incident-banners/index.vue: deleteBanner failed', err)
    notification.error(t('incident_banner.delete_failed'))
  } finally {
    deletingId.value = null
  }
}

// =============================================================================
// 検知候補 (Suggestions)
// =============================================================================

const suggestions = ref<IncidentSuggestionResponse[]>([])
const loadingSuggestions = ref(false)
const suggestionsExpanded = ref(false)

async function loadSuggestions() {
  loadingSuggestions.value = true
  try {
    const res = await bannerApi.fetchSuggestions()
    suggestions.value = res.data ?? []
  } catch (err) {
    console.error('incident-banners/index.vue: loadSuggestions failed', err)
    notification.error(t('incident_banner.suggestions_load_failed'))
    suggestions.value = []
  } finally {
    loadingSuggestions.value = false
  }
}

function toggleSuggestions() {
  suggestionsExpanded.value = !suggestionsExpanded.value
  if (suggestionsExpanded.value && suggestions.value.length === 0) {
    loadSuggestions()
  }
}

/** severity → level マッピング（CRITICAL→CRITICAL, HIGH→WARNING） */
function severityToLevel(severity?: string): 'CRITICAL' | 'WARNING' | 'INFO' {
  if (severity === 'CRITICAL') return 'CRITICAL'
  if (severity === 'HIGH') return 'WARNING'
  return 'INFO'
}

function severityBadgeSeverity(severity?: string): 'danger' | 'warn' | 'info' {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'HIGH') return 'warn'
  return 'info'
}

function createFromSuggestion(suggestion: IncidentSuggestionResponse) {
  openCreate({
    pagePattern: suggestion.pagePattern,
    level: severityToLevel(suggestion.severity),
  })
}

// =============================================================================
// 表示ヘルパ
// =============================================================================

/** バナーの原文メッセージを返す（originalLanguage の translation を参照） */
function getBannerMessage(banner: IncidentBannerResponse): string {
  const lang = banner.originalLanguage ?? 'ja'
  const trans = banner.translations?.find(tr => tr.language === lang)
  return trans?.message ?? ''
}

/** 掲示期間の文字列化 */
function formatPeriod(banner: IncidentBannerResponse): string {
  const from = banner.startsAt ? formatDateTime(banner.startsAt) : t('incident_banner.period_immediate')
  const to = banner.endsAt ? formatDateTime(banner.endsAt) : t('incident_banner.period_manual')
  return `${from} 〜 ${to}`
}
</script>

<template>
  <div class="mx-auto max-w-screen-xl space-y-6 p-4">
    <!-- 権限チェック -->
    <div
      v-if="!isAllowed"
      class="flex flex-col items-center gap-3 rounded-xl border border-dashed border-surface-300 py-16 text-surface-400"
    >
      <i class="pi pi-lock text-4xl" aria-hidden="true" />
      <p class="text-sm">{{ t('village.creationRequest.noPermission') }}</p>
    </div>

    <template v-else>
      <!-- ヘッダー -->
      <header class="flex items-center justify-between">
        <div>
          <span
            class="rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-semibold text-red-600 dark:bg-red-900/30 dark:text-red-400"
          >
            SYSTEM ADMIN
          </span>
          <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
            {{ t('incident_banner.title') }}
          </h1>
        </div>
        <div class="flex items-center gap-2">
          <Button
            v-tooltip.left="'再読み込み'"
            icon="pi pi-refresh"
            text
            rounded
            :loading="loading"
            @click="load"
          />
          <Button
            :label="t('incident_banner.create_btn')"
            icon="pi pi-plus"
            @click="openCreate()"
          />
        </div>
      </header>

      <!-- ローディング -->
      <div v-if="loading" class="flex items-center justify-center py-12">
        <i class="pi pi-spin pi-spinner mr-2 text-2xl text-surface-400" aria-hidden="true" />
      </div>

      <!-- バナー一覧テーブル -->
      <DataTable
        v-else
        :value="banners"
        data-key="id"
        striped-rows
        class="text-sm"
      >
        <template #empty>
          <div class="flex flex-col items-center justify-center gap-3 py-12 text-surface-400">
            <i class="pi pi-inbox text-4xl" aria-hidden="true" />
            <p class="text-sm">{{ t('incident_banner.no_data') }}</p>
          </div>
        </template>

        <!-- レベル -->
        <Column :header="t('incident_banner.table.level')" style="width: 10rem">
          <template #body="{ data: row }: { data: IncidentBannerResponse }">
            <Tag :value="row.level" :severity="levelBadgeSeverity(row.level)" />
          </template>
        </Column>

        <!-- メッセージ（原文） -->
        <Column :header="t('incident_banner.table.message')" style="min-width: 16rem">
          <template #body="{ data: row }: { data: IncidentBannerResponse }">
            <span class="line-clamp-2 text-sm text-surface-700 dark:text-surface-200">
              {{ getBannerMessage(row) }}
            </span>
          </template>
        </Column>

        <!-- 対象ページ -->
        <Column :header="t('incident_banner.table.page_pattern')" style="width: 12rem">
          <template #body="{ data: row }: { data: IncidentBannerResponse }">
            <code class="rounded bg-surface-100 px-1 py-0.5 text-xs dark:bg-surface-700">
              {{ row.pagePattern ?? '*' }}
            </code>
          </template>
        </Column>

        <!-- 状態 -->
        <Column :header="t('incident_banner.table.status')" style="width: 8rem">
          <template #body="{ data: row }: { data: IncidentBannerResponse }">
            <Tag
              :value="row.published ? t('incident_banner.status.published') : t('incident_banner.status.draft')"
              :severity="row.published ? 'success' : 'secondary'"
            />
          </template>
        </Column>

        <!-- 掲示期間 -->
        <Column :header="t('incident_banner.table.period')" style="min-width: 14rem">
          <template #body="{ data: row }: { data: IncidentBannerResponse }">
            <span class="text-xs text-surface-500 dark:text-surface-400">
              {{ formatPeriod(row) }}
            </span>
          </template>
        </Column>

        <!-- 更新日時 -->
        <Column :header="t('incident_banner.table.updated_at')" style="width: 11rem">
          <template #body="{ data: row }: { data: IncidentBannerResponse }">
            <span class="text-xs text-surface-500 dark:text-surface-400">
              {{ row.updatedAt ? formatDateTime(row.updatedAt) : '-' }}
            </span>
          </template>
        </Column>

        <!-- 操作 -->
        <Column :header="t('incident_banner.table.actions')" style="width: 16rem">
          <template #body="{ data: row }: { data: IncidentBannerResponse }">
            <div class="flex flex-wrap items-center gap-1">
              <Button
                :label="t('incident_banner.cancel_btn')"
                icon="pi pi-pencil"
                size="small"
                severity="secondary"
                @click="openEdit(row)"
              />
              <Button
                v-if="!row.published"
                :label="t('incident_banner.publish_btn')"
                icon="pi pi-eye"
                size="small"
                severity="success"
                :loading="publishingId === row.id"
                @click="togglePublish(row)"
              />
              <Button
                v-else
                :label="t('incident_banner.unpublish_btn')"
                icon="pi pi-eye-slash"
                size="small"
                severity="warning"
                :loading="publishingId === row.id"
                @click="togglePublish(row)"
              />
              <Button
                :label="t('incident_banner.delete_btn')"
                icon="pi pi-trash"
                size="small"
                severity="danger"
                text
                :loading="deletingId === row.id"
                @click="deleteBanner(row)"
              />
            </div>
          </template>
        </Column>
      </DataTable>

      <!-- ページネーション -->
      <div v-if="totalElements > pageSize" class="flex justify-center gap-2 pt-2">
        <Button
          icon="pi pi-chevron-left"
          text
          rounded
          :disabled="currentPage === 0"
          @click="currentPage--; load()"
        />
        <span class="flex items-center text-sm text-surface-500">
          {{ currentPage + 1 }} / {{ Math.ceil(totalElements / pageSize) }}
        </span>
        <Button
          icon="pi pi-chevron-right"
          text
          rounded
          :disabled="(currentPage + 1) * pageSize >= totalElements"
          @click="currentPage++; load()"
        />
      </div>

      <!-- 検知候補パネル -->
      <section class="rounded-xl border border-surface-200 dark:border-surface-700">
        <button
          class="flex w-full items-center justify-between px-4 py-3 text-left transition-colors hover:bg-surface-50 dark:hover:bg-surface-800"
          @click="toggleSuggestions"
        >
          <h2 class="flex items-center gap-2 text-sm font-semibold text-surface-700 dark:text-surface-200">
            <i class="pi pi-exclamation-triangle text-orange-500" />
            {{ t('incident_banner.suggestions.title') }}
          </h2>
          <i
            class="pi text-surface-400 transition-transform"
            :class="suggestionsExpanded ? 'pi-chevron-up' : 'pi-chevron-down'"
          />
        </button>

        <div v-if="suggestionsExpanded" class="p-4 pt-0">
          <div v-if="loadingSuggestions" class="flex items-center justify-center py-8">
            <i class="pi pi-spin pi-spinner mr-2 text-xl text-surface-400" aria-hidden="true" />
          </div>

          <DataTable
            v-else
            :value="suggestions"
            data-key="pagePattern"
            class="text-sm"
          >
            <template #empty>
              <div class="flex flex-col items-center justify-center gap-2 py-8 text-surface-400">
                <i class="pi pi-check-circle text-3xl text-green-500" aria-hidden="true" />
                <p class="text-sm">{{ t('incident_banner.suggestions.no_data') }}</p>
              </div>
            </template>

            <Column
              :header="t('incident_banner.suggestions.page_pattern')"
              style="min-width: 12rem"
            >
              <template #body="{ data: row }: { data: IncidentSuggestionResponse }">
                <code class="rounded bg-surface-100 px-1 py-0.5 text-xs dark:bg-surface-700">
                  {{ row.pagePattern }}
                </code>
              </template>
            </Column>

            <Column :header="t('incident_banner.suggestions.severity')" style="width: 9rem">
              <template #body="{ data: row }: { data: IncidentSuggestionResponse }">
                <Tag
                  :value="row.severity"
                  :severity="severityBadgeSeverity(row.severity)"
                />
              </template>
            </Column>

            <Column
              :header="t('incident_banner.suggestions.occurrence_count')"
              field="occurrenceCount"
              style="width: 9rem"
            />

            <Column
              :header="t('incident_banner.suggestions.affected_users')"
              field="affectedUserCount"
              style="width: 10rem"
            />

            <Column :header="t('incident_banner.suggestions.since')" style="width: 11rem">
              <template #body="{ data: row }: { data: IncidentSuggestionResponse }">
                <span class="text-xs text-surface-500">
                  {{ row.since ? formatDateTime(row.since) : '-' }}
                </span>
              </template>
            </Column>

            <Column :header="''" style="width: 10rem">
              <template #body="{ data: row }: { data: IncidentSuggestionResponse }">
                <Button
                  :label="t('incident_banner.suggestions.create_banner_btn')"
                  icon="pi pi-plus"
                  size="small"
                  @click="createFromSuggestion(row)"
                />
              </template>
            </Column>
          </DataTable>
        </div>
      </section>
    </template>

    <!-- 作成・編集 Dialog -->
    <Dialog
      v-model:visible="dialogOpen"
      modal
      :header="dialogMode === 'create' ? t('incident_banner.create_dialog_title') : t('incident_banner.edit_dialog_title')"
      :style="{ width: '42rem' }"
      :draggable="false"
      @hide="closeDialog"
    >
      <div class="flex flex-col gap-4">
        <!-- メッセージ -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('incident_banner.form.message_label') }}
            <span class="text-red-600">*</span>
          </label>
          <Textarea
            v-model="formMessage"
            :maxlength="MESSAGE_MAX"
            :placeholder="t('incident_banner.form.message_placeholder')"
            :invalid="!!messageError && formMessage.length > 0"
            class="w-full"
            rows="3"
            auto-resize
          />
          <div class="mt-1 flex items-start justify-between">
            <p v-if="messageError && formMessage.length > 0" class="text-xs text-red-600">
              {{ messageError }}
            </p>
            <span class="ml-auto text-xs text-surface-400">
              {{ formMessage.length }} / {{ MESSAGE_MAX }}
            </span>
          </div>
        </div>

        <!-- レベル -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('incident_banner.form.level_label') }}
            <span class="text-red-600">*</span>
          </label>
          <Select
            v-model="formLevel"
            :options="levelOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <!-- プレビュー -->
        <div v-if="formMessage.trim()">
          <label class="mb-1 block text-xs font-medium text-surface-500">
            {{ t('incident_banner.form.preview_label') }}
          </label>
          <Message :severity="levelToSeverity(formLevel)" :closable="false">
            {{ formMessage.trim() }}
          </Message>
        </div>

        <!-- 対象ページパターン -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('incident_banner.form.page_pattern_label') }}
          </label>
          <InputText
            v-model="formPagePattern"
            :placeholder="t('incident_banner.form.page_pattern_placeholder')"
            class="w-full"
            maxlength="255"
          />
        </div>

        <!-- 掲示期間 -->
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="mb-1 block text-sm font-medium">
              {{ t('incident_banner.form.starts_at_label') }}
            </label>
            <DatePicker
              v-model="formStartsAt"
              :placeholder="t('incident_banner.form.starts_at_placeholder')"
              class="w-full"
              show-time
              hour-format="24"
              show-button-bar
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">
              {{ t('incident_banner.form.ends_at_label') }}
            </label>
            <DatePicker
              v-model="formEndsAt"
              :placeholder="t('incident_banner.form.ends_at_placeholder')"
              class="w-full"
              show-time
              hour-format="24"
              show-button-bar
            />
          </div>
        </div>

        <!-- 作成直後公開トグル（作成モードのみ） -->
        <div v-if="dialogMode === 'create'" class="flex items-center gap-3">
          <ToggleSwitch v-model="formPublishAfterSave" />
          <label class="text-sm font-medium">
            {{ t('incident_banner.form.publish_toggle_label') }}
          </label>
        </div>

        <!-- 既存翻訳一覧（編集モードのみ） -->
        <div v-if="dialogMode === 'edit' && existingTranslations.length > 0">
          <p class="mb-2 text-xs font-medium text-surface-500">
            {{ t('incident_banner.translations.title') }}
          </p>
          <p class="mb-2 text-xs text-orange-500">
            {{ t('incident_banner.translation_pending') }}
          </p>
          <div class="rounded-lg border border-surface-200 dark:border-surface-700">
            <div
              v-for="tr in existingTranslations"
              :key="tr.language"
              class="flex gap-3 border-b border-surface-100 px-3 py-2 last:border-b-0 dark:border-surface-700"
            >
              <span class="w-8 shrink-0 text-xs font-semibold uppercase text-surface-400">
                {{ tr.language }}
              </span>
              <span class="text-sm text-surface-600 dark:text-surface-300">{{ tr.message }}</span>
            </div>
          </div>
        </div>

        <!-- 編集モードで翻訳が未生成の場合のヒント -->
        <div v-else-if="dialogMode === 'edit' && existingTranslations.length === 0">
          <p class="text-xs text-orange-500">
            {{ t('incident_banner.translation_pending') }}
          </p>
        </div>
      </div>

      <template #footer>
        <Button
          :label="t('incident_banner.cancel_btn')"
          severity="secondary"
          text
          @click="closeDialog"
        />
        <Button
          :label="t('incident_banner.save_btn')"
          :disabled="!canSave"
          :loading="saving"
          @click="save"
        />
      </template>
    </Dialog>
  </div>
</template>
