# CLAUDE.md — Mannschaft 開発ガイド

## コミュニケーション言語

- 会話・説明・コメント・コミットメッセージ・ドキュメントはすべて **日本語** で記述すること
- コード内の変数名・関数名・クラス名は英語（仕様書の用語と一致させる）

---

## 作業開始前の必須確認

コーディングを始める前に必ず `pwd`（または `git worktree list`）で自分の作業ディレクトリを確認すること。

- ✅ `.claude/worktrees/agent-xxxxx/` 配下 → そのまま作業してよい
- ❌ `C:/Claude/mannschaft` 直下 → **作業を中断し、殿に報告する**
  - ただし「大名システム活用ルール」の**例外**節（軽い対話への回答・1〜2ファイル限定でコミットしない確認・ドキュメント軽微追記・worktree管理操作）に該当する場合はこの限りではない

自分が並行出陣中のエージェントかどうかに関わらず、本陣で直接コーディング・コミットすることは禁止。

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

1. **`/軍議`** — 家老に偵察・設計を命じ、陣立て書＋**受け入れ条件**をマスターに上奏
2. **マスターの御裁可**（「よきにはからえ」など）を得てから出陣
3. **`/試練`** — 受け入れ条件から**失敗するテスト（red）を先行作成**（BEドメインUT・API契約テスト。実装漏れ根治のため実装より前に書く）
4. **`/出陣`** — 足軽を `Agent(isolation:"worktree")` で起動、試練を green 化（実装）
5. **`/検分`** — 成果物のレビュー＋**受け入れ条件↔テスト↔実装のトレーサビリティ照合**

> 開発順序の基本形: **設計書 → 軍議〔受け入れ条件〕→ 試練（red）→ 出陣（green）→ 検分 → E2E**。BE/API はテスト先行（`feedback_test_first_be_api`）。FE/設計が薄い機能は従来順に戻してよい。

**開発作業（実装・修正・調査・テスト）は原則として必ず大名システム（Agent サブエージェント）経由で実行すること。** メインの作業ディレクトリ `C:\Claude\mannschaft` で直接コーディング・コミットする運用は禁止。

### Dynamic Workflows との連携（出陣・検分の高速化／コスト最適化）

`/出陣`・`/検分` は **Dynamic Workflows（Workflow ツール）** で「足軽の並列起動」を決定論的に表現できる。役割と承認ゲート（§28.1〜28.4）は維持したまま、フェーズの中身だけ差し替える方針:

- **軍議・早馬は対話のまま**（Workflow は自走して御裁可ゲートを内包できない）
- **出陣 = `pipeline(実装, ビルド)` + worktree**、**検分 = `pipeline(次元別レビュー, 敵対的検証)`**
- **モデル/effort をスクリプトで固定** — 機械的タスクは sonnet/haiku・低 effort、難所と敵対的検証のみ opus・high（コスト削減であってトークン削減ではない）
- **Workflow はオプトイン**。スキル経由かマスターの明示依頼時のみ起動し、コミット/マージは Workflow の外で `gh` で行う

設計詳細・参考スクリプト: [`docs/development/daimyo_workflow_migration.md`](docs/development/daimyo_workflow_migration.md)（`backend/.claudecode.md §28.9`）。

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

詳細: `~/.claude/projects/C--Claude-mannschaft/memory/feedback_branch_isolation.md`

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
| `docs/security/README.md` | セキュリティ横断方針（認可基盤・Cookie/セッション・CSP/ヘッダー・依存管理・インジェクション）|

実装前に必ず確認すること。**認可・Cookie・セキュリティヘッダー・依存関係に関わる変更時は `docs/security/` を参照**すること。

---

## プロジェクト概要

- **バックエンド:** Spring Boot (Java) — `backend/`
- **フロントエンド:** Nuxt 3 (TypeScript / Vue 3) — `frontend/`
- **インフラ:** Docker Compose — `docker-compose.yml`
- **ドキュメント:** `docs/`（設計・機能仕様）

### ポート一覧

| サービス | ポート | 備考 |
|---|---|---|
| Spring Boot API | 8080 | `http://localhost:8080`（本陣・正本の dev サーバー）|
| Nuxt dev server | 3000 | 使用中の場合 3001 に移動 |
| MySQL 8.0 | 3306 | コンテナ名: `mannschaft-mysql` |
| Valkey (Redis互換) | 6379 | コンテナ名: `mannschaft-valkey` |

