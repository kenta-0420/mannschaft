export type TournamentFormat = 'LEAGUE' | 'KNOCKOUT' | 'GROUP_KNOCKOUT'
export type TournamentStatus = 'DRAFT' | 'OPEN' | 'IN_PROGRESS' | 'COMPLETED' | 'ARCHIVED'

// ===== TournamentResponse ネスト化 DTO =====

export interface TournamentScopeDto {
  organizationId?: number
  templateId?: number
  previousTournamentId?: number
}

export interface TournamentContentDto {
  name?: string
  description?: string | null
  format?: TournamentFormat
  season?: string | null
  startDate?: string | null
  endDate?: string | null
}

export interface TournamentScoringDto {
  winPoints?: number
  drawPoints?: number
  lossPoints?: number
  hasDraw?: boolean
  hasSets?: boolean
  setsToWin?: number | null
  hasExtraTime?: boolean
  hasPenalties?: boolean
  scoreUnitLabel?: string | null
  bonusPointRules?: string | null
}

export interface TournamentStructureDto {
  leagueRoundType?: string | null
  knockoutLegs?: number | null
  visibility?: string
  status?: TournamentStatus
}

export interface TournamentAuditDto {
  version?: number
  createdBy?: number | null
  createdAt?: string
  updatedAt?: string
}

export interface TiebreakerResponse {
  id?: number
  priority?: number
  criteria?: string
  direction?: string
}

export interface StatDefResponse {
  id?: number
  name?: string
  statKey?: string
  unit?: string
  dataType?: string
  aggregationType?: string
  isRankingTarget?: boolean
  rankingLabel?: string | null
  sortOrder?: number
}

export interface TournamentResponse {
  id: number
  scope?: TournamentScopeDto
  content?: TournamentContentDto
  scoring?: TournamentScoringDto
  structure?: TournamentStructureDto
  tiebreakers?: TiebreakerResponse[]
  statDefs?: StatDefResponse[]
  audit?: TournamentAuditDto
}

// ===== TournamentDivision ネスト化 DTO =====

export interface DivisionSlotsDto {
  promotionSlots?: number
  relegationSlots?: number
  playoffPromotionSlots?: number
  maxParticipants?: number
  minEntryCount?: number | null
  maxEntryCount?: number | null
  sortOrder?: number
}

export interface DivisionAuditDto {
  createdAt?: string
  updatedAt?: string
}

export interface TournamentDivision {
  id: number
  tournamentId?: number
  name?: string
  level?: number
  slots?: DivisionSlotsDto
  audit?: DivisionAuditDto
}

export interface TournamentParticipant {
  id: number
  divisionId: number
  teamId: number
  teamName: string
  teamLogoUrl: string | null
  registeredAt: string
}

export interface TournamentMatchday {
  id: number
  divisionId: number
  roundNumber: number
  matchDate: string | null
  matches: TournamentMatch[]
}

// ===== TournamentMatch ネスト化 DTO =====

export interface MatchParticipantsDto {
  homeParticipantId?: number
  awayParticipantId?: number
  winnerParticipantId?: number | null
}

export interface MatchScoreDto {
  homeScore?: number | null
  awayScore?: number | null
  homeExtraScore?: number | null
  awayExtraScore?: number | null
  homePenaltyScore?: number | null
  awayPenaltyScore?: number | null
}

export interface MatchInfoDto {
  matchNumber?: number | null
  scheduledDatetime?: string | null
  venue?: string | null
  result?: string | null
  leg?: number | null
  notes?: string | null
  status?: string
  nextMatchId?: number | null
  nextMatchSlot?: string | null
  scheduleId?: number | null
}

export interface MatchAuditDto {
  version?: number
  createdAt?: string
  updatedAt?: string
}

export interface TournamentMatch {
  id: number
  matchdayId?: number
  participants?: MatchParticipantsDto
  score?: MatchScoreDto
  info?: MatchInfoDto
  sets?: Array<{ setNumber: number; homeScore: number; awayScore: number }>
  playerStats?: unknown[]
  audit?: MatchAuditDto
}

// ===== TournamentStanding ネスト化 DTO =====

export interface StandingMetaDto {
  divisionId?: number
  participantId?: number
}

export interface StandingTeamDto {
  teamId?: number
  teamName?: string
  rank?: number
}

