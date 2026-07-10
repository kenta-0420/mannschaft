import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TeamReservationGuideContent from '~/components/reservation/TeamReservationGuideContent.vue'

/**
 * TeamReservationGuideContent.vue（チーム予約ガイド・タブ別内容）ユニットテスト — 番人
 *
 * 背景: マスター評「現状のガイドでは作成者の私でさえ使い方がわからない」を受け、
 * ガイドを isAdmin 一括出し分けから activeTab（開いていたタブ）連動へ全面書き直しした
 * （第三弾第一波C）。本テストは activeTab の切り替えで正しいタブのセクション見出しが
 * 描画されることを固定し、タブ非連動への後退（再発）を防ぐ番人とする。
 *
 * 観点:
 *   AC-1: activeTab=0（予約する）で「マトリックスの見方」系の見出しが描画される
 *   AC-2: activeTab=2（予約対象の管理）で「予約対象を作る」系の初期セットアップ見出しが描画される
 *
 * 注: テスト環境の既定ロケールは en。i18n 実解決の描画文字列（英語）で検証する
 *     （MenuManager.spec.ts 等の既存パターンを踏襲）。
 */
describe('TeamReservationGuideContent.vue タブ別ガイド', () => {
  it('AC-1: activeTab=0（予約する）でマトリックスの見方セクションが描画される', async () => {
    const wrapper = await mountSuspended(TeamReservationGuideContent, {
      props: { isAdmin: false, isAdminOrDeputy: false, activeTab: 0 },
    })

    expect(wrapper.text()).toContain('Reading the matrix')
    expect(wrapper.text()).toContain('Booking flow')
    // 予約一覧・予約対象の管理タブの見出しは描画されない（タブ連動の裏取り）
    expect(wrapper.text()).not.toContain('Create reservation targets')
  })

  it('AC-2: activeTab=2（予約対象の管理）で初期セットアップ①のセクションが描画される', async () => {
    const wrapper = await mountSuspended(TeamReservationGuideContent, {
      props: { isAdmin: true, isAdminOrDeputy: true, activeTab: 2 },
    })

    // F03.4.5 §3.2 管理タブ再編: ①営業時間（新設）→②予約対象→③メニュー→④週間スケジュール
    expect(wrapper.text()).toContain('1. Set business hours')
    expect(wrapper.text()).toContain('2. Create reservation targets')
    expect(wrapper.text()).toContain('Create a weekly schedule')
    // 予約するタブの見出しは描画されない（タブ連動の裏取り）
    expect(wrapper.text()).not.toContain('Reading the matrix')
  })
})