#### 常駐サーバーのポート規約（本陣 と 検証用 worktree を分離）

並行セッション・E2E で本陣の 8080/3000 を奪い合う事故（ゾンビ bootRun 等。`feedback_opus_infra_ops_danger`）を防ぐため、**本陣と検証用 worktree でポートを分ける**。worktree はディレクトリ隔離、ポートは実行時リソース隔離で、両者は補完関係（冗長ではない）。

| 用途 | バックエンド | フロントエンド |
|---|---|---|
| **本陣**（正本の dev サーバー）| 8080 | 3000 |
| **検証・E2E 用 worktree**（本陣と別ディレクトリで起動）| **8081** | **3001** |

- 検証用 BE は `./gradlew bootRun --args='--server.port=8081'`、FE は `npm run dev -- --port 3001` で固定起動する
- **テスト（試練）はこの規約の対象外**。`@SpringBootTest(webEnvironment = RANDOM_PORT)` + Testcontainers 自動採番で、ポートを固定しない（並行実行の衝突回避。`/試練` 参照）

### 型定義の管理

- `frontend/app/types/` 配下に手動管理（145+ファイル）
- `frontend/app/types/generated/index.ts` は **openapi-typescript による自動生成**（手動編集禁止）
  - 再生成: `cd frontend && npm run generate:types`（`docs/openapi.json` を入力とする）
  - Backend API 変更後は必ず再生成してコミットすること
- 新規 API の型は生成型を優先して使用し、既存の手動型は段階的に移行する

---

## Git運用ルール

- `main` への直接コミット禁止
- 作業は `feature/[issue番号]-[説明]` ブランチで行う
- 大規模作業は `git worktree add` で物理的にディレクトリを隔離してから着手する（大名システム必須ルール参照）
- コミットメッセージは日本語で要約を記載（例: `機能追加: ユーザー認証APIの実装`）
- 完了後はPRを作成してCIが合格してから `main` へマージ
- **Flyway 採番**: `V{major}.{yyyyMMddHHmmss}__{説明}.sql`。major は「origin/main 全体の最大 major + 1」・**minor はタイムスタンプ必須（連番 `.001` 等は禁止・番人テスト `FlywayTimestampNamingGuardTest` が機械的に拒否）**。タイムスタンプは `date -u '+%Y%m%d%H%M%S'` で採る（詳細: `backend/.claudecode.md` §18）

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
- **メインの作業ディレクトリ `C:\Claude\mannschaft` で大規模実装を直接行うこと**（並列セッションとの HEAD 衝突を防ぐため、必ず大名システム経由で worktree 隔離する）
- **対処療法でバグを切り抜けること**（障害対応の原則を参照）
- TypeScript の `any` 使用（原則禁止）
- `types/generated/` への直接編集（openapi-typescript による自動生成。手動編集禁止・再生成は `cd frontend && npm run generate:types`）
- UIへの文字列直書き（i18nルール参照）
- 複雑な型パズル（Conditional Types のネスト、Template Literal Types の乱用）
- **本陣（`C:\Claude\mannschaft` 直下）での commit**（pre-commit フックで機械的に拒否。worktree を使うこと）

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
- メインリポ (`C:\Claude\mannschaft`) を間違って削除しないこと（`grep "agent-"` で必ず agent の worktree のみに絞る）

---

## 本陣保護フック（worktree 隔離の機械的強制）**【セットアップ必須】**

本陣（`C:\Claude\mannschaft` 直下）での直接 commit は、並行セッションの HEAD 衝突・作業消失を招くため**フックで機械的に禁止**している。クローン直後・新環境では以下を1回だけ実施すること。

### A. git pre-commit フック（最終防衛線・全アクターに有効）

`.githooks/pre-commit` が本陣（git-dir == git-common-dir）での commit を exit 1 で拒否する。worktree では許可。
インストール（どちらか一方）:

```bash
# 推奨: hooksPath を切り替える（コミットされた .githooks をそのまま使う。更新も自動追従）
git config core.hooksPath .githooks

# あるいは: 既定の .git/hooks にコピー（hooksPath 既定のまま）
cp .githooks/pre-commit .git/hooks/pre-commit   # PowerShell: Copy-Item .githooks\pre-commit .git\hooks\pre-commit -Force
```

人間が緊急で本陣 commit したい場合の脱出口は `git commit --no-verify`。**Claude は `--no-verify` 禁止**なので迂回できない。

### B. Claude PreToolUse フック（Claude を着手段階で阻止）

`.claude/hooks/block-honjin-git.ps1` が、本陣 CWD での `git checkout/switch/commit/reset/merge/rebase/cherry-pick/pull` を deny する（worktree 対象コマンドは許可）。
`.claude/settings.local.json`（マシンローカル）の最上位 `hooks` に以下を追加し、`/hooks` を一度開く or Claude 再起動で有効化する。**パスは各自の絶対パスに合わせること**（`$CLAUDE_PROJECT_DIR` でも可）:

```json
"hooks": {
  "PreToolUse": [
    {
      "matcher": "Bash",
      "hooks": [
        {
          "type": "command",
          "command": "powershell -NoProfile -ExecutionPolicy Bypass -File \"<リポジトリ絶対パス>/.claude/hooks/block-honjin-git.ps1\"",
          "if": "Bash(git *)",
          "timeout": 15,
          "statusMessage": "本陣git操作ガード"
        }
      ]
    }
  ]
}
```

### C. dev サーバーは本陣と別 worktree で起動（任意・推奨）

画面確認用 dev サーバーを本陣と別ディレクトリで動かすと、本陣 HEAD が動いても表示が無傷。
`git worktree add .claude/worktrees/dev-main main` → そこで以下のように **検証用ポート（BE 8081 / FE 3001）** で起動する（本陣 8080/3000 と衝突しない。上記「常駐サーバーのポート規約」参照）:

```bash
# 検証用 worktree 内で
cd backend && ./gradlew bootRun --args='--server.port=8081' &
cd frontend && npm run dev -- --port 3001
```

詳細経緯: memory `feedback_branch_isolation` / `feedback_merge_gh_only_no_honjin_git`。

---

## アーキテクチャ思想 **【実装時必読】**

Mannschaftは将来のマイクロサービス分割を見据えた**モジュラーモノリス**として設計する。
以下の原則は新機能実装・DB変更時に必ず遵守すること。

### ドメイン境界の原則

パッケージはドメイン単位で分割し、ドメイン間の直接依存を最小化する。

```
com.mannschaft.app.user/
com.mannschaft.app.team/
com.mannschaft.app.schedule/
com.mannschaft.app.shift/
...
```

- 異なるドメインのEntityを直接参照しない（IDのみ保持する）
- ドメイン間のデータ取得はServiceのメソッド呼び出し経由で行う

### DB設計の原則

#### 1. クロスドメインFKは作らない
異なるドメインのテーブル間にForeign Key制約を追加してはならない。
参照整合性はアプリケーション層で保証する。

```sql
-- NG: クロスドメインFK
ALTER TABLE shift_assignments
  ADD CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id);

-- OK: インデックスのみ（整合性はアプリ側で保証）
CREATE INDEX idx_shift_assignments_user_id ON shift_assignments(user_id);
```

#### 2. CASCADE DELETE は同一ドメイン内のみ許可
`ON DELETE CASCADE` は**親子が同一ドメインに属する場合のみ**許可する。
クロスドメインの削除連鎖は禁止。

```sql
-- OK: 同一ドメイン内（chat_channelとchat_messageは同じchatドメイン）
FOREIGN KEY (channel_id) REFERENCES chat_channels(id) ON DELETE CASCADE

-- NG: クロスドメイン（scheduleドメイン → teamドメイン）
FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE
```

#### 3. コアエンティティは論理削除（soft delete）を使う
`users` / `teams` / `organizations` は物理削除せず `deleted_at` カラムで論理削除する。
これらはすでに `deleted_at` カラムを持っている。

#### 4. ユーザー退会時は匿名化（削除しない）
ユーザーが退会しても投稿・履歴・統計データは保持し、個人情報のみ消去する。

