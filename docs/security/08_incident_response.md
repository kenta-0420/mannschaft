# 08. インシデント対応フロー

> **ステータス**: 🟡 設計確定（手順整備未完了）
> **実装フェーズ**: Security Hardening Phase 3
> **最終更新**: 2026-06-02
> **関連ドキュメント**: [README](README.md), [09 キー管理・ローテーション](09_key_management_and_rotation.md), F10.3 監査ログ, F12.3 GDPR

---

## 1. インシデント種別定義

| 種別 | 例 | 深刻度 |
|---|---|---|
| **S1: データ漏洩** | 個人情報（氏名・メール・生年月日）が外部に流出 | 最高（Critical） |
| **S2: 認証突破** | 攻撃者が管理者権限を取得、または大規模アカウント乗っ取り | 最高（Critical） |
| **S3: DDoS** | サービス応答不能（5 分以上のダウン） | 高（High） |
| **S4: 内部不正** | 管理者が不正にデータを持ち出し/改ざん | 高（High） |
| **S5: 脆弱性報告** | 外部研究者または自動スキャンによる脆弱性発見 | 中〜高（Medium-High） |
| **S6: サプライチェーン** | 使用ライブラリに既知 CVE が発見 | 中（Medium） |

---

## 2. 初動対応フロー

```
検知（監査ログアラート / ユーザー報告 / 自動スキャン / 外部報告）
  ↓
[15 分以内] 担当者がインシデント種別・深刻度を評価
  ↓
  ├──[S1/S2 の場合] 即時封じ込め（§3 参照）
  │      ↓
  │   [72 時間以内] GDPR Article 33 監督機関通知（§5 参照）
  │      ↓
  │   [72 時間以内] 被害ユーザーへの通知（§6 参照・該当する場合）
  │
  ├──[S3 の場合] インフラ層での緩和（Cloudflare DDoS 防護有効化 / WAF ルール強化）
  │
  ├──[S4 の場合] 監査ログ保全 → 当該管理者アカウントをロック → 法的対応検討
  │
  └──[S5/S6 の場合] 影響範囲評価 → パッチ適用計画（§7 参照）
  ↓
証拠保全（ログのアーカイブ・変更禁止ロック）
  ↓
根本原因分析（RCA: Root Cause Analysis）
  ↓
恒久対策実施
  ↓
事後検証レポート作成（§8 参照）
```

---

## 3. 封じ込め手順

### 3.1 全ユーザー強制ログアウト（JTI 一括無効化）

全 access token を即時無効化する最終手段。S1/S2 で JTI 漏洩・認証突破の可能性がある場合に使用する。

```sql
-- 方式A: 全ユーザーの user_invalidated_at を現在時刻に更新
-- JwtAuthenticationFilter で iat < user_invalidated_at の token を 401 で弾く
UPDATE user_security_settings
SET force_logout_at = NOW(), updated_at = NOW();
```

または Spring Boot の Valkey コマンド経由:

```
// 全ユーザーのトークンを即時無効化するタイムスタンプをセット
SET mannschaft:all_tokens_invalidated_at:{unix_timestamp} 1 EX 86400
```

> 実装時には `JwtAuthenticationFilter` がグローバルタイムスタンプを参照するロジックを追加すること。現状は per-user の `user_invalidated_at` のみ実装済み（`AuthTokenService.java:199-203`）。

### 3.2 特定アカウントの即時ロック

```
PATCH /api/v1/system-admin/users/{id}/lock
```

`user.status = 'FROZEN'` に変更し、次回 JWT 検証時に 423 を返す。

### 3.3 漏洩した秘密鍵のローテーション

[09_key_management_and_rotation.md §3](09_key_management_and_rotation.md) の緊急ローテーション手順に従う。

### 3.4 影響を受けたエンドポイントの一時的無効化

```java
// SecurityConfig で当該エンドポイントを一時的にブロック
.requestMatchers("/api/v1/vulnerable-endpoint/**").denyAll()

// またはメンテナンスモード設定（全エンドポイント → 503）
```

### 3.5 ログの保全

インシデント対応開始と同時に以下を実施する:

1. 関連するアプリケーションログ（Spring Boot / Nginx / Cloudflare）をアーカイブ
2. 監査ログ（`audit_logs` テーブル）のスナップショットを別ストレージに退避
3. 保全したログへの書き込み権限を削除（証拠改ざん防止）

---

## 4. 深刻度別の対応タイムライン

| 深刻度 | 検知後の初動 | 封じ込め完了目標 | 経営報告 |
|---|---|---|---|
| Critical（S1/S2） | 15 分以内 | 2 時間以内 | 即時（電話） |
| High（S3/S4） | 30 分以内 | 6 時間以内 | 4 時間以内 |
| Medium（S5/S6） | 24 時間以内 | 1 週間以内 | 翌営業日 |

---

## 5. GDPR Article 33 対応（72 時間以内の監督機関通知）

### 5.1 通知が必要な条件

個人データの**漏洩・破壊・変更・不正アクセス**が発生した場合（推測・疑いの段階でも通知義務が発生することがある）。

### 5.2 通知先（日本）

**個人情報保護委員会（PPC）**
- Web 届出: https://www.ppc.go.jp/personal/legal/leakage/
- 電話: 03-6457-9849

### 5.3 通知内容（Article 33.3 要件）

| 項目 | 内容 |
|---|---|
| 漏洩の性質 | どのような種類のデータが、どのような方法で漏洩したか |
| 影響を受けたデータ主体の数・種類 | 概算人数・ユーザー属性（メール・氏名・生年月日等） |
| 連絡先担当者 | DPO または担当者の氏名・連絡先 |
| 発生しうる結果 | 当該漏洩によりデータ主体が受ける可能性のある影響 |
| 講じた・予定する対策 | 封じ込め措置・再発防止策 |