export interface StandingRecordDto {
  played?: number
  wins?: number
  draws?: number
  losses?: number
}

export interface StandingScoreDto {
  scoreFor?: number
  scoreAgainst?: number
  scoreDifference?: number
  points?: number
  bonusPoints?: number
  setsWon?: number | null
  setsLost?: number | null
}

export interface TournamentStanding {
  id?: number
  meta?: StandingMetaDto
  team?: StandingTeamDto
  record?: StandingRecordDto
  score?: StandingScoreDto
  form?: unknown[]
  status?: string
}

// ===== IndividualRanking ネスト化 DTO =====

export interface IndividualRankingContextDto {
  tournamentId?: number
  userId?: number
  participantId?: number
  matchesPlayed?: number
}

export interface IndividualRankingStatDto {
  statKey?: string
  rankingLabel?: string | null
  totalValueInt?: number | null
  totalValueDecimal?: number | null
  totalValueTime?: number | null
}

export interface IndividualRanking {
  id?: number
  context?: IndividualRankingContextDto
  stat?: IndividualRankingStatDto
  rank?: number
  lastCalculatedAt?: string | null
}

export interface TournamentTemplate {
  id: number
  organizationId: number
  name: string
  sportCategory: string
  format: TournamentFormat
  winPoints: number
  drawPoints: number
  lossPoints: number
  tiebreakers: string[]
  statDefs: Array<{ key: string; label: string; aggregationType: string }>
}

export interface TournamentPreset {
  id: number
  name: string
  sportCategory: string
  format: TournamentFormat
  winPoints: number
  drawPoints: number
  lossPoints: number
  tiebreakers: string[]
  statDefs: Array<{ key: string; label: string; aggregationType: string }>
}

export interface TournamentMatrix {
  divisionId: number
  participants: Array<{ id: number; teamName: string }>
  results: Array<
    Array<{ homeScore: number | null; awayScore: number | null; matchId: number | null }>
  >
}

// ===== PromotionRecord ネスト化 DTO =====

export interface PromotionRecordContextDto {
  tournamentId?: number
  teamId?: number
}

export interface PromotionRecordDetailDto {
  fromDivisionId?: number
  toDivisionId?: number
  type?: string
  finalRank?: number | null
  reason?: string | null
}

export interface PromotionRecordExecutionDto {
  executedBy?: number
  executedAt?: string
}

export interface PromotionRecord {
  id?: string
  context?: PromotionRecordContextDto
  detail?: PromotionRecordDetailDto
  execution?: PromotionRecordExecutionDto
}

export interface TournamentRoster {
  id: number
  matchId: number
  teamId: number
  userId: number
  displayName: string
  jerseyNumber: number | null
  position: string | null
}

// ===== TeamTournamentHistoryResponse ネスト化 DTO =====

/** @deprecated 旧型。新規実装では TeamTournamentHistoryResponse / TeamTournamentStatsResponse を使うこと */
export interface TournamentHistory {
  tournamentId: number
  title: string
  seasonYear: number
  divisionName: string
  finalRank: number | null
  played: number
  won: number
  drawn: number
  lost: number
  points: number
  organizationId: number
  divisionId: number
  participantId: number
}

/** @deprecated 旧型。新規実装では TeamTournamentStatsResponse を使うこと */
export interface TournamentTeamStats {
  totalTournaments: number
  totalMatches: number
  wins: number
  draws: number
  losses: number
  goalsFor: number
  goalsAgainst: number
}

export interface TournamentHistoryEntryMeta {
  tournamentName?: string
  season?: string | null
  divisionName?: string
  finalRank?: number | null
}

export interface TournamentHistoryEntryIdentifiers {
  tournamentId?: number
  divisionId?: number
  participantId?: number
}

export interface TournamentHistoryEntryRecord {
  played?: number
  wins?: number
  draws?: number
  losses?: number
  points?: number
}

export interface TournamentHistoryEntry {
  organizationId?: number
  meta?: TournamentHistoryEntryMeta
  identifiers?: TournamentHistoryEntryIdentifiers
  record?: TournamentHistoryEntryRecord
}

export interface TeamTournamentHistoryResponse {
  teamId?: number
  history?: TournamentHistoryEntry[]
}