```java
// ユーザー退会処理の方針
user.anonymize();        // 氏名・メール・アイコンを匿名化
user.softDelete();       // deleted_at をセット
// 投稿・ログ・ポイント等のデータは user_id=NULL にせずそのまま残す
// → 統計・履歴の価値を保持しつつ個人情報を保護（GDPR対応）
```

**PII 消去のタイミング（2026-05-18 改訂 / マスター御裁可 §13.12）:**

PII（個人識別情報）の消去は **GDPR Art.17 の 30 日タイムリミット内であれば段階的実施を許容する**。
退会フローは「即時消去対象（弱匿名化）」と「猶予対象（強匿名化）」の二段モデルを採用する。

| 区分 | 対象ドメイン | タイミング | 根拠 |
|---|---|---|---|
| **即時消去（弱匿名化）** | 通知・カレンダー連携・天気設定・お気に入り 等の「再設定で復旧可能」かつ「個人特定リスクが残る」データ | `requestWithdrawal` 受付直後（`UserAnonymizedEvent` 即時発火）| 退会撤回時は再設定で対応可。漏洩リスクを最小化 |
| **猶予対象（強匿名化）** | auth（OAuth/2FA）・social・village 所有権・scopefolder 等の「復旧不可能」または「業務整合性に重大影響」のあるデータ | `requestWithdrawal` 受付から最大 30 日後（`AccountPurgeService` バッチ）| 退会撤回ウィンドウを保持しつつ GDPR Art.17 を遵守 |

設計詳細: [`docs/architecture/withdrawal_flow_immediate_anonymization_fix.md`](docs/architecture/withdrawal_flow_immediate_anonymization_fix.md) §1.3 / §13.12（PR #793 main マージ済）。

#### 5. @Transactional はドメイン内に閉じる
`@Transactional` メソッドが複数ドメインのRepositoryをまたぐ場合は設計を見直す。
やむを得ずまたぐ場合はコメントで理由を明記し、将来のイベント駆動化候補として記録する。

```java
@Transactional
// TODO: ScheduleドメインとUserドメインをまたいでいる。将来はUserUpdatedEventで分離予定
public void createSchedule(...) { ... }
```

#### 6. 新規テーブルの主キーは UuidV7Entity を継承する（2026-05-11〜）
**新規に作成するテーブルの Entity** は `UuidV7Entity` を継承し、主キーを UUIDv7 にすること。
既存テーブルの BIGINT ID は変更しない。

```java
// 新規 Entity はこれを継承する
public class MyNewEntity extends UuidV7Entity {
    // id フィールドは UuidV7Entity が持つ（UUID型、自動生成）
    ...
}
```

```sql
-- 新規テーブルの DDL も UUID に合わせる
CREATE TABLE my_new_table (
    id BINARY(16) NOT NULL,  -- または CHAR(36)
    ...
    PRIMARY KEY (id)
);
```

**なぜ変更したか:**
BIGINT AUTO_INCREMENT は単一の発番サーバーが必要なため、水平分割（シャーディング）ができない。
UUIDv7 は時刻順ソート可能でインデックス効率が高く、複数DBノードで独立して発番できる。
1000万ユーザー規模でシャーディングが必要になったとき、既存テーブルのID変更は超侵襲的な作業になるため、
**新規テーブルから先行して UUIDv7 に移行することで、段階的にシャーディング対応を進める**方針とした。

**例外（UUIDv7 を適用しなくてよいテーブル）:**

原則 6 の意図は「将来シャーディングしたときに各ノードで独立発番できるようにする」ことである。
シャーディングの対象にならないテーブルは原則 6 の意図に該当しないため、自然キー / 固定値 ID のままで構わない。

| 例外区分 | 説明 | 主キーの推奨 |
|---|---|---|
| **マスタテーブル** | 全テナント・全ユーザー共通の参照データ。書き込みは運用バッチのみ、シャーディング時は全シャードに同じデータをコピーする運用。例: 郵便番号→緯度経度マスタ、国コード表、税率表 | 自然キー（複合キーでも可） |
| **シングルトン表** | 行が常に 1 行のみ存在する設定/運用状態テーブル。`CHECK (id = 1)` 等で行数を制約する。例: 取り込みバッチのメタデータ、初回マイグレーションの冪等フラグ | 固定値 ID（`TINYINT UNSIGNED CHECK (id = 1)` 等） |

