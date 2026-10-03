// @vitest-environment happy-dom
//
// 検査対象は純関数だけで、Nuxt ランタイム（app context）を必要としない。
// environment の選択理由は surveyDisplayMode.spec.ts と同じ。
import { describe, it, expect } from 'vitest'
import {
  canManageSurvey,
  canRemindSurvey,
  canViewSurveyTeamBreakdown,
  type SurveyViewerCapabilityInput,
} from '~/utils/surveyViewerCapabilities'

/**
 * CMP-041 AC-27 — 権限を持たない副管理者に管理導線を出さない。
 *
 * BE は survey の管理操作を「ADMIN または MANAGE_SURVEYS 保有 DEPUTY_ADMIN」へ締めた。
 * FE がロール名（DEPUTY_ADMIN であること）だけで出し分けていると、
 * 権限を持たない副管理者に「押すと必ず 403 になるボタン」が見える。
 * 詳細応答の viewerCanManage / viewerCanViewTeamBreakdown に従うことをここで固定する。
 */
describe('surveyViewerCapabilities（CMP-041 管理導線の出し分け）', () => {
  /** 権限を持たない副管理者に対して BE が返す詳細応答（両フラグとも false）。 */
  const deputyWithoutPermission: SurveyViewerCapabilityInput = {
    viewerCanManage: false,
    viewerCanViewTeamBreakdown: false,
  }

  /** ADMIN または MANAGE_SURVEYS 保有 DEPUTY_ADMIN に対する応答。 */
  const admin: SurveyViewerCapabilityInput = {
    viewerCanManage: true,
    viewerCanViewTeamBreakdown: true,
  }

  /** 作成者だが管理ロールは持たない利用者（内訳は作成者高速パスを持たないため false）。 */
  const creatorOnly: SurveyViewerCapabilityInput = {
    viewerCanManage: true,
    viewerCanViewTeamBreakdown: false,
  }

  describe('AC-27: 権限を持たない副管理者', () => {
    it('管理操作（締切・設問追加・回答者一覧）を出さない', () => {
      expect(canManageSurvey(deputyWithoutPermission)).toBe(false)
    })

    it('督促送信を出さない（公開中であっても）', () => {
      expect(canRemindSurvey(deputyWithoutPermission, 'PUBLISHED')).toBe(false)
    })

    it('チーム別内訳を出さない', () => {
      expect(canViewSurveyTeamBreakdown(deputyWithoutPermission)).toBe(false)
    })
  })

  describe('非回帰: 管理者・権限保有副管理者・作成者', () => {
    it('管理者には管理操作・内訳・督促がすべて出る', () => {
      expect(canManageSurvey(admin)).toBe(true)
      expect(canViewSurveyTeamBreakdown(admin)).toBe(true)
      expect(canRemindSurvey(admin, 'PUBLISHED')).toBe(true)
    })

    it('作成者には管理操作が出るが、内訳は BE の EP に合わせて出さない', () => {
      expect(canManageSurvey(creatorOnly)).toBe(true)
      // BE の getTeamBreakdown は作成者高速パスを持たない（ADMIN/権限保有 DEPUTY_ADMIN のみ）。
      // ここで true にすると「押すと 403」の導線が復活する。
      expect(canViewSurveyTeamBreakdown(creatorOnly)).toBe(false)
    })

    it('督促は公開中のみ（DRAFT / CLOSED では管理者でも出さない）', () => {
      expect(canRemindSurvey(admin, 'DRAFT')).toBe(false)
      expect(canRemindSurvey(admin, 'CLOSED')).toBe(false)
      expect(canRemindSurvey(admin, undefined)).toBe(false)
    })
  })

  describe('fail-closed: フラグが欠けた応答', () => {
    it('詳細未取得（null / undefined）では何も出さない', () => {
      expect(canManageSurvey(null)).toBe(false)
      expect(canManageSurvey(undefined)).toBe(false)
      expect(canViewSurveyTeamBreakdown(null)).toBe(false)
      expect(canRemindSurvey(null, 'PUBLISHED')).toBe(false)
    })

    it('フラグ欠落（undefined）は許可へ倒さない', () => {
      const missing = {
        viewerCanManage: undefined,
        viewerCanViewTeamBreakdown: undefined,
      } as SurveyViewerCapabilityInput
      expect(canManageSurvey(missing)).toBe(false)
      expect(canViewSurveyTeamBreakdown(missing)).toBe(false)
      expect(canRemindSurvey(missing, 'PUBLISHED')).toBe(false)
    })
  })
})
