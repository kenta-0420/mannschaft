<script setup lang="ts">
/**
 * F08.10 入口①: 大会の対戦表ページ（節／会場／日付グルーピング）＋A対B記録導線。
 *
 * ルート `[id]`=組織 publicId / `[tId]`=tournamentId。
 * ディビジョンごとに matchdays（節）と participants（参加チーム）を取得し、
 *   節（roundNumber）見出し ＞ 会場・日付グルーピング ＞ fixture カード（A 対 B・スコア・status）
 * の三段で表示する。各カードの「記録」ボタンから EventDetailPanel.recordMatch と同型の
 * by-fixture 解決→既存あれば live／無ければ createMatch→live の導線を起動する。
 *
 * 記録主体の決定: 自分の所属チーム（/me/teams の数値 id 集合）と fixture の home/away
 * participant.teamId を突合し、一致した participant を記録主体とする（home 一致=HOME・
 * away 一致=AWAY＝05 §H.1.2 home participant=HOME 固定）。一致が複数なら home 優先・
 * 一致ゼロなら記録不可（理由をトーストで提示し症状を隠さない）。
 */
import type {
  TournamentResponse,
  TournamentDivision,
  TournamentMatchday,
  TournamentMatch,
} from '~/types/tournament'
import {
  groupFixtures,
  resolveRecordTarget,
  type ParticipantInfo,
  type FixtureGroup,
} from '~/utils/tournamentFixtures'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const notification = useNotification()
const orgId = String(route.params.id)
const tId = Number(route.params.tId)

const { getTournament, getDivisions, getMatchdays, getParticipants } = useTournamentApi()
const { resolveContextByTeamId } = useMatchOrgContext()
const { resolveMatchByFixture, createMatch } = useMatchApi()

const tournament = ref<TournamentResponse | null>(null)
const loading = ref(true)
const loadError = ref(false)

/** participantId → 参加チーム情報（チーム名／数値 teamId）の引き当て表。 */
const participantMap = ref<Map<number, ParticipantInfo>>(new Map())

/** 自分が所属するチームの数値 id 集合（記録主体の突合に使う）。 */
const myTeamIds = ref<Set<number>>(new Set())

/** 節（matchday）単位のグルーピング表示モデル。 */
interface MatchdayGroup {
  matchdayId: number
  divisionId: number
  divisionName: string
  roundNumber: number
  groups: FixtureGroup[]
}

const matchdayGroups = ref<MatchdayGroup[]>([])

/** 記録処理中の fixtureId（多重起動防止）。 */
const recordingFixtureId = ref<number | null>(null)

// ===== 表示ヘルパー =====

const statusSeverity: Record<string, string> = {
  SCHEDULED: 'secondary',
  IN_PROGRESS: 'info',
  COMPLETED: 'success',
  POSTPONED: 'warn',
  CANCELLED: 'danger',
}

function statusLabel(status: string | undefined): string {
  if (!status) return ''
  return t(`match.fixtures.status.${status}`)
}

function statusSeverityOf(status: string | undefined): string {
  if (!status) return 'secondary'
  return statusSeverity[status] ?? 'secondary'
}

function participantName(participantId: number | undefined): string {
  if (participantId == null) return t('match.fixtures.tbd_participant')
  return participantMap.value.get(participantId)?.displayName ?? t('match.fixtures.tbd_participant')
}

function scoreText(fx: TournamentMatch): string {
  const h = fx.score?.homeScore
  const a = fx.score?.awayScore
  if (h == null || a == null) return t('match.fixtures.no_score')
  return `${h} - ${a}`
}

const { formatDate } = useDatetime()

function dateLabel(date: string | null): string {
  if (!date) return t('match.fixtures.date_tbd')
  return formatDate(date)
}

function venueLabel(venue: string | null): string {
  return venue && venue.trim() !== '' ? venue : t('match.fixtures.venue_tbd')
}

// ===== グルーピング／記録主体突合は ~/utils/tournamentFixtures の純関数を使用 =====

/** カードの「記録」ボタンの活性可否（自チームが参加している fixture のみ）。 */
function canRecord(fx: TournamentMatch): boolean {
  return resolveRecordTarget(fx, participantMap.value, myTeamIds.value) !== null
}

// ===== 記録導線（EventDetailPanel.recordMatch 踏襲） =====

async function recordFixture(fx: TournamentMatch): Promise<void> {
  if (fx.id == null || recordingFixtureId.value !== null) return
  const target = resolveRecordTarget(fx, participantMap.value, myTeamIds.value)
  if (!target) {
    notification.warn(t('match.fixtures.record_not_participant'))
    return
  }

  recordingFixtureId.value = fx.id
  try {
    // 1) 数値 teamId → 数値 orgId ＋ teamPublicId を解決（live 遷移先に publicId が要る）
    const ctx = await resolveContextByTeamId(target.selfTeamId)
    if (!ctx) return // 解決失敗時は composable 内で通知済み

    // 2) この fixture に紐づく既存 match があれば live を開く（二重起票防止）
    const existing = await resolveMatchByFixture(ctx.orgId, ctx.teamId, fx.id)
    if (existing?.id) {
      await navigateTo(`/teams/${ctx.teamPublicId}/matches/${existing.id}/live`)
      return
    }

    // 3) 無ければ fixture 情報をプリフィルして作成 → live へ
    const opponentInfo =
      target.opponentParticipantId != null
        ? participantMap.value.get(target.opponentParticipantId)
        : undefined
    const created = await createMatch(ctx.orgId, ctx.teamId, {
      kind: 'TOURNAMENT',
      tournamentFixtureId: fx.id,
      homeAway: target.homeAway,
      opponentTeamId: opponentInfo?.teamId,
      opponentName: opponentInfo?.displayName,
      kickoffAt: fx.info?.scheduledDatetime ?? undefined,
      venue: fx.info?.venue ?? undefined,
    })
    if (created.id) {
      await navigateTo(`/teams/${ctx.teamPublicId}/matches/${created.id}/live`)
    }
  } catch {
    // エラーは composable 内で通知済み（症状は隠さない）
  } finally {
    recordingFixtureId.value = null
  }
}

