# Claude Code コーディング規約 (BACKEND_CODING_CONVENTION.md)

本プロジェクトの品質、保守性、およびセキュリティを維持するため、すべての開発者は以下の規約を遵守してください。

---

## 1. 命名規則と基本スタイル
* **キャメルケースの徹底**: 変数名およびメソッド名は **camelCase** とします。
* **インデント**: **半角スペース4つ**を使用してください。
* **クラス命名**: `～Manager` や `～Util` といった曖昧な名称は避け、責務を具体的に表現してください。
* **ボイラープレートの削減**: **Lombok** を積極的に活用してください。
    - **DTO / Request / Response**: `@Getter` + `@RequiredArgsConstructor` を基本とし、フィールドは `final` で宣言する（不変オブジェクト）。`@Setter` は使用しない（`.claudecode.md` §19 DTO粒度ルール参照）。Jackson がコンストラクタ引数名を認識してデシリアライズできるよう、`build.gradle.kts` に `-parameters` コンパイラオプションを追加すること（下記参照）。不要な `toString` や `equals` のオーバーライドは避けること。
```kotlin
// build.gradle.kts — Jackson がコンストラクタ引数名を認識するために必須
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}
```
    - **JPA Entity**: クラスレベルの `@Setter` は禁止する。`@Getter` + `@NoArgsConstructor(access = AccessLevel.PROTECTED)` + `@Builder(toBuilder = true)` + `@AllArgsConstructor(access = AccessLevel.PRIVATE)` を基本とし、状態変更はビジネスメソッド（例: `changeEmail(String email)`）経由で行うこと。`@Builder` はテストデータ作成（TestFixture）で活用するため付与する。`toBuilder = true` により既存インスタンスの一部フィールドだけ変更したコピーを作成できる。不正な状態遷移を防止し、ドメインロジックを Entity 内に集約するため。
* **DI方式**: **コンストラクタインジェクションを必須** とし、`@Autowired` によるフィールドインジェクションは禁止する。Lombok の `@RequiredArgsConstructor` と `final` フィールドを組み合わせて実装すること。
* **マジックナンバーの禁止**: 意味のある数字は直接記述せず、必ず定数（`static final`）を定義してください。
* **区分値の管理**: 区分値や状態フラグは String/int 定数ではなく、原則として **Enum** を使用してください。
* **定数の配置ルール**:
    - **機能固有の定数**: その機能のクラス内に `private static final` または `static final` で定義する。
    - **状態・種類を表すもの**: Enum として定義する。
    - **プロジェクト横断的なシステム定数**: `com.mannschaft.app.common.CommonConstants` に集約する（例: 日付フォーマット、ページネーションのデフォルト値等）。環境依存の値は環境変数で管理し、`CommonConstants` にはハードコードしない。
* **パッケージ構成**: **機能別パッケージ（feature別）** を採用する。`src/main/java/com/mannschaft/app/[feature]/` 配下に Controller, Service, Repository, Entity をまとめて配置すること。

## 2. メソッド設計と実装品質
* **Service/Controllerの分割**: 機能（パッケージ）内で Service が肥大化する場合は、役割ごとにクラスを分割すること。Controller も同様に分割を許容する。同一 feature パッケージ内に配置する。
    - 例: `AuthService`, `AuthTokenService`, `AuthOAuthService`, `Auth2faService`
    - 例: `AuthLoginController`, `AuthOAuthController`
* **データ変換**: EntityとDTOの変換には **MapStruct** を使用してください。
    - 各機能パッケージ内に `Mapper` インターフェースを作成し、手動での詰め替え（setter地獄）を避けること。

### MapStruct 変換パターン

各機能パッケージ内に `[Feature]Mapper` インターフェースを1つ作成し、`@Mapper(componentModel = "spring")` を付与して Spring Bean として注入する。

