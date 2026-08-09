# タイムゾーン棚卸し: 「業務ローカル時刻」判定の全域調査（Issue #2526 関連）

> **本文書は棚卸し（inventory）であり、設計判断・実装方針の決定は含まない。**
> 「チーム単位のタイムゾーン導入」の要否・方式はマスターの裁可事項であり、本文書は判断材料の提供のみを目的とする。
>
> - 調査日: **2026-08-09**
> - 調査範囲: `backend/src/main/java/com/mannschaft/app` 配下（Java 本体コード）。フロントエンドは前提事実の確認のみで再調査していない。
> - 作成者: 足軽（調査専任、実装なし）
> - 事実（実コードで確認済み）と推測（コードから読み取れない意図の解釈）を明確に区別して記載する。推測箇所には都度「推測」と明記する。

---

## 0. 前提事実（再調査せず所与とした事項）

- `teams` / `organizations` に timezone 相当のカラムは存在しない。
- `users` には既に `timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Tokyo'` が存在する（`V1.001__create_users_table.sql:16`、`UserEntity.java:130`）。
- FE の `frontend/app/composables/useDatetime.ts` は `authStore.user.timezone`（個人TZ）で表示を組んでおり、FE は「個人TZ」モデルで統一されている。
- `ClockConfig`（`backend/src/main/java/com/mannschaft/app/config/ClockConfig.java`）は `Clock.system(ZoneOffset.UTC)` を返す単一 Bean `utcClock`。
- `docs/` 配下にタイムゾーンの設計方針を定めた文書はこれまで存在しない（本文書が最初）。

---

## A. 仕分け基準（「業務ローカル時刻」か「監査タイムスタンプ」か）

### A-1. 判定基準

以下のいずれかに該当するものを「業務ローカル時刻の解釈が必要」（= テナントTZ導入の影響を受けうる）と判定する。

| # | 観点 | 該当条件 | 非該当（監査タイムスタンプ）の例 |
|---|---|---|---|
| 1 | 比較相手の型 | `DATE`/`TIME` 型カラム（例: `slot_date`, `start_time`, `reserved_date`）や、営業時間・締切のような「壁時計としての意味を持つ値」との比較 | `created_at`/`updated_at`/`deleted_at` 等 `DATETIME`（瞬間）同士の比較 |
| 2 | 暦日境界 | 「今日」「明日」「今月」など暦日の境界そのものが結果に意味を持つ（日をまたぐと判定が変わる） | 「N時間以内」のような相対経過時間の判定（瞬間同士の差分でよい） |
| 3 | 利用者への提示 | 通知本文・画面表示で「何時」として利用者に見える値（人間が「今何時か」を意識する） | システム内部でしか使わない技術的なタイムスタンプ |
| 4 | 比較対象の出自 | 比較の片方が業務ローカル時刻由来（`LocalDate.of(slotDate, startTime)` 等）で、もう片方を「今」で作る必要がある | 比較の両辺が同じ瞬間系（Clock由来）で完結している自己参照的な比較 |

**判定フロー（推奨する読み方）**: まず「比較相手は何か」を特定する。相手が `DATE`/`TIME` 型カラムなら基準1に該当し、原則「業務ローカル時刻」。相手が別の `DATETIME`（瞬間）なら基準4を確認し、両辺が同じ瞬間系Clockで完結していれば対象外。

### A-2. Issue #2526 の実例による具体化

#### 対象外と判断された例（`ReservationWaitlistService.java:275-280` 付近、`notifySlotReopened`）

```java
// Issue #2526 検討済み・変更しない: ここでの比較相手は notifiedAt であり、
// notifiedAt 自体も本メソッド内で LocalDateTime.now(clock) から書かれる（markNotified(now)）。
// 業務ローカル時刻（slot_date/start_time）は一切絡まない UTC Clock 同士の自己完結した比較のため、
// .withZone(ZoneId.systemDefault()) に変えると逆に他の判定基準とズレて壊れる。一律置換禁止。
LocalDateTime now = LocalDateTime.now(clock);
```
→ 基準4に該当（両辺が UTC Clock 由来の瞬間同士）。**対象外の模範例。**

