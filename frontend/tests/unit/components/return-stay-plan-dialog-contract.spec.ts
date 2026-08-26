import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const memberTable = readFileSync(resolve(process.cwd(), 'app/components/MemberTable.vue'), 'utf8')
const widget = readFileSync(resolve(process.cwd(), 'app/components/dashboard/WidgetReturnStayPlan.vue'), 'utf8')

describe('return/stay detail dialogs', () => {
  it('opens a member plan detail dialog from a clickable pill', () => {
    expect(memberTable).toContain('@click="openReturnStayDetail(plan)"')
    expect(memberTable).toContain('v-if="selectedReturnStayPlan"')
    expect(memberTable).toContain('return-stay-detail-dialog')
  })

  it('declares accessible close, focus, escape and mobile fullscreen behavior', () => {
    for (const source of [memberTable, widget]) {
      expect(source).toContain('closable')
      expect(source).toContain('close-on-escape')
      expect(source).toContain('dismissable-mask')
      expect(source).toContain('@show=')
      expect(source).toContain('max-width: 640px')
      expect(source).toContain('height: 100vh')
    }
  })

  it('reloads and cancels stale member data when the team scope changes', () => {
    expect(memberTable).toContain('() => [props.scopeType, props.scopeId]')
    expect(memberTable).toContain('() => loadMembers()')
    expect(memberTable).toContain('memberController?.abort()')
  })
})
