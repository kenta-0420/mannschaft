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
| schedule | 22 | 1（訂正: 当初「含む・12」と誤記載。B-2で行レベル全件精査した結果、確度をもって「含む」と言えるのは1件のみ） | 大半は対象外（監査タイムスタンプ・外部API絶対時刻比較）。**含む**は1件（詳細後述） |
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

### B-2. schedule ドメイン（全件精査・訂正版）

> **訂正履歴**: 本節は初版で「schedule は reservation と並ぶ含む・12件」としていたが、殿の実測指摘を受けて `backend/src/main/java/com/mannschaft/app/schedule` 配下の `ZoneId.systemDefault()` / `LocalDateTime.now()` / `LocalDate.now()` の全出現（48箇所）を1件ずつ読み直した。**その結果、確度をもって「業務ローカル時刻の解釈が必要」と言えるのは1箇所のみであり、初版の判定は過大だった。** 以下は再精査後の正確な内訳。

**実測結果（事実）**:
- `schedule` 配下に `LocalDate.now()` は **1件も存在しない**（`ZoneId.systemDefault()` / `LocalDateTime.now()` / `LocalDate.now()` を Grep ツールで機械的に列挙して確認）。したがって「暦日の境界」が意味を持つ判定は schedule には存在しない。
- 出現48箇所のうち、entity群（`ScheduleAttendanceEntity.respondedAt`、`ScheduleDelegationEntity.updatedAt/reviewedAt`、`ScheduleCrossRefEntity.createdAt/respondedAt`、`ScheduleScheduledTaskEntity.updatedAt/deletedAt`、`ScheduleAttendanceReminderEntity.createdAt/sentAt`、`ScheduleMediaUploadEntity.createdAt`、`UserGoogleCalendarConnectionEntity.lastSyncErrorAt`、`UserScheduleGoogleEventEntity.lastSyncedAt`、`UserIcalTokenEntity.lastPolledAt`、`EventSurveyEntity.createdAt`、`PersonalScheduleReminderEntity.createdAt`、`ScheduleEntity.deletedAt` 等・計21箇所）は**すべて監査タイムスタンプ**（A-1基準1・4により対象外）。