#### 対象と判断され修正された例（`ReservationPendingExpireService.java:116` 付近、`findExpirableUnits`）

```java
// 根本には「枠の日時（slot_date/start_time）は業務ローカル時刻だが
// テナントのタイムゾーンを持たない」という設計負債がある。
LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));
```
`bookedAt`（`LocalDateTime.now()` = JVM既定ゾーンで書かれる）からの経過時間を測るため、比較の基準を「JVM既定ゾーンで解釈した瞬間」に揃えている。→ 基準1・4（比較相手 `bookedAt` の書き込み時基準に合わせる必要がある）に該当。**対象の模範例。**

他に同型で修正済み: `ReservationGroupService.java:188,369`（先頭枠の未来判定・キャンセル締切判定）、`ReservationWaitlistService.java:109,333`（過去枠拒否・失効クリーンアップ）、`ReservationReminderService.java:146`。いずれも比較相手が `slot_date`/`start_time` 由来の `LocalDateTime`。

### A-3. 本調査での運用

上記基準を、`ZoneId.systemDefault()` / `LocalDateTime.now()` / `LocalDate.now()`（いずれも引数なし・直書き）/ `ZoneId.of(...)` の出現箇所（全体で **約1932件**、ファイル単位で **924ファイル**、**107ドメイン**）に対して機械的に一次スクリーニングし、その後ドメイン単位でサンプル精査した。

一次スクリーニングの方法: 該当ファイル内に `Deadline`/`cutoff`/`isBefore`/`isAfter`/`slotDate`/`startTime`/「締切」「営業時間」等の語が共起するかを検索し、共起するファイルを「業務ローカル判定を含む可能性が高い（要精査）」候補とした。**この抽出はヒューリスティックであり、共起しなかった箇所に業務ローカル判定が皆無であることを保証しない**（誤検出・見落とし双方があり得る）。

---

## B. ドメイン別仕分け結果

### B-1. 全体分布（ファイル単位、上位30ドメイン）

`ZoneId.systemDefault()` / `LocalDateTime.now()` / `LocalDate.now()` / `ZoneId.of(...)` のいずれかを含むファイル数（同一ファイルに複数出現があっても1件としてカウント）。

| ドメイン | 該当ファイル数 | うち業務ローカル候補（ヒューリスティック） | 一次判定 |
|---|---:|---:|---|
| village | 65 | 6 | 要精査（festival/match recruit の日程系あり） |
| advertising | 42 | 5 | 要精査（キャンペーン期間・配信スケジュール） |
| payment | 35 | 1 | ほぼ対象外（監査中心。escrow の1件のみ要精査） |
| auth | 30 | 8 | 要精査（トークン失効・保護者同意の年齢境界日） |
| recruitment | 25 | 6 | **含む**（応募締切・ノーショー判定） |
| tournament | 23 | 2 | 要精査（提出要件エンティティ） |
| schedule | 22 | 12 | **含む**（B-2で全件精査、詳細後述） |
| reservation | 21 | 10 | **含む**（B-3で全件精査、詳細後述） |
| notification | 20 | 4 | 要精査（confirmable の期限） |
| family | 18 | 3 | 要精査（不在アラート・イベント終了リマインド） |
| school | 17 | 1 | 要精査（出席要件） |
| repairplan | 17 | 3 | 要精査（任期リマインド・見積カンバン） |
| errorreport | 16 | 2 | ほぼ対象外（クリーンアップ中心） |
| timetable | 15 | 2 | **含む**（個人時間割の「今日」判定） |
| succession | 15 | 2 | 要精査（アクセスガード・可視性） |
| admin | 15 | 0（サンプル外だが別途JST直書き2件あり） | 要精査 |
| todo | 12 | 1 | 要精査（共有メモ） |
| quickmemo | 12 | 2 | 要精査（既にユーザーTZ対応の兆候あり、後述） |
| gamification | 12 | 0 | 対象外の可能性（未精査） |
| disclosure | 12 | 3 | 要精査（開示エクスポート） |
| ticket | 11 | 3 | **含む**（有効期限バッチが「今日」判定） |
| social | 11 | 2 | 要精査（お知らせフィード） |
| role | 11 | 2 | 要精査（招待トークン） |
| residencestatus | 11 | 5 | 要精査（年次レビュー・訪問委員会） |
| parking | 11 | 2 | **含む**（訪問者予約の日付差分判定） |
| moderation | 11 | 3 | 要精査（違反・appeal） |
| incident | 11 | 3 | 要精査（SLAバッチに JST 直書きあり） |
| その他75ドメイン | 計約400 | 未精査分含む | 大半は監査タイムスタンプと推測されるが**未精査** |

