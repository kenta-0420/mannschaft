<script setup lang="ts">
/**
 * 重要事項説明書（参考） ドラフト一覧ページ（F09.14 Phase 2-β-5）。
 *
 * URL: /property-disclosure?organizationId=N
 *
 * - 「新規ドラフトを作成」ボタン → DisclosureTemplatePicker → タイトル入力モーダル → 作成
 * - ドラフトテーブル: title / template / status / 更新日 / 編集アクション
 * - 「出力履歴」リンク
 * - ドラフト件数 50 件上限警告（45 件超で表示）
 */
import dayjs from 'dayjs'
import type {
  DisclosureFormDraft,
  DisclosureFormTemplate,
} from '~/types/disclosure'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const { success: showSuccess, error: showError } = useNotification()
const { userTimezone } = useDatetime()

const organizationId = computed<string>(() => {
  const raw = route.query.organizationId
  return raw ? String(Array.isArray(raw) ? raw[0] : raw) : ''
})

const api = computed(() => useDisclosureApi(organizationId.value))

const drafts = ref<DisclosureFormDraft[]>([])
const totalElements = ref(0)
const loading = ref(false)
const page = ref(0)
const size = ref(20)

const DRAFT_LIMIT = 50
const DRAFT_LIMIT_WARN_THRESHOLD = 45

