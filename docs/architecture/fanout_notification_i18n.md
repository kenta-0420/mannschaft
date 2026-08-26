# fan-out 通知の i18n 化（Issue #2871）

**状態**: 実装済み（PR: `feature/2871-fanout-notification-i18n`）
**関連**: CMP-001（fan-out 抜本改修）/ Issue #2715 CMP-055（通知 i18n ロットA〜C）

---

## 1. 何が問題だったか

fan-out 耐久ジョブ `notification_fanout_jobs` は、**日本語で描画済みの `title` / `body` 文字列**を列に持っていた。
受信者は enqueue 時点では展開されず、裏ワーカーが後からキーセットで取り出す設計であるため、
「受信者ごとに言語を変える」ことが**構造的に不可能**だった。

通知 i18n の他の 62 箇所（ロットA〜C）は受信者が呼び出し時点で確定しているため
`NotificationHelper#notifyAllPreAuthorizedLocalized` で解決できたが、fan-out だけはこの形に乗らない。

対象は **4 経路**（起票時は 5 と書いたが実測は 4。`UserBirthYearBackfillChunkService` は javadoc の言及のみで enqueue を呼んでいない）:

| 呼び出し元 | 可変部分 |
|---|---|
| `schedule/service/ScheduleKeepNotificationService` | 予定タイトル ×2 |
| `survey/listener/SurveyPublishNotificationListener` | アンケート名 ×1 |
| `shift/event/ShiftPublishedNotificationListener` | **なし**（全文アプリの文言） |
| `village/service/VillageEventFeedRefluxService` | 行事名 ×1 ＋ 4 種の分岐 |

可変部分は**すべて String**（数値も日時も 1 つも無い）。

---

## 2. 翻訳の範囲 — 枠だけを訳し、中身は配る

**アプリが書く「枠」だけを翻訳し、利用者が書いた「中身」（アンケート名・行事名・予定名）はそのまま配る。**

```
「{0}」が公開されました。回答にご協力ください。   ← 枠：翻訳する
   ↑ ここに入る「第3回 保護者アンケート」        ← 中身：翻訳も改変もしない
```

`shift/event/ShiftPublishedNotificationListener` は補間点が 1 つも無く全文がアプリの文言なので、全文を翻訳する。

---

## 3. 採用した設計

### 3.1 enqueue 時に 6 ロケールぶん描画し、子表に保存する

受信者は未確定でも**配信ロケールは 6 種（ja/en/zh/ko/es/de）しかない**。
よって受信者スナップショットを取らずとも「起こりうる文面」は 6 通りで尽きる。

- 新表 `notification_fanout_job_messages(job_id, locale, title, body)` / `UNIQUE(job_id, locale)`
- 親表の `title` / `body` 列は**撤去**（二経路を残さない。本番に未処理データが無いことをマスター確認済み）
- 副次的な利点:
  - ジョブ処理中に翻訳がデプロイされても、**1 イベント内の文面が前半と後半で食い違わない**
  - 切り詰め（title 200 / body 1000）も enqueue 時に確定するため、リトライやデプロイをまたいでも同じ文面が再現される

`enqueue` の引数は「描画済み文字列 2 本」から **`FanoutMessageKind` ＋ 型付き引数（String 最大 2 個）** に変わった。
`title_key` / `body_key` という**キー文字列の列は持たせない**（存在しないキーが列に入りうる状態を作らないため。enum なら参照時点で閉じる）。

### 3.2 受信者を取得する時点で locale も一緒に取る

`FanoutRecipientSource#nextPage` の戻り値を `List<Long>` → `List<FanoutRecipient(userId, locale)>` へ。

- locale 解決のための DB 往復が増えない
- `UserLocaleCache`（既定上限 50,000 件）を 50 万人配信が洗い流す問題も回避
- あわせて 3 / 4 / 6 引数のオーバーロード委譲構造を `FanoutPageRequest` 1 つへ集約

**locale の意味**: 「**その受信者ページを取得した時点の**利用者の locale」。配信途中の言語切り替えは反映されない。
fan-out の不変条件は「欠落なし・at-least-once」であって「言語切り替えの即時反映」ではないため、これを許容する。

### 3.3 書き込みは 500 行を 1 文・1 トランザクションのまま

`NotificationBulkFanoutService` の `ROW_PLACEHOLDERS = "(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"` は
**1 行 14 列で title / body を既に行ごとに持っている**。共有引数になっていたのは Java 側の API だけだった。

したがって **INSERT 文数・トランザクション数は 1 チャンクにつき 1 のまま**（受信者数にもロケール数にも比例しない）。
at-least-once の重複上限も最大 500 件のまま。

### 3.4 村の 4 分岐はキー名で表す

行事追加 / 明日開催 / 寄合日程確定 / 祭り開始は、語順・文意・助詞まで異なる**別テンプレート**。
種別を引数として properties へ渡すと properties 側に分岐ロジックを持ち込むことになるため、
4 種を別の `FanoutMessageKind`（`VILLAGE_EVENT_ADDED` 等）とし、**引数は行事名ただ 1 つ**に保つ。