```java
// ① 基本変換（フィールド名が一致する場合は自動マッピング）
@Mapper(componentModel = "spring")
public interface UserMapper {
    UserDetailResponse toDetailResponse(UserEntity entity);
    UserSummaryResponse toSummaryResponse(UserEntity entity);
    List<UserSummaryResponse> toSummaryList(List<UserEntity> entities);
}

// ② フィールド名が異なる場合
@Mapping(source = "team.name", target = "teamName")
MemberResponse toResponse(UserEntity user);

// ③ 変換ロジックが必要な場合（default メソッド）
default String formatFullName(UserEntity user) {
    return user.getLastName() + " " + user.getFirstName();
}

// ④ null 時のデフォルト値
@Mapping(target = "avatarUrl", defaultValue = "/images/default-avatar.png")
UserSummaryResponse toSummary(UserEntity entity);
```

**禁止事項**:
- Entity → Entity の変換に MapStruct を使わない（Entity の状態変更はビジネスメソッド経由で行うこと）。
- 1つの Mapper が他機能の Entity に依存する場合は、`uses = {OtherMapper.class}` で委譲する（直接変換しない）。
* **メソッドの長さ**: 1メソッドは **200行以内** とします（絶対上限）。ただし、Service分割の判断基準（§2.1）では **100行超過** で分割を検討するため、実質的には100行を超えたら見直しの対象とし、200行は「いかなる理由でも超えてはならない」ハードリミットとする。
* **ネストの深さ**: `if` や `for` のネストは **3階層以内** を目指し、**早期 return（ガード句）** を活用して可読性を高めてください。
* **文字列結合**: Java 21+ の最適化（`StringConcatFactory`）を活用するため、ループ内を含め、原則として `+` 演算子による結合を許可する。ただし、極端にループ回数が多い場合や、パフォーマンスが最優先される特殊な処理に限り、明示的な `StringBuilder` の使用を検討すること。
* **疎結合の維持**: 外部APIやライブラリに依存する箇所は、`Interface` を介して実装し、差し替え可能な設計にします。
* **保守性の優先**: 常に「未来の自分や他者が読みやすいか」を最優先にコーディングしてください。

### Service分割の判断基準 (Refactoring Triggers)

クラスの肥大化を防ぎ、保守性、拡張性、およびテスト容易性を確保するため、以下の基準（トリガー）のいずれかに抵触した場合は、Serviceクラスの分割またはロジックの抽出を検討してください。

#### 2.1 コードボリューム（物理的指標）
* **クラス全体のサイズ**: 有効行（空行・コメントを除く実コード）が **500行** を超過した場合。
* **メソッドのサイズ**: 1メソッドの有効行が **100行** を超過した場合。
    * **対策**: プライベートメソッドへの抽出、または `InternalHelper` 等のコンポーネントへの委譲を検討する。

#### 2.2 依存関係の密度（構造的指標）
* **インジェクション数**: コンストラクタで注入（DI）している Bean（Repository, Service等）が **7つ** を超過した場合。
    * **理由**: そのクラスが「知りすぎている（多すぎる責務を持っている）」状態であり、結合度が高まりすぎているサインです。

#### 2.3 関心の混在（論理的指標）
純粋なビジネスロジック（ドメインロジック）の中に、以下の「副次的関心事」が混在し始めた場合は分割の対象となります。
* **外部連携**: API呼出、S3アップロード、メッセージキュー送信など。
* **複雑な通知**: テンプレート生成を含むメール送信やプッシュ通知。
* **重いデータ加工**: 大量のリスト操作や、複雑な集計・解析ロジック。
    * **対策**: これらを `XXXClient`, `XXXPublisher`, `XXXAggregator` として別クラスに切り出し、主となるServiceからはそれらを呼び出す形にする。

#### 2.4 テストの複雑性（品質指標）
* **テストクラスの肥大化**: 対応するユニットテスト（JUnit）が **1,000行** を超過した場合。
* **セットアップの困難さ**: `@BeforeEach` 等での Mock 作成やデータ準備が複雑になり、テストの本質（検証内容）が不明瞭になった場合。

