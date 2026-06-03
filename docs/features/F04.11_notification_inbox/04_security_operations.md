# F04.11: セキュリティ・運用・テスト・精査ログ

> **ステータス**: 🟢 設計確定（完了・未解決事項ゼロ）
> **最終更新**: 2026-05-30
> **関連ドキュメント**:
> - [README.md](./README.md) / [01_data_model.md](./01_data_model.md) / [02_api_design.md](./02_api_design.md) / [03_business_logic.md](./03_business_logic.md)
> - [docs/security/README.md](../../security/README.md) — 認可・GDPR 横断方針

---

## 1. セキュリティ考慮事項

### 1.1 認可（per-user 限定）
- 全エンドポイントで `currentUserId` 必須。triage/ラベルは inbox ドメインの個人データであり、ロール判定は不要（本人のみ）。
- ラベル操作は `findByIdAndUserId(labelId, currentUserId)`。不存在/他人は一律 `INBOX_LABEL_NOT_FOUND`（存在秘匿）。
- triage の対象 `(source_type, source_id)` は **本人宛て通知か**を集約アダプタの可視性判定で検証する（§1.2）。

### 1.2 IDOR 防止
- `inbox_item_states` / `inbox_label_links` の全クエリに `user_id = currentUserId` を必須付与（`AbstractUserOwnedRepository` 系）。
- **重要**: `source_id` は任意の数値を送れてしまうため、triage 対象が「本人に可視な通知」であることを **書き込み前に検証**する。検証は各アダプタの `isVisibleTo(userId, sourceId)`（一覧取得と同じ可視性ロジック）を再利用。可視でない対象への snooze/archive/label は `INBOX_SOURCE_NOT_FOUND` を返し、オーバーレイ行を作らない。
- ラベル付与時も同様に対象通知の可視性を検証（他人の通知に自分のラベルを紐付けてオーバーレイ表を肥大化させる攻撃の防止）。

### 1.3 入力検証
- `sourceType` は enum バリデーション（未知は `INBOX_INVALID_SOURCE_TYPE`）。
- `snoozedUntil` は `@Future`（過去/欠落は `INBOX_INVALID_SNOOZE_TIME`）。
- ラベル `name` は長さ 50・前後トリム・現役同名重複禁止。`color` は `#RRGGBB` 形式、`icon` は許可プレフィックス（`pi-`）検証。
- `excerpt` はソース本文をサニタイズ（HTML エスケープ・既存通知の body 表示と同方針）。

### 1.4 GDPR / 退会時の扱い
3 表は PII を含まない個人状態/設定（再設定可能）。CLAUDE.md §13.12 の **弱匿名化区分（即時消去）** に該当 → `UserAnonymizedEvent` 受信時に `inbox_item_states` / `notification_labels` / `inbox_label_links` を **即時物理削除**（退会撤回時は再設定で復旧可能）。`AccountPurgeService` 連携は不要（即時で完結）。

**データエクスポート（GDPR 第 15 条アクセス権）**: per-user オーバーレイ3表（`inbox_item_states` / `notification_labels` / `inbox_label_links`）を **`category=inbox`** として GDPR データエクスポート対象に追加した（**案A 3表フルダンプ**）。3 Entity それぞれに `@PersonalData(category = "inbox")` を付与し（手本 `action_memos` と同形・`PersonalDataCoverageValidator` の網羅性チェックは category 単位）、`PersonalDataCollector.collectInbox(userId)` が `AbstractUserOwnedRepository.findByUserId` で3表を N+1 なくまとめ取りして **1 ファイル `inbox.json`**（`{ "inbox_item_states": [...], "notification_labels": [...], "inbox_label_links": [...] }`）に束ねて出力する。source（通知本体）の人間可読リッチ化は行わず、`(source_type, source_id)` の論理参照を含む生データをそのままダンプする。`notification_labels` は `@SQLRestriction("deleted_at IS NULL")` により論理削除済みラベルは除外（ユーザーが削除と認識したデータは含めない）。DDL 変更なし（既存3表を流用）。

