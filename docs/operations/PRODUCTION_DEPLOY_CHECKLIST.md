# 本番デプロイ前 確認チェックリスト

このドキュメントは本番環境への変更デプロイ前に確認すべき項目をまとめる。
PR/migration ごとに該当セクションを追加し、デプロイ判断の根拠とする。

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

---

## PR別 個別チェック

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
