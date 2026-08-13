// @vitest-environment happy-dom
//
// 検査対象は純関数だけで、Nuxt ランタイム（app context）を必要としない。
// 既定の environment: 'nuxt' は beforeAll で setupNuxt() を走らせるため遅く、
// 負荷が高いと 120 秒の hook timeout でファイルごと落ちる（テスト内容とは無関係の偽赤）。
// ただし setup ファイル経由で読み込まれるモジュールが document を触るので、
// 素の node ではなく DOM のある happy-dom を使う（requestBodySchemaConformance.spec.ts と同方針）。
import { describe, it, expect } from 'vitest'
import { shouldFetchResultsOnMount } from '~/utils/surveyResults'
import type { SurveyResultSummary } from '~/types/survey'

/**
 * 集計の二重取得防止（性能回帰の番人）。
 *
 * 詳細ページは配信対象かどうかを確かめるため結果取得を 1 回行っている。その結果を
 * SurveyResultsPanel が再利用しないと、結果を閲覧できる全ユーザーの全表示で
 * 高コストな集計と転送が必ず 2 回走る。
 *
 * 判定を純関数に切り出しているため、画面をマウントせず決定的に検証できる
 * （コンポーネントのマウントを伴う検証は tests/unit/components/survey/
 *   SurveyResultsPanel.spec.ts 側で行う）。
 */
const RESULT: SurveyResultSummary = {
  questionId: 1,
  questionText: 'Q1',
  questionType: 'SINGLE_CHOICE',
  totalResponses: 3,
  optionResults: [{ optionId: 1, optionText: 'はい', count: 3, percentage: 100 }],
}

describe('shouldFetchResultsOnMount（集計の二重取得防止）', () => {
  it('集計を渡されたら取得しない（二重取得の防止）', () => {
    expect(shouldFetchResultsOnMount([RESULT])).toBe(false)
  })

  it('空配列（回答ゼロ）も正当な集計として扱い、取得し直さない', () => {
    // 真偽値で判定していると [] が falsy になり、回答ゼロのアンケートでだけ
    // 二重取得が静かに復活する。
    expect(shouldFetchResultsOnMount([])).toBe(false)
  })

  it('渡されなければ自分で取得する（他画面からの利用を壊さない）', () => {
    expect(shouldFetchResultsOnMount(undefined)).toBe(true)
    expect(shouldFetchResultsOnMount(null)).toBe(true)
  })
})