（残り75ドメインは低effortでの全数精査が非現実的なため、ヒューリスティック候補ファイル一覧＝170ファイルを付録として保持しているが、個別のコード読解までは行っていない。**未調査**として正直に記載する。）

### B-2. schedule ドメイン（全件精査）— reservation と並ぶ最重要対象

**重大な発見**: schedule ドメインは reservation ドメインと異なり、Issue #2526 の是正パターン（`clock.withZone(ZoneId.systemDefault())`）が**適用されていない**。業務ローカル判定と思われる箇所の大半が素の `LocalDateTime.now()`（JVM既定ゾーン直読み）のままである。

| ファイル:行 | 内容 | 判定 |
|---|---|---|
| `ScheduleAttendanceService.java:913` | `LocalDateTime.now().isAfter(schedule.getAttendanceDeadline())` — 出欠回答締切判定 | **含む**（締切は業務ローカル時刻の想定） |
| `ScheduleMediaQueryService.java:257` | `LocalDateTime cutoff = LocalDateTime.now().minusHours(72)` | 要精査（72時間の相対判定だが、由来が投稿時刻＝業務ローカルなら対象） |
| `ScheduleReminderService.java:93` コメント | 「OffsetDateTime → JSTのLocalDateTimeに変換して保存（バッチ側はLocalDateTime.now()=JSTと比較）」 | **含む・明示的にJST前提が書かれている** |
| `ScheduleScheduledTaskService.java:47,76` コメント | 「ScheduledAt 保存時に OffsetDateTime を変換する先のタイムゾーン（バッチ側は LocalDateTime.now()=JST と比較）」 | **含む・明示的にJST前提が書かれている** |
| `PersonalScheduleService.java:400` コメント | 「バッチ側は LocalDateTime.now()（JVM=JST）と比較するため」 | **含む・JVM既定ゾーン=JSTという暗黙前提の明文化** |
| `GoogleCalendarWebhookService.java:170` | `channel.getExpiresAt().isBefore(LocalDateTime.now().plusDays(...))` | 要精査（Google側の絶対時刻との比較が主目的で、暦日境界に意味があるかは要確認） |
| `GoogleCalendarService.java:166,217,481,696` / `GoogleCalendarWebhookService.java:137,143,275,548,559` | トークン有効期限・最終同期時刻 | 対象外に近い（外部API都合のUTC/瞬間比較が主。ただし現状 `LocalDateTime.now()` で書いており、監査目的にせよ瞬間の取り違えリスクはある） |
| `IcalService.java:167,168,213,214` | iCal取得範囲 `now().minusMonths/plusMonths` | 要精査（「過去N月〜未来N月」の範囲は暦日境界に意味がある） |
| entity群（`ScheduleAttendanceEntity`, `ScheduleDelegationEntity`, `ScheduleCrossRefEntity` 等）の `createdAt`/`updatedAt`/`respondedAt`/`reviewedAt`/`sentAt` | 監査タイムスタンプ | **対象外**（瞬間の記録であり暦日・業務時刻の解釈不要） |

