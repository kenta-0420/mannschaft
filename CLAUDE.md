# CLAUDE.md — Mannschaft 開発ガイド

## コミュニケーション言語

- 会話・説明・コメント・コミットメッセージ・ドキュメントはすべて **日本語** で記述すること
- コード内の変数名・関数名・クラス名は英語（仕様書の用語と一致させる）

---

## 作業開始前の必須確認

コーディングを始める前に必ず `pwd`（または `git worktree list`）で自分の作業ディレクトリを確認すること。

- ✅ `.claude/worktrees/agent-xxxxx/` 配下 → そのまま作業してよい
- ❌ `C:/Claude/mannschaft` 直下 → **作業を中断し、殿に報告する**
  - ただし「大名システム活用ルール」の**例外**節に該当する場合はこの限りではない

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
3. **`/試練`** — 受け入れ条件から**失敗するテスト（red）を先行作成**（実装漏れ根治のため実装より前に書く）
4. **`/出陣`** — 足軽を `Agent(isolation:"worktree")` で起動、試練を green 化（実装）
5. **`/検分`** — 成果物のレビュー＋**受け入れ条件↔テスト↔実装のトレーサビリティ照合**

**開発作業は原則として必ず大名システム（Agent サブエージェント）経由で実行すること。** 本陣 `C:\Claude\mannschaft` で直接コーディング・コミットする運用は禁止。BE/API はテスト先行、FE/設計が薄い機能は従来順で可。

戦役をまたぐ横断の課題正本は [`docs/task-list.md`](docs/task-list.md)（git追跡・永続、行の粒度は戦役単位）。1戦役内の詳細な進捗地図は `.claude/campaigns/*.md`（`.gitignore` 済・揮発、完了後削除）。

**`docs/task-list.md` はコードと同じく worktree + PR で扱うこと。本陣の作業木で直接編集してはならない**（下記「例外」節の対象外）。本陣の作業木は全セッションで共有されており、そこでの編集は隔離されない。別セッションが自分の版を先に commit すると、**git の競合すら起こさずに自分の編集が消える**（2026-08-14、CMP-028 の完了記録が実際にこの経路で消失した）。

**採番手順**: 新しい行の ID は**日時形式 `CMP-YYMMDD-HHMM`（JST）**とする。`date '+%y%m%d-%H%M'` で生成し（例: `CMP-260819-2131`）、そのまま使う。**`git fetch` して最大番号を調べる手順は不要**であり、採番の衝突は原理的に起きない。旧・連番形式（`CMP-001`〜）で採番していた時期の行はそのまま残すため、台帳には両形式が共存する（既存行の ID は書き換えない）。**方式を変えた理由**: 旧方式（`origin/main` 上の最大 CMP 番号 +1）は、採番からマージまでの時間差の間に並行セッションが同じ番号を取るため衝突が常態化しており、2026-08-19 には1行の追加に対して採番を3度やり直した（CMP-050 → 102 → 110。いずれも待っている間に他セッションに取られた）。

**新規行は必ず表の末尾に追加すること。** 直前に触った行の近くへ挿入すると、同じ ID が別々の位置に入っても git が競合として検知できず、重複したまま静かに main へ入りうる。末尾追記に統一すれば、同時追加は必ず競合として止まる。なお重複したまま main へ入ることは番人 `TaskListCmpIdDuplicateGuardTest`（`backend/src/test/java/com/mannschaft/app/common/architecture/`）が CI で検出する（新旧両形式に対応）。重複が検出された場合は、後から merge された側が採番し直す。

### Dynamic Workflows との連携（出陣・検分の高速化／コスト最適化）

`/出陣`・`/検分 claude` は Dynamic Workflows で足軽の並列起動を表現できる（`/検分` の既定検分者は `codex` で、Codex による独立検分が走る。Workflow 検分を使うには `claude` を明示する）（オプトイン。機械的タスクは sonnet/haiku・低 effort、難所は opus・high に固定。コミット/マージは `gh`）。詳細: [`docs/development/daimyo_workflow_migration.md`](docs/development/daimyo_workflow_migration.md)。

### 例外（メインディレクトリで直接やってよい作業）

- ユーザーとの軽い対話・質問への回答
- 1〜2ファイル限定の即時的な修正で、コミットせず確認だけする場合
- ドキュメントの軽微な追記（**ただし `docs/task-list.md` は対象外**。正本であり並行編集の的になるため、軽微に見えてもコミットする場合は必ず worktree + PR で扱う。詳細は上記「大名システム活用ルール」節参照）
- worktree のクリーンアップなど git 管理操作そのもの

