import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const notificationListSource = readFileSync(
  resolve(process.cwd(), 'app/components/notification/NotificationList.vue'),
  'utf8',
)
const notificationBellSource = readFileSync(
  resolve(process.cwd(), 'app/components/notification/NotificationBell.vue'),
  'utf8',
)
const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const listKeys = [
  'all',
  'unread',
  'markAllRead',
  'markUnread',
  'markRead',
  'empty',
  'loadMore',
  'loadError',
  'actionError',
  'markAllReadError',
  'confirmInfoMissing',
  'invalidScope',
  'confirmed',
  'confirmError',
] as const
const bellKeys = ['notification', 'chat', 'mention', 'none'] as const

describe('通知画面の多言語表示とモバイル操作領域', () => {
  it.each(locales)('%s ロケールに通知一覧と通知ベルの同一キーがある', (locale) => {
    const messages = JSON.parse(
      readFileSync(resolve(process.cwd(), `app/locales/${locale}/common.json`), 'utf8'),
    ).notification as {
      pageTitle: string
      list: Record<string, string>
      bell: Record<string, string>
    }

    expect(messages.pageTitle.trim()).not.toBe('')
    expect(Object.keys(messages.list)).toEqual(listKeys)
    expect(Object.keys(messages.bell)).toEqual(bellKeys)
    expect(Object.values(messages.list).every((value) => value.trim() !== '')).toBe(true)
    expect(Object.values(messages.bell).every((value) => value.trim() !== '')).toBe(true)
  })

  it('通知一覧の操作文言と失敗文言を翻訳キーから表示する', () => {
    for (const key of listKeys) {
      expect(notificationListSource).toContain(`notification.list.${key}`)
    }
    expect(notificationListSource).not.toContain("showError('通知")
    expect(notificationListSource).not.toContain('label="すべて既読にする"')
    expect(notificationListSource).not.toContain('>通知はありません<')
    expect(notificationListSource).not.toContain('label="もっと読む"')
  })

  it('通知ベルの表示文言を翻訳キーから表示する', () => {
    for (const key of bellKeys) {
      expect(notificationBellSource).toContain(`notification.bell.${key}`)
    }
    expect(notificationBellSource).not.toContain('v-tooltip.bottom="\'通知\'"')
    expect(notificationBellSource).not.toContain('>チャット<')
    expect(notificationBellSource).not.toContain('>メンション<')
    expect(notificationBellSource).not.toContain('>なし<')
  })

  it('モバイルで使う主要操作を44px以上にする', () => {
    expect(notificationListSource).toContain('class="notification-filter"')
    expect(notificationListSource).toContain(':deep(.notification-filter .p-togglebutton)')
    expect(notificationListSource).toContain('min-width: 2.75rem')
    expect(notificationListSource).toContain('min-height: 2.75rem')
    expect(notificationListSource).toContain(
      'mb-4 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between',
    )
    expect(notificationListSource).toContain('class="min-h-11 w-full sm:w-auto"')
    expect(notificationListSource.match(/class="min-h-11[^"]*"/g) ?? []).toHaveLength(4)
    expect(notificationBellSource).toContain('class="min-h-11 min-w-11"')
  })
})