**所見（推測を含む）**: `PersonalScheduleService.java:400` と `ScheduleScheduledTaskService.java:47` のコメントは「JVM既定ゾーン = JST」であることを開発者が前提として明記しており、reservation の Issue #2526 と**同一クラスの潜在バグ**が schedule ドメインに存在する可能性が高い（推測）。ただし実際に JVM のデフォルトタイムゾーンが本番環境で何に設定されているかは本調査では確認していない（**未調査**）。もし本番の JVM 既定ゾーンが UTC であれば、この前提コメント自体が現状の実態と食い違っており「バッチが常に9時間ずれて動いている」可能性がある。これは棚卸しの範囲を超える実測（本番/CI環境変数 `TZ` の確認、または実際の稼働ログの確認）が必要なため、**結論は出さず要精査として明記するに留める**。

### B-3. reservation ドメイン（全件精査）

Issue #2526 で既に是正済み。`clock.withZone(ZoneId.systemDefault())` パターンが一貫して使われている。

| ファイル:行 | 内容 | 状態 |
|---|---|---|
| `ReservationPendingExpireService.java:116` | 失効対象抽出（`bookedAt` 基準） | 是正済み |
| `ReservationGroupService.java:188` | グループ予約：先頭枠が未来かの判定 | 是正済み |
| `ReservationGroupService.java:369` | グループ予約：キャンセル締切判定 | 是正済み |
| `ReservationWaitlistService.java:109` | キャンセル待ち登録：過去枠拒否 | 是正済み |
| `ReservationWaitlistService.java:333` | キャンセル待ち：失効クリーンアップ | 是正済み |
| `ReservationService.java:433` | 単枠キャンセル締切判定（`isCancelDeadlinePassed`） | 是正済み（コメントに「LocalDateTime.now() 直書きは CI 破壊地雷のため禁止」と明記） |
| `ReservationService.java:797` | （要文脈確認・締切/判定系と推測） | 是正済みパターンだが個別確認は未実施 |
| `ReservationReminderService.java:146` | リマインダー送出対象抽出 | 是正済み |
| `ReservationReminderEventListener.java:76` | リマインダーイベント処理 | 是正済み |
| `ReservationWaitlistService.java:319`（`notifySlotReopened`） | 空き通知の再通知抑制（`notifiedAt` 同士） | **対象外と明示判断済み**（A-2参照） |
| `ReservationAdminAlertQueryService.java:31` / `ReservationAdminQueryService.java:38` | `ZoneId JST = ZoneId.of("Asia/Tokyo")` の直書き | **C章で扱う固定JST直書き** |
| entity群（`ReservationEntity`/`ReservationSlotEntity`/`ReservationMenuEntity` 等の `bookedAt`/`confirmedAt`/`cancelledAt`/`completedAt`/`deletedAt`/`updatedAt`） | 監査タイムスタンプ | 対象外 |

**気になる矛盾（未解決のまま記載）**: `ReservationPendingExpireService.java` のjavadocコメント（93行目付近）には「`ReservationWaitlistService`（過去枠拒否・失効クリーンアップ）や `ReservationGroupService`（先頭枠が未来かの判定）は `LocalDateTime.now(clock)` で UTC Clock を直接使っており、JST 環境では最大9時間ずれる既知の不具合が残っている」と書かれているが、**実際のコード（本調査時点）ではこれら箇所は既に `.withZone(ZoneId.systemDefault())` に修正済み**であった。コメントが後続の修正に追随せず古い記述のまま残っている可能性が高い（推測）。ドキュメントとコードの不整合として指摘するに留め、本調査では判断・修正は行わない。

### B-4. その他ドメインで「含む」と判定した具体箇所（サンプリングで発見した代表例）

