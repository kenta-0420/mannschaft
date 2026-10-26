/**
 * F02.5 行動メモ API クライアント — facade。
 *
 * <p>Backend は一部フィールドを {@code @JsonProperty} でスネークケース表現にしているため
 * （{@code memo_date}, {@code tag_ids}, {@code related_todo_id}, {@code timeline_post_id},
 * {@code created_at}, {@code updated_at}, {@code next_cursor}, {@code mood_enabled}）、
 * クライアント内でキャメルケース ⇔ スネークケースを明示的に変換する。
 * 共通正規化ロジックは {@code composables/actionMemo/shared/normalize.ts} に集約。</p>
 *
 * <p>レートリミット 429 応答は {@link ActionMemoRateLimitError} に再ラップして
 * {@code Retry-After} ヘッダーの秒数を呼び出し側へ伝える。</p>
 *
 * <h3>リファクタ履歴</h3>
 * <p>Phase 3（フロント技術的負債一掃）にて、753 行の単一ファイルを 7 ドメイン composable に分割した。
 * 本ファイルは下記 7 ドメインを束ねる薄い facade として機能し、既存の export 名・署名は
 * 100% 維持して呼び出し側の無変更を保証する。</p>
 *
 * <ul>
 *   <li>{@link useActionMemoMemo} — Memo CRUD + linkTodo + revertTodoCompletion + 監査ログ</li>
 *   <li>{@link useActionMemoTag} — Tag CRUD + add/remove tags to memo</li>
 *   <li>{@link useActionMemoSettings} — settings 取得・更新</li>
 *   <li>{@link useActionMemoPublish} — publishDaily / publishToTeam / publishDailyToTeam</li>
 *   <li>{@link useActionMemoStats} — getMoodStats</li>
 *   <li>{@link useActionMemoTeams} — チーム / 組織 / メンバー / メモ取得</li>
 *   <li>{@link useActionMemoWeekly} — 週次サマリ</li>
 * </ul>
 */
import { useActionMemoMemo } from './actionMemo/useActionMemoMemo'
import { useActionMemoPublish } from './actionMemo/useActionMemoPublish'
import { useActionMemoSettings } from './actionMemo/useActionMemoSettings'
import { useActionMemoStats } from './actionMemo/useActionMemoStats'
import { useActionMemoTag } from './actionMemo/useActionMemoTag'
import { useActionMemoTeams } from './actionMemo/useActionMemoTeams'
import { useActionMemoWeekly } from './actionMemo/useActionMemoWeekly'

export function useActionMemoApi() {
  const memo = useActionMemoMemo()
  const tag = useActionMemoTag()
  const settings = useActionMemoSettings()
  const publish = useActionMemoPublish()
  const stats = useActionMemoStats()
  const teams = useActionMemoTeams()
  const weekly = useActionMemoWeekly()

  return {
    // === Memo CRUD ===
    createMemo: memo.createMemo,
    fetchMemos: memo.fetchMemos,
    getMemo: memo.getMemo,
    updateMemo: memo.updateMemo,
    deleteMemo: memo.deleteMemo,
    linkTodo: memo.linkTodo,
    // === Settings ===
    getSettings: settings.getSettings,
    updateSettings: settings.updateSettings,
    // === Publish ===
    publishDaily: publish.publishDaily,
    // === Weekly Summary ===
    fetchWeeklySummaries: weekly.fetchWeeklySummaries,
    getWeeklySummary: weekly.getWeeklySummary,
    // === Tag CRUD ===
    getTags: tag.getTags,
    createTag: tag.createTag,
    updateTag: tag.updateTag,
    deleteTag: tag.deleteTag,
    addTagsToMemo: tag.addTagsToMemo,
    removeTagFromMemo: tag.removeTagFromMemo,
    // === Stats ===
    getMoodStats: stats.getMoodStats,
    // Phase 3
    fetchAvailableTeams: teams.fetchAvailableTeams,
    publishToTeam: publish.publishToTeam,
    publishDailyToTeam: publish.publishDailyToTeam,
    // Phase 4-β
    revertTodoCompletion: memo.revertTodoCompletion,
    fetchMemberMemos: teams.fetchMemberMemos,
    // Phase 5-1
    getMemoAuditLogs: memo.getMemoAuditLogs,
    // Phase 5-2
    fetchAvailableOrgs: teams.fetchAvailableOrgs,
    // Phase 6-1
    fetchTeamMembers: teams.fetchTeamMembers,
  }
}
