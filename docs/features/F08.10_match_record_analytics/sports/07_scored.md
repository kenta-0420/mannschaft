# F08.10 / sports / 07: 採点競技（第 4 状態モデル類型 SCORED）— フィギュアスケート＋体操【設計確定】

> **ステータス**: 🟢 設計完了（**マスター御裁可済・実装は別波**／未解決ブロッカー ゼロ）
> **最終更新**: 2026-06-15（第 4 状態モデル類型 `SCORED` を**マスター御裁可の結論で確定版に更新**。論点(1)対象競技＝**フィギュアスケート＋体操の 2 競技**、論点(2)採点粒度＝**MVP は合計点のみ（確定 DDL）・審判別内訳子表は後段 Phase の設計済 DDL**、論点(3)対戦モデル＝**MVP は 2 者対戦（確定）・多人数順位制は後段 Phase の設計済 DDL** で決定）
> **位置づけ**: **F08.10 コアを継承する「採点競技」カタログ＝第 4 状態モデル類型 `SCORED`**。既存 3 類型（CONTINUOUS_TIME / SET_BASED / TURN_BASED・コア §D.6）に乗らない「得点＝審判スコアの合算」競技を扱う。MVP の対象競技は **FIGURE_SKATING（フィギュアスケート）＋ GYMNASTICS（体操）の 2 競技**。
> **関連機能番号**: F08.10（試合記録・分析）／ F08.7 ／ F19.1
> **関連ドキュメント（コア）**:
> - [../README.md](../README.md) — §1.0a 状態モデル類型・§7.1「採点競技 SCORED（フィギュア＋体操）」解決済み
> - [../01_domain_and_ddl.md](../01_domain_and_ddl.md) — §B.1/§B.1.2（勝敗格納規約＝全競技 home/away_score 統一）・§B.5（match_sets）・§B.6（団体戦）・§D.6（StateModel 類型）・§D.7（ターン制勝ち方）・§D.8（SCORED コア要約）
> - [../03_permissions_and_recording_modes.md](../03_permissions_and_recording_modes.md) — 記録権限・IDOR・F00 可視性・入力検証
> - [../04_frontend_and_ux.md](../04_frontend_and_ux.md) — §G.16 共通シェル＋競技別動的 import・§G.16a ターン制最小 UI（採点 UI の前例）
> - [05_shogi.md](./05_shogi.md) — ターン制（球技でない競技）の前例。**本書は「スコア無し」のターン制とも「連続スコア」の球技とも異なる第 3 の極**（審判合算スコア）

---

## §0 この文書の決定事項（マスター御裁可）— 選択肢から確定へ

本書はかつて 3 つの論点をマスターの選択肢として提示する設計提案であった。**マスター御裁可により以下のとおり確定したため、本書は確定版設計書として書き換える**（選択肢の併記ではなく決定として記述する）。

| 論点 | 御裁可による決定 | §参照 |
|------|------------------|-------|
| **(1) 対象競技** | **フィギュアスケート＋体操の 2 競技を MVP で対応**。`Sport` enum に `FIGURE_SKATING`＋`GYMNASTICS` を追加し、両者の `stateModel()` を `SCORED` とする。両競技の採点構造の違い（フィギュア＝TES+PCS／体操＝D スコア+E スコア）を設計に明記する | §2 |
| **(2) 採点粒度** | **MVP＝合計点のみ**（`home_score`/`away_score` に整数スケール×1000 で合計点を格納・§B.1.2 の単一正準に準拠・勝敗導出は `resolveResult()` 再利用）。**審判別内訳（`match_scored_components` 子表）は DDL 設計だけ用意し、実装は後段 Phase**（MVP では実装しない） | §4・§4B |
| **(3) 対戦モデル** | **MVP＝2 者対戦**（`home_score`/`away_score` を流用）。**多人数順位制（`match_score_entries` 新設）は DDL 設計だけ用意し、後段 Phase**。「2 者対戦だけでは多人数順位の大会を表現できない」ことを**意図的な MVP 割り切り**として明記（症状を隠す回避ではない） | §5・§5B |

> **本書のスコープは設計確定であって実装ではない**（実装は別波）。本書は設計書のみを更新する。

---

## §1 SCORED 第 4 類型の位置づけ — なぜ既存 3 類型に乗らないか

採点競技（フィギュアスケート・体操）は試合進行のしかたが既存 3 類型のいずれとも根本的に異なる。

| 観点 | CONTINUOUS_TIME（球技） | SET_BASED（バレー） | TURN_BASED（将棋/囲碁） | **SCORED（採点競技）** |
|------|--------------------------|----------------------|--------------------------|--------------------------|
| 進行 | タイマー＋ピリオド | セット進行 | 手番の応酬（総手数） | **演技/試技の提出 → 審判採点** |
| スコアの源泉 | 試合中のイベント（GOAL 等）の集計 | セット内ラリー得点 | スコア無し（勝敗＋勝ち方） | **審判団の採点の合算**（イベント集計でない） |
| 勝敗の決まり方 | 得点の大小 | 獲得セット数 | 勝ち方＋勝者 side | **合計点の大小** |
| 時間概念 | あり（出場時間算出） | 希薄 | なし | **なし**（演技時間はあるが記録不要） |
| 本来の対戦単位 | 2 チーム | 2 チーム | 2 者（個人/団体ボード） | **多人数が同一種目に出場し順位を競う**（MVP は 2 者対戦に割り切り・§5） |

