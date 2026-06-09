<script setup lang="ts">
import type {
  TournamentResponse,
  TournamentParticipant,
  EntryMemberSummary,
} from '~/types/tournament'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgSlug = String(route.params.slug)
const tId = Number(route.params.tId)

const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)
const { getTournament, getParticipants, getEntrySummary } = useTournamentApi()
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
    const res = await getParticipants(orgSlug, tId, divId)
    participantsMap.value[divId] = res.data
    // 管理者のみエントリーサマリーを取得
    if (isAdminOrDeputy.value) {
      const summary = await getEntrySummary(orgSlug, tId, divId)
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
      const summary = await getEntrySummary(orgSlug, tId, divId)
      entrySummaryMap.value[divId] = summary
    } catch {
      // サマリー更新失敗はサイレントで続行
    }
  }
}

onMounted(async () => {
  try {
    await loadPermissions()
    const res = await getTournament(orgSlug, tId)
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
      <BackButton :to="`/organizations/${orgSlug}/tournaments`" label="大会一覧に戻る" />
      <NuxtLink
        :to="`/organizations/${orgSlug}/tournaments/${tId}/rosters`"
        class="ml-auto flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm text-surface-600 transition hover:border-primary-400 hover:text-primary"
      >
        <i class="pi pi-list-check" />
        {{ $t('tournament.roster.title') }}
      </NuxtLink>
      <NuxtLink
        :to="`/organizations/${orgSlug}/tournaments/${tId}/communication`"
        class="flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm text-surface-600 transition-colors hover:bg-surface-100"
      >
        <i class="pi pi-comments text-sm" />
        {{ t('tournament.communication.title') }}
      </NuxtLink>
      <NuxtLink
        :to="`/organizations/${orgSlug}/tournaments/${tId}/submissions`"
        class="flex items-center gap-1 rounded-lg border border-surface-200 px-3 py-1.5 text-xs font-medium text-surface-600 transition hover:border-primary-400 hover:text-primary-600"
      >
        <i class="pi pi-file-edit text-xs" />
        {{ $t('tournament.submission.nav_link') }}
      </NuxtLink>
      <NuxtLink
        :to="`/organizations/${orgSlug}/tournaments/${tId}/files`"
        class="flex items-center gap-1.5 rounded-lg border border-surface-200 bg-surface-0 px-3 py-1.5 text-sm text-surface-600 transition-colors hover:bg-surface-100"
      >
        <i class="pi pi-folder text-amber-500" />
        {{ $t('tournament.files.title') }}
      </NuxtLink>
      <NuxtLink
        :to="`/organizations/${orgSlug}/tournaments/${tId}/fees`"
        class="flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm text-surface-600 transition hover:border-primary-400 hover:text-primary-600"
      >
        <i class="pi pi-money-bill text-sm" />
        <span>{{ $t('tournament.fees.title') }}</span>
      </NuxtLink>
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
      :org-id="orgSlug"
      :tournament-id="tId"
      :division-id="selectedDivisionId"
      :participant-id="selectedParticipant.id"
      :team-id="String(selectedParticipant.teamId)"
      :is-admin="isAdminOrDeputy"
      @close="selectedParticipant = null"
      @saved="onEntrySaved"
    />
  </div>
</template>
