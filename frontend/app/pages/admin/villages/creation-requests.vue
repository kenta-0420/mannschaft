<script setup lang="ts">
/**
 * F17.1 村機能 — 運営による村作成申請審査画面
 *
 * 設計書: docs/features/F17.1_village_community.md §4.4.3 / §5
 * Backend Controller: VillageCreationRequestController（運営用エンドポイント）
 *
 * - SYSTEM_ADMIN 専用画面。SYSTEM_ADMIN でない場合はページ内で「権限がありません」表示
 * - ステータスフィルタタブ（PENDING がデフォルト）で絞り込み
 * - PENDING 行のみ「承認」「却下」操作を提示
 * - 却下時は審査コメント必須
 */
import type {
  VillageCreationRequestResponse,
  VillageRequestStatus,
} from '~/types/village'

definePageMeta({ layout: 'default', middleware: 'auth' })

const { t } = useI18n()
const authStore = useAuthStore()
const { listAdminCreationRequests, reviewCreationRequest } = useVillageApi()
const { error: showError, success: showSuccess } = useNotification()
const { formatDateTime } = useDatetime()

// 権限判定 — SYSTEM_ADMIN 以外は閲覧不可
const isAllowed = computed(() => authStore.isSystemAdmin)

// =====================================================================
// 状態
// =====================================================================

type StatusFilter = VillageRequestStatus | 'ALL'

const STATUS_FILTERS: StatusFilter[] = ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'ALL']

/** サーバーサイドページングの1ページあたり件数（BE Pageable 既定 size=20 に合わせる） */
const PAGE_SIZE = 20

const statusFilter = ref<StatusFilter>('PENDING')
const requests = ref<VillageCreationRequestResponse[]>([])
const loading = ref(false)
const totalRecords = ref(0)
const page = ref(0)

// 詳細 Dialog
const detailVisible = ref(false)
const detailRequest = ref<VillageCreationRequestResponse | null>(null)

// 承認 Dialog
const approveVisible = ref(false)
const approveComment = ref('')
const approveTarget = ref<VillageCreationRequestResponse | null>(null)

// 却下 Dialog
const rejectVisible = ref(false)
const rejectComment = ref('')
const rejectTarget = ref<VillageCreationRequestResponse | null>(null)
const rejectError = ref<string | null>(null)

const submitting = ref(false)

// =====================================================================
// データ取得
// =====================================================================

async function load() {
  if (!isAllowed.value) return
  loading.value = true
  try {
    const params = {
      ...(statusFilter.value === 'ALL' ? {} : { status: statusFilter.value }),
      page: page.value,
      size: PAGE_SIZE,
    }
    const res = await listAdminCreationRequests(params)
    requests.value = res.content
    totalRecords.value = res.totalElements
  }
  catch {
    requests.value = []
    totalRecords.value = 0
    showError(t('village.creationRequest.loadFailed'))
  }
  finally {
    loading.value = false
  }
}

/** ページ送り — BE に page を送って該当ページのみ取得する（サーバーサイドページング） */
function onPage(event: { page: number }) {
  page.value = event.page
  void load()
}

// =====================================================================
// 操作
// =====================================================================

function openDetail(req: VillageCreationRequestResponse) {
  detailRequest.value = req
  detailVisible.value = true
}

function openApprove(req: VillageCreationRequestResponse) {
  approveTarget.value = req
  approveComment.value = ''
  approveVisible.value = true
}

function openReject(req: VillageCreationRequestResponse) {
  rejectTarget.value = req
  rejectComment.value = ''
  rejectError.value = null
  rejectVisible.value = true
}

async function submitApprove() {
  if (!approveTarget.value) return
  submitting.value = true
  try {
    await reviewCreationRequest(approveTarget.value.id, 'approve', {
      reviewComment: approveComment.value || null,
    })
    showSuccess(t('village.creationRequest.approveSuccess'))
    approveVisible.value = false
    await load()
  }
  catch {
    showError(t('village.creationRequest.reviewFailed'))
  }
  finally {
    submitting.value = false
  }
}

async function submitReject() {
  if (!rejectTarget.value) return
  const trimmed = rejectComment.value.trim()
  if (!trimmed) {
    rejectError.value = t('village.creationRequest.rejectCommentRequired')
    return
  }
  submitting.value = true
  try {
    await reviewCreationRequest(rejectTarget.value.id, 'reject', {
      reviewComment: trimmed,
    })
    showSuccess(t('village.creationRequest.rejectSuccess'))
    rejectVisible.value = false
    await load()
  }
  catch {
    showError(t('village.creationRequest.reviewFailed'))
  }
  finally {
    submitting.value = false
  }
}

// =====================================================================
// 表示ヘルパー
// =====================================================================

function statusLabel(status: VillageRequestStatus): string {
  switch (status) {
    case 'PENDING':
      return t('village.creationRequest.pending')
    case 'APPROVED':
      return t('village.creationRequest.approved')
    case 'REJECTED':
      return t('village.creationRequest.rejected')
    case 'WITHDRAWN':
      return t('village.creationRequest.withdrawn')
  }
}

