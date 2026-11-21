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

### エラーメッセージの多言語化（CMP-261113-1507）
API のエラー応答 `error.message` は BE が利用者の言語（ログイン済みは users.locale、未ログインは Accept-Language）で返す。詳細は `docs/features/F11.3_ui_i18n.md` §4.7.1。
* **新しい ErrorCode を足すときは 5 言語の訳も同じ PR で足す**: enum に ja 文言（正本）を書き、`messages_{en,de,zh,ko,es}.properties` に導出キー（`TEAM_001 → error.team.001`）の訳を追加する。欠落・日本語の取り残し・プレースホルダ不一致は番人 `ErrorMessageI18nGuardTest` が CI で落とす。
* **埋め込み値は `BusinessException.withMessageArgs(code, cause, args...)`** で渡し、文言は `{0}` 形式で書く。引数ありのときだけ MessageFormat を通るため、訳文のアポストロフィは typographic な `’` を使う（ASCII の `'` を書くなら、引数ありの訳は `''`、引数なしは `'` 1 個）。数値 ID は桁区切りを避けるため `String.valueOf` で渡す。
* **エラー応答は `GlobalExceptionHandler`（`ErrorMessageResolver`）経由で作る**。`ErrorResponse.of(errorCode)` は enum 文言（ja）を直接入れるため、ハンドラやコントローラで応答本文を作るのに使わない。
* **DispatcherServlet より前（Security のフィルタ鎖・前段フィルタ）で応答を書くときは `EarlyErrorResponseWriter`** を使う。`{"error":"<日本語>"}` のような独自形式・文言の直書きは禁止。
* **`ResponseStatusException` に日本語の reason を書かない**。ja 以外の言語では共通コードの訳文に置き換わり、reason の内容は失われる。業務エラーは訳あり ErrorCode の `BusinessException` で投げる。

### バリデーションの責務分離

#### Controller層（Jakarta Bean Validation）— 形式チェック
Request DTO に Jakarta Bean Validation アノテーションを付与し、「入力値の形式」を検証する。
- `@NotBlank`, `@NotNull`, `@Size`, `@Email`, `@Pattern`, `@Min`, `@Max` 等
- 「このフィールドは必須」「8文字以上」「メール形式」といった、ビジネスロジックに依存しない形式検証が対象
- **ID・要素のリストを受けるリクエスト DTO には必ず `@Size(max = …)` を付ける**（無制限リスト＝無制限クエリ・DoS）。上限は業務上の最大件数で決める。

#### Service層（コード内チェック）— ビジネスルールチェック
Service メソッド内でビジネスルールを検証し、違反時は `BusinessException` をスローする。
- 「このメールアドレスは既に登録済み」
- 「このチームの定員を超えている」
- 「この操作は ADMIN 権限が必要」

#### ルール
- Controller で形式が正しいことを保証し、Service は形式チェック済みの値だけを扱う
- **カスタムバリデーションアノテーション（`@UniqueEmail` 等）は作成しない**。DB アクセスを伴うチェックは Service の責務であり、アノテーション化すると追跡が困難になるため。
- **グループバリデーション（`groups`）は使わない**。Create / Update で DTO を分離するため不要（`.claudecode.md` §19 参照）。
- **`GlobalExceptionHandler` は `@RequestBody` の `MethodArgumentNotValidException` に加え、`@RequestParam` / `@PathVariable` の `@Min` / `@Max` 等（Spring 6.1 以降の `HandlerMethodValidationException`）でも 400 `COMMON_001` + `fieldErrors` を返す**。パラメータ検証の違反を 500 や `fieldErrors` 空にしないこと（CMP-261001-0630 で追加。F03.13 の `periodNumber` 1〜15 等）。
- 本人の出生情報・診断・恐竜への命名など、私的入力の非露出契約を持つ経路では、入力由来の違反メッセージや `fieldErrors` を返さず、既存の `privateInputRejected` による固定400と `Cache-Control: no-store` を返す。一般の入力検証では上記のフィールド別応答を維持する。

### Request DTO にコンストラクタを 2 本目以降足すときは `@JsonCreator` 必須（番人 D-7）