async function load() {
  if (!organizationId.value) return
  loading.value = true
  try {
    const res = await api.value.listDrafts({ page: page.value, size: size.value })
    drafts.value = res.data
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

// === テンプレート選択 → タイトル入力モーダル → 作成 ===
const showTemplatePicker = ref(false)
const showCreateDialog = ref(false)
const creating = ref(false)
const selectedTemplate = ref<DisclosureFormTemplate | null>(null)
const newDraftTitle = ref('')
const newTargetDwellingUnitId = ref<number | null>(null)

function openTemplatePicker() {
  if (!organizationId.value) return
  if (totalElements.value >= DRAFT_LIMIT) {
    showError(t('disclosure.limit.exceeded'))
    return
  }
  showTemplatePicker.value = true
}

function onTemplateSelected(template: DisclosureFormTemplate) {
  selectedTemplate.value = template
  newDraftTitle.value = template.name
  newTargetDwellingUnitId.value = null
  showCreateDialog.value = true
}

async function saveCreate() {
  if (!selectedTemplate.value) return
  if (!newDraftTitle.value.trim()) {
    showError(t('disclosure.validation.title'))
    return
  }
  creating.value = true
  try {
    const created = await api.value.createDraft({
      templateId: selectedTemplate.value.id,
      title: newDraftTitle.value.trim(),
      targetDwellingUnitId: newTargetDwellingUnitId.value,
    })
    showSuccess(t('disclosure.saved'))
    showCreateDialog.value = false
    selectedTemplate.value = null
    // 直接編集ページへ遷移
    navigateTo({
      path: `/property-disclosure/${created.id}`,
      query: { organizationId: String(organizationId.value) },
    })
  } catch {
    showError(t('disclosure.errors.saveFailed'))
  } finally {
    creating.value = false
  }
}

function onSelect(draftId: number) {
  navigateTo({
    path: `/property-disclosure/${draftId}`,
    query: { organizationId: String(organizationId.value) },
  })
}

function gotoExports() {
  navigateTo({
    path: '/property-disclosure/exports',
    query: { organizationId: String(organizationId.value) },
  })
}

async function deleteDraft(draftId: number) {
  if (!confirm(t('disclosure.deleteConfirm'))) return
  try {
    await api.value.deleteDraft(draftId)
    showSuccess(t('disclosure.deleted'))
    await load()
  } catch {
    showError(t('disclosure.errors.deleteFailed'))
  }
}

const remaining = computed(() => Math.max(0, DRAFT_LIMIT - totalElements.value))
const showLimitWarning = computed(
  () => totalElements.value >= DRAFT_LIMIT_WARN_THRESHOLD && totalElements.value < DRAFT_LIMIT,
)

function formatDate(iso: string): string {
  if (!iso) return '-'
  const d = dayjs(iso)
  if (!d.isValid()) return iso
  return d.tz(userTimezone.value).format('YYYY/MM/DD HH:mm')
}

function statusSeverity(status: string): 'info' | 'success' | 'warn' | 'secondary' {
  if (status === 'DRAFT') return 'info'
  if (status === 'READY') return 'warn'
  if (status === 'EXPORTED') return 'success'
  return 'secondary'
}
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <header class="flex flex-wrap items-center justify-between gap-3">
      <div>
        <PageHeader :title="t('disclosure.title')" />
        <p class="text-sm text-surface-500 dark:text-surface-400">
          {{ t('disclosure.subtitle') }}
        </p>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <Button
          icon="pi pi-history"
          :label="t('disclosure.actions.viewExports')"
          severity="secondary"
          text
          data-testid="disclosure-exports-link"
          :disabled="!organizationId"
          @click="gotoExports"
        />
        <Button
          icon="pi pi-plus"
          :label="t('disclosure.newDraft')"
          severity="primary"
          data-testid="disclosure-new-draft-btn"
          :disabled="!organizationId"
          @click="openTemplatePicker"
        />
      </div>
    </header>

    <p
      v-if="!organizationId"
      class="rounded-md border border-dashed border-yellow-300 bg-yellow-50 p-4 text-sm text-yellow-700 dark:border-yellow-800 dark:bg-yellow-950 dark:text-yellow-200"
    >
      ?organizationId=N
    </p>

    <section v-else class="space-y-4">
      <!-- 50件上限警告 -->
      <div
        v-if="showLimitWarning"
        class="rounded-md border border-yellow-300 bg-yellow-50 p-3 text-sm text-yellow-700 dark:border-yellow-800 dark:bg-yellow-950 dark:text-yellow-200"
        data-testid="disclosure-limit-warning"
      >
        {{ t('disclosure.limit.warning', { remaining }) }}
      </div>

      <!-- 一覧テーブル -->
      <div
        v-if="loading"
        class="rounded-md border border-surface-200 p-8 text-center text-sm text-surface-500 dark:border-surface-700"
      >
        {{ t('disclosure.loading') }}
      </div>

      <div
        v-else-if="drafts.length === 0"
        class="rounded-md border border-dashed border-surface-300 p-8 text-center text-sm text-surface-500 dark:border-surface-700"
        data-testid="disclosure-empty"
      >
        {{ t('disclosure.noDrafts') }}
      </div>

      <DataTable
        v-else
        :value="drafts"
        striped-rows
        :data-key="'id'"
        responsive-layout="scroll"
        data-testid="disclosure-drafts-table"
      >
        <Column field="title" :header="t('disclosure.fields.title')" sortable>
          <template #body="{ data }: { data: DisclosureFormDraft }">
            <button
              class="text-left text-blue-700 hover:underline dark:text-blue-400"
              :data-testid="`disclosure-draft-${data.id}`"
              @click="onSelect(data.id)"
            >
              {{ data.title }}
            </button>
          </template>
        </Column>
        <Column :header="t('disclosure.fields.template')">
          <template #body="{ data }: { data: DisclosureFormDraft }">
            <span class="text-sm text-surface-600">
              v{{ data.templateVersionSnapshot }}
            </span>
          </template>
        </Column>
        <Column :header="t('disclosure.fields.status')">
          <template #body="{ data }: { data: DisclosureFormDraft }">
            <Tag
              :severity="statusSeverity(data.status)"
              :value="t(`disclosure.draftStatus.${data.status}`)"
            />
          </template>
        </Column>
        <Column :header="t('disclosure.fields.updatedAt')">
          <template #body="{ data }: { data: DisclosureFormDraft }">
            {{ formatDate(data.updatedAt) }}
          </template>
        </Column>
        <Column :header="''">
          <template #body="{ data }: { data: DisclosureFormDraft }">
            <div class="flex gap-1">
              <Button
                icon="pi pi-pencil"
                severity="secondary"
                text
                size="small"
                :aria-label="t('disclosure.actions.edit')"
                :data-testid="`disclosure-edit-${data.id}`"
                @click="onSelect(data.id)"
              />
              <Button
                icon="pi pi-trash"
                severity="danger"
                text
                size="small"
                :aria-label="t('disclosure.actions.delete')"
                :data-testid="`disclosure-delete-${data.id}`"
                @click="deleteDraft(data.id)"
              />
            </div>
          </template>
        </Column>
      </DataTable>

      <!-- ページネーション -->
      <Paginator
        v-if="totalElements > size"
        v-model:first="page"
        :rows="size"
        :total-records="totalElements"
        :rows-per-page-options="[10, 20, 50]"
        @update:rows="(v: number) => (size = v)"
      />
    </section>

    <!-- 様式選択モーダル -->
    <DisclosureTemplatePicker
      v-model:visible="showTemplatePicker"
      :organization-id="Number(organizationId)"
      @select="onTemplateSelected"
    />

    <!-- タイトル入力モーダル -->
    <Dialog
      v-model:visible="showCreateDialog"
      :header="t('disclosure.newDraft')"
      modal
      :style="{ width: '32rem' }"
      :breakpoints="{ '768px': '90vw' }"
    >
      <div class="space-y-3">
        <div v-if="selectedTemplate" class="rounded-md bg-surface-50 p-3 text-sm dark:bg-surface-900">
          <strong>{{ selectedTemplate.name }}</strong>
          <span class="ml-2 text-xs text-surface-500">
            v{{ selectedTemplate.version }}
          </span>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('disclosure.fields.title') }}
            <span class="text-red-500">*</span>
          </label>
          <InputText
            v-model="newDraftTitle"
            class="w-full"
            data-testid="disclosure-new-title"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('disclosure.fields.targetDwelling') }}
          </label>
          <InputNumber
            v-model="newTargetDwellingUnitId"
            class="w-full"
            :placeholder="t('disclosure.fields.targetDwellingPlaceholder')"
            :use-grouping="false"
          />
        </div>
      </div>

      <template #footer>
        <Button
          :label="t('disclosure.actions.cancel')"
          severity="secondary"
          text
          @click="showCreateDialog = false"
        />
        <Button
          :label="t('disclosure.actions.create')"
          :loading="creating"
          severity="primary"
          data-testid="disclosure-create-confirm"
          @click="saveCreate"
        />
      </template>
    </Dialog>
  </div>
</template>
