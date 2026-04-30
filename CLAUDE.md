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

## 大名システム（Agent）活用ルール **【必須】**

### 役割定義（詳細は `backend/.claudecode.md §28`）

| 役職 | 実体 | 責務 |
|---|---|---|
| **マスター** | ユーザー（人間） | 大方針・最終承認 |
| **殿** | メイン Claude（このセッション） | 軍議主催・家老への指示。**直接コーディング禁止** |
| **家老** | `Explore`/`Plan` サブエージェント | 偵察・設計・タスク分解 |
| **足軽** | `Agent(isolation:"worktree")` | 個別タスクの実装・テスト |

### 正しい実行フロー

1. **`/軍議`** — 家老に偵察・設計を命じ、陣立て書をマスターに上奏
2. **マスターの御裁可**（「よきにはからえ」など）を得てから出陣
3. **`/出陣`** — 足軽を `Agent(isolation:"worktree")` で起動、実装
4. **`/検分`** — 成果物のレビュー

**開発作業（実装・修正・調査・テスト）は原則として必ず大名システム（Agent サブエージェント）経由で実行すること。** メインの作業ディレクトリ `D:\mannschaft` で直接コーディング・コミットする運用は禁止。

### なぜ必須なのか

- 大名システムは内部で `git worktree add .claude/worktrees/agent-xxxxx` を使い、**物理的に別ディレクトリ** で agent を起動する。これにより複数の Claude セッションが並列に動いても HEAD 衝突しない。
- メインディレクトリで `git checkout` して作業すると、別の Claude セッションが同じディレクトリで `git checkout` した瞬間に HEAD が引っ張られ、作業中のファイルが消える / コミット前の修正が stash 待避される事故が発生する（2026-04-08 に実際に発生・記録済み）。
- worktree 隔離なら、別 Claude が何をしようとそちらのディレクトリは無傷。安心して長時間タスクを走らせられる。

### 起動すべき場面

- **新機能の実装・大規模リファクタ**（複数ファイル・長時間にわたる作業すべて）
- **コードベース全体にまたがる調査・探索**
- **独立して並列実行できるタスク**（ビルド確認・テスト・リサーチなど）
- **E2E テスト実行・修正**（dev サーバー起動を伴うもの）
- **長時間かかる可能性のある処理**

### 例外（メインディレクトリで直接やってよい作業）

- ユーザーとの軽い対話・質問への回答
- 1〜2ファイル限定の即時的な修正で、コミットせず確認だけする場合
- ドキュメントの軽微な追記
- worktree のクリーンアップなど git 管理操作そのもの

### 並列セッションの作法

- 新機能・大規模実装を開始する前に、**着手前に必ず専用ブランチを `git worktree add` で物理ディレクトリごと隔離** すること
- 同じ作業ディレクトリで複数の Claude セッションを動かす運用は **絶対に避ける**（HEAD 衝突で作業が破壊される）
- worktree 内で commit が完了したら、メインリポジトリに `git merge` でマージする

詳細: `~/.claude/projects/D--mannschaft/memory/feedback_branch_isolation.md`

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
- 大規模作業は `git worktree add` で物理的にディレクトリを隔離してから着手する（大名システム必須ルール参照）
- コミットメッセージは日本語で要約を記載（例: `機能追加: ユーザー認証APIの実装`）
- 完了後はPRを作成してCIが合格してから `main` へマージ

---

## i18n ルール

- UIに表示する文字列は **直書き禁止**。必ずロケールファイルに追加してから `$t('key')` で参照すること
- ロケールファイル: `frontend/app/locales/{ja,en,zh,ko,es,de}/{common,auth,validation,landing}.json`
- 6言語すべてに追加する（未翻訳ならとりあえず日本語と同じ値で可、後で翻訳）
- デフォルトロケール: `ja`

---

## 障害対応の原則 — 根治治療を徹底すること **【必須】**

バグや障害に遭遇した際、**対処療法（症状を隠す・エラーを握りつぶす・一時的な回避策）で切り抜けることを禁止する。** 必ず根本原因を特定し、根治治療を行うこと。

### 具体的なルール

