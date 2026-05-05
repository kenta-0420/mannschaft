/**
 * F02.5 行動メモ — Settings ドメイン。
 *
 * <p>Phase 3 リファクタにて {@code useActionMemoApi.ts} から分離。
 * getSettings / updateSettings の 2 関数を提供する。</p>
 */
import type { ActionMemoSettings } from '~/types/actionMemo'
import {
  ACTION_MEMO_SETTINGS_BASE,
  normalizeSettings,
  rethrow,
  type RawSettings,
} from './shared/normalize'

export function useActionMemoSettings() {
  const api = useApi()
  const SETTINGS_BASE = ACTION_MEMO_SETTINGS_BASE

  async function getSettings(): Promise<ActionMemoSettings> {
    try {
      const res = await api<{ data: RawSettings }>(SETTINGS_BASE)
      return normalizeSettings(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  async function updateSettings(payload: Partial<ActionMemoSettings>): Promise<ActionMemoSettings> {
    const body: Record<string, unknown> = {}
    if (payload.moodEnabled !== undefined) body.mood_enabled = payload.moodEnabled
    // Phase 3
    if (payload.defaultPostTeamId !== undefined) body.default_post_team_id = payload.defaultPostTeamId
    if (payload.defaultCategory !== undefined) body.default_category = payload.defaultCategory
    // Phase 4-β
    if (payload.reminderEnabled !== undefined) body.reminder_enabled = payload.reminderEnabled
    if (payload.reminderTime !== undefined) body.reminder_time = payload.reminderTime
    try {
      const res = await api<{ data: RawSettings }>(SETTINGS_BASE, {
        method: 'PATCH',
        body,
      })
      return normalizeSettings(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  return {
    getSettings,
    updateSettings,
  }
}
