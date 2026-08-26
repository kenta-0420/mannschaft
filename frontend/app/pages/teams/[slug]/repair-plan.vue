<script setup lang="ts">
import dayjs from 'dayjs'
import type { CreateKanbanRequest, KanbanStage, QuoteKanban } from '~/types/repairPlanKanban'
import type { RepairPlanTimelineResponse } from '~/types/repairPlanTimeline'

definePageMeta({ middleware: 'auth', layout: 'team' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const { getTimeline } = useRepairPlanTimelineApi()
const { listKanbans, createKanban, moveCard } = useRepairPlanKanbanApi()
const notification = useNotification()
const { formatDate, userTimezone } = useDatetime()
const teamApi = useTeamApi()
const { isAdminOrDeputy, isAdmin, loadPermissions } = useRoleAccess('team', teamSlug)

// タブ管理
type Tab = 'timeline' | 'kanban' | 'handover'
const activeTab = ref<Tab>('timeline')

// --- タイムライン ---
const currentYear = dayjs().tz(userTimezone.value).year()
const yearFrom = ref(currentYear - 20)
const yearTo = ref(currentYear + 10)
const timelineData = ref<RepairPlanTimelineResponse | null>(null)
const timelineLoading = ref(false)

// --- カンバン ---
const kanbans = ref<QuoteKanban[]>([])
const kanbanLoading = ref(false)
const organizationId = ref<number | null>(null)
const selectedKanban = ref<QuoteKanban | null>(null)
const showCreateDialog = ref(false)
const createForm = reactive<CreateKanbanRequest>({
  title: '',
  bidDeadlineAt: '',
  visibilityToMember: 'FULL',
})

/** チームが所属する組織IDを取得する（最初の組織を使用）*/
async function loadOrganizationId() {
  if (organizationId.value !== null) return
  try {
    const res = await teamApi.getOrganizations(teamSlug.value)
    const orgs = res.data ?? res
    if (Array.isArray(orgs) && orgs.length > 0) {
      const firstOrg = orgs[0] as Record<string, unknown>
      const rawId = firstOrg.id
      if (typeof rawId === 'number') {
        organizationId.value = rawId
      }
    }
  } catch {
    // organization が存在しない場合は null のまま
  }
}

async function loadTimeline() {
  timelineLoading.value = true
  try {
    timelineData.value = await getTimeline('teams', teamSlug.value, {
      yearFrom: yearFrom.value,
      yearTo: yearTo.value,
    })
  } catch {
    notification.error(t('repair_plan.timeline.no_data'))
  } finally {
    timelineLoading.value = false
  }
}

async function loadKanbans() {
  if (organizationId.value === null) return
  kanbanLoading.value = true
  try {
    kanbans.value = await listKanbans('teams', teamSlug.value, organizationId.value)
  } catch {
    notification.error(t('common.fetch_failed'))
  } finally {
    kanbanLoading.value = false
  }
}

async function handleTabChange(tab: Tab) {
  activeTab.value = tab
  if (tab === 'kanban' && kanbans.value.length === 0) {
    await loadOrganizationId()
    await loadKanbans()
  }
}

// 申し送りパック生成後に履歴リストを再ロードするための ref
const handoverHistoryRef = ref<{ reload: () => Promise<void> } | null>(null)

async function handlePackGenerated() {
  // 少し待ってから履歴をリロード（バックグラウンド生成の開始を反映）
  await handoverHistoryRef.value?.reload()
}

async function handleTimelineRefresh() {
  await loadTimeline()
}

function openKanban(kanban: QuoteKanban) {
  selectedKanban.value = kanban
}

function openCreateDialog() {
  Object.assign(createForm, {
    title: '',
    bidDeadlineAt: '',
    visibilityToMember: 'FULL',
  })
  showCreateDialog.value = true
}

async function handleCreateKanban() {
  if (!organizationId.value) return
  try {
    await createKanban('teams', teamSlug.value, organizationId.value, createForm)
    showCreateDialog.value = false
    await loadKanbans()
    notification.success(t('repair_plan.kanban.created'))
  } catch {
    notification.error(t('common.save_failed'))
  }
}

async function handleCardMoved(cardId: string, newStage: KanbanStage) {
  if (!organizationId.value || !selectedKanban.value) return
  try {
    await moveCard('teams', teamSlug.value, cardId, organizationId.value, { newStage })
    // カンバンを再取得して表示を更新
    const updated = await listKanbans('teams', teamSlug.value, organizationId.value)
    kanbans.value = updated
    // 選択中のカンバンも更新
    const refreshed = updated.find((k) => k.id === selectedKanban.value!.id)
    if (refreshed) selectedKanban.value = refreshed
  } catch {
    notification.error(t('common.save_failed'))
  }
}

onMounted(async () => {
  await loadPermissions()
  await loadTimeline()
})
</script>

<template>
  <div class="mx-auto max-w-7xl">
    <!-- ページヘッダ -->
    <div class="mb-4 flex items-center gap-3">
      <PageHeader :title="$t('repair_plan.dashboard.title')" />
    </div>

    <!-- タブ切り替え -->
    <div class="mb-6 flex gap-2 border-b border-surface-200 dark:border-surface-700">
      <button
        class="border-b-2 px-4 py-2 text-sm font-medium transition"
        :class="
          activeTab === 'timeline'
            ? 'border-primary-500 text-primary-600 dark:text-primary-400'
            : 'border-transparent text-surface-500 hover:text-surface-700'
        "
        @click="handleTabChange('timeline')"
      >
        {{ $t('repair_plan.timeline.title') }}
      </button>
      <button
        class="border-b-2 px-4 py-2 text-sm font-medium transition"
        :class="
          activeTab === 'kanban'
            ? 'border-primary-500 text-primary-600 dark:text-primary-400'
            : 'border-transparent text-surface-500 hover:text-surface-700'
        "
        @click="handleTabChange('kanban')"
      >
        {{ $t('repair_plan.kanban.title') }}
      </button>
      <button
        class="border-b-2 px-4 py-2 text-sm font-medium transition"
        :class="
          activeTab === 'handover'
            ? 'border-primary-500 text-primary-600 dark:text-primary-400'
            : 'border-transparent text-surface-500 hover:text-surface-700'
        "
        @click="handleTabChange('handover')"
      >
        {{ $t('repair_plan.dashboard.tab.handover') }}
      </button>
    </div>

    <!-- ===== タイムラインタブ ===== -->
    <div v-if="activeTab === 'timeline'">
      <!-- 年度範囲フォーム -->
      <div class="mb-6 flex flex-wrap items-center justify-end gap-3">
        <div class="flex items-center gap-2">
          <label class="text-sm text-surface-600 dark:text-surface-300">
            {{ $t('repair_plan.timeline.year_from') }}
          </label>
          <InputNumber
            v-model="yearFrom"
            :min="1900"
            :max="yearTo - 1"
            :use-grouping="false"
            class="w-24"
            input-class="text-center"
          />
        </div>
        <span class="text-surface-400">〜</span>
        <div class="flex items-center gap-2">
          <label class="text-sm text-surface-600 dark:text-surface-300">
            {{ $t('repair_plan.timeline.year_to') }}
          </label>
          <InputNumber
            v-model="yearTo"
            :min="yearFrom + 1"
            :max="2100"
            :use-grouping="false"
            class="w-24"
            input-class="text-center"
          />
        </div>
        <Button
          :label="$t('button.update')"
          icon="pi pi-refresh"
          size="small"
          @click="handleTimelineRefresh"
        />
      </div>

      <PageLoading v-if="timelineLoading" />

      <DashboardEmptyState
        v-else-if="!timelineData || timelineData.labels.length === 0"
        icon="pi pi-chart-bar"
        :message="$t('repair_plan.timeline.no_data')"
      />

      <SectionCard v-else :title="$t('repair_plan.timeline.title')">
        <StratifiedTimeline :data="timelineData" />
      </SectionCard>
    </div>

    <!-- ===== カンバンタブ ===== -->
    <div v-else-if="activeTab === 'kanban'">
      <!-- カンバン一覧ヘッダ -->
      <div v-if="!selectedKanban" class="mb-4 flex items-center justify-between">
        <h2 class="text-base font-semibold text-surface-700 dark:text-surface-200">
          {{ $t('repair_plan.kanban.title') }}
        </h2>
        <Button
          v-if="isAdminOrDeputy"
          :label="$t('repair_plan.kanban.create')"
          icon="pi pi-plus"
          size="small"
          @click="openCreateDialog"
        />
      </div>

      <!-- 組織ID未取得の警告 -->
      <Message
        v-if="!kanbanLoading && organizationId === null && !selectedKanban"
        severity="warn"
        :closable="false"
        class="mb-4"
      >
        {{ $t('repair_plan.kanban.no_organization') }}
      </Message>

      <PageLoading v-if="kanbanLoading" />

      <!-- カンバン一覧 -->
      <div v-else-if="!selectedKanban">
        <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <SectionCard
            v-for="kanban in kanbans"
            :key="kanban.id"
            class="cursor-pointer transition-shadow hover:shadow-md"
            @click="openKanban(kanban)"
          >
            <div class="mb-1 flex items-start justify-between gap-2">
              <h3 class="font-semibold text-surface-800 dark:text-surface-100">
                {{ kanban.title }}
              </h3>
              <span
                class="inline-flex shrink-0 items-center rounded-full px-2 py-0.5 text-xs font-medium"
                :class="{
                  'bg-green-100 text-green-700': kanban.status === 'OPEN',
                  'bg-surface-200 text-surface-600': kanban.status === 'CLOSED',
                  'bg-blue-100 text-blue-700': kanban.status === 'AWARDED',
                  'bg-red-100 text-red-700': kanban.status === 'CANCELED',
                }"
              >
                {{ $t(`repair_plan.kanban.status.${kanban.status.toLowerCase()}`) }}
              </span>
            </div>
            <p class="text-xs text-surface-500 dark:text-surface-400">
              {{ $t('repair_plan.kanban.deadline.label') }}:
              {{ formatDate(kanban.bidDeadlineAt) }}
            </p>
            <p class="mt-1 text-xs text-surface-400">
              {{ $t('repair_plan.kanban.card_count', { count: kanban.cards.length }) }}
            </p>
          </SectionCard>
        </div>

        <DashboardEmptyState
          v-if="kanbans.length === 0 && !kanbanLoading"
          icon="pi pi-table"
          :message="$t('repair_plan.kanban.no_kanbans')"
          class="mt-4"
        />
      </div>

      <!-- カンバン詳細ボード -->
      <div v-else>
        <div class="mb-4 flex items-center gap-3">
          <Button
            icon="pi pi-arrow-left"
            text
            rounded
            size="small"
            :aria-label="$t('button.back')"
            @click="selectedKanban = null"
          />
          <span class="text-sm text-surface-500 dark:text-surface-400">
            {{ $t('repair_plan.kanban.title') }}
          </span>
        </div>
        <QuoteKanbanBoard
          :kanban="selectedKanban"
          :can-edit="isAdminOrDeputy"
          @card-moved="handleCardMoved"
        />
      </div>
    </div>

    <!-- ===== 申し送りタブ ===== -->
    <div v-if="activeTab === 'handover'" class="space-y-6">
      <MemberTermManager :team-id="teamSlug" :is-admin="isAdmin" />
      <HandoverPackBuilder
        scope-type="teams"
        :scope-id="teamSlug"
        :team-id="teamSlug"
        @generated="handlePackGenerated"
      />
      <HandoverPackHistoryList
        ref="handoverHistoryRef"
        scope-type="teams"
        :scope-id="teamSlug"
        :is-admin="isAdmin"
      />
    </div>

    <!-- カンバン作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      :header="$t('repair_plan.kanban.create')"
      modal
      class="w-full max-w-lg"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ $t('repair_plan.kanban.form.title') }}
          </label>
          <InputText
            v-model="createForm.title"
            class="w-full"
            :placeholder="$t('repair_plan.kanban.form.title_placeholder')"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ $t('repair_plan.kanban.deadline.label') }}
          </label>
          <InputText
            v-model="createForm.bidDeadlineAt"
            type="datetime-local"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ $t('repair_plan.kanban.visibility.label') }}
          </label>
          <Select
            v-model="createForm.visibilityToMember"
            :options="['HIDDEN', 'ANONYMIZED', 'FULL']"
            :option-label="(v: string) => $t(`repair_plan.kanban.visibility.${v.toLowerCase()}`)"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button :label="$t('button.cancel')" text @click="showCreateDialog = false" />
        <Button :label="$t('button.create')" @click="handleCreateKanban" />
      </template>
    </Dialog>
  </div>
</template>