| ファイル:行 | 内容 | 比較相手 | 判定 | 根拠 |
|---|---|---|---|---|
| `ScheduleAttendanceService.java:913` | `LocalDateTime.now().isAfter(schedule.getAttendanceDeadline())` | `schedule.getAttendanceDeadline()` | **含む**（確定） | `attendanceDeadline` は `CreateScheduleRequest`/`UpdateScheduleRequest` が `OffsetDateTime`（クライアントTZ付き絶対時刻）で受け取り、`ScheduleService.java:217`（`toJst(...)`）で `Asia/Tokyo` 壁時計の `LocalDateTime` に変換して保存する（`ScheduleEntity.attendanceDeadline`、javadoc「出欠回答期限（JST の LocalDateTime）」）。読み出し側は素の `LocalDateTime.now()`（JVM既定ゾーン依存）と比較しており、**JVM既定ゾーンが Asia/Tokyo と一致しない環境では締切判定がずれる**。これは reservation の Issue #2526 と同型の構造的リスクである（保存側は固定JSTでエンコード、比較側はJVM既定ゾーン依存の `now()`）。 |
| `GoogleWebhookChannelRenewalBatch.java:45` | `LocalDateTime.now().plusDays(...)` と `channel.getExpiresAt()` の比較 | Google Calendar Webhookチャンネルの有効期限（外部APIが発行する絶対時刻） | 対象外 | 基準4：比較相手は業務ローカル値ではなく外部サービスが発行した絶対時刻（瞬間）。 |
| `GoogleCalendarService.java:166,217,481,681,696` / `GoogleCalendarWebhookService.java:137,143,170,275,548,559` | OAuthトークン有効期限・Webhookチャンネル有効期限・最終同期時刻 | いずれも外部API（Google）が発行する絶対時刻、または技術的な同期記録 | 対象外 | 基準1・4：DATE/TIME型の業務ローカル値との比較ではなく、外部サービスの絶対時刻または監査記録との比較。 |
| `IcalService.java:167,168,183,213,214` | iCal取得範囲 `now().minusMonths(...)`/`plusMonths(...)`、最終ポーリング時刻更新 | 外部iCalフィードへの問い合わせ範囲、および `lastPolledAt`（監査記録） | 対象外 | 「過去N月〜未来N月」は取得ウィンドウの技術的な広さであり、利用者に「何時」として提示される値でも暦日境界の判定でもない。 |
| `ScheduleMediaQueryService.java:257` | `LocalDateTime cutoff = LocalDateTime.now().minusHours(72)` → `findOrphanMedia(cutoff)` | 孤立メディアの `createdAt`（アップロード時刻＝監査タイムスタンプ） | 対象外 | 基準1・4：比較相手はアップロード時刻という瞬間の記録であり、業務ローカルの暦日・営業時間とは無関係な技術的保持期間（72時間）。 |
| `ScheduleAttendanceService.java:461` | `LocalDateTime now = LocalDateTime.now()` → `findUnansweredUpcomingFor...(scopeId, userId, now)` | 予定の開始時刻（クエリ内部で「開始が現在以降」を判定） | 要精査 | クエリの中身（SQL/JPQL側の比較列）までは確認していない。予定開始時刻と比較している可能性があり、`ScheduleAttendanceService.java:913` と同様の構造リスクを持ちうるが、確定はしていない。 |
| `ScheduleReminderService.java:243` / `ScheduleScheduledTaskBatchService.java:95` | `LocalDateTime now = LocalDateTime.now()` → `findDuePage(now, ...)` / `findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(..., now)` | `remind_at`/`scheduledAt`（`ScheduleAttendanceReminderEntity`/`ScheduleScheduledTaskEntity` に `UserZoneLocalDateTimeParser.SERVER_ZONE`＝Asia/Tokyo 固定で変換保存された絶対時刻由来の値。詳細は `ScheduleScheduledTaskService.java:47,76`・`ScheduleScheduledTaskBatchService.java:190-191` を参照） | **要精査（含む寄り）** | 保存側は `OffsetDateTime`（絶対時刻）を `Asia/Tokyo` 壁時計に変換して格納しており、読み出し側の `LocalDateTime.now()` と正しく比較できるかは JVM既定ゾーンが Asia/Tokyo と一致するかに依存する。`attendanceDeadline` と同根の構造だが、`remind_at`/`scheduledAt` 自体が「利用者が指定した任意の絶対時刻」であり `slot_date`/`start_time` のような業務ローカルの暦日値そのものではないため、A-1基準1に厳密には該当しない可能性がある。**行レベルでの最終判定は保留**。 |
| `ScheduleReminderService.java:93` コメント / `ScheduleScheduledTaskService.java:47,76` コメント / `PersonalScheduleService.java:400` コメント | 「バッチ側は `LocalDateTime.now()`（JVM=JST）と比較するため」等 | — | コメントのみ（コード上の判定根拠にはならない） | 初版ではこれらのコメント文言のみを根拠に「含む」と判定していたが、**コメントに JST と書いてあることは判定根拠にならない**（殿の指摘のとおり）。実際の比較相手（`attendanceDeadline`/`scheduledAt`/`remind_at`）がどの型・由来かで判定すべきであり、上記の各行で個別に再判定した。 |

**結論（訂正）**: schedule ドメインで**確度をもって「含む」と判定できるのは `ScheduleAttendanceService.java:913`（出欠回答締切）の1件のみ**。`ScheduleReminderService.java:243` と `ScheduleScheduledTaskBatchService.java:95` は保存側が固定JSTエンコードである点で類似の構造的リスクを抱えるが、比較相手が「業務が設定する暦日値」ではなく「利用者が指定した任意の絶対時刻」であるため、A-1基準への当てはめが確定しておらず「要精査」に留める。それ以外（Google連携、iCal、メディアクリーンアップ、entity群の監査スタンプ）は対象外と判定する。

**所見（推測を含む）**: 上記の構造（`OffsetDateTime` を `Asia/Tokyo` 固定で `LocalDateTime` に変換して保存し、素の `LocalDateTime.now()` と比較する）自体は、JVM既定ゾーンが実際に `Asia/Tokyo` であることに依存しており、これは「テナントTZ」の論点というより「サーバーの技術的な保持基準（JST）とJVM既定ゾーンが一致しているか」という別種のリスクである（推測）。本番/CI環境のJVM既定タイムゾーンが実際に何かは本調査では確認していない（**未調査**）。もし一致していなければ、`ScheduleAttendanceService.java:913` の締切判定は現在進行形でずれている可能性があるが、これも実測なしでは断定できない。

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

