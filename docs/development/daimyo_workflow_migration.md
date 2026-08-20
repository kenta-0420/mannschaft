# 大名システム × Dynamic Workflows 移行設計

> 対象読者: 殿（メイン Claude セッション）
> 関連: `backend/.claudecode.md §28`（大名システム正典）、メモリ `feedback_model_routing` / `feedback_daimyo_system` / `feedback_subagent_worktree_required`
> ステータス: **設計方針のみ**（実装着手はマスター御裁可後）

---

## 0. 結論（要旨）

Dynamic Workflows は大名システムを**置き換えるものではなく、出陣・検分フェーズの「中身」を決定論的ファンアウトに差し替える道具**である（検分については `/検分 claude` を選んだ場合の話。`/検分` の既定検分者は `codex` で、Codex による独立検分が走る）。

- **軍議は対話スキルのまま残す** — Workflow は起動後に承認待ちで停止できず最後まで自走するため、`マスター御裁可` ゲートを内包できない。
- **出陣・`検分 claude` は Workflow 化が有効** — 独立足軽の並列実装（`pipeline` + worktree）、次元別レビュー→敵対的検証（find→verify）は Workflow の本命パターン。
- **モデル/effort 振り分けをスクリプトに固定できる** — 口頭ルール（`feedback_model_routing`）を機械的保証に格上げ。足軽が勝手に opus を引かない。
- **トークン削減の正体を誤認しない** — context 隔離による節約は現行 Agent ツール経由の大名システムでも既に得ている。Workflow の上乗せ価値は **コスト削減（安価モデル振り分け）＋決定論的制御＋予算上限**であって、context 節約そのものは現状と同等。

---

## 1. 前提: Workflow ツールの性質

| 性質 | 内容 | 大名システムへの含意 |
|---|---|---|
| **オプトイン必須** | `ultracode` 指定・明示依頼・スキル経由のいずれかでのみ起動 | 殿が独断で常用しない。スキル（`/出陣`等）からの呼び出しは正当 |
| **バックグラウンド自走** | 起動したら完了まで止まらない。途中で人間承認を待てない | 承認ゲートは Workflow の**外**に置く（後述 §4） |
| **1呼び出し=1ファンアウト** | 複数フェーズは Workflow を複数回に分け、間に殿が入る | 軍議→出陣→検分を1本に詰めない |
| **per-agent モデル/effort/isolation** | `agent(prompt, {model, effort, isolation:'worktree', schema})` | 役職別モデル振り分けと worktree 隔離をそのまま表現 |
| **budget** | `budget.total / remaining()` で「+500k」等の上限に足軽数を追従 | 重いタスクのコスト天井を機械的に守れる |

---

## 2. 役職 ↔ Workflow の対応

| 大名システム（§28） | Workflow での表現 |
|---|---|
| マスター（天上人） | Workflow の外。御裁可ゲートは殿が対話で取り次ぐ |
| 殿様（PM・メイン Claude） | Workflow を**起動する側**。スクリプト本体は書くが、フェーズ間で必ずループに戻る |
| 家老（設計・検分） | 軍議は `Plan`/`Explore` サブエージェントのまま。検分は `/検分 claude` を選んだ場合に Workflow の verify ステージ群（既定の `/検分` は Codex 検分）|
| 足軽（worktree 実装） | `pipeline()` / `parallel()` 内の `agent(..., {isolation:'worktree'})` |

---

## 3. スキル別 移行方針

| スキル | 方針 | 理由 |
|---|---|---|
| `/軍議` | **対話のまま維持** | 末尾に `マスター御裁可` ゲートがある。Workflow は停止できない |
| `/出陣` | **Workflow 化（推奨）** | 独立足軽の並列実装＝`pipeline` + worktree の典型 |
| `/検分 claude` | **Workflow 化（本命）** | 次元別レビュー→敵対的検証の find/verify が最も効く。**`/検分` の既定検分者は `codex` のため、この Workflow を走らせるには `claude` の明示が要る**（両者を独立に当てるなら `both`）|
| `/巡回` | 任意（軽量なら対話で十分） | ビルド/テスト監視は単発が多い |
| `/早馬` | **対話のまま** | 緊急バグは探索的・対話的。決定論ファンアウトに乗りにくい |

判断基準: **「人間承認ゲートを途中に挟むか」**＝挟むなら対話、挟まないファンアウトなら Workflow。

