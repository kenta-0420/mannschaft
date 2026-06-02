# 本番デプロイ前 確認チェックリスト

このドキュメントは本番環境への変更デプロイ前に確認すべき項目をまとめる。
PR/migration ごとに該当セクションを追加し、デプロイ判断の根拠とする。

> **初回構築・環境変数の整備は `PRODUCTION_SETUP.md` を参照。**
> 本ドキュメントは「日々のリリース」のためのチェックリスト。

---

## 共通チェックリスト

すべての本番デプロイで必ず確認すること。

### コード品質

- [ ] CI が `main` ブランチで合格している
- [ ] ユニットテスト・統合テストがすべてパス
- [ ] テストカバレッジが基準値（バックエンド80%以上 / フロントエンド70%以上）を満たしている
- [ ] 静的解析（ESLint / Checkstyle / SpotBugs 等）エラーゼロ
- [ ] PR レビューで承認を得ている（最低1名以上）
- [ ] コミットメッセージ・PR説明が日本語で要点を明示している

### データベース変更がある場合

- [ ] migration ファイル（`backend/src/main/resources/db/migration/V*.sql`）が正しく配置されている
- [ ] migration のバージョン番号が既存と衝突していない
- [ ] ロールバック手順がドキュメントに記載されている
- [ ] 本番DBのバックアップ取得を事前手配（DBA連携 or 自動バックアップ確認）
- [ ] 想定外のデータがないか事前 `SELECT` で確認（NULL値、未知ENUM、孤児レコード等）
- [ ] 大規模UPDATE/DELETEを伴う場合、ロック影響とダウンタイムを試算
- [ ] Flyway 適用順序が正しいことを確認（依存migration が先に適用されるか）

### セキュリティ

- [ ] 機密情報（APIキー、パスワード、トークン）がコード/コミットに含まれていない
- [ ] `.env` 系ファイルが `.gitignore` 対象になっており追跡されていない
- [ ] 認証・認可ロジックの変更レビューを完了
- [ ] 依存パッケージの脆弱性スキャン（`npm audit` / `gradle dependencyCheck`）でCriticalゼロ
- [ ] 新規エンドポイントは適切な権限チェック（`@PreAuthorize` 等）が設定されている
- [ ] 個人情報を扱う処理に監査ログが仕込まれている
- [ ] `MANNSCHAFT_COOKIE_SECURE=true` が設定されている（本番は HTTPS のため必須。`docs/security/02_cookie_and_session.md`）
- [ ] 認可の既定値が deny-by-default（`.authenticated()`）で動作し、webhook 4 系統（Stripe / SES / LINE / incoming）が無認証で到達することを確認（`docs/security/01_authorization_baseline.md` §3.6）
- [ ] 主要画面でブラウザコンソールに CSP 違反が出ないことを確認（`docs/security/03_security_headers_and_csp.md`）
- [ ] Swagger UI / `/v3/api-docs` が本番で遮断されている（`docs/security/01_authorization_baseline.md` §8）
- [ ] **[本番前 Critical]** 認可基盤根治（Phase 1〜3）完了済みであること（JWT roles 修正・`@EnableMethodSecurity` 有効化）（`docs/security/03_role_authority_model.md`）
- [ ] **[本番前 Critical]** `npm audit --audit-level=high` が 0 件で終了すること（serialize-javascript RCE 等 11 件が未解消の場合は本番不可）（`docs/security/04_dependency_and_supply_chain.md` §4.2）
- [ ] CSP 違反レポート受信 EP（`POST /api/v1/security/csp-reports`）が実装済みであること（`docs/security/03_security_headers_and_csp.md`）
- [ ] Valkey がパスワード認証付きで起動していること（本番環境。`requirepass` 設定を確認）

> セキュリティ横断方針の全体は `docs/security/README.md` を参照。本番必須の環境変数は同 §5/§6 にまとまっている。

### パフォーマンス

- [ ] 大量データへの影響評価（N+1クエリ、大規模UPDATE/DELETE）を実施
- [ ] インデックス追加・削除の影響評価（既存クエリ計画への影響）
- [ ] 新規APIに想定リクエスト数とレスポンスタイム目標を設定
- [ ] キャッシュ戦略（Valkey/Redis）の見直しが必要な変更ではないか確認

