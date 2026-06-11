<script setup lang="ts">
import type {
  TournamentParticipant,
  EntryMemberSummary,
  TournamentResponse,
} from '~/types/tournament'
import {
  TOURNAMENT_VISIBILITY_LEVELS,
  isTournamentVisibility,
  type TournamentVisibility,
} from '~/utils/tournamentStandings'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgId = String(route.params.slug)
const tId = Number(route.params.tId)

const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)
const { getTournament, getParticipants, getEntrySummary, updateTournament } = useTournamentApi()
const notification = useNotification()

const tournament = ref<TournamentResponse | null>(null)
const loading = ref(true)
const activeDivisionId = ref<number | null>(null)

// ディビジョン別の参加チーム・エントリーサマリーをキャッシュ
const participantsMap = ref<Record<number, TournamentParticipant[]>>({})
const entrySummaryMap = ref<Record<number, EntryMemberSummary>>({})
const participantsLoading = ref<Record<number, boolean>>({})

// エントリーモーダル制御
const selectedParticipant = ref<TournamentParticipant | null>(null)
const selectedDivisionId = ref<number>(0)

// ===== ステータス表示ヘルパー =====

function getStatusLabel(s: string): string {
  const map: Record<string, string> = {
    DRAFT: '準備中',
    OPEN: '受付中',
    IN_PROGRESS: '開催中',
    COMPLETED: '終了',
    CANCELLED: '中止',
    ARCHIVED: 'アーカイブ',
  }
  return map[s] ?? s
}

function getStatusClass(s: string): string {
  const map: Record<string, string> = {
    DRAFT: 'bg-surface-100 text-surface-600',
    OPEN: 'bg-green-100 text-green-700',
    IN_PROGRESS: 'bg-blue-100 text-blue-700',
    COMPLETED: 'bg-purple-100 text-purple-700',
    CANCELLED: 'bg-red-100 text-red-600',
    ARCHIVED: 'bg-surface-200 text-surface-500',
  }
  return map[s] ?? 'bg-surface-100'
}

function getFormatLabel(f: string): string {
  const map: Record<string, string> = {
    LEAGUE: 'リーグ戦',
    KNOCKOUT: 'トーナメント',
    GROUP_KNOCKOUT: 'グループ+T',
  }
  return map[f] ?? f
}

// ===== エントリーサマリー =====

function getEntryCount(divId: number, participantId: number): string {
  const summary = entrySummaryMap.value[divId]
  if (!summary) return '-'
  const item = summary.summary.find(s => s.participantId === participantId)
  return item ? `${item.entryCount}名` : '0名'
}

// ===== データ取得 =====

async function loadParticipantsForDivision(divId: number) {
  if (participantsMap.value[divId] !== undefined) return
  participantsLoading.value[divId] = true
  try {
    const res = await getParticipants(orgId, tId, divId)
    participantsMap.value[divId] = res.data
    // 管理者のみエントリーサマリーを取得
    if (isAdminOrDeputy.value) {
      const summary = await getEntrySummary(orgId, tId, divId)
      entrySummaryMap.value[divId] = summary
    }
  } catch {
    notification.error('参加チームの取得に失敗しました')
  } finally {
    participantsLoading.value[divId] = false
  }
}

function onTabChange(divId: number) {
  activeDivisionId.value = divId
  loadParticipantsForDivision(divId)
}

function openEntryModal(participant: TournamentParticipant, divId: number) {
  selectedParticipant.value = participant
  selectedDivisionId.value = divId
}

async function onEntrySaved() {
  // 保存後はサマリーを再取得して表示を更新
  const divId = selectedDivisionId.value
  if (divId && isAdminOrDeputy.value) {
    try {
      const summary = await getEntrySummary(orgId, tId, divId)
      entrySummaryMap.value[divId] = summary
    } catch {
      // サマリー更新失敗はサイレントで続行
    }
  }
}

// ===== 可視性設定（org 管理者のみ） =====

const showVisibilityDialog = ref(false)
const savingVisibility = ref(false)
const visibilityForm = ref<TournamentVisibility>('PUBLIC')

/** 可視性 6 レベルのセレクタ選択肢（ラベル＋説明は i18n）。 */
const visibilityOptions = computed(() =>
  TOURNAMENT_VISIBILITY_LEVELS.map((level) => ({
    value: level,
    label: t(`tournament.visibility.levels.${level}.label`),
    description: t(`tournament.visibility.levels.${level}.description`),
  })),
)

function openVisibilityDialog() {
  const current = tournament.value?.structure?.visibility
  visibilityForm.value = isTournamentVisibility(current) ? current : 'PUBLIC'
  showVisibilityDialog.value = true
}