- `ticket/service/TicketExpiryBatchService.java:40,69,94` — `LocalDateTime.now()`／`cutoff`／`LocalDate today = LocalDate.now()` で有効期限バッチを判定。**含む**（暦日境界あり、直書き）。
- `parking/service/ParkingVisitorReservationService.java:91` — `ChronoUnit.DAYS.between(LocalDate.now(), request.getReservedDate())` で来訪予定日までの日数を算出。**含む**（`LocalDate.now()` 直書き、暦日境界に意味がある）。
- `timetable/personal/service/PersonalTimetableSlotService.java:73` — `LocalDate today = LocalDate.now()` で「今日」の時間割を判定。**含む**。
- `shift/service/ShiftRequestService.java:330` — `LocalDateTime.now().isAfter(schedule.getRequestDeadline())` でシフト希望締切判定。**含む**。
- `shift/service/ShiftAutoArchiveBatchService.java:48` / `ShiftCleanupBatchService.java:48,89` / `ShiftPreferenceReminderBatchService.java:73` — いずれも `ZoneId.of("Asia/Tokyo")` を直書きして `LocalDate`/`LocalDateTime` を算出。**含む・かつC章の固定JST直書きにも該当**。

### B-5. 「業務ローカル時刻の解釈が必要」と判定した箇所の総件数

- **全件精査した2ドメイン（reservation + schedule）で確認できた具体箇所: reservation 9件 + schedule 6件 = 15件**（是正済み・未是正を問わず、A-1基準に該当すると判定した箇所。うち reservation は是正済み、schedule は概ね未是正）。
- **サンプリングで確認した他ドメインの具体箇所: 5件**（ticket 1、parking 1、timetable 1、shift 2 のファイル単位。行単位ではticket 3行・shift 4行など複数箇所を含む）。
- **ヒューリスティック候補として抽出したが個別の行レベル確認まで至っていない残り: 約155ファイル**（170候補 − 上記で個別確認した約15ファイル）。この155ファイルの中に業務ローカル判定が実際に何件含まれるかは**未調査**。

したがって、**確度をもって「業務ローカル時刻の解釈が必要」と断定できたのは合計 約20件（行レベル）**であり、これは氷山の一角である可能性が高い（推測）。真の総量は候補170ファイルの全数精査（および共起語に引っかからなかった残り約750ファイルの再確認）をもって初めて確定する。**「約1932件のうち大半は監査タイムスタンプ」という前提は本調査で覆らなかった**が、業務ローカル判定の総数を正確に確定するには追加調査が必要、というのが正直な結論である。

---

## C. 固定JST直書き（`ZoneId.of("Asia/Tokyo")` 等）の全件リスト

コードに `ZoneId.of("Asia/Tokyo")` 等を直書きしている箇所（`backend/src/main/java` 配下、テストコード除く）。**これらは「JST以外のチームが混在した瞬間に破綻する」最も明白な証拠**である。