- **SCORED の本質**: スコアは「試合中に起きたイベント（得点）の合算」ではなく、**演技/試技に対して審判団が与える点数の合算**である。CONTINUOUS_TIME がイベント駆動でスコアを積み上げるのに対し、SCORED は**確定した審判スコアを入力**する（タイムライン入力ではない）。この点で SCORED は TURN_BASED（結果のみ入力）の最小 UI に近いが、**「スコア無し」の TURN_BASED と異なり連続量のスコアを持つ**。
- **コアへの最小追加（確定）**: `StateModel` enum に `SCORED` を 1 値追加し、`assertCompletable` の `switch(stateModel)` に `case SCORED` を追加する（現状は `default -> MATCH_024`＝「入力内容に不備があります」で弾かれるため、**分岐の追加が必須**）。出場時間算出は TURN_BASED 同様**起動しない**（出場交代の概念が無い・コア §D.6）。

```java
// コア StateModel.java への追加（§D.6 への追記＝§8）
public enum StateModel {
    CONTINUOUS_TIME, // 球技（タイマー）
    SET_BASED,       // バレー（セット）
    TURN_BASED,      // 将棋/囲碁（勝敗＋勝ち方・スコア無し）
    SCORED           // 採点競技（フィギュア/体操・審判合算スコア）★追加
}
```

```java
// コア Sport.java への追加（両競技とも StateModel.SCORED を宣言）
FIGURE_SKATING(StateModel.SCORED), // フィギュアスケート（TES + PCS − 減点）
GYMNASTICS(StateModel.SCORED)      // 体操（D スコア + E スコア・種目別合算）
```

---

## §2 対象競技＝フィギュアスケート＋体操（確定）— 採点構造の違い

MVP で対応する採点競技は **フィギュアスケートと体操の 2 競技**（マスター御裁可）。両者は「審判団の採点を合算して合計点を出し、その大小で勝敗/順位が決まる」共通構造を持つが、**採点の内訳構造が異なる**。本節で両競技の差異を明記する。

| 競技 | 採点構造（点の内訳） | 合計点の出し方 | 種目（apparatus）の有無 |
|------|----------------------|----------------|--------------------------|
| **フィギュアスケート** | **TES（技術点＝要素ごとの基礎点＋GOE）＋ PCS（演技構成点＝5 コンポーネント）− 減点（転倒等の Deductions）** | TES + PCS − Deductions = Total Segment Score。SP（ショート）と FS（フリー）の 2 セグメント合算で最終 | セグメント（SP/FS）はあるが「種目」ではない。1 選手 1 競技 |
| **体操（器械体操）** | **D スコア（難度点・上限なし）＋ E スコア（実施点・10.000 から減点）** | D + E = 種目スコア。複数種目（床/あん馬/つり輪/跳馬/平行棒/鉄棒…）の合算で個人総合 | **種目別（apparatus）がある**（男子 6・女子 4）。種目別スコア → 個人総合 |

### §2.1 各競技の最小記録粒度（MVP＝「合計点のみ」）

MVP は「合計点のみ」を記録する（粒度の確定は §4）。両競技とも下表の 1 値に還元する。

| 競技 | MVP で記録する合計点（整数スケール×1000 で格納） |
|------|----------------------------------------------------|
| **フィギュアスケート** | 選手ごとの Total Segment Score（SP/FS 別 or 最終合計。例 198.45 → `198450`） |
| **体操** | 選手ごとの個人総合スコア（種目別スコアの合計。例 85.332 → `85332`） |

> いずれも「**選手（チーム）ごとに 1 つの合計点があり、その大小で勝敗/順位が決まる**」という共通構造に還元できる。この共通構造が SCORED 類型をコアで成立させる鍵である。**競技差分（フィギュアの TES/PCS、体操の D/E・種目別）は内訳の有無＝§4B の後段 Phase に集約され、MVP の合計点格納には影響しない**（合計点だけならフィギュアも体操も同一の格納形）。

### §2.2 体操の種目別集計とフィギュアの 2 セグメント — 後段 Phase の内訳設計に吸収

- **体操の種目別集計**: 体操は「床」「あん馬」等の種目別スコアを合算して個人総合を出す。MVP では**個人総合（合計点）のみ**を `home_score`/`away_score` に格納する。種目別の内訳（どの種目で何点か）を残したい要件は **後段 Phase の `match_scored_components`（§4B）の `apparatus` 列で表現する**（`apparatus=FLOOR/POMMEL_HORSE/RINGS/VAULT/PARALLEL_BARS/HORIZONTAL_BAR…`）。
- **フィギュアの TES+PCS 内訳**: フィギュアは TES（技術点）＋PCS（演技構成点）−Deductions の内訳を持つ。MVP では**合計（Total Segment Score）のみ**を格納する。内訳（TES/PCS/減点・SP/FS セグメント別）は **後段 Phase の `match_scored_components` の `component_type=TES/PCS/DEDUCTION`・`apparatus=SP/FS`** で表現する。
- **MVP 割り切りの根拠**: フィギュアのフル採点（要素別基礎点・GOE）も体操の D/E 内訳も専用採点システム級で、F08.10 の主目的（試合結果の記録と統計）には過剰。「合計点と勝敗/順位が記録・統計できれば足りる」という GoalNote 上位互換の射程に合致する（盤上競技で「棋譜フルエンジンを持たない」と割り切ったのと同じ思想・README §1.0a）。

---

## §3 event_type カタログ（採点競技）

採点競技は「イベントの時系列」を持たない（演技中の出来事を逐次記録しない・記録粒度＝結果スコア）。よってイベントは**結果系の少数**に限る（TURN_BASED と同じ思想）。

