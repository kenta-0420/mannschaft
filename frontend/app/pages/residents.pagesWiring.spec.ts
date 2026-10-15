import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const organizationPage = readFileSync(
  resolve(process.cwd(), 'app/pages/organizations/[slug]/residents.vue'),
  'utf8',
)
const teamPage = readFileSync(
  resolve(process.cwd(), 'app/pages/teams/[slug]/residents.vue'),
  'utf8',
)
const createDialog = readFileSync(
  resolve(process.cwd(), 'app/components/resident/DwellingUnitCreateDialog.vue'),
  'utf8',
)
const residentType = readFileSync(resolve(process.cwd(), 'app/types/resident.ts'), 'utf8')
const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const

describe('住民台帳', () => {
  it('API契約のresidentCountを使い、廃止済みのresidents配列を参照しない', () => {
    expect(residentType).toContain('residentCount: number')
    expect(residentType).not.toContain('residents: ResidentResponse[]')
    for (const page of [organizationPage, teamPage]) {
      expect(page).toContain('u.residentCount ?? 0')
      expect(page).not.toContain('u.residents.length')
    }
  })

  it('組織・チームで失敗、空状態、権限付き作成導線を同じ作法で扱う', () => {
    for (const page of [organizationPage, teamPage]) {
      expect(page).toContain("useRoleAccess('")
      expect(page).toContain('isAdminOrDeputy')
      expect(page).toContain('v-if="isAdminOrDeputy"')
      expect(page).toContain('DashboardErrorState')
      expect(page).toContain('@retry="load"')
      expect(page).toContain('DashboardEmptyState v-else')
      expect(page).toContain('<DwellingUnitCreateDialog')
      expect(page).toContain('@created="onCreated"')
    }
  })

  it('住戸作成はZod/VeeValidate、送信中ロック、作成後通知を持つ', () => {
    expect(createDialog).toContain("import { toTypedSchema } from '@vee-validate/zod'")
    expect(createDialog).toContain("import { useForm } from 'vee-validate'")
    expect(createDialog).toContain("import { z } from 'zod'")
    expect(createDialog).toContain('.min(1')
    expect(createDialog).toContain('.max(50')
    expect(createDialog).toContain('.max(20')
    expect(createDialog).toContain('const submit = handleSubmit')
    expect(createDialog).toContain('await createUnit(props.scopeType, props.scopeId')
    expect(createDialog).toContain(':loading="submitting"')
    expect(createDialog).toContain('if (submitting.value) return')
    expect(createDialog).toContain('getFieldErrors(error)')
    expect(createDialog).toContain('handleApiError(error')
    expect(createDialog).toContain('grid-cols-1 gap-3 sm:grid-cols-2')
    expect(createDialog).toContain('text-base')
    expect(createDialog).toContain("emit('created')")
  })

  it.each(locales)('%sロケールに住民台帳の表示文言がある', (locale) => {
    const messages = JSON.parse(
      readFileSync(resolve(process.cwd(), `app/locales/${locale}/property.json`), 'utf8'),
    ).property.residents
    for (const key of [
      'title',
      'create',
      'empty',
      'residentCount',
      'createSuccess',
      'createFailed',
      'validation',
    ]) {
      expect(messages[key]).toBeTruthy()
    }
  })
})
