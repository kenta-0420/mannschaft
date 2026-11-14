# 04. 依存関係・サプライチェーン管理

> **ステータス**: 🟢 設計確定
> **実装フェーズ**: Security Hardening Phase 1
> **最終更新**: 2026-10-24
> **関連ドキュメント**: [README](README.md)

---

## 1. 概要

OSS ライブラリの既知脆弱性（OWASP A06）への対応方針を定義する。既存の OWASP Dependency-Check に加え、**Dependabot による自動検知・更新 PR** を導入し、検知から更新までのフローを整備する。

---

## 2. 現状

| 仕組み | 状態 | 備考 |
|---|---|---|
| OWASP Dependency-Check（Gradle プラグイン `org.owasp.dependencycheck`） | 導入済み | バックエンド依存の CVE スキャン |
| `.github/workflows/security-scan.yml` | 存在 | CI でのセキュリティスキャン |
| Dependabot（`.github/dependabot.yml`） | **未導入** ★本 Phase で追加 | 自動更新 PR |
| フロント `npm audit` の CI 組み込み | **導入済み（ブロッキング）** | §4 参照。`frontend-ci.yml` に `--audit-level=high` を `continue-on-error` なしで実行。high/critical 0 件達成済み（2026-06-13）|

---

## 3. Dependabot 設定

`.github/dependabot.yml` を新規作成し、3 エコシステムを週次でチェックする。

```yaml
version: 2
updates:
  - package-ecosystem: "gradle"
    directory: "/backend"
    schedule:
      interval: "weekly"
    open-pull-requests-limit: 5
  - package-ecosystem: "npm"
    directory: "/frontend"
    schedule:
      interval: "weekly"
    open-pull-requests-limit: 5
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "weekly"
    open-pull-requests-limit: 3
```

### 運用方針
- **セキュリティ更新（patch/minor）**: 優先的にレビュー・マージ。CI（backend-ci / frontend-ci）が通ることを確認
- **メジャー更新**: 破壊的変更を伴うため、個別に影響評価してからマージ
- PR 上限を設けてレビュー負荷を制御。溜まった場合は手動で `@dependabot rebase` 等で消化

---

## 4. フロント `npm audit`

- フロントエンド CI（`frontend-ci.yml`）に `npm audit --audit-level=high` ステップを追加済み（依存インストール `npm ci` の直後に実行）
- **現状の CI 扱いは「ブロッキング（門番）」**: `continue-on-error` を付けずに実行し、high/critical が 1 件でも検出されると CI を落とす
  - **経緯**: 2026-05-26 の初回スキャンでは high 11 件（moderate 14・low 1・total 26）が存在したため、段階導入方針に従い当初は警告のみ（`continue-on-error: true`）で導入した。その後 Nuxt 系の更新で high が全て解消され、2026-06-02 に `continue-on-error` を削除してブロッキング化した
  - **現状（2026-06-13）**: high/critical のみならず moderate も含め `npm audit` は **0 件**。`--audit-level` を `critical` 等へ安易に緩めて症状を隠すことは引き続き禁止
- 既知の誤検知・修正不可能な transitive 依存は `package.json` の `overrides` で管理し、理由をコメントで残す（audit の個別除外は現在ゼロ。§4.3）
- Dependabot と役割が重複するが、audit は「（脆弱性解消後は）CI を落とす門番」、Dependabot は「更新 PR の自動生成」と位置づけ、両輪で運用する

### 4.1. 初回スキャン結果（2026-05-26）→ 全件解消済み（2026-06-13）