```
// コア MatchEventType に追加（フィギュア・体操 共通。球技/盤上は使わない）
SCORE_SUBMITTED,  // 採点結果の提出（選手/チームの合計点確定・home/away_score へ反映）
COMMENT           // メモ（note へ自由記述・任意）
```

```java
// SportEventCatalog の採点競技集合（コア §D.3）— フィギュア・体操の両方を登録
Sport.FIGURE_SKATING, EnumSet.of(SCORE_SUBMITTED, COMMENT, OTHER)
Sport.GYMNASTICS,      EnumSet.of(SCORE_SUBMITTED, COMMENT, OTHER)
```

> **STARTER/SUB_IN・GOAL・カード・total_moves は使わない**: 採点競技は出場交代・得点イベント・カード体系・手数の概念が無いため、これらの event_type を SCORED カタログに含めない（カタログ検証で弾く・コア §D.3）。`card_reason_code`・`win_method`・`total_moves` はいずれも NULL（球技/盤上の専用列を流用しない）。
>
> **後段 Phase（内訳子表）導入時の補足**: 審判別内訳を子テーブル（§4B）に持つ場合でも、`match_events` は「合計点の確定」を表す `SCORE_SUBMITTED` の 1 イベントに留め、内訳は子テーブルが正本とする（二層正本＝§4B）。MVP では内訳子表を持たないため、`SCORE_SUBMITTED` 直後に `home_score`/`away_score` を確定する。

---

## §4 採点粒度＝MVP は合計点のみ（確定）

採点競技の MVP 記録粒度は **「合計点のみ」（マスター御裁可）**。`matches.home_score`/`away_score` に合計点（整数スケール×1000）を格納し、各競技固有の内訳（TES/PCS、D/E、種目別等）は MVP では持たない。**審判別内訳の子表（`match_scored_components`）は後段 Phase の設計済 DDL として §4B に残すが、MVP では実装しない**。

### §4.1 合計点の格納（確定 DDL）— 整数スケール×1000

採点は小数点を持つ（フィギュア 198.45 点・体操 85.332 点）。SMALLINT では表現できないため、**スコアを 1000 倍した整数を `home_score`/`away_score` に格納する**（表示時に小数へ戻す）。

- **小数スケール格納（確定方式）**: スコアを 1000 倍した整数を既存 `home_score`/`away_score` に格納し、表示時に 1000 で割って小数へ戻す。例: 198.45 → `198450`、85.332 → `85332`。
- **`INT UNSIGNED` への型拡張（確定 DDL）**: 現状の `home_score`/`away_score` は `SMALLINT UNSIGNED`（最大 65535）で桁が足りない（×1000 すると 6 桁以上）。**採点競技導入時に 1 回だけ `home_score`/`away_score` を `INT UNSIGNED` へ拡張する ALTER を行う**（全競技共通列の単純拡張＝既存の球技/盤上スコア〔小さな整数〕に無害）。
- **§B.1.2 単一正準の維持**: §B.1.2「勝敗は全競技で `home_score`/`away_score` の大小から導出（`resolveResult()` 再利用）」という確立済み規約を、整数スケール格納なら崩さずに採点競技へ拡張できる。スケール係数（×1000）は SCORED 類型に限り適用し、**表示変換は FE/DTO 層で行う**（コアの集計コード＝`resolveResult()` は整数の大小だけ見るため改造不要）。専用小数列（`home_score_decimal` 等）は新設しない（二重持ち・分岐で §B.1.2 の単一正準が崩れるため・採らない）。

> **採用理由（御裁可と整合）**: 整数スケール×1000＋`INT UNSIGNED` 拡張なら、勝敗導出 `resolveResult()`（大小比較）が整数のまま動き、§B.1.2 の単一正準を崩さない。MVP で内訳を持たないため、`SCORE_SUBMITTED` 入力時に直接 `home_score`/`away_score` を確定できる（中間集計テーブル不要）。

### §4.2 勝敗導出

- 合計点（整数スケール）の大小で W/D/L（§5 の 2 者対戦）。`resolveResult()` をそのまま再利用（§B.1.2）。
- 同点（整数スケール同値）は引分（DRAW）。詳細は §6。

---

## §4B 審判別内訳子表 `match_scored_components`（**後段 Phase の設計済 DDL・MVP では実装しない**）

> **スコープ明記**: 本節は **MVP の実装スコープ外**である。「審判別の採点を残したい・種目別/コンポーネント別の内訳分析が欲しい」という要件が顕在化したときに着手する**後段 Phase の設計済 DDL**として用意する。MVP は §4 の合計点のみで成立し、本節のテーブルは作成しない。

### §4B.1 後段 Phase の DDL（設計のみ）

審判パネル・難度点/実施点・TES/PCS 等を子テーブル `match_scored_components` に持つ。