> worktree 隔離が必須な理由・起動すべき場面・並列セッションの作法の詳しい解説は [`docs/development/worktree_operations.md`](docs/development/worktree_operations.md) を参照（大名システムの運用に疑問が生じたら読む）。

---

## ドキュメント更新ルール

**コードを修正・追加した場合、必ず以下も更新すること（実装とドキュメントは常に同期させる。省略しない）:**
- `README.md`（機能・構成・API変更時）
- `docs/` 配下の該当ドキュメント（設計・機能仕様変更時）
- `backend/BACKEND_CODING_CONVENTION.md`（BE規約変更時）
- `frontend/FRONTEND_CODING_CONVENTION.md`（FE規約変更時）
- `backend/.claudecode.md`（プロジェクト構成規約変更時）

---

## 規約ドキュメント（必読）

| ファイル | 内容 |
|---|---|
| `backend/.claudecode.md` | プロジェクト全体構成規約（Spring Boot / Nuxt.js）|
| `backend/BACKEND_CODING_CONVENTION.md` | Javaコーディング規約 |
| `frontend/FRONTEND_CODING_CONVENTION.md` | TypeScript / Nuxt.jsコーディング規約 |
| `TEST_CONVENTION.md` | テスト規約 |
| `docs/security/README.md` | セキュリティ横断方針 |

実装前に必ず確認すること。**認可・Cookie・セキュリティヘッダー・依存関係に関わる変更時は `docs/security/` を参照**すること。

---

## プロジェクト概要

- **バックエンド:** Spring Boot (Java) — `backend/`
- **フロントエンド:** Nuxt 3 (TypeScript / Vue 3) — `frontend/`
- **インフラ:** Docker Compose — `docker-compose.yml`
- **ドキュメント:** `docs/`（設計・機能仕様）

### ポート一覧・常駐サーバーのポート規約

本陣と検証・E2E 用 worktree でポートを分ける（奪い合い事故防止。worktree=ディレクトリ隔離、ポート=リソース隔離）:

| サービス | 本陣 | 検証用 worktree | 備考 |
|---|---|---|---|
| Spring Boot API | 8080 | 8081 | 検証用は `--args='--server.port=8081'` |
| Nuxt dev server | 3000 | 3001 | 検証用は `npm run dev -- --port 3001` |
| MySQL 8.0 | 3306 | — | コンテナ名: `mannschaft-mysql` |
| Valkey (Redis互換) | 6379 | — | コンテナ名: `mannschaft-valkey` |

- ⚠️ **検証用 BE の CORS 許可オリジン**（`application.yml:94` の `mannschaft.allowed-origins`、既定 `localhost:3000,8080` で 3001 非対応）**に 3001 を足さないと全 API が落ちる**。起動時に上書き: `MANNSCHAFT_ALLOWED_ORIGINS=http://localhost:3001,http://localhost:8081 ./gradlew bootRun --args='--server.port=8081'`
- テスト（試練）はこの規約の対象外（Testcontainers 自動採番でポート固定しない）

### 型定義の管理

- `frontend/app/types/` 配下に手動管理（145+ファイル）
- `frontend/app/types/generated/index.ts` は openapi-typescript による自動生成（手動編集禁止）。再生成は `cd frontend && npm run generate:types`（`docs/openapi.json` が入力）。Backend API 変更後は必ず再生成してコミット
- 新規 API の型は生成型を優先し、既存の手動型は段階的に移行する

---

## Git運用ルール

- `main` への直接コミット禁止
- 作業は `feature/[issue番号]-[説明]` ブランチで行う
- 大規模作業は `git worktree add` で物理的にディレクトリを隔離してから着手する（大名システム必須ルール参照）
- コミットメッセージは日本語で要約を記載（例: `機能追加: ユーザー認証APIの実装`）
- 完了後はPRを作成してCIが合格してから `main` へマージ
- **Flyway 採番**: `V{major}.{yyyyMMddHHmmss}__{説明}.sql`。major は origin/main 全体の最大 major+1、minor はタイムスタンプ必須（連番禁止、`FlywayTimestampNamingGuardTest` が拒否）。採番は `date -u '+%Y%m%d%H%M%S'`（詳細: `backend/.claudecode.md` §18）

---

## i18n ルール

- UIに表示する文字列は **直書き禁止**。ロケールファイルに追加してから `$t('key')` で参照すること
- ロケールファイル: `frontend/app/locales/{ja,en,zh,ko,es,de}/{common,auth,validation,landing}.json`（6言語すべてに追加。未翻訳ならとりあえず日本語と同じ値で可）
- デフォルトロケール: `ja`

---

## 障害対応の原則 — 根治治療を徹底すること **【必須】**