### 互換性・運用

- [ ] フロントエンド型定義（`frontend/app/types/`）とバックエンドDTOが整合している
- [ ] i18n: 6言語（ja/en/zh/ko/es/de）すべてに翻訳追加済み
- [ ] フィーチャーフラグ運用が必要な場合、デフォルトOFFになっている
- [ ] ステージング環境で動作確認を完了
- [ ] 関連ドキュメント（`README.md` / `docs/` / `BACKEND_CODING_CONVENTION.md` 等）を更新

### 初回デプロイ時の追加手順

- [ ] `WEATHER_API_KEY` 環境変数が設定されている（F02.10 天気ウィジェット）
- [ ] GeoNames データを手動インポート済み（F02.10 天気ウィジェット）:
  ```bash
  cd backend && ./gradlew importPostalCodes
  ```
  ※ 未実行だと全ユーザーの天気ウィジェットが `POSTAL_CODE_NOT_FOUND` になる。約 5〜10 分かかる。

---

## PR別 個別チェック

### Security Hardening Phase 1 — セキュリティ強化 (2026-05-26)

設計: `docs/security/`。実装 PR: #1069（設計書）/ #1070（Dependabot）/ #1072（BE 認可・Cookie）/ #1086（FE CSP）= 全て main マージ済み。本番投入前に以下を確認すること。

**🔴 FE 実機 CSP 検証（未実施・本番前必須）**

nuxt-security による CSP を強制適用済み。実装時にブラウザ実機確認ができていないため、デプロイ前に dev もしくは staging で **ブラウザ DevTools の Console/Network** を開き、CSP 違反（`Refused to load/execute ...` エラー）がゼロであることを確認する。

- [ ] ログイン・主要画面の表示と hydration が正常
- [ ] PrimeVue ダイアログ/ドロップダウン等の動的スタイルが適用される
- [ ] Google Maps 埋め込み（`PublicMapEmbed.vue`）が表示される
- [ ] 画像表示（アバター/アップロード画像、R2/CDN 経由）が表示される
- [ ] PWA インストール・service worker 登録・オフライン動作が機能する
- 確認: `cd frontend && npm run dev`（または staging URL）。違反が出たら `frontend/nuxt.config.ts` の `security.headers.contentSecurityPolicy` を `docs/security/03_security_headers_and_csp.md` §2.1 に沿って調整

**🔴 環境変数（本番必須）**

- [ ] `MANNSCHAFT_COOKIE_SECURE=true`（本番 HTTPS。未設定だと Cookie に Secure 属性が付かない）

**🟠 認可 deny-by-default の疎通（staging 推奨）**

- [ ] webhook 4 系統（`POST /api/v1/webhooks/stripe`・`/api/v1/webhooks/ses`・`/api/v1/line/webhook/{x}`・`/incoming/{x}`）が無認証で到達する（認証起因の 401/403 にならない）
- [ ] 一般 API が未認証で 401、`/api/v1/system-admin/**` が一般権限で 403
- [ ] WebSocket（`/ws`）ハンドシェイクが成功する

**🟡 Swagger 本番遮断**

- [ ] Swagger UI / `/v3/api-docs` が本番で遮断されている（`docs/security/01_authorization_baseline.md` §8）

### セキュリティ精査確認項目（第2回軍議 / 2026-06-02）

設計: `docs/security/06〜09`（ビジネスロジック攻撃防止・ファイルストレージ・インシデント対応・キー管理）。精査により追加された本番要件の確認項目。

