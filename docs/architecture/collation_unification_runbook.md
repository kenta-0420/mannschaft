# 照合順序統一（V175 / issue #2589）運用 runbook

`V175.20260804134628__unify_table_collation.sql` は**全表を再構築する重い migration** であり、
失敗した場合の巻き戻しが実質不可能である。適用前に本書を通読すること。

関連: [`domain_db_design_principles.md`](domain_db_design_principles.md) §8（新規テーブルは照合順序を明示宣言する）

---

## 1. この migration が何をするか

| STEP | 内容 | 失敗しうるか |
|---|---|---|
| 1 | 全 UNIQUE 制約を実データで事前検査。衝突があれば**何も変更せず中断** | する（安全な失敗） |
| 2 | 外部キー数・行数・列の型/長さのスナップショット取得 | ほぼしない |
| 3 | `ALTER DATABASE` ＋ 対象表を 1 枚ずつ `CONVERT TO CHARACTER SET` | **する（危険な失敗）** |
| 4 | 照合順序・FK 数・行数・列型が期待どおりかを検証 | する |

STEP 1 で落ちた場合は**まだ 1 表も変換していない**ので安全。
STEP 3 の途中で落ちた場合が問題で、その対処が本書の主題である。

---

## 2. 適用前に必ず確認すること

1. **バックアップ（RDS スナップショット）を取得し、復元可能であることを確認する。**
   巻き戻し手段は事実上これ 1 つである（後述 §5）。
2. **メンテナンス時間を確保する。** `CONVERT TO CHARACTER SET` は COPY アルゴリズムでの
   全表再構築であり、対象表への**書き込みをブロック**する。
3. **所要時間の見積もり**を PR 本文の実測値と自環境の行数から立てる。
   FULLTEXT / ngram インデックスを持つ表（`V11.130`・`V8.003`・`V139.001` で作られる表）は
   索引の再構築を伴うため特に重い。
4. STEP 1 の事前検査自体が全 UNIQUE インデックスに対する `GROUP BY` 走査であり、
   読み取り負荷がかかる。ピーク時間帯を避ける。

---

## 3. 事前検査（STEP 1）で中断した場合

エラーメッセージの例:

```
issue #2589: 照合順序の統一を中断しました（変換は一切行っていません）。
utf8mb4_0900_ai_ci へ変換すると一意制約に違反する既存データが N インデックスで見つかりました。
対処: docs/architecture/collation_unification_runbook.md / 詳細: <表>.<索引>(<列>) 重複<件数>組 例=<値>
```

**この状態はスキーマ的に無傷である。** Flyway の履歴には失敗が記録されるので:

   エラーメッセージの `[DUPLICATE]` と `[UNCHECKABLE]` は意味が違う。

   - `[DUPLICATE]` … 変換すると一意制約に違反する組が**実在する**。下記 2 の手順へ。
   - `[UNCHECKABLE]` … 検査用 SQL 自体が実行できず、衝突の有無を**判定できなかった**。
     データの問題ではないので消してはならない。migration の検査ロジック側の不備なので、
     `collation_precheck_findings.err_code` / `err_message` / `check_sql` を見て原因を特定し、
     migration を修正すること。

   ⚠️ **`dup_groups` が `NULL` の行は「重複ゼロ」ではなく「計算に到達していない」**という意味である
   （`UNCHECKABLE` の行は必ず `NULL`）。`0` と混同して「重複が無いから安全」と判断してはならない。
   検査できていない索引が 1 つでもある限り、変換は安全とは言えない。

   ```sql
   SELECT reason, table_name, index_name, dup_groups, err_code, err_message, check_sql
     FROM collation_precheck_findings ORDER BY table_name, index_name;
   ```

1. メッセージ中の表・索引・列・サンプル値から、衝突している行を特定する。

   ```sql
   SELECT <列>, COUNT(*)
     FROM <表>
    WHERE <列> IS NOT NULL
    GROUP BY <列> COLLATE utf8mb4_0900_ai_ci
   HAVING COUNT(*) > 1;
   ```

