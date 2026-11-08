/** Pageの受信境界を生成型と照合する。要素DTOのnullable差は別契約。 */
import type { CursorMeta, PagedResponse, SpringPage } from './api'
import type { SpringPage as PublicSpringPage } from './public'
import type { components } from './generated'
import type { EmailOutboxListResponse, useEmailOutboxAdminApi } from '../composables/useEmailOutboxAdminApi'
import type { DashboardTeamsResponse, DashboardUsersResponse, useSystemAdminDashboard } from '../composables/system-admin/useSystemAdminDashboard'
import type { AdminDashboardUsersResponse, useAdminReportApi } from '../composables/useAdminReportApi'
import type { CommitteeListResponse, CommitteeDistributionsResponse, useCommitteeApi } from '../composables/useCommitteeApi'

type Schemas = components['schemas']
type AssertTrue<T extends true> = T
type SameKeys<A, B> = [keyof A, keyof B] extends [keyof B, keyof A] ? true : false
type SameType<A, B> = [A, B] extends [B, A] ? true : false
type Dashboard = ReturnType<typeof useSystemAdminDashboard>
type Admin = ReturnType<typeof useAdminReportApi>
type Committee = ReturnType<typeof useCommitteeApi>
type Outbox = ReturnType<typeof useEmailOutboxAdminApi>

/** 4 adapterのraw genericに使う型を、生成されたWの外側と内側の両方へ結ぶ。 */
export type RawOuterContracts = [
  AssertTrue<SameKeys<EmailOutboxListResponse, Schemas['ApiResponsePageEmailOutboxSummaryResponse']>>,
  AssertTrue<SameKeys<CommitteeListResponse, Schemas['ApiResponsePageCommitteeSummaryResponse']>>,
  AssertTrue<SameKeys<CommitteeDistributionsResponse, Schemas['ApiResponsePageCommitteeDistributionLogResponse']>>,
  AssertTrue<SameKeys<DashboardUsersResponse, Schemas['ApiResponsePageSystemAdminUserSummaryResponse']>>,
  AssertTrue<SameKeys<DashboardTeamsResponse, Schemas['ApiResponsePageSystemAdminTeamSummaryResponse']>>,
  AssertTrue<SameKeys<AdminDashboardUsersResponse, Schemas['ApiResponsePageScopeUserRoleResponse']>>,
]
export type RawPageContracts = [
  AssertTrue<SameKeys<EmailOutboxListResponse['data'], Schemas['PageEmailOutboxSummaryResponse']>>,
  AssertTrue<SameKeys<CommitteeListResponse['data'], Schemas['PageCommitteeSummaryResponse']>>,
  AssertTrue<SameKeys<CommitteeDistributionsResponse['data'], Schemas['PageCommitteeDistributionLogResponse']>>,
  AssertTrue<SameKeys<DashboardUsersResponse['data'], Schemas['PageSystemAdminUserSummaryResponse']>>,
  AssertTrue<SameKeys<Awaited<ReturnType<Dashboard['getDashboardTeams']>>['data'], Schemas['PageSystemAdminTeamSummaryResponse']>>,
  AssertTrue<SameKeys<Awaited<ReturnType<Admin['getAdminDashboardUsers']>>['data'], Schemas['PageScopeUserRoleResponse']>>,
]

/** 共用Pageの値型も確認する。生成型のoptionalだけを正規化し、number/string誤宣言を通さない。 */
type ScalarKeys = 'number' | 'size' | 'totalElements' | 'totalPages' | 'numberOfElements' | 'empty' | 'first' | 'last'
export type PageScalarTypes = AssertTrue<SameType<
  Pick<SpringPage<unknown>, ScalarKeys>, Required<Pick<Schemas['PageEmailOutboxSummaryResponse'], ScalarKeys>>
>>
export type AdapterOutputs = [
  AssertTrue<SameType<Awaited<ReturnType<Outbox['fetchList']>>['meta'], PagedResponse<unknown>['meta']>>,
  AssertTrue<SameKeys<Awaited<ReturnType<Committee['listCommittees']>>, PagedResponse<unknown>>>,
  AssertTrue<SameKeys<Awaited<ReturnType<Committee['listDistributions']>>, PagedResponse<unknown>>>,
  AssertTrue<SameKeys<Awaited<ReturnType<Dashboard['getDashboardUsers']>>, PagedResponse<unknown>>>,
]

/** 既知の誤宣言の負例と、別の正規契約の正常対照。 */
// @ts-expect-error W内のPageをPとして宣言してはならない
export type RejectPageAsPaged = AssertTrue<SameKeys<SpringPage<unknown>, PagedResponse<unknown>>>
// @ts-expect-error W内のPageを配列として宣言してはならない
export type RejectPageAsArray = AssertTrue<SameKeys<SpringPage<unknown>, unknown[]>>
export type TruePagedControl = AssertTrue<SameKeys<PagedResponse<unknown>, Schemas['PagedResponseMyJoinRequestResponse']>>
export type PublicDirectControl = AssertTrue<SameType<PublicSpringPage<unknown>, Omit<SpringPage<unknown>, 'pageable' | 'sort'>>>
export type CursorControl = AssertTrue<SameKeys<CursorMeta, Schemas['CursorMeta']>>