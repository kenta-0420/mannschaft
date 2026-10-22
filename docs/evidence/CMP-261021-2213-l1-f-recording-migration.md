# CMP-261021-2213 L1-F 初回記録移行の証拠

2026-10-22、ユーザーの「許可。進めて」により、初回に限り既存文言群内の未記録依存を既存負債として明記し、三上限を実測値へ補正した。[承認・再開記録](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6329614365)。これは返済ではない。判定・除外・Dependencyごとの検出範囲を変えず、無断の新規class-pair追加やdedupは行っていない。

## 固定原本と照合手順

- before: `6d3248d3ab05135bb3a4f939eeed604fb8b4997f`。再開merge後も本番Javaと全8storeファイルはこのbeforeと同一。
- 候補生成head: `8e3f87eab6e479782e35b5ed2f9567e0c7fa4a58`、[既存CI run 39696936570](https://github.com/kenta-0420/mannschaft/actions/runs/39696936570)。
- `test-results-xml-pr-4045-shard-5` のD-1、`shard-2` のD-3、`shard-4` のD-5の `build/test-results/archUnitTest/` exact suiteを採用。各suiteは1case/1failure/error0で、failure本文だけを一度読み、ヘッダ件数と抽出件数を一致確認した。failure.messageと本文を二重抽出していない。
- 候補の全行一意・旧prefixの集合一致・削減0・下表の頻度差分だけを確認した。全行に依存descriptionとoccurrenceを保持する。初回例外以後は新規違反failを維持し、通常の返済はchip-awayする。
- L1-G0完了後、他レーンの返済による同三store変更と同時実施せずに移行する。L1-Aは別宣言の未merge成果であり候補へ含めない。

| 番人 | before | after/上限 | 既存群内の増加 | 削減 | 新class-pair |
|---|---:|---:|---:|---:|---:|
| D-1 | 2037 | 2156 | 119 | 0 | 0 |
| D-3 | 1292 | 1325 | 33 | 0 | 0 |
| D-5 | 1850 | 1876 | 26 | 0 | 0 |

D-1/D-3/D-5の延べ178件であり、同じ依存が複数番人から数えられるため業務上の178箇所とは主張しない。

## 見逃しの根因

ArchUnit 1.3.0の配布bytecodeで確認した。`FreezingArchRule$CategorizedViolations` は一致した保存行を`Iterator.remove`（offset113–115）で消費する一方、一致した実違反文言を `HashSet knownActualViolations`（offset120–130）へ格納する。外側の `filterOutKnownViolations` は `Set.contains` で同文言を一括除外するため、保存件数を超える未照合の同文言まで消える。保存行を消費しないことが原因なのではない。

実装前の全archTest（347件）では `DependencyFreezeRecordingTest` の2call→3callの増加拒否が不発という実AssertionFailedErrorを確認した。一意suffix導入後の同じ実javac検体はCIで全4case成功し、行移動・同一行/別行の整形を許し、増加fail・減少chip-awayを実測した。IntegritySelfTest17caseも同CIで成功した。

## 増分全件（旧prefixの頻度Map差分）

旧prefixには依存位置の識別子がなかったため、歴史的にどのdescriptionが追加されたかは一意に復元できない。下表は増加した全旧prefixと全頻度差分であり、新形式は実全量の全descriptionを省略せず記録する。

| 番人 | 旧prefix | before | after | 増分 |
|---|---|---:|---:|---:|
| D-1 | `com.mannschaft.app.contact.service.ContactHandleService (domain 'contact') depends on other-domain entity com.mannschaft.app.auth.entity.UserEntity (domain 'auth')` | 20 | 21 | 1 |
| D-1 | `com.mannschaft.app.dashboard.controller.DashboardController (domain 'dashboard') depends on other-domain entity com.mannschaft.app.schedule.entity.ScheduleEntity (domain 'schedule')` | 14 | 16 | 2 |
| D-1 | `com.mannschaft.app.dashboard.controller.DashboardController (domain 'dashboard') depends on other-domain entity com.mannschaft.app.team.entity.TeamEntity (domain 'team')` | 2 | 8 | 6 |
| D-1 | `com.mannschaft.app.dashboard.service.DashboardService (domain 'dashboard') depends on other-domain entity com.mannschaft.app.admin.entity.PlatformAnnouncementEntity (domain 'admin')` | 5 | 11 | 6 |
| D-1 | `com.mannschaft.app.dashboard.service.DashboardService (domain 'dashboard') depends on other-domain entity com.mannschaft.app.bulletin.entity.BulletinThreadEntity (domain 'bulletin')` | 1 | 4 | 3 |
| D-1 | `com.mannschaft.app.dashboard.service.DashboardService (domain 'dashboard') depends on other-domain entity com.mannschaft.app.schedule.entity.ScheduleEntity (domain 'schedule')` | 7 | 12 | 5 |
| D-1 | `com.mannschaft.app.dashboard.service.ScopeWidgetSummaryService (domain 'dashboard') depends on other-domain entity com.mannschaft.app.bulletin.entity.BulletinThreadEntity (domain 'bulletin')` | 1 | 6 | 5 |
| D-1 | `com.mannschaft.app.family.service.EventEndReminderBatchService (domain 'family') depends on other-domain entity com.mannschaft.app.event.entity.EventEntity (domain 'event')` | 21 | 22 | 1 |
| D-1 | `com.mannschaft.app.favorite.resolver.impl.BlogAuthorFavoriteResolver (domain 'favorite') depends on other-domain entity com.mannschaft.app.auth.entity.UserEntity (domain 'auth')` | 5 | 8 | 3 |
| D-1 | `com.mannschaft.app.favorite.resolver.impl.OrganizationFavoriteResolver (domain 'favorite') depends on other-domain entity com.mannschaft.app.organization.entity.OrganizationEntity (domain 'organization')` | 5 | 6 | 1 |
| D-1 | `com.mannschaft.app.favorite.resolver.impl.TeamFavoriteResolver (domain 'favorite') depends on other-domain entity com.mannschaft.app.team.entity.TeamEntity (domain 'team')` | 5 | 6 | 1 |
| D-1 | `com.mannschaft.app.gdpr.service.AccountPurgeService (domain 'gdpr') depends on other-domain entity com.mannschaft.app.auth.entity.UserEntity (domain 'auth')` | 7 | 13 | 6 |
| D-1 | `com.mannschaft.app.market.service.MarketQueryService (domain 'market') depends on other-domain entity com.mannschaft.app.recruitment.entity.RecruitmentListingEntity (domain 'recruitment')` | 27 | 29 | 2 |
| D-1 | `com.mannschaft.app.membership.query.MemberQueryDispatcher (domain 'membership') depends on other-domain entity com.mannschaft.app.role.entity.RoleEntity (domain 'role')` | 2 | 6 | 4 |
| D-1 | `com.mannschaft.app.membership.query.MemberQueryDispatcher (domain 'membership') depends on other-domain entity com.mannschaft.app.role.entity.UserRoleEntity (domain 'role')` | 9 | 17 | 8 |
| D-1 | `com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity (domain 'notification') depends on other-domain entity com.mannschaft.app.auth.entity.UserEntity (domain 'auth')` | 5 | 6 | 1 |
| D-1 | `com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity (domain 'notification') depends on other-domain entity com.mannschaft.app.auth.entity.UserEntity (domain 'auth')` | 5 | 6 | 1 |
| D-1 | `com.mannschaft.app.organization.service.OrganizationMembershipService (domain 'organization') depends on other-domain entity com.mannschaft.app.team.entity.TeamEntity (domain 'team')` | 7 | 10 | 3 |
| D-1 | `com.mannschaft.app.organization.service.OrganizationMembershipService (domain 'organization') depends on other-domain entity com.mannschaft.app.team.entity.TeamOrgMembershipEntity (domain 'team')` | 2 | 4 | 2 |
| D-1 | `com.mannschaft.app.payment.service.MembershipSubscriptionService (domain 'payment') depends on other-domain entity com.mannschaft.app.auth.entity.UserEntity (domain 'auth')` | 2 | 4 | 2 |
| D-1 | `com.mannschaft.app.publicview.service.AdminPublicSettingsService (domain 'publicview') depends on other-domain entity com.mannschaft.app.organization.entity.OrganizationEntity (domain 'organization')` | 3 | 7 | 4 |
| D-1 | `com.mannschaft.app.publicview.service.AdminPublicSettingsService (domain 'publicview') depends on other-domain entity com.mannschaft.app.team.entity.TeamEntity (domain 'team')` | 3 | 7 | 4 |
| D-1 | `com.mannschaft.app.publicview.service.PublicOrganizationSearchQueryService (domain 'publicview') depends on other-domain entity com.mannschaft.app.organization.entity.OrganizationEntity (domain 'organization')` | 4 | 6 | 2 |
| D-1 | `com.mannschaft.app.publicview.service.PublicPostQueryService (domain 'publicview') depends on other-domain entity com.mannschaft.app.cms.entity.BlogPostEntity (domain 'cms')` | 23 | 36 | 13 |
| D-1 | `com.mannschaft.app.publicview.service.SitemapQueryService (domain 'publicview') depends on other-domain entity com.mannschaft.app.cms.entity.BlogPostEntity (domain 'cms')` | 6 | 7 | 1 |
| D-1 | `com.mannschaft.app.publicview.service.SitemapQueryService (domain 'publicview') depends on other-domain entity com.mannschaft.app.organization.entity.OrganizationEntity (domain 'organization')` | 2 | 3 | 1 |
| D-1 | `com.mannschaft.app.recruitment.service.MarketFinalizeService (domain 'recruitment') depends on other-domain entity com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority (domain 'notification')` | 1 | 2 | 1 |
| D-1 | `com.mannschaft.app.role.controller.MeController (domain 'role') depends on other-domain entity com.mannschaft.app.organization.entity.OrganizationEntity (domain 'organization')` | 6 | 10 | 4 |
| D-1 | `com.mannschaft.app.search.service.GlobalSearchService (domain 'search') depends on other-domain entity com.mannschaft.app.schedule.entity.ScheduleEntity (domain 'schedule')` | 4 | 5 | 1 |
| D-1 | `com.mannschaft.app.shiftbudget.listener.ShiftBudgetConsumptionRecordListener (domain 'shiftbudget') depends on other-domain entity com.mannschaft.app.shift.entity.ShiftSlotEntity (domain 'shift')` | 14 | 19 | 5 |
| D-1 | `com.mannschaft.app.succession.service.SuccessionCovenantService (domain 'succession') depends on other-domain entity com.mannschaft.app.resident.entity.ResidentRegistryEntity (domain 'resident')` | 9 | 11 | 2 |
| D-1 | `com.mannschaft.app.supporter.service.SupporterService (domain 'supporter') depends on other-domain entity com.mannschaft.app.membership.entity.MembershipEntity (domain 'membership')` | 5 | 7 | 2 |
| D-1 | `com.mannschaft.app.team.service.TeamService (domain 'team') depends on other-domain entity com.mannschaft.app.organization.entity.OrganizationEntity (domain 'organization')` | 4 | 7 | 3 |
| D-1 | `com.mannschaft.app.timetable.personal.listener.PersonalTimetableLinkSyncListener (domain 'timetable') depends on other-domain entity com.mannschaft.app.schedule.entity.ScheduleEntity (domain 'schedule')` | 12 | 25 | 13 |
| D-3 | `com.mannschaft.app.chat.service.ChatChannelService (domain 'chat', @Transactional) depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository (domain 'auth')` | 4 | 5 | 1 |
| D-3 | `com.mannschaft.app.circulation.service.CirculationService (domain 'circulation', @Transactional) depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository (domain 'auth')` | 3 | 5 | 2 |
| D-3 | `com.mannschaft.app.circulation.service.CirculationService (domain 'circulation', @Transactional) depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository$MemberSummary (domain 'auth')` | 2 | 4 | 2 |
| D-3 | `com.mannschaft.app.dashboard.service.DashboardScopeTabService (domain 'dashboard', @Transactional) depends on other-domain repository com.mannschaft.app.organization.repository.OrganizationRepository (domain 'organization')` | 3 | 4 | 1 |
| D-3 | `com.mannschaft.app.dashboard.service.DashboardScopeTabService (domain 'dashboard', @Transactional) depends on other-domain repository com.mannschaft.app.team.repository.TeamRepository (domain 'team')` | 3 | 4 | 1 |
| D-3 | `com.mannschaft.app.family.service.EventEndReminderBatchService (domain 'family', @Transactional) depends on other-domain repository com.mannschaft.app.event.repository.EventRepository (domain 'event')` | 4 | 5 | 1 |
| D-3 | `com.mannschaft.app.market.service.MarketQueryService (domain 'market', @Transactional) depends on other-domain repository com.mannschaft.app.recruitment.repository.RecruitmentListingRepository (domain 'recruitment')` | 6 | 8 | 2 |
| D-3 | `com.mannschaft.app.membership.query.MemberQueryDispatcher (domain 'membership', @Transactional) depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository (domain 'auth')` | 6 | 7 | 1 |
| D-3 | `com.mannschaft.app.membership.query.MemberQueryDispatcher (domain 'membership', @Transactional) depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository$MemberSummary (domain 'auth')` | 8 | 11 | 3 |
| D-3 | `com.mannschaft.app.membership.query.MemberQueryDispatcher (domain 'membership', @Transactional) depends on other-domain repository com.mannschaft.app.role.repository.RoleRepository (domain 'role')` | 4 | 6 | 2 |
| D-3 | `com.mannschaft.app.membership.query.MemberQueryDispatcher (domain 'membership', @Transactional) depends on other-domain repository com.mannschaft.app.role.repository.UserRoleRepository (domain 'role')` | 6 | 10 | 4 |
| D-3 | `com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService (domain 'notification', @Transactional) depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository (domain 'auth')` | 4 | 5 | 1 |
| D-3 | `com.mannschaft.app.organization.service.OrganizationHierarchyService (domain 'organization', @Transactional) depends on other-domain repository com.mannschaft.app.team.repository.TeamOrgMembershipRepository (domain 'team')` | 3 | 4 | 1 |
| D-3 | `com.mannschaft.app.organization.service.OrganizationMembershipService (domain 'organization', @Transactional) depends on other-domain repository com.mannschaft.app.role.repository.UserRoleRepository (domain 'role')` | 5 | 11 | 6 |
| D-3 | `com.mannschaft.app.organization.service.OrganizationMembershipService (domain 'organization', @Transactional) depends on other-domain repository com.mannschaft.app.team.repository.TeamRepository (domain 'team')` | 4 | 5 | 1 |
| D-3 | `com.mannschaft.app.payment.service.MembershipSubscriptionService (domain 'payment', @Transactional) depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository (domain 'auth')` | 3 | 4 | 1 |
| D-3 | `com.mannschaft.app.publicview.service.AdminPublicSettingsService (domain 'publicview', @Transactional) depends on other-domain repository com.mannschaft.app.organization.repository.OrganizationRepository (domain 'organization')` | 4 | 5 | 1 |
| D-3 | `com.mannschaft.app.publicview.service.AdminPublicSettingsService (domain 'publicview', @Transactional) depends on other-domain repository com.mannschaft.app.team.repository.TeamRepository (domain 'team')` | 4 | 5 | 1 |
| D-3 | `com.mannschaft.app.village.service.VillageRepresentativeService (domain 'village', @Transactional) depends on other-domain repository com.mannschaft.app.role.repository.UserRoleRepository (domain 'role')` | 4 | 5 | 1 |
| D-5 | `com.mannschaft.app.advertising.campaign.service.evaluator.AgeRangeSegmentEvaluator (domain 'advertising') depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository (domain 'auth')` | 3 | 4 | 1 |
| D-5 | `com.mannschaft.app.advertising.campaign.service.evaluator.CitySegmentEvaluator (domain 'advertising') depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository (domain 'auth')` | 3 | 4 | 1 |
| D-5 | `com.mannschaft.app.advertising.campaign.service.evaluator.GenderSegmentEvaluator (domain 'advertising') depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository (domain 'auth')` | 3 | 4 | 1 |
| D-5 | `com.mannschaft.app.advertising.campaign.service.evaluator.InterestTagSegmentEvaluator (domain 'advertising') depends on other-domain repository com.mannschaft.app.auth.repository.UserInterestTagRepository (domain 'auth')` | 3 | 4 | 1 |
| D-5 | `com.mannschaft.app.advertising.campaign.service.evaluator.PrefectureSegmentEvaluator (domain 'advertising') depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository (domain 'auth')` | 3 | 4 | 1 |
| D-5 | `com.mannschaft.app.family.service.EventEndReminderBatchService (domain 'family') depends on other-domain repository com.mannschaft.app.event.repository.EventRepository (domain 'event')` | 4 | 5 | 1 |
| D-5 | `com.mannschaft.app.gdpr.service.AccountPurgeService (domain 'gdpr') depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository (domain 'auth')` | 5 | 6 | 1 |
| D-5 | `com.mannschaft.app.market.service.MarketQueryService (domain 'market') depends on other-domain repository com.mannschaft.app.recruitment.repository.RecruitmentListingRepository (domain 'recruitment')` | 6 | 8 | 2 |
| D-5 | `com.mannschaft.app.membership.query.MemberQueryDispatcher (domain 'membership') depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository (domain 'auth')` | 6 | 7 | 1 |
| D-5 | `com.mannschaft.app.membership.query.MemberQueryDispatcher (domain 'membership') depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository$MemberSummary (domain 'auth')` | 8 | 11 | 3 |
| D-5 | `com.mannschaft.app.membership.query.MemberQueryDispatcher (domain 'membership') depends on other-domain repository com.mannschaft.app.role.repository.RoleRepository (domain 'role')` | 4 | 6 | 2 |
| D-5 | `com.mannschaft.app.membership.query.MemberQueryDispatcher (domain 'membership') depends on other-domain repository com.mannschaft.app.role.repository.UserRoleRepository (domain 'role')` | 6 | 10 | 4 |
| D-5 | `com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService (domain 'notification') depends on other-domain repository com.mannschaft.app.auth.repository.UserRepository (domain 'auth')` | 4 | 5 | 1 |
| D-5 | `com.mannschaft.app.organization.service.OrganizationHierarchyService (domain 'organization') depends on other-domain repository com.mannschaft.app.team.repository.TeamOrgMembershipRepository (domain 'team')` | 3 | 4 | 1 |
| D-5 | `com.mannschaft.app.organization.service.OrganizationMembershipService (domain 'organization') depends on other-domain repository com.mannschaft.app.role.repository.UserRoleRepository (domain 'role')` | 10 | 11 | 1 |
| D-5 | `com.mannschaft.app.publicview.service.AdminPublicSettingsService (domain 'publicview') depends on other-domain repository com.mannschaft.app.organization.repository.OrganizationRepository (domain 'organization')` | 4 | 5 | 1 |
| D-5 | `com.mannschaft.app.publicview.service.AdminPublicSettingsService (domain 'publicview') depends on other-domain repository com.mannschaft.app.team.repository.TeamRepository (domain 'team')` | 4 | 5 | 1 |
| D-5 | `com.mannschaft.app.role.controller.MeController (domain 'role') depends on other-domain repository com.mannschaft.app.organization.repository.OrganizationRepository (domain 'organization')` | 3 | 4 | 1 |
| D-5 | `com.mannschaft.app.village.service.VillageRepresentativeService (domain 'village') depends on other-domain repository com.mannschaft.app.role.repository.UserRoleRepository (domain 'role')` | 4 | 5 | 1 |

## 8ファイルの内容SHA-256（Git blobの生バイト、既存改行を保持）

| ファイル | before | 移行後 |
|---|---|---|
| `296295dd-06cf-4f7b-bf82-315ba12ff501` | `0dbc1a782c871369e60e666a53e907284403aa8c00f53985b2dedb1cdd03db82` | `0dbc1a782c871369e60e666a53e907284403aa8c00f53985b2dedb1cdd03db82` |
| `2c0ba995-682e-4f80-a5a5-f68c835b720d` | `8b0ae3b04cec962bce63bab678024c1f253c63457cd43b0db089dd2d9278535f` | `8b0ae3b04cec962bce63bab678024c1f253c63457cd43b0db089dd2d9278535f` |
| `427c445d-37ce-4d6e-b095-a1733efe209f` | `7c3bcc86c041f1deaa4b364ae2bd7d6003433d91111feedeeb6e3cf04c74e9e8` | `73226a2d009ffc6aceefc8fc4bbcbe9f2e0a2d456d48068d2d575a66c15ede21` |
| `584c3a46-b9c1-4cc2-bf74-e0a18eab1bef` | `28d4ef00f8665b47771be1a4652e5fc9b463fcbacdc5fd86c6764b5bbf9dea28` | `b18683b638329458d878fd76160d4e37ffcbd9f34b6a3604c1bdc9573c7b6c9d` |
| `93124b52-f328-4f09-8c4f-6d022519fae2` | `f8b6650a4f7ef2e812751cd17a23ccc5456989d7c7acd8eac267c71572fae133` | `f8b6650a4f7ef2e812751cd17a23ccc5456989d7c7acd8eac267c71572fae133` |
| `9ed4737d-c74f-4374-923e-4663d3c9e256` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `f14374b1-655e-4df2-8e82-2d79c8df9174` | `a3670b4f1b1359667411a04d58363e2c33fab43d37eededf2f70386150403c31` | `2d93d9689f18f433ad68a9677797a62c812d8c60c44e3a6e1a24b2a43aa4d4ef` |
| `stored.rules` | `0866233a91f8bed69deca5d268bb4147a7ae86a34e743ba7e2bb46bd27cccabf` | `0866233a91f8bed69deca5d268bb4147a7ae86a34e743ba7e2bb46bd27cccabf` |

他4store・stored.rules・UUID対応は不変。他4上限も不変。build設定と本番Java/API/UI/認可は変更しない。七定数横の旧履歴原文は `docs/architecture/archunit_freeze_history.md` に完全維持し、初回補正を追記する。

## 検証状況

- 移行後の通常 `freeze.refreeze=false`・`allowStoreCreation=false` の既存CIを確認する。全6runnerの専用guard各2case、全archUnitTest、archTestのgreenと、担当runnerのafter8ファイルがcommitと同じであることを必須とする。
- L0-Cの全量total/domain差分が承認178だけであること、同じHEADの既存CI再実行でstore差分0であることを確認して追記する。
- 実機E2E・アリシゼーションは未実施。本番Java・API/UI・認可の変更がなく、変更対象となる実機操作経路がないため。

## L0-C集計の前後照合

既存 `backend/scripts/collect_archunit_debt.py --store-dir ...` を変更せず両方へ実行した。beforeは固定beforeから作成しArchUnit engineを実行していない所有snapshotで、生8SHAの一致も再確認した。全7番人のtotal/domain頻度差分は承認済み旧prefix増分の発生元domain集計と全件一致し、他4番人は完全一致した。

| 番人 | before total | after total | domain別増分 |
|---|---:|---:|---|
| AUTHZ_WAVE4 | 0 | 0 | 変化なし |
| D-1 | 2037 | 2156 | contact+1、dashboard+27、family+1、favorite+5、gdpr+6、market+2、membership+12、notification+2、organization+5、payment+2、publicview+25、recruitment+1、role+4、search+1、shiftbudget+5、succession+2、supporter+2、team+3、timetable+13 |
| D-2b | 569 | 569 | 変化なし |
| D-3 | 1292 | 1325 | chat+1、circulation+4、dashboard+2、family+1、market+2、membership+10、notification+1、organization+8、payment+1、publicview+2、village+1 |
| D-3T | 7325 | 7325 | 変化なし |
| D-5 | 1850 | 1876 | advertising+5、family+1、gdpr+1、market+2、membership+10、notification+1、organization+2、publicview+2、role+1、village+1 |
| ServiceAPI | 600 | 600 | 変化なし |