function statusSeverity(
  status: VillageRequestStatus,
): 'warn' | 'success' | 'danger' | 'secondary' {
  switch (status) {
    case 'PENDING':
      return 'warn'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'WITHDRAWN':
      return 'secondary'
  }
}

function filterLabel(s: StatusFilter): string {
  if (s === 'ALL') return t('village.creationRequest.filter.all')
  return t(`village.creationRequest.filter.${s}`)
}

function truncate(text: string | null | undefined, max = 60): string {
  if (!text) return ''
  return text.length > max ? `${text.slice(0, max)}…` : text
}


// =====================================================================
// ライフサイクル
// =====================================================================

watch(statusFilter, () => {
  page.value = 0
  void load()
})
onMounted(() => {
  if (isAllowed.value) load()
})
</script>

<template>
  <div class="mx-auto max-w-6xl p-4">
    <!-- 権限なし表示 -->
    <div v-if="!isAllowed" class="rounded-lg border border-surface-200 bg-surface-50 p-8 text-center dark:border-surface-700 dark:bg-surface-800">
      <i class="pi pi-lock mb-3 text-4xl text-surface-400" />
      <p class="text-surface-500">{{ t('village.creationRequest.noPermission') }}</p>
    </div>

    <template v-else>
      <div class="mb-4">
        <h1 class="text-2xl font-bold">{{ t('village.creationRequest.adminTitle') }}</h1>
      </div>

      <!-- ステータスフィルタタブ -->
      <Tabs v-model:value="statusFilter" class="mb-4">
        <TabList>
          <Tab
            v-for="s in STATUS_FILTERS"
            :key="s"
            :value="s"
          >
            {{ filterLabel(s) }}
          </Tab>
        </TabList>
      </Tabs>

      <DataTable
        :value="requests"
        :loading="loading"
        data-key="id"
        striped-rows
        :lazy="true"
        :paginator="true"
        :rows="PAGE_SIZE"
        :total-records="totalRecords"
        :first="page * PAGE_SIZE"
        row-hover
        @page="onPage"
        @row-click="(e) => openDetail(e.data as VillageCreationRequestResponse)"
      >
        <template #empty>
          <div class="py-12 text-center">
            <i class="pi pi-inbox mb-3 text-4xl text-surface-300" />
            <p class="text-surface-400">{{ t('village.creationRequest.empty') }}</p>
          </div>
        </template>

        <Column :header="t('village.creationRequest.column.requester')" style="width: 140px">
          <template #body="{ data }">
            <span class="text-sm">#{{ (data as VillageCreationRequestResponse).requesterUserId }}</span>
          </template>
        </Column>

        <Column :header="t('village.creationRequest.column.village')">
          <template #body="{ data }">
            <div class="flex flex-col">
              <span class="text-sm font-medium">{{ (data as VillageCreationRequestResponse).name }}</span>
              <span class="text-xs text-surface-400">{{ (data as VillageCreationRequestResponse).slug }}</span>
            </div>
          </template>
        </Column>

        <Column :header="t('village.creationRequest.column.category')" style="width: 140px">
          <template #body="{ data }">
            <span class="text-sm">{{ (data as VillageCreationRequestResponse).category ?? '—' }}</span>
          </template>
        </Column>

        <Column :header="t('village.creationRequest.column.purpose')">
          <template #body="{ data }">
            <span class="line-clamp-2 max-w-md text-sm">{{ truncate((data as VillageCreationRequestResponse).purpose, 80) }}</span>
          </template>
        </Column>

        <Column :header="t('village.field.createdAt')" style="width: 160px">
          <template #body="{ data }">
            <span class="text-sm">{{ formatDateTime((data as VillageCreationRequestResponse).createdAt) }}</span>
          </template>
        </Column>

        <Column header="" style="width: 110px">
          <template #body="{ data }">
            <Tag
              :value="statusLabel((data as VillageCreationRequestResponse).status)"
              :severity="statusSeverity((data as VillageCreationRequestResponse).status)"
            />
          </template>
        </Column>

        <Column :header="t('village.creationRequest.column.actions')" style="width: 200px">
          <template #body="{ data }">
            <div
              v-if="(data as VillageCreationRequestResponse).status === 'PENDING'"
              class="flex gap-2"
              @click.stop
            >
              <Button
                :label="t('village.action.approve')"
                icon="pi pi-check"
                size="small"
                severity="success"
                @click="openApprove(data as VillageCreationRequestResponse)"
              />
              <Button
                :label="t('village.action.reject')"
                icon="pi pi-times"
                size="small"
                severity="danger"
                @click="openReject(data as VillageCreationRequestResponse)"
              />
            </div>
            <span v-else class="text-xs text-surface-400">—</span>
          </template>
        </Column>
      </DataTable>

      <!-- 詳細 Dialog -->
      <Dialog
        v-model:visible="detailVisible"
        :header="t('village.creationRequest.detailTitle')"
        modal
        style="width: 600px"
      >
        <div v-if="detailRequest" class="flex flex-col gap-3 text-sm">
          <div class="flex flex-col gap-1">
            <span class="text-xs font-medium text-surface-400">{{ t('village.creationRequest.column.requester') }}</span>
            <span>#{{ detailRequest.requesterUserId }}</span>
          </div>
          <div class="flex flex-col gap-1">
            <span class="text-xs font-medium text-surface-400">{{ t('village.field.name') }}</span>
            <span>{{ detailRequest.name }}</span>
          </div>
          <div class="flex flex-col gap-1">
            <span class="text-xs font-medium text-surface-400">{{ t('village.field.slug') }}</span>
            <span>{{ detailRequest.slug }}</span>
          </div>
          <div class="flex flex-col gap-1">
            <span class="text-xs font-medium text-surface-400">{{ t('village.field.category') }}</span>
            <span>{{ detailRequest.category ?? '—' }}</span>
          </div>
          <div class="flex flex-col gap-1">
            <span class="text-xs font-medium text-surface-400">{{ t('village.creationRequest.purpose') }}</span>
            <p class="whitespace-pre-wrap rounded-lg bg-surface-50 p-3 dark:bg-surface-800">{{ detailRequest.purpose ?? '—' }}</p>
          </div>
          <div class="flex flex-col gap-1">
            <span class="text-xs font-medium text-surface-400">{{ t('village.field.createdAt') }}</span>
            <span>{{ formatDateTime(detailRequest.createdAt) }}</span>
          </div>
          <div class="flex flex-col gap-1">
            <span class="text-xs font-medium text-surface-400">Status</span>
            <div>
              <Tag :value="statusLabel(detailRequest.status)" :severity="statusSeverity(detailRequest.status)" />
            </div>
          </div>
          <div v-if="detailRequest.reviewComment" class="flex flex-col gap-1">
            <span class="text-xs font-medium text-surface-400">{{ t('village.creationRequest.reviewComment') }}</span>
            <p class="whitespace-pre-wrap rounded-lg bg-surface-50 p-3 dark:bg-surface-800">{{ detailRequest.reviewComment }}</p>
          </div>
          <div v-if="detailRequest.reviewedAt" class="flex flex-col gap-1">
            <span class="text-xs font-medium text-surface-400">Reviewed at</span>
            <span>{{ formatDateTime(detailRequest.reviewedAt) }}</span>
          </div>
        </div>
        <template #footer>
          <Button
            :label="t('village.action.cancel')"
            severity="secondary"
            @click="detailVisible = false"
          />
        </template>
      </Dialog>

      <!-- 承認 Dialog -->
      <Dialog
        v-model:visible="approveVisible"
        :header="t('village.action.approve')"
        modal
        style="width: 480px"
      >
        <div v-if="approveTarget" class="flex flex-col gap-4">
          <p class="text-sm">{{ t('village.creationRequest.confirm.approve') }}</p>
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="text-sm font-medium">{{ approveTarget.name }}</p>
            <p class="text-xs text-surface-400">{{ approveTarget.slug }}</p>
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('village.creationRequest.reviewComment') }}</label>
            <Textarea
              v-model="approveComment"
              :placeholder="t('village.creationRequest.reviewCommentPlaceholder')"
              rows="3"
              class="w-full"
            />
          </div>
        </div>
        <template #footer>
          <Button
            :label="t('village.action.cancel')"
            severity="secondary"
            :disabled="submitting"
            @click="approveVisible = false"
          />
          <Button
            :label="t('village.action.approve')"
            severity="success"
            :loading="submitting"
            @click="submitApprove"
          />
        </template>
      </Dialog>

      <!-- 却下 Dialog -->
      <Dialog
        v-model:visible="rejectVisible"
        :header="t('village.action.reject')"
        modal
        style="width: 480px"
      >
        <div v-if="rejectTarget" class="flex flex-col gap-4">
          <p class="text-sm">{{ t('village.creationRequest.confirm.reject') }}</p>
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="text-sm font-medium">{{ rejectTarget.name }}</p>
            <p class="text-xs text-surface-400">{{ rejectTarget.slug }}</p>
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">
              {{ t('village.creationRequest.reviewComment') }}
              <span class="text-red-500">*</span>
            </label>
            <Textarea
              v-model="rejectComment"
              :placeholder="t('village.creationRequest.reviewCommentPlaceholder')"
              rows="4"
              class="w-full"
            />
            <p v-if="rejectError" class="mt-1 text-xs text-red-500">{{ rejectError }}</p>
          </div>
        </div>
        <template #footer>
          <Button
            :label="t('village.action.cancel')"
            severity="secondary"
            :disabled="submitting"
            @click="rejectVisible = false"
          />
          <Button
            :label="t('village.action.reject')"
            severity="danger"
            :loading="submitting"
            @click="submitReject"
          />
        </template>
      </Dialog>
    </template>
  </div>
</template>
