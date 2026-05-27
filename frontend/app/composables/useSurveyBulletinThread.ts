import type { BulletinThreadResponse } from '~/types/bulletin'

/**
 * アンケートに紐づく掲示板スレッド情報を取得する composable。
 *
 * GET /api/v1/surveys/{surveyId}/thread のラッパー。
 * スレッドが存在しない場合（HTTP 404）は null を返す。
 */
export function useSurveyBulletinThread() {
  const api = useApi()

  /**
   * 指定アンケートに紐づく掲示板スレッドを取得する。
   *
   * @param surveyId - アンケートID
   * @returns スレッド情報、またはスレッドが存在しない場合は null
   */
  async function getSurveyThread(surveyId: number | string): Promise<BulletinThreadResponse | null> {
    try {
      const res = await api<{ data: BulletinThreadResponse }>(`/api/v1/surveys/${surveyId}/thread`)
      return res.data
    } catch (err: unknown) {
      // 404 は「スレッド未生成」を意味するため null を返す（エラーは出さない）
      if (
        err !== null &&
        typeof err === 'object' &&
        'status' in err &&
        (err as { status: number }).status === 404
      ) {
        return null
      }
      throw err
    }
  }

  return { getSurveyThread }
}
