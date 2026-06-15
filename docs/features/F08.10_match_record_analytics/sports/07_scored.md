# F08.10 / sports / 07: 採点競技（第 4 状態モデル類型 SCORED）— 設計提案【マスター判断待ち】

> **ステータス**: 🟡 設計提案（**選択肢提示・マスター御裁可待ち**／実装未着手）
> **最終更新**: 2026-06-15（第 4 状態モデル類型 SCORED の起草。**対象競技候補・採点粒度(い)/(ろ)・多人数順位制 vs 2 者対戦モデル**の 3 つをマスターの選択肢として具体案で提示し、家老所見（推奨）を添える）
> **位置づけ**: **F08.10 コアを継承する「採点競技」カタログ＝第 4 状態モデル類型 `SCORED` の提案**。既存 3 類型（CONTINUOUS_TIME / SET_BASED / TURN_BASED・コア §D.6）に乗らない「得点＝審判スコアの合算」競技（フィギュアスケート・体操・新体操・飛込 等）を扱う。
> **関連機能番号**: F08.10（試合記録・分析）／ F08.7 ／ F19.1
> **関連ドキュメント（コア）**:
> - [../README.md](../README.md) — §1.0a 状態モデル類型・§7.2「3 類型外の競技（採点競技等）」先送り決定
> - [../01_domain_and_ddl.md](../01_domain_and_ddl.md) — §B.1/§B.1.2（勝敗格納規約＝全競技 home/away_score 統一）・§B.5（match_sets）・§B.6（団体戦）・§D.6（StateModel 類型）・§D.7（ターン制勝ち方）
> - [../03_permissions_and_recording_modes.md](../03_permissions_and_recording_modes.md) — 記録権限・IDOR・F00 可視性・入力検証
> - [../04_frontend_and_ux.md](../04_frontend_and_ux.md) — §G.16 共通シェル＋競技別動的 import・§G.16a ターン制最小 UI（採点 UI の前例）
> - [05_shogi.md](./05_shogi.md) — ターン制（球技でない競技）の前例。**本書は「スコア無し」のターン制とも「連続スコア」の球技とも異なる第 3 の極**（審判合算スコア）

---

## §0 この文書の読み方 — 「決め打ち設計」ではなく「選択肢提示」

README §7.2 のとおり、採点競技は MVP 6 競技に含めず「要件が顕在化したら新類型を追加する余地」として先送り決定済みであった。本書は**マスターが「採点競技を入れるならどう作るか」を具体案で判断できるよう**、決定をマスターに委ねる 3 つの論点を**具体的な DDL/UI 案＋家老の推奨**として提示する設計提案である。

| マスターが決める論点 | 選択肢 | §参照 |
|----------------------|--------|-------|
| **(1) 第一採点競技をどれにするか** | フィギュア / 体操 / 新体操 / 飛込 / 後送り | §2 |
| **(2) 採点粒度** | (い) 合計点のみ（最小侵襲・新テーブル無し） / (ろ) 審判別内訳（子テーブル新設・本格） | §4 |
| **(3) 対戦モデル** | (A) 2 者対戦（既存 home/away を流用） / (B) 多人数順位制（新規・採点競技の本来形） | §5 |

家老の総合推奨は **§9 家老所見** に集約する（先に結論を見たいマスター向け）。

---

## §1 SCORED 第 4 類型の位置づけ — なぜ既存 3 類型に乗らないか

採点競技（フィギュアスケート・体操等）は試合進行のしかたが既存 3 類型のいずれとも根本的に異なる。

| 観点 | CONTINUOUS_TIME（球技） | SET_BASED（バレー） | TURN_BASED（将棋/囲碁） | **SCORED（採点競技）** |
|------|--------------------------|----------------------|--------------------------|--------------------------|
| 進行 | タイマー＋ピリオド | セット進行 | 手番の応酬（総手数） | **演技/試技の提出 → 審判採点** |
| スコアの源泉 | 試合中のイベント（GOAL 等）の集計 | セット内ラリー得点 | スコア無し（勝敗＋勝ち方） | **審判団の採点の合算**（イベント集計でない） |
| 勝敗の決まり方 | 得点の大小 | 獲得セット数 | 勝ち方＋勝者 side | **合計点の大小（or 順位）** |
| 時間概念 | あり（出場時間算出） | 希薄 | なし | **なし**（演技時間はあるが記録不要） |
| 本来の対戦単位 | 2 チーム | 2 チーム | 2 者（個人/団体ボード） | **多人数が同一種目に出場し順位を競う**（§5 の最重要論点） |

