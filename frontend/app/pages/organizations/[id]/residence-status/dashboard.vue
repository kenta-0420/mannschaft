<script setup lang="ts">
import dayjs from 'dayjs'
import type {
  AnnualReviewCreateRequest,
  AnnualReviewResponse,
  ContactResult,
  MonitoringVisitResponse,
  ResidenceStatusDashboard,
} from '~/types/residenceStatus'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = computed(() => String(route.params.id))

const { getDashboard } = useActivitySnapshotApi()
const { listReviews, createReview, closeReview } = useAnnualReviewApi()
const { listVisitsByCommittee } = useMonitoringVisitApi()
const { triggerSafetyCheck } = useOrgWideSafetyCheckApi()
const { formatDate, userTimezone } = useDatetime()

// ダッシュボード
const dashboard = ref<ResidenceStatusDashboard | null>(null)
const loadingDashboard = ref(false)

// 年次更新
const reviews = ref<AnnualReviewResponse[]>([])
const loadingReviews = ref(false)
const showCreateDialog = ref(false)
const targetYearInput = ref<string>(String(dayjs().tz(userTimezone.value).year()))
const createForm = ref<Omit<AnnualReviewCreateRequest, 'targetYear'>>({
  title: '',
  deadlineDate: '',
})
const submittingCreate = ref(false)
const closingReviewId = ref<string | null>(null)

// 訪問記録
const committeeIdInput = ref<string>('')
const visits = ref<MonitoringVisitResponse[]>([])
const loadingVisits = ref(false)

// 横展開安否確認
const showSafetyCheckDialog = ref(false)
const triggerReason = ref('')
const submittingSafetyCheck = ref(false)


// ContactResult の Tag severity
function contactResultSeverity(result: ContactResult): string {
  const map: Record<ContactResult, string> = {
    MET: 'success',
    NO_RESPONSE: 'warning',
    MAILBOX_ABNORMAL: 'danger',
    METER_ABNORMAL: 'danger',
    NEIGHBOR_INFO: 'info',
    REFUSED: 'secondary',
    OTHER: 'secondary',
  }
  return map[result] ?? 'secondary'
}

// ContactResult の表示ラベル
function contactResultLabel(result: ContactResult): string {
  const map: Record<ContactResult, string> = {
    MET: '対面確認',
    NO_RESPONSE: '応答なし',
    MAILBOX_ABNORMAL: 'ポスト異常',
    METER_ABNORMAL: 'メーター異常',
    NEIGHBOR_INFO: '近隣情報',
    REFUSED: '拒否',
    OTHER: 'その他',
  }
  return map[result] ?? result
}

// ダッシュボード取得
async function fetchDashboard() {
  loadingDashboard.value = true
  try {
    const res = await getDashboard(orgId.value)
    dashboard.value = res.data
  }
  catch (e) {
    console.error('ダッシュボード取得エラー:', e)
  }
  finally {
    loadingDashboard.value = false
  }
}

// 年次更新一覧取得
async function fetchReviews() {
  loadingReviews.value = true
  try {
    const res = await listReviews(orgId.value)
    reviews.value = res.data
  }
  catch (e) {
    console.error('年次更新一覧取得エラー:', e)
  }
  finally {
    loadingReviews.value = false
  }
}

// 年次更新作成
async function handleCreateReview() {
  submittingCreate.value = true
  try {
    await createReview(orgId.value, {
      ...createForm.value,
      targetYear: Number(targetYearInput.value),
    })
    showCreateDialog.value = false
    targetYearInput.value = String(dayjs().tz(userTimezone.value).year())
    createForm.value = {
      title: '',
      deadlineDate: '',
    }
    await fetchReviews()
  }
  catch (e) {
    console.error('年次更新作成エラー:', e)
  }
  finally {
    submittingCreate.value = false
  }
}

// 年次更新クローズ
async function handleCloseReview(reviewId: string) {
  closingReviewId.value = reviewId
  try {
    await closeReview(orgId.value, reviewId)
    await fetchReviews()
  }
  catch (e) {
    console.error('年次更新クローズエラー:', e)
  }
  finally {
    closingReviewId.value = null
  }
}

// 訪問記録検索
async function fetchVisits() {
  if (!committeeIdInput.value) return
  loadingVisits.value = true
  try {
    const res = await listVisitsByCommittee(orgId.value, Number(committeeIdInput.value))
    visits.value = res.data
  }
  catch (e) {
    console.error('訪問記録取得エラー:', e)
  }
  finally {
    loadingVisits.value = false
  }
}

// 横展開安否確認発動
async function handleTriggerSafetyCheck() {
  submittingSafetyCheck.value = true
  try {
    await triggerSafetyCheck(orgId.value, triggerReason.value)
    showSafetyCheckDialog.value = false
    triggerReason.value = ''
  }
  catch (e) {
    console.error('横展開安否確認発動エラー:', e)
  }
  finally {
    submittingSafetyCheck.value = false
  }
}