```sql
-- 【後段 Phase・MVP では作成しない】採点内訳（match ドメイン内 → 親 matches へ CASCADE／原則 2・コア A.4 二段アクセス）
-- organization_id / deleted_at は持たない（テナント分離は親 matches）
CREATE TABLE match_scored_components (
    id BINARY(16) NOT NULL,                       -- UUIDv7（コア原則 6）
    match_id BINARY(16) NOT NULL,                 -- matches(id)（同一ドメイン → FK CASCADE 可）
    competitor_side ENUM('HOME','AWAY') NULL,     -- 2 者対戦時の side（多人数順位制〔§5B〕導入時は NULL・score_entry_id を使う）
    score_entry_id BINARY(16) NULL,               -- 多人数順位制〔§5B〕のエントリ参照（2 者対戦時は NULL）
    apparatus VARCHAR(32) NULL,                    -- 種目/セグメント（体操の FLOOR/POMMEL_HORSE… フィギュアの SP/FS・競技別カタログ enum 文字列）
    judge_label VARCHAR(32) NULL,                 -- 審判識別（J1〜J9 等・審判別素点を持つフィギュア GOE 用・集計のみなら NULL）
    component_type VARCHAR(32) NOT NULL,           -- 項目（フィギュア=TES/PCS/DEDUCTION・体操=D_SCORE/E_SCORE・競技別カタログ enum 文字列）
    points_scaled INT NOT NULL DEFAULT 0,          -- 当該項目の点数（整数スケール＝×1000・小数は表示で復元・§4.1 と整合）
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_scored_components_match (match_id, competitor_side, apparatus),
    INDEX idx_scored_components_entry (score_entry_id),
    CONSTRAINT fk_scored_components_match FOREIGN KEY (match_id)
      REFERENCES matches (id) ON DELETE CASCADE   -- 同一 match ドメイン内（原則 2）
);
```

### §4B.2 後段 Phase 導入時の設計方針（実装時の指針）

- **二層正本（再導出パターン）**: 内訳（`match_scored_components`）を合計して `matches.home_score`/`away_score`（多人数順位制併用時は §5B の `match_score_entries.total_scaled`）へ反映する。これは **`match_sets`（セット内得点 → 獲得セット数を matches 列へ集計）・団体戦（子ボード勝ち星 → 親 matches 列へ集計）と全く同じ二層正本パターン**（コア §B.5・§B.6）。集計の正準は内訳テーブル、`matches` 列は再導出された合計、という責務分離。
- **両競技の内訳マッピング**: フィギュア＝`component_type=TES/PCS/DEDUCTION`・`apparatus=SP/FS`・審判別 GOE は `judge_label`。体操＝`component_type=D_SCORE/E_SCORE`・`apparatus=FLOOR/POMMEL_HORSE/…`。競技別カタログ（`Map<Sport, ScoredComponentCatalog>`）で列挙値を定義し、列挙外は 400（コア §D.3 案 A と同方針・症状を隠さない）。
- **`competitor_side` と `score_entry_id` の使い分け**: 2 者対戦（§5）なら `competitor_side`（HOME/AWAY）で内訳を束ね、多人数順位制（§5B 後段 Phase）なら `score_entry_id` で束ねる（両方 NULL 許容で対戦モデルに応じて使い分け）。

### §4B.3 なぜ後段 Phase か（ブロッカー無しの明記）

- **ブロッカーは無い**: MVP の合計点格納（§4）は内訳子表に依存せず単独で成立する。後段 Phase の着手に MVP 側の変更は不要（子テーブル新設＋集計ロジック追加だけで拡張でき、`matches` 列の意味＝合計点は変えない）。
- **なぜ今やらないか**: 内訳のフル採点記録は専用採点システム級で、F08.10 の主目的（試合結果の記録と統計）には過剰。MVP は「合計点と勝敗が記録・統計できれば足りる」（GoalNote 上位互換の射程）。**内訳分析の要件が顕在化したときに本節の設計済 DDL から着手する**（曖昧な TODO ではなく、着手条件・拡張点・侵襲が明確な後段 Phase）。

---

## §5 対戦モデル＝MVP は 2 者対戦（確定）

採点競技の MVP 対戦モデルは **「2 者対戦」（マスター御裁可）**。既存の `matches.home_score`/`away_score` をそのまま使い、SCORED 類型をコアに最小コストで通す。**多人数順位制（`match_score_entries`）は後段 Phase の設計済 DDL として §5B に残すが、MVP では実装しない**。

### §5.1 2 者対戦モデル（確定）— 既存 home/away を流用

採点競技を「2 者（2 チーム）の対戦」として扱い、既存の `matches.home_score`/`away_score`（§4.1 の整数スケール格納）をそのまま使う。

- **適用できるケース**: 団体戦の 1 マッチ（A 校 vs B 校の対抗戦）・デュアルミート（2 校対抗の体操競技会＝米国大学体育で一般的）・「自分 vs 相手」の 1 対 1 採点比較。
- **メリット**: **コア改造が最小**（`home_score`/`away_score` に合計点を入れ §B.1.2 の `resolveResult()` をそのまま再利用・新テーブル不要）。team 中心の権限・IDOR・F00 可視性・WebSocket 観戦・集計がすべて既存のまま動く。
- **意図的な MVP 割り切り（誤魔化さず明記）**: **2 者対戦だけでは「多人数が順位を競う採点競技の大会」（フィギュア大会＝十数人〜数十人が滑り、合計点で 1〜N 位を決める／体操の個人総合順位）を表現できない**。これは MVP の意図的な割り切りであり、症状を隠す回避ではなく**段階導入の設計判断**である（README §7 の段階導入思想と一貫）。本来形の多人数順位制は §5B の後段 Phase で着手する。

### §5.2 多人数順位制を MVP に含めない判断の根拠

- 多人数順位制（§5B）は `home_score`/`away_score` を前提にした F08.10 コア全体（集計・権限・IDOR・F00 可視性・WebSocket 観戦・順位連携）への**新経路追加**を伴い、TURN_BASED 導入を上回る侵襲になる。
- MVP は 2 者対戦でコアに通し、**「採点競技をコアが扱える」状態を最小コストで作る**。本格的な大会順位制は要件が固まってから §5B の設計済 DDL で着手する（§4 の合計点・後段 Phase の内訳と同じ「小さく入れて拡張余地を残す」戦略）。