`@RequestBody` / `@RequestPart` で受ける DTO（およびその入れ子 DTO）に**コンストラクタを 2 本以上**
持たせる場合、**完全コンストラクタに `@JsonCreator` を付け、各引数に `@JsonProperty("...")` を付ける**こと。

```java
// NG: 後方互換用の短いコンストラクタを足した瞬間、この DTO を受ける POST は「常時 500」になる
@Getter
public class CreateFooRequest {
    private final String title;
    private final String body;
    public CreateFooRequest(String title) { this(title, null); }
    public CreateFooRequest(String title, String body) { ... }
}

// OK: 金型は chat/dto/SendMessageRequest
@JsonCreator
public CreateFooRequest(@JsonProperty("title") String title,
                        @JsonProperty("body") String body) { ... }
```

理由: Jackson はコンストラクタが複数あるとデシリアライズ用を一意に決められず、
「引数無しコンストラクタ無し」だと no suitable creator でデシリアライザ構築に失敗する。
Spring はこれを `HttpMessageConversionException`（400 にマップ済みの
`HttpMessageNotReadableException` とは**別系統**）で投げるため、`GlobalExceptionHandler` に届かず
**body の内容によらず 500** になる。Mock ベースの Service UT では原理的に検出できず、
`SendMessageRequest`（PR #2033）/ `CreateThreadRequest`（PR #2503）で 2 度再発した。

- コンストラクタが **1 本だけ**なら `@JsonCreator` は不要（`-parameters` ＋ `ParameterNamesModule` で暗黙 creator になる）
- Lombok の `@Data + @NoArgsConstructor + @AllArgsConstructor` 様式も可（引数無しコンストラクタ＋setter で成立する）
- `record` は Jackson が正準コンストラクタをネイティブ解決するため、コンストラクタが複数でも `@JsonCreator` は不要
- `@JsonCreator` は **1 本だけ**付けること。2 本のコンストラクタに付けると Jackson が
  properties-based creator を一意に決められず（conflicting property-based creators）、
  **付けていないのと同じく常時 500** になる（delegating creator と 1 本ずつの共存のみ可）
- `@JsonCreator(mode = Mode.DISABLED)` は creator の**明示的な打ち消し**であり、
  「付いているのに creator が 1 本も無い」状態になる。救済にはならない
- 機械検出: `JsonRequestBodyCreatorArchTest`（`common/architecture`。`.claudecode.md` §30）。
  その構造条件が実際の壊れ方と一致していることは `JsonRequestBodyCreatorRuntimeProofTest` が
  本番同等設定の実 `ObjectMapper` でデシリアライズを走らせて実測固定している

#### `@ModelAttribute`（フォームバインド）DTO は `@JsonCreator` では救えない

`@ModelAttribute` で受ける DTO（および Controller メソッドの**無注釈複合型引数**）は、
`BeanUtils.getResolvableConstructor` が「Kotlin primary → **宣言コンストラクタがちょうど 1 本** →
引数無しコンストラクタ」の順にしか解決しない。**Jackson の注釈は一切見ない**ため、
`@JsonCreator` を付けても「2 本以上＋引数無し無し」なら `IllegalStateException` で同じく 500 になる。

フォーム DTO は **引数無しコンストラクタ ＋ setter**（`@Getter @Setter` ＋暗黙 no-arg。
main の実在例は `recruitment/dto/RecruitmentListingSearchRequest`）にするか、
**コンストラクタを 1 本に保つ**こと。こちらも同じ番人 D-7 が検出する。