// 考慮メモの先頭20文字
function truncateMemo(memo: string | null): string {
  if (!memo) return '-'
  return memo.length > 20 ? memo.slice(0, 20) + '...' : memo
}

onMounted(async () => {
  await fetchDashboard()
  await fetchReviews()
})
</script>

<template>
  <div class="flex flex-col gap-6 p-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">居住実態管理</h1>
    </div>

    <TabView>
      <!-- ダッシュボードタブ -->
      <TabPanel value="dashboard" header="ダッシュボード">
        <div v-if="loadingDashboard" class="flex items-center justify-center py-12">
          <LoadingBounce />
        </div>
        <div v-else-if="dashboard" class="flex flex-col gap-6">
          <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
            <div class="card p-4 text-center border rounded bg-white dark:bg-surface-800">
              <div class="text-3xl font-bold text-red-500">
                {{ dashboard.highRiskCount }}
              </div>
              <div class="text-sm text-gray-500 mt-1">
                高リスク
              </div>
            </div>
            <div class="card p-4 text-center border rounded bg-white dark:bg-surface-800">
              <div class="text-3xl font-bold text-orange-400">
                {{ dashboard.midRiskCount }}
              </div>
              <div class="text-sm text-gray-500 mt-1">
                中リスク
              </div>
            </div>
            <div class="card p-4 text-center border rounded bg-white dark:bg-surface-800">
              <div class="text-3xl font-bold text-green-500">
                {{ dashboard.lowRiskCount }}
              </div>
              <div class="text-sm text-gray-500 mt-1">
                低リスク
              </div>
            </div>
            <div class="card p-4 text-center border rounded bg-white dark:bg-surface-800">
              <div class="text-3xl font-bold text-gray-400">
                {{ dashboard.unresponsiveCount }}
              </div>
              <div class="text-sm text-gray-500 mt-1">
                未応答
              </div>
            </div>
          </div>

          <div class="flex gap-4 flex-wrap">
            <div class="border rounded p-4 bg-white dark:bg-surface-800">
              <div class="text-sm text-gray-500">
                総住民数
              </div>
              <div class="text-2xl font-semibold mt-1">
                {{ dashboard.totalResidents }}
              </div>
            </div>
            <div class="border rounded p-4 bg-white dark:bg-surface-800">
              <div class="text-sm text-gray-500">
                オープン中の年次更新
              </div>
              <div class="text-2xl font-semibold mt-1">
                {{ dashboard.openAnnualReviewCount }}
              </div>
            </div>
            <div class="border rounded p-4 bg-white dark:bg-surface-800">
              <div class="text-sm text-gray-500">
                データ生成日時
              </div>
              <div class="text-base mt-1">
                {{ formatDate(dashboard.generatedAt) }}
              </div>
            </div>
          </div>
        </div>
        <div v-else class="text-center py-12 text-gray-400">
          データがありません
        </div>
      </TabPanel>

      <!-- 年次更新タブ -->
      <TabPanel value="annual-review" header="年次更新">
        <div class="flex flex-col gap-4">
          <div class="flex justify-end">
            <Button
              label="新規作成"
              icon="pi pi-plus"
              @click="showCreateDialog = true"
            />
          </div>

          <div v-if="loadingReviews" class="flex items-center justify-center py-12">
            <LoadingBounce />
          </div>
          <DataTable
            v-else
            :value="reviews"
            class="w-full"
          >
            <template #empty>
              <div class="text-center py-8 text-gray-400">
                データがありません
              </div>
            </template>
            <Column
              field="targetYear"
              header="対象年度"
              style="width: 8rem"
            />
            <Column
              field="title"
              header="タイトル"
            />
            <Column
              field="deadlineDate"
              header="締切日"
              style="width: 10rem"
            >
              <template #body="{ data }">
                {{ formatDate(data.deadlineDate) }}
              </template>
            </Column>
            <Column
              field="responseCount"
              header="回答数"
              style="width: 7rem"
            />
            <Column
              field="status"
              header="状態"
              style="width: 8rem"
            >
              <template #body="{ data }">
                <Tag
                  :value="data.status === 'OPEN' ? '受付中' : 'クローズ'"
                  :severity="data.status === 'OPEN' ? 'success' : 'secondary'"
                />
              </template>
            </Column>
            <Column
              field="closedAt"
              header="クローズ日時"
              style="width: 10rem"
            >
              <template #body="{ data }">
                {{ formatDate(data.closedAt) }}
              </template>
            </Column>
            <Column
              header="操作"
              style="width: 8rem"
            >
              <template #body="{ data }">
                <Button
                  label="クローズ"
                  size="small"
                  severity="secondary"
                  :disabled="data.status !== 'OPEN' || closingReviewId === data.id"
                  :loading="closingReviewId === data.id"
                  @click="handleCloseReview(data.id)"
                />
              </template>
            </Column>
          </DataTable>
        </div>

        <!-- 新規作成ダイアログ -->
        <Dialog
          v-model:visible="showCreateDialog"
          header="年次更新を新規作成"
          modal
          style="width: 30rem"
        >
          <div class="flex flex-col gap-4 py-2">
            <div class="flex flex-col gap-1">
              <label class="text-sm font-medium">対象年度</label>
              <InputText
                v-model="targetYearInput"
                type="number"
                placeholder="例: 2026"
              />
            </div>
            <div class="flex flex-col gap-1">
              <label class="text-sm font-medium">タイトル</label>
              <InputText
                v-model="createForm.title"
                placeholder="例: 2026年度 居住実態調査"
              />
            </div>
            <div class="flex flex-col gap-1">
              <label class="text-sm font-medium">締切日</label>
              <InputText
                v-model="createForm.deadlineDate"
                type="date"
              />
            </div>
          </div>
          <template #footer>
            <Button
              label="キャンセル"
              severity="secondary"
              outlined
              @click="showCreateDialog = false"
            />
            <Button
              label="作成"
              :loading="submittingCreate"
              :disabled="!createForm.title || !createForm.deadlineDate"
              @click="handleCreateReview"
            />
          </template>
        </Dialog>
      </TabPanel>

      <!-- 訪問記録タブ -->
      <TabPanel value="visits" header="訪問記録">
        <div class="flex flex-col gap-4">
          <div class="flex items-center justify-between gap-4 flex-wrap">
            <div class="flex items-center gap-2">
              <label class="text-sm font-medium whitespace-nowrap">委員会ID</label>
              <InputText
                v-model="committeeIdInput"
                type="number"
                placeholder="委員会IDを入力"
                style="width: 12rem"
              />
              <Button
                label="検索"
                icon="pi pi-search"
                :loading="loadingVisits"
                @click="fetchVisits"
              />
            </div>
            <Button
              label="横展開安否確認を発動"
              severity="danger"
              icon="pi pi-exclamation-triangle"
              @click="showSafetyCheckDialog = true"
            />
          </div>

          <div v-if="loadingVisits" class="flex items-center justify-center py-12">
            <LoadingBounce />
          </div>
          <DataTable
            v-else
            :value="visits"
            class="w-full"
          >
            <template #empty>
              <div class="text-center py-8 text-gray-400">
                データがありません（委員会IDを入力して検索してください）
              </div>
            </template>
            <Column
              field="visitedAt"
              header="訪問日時"
              style="width: 10rem"
            >
              <template #body="{ data }">
                {{ formatDate(data.visitedAt) }}
              </template>
            </Column>
            <Column
              field="contactResult"
              header="結果"
              style="width: 10rem"
            >
              <template #body="{ data }">
                <Tag
                  :value="contactResultLabel(data.contactResult)"
                  :severity="contactResultSeverity(data.contactResult)"
                />
              </template>
            </Column>
            <Column
              field="considerationMemo"
              header="メモ"
            >
              <template #body="{ data }">
                {{ truncateMemo(data.considerationMemo) }}
              </template>
            </Column>
            <Column
              field="nextVisitRecommendedAt"
              header="次回推奨訪問日"
              style="width: 10rem"
            >
              <template #body="{ data }">
                {{ formatDate(data.nextVisitRecommendedAt) }}
              </template>
            </Column>
          </DataTable>
        </div>

        <!-- 横展開安否確認ダイアログ -->
        <Dialog
          v-model:visible="showSafetyCheckDialog"
          header="横展開安否確認を発動"
          modal
          style="width: 30rem"
        >
          <div class="flex flex-col gap-4 py-2">
            <p class="text-sm text-gray-600">
              組織全体に対して安否確認を発動します。発動理由を入力してください。
            </p>
            <div class="flex flex-col gap-1">
              <label class="text-sm font-medium">発動理由</label>
              <Textarea
                v-model="triggerReason"
                rows="4"
                placeholder="例: 台風接近に伴う安否確認"
              />
            </div>
          </div>
          <template #footer>
            <Button
              label="キャンセル"
              severity="secondary"
              outlined
              @click="showSafetyCheckDialog = false"
            />
            <Button
              label="発動する"
              severity="danger"
              :loading="submittingSafetyCheck"
              :disabled="!triggerReason.trim()"
              @click="handleTriggerSafetyCheck"
            />
          </template>
        </Dialog>
      </TabPanel>
    </TabView>
  </div>
</template>
