/**
 * F02.5 行動メモ — Publish（タイムライン投稿）ドメイン。
 *
 * <p>Phase 3 リファクタにて {@code useActionMemoApi.ts} から分離。
 * publishDaily / publishToTeam / publishDailyToTeam の 3 関数を提供する。</p>
 */
import type {
  PublishDailyPayload,
  PublishDailyResponse,
  PublishDailyToTeamPayload,
  PublishToTeamPayload,
} from '~/types/actionMemo'
import { ACTION_MEMO_BASE, rethrow } from './shared/normalize'

export function useActionMemoPublish() {
  const api = useApi()
  const BASE = ACTION_MEMO_BASE

  type RawPublishDailyResponse = {
    timeline_post_id: number
    memo_count: number
    memo_date: string
  }

  /**
   * 当日分（または指定日分）のメモをまとめて PERSONAL タイムラインに投稿する。
   *
   * <p>設計書 §4 §5.4 に基づく「今日を締める」儀式。サーバー側は 0 件の日は 400、
   * 1 分 5 回超の連打は 429、それ以外は 201 Created で {@code timeline_post_id} 等を返す。
   * 同日内の再実行は旧投稿を論理削除してから新規投稿を作り直す（冪等再実行）。</p>
   */
  async function publishDaily(payload: PublishDailyPayload = {}): Promise<PublishDailyResponse> {
    const body: Record<string, unknown> = {}
    if (payload.memoDate !== undefined) body.memo_date = payload.memoDate
    if (payload.extraComment !== undefined) body.extra_comment = payload.extraComment
    try {
      const res = await api<{ data: RawPublishDailyResponse }>(`${BASE}/publish-daily`, {
        method: 'POST',
        body,
      })
      return {
        timelinePostId: res.data.timeline_post_id,
        memoCount: res.data.memo_count,
        memoDate: res.data.memo_date,
      }
    } catch (error) {
      rethrow(error)
    }
  }

  /**
   * 個別メモをチームタイムラインに投稿する。
   * {@code POST /api/v1/action-memos/{memoId}/publish-to-team}
   */
  async function publishToTeam(memoId: number, payload: PublishToTeamPayload): Promise<void> {
    const body: Record<string, unknown> = {}
    if (payload.teamId !== undefined) body.team_id = payload.teamId
    if (payload.extraComment !== undefined) body.extra_comment = payload.extraComment
    try {
      await api(`${BASE}/${memoId}/publish-to-team`, {
        method: 'POST',
        body,
      })
    } catch (error) {
      rethrow(error)
    }
  }

  /**
   * 今日の WORK メモを一括チーム投稿する。
   * {@code POST /api/v1/action-memos/publish-daily-to-team}
   */
  async function publishDailyToTeam(payload: PublishDailyToTeamPayload = {}): Promise<void> {
    const body: Record<string, unknown> = {}
    if (payload.teamId !== undefined) body.team_id = payload.teamId
    try {
      await api(`${BASE}/publish-daily-to-team`, {
        method: 'POST',
        body,
      })
    } catch (error) {
      rethrow(error)
    }
  }

  return {
    publishDaily,
    publishToTeam,
    publishDailyToTeam,
  }
}