### 1.5 レートリミット
[02](./02_api_design.md) §4 のとおり（一覧 120/min、triage 240/min、ラベル作成 30/hour 等）。`Bucket4j`。

---

## 2. アーキテクチャ原則準拠

[01](./01_data_model.md) §4 の対照表のとおり、原則 1（クロスドメイン FK 禁止）・原則 6（UUIDv7）・原則 7（user_id 単位のため `AbstractTenantAwareRepository` 不適用）を遵守。発生源テーブルを変更しないため既存ドメイン境界を侵さない。

---

## 3. Flyway マイグレーション

[01](./01_data_model.md) §6 のとおり、3 表を 1 マイグレーションで作成（`V**.***__create_inbox_overlay_tables.sql`）。**番号はマージ直前に `origin/main` 最新（2026-05-30 時点 `V9.180` 前後）+1 を再確認**（`feedback_migration_version_collision`）。シードなし。

---

## 4. UX（ADHD 配慮・入力摩擦ゼロ）

| 要件 | 設計 |
|------|------|
| 必須項目最小 | triage は対象指定のみ。スヌーズは**プリセット選択**（3 時間後/今晩/明日朝/来週）で日時入力なし |
| 自動整理 | 緊急度/種類は自動グルーピング。ラベルは任意（未分類で破綻しない）|
| 1 タップ | 各行にスヌーズ/アーカイブ/ラベルのアイコン。モバイルはスワイプでアーカイブも検討 |
| 楽観更新 | `useInboxStore` で即 UI 反映 → API 確定（`useNotificationStore` 手本）|
| 確認との区別 | confirmable は「確認はこちら」を明示。アーカイブ＝視界から消すだけと区別（[03](./03_business_logic.md) §7）|
| 空状態 | 受信箱が空＝「すべて対応済み」の肯定的メッセージ（達成感）|
| 通知が消えた場合 | ソース削除済みの通知はマージで脱落し表示されない（孤児を見せない）|
| 既読化 | **インボックス独自の一括既読は MVP 対象外**（5 ソースで既読モデルが異なるため）。既読は項目を開く（`actionUrl` 遷移）と各ソースの既読 API が発火する委譲方式（[README](./README.md) §2.3）。横断一括既読は Phase 2 で再検討 |
| タイムゾーン | TODO 期限の境界（期限切れ/当日/3 日内）とスヌーズプリセット（今晩/明日の朝/来週）の日時計算は**ユーザーのアカウント TZ**（`useDatetime` / タイムゾーン基盤）で行い、サーバ比較も同 TZ に正規化（[03](./03_business_logic.md) §3）|

### 4.1 フロント構成（実装はロードマップ）
`pages/inbox.vue` / `composables/useInboxApi.ts` / `stores/useInboxStore.ts` / `components/widgets/WidgetInbox.vue`（`useDashboardWidgets.ts` の `ALL_WIDGETS` に `{ key:'inbox', label:'受信箱', icon:'pi pi-inbox', scope:['personal'] }` を追加）/ `types/inbox.ts`。

### 4.2 i18n（6 言語必須）
`frontend/app/locales/{ja,en,zh,ko,es,de}/inbox.json` を新規追加。主なキー群（状態/操作/緊急度/種類/空状態）：

| キー | ja（例）|
|------|---------|
| `inbox.title` | 受信箱 |
| `inbox.tab.inbox` / `.snoozed` / `.archived` | 受信箱 / スヌーズ中 / 保管庫 |
| `inbox.priority.urgent/high/normal/low` | 緊急/重要/通常/低 |
| `inbox.source.notification/announcement/mention/confirmable/todoDue` | 通知/お知らせ/メンション/要確認/TODO期限 |
| `inbox.action.snooze/archive/unarchive/label` | スヌーズ/保管/受信箱へ戻す/ラベル |
| `inbox.snoozePreset.in3h/tonight/tomorrowMorning/nextWeek` | 3時間後/今晩/明日の朝/来週 |
| `inbox.empty` | すべて対応済みです 🎉 |
| `inbox.label.create/limitReached/duplicate` | ラベルを作成/上限に達しました/同名があります |

