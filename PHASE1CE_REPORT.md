# Phase 1-C/E 確認レポート（2026-05-09）

## Phase 1-C: コアエンティティ deleted_at チェック

| Entity | @SQLRestriction | 状態 |
|---|---|---|
| UserEntity | `@SQLRestriction("deleted_at IS NULL")` | OK |
| TeamEntity | `@SQLRestriction("deleted_at IS NULL")` | OK |
| OrganizationEntity | `@SQLRestriction("deleted_at IS NULL")` | OK |

全エンティティに `@SQLRestriction` が設定されており、JPA が自動で `WHERE deleted_at IS NULL` を付与する。
Repository 側への明示的な記述は不要で、現状の実装は正しい。

## Phase 1-E: @Transactional 越境箇所 TODO 追記

TODO 追記数: **20件**

| ファイル | 対象メソッド | 越境ドメイン | 将来の対応方針 |
|---|---|---|---|
| `schedule/service/ScheduleService.java` | `getMyCalendar` | schedule→role（UserRoleRepository） | UserRoleQueryServiceのAPI呼び出しに分離 |
| `schedule/service/IcalService.java` | `generateIcalFeed` | schedule→role（UserRoleRepository） | UserRoleQueryServiceのAPI呼び出しに分離 |
| `schedule/service/IcalService.java` | `calculateETag` | schedule→role（UserRoleRepository） | UserRoleQueryServiceのAPI呼び出しに分離 |
| `schedule/service/ScheduleAttendanceService.java` | `respondAttendance` | schedule→proxy（ProxyInputRecordRepository） | ProxyInputServiceのAPI呼び出しに分離 |
| `schedule/service/ScheduleAttendanceService.java` | `getTeamAttendanceStats` | schedule→role（UserRoleRepository） | UserRoleQueryServiceのAPI呼び出しに分離 |
| `schedule/service/ScheduleAttendanceService.java` | `getOrgAttendanceStats` | schedule→role（UserRoleRepository） | UserRoleQueryServiceのAPI呼び出しに分離 |
| `schedule/service/ScheduleAttendanceService.java` | `getMyAttendanceStats` | schedule→role（UserRoleRepository） | UserRoleQueryServiceのAPI呼び出しに分離 |
| `shift/service/ShiftArchivedTodoCancelService.java` | クラスレベル（`cancelShiftLinkedTodos`） | shift→todo（TodoRepository） | TodoCommandServiceのAPI呼び出しに分離 |
| `shift/service/ShiftPreferenceReminderBatchService.java` | `processReminders` | shift→role・team（UserRoleRepository・TeamShiftSettingsRepository） | UserRoleQueryService・TeamShiftSettingsServiceのAPI呼び出しに分離 |
| `shift/service/ShiftRequestService.java` | `submitRequest` | shift→proxy（ProxyInputRecordRepository） | ProxyInputServiceのAPI呼び出しに分離 |
| `shift/service/ShiftRequestService.java` | `getRequestSummary` | shift→role（UserRoleRepository） | UserRoleQueryServiceのAPI呼び出しに分離 |
| `shift/service/ShiftToTaskService.java` | `createTodosForSchedule` | shift→todo（TodoRepository） | TodoCommandServiceのAPI呼び出しに分離 |
| `shift/event/ShiftPublishedNotificationListener.java` | `onShiftPublished` | shift→role（UserRoleRepository） | UserRoleQueryServiceのAPI呼び出しに分離 |
| `chat/service/ChatChannelService.java` | `createChannel` | chat→auth・user・role・dashboard（UserRepository・UserBlockRepository・UserRoleRepository・ChatContactFolderItemRepository） | 各QueryService/Eventで分離 |
| `chat/service/ChatChannelService.java` | `startConversation` | chat→auth・user・role・dashboard | 各QueryService/Eventで分離 |
| `chat/service/ChatChannelService.java` | `inviteToZimmer` | chat→auth・user・role・dashboard | 各QueryService/Eventで分離 |
| `family/service/CareAbsentAlertBatchService.java` | `runNoContactCheck` | family→event（EventRepository・EventRsvpResponseRepository・EventCheckinRepository） | EventQueryServiceのAPI呼び出しに分離 |
| `family/service/CareAbsentAlertBatchService.java` | `runAbsentAlertCheck` | family→event | EventQueryServiceのAPI呼び出しに分離 |
| `family/service/CareEventNotificationService.java` | `notifyRsvpConfirmed` 他4件 | family→event・auth（EventRepository・EventCareNotificationLogRepository・UserRepository） | EventQueryService・UserQueryServiceのAPI呼び出しに分離 |
| `family/service/EventEndReminderBatchService.java` | `runEndReminderCheck` | family→event・role（EventRepository・UserRoleRepository） | EventQueryService・UserRoleQueryServiceのAPI呼び出しに分離 |

### 対象ドメインまとめ

- **schedule** ドメイン: role・proxyドメインをまたぐ TODO 7件
- **shift** ドメイン: todo・role・team・proxyドメインをまたぐ TODO 7件
- **chat** ドメイン: auth・user・role・dashboardドメインをまたぐ TODO 3件（メソッド数）
- **family** ドメイン: event・auth・roleドメインをまたぐ TODO 6件（メソッド数）

### 残件（未処理）

今回の作業では優先4ドメイン（schedule/shift/chat/family）に絞り、合計 20件の TODO を追記した。
全体では 104件超の越境箇所が存在するとされており、残件は Phase 2 以降で段階対応予定。

主な未処理ドメイン:
- `notification` ドメイン（多数のドメインから直接呼び出し）
- `event` ドメイン（team・user・scheduleを越境）
- `school` ドメイン（user・authを越境）
- `safetycheck` ドメイン（user・roleを越境）

## コンパイル確認

```
./gradlew compileJava --no-daemon -q
```

警告9件（既存）のみ。エラーなし。