* **Null安全**: 戻り値が空になる可能性がある場合は `Optional` を検討し、原則として `null` を直接返さないでください。
* **トランザクション管理**:
    - Service クラスのクラスレベルに `@Transactional(readOnly = true)` を付与する（デフォルトを読み取り専用にする）。
    - データの登録・更新・削除（CUD処理）を行うメソッドにのみ、個別に `@Transactional` を付与してオーバーライドする。
    - これにより、読み取り時のDB最適化（スレーブ参照、フラッシュ不要）が自動的に効く。
    - **例外（他ドメインの権限確認・名前解決を読む Service）**: 他ドメインの権限確認（`AccessControlService` 等）や名前解決（`NameResolverService` 等）を読む Service には、**クラス単位の TX を付けない**。書き込みはメソッド単位の `@Transactional` を必ず持ち、**別の部品（書き込み専用 Bean）に分ける**（同じクラス内の自己呼び出しはプロキシを通らず TX が掛からないため）。クラス単位の readOnly TX を付けると、private メソッドを含む全メソッドが番人 D-3T（`CrossDomainTransactionalTransitiveArchTest`）の入口になり、権限確認が自ドメインの TX の中で走ってドメイン境界原則5に違反する。他ドメインへの監査記録などの副作用は、書き込み TX の中でイベントを発行し `@TransactionalEventListener(AFTER_COMMIT)` で記録する。
        - なお `ReplicaRoutingAspect` はメソッドに付いた `@Transactional` しか見ないため、クラス単位の readOnly を外しても DB の振り分け先は変わらない。
        - 実例: `member/service/MemberSubtabVisibilityService`（段取り役）＋ `MemberSubtabVisibilityWriter`（書き込み専用）、`TeamPageService`・`MemberProfileService`・`TeamPageSectionService`（PR #3387）、`billing/beta/MembershipQueryService`。構造は `MemberTransactionBoundaryTest` が固定している。
* **テスト容易性**: 現在時刻や外部通信などの「変動要素」はモック化可能な設計にし、ユニットテストの実行を容易にしてください。

## 5. データアクセスと開発環境

> **詳細ファイル（必読）**: 本節の本文（コード例・経緯・番人の細則を含む）は [`docs/conventions/backend/05-data-access-dev-env.md`](../docs/conventions/backend/05-data-access-dev-env.md) に逐語で移設した。Repository・派生クエリ・Entity 更新・`@Column` を触る作業、Testcontainers／実スキーマテストを書く作業では、必ず詳細ファイルを開いて読むこと。以下は必須・禁止規則の要点である。

### 要点

**STRING永続化enumの定数追加**
- `@Enumerated(EnumType.STRING)` の定数を足すときは、`.claudecode.md` §23 と [`二段階展開の作法`](../docs/development/persisted_enum_deployment.md) に従う。
- 定数だけを全タスクへ配り、旧タスクの退場を確認してから別リリースで書込みを始める。
- 番人 `PersistedEnumCompatibilityArchTest` が検知した追加を互換性台帳へ反映する前に、PRで書込み経路・二段階計画・rollback下限をレビューする。

**データアクセス (Spring Data JPA + QueryDSL)**
- データアクセスは **Spring Data JPA**。各機能パッケージ内に `[Feature]Repository` を作り、`JpaRepository<Entity, Long>` を継承する。
- 単純なCRUD・名前ベースのクエリは、派生クエリメソッド（`findByEmail` 等）を優先する。
- 動的フィルタリング（`.claudecode.md` §13 のフィルタリング規約）は **QueryDSL**（`BooleanBuilder` / `BooleanExpression`）でタイプセーフに組み立てる。**文字列ベースの `@Query` で動的条件を結合する方式は禁止**。
- 動的条件を伴わない固定的な複雑クエリ（JOIN の多い集計・レポート）は `@Query`（JPQL）で書いてよい。ネイティブSQL（`nativeQuery = true`）は最終手段で、使用時はコメントで理由を記載する。
- QueryDSL は `querydsl-jpa` に **`:jakarta` クラシファイア**を指定し、Gradle の APT で `Q` クラスを自動生成する。生成物は `build/generated` 配下に出力し、バージョン管理対象外とする。
- N+1 防止のため、リレーション取得には `@EntityGraph` または JPQL の `JOIN FETCH` を明示的に使う（Lazy Loading による N+1 を防ぐ）。**ループ（`for` / `forEach` / `stream`）内で Repository・他 Service の単件取得・`count`・`exists`・`save` を呼ばない**（ID を集めて `findAllById` / `…In` / `GROUP BY` / `saveAll` にする）。`@Transactional` 内の managed エンティティへの `save()` は不要。内部の `PageRequest.of(0, 10000)` / `Pageable.unpaged()` は `max-page-size` が効かない。詳細・例は [05 データアクセス](../docs/conventions/backend/05-data-access-dev-env.md) の「N+1 問題の防止」。