> i18n ルール（CLAUDE.md）に従い直書き禁止。MVP では英訳まで、他 4 言語は日本語値で投入し後追い翻訳可。

---

## 5. 運用・性能

- **N+1 回避**: オーバーレイ/ラベルは集約後の `(sourceType,sourceId)` 集合に `IN` 1 クエリでまとめ取り（[03](./03_business_logic.md) §4）。
- **境界付きウィンドウページング（Phase 3 ③ 実装済み・方針更新）**: 各ソースを `Pageable` で `window = (page+1)*size + 安全マージン` 件まで取得し（無制限 fetch を根絶）、**完全全順序**（priority → occurredAt → sourceType名 → sourceId）でマージ・スライスする。これにより**決定的（重複なし・load-more 連続）**なページングを行う。取りこぼし保証は**ソースの fetch 順がグローバル順と整合するかで分かれる**: **NOTIFICATION（priority 第一クエリ）・MENTION（一律 HIGH）・TODO_DUE（due_date 昇順 ↔ priority 降順）は取りこぼしなし**。**ANNOUNCEMENT・CONFIRMABLE は取得順が priority と独立（pinned/created_at・親 created_at＋時刻依存 24h 昇格）のため、稀な偏在で高 priority・低時刻の項目が後ページに送られうる**（pinned/保留件数は小さく実害限定。ANNOUNCEMENT は共有 `findByScope` を壊さないため据え置き。[03](./03_business_logic.md) §4.1 に保証レベルと限界を正直に明記）。複合キーセットカーソルは 5 ソースの順序キー混在で境界バグ温床になり FE レスポンス契約も壊すため採らない。旧方針（ソース毎ハードリミット＋深いページ取りこぼし許容）から置換した。
- **並列化余地**: アダプタ呼び出しは将来 `CompletableFuture`/仮想スレッドで並列化可（`DashboardService` の構想に準拠）。MVP は直列で十分。
- **cleanup バッチとの非干渉**: `NotificationCleanupBatchService`（`notifications` を既読 90 日で物理削除）は B 案では**無関係**（オーバーレイは別表）。ただしソースが消えた際の孤児オーバーレイ行は残存しうる → 任意の掃除バッチを Phase 3 候補とする（残っても一覧時に脱落するため実害なし）。
- **スヌーズ復帰 push はベストエフォート 1 回**: `InboxSnoozeRevivalBatchService`（5 分毎）は復帰期限到来行へ push を **1 度だけ**試行し、**成否に関わらず** `snooze_notified_at` を刻んで再送しない（上限 1 回・冪等）。push 失敗は `log.error` に残すのみ（症状を隠さない）。**DLQ・リトライ上限列は設けない**: 送信先が無効（HTTP 410/404）な Web Push サブスクの失効掃除は `WebPushService.deleteByEndpoint` に委譲し、429/5xx は同サービスが `MAX_RETRY_COUNT` まで内部リトライしてから諦める。これにより旧仕様の「失敗行を 5 分毎に無限再試行する」挙動を根絶した（[03](./03_business_logic.md) §5.1）。

---

## 6. 保守性

- **拡張性**: 新ソース追加は `InboxSourceAdapter` 実装を 1 つ足すだけ（[03](./03_business_logic.md) §2）。集約・状態マージ・API は不変。
- **責務分離**: `DashboardService`（ダッシュボード集約）と `InboxAggregationService`（インボックス集約）は別サービス。共通の可視性判定ロジックは将来共通化余地（§8）。
- **規約遵守**: UuidV7Entity・`AbstractUserOwnedRepository`・FK なし・i18n 6 言語・Flyway 番号マージ直前確認。

---

## 7. テスト計画

