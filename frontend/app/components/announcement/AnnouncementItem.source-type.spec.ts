// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { toAnnouncementItem } from '~/utils/announcementAdapter'

// generated DTOはsourceTypeをstringで返すため、BEのenumを契約の正本として照合する。
const sourceEnum = readFileSync(resolve(process.cwd(), '../backend/src/main/java/com/mannschaft/app/social/announcement/AnnouncementSourceType.java'), 'utf8')
  .replace(/\/\*[\s\S]*?\*\/|\/\/[^\r\n]*/g, '')
const sourceTypes = (sourceEnum.match(/enum AnnouncementSourceType\s*\{([\s\S]*?)\}/)?.[1] ?? '')
  .split(',').map((value) => value.trim()).filter((value) => /^[A-Z_]+$/.test(value))

describe('お知らせの配信元表示', () => {
  it('BE enumの実値を列挙し、FE型にも全値を宣言する', () => {
    expect(sourceTypes).toContain('TODO')
    expect(sourceTypes).toContain('ADVERTISER_CAMPAIGN')
    const typeSource = readFileSync(resolve(process.cwd(), 'app/types/announcement.ts'), 'utf8')
    const declaration = typeSource.match(/export type AnnouncementSourceType =([\s\S]*?)\n\n/)?.[1] ?? ''
    const declaredTypes = [...declaration.matchAll(/'([A-Z_]+)'/g)].map((match) => match[1])
    expect(declaredTypes.sort()).toEqual([...sourceTypes, 'CIRCULATION'].sort())
  })

  it.each(sourceTypes)('%sはadapterで別名に変換せず表示へ渡す', (sourceType) => {
    expect(toAnnouncementItem({ id: 1, scopeId: 1, scopeType: 'TEAM', sourceType }).sourceType).toBe(sourceType)
  })

  it.each(['ja', 'en', 'zh', 'ko', 'es', 'de'])('%sは全配信元の表示名を持つ', (locale) => {
    const messages = JSON.parse(readFileSync(resolve(process.cwd(), 'app/locales', locale, 'announcement.json'), 'utf8')) as {
      announcement: { source_type: Record<string, string> }
    }
    for (const sourceType of [...sourceTypes, 'CIRCULATION']) {
      expect(messages.announcement.source_type[sourceType], sourceType).toBeTruthy()
      expect(messages.announcement.source_type[sourceType]).not.toBe(`announcement.source_type.${sourceType}`)
    }
  })
})