**派生クエリの引数型は Entity 属性の型と一致させる（`@Enumerated` 属性に `String` を渡さない）**
- 派生クエリの引数型は **Entity の属性型をそのまま書く**（`@Enumerated` 属性なら enum、`String` 属性なら `String`）。`String` で受けるとコンパイル・起動は通るが、そのクエリが呼ばれた瞬間に必ず HTTP 500 になる。
- 呼び出し側で `.name()` / `valueOf()` を書きたくなったら引数型が間違っているサイン。変換ではなく宣言を直す。
- ドメインをまたいで enum を渡すときは、写像専用メソッドを 1 つ設けて明示的に写像する。**このとき `switch` を使ってはならない**（別ドメイン enum への `switch` は合成クラス `OuterClass$1` が生成され ArchUnit D-1 `CrossDomainEntityImportArchTest` を落とす）。`==` による enum 参照比較で分岐し、未知値は `IllegalStateException` を投げる。黙って `null` や空リストを返して握りつぶすのは禁止。
- `switch` を捨てる代わりに、写像元 enum の `values()` を**全件ループ**して 1 つずつ写像が成功することを検査する番人テストを必ず置く（定数を個別に書き並べる形は不可。実例 `NotificationScopeTypeMappingTest`）。
- **enum 属性を条件に含む派生クエリは、実 DB（Testcontainers）を通すテストを必ず 1 本置く**。リポジトリをモックした単体テストだけで済ませてはならない（モックでは原理的に検出できない）。
- ネイティブクエリ（`nativeQuery = true`）は SQL 文字列として評価されるので `String` で正しい。JPQL／派生クエリのみが本ルールの対象。

**Entity 更新パターン — managed entity の直接ミューテートで行う（`toBuilder().build() → save` 禁止）**
- 既存エンティティの更新は、`findById` で取得した **managed entity にドメインメソッド（`applyUpdate` 等）でその場でミューテートし、そのまま `save(entity)`** する。`toBuilder().build()` で再構築して `save()` する方式は禁止（`id` が落ちて静かに INSERT 化／行の二重化を招く）。
- `toBuilder()` / `builder()` を使ってよい用途は、新規エンティティの作成、レスポンス DTO の組み立て、`@Id` を自前宣言しているエンティティ、TestFixture での複製に限る。
- 継承する Entity（`UuidV7Entity` / `BaseEntity` 継承）は **`@SuperBuilder(toBuilder = true)` が標準**。継承を持たない単純 DTO は `@Builder` でよい。
- 限定例外（CMP-260820-1017）: `ResidentRegistryEntity` は、新規登録の安全な16入力だけを受け取る private constructor に `@Builder` を付ける。公開 builder に死亡・転出・論理削除・監査・派生状態や継承 ID／日時を含めず、`toBuilder()` は提供しない。初期状態は Entity 内で定め、更新は既存の業務メソッドで managed entity に直接行う。JPA 用の protected 無引数 constructor は維持する。
- 移行時の注意①: 旧コードの null ガード（「リクエストが `null` のフィールドは既存値を維持する」）のセマンティクスを `applyUpdate` 内または呼び出し側で正確に再現する。
- 移行時の注意②: 監査ログ・通知・状態遷移など旧値と新値を比較して副作用を起こす処理がある場合は、ミューテートの**前に**旧値をローカル変数へ退避してから比較・記録する。

**Entity フィールドの `@Column(name)` 必須ルール**
- フィールド名に「数字→大文字」の並び（`S3Key` / `S3Url` / `S3Bucket`、`PdfUrl` / `PdfKey` / `Pdf2`、`HtmlUrl` / `HtmlContent`、その他数字と大文字が隣接する略語全般）を含む場合は、**`@Column(name = "...")` を DDL の実列名に合わせて必ず明示する**（Hibernate の物理命名戦略は数字→大文字の境界で `_` を挿入せず、Flyway の列名と食い違うため）。
- `ddl-auto=create` のテストでは自己整合で通過して検出できない。S3Key 系フィールドを持つ Entity には、専用の Flyway 実スキーマテストクラスを作成する（JPA 往復確認に加え、ネイティブ SQL で DDL 実列名を直接確認する）。
- 機械的な番人: `EntityDigitBoundaryColumnNameGuardTest`（全 `@Entity` を反射走査）と `EntityDigitBoundaryColumnFlywaySchemaIT`（実 Flyway スキーマ）。
- `@SpringBootTest(properties=...)` でプロパティを上書きすると別の ApplicationContext が生成される（OOM 回避のため、このパターンのクラスを不必要に増やさない）。参照実装は `ProxyInputConsentS3KeyFlywaySchemaTest`。

