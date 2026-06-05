<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / 村史タブ
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2（Phase 3）
 *
 * 構成:
 *   - 上段: <VillageHeader activeTab="chronicle" />
 *   - 下段:
 *       - 月別アーカイブ表示（年/月セレクタ）
 *       - 各村史: 投稿数 / 新規参加 / TOP3 タグカード表示
 *       - 編集不要（読み取り専用）
 */
import type {
  MembershipResponse,
  VillageChronicleResponse,
  VillageResponse,
} from '~/types/village'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
  key: route => route.fullPath,
})

const route = useRoute()
const villageId = String(route.params.id)
const { t } = useI18n()
const villageApi = useVillageApi()
const authStore = useAuthStore()
const { handleApiError } = useErrorHandler()

const village = ref<VillageResponse | null>(null)
const loading = ref(true)
const notFound = ref(false)
const myMembership = ref<MembershipResponse | null>(null)
const currentUserId = computed<number | null>(() => authStore.currentUser?.id ?? null)

const chronicles = ref<VillageChronicleResponse[]>([])
const chroniclesLoading = ref(false)
const totalChronicles = ref(0)
const page = ref(0)
const PAGE_SIZE = 12

// 年/月セレクタ — 全件取得後にフロントで絞り込み（件数が少ない前提）
const selectedYear = ref<number | 'ALL'>('ALL')

const availableYears = computed<number[]>(() => {
  const years = new Set<number>()
  for (const c of chronicles.value) {
    const y = Number.parseInt(c.yearMonth.slice(0, 4), 10)
    if (!Number.isNaN(y)) years.add(y)
  }
  return Array.from(years).sort((a, b) => b - a)
})

const filteredChronicles = computed<VillageChronicleResponse[]>(() => {
  if (selectedYear.value === 'ALL') return chronicles.value
  const y = String(selectedYear.value)
  return chronicles.value.filter(c => c.yearMonth.startsWith(y))
})

const yearOptions = computed(() => [
  { value: 'ALL' as const, label: t('village.chronicle.title') },
  ...availableYears.value.map(y => ({ value: y, label: `${y}` })),
])

async function loadChronicles() {
  chroniclesLoading.value = true
  try {
    const res = await villageApi.listChronicles(villageId, page.value, PAGE_SIZE)
    chronicles.value = res.items
    totalChronicles.value = res.total
  }
  catch (error) {
    chronicles.value = []
    totalChronicles.value = 0
    handleApiError(error, t('village.chronicle.loadFailed'))
  }
  finally {
    chroniclesLoading.value = false
  }
}

function formatYearMonth(yearMonth: string): string {
  // YYYY-MM 形式
  const [y, m] = yearMonth.split('-')
  if (!y || !m) return yearMonth
  return `${y}年${Number.parseInt(m, 10)}月`
}

function topTags(chronicle: VillageChronicleResponse): string[] {
  return chronicle.topicTags.slice(0, 3)
}

// =====================================================================
// VillageHeader アクションハンドラ
// =====================================================================

async function loadVillage() {
  loading.value = true
  notFound.value = false
  try {
    village.value = await villageApi.getVillage(villageId)
    if (village.value?.isMember) {
      await loadMyMembership()
    }
    await loadChronicles()
  }
  catch (error: unknown) {
    const status = (error as { statusCode?: number; response?: { status?: number } })
    const code = status?.statusCode ?? status?.response?.status
    if (code === 404) {
      notFound.value = true
    }
    else {
      handleApiError(error, t('village.title'))
    }
  }
  finally {
    loading.value = false
  }
}

async function loadMyMembership() {
  const myUserId = currentUserId.value
  if (!myUserId) {
    myMembership.value = null
    return
  }
  try {
    const res = await villageApi.listMembers(villageId, { page: 0, size: 100 })
    myMembership.value
      = res.content.find(
        m => m.subjectType === 'USER' && m.subjectId === myUserId,
      ) ?? null
  }
  catch (error) {
    console.warn('[village/chronicles] listMembers failed', error)
    myMembership.value = null
  }
}

async function onJoin() {
  const myUserId = currentUserId.value
  if (!myUserId) return
  try {
    await villageApi.joinVillage(villageId, {
      subjectType: 'USER',
      subjectId: myUserId,
    })
    await loadVillage()
  }
  catch (error) {
    handleApiError(error, t('village.action.join'))
  }
}