### B-5. 「業務ローカル時刻の解釈が必要」と判定した箇所の総件数（訂正）

- **reservation（全件精査）: 8〜9件**（是正済み。`ReservationGroupService.java:188,369`、`ReservationWaitlistService.java:109,333`、`ReservationPendingExpireService.java:116`、`ReservationService.java:433`、`ReservationReminderService.java:146`、`ReservationReminderEventListener.java:76` の8件は確定。`ReservationService.java:797` は同型パターンだが個別文脈は未確認のため参考扱い）。
- **schedule（全件精査・訂正後）: 確定1件**（`ScheduleAttendanceService.java:913`）＋**要精査2件**（`ScheduleReminderService.java:243`、`ScheduleScheduledTaskBatchService.java:95`。当初「含む・複数件」としていたが、実測により対象外・要精査へ大幅に下方修正した）。
- **サンプリングで確認した他ドメインの具体箇所: 5件**（ticket 1、parking 1、timetable 1、shift 2 のファイル単位。行単位ではticket 3行・shift 4行など複数箇所を含む）。
- **ヒューリスティック候補として抽出したが個別の行レベル確認まで至っていない残り: 約155ファイル**（170候補 − 上記で個別確認した約15ファイル）。この155ファイルの中に業務ローカル判定が実際に何件含まれるかは**未調査**。

したがって、**確度をもって「業務ローカル時刻の解釈が必要」と断定できたのは reservation 8件 + schedule 1件 + 他ドメインサンプル 5件 = 合計 約14件（行レベル）**であり、これに「要精査」（reservation 1件・schedule 2件）を加えても約17件である。初版で「約20件」としていたのは、schedule ドメインの実測不足によりコメント文言（「JST」という記載があること自体）を判定根拠に使ってしまったための過大評価だった。**確定件数はこれで全てではなく、氷山の一角である可能性は依然として残る**（未確認の候補155ファイルが残っているため）。真の総量は候補170ファイルの全数精査（および共起語に引っかからなかった残り約750ファイルの再確認）をもって初めて確定する。**「約1932件のうち大半は監査タイムスタンプ」という前提は、少なくとも schedule ドメインの実測によって裏付けられた**（48出現中21件が明確な監査タイムスタンプ、確定して業務ローカルと言えるのは1件のみ）。他ドメインでも同様の比率になる可能性は高いが、これは**推測**であり実測ではない。

---

## C. 固定JST直書き（`ZoneId.of("Asia/Tokyo")` の全件リスト（訂正版）

> **訂正**: 初版は「53件」としていたが、これは `ZoneId.of("Asia/Tokyo")` の**リテラル直書き**と、`ZoneId.of(変数)`（ユーザーTZ文字列などを動的に解決する呼び出し）を混同してカウントしていた。後者は「JST固定」ではなく、むしろ逆に**既にテナント/個人TZに対応しようとしている箇所**であり、固定直書きの証拠として数えるのは不正確だった。Grep ツールで `ZoneId\.of\(` を再列挙し、引数がリテラル文字列 `"Asia/Tokyo"` のもの（コメント中の言及を除く）のみを数え直した結果は**48ファイル・51箇所**であり、これは殿の実測（48ファイル・51箇所）と一致した。