1. **原因の連鎖を最後まで追う** — 表面的なエラーメッセージだけで判断しない。「なぜそのエラーが出るのか」を連鎖的に掘り下げ、真の原因にたどり着くまで調査を止めない
2. **症状を隠さない** — `.catch(() => {})` でエラーを握りつぶす、`try-catch` で例外を飲み込む、フラグで分岐を回避するなどの対処療法は禁止。壊れているなら壊れていると正直に報告し、修正する
3. **未実装は未実装として対処する** — フロントエンドが呼んでいるのにバックエンドAPIが存在しない場合、エラーハンドリングで誤魔化すのではなく、APIを実装する
4. **Flyway マイグレーション失敗は必ず原因を修正する** — `flyway repair` だけで済ませず、SQL のバグ自体を修正してから再適用する
5. **ビルドエラーは根本から直す** — キャッシュクリアや再起動だけで「たまたま通った」状態を正とせず、なぜエラーが出たかを理解して修正する

### 実例（2026-04-10 発生）

「ログイン後にloadingで止まる」という報告に対して:
- **対処療法（NG）**: フロントエンドのタイムアウトを追加して loading を強制解除する
- **根治治療（実施）**: Playwright で再現 → バックエンドAPIが応答なし → Spring Boot ログで Flyway V3.120 の SQL バグ（NOT NULL + ON DELETE SET NULL の矛盾）を発見 → DDL 修正 → さらに未実装の `/api/v1/mentions` エンドポイントも発見 → バックエンド実装を追加

---

## 禁止事項

- `main` ブランチへの直接コミット
- **メインの作業ディレクトリ `D:\mannschaft` で大規模実装を直接行うこと**（並列セッションとの HEAD 衝突を防ぐため、必ず大名システム経由で worktree 隔離する）
- **対処療法でバグを切り抜けること**（障害対応の原則を参照）
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

## worktree クリーンアップ **【定期実施】**

大名システム（Agent）の worktree は **作業完了後に必ず掃除する**。放置すると以下の問題が起きる:

- ディスク容量の圧迫（worktree 1 つあたり数十 MB〜数百 MB、`.gradle`/`node_modules` キャッシュを含むとさらに膨らむ）
- `git worktree list` の出力が肥大化して状況把握が困難になる
- IDE のファイルウォッチャー / インデクサが大量のファイルを舐めて遅くなる
- `worktree-agent-*` という孤立ブランチが大量に積もる

### Claude が守るべきタイミング

| タイミング | 何をするか |
|---|---|
| **大名 (Agent) 起動完了直後** | その agent の commit を本リポに統合 (cherry-pick / merge) → 直ちに対応する worktree を `git worktree remove --force` で削除 |
| **セッション開始時** | `git worktree list` を確認し、自分が作ったものでない `agent-*` worktree が残っていれば原因を確認のうえ削除を提案する |
| **セッション終了時** | 自分が起動した agent の worktree がすべて消えていることを確認 |
| **週次** | 全 `worktree-agent-*` ブランチと残骸ディレクトリを一括削除 |

### コマンド集

```bash
# 残存worktreeの確認
git worktree list

# 個別削除（コミットを取り込み済みであることを確認してから）
git worktree remove --force .claude/worktrees/agent-xxxxx
git branch -D worktree-agent-xxxxx

# 全 agent worktree を一括削除（変更が残っていても強制削除する）
for wt in $(git worktree list --porcelain | grep "^worktree" | grep "agent-" | awk '{print $2}'); do
  git worktree remove --force "$wt"
done

# 孤立した worktree-agent-* ブランチを一括削除
git branch -D $(git branch | grep "worktree-agent-" | tr -d ' ')

# stale entries（既にディレクトリが消えた worktree のメタ情報）を削除
git worktree prune

# .claude/worktrees/ 配下に空ディレクトリが残っていれば削除
rmdir .claude/worktrees/agent-* 2>/dev/null || true
```

### 注意事項

- **進行中の agent の worktree は絶対に削除しない**。`git worktree list` の出力で他に動いている agent がないか確認してから削除すること
- 削除前に **当該 worktree の変更がメインリポに統合されているか** を必ず確認する。未マージの commit を消すと作業が失われる
- メインリポ (`D:\mannschaft`) を間違って削除しないこと（`grep "agent-"` で必ず agent の worktree のみに絞る）

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
