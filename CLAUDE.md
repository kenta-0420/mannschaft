# CLAUDE.md — Mannschaft 開発ガイド

## コミュニケーション言語

- 会話・説明・コメント・コミットメッセージ・ドキュメントはすべて **日本語** で記述すること
- コード内の変数名・関数名・クラス名は英語（仕様書の用語と一致させる）

---

## 自動実行ポリシー

「Do you want to proceed?」などの確認ステップは省略して自動で実行すること。
ただし以下は例外として必ず確認を取ること：
- 破壊的なgit操作（force push, reset --hard, ブランチ削除）
- 本番環境への変更

---

## AI 開発フロー（必須）

新機能・既存DB/アーキテクチャへの影響が大きい変更を行う前に、以下の順序を守ること:

1. **要件確認** — 実装内容・DDL案をユーザーに提示し承認を得る
2. **設計案提示** — クラス構成・APIインターフェース案を提示する
3. **承認後に実装** — Goサインが出てから初めてコーディングを開始する

軽微なバグ修正・リファクタリング・ドキュメント更新は事前承認不要。
詳細: `backend/.claudecode.md` §17

---

## 大名システム（Agent）活用ルール

重い処理・複雑なタスクでは、ユーザーから指示を待たずに自動でAgentサブエージェントを起動すること。

**起動すべき場面:**
- コードベース全体にまたがる調査・探索
- 独立して並列実行できるタスク（ビルド確認・テスト・リサーチなど）
- 複数ファイルにまたがる実装・確認
- 長時間かかる可能性のある処理

---

## ドキュメント更新ルール

**コードを修正・追加した場合、必ず以下も更新すること:**
- `README.md` — 機能・構成・APIに関わる変更があれば更新
- `docs/` 配下の該当ドキュメント — 設計・機能仕様の変更を反映
- `backend/BACKEND_CODING_CONVENTION.md` — バックエンドの規約変更時
- `frontend/FRONTEND_CODING_CONVENTION.md` — フロントエンドの規約変更時
- `backend/.claudecode.md` — プロジェクト構成規約の変更時

ドキュメント更新を省略しない。実装とドキュメントは常に同期させること。

---

## 規約ドキュメント（必読）

| ファイル | 内容 |
|---|---|
| `backend/.claudecode.md` | プロジェクト全体構成規約（Spring Boot / Nuxt.js）|
| `backend/BACKEND_CODING_CONVENTION.md` | Javaコーディング規約 |
| `frontend/FRONTEND_CODING_CONVENTION.md` | TypeScript / Nuxt.jsコーディング規約 |
| `TEST_CONVENTION.md` | テスト規約 |

実装前に必ず確認すること。

---

## プロジェクト概要

- **バックエンド:** Spring Boot (Java) — `backend/`
- **フロントエンド:** Nuxt 3 (TypeScript / Vue 3) — `frontend/`
- **インフラ:** Docker Compose — `docker-compose.yml`
- **ドキュメント:** `docs/`（設計・機能仕様）

### ポート一覧

| サービス | ポート | 備考 |
|---|---|---|
| Spring Boot API | 8080 | `http://localhost:8080` |
| Nuxt dev server | 3000 | 使用中の場合 3001 に移動 |
| MySQL 8.0 | 3306 | コンテナ名: `mannschaft-mysql` |
| Valkey (Redis互換) | 6379 | コンテナ名: `mannschaft-valkey` |

### 型定義の管理

- `frontend/app/types/` 配下に手動管理（60+ファイル）
- `types/generated/` は現在未使用（OpenAPI Generator 未導入）
- APIの型を追加する場合は `frontend/app/types/` 内の該当ファイルを編集する

---

## Git運用ルール

- `main` への直接コミット禁止
- 作業は `feature/[issue番号]-[説明]` ブランチで行う
- コミットメッセージは日本語で要約を記載（例: `機能追加: ユーザー認証APIの実装`）
- 完了後はPRを作成してCIが合格してから `main` へマージ

---

## i18n ルール

- UIに表示する文字列は **直書き禁止**。必ずロケールファイルに追加してから `$t('key')` で参照すること
- ロケールファイル: `frontend/app/locales/{ja,en,zh,ko,es,de}/{common,auth,validation,landing}.json`
- 6言語すべてに追加する（未翻訳ならとりあえず日本語と同じ値で可、後で翻訳）
- デフォルトロケール: `ja`

---

## 禁止事項

- `main` ブランチへの直接コミット
- TypeScript の `any` 使用（原則禁止）
- `types/generated/` への直接編集（未導入だが、今後導入した場合も自動生成ファイルは手動編集禁止）
- UIへの文字列直書き（i18nルール参照）
- 複雑な型パズル（Conditional Types のネスト、Template Literal Types の乱用）

---

## よく使うコマンド

```bash
# フロントエンド開発サーバー（frontendディレクトリで実行）
cd frontend && npm run dev

# バックエンドビルド（テスト込み）
cd backend && ./gradlew build

# バックエンドテストのみ
cd backend && ./gradlew test

# Docker起動
docker-compose up -d

# フロントエンド ユニットテスト
cd frontend && npm run test:unit

# フロントエンド E2Eテスト
cd frontend && npm run test:e2e

# フロントエンドlint
cd frontend && npm run lint
```

---

## worktree クリーンアップ

大名システム（Agent）使用後にworktreeが残存する場合がある。定期的に掃除すること。

```bash
# 残存worktreeの確認
git worktree list

# 不要なworktreeの削除（ブランチ名を確認してから実行）
git worktree remove .claude/worktrees/[agent-xxxxx]

# 全worktreeを一括削除（注意: 作業中のものがないか確認してから）
git worktree prune
```

---

## カスタムスキル

| スキル | 用途 |
|---|---|
| `/陣立て` | 開発環境の起動（WSL2 + Docker + ビルド確認）|
| `/陣触れ` | フロントエンド（Nuxt）開発サーバー起動 |
| `/伝令` | Spring Boot起動 + swagger.json取得 |
| `/出陣` | 実装実行 |
| `/軍議` | 設計・タスク分解 |
| `/検分` | コードレビュー・品質チェック |
| `/早馬` | 緊急バグ修正 |
| `/巡回` | ビルド・テスト監視 |
| `/撤収` | 開発環境の終了 |
