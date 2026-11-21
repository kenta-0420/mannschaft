<!-- `backend/BACKEND_CODING_CONVENTION.md` §8 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/BACKEND_CODING_CONVENTION.md（索引）に戻る](../../../backend/BACKEND_CODING_CONVENTION.md)

## 8. セキュリティ
* **機密情報の保護**: APIキー、パスワード、暗号化鍵などをソースコードに直接記述（ハードコード）することを厳禁とします。
* **インジェクション対策**: SQL発行時は文字列結合を避け、必ず **プレースホルダ** を使用してください。
* **情報の秘匿**: パスワードや機密性の高い個人情報をログに出力しないでください。
* **認可 (Authorization)**: ID等のパラメータを書き換えて他者のデータにアクセスできないよう、処理の入り口で必ず**実行権限チェック**を行ってください。
* **XSS対策**: ユーザー入力値を画面表示する際は、サニタイズ（エスケープ）処理を徹底してください。

### パスワード保存・管理規約
* **生パスワード保持の厳禁**: データベースにパスワードを平文（そのままの文字列）で保存することをいかなる理由があっても禁止する。
* **ハッシュ化アルゴリズム**: `bcrypt` を使用し、適切にソルト（Salt）を付与して保存すること。
* **ストレッチング設定**: `bcrypt` のコストファクター（計算負荷）は、現在のサーバー性能に合わせて **10〜12** 程度（またはそれ以上）を設定し、総当たり攻撃への耐性を高める。

### パスワードポリシー
* **最低文字数**: 8文字以上（推奨12文字以上）とする。
* **複雑性要件**: 英大文字・小文字・数字・記号のうち **3種類以上** を組み合わせることを必須とする。
* **禁止事項**: ユーザーIDと同じ文字列や、一般的すぎるパスワード（`123456`, `password` 等）を拒否するチェックを実装する。

### JWT（JSON Web Token）運用規約
* **有効期限の設定**:
    * **Access Token**: **15分**（短期間に設定し、漏洩リスクを低減する）
    * **Refresh Token**: **7日間**（ユーザーの利便性と安全性のバランスを考慮）
* **トークンローテーション**: Refresh Token を使用して新しい Access Token を発行する際、Refresh Token 自体も新しく発行し、古いものは無効化する。
* **ログアウト・無効化処理**: ログアウト時は該当トークンを Valkey 上の「ブラックリスト」に登録し、期限内であっても即座に使用不能とする。
* **保存場所**: 認証は二本立てである。Web は `HttpOnly`・`SameSite=Strict` の Cookie（`access_token` と `refresh_token`）、ネイティブアプリは `Authorization: Bearer` ヘッダーで送る。BE は Bearer を優先し、無ければ Cookie を読む。フロントエンドはトークンをメモリにだけ持ち、`localStorage` / `sessionStorage` には保存しない。CSRF は `SameSite=Strict` と同一オリジンで防ぐ（2026-11-17 マスター裁可により、実装と `docs/features/F01.1_auth.md`・`docs/security/02_cookie_and_session.md` に合わせて改訂）。
* **デバイスバインディング（推奨）**: Refresh Token 使用時に、発行時のIPアドレスおよびUser-Agentとの一致を検証することを推奨する。不一致の場合はトークンを無効化し、再ログインを要求する。これにより、XSSで Refresh Token が漏洩した場合でも、異なるデバイスからの悪用を防止できる。

### WebSocket (STOMP) 認証規約
* **認証方式**: 利用者のJWTと端末capabilityを区別する。いずれもWebSocket URLのqueryを資格情報として受け付けない。
* **JWT送付タイミング**: STOMP の `CONNECT` フレームで `Authorization: Bearer ...` を渡す。Bearer/Cookie/匿名の優先順位は `docs/security/01_authorization_baseline.md` §5を正本とし、端末capabilityでJWT Principal/userIdを置き換えない。
* **掲示端末capability**: `CONNECT` の専用native header `X-Signage-Token` だけで渡す（F09.10 §6）。HTTP header・URL query・SUBSCRIBE/SEND headerから取得しない。CONNECT検証後はraw tokenをnative headerから除去し、server registryにはtoken ID・screen ID・直接peer IP・購読IDとexact宛先だけを保持する。匿名のtoken所持端末を許可し、所属/管理権限によるtokenlessバイパスは設けない。
* **サーバー側検証**:
    * `ChannelInterceptor` を実装し、`preSend` メソッドにて `CONNECT` コマンド受信時のヘッダー検証を行う。
    * 明示した資格が無効なら接続を拒否する。資格なしの共通CONNECTは匿名として通し、各topicのSUBSCRIBEと配送境界で認可する。
    * 掲示topicはSUBSCRIBE時とrecipientごとの配送時にtoken・画面の現在状態を検証する。購読時だけの検証で失効後の配送を許してはならない。
* **ハンドシェイク時**: HTTP upgradeは許可Originで受け付ける。共通CONNECTでJWTの認証ソースを確定し、匿名を含む接続の各topicのSUBSCRIBE/配送境界で、そのtopicの許可契約を強制する。

### レートリミット（リクエスト制限）規約
* **必須適用箇所**:
    * ログインAPI（認証試行）
    * パスワードリセット・メール送信API
    * 公開API全般（認証不要エンドポイントは特に厳格に適用すること）
* **実装方針**: Spring Boot 環境において、`Bucket4j` または `Valkey` を用いたレートリミット機構を実装する。
* **制限値の目安**:
    * 認証系: 同一IPにつき「1分間に10回まで」等の厳格な制限。
    * 一般API: サービスの特性に応じた妥当なバースト許容値を設定。
    * **`POST /error-reports`（エラーレポート送信）**: 認証不要の公開エンドポイントのため以下の制限を適用する。
        * 同一IPにつき **1分間に10回まで**（実装: ErrorReportRateLimitFilter、F12.5 §5.6）。
        * 制限超過時は `429 Too Many Requests` を返し、`Retry-After` ヘッダーで再試行可能時刻を通知する
* **レスポンス**: 制限超過時は、HTTPステータス `429 Too Many Requests` を返却すること。

### CORS（交差オリジンリソース共有）設定規約
* **ワイルドカードの禁止**: `AllowedOrigins` に `*`（すべて許可）を設定することを禁止する。必ず信頼できる特定のドメインのみを許可すること。
* **環境変数による管理**: 許可するドメイン（Origin）のリストは、コード内に直接記述（ハードコード）せず、`MANNSCHAFT_ALLOWED_ORIGINS` 環境変数として外部から注入する構成とする（`.claudecode.md` §21 命名規約に準拠）。
* **環境別の設定**: 開発環境（localhost等）と本番環境で許可リストを適切に切り分け、本番環境に不要なドメインが含まれないよう徹底する。

### ストレージ（Pre-signed URL）運用規約
* **有効期限の最小化**: アップロード/ダウンロード用に発行する Pre-signed URL の有効期限は、原則 **5〜15分** 以内に設定する。
* **アップロード制限**:
    * **ファイルサイズ**: 発行時に `content-length-range` を指定し、期待されるファイルサイズ（例：5MB以下）を超えるリクエストを拒否する。
    * **ファイル形式**: `Content-Type` を固定し、予期せぬファイル形式のアップロードを防止する。
* **一度限りの使用**: セキュリティ向上のため、1つのURLで実行できる操作は1回のみ（Single Use）とする実装を検討する。

### コンテンツセキュリティポリシー (CSP) 運用規約
* **基本方針**: XSS攻撃に対する多層防御として、HTTPレスポンスヘッダーに `Content-Security-Policy` を設定する。
* **デフォルト設定**: `default-src 'self';` を基本とし、原則として自ドメイン以外のリソース読み込みを制限する。
* **スクリプト制限**: `script-src 'self';` とし、HTML内に直接記述されたインラインスクリプトの実行を禁止する。
* **外部リソースの許可**: Google Fonts、外部分析ツール（GA等）、特定の外部APIなど、業務上必要なドメインのみを明示的にホワイトリストへ追加する。
* **運用開始ステップ**: 開発初期は `Content-Security-Policy-Report-Only` ヘッダーを使用し、正常な動作を妨げないことを確認してから正式に適用（Enforce）する。

### APIリクエストサイズ制限
* **リクエストボディ**: Spring Boot のデフォルト設定をベースに、APIエンドポイントのリクエストボディは原則 **1MB以下** に制限する。
* **ファイルアップロード**: ファイルのアップロードは Pre-signed URL 経由で行うため、API サーバーにファイルを直接送信しない。`spring.servlet.multipart.max-file-size` は小さく設定し（例: 1MB）、不正な大容量リクエストを遮断する。
* **例外**: アバター画像など小さなファイルを API 経由で受け付ける場合は、エンドポイントごとに個別設定する。
* **リスト入力の件数上限**: ID・要素のリストを受けるリクエスト DTO には必ず `@Size(max = …)` を付ける。1MB 以下でも数万件の ID を送れ、無制限の DB クエリ（N+1 含む）を誘発して DoS になる。

### 静的解析 (Checkstyle)
* **導入方針**: Gradle の Checkstyle プラグインを導入し、`gradle checkstyleMain` でコーディング規約への準拠を自動検証する。
* **設定ファイル**: `config/checkstyle/checkstyle.xml` にプロジェクト共通のルールセットを配置する。Google Java Style をベースに、本規約に合わせてカスタマイズする。
* **主な検査項目**:
    * インデント（半角スペース4つ）
    * 命名規則（camelCase / PascalCase / UPPER_SNAKE_CASE）
    * メソッド行数上限（200行）
    * ネスト深度上限（3階層）
    * `import` の整理（ワイルドカードインポート `*` の禁止、未使用インポートの検出）
    * Javadoc の存在チェック（クラスレベル必須）
* **CI 統合**: GitHub Actions の CI パイプラインに `checkstyleMain` タスクを組み込み、違反がある場合はビルドを失敗させる。
* **抑制**: やむを得ず規約に従えない箇所は `@SuppressWarnings("checkstyle:ルール名")` で個別に抑制し、理由をコメントに残すこと。ファイル単位での抑制（`suppressions.xml`）は原則禁止する。

### 静的バグ検出 (SpotBugs)
* **導入方針**: Gradle の SpotBugs プラグインを導入し、`gradle spotbugsMain` でバグパターンを自動検出する。Checkstyle がスタイルを検査するのに対し、SpotBugs は**実際のバグ**（NullPointerException、リソースリーク、並行処理の不具合等）を検出する。両者は役割が異なるため併用する。
* **検出レベル**: 報告レベルは `medium` 以上、検出努力（effort）は `max` を基本設定とする。
* **主な検出カテゴリ**:
    * Null参照の可能性（`NP_NULL_ON_SOME_PATH` 等）
    * リソースの閉じ忘れ（`OBL_UNSATISFIED_OBLIGATION` 等）
    * 並行処理の問題（`IS2_INCONSISTENT_SYNC` 等）
    * 無意味な比較・計算（`EC_UNRELATED_TYPES` 等）
* **CI 統合**: GitHub Actions の CI パイプラインに `spotbugsMain` タスクを組み込み、`medium` 以上のバグが検出された場合はビルドを失敗させる。
* **抑制**: 誤検知の場合は `@SuppressFBWarnings(value = "ルール名", justification = "理由")` で個別に抑制し、理由を必ず明記すること。

### EditorConfig
* **目的**: IDE やエディタ間でインデント・改行コード・文字コードを統一し、環境差異によるフォーマット崩れを防止する。
* **設定ファイル**: プロジェクトルートに `.editorconfig` を配置する。
* **主な設定内容**:
    * `charset = utf-8`
    * `end_of_line = lf`（改行コードを LF に統一し、Git の CRLF 警告を解消する）
    * `indent_style = space`
    * Java ファイル: `indent_size = 4`
    * Vue / TypeScript / JSON / YAML ファイル: `indent_size = 2`
    * `trim_trailing_whitespace = true`
    * `insert_final_newline = true`
* **適用範囲**: バックエンド・フロントエンド共通で適用する。

### IDE 設定（バックエンド）
開発者がコーディング中にリアルタイムで規約違反に気づけるよう、以下の IDE 設定を整備する。

#### VS Code
* **推奨拡張機能**: プロジェクトルートに `.vscode/extensions.json` を配置し、チーム全体で統一する。
```json
{
  "recommendations": [
    "vscjava.vscode-java-pack",
    "shengchen.vscode-checkstyle",
    "editorconfig.editorconfig",
    "eamodio.gitlens"
  ]
}
```
* **ワークスペース設定**: `.vscode/settings.json` を配置する。
```json
{
  "java.format.settings.url": "config/checkstyle/checkstyle.xml",
  "java.format.enabled": true,
  "editor.formatOnSave": true,
  "java.checkstyle.configuration": "${workspaceFolder}/config/checkstyle/checkstyle.xml",
  "java.checkstyle.version": "10.17.0",
  "files.eol": "\n",
  "files.trimTrailingWhitespace": true,
  "files.insertFinalNewline": true
}
```

#### IntelliJ IDEA
* **推奨プラグイン**: `CheckStyle-IDEA`, `Save Actions`（または IDE 標準の Save Actions）。
* **Checkstyle 連携**: `CheckStyle-IDEA` プラグインで `config/checkstyle/checkstyle.xml` を読み込み、リアルタイムインスペクションを有効化する。
* **保存時アクション**: `Settings → Tools → Actions on Save` で以下を有効化する。
    * `Reformat code`（コードフォーマット）
    * `Optimize imports`（未使用インポートの除去）
