/**
 * 試合メンバー表 composable（F08.7.1/05）。
 *
 * 対象 API:
 *   GET    /api/v1/tournaments/{tId}/matches/{matchId}/rosters/me
 *   PUT    /api/v1/tournaments/{tId}/matches/{matchId}/rosters/me
 *   POST   /api/v1/tournaments/{tId}/matches/{matchId}/rosters/me/apply-template
 *   GET    /api/v1/tournaments/{tId}/matches/{matchId}/rosters          （主催者）
 *   PATCH  /api/v1/tournaments/{tId}/matches/{matchId}                  （締切設定）
 */
import type {
  FixtureRosterResponse,
  OrganizerRosterView,
  SubmitRosterRequest,
  ApplyRosterTemplateRequest,
} from '~/types/tournament'

export function useMatchRoster(tournamentId: number, matchId: number) {
  const api = useApi()
  const notification = useNotification()
  const { t } = useI18n()

  const base = `/api/v1/tournaments/${tournamentId}/matches/${matchId}`

  const myRoster = ref<FixtureRosterResponse | null>(null)
  const allRosters = ref<OrganizerRosterView[]>([])
  const loadingMy = ref(false)
  const loadingAll = ref(false)
  const submitting = ref(false)
  const applyingTemplate = ref(false)
  const updatingDeadline = ref(false)

  // ===== 自チームメンバー表取得 =====

  async function getMyRoster(): Promise<FixtureRosterResponse | null> {
    loadingMy.value = true
    try {
      const res = await api<{ data: FixtureRosterResponse }>(`${base}/rosters/me`)
      myRoster.value = res.data
      return res.data
    } catch (err) {
      notification.error(t('tournament.roster.error.load_failed'))
      throw err
    } finally {
      loadingMy.value = false
    }
  }

  // ===== 自チームメンバー表提出 =====

  async function submitMyRoster(request: SubmitRosterRequest): Promise<FixtureRosterResponse> {
    submitting.value = true
    try {
      const res = await api<{ data: FixtureRosterResponse }>(`${base}/rosters/me`, {
        method: 'PUT',
        body: request,
      })
      myRoster.value = res.data
      notification.success(t('tournament.roster.submit_success'))
      return res.data
    } catch (err) {
      notification.error(t('tournament.roster.error.submit_failed'))
      throw err
    } finally {
      submitting.value = false
    }
  }

  // ===== テンプレ適用（1 タップ） =====

  async function applyTemplate(req: ApplyRosterTemplateRequest): Promise<FixtureRosterResponse> {
    applyingTemplate.value = true
    try {
      const res = await api<{ data: FixtureRosterResponse }>(`${base}/rosters/me/apply-template`, {
        method: 'POST',
        body: req,
      })
      myRoster.value = res.data
      notification.success(t('tournament.roster.apply_template_success'))
      return res.data
    } catch (err) {
      notification.error(t('tournament.roster.error.apply_template_failed'))
      throw err
    } finally {
      applyingTemplate.value = false
    }
  }

  // ===== 全チームメンバー表閲覧（主催者） =====

  async function getAllRosters(): Promise<OrganizerRosterView[]> {
    loadingAll.value = true
    try {
      const res = await api<{ data: OrganizerRosterView[] }>(`${base}/rosters`)
      allRosters.value = res.data
      return res.data
    } catch (err) {
      notification.error(t('tournament.roster.error.load_all_failed'))
      throw err
    } finally {
      loadingAll.value = false
    }
  }

  // ===== 提出締切設定（主催者） =====

  async function updateDeadline(rosterDeadline: string | null): Promise<void> {
    updatingDeadline.value = true
    try {
      await api(`${base}`, {
        method: 'PATCH',
        body: { rosterDeadline },
      })
      notification.success(t('tournament.roster.deadline_updated'))
    } catch (err) {
      notification.error(t('tournament.roster.error.update_deadline_failed'))
      throw err
    } finally {
      updatingDeadline.value = false
    }
  }

  // ===== ヘルパー =====

  /** 現在時刻と rosterDeadline を比較し締切済みかどうかを返す */
  function isDeadlinePassed(deadline: string | null): boolean {
    if (!deadline) return false
    return new Date(deadline) < new Date()
  }

  return {
    myRoster,
    allRosters,
    loadingMy,
    loadingAll,
    submitting,
    applyingTemplate,
    updatingDeadline,
    getMyRoster,
    submitMyRoster,
    applyTemplate,
    getAllRosters,
    updateDeadline,
    isDeadlinePassed,
  }
}