```
actionmemo/service/ActionMemoPublishingService.java:56        private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");
actionmemo/service/ActionMemoReminderBatchService.java:48      private static final ZoneId ZONE_FALLBACK = ZoneId.of("Asia/Tokyo");
actionmemo/service/ActionMemoWeeklySummaryService.java:76      private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");
admin/service/AdminBusinessAlertService.java:55                private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
advertising/campaign/service/AdFrequencyCapService.java:52     private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Tokyo");
advertising/controller/StripeAdInvoiceWebhookController.java:108  LocalDateTime.ofInstant(..., ZoneId.of("Asia/Tokyo"))
advertising/controller/SystemAdminSpotlightBatchController.java:43  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
advertising/ranking/controller/EquipmentReplenishLinkController.java:88  LocalDateTime.now(ZoneId.of("Asia/Tokyo"))
advertising/ranking/service/EquipmentRankingBatchService.java:89,152  LocalDateTime.now(ZoneId.of("Asia/Tokyo")) ×2
advertising/ranking/service/EquipmentRankingService.java:89    LocalDateTime.now(ZoneId.of("Asia/Tokyo"))
advertising/service/AdDailyStatsAggregationBatchService.java:53  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
analytics/service/DailyAggregationBatchService.java:59          LocalDate.now(ZoneId.of("Asia/Tokyo")).minusDays(1)
analytics/service/DateRangeResolver.java:22                     private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
analytics/service/MonthlyCohortBatchService.java:46              LocalDate.now(ZoneId.of("Asia/Tokyo"))
analytics/service/MonthlyKpiSnapshotBatchService.java:52          LocalDate.now(ZoneId.of("Asia/Tokyo"))
analytics/service/PageViewDailyAggregationBatchService.java:47   private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
analytics/service/PageViewRecordingService.java:48               private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
auth/guardianship/JapanGuardianshipAgePolicy.java:44             private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");  ※国別ポリシーとして意図的にJST固定の可能性（推測）
auth/service/ParentalConsentReleaseBatchService.java:77          private static final ZoneId BATCH_ZONE = ZoneId.of("Asia/Tokyo");
billing/beta/LoginActivityQueryService.java:86                   private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Tokyo");（フォールバック用。動的解決は別行・非カウント）
budget/service/BudgetAdminSummaryQueryService.java:53            private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
cms/service/BlogFeedService.java:137                             ldt.atZone(ZoneId.of("Asia/Tokyo")).toInstant()
common/timezone/UserTimezoneFilter.java:78                      private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Tokyo");（共通基盤・後述D-1で詳述）
common/timezone/UserZoneLocalDateTimeParser.java:97              public static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Tokyo");（共通基盤・後述D-1で詳述）
config/jackson/LenientOffsetDateTimeDeserializer.java:35          private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Tokyo");
inbox/service/InboxTriageService.java:42                          private static final ZoneId APP_ZONE = ZoneId.of("Asia/Tokyo");
incident/service/IncidentSlaBatchService.java:189                 ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))
incident/service/MaintenanceScheduleService.java:309              ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))
membership/service/MembershipStatsQueryService.java:35             private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
notification/confirmable/dto/ConfirmableNotificationCreateRequest.java:22  private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");
notification/service/NotificationService.java:228                  snoozedUntil.atZoneSameInstant(ZoneId.of("Asia/Tokyo"))
onboarding/service/OnboardingReminderBatchService.java:48           LocalDateTime.now(ZoneId.of("Asia/Tokyo"))
quickmemo/service/QuickMemoReminderBatchService.java:41             private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");
quickmemo/service/QuickMemoService.java:39                          private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
quickmemo/service/UserQuickMemoSettingsService.java:39               private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
reflection/service/RecallService.java:35                            private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Tokyo");（フォールバック用）
reflection/service/ReflectionEntryService.java:41                   private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Tokyo");（フォールバック用）
reflection/service/ReflectionSpacedReminderService.java:43,44        STORAGE_ZONE / ZONE_FALLBACK ×2（フォールバック用）
reflection/service/ReflectionTodayService.java:51                   private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Tokyo");（フォールバック用）
repairplan/batch/TeamMemberTermDemoteBatch.java:48                  LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"))
repairplan/batch/TeamMemberTermReminderBatch.java:49                LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"))
reservation/service/ReservationAdminAlertQueryService.java:31       private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
reservation/service/ReservationAdminQueryService.java:38            private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
resume/service/ResumeExportService.java:209                         OffsetDateTime.now(ZoneId.of("Asia/Tokyo"))
shift/service/ShiftAutoArchiveBatchService.java:48                  LocalDate.now(ZoneId.of("Asia/Tokyo")).minusDays(...)
shift/service/ShiftCleanupBatchService.java:48,89                   LocalDateTime.now(ZoneId.of("Asia/Tokyo")) ×2
shift/service/ShiftPreferenceReminderBatchService.java:73            LocalDateTime.now(ZoneId.of("Asia/Tokyo"))
todo/batch/TodoDueReminderBatch.java:90                              private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Tokyo");（フォールバック用）
```

**件数（訂正）**: **48ファイル・51箇所**（リテラル `"Asia/Tokyo"` の直書きのみを対象とし、`ZoneId.of(変数)` のような動的解決呼び出しは含めない）。ドメイン数では約26ドメインにまたがる。この48ファイル・51箇所は殿の実測値と一致している。

