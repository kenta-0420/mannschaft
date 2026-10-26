import type { SurveyResultSummary } from '~/types/survey'

/**
 * SurveyResultsPanel が mount 時に集計 API を呼ぶべきか。
 *
 * 詳細ページは「配信対象かどうか」を確かめるため結果取得を 1 回行っている
 * （403 をサーバーの判定として扱う）。その結果をパネルへ渡して再利用しないと、
 * **結果を閲覧できる全ユーザーの全表示で高コストな集計と転送が必ず 2 回**走る。
 *
 * ⚠️ 空配列は「回答ゼロ」という**正当な集計結果**である。真偽値で判定すると
 * `[]` が falsy 扱いになり、回答ゼロのアンケートでだけ二重取得が復活する。
 * そのため `null` / `undefined` かどうかで判定する。
 *
 * 渡されなかった場合（他画面からの利用）は従来どおり自分で取得する。
 */
export function shouldFetchResultsOnMount(
  initialResults: SurveyResultSummary[] | null | undefined,
): boolean {
  return initialResults == null
}
