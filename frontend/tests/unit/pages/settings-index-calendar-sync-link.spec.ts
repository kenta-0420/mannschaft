import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'

/**
 * CMP-260912-0911: 重複していた `/admin/google-calendar` を削除した後も、
 * 正規の Google Calendar 連携ページ `/settings/calendar-sync` へ設定ハブから
 * 到達できることを検証する。
 *
 * `/admin/google-calendar` はどこからもリンクされていなかった（削除の根拠）が、
 * `/settings/calendar-sync` はここで確認する導線一本のみで到達可能なため、
 * この導線が壊れると画面に一切到達できなくなる。
 */

const SettingsIndexPage = (await import('~/pages/settings/index.vue')).default

describe('設定ハブ Google Calendar 連携導線', () => {
  it('individualItems に /settings/calendar-sync へのリンクが出る', async () => {
    const wrapper = await mountSuspended(SettingsIndexPage)
    // 個別設定一覧はアコーディオンで初期折りたたみのため、開いてから検証する
    // （Phase4Links.spec.ts と同じ作法。ラベルは i18n 化されておりロケール依存のため
    // トグルボタン固有のアイコン pi-list で選択する）。
    const toggleButton = wrapper.findAll('button').find(b => b.find('.pi-list').exists())
    expect(toggleButton).toBeTruthy()
    await toggleButton!.trigger('click')
    await wrapper.vm.$nextTick()

    const html = wrapper.html()
    expect(html).toContain('href="/settings/calendar-sync"')
  })
})