**除外した箇所（参考・別カテゴリ）**: 以下は「JST固定直書き」ではなく、**動的にユーザー/テナントのTZ文字列を解決する呼び出し**であり、性質が逆（テナントTZ対応の先例）のため上記件数から除外した。
- `ZoneId.of(tz)` / `ZoneId.of(timezoneStr)` / `ZoneId.of(userTimezoneCache.getTimezone(userId))` 等: `ActionMemoReminderBatchService.java:195`、`AdFrequencyCapService.java:250`、`LoginActivityQueryService.java:207`、`RecallService.java:109`、`ReflectionEntryService.java:311`、`ReflectionSpacedReminderService.java:244`、`ReflectionTodayService.java:285`、`TodoDueReminderBatch.java:252`、`UserTimezoneFilter.java:163`、`DigestConfigService.java:166`（バリデーション用途）
- コメント中の `ZoneId.of()` への言及のみ（コードとしての呼び出しではない）: `common/validation/ValidTimezone.java:18`、`common/validation/TimezoneValidator.java:16`
- `ZoneId.of("UTC")` （JSTではなくUTCの明示指定）: `ActionMemoReminderBatchService.java:67`、`ScopeWidgetSummaryService.java:173`（フォールバックのみ）

**パターンの分類（事実）**:
1. **完全固定型**（フォールバックもJST固定、動的解決なし）: `AdminBusinessAlertService` / `AdDailyStatsAggregationBatchService` / `DateRangeResolver` / `MonthlyCohortBatchService` 系 / `IncidentSlaBatchService` / `MembershipStatsQueryService` / `QuickMemoService` 系 / `repairplan` バッチ2件 / `reservation` の2件 / `ResumeExportService` / `shift` バッチ3件 など。テナントTZが導入されても**個別に書き換えないと反映されない**。
2. **ユーザーTZ動的解決＋JSTフォールバック型**（上記「除外した箇所」参照）: `actionmemo`, `advertising/AdFrequencyCapService`, `billing/LoginActivityQueryService`, `reflection` 全4サービス, `todo/TodoDueReminderBatch`。**既に `users.timezone`（個人TZ）を使う基盤が一部ドメインで実装済み**であることを示す（D-1で詳述）。
3. **共通基盤で明示的にJST固定**: `common/timezone/UserTimezoneFilter.java` / `UserZoneLocalDateTimeParser.java` — この共通基盤を D-1 で読み込んで詳述する。

---

## D. 設計上の論点整理（結論は出さない）

### D-0. 既存の共通基盤 `common/timezone/`（今回読み込んで判明した事実）

殿の指摘を受けて `common/timezone/UserTimezoneFilter.java` と `common/timezone/UserZoneLocalDateTimeParser.java` を全文読み込んだ。テナントTZ導入は**この既存基盤の上に乗るはずであり、これを知らずに設計すると車輪の再発明・二重実装になる**。以下、この基盤が「何を解決していて、何を解決していないか」を明記する。

**構成要素（事実）**:
1. `TimezoneContextHolder`（本調査では未読だが、両クラスから参照される保持先。**未調査**）— リクエストスレッドローカルに「解決済みZoneId」を保持する。
2. `UserTimezoneFilter`（`OncePerRequestFilter`、`@Order(LOWEST_PRECEDENCE - 9)`）— リクエストごとに、認証済みユーザーの `users.timezone`（`UserTimezoneCache` 経由、TTL5分）を読み、`TimezoneContextHolder` に「解決済みの印」付きでセットする。未ログイン・キャッシュ未使用時（`@WebMvcTest` スライス等）は `ZoneOffset.UTC` を「未解決の印」で積む。不正なTZ文字列は `Asia/Tokyo` にフォールバック。
3. `UserZoneLocalDateTimeParser`（static utility）— クライアントから届く日時文字列（リクエストボディ・クエリパラメータの両経路）を、**サーバー保持形式（`Asia/Tokyo` の壁時計 `LocalDateTime`、定数 `SERVER_ZONE`）へ正規化して解釈する**共通パーサ。
   - オフセット付き入力（`+09:00`/`Z` 等）: 瞬間が確定しているので `SERVER_ZONE`（Asia/Tokyo）の壁時計へ変換。
   - オフセット無し入力＋ユーザーTZが解決済み: そのユーザーTZの壁時計として解釈した上で `SERVER_ZONE` へ変換。
   - オフセット無し入力＋未解決（未認証・バッチスレッド）: `SERVER_ZONE`（Asia/Tokyo）の壁時計としてそのまま解釈（恒等変換）。
   - 夏時間（DST）のgap/overlapはJDK既定規則にそのまま従う（独自補正なし、意図的な設計判断とjavadocに明記）。
   - 値域超過（`+999999999-12-31...` 等）は `DateTimeException`/`ArithmeticException` を `DateTimeParseException` に正規化し、呼び出し側の400応答経路に合流させる。