async function onLeave() {
  if (!myMembership.value) await loadMyMembership()
  if (!myMembership.value) {
    await loadVillage()
    return
  }
  try {
    await villageApi.leaveVillage(villageId, myMembership.value.id)
    await loadVillage()
  }
  catch (error) {
    handleApiError(error, t('village.action.leave'))
  }
}

async function onPin() {
  try {
    await villageApi.addPin(villageId)
    await loadVillage()
  }
  catch (error) {
    handleApiError(error, t('village.action.pin'))
  }
}

async function onUnpin() {
  try {
    await villageApi.removePin(villageId)
    await loadVillage()
  }
  catch (error) {
    handleApiError(error, t('village.action.unpin'))
  }
}

const showReportDialog = ref(false)
function onReportClick() {
  showReportDialog.value = true
}

const showVillageEditDialog = ref(false)
function onEdit() {
  showVillageEditDialog.value = true
}

function onVillageUpdated(updated: VillageResponse) {
  village.value = updated
}

onMounted(() => {
  loadVillage()
})
</script>

<template>
  <div>
    <PageLoading v-if="loading" />

    <div v-else-if="notFound" class="mx-auto max-w-2xl p-6 text-center">
      <i class="pi pi-exclamation-circle text-4xl text-surface-400" />
      <p class="mt-4 text-lg">
        {{ t('village.error.VILLAGE_001') }}
      </p>
      <NuxtLink to="/villages" class="mt-4 inline-block text-primary-600 hover:underline">
        <i class="pi pi-arrow-left mr-1" />
        {{ t('village.error.backToList') }}
      </NuxtLink>
    </div>

    <template v-else-if="village">
      <VillageHeader
        :village="village"
        active-tab="chronicle"
        @join="onJoin"
        @request-join="onJoin"
        @leave="onLeave"
        @pin="onPin"
        @unpin="onUnpin"
        @report-click="onReportClick"
        @edit="onEdit"
      />

      <div class="mx-auto max-w-4xl p-4 sm:p-6">
        <div class="flex items-center justify-between flex-wrap gap-3 mb-4">
          <div>
            <h2 class="text-xl font-bold">
              {{ t('village.chronicle.title') }}
            </h2>
            <p class="text-sm text-surface-500">
              {{ t('village.chronicle.subtitle') }}
            </p>
          </div>
          <Select
            v-model="selectedYear"
            :options="yearOptions"
            option-value="value"
            option-label="label"
            class="w-44"
          />
        </div>

        <div v-if="chroniclesLoading" class="text-center py-12 text-surface-500">
          <i class="pi pi-spin pi-spinner text-2xl" />
        </div>
        <DashboardEmptyState
          v-else-if="filteredChronicles.length === 0"
          icon="pi pi-book"
          :message="t('village.chronicle.empty')"
        />
        <div v-else class="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div
            v-for="c in filteredChronicles"
            :key="c.id"
            class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
          >
            <div class="flex items-center justify-between gap-2 mb-2">
              <h3 class="font-semibold text-lg">
                {{ formatYearMonth(c.yearMonth) }}
              </h3>
              <span class="text-xs text-surface-400">
                {{ c.generatedAt.slice(0, 10) }}
              </span>
            </div>
            <div class="grid grid-cols-2 gap-2 mb-3">
              <div class="rounded bg-surface-50 p-2 dark:bg-surface-800 text-center">
                <div class="text-xs text-surface-500">
                  {{ t('village.chronicle.postCount') }}
                </div>
                <div class="text-xl font-bold">
                  {{ c.postCount }}
                </div>
              </div>
              <div class="rounded bg-surface-50 p-2 dark:bg-surface-800 text-center">
                <div class="text-xs text-surface-500">
                  {{ t('village.chronicle.newMemberCount') }}
                </div>
                <div class="text-xl font-bold">
                  {{ c.newMemberCount }}
                </div>
              </div>
            </div>
            <div v-if="topTags(c).length > 0">
              <div class="text-xs text-surface-500 mb-1">
                {{ t('village.chronicle.topicTags') }}
              </div>
              <div class="flex flex-wrap gap-1">
                <Tag
                  v-for="tag in topTags(c)"
                  :key="tag"
                  :value="tag"
                  severity="secondary"
                />
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 通報ダイアログ -->
      <VillageReportDialog
        v-model:visible="showReportDialog"
        :village-id="village.id"
        target-type="VILLAGE"
        :target-ref-id="village.id"
      />

      <!-- 編集ダイアログ -->
      <VillageEditDialog
        v-model:visible="showVillageEditDialog"
        :village="village"
        @updated="onVillageUpdated"
      />
    </template>
  </div>
</template>
