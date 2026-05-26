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
| フロント `npm audit` の CI 組み込み | 未導入 | §4 で方針定義 |

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

- フロントエンド CI（`frontend-ci.yml`）に `npm audit --audit-level=high` ステップの追加を検討する
- 既知の誤検知・修正不可能な transitive 依存は `package.json` の `overrides` または audit の除外設定で管理し、理由をコメントで残す
- Dependabot と役割が重複するが、audit は「CI を落とす門番」、Dependabot は「更新 PR の自動生成」と位置づけ、両輪で運用する

---

## 5. 脆弱性対応フロー

1. **検知**: Dependabot / Dependency-Check / npm audit / GitHub Security Advisory
2. **評価**: 深刻度（CVSS）・到達可能性（実際に該当コードパスを使うか）・影響範囲を判断
3. **更新**: 依存を更新（Dependabot PR を利用 or 手動）
4. **検証**: CI（ビルド・テスト）通過 + 必要に応じ実機確認
5. **記録**: 重大なものは監査ログ / セキュリティスキャン状態表示（`GITHUB_API_TOKEN` 経由、system_admin_security_scan）で可視化

---

## 6. 今後の拡張（スコープ外・意思決定済み）

- **`npm audit` の CI 扱い**: 初期は **警告のみ（非ブロッキング）** とする（`--audit-level=high` を `continue-on-error` で実行）。誤検知頻度を運用で観測し、安定したら `high` 以上をブロッキング条件へ昇格する（API ドリフトチェックの段階導入と同方針）
- **Dependabot グルーピング**: 関連依存をまとめる `groups` 設定は PR 数が過多になった場合に導入する（初期は未使用で開始）

---

## 7. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-05-26 | 新規作成。Dependabot 導入・npm audit 方針・脆弱性対応フローを定義 |