---

## §5B 多人数順位制 `match_score_entries`（**後段 Phase の設計済 DDL・MVP では実装しない**）

> **スコープ明記**: 本節は **MVP の実装スコープ外**である。「多人数が同一種目に出場し順位を競う採点競技の大会」（フィギュア大会・体操の個人総合順位）を表現する**本来形**であり、要件が固まったときに着手する**後段 Phase の設計済 DDL**として用意する。MVP は §5 の 2 者対戦で成立し、本節のテーブルは作成しない。

### §5B.1 後段 Phase の DDL（設計のみ）

1 つの match を「種目（イベント）」とし、複数の出場者（エントリ）が合計点を持ち、順位を導出する。

```sql
-- 【後段 Phase・MVP では作成しない】採点競技の出場者エントリ（match ドメイン内 → 親 matches へ CASCADE／原則 2）
-- 1 match=1 種目に複数の出場者が並ぶ（home/away の 2 者モデルを超える）
CREATE TABLE match_score_entries (
    id BINARY(16) NOT NULL,                       -- UUIDv7（コア原則 6）
    match_id BINARY(16) NOT NULL,                 -- matches(id)（同一ドメイン → FK CASCADE 可）
    competitor_user_id BIGINT NULL,               -- 出場選手（user ドメイン ID 参照・未登録は NULL・コア原則 1）
    competitor_name VARCHAR(128) NULL,            -- 未登録選手名（competitor_user_id NULL のとき）
    competitor_team_id BIGINT NULL,               -- 所属チーム（team ドメイン ID 参照・団体採点時）
    total_scaled INT NOT NULL DEFAULT 0,          -- 合計点（整数スケール×1000・§4.1 と整合・内訳〔§4B〕の集計 or 直接入力）
    rank_position SMALLINT UNSIGNED NULL,         -- 順位（合計点の降順で導出・同点同順位・Service が再計算）
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_score_entries_match (match_id, rank_position),
    INDEX idx_score_entries_user (competitor_user_id),
    CONSTRAINT fk_score_entries_match FOREIGN KEY (match_id)
      REFERENCES matches (id) ON DELETE CASCADE   -- 同一 match ドメイン内（原則 2）
);
```

### §5B.2 後段 Phase 導入時の設計方針（実装時の指針）

- **`matches` の 2 者列の扱い**: 多人数順位制では `home_score`/`away_score` は**主役でなくなる**。整合策として `matches.home_score` に「優勝エントリ or 自チーム最上位エントリの合計点」を補助的に格納し（順位表/ダッシュボードの既存導線が空にならないように）、正本は `match_score_entries` とする（二層正本・§4B と同じ思想）。
- **順位導出**: `rank_position` を合計点降順で Service が導出。同点は同順位（次順位を飛ばす標準ルール＝1,2,2,4）。
- **個人キャリア統計の価値**: 順位（`rank_position`）から表彰台回数・平均順位・自己ベストを導出でき、GoalNote 上位互換として価値が高い（§7.1 の podium/avgRank 指標が活きる）。

### §5B.3 なぜ後段 Phase か（ブロッカー無しの明記）

- **ブロッカーは無い**: MVP の 2 者対戦（§5）は `match_score_entries` に依存せず単独で成立する。後段 Phase は新規テーブル追加＋SCORED 専用の Service/Controller/集計 DTO＋集計/権限/IDOR/可視性/観戦の再配線で拡張する（home/away を前提にした既存コードを壊さず、多人数エントリ用の新経路を追加する）。
- **なぜ今やらないか**: 多人数順位制は home/away 2 者モデルを超える新経路で侵襲が大きく、本格的な大会順位制の要件（タイブレーク・種目別順位・総合順位の関係等）が固まってから着手するのが妥当。MVP は 2 者対戦で「採点競技をコアが扱える」状態を最小コストで作る（曖昧な TODO ではなく、着手条件・拡張点・侵襲が明確な後段 Phase）。

---

## §6 勝敗導出・引分けの扱い（MVP＝2 者対戦）

- **2 者対戦（MVP・確定）**: §B.1.2 の勝敗格納規約に完全準拠。合計点（整数スケール×1000）を `home_score`/`away_score` に入れ、`resolveResult()`（大小比較）で W/D/L を導出。**同点（整数スケール同値）は引分（DRAW）**。採点競技は小数（×1000）まで採るため同点は稀だが、同値時は DRAW とする。
  - **タイブレーク**: 大会レギュレーションのタイブレーク（フィギュア＝PCS 優先等／体操＝E スコア優先等）は MVP では扱わず単純引分とする。**なぜ今やらないか**: タイブレーク規定は大会依存で、採点競技を MVP に入れる第一弾では単純比較で成立する。後段で設定化しても集計の比較関数差し替えで吸収可能（侵襲小・ブロッカーではない）。
- **多人数順位制（後段 Phase・§5B）**: `match_score_entries.rank_position` を合計点降順で導出。同点は同順位（1,2,2,4）。引分概念は「同点同順位」として表現（W/D/L の二者勝敗とは別軸）。
- **`win_method` は使わない**（ターン制専用・採点競技では NULL）。「どう勝ったか」は採点競技では「合計点の差」そのものであり、勝ち方の分類は不要。

---

## §7 統計定義（採点競技固有指標）

### §7.1 個人キャリア統計（`UserMatchStatsResponse`）