**コネクションプール (HikariCP)**
- Spring Boot 標準の **HikariCP** をそのまま使い、別のプールライブラリへの変更は禁止。
- `maximum-pool-size` は既定 `10`（本番は DB 接続数上限とインスタンス数を考慮して調整）、`minimum-idle` は `maximum-pool-size` と同値、`connection-timeout` は `30000`ms、`leak-detection-threshold` は開発 `2000`ms・本番 `0`（無効）でよい。
- プール設定は `application-{profile}.yml` でプロファイル別に管理する。

**開発環境ツール**
- Spring Boot DevTools は `build.gradle.kts` の **`developmentOnly`** スコープで追加し、本番ビルドに含めない。
- Docker Compose Support（Spring Boot 3.1+）は、プロジェクトルートの `compose.yml`（MySQL + Valkey）と `spring-boot-docker-compose` 依存で `gradle bootRun` 時にコンテナを自動起動・停止する。**CI 環境では無効化し、Testcontainers を使う**（`spring.docker.compose.enabled=false`）。

**APIドキュメント (Springdoc OpenAPI)**
- グループは `Public API`（`/api/v1/...`）、`Admin API`（`/api/v1/admin/...`）、`System Admin API`（`/api/v1/system-admin/...`）、`Internal API`（`/internal/...`）の4区分で定義する。
- JWT Bearer 認証を `SecurityScheme` として定義し、Swagger UI 上で全エンドポイントにトークンを送信できる構成にする。
- 各 API に、正常系（200/201）だけでなく想定される異常系（400, 401, 403, 404）のレスポンス例を明記する。
- この OpenAPI 仕様を「唯一の正解（Single Source of Truth）」とし、フロントエンドの Zod スキーマ生成のソースにする（FRONTEND_CODING_CONVENTION.md §7 参照）。

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

> **詳細ファイル（必読）**: 本節の本文（設定値・コード例・手順を含む）は [`docs/conventions/backend/08-security.md`](../docs/conventions/backend/08-security.md) に逐語で移設した。認証・認可・JWT・WebSocket・レートリミット・CORS・Pre-signed URL・CSP を触る作業、および静的解析（Checkstyle / SpotBugs）・EditorConfig・IDE 設定・pre-commit フック・TestFixture・テスト実行環境（本節末尾に同居）を触る作業では、必ず詳細ファイルを開いて読むこと。以下は必須・禁止規則の要点である。

### 要点

**基本方針**
- APIキー・パスワード・暗号化鍵などをソースコードに直接記述（ハードコード）することを厳禁とする。
- SQL 発行時は文字列結合を避け、必ず**プレースホルダ**を使う。
- パスワードや機密性の高い個人情報をログに出力しない。
- ID 等のパラメータを書き換えて他者のデータにアクセスできないよう、処理の入り口で必ず**実行権限チェック**を行う。
- ユーザー入力値を画面表示する際は、サニタイズ（エスケープ）を徹底する。

**パスワード保存・管理**
- パスワードの平文保存はいかなる理由があっても禁止。`bcrypt` を使い、適切にソルトを付与する。コストファクターは **10〜12** 程度（またはそれ以上）。

**パスワードポリシー**
- 最低 8 文字以上（推奨 12 文字以上）。英大文字・小文字・数字・記号のうち **3 種類以上**の組み合わせを必須とする。
- ユーザーIDと同じ文字列や一般的すぎるパスワード（`123456`, `password` 等）を拒否するチェックを実装する。