export interface TeamTournamentStatsResponse {
  teamId?: number
  totalTournaments?: number
  totalPlayed?: number
  totalWins?: number
  totalDraws?: number
  totalLosses?: number
  totalScoreFor?: number
  totalScoreAgainst?: number
  bestRank?: number | null
}

// ===== Phase 9: 大会エントリー表機能 =====

export interface TournamentEntryMember {
  id: string
  participantId: number
  userId: number
  displayName: string
  memberNumber: string | null
  position: string | null
  jerseyNumber: number | null
  notes: string | null
  sortOrder: number
  createdAt: string
  updatedAt: string
}

export interface TeamMemberCandidate {
  userId: number
  displayName: string
  memberNumber: string | null
  position: string | null
  isAlreadyEntered: boolean
}

export interface EntryMemberListResponse {
  entryMembers: TournamentEntryMember[]
  teamMemberCandidates?: TeamMemberCandidate[]
  entryCount: number
  minEntryCount: number | null
  maxEntryCount: number | null
}

export interface EntryLoadResponse {
  added: number
  skipped: number
  total: number
  entryMembers: TournamentEntryMember[]
}

export interface EntryMemberSummaryItem {
  participantId: number
  teamId: number
  displayName: string
  entryCount: number
  isMinMet: boolean
  isMaxExceeded: boolean
  lastUpdatedAt: string | null
}

export interface EntryMemberSummary {
  divisionId: number
  divisionName: string
  minEntryCount: number | null
  maxEntryCount: number | null
  summary: EntryMemberSummaryItem[]
}

// ===== Phase 9-B: エントリーテンプレート管理 =====

export interface EntryTemplate {
  id: string
  name: string
  description: string | null
  sortOrder: number
  memberCount: number
  updatedAt: string
}

export interface EntryTemplateMember {
  id: string
  userId: number
  displayName: string
  jerseyNumber: number | null
  position: string | null
  sortOrder: number
}

export interface EntryTemplateDetail extends Omit<EntryTemplate, 'memberCount'> {
  members: EntryTemplateMember[]
}

export interface ApplyTemplateResponse {
  applied: number
  skipped: number
  skippedInactive: number
  total: number
  entryMembers: TournamentEntryMember[]
}

// ──────────────────────────────────────────────────
// F08.7.1 / 02: 成績ウィジェット用の追加型
// バックエンド StandingsController / OrganizationTournamentSummaryController に対応。
// ──────────────────────────────────────────────────

/** チーム通算成績（GET /teams/{id}/tournament-stats） */
export interface TeamTournamentStats {
  teamId: number
  totalTournaments: number
  totalPlayed: number
  totalWins: number
  totalDraws: number
  totalLosses: number
  totalScoreFor: number
  totalScoreAgainst: number
  bestRank: number | null
}

/** チーム大会参加履歴の 1 エントリ（GET /teams/{id}/tournament-history） */
export interface TeamTournamentHistoryEntry {
  organizationId: number
  meta: {
    tournamentName: string
    season: string | null
    divisionName: string
    finalRank: number | null
  }
  identifiers: {
    tournamentId: number
    divisionId: number
    participantId: number
  }
  record: {
    played: number
    wins: number
    draws: number
    losses: number
    points: number
  }
}

export interface TeamTournamentHistory {
  teamId: number
  history: TeamTournamentHistoryEntry[]
}

/** 順位表の 1 行（GET .../divisions/{divId}/standings） */
export interface TournamentStanding {
  id: number
  meta: { divisionId: number; participantId: number }
  team: { teamId: number; teamName: string; rank: number | null }
  record: { played: number; wins: number; draws: number; losses: number }
  score: {
    scoreFor: number
    scoreAgainst: number
    scoreDifference: number
    points: number
    bonusPoints: number
    setsWon: number
    setsLost: number
  }
  form: string | null
  status: { promotionZone: string | null; lastCalculatedAt: string | null }
}

/** 主催大会サマリ（GET /organizations/{orgId}/tournaments/summary） */
export interface OrganizationTournamentSummaryDivision {
  divisionId: number
  name: string
  participantCount: number
  leaderTeamName: string | null
}

export interface OrganizationTournamentSummaryEntry {
  tournamentId: number
  name: string
  status: string
  divisions: OrganizationTournamentSummaryDivision[]
}

export interface OrganizationTournamentSummary {
  tournaments: OrganizationTournamentSummaryEntry[]
}
