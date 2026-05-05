/**
 * F02.5 行動メモ — Stats（気分統計）ドメイン。
 *
 * <p>Phase 3 リファクタにて {@code useActionMemoApi.ts} から分離。
 * getMoodStats の 1 関数を提供する。</p>
 */
import type { Mood, MoodStatsResponse } from '~/types/actionMemo'
import { ACTION_MEMO_BASE, rethrow } from './shared/normalize'

export function useActionMemoStats() {
  const api = useApi()
  const BASE = ACTION_MEMO_BASE

  type RawMoodStatsResponse = {
    total: number
    distribution: Record<string, number>
  }

  async function getMoodStats(params: { from: string; to: string }): Promise<MoodStatsResponse> {
    const query = new URLSearchParams()
    query.set('from', params.from)
    query.set('to', params.to)
    try {
      const res = await api<{ data: RawMoodStatsResponse }>(`${BASE}/mood-stats?${query.toString()}`)
      return {
        total: res.data.total,
        distribution: (res.data.distribution ?? {}) as Partial<Record<Mood, number>>,
      }
    } catch (error) {
      rethrow(error)
    }
  }

  return {
    getMoodStats,
  }
}
