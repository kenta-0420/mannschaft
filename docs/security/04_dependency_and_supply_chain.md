# 04. 依存関係・サプライチェーン管理

> **ステータス**: 🟢 設計確定
> **実装フェーズ**: Security Hardening Phase 1
> **最終更新**: 2026-10-02
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
- **現状の CI 扱いは「ブロッキング（門番）」**: `continue-on-error` を付けずに実行し、§4.3/§4.4 の個別例外以外の high/critical が 1 件でも検出されると CI を落とす
  - **経緯**: 2026-05-26 の初回スキャンでは high 11 件（moderate 14・low 1・total 26）が存在したため、段階導入方針に従い当初は警告のみ（`continue-on-error: true`）で導入した。その後 Nuxt 系の更新で high が全て解消され、2026-06-02 に `continue-on-error` を削除してブロッキング化した
  - **現状（2026-06-13）**: high/critical のみならず moderate も含め `npm audit` は **0 件**。`--audit-level` を `critical` 等へ安易に緩めて症状を隠すことは引き続き禁止
- 既知の誤検知・修正不可能な transitive 依存は `package.json` の `overrides` または audit の除外設定で管理し、理由をコメントで残す
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

### 4.3. node-forge の期限付き個別例外（2026-10-02）

[GHSA-86w9-cpqp-85rv](https://github.com/advisories/GHSA-86w9-cpqp-85rv)（CVE-2026-85393）は RSA PKCS#1 v1.5 の署名検証の問題。2026-10-02 時点で影響範囲は `<=1.4.0`、公式 advisory の修正版は None。既存 lock の `node-forge` は 1.4.0、`listhen` は 1.10.0。`npm audit` の `fixAvailable` は Nuxt 3.15.1 への降格を提示するが、node-forge の公式修正版を意味しない。強制降格・未公開暗号パッチの取り込みは行わない。2026-06-13 の0件という記載は当時の記録であり、この新しい advisory の解消を意味しない。

§4 の「修正不可能な transitive 依存」の管理規則に基づき、`frontend/scripts/audit-with-exemption.mjs` で **当該 URL・パッケージ・high・影響範囲・間接依存・lock の 1.4.0** が一致するものだけを一時除外する。実監査の high 7 パッケージ（`node-forge` / `listhen` / `nitropack` / `@nuxt/cli` / `@nuxt/nitro-server` / `@nuxt/vite-builder` / `nuxt`）に限定する。Nuxt の循環を含む `via` の参照先も追い、末端 advisory を全て検証する。別 high/critical、未知の high 消費者、参照欠落、末端のない high 循環、不正 JSON・集計・到達 advisory より低い依存の深刻度、取得失敗は CI を落とす。Node 標準テストを CI の必須ステップとして実行する。

到達経路の根拠: `listhen` 1.10.0 の `dist/shared/listhen.DmCHmEQ1.cjs` は forge を読み、`resolveCertificate` で RSA 鍵生成と証明書署名を行い、HTTPS オプション時に呼ぶ。当該署名検証の `.verify` 呼び出しはこの経路に無く、`frontend/nuxt.config.ts` の `devServer` に HTTPS 設定は無い。アプリ・サーバーに forge/RSA の直接利用も確認されなかった。Nuxt は `dependencies` にあるため「devOnly」とは扱わず、**本番 `.output` からの除外は未実測**。脆弱性そのものが直ったという判断ではない。

有効期限は **2026-10-16 UTC 当日まで（2026-10-17T00:00:00Z 以降は当該例外を拒否）**。解除管理は `docs/task-list.md` の CMP-261002-1135。公式修正版を導入するか listhen から依存が撤去されたら、例外を削除して通常の `npm audit --audit-level=high` に戻す。期限以前でも、RSA 署名検証の利用追加・新しい消費者・依存の直接化があれば例外を削除し、到達可能性を再評価する。期限延長を自動では行わない。

### 4.4. braces の期限付き個別例外（2026-10-03）

[GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm) は `braces` の問題。影響範囲は `<=3.0.3`（全公開版）で、2026-10-03 時点で修正版は未公開（`npm audit` の `fixAvailable` も false）。既存 lock の `braces` は 3.0.3。`chokidar` / `micromatch` / `fast-glob` を経由し、`nuxt` / `@nuxtjs/i18n` / `@nuxtjs/tailwindcss` / `@primevue/nuxt-module` などビルド時ツールへ波及して FE CI の Install ジョブが赤になった。

§4.3 と同じ仕組みで、`frontend/scripts/audit-with-exemption.mjs` の `EXEMPTIONS` 表に **GHSA 単位で名指し**して一時除外する（パッケージ名での包括除外や `--audit-level` の緩和はしない）。**当該 URL・パッケージ・high・影響範囲 `<=3.0.3`・間接依存・lock の 3.0.3** が一致するものだけを通し、波及先は実監査で確認した経路（`braces` / `micromatch` / `chokidar` / `fast-glob` / `globby` / `tailwindcss` / `unplugin-vue-components` / `unplugin-vue-router` / `@intlify/unplugin-vue-i18n` / `@nuxtjs/i18n` / `@nuxtjs/tailwindcss` / `@primevue/nuxt-module` / `nitropack` / `@nuxt/nitro-server` / `@nuxt/vite-builder` / `nuxt`）に限定する。除外ごとに許可パッケージ集合を持つため、node-forge の消費者が braces を、またはその逆を流用することはできない。fail closed の検証（取得失敗・不正レポート・深刻度の過小報告・未知の high 消費者の拒否）は §4.3 と共通。

確認した範囲: `braces` は glob の波括弧展開に使われ、lock 上の直接消費者は `micromatch` と `chokidar` の2コピー。アプリの `app/server/scripts/tests` の ts/js/mjs/cjs に対する直接 glob 呼出し検索は0件で、確認した i18n の設定ファイル列挙・Tailwind content・PWA globPatterns は固定値だった。dbaa 本番 `.output/server` の2537テキストファイルでは静的 token・package 検出は0件。ただし14リンク・4バイナリは除外され、minify・動的参照を含む非到達は未証明。Nuxt は `dependencies` にあり、dev-only や本番利用者入力からの非到達を断定しない。保存済み実監査は high 19エントリ・末端 advisory 2件であり、脆弱性そのものが直ったという判断ではない。

braces 側は上記16パッケージについて実監査の既知 `nodes` と、braces advisory に到達する `via` の依存辺だけを許容する。末端は `node_modules/braces` に限定し lock の3.0.3を確認する。既知名でも未知ノード・経路付替えは拒否する。Nuxt の既知循環を許容し、forge だけに到達する混在辺は braces の表で判定しない。消費者全バージョンや lock 全体の固定は行わない。

有効期限は **2026-10-16 UTC 当日まで（2026-10-17T00:00:00Z 以降は当該例外を拒否）**。解除管理は既存 CMP-261003-1229 に集約する。解除条件: `braces` の修正版が公開されたら lock を引き上げ、除外を削除して通常の `npm audit --audit-level=high` に戻す。期限延長を自動では行わない。

### 4.5. simple-git 等の修正版と DevTools 無効化（2026-10-07）

新たに検出された [simple-git](https://github.com/advisories/GHSA-x6jw-m9v5-85vh) / [argv-parser](https://github.com/advisories/GHSA-v5rq-49vh-5v5c) の critical は例外に追加せず、simple-git 4.0.2（argv-parser 2.0.1）へ更新する。[Vue SSR](https://github.com/advisories/GHSA-g2v6-rqmx-r4w6) 3.5.43、[seroval](https://github.com/advisories/GHSA-jp82-f5mq-hwhp) 1.6.8、[source-map-js](https://github.com/advisories/GHSA-68fv-2mgg-jv7q) 1.2.2、[postcss-selector-parser](https://github.com/advisories/GHSA-rj75-hqrm-r3gf) 7.1.6 も既知 advisory の影響範囲外へ更新する。Vue compiler-sfc の実依存要求を満たすため、正規 npm resolver の PostCSS 8.5.29 / nanoid 3.3.20 の node を同期し、既存 platform binding は保持する。

DevTools 3.4.1 / 3.4.2 は simple-git の default import を使い、4.0.2 の named export と互換性がないため、全環境で `devtools.enabled: false` とする。installed Nuxt の静的条件分岐を確認したが、修正後の実 dev 起動・CI・本番生成は別途検証が必要であり、本記録だけで合格とは扱わない。既存 node-forge / braces の期限付き個別例外と経路制限を維持し、critical の例外追加や audit 閾値緩和は行わない。

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
| 2026-10-02 | §4.3 に未修正版 node-forge の個別例外と利用経路・限界・UTC期限・解除条件を記録。取得失敗・不正レポート・別 high/critical を停止する門番と必須試験を追加 |
| 2026-06-13 | 残存 moderate 3 件の解消バージョンを `nuxt` 3.21.8 → **3.21.6** に修正。3.21.8 は CI（`ssr: false`）の dev サーバー起動が `No entry found in rollupOptions.input` でクラッシュするリグレッション（[nuxt#35033](https://github.com/nuxt/nuxt/issues/35033)）を含むため。両 GHSA の first patched version である 3.21.6 で `npm audit` 全レベル 0 件かつ dev/CI 起動可能を両立 |
| 2026-06-13 | high/critical 11 件の全件解消を確認し、残存 moderate 3 件も `nuxt` patch 更新で解消（`npm audit` 全レベル 0 件）。§2/§4/§4.1/§4.2/§6 のステータスを「解消済み・ブロッキング化済み」へ更新 |
| 2026-06-02 | §4.2「脆弱性解消の優先順位」を追加。セキュリティ精査結果（2026-06-02）に基づき 11 件を優先度別に分類。serialize-javascript/simple-git を最優先として対応方針を明示。high 0 件達成により frontend-ci.yml の `npm audit` を `continue-on-error` 削除でブロッキング化 |
| 2026-05-26 | 新規作成。Dependabot 導入・npm audit 方針・脆弱性対応フローを定義 |
| 2026-05-26 | frontend-ci.yml に `npm audit --audit-level=high` を警告のみ（`continue-on-error`）で追加。初回スキャン結果（high 11 件）を §4.1 に記録 |