| 指標 | 算出元（採点競技） |
|------|--------|
| totalGames | 出場した種目（match）数 |
| wins / draws / losses | 2 者対戦（MVP）: `resolveResult()`（合計点大小）。多人数順位制（後段 Phase）: 1 位回数を別指標化（下記 podium 推奨） |
| bestScore / avgScore | 合計点（整数スケール→小数復元）の自己ベスト・平均（フィギュア＝Total Segment Score・体操＝個人総合の最高/平均） |
| podiumCount / firstPlaceCount | **多人数順位制（後段 Phase）**: 表彰台（rank≤3）回数・優勝回数（採点競技の主要キャリア指標） |
| avgRank | **多人数順位制（後段 Phase）**: 平均順位（`rank_position` の平均・低いほど良い） |
| monthlyTrend[] / seasonTrend[] | 月別/シーズン別の平均スコア（フィギュア・体操とも合計点系列・line 用） |
| totalMinutes / goalsPer90 系 | **採点競技では無効**（NULL・FE 非表示・コア 04 §G.8。TURN_BASED と同じ扱い） |

> **MVP の有効指標**: 2 者対戦の MVP では totalGames / wins-draws-losses / bestScore / avgScore / monthlyTrend / seasonTrend が有効。podium/avgRank は多人数順位制（後段 Phase）で活きる指標であり、MVP では NULL（FE 非表示）。

### §7.2 チーム統計（`TeamMatchStatsResponse`）

| 指標 | 説明（採点競技） |
|------|------|
| wins / draws / losses | 2 者対戦（MVP）: チーム勝敗。多人数順位制（後段 Phase）: チーム所属選手の表彰台/順位サマリ |
| playerRankings | { userId, displayName, bestScore, avgRank, podiumCount }（top-N・退会者匿名化追従・コア原則 4。avgRank/podiumCount は後段 Phase で有効） |
| avgTeamScore | チーム所属選手の平均合計点（フィギュア・体操の合計点平均） |

---

## §8 コアへの追記差分（01 §D.6 / MatchService / カタログ）— 実装着手時の変更箇所

> 本書は設計確定であり実装は別波。以下は実装着手時の変更箇所一覧（実装計画の素案）。MVP（合計点・2 者対戦）に必要な変更のみを「MVP」、後段 Phase に必要な変更を「後段」と明示する。

1. **【MVP】`StateModel.java`（コア §D.6）**: `SCORED` を 1 値追加（§1）。
2. **【MVP】`Sport.java`**: `FIGURE_SKATING(StateModel.SCORED)`＋`GYMNASTICS(StateModel.SCORED)` の 2 競技を追加。
3. **【MVP】`MatchService.assertCompletable` の `switch(stateModel)`**: **`case SCORED` を追加**（現状 `default -> MATCH_024` で弾かれるため必須）。COMPLETED 条件は「合計点（`home_score`〔and `away_score`〕）が確定していること」。新エラーコード **`MATCH_035`（採点未確定）**を `MatchErrorCode` に追加（現状最大は `MATCH_034`＝添付件数上限ゆえ次番は `MATCH_035`）。
4. **【MVP】`home_score`/`away_score` の型拡張**: `SMALLINT UNSIGNED` → `INT UNSIGNED` ALTER（整数スケール×1000 格納のため・全競技共通列・既存値に無害）。Flyway 採番はマージ直前に origin/main 全体最大 major の次を採る（コア §B.1 採番規約・[[feedback_flyway_version_sort_after_global_max]] / [[feedback_migration_version_collision]]）。
5. **【MVP】`SportEventCatalog`（コア §D.3）**: フィギュア・体操の採点競技集合（`SCORE_SUBMITTED`/`COMMENT`/`OTHER`）を追加。`MatchEventType` に `SCORE_SUBMITTED` を追加（器）。
6. **【後段】`match_scored_components`（§4B）の新規テーブル CREATE**: 審判別内訳の要件が顕在化したとき（コア原則 6 UUIDv7・原則 2 CASCADE・A.4 二段アクセス）。MVP では作成しない。
7. **【後段】`match_score_entries`（§5B）の新規テーブル CREATE**: 多人数順位制の要件が固まったとき（同上）。MVP では作成しない。SCORED 専用 Service/Controller/集計 DTO の新経路を伴う。
8. **【MVP】README §1.0a の状態モデル類型表に SCORED 行を追加**・§7「3 類型外の競技」の先送り決定を「SCORED 類型（フィギュア＋体操）として設計確定済（本書）」へ更新（本更新で反映済）。

### §8.1 01 §D.6 への追記（StateModel 表に 1 行追加）

01 §D.6 の類型表に以下の行を追記する（本更新で §D.8 を確定版に昇格）:

| 類型 | 対象競技 | 試合進行 | スコア表現 | 出場時間算出 | period | FE composable |
|------|----------|----------|------------|--------------|--------|----------------|
| **SCORED** | FIGURE_SKATING / GYMNASTICS | 演技/試技 → 審判採点 | 合計点（整数スケール×1000・`home_score`/`away_score`・MVP）／審判内訳（`match_scored_components`・後段）／多人数順位（`match_score_entries`・後段） | **算出しない**（出場交代概念なし・TURN_BASED 同様） | **NULL**（ピリオド無） | `useMatchScoreEntry`（新設・§9 FE） |

---

## §9 FE — 共通シェルへの SCORED モジュール追加方針

既存の共通シェル（`live.vue`）＋競技別動的 import 機構（04 §G.16・`sportModuleRegistry.ts`）をそのまま踏襲する。