- **SCORED の本質**: スコアは「試合中に起きたイベント（得点）の合算」ではなく、**演技/試技に対して審判団が与える点数の合算**である。CONTINUOUS_TIME がイベント駆動でスコアを積み上げるのに対し、SCORED は**確定した審判スコアを入力**する（タイムライン入力ではない）。この点で SCORED は TURN_BASED（結果のみ入力）の最小 UI に近いが、**「スコア無し」の TURN_BASED と異なり連続量のスコアを持つ**。
- **コアへの最小追加**: `StateModel` enum に `SCORED` を 1 値追加し、`assertCompletable` の `switch(stateModel)` に `case SCORED` を追加する（現状は `default -> MATCH_024`＝「入力内容に不備があります」で弾かれるため、**分岐の追加が必須**）。出場時間算出は TURN_BASED 同様**起動しない**（出場交代の概念が無い・コア §D.6）。

```java
// コア StateModel.java への追加案（§D.6 への追記＝§8）
public enum StateModel {
    CONTINUOUS_TIME, // 球技（タイマー）
    SET_BASED,       // バレー（セット）
    TURN_BASED,      // 将棋/囲碁（勝敗＋勝ち方・スコア無し）
    SCORED           // 採点競技（フィギュア/体操等・審判合算スコア・順位制）★追加
}
```

---

## §2 対象競技の候補と採点構造の違い【マスター論点(1)】

採点競技は競技ごとに採点構造が大きく異なる。MVP の第一採点競技を 1 つに絞る前提で候補を挙げる（複数同時導入は保守コスト過大ゆえ非推奨）。

| 競技 | 採点構造（点の内訳） | 合計点の出し方 | 特徴・記録の難しさ |
|------|----------------------|----------------|---------------------|
| **フィギュアスケート** | TES（技術点＝要素ごとの基礎点＋GOE）＋ PCS（演技構成点＝5 コンポーネント）− 減点（転倒等） | TES + PCS − Deductions = Total Segment Score。SP と FS の 2 セグメント合算で最終 | 要素別の基礎点表が巨大・GOE 計算が複雑。**フル採点は専用システム級**。記録粒度を「セグメント合計点」に割り切れば最小 |
| **体操（器械体操）** | D スコア（難度点・上限なし）＋ E スコア（実施点・10.000 から減点） | D + E = 種目スコア。複数種目（床/あん馬/つり輪…）の合算で個人総合 | D/E の 2 値構造はシンプル。**種目が多い**（男子 6・女子 4）ため種目別入力が要る |
| **新体操** | D（難度）＋ E（実施）＋（種目により A 芸術点） | D + E (+ A) = 種目スコア。種目（フープ/ボール/クラブ/リボン）合算 | 体操に近い 2〜3 値構造。種目数は体操より少ない |
| **飛込** | 各審判の素点（0〜10）× 演技難度（DD）。高低の素点を除外して合算 | (採用素点合計 × DD) = 1 本のスコア。複数本（試技）の合算 | **審判別素点 × 難度係数**の計算が独特。試技（dive）単位の積み上げ |

### §2.1 各競技の最小記録粒度（「合計点のみ」に割り切った場合）

| 競技 | 「合計点のみ」で記録する値 |
|------|----------------------------|
| フィギュア | 選手ごとの Total Segment Score（SP/FS 別 or 最終合計） |
| 体操 | 選手ごとの個人総合スコア（or 種目別スコア） |
| 新体操 | 選手ごとの総合スコア |
| 飛込 | 選手ごとの総得点 |

> いずれも「**選手（チーム）ごとに 1 つの合計点があり、その大小で順位が決まる**」という共通構造に還元できる。この共通構造が SCORED 類型をコアで成立させる鍵である（競技差分は内訳の有無＝§4 の粒度問題に集約される）。

---

## §3 event_type カタログ（採点競技）

採点競技は「イベントの時系列」を持たない（演技中の出来事を逐次記録しない・記録粒度＝結果スコア）。よってイベントは**結果系の少数**に限る（TURN_BASED と同じ思想）。

```
// コア MatchEventType に追加（器・SCORED 共通。球技/盤上は使わない）
SCORE_SUBMITTED,  // 採点結果の提出（選手/チームの合計点確定・home/away_score or 順位エントリへ反映）
COMMENT           // メモ（note へ自由記述・任意）
```

