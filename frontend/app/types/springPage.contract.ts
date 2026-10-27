/** Spring Pageの受信境界と生成型を照合する。optional/nullableの要素DTO差は本契約の対象外。 */
import type { PagedResponse, SpringPage } from './api'
import type { components } from './generated'
import type { useSystemAdminDashboard } from '../composables/system-admin/useSystemAdminDashboard'
import type { useAdminReportApi } from '../composables/useAdminReportApi'
import type { useCommitteeApi } from '../composables/useCommitteeApi'

type Schemas = components['schemas']
type AssertTrue<T extends true> = T
type SameKeys<A, B> = [keyof A, keyof B] extends [keyof B, keyof A] ? true : false

type Dashboard = ReturnType<typeof useSystemAdminDashboard>
type Admin = ReturnType<typeof useAdminReportApi>
type Committee = ReturnType<typeof useCommitteeApi>

/** 実関数の戻り型から取り出す。未使用宣言を台帳の自己申告だけで済ませない。 */
export type DashboardTeamsWireMatch = AssertTrue<SameKeys<
  Awaited<ReturnType<Dashboard['getDashboardTeams']>>['data'], Schemas['PageSystemAdminTeamSummaryResponse']
>>
export type AdminUsersWireMatch = AssertTrue<SameKeys<
  Awaited<ReturnType<Admin['getAdminDashboardUsers']>>['data'], Schemas['PageScopeUserRoleResponse']
>>
export type CommitteeListOutputMatch = AssertTrue<SameKeys<
  Awaited<ReturnType<Committee['listCommittees']>>, PagedResponse<unknown>
>>
export type CommitteeDistributionsOutputMatch = AssertTrue<SameKeys<
  Awaited<ReturnType<Committee['listDistributions']>>, PagedResponse<unknown>
>>

/** 正常対照と、以前の誤宣言を必ず型エラーにする負例。 */
export type SpringPageKeysMatch = AssertTrue<SameKeys<SpringPage<unknown>, Schemas['PageEmailOutboxSummaryResponse']>>
// @ts-expect-error W内のPageをPとして宣言してはならない
export type RejectPageAsPaged = AssertTrue<SameKeys<SpringPage<unknown>, PagedResponse<unknown>>>
// @ts-expect-error W内のPageを配列として宣言してはならない
export type RejectPageAsArray = AssertTrue<SameKeys<SpringPage<unknown>, unknown[]>>
export type TruePagedControl = AssertTrue<SameKeys<PagedResponse<unknown>, Schemas['PagedResponseMyJoinRequestResponse']>>