- **新モジュール `scoredModule`**: `SportLiveModuleScored`（`stateModel: 'SCORED'`）を `SportLiveModule` ディスクリミネーテッドユニオンに追加し、`REGISTRY` に **`FIGURE_SKATING` と `GYMNASTICS` の 2 エントリ**を `() => import('.../modules/scoredModule')` で追加（同一モジュールを両競技で共有・動的 import 踏襲＝バンドル肥大化回避・§G.16）。型ガード `isScoredModule` を追加。
- **採点入力 UI（合計点入力主動線）**: ターン制の最小 UI（§G.16a）と同系統の**結果入力主動線**。タイマー・選手グリッド・3 タップタイムラインは表示しない。
  - **MVP（合計点のみ・2 者対戦）**: 「採点を記録」→ 自/相手（HOME/AWAY）→ **合計点を入力**（小数キーパッド・×1000 整数へ変換して送信）→ 任意でコメント。**入力 1〜2 タップ＋数値入力**の最小摩擦（ADHD 配慮・§11）。
    - フィギュア＝Total Segment Score を入力（SP/FS 別入力は後段の内訳 Phase で対応）。体操＝個人総合スコアを入力（種目別入力は後段の内訳 Phase で対応）。MVP の入力 UI は両競技で共通（合計点 1 値）。
  - **後段 Phase（審判別内訳・§4B）**: 種目/セグメント（apparatus＝体操 FLOOR…／フィギュア SP/FS）→ 項目別点数（体操 D/E、フィギュア TES/PCS）→ 合計を自動算出表示。入力摩擦が上がるため後段 Phase。
  - **後段 Phase（多人数順位制・§5B）**: エントリ一覧（出場者を追加し各自の合計点を入力）→ 順位を合計点降順で自動表示。盤上の団体戦ボード進捗一覧（§G.16a）に近い「一覧＋個別入力」UI。
- **composable `useMatchScoreEntry`**: 最小遷移（WAITING→IN_PROGRESS→COMPLETED）＋合計点（MVP）／エントリ・順位（後段）管理の軽量 composable（`useMatchTurnTracker` の採点版・タイマー無し）。MVP は合計点管理のみ。
- **観戦者ビュー（§G.17）**: SCORED も WebSocket 観戦に乗る（採点確定を AFTER_COMMIT 配信・read-only）。多人数順位制（後段）は順位表の差分更新（live 順位）として配信できる（採点競技の観戦価値が高い）。

---

## §10 カタログ — ScoredCatalog の event_type・勝ち方相当の有無

- **event_type**: 採点競技は**イベント概念が薄い**（演技中の逐次イベントを記録しない）。`SCORE_SUBMITTED`（採点確定）＋`COMMENT`＋`OTHER` の少数に限る（§3）。タイムライン入力が主動線でない点は TURN_BASED と同じ。フィギュア・体操とも同一の event_type 集合。
- **勝ち方相当（win_method）**: **無し**（NULL）。ターン制の `win_method`（投了/詰み等）に相当する「勝ち方の分類」は採点競技には存在しない（勝敗＝合計点差そのもの・§6）。`WinMethodCatalog` にフィギュア・体操を登録しない（付けると 400・コア §D.7 の検証規約に準拠）。
- **規律コード（card_reason_code）**: 無し（NULL）。採点競技の減点（フィギュアの転倒等・体操の E スコア減点）は MVP では合計点に織り込み済み（個別記録しない）。後段 Phase（§4B）では `match_scored_components.component_type=DEDUCTION`（フィギュア）として内訳で表現する。
- **種目カタログ（後段 Phase・競技別）**: 後段 Phase（§4B）導入時のみ、競技別の `apparatus`（体操の FLOOR/POMMEL_HORSE/RINGS/VAULT/PARALLEL_BARS/HORIZONTAL_BAR… フィギュアの SP/FS）と `component_type`（フィギュア=TES/PCS/DEDUCTION・体操=D_SCORE/E_SCORE）を競技別カタログ（`Map<Sport, ScoredComponentCatalog>`）で定義する（コア §D.3 案 A と同方針・DB マスタ化は将来余地）。検証規約は「その競技カタログの列挙値であること」（列挙外は 400・症状を隠さない）。**MVP では種目カタログを持たない**（合計点のみ）。

---

## §11 セキュリティ・ユーザビリティ・保守性の観点

- **セキュリティ（採点の改竄防止・記録権限）**:
  - 採点スコアの確定・更新は team 中心権限（作成者/記録係/主体チーム ADMIN/DEPUTY）に限り、全変更を**監査ログ**に before/after 記録する（既存 `finalizeScore` の監査パターン踏襲・コア 03 §C.7）。**採点は順位・表彰に直結するため改竄インパクトが大きく、監査は必須**。フィギュア・体操とも合計点の変更は監査対象。
  - IDOR: 後段 Phase の子テーブル（`match_scored_components`/`match_score_entries`）は `organization_id`/`deleted_at` を持たず、**親 matches の二段アクセス**（コア A.4）で必ずアクセスする（子 ID 直引き禁止）。子 ID 指定 API は `entry.match_id == パスの matchId` を検証（不一致は 404・既存 MATCH_002/003 系の流用）。MVP は子テーブルを持たないため、`matches` の二段アクセス（テナント＋論理削除）のみ。
  - F00 可視性: 独自 visibility 述語を書かず `MatchVisibilityResolver`（コア 03 §C.3）に委譲。後段 Phase の多人数エントリも親 match の可視性に従う。
