# CMP-261004-1816 選択した完了証拠

最終形を実装した[PR #4045](https://github.com/kenta-0420/mannschaft/pull/4045)のhead 1487f99fbba85b10b6c1098a7e8f20109b13c296 をimplementationとする。専用stage導入と旧件数不一致の修復は先行[PR #3635](https://github.com/kenta-0420/mannschaft/pull/3635)（merge aea916eb80c836029ffd901443e45efc2e33d60d、ストア同期c6c4f8d440105bf2ca3bb4447d212f1393a3d1a8）の成果であり、4045だけによる導入とはしない。

## 原受け入れ条件と検分

> archUnitTest と test の両経路で凍結ストアの行数が定数と一致し shard 5 が緑になる。減った行が事故（番人の検出範囲の縮小）でないことを確認する。

条件原文は変更していない。独立ソース検分と選択原XMLの独立parse・SHA256照合（2＋17件、全19 testcase F/E/S0）を実施した。専用Integrity stageの必須graph、現在の定数と実数、D3/D5の走査範囲、削減原因を確認した。backend/build.gradle.ktsの専用taskはarchUnitTestのfinalizerであり、testの必須依存でもある。shard/profile/CLI絞込みによってguardを除外せず、必要class欠損をNO-SOURCE成功にしない。D3/D5のAnalyzeClassesはcom.mannschaft.app全体とDoNotIncludeTestsを保つ。専用JVM分離による走査範囲縮小を示す差分はない。

固定main779dfe616e9113f32c85969bd6394a48cdb12431の七ストアは次のとおり、現在の実数と定数が一致する。

| ストア | 実数 | 定数 |
|---|---:|---:|
| public controller endpoints must have an authorization signal (Wave4) | 0 | 0 |
| no cross-domain entity dependency (D-1) | 2157 | 2157 |
| transactional should not span other-domain repositories (D-3) | 1346 | 1346 |
| transactional entry should not transitively reach other-domain repositories (D-3T) | 7352 | 7352 |
| entities should extend UuidV7Entity (D-2b) | 569 | 569 |
| no cross-domain repository dependency (D-5) | 1897 | 1897 |
| service API must not expose entities in signature (D-1 API boundary) | 600 | 600 |

この一致は受入時点の観測事実であり、将来も減少を拒否する保証ではない。[親設計Q5・第7.4節](../architecture/modular_monolith_boundary_debt_repayment.md)とPR4045の承認後、番人は上限以内・形式・重複・整列・登録閉集合を検査する方式になった。一行減少・上限未満・空が通り、上限超過は拒否される。旧完全一致への復旧は行わない。

ソース検分の裁定記録SHA256は45735405abb2bcb895ed08c3950ab35c94a21acfd2bdaf4fd7b50195b4d667bc。原XMLの独立parseとhash照合を含む根拠確認の記録であり、公開候補自体の最終出荷判断とは区別する。参照実装は[必須task graph](../../backend/build.gradle.kts)、[Integrity](../../backend/src/test/java/com/mannschaft/app/common/architecture/ArchUnitFreezeStoreIntegrityTest.java)、[D3](../../backend/src/test/java/com/mannschaft/app/common/architecture/CrossDomainTransactionalArchTest.java)、[D5](../../backend/src/test/java/com/mannschaft/app/common/architecture/CrossDomainRepositoryDependencyArchTest.java)。

## 旧減少の原因

PR3635のD3は1407→1366（41行）。AccountPurgeServiceの38行はTransactionTemplate化による注釈入口scopeの変化であり、実越境返済とは呼ばない。残る3行はShiftRequestServiceの直接UserRoleRepository依存除去に対応する。D5は1938→1865、23主体73行。各本番変更と移設先を照合した要約を示す。common窓口への集約は依存全消滅ではなく、既存除外対象の窓口への移設である。

| D5主体 | 削除行 | 本番commit | 対応する変更 |
|---|---:|---|---|
| actionmemo.service.ActionMemoScopeService | 4 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |
| dashboard.controller.DashboardController | 10 | d883cdfb40、bf5b3a4589 | UserRole直接呼出をMembershipScopeQueryServiceへ、ShiftAssignmentをShiftMyServiceへ。正本割当の読取へ変更 |
| dashboard.service.DashboardService | 4 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |
| gamification.event.GamificationPointListener | 4 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |
| inbox.service.adapter.AnnouncementInboxAdapter | 4 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |
| membership.service.MembershipService | 1 | cad8a84faf | RoleRepositoryのADMINロック呼出をAdminRoleMutationLockService.lockScopeAdminRowsへ。その他Repo用途は残る |
| notification.credit.batch.NotificationCreditExpiryBatch | 4 | 6f21206e76 | 直接UserRoleRepositoryと送信処理を除去。NotificationCreditExpiryRunnerで項目TX、NotificationCreditAlertSenderで通知。移設先はRoleService.getAdminUserIdsByOrganizationIdへ |
| notification.credit.service.NotificationCreditService | 3 | 780243ec16 | 直接UserRoleRepositoryと無料枠送信を除去。ApplicationEventPublisher→AFTER_COMMIT NotificationCreditFreeQuotaAlertListener。移設先はRoleServiceへ |
| organization.service.OrganizationHierarchyService | 3 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |
| organization.service.OrganizationService | 1 | cad8a84faf | RoleRepository.findByNameをAdminRoleMutationLockService.lockAdminRoleIdForCreationへ。その他Repo用途は残る |
| payment.service.PaymentRequirementService | 4 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |
| performance.service.PerformanceStatsService | 3 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |
| recruitment.service.RecruitmentListingService | 2 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |
| recruitment.visibility.RecruitmentListingVisibilityResolver | 3 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |
| schedule.controller.TeamAnnualScheduleController | 3 | 523c63bc80 | TeamOrgMembershipRepositoryをTeamOrgMembershipQueryService.findActiveOrganizationIdsInPrimaryOrderへ置換 |
| schedule.controller.TeamEventCategoryController | 3 | 523c63bc80 | TeamOrgMembershipRepositoryをTeamOrgMembershipQueryService.findActiveOrganizationIdsInPrimaryOrderへ置換 |
| schedule.service.IcalService | 6 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |
| schedule.service.ScheduleAttendanceService | 2 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |
| shift.service.ShiftRequestService | 3 | 97e425f0b3 | UserRoleRepository直接注入/メンバー候補読取を除去。非TX ShiftRequestFacadeがrole窓口から候補を取得してTX本体へ渡す |
| team.service.TeamService | 1 | cad8a84faf | RoleRepository.findByNameをAdminRoleMutationLockService.lockAdminRoleIdForCreationへ。その他Repo用途は残る |
| timetable.personal.service.PersonalTimetableDashboardService | 1 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |
| tournament.fee.TournamentFeePaymentService | 1 | d883cdfb40 | MembershipRepository.findActiveByUserAndScopeTypeをMembershipScopeQueryService.findCurrentMembershipOrganizationIds/findCurrentMembershipTeamIdsへ。他用途は残る |
| visibility.service.VisibilityTemplateEvaluator | 3 | d883cdfb40 | 在籍IDのUserRoleRepository直接呼出をcommon.MembershipScopeQueryService.findActiveTeamIds/findActiveOrganizationIdsへ集約。既設common除外に属する窓口にはRepo依存が残るため、実越境全消失とは扱わない |

旧ストアはclass-pair文言の重複を含むため、各削除行をbytecode occurrenceへ一対一で再同定した主張ではない。主体・相手Repository・本番差分と先行PR3635の原因検分記録を対応させた。D3注釈scope減少もD5 common集約も実越境全返済とは扱わない。

## 同headのCIと選択XML

[Backend run40073246043](https://github.com/kenta-0420/mannschaft/actions/runs/40073246043)は上記implementation headで最終attempt2成功。shard5は元attempt1の[job120811127553](https://github.com/kenta-0420/mannschaft/actions/runs/40073246043/job/120811127553)が成功し、再実行対象ではない。マージ時点の最新checkは22成功・1予定skipで、旧attempt1のshard3/Compile failureはマージ前の同名最新successに置換される。merge23daf720490cdda597b4596cd03f7fd3f5ac9b81、mergedAt2026-10-24T10:41:53Z。

19件の選択XMLは同headで実行された元attempt1の成功producerから取得した。run全体の最終attempt2成功を、全jobがattempt2で再実行された意味には使わない。

| 対象 | suite tests | failures/errors/skips | 実producer job（attempt） | artifact ID / ZIP bytes |
|---|---:|---|---|---|
| Integrity | 2 | 0 / 0 / 0 | 120811127476 (1) | 12553880713 / 18112953 |
| SelfTest | 17 | 0 / 0 / 0 | 120811127329 (1) | 12552117820 / 68655 |

| 対象 | GitHub digestと一致したZIP SHA256 | 選択XML SHA256 / bytes |
|---|---|---|
| Integrity | 4705a2afb1306ac893f868b2de7fbd7863d0cbb3bd6494c82a009e7a03edad86 | 39567a6dba7a2b1c5f2ee95129ad09865b5a4ace3364b130931a1f9387179f65 / 798 |
| SelfTest | 9a710a1e8e9fbc62396b564f9d16c1caef38fd1d077b41a53edeec5c03a93111 | ef8f222d1a7e76e501f7d85a6e7c548d873b93875643d54b91fb777d0661f1ad / 3464 |

Integrityは登録対応と七ストア健全性の2件、SelfTestは登録閉集合・欠損・上限境界・減少許容・形式・重複・整列等の17件で、各actual testcaseにもfailure/error/skipped要素がない。原XMLの日本語testcase名には文字化けがあるため修復せず保全し、source日本語名とのbytes一致を主張しない。公開するのは選択件数とdigestのみ。ZIPは採取後削除した。artifact作成時刻は各元producer jobの実行時間内にある。

現在mainとの関連source同一性はbuild、Integrity、D3/D5、Self、properties、backend-ci、freeze履歴、8storeの16pathで確認した。これは現在mainと同じHEADの新規greenではない。独立archUnitTestコマンドの新規actualや通常テストXML全量を再取得した主張でもない。JSONのredEvidenceは固定旧台帳に残る失敗の報告記録を指し、今回取得した失敗XMLや新規red実行ではない。新Gradleは実行せず、既存同head CIと必須graphを用いた。

実装PR4045は試験基準・番人・検体・記録のみのtest-onlyで、本番Java・画面・API・認可・導線を変更しない。実機E2Eと探索はその具体的差分がないため対象外とした。先行PR3635はbuild変更を含む別PRであり、このtest-only分類を遡及適用しない。