```java
// SportEventCatalog の採点競技集合（コア §D.3）— 第一競技を FIGURE_SKATING と仮置き
Sport.FIGURE_SKATING, EnumSet.of(SCORE_SUBMITTED, COMMENT, OTHER)
```

> **STARTER/SUB_IN・GOAL・カード・total_moves は使わない**: 採点競技は出場交代・得点イベント・カード体系・手数の概念が無いため、これらの event_type を SCORED カタログに含めない（カタログ検証で弾く・コア §D.3）。`card_reason_code`・`win_method`・`total_moves` はいずれも NULL（球技/盤上の専用列を流用しない）。
>
> **(ろ)案を採る場合の補足**: 審判別内訳を子テーブル（§4(ろ)）に持つ場合でも、`match_events` は「合計点の確定」を表す `SCORE_SUBMITTED` の 1 イベントに留め、内訳は子テーブルが正本とする（二層正本＝§4(ろ)）。

---

## §4 採点粒度の二択【マスター論点(2)・侵襲度が桁違い】

採点競技を「どこまで細かく記録するか」で侵襲度が大きく異なる。**(い) と (ろ) を併用する両立案（MVP は (い)・(ろ) は将来拡張余地として子テーブル設計だけ用意）**を家老は推奨する（§9）。

### §4(い) 合計点のみ — 最小侵襲・新テーブル不要【家老推奨・MVP】

**`matches.home_score`/`away_score` に合計点を入れる**（既存スカラ列の流用）。各競技固有の内訳（TES/PCS、D/E 等）は持たない。

- **DDL 変更ゼロ**（`home_score`/`away_score` SMALLINT UNSIGNED を流用）。**ただし採点は小数点を持つ**（フィギュア 198.45 点・体操 14.766 点）。SMALLINT では表現できないため、**小数スコアの格納方式が唯一の DDL 論点**になる。下記 (い-1)/(い-2) のいずれかで解決する。

  | 方式 | 内容 | 採否 |
  |------|------|------|
  | **(い-1) 整数スケール格納**（推奨） | スコアを 1000 倍（or 100 倍）した整数を既存 `home_score`/`away_score` に格納し、表示時に小数へ戻す。例: 198.45 → 198450（×1000）。**SMALLINT は最大 65535 で桁不足**ゆえ、**`home_score`/`away_score` を `INT UNSIGNED` へ拡張する ALTER が必要**（採点競技導入時の 1 回のみ・全競技共通列の拡張＝既存値に無害） | **推奨**。勝敗導出 `resolveResult()`（大小比較）が整数のまま動く・§B.1.2 統一を崩さない |
  | **(い-2) 専用小数列の追加** | `home_score_decimal`/`away_score_decimal`（`DECIMAL(7,3)`）を新設し採点競技のみ使用 | 非推奨。`resolveResult()` が `home_score`/`away_score`（整数）を見るため二重持ち・分岐が生じ §B.1.2 の単一正準が崩れる |

  > **家老所見（い-1 採用理由）**: §B.1.2「勝敗は全競技で `home_score`/`away_score` の大小から導出（`resolveResult()` 再利用）」という確立済み規約を**整数スケールなら崩さずに採点競技へ拡張できる**。`INT UNSIGNED` 拡張は全競技共通列の単純拡張で既存の球技/盤上スコア（小さな整数）に無害。スケール係数（×1000）は SCORED 類型に限り適用し、表示変換は FE/DTO 層で行う（コアの集計コードは整数の大小だけ見る）。

- **勝敗導出**: 合計点（整数スケール）の大小で W/D/L（§5(A) 2 者対戦の場合）。**多人数順位制(B)を採る場合は §5(B) の `match_score_entries` で順位を持つ**。
- **内訳を持たない割り切りの妥当性**: フィギュアのフル採点（要素別基礎点・GOE）は専用採点システム級で、F08.10 の主目的（試合結果の記録と統計）には過剰。「合計点と順位が記録・統計できれば足りる」という GoalNote 上位互換の射程に合致する（盤上競技で「棋譜フルエンジンを持たない」と割り切ったのと同じ思想・README §1.0a）。

### §4(ろ) 審判別内訳 — 本格・子テーブル新設【将来拡張余地】

審判パネル・難度点/実施点等を子テーブル `match_scored_components` に持つ。本格的だが大規模新設。