### 5.4 72 時間以内に通知できない場合

遅延の理由を説明した上で段階的に通知する（Article 33.1 但し書き）。

---

## 6. 被害を受けたユーザーへの通知（Article 34）

### 6.1 通知義務が生じる条件

「高いリスクを生じさせる可能性」がある場合（氏名・メール・生年月日・パスワードハッシュ等の流出）。

### 6.2 通知タイムライン

- **S1 発生時**: 影響を受けたユーザーに **72 時間以内** にメールで通知

### 6.3 通知内容

- 何のデータが・いつ・どのように漏洩したか
- ユーザーが取るべきアクション（パスワード変更・二要素認証の有効化等）
- 問い合わせ先

---

## 7. 脆弱性報告の受付（Vulnerability Disclosure Program）

### 7.1 受付窓口

- **連絡先**: セキュリティ担当メールアドレス（本番運用開始前に設定すること）
- **PGP 公開鍵**: 機密情報を含む報告はPGP暗号化を推奨（鍵は公式サイトに掲載）

### 7.2 対応 SLA

| 段階 | タイムライン |
|---|---|
| 受信確認 | 報告受付から **5 営業日以内** に初回返信 |
| 影響評価 | 初回返信から **14 営業日以内** |
| 修正完了 | 重大度 Critical: 7 日以内 / High: 30 日以内 / Medium: 90 日以内 |
| 報告者への通知 | 修正完了後に通知 |

### 7.3 報告者へのインセンティブ

- クレジット掲載（希望する場合）: 修正リリースノートに氏名/ハンドル名を記載
- バグバウンティプログラム: 将来的に導入を検討

### 7.4 スコープ外（報告不要）

- 既に公開されている脆弱性（CVE 報告済み）
- 物理的なサーバーへのアクセスを前提とする攻撃
- ソーシャルエンジニアリング
- DoS 攻撃を実際に実行することによる脆弱性「実証」

---

## 8. 事後検証レポート（Post-Incident Review）

### 8.1 目的

同種のインシデントの再発を防ぐため、原因・対応・改善策を文書化する。

### 8.2 テンプレート

```markdown
# インシデント事後検証レポート

## 基本情報
- インシデント ID: INC-{YYYYMMDD}-{連番}
- 種別: S{1-6}
- 発生日時: 
- 検知日時: 
- 封じ込め完了日時:
- 影響範囲:

## タイムライン
- {日時}: {出来事}

## 根本原因
（"5 Whys" または類似の根本原因分析手法を適用）

## 影響
- 影響を受けたユーザー数:
- データの種類:
- 推定被害:

## 対応内容
- 即時対応:
- 恒久対応:

## 改善アクション
| アクション | 担当 | 期限 | ステータス |
|---|---|---|---|
| ... | ... | ... | ... |

## 学習事項
（他のシステム・チームへの横展開が必要な学習事項）
```

---

## 9. SecurityIncidentLog エンティティ（実装設計）

GDPR Article 33 の72時間通知を適切に管理するため、
以下のエンティティを実装すること（未実装の場合）:

### 9.1 DB スキーマ

```sql
CREATE TABLE security_incidents (
    id               BINARY(16)   NOT NULL COMMENT 'UUIDv7',
    incident_type    VARCHAR(50)  NOT NULL,  -- DATA_BREACH / AUTH_COMPROMISE / DDOS / SUPPLY_CHAIN
    severity         VARCHAR(20)  NOT NULL,  -- CRITICAL / HIGH / MEDIUM / LOW
    detected_at      DATETIME(6)  NOT NULL,  -- 検知時刻（72時間カウント開始）
    records_affected INT,                    -- 影響を受けたレコード数（概算）
    description      TEXT,
    status           VARCHAR(20)  NOT NULL DEFAULT 'OPEN',  -- OPEN / INVESTIGATING / CONTAINED / CLOSED
    notified_dpa_at  DATETIME(6),            -- DPA（監督機関）への通知時刻
    resolved_at      DATETIME(6),
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
);
```

### 9.2 72時間タイムリミット管理

- `detected_at + 70 hours` のタイミングで SYSTEM_ADMIN にアラートメール送信
- `status = 'OPEN'` かつ `notified_dpa_at IS NULL` のインシデントを
  SYSTEM_ADMIN ダッシュボードの最上部に警告表示する

### 9.3 API

- `POST /api/v1/system-admin/security-incidents` — インシデント登録（SYSTEM_ADMIN のみ）
- `GET /api/v1/system-admin/security-incidents` — 一覧（OPEN 優先ソート）
- `PATCH /api/v1/system-admin/security-incidents/{id}` — ステータス更新・DPA通知記録

---

## 10. 連絡体制

> 本番運用開始前に以下を設定すること。

| 役割 | 担当者 | 連絡先 |
|---|---|---|
| セキュリティ責任者 | （設定すること） | （設定すること） |
| DPO（Data Protection Officer） | （設定すること） | （設定すること） |
| インフラ担当 | （設定すること） | （設定すること） |
| 開発リード | （設定すること） | （設定すること） |

---

## 11. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-06-02 | 新規作成。インシデント種別定義・初動対応フロー・封じ込め手順・GDPR Article 33 対応・脆弱性報告受付・事後検証レポートテンプレートを定義 |
| 2026-06-02 | §9 SecurityIncidentLog エンティティ設計を追加（GDPR Art.33 72時間通知管理用 DB スキーマ・70時間アラート・SYSTEM_ADMIN ダッシュボード警告・管理 API 3本） |