// ===== データ取得 =====

async function load(): Promise<void> {
  loading.value = true
  loadError.value = false
  try {
    const [tRes, divRes] = await Promise.all([
      getTournament(orgId, tId),
      getDivisions(orgId, tId),
    ])
    tournament.value = tRes.data
    const divisions: TournamentDivision[] = divRes.data ?? []

    const pMap = new Map<number, ParticipantInfo>()
    const groups: MatchdayGroup[] = []

    // 自分の所属チーム id 集合を取得（記録主体の突合用・失敗しても閲覧は継続）。
    try {
      const api = useApi()
      const meRes = await api<{ data: Array<{ id?: number }> }>('/api/v1/me/teams')
      myTeamIds.value = new Set(
        (meRes.data ?? []).map((tm) => tm.id).filter((id): id is number => typeof id === 'number'),
      )
    } catch {
      myTeamIds.value = new Set()
    }

    for (const div of divisions) {
      const [mdRes, pRes] = await Promise.all([
        getMatchdays(orgId, tId, div.id),
        getParticipants(orgId, tId, div.id),
      ])
      for (const p of pRes.data ?? []) {
        pMap.set(p.id, { teamId: p.teamId, displayName: p.teamName })
      }
      const matchdays: TournamentMatchday[] = mdRes.data ?? []
      for (const md of matchdays) {
        groups.push({
          matchdayId: md.id,
          divisionId: div.id,
          divisionName: div.name ?? '',
          roundNumber: md.roundNumber,
          groups: groupFixtures(md.matches ?? []),
        })
      }
    }

    participantMap.value = pMap
    // 節番号順（同節内はディビジョン順）に整える。
    groups.sort((a, b) => a.roundNumber - b.roundNumber || a.divisionId - b.divisionId)
    matchdayGroups.value = groups
  } catch {
    loadError.value = true
    notification.error(t('match.fixtures.load_failed'))
  } finally {
    loading.value = false
  }
}

const isEmpty = computed(
  () => matchdayGroups.value.every((mg) => mg.groups.length === 0),
)

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between gap-3">
      <BackButton :to="`/organizations/${orgId}/tournaments/${tId}`" :label="$t('match.fixtures.back')" />
    </div>

    <h1 class="mb-4 text-xl font-bold">
      {{ tournament?.content?.name ?? '' }}
      <span class="ml-1 text-base font-normal text-surface-500">{{ $t('match.fixtures.title') }}</span>
    </h1>

    <PageLoading v-if="loading" size="40px" />

    <DashboardEmptyState
      v-else-if="loadError"
      icon="pi pi-exclamation-triangle"
      :message="$t('match.fixtures.load_failed')"
    />

    <DashboardEmptyState
      v-else-if="isEmpty"
      icon="pi pi-calendar-times"
      :message="$t('match.fixtures.empty')"
    />

    <div v-else class="space-y-8">
      <!-- 節（matchday）見出し -->
      <section
        v-for="md in matchdayGroups"
        :key="`md-${md.matchdayId}`"
        class="space-y-3"
      >
        <h2 class="flex items-center gap-2 border-b border-surface-200 pb-1 text-base font-semibold dark:border-surface-700">
          <span>{{ $t('match.fixtures.round', { n: md.roundNumber }) }}</span>
          <span v-if="md.divisionName" class="text-sm font-normal text-surface-500">{{ md.divisionName }}</span>
        </h2>

        <!-- 会場・日付グループ -->
        <div
          v-for="grp in md.groups"
          :key="`${md.matchdayId}-${grp.key}`"
          class="rounded-lg border border-surface-200 dark:border-surface-700"
        >
          <div class="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-t-lg bg-surface-50 px-3 py-2 text-sm dark:bg-surface-800">
            <span class="flex items-center gap-1 font-medium">
              <i class="pi pi-map-marker text-surface-400" />
              {{ venueLabel(grp.venue) }}
            </span>
            <span class="flex items-center gap-1 text-surface-500">
              <i class="pi pi-calendar text-surface-400" />
              {{ dateLabel(grp.date) }}
            </span>
          </div>

          <!-- fixture カード -->
          <ul class="divide-y divide-surface-100 dark:divide-surface-700">
            <li
              v-for="fx in grp.fixtures"
              :key="`fx-${fx.id}`"
              class="flex flex-wrap items-center gap-2 px-3 py-2.5"
            >
              <div class="flex min-w-0 flex-1 items-center gap-2">
                <span class="truncate font-medium">{{ participantName(fx.participants?.homeParticipantId) }}</span>
                <span class="shrink-0 text-sm text-surface-400">{{ scoreText(fx) }}</span>
                <span class="truncate font-medium">{{ participantName(fx.participants?.awayParticipantId) }}</span>
              </div>
              <Tag
                :value="statusLabel(fx.info?.status)"
                :severity="statusSeverityOf(fx.info?.status)"
                rounded
                class="shrink-0"
              />
              <Button
                :label="$t('match.fixtures.record')"
                icon="pi pi-play"
                size="small"
                outlined
                class="shrink-0"
                :disabled="!canRecord(fx)"
                :loading="recordingFixtureId === fx.id"
                @click="recordFixture(fx)"
              />
            </li>
          </ul>
        </div>
      </section>
    </div>
  </div>
</template>