> **⚠ タイムライン投稿は i18n 化しない（意図的な非対称）**
>
> `VillageEventFeedRefluxService#buildContent` は**村のタイムライン投稿**（システム名義の 1 レコード）を組み立てる。
> 投稿は村人全員が同じ 1 本を読むため、受信者ごとに言語を変えるという概念がそもそも成立しない
> （1 行のレコードに 1 つの本文しか持てない）。
> よって投稿は従来どおり `buildContent` の日本語リテラルを使い続け、**通知だけ**を `messageKindOf(type)` で
> 文面種別に写して fan-out へ渡す。両者を 1 つの関数から作ると
> 「投稿を翻訳しようとして全村人の掲示板がバラバラになる」か「通知の翻訳を諦める」かのどちらかに倒れる。

---

## 4. 却下した案

| 案 | 却下理由 |
|---|---|
| 配信直前にチャンク内で locale を解決して分割 | 1 チャンクの INSERT 文数が 1 → 最大 6 に増える。190 件/秒で SLO 未達の局面で書き込み経路をさらに細分化するのは不適。加えて 50 万人配信が locale キャッシュを洗い流す |
| locale 別にジョブを分ける | **構造的に成立しない。** locale は可変属性であり、配信途中に利用者が言語を切り替えるとジョブの進行順次第で**重複または欠落**が起こる。fan-out が保証してきた「欠落なし」を壊す |

---

## 5. 索引への影響（実測）

受信者 keyset クエリ 4 本すべてで、`users` を JOIN して `locale` を同時取得する形の実行計画を実測した
（MySQL 8.0・20 万行の合成データ）。

| 経路 | 変更前 | 変更後 | 判定 |
|---|---|---|---|
| TEAM | 既に `JOIN users`（PK eq_ref） | 射影 1 列追加のみ | 実行計画は**完全一致** |
| SCHEDULE_KEEP_TEAM | 既に `JOIN users` | 射影 1 列追加のみ | 実行計画は**完全一致** |
| ORGANIZATION（UNION 2 枝） | 両枝とも既に `JOIN users` | 両枝＋外側に射影 1 列追加 | 枝内 `LIMIT` 維持・covering index range scan 維持・users は eq_ref 単行ルックアップ・**新規の filesort / temporary なし** |
| VILLAGE | users を JOIN していなかった | `LEFT JOIN users` を追加 | `idx_vm_fanout_keyset` の covering index range scan は駆動表のまま。users の eq_ref 1 段が加わるだけ |

ORG の枝内 `SELECT DISTINCT` に locale が加わっても、locale は users の**主キー等値結合**で決まる
＝ user_id に関数従属するため、重複排除の結果行数は変わらず、枝内 `LIMIT :chunk` が数える行数も変わらない。

VILLAGE を **`LEFT JOIN`** にしているのは意図的である。このクエリの母集団条件は「村メンバーシップが現役」だけで、
ユーザー状態（`status` / `deleted_at`）は元々見ていない。`INNER JOIN` にすると users 側に行が無いケースで
受信者が静かに減る＝**母集団の定義を変えてしまう**。JOIN は locale を取るためだけのものであり、
絞り込みではないことを `LEFT JOIN` で構造的に表す。

---

## 6. locale 正規化は一箇所（`common/i18n/DeliveryLocales`）

受信者ソースは 4 実装あり、さらに enqueue 側でも「どのロケールぶん描画するか」を決める。
ここが実装ごとにバラつくと、`zh-CN` の利用者が「受信者ソースでは zh に落ちるが enqueue では zh-CN のまま」となり、
**その利用者だけ文面を引けず配信落ちする**。

- 配信 bucket は `ja/en/zh/ko/es/de` の**ちょうど 6 種**
- **base properties（サフィックス無し）は配信 bucket ではない**。`MessageSource` が最後に見るフォールバック資源
- null・空・未対応タグ・地域タグ付き（`zh-CN` / `zh_Hans` / `en-US`）・大文字小文字の揺れをすべて 6 種へ落とす
- `FanoutRecipient` のコンパクトコンストラクタで必ず `normalize` を通すため、**呼び忘れが構造的に作れない**

---

## 7. 握り潰さない・壊さない

- **欠落キー**: `MessageSource` は `useCodeAsDefaultMessage(false)` 構成のため、どのバンドルにも無いキーは
  `NoSuchMessageException` を投げる。レンダラはこれを**捕捉しない**。キー文字列を本文として配ると
  「意味不明な文字列が届いたのにログにもメトリクスにも何も残らない」最悪の握り潰しになる
- **切り詰め**: 素の `substring` は UTF-16 コードユニット単位で切るため絵文字のサロゲートペアを分断し、
  壊れた文字を作る。**コードポイント境界**で切る（MySQL の `VARCHAR(n)` は文字数で数えるため n コードポイント以内なら必ず収まる）
- **カーソル前進**は従来どおり **INSERT 確定の後**。再開カーソルは **user_id ただ 1 本**（locale を混ぜない）

### メトリクス

| 名前 | 意味 |
|---|---|
| `mannschaft.notification.fanout.message.rendered` | ロケール別の描画件数 |
| `mannschaft.notification.fanout.message.render_failed` | 描画失敗（欠落キー等） |
| `mannschaft.notification.fanout.message.truncated` | 切り詰め発生 |

---

## 8. 本 issue の対象外

`shiftbudget` の失敗イベントのリトライ経路（`ShiftBudgetRetryExecutor#retryNotificationSend` が
`Locale.JAPANESE` で描画済みの文字列を再送する問題）は fan-out とは別の作りなので**別途対応する**。
payload には再組み立てに必要な `threshold_percent` が既に保存されており、キー 2 本を足せば
`notifyAllLocalized` へ切り替えられる見込み。