## 3. ドキュメント (Javadoc)
すべてのクラスにクラスレベルの Javadoc を必須とし、以下の構成で記述します。

* **クラス説明**:
    1.  1行目：クラスの簡潔な役割説明。
    2.  2行目以降：クラスの詳細な責務、主要な機能の要約。
    3.  特記事項：スレッド安全性、Nullの扱い、依存関係の注意点。
* **メタデータ**: `@author` および `@version` タグの使用を禁止する。作成者や変更履歴は Git のコミットログで厳密に管理されており、ソースコード内での手動記述は情報の不整合（二重管理）を招くため。
* **メソッドレベルの Javadoc**:
    - ビジネスロジックや複雑な条件分岐を持つメソッドには Javadoc を必須とする。
    - CRUD系の自明なメソッド（`findById`, `save`, `delete` 等）や、メソッド名と引数名から意図が明確なメソッドは Javadoc を省略可とする。
    - ビジネスロジックの「なぜ（Why）」を説明するコメントに集中し、「何をしているか（What）」はコード自体で表現すること。

## 4. エラーハンドリングとテスト
* **ユニットテスト**: **JUnit 5** と **Mockito** を使用したテストを `src/test/java` 配下に必ず作成してください。
    - 正常系だけでなく、主要な異常系のカバレッジも考慮すること。
* **テストカバレッジの計測と運用**:
    - **使用ツール**: テスト実行は JUnit 5 を使用し、カバレッジ計測には **JaCoCo** (Java Code Coverage) を採用する。
    - **自動化**: Gradle の JaCoCo プラグインを導入し、`gradle test` 実行時に自動でカバレッジレポート（HTML形式）を生成するよう設定する。
    - **品質ゲート**: プロジェクト全体の目標カバレッジを **80%以上** とする。特に重要なビジネスロジック（Service層）については、より高い網羅率を意識してテストを作成すること。
    - 単純な Getter/Setter や MapStruct 自動生成コードはカバレッジ対象外とする。
    - 数値達成のみを目的とせず、**境界値テスト**や**異常系パターンの網羅**を優先すること。
* **例外の処理**: 例外の握り潰し（catchブロックを空にする）は原則禁止です。必ずログ出力または再スローを行ってください。
* **バリデーション**: メソッドの入り口で **引数チェック（Validation）** を実施し、不正な場合は早期に例外を投げてください。

### バリデーションの責務分離

#### Controller層（Jakarta Bean Validation）— 形式チェック
Request DTO に Jakarta Bean Validation アノテーションを付与し、「入力値の形式」を検証する。
- `@NotBlank`, `@NotNull`, `@Size`, `@Email`, `@Pattern`, `@Min`, `@Max` 等
- 「このフィールドは必須」「8文字以上」「メール形式」といった、ビジネスロジックに依存しない形式検証が対象

#### Service層（コード内チェック）— ビジネスルールチェック
Service メソッド内でビジネスルールを検証し、違反時は `BusinessException` をスローする。
- 「このメールアドレスは既に登録済み」
- 「このチームの定員を超えている」
- 「この操作は ADMIN 権限が必要」

#### ルール
- Controller で形式が正しいことを保証し、Service は形式チェック済みの値だけを扱う
- **カスタムバリデーションアノテーション（`@UniqueEmail` 等）は作成しない**。DB アクセスを伴うチェックは Service の責務であり、アノテーション化すると追跡が困難になるため。
- **グループバリデーション（`groups`）は使わない**。Create / Update で DTO を分離するため不要（`.claudecode.md` §19 参照）。
* **Null安全**: 戻り値が空になる可能性がある場合は `Optional` を検討し、原則として `null` を直接返さないでください。
* **トランザクション管理**:
    - Service クラスのクラスレベルに `@Transactional(readOnly = true)` を付与する（デフォルトを読み取り専用にする）。
    - データの登録・更新・削除（CUD処理）を行うメソッドにのみ、個別に `@Transactional` を付与してオーバーライドする。
    - これにより、読み取り時のDB最適化（スレーブ参照、フラッシュ不要）が自動的に効く。