| 層 | 主なケース |
|----|-----------|
| 単体（normalizer）| priority 正規化（5 ソース×各値・TODO 期限の境界：超過/当日/3 日内/対象外・confirmable 締切 24h 昇格）|
| 単体（triage）| snooze upsert / 過去時刻拒否 / unsnooze・unarchive で両 NULL→物理削除 / ラベル上限（20・10）/ 同名重複 |
| 単体（マージ）| ARCHIVED>SNOOZED>READ>UNREAD の優先順位 / オーバーレイ優先・notifications フォールバック |
| 統合（集約）| 5 アダプタ各々の取得・本人外通知の除外（IDOR）/ N+1 が発生しない（クエリ数アサート）/ state・priority・sourceType・label フィルタ |
| 統合（認可）| 他人の `source_id` への snooze/archive/label が `INBOX_SOURCE_NOT_FOUND` / 他人ラベル操作が `INBOX_LABEL_NOT_FOUND` |
| 統合（GDPR）| `UserAnonymizedEvent` で 3 表が即時削除される |
| E2E | 受信箱表示→スヌーズ→（時刻操作で）復帰→アーカイブ→保管庫表示→ラベル付与/絞り込み |

---

## 8. 未解決事項

設計中に検討した論点。**すべて本フェーズで解決済み**（取り消し線）。本フェーズに残す未解決事項は**ゼロ**。

- ~~A 案/B 案どちらを採るか~~ → **解決**: B 案採用（[README](./README.md) §4 比較表・判断記録）。
- ~~既存スヌーズのフロント/バックエンド DTO 不整合~~ → **解決**: `snoozedUntil` 統一・既存ベル UI も同 DTO に是正（[03](./03_business_logic.md) §6）。MVP スコープに明記。
- ~~cleanup バッチがアーカイブを誤削除するリスク~~ → **解決**: B 案はオーバーレイ別表で `notifications` 行を触らないため**発生しない**（§5）。A 案固有リスクとして比較表に記録。
- ~~confirmable の確認状態とインボックス状態の二重持ち~~ → **解決**: archive/snooze は確認状態と独立、確認は F04.9 API へ誘導と UX で明示（[03](./03_business_logic.md) §7）。
- ~~F22.1「要対応」ウィジェットとの重複~~ → **解決**: 棲み分け定義（個人受け皿 vs スコープ別抽出。[README](./README.md) §7）。データ重複なし。
- ~~読み取り集約の N+1・ページング~~ → **解決**: `IN` まとめ取り（N+1 回避）＋**境界付きウィンドウページング**（Phase 3 ③・全ソース `Pageable`・完全全順序で**決定的**。NOTIFICATION/MENTION/TODO_DUE は取りこぼしなし、ANNOUNCEMENT/CONFIRMABLE は priority 独立取得ゆえ稀に後ページ送り＝限界明記。§5・[03](./03_business_logic.md) §4.1）。旧「ソース毎ハードリミット＋深いページ非保証」から置換。
- ~~名寄せ（重複通知）~~ → **解決（方針確定）**: MVP は名寄せしない（誤名寄せの混乱回避）。Phase 3 で正規化辞書導入と明記（[03](./03_business_logic.md) §8）。
- ~~announcement の取得経路（getPersonalFeed 未実装）~~ → **解決**: `AnnouncementFeedQueryRepository.findByScope` を直接利用（[03](./03_business_logic.md) §2）。

**将来フェーズの拡張候補（未解決ではなく計画済み）**: ~~名寄せ辞書~~（Phase 3 ① 実装済み）/ ~~ソース別真ページング~~（Phase 3 ③ 境界付きウィンドウページングとして実装済み）/ ~~スヌーズ復帰 push~~（Phase 3 ② 実装済み）/ `notifications.snoozed_until` のオーバーレイ移送 deprecate / F22.1 要対応との可視性判定共通化 / ~~GDPR エクスポート~~（Phase 4 実装済み・§1.4 案A 3表フルダンプ `category=inbox`）。すべて [README](./README.md) §6 ロードマップに位置づけ済み。

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-05-30 | 初版作成。B 案採用・全 5 ソース完全設計。2 周精査実施（§10/§11）・未解決事項ゼロで設計確定 |