```
actionmemo/service/ActionMemoPublishingService.java:56       private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");
actionmemo/service/ActionMemoReminderBatchService.java:48     private static final ZoneId ZONE_FALLBACK = ZoneId.of("Asia/Tokyo");
actionmemo/service/ActionMemoReminderBatchService.java:67     ZonedDateTime nowUtc = ZonedDateTime.now(ZoneId.of("UTC"));
actionmemo/service/ActionMemoReminderBatchService.java:195    return ZoneId.of(tz);                         // ユーザーTZ文字列を動的解決（フォールバックがJST）
actionmemo/service/ActionMemoWeeklySummaryService.java:76     private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");
admin/service/AdminBusinessAlertService.java:55                private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
advertising/campaign/service/AdFrequencyCapService.java:52     private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Tokyo");
advertising/campaign/service/AdFrequencyCapService.java:250    return ZoneId.of(tz);                         // 同上、動的解決
advertising/controller/StripeAdInvoiceWebhookController.java:108  ZoneId.of("Asia/Tokyo") でLocalDateTime変換
advertising/controller/SystemAdminSpotlightBatchController.java:43  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
advertising/ranking/controller/EquipmentReplenishLinkController.java:88  LocalDateTime.now(ZoneId.of("Asia/Tokyo"))
advertising/ranking/service/EquipmentRankingBatchService.java:89,152  LocalDateTime.now(ZoneId.of("Asia/Tokyo")) ×2
advertising/ranking/service/EquipmentRankingService.java:89   LocalDateTime.now(ZoneId.of("Asia/Tokyo"))
advertising/service/AdDailyStatsAggregationBatchService.java:53  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
analytics/service/DailyAggregationBatchService.java:59         LocalDate.now(ZoneId.of("Asia/Tokyo")).minusDays(1)
analytics/service/DateRangeResolver.java:22                    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
analytics/service/MonthlyCohortBatchService.java:46             LocalDate.now(ZoneId.of("Asia/Tokyo"))
analytics/service/MonthlyKpiSnapshotBatchService.java:52         LocalDate.now(ZoneId.of("Asia/Tokyo"))
analytics/service/PageViewDailyAggregationBatchService.java:47  private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
analytics/service/PageViewRecordingService.java:48              private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
auth/guardianship/JapanGuardianshipAgePolicy.java:44            private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");  ※国別ポリシーとして意図的にJST固定の可能性（推測）
auth/service/ParentalConsentReleaseBatchService.java:77         private static final ZoneId BATCH_ZONE = ZoneId.of("Asia/Tokyo");
billing/beta/LoginActivityQueryService.java:86,207              DEFAULT_ZONE固定 + ユーザー指定TZの動的解決
budget/service/BudgetAdminSummaryQueryService.java:53           private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
cms/service/BlogFeedService.java:137                            ldt.atZone(ZoneId.of("Asia/Tokyo")).toInstant()
common/timezone/UserTimezoneFilter.java:78,163                 SERVER_ZONE固定 + リクエストTZの動的解決（共通基盤・後述）
common/timezone/UserZoneLocalDateTimeParser.java:97             public static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Tokyo");（共通基盤）
config/jackson/LenientOffsetDateTimeDeserializer.java:35         private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Tokyo");
dashboard/service/ScopeWidgetSummaryService.java:173             LocalDate.now(zoneId != null ? zoneId : ZoneId.of("UTC"))  ※フォールバックはUTC
digest/service/DigestConfigService.java:166                      ZoneId.of(timezone) のバリデーション用途
inbox/service/InboxTriageService.java:42                         private static final ZoneId APP_ZONE = ZoneId.of("Asia/Tokyo");
incident/service/IncidentSlaBatchService.java:189                ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))
incident/service/MaintenanceScheduleService.java:309             ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))
membership/service/MembershipStatsQueryService.java:35            private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
notification/confirmable/dto/ConfirmableNotificationCreateRequest.java:22  private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");
notification/service/NotificationService.java:228                 snoozedUntil.atZoneSameInstant(ZoneId.of("Asia/Tokyo"))
onboarding/service/OnboardingReminderBatchService.java:48          LocalDateTime.now(ZoneId.of("Asia/Tokyo"))
quickmemo/service/QuickMemoReminderBatchService.java:41            private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");
quickmemo/service/QuickMemoService.java:39                         private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
quickmemo/service/UserQuickMemoSettingsService.java:39              private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
reflection/service/RecallService.java:35,109                       DEFAULT_ZONE固定 + ユーザーTZ動的解決
reflection/service/ReflectionEntryService.java:41,311               同上
reflection/service/ReflectionSpacedReminderService.java:43,44,244   STORAGE_ZONE/FALLBACK固定 + ユーザーTZ動的解決
reflection/service/ReflectionTodayService.java:51,285                同上
repairplan/batch/TeamMemberTermDemoteBatch.java:48                  LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"))
repairplan/batch/TeamMemberTermReminderBatch.java:49                LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"))
reservation/service/ReservationAdminAlertQueryService.java:31       private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
reservation/service/ReservationAdminQueryService.java:38            private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
resume/service/ResumeExportService.java:209                         OffsetDateTime.now(ZoneId.of("Asia/Tokyo"))
shift/service/ShiftAutoArchiveBatchService.java:48                  LocalDate.now(ZoneId.of("Asia/Tokyo")).minusDays(...)
shift/service/ShiftCleanupBatchService.java:48,89                   LocalDateTime.now(ZoneId.of("Asia/Tokyo")) ×2
shift/service/ShiftPreferenceReminderBatchService.java:73            LocalDateTime.now(ZoneId.of("Asia/Tokyo"))
todo/batch/TodoDueReminderBatch.java:90,252                          FALLBACK_ZONE固定 + ユーザーTZ動的解決
```