* **テスト容易性**: 現在時刻や外部通信などの「変動要素」はモック化可能な設計にし、ユニットテストの実行を容易にしてください。

## 5. データアクセスと開発環境

### データアクセス (Spring Data JPA + QueryDSL)
* **基本方式**: データアクセスには **Spring Data JPA** を使用する。各機能パッケージ内に `[Feature]Repository` インターフェースを作成し、`JpaRepository<Entity, Long>` を継承すること。
* **クエリ戦略**:
    * **単純なCRUDおよび名前ベースのクエリ**: Spring Data JPA の派生クエリメソッド（`findByEmail`, `findByStatusAndCreatedAtAfter` 等）を優先的に使用する。
    * **動的フィルタリング**: `.claudecode.md` §13 で定めたフィルタリング規約（`?status=active&price_min=1000` 等）の実装には **QueryDSL** を使用する。`BooleanBuilder` または `BooleanExpression` を用いてタイプセーフに条件を組み立てること。文字列ベースの `@Query` で動的条件を結合する方式は禁止する。
    * **固定的な複雑クエリ**: JOIN が多い集計クエリやレポート用クエリなど、動的条件を伴わない固定的な複雑クエリは `@Query`（JPQL）で記述してよい。ネイティブSQL（`nativeQuery = true`）は最終手段とし、使用時はコメントで理由を記載すること。
* **QueryDSL 設定**: Spring Boot 3.x (Jakarta EE) 環境では、`querydsl-jpa` に **`:jakarta` クラシファイア**を指定すること（`javax` → `jakarta` 名前空間対応）。Gradle の APT（Annotation Processing Tool）を導入し、`Q` クラスを自動生成する。生成されたクラスは `build/generated` 配下に出力し、バージョン管理対象外とする。
```kotlin
// build.gradle.kts
dependencies {
    implementation("com.querydsl:querydsl-jpa:5.1.0:jakarta")
    annotationProcessor("com.querydsl:querydsl-apt:5.1.0:jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
}
```
* **N+1 問題の防止**: リレーションの取得には `@EntityGraph` またはJPQLの `JOIN FETCH` を明示的に使用し、Lazy Loading による N+1 問題を防止すること。

### コネクションプール (HikariCP)
* **使用ライブラリ**: Spring Boot 標準の **HikariCP** をそのまま使用する。別のプールライブラリへの変更は禁止する。
* **設定指針**:
    * `maximum-pool-size`: デフォルト `10`。本番環境ではDB接続数の上限とアプリケーションインスタンス数を考慮して調整する（目安: `DB max_connections / インスタンス数 - 余裕`）。
    * `minimum-idle`: `maximum-pool-size` と同値に設定し、コネクションの急増減を防ぐ（HikariCP 推奨設定）。
    * `connection-timeout`: `30000`ms（30秒）。これを超えた場合は `SQLException` がスローされる。
    * `leak-detection-threshold`: 開発環境では `2000`ms に設定し、コネクションリークを早期検出する。本番環境では `0`（無効）でよい。
* **環境別設定**: プール設定は `application-{profile}.yml` でプロファイル別に管理する。

### 開発環境ツール
* **Spring Boot DevTools**:
    * 開発時の `spring-boot-devtools` 依存を追加し、コード変更時の自動リスタートとライブリロードを有効化する。
    * `build.gradle.kts` では `developmentOnly` スコープで追加し、本番ビルドに含めないこと。
    * テンプレートファイルやプロパティ変更時のキャッシュ無効化も自動で行われる。