バグや障害に遭遇した際、**対処療法（症状隠し・エラー握りつぶし・一時しのぎ）を禁止**し、必ず根本原因を特定して根治治療を行うこと。

1. **原因の連鎖を最後まで追う** — 表面的なエラーメッセージで判断せず、真の原因にたどり着くまで掘り下げる
2. **症状を隠さない** — `.catch(() => {})`・`try-catch` でのエラー握りつぶし、フラグでの分岐回避は禁止。壊れているなら正直に報告し修正する
3. **未実装は未実装として対処する** — FE が呼ぶ API が BE に無い場合、誤魔化さず API を実装する
4. **Flyway 失敗は SQL のバグ自体を修正**してから再適用する（`flyway repair` だけで済ませない）
5. **ビルドエラーは根本から直す** — キャッシュクリア・再起動の「たまたま通った」を正とせず原因を理解して直す

（実例 2026-04-10: loading止まりをタイムアウト追加で誤魔化さず、実機再現→Flyway SQLバグ発見→未実装API追加まで遡って根治）

---

## 禁止事項

- `main` ブランチへの直接コミット
- **本陣 `C:\Claude\mannschaft` での大規模実装・commit**（worktree 隔離必須。commit は pre-commit フックで機械的に拒否）
- **対処療法でバグを切り抜けること**（障害対応の原則を参照）
- TypeScript の `any` 使用（原則禁止）
- `types/generated/` への直接編集（自動生成。再生成は `cd frontend && npm run generate:types`）
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

大名システム（Agent）の worktree は作業完了後に必ず掃除する — agent 完了直後にその commit を本リポに統合してから `git worktree remove --force` で削除、定期的な一括掃除はスキル `/陣払い`（既定7日以上前を撤去）。進行中の agent の worktree は削除しない。コマンド集・タイミング表など詳細は [`docs/development/worktree_operations.md`](docs/development/worktree_operations.md) を参照。

---

## 本陣保護フック（worktree 隔離の機械的強制）**【セットアップ必須】**

本陣（`C:\Claude\mannschaft` 直下）での直接 commit は、git pre-commit フックと Claude PreToolUse フックの二重で機械的に拒否している。クローン直後・新環境でのセットアップ手順は [`docs/development/honjin_protection_setup.md`](docs/development/honjin_protection_setup.md) を参照（初回セットアップ時に読む）。

---

## アーキテクチャ思想 **【実装時必読】**

Mannschaft は将来のマイクロサービス分割を見据えた**モジュラーモノリス**として設計する（ドメイン単位でパッケージ分割し、ドメイン間はID参照＋Service経由のみ）。DB設計の原則:

1. クロスドメインFK禁止（整合性はアプリ層で保証、インデックスのみ張る）
2. CASCADE DELETE は同一ドメイン内のみ許可
3. コアエンティティ（users/teams/organizations）は `deleted_at` による論理削除
4. ユーザー退会時は匿名化（物理削除しない）。PII消去は即時（弱匿名化）と30日後（強匿名化）の二段モデル（GDPR Art.17準拠。詳細: [`docs/architecture/withdrawal_flow_immediate_anonymization_fix.md`](docs/architecture/withdrawal_flow_immediate_anonymization_fix.md) §1.3/§13.12）
5. `@Transactional` はドメイン内に閉じる（越境時はコメントで理由明記）
6. 新規テーブルは `UuidV7Entity` 継承（主キーUUIDv7。マスタテーブル・シングルトン表は自然キー/固定値IDのまま可）
7. テナントスコープ（`organization_id` 絞り込み）Repository は `AbstractTenantAwareRepository` 継承

**DB変更・新規テーブル・ドメイン境界に関わる実装時は必ず** [`docs/architecture/domain_db_design_principles.md`](docs/architecture/domain_db_design_principles.md) **を読むこと**（コード例・例外区分の判定基準・なぜこの設計かの背景を含む詳細版）。

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

### DeepSeek 用スキル（`deepseek:` 名前空間）

DeepSeek プロバイダでセッションを起動している場合、`/deepseek:軍議` `/deepseek:出陣` 等の `deepseek:` プレフィックス付きスキルが使える。
- 本文中のモデル名が **Pro**（Opus相当）/ **Flash**（Sonnet/Haiku相当）表記に置き換わっている
- Agent ツールの `model` パラメータは tier 識別子（`opus`/`sonnet`）のままで、MulmoTerminal の provider 機能が環境変数経由で DeepSeek の実モデルに自動解決する
- 元の `/軍議` `/出陣` もそのまま使える（プロバイダ自動変換のため）。`deepseek:` 版は人間向けの可読性向上が目的
