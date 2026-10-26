// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import type { AnnouncementSourceType } from '~/types/announcement'

const sourceTypes = {
  BLOG_POST: true, BULLETIN_THREAD: true, TIMELINE_POST: true, CIRCULATION: true,
  SURVEY: true, TODO: true, SCHEDULE: true,
} satisfies Record<AnnouncementSourceType, boolean>

describe('お知らせの配信元表示', () => {
  it.each(['ja', 'en', 'zh', 'ko', 'es', 'de'])('%sは全配信元の表示名を持つ', (locale) => {
    const messages = JSON.parse(readFileSync(resolve(process.cwd(), 'app/locales', locale, 'announcement.json'), 'utf8')) as {
      announcement: { source_type: Record<string, string> }
    }
    for (const sourceType of Object.keys(sourceTypes)) {
      expect(messages.announcement.source_type[sourceType], sourceType).toBeTruthy()
      expect(messages.announcement.source_type[sourceType]).not.toBe(`announcement.source_type.${sourceType}`)
    }
  })
})
