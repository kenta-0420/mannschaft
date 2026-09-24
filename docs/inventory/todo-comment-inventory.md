# TODOコメント棚卸し（CMP-019 Wave 0）

基準は origin/main（94649eef63e43e7b822cd9b7d0e072ac85eec668）。生成物・vendor・node_modules・build・dist・target・.nuxtを除外し、正規表現に合うコメント中マーカーを機械抽出した207件（BE 184、FE 23）。分類別件数：越境整理 89件、将来仕様 62件、性能 2件、既存CMP・Issue候補 18件、実装不足 22件、非債務（TODO機能語）14件。旧 docs/TODO_LIST.txt は存在しない。

## FeatureFlag・AWS/R2

旧FeatureFlag 34件は棚卸し正本・番人（PR #2863）とFeatureGate適用工事（PR #2894、#2902、#2965、#3008、#3010、#3011）へ吸収済みで、今回の実コードTODOコメントには該当しない。AWS/R2は動かさない。AWSはCMP-012で凍結メモ化済み、R2は資格情報未整備のためdry-runまでとする。

## 優先候補

ScheduleMediaControllerのTODO（124、154行）はF03.14の権限（DEPUTY・SYSTEM_ADMIN・領収書解除）仕様矛盾が見つかったため、Issue #3253でAC確定を伴う第一陣とした。AdminModuleの将来実装コメントは行頭TODO抽出外だが、別途確認対象とする。

## 再現・検算

```powershell
$OutputEncoding=[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new()
git grep -n -I -E '//[[:space:]]*(TODO|FIXME|HACK|XXX)' origin/main -- backend/src/main frontend/app
```

出力207件と、各行のパス・行番号・原文要約を照合する。分類は初期判定であり、次陣で既存CMP/Issueを突合する。

## 全件台帳