2. 衝突している 2 値の**どこが違うのか**を突き止める。見た目では区別がつかないことが多いので、
   必ずコードポイントで確認すること。

   ```sql
   SELECT id, <列>, HEX(CONVERT(<列> USING utf32)) FROM <表> WHERE <列> IN (...);
   ```

   実測で確認されている「変換によって新たに等価になる」軸は次のとおり:

   - **異なる字体系の数字** — ASCII `0`(U+0030) と NKo `߀`(U+07C0)、タミル `௦`(U+0BE6) など。
     `0` だけで 44 文字、`1` は 50 文字が同一視される
   - **縦書き用の異体** — `,`(U+002C) と縦書きカンマ(U+FE10)
   - **無視可能文字**（制御文字等）— 729 文字がまとめて 1 グループ

   ⚠️ **以下は「どちらの照合順序でも既に等価」なので、ここを探しても衝突の原因は見つからない**（実測）:
   全角/半角の素の形（`,` と `，`、`A` と `Ａ`、`。` と `｡`）、アクセント（`e` と `é`）、
   濁点・半濁点（`は` と `ば`）、かな種別（`は` と `ハ`）。
   `utf8mb4_unicode_ci` も accent insensitive であるため、これらの軸では差が出ない。
   **「全角半角や濁点だろう」という直感で探すと時間を溶かす。** 上記 3 軸を疑うこと。

   原因を特定したら、業務的にどちらが正しいかを確認し、**データを正規化してから**再適用する。
   機械的に片方を消してはならない（利用者のデータである）。

3. データ修正後、`flyway repair` で失敗記録を消してから再適用する。

   ```bash
   ./gradlew flywayRepair   # 失敗した migration の履歴行を掃除
   # その後アプリを起動すれば V175 が再実行される
   ```

---

## 4. 変換途中（STEP 3）で落ちた場合 — 最も注意を要する状態

MySQL の DDL は**暗黙コミット**であり、`ALTER TABLE` ごとに確定する。
したがって途中で落ちると **「一部の表だけ新照合順序」という混在状態**が残る。
これは元の状態より悪い（統一されていた分類がさらに割れるため）。

### 4-1. どこまで進んだかの特定

```sql
-- 未変換で残っている表
SELECT TABLE_NAME, TABLE_COLLATION
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_TYPE = 'BASE TABLE'
   AND TABLE_NAME <> 'flyway_schema_history'
   AND TABLE_COLLATION <> 'utf8mb4_0900_ai_ci'
 ORDER BY TABLE_NAME;
```

対象表はアルファベット順に処理されるため、返ってきた最初の表の直前まで完了している。

⚠️ **表だけでなくデータベース既定も変わっている。** STEP 3 は表の変換より先に
`ALTER DATABASE ... COLLATE utf8mb4_0900_ai_ci` を実行するため、途中で落ちた場合
「DB 既定は新しい照合順序／一部の表は旧照合順序」という混在になる。

```sql
SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
  FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE();
```

この状態で**新しく作られた表は新しい既定を継承する**ため、放置すると混在がさらに広がる。
再開までの間に新規テーブルを作る migration を流さないこと。
DB 既定の変更自体は既存データに影響しないので、戻す必要はない（再開すれば整合する）。

### 4-2. 落ちた原因の確認

想定される原因:

| 原因 | 見分け方 | 対処 |
|---|---|---|
| `ERROR 1062 Duplicate entry` | エラーに索引名と値が出る | §3 と同じ手順でデータを正規化 |
| ディスク不足 | `ERROR 1114` / OS のディスク監視 | 空き容量を確保。COPY 方式は表 1 枚分の一時領域を使う |
| ロック待ちタイムアウト | `ERROR 1205` / `1206` | 長時間トランザクションを止めてから再開 |
| 接続断・タイムアウト | クライアント側のログ | サーバ側は完走している場合がある。4-1 で実態を確認 |

**STEP 1 を通ったのに 1062 が出た場合**は、事前検査から変換までの間に新しい行が書き込まれた可能性が高い
（＝アプリを止めずに適用した）。次回はアプリを停止して適用すること。

### 4-3. 再開

原因を除去したうえで:

```bash
./gradlew flywayRepair
# 再適用。V175 は「まだ統一先でない表」だけを対象にするので、
# 完了済みの表を二度変換することはない（冪等）。
```

**`flyway repair` だけで済ませてはならない。** 落ちた原因（データ・容量・ロック）を必ず先に取り除くこと。

---

## 5. 巻き戻しについて — 実質不可能である

**逆方向の migration（`utf8mb4_unicode_ci` へ戻す）は書けるが、実質的な救済にならない。**

1. 逆変換も 551 表の**全表再構築**であり、正方向と同じだけの時間・ロック・ディスクを要する。
   障害中の切迫した状況でこれを流すのは現実的でない。