- [ ] Valkey が Sentinel または Cluster 構成で稼働していること（シングルノード禁止。`docs/security/09_key_management_and_rotation.md §8` 参照）
- [ ] `MANNSCHAFT_ENCRYPTION_KEY` が設定済みであること（AES-256-GCM 対象フィールド: `users.birth_date` 等）
- [ ] WebSocket の `setAllowedOriginPatterns` が本番ドメインに限定されていること（`"*"` 禁止。`docs/security/01_authorization_baseline.md §5` 参照）
- [ ] 退会済みユーザーの access token で API を叩いて 401 が返ること（全デバイス無効化の動作確認）
- [ ] MFA 回復後に他デバイスが 401 で弾かれることを確認（全デバイス無効化の動作確認）
- [ ] GDPR バッチを dry-run で実行し、4 テーブルの削除対象件数が期待通りであることを確認
- [ ] セキュリティインシデント管理テーブル（`security_incidents`）が作成済みであること（`docs/security/08_incident_response.md §9` — 将来実装予定）
- [ ] Presigned URL 発行エンドポイントが `Cache-Control: no-cache` ヘッダーを付与していること（`docs/security/07_file_and_storage_security.md §2.4`）
- [ ] ログイン・パスワードリセット・メール認証コードのレートリミット閾値が設計書と一致していること（ログイン 5回/分・リセット申請 3回/分・メール認証 3回/分。`docs/security/02_cookie_and_session.md §5`）

### V9.091.1 — 組織タイプ未知値の救済 (2026-04-21)

**背景**:

V9.091 が `org_type='FEDERATION'` を想定外として ALTER に失敗した。本PRで V9.091.1 を追加し、以下の処理を行う:

1. `FEDERATION` → `ASSOCIATION` へマップ
2. 新ENUM 9種以外の未知値 → `OTHER` へフォールバック
3. ALTER再実行（冪等処理）

**デプロイ前必須確認**:

- [ ] 本番DBで未知値の有無を確認:
  ```sql
  SELECT DISTINCT org_type, COUNT(*) AS cnt
  FROM organizations
  WHERE org_type NOT IN
        ('GOVERNMENT','MUNICIPALITY','COMPANY','HOSPITAL',
         'ASSOCIATION','SCHOOL','NPO','COMMUNITY','OTHER')
  GROUP BY org_type;
  ```
- [ ] `FEDERATION` 以外の未知値が見つかった場合、`ASSOCIATION` / `OTHER` へのマップ妥当性をビジネスサイドに確認
- [ ] migration 適用前に `organizations` テーブルのバックアップ取得:
  ```bash
  mysqldump -u<user> -p mannschaft organizations > organizations_backup_YYYYMMDD.sql
  ```
- [ ] ステージング環境で V9.091.1 適用 → アプリ起動確認 → 組織関連APIの疎通確認
- [ ] 本番適用後、`SELECT DISTINCT org_type FROM organizations` が新ENUM 9種以内であることを確認

**ロールバック手順**:

- migration 適用前のバックアップで `organizations` テーブルを restore
- `flyway_schema_history` から V9.091.1 行を削除:
  ```sql
  DELETE FROM flyway_schema_history WHERE version='9.091.1';
  ```
- アプリを V9.091.1 適用前のバージョンへ巻き戻し

**影響範囲**:

- バックエンド: `organizations` テーブル（`org_type` カラムのみ）
- フロントエンド: 影響なし（`types/organization.ts` は既に9種対応済）
- API: 影響なし（既存ENUM定義に合わせるだけ）

---

## ローカル開発環境での復旧手順（参考）

V9.091 失敗状態のローカルDBを修復する場合:

```bash
# 1. flyway_schema_history から失敗行を削除
docker exec mannschaft-mysql mysql -uroot -proot mannschaft \
  -e "DELETE FROM flyway_schema_history WHERE version='9.091' AND success=0;"

# 2. Spring Boot起動（V9.091リトライ + V9.091.1適用が走る）
cd backend && ./gradlew bootRun
```

**注意**: 本手順はローカル限定。本番では `DELETE` せず、必ずバックアップ取得とロールバック計画を立てること。

---

## チェックリスト運用ルール

- 新規 PR で本番影響がある場合、本ドキュメントに `## PR別 個別チェック` のサブセクションを追加する
- セクション見出しは `### V<バージョン> — <概要> (<日付>)` の形式で統一する
- デプロイ完了後、該当セクションは履歴として残す（削除しない）
- 共通チェックリストの追加・改訂は別PRで実施し、レビュー必須