判定基準: 「テナントごと・ユーザーごとに行が増えていくテーブル」=原則 6 適用。
「全テナント共通で読み取られるテーブル」「行が 1 件で固定のテーブル」=例外。

迷ったら**原則 6 を適用**しておけば後悔しない（BINARY(16) なら 16 バイト/行のオーバーヘッドだけで済む）。
ただし、上記 2 区分に該当するテーブルでは UUIDv7 化の利点が完全にゼロなので、設計書に「マスタ例外」「シングルトン例外」と明記して自然キーで設計してよい。

#### 7. テナントスコープのリポジトリは AbstractTenantAwareRepository を実装する（2026-05-11〜）
`organization_id` で絞り込む Repository は `AbstractTenantAwareRepository<T, ID>` を継承すること。

```java
// Before
public interface MyRepository extends JpaRepository<MyEntity, Long> {

// After（organization_id カラムを持つ Entity の場合）
public interface MyRepository extends AbstractTenantAwareRepository<MyEntity, Long> {
```

`AbstractTenantAwareRepository` が提供するメソッド:
- `findByOrganizationIdAndDeletedAtIsNull(Long organizationId)`
- `findByOrganizationIdAndDeletedAtIsNull(Long organizationId, Pageable pageable)`
- `findByIdAndOrganizationIdAndDeletedAtIsNull(ID id, Long organizationId)`
- `countByOrganizationIdAndDeletedAtIsNull(Long organizationId)`

**なぜ変更したか:**
将来の水平シャーディングでは `organization_id` をシャードキーとして使う。
リポジトリ層で `organization_id` 絞り込みを統一しておくことで、シャーディング導入時にルーティングロジックを
一箇所（基底クラス）に追加するだけで全テナント対応が完了する設計とした。

### なぜこの設計か

**1000万ユーザー規模**で発生する具体的な問題を防ぐために段階的に設計を整備している。

| 問題 | 対応原則 |
|---|---|
| クロスドメインFK でシャード分割不能 | 原則1・2（FK撤廃、CASCADE制限）|
| 退会トリガーの連鎖削除で統計破壊 | 原則2・3・4（CASCADE制限、論理削除、匿名化）|
| 巨大テーブルの B-Tree 破綻 | Phase 3（パーティショニング・アーカイブ）|
| 単一 DB ノードの書き込み上限 | 原則6・7 + Phase 4（UUID・テナント設計・レプリカ）|
| @Transactional 越境でデッドロック頻発 | 原則5（ドメイン内 @Transactional）|

詳細な実装記録は `docs/architecture/db_scalability.md` を参照。

---

## カスタムスキル

| スキル | 用途 |
|---|---|
| `/陣立て` | 開発環境の起動（WSL2 + Docker + ビルド確認）|
| `/陣触れ` | フロントエンド（Nuxt）開発サーバー起動 |
| `/伝令` | Spring Boot起動 + swagger.json取得 |
| `/軍議` | 設計・タスク分解＋受け入れ条件の列挙 |
| `/試練` | テスト先行（受け入れ条件から red テスト作成） |
| `/出陣` | 実装実行（試練を green 化） |
| `/検分` | コードレビュー・品質チェック＋トレーサビリティ照合 |
| `/早馬` | 緊急バグ修正 |
| `/巡回` | ビルド・テスト監視 |
| `/撤収` | 開発環境の終了 |
| `/実機` | 実機E2E（ユーザー視点・モックなしの最終確認） |
| `/凱旋` | 検分→CI緑裏取り→マージ（`--admin`可）を一気通貫 |
| `/物見` | CI/PR 戦況の低頻度・単発確認（ポーリング枯渇回避） |
| `/陣払い` | 古い足軽 worktree の一括掃除 |
| `/統一` | デザイン統一（共通コンポーネントへ直書きUIを寄せる） |
| `/手助け` | 指定ページに「使い方モーダル」を作成 |
| `/手助けモーダル` | ダイアログ画面に使い方ガイドを付与 |
| `/引継` | セッション引き継ぎ書を生成（中断時の引き渡し） |
| `/絵図` | 設計書の要否精査→作成/修正→2度精査 |