```sql
-- 採点内訳（match ドメイン内 → 親 matches へ CASCADE 可／原則 2・コア A.4 二段アクセス）
-- organization_id / deleted_at は持たない（テナント分離は親 matches）
CREATE TABLE match_scored_components (
    id BINARY(16) NOT NULL,                       -- UUIDv7（コア原則 6）
    match_id BINARY(16) NOT NULL,                 -- matches(id)（同一ドメイン → FK CASCADE 可）
    competitor_side ENUM('HOME','AWAY') NULL,     -- 2 者対戦(A)時の side（多人数(B)時は NULL・score_entry_id を使う）
    score_entry_id BINARY(16) NULL,               -- 多人数順位制(B)のエントリ参照（§5(B) match_score_entries.id・2 者(A)時は NULL）
    apparatus VARCHAR(32) NULL,                    -- 種目（体操の FLOOR/POMMEL… フィギュアの SP/FS・競技別カタログ enum 文字列）
    judge_label VARCHAR(32) NULL,                 -- 審判識別（J1〜J9 等・審判別素点を持つ飛込/フィギュア GOE 用・集計のみなら NULL）
    component_type VARCHAR(32) NOT NULL,           -- 項目（TES/PCS/DEDUCTION/D_SCORE/E_SCORE/RAW_SCORE 等・競技別カタログ enum 文字列）
    points_scaled INT NOT NULL DEFAULT 0,          -- 当該項目の点数（整数スケール＝×1000・小数は表示で復元・(い-1) と整合）
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_scored_components_match (match_id, competitor_side, apparatus),
    INDEX idx_scored_components_entry (score_entry_id),
    CONSTRAINT fk_scored_components_match FOREIGN KEY (match_id)
      REFERENCES matches (id) ON DELETE CASCADE   -- 同一 match ドメイン内（原則 2）
);
```

- **二層正本（再導出パターン）**: 内訳（`match_scored_components`）を合計して `matches.home_score`/`away_score`（or §5(B) の `match_score_entries.total_scaled`）へ反映する。これは **`match_sets`（セット内得点 → 獲得セット数を matches 列へ集計）・団体戦（子ボード勝ち星 → 親 matches 列へ集計）と全く同じ二層正本パターン**（コア §B.5・§B.6）。集計の正準は内訳テーブル、`matches` 列は再導出された合計、という責務分離。
- **competitor_side と score_entry_id の二者択一**: 2 者対戦(A)なら `competitor_side`（HOME/AWAY）で内訳を束ね、多人数順位制(B)なら `score_entry_id`（§5(B)）で内訳を束ねる。どちらを採るかは §5 の論点(3)に従う（両方を NULL 許容にして対戦モデルに応じて使い分ける）。
- **将来拡張余地としての位置づけ**: MVP は (い) で合計点のみを出し、(ろ) は**子テーブル DDL の設計だけ用意して実装は後段 Phase**とする。要件が「審判別の採点を残したい・種目別の内訳分析が欲しい」と顕在化したときに、子テーブル新設＋集計ロジック追加で拡張できる（`matches` 列の意味＝合計点は変えない）。

---

## §5 対戦モデル — 多人数順位制 vs 2 者対戦【マスター論点(3)・採点競技の最重要乖離】

> **本節は採点競技の最も本質的な論点であり、誤魔化さず明記する。** 採点競技は通常「2 チーム/2 者の対戦」ではなく、**多人数が同一種目に出場し、全員の合計点を比べて順位を決める**（フィギュアの大会＝十数人〜数十人が滑り、合計点で 1〜N 位を決める）。F08.10 コアの `matches` は **home/away の 2 者対戦モデル**を前提にしているため、ここに**構造的乖離**がある。

### §5(A) 2 者対戦モデル — 既存 home/away を流用【MVP の割り切り候補】

採点競技を「2 者（2 チーム）の対戦」として扱い、既存の `matches.home_score`/`away_score` をそのまま使う。

- **適用できるケース**: 団体戦の 1 マッチ（A 校 vs B 校の対抗戦）・デュアルミート（2 校対抗の体操競技会＝米国大学体育で一般的）・「自分 vs 相手」の 1 対 1 採点比較。
- **メリット**: **コア改造が最小**（`home_score`/`away_score` に合計点を入れ §B.1.2 の `resolveResult()` をそのまま再利用・新テーブル不要〔(い)の場合〕）。team 中心の権限・IDOR・F00 可視性・WebSocket 観戦・集計がすべて既存のまま動く。
- **デメリット**: **本来の採点競技（多人数が順位を競う個人戦の大会）を表現できない**。「10 人が滑って 1〜10 位」を 2 者対戦に押し込むと、9 個の擬似マッチ（総当たり）を作る等の不自然なモデルになり破綻する。