**件数**: 定義・使用箇所を1行1件として数え、**53件**（上記リストの行数。同一ファイル内の複数箇所は別カウント）。ドメイン数では**約30ドメイン**にまたがる。

**パターンの分類（事実）**:
1. **完全固定型**（フォールバックもJST固定、動的解決なし）: `AdminBusinessAlertService` / `AdDailyStatsAggregationBatchService` / `DateRangeResolver` / `MonthlyCohortBatchService` 系 / `IncidentSlaBatchService` / `MembershipStatsQueryService` / `QuickMemoService` 系 / `repairplan` バッチ2件 / `reservation` の2件 / `ResumeExportService` / `shift` バッチ3件 など。テナントTZが導入されても**個別に書き換えないと反映されない**。
2. **ユーザーTZ動的解決＋JSTフォールバック型**（`ZoneId.of(userTimezoneCache.getTimezone(userId))` 等）: `actionmemo`, `advertising/AdFrequencyCapService`, `billing/LoginActivityQueryService`, `reflection` 全4サービス, `todo/TodoDueReminderBatch`。**既に `users.timezone`（個人TZ）を使う基盤が一部ドメインで実装済み**であることを示す（後述D章の論点に直結する事実）。
3. **共通基盤で明示的にJST固定**: `common/timezone/UserTimezoneFilter.java` / `UserZoneLocalDateTimeParser.java` — コメントに「なぜ `ZoneId.of("UTC")` ではないのか」という設計意図の記述がある（`UserTimezoneFilter.java:87`）。この共通基盤の詳細な設計意図・利用範囲は本調査では深掘りしていない（**未調査**、D章の論点に関わる）。

---

## D. 設計上の論点整理（結論は出さない）

### D-1. 個人TZ（既存）と店舗TZ（新規検討）が並立した場合、どちらを使うべきか

- 既に `users.timezone` を使った「個人TZ動的解決」パターンが `actionmemo`/`reflection`/`todo`/`billing`/`advertising` 等**複数ドメインで実装済み**（C章パターン2）。これらは「個人が見る通知・集計」を個人の生活時間で解釈する用途と推測される。
- 一方 reservation の `slot_date`/`start_time` は「店舗（チーム）が設定する枠」であり、その枠を予約する利用者のTZではなく**店舗側のTZ**で解釈するのが業務上自然、という考え方があり得る（推測・要マスター判断）。
- 選択肢（列挙のみ）:
  - (a) 判定対象ごとに「個人が見る値か」「店舗・チームが管理する値か」で個人TZ/店舗TZを使い分ける。
  - (b) 個人TZに一本化し、店舗TZは導入しない（既存 `users.timezone` の適用範囲を reservation/schedule にも拡大）。
  - (c) 店舗TZに一本化し、個人TZ側の実装（C章パターン2）も店舗TZ経由に将来的に寄せる。
- 得失は本調査の範囲外（推測での記載は避ける）。

### D-2. `ClockConfig`（現状UTC単一Bean）の扱い

- 選択肢:
  - (a) 現状維持（`utcClock` は瞬間のみを提供し、業務ローカル解釈は呼び出し側が個別に `withZone` する）。reservation の是正パターンがこれに該当。
  - (b) テナントTZ解決済みの `Clock` を都度生成するファクトリ/ヘルパーを共通化する（例: `ClockConfig` に `Clock forTenant(Long teamId)` のようなAPIを追加）。
  - (c) `Clock` Bean 自体は変えず、`UserZoneLocalDateTimeParser`（既存の共通基盤）と同様の「テナントZone解決＋比較ヘルパー」を新設する。