---

## 10. 精査ログ（1 回目）

観点：不備（完全性）/ セキュリティ / ユーザビリティ / 見落とし / 保守性。指摘はその場で各ファイルへ反映済み。

| # | 観点 | 指摘 | 対応 |
|---|------|------|------|
| 1-1 | 見落とし | TODO 期限の境界判定（期限切れ/当日/3日内）とスヌーズプリセット（今晩/明日朝）の日時計算で**タイムゾーン**への言及が欠落。本プロジェクトはアカウント別 TZ 基盤を持つため誤判定リスク | [03](./03_business_logic.md) §3 に「ユーザー TZ で暦日判定・絶対時刻で送信・サーバ比較は UTC」を明記。§4 UX 表にもタイムゾーン行を追加 |
| 1-2 | 完全性 / UX | README §1.3 図と i18n に「すべて既読」があるが、スコープ §2.3「既読書き込みは新設せず各ソース既読 API へ委譲」と矛盾（5 ソースで既読モデルが異なり横断一括既読は非自明）| 図とアクション i18n から「すべて既読」を削除。§4 UX 表に「既読化＝開封で各ソース既読 API 発火・横断一括は Phase 2」を明記 |
| 1-3 | 完全性 | `GET /api/v1/inbox/labels` のレスポンス形が未記載 | [02](./02_api_design.md) §3.4 に GET レスポンス例（現役のみ・sort_order 昇順）を追加 |
| 1-4 | セキュリティ | 論理削除済みラベルへの付与拒否が未記載（削除済みラベルを付与され表示破綻の恐れ）| [02](./02_api_design.md) §3.4 で `findByIdAndUserIdAndDeletedAtIsNull` ＋ `INBOX_LABEL_NOT_FOUND` を明記 |
| 1-5 | 保守性 | 新ソース追加の拡張点が散文のみ | [03](./03_business_logic.md) §2 に `InboxSourceAdapter` インターフェースを明示し「1 実装追加で新ソース対応」を確認（既に反映済みを再確認）|

---

## 11. 精査ログ（2 回目・回帰確認含む）

1 回目修正が新たな矛盾を生んでいないかの回帰確認を含む。

| # | 観点 | 指摘 | 対応 |
|---|------|------|------|
| 2-1 | 回帰（1-2）| 「すべて既読」削除後に他箇所へ残存参照がないか（README 図・i18n・02 監査・scope）| 全箇所確認。残存なし。既読は §2.3 委譲方式で一貫 |
| 2-2 | 回帰（1-1）| TZ 追記がサーバ比較ロジックと矛盾しないか | `snoozed_until <= now` は UTC 絶対時刻同士のため TZ 非依存。暦日判定のみ TZ 適用で整合。矛盾なし |
| 2-3 | 完全性 | `InboxItem.state` で TODO_DUE は既読概念がなく常に UNREAD/SNOOZED/ARCHIVED のみを取る点が暗黙 | [03](./03_business_logic.md) §2 注記「TODO は完了=対象外」で表現済みと確認。設計意図どおり（期限 TODO は対応するまで未読相当）。変更不要 |
| 2-4 | セキュリティ | ラベル付与の対象通知可視性チェックと、削除済みラベル拒否が二重に効くか | [02](./02_api_design.md) §3.4 ＋ [04](#1-2-idor-防止) §1.2 で「ラベル所有＋対象可視」の二重検証を確認。整合 |
| 2-5 | 保守性 / 性能 | `summary` の ARCHIVED 件数集計が将来肥大化しないか | `inbox_item_states` は per-user・`idx_iis_user_archived` 索引済みで件数は個人規模に限定。許容範囲と確認。変更不要 |

**最終判定**: 2 周の精査で検出した指摘はすべて反映済み。**未解決事項ゼロ**。設計確定（完了）とする。
