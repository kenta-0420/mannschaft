import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ErrorReportDialog from '~/components/ErrorReportDialog.vue'

/**
 * CMP-260920-1042: エラー報告パネルが操作要素を覆い、クリックを物理的に塞ぐ不具合の回帰テスト。
 *
 * 修正前は capture() 呼び出し（＝ API エラー発生）と同時に幅320pxのパネルが
 * 画面右下へ自動展開し、下にある操作ボタン（例: 回覧作成）のクリックを塞いでいた。
 *
 * 修正後は:
 * - 自動では小さいバッジ（44x44px）のみが表示され、パネル本体（w-80）は描画されない
 * - バッジをクリックしたときだけパネルが展開される
 * - バッジ・パネルとも画面右下（クイックメモ等の FAB 定位置）ではなく右上に配置される
 *
 * ErrorReportDialog は <Teleport to="body"> で document.body 直下にレンダリングされるため、
 * wrapper.find ではなく document.body.querySelector で要素を取得する
 * （ActionMemoEditDialog.spec.ts 等、既存の Teleport コンポーネントのテストと同じ作法）。
 */
describe('ErrorReportDialog', () => {
  beforeEach(() => {
    // useState は Nuxt のアプリ単位シングルトンなので、テストごとに明示的に初期化する
    const state = useState('errorReport')
    state.value = {
      visible: false,
      expanded: false,
      submitting: false,
      submitted: false,
      commentSent: false,
      errorMessage: '',
      stackTrace: '',
      pageUrl: '',
      userAgent: '',
      requestId: '',
      context: '',
    }
    document.body.innerHTML = ''
  })

  it('エラー未発生時は何も描画しない', async () => {
    await mountSuspended(ErrorReportDialog)
    expect(document.body.querySelector('button[aria-label]')).toBeNull()
    expect(document.body.querySelector('.w-80')).toBeNull()
  })

  it('capture 相当（visible=true, expanded=false）ではバッジのみが表示され、幅広パネルは描画されない', async () => {
    const state = useState('errorReport')
    state.value = { ...state.value, visible: true, expanded: false, errorMessage: 'boom' }

    await mountSuspended(ErrorReportDialog)

    // バッジ（44x44px の丸ボタン）は表示される＝エラー発生自体は利用者に伝わる
    const badge = document.body.querySelector('button[aria-label]')
    expect(badge).not.toBeNull()

    // 幅 320px（w-80）の詳細パネルは自動展開されない＝操作ボタンを覆わない
    expect(document.body.querySelector('.w-80')).toBeNull()
  })

  it('バッジをクリックすると詳細パネルが展開される', async () => {
    const state = useState('errorReport')
    state.value = { ...state.value, visible: true, expanded: false, errorMessage: 'boom' }

    await mountSuspended(ErrorReportDialog)
    const badge = document.body.querySelector<HTMLButtonElement>('button[aria-label]')
    expect(badge).not.toBeNull()
    badge!.click()
    await nextTick()

    expect(document.body.querySelector('.w-80')).not.toBeNull()
  })

  it('バッジ・パネルは画面右下（FABの定位置）ではなく右上に配置される', async () => {
    const state = useState('errorReport')
    state.value = { ...state.value, visible: true, expanded: true, errorMessage: 'boom' }

    await mountSuspended(ErrorReportDialog)
    const panel = document.body.querySelector('.w-80')
    expect(panel).not.toBeNull()
    // bottom-* ではなく top-* に配置されていること（右下 FAB 群との競合回避）
    expect(panel!.className).toContain('top-20')
    expect(panel!.className).not.toContain('bottom-4')
  })
})