- 各選択肢の得失（パフォーマンス、テスト容易性、既存の `LocalDateTime.now(clock)` 呼び出し箇所への影響範囲）は未検討。

### D-3. timezone を `teams` に持たせる場合と `organizations` に持たせる場合の得失

- `teams` 保持: チーム＝店舗・拠点という設計に近い場合、拠点ごとのTZを直接表現できる。ただし同一チームが複数拠点にまたがる運用があるかは未調査。
- `organizations` 保持: 組織単位の既定TZとして扱える。ただし「同一組織で拠点が国をまたぐケース」（例: 日本本社＋海外支店が同一 `organizations` 配下）では組織単位のTZでは拠点ごとの差異を表現できない。この場合 `teams` 側での上書きが必要になる、という構造的な論点がある。
- ハイブリッド（`organizations` に既定値、`teams` に上書き可能なnull許容カラム）という選択肢もあり得るが、これも列挙に留める。
- 現状 `mannschaft` の組織/チーム/拠点の実際の運用実態（1組織が複数国にまたがる例が実在するか）は本調査では確認していない（**未調査**）。

### D-4. 既存データのバックフィル方針

- 選択肢:
  - (a) 新カラムに `NOT NULL DEFAULT 'Asia/Tokyo'`（`users.timezone` と同じパターン）で追加し、全既存行を暗黙的にJST扱いとする。
  - (b) `NULL` 許容にして「未設定＝JVM既定ゾーンにフォールバック」とする（既存の C章パターン1のフォールバック実装と整合させやすい）。
  - (c) 移行バッチで各チーム/組織の実態（利用者の `users.timezone` 分布等）から推定値を設定する。
- 現状、日本国外の運用実績があるかどうかは本調査で確認していない（**未調査**）。もし全チームが実質JSTのみであれば、バックフィル自体のリスクは小さいと推測されるが、これは推測に留まる。

---

## 付録: 未調査・判断に迷った点の一覧

1. **本番/CI環境のJVM既定タイムゾーン設定が何か**（`TZ` 環境変数、Dockerイメージのタイムゾーン設定、Spring Boot起動オプション等）を実測していない。schedule ドメインのコメントは「JVM既定ゾーン=JST」を前提にしているが、これが実態と一致しているかは未確認。もし本番が実は UTC 稼働なら、schedule ドメインの締切判定は**現在進行形で壊れている**可能性がある（推測、要実測）。
2. **170件のヒューリスティック候補ファイルのうち、reservation/schedule/ticket/parking/timetable/shift 以外の約150ファイル**は行レベルでの個別確認をしていない。「含む/含まない」の最終判定は保留（要精査のまま）。
3. **候補抽出で漏れた可能性のあるファイル**（共起語検索に引っかからなかった約750ファイル）を再確認していない。命名規則から外れた業務ローカル判定（例: 変数名が `deadline` ではなく独自の言い回しのもの）を見落としている可能性がある。
4. `common/timezone/UserTimezoneFilter.java` / `UserZoneLocalDateTimeParser.java` という共通基盤が既に存在し、リクエストスコープでのTZ解決の仕組みを持っていることが判明したが、この基盤がどのドメインでどう使われているか、reservation/schedule がなぜこの基盤を使っていないのかは深掘りしていない。
5. `ReservationPendingExpireService.java` のjavadocコメントに残る「未修正」という記述と実コードの状態（既に修正済み）の食い違いの原因（コメント更新漏れか、別のリファクタで再度直したのか）は特定していない。
6. `JapanGuardianshipAgePolicy.java` のJST固定は「国別ポリシー」という性質上、意図的な固定である可能性があるが、この判断はドメイン知識を要するため本調査では踏み込んでいない。
7. FE側（`useDatetime.ts` 以外の箇所でTZに依存する処理があるか）は前提事実の確認のみで、再調査していない。

---

以上。
