/**
 * 予定の「作成先スコープ」を一意に指す鍵（key）の唯一の生成元。
 *
 * F03.19 実機E2E 欠陥2 の根治: 作成先の選択肢（`useMyCalendarData.availableScopes`）は
 * `TEAM:<slug>` 形式で鍵を作り、作成ダイアログ（`ScheduleEventForm.vue`）は
 * `team_<slug>` 形式で初期選択の鍵を組み立てていた。**形式が食い違うため
 * `scopeOptions.find(...)` が必ず外れ**、ダイアログを開いた直後はどのボタンにも
 * 選択状態が付かなかった（保存先自体は props フォールバックで正しかったため、
 * 「初期表示だけが壊れている」＝気付きにくい欠陥になっていた）。
 *
 * 再発を防ぐため、**鍵を作る場所をこの関数ただ1つに限る**。素の文字列連結
 * （`` `${scopeType}_${scopeId}` `` のような書き方）を新たに足してはならない。
 */

/** 個人の予定を指す鍵（スコープ種別・IDを持たない唯一の特別扱い）。 */
export const PERSONAL_SCOPE_KEY = 'personal'

/**
 * スコープ種別＋公開スコープID（slug）から作成先スコープの鍵を作る。
 *
 * スコープ種別は経路によって大小文字が揺れる（レイヤー API は `TEAM`、
 * ダイアログの props は `team`）ため、ここで大文字へ正規化してから連結する。
 * 揺れの吸収をこの1箇所に閉じ込めることが、この関数の存在理由である。
 */
export function scheduleScopeKey(scopeType: string, scopeId: string): string {
  return `${scopeType.toUpperCase()}:${scopeId}`
}