* **Spring Boot Docker Compose Support**（Spring Boot 3.1+）:
    * プロジェクトルートに `compose.yml`（MySQL + Valkey）を配置し、`gradle bootRun` 実行時にコンテナを自動起動・自動停止させる。
    * `spring-boot-docker-compose` 依存を追加し、手動での `docker compose up/down` を不要にする。
    * CI 環境では Docker Compose Support を無効化し、Testcontainers を使用すること（`spring.docker.compose.enabled=false`）。

### APIドキュメント (Springdoc OpenAPI) 設定規約
* **グループ化**:
    * `Public API`（`/api/v1/...`）、`Admin API`（`/api/v1/admin/...`）、`System Admin API`（`/api/v1/system-admin/...`）、`Internal API`（`/internal/...`）の4区分でグループを定義する。
* **認証スキーマ**:
    * JWT Bearer 認証を `SecurityScheme` として定義し、Swagger UI 上で全エンドポイントに対してトークンを送信できる構成にする。
* **レスポンス記述**:
    * 各 API には、正常系（200/201）だけでなく、想定される異常系（400, 401, 403, 404）のレスポンス例を明記する。
* **自動同期**:
    * この OpenAPI 仕様を「唯一の正解（Single Source of Truth）」とし、フロントエンドの Zod スキーマ生成のソースとして活用する（FRONTEND_CODING_CONVENTION.md §7「フロント・バック間のバリデーション同期」参照）。

---

## 6. 環境設定・プロファイル管理規約
* **プロファイル分離**: `default`（共通）, `local`（開発）, `test`（テスト）, `prod`（本番）の4種類を基本構成とする。
* **機密情報の秘匿**:
    * パスワード、APIキー、シークレット等の機密情報は設定ファイルに直接記述（ハードコード）することを厳禁とする。
    * 必ず `${ENV_NAME}` 形式で環境変数を参照し、実行環境（Docker/GitHub Actions等）側で注入する。
* **ローカル開発**: `.gitignore` に `application-local.yml` を追加し、個人環境固有の設定がリポジトリに混入しないよう徹底する。
* **検証**: 起動時に必須の環境変数が不足している場合、速やかにエラーで終了（Fail-fast）するよう実装する。

### 初期データ (Seed) 管理規約
* **マスターデータ**: システムの動作に不可欠な固定データ（ロール、権限、モジュール定義）は、`Flyway` の SQL マイグレーションファイルを使用して投入する。
* **アカウント・デモデータ**: 管理者ユーザーの作成など、ビジネスロジック（パスワードのハッシュ化等）を伴う初期データは、Spring Boot の `ApplicationRunner` を使用して投入する。
* **環境別投入**: 開発環境専用のデモデータは、プロファイル（`local`, `dev`）が有効な場合のみ実行されるよう `@Profile` で制御する。
* **冪等性の担保**: すでにデータが存在する場合はスキップするなど、何度起動してもエラーにならない（冪等性）実装を徹底する。

### CI/CD パイプライン規約 (GitHub Actions)
CI/CD パイプラインの詳細設定（ワークフロー YAML、JaCoCo 集計、ブランチ保護ルール等）は `TEST_CONVENTION.md` §8 に定義する。ここでは方針のみ記載する。
* **トリガー**: `main` ブランチへのプルリクエスト作成時、および `main` へのマージ時に自動実行する。
* **バックエンド**: Build → Checkstyle → SpotBugs → Unit Tests → Integration Tests → JaCoCo（80%未満で失敗）。
* **フロントエンド**（同リポジトリ `frontend/` 配下）: ESLint + Prettier → Type Check → Vitest。
* **マージ条件**: すべてのチェックが「Pass」かつ、1名以上の承認（Approve）がある場合のみマージ可能とする。