**この基盤が解決していること（事実）**:
- **「入力（書き込み）時の解釈」を1箇所に集約**している。リクエストボディ（Jackson `LocalDateTimeTimezoneDeserializer`）とクエリパラメータ（Spring `ConversionService`）という2つの別経路が、同じ解釈規則（`UserZoneLocalDateTimeParser`）を共有する構造になっている（javadocに「片方だけ直る／片方だけ壊れる事故を構造的に防ぐ」と明記）。
- ユーザー個人のTZ（`users.timezone`）を使って、クライアントが送るオフセット無し日時文字列を正しく解釈する仕組みは**既に存在する**。

**この基盤が解決していないこと（事実）**:
- **「読み出し（比較）時の `now()` 側」は関与しない。** `UserZoneLocalDateTimeParser` は入力文字列をサーバー保持形式（Asia/Tokyo固定）の `LocalDateTime` に変換するだけであり、B章で確認した `LocalDateTime.now()`（JVM既定ゾーン依存）とサーバー保持値を比較する箇所（`ScheduleAttendanceService.java:913` 等）の安全性は、この基盤の存在だけでは保証されない。JVM既定ゾーンが `Asia/Tokyo` と一致していることが暗黙の前提のままである。
- **「テナント（チーム・組織）TZ」という概念は一切扱っていない。** 解決するのは常に「個人（`users.timezone`）」のみであり、「店舗・チームが設定する営業時間・枠の日時をどのTZで解釈するか」という reservation/schedule の悩みには答えていない。全ての入力は最終的に単一の `SERVER_ZONE`（Asia/Tokyo 固定）に正規化される設計であり、**テナントごとに異なる正規化先を持つ余地は現状の実装には無い**（`SERVER_ZONE` は `public static final` の単一定数）。
- `TimezoneContextHolder` の詳細な実装・スコープ（リクエストスレッド以外＝バッチスレッドでどう振る舞うか）は本調査では読み込んでいない（**未調査**）。

### D-1. 個人TZ（既存）と店舗TZ（新規検討）が並立した場合、どちらを使うべきか

- 既に `users.timezone` を使った「個人TZ動的解決」パターンが `actionmemo`/`reflection`/`todo`/`billing`/`advertising` 等**複数ドメインで実装済み**（C章パターン2）。加えて `common/timezone/` パッケージ（D-0）が、リクエスト入力の解釈という**より基盤的なレイヤーで個人TZを既に扱っている**。これらは「個人が見る通知・集計・入力」を個人の生活時間で解釈する用途と推測される。
- 一方 reservation の `slot_date`/`start_time` は「店舗（チーム）が設定する枠」であり、その枠を予約する利用者のTZではなく**店舗側のTZ**で解釈するのが業務上自然、という考え方があり得る（推測・要マスター判断）。D-0で確認した通り、既存の `common/timezone/` 基盤は個人TZしか扱っておらず、店舗TZの概念を追加するならこの基盤とは別の解決経路（またはこの基盤の拡張）が必要になる。
- 選択肢（列挙のみ）:
  - (a) 判定対象ごとに「個人が見る値か」「店舗・チームが管理する値か」で個人TZ/店舗TZを使い分ける。`common/timezone/` は個人TZ専用のまま残し、店舗TZは別の解決経路（例: `TeamTimezoneContextHolder` 相当）を新設する。
  - (b) 個人TZに一本化し、店舗TZは導入しない（既存 `users.timezone` ＋ `common/timezone/` の適用範囲を reservation/schedule の該当箇所にも拡大）。
  - (c) 店舗TZに一本化し、個人TZ側の実装（C章パターン2、および `common/timezone/` の `SERVER_ZONE` 正規化）も店舗TZ経由に将来的に寄せる。
- 得失は本調査の範囲外（推測での記載は避ける）。

### D-2. `ClockConfig`（現状UTC単一Bean）の扱い

