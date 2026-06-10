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
// F08.7.1 / 05: 試合メンバー表（自チーム作成＋テンプレ流用＋主催者締切管理）
// ──────────────────────────────────────────────────

/** 試合メンバー表の選手 1 行 */
export interface RosterPlayerResponse {
  id: number
  userId: number
  displayName: string
  isStarter: boolean | null
  jerseyNumber: number | null
  position: string | null
  registrationNumber: string | null
  uniformSetId: string | null
}

/** 試合メンバー表のベンチ入り役員 1 行 */
export interface RosterStaffResponse {
  id: string
  role: string
  name: string
  userId: number | null
}

/** 自チーム分の試合メンバー表（GET / PUT rosters/me のレスポンス） */
export interface MatchRosterResponse {
  matchId: number
  participantId: number
  teamId: number
  rosterDeadline: string | null
  locked: boolean
  players: RosterPlayerResponse[]
  staff: RosterStaffResponse[]
}

/** 主催者ビュー: 参加チーム単位の提出状況・内容 */
export interface OrganizerRosterView {
  participantId: number
  teamId: number
  teamDisplayName: string
  submitted: boolean
  playerCount: number
  staffCount: number
  players: RosterPlayerResponse[]
  staff: RosterStaffResponse[]
}

/** PUT rosters/me リクエスト: 選手エントリー */
export interface RosterPlayerEntry {
  userId: number
  isStarter?: boolean | null
  jerseyNumber?: number | null
  position?: string | null
  registrationNumber?: string | null
  uniformSetId?: string | null
}

/** PUT rosters/me リクエスト: ベンチ役員エントリー */
export interface RosterStaffEntry {
  role: string
  name: string
  userId?: number | null
}

/** PUT rosters/me リクエスト */
export interface SubmitRosterRequest {
  players?: RosterPlayerEntry[] | null
  staff?: RosterStaffEntry[] | null
}

/** POST rosters/me/apply-template リクエスト */
export interface ApplyRosterTemplateRequest {
  templateId: string
  overwriteExisting?: boolean
  defaultUniformSetId?: string | null
}

// ──────────────────────────────────────────────────
// F08.7.1 / 02 ②: 主催大会サマリ（ORG_TOURNAMENT_SUMMARY ウィジェット）
// GET /api/v1/organizations/{orgId}/tournaments/summary
// ──────────────────────────────────────────────────

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

// =====================================================================
// F08.7.1: 大会連絡スペース
// =====================================================================

/** 連絡スペース（大会・ディビジョン共通）。 */
export interface TournamentContactSpace {
  /** 連絡スペースID（UUID）。 */
  id: string
  /** 掲示板参照ID。null の場合は掲示板スペースなし。 */
  bulletinRefId: number | null
  /** チャットチャンネルID。null の場合はチャットスペースなし。 */
  chatChannelId: number | null
  /** 公開フラグ。false の場合は参加者のみ閲覧可能。 */
  isPublic: boolean
  /** スペース名称（任意）。 */
  name?: string | null
}

/** 連絡スペース一覧レスポンス。 */
export interface TournamentContactSpaceListResponse {
  data: TournamentContactSpace[]
}

/** 連絡スペース公開設定更新リクエスト。 */
export interface UpdateContactSpaceVisibilityRequest {
  isPublic: boolean
}

// ──────────────────────────────────────────────────
// F08.7.1 / FE-E: 書類提出受付
// /api/v1/organizations/{orgId}/tournaments/{tournamentId}/submission-requirements
// ──────────────────────────────────────────────────

export type SubmissionRequirementTarget = 'ALL' | 'SPECIFIC'
export type SubmissionStatusValue = 'NOT_SUBMITTED' | 'SUBMITTED' | 'APPROVED' | 'REJECTED'

export interface SubmissionRequirementResponse {
  id: number
  tournamentId: number
  formTemplateId: number
  formTemplateName: string | null
  deadline: string | null
  target: SubmissionRequirementTarget
  targetTeamIds: number[] | null
  requiresPayment: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateSubmissionRequirementRequest {
  formTemplateId: number
  deadline?: string | null
  target: SubmissionRequirementTarget
  targetTeamIds?: number[] | null
  requiresPayment?: boolean
}

export interface UpdateSubmissionRequirementRequest {
  formTemplateId?: number
  deadline?: string | null
  target?: SubmissionRequirementTarget
  targetTeamIds?: number[] | null
  requiresPayment?: boolean
}

export interface TeamSubmissionStatusItem {
  teamId: number
  teamName: string
  status: SubmissionStatusValue
  submittedAt: string | null
  formSubmissionId: number | null
}

export interface SubmissionStatusDashboardResponse {
  requirementId: number
  formTemplateName: string | null
  deadline: string | null
  teamStatuses: TeamSubmissionStatusItem[]
}

export interface SubmitForTeamRequest {
  formSubmissionId?: number
  values?: Array<{
    fieldKey: string
    fieldType?: string
    textValue?: string | null
    numberValue?: number | null
    dateValue?: string | null
    fileKey?: string | null
  }>
}

// ──────────────────────────────────────────────────
// F08.7.1: 大会参加費 Connect 決済（自分の参加費一覧・チェックアウト）
// GET  /api/v1/tournament-fees/my
// POST /api/v1/tournament-fees/{feeId}/checkout
// ──────────────────────────────────────────────────

export interface MyTournamentFeeItem {
  feeId: string
  tournamentId: number
  tournamentName: string
  divisionId: number | null
  divisionName: string | null
  title: string
  paymentItemId: number
  faceAmount: number
  payerSurcharge: number
  totalCharge: number
  dueDate: string | null
  alreadyPaid: boolean
  paidAt: string | null
}

export interface MyTournamentFeesResponse {
  fees: MyTournamentFeeItem[]
}

export interface TournamentFeeCheckoutRequest {
  idempotencyKey?: string
}

export interface TournamentFeeCheckoutResponse {
  clientSecret: string | null
  memberPaymentId: number
  escrowTransactionId: string
}