## 7. ログと運用監視
* **ログフレームワーク**: **SLF4J + Logback**（Spring Boot標準）を使用する。`System.out.println` によるログ出力は**禁止**とし、Lombok の `@Slf4j` アノテーションを使用すること。
* **トレーサビリティ**: ログには「誰が」「いつ」「何を（どのIDに対して）」操作したかを特定できる情報を必ず含めてください。
* **ログレベル**: 適切にレベルを使い分け、障害発生時の追跡性を確保してください。
    - `ERROR`: システム異常・予期しない例外（即時対応が必要）
    - `WARN`: 想定内だが注意が必要な状態（リトライ発生、閾値超過等）
    - `INFO`: 業務上の重要なイベント（ログイン、データ更新等）
    - `DEBUG`: 開発・デバッグ用の詳細情報（本番環境では原則OFF）
* **エラーログ**: 例外発生時は必ず**スタックトレースを含めて**ログ出力すること（`log.error("message", exception)` の形式）。
* **構造化ログ**: 将来的なログ集約基盤（ELK等）への対応を見据え、ログメッセージにはキーバリュー形式の情報を含めることを推奨する（例: `log.info("ユーザー登録完了 userId={}", userId)`）。

### 監査ログ記録規約
* **必須記録イベント**:
    * 認証関連: ログイン、ログアウト、連続するログイン失敗。
    * 権限関連: ユーザー招待・削除、ロール（権限）の変更。
    * 設定関連: システム設定の変更、課金プランの変更。
    * データ操作: データの物理削除、大量データのCSVエクスポート。
* **記録項目**: タイムスタンプ、実行ユーザーID、操作種別、対象リソース識別子、IPアドレス、実行結果（成功/失敗）。
* **保存期間**: セキュリティおよびコンプライアンスの観点から、最低 **2年間** は検索可能な状態で保持する（README.md DB設計と統一。設定変更可）。

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
* **保存場所**: フロントエンドでは `localStorage` / `sessionStorage` に保存し、`Authorization: Bearer` ヘッダーで送信する。Cookie には格納しない（CSRF攻撃を構造的に排除するため）。
* **デバイスバインディング（推奨）**: Refresh Token 使用時に、発行時のIPアドレスおよびUser-Agentとの一致を検証することを推奨する。不一致の場合はトークンを無効化し、再ログインを要求する。これにより、XSSで Refresh Token が漏洩した場合でも、異なるデバイスからの悪用を防止できる。

### WebSocket (STOMP) 認証規約
* **認証方式**: REST API と同様に JWT を使用するが、セキュリティ確保のためクエリパラメータでのトークン送付は禁止する。
* **トークン送付タイミング**: STOMP の `CONNECT` フレーム送信時に、カスタムヘッダー（`Authorization`）として Bearer トークンを付与する。
* **サーバー側検証**:
    * `ChannelInterceptor` を実装し、`preSend` メソッドにて `CONNECT` コマンド受信時のヘッダー検証を行う。
    * 有効なトークンが確認できない場合は、接続を拒否（例外をスロー）する。
* **ハンドシェイク時**: 初期接続（HTTP Handshake）時は認証をスキップし、その後の STOMP プロトコルレベルで認証を強制する構成とする。

### レートリミット（リクエスト制限）規約
* **必須適用箇所**:
    * ログインAPI（認証試行）
    * パスワードリセット・メール送信API
    * 公開API全般（認証不要エンドポイントは特に厳格に適用すること）
* **実装方針**: Spring Boot 環境において、`Bucket4j` または `Valkey` を用いたレートリミット機構を実装する。
* **制限値の目安**:
    * 認証系: 同一IPにつき「1分間に10回まで」等の厳格な制限。
    * 一般API: サービスの特性に応じた妥当なバースト許容値を設定。
    * **`POST /error-reports`（エラーレポート送信）**: 認証不要の公開エンドポイントのため以下の二段階制限を適用する。
        * 短期制限: 同一IPにつき **1分間に5回まで**（バースト的な連送を防止）
        * 日次制限: 同一IPにつき **1日50回まで**（持続的な悪用・DoSを防止）
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
