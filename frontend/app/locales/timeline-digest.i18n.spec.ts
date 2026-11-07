// @vitest-environment node
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'

// 登録mainの元JSONを基準とする。Git blobは一次資料の識別子、意味hashは名前空間を
// 外した辞書のキーと文言を固定する。JSONの改行やindentだけでは保全判定を変えない。
const BASELINE_SOURCE = '45b4931291866cd1cf5c2fa775a8da42bd48ddf1'
const BASELINE = {
  ja: {
    gitBlob: 'ffa3de3f0b06121eb9faea53eda692ad72884d11',
    semanticSha256: 'e81796cfe907f5fc6b3d7f4058250dae364bdc0ffc2e1d5e06ee3090309b5d6d',
  },
  en: {
    gitBlob: 'bba0b6900b6df1c11fe043d2c1dbbcc33d3bf8aa',
    semanticSha256: '717a8812eaa92dd573f4ae177e9acde561734fb2943eddf446c231ff7d116b1f',
  },
  zh: {
    gitBlob: '6dd997d3829d15366539bb8b78dc2ae094f7b85b',
    semanticSha256: 'bce4e96453ab2a74d24aee83a49ec99f3a2312edafa58836abe27e0dda27677c',
  },
  ko: {
    gitBlob: '80a5978ff4b2ffd35e12de4d23d9cfe92690fbb2',
    semanticSha256: '1ea9f9c7c23fc13eab3af3ec2127153c6b5b46487a696551e9f214075e8b9c55',
  },
  es: {
    gitBlob: 'f2d14984c7cb809ef0df2c44753099359591456f',
    semanticSha256: 'bd7c21193aa8a1c0cb860a3939d603c437cabce115d37d2c232f9dbbefca5c67',
  },
  de: {
    gitBlob: '452f7953ce7744914cb2b6146af9e7a97415f9ee',
    semanticSha256: 'd51b8ccb55fdca1d2da355d28cd075b046dcea362f9dcd8f9109fa1f28464211',
  },
} as const

const localesDir = dirname(fileURLToPath(import.meta.url))
type DigestLocale = keyof typeof BASELINE
type DigestMessages = Record<string, string | Record<string, string>>

function readMessages(locale: DigestLocale): DigestMessages {
  return JSON.parse(
    readFileSync(resolve(localesDir, locale, 'timeline_digest.json'), 'utf8'),
  ) as DigestMessages
}

const localeMessages = {
  ja: readMessages('ja'),
  en: readMessages('en'),
  zh: readMessages('zh'),
  ko: readMessages('ko'),
  es: readMessages('es'),
  de: readMessages('de'),
}

function semanticHash(messages: Record<string, string>) {
  const sorted = Object.fromEntries(
    Object.keys(messages)
      .sort()
      .map((key) => [key, messages[key]]),
  )
  return createHash('sha256').update(JSON.stringify(sorted)).digest('hex')
}

describe('CMP-261108-0254 ダイジェストの実ロケール解決', () => {
  it.each(['ja', 'en', 'zh', 'ko', 'es', 'de'] as const)(
    '%s は既存54キーと文言を保持して名前空間付き翻訳を解決する',
    (locale) => {
      const messages = localeMessages[locale]
      // 実loaderと同じくJSONをrootへ渡す。ファイル名で名前空間を補ったり、tをmockしない。
      const i18n = createI18n<[DigestMessages], DigestLocale, false>({
        legacy: false,
        locale,
        fallbackLocale: false,
        messages: localeMessages,
        missingWarn: false,
        fallbackWarn: false,
      })
      try {
        expect(i18n.global.te('timeline_digest.title', locale)).toBe(true)
        const namespace = messages.timeline_digest
        expect(namespace).toBeTypeOf('object')
        if (typeof namespace !== 'object' || namespace === null) {
          throw new Error('timeline_digest namespace is missing')
        }
        expect(Object.keys(namespace)).toHaveLength(54)
        expect(
          semanticHash(namespace),
          `${BASELINE_SOURCE}:${BASELINE[locale].gitBlob} のキーと文言を保持する`,
        ).toBe(BASELINE[locale].semanticSha256)

        for (const [key, value] of Object.entries(namespace)) {
          const fullKey = `timeline_digest.${key}`
          expect(i18n.global.te(fullKey, locale), fullKey).toBe(true)
          expect(value).toBeTypeOf('string')
          expect(i18n.global.t(fullKey, { remaining: 3, limit: 10 })).toBe(
            value.replaceAll('{remaining}', '3').replaceAll('{limit}', '10'),
          )
        }
        if (locale === 'ja') {
          expect(i18n.global.t('timeline_digest.title')).toBe('タイムラインダイジェスト')
          expect(i18n.global.t('timeline_digest.empty')).toBe('ダイジェストがありません')
          expect(i18n.global.t('timeline_digest.button_config')).toBe('設定')
        }
      } finally {
        i18n.dispose()
      }
    },
  )
})