- **ユーザビリティ（採点入力の摩擦）**: MVP は合計点のみで**入力 1〜2 タップ＋数値入力**に抑える（ADHD 配慮の入力摩擦最小化・ユーザー memory `user_adhd_tendency`）。小数入力は専用キーパッド＋整数スケール自動変換でミスを防ぐ（198.45 を入力 → 内部 `198450`）。フィギュア・体操とも MVP の入力は合計点 1 値で統一（競技差を入力 UI に出さない）。内訳の項目別入力（後段 §4B）は摩擦が高いため後段 Phase。
- **保守性（新採点競技追加の容易さ）**: SCORED 類型を 1 つ通せば、フィギュア・体操の 2 競技は `Sport` enum 2 値＋カタログ登録 2 行＋composable 1 本（共有）で足り、3 番目以降の採点競技（新体操/飛込等）も `Sport` enum＋カタログ登録のみで追加できる（コア §D.6 の「3 ステップ」が 4 類型でも成立）。既存 3 類型のコア（タイマー/出場時間/集計枠組み/権限/IDOR/可視性/観戦）は再実装不要。
- **既存整合**: 勝敗格納規約（§B.1.2 home/away_score 統一・整数スケールで維持）・二層正本（§B.5/§B.6 の再導出パターン＝後段 §4B/§5B の集計に踏襲）・動的 import（§G.16）・F00 可視性委譲（03 §C.3）という既存設計の確立済みパターンに**すべて乗せている**（新パターンを発明していない）。唯一の例外は後段 Phase の多人数順位制（§5B）が home/away 2 者モデルを超える点で、これは MVP に含めず後段 Phase へ分離した。
- **2 競技の差異の取り扱い**: フィギュア（TES+PCS−減点・SP/FS セグメント）と体操（D+E・種目別）の採点構造の違いは、**MVP では合計点に還元されて差が消える**（両競技とも 1 つの合計点を `home_score`/`away_score` に格納）。差異が表面化するのは後段 Phase の内訳子表（§4B：フィギュア=TES/PCS/DEDUCTION・SP/FS、体操=D/E・apparatus 種目別）であり、競技別カタログで吸収する。**MVP の射程では 2 競技を同一経路で扱える**（保守性が高い）。

---

## §12 未解決事項（**ブロッカー ゼロ・曖昧 TODO なし**）

> 本書はマスター御裁可により論点(1)〜(3)が確定したため、**実装着手を妨げる未解決ブロッカーは存在しない**。以下に「確定済み事項」と「後段 Phase へ意図的に先送りした事項（着手条件・根拠付き）」を整理する。先送りはいずれも曖昧 TODO ではなく、ブロッカー無し・着手条件明確・MVP 割り切りの内容を明示している。

### §12.1 確定済み（マスター御裁可）

1. **対象競技** — **フィギュアスケート＋体操の 2 競技**（`Sport` に `FIGURE_SKATING`/`GYMNASTICS` 追加・両者 `stateModel()=SCORED`）。採点構造の差異（フィギュア=TES+PCS／体操=D+E・種目別）は §2 に明記。
2. **採点粒度** — **MVP=合計点のみ**（`home_score`/`away_score` に整数スケール×1000・§B.1.2 単一正準準拠・`resolveResult()` 再利用）。小数格納は整数スケール×1000＋`INT UNSIGNED` 拡張で確定（§4.1）。
3. **対戦モデル** — **MVP=2 者対戦**（`home_score`/`away_score` 流用）。
4. **同点** — MVP（2 者対戦）は単純引分（DRAW・整数スケール同値時）。タイブレークは後段（比較関数差し替えで吸収可・ブロッカーではない・§6）。

### §12.2 後段 Phase へ先送り（ブロッカー無し・着手条件明確）

1. **審判別内訳子表 `match_scored_components`（§4B）** — **設計済 DDL を用意・MVP では実装しない**。**ブロッカー無し**（MVP の合計点格納は子表に非依存で単独成立）。**なぜ後段か**: 内訳のフル記録は専用採点システム級で MVP の主目的（結果記録と統計）に過剰。**着手条件**: 「審判別/種目別/コンポーネント別の内訳分析」要件が顕在化したとき。子表新設＋集計ロジック追加で拡張でき `matches` 列の意味は変えない。
2. **多人数順位制 `match_score_entries`（§5B）** — **設計済 DDL を用意・MVP では実装しない**。**ブロッカー無し**（MVP の 2 者対戦は本表に非依存で単独成立）。**MVP 割り切りの内容**: 2 者対戦だけでは「多人数が順位を競う採点競技の大会」を表現できない（意図的な割り切り・症状隠しでない・§5.1）。**なぜ後段か**: home/away 2 者モデルを超える新経路で侵襲が大きく、本格的な大会順位制の要件が固まってから着手するのが妥当。**着手条件**: 大会順位制（タイブレーク・種目別/総合順位の関係等）の要件が固まったとき。
3. **タイブレーク規定** — MVP は単純引分。**ブロッカー無し**（後段で設定化しても集計の比較関数差し替えで吸収可・侵襲小）。**なぜ後段か**: タイブレークは大会依存で第一弾は単純比較で成立する。

> **未解決ブロッカーはゼロ**: §12.1 の確定事項で MVP 実装に必要な意思決定はすべて済んでいる。§12.2 の先送りはいずれも MVP 既定値で成立し、後段 Phase は着手条件・拡張点・侵襲が明確（曖昧 TODO ではない）。御裁可済みのため、本書の §8 変更箇所一覧に沿って実装計画（別波）へ展開できる。