| 分類 | パス | 行 | 原文コメント要約 | 処置先 |
|---|---|---:|---|---|
| 越境整理 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoAdminService.java | 74 | // TODO: actionmemoドメインがtodoドメイン(TodoRepository/TodoService)・roleドメイン(UserRoleRepository)・authドメイン(AuditLogService)をまたいでいる。将来はTodoRevertedByAdminEventで分離予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoAdminService.java | 97 | // TODO を OPEN に戻す（memo 所有者のIDで操作—TodoService の権限チェックをバイパスするため直接変更） | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoPublishingService.java | 92 | // TODO: actionmemoドメインとtimelineドメイン(TimelinePostRepository)をまたいでいる。将来はActionMemoPublishedEvent(PERSONAL)で分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoPublishingService.java | 181 | // TODO: actionmemoドメインがtimelineドメイン(TimelinePostRepository)・roleドメイン(UserRoleRepository)をまたいでいる。将来はActionMemoPublishedEvent(TEAM)で分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoPublishingService.java | 242 | // TODO: actionmemoドメインがroleドメイン(UserRoleRepository)・timelineドメイン(TimelinePostRepository)をまたいでいる。将来はイベント駆動で分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoReminderBatchService.java | 57 | // TODO: actionmemoドメインとnotificationドメイン・authドメイン(AuditLogService/UserRepository)をまたいでいる。将来はActionMemoReminderTriggeredEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoService.java | 102 | // TODO: actionmemoドメインがtodoドメイン(TodoRepository/TodoService)・timelineドメイン(TimelinePostRepository)・roleドメイン(UserRoleRepository)・organizationドメイン(OrganizationRepository)・authドメイン(AuditLogService)をまたいでいる。将来はイベント駆動で分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoService.java | 315 | // TODO: actionmemoドメインがtodoドメイン(TodoService)・roleドメイン(UserRoleRepository)・authドメイン(AuditLogService)をまたいでいる。将来はイベント駆動で分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoService.java | 456 | // TODO: actionmemoドメインとauthドメイン(AuditLogService)をまたいでいる。将来はActionMemoDeletedEventで分離予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoService.java | 479 | // TODO 紐付け | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoService.java | 485 | // TODO: actionmemoドメインとtodoドメイン(TodoRepository)をまたいでいる。将来はTodoLinkedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoSettingsService.java | 98 | // TODO: actionmemoドメインとroleドメイン(UserRoleRepository)をまたいでいる。将来はUserRoleVerifiedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoWeeklySummaryService.java | 172 | // TODO: actionmemoドメインとcmsドメイン(BlogPostRepository)をまたいでいる。将来はWeeklySummaryGeneratedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/actionmemo/service/ActionMemoWeeklySummaryService.java | 228 | // TODO: actionmemoドメインとcmsドメイン(BlogPostRepository)をまたいでいる。将来はWeeklySummaryGeneratedEventで分離予定 | 次陣で仕様化・Issue化 |
| 性能 | backend/src/main/java/com/mannschaft/app/admin/service/AdminBusinessAlertService.java | 151 | // TODO: チーム群一括判定クエリを追加して最適化する候補 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/admin/service/AdminDashboardService.java | 38 | // TODO: adminドメインがmoderationドメイン(ContentReportRepository)・roleドメイン(UserRoleRepository)・scheduleドメイン(ScheduleRepository)をまたいでいる。将来はドメインイベント集約またはQuery Serviceで分離予定 | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/auth/AuditEventType.java | 228 | // TODO(F17 Phase 1): 各 Village Service の主要メソッドへ | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/auth/service/AuthRegistrationService.java | 96 | // TODO: authドメインとroleドメインをまたいでいる（InviteService.joinByInviteを直接呼び出し）。将来はUserRegisteredEventで分離予定 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/auth/service/NewDeviceDetectionService.java | 63 | // TODO: NotificationDispatchService 連携は通知基盤の実装状況に応じて追加 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/auth/service/UserService.java | 225 | // TODO: userRoleRepository.isSystemAdmin() は role ドメイン直接参照。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/bulletin/repository/BulletinThreadRepository.java | 305 | // TODO: 将来は VillagePostCreatedEvent によるカウンタ非同期更新へ分離予定。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/chat/service/ChatBoardMigrationService.java | 33 | // TODO: chatドメインがbulletinドメイン（BulletinThreadService）をまたいでいる。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/chat/service/ChatBoardMigrationService.java | 96 | // TODO: chatドメインがbulletinドメインをまたいでいる。将来はイベント駆動化予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/chat/service/ChatChannelService.java | 214 | // TODO: chatドメインがauthドメイン（UserRepository）・userドメイン（UserBlockRepository）・roleドメイン（UserRoleRepository）・dashboardドメイン（ChatContactFolderItemRepository）をまたいでいる。将来はそれぞれのQueryService/Eventで分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/chat/service/ChatChannelService.java | 366 | // TODO: chatドメインがauthドメイン（UserRepository）・userドメイン（UserBlockRepository）・roleドメイン（UserRoleRepository）・dashboardドメイン（ChatContactFolderItemRepository）をまたいでいる。将来はそれぞれのQueryService/Eventで分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/chat/service/ChatChannelService.java | 457 | // TODO: chatドメインがauthドメイン（UserRepository）・userドメイン（UserBlockRepository）・roleドメイン（UserRoleRepository）・dashboardドメイン（ChatContactFolderItemRepository）をまたいでいる。将来はそれぞれのQueryService/Eventで分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/cms/service/BlogPostService.java | 85 | // TODO: publicview ドメインが cms ドメインを参照（CLAUDE.md 原則5）。将来はイベント駆動化を検討。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/cms/service/BlogPostService.java | 87 | // TODO: cms ドメインが team/organization ドメインを参照（CLAUDE.md 原則5）。将来はイベント駆動化を検討。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/cms/service/BlogPostService.java | 95 | // TODO: cms ドメインが payment ドメインを参照（CLAUDE.md 原則5）。クロスドメイン FK は張らず | 次陣で仕様化・Issue化 |
| 非債務（TODO機能語） | backend/src/main/java/com/mannschaft/app/common/GlobalExceptionHandler.java | 327 | Map.entry("TODO_010", HttpStatus.NOT_FOUND),             // TODO_NOT_FOUND (IDOR/BOLA 秘匿) | 対応不要 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/common/GlobalExceptionHandler.java | 497 | Map.entry("SCHEDULE_051", HttpStatus.CONFLICT),                 // TODO_ALREADY_LINKED | 次陣で仕様化・Issue化 |
| 非債務（TODO機能語） | backend/src/main/java/com/mannschaft/app/common/GlobalExceptionHandler.java | 618 | Map.entry("SHIFT_BUDGET_025", HttpStatus.NOT_FOUND),            // TODO_NOT_FOUND (IDOR 対策で 404) | 対応不要 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/common/GlobalExceptionHandler.java | 785 | Map.entry("TODO_033", HttpStatus.CONFLICT),                      // TODO_ALREADY_LINKED（連携重複 → 409） | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/common/GlobalExceptionHandler.java | 788 | // TODO_004/011/013/018/021/030/031/040/074/075/080/081 は入力値・組み合わせのバリデーション | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/common/visibility/ContentVisibilityChecker.java | 252 | // TODO (Phase A-1c): allow かつ shouldAuditAllow なら | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/contact/service/ContactHandleService.java | 57 | // TODO: ContactドメインとAuthドメイン・Userドメインをまたいでいる。将来はContactHandleUpdatedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/contact/service/ContactRequestService.java | 54 | // TODO: ContactドメインとAuthドメイン・Notificationドメイン・Userドメインをまたいでいる。将来はContactRequestSentEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/contact/service/ContactRequestService.java | 156 | // TODO: ContactドメインとAuthドメイン・Notificationドメインをまたいでいる。将来はContactRequestAcceptedEventで分離予定 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/dashboard/service/ChatHubService.java | 116 | // TODO: 未読数は別フェーズで実装予定。現在は 0 を返す。 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/dashboard/service/ChatHubService.java | 141 | // TODO: 未読数は別フェーズで実装予定。現在は 0 を返す。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/dashboard/service/ChatHubService.java | 251 | // TODO: totalUnread は未読数集計フェーズで正確な値に置き換える予定。現在はメンバーシップの合計値を使用。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/event/repository/EventRepository.java | 241 | // TODO: クロスドメイン参照(publicview→event)。将来はイベント駆動で分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/family/service/CareAbsentAlertBatchService.java | 106 | // TODO: familyドメインとeventドメインをまたいでいる（EventRepository・EventRsvpResponseRepository・EventCheckinRepositoryを直接参照）。将来はEventQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/family/service/CareAbsentAlertBatchService.java | 156 | // TODO: familyドメインとeventドメインをまたいでいる（EventRepository・EventRsvpResponseRepository・EventCheckinRepositoryを直接参照）。将来はEventQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/family/service/CareEventNotificationService.java | 56 | // TODO: familyドメインがeventドメイン（EventRepository・EventCareNotificationLogRepository）とauthドメイン（UserRepository）をまたいでいる。将来はEventQueryServiceとUserQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/family/service/EventEndReminderBatchService.java | 121 | // TODO: familyドメインがeventドメイン（EventRepository）とroleドメイン（UserRoleRepository）をまたいでいる。将来はEventQueryServiceとUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/faq/service/FaqAdminService.java | 63 | // TODO: faq → team のクロスドメイン参照（存在確認・カテゴリ解決のみ）。将来はイベント駆動化候補。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/faq/service/FaqAdminService.java | 65 | // TODO: faq → organization のクロスドメイン参照（存在確認・カテゴリ解決のみ）。将来はイベント駆動化候補。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/faq/service/FaqAdminService.java | 355 | // TODO: faq → team クロスドメイン参照（存在確認・カテゴリ解決のみ） | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/faq/service/FaqAdminService.java | 360 | // TODO: faq → organization クロスドメイン参照（存在確認・カテゴリ解決のみ） | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/inbox/event/InboxAnonymizationEventListener.java | 62 | // TODO(F04.11 Phase 3+): 失敗 userId を再処理キュー/メトリクスに積み、取りこぼしゼロを担保する。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobQrTokenController.java | 80 | // TODO(存在オラクル是正): 実挙動は「発行権限なし」も 404（JOB_PERMISSION_DENIED を 404 に写像済み）。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobQrTokenController.java | 134 | // TODO(存在オラクル是正): 実挙動は「閲覧権限なし」も 404。下の 403 宣言は実態と乖離しているが、 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/moderation/service/ContentReportService.java | 158 | // TODO: moderationドメインとauthドメインをまたいでいる（UserRepositoryを直接参照）。将来はUserReportingRestrictionChangedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/organization/service/OrganizationMembershipService.java | 184 | // TODO: OrganizationドメインとAuthドメイン・Roleドメイン・Teamドメインをまたいでいる。将来はMemberQueryServiceで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/organization/service/OrganizationService.java | 90 | // TODO: OrganizationドメインとAuthドメイン・Roleドメインをまたいでいる。将来はOrganizationCreatedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/organization/service/OrganizationService.java | 641 | // TODO: OrganizationドメインとRoleドメインをまたいでいる。将来はOrganizationDeletedEventで分離予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/payment/service/StripeWebhookService.java | 35 | // TODO: notificationドメイン → paymentドメインの依存。将来はWebhookEventで分離予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/payment/service/StripeWebhookService.java | 41 | // TODO: billing ドメイン → payment ドメインの委譲（NotificationCreditCheckoutService と同型）。将来は WebhookEvent で分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/proxy/service/ProxyInputConsentService.java | 52 | // TODO: proxyドメインとauthドメイン(AuditLogService)をまたいでいる。将来はProxyConsentCreatedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/proxy/service/ProxyInputConsentService.java | 126 | // TODO: proxyドメインとauthドメイン(AuditLogService)をまたいでいる。将来はProxyConsentApprovedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/proxy/service/ProxyInputConsentService.java | 165 | // TODO: proxyドメインとauthドメイン(AuditLogService)をまたいでいる。将来はProxyConsentRevokedEventで分離予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/publicview/metrics/PublicViewMetricsService.java | 31 | // TODO: publicviewドメインからteam/organizationドメインのRepositoryを横断参照。将来のイベント駆動化候補 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/publicview/metrics/PublicViewMetricsService.java | 38 | // TODO: publicviewドメインからteam/organizationドメインのRepositoryを横断参照。将来のイベント駆動化候補 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/AdminPublicSettingsService.java | 35 | // TODO: publicview → team のクロスドメイン参照。将来はイベント駆動化候補。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/AdminPublicSettingsService.java | 37 | // TODO: publicview → organization のクロスドメイン参照。将来はイベント駆動化候補。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/AdminPublicSettingsService.java | 54 | // TODO: publicview → team クロスドメイン参照 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/AdminPublicSettingsService.java | 80 | // TODO: publicview → organization クロスドメイン参照 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/publicview/service/PostAuthorSnapshotService.java | 36 | // TODO: publicview ドメインが team/org/auth ドメインの Repository を直接参照。将来はイベント駆動化を検討。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicEventQueryService.java | 45 | // TODO: クロスドメイン参照(publicview→team)。将来はイベント駆動で分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicEventQueryService.java | 48 | // TODO: クロスドメイン参照(publicview→organization)。将来はイベント駆動で分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicEventQueryService.java | 51 | // TODO: クロスドメイン参照(publicview→event)。将来はイベント駆動で分離予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicOrganizationSearchQueryService.java | 32 | // TODO: publicviewドメインからorganizationドメイン(OrganizationRepository)とcmsドメイン(BlogPostRepository)を | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicPostCommentService.java | 46 | // TODO: publicview → cms のクロスドメイン参照。将来はイベント駆動化候補。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicPostCommentService.java | 48 | // TODO: publicview → auth のクロスドメイン参照。将来はイベント駆動化候補。 | 次陣で仕様化・Issue化 |
| 性能 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicPostCommentService.java | 69 | // TODO: N+1 問題。コメント件数が多い場合は author_id をバルク取得して UserRepository.findAllById で解決すること。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicPostCommentService.java | 86 | // TODO: publicview → cms / auth クロスドメイン参照。将来はイベント駆動化候補。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicPostCommentService.java | 97 | // TODO: チームの supporter_name_disclosure = REAL_NAME の場合、本名スナップショットを設定する | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicPostCommentService.java | 151 | // TODO: publicview → cms クロスドメイン参照 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicPostQueryService.java | 78 | // TODO: publicview ドメインが payment ドメインを参照（CLAUDE.md 原則5）。クロスドメイン FK は張らず | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicTeamSearchQueryService.java | 32 | // TODO: publicviewドメインからteamドメイン(TeamRepository)とcmsドメイン(BlogPostRepository)を | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicTimelinePostQueryService.java | 44 | // TODO: クロスドメイン参照(publicview→team)。将来はイベント駆動で分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicTimelinePostQueryService.java | 47 | // TODO: クロスドメイン参照(publicview→organization)。将来はイベント駆動で分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicTimelinePostQueryService.java | 50 | // TODO: クロスドメイン参照(publicview→timeline)。将来はイベント駆動で分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/publicview/service/PublicUserProfileQueryService.java | 40 | // TODO: publicview → team のクロスドメイン参照。将来はチーム名をスナップショットで保持する方式に移行予定。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/publicview/service/SupporterNameDisclosureService.java | 44 | // TODO: publicview ドメインが team / organization ドメインの Repository を直接参照。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/publicview/service/ViewerContextBuilder.java | 39 | // TODO: publicview ドメインが role ドメインの Repository を直接参照。将来はイベント駆動化を検討。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/quickmemo/service/QuickMemoConvertToTodoService.java | 70 | // TODO を作成（PERSONAL スコープ） | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/recruitment/service/RecruitmentNoShowService.java | 106 | // TODO: F04.9 実装後に RECRUITMENT_NO_SHOW_RECORDED 通知を送信 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/recruitment/service/RecruitmentNoShowService.java | 175 | // TODO: F04.9 実装後に主催者へ RECRUITMENT_NO_SHOW_DISPUTE_RAISED 通知 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/recruitment/service/RecruitmentPenaltyLiftBatch.java | 58 | // TODO: F04.9 実装後に RECRUITMENT_PENALTY_LIFTED 通知を送信 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/recruitment/service/RecruitmentPenaltyRecomputeBatch.java | 133 | // TODO: F04.9 実装後に解除対象ユーザーへ RECRUITMENT_PENALTY_LIFTED 通知 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/recruitment/service/RecruitmentPenaltyService.java | 91 | // TODO: F04.9 実装後に RECRUITMENT_PENALTY_APPLIED (URGENT確認通知) を送信 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/recruitment/service/RecruitmentPenaltyService.java | 120 | // TODO: F04.9 実装後に RECRUITMENT_PENALTY_LIFTED 通知を送信 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/repairplan/batch/TeamMemberTermDemoteBatch.java | 77 | // TODO: 将来フェーズで MembershipService.demoteToMember(term.getUserId(), term.getScopeId()) を呼ぶ | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/repairplan/entity/ExternalAgentDelegation.java | 20 | // TODO: EXTERNAL_AGENT_DELEGATION_GRANTED/REVOKED 監査ログ — 委任サービス実装時に追加すること | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/repairplan/service/RepairPlanQuoteKanbanService.java | 235 | // TODO: VendorRepository は property ドメインへのクロスドメイン参照。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/repairplan/service/RepairPlanQuoteKanbanService.java | 334 | // TODO: クロスドメイン更新。将来 WorkPackageVendorSelectedEvent に分離予定。 | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/repairplan/service/RepairPlanScenarioService.java | 325 | // TODO: F02.8 AnnouncementBroadcastService への接続（将来フェーズ） | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/repairplan/service/RepairPlanScenarioService.java | 361 | // TODO: F09.8 CorkboardService のピン止めメソッドを呼ぶ（将来フェーズ） | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/repairplan/service/RepairPlanTimelineService.java | 30 | // TODO: TIMELINE_EXPORTED 監査ログ — エクスポートAPIが実装された時点で | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/repairplan/service/RepairPlanTimelineService.java | 44 | // TODO: 将来 UserQueryService に切り出す（クロスドメイン auth→repairplan 参照） | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/repairplan/service/TeamMemberTermService.java | 168 | // TODO: 将来フェーズで UserQueryService.getDisplayName(term.getUserId()) で解決 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/residencestatus/batch/ResidentActivityAggregatorBatch.java | 48 | // TODO: 組織・居住者を走査して upsertDailySnapshot を呼ぶ | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/residencestatus/batch/ResidentActivityAggregatorBatch.java | 50 | // TODO: 将来 EventListener 化予定（クロスドメイン依存を解消） | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/residencestatus/service/MonitoringCommitteeVisitService.java | 80 | // TODO: 将来は委員会 WATCHER ロール（F04.10）の検証を実装予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/residencestatus/service/OrgWideSafetyCheckService.java | 58 | // TODO: residencestatusドメインとsafetycheckドメインをまたぐ@Transactional。将来はOrgWideSafetyCheckTriggeredEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/role/service/InviteService.java | 251 | // TODO: RoleドメインとOrganizationドメイン・Teamドメイン・ScopeFolderドメインをまたいでいる。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/safetycheck/service/SafetyCheckService.java | 68 | // TODO: SafetycheckドメインとRoleドメイン・Notificationドメインをまたいでいる。将来はMemberCountResolvedEventとNotificationRequestedEventで分離予定 | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/schedule/controller/ScheduleMediaController.java | 124 | // TODO(F03.12): スケジュールの所属チームIDを解決して scopeId を渡す必要がある。 | Issue #3253 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/schedule/controller/ScheduleMediaController.java | 154 | // TODO(F03.12): スケジュールの所属チームIDを解決して scopeId を渡す必要がある。 | Issue #3253 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/schedule/service/IcalService.java | 145 | // TODO: scheduleドメインとroleドメインをまたいでいる（fetchSchedulesForFeed内でUserRoleRepositoryを参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/schedule/service/IcalService.java | 196 | // TODO: scheduleドメインとroleドメインをまたいでいる（fetchSchedulesForFeed内でUserRoleRepositoryを参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/schedule/service/ScheduleAttendanceService.java | 99 | // TODO: scheduleドメインとproxyドメインをまたいでいる（ProxyInputRecordRepositoryを直接参照）。将来はProxyInputServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/schedule/service/ScheduleAttendanceService.java | 549 | // TODO: scheduleドメインとroleドメインをまたいでいる（UserRoleRepositoryを直接参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。機能55: 2026-06-01 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/schedule/service/ScheduleAttendanceService.java | 645 | // TODO: scheduleドメインとroleドメインをまたいでいる（UserRoleRepositoryを直接参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/schedule/service/ScheduleAttendanceService.java | 679 | // TODO: scheduleドメインとroleドメインをまたいでいる（UserRoleRepositoryを直接参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/schedule/service/ScheduleAttendanceService.java | 710 | // TODO: scheduleドメインとroleドメインをまたいでいる（UserRoleRepositoryを直接参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/school/service/AttendanceTransitionDetectionService.java | 114 | // TODO: Phase 3/4 で通知処理を実装する | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/school/service/PeriodAttendanceService.java | 177 | // TODO: Phase 3 でチームメンバーリポジトリと正式連携 | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/school/service/PeriodAttendanceService.java | 262 | // TODO: Phase 3 で保護者・担任への権限拡張 | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/school/service/SchoolAttendanceNotificationService.java | 100 | // TODO: Phase 3 で user_care_links から保護者 userId を取得し、 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/school/service/SchoolAttendanceNotificationService.java | 128 | // TODO: NotificationDispatchService 経由で担任へプッシュ通知を送信する | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/school/service/SchoolAttendanceNotificationService.java | 144 | // TODO: NotificationDispatchService 経由で保護者へプッシュ通知を送信する | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/school/service/SchoolAttendanceNotificationService.java | 166 | // TODO: NotificationDispatchService 経由でプッシュ通知を送信する | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/school/service/SchoolAttendanceNotificationService.java | 184 | // TODO: NotificationDispatchService 経由でプッシュ通知を送信する | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/school/service/SchoolAttendanceNotificationService.java | 202 | // TODO: NotificationDispatchService 経由でプッシュ通知を送信する | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/school/service/SchoolAttendanceNotificationService.java | 220 | // TODO: NotificationDispatchService 経由でプッシュ通知を送信する | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/shift/service/ShiftArchivedTodoCancelService.java | 21 | // TODO: shiftドメインとtodoドメインをまたいでいる（TodoRepositoryを直接参照）。将来はTodoCommandServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/shift/service/ShiftPreferenceReminderBatchService.java | 75 | // TODO: shiftドメインがroleドメイン（UserRoleRepository）とteamドメイン（TeamShiftSettingsRepository）をまたいでいる。将来はそれぞれのQueryService経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/shift/service/ShiftPreferenceReminderBatchService.java | 187 | // TODO: SUPPORTER・GUEST を除外するロール別フィルタは Phase 4-1 で実装 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/shift/service/ShiftRequestService.java | 102 | // TODO: shiftドメインとproxyドメインをまたいでいる（ProxyInputRecordRepositoryを直接参照）。将来はProxyInputServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/shift/service/ShiftRequestService.java | 199 | // TODO: shiftドメインとroleドメインをまたいでいる（UserRoleRepositoryを直接参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/shift/service/ShiftToTaskService.java | 43 | // TODO: shiftドメインとtodoドメインをまたいでいる（TodoRepositoryを直接参照）。将来はTodoCommandServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/skill/service/SkillCsvService.java | 92 | // TODO: Phase 2 で S3 保存とジョブ進捗管理を実装する | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/social/announcement/AnnouncementSourceResolver.java | 78 | // TODO / SCHEDULE は F02.8 告知ウィザード専用。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/social/announcement/AnnouncementSyncEventListener.java | 40 | * // TODO: 各 Service から ApplicationEventPublisher.publishEvent() を呼ぶこと。例: | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/social/announcement/AnnouncementSyncEventListener.java | 175 | // TODO: 各 Service から ApplicationEventPublisher.publishEvent() を呼ぶこと。 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/social/announcement/controller/PersonalAnnouncementController.java | 51 | // TODO: AnnouncementFeedService.getPersonalFeed 実装後に注入する | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/social/announcement/controller/PersonalAnnouncementController.java | 86 | // TODO: AnnouncementFeedService.getPersonalFeed(userId, cursor, limit) を呼ぶ | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/social/service/FollowService.java | 252 | // TODO: SocialドメインとAuthドメインをまたいでいる。将来はFollowListVisibilityUpdatedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/social/service/FriendNotificationService.java | 106 | // TODO: SocialドメインとNotificationドメイン・Roleドメインをまたいでいる。将来はFriendNotificationDispatchedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/social/service/TeamFriendsService.java | 125 | // TODO: SocialドメインとAuthドメイン・Notificationドメイン・Roleドメイン・Teamドメイン・Timelineドメインをまたいでいる。将来はTeamFriendEstablishedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/social/service/TeamFriendsService.java | 297 | // TODO: SocialドメインとAuthドメイン・Notificationドメイン・Roleドメイン・Teamドメイン・Timelineドメインをまたいでいる。将来はTeamFriendDissolvedEventで分離予定 | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/social/service/TeamFriendsService.java | 439 | // TODO: Phase 3 で PostScopeType.FRIEND_ARCHIVE 正式追加後に | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/succession/service/DelinquencyEscalationListener.java | 65 | // TODO: residentドメインとsuccessドメインをまたぐ依存。将来はResidentDelinquentEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/succession/service/DelinquencyEscalationService.java | 54 | // TODO: residentドメイン → successionドメインのクロスドメイン呼び出し。将来は | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/team/service/TeamService.java | 104 | // TODO: teamドメインがroleドメイン(RoleRepository/UserRoleRepository)・socialドメイン(TeamFriendRepository)・membershipドメイン(MembershipRepository/MembershipService)・shiftドメイン(TeamShiftSettingsService)をまたいでいる。将来はTeamCreatedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/team/service/TeamService.java | 681 | // TODO: teamドメインがroleドメイン(UserRoleRepository)・socialドメイン(TeamFriendRepository)・membershipドメイン(MembershipRepository)をまたいでいる。将来はTeamUpdatedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/team/service/TeamService.java | 863 | // TODO: teamドメインとmembershipドメイン(MembershipRepository/MembershipService)をまたいでいる。将来はTeamFollowRequestedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/team/service/TeamService.java | 901 | // TODO: teamドメインとmembershipドメイン(MembershipRepository/MembershipService)をまたいでいる。将来はTeamUnfollowedEventで分離予定 | 次陣で仕様化・Issue化 |
| 実装不足 | backend/src/main/java/com/mannschaft/app/timeline/dto/TimelineFeedResponse.java | 74 | // TODO: 無限スクロール本実装時は最終投稿 ID をカーソルとして返す | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/timeline/repository/TimelinePostRepository.java | 319 | // TODO: クロスドメイン参照(publicview→timeline)。将来はイベント駆動で分離予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/timeline/repository/TimelinePostRepository.java | 435 | // TODO: 将来は VillagePostCreatedEvent によるカウンタ非同期更新へ分離予定。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/todo/batch/TodoDueReminderBatch.java | 99 | // TODO: ScheduleドメインとUserドメインをまたいでいる。将来はUserTimezoneQueryService等に分離予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java | 267 | // TODO ID は 404 秘匿）。進捗率・進捗モード EP と同一のガードに揃える。 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/todo/service/TodoHandoffService.java | 86 | // TODO: TodoドメインとAuthドメインをまたいでいる。将来はTodoHandoffAuditEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/todo/service/TodoScheduleLinkService.java | 35 | // TODO: TodoドメインとScheduleドメインをまたいでいる。将来はScheduleLinkedEventで分離予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/todo/service/TodoScheduleLinkService.java | 65 | // TODOを取得 | 次陣で仕様化・Issue化 |
| 非債務（TODO機能語） | backend/src/main/java/com/mannschaft/app/todo/service/TodoScheduleLinkService.java | 82 | // TODO側: 既に別のスケジュールと連携されていないか確認 | 対応不要 |
| 非債務（TODO機能語） | backend/src/main/java/com/mannschaft/app/todo/service/TodoScheduleLinkService.java | 87 | // TODO側更新（双方向: linked_schedule_id設定、必要なら親TODO変更） | 対応不要 |
| 非債務（TODO機能語） | backend/src/main/java/com/mannschaft/app/todo/service/TodoScheduleLinkService.java | 150 | // TODO側にlinked_schedule_idを設定（双方向リンク完成） | 対応不要 |
| 非債務（TODO機能語） | backend/src/main/java/com/mannschaft/app/todo/service/TodoScheduleLinkService.java | 188 | // TODO側をNULL化 | 対応不要 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/todo/service/TodoStatusService.java | 155 | // TODO(F02.7 Phase 15-3 残件): 現在は skippedLockedIds をログ出力のみで、APIレスポンスには含めていない。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryMemberService.java | 213 | .orElse("userId=" + userId); // TODO: UserQueryService で解決するまでのプレースホルダー | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryMemberService.java | 279 | // TODO: memberQueryDispatcher はチームスコープのメンバーを返すが、 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryMemberService.java | 289 | .memberNumber(null)   // TODO: TeamMemberRepository から解決 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryMemberService.java | 290 | .position(null)       // TODO: TeamMemberRepository から解決 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryMemberService.java | 330 | // TODO: memberQueryDispatcher はトランザクション境界の外で呼ぶことを推奨だが、 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryTemplateService.java | 173 | .orElse("userId=" + userId); // TODO: UserQueryService で解決するまでのプレースホルダー | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryTemplateService.java | 424 | // TODO: memberQueryDispatcher はチームドメインをまたぐ。将来はイベント駆動化予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/tournament/fee/TournamentFeePaymentService.java | 211 | // TODO: 越境（原則5）: tournament ドメインから payment ドメインを直接呼ぶ。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/tournament/leaguetransfer/LeagueTransferService.java | 340 | // TODO（通知再利用・原則5）: 受け入れ側 org（to_organization_id）/ チーム受信箱へ | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/tournament/service/TournamentContactSpaceProvisioningService.java | 30 | * // TODO: tournament ドメインから chat/bulletin ドメインの Service/Repository を直接呼んでいる。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/tournament/service/TournamentFolderService.java | 39 | * // TODO: tournament ドメインから filesharing ドメインの Service を直接呼んでいる。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/tournament/submission/TournamentSubmissionRequirementService.java | 310 | // TODO: 原則5 — tournament ドメインの本メソッドが forms ドメインの FormSubmissionService を介して | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/user/service/UserBlockService.java | 48 | // TODO: userドメインがcontactドメイン(ContactService/ContactRequestRepository/ContactRequestBlockRepository)・chatドメイン(ChatChannelRepository)・authドメイン(UserRepository)をまたいでいる。将来はUserBlockedEventで分離予定 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/user/service/UserBlockService.java | 95 | // TODO: userドメインとcontactドメイン(ContactRequestBlockRepository)をまたいでいる。将来はUserUnblockedEventで分離予定 | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/village/batch/VillageSerendipityBatchService.java | 125 | // TODO Phase 4: chat_messages（村ロビー）も同様に集計する。 | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/village/batch/VillageSerendipityBatchService.java | 159 | // TODO Phase 4: 反射爆撃対策（同一人物の連投で出会い回数が膨れる）と | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/village/service/VillageChronicleService.java | 84 | // TODO: 将来は VillagePostCreatedEvent を購読するカウンタテーブルへ分離し、 | 次陣で仕様化・Issue化 |
| 越境整理 | backend/src/main/java/com/mannschaft/app/village/service/VillageLobbyService.java | 75 | // TODO: chat と village ドメインをまたいでいる。将来 VillageCreatedEvent + ChatProvisioner 分離予定。 | 次陣で仕様化・Issue化 |
| 将来仕様 | backend/src/main/java/com/mannschaft/app/village/service/VillageNewsletterDigestAggregator.java | 49 | // TODO: 将来は VillagePostCreatedEvent を購読するカウンタテーブルへ分離し、read-only 越境を解消する（原則5）。 | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | backend/src/main/java/com/mannschaft/app/village/service/VillageSerendipityService.java | 190 | // TODO Phase 4: 専用 COUNT クエリ or materialized rank に置換して効率化 | 次陣で仕様化・Issue化 |
| 実装不足 | frontend/app/components/admin/PublicVisibleToggle.vue | 14 | // TODO: Phase 3 以降で API 実装時にインポートを有効化する | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | frontend/app/components/admin/PublicVisibleToggle.vue | 63 | // TODO: Phase 3 以降で以下のリクエスト本体を使い API を呼び出す | 次陣で仕様化・Issue化 |
| 既存CMP・Issue候補 | frontend/app/components/admin/PublicVisibleToggle.vue | 72 | // TODO: Phase 3 以降で以下の API 呼び出しを有効化する | 次陣で仕様化・Issue化 |
| 将来仕様 | frontend/app/components/dashboard/DashboardActionPanel.vue | 145 | // TODO 期限切れ | 次陣で仕様化・Issue化 |
| 非債務（TODO機能語） | frontend/app/components/dashboard/DashboardPersonalPanel.vue | 123 | 'todo-countdown',              // TODOカウントダウン | 対応不要 |
| 実装不足 | frontend/app/components/member/MemberFieldsManager.vue | 67 | // TODO: updateField が useMemberProfileApi に存在しないため編集機能は未実装 | 次陣で仕様化・Issue化 |
| 実装不足 | frontend/app/components/member/MemberFieldsManager.vue | 90 | // TODO: updateField が useMemberProfileApi に存在しないため更新は未実装 | 次陣で仕様化・Issue化 |
| 実装不足 | frontend/app/components/member/MemberFieldsManager.vue | 119 | // TODO: deleteField が useMemberProfileApi に存在しないため削除は未実装 | 次陣で仕様化・Issue化 |
| 非債務（TODO機能語） | frontend/app/components/todo/TodoHandoffDialog.vue | 160 | // TODO_NOT_FOUND — IDOR 対策で 404 | 対応不要 |
| 非債務（TODO機能語） | frontend/app/components/todos/TodoListTable.vue | 124 | // TODO は論理削除（soft delete）なので、Undo で restore EP を叩けば一覧に復活する。 | 対応不要 |
| 非債務（TODO機能語） | frontend/app/composables/useDashboardWidgets.ts | 227 | // TODOカウントダウン（WidgetTodoCountdown） | 対応不要 |
| 非債務（TODO機能語） | frontend/app/composables/useMyCalendarData.ts | 351 | // TODO 取得だけは部分失敗として扱う（カレンダー本体＝個人予定・共有予定・reflection は描画を続ける）。 | 対応不要 |
| 非債務（TODO機能語） | frontend/app/composables/useMyCalendarData.ts | 469 | // TODO の期限は LocalDate。ユーザーTZの 00:00:00 / 23:59:59 としてオフセット付きで組む | 対応不要 |
| 非債務（TODO機能語） | frontend/app/composables/useMyCalendarData.ts | 479 | // TODO は元々レイヤー API と同じ数値IDを持つ（MyCalendarTodo.scopeId: number）。 | 対応不要 |
| 実装不足 | frontend/app/composables/useSealApi.ts | 12 | // TODO: StampLogResponse の targetTitle / revokeReason が BE 未実装のため手動型を維持。 | 次陣で仕様化・Issue化 |
| 将来仕様 | frontend/app/composables/useShiftBudgetApi.ts | 291 | // TODO 紐付 | 次陣で仕様化・Issue化 |
| 将来仕様 | frontend/app/pages/blog/posts/[id]/edit.vue | 197 | // TODO: adminPublishPost が useBlogApi に追加されたら差し替えること | 次陣で仕様化・Issue化 |
| 将来仕様 | frontend/app/pages/blog/posts/[id]/edit.vue | 220 | // TODO: adminPublishPost が useBlogApi に追加されたら差し替えること | 次陣で仕様化・Issue化 |
| 非債務（TODO機能語） | frontend/app/pages/calendar.vue | 216 | // TODO イベントは負数 ID（-(todoId + 1) で格納） | 対応不要 |
| 将来仕様 | frontend/app/pages/organizations/[slug]/residence-status/monitoring-visit-form.vue | 2 | // TODO: i18n キーへの移行が必要 | 次陣で仕様化・Issue化 |
| 将来仕様 | frontend/app/pages/organizations/[slug]/residence-status/monitoring-visit-form.vue | 46 | subjectUserId: 0, // TODO: 実際のサブジェクトユーザーIDを設定できるよう拡張予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | frontend/app/pages/organizations/[slug]/residence-status/monitoring-visit-form.vue | 47 | visitorUserId: 0, // TODO: 実際の訪問者ユーザーIDを設定できるよう拡張予定 | 次陣で仕様化・Issue化 |
| 将来仕様 | frontend/app/pages/organizations/[slug]/residence-status/monitoring-visits-review.vue | 2 | // TODO: i18n キーへの移行が必要 | 次陣で仕様化・Issue化 |