async function saveVisibility() {
  if (savingVisibility.value) return
  const version = tournament.value?.audit?.version
  if (version == null) {
    notification.error(t('tournament.visibility.saveFailed'))
    return
  }
  savingVisibility.value = true
  try {
    await updateTournament(orgId, tId, {
      visibility: visibilityForm.value,
      version,
    })
    // 楽観ロックの version を更新するため大会情報を再取得する。
    const res = await getTournament(orgId, tId)
    tournament.value = res.data
    notification.success(t('tournament.visibility.saved'))
    showVisibilityDialog.value = false
  } catch {
    notification.error(t('tournament.visibility.saveFailed'))
  } finally {
    savingVisibility.value = false
  }
}

onMounted(async () => {
  try {
    await loadPermissions()
    const res = await getTournament(orgId, tId)
    tournament.value = res.data
    if ((res.data.tiebreakers?.length ?? 0) > 0 || (res.data.statDefs?.length ?? 0) > 0) {
      // tiebreakers/statDefs は将来用。現在は divisions 相当の情報なし
    }
  } catch {
    notification.error('大会情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between gap-3">
      <BackButton :to="`/organizations/${orgId}/tournaments`" label="大会一覧に戻る" />
      <NuxtLink
        :to="`/organizations/${orgId}/tournaments/${tId}/standings`"
        class="ml-auto flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm text-surface-600 transition hover:border-primary-400 hover:text-primary"
      >
        <i class="pi pi-list" />
        {{ $t('tournament.standings.title') }}
      </NuxtLink>
      <NuxtLink
        :to="`/organizations/${orgId}/tournaments/${tId}/rankings`"
        class="flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm text-surface-600 transition hover:border-primary-400 hover:text-primary"
      >
        <i class="pi pi-chart-bar" />
        {{ $t('tournament.rankings.title') }}
      </NuxtLink>
      <NuxtLink
        :to="`/organizations/${orgId}/tournaments/${tId}/matrix`"
        class="flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm text-surface-600 transition hover:border-primary-400 hover:text-primary"
      >
        <i class="pi pi-table" />
        {{ $t('tournament.matrix.title') }}
      </NuxtLink>
      <NuxtLink
        :to="`/organizations/${orgId}/tournaments/${tId}/fixtures`"
        class="flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm text-surface-600 transition hover:border-primary-400 hover:text-primary"
      >
        <i class="pi pi-sitemap" />
        {{ $t('match.fixtures.title') }}
      </NuxtLink>
      <NuxtLink
        :to="`/organizations/${orgId}/tournaments/${tId}/rosters`"
        class="flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm text-surface-600 transition hover:border-primary-400 hover:text-primary"
      >
        <i class="pi pi-list-check" />
        {{ $t('tournament.roster.title') }}
      </NuxtLink>
      <NuxtLink
        :to="`/organizations/${orgId}/tournaments/${tId}/communication`"
        class="flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm text-surface-600 transition-colors hover:bg-surface-100"
      >
        <i class="pi pi-comments text-sm" />
        {{ t('tournament.communication.title') }}
      </NuxtLink>
      <NuxtLink
        :to="`/organizations/${orgId}/tournaments/${tId}/submissions`"
        class="flex items-center gap-1 rounded-lg border border-surface-200 px-3 py-1.5 text-xs font-medium text-surface-600 transition hover:border-primary-400 hover:text-primary-600"
      >
        <i class="pi pi-file-edit text-xs" />
        {{ $t('tournament.submission.nav_link') }}
      </NuxtLink>
      <NuxtLink
        :to="`/organizations/${orgId}/tournaments/${tId}/files`"
        class="flex items-center gap-1.5 rounded-lg border border-surface-200 bg-surface-0 px-3 py-1.5 text-sm text-surface-600 transition-colors hover:bg-surface-100"
      >
        <i class="pi pi-folder text-amber-500" />
        {{ $t('tournament.files.title') }}
      </NuxtLink>
      <NuxtLink
        :to="`/organizations/${orgId}/tournaments/${tId}/fees`"
        class="flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm text-surface-600 transition hover:border-primary-400 hover:text-primary-600"
      >
        <i class="pi pi-money-bill text-sm" />
        <span>{{ $t('tournament.fees.title') }}</span>
      </NuxtLink>
      <button
        v-if="isAdminOrDeputy"
        type="button"
        class="flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm text-surface-600 transition hover:border-primary-400 hover:text-primary-600"
        data-testid="visibility-settings-button"
        @click="openVisibilityDialog"
      >
        <i class="pi pi-eye text-sm" />
        <span>{{ $t('tournament.visibility.title') }}</span>
      </button>
    </div>

    <PageLoading v-if="loading" size="40px" />
    <template v-else-if="tournament">
      <!-- ヘッダー -->
      <div class="mb-6">
        <div class="mb-1 flex flex-wrap items-center gap-2">
          <span
            :class="getStatusClass(tournament.structure?.status ?? '')"
            class="rounded px-2 py-0.5 text-xs font-medium"
          >
            {{ getStatusLabel(tournament.structure?.status ?? '') }}
          </span>
          <span class="rounded bg-surface-100 px-1.5 py-0.5 text-xs">
            {{ getFormatLabel(tournament.content?.format ?? '') }}
          </span>
        </div>
        <h1 class="text-xl font-bold">{{ tournament.content?.name }}</h1>
        <div class="mt-1 flex flex-wrap items-center gap-3 text-sm text-surface-500">
          <span v-if="tournament.content?.season">{{ tournament.content.season }}</span>
          <span v-if="tournament.content?.startDate || tournament.content?.endDate">
            <template v-if="tournament.content?.startDate">{{ tournament.content.startDate }}</template>
            <template v-if="tournament.content?.startDate && tournament.content?.endDate">〜</template>
            <template v-if="tournament.content?.endDate">{{ tournament.content.endDate }}</template>
          </span>
        </div>
        <p v-if="tournament.content?.description" class="mt-2 text-sm text-surface-600">
          {{ tournament.content.description }}
        </p>
      </div>

      <!-- ディビジョン情報なし（新DTO では divisions は別エンドポイント管理のためメッセージのみ表示） -->
      <DashboardEmptyState
        v-if="!activeDivisionId"
        icon="pi pi-sitemap"
        message="部門が登録されていません"
      />

      <!-- ディビジョンタブ（部門がある場合のみ表示） -->
      <Tabs
        v-else
        :value="activeDivisionId"
        @update:value="onTabChange($event as number)"
      >
        <TabList>
          <Tab :value="activeDivisionId">
            部門
          </Tab>
        </TabList>

        <TabPanels>
          <TabPanel :value="activeDivisionId">
            <div class="mt-4">
              <PageLoading v-if="activeDivisionId && participantsLoading[activeDivisionId]" size="32px" />
              <template v-else>
                <DashboardEmptyState
                  v-if="!activeDivisionId || !participantsMap[activeDivisionId] || participantsMap[activeDivisionId]!.length === 0"
                  icon="pi pi-users"
                  message="参加チームがいません"
                />
                <DataTable
                  v-else
                  :value="participantsMap[activeDivisionId]"
                  class="text-sm"
                  striped-rows
                >
                  <Column field="teamName" header="チーム名" />
                  <Column header="エントリー数">
                    <template #body="{ data }">
                      {{ activeDivisionId ? getEntryCount(activeDivisionId, data.id) : '-' }}
                    </template>
                  </Column>
                  <Column v-if="isAdminOrDeputy" header="操作" style="width: 8rem">
                    <template #body="{ data }">
                      <Button
                        label="エントリー管理"
                        icon="pi pi-list"
                        size="small"
                        text
                        @click="activeDivisionId && openEntryModal(data, activeDivisionId)"
                      />
                    </template>
                  </Column>
                </DataTable>
              </template>
            </div>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>

    <!-- エントリー管理モーダル -->
    <TournamentEntryModal
      v-if="selectedParticipant !== null"
      :is-open="selectedParticipant !== null"
      :org-id="orgId"
      :tournament-id="tId"
      :division-id="selectedDivisionId"
      :participant-id="selectedParticipant.id"
      :team-id="String(selectedParticipant.teamId)"
      :is-admin="isAdminOrDeputy"
      @close="selectedParticipant = null"
      @saved="onEntrySaved"
    />

    <!-- 可視性設定ダイアログ（org 管理者のみ） -->
    <Dialog
      v-model:visible="showVisibilityDialog"
      modal
      :header="$t('tournament.visibility.title')"
      :style="{ width: '32rem' }"
    >
      <div class="flex flex-col gap-4 py-2">
        <p class="text-sm text-surface-500">{{ $t('tournament.visibility.description') }}</p>
        <Select
          v-model="visibilityForm"
          :options="visibilityOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          data-testid="visibility-select"
        >
          <template #option="{ option }">
            <div class="flex flex-col">
              <span class="font-medium">{{ option.label }}</span>
              <span class="text-xs text-surface-500">{{ option.description }}</span>
            </div>
          </template>
        </Select>
        <p class="rounded bg-surface-50 px-3 py-2 text-xs text-surface-600 dark:bg-surface-800">
          {{ $t(`tournament.visibility.levels.${visibilityForm}.description`) }}
        </p>
      </div>
      <template #footer>
        <Button
          :label="$t('common.cancel')"
          text
          :disabled="savingVisibility"
          @click="showVisibilityDialog = false"
        />
        <Button
          :label="$t('tournament.visibility.save')"
          icon="pi pi-check"
          :loading="savingVisibility"
          data-testid="visibility-save-button"
          @click="saveVisibility"
        />
      </template>
    </Dialog>
  </div>
</template>