* **コードスタイル同期**: `Settings → Editor → Code Style → Java → Import Scheme → Checkstyle configuration` で Checkstyle 設定をインポートし、IDE のフォーマッタと規約を一致させる。
* **設定ファイル**: `.idea/` 配下の設定は `.gitignore` で除外し、プロジェクト共有が必要な設定は `README` またはセットアップガイドに手順を記載する。

### pre-commit フック
* **目的**: コミット前に静的解析・フォーマットチェックを自動実行し、規約違反がリポジトリに混入するのを防止する。CI まで待たずにローカルで即検知できるため、フィードバックループが短縮される。

#### バックエンド セットアップ手順
`gradle-git-hooks` プラグインを使用し、Gradle ビルドと Git フックを統合する。

1. **`build.gradle.kts` にプラグイン追加**:
```kotlin
plugins {
    id("com.github.niclasvoss.gradle-git-hooks") version "0.0.3"
}

gitHooks {
    preCommit = "checkstyleMain spotbugsMain"
}
```

2. **フック登録**: プロジェクトのクローン後に以下を実行する。
```bash
./gradlew installGitHooks
```

3. **代替方式（手動スクリプト）**: プラグインを使用しない場合は、`.githooks/pre-commit` にシェルスクリプトを配置し、`git config core.hooksPath .githooks` で有効化する。
```bash
#!/bin/sh
echo "Running pre-commit checks..."
./gradlew checkstyleMain spotbugsMain --daemon
if [ $? -ne 0 ]; then
  echo "Pre-commit checks failed. Commit aborted."
  exit 1
fi
```

#### フロントエンド セットアップ手順（`frontend/` ディレクトリ）
**Husky** + **lint-staged** を導入する。

1. **依存インストールとHusky初期化**:
```bash
npm install -D husky lint-staged
npx husky init
```

2. **`.husky/pre-commit` を編集**:
```bash
npx lint-staged
```

3. **`package.json` に lint-staged 設定を追加**:
```json
{
  "lint-staged": {
    "*.{ts,vue}": ["eslint --fix", "prettier --write"],
    "*.{json,md,yml}": ["prettier --write"]
  }
}
```

4. **動作確認**: ステージされたファイルのみを対象に `eslint --fix` および `prettier --write` が実行される。全ファイルを対象にしないことで高速性を維持する。

#### 強制力
* フックのスキップ（`git commit --no-verify`）は緊急時のみに限定し、通常の開発では禁止する。
* 新規開発者のオンボーディング手順に `./gradlew installGitHooks`（バックエンド）および `npm install`（フロントエンド、Husky が `prepare` スクリプト経由で自動設定）を含めること。

### テストデータ作成パターン（TestFixture 方式）

テストデータの生成を集約するため、**TestFixture クラス**を作成する。

#### 配置ルール
```
src/test/java/com/mannschaft/app/
├── common/
│   └── TestFixture.java          # 共通ヘルパー（User, Team 等の頻出エンティティ）
└── [feature]/
    └── [Feature]TestFixture.java  # 機能固有のテストデータ
```

#### 書き方
```java
public class TestFixture {
    // 「最低限有効なデフォルト値」を持つ static メソッドを用意する
    public static UserEntity defaultUser() {
        return UserEntity.builder()
            .email("test@example.com")
            .lastName("テスト")
            .firstName("太郎")
            .displayName("テスト太郎")
            .build();
    }

    // テストごとに必要なフィールドだけオーバーライドする
    public static UserEntity userWithRole(RoleType role) {
        return defaultUser().toBuilder()
            .role(role)
            .build();
    }
}
```

#### ルール
- DB に保存する場合は **Repository 経由**で行う: `userRepository.save(TestFixture.defaultUser())`
- **手書きの INSERT SQL は禁止**（エンティティの変更に追従できなくなるため）
- テスト間でデータが干渉しないよう、`@Transactional`（自動ロールバック）または `@BeforeEach` でクリーンアップする
- テストメソッド名は日本語を許容する（例: `void 管理者のみチーム作成が可能()`）。テストの意図を明確にすることを優先する

### テスト実行環境
* **統合テスト**: データベースを用いた統合テストには **Testcontainers** (MySQL 8.0) を使用する。ローカル環境に MySQL をインストールする必要はない。
* **CI/CD**: GitHub Actions 等の CI パイプラインでも Testcontainers を実行する。Docker-in-Docker または Docker Socket マウント方式を使用すること。
* **テスト分離**: 統合テストとユニットテストは Gradle タスクで分離可能にする（例: `gradle test` でユニット、`gradle integrationTest` で統合テスト）。
