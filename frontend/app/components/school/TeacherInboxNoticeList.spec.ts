import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TeacherInboxNoticeList from './TeacherInboxNoticeList.vue'
import type { FamilyAttendanceNoticeResponse, FamilyNoticeStatus } from '~/types/school'

function notice(id: number, status: FamilyNoticeStatus): FamilyAttendanceNoticeResponse {
  return { id, status, teamId: 42, studentUserId: 7, submitterUserId: 8,
    attendanceDate: '2026-11-10', noticeType: 'ABSENCE', appliedToRecord: status === 'APPLIED',
    createdAt: '2026-11-10T08:00:00', updatedAt: '2026-11-10T08:00:00' }
}
const records = [notice(1, 'PENDING'), notice(2, 'ACKNOWLEDGED'), notice(3, 'APPLIED')]

describe('保護者連絡の閲覧と操作資格', () => {
  it.each([undefined, false])('資格%sでは連絡を読めても操作ボタンを出さない', async canRecord => {
    const wrapper = await mountSuspended(TeacherInboxNoticeList, { props: { records, canRecord } })
    expect(wrapper.findAll('[data-testid^="teacher-notice-item-"]')).toHaveLength(3)
    expect(wrapper.find('[data-testid^="teacher-notice-acknowledge-"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid^="teacher-notice-apply-"]').exists()).toBe(false)
    expect(wrapper.emitted('acknowledge')).toBeUndefined()
    expect(wrapper.emitted('apply')).toBeUndefined()
  })

  it('資格trueでは既存statusごとの操作を表示し同じnotice IDをemitする', async () => {
    const wrapper = await mountSuspended(TeacherInboxNoticeList, { props: { records, canRecord: true } })
    await wrapper.get('[data-testid="teacher-notice-acknowledge-1"]').trigger('click')
    await wrapper.get('[data-testid="teacher-notice-apply-2"]').trigger('click')
    expect(wrapper.emitted('acknowledge')).toEqual([[1]])
    expect(wrapper.emitted('apply')).toEqual([[2]])
    expect(wrapper.find('[data-testid="teacher-notice-acknowledge-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="teacher-notice-apply-3"]').exists()).toBe(false)
    await wrapper.setProps({ processing: true })
    expect(wrapper.get('[data-testid="teacher-notice-apply-1"]').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ canRecord: false })
    expect(wrapper.find('[data-testid^="teacher-notice-apply-"]').exists()).toBe(false)
  })
})