2. 逆方向は照合順序が**粗くなる**方向にも動くため（実測: 44 グループが新たに等価化）、
   逆変換それ自体が別の一意制約違反を起こしうる。つまり**戻す操作も失敗しうる**。
3. 混在状態から戻す場合、「どの表が元は宣言なしだったか」は変換後の DB からは判別できない。
   元の分類（明示 551 / 宣言なし 142 / 村 33）は migration の履歴にしか無い。

⇒ **巻き戻し手段は RDS スナップショットからの復元のみ**と考えること。
これが「適用前にバックアップを取り、復元可能性を確認する」を §2-1 で必須にしている理由である。

---

## 6. 適用後の確認

```sql
-- 統一されていない表・列が 0 件であること
SELECT COUNT(*) FROM information_schema.TABLES
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'
   AND TABLE_NAME<>'flyway_schema_history' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci';

SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history'
   AND COLLATION_NAME IS NOT NULL AND COLLATION_NAME<>'utf8mb4_0900_ai_ci';
```

いずれも 0 であること（migration の STEP 4 が同じ検査をしているが、独立に確認する）。

そのうえで、本 issue の発端であるフォルダ未読集計 API が実際に応答することを確認する。

---

## 7. 教訓 — 診断の仕組み自体が嘘をつく

事前ゲートを動かすまでに、原因が **5 層**重なっていた。各層は前の層を直して初めて見える。
将来この migration や類似の動的 SQL を触る人が同じ罠を踏むので記録しておく。

| 層 | 見えたもの | 実体 | 対処 |
|---|---|---|---|
| 1 | `errno=1253` | 非文字列を返す式に `COLLATE` を付けていた（**真の情報**） | `CAST((expr) AS CHAR) COLLATE ...` |
| 2 | `errno=1243` | `PREPARE` 失敗後も `CONTINUE` ハンドラで `EXECUTE`/`DEALLOCATE` が走り、後始末のエラーが真の errno を上書き | 失敗後は後続の文を実行しない／初回 errno を保持 |
| 3 | `errno=NULL` | `IF @err_code IS NULL THEN GET DIAGNOSTICS ...` の**条件評価が診断領域をクリア** | `GET DIAGNOSTICS` をハンドラの第一文に |
| 4 | `errno=NULL` | ハンドラ本体の `DECLARE ... DEFAULT` が**ブロック入場時の代入として診断領域をクリア** | ローカル変数をやめセッション変数へ直接採取 |
| 5 | `errno=1064` | **真因**。`information_schema.STATISTICS.EXPRESSION` はクォートをバックスラッシュでエスケープした文字列を返す（`_utf8mb4\'9999-12-31 00:00:00\'`）。そのまま動的 SQL に埋めると構文エラー | `REPLACE` で `\'` を `'` に戻してから埋め込む |

**教えるところ:**

- **見えているエラーが真因とは限らない。** 2〜4 層目はすべて自分が足した診断コードが作った目隠しだった。
- **エラーが無いこと自体が異常のサインでありうる。** 「ハンドラは発火したのに errno が NULL」という
  矛盾に気づけたから 3〜4 層目に到達できた。矛盾は矛盾として明示的に記録すること
  （現在の実装は、この組み合わせを検出したら「診断採取に失敗」と記録する）。
- **診断コードを足したら、その診断コードが正しく動くことを別途確かめる。**
  本件では「わざと失敗する `PREPARE` だけを含む最小プロシージャ」を作り、
  採取機構そのものの正常性を先に確認して 3〜4 層目を切り分けた。
  本番の複雑な対象で切り分けようとすると、原因の候補が多すぎて進まない。

---

## 8. 再発防止（この migration 以降）

| 層 | 実体 |
|---|---|
| 静的番人 | `MigrationCollationDeclarationGuardTest` — 新規 `CREATE TABLE` の宣言漏れを Docker 不要で拒否 |
| 動的番人 | `SchemaCollationConsistencyIT` — 本番と同じ `collation_server` で Flyway を流し実スキーマ全体を検証 |
| 生成列 | `SchemaCollationConsistencyIT#STORED生成列を持つ表が変換に耐える` — STORED 生成列を持つ表に実際に `CONVERT TO` を流して確認 |
| DB 既定 | V175 の `ALTER DATABASE` — 宣言を忘れても正しい照合順序を継承する多重防御 |