> **ステータス（2026-06-13）**: 下表の high 11 件は **全て解消済み**。Nuxt 系の継続的な更新（serialize-javascript 7.0.5 / node-forge 1.4.0 / vite 7.3.5 / lodash 4.18.1 / h3 1.15.11 / simple-git 3.36.0 等の安全版への引き上げ）により high/critical はゼロになった。残っていた moderate 3 件（`@nuxt/nitro-server` / `@nuxt/vite-builder` 由来の `__nuxt_island` shared-cache poisoning / route middleware バイパス）も `nuxt` を 3.21.2 → **3.21.6** へ patch 更新して解消し、`npm audit`（全レベル）は **0 件** となった。両 GHSA（`GHSA-g8wj-3cr3-6w7v` / `GHSA-hg3f-28rg-4jxj`）の first patched version は 3.21.6 であり、これが脆弱性を解消する最小バージョンである。なお 3.21.7 以降は `ssr: false`（SPA モード）での dev サーバー起動が `No entry found in rollupOptions.input` でクラッシュするリグレッションを含むため（[nuxt#35033](https://github.com/nuxt/nuxt/issues/35033)）、本プロジェクトの CI（`CI=true` で `ssr: false`）と両立する 3.21.6 を採用した。下表は当時の記録として保持する。

`frontend/` で `npm audit --audit-level=high` を実行した結果、high レベルの脆弱性は以下の 11 件（いずれも transitive 依存。`npm audit fix` で修正可能と表示されるが、Nuxt/Vite 系のメジャー更新を含むため別 PR で慎重に解消する）:

| パッケージ | 深刻度 | 概要 |
|---|---|---|
| `@babel/plugin-transform-modules-systemjs` | high | 悪意ある入力で任意コード生成（GHSA-fv7c-fp4j-7gwp）|
| `devalue` | high | sparse array デシリアライズによる DoS（GHSA-77vg-94rm-hx3p）|
| `fast-uri` | high | percent-encoded ドットセグメントによるパストラバーサル / host confusion |
| `h3` | high | serveStatic のパストラバーサル / SSE インジェクション / ミドルウェアバイパス 等（多数）|
| `js-cookie` | high | prototype hijack による cookie 属性インジェクション（GHSA-qjx8-664m-686j）|
| `lodash` | high | `_.template` の Code Injection / `_.unset`・`_.omit` の Prototype Pollution |
| `node-forge` | high | 証明書チェーン検証バイパス / 署名偽造 / DoS（多数）|
| `picomatch` | high | POSIX 文字クラスのメソッドインジェクション / extglob の ReDoS |
| `serialize-javascript` | high | RegExp.flags 経由の RCE / CPU 枯渇 DoS |
| `simple-git` | high | Remote Code Execution（GHSA-hffm-xvc3-vprc）|
| `vite` | high | Optimized Deps `.map` のパストラバーサル / dev server WebSocket の任意ファイル読み取り 等 |

> moderate（14 件）・low（1 件）は `--audit-level=high` では CI 出力に含めない（門番の閾値外）。脆弱性解消は本 PR のスコープ外とし、Dependabot PR / 個別の更新 PR で順次対応する。

### 4.2. 脆弱性解消の優先順位（2026-06-02 精査）→ 全件解消済み（2026-06-13）

> **ステータス（2026-06-13）**: 下表の優先度別 11 件は **全て解消済み**。`serialize-javascript` / `simple-git` / `node-forge` / `vite` / `lodash` / `h3` 等はいずれも安全版へ更新済みで、`npm audit --audit-level=high` は 0 件。下表は対応経緯の記録として保持する。

以下の優先度で解消すること（2026-05-26 時点で 11 件全て未解消）:

| 優先度 | パッケージ | 脆弱性種別 | 対応方針 |
|---|---|---|---|
| 🔴 最高 | `serialize-javascript` | RCE / CPU 枯渇 DoS | `npm update` または代替ライブラリへ移行 |
| 🔴 最高 | `simple-git` | Remote Code Execution | `npm update` |
| 🔴 高 | `node-forge` | 証明書偽造 / DoS | `npm update` |
| 🔴 高 | `vite` | パストラバーサル / WebSocket 任意ファイル読み取り | Vite メジャーアップデート（破壊的変更確認要） |
| 🟠 中 | `lodash` | Prototype Pollution / Code Injection | `lodash-es` に移行 or アップデート |
| 🟠 中 | `h3` | SSE バイパス / パストラバーサル | 間接依存 → Nuxt アップデートで解消期待 |
| 🟡 低 | `fast-uri`, `picomatch`, `js-cookie`, `devalue` | 各種 | Dependabot PR で対応 |

**解消後のアクション**: `continue-on-error: true` を削除して CI ブロッキング化する（§6 参照）。→ **実施済み（2026-06-02 にブロッキング化、2026-06-13 に moderate も含め 0 件達成）**。

---

### 4.3. node-forge / braces の個別例外は撤去済み（2026-11-03）

現状: 監査例外の仕組み（`frontend/scripts/audit-with-exemption.mjs` の `EXEMPTIONS` 表）は畳んだ。個別除外は **ゼロ** で、`npm audit` の high/critical は **無条件で CI を落とす**。除外を再導入する場合は GHSA 単位・期限付き・到達経路限定で定義し、本書に理由を残すこと。`--audit-level` の緩和は引き続き禁止。

撤去の経緯（履歴）:

- node-forge（GHSA-86w9-cpqp-85rv）: 2026-10-02 に修正版が無く期限付き例外を設けたが、1.4.2 への更新で解消したため撤去した。
- braces（GHSA-vfj7-8cjw-p6xm、CMP-261003-1229）: 2026-10-03 に修正版が無く期限付き例外を設けた。期限（2026-10-23 UTC）切れ後、勧告が発火しなくなった（npm audit の high/critical は 0 件、braces 3.0.3）ため撤去した。

設計の詳細（許可 packages/nodes/edges・期限判定など）は git 履歴（PR #4259 以前の本節と `audit-with-exemption.mjs`）を参照する。

### 4.5. 修正版依存と simple-git v4 の限定consumer互換補正（2026-10-06）

新しい監査findingは `simple-git`（GHSA-x6jw-m9v5-85vh / GHSA-g4wm-2vf7-vfgr / GHSA-858h-whjf-mvg5）、`@simple-git/argv-parser`（GHSA-v5rq-49vh-5v5c）、`@vue/server-renderer`（GHSA-g2v6-rqmx-r4w6）、`seroval`（GHSA-jp82-f5mq-hwhp）、`source-map-js`（GHSA-68fv-2mgg-jv7q）。公式修正版をそれぞれ simple-git 4.0.1、argv-parser 2.0.1、Vue/server-renderer 3.5.42、seroval 1.6.3、source-map-js 1.2.2 へ引き上げる。simple-git 4.0.1 自身の argv-parser 依存は exact 2.0.0 のため、その子依存と他の消費者も exact 2.0.1 へ指定する。監査例外の追加・閾値緩和・Nuxtの降格は行わない。

Nuxt DevTools は `@nuxt/devtools` override で3.4.1に固定する。安定版3.4.2も同じdefault importを持ち、4系betaへの更新はNuxt/Viteの変更を伴うため採用しない。Nuxt DevTools 3.4.1 は配布物 `dist/chunks/module-main.mjs` に default import を一つ持つ。一方、simple-git v4 は default export を削除したため、`scripts/patch-nuxt-devtools-simple-git.mjs` を正規 postinstall の `nuxt prepare` より前に実行し、その唯一の import を `import { simpleGit as Git } from 'simple-git'` へ合わせる。Gitの branch/revparse/status APIや本体の認可・Git unsafe guard は変更しない。対象版3.4.1・元配布SHA256 `13f0dbd2e845848ad98fe644eab152c43a0a8cb88d6ff04a4bd77d12a68ac784`・補正後SHA256 `e42d96ee5d9a85f68785341fcac20b161da70336c5a10a11da5ea703d6c7406e` が一致するものだけを受理し、既に同一補正済みの場合のみ冪等とする。未知版・改変bytes・symlink・任意pathは失敗させる。変更対象は install された node_modules 内の一ファイルだけで、公式配布物をリポジトリへ複製しない。追加npmパッケージは使わず、Dockerの依存インストール段階にも同じ厳格スクリプトを渡す。postcss-selector-parserは7.1.6へ固定する。

解除条件は、安定版の公式DevToolsがnamed importへ対応し、適切な修正済みsimple-git/argv-parserへ依存し、正規npm ci→nuxt prepareと実監査ゲート・FE CIが通ること。その時に互換script/test・postinstall挿入を除去し、不要となった限定overrideも見直す。版を更新してSHA検査を単に緩める運用は禁止。監査例外の追加や閾値緩和は行わない。

本節の版指定だけで検証済みとは扱わない。正規lock生成、実npm ci/nuxt prepare、配布bytesを用いた互換補正テスト、実auditと既監査ゲート、FE unit/lint/type/type-drift の実行結果が別途必要。

### 4.6. simple-git 等の修正版と DevTools 無効化（2026-10-07）

新たに検出された [simple-git](https://github.com/advisories/GHSA-x6jw-m9v5-85vh) / [argv-parser](https://github.com/advisories/GHSA-v5rq-49vh-5v5c) の critical は例外に追加せず、simple-git 4.0.2（argv-parser 2.0.1）へ更新する。[Vue SSR](https://github.com/advisories/GHSA-g2v6-rqmx-r4w6) 3.5.43、[seroval](https://github.com/advisories/GHSA-jp82-f5mq-hwhp) 1.6.8、[source-map-js](https://github.com/advisories/GHSA-68fv-2mgg-jv7q) 1.2.2、[postcss-selector-parser](https://github.com/advisories/GHSA-rj75-hqrm-r3gf) 7.1.6 も既知 advisory の影響範囲外へ更新する。Vue compiler-sfc の実依存要求を満たすため、正規 npm resolver の PostCSS 8.5.29 / nanoid 3.3.20 の node を同期し、既存 platform binding は保持する。

利用方針の選択により全環境で `devtools.enabled: false` とする。§4.5 の版・配布物 SHA を照合する互換パッチは保持する。Docker の依存インストール段階では製品 Nuxt 設定をまだコピーしていないため、このパッチが必要である。installed Nuxt の静的条件分岐を確認したが、修正後の実 dev 起動・CI・本番生成は別途検証が必要であり、本記録だけで合格とは扱わない。監査例外の追加や audit 閾値緩和は行わない（個別例外の仕組みは撤去済み。§4.3）。

2026-10-07 の追加監査では [sharp の GHSA-wq5f-xc86-pv6w](https://github.com/lovell/sharp/security/advisories/GHSA-wq5f-xc86-pv6w) と [shell-quote の GHSA-pqg4-j6r4-53mv](https://github.com/ljharb/shell-quote/security/advisories/GHSA-pqg4-j6r4-53mv) が検出された。sharp 0.35.5（対応する全プラットフォーム配布物を含む）と shell-quote 1.11.0 を通常 npm 解決による修正版候補とする。候補 lock の読み取り監査は critical 0、既存の期限付き例外のみ high 18 で、既存 checkAudit の実終了値は 0。npm ci、実開発起動、Sharp の実ロード、本番生成の検証は別途必要であり、この候補監査だけで合格とは扱わない。新しい例外や閾値緩和は行わない。


2026-10-23、DOMPurifyの[GHSA-cg2g-xh64-v4q7](https://github.com/cure53/DOMPurify/security/advisories/GHSA-cg2g-xh64-v4q7)と[GHSA-mrv2-3pqr-j5jr](https://github.com/cure53/DOMPurify/security/advisories/GHSA-mrv2-3pqr-j5jr)に対応し、既存指定範囲内の最小修正版3.4.17へlockを更新する。critical/highの新しい例外は追加しない。

### 4.7. Zod 4 と安定版 VeeValidate 4 の互換対応（2026-10-24）

Zod の GHSA-mrhx-c7xc-m3w5（過大な制約付きコレクション検証によるメモリ枯渇、影響範囲 `<4.6.6`）に対し、Zod を 4.6.7 へ更新する。Zod 3 の peer dependency を持つ `@vee-validate/zod` 4.15.1 は撤去し、安定版 VeeValidate 4.15.1 を維持する。ベータ版への移行、peer dependency の強制無視、監査例外の追加や閾値緩和は行わない。happy-dom の GHSA-834v-8h66-3h79 も最小修正版 21.0.4 へ更新する。

`frontend/app/utils/toTypedSchema.ts` は現行フォームの平坦な `ZodObject` だけを接続する。公開 API の `safeParseAsync` で非同期検証・trim 後の出力・フィールド別およびルートのエラーを VeeValidate へ渡す。同期 cast が未入力や非同期検証で成立しない初期化・reset では、公開 `shape` と `ZodDefault.parse(undefined)` で取得した default に明示入力を優先して重ねる。内部 `_def` には依存しない。配列・union・入れ子スキーマ向けの汎用 adapter は追加しない。`describe` は現行の平坦なフィールドの存在・必須判定を担う。

既存スキーマの旧エラー API は Zod 4 の `error` に合わせ、required/invalid type の指定範囲、同意文言、日付 null の拒否、命名検証時の翻訳評価を保持する。実 `useForm` を使う互換試験は `toTypedSchema.spec.ts`、日付と命名は既存のフォーム試験で検証する。既存の VeeValidate を mock したフォーム試験だけを互換性の根拠にしない。

Webhook の URL は Zod 4 により前後の空白と ASCII tab/CR/LF を除いた出力になる。通常の URL の受理条件とバックエンドの HTTPS・DNS/SSRF 境界は維持し、この正規化を互換試験で固定する。

正規 `npm ci` / `npm ls`、全体 lint と8GBヒープの型検査、実 `useForm` 7件と監査関連の Node 回帰27件を確認した。実監査では Zod・旧 adapter・happy-dom の finding は消失したが、当初はnode-forgeの例外期限切れで監査ゲートが赤だった。node-forge の例外は後に撤去した（§4.3）。全量 Vitest の fixture 補完再試行と既存 SSR の5件 skip を含む検証記録は `docs/task-list.md` の CMP-261024-0528 に記載する。

解除条件は、公式の安定版 adapter が修正済み Zod 4 と互換であり、初期値/default/reset・非同期検証・送信出力・エラーの契約試験と実機確認を通過すること。その時にこの互換層を撤去する。版指定や単体試験だけで出荷済みとは扱わず、正規 `npm ci`、`npm ls`、実監査、lint/typecheck、既存フォームの回帰と実機試験の結果を別途記録する。監査例外の追加や閾値緩和は行わない。

### 4.8. Nitro/MLLY の限定モジュール解決補正（2026-11-12）

Windows の標準 SSR production build で、Nitro 2.13.4 の外部化判定が `fileURLToPath` の native 逆slashと正規化済みidを直接比較し、主entry/公開subpathの一致を見失う。また MLLY 1.8.2 の `lookupNodeModuleSubpath` は対象fileに近い入れ子manifestを読み、Vue の root exports から正式な `./server-renderer` を逆引きできない。この2点だけを `frontend/scripts/patch-nitro-module-resolution.mjs` で補正する。Nitroは返却pathを既存の `normalize` へ通し、MLLYはparse済みのdir/nameでroot package.jsonを読む（CJS/ESM双方）。非公開 `vue/server-renderer/index.mjs` を公開したり、exportsの制限を迂回したりしない。

| 対象（版） | 公式配布物SHA256 | 補正後SHA256 |
|---|---|---|
| nitropack 2.13.4 `dist/rollup/index.mjs` | `847ad3149fd532c8241293de7d8d08b1b181fe2ddc4793f1b1add59c4ad5a5ae` | `fad016c0a5be7bbf31874aee444272e02807ceab06107c942cfe7f61aee743aa` |
| mlly 1.8.2 `dist/index.cjs` | `5eff0719b8c589ec720f013928a4b61063077b6220e5babc6133d81febf8d901` | `280232f73546025e6cb314bd618b0b0798e883fa78a0cffd7d129c62cd4cd0c1` |
| mlly 1.8.2 `dist/index.mjs` | `170c8b21a62adf2803ad08305b5b7d1c0ad1c45db35136f5ffaa4572bf961656` | `a828dca32ee48caad4a6fe14c4d699ddbb5e305cc99e777d25ceef6030963612` |

正規npm ci（Node 22.23.2/npm 10.9.8）のintegrity検証を通った対象bytesからSHAを採取し、lockは変更しない。3対象を固定版・全byteSHA・唯一一致置換・補正後SHAで限定し、同一適用済みbytesだけ冪等とする。所有frontendの実dirを起点とし、任意path引数・symlinkを拒否する。全対象を事前検証し、未知版/bytesが1つでもあれば全無書込。途中write失敗や書込後SHA不一致は非0となり、postinstallの `&& nuxt prepare` へ進まない。既存DevTools補正の後に実行し、Docker依存stageのCOPYと既存FE CIのNode回帰試験にも接続する。依存版・lock・SSR/CSP/auth・監査閾値/例外は変更しない。

回帰は実MLLYのCJS/ESM公開APIでroot exports逆引き・公開guessの同一実file・主entry・非公開subpath拒否を検査する。Nitroは実配布物のtryResolveを切り出し、Windowsの実native pathと正規化済みidの一致を検査する。Linux上の逆slash合成境界試験は実Windows試験と区別する。固定bytesの唯一置換/冪等と、最終対象の未知bytes・後段版不一致・linkでの先行対象無書込も検査する。代表条件 `node/import/production` の単体回帰は実builderの全条件やbuild完走の証明ではない。標準SSR production build終端、実BE/FE/DBを使う権限横断UI、実機後のfresh3、独立検分とCIは別途必要で、単体GREENだけでは戦役完了としない（CMP-261112-0158）。

解除条件は公式の安定版Nitro/MLLYが両原因を解消し、公開API回帰とWindows標準SSR production build・実機・CIを通ること。その時にhelper/test・postinstall挿入・Docker COPYを除去し、CIの試験列挙を更新する。版やSHAの検査を緩めて更新を通す運用は行わない。

## 5. 脆弱性対応フロー

1. **検知**: Dependabot / Dependency-Check / npm audit / GitHub Security Advisory
2. **評価**: 深刻度（CVSS）・到達可能性（実際に該当コードパスを使うか）・影響範囲を判断
3. **更新**: 依存を更新（Dependabot PR を利用 or 手動）
4. **検証**: CI（ビルド・テスト）通過 + 必要に応じ実機確認
5. **記録**: 重大なものは監査ログ / セキュリティスキャン状態表示（`GITHUB_API_TOKEN` 経由、system_admin_security_scan）で可視化

---

## 6. 今後の拡張（スコープ外・意思決定済み）

- **`npm audit` の CI 扱い**: 段階導入は完了。初期は警告のみ（`--audit-level=high` を `continue-on-error` で実行・2026-05-26 導入）で開始し、high 11 件を解消したうえで **2026-06-02 に `continue-on-error` を削除してブロッキング（門番）へ昇格済み**（§4 / §4.1 参照）。2026-06-13 時点で high/critical/moderate すべて 0 件
- **Dependabot グルーピング**: 関連依存をまとめる `groups` 設定は PR 数が過多になった場合に導入する（初期は未使用で開始）

---

## 7. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-11-03 | §4.3/§4.4 の node-forge・braces 個別例外と `EXEMPTIONS` を撤去済みの現状へ改め、経緯を履歴化（CMP-261003-1229）。high/critical は無条件で拒否 |
| 2026-10-24 | 静的到達性の再評価とユーザー再承認によりnode-forgeの当該GHSAだけ10月30日UTC当日まで再承認。braces期限・対象・経路・拒否条件は不変 |
| 2026-10-17 | ユーザー再承認により§4.3/§4.4の既存2例外を10月23日UTC当日まで7日延長。対象・経路・拒否条件は不変 |
| 2026-10-06 | §4.5 に修正版依存の指定と DevTools 3.4.1 の版・配布SHAを検証する厳格互換対応、Docker連携・解除条件を記録 |
| 2026-10-02 | §4.3 に未修正版 node-forge の個別例外と利用経路・限界・UTC期限・解除条件を記録。取得失敗・不正レポート・別 high/critical を停止する門番と必須試験を追加 |
| 2026-06-13 | 残存 moderate 3 件の解消バージョンを `nuxt` 3.21.8 → **3.21.6** に修正。3.21.8 は CI（`ssr: false`）の dev サーバー起動が `No entry found in rollupOptions.input` でクラッシュするリグレッション（[nuxt#35033](https://github.com/nuxt/nuxt/issues/35033)）を含むため。両 GHSA の first patched version である 3.21.6 で `npm audit` 全レベル 0 件かつ dev/CI 起動可能を両立 |
| 2026-06-13 | high/critical 11 件の全件解消を確認し、残存 moderate 3 件も `nuxt` patch 更新で解消（`npm audit` 全レベル 0 件）。§2/§4/§4.1/§4.2/§6 のステータスを「解消済み・ブロッキング化済み」へ更新 |
| 2026-06-02 | §4.2「脆弱性解消の優先順位」を追加。セキュリティ精査結果（2026-06-02）に基づき 11 件を優先度別に分類。serialize-javascript/simple-git を最優先として対応方針を明示。high 0 件達成により frontend-ci.yml の `npm audit` を `continue-on-error` 削除でブロッキング化 |
| 2026-05-26 | 新規作成。Dependabot 導入・npm audit 方針・脆弱性対応フローを定義 |
| 2026-05-26 | frontend-ci.yml に `npm audit --audit-level=high` を警告のみ（`continue-on-error`）で追加。初回スキャン結果（high 11 件）を §4.1 に記録 |
