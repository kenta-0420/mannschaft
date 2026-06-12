# 04. 依存関係・サプライチェーン管理

> **ステータス**: 🟢 設計確定
> **実装フェーズ**: Security Hardening Phase 1
> **最終更新**: 2026-05-26
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
- **現状の CI 扱いは「ブロッキング（門番）」**: `continue-on-error` を付けずに実行し、high/critical の脆弱性が 1 件でも検出されると CI を落とす
  - **経緯**: 2026-05-26 の初回スキャンでは high 11 件（moderate 14・low 1・total 26）が存在したため、段階導入方針に従い当初は警告のみ（`continue-on-error: true`）で導入した。その後 Nuxt 系の更新で high が全て解消され、2026-06-02 に `continue-on-error` を削除してブロッキング化した
  - **現状（2026-06-13）**: high/critical のみならず moderate も含め `npm audit` は **0 件**。`--audit-level` を `critical` 等へ安易に緩めて症状を隠すことは引き続き禁止
- 既知の誤検知・修正不可能な transitive 依存は `package.json` の `overrides` または audit の除外設定で管理し、理由をコメントで残す
- Dependabot と役割が重複するが、audit は「（脆弱性解消後は）CI を落とす門番」、Dependabot は「更新 PR の自動生成」と位置づけ、両輪で運用する

### 4.1. 初回スキャン結果（2026-05-26）→ 全件解消済み（2026-06-13）

> **ステータス（2026-06-13）**: 下表の high 11 件は **全て解消済み**。Nuxt 系の継続的な更新（serialize-javascript 7.0.5 / node-forge 1.4.0 / vite 7.3.5 / lodash 4.18.1 / h3 1.15.11 / simple-git 3.36.0 等の安全版への引き上げ）により high/critical はゼロになった。残っていた moderate 3 件（`@nuxt/nitro-server` / `@nuxt/vite-builder` 由来の `__nuxt_island` shared-cache poisoning / route middleware バイパス）も `nuxt` を 3.21.2 → 3.21.8 へ patch 更新して解消し、`npm audit`（全レベル）は **0 件** となった。下表は当時の記録として保持する。

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
| 2026-06-13 | high/critical 11 件の全件解消を確認し、残存 moderate 3 件も `nuxt` 3.21.2→3.21.8 patch 更新で解消（`npm audit` 全レベル 0 件）。§2/§4/§4.1/§4.2/§6 のステータスを「解消済み・ブロッキング化済み」へ更新 |
| 2026-06-02 | §4.2「脆弱性解消の優先順位」を追加。セキュリティ精査結果（2026-06-02）に基づき 11 件を優先度別に分類。serialize-javascript/simple-git を最優先として対応方針を明示。high 0 件達成により frontend-ci.yml の `npm audit` を `continue-on-error` 削除でブロッキング化 |
| 2026-05-26 | 新規作成。Dependabot 導入・npm audit 方針・脆弱性対応フローを定義 |
| 2026-05-26 | frontend-ci.yml に `npm audit --audit-level=high` を警告のみ（`continue-on-error`）で追加。初回スキャン結果（high 11 件）を §4.1 に記録 |