**JWT 運用**
- Access Token は **15分**、Refresh Token は **7日間**。
- Refresh Token での Access Token 発行時は Refresh Token 自体も新しく発行し、古いものを無効化する（ローテーション）。
- ログアウト時は該当トークンを Valkey 上のブラックリストに登録し、期限内でも即座に使用不能にする。
- 認証は二本立てである。**Web は `HttpOnly`・`SameSite=Strict` の Cookie**（`access_token` と `refresh_token`）、**ネイティブアプリは `Authorization: Bearer` ヘッダー**で送る。BE は Bearer を優先し、無ければ Cookie を読む（`JwtAuthenticationFilter`）。FE はトークンをメモリにだけ持ち、`localStorage` / `sessionStorage` には保存しない。CSRF は `SameSite=Strict` と同一オリジンで防ぐ（2026-11-17 マスター裁可により、実装と `docs/features/F01.1_auth.md`・`docs/security/02_cookie_and_session.md` に合わせて改訂）。
- デバイスバインディング（推奨）: Refresh Token 使用時に発行時の IP アドレスと User-Agent の一致を検証し、不一致ならトークンを無効化して再ログインを要求する。

**WebSocket (STOMP) 認証**
- 利用者の JWT と掲示端末 capability を区別し、WebSocket URL の query を資格情報として受け付けない。
- JWT は STOMP `CONNECT` の `Authorization`、掲示端末 token は専用 native header `X-Signage-Token` で渡す。Bearer/Cookie/匿名の優先順位は `docs/security/01_authorization_baseline.md` §5 に従う。
- 明示した無効資格は拒否し、資格なし CONNECT は匿名として各 topic の購読・配送境界で認可する。掲示端末は購読時と recipient ごとの配送時に token・画面の現在状態を検証する。
- 詳細なヘッダー処理・保持情報・HTTP upgrade の契約は [§8 詳細](../docs/conventions/backend/08-security.md#websocket-stomp-認証規約) を正本とする。

**レートリミット**
- 必須適用箇所: ログイン API（認証試行）、パスワードリセット・メール送信 API、公開 API 全般（認証不要エンドポイントは特に厳格に）。
- `Bucket4j` または `Valkey` を用いたレートリミット機構を実装する。
- 目安: 認証系は同一 IP につき「1 分間に 10 回まで」等の厳格な制限。一般 API はサービス特性に応じた妥当なバースト許容値を設定。
- `POST /error-reports`（認証不要の公開エンドポイント）は同一 IP につき **1 分間に 10 回まで**（実装 `ErrorReportRateLimitFilter`、F12.5 §5.6）。超過時は `429 Too Many Requests` と `Retry-After` ヘッダーで再試行可能時刻を通知する。
- 制限超過時は HTTP ステータス `429 Too Many Requests` を返す。

**CORS**
- `AllowedOrigins` に `*`（すべて許可）を設定することを**禁止**。信頼できる特定ドメインのみ許可する。
- 許可 Origin はコードにハードコードせず、`MANNSCHAFT_ALLOWED_ORIGINS` 環境変数として外部から注入する（`.claudecode.md` §21 命名規約に準拠）。
- 開発環境（localhost 等）と本番環境で許可リストを切り分け、本番に不要なドメインが含まれないようにする。

**ストレージ（Pre-signed URL）**
- 有効期限は原則 **5〜15分** 以内。
- 発行時に `content-length-range` を指定して期待サイズ（例: 5MB 以下）超過を拒否し、`Content-Type` を固定して予期せぬ形式を防ぐ。
- 1 つの URL で実行できる操作を 1 回のみ（Single Use）とする実装を検討する。

**CSP**
- HTTP レスポンスヘッダーに `Content-Security-Policy` を設定する。`default-src 'self';` を基本とし、`script-src 'self';` としてインラインスクリプトの実行を禁止する。
- 外部リソースは業務上必要なドメインのみを明示的にホワイトリストへ追加する。
- 開発初期は `Content-Security-Policy-Report-Only` で動作を妨げないことを確認してから正式に適用（Enforce）する。

**API リクエストサイズ制限**
- リクエストボディは原則 **1MB 以下**。
- ファイルアップロードは Pre-signed URL 経由とし、API サーバーに直接送信しない。`spring.servlet.multipart.max-file-size` は小さく設定する（例: 1MB）。
- 例外: アバター画像など小さなファイルを API 経由で受ける場合は、エンドポイントごとに個別設定する。

**静的解析 (Checkstyle)**
- Gradle の Checkstyle プラグインを導入し、`gradle checkstyleMain` で検証する。設定は `config/checkstyle/checkstyle.xml`（Google Java Style ベース）。
- 主な検査項目: インデント（半角スペース4つ）、命名規則（camelCase / PascalCase / UPPER_SNAKE_CASE）、メソッド行数上限（200行）、ネスト深度上限（3階層）、`import` の整理（ワイルドカード `*` 禁止・未使用検出）、Javadoc の存在チェック（クラスレベル必須）。
- CI に `checkstyleMain` を組み込み、違反があればビルドを失敗させる。
- やむを得ない抑制は `@SuppressWarnings("checkstyle:ルール名")` で個別に行い理由をコメントに残す。**ファイル単位の抑制（`suppressions.xml`）は原則禁止**。

**静的バグ検出 (SpotBugs)**
- Gradle の SpotBugs プラグインを導入し、`gradle spotbugsMain` で検出する。Checkstyle とは役割が異なるため併用する。
- 報告レベルは `medium` 以上、検出努力（effort）は `max` を基本とする。
- CI に `spotbugsMain` を組み込み、`medium` 以上のバグが検出されたらビルドを失敗させる。
- 誤検知の抑制は `@SuppressFBWarnings(value = "ルール名", justification = "理由")` で個別に行い、理由を必ず明記する。

**EditorConfig**
- プロジェクトルートに `.editorconfig` を配置し、バックエンド・フロントエンド共通で適用する。
- 主な設定: `charset = utf-8`、`end_of_line = lf`、`indent_style = space`、Java は `indent_size = 4`、Vue / TypeScript / JSON / YAML は `indent_size = 2`、`trim_trailing_whitespace = true`、`insert_final_newline = true`。

**IDE 設定（バックエンド）**
- VS Code: `.vscode/extensions.json` に推奨拡張機能（`vscjava.vscode-java-pack` / `shengchen.vscode-checkstyle` / `editorconfig.editorconfig` / `eamodio.gitlens`）、`.vscode/settings.json` にワークスペース設定を置く。
- IntelliJ IDEA: `CheckStyle-IDEA` と Save Actions を使い、保存時に `Reformat code` と `Optimize imports` を有効化し、Checkstyle 設定を Import Scheme でインポートして IDE のフォーマッタと規約を一致させる。`.idea/` 配下は `.gitignore` で除外し、共有が必要な設定は README かセットアップガイドに手順を記載する。

**pre-commit フック**
- コミット前に静的解析・フォーマットチェックを自動実行する。バックエンドは `gradle-git-hooks` プラグイン（`preCommit = "checkstyleMain spotbugsMain"`、クローン後に `./gradlew installGitHooks`）または `.githooks/pre-commit` ＋ `git config core.hooksPath .githooks`。フロントエンド（`frontend/`）は **Husky + lint-staged**（ステージ済みファイルのみ対象）。
- フックのスキップ（`git commit --no-verify`）は緊急時のみに限定し、通常の開発では**禁止**。
- 新規開発者のオンボーディング手順に `./gradlew installGitHooks`（バックエンド）と `npm install`（フロントエンド、Husky が `prepare` で自動設定）を含める。

**テストデータ作成パターン（TestFixture 方式）**
- テストデータ生成は **TestFixture クラス**に集約する。配置は共通が `src/test/java/com/mannschaft/app/common/TestFixture.java`、機能固有が `[feature]/[Feature]TestFixture.java`。「最低限有効なデフォルト値」を持つ static メソッドを用意し、テストごとに必要なフィールドだけオーバーライドする。
- DB に保存する場合は **Repository 経由**で行う。**手書きの INSERT SQL は禁止**（エンティティの変更に追従できなくなるため）。
- テスト間でデータが干渉しないよう、`@Transactional`（自動ロールバック）または `@BeforeEach` でクリーンアップする。
- テストメソッド名は日本語を許容する（テストの意図を明確にすることを優先）。

**テスト実行環境**
- DB を用いた統合テストには **Testcontainers**（MySQL 8.0）を使う（ローカルに MySQL のインストールは不要）。
- CI でも Testcontainers を実行する（Docker-in-Docker または Docker Socket マウント方式）。
- 統合テストとユニットテストは Gradle タスクで分離可能にする（例: `gradle test` でユニット、`gradle integrationTest` で統合）。