### §5(B) 多人数順位制モデル — 新規 `match_score_entries`【採点競技の本来形】

1 つの match を「種目（イベント）」とし、複数の出場者（エントリ）が合計点を持ち、順位を導出する。

```sql
-- 採点競技の出場者エントリ（match ドメイン内 → 親 matches へ CASCADE 可／原則 2）
-- 1 match=1 種目に複数の出場者が並ぶ（home/away の 2 者モデルを超える）
CREATE TABLE match_score_entries (
    id BINARY(16) NOT NULL,                       -- UUIDv7（コア原則 6）
    match_id BINARY(16) NOT NULL,                 -- matches(id)（同一ドメイン → FK CASCADE 可）
    competitor_user_id BIGINT NULL,               -- 出場選手（user ドメイン ID 参照・未登録は NULL・コア原則 1）
    competitor_name VARCHAR(128) NULL,            -- 未登録選手名（competitor_user_id NULL のとき）
    competitor_team_id BIGINT NULL,               -- 所属チーム（team ドメイン ID 参照・団体採点時）
    total_scaled INT NOT NULL DEFAULT 0,          -- 合計点（整数スケール×1000・(い-1) と整合・内訳(ろ)の集計 or 直接入力）
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

- **`matches` の 2 者列の扱い**: 多人数順位制では `home_score`/`away_score` は**主役でなくなる**。MVP の整合策として **`matches.home_score` に「自チーム最上位エントリの合計点」or「優勝エントリの合計点」を補助的に格納**し（順位表/ダッシュボードの既存導線が空にならないように）、正本は `match_score_entries` とする（二層正本・§4(ろ) と同じ思想）。`away_score` は NULL or 2 位エントリ点（運用で決める）。
- **メリット**: **採点競技を自然に表現できる**（個人戦の大会＝多人数が順位を競う本来形）。順位（`rank_position`）から個人キャリア統計（表彰台回数・平均順位・自己ベスト）を導出でき、GoalNote 上位互換として価値が高い。
- **デメリット**: **コアへの新規テーブル追加＋集計/権限/IDOR/可視性/観戦の再配線が必要**（home/away を前提にした既存コードが多人数エントリを扱えない＝新経路）。侵襲が大きく、SCORED 専用の Service/Controller/集計 DTO が要る。

### §5.1 家老の対戦モデル所見

- **本来の採点競技を価値あるものにするなら (B) が正しい**。しかし (B) は home/away を前提にした F08.10 コア全体（集計・権限・IDOR・F00 可視性・WebSocket 観戦・順位連携）への**新経路追加**を伴い、TURN_BASED 導入を上回る侵襲になる。
- **MVP の割り切り提案**: **第一弾は (A) 2 者対戦（団体採点の対抗戦・デュアルミート）に限定して SCORED 類型をコアに通し**、**(B) 多人数順位制は `match_score_entries` の DDL 設計だけ用意して後段 Phase**とする（§4 の (い)/(ろ) と同じ「小さく入れて拡張余地を残す」戦略）。これにより「採点競技をコアが扱える」状態を最小コストで作り、本格的な大会順位制は要件が固まってから着手できる。
- **誤魔化さない明記**: (A) だけでは「フィギュアの大会（多人数順位）」は表現できない。これは MVP の意図的な割り切りであり、症状を隠す回避ではなく**段階導入の設計判断**である（README §7.2 の先送り思想と一貫）。マスターが「最初から多人数順位制が要る」と判断するなら (B) を MVP に含める（その場合の侵襲を §5(B) デメリットに明記済）。

---

## §6 勝敗導出・引分けの扱い

- **2 者対戦(A)**: §B.1.2 の勝敗格納規約に完全準拠。合計点（整数スケール）を `home_score`/`away_score` に入れ、`resolveResult()`（大小比較）で W/D/L を導出。**同点は引分（DRAW）**。採点競技は小数まで採るため同点は稀だが、整数スケール（×1000）の同値時は DRAW とする（大会レギュレーションでタイブレーク〔技術点優先等〕がある場合は将来余地・MVP は単純引分・ブロッカーではない）。
- **多人数順位制(B)**: `match_score_entries.rank_position` を合計点降順で Service が導出。**同点は同順位**（次順位を飛ばす標準ルール＝1,2,2,4）。引分概念は「同点同順位」として表現（W/D/L の二者勝敗とは別軸）。
- **`win_method` は使わない**（ターン制専用・採点競技では NULL）。「どう勝ったか」は採点競技では「合計点の差」そのものであり、勝ち方の分類は不要。

---

## §7 統計定義（採点競技固有指標）

### §7.1 個人キャリア統計（`UserMatchStatsResponse`）

| 指標 | 算出元（採点競技） |
|------|--------|
| totalGames | 出場した種目（match）数 |
| wins / draws / losses | 2 者対戦(A): `resolveResult()`（合計点大小）。多人数(B): 1 位回数を wins とみなすか別指標化（下記 podium 推奨） |
| bestScore / avgScore | 合計点（整数スケール→小数復元）の自己ベスト・平均 |
| podiumCount / firstPlaceCount | 多人数(B): 表彰台（rank≤3）回数・優勝回数（採点競技の主要キャリア指標） |
| avgRank | 多人数(B): 平均順位（`rank_position` の平均・低いほど良い） |
| monthlyTrend[] / seasonTrend[] | 月別/シーズン別の平均スコア・順位（line 用） |
| totalMinutes / goalsPer90 系 | **採点競技では無効**（NULL・FE 非表示・コア 04 §G.8。TURN_BASED と同じ扱い） |

### §7.2 チーム統計（`TeamMatchStatsResponse`）

| 指標 | 説明（採点競技） |
|------|------|
| wins / draws / losses | 2 者対戦(A): チーム勝敗。多人数(B): チーム所属選手の表彰台/順位サマリ |
| playerRankings | { userId, displayName, bestScore, avgRank, podiumCount }（top-N・退会者匿名化追従・コア原則 4） |
| avgTeamScore | チーム所属選手の平均合計点 |

---

## §8 コアへの追記差分（01 §D.6 / MatchService / カタログ）— 実装着手時の変更箇所

> 本書は提案段階のため、コア（01 §D.6 等）への正式追記は**マスターが論点(1)〜(3)を裁可してから**行う。以下は裁可後の変更箇所の一覧（実装計画の素案）。

1. **`StateModel.java`（コア §D.6）**: `SCORED` を 1 値追加（§1）。`Sport.stateModel()` の新採点競技に `SCORED` を宣言。
2. **`Sport.java`**: 第一採点競技（例 `FIGURE_SKATING`）を追加し `Sport(StateModel.SCORED)` を宣言。
3. **`MatchService.assertCompletable` の `switch(stateModel)`**: **`case SCORED` を追加**（現状 `default -> MATCH_024` で弾かれるため必須）。COMPLETED 条件は「合計点（`home_score`〔and `away_score`〕or `match_score_entries`）が確定していること」。新エラーコード `MATCH_035`（採点未確定）を `MatchErrorCode` に追加。
4. **`home_score`/`away_score` の型拡張（(い-1) 採用時）**: `SMALLINT UNSIGNED` → `INT UNSIGNED` ALTER（整数スケール格納のため・全競技共通列・既存値に無害）。Flyway 採番はマージ直前に origin/main 全体最大 major の次を採る（コア §B.1 採番規約・[[feedback_flyway_version_sort_after_global_max]] / [[feedback_migration_version_collision]]）。
5. **`SportEventCatalog`（コア §D.3）**: 採点競技集合（`SCORE_SUBMITTED`/`COMMENT`/`OTHER`）を追加。`MatchEventType` に `SCORE_SUBMITTED` を追加（器）。
6. **(ろ)/(B) 採用時のみ**: `match_scored_components`（§4ろ）・`match_score_entries`（§5B）の新規テーブル CREATE（コア原則 6 UUIDv7・原則 2 CASCADE・A.4 二段アクセス）。
7. **README §1.0a の状態モデル類型表に SCORED 行を追加**・§7.2「3 類型外の競技」の先送り決定を「SCORED 類型として設計提案済（本書）」へ更新。

### §8.1 01 §D.6 への追記案（StateModel 表に 1 行追加）

01 §D.6 の類型表に以下の行を追記する（裁可後）:

| 類型 | 対象競技（候補） | 試合進行 | スコア表現 | 出場時間算出 | period | FE composable |
|------|------------------|----------|------------|--------------|--------|----------------|
| **SCORED** | FIGURE_SKATING（候補・§2） 等 | 演技/試技 → 審判採点 | 合計点〔(い)〕or 審判内訳〔(ろ)〕・2 者(A)は home/away_score・多人数(B)は match_score_entries | **算出しない**（出場交代概念なし・TURN_BASED 同様） | **NULL**（ピリオド無） | `useMatchScoreEntry`（新設・§9 FE） |

---

## §9 FE — 共通シェルへの SCORED モジュール追加方針

既存の共通シェル（`live.vue`）＋競技別動的 import 機構（04 §G.16・`sportModuleRegistry.ts`）をそのまま踏襲する。

- **新モジュール `scoredModule`**: `SportLiveModuleScored`（`stateModel: 'SCORED'`）を `SportLiveModule` ディスクリミネーテッドユニオンに追加し、`REGISTRY` に `FIGURE_SKATING: () => import('.../modules/scoredModule')` を 1 エントリ追加（動的 import 踏襲＝バンドル肥大化回避・§G.16）。型ガード `isScoredModule` を追加。
- **採点入力 UI（採点 UI＝項目別 or 合計点入力）**: ターン制の最小 UI（§G.16a）と同系統の**結果入力主動線**。タイマー・選手グリッド・3 タップタイムラインは表示しない。
  - **(い) 合計点のみ**: 「採点を記録」→ 出場者（2 者(A) なら自/相手・多人数(B) ならエントリ追加）→ **合計点を入力**（小数キーパッド・×1000 整数へ変換して送信）→ 任意でコメント。最小摩擦。
  - **(ろ) 審判別内訳**: 種目（apparatus）→ 項目別点数（D/E、TES/PCS 等）→ 合計を自動算出表示（内訳合計＝合計点）。入力摩擦は上がるため (ろ) は将来 Phase。
  - **(B) 多人数順位制**: エントリ一覧（出場者を追加し各自の合計点を入力）→ 順位を合計点降順で自動表示（`rank_position` 導出）。盤上の団体戦ボード進捗一覧（§G.16a）に近い「一覧＋個別入力」UI。
- **composable `useMatchScoreEntry`**: 最小遷移（WAITING→IN_PROGRESS→COMPLETED）＋エントリ/合計点/順位管理の軽量 composable（`useMatchTurnTracker` の採点版・タイマー無し）。
- **観戦者ビュー（§G.17）**: SCORED も WebSocket 観戦に乗る（採点確定を AFTER_COMMIT 配信・read-only）。多人数(B) は順位表の差分更新（live 順位）として配信できる（採点競技の観戦価値が高い）。

---

## §10 カタログ — ScoredCatalog の event_type・勝ち方相当の有無

- **event_type**: 採点競技は**イベント概念が薄い**（演技中の逐次イベントを記録しない）。`SCORE_SUBMITTED`（採点確定）＋`COMMENT`＋`OTHER` の少数に限る（§3）。タイムライン入力が主動線でない点は TURN_BASED と同じ。
- **勝ち方相当（win_method）**: **無し**（NULL）。ターン制の `win_method`（投了/詰み等）に相当する「勝ち方の分類」は採点競技には存在しない（勝敗＝合計点差そのもの・§6）。`WinMethodCatalog` に採点競技を登録しない（付けると 400・コア §D.7 の検証規約に準拠）。
- **規律コード（card_reason_code）**: 無し（NULL）。採点競技の減点（転倒等）は (ろ) 採用時に `match_scored_components.component_type=DEDUCTION` として内訳で表現し、(い) では合計点に織り込み済み（個別記録しない）。
- **種目カタログ（(ろ)/競技別）**: (ろ) 採用時のみ、競技別の `apparatus`（体操の FLOOR/POMMEL_HORSE/RINGS… フィギュアの SP/FS）と `component_type`（TES/PCS/D_SCORE/E_SCORE/DEDUCTION）を競技別カタログ（`Map<Sport, ScoredComponentCatalog>`）で定義する（コア §D.3 案 A と同方針・DB マスタ化は将来余地）。検証規約は「その競技カタログの列挙値であること」（列挙外は 400・症状を隠さない）。

---

## §11 セキュリティ・ユーザビリティ・保守性の観点

- **セキュリティ（採点の改竄防止・記録権限）**:
  - 採点スコアの確定・更新は team 中心権限（作成者/記録係/主体チーム ADMIN/DEPUTY）に限り、全変更を**監査ログ**に before/after 記録する（既存 `finalizeScore` の監査パターン踏襲・コア 03 §C.7）。採点は順位・表彰に直結するため改竄インパクトが大きく、監査は必須。
  - IDOR: 子テーブル（`match_scored_components`/`match_score_entries`）は `organization_id`/`deleted_at` を持たず、**親 matches の二段アクセス**（コア A.4）で必ずアクセスする（子 ID 直引き禁止）。子 ID 指定 API は `entry.match_id == パスの matchId` を検証（不一致は 404・新エラーコード or 既存 MATCH_002/003 系の流用）。
  - F00 可視性: 独自 visibility 述語を書かず `MatchVisibilityResolver`（コア 03 §C.3）に委譲。多人数(B) のエントリも親 match の可視性に従う。
- **ユーザビリティ（採点入力の摩擦）**: MVP は (い) 合計点のみで**入力 1〜2 タップ＋数値入力**に抑える（ADHD 配慮の入力摩擦最小化・ユーザー memory `user_adhd_tendency`）。(ろ) の項目別入力は摩擦が高いため将来 Phase。小数入力は専用キーパッド＋整数スケール自動変換でミスを防ぐ。
- **保守性（新競技追加の容易さ）**: SCORED 類型を 1 つ通せば、2 番目以降の採点競技（体操→新体操等）は `Sport` enum＋カタログ＋composable 追加のみで足りる（コア §D.6 の「3 ステップ」が 4 類型でも成立）。既存 3 類型のコア（タイマー/出場時間/集計枠組み/権限/IDOR/可視性/観戦）は再実装不要。
- **既存整合**: 勝敗格納規約（§B.1.2 home/away_score 統一）・二層正本（§B.5/§B.6 の再導出パターン）・動的 import（§G.16）・F00 可視性委譲 という既存設計の確立済みパターンに**すべて乗せている**（新パターンを発明していない）。唯一の例外は (B) 多人数順位制が home/away 2 者モデルを超える点で、これは §5 で明示的に論点化した。

---

## §12 未解決事項（マスター裁可待ち＝意図的に開いている／曖昧 TODO ではない）

> 以下は「曖昧な先送り」ではなく、**マスターが具体案から選ぶべき意思決定**として明示的に開いている。各項目に選択肢と家老推奨を併記しており、裁可されれば即実装に落とせる（ブロッカーの所在が明確）。

1. **第一採点競技の選定（論点 1）** — 候補: フィギュア/体操/新体操/飛込（§2）。**家老推奨: フィギュアスケート**（採点構造を「セグメント合計点」に割り切りやすく、種目数が少なく、知名度が高い）。裁可後に `Sport.FIGURE_SKATING` を確定。
2. **採点粒度（論点 2）** — (い) 合計点のみ / (ろ) 審判別内訳（§4）。**家老推奨: 両立案＝MVP は (い)・(ろ) は `match_scored_components` の DDL 設計だけ用意して後段 Phase**。
3. **対戦モデル（論点 3）** — (A) 2 者対戦 / (B) 多人数順位制（§5）。**家老推奨: MVP は (A) でコアに通し、(B) は `match_score_entries` の DDL 設計だけ用意して後段 Phase**。ただし「最初から大会順位制が要る」とマスターが判断するなら (B) を MVP に含める（侵襲は §5(B) デメリット参照）。
4. **小数スコアの格納（(い) 採用時）** — (い-1) 整数スケール×1000＋`INT UNSIGNED` 拡張 / (い-2) 専用 DECIMAL 列（§4い）。**家老推奨: (い-1)**（§B.1.2 の単一正準を崩さない）。
5. **同点タイブレーク** — MVP は単純引分（2 者）/同点同順位（多人数）。大会レギュレーションのタイブレーク（技術点優先等）は将来余地。**なぜ今やらないか**: タイブレーク規定は大会依存で、採点競技を MVP に入れる第一弾では単純比較で成立する（後段で設定化しても集計関数の比較関数差し替えで吸収可能・侵襲小）。

> **本書はブロッカーを「マスターの意思決定 3 点」に集約しており、それ以外の技術的未解決は無い**（小数格納・引分けは既定値で成立）。裁可が下りれば §8 の変更箇所一覧に沿って実装計画（Phase）へ展開できる。