---

## 4. 承認ゲートの保ち方（最重要）

Workflow は自走するので、`軍議→裁可→出陣→裁可→検分→裁可→コミット` の鎖を**1本に詰めてはならない**。殿が各ゲートで対話に戻る「複数 Workflow 直列」方式を採る。

```
[対話] /軍議  → 家老が陣立て → 殿がマスターに上奏 →（御裁可）
            │
[Workflow] /出陣 を1本起動（足軽の並列実装＋ビルド／テスト）→ 殿が結果を読む
            │
[Workflow] /検分 claude を1本起動（次元別レビュー→敵対的検証）→ 殿が判定  ※無指定は Codex 検分
            │
[対話] 殿がマスターに戦果上奏 →（御裁可）→ コミット／マージ（gh のみ）
```

各 Workflow は「足軽の tool 出力を殿の context に持ち込まず、構造化結果だけ返す」ので、殿はフェーズ間の判断に集中できる。

---

## 5. モデル / effort 振り分け方針（コスト削減の核）

`feedback_model_routing` をスクリプト定数に落とす。原則:

| 用途 | model | effort | 根拠 |
|---|---|---|---|
| 殿の main loop（Workflow 起動元） | opus（その時点の最新世代） | — | 采配・最終判断 |
| 家老（軍議・設計） | opus（その時点の最新世代） | high | 設計の質がボトルネック |
| 足軽: 機械的タスク（DTO 量産・i18n・リネーム・getter/setter） | haiku / sonnet | low | 単価が安く、推論浅くて足りる |
| 足軽: 通常実装 | sonnet | medium | デフォルト |
| 足軽: 難所（並行制御・認可・複雑ドメイン） | opus | high | 失敗コストが高い |
| 検分: 一次レビュー（広く拾う） | sonnet | low | 件数を稼ぐ |
| 検分: 敵対的検証（詰める） | opus | high | 偽陽性/偽陰性を潰す |

> 注意: モデル切替は**トークン削減ではなくコスト削減**。安価モデルは opus より単価が低いだけで、消費トークン量そのものは減らない。期待値を区別すること。

> **model 省略時の挙動**: `agent()` / `Agent` で model を省略すると親（殿）のモデルを継承する。親が高価モデル（Opus/Fable 等）のとき浪費の最大要因になるため、機械的タスクでは必ず model を明示すること（`feedback_model_routing`）。

---

## 6. `/検分 claude` Workflow 骨格（参考実装）

```js
export const meta = {
  name: 'kenbun',
  description: '差分を次元別にレビューし、各 finding を敵対的に検証する',
  phases: [{ title: 'Review' }, { title: 'Verify' }],
}

const DIMENSIONS = [
  { key: 'compile',  prompt: '...コンパイル/型エラーの観点でレビュー' },
  { key: 'security', prompt: '...OWASP Top 10・認可(F00)漏れの観点でレビュー' },
  { key: 'spec',     prompt: '...陣立て書/仕様書との整合の観点でレビュー' },
  { key: 'reuse',    prompt: '...重複・簡素化の観点でレビュー' },
]

const results = await pipeline(
  DIMENSIONS,
  // 一次レビュー: 広く安く拾う
  d => agent(d.prompt, { label: `review:${d.key}`, phase: 'Review',
                         model: 'sonnet', effort: 'low', schema: FINDINGS_SCHEMA }),
  // 各 finding を敵対的に検証: opus で詰める（反証を試みさせる）
  review => parallel(review.findings.map(f => () =>
    agent(`次の指摘を敵対的に検証し、本物か反証せよ: ${f.title}\n${f.detail}`,
          { label: `verify:${f.file}`, phase: 'Verify', model: 'opus', effort: 'high',
            schema: VERDICT_SCHEMA })
      .then(v => ({ ...f, verdict: v }))))
)

const confirmed = results.flat().filter(Boolean).filter(f => f.verdict?.isReal)
return { confirmed }
```

ポイント:
- `pipeline` なので security 次元の検証が走る間に reuse 次元のレビューが並行 → 待ち時間ゼロ。
- 偽バグ捏造の罠（`feedback_empirical_bug_detection_over_speculation`）対策として、verify は「ファイル:行の実在」を必須スキーマ項目にする。

---

## 7. `/出陣` Workflow 骨格（参考実装）