- 選択肢:
  - (a) 現状維持（`utcClock` は瞬間のみを提供し、業務ローカル解釈は呼び出し側が個別に `withZone` する）。reservation の是正パターンがこれに該当。
  - (b) テナントTZ解決済みの `Clock` を都度生成するファクトリ/ヘルパーを共通化する（例: `ClockConfig` に `Clock forTenant(Long teamId)` のようなAPIを追加）。
  - (c) `Clock` Bean 自体は変えず、`UserZoneLocalDateTimeParser`（D-0で詳述した既存の共通基盤）と同様の「テナントZone解決＋比較ヘルパー」を新設する。ただし D-0 の通り `UserZoneLocalDateTimeParser` は「入力解釈」専用であり「`now()` との比較」は扱っていないため、この選択肢を採る場合は新規に「比較用ヘルパー」を設計する必要がある（既存基盤の単純な流用では済まない）。
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

1. **本番/CI環境のJVM既定タイムゾーン設定が何か**（`TZ` 環境変数、Dockerイメージのタイムゾーン設定、Spring Boot起動オプション等）を実測していない。`ScheduleAttendanceService.java:913` の出欠締切判定は「JVM既定ゾーン=Asia/Tokyo」に暗黙依存しているが、これが実態と一致しているかは未確認。もし本番が実は UTC 稼働なら、この締切判定は**現在進行形で壊れている**可能性がある（推測、要実測）。
2. **170件のヒューリスティック候補ファイルのうち、reservation/schedule/ticket/parking/timetable/shift 以外の約150ファイル**は行レベルでの個別確認をしていない。「含む/含まない」の最終判定は保留（要精査のまま）。
3. **候補抽出で漏れた可能性のあるファイル**（共起語検索に引っかからなかった約750ファイル）を再確認していない。命名規則から外れた業務ローカル判定（例: 変数名が `deadline` ではなく独自の言い回しのもの）を見落としている可能性がある。
4. `common/timezone/` の `TimezoneContextHolder`（`UserTimezoneFilter`/`UserZoneLocalDateTimeParser` から参照される保持先）自体の実装・バッチスレッドでの振る舞いは読み込んでいない。また、この基盤が reservation/schedule ドメインで実際に使われているか（使われていないなら理由は何か）は未確認。
5. `ReservationPendingExpireService.java` のjavadocコメントに残る「未修正」という記述と実コードの状態（既に修正済み）の食い違いの原因（コメント更新漏れか、別のリファクタで再度直したのか）は特定していない。
6. `JapanGuardianshipAgePolicy.java` のJST固定は「国別ポリシー」という性質上、意図的な固定である可能性があるが、この判断はドメイン知識を要するため本調査では踏み込んでいない。
7. FE側（`useDatetime.ts` 以外の箇所でTZに依存する処理があるか）は前提事実の確認のみで、再調査していない。
8. **（訂正で新たに生じた未確認事項）** `ScheduleReminderService.java:243` と `ScheduleScheduledTaskBatchService.java:95` を「要精査」としたが、`remind_at`/`scheduledAt` が A-1基準1（DATE/TIME型の業務ローカル値）に厳密に該当するかどうかの最終判断はしていない。「利用者が指定した任意の絶対時刻」と「業務が設定する暦日値」の境界線をどこに引くかは、A章の判定基準そのものの解釈に関わるため、マスター判断を仰ぐべき論点として残す。

---

## 訂正履歴

- **2026-08-09（初版公開後の同日訂正）**: 殿の実測指摘を受け、以下を修正した。
  1. schedule ドメインの判定を「含む・12件相当」から「確定1件（`ScheduleAttendanceService.java:913`）＋要精査2件」に下方修正（B-2, B-1, B-5）。誤りの原因は、コメント中の「JST」という記述のみを根拠に判定してしまい、実際の比較相手（DATE/TIME型か、外部API絶対時刻か、監査タイムスタンプか）を1行ずつ確認していなかったこと。
  2. C章の固定JST直書き件数を「53件」から「48ファイル・51箇所」に訂正。`ZoneId.of("Asia/Tokyo")` のリテラル直書きと `ZoneId.of(変数)` の動的解決呼び出しを混同していたことが原因。
  3. `common/timezone/UserTimezoneFilter.java` / `UserZoneLocalDateTimeParser.java` を全文読み込み、D-0として新設し、この基盤が「個人TZの入力解釈」を解決している一方「テナントTZ」「`now()` 側の比較」は解決していないことを明記した。

以上。