```js
export const meta = {
  name: 'shutsujin',
  description: '足軽を worktree 並列で起動し実装→ビルド/テストまで通す',
  phases: [{ title: 'Implement' }, { title: 'Build' }],
}

// args = 家老の陣立て書（タスク配列）。殿が /軍議 の結果を渡す
const tasks = args // [{ id, scope, model, effort, prompt }]

const built = await pipeline(
  tasks,
  // 実装: worktree 隔離必須。難易度で model/effort を振り分け
  t => agent(t.prompt, { label: `足軽:${t.scope}`, phase: 'Implement',
                         isolation: 'worktree', model: t.model, effort: t.effort,
                         schema: IMPL_RESULT_SCHEMA }),
  // 各足軽の worktree で自己ビルド/テスト（先にコミット→長いビルドは後: feedback_subagent_commit_before_long_build）
  (impl, t) => agent(`${t.scope} の worktree でビルド・テストを通し、結果を返せ`,
                     { label: `巡回:${t.scope}`, phase: 'Build',
                       isolation: 'worktree', model: 'sonnet', effort: 'low',
                       schema: BUILD_RESULT_SCHEMA }))

return built.filter(Boolean)
```

ポイント:
- 依存のある足軽は `tasks` の段階を分け、Workflow を2本に割る（並行不可な鎖は pipeline に混ぜない）。
- 各足軽は `isolation:'worktree'` 必須。本陣にファイルツールが解決して破壊する事故（`feedback_subagent_file_tools_resolve_to_honjin`）を Workflow でも回避するため、足軽プロンプトに「起動直後 CWD 確認＋強制 cd」を引き続き明記する。
- コミット/マージは Workflow 内でやらない。殿が御裁可後に `gh` で行う（`feedback_merge_gh_only_no_honjin_git`）。

---

## 8. トークン / コスト削減の正直な評価

| 効果 | 現行 Agent 大名システム | Workflow 化後 | 差分 |
|---|---|---|---|
| 足軽 tool 出力を殿の context から隔離 | ✅ あり | ✅ あり | 同等（上乗せなし） |
| 役職別モデル振り分け | △ 口頭ルール頼み | ✅ スクリプトで強制 | **コスト削減を機械保証** |
| 機械的タスクの effort 引き下げ | △ 都度指定 | ✅ 定数化 | **コスト削減** |
| 決定論的ファンアウト/pipeline | ✗ 殿の手動采配 | ✅ | 待ち時間削減・取りこぼし防止 |
| 予算上限（+500k 等）への自動追従 | ✗ | ✅ `budget` | 暴走コスト防止 |

→ **「トークンを抑える」の実体はコスト削減（安価モデル＋低 effort）と暴走防止であり、context 節約の上積みは小さい**。最大の費用対効果は「機械的フェーズを haiku/sonnet・effort low に固定し、opus を難所と最終検証だけに集中させる」運用にある。

---

## 9. 段階導入計画

1. **第1段: `/検分 claude` を Workflow 化**（`/検分` の既定検分者は `codex` のため、この Workflow は `claude` 明示時に走る） — 既存の検分フローは独立性が高く、承認ゲートを内包しないため最も安全な初手。`code-review` スキルの思想を時代劇運用に合わせて移植。
2. **第2段: `/出陣` を Workflow 化** — 陣立て書（`args`）→ worktree 並列実装 → 自己ビルド。依存鎖は Workflow 分割で表現。
3. **第3段: 振り分け定数のチューニング** — どのスコープに haiku/sonnet/opus を当てるかを実績で調整。`feedback_model_routing` を更新。
4. **軍議・早馬は対話維持** — 移行対象外。

---

## 10. 制約・注意

- **Workflow はオプトイン**。スキル（`/出陣` `/検分 claude`）内から呼ぶか、マスターが明示依頼した時のみ起動する。殿が普通の依頼で勝手にファンアウトしない。
- **自走するので承認ゲートを内包しない**。御裁可は必ず Workflow の外（対話）で取る。
- **コミット/マージは Workflow 内禁止**。本陣 git 禁止フック・gh のみ運用を維持。
- **足軽の本陣破壊対策はスクリプト化後も必須**（CWD 確認・worktree 隔離・ファイルツールの本陣解決注意）。
- **モデル振り分けは品質とのトレードオフ**。難所に haiku を当てて差し戻しが増えれば逆にコスト増。実績で調整する。
