# 01. 認可基盤（Authorization Baseline）

> **ステータス**: 🟢 設計確定・deny-by-default 実装済み
> **実装フェーズ**: Security Hardening Phase 1
> **最終更新**: 2026-06-12
> **関連ドキュメント**: [README](README.md), F01.2-04 セキュリティ運用, F03.5-04 セキュリティ運用, F19.1 公開ページ, F09.18 メール配信基盤

---

## 1. 概要

`SecurityConfig` の `SecurityFilterChain` における **既定の認可方針** を定義する。

**中心となる方針転換**: 旧来の `.anyRequest().permitAll()`（全許可フォールバック + JwtFilter/`@PreAuthorize` が個別防御）から、**`.anyRequest().authenticated()`（deny-by-default）** へ移行した。これにより「許可リストに無いエンドポイントは認証必須」が保証され、新規 Controller を追加した際に認可設定を忘れても無防備に公開されることがなくなる。

> 🟢 **実装済み（#1266・2026-06-02 点火）**: `SecurityConfig.java` の `anyRequest().authenticated()` 反転・`/api/v1/system-admin/**` 包括ルール・全 webhook 許可リスト・公開 GET の permitAll 登録が完了している。

### 解決した課題（根治済み）
- ~~現状フォールバックは「Controller 側の `@PreAuthorize` 漏れ = そのまま全世界公開」というフェイルオープン構造~~ → **deny-by-default 反転で解消**
- ~~`SecurityConfig.java:173-184` の TODO（system-admin 包括ルール未設定）が本番リスクとして残置~~ → **`/api/v1/system-admin/**` 包括ルール追加で解消**

---

## 2. 認可モデル（多層防御）

| 層 | 役割 | 例 |
|---|---|---|
| **SecurityFilterChain**（粗い境界） | パス単位で「公開 / 認証必須 / ロール必須」を決める | `/api/v1/public/**` は公開、`/api/v1/system-admin/**` は SYSTEM_ADMIN |
| **`@PreAuthorize` / Service 層**（細かい所有権） | リソースの所有権・テナント・メンバーシップを検証 | `@quickMemoAccessGuard.canAccess(...)`, `accessControlService.checkMembership(...)` |

SecurityFilterChain は「最低限のゲート」、所有権の最終判定は Service/`@PreAuthorize` が担う。**両方を必ず通す**（片方だけに依存しない）。

### BOLA / IDOR 防止（横断）
- テナント絞り込みは `AbstractTenantAwareRepository`（`findByIdAndOrganizationIdAndDeletedAtIsNull` 等）に統一
- 所有権検証は `AccessControlService`（`checkMembership` / ロール判定）に集約
- 公開 API のパスパターンは **`*`（1 階層厳格）** を使い、`/**`（再帰）を避ける（連番 ID 推測による横断アクセスの面を最小化。F19.1 §17.8 / F15.4 Phase 5 §4.2 と整合）

---

## 3. 公開エンドポイント許可リスト（permitAll）

`.authenticated()` 反転後も**認証不要**で到達できる必要があるエンドポイント。これ以外は全て認証必須。

### 3.0 公開根拠の引用規約 — `@IntentionallyPublic` は **matcher 式**を引用する（2026-07-30 改訂）

`permitAll` の EP には監査済マーカー `@IntentionallyPublic` を付与し、**根拠となる matcher 式（パス文字列）を注釈の属性に列挙する**。

```java
@IntentionallyPublic("/api/v1/public/stats")            // 単一
@IntentionallyPublic({                                   // 複数
        "/api/v1/public/teams/*/faqs",
        "/api/v1/public/organizations/*/faqs"
})
```

**なぜ行番号引用をやめたか（実測に基づく規約変更）:**

旧規約は `SecurityConfig.java:88 — requestMatchers(...)` のように **行番号**で permitAll 登録箇所を引用していた。しかし `SecurityConfig` に 1 行挿入されるだけで以降の引用がすべてずれる。2026-07-30 に全件突き合わせを実測した結果、**`@IntentionallyPublic` の行番号引用 35 件のうち 34 件が既に別の行を指していた**（唯一正しかったのは直前の PR で追加された 1 件のみ）。「なぜこの EP が公開されているのか」を追う唯一の手掛かりが、誰にも気づかれないまま嘘になっていた。

matcher 式は行挿入で腐らず、`SecurityConfig` を Ctrl+F すれば人間もすぐ辿れる。さらに文字列であるため**番人が機械的に突き合わせられる**。

**番人:** `IntentionallyPublicMatcherGuardTest`（`backend/src/test/java/com/mannschaft/app/common/architecture/`）が以下を機械検証する。

- 各 `@IntentionallyPublic` が matcher 式を宣言していること（空宣言は違反）
- 列挙された各パターンが `SecurityConfig` に**実在し、かつ permitAll されている**こと
- `@IntentionallyPublic` を持つファイルに行番号引用（`SecurityConfig.java:NNN`）が残っていないこと

引用が腐れば静かに嘘になるのではなく **ビルドが赤くなる**。CORS プリフライト（`OPTIONS "/**"`）は「公開の根拠」にならないため番人の permitAll 集合から除外している（`/**` を引用するだけで番人を通す抜け道を塞ぐため）。

> **注**: 公開してよいと判断した**理由の記述（Javadoc）は本体であり、引用形式の変更後も必ず残すこと。** 理由なき付与は「認可漏れの永久凍結」と区別がつかない。
>
> **残務**: 姉妹マーカー `@AuthorizedByPathConfig` も同じ行番号引用規約で、実測 **40 件中 38 件がずれている**（残り 2 件はパス引用が無く測定不能）。同じ移行が必要だが、対象 Controller が 30 以上に及び並行 PR との衝突面が大きいため別チケットとする。

### 3.1 インフラ・ドキュメント
| パターン | メソッド | 公開理由 |
|---|---|---|
| `/**` | OPTIONS | CORS プリフライト |
| `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**` | GET | API ドキュメント（本番では別途遮断推奨 → §7 未解決事項） |
| Actuator `HealthEndpoint` | GET | ヘルスチェック（匿名）。他の Actuator は SYSTEM_ADMIN 限定 |

### 3.2 認証フロー（未ログイン前提）
| パターン | 公開理由 |
|---|---|
| `/api/v1/auth/login`, `/register`, `/refresh` | ログイン前 |
| `/api/v1/auth/password-reset/**`, `/email-verification/**`, `/oauth/**` | ログイン前のメールリンク/OAuth |
| `/api/v1/parental-consent/approve`, `/reject` | 保護者がメールリンクから直接アクセス（F01.9） |

### 3.3 公開閲覧（F19.1 / F15.4 / F22.1・レート制限あり）
`/api/v1/public/**` の GET 群（teams/organizations の詳細・posts・events・timeline-posts・search・users・blog-posts comments、**市（F22.1）の `market/listings`・`market/listings/*`・`market/regions`・`market/summary`・`market/categories`**）、`/api/v1/organizations/*/teams/search`、`/api/v1/contact-invite/*`、SEO（`/sitemap.xml`・`/robots.txt`・`/sitemap-*.xml`）、i18n（`/api/i18n/**`）。

> **🔴 根治記録（2026-05-31）**: 市一覧ページが認証必須の `GET /api/v1/recruitment-categories` をジャンルフィルタ用に直叩きし、未ログインで 401 → FE が `/login` へ飛ばす重大バグが実機 E2E で発覚。公開ページは公開 API のみに依存させる原則に基づき `GET /api/v1/public/market/categories` を新設・permitAll 登録して根治（F22.1 02_api_design §3.6 / 04_security §1.6）。
> - 根治策: `GET /api/v1/public/market/categories` を新設し、SecurityFilterChain の permitAll リストに登録済み
> - 旧 `GET /api/v1/recruitment-categories` は認証必須のまま維持（廃止予定なし・既存ロジックを温存）
> - FE の市一覧ページは `/public/market/categories` を使用するよう修正済み
> POST/DELETE（コメント投稿・削除など）は **認証必須**（許可リストに入れず `.authenticated()` が制御）。

#### F06.4 公開活動記録（2026-07-29 追加）

チーム / 組織が対外的に活動実績を見せる機能であり、未ログインの閲覧（保護者候補・スポンサー・検索エンジン）が要件そのもの。SSR / SNS シェア用に以下 5 EP を permitAll する（`SecurityConfig.java` の `/api/v1/public/activities/*` 以下 5 行）。

| パターン | メソッド | コントローラ | 公開理由・ガード |
|---|---|---|---|
| `/api/v1/public/activities/*` | GET | `activity/ActivityPublicController#getPublicActivityById` | SNS シェア URL `/activity/{id}` から ID 直引き（スコープ不問） |
| `/api/v1/public/teams/*/activities` | GET | 〃 `#listTeamPublicActivities` | チーム公開活動記録一覧 |
| `/api/v1/public/teams/*/activities/*` | GET | 〃 `#getTeamPublicActivity` | チーム公開活動記録詳細 |
| `/api/v1/public/organizations/*/activities` | GET | 〃 `#listOrgPublicActivities` | 組織公開活動記録一覧 |
| `/api/v1/public/organizations/*/activities/*` | GET | 〃 `#getOrgPublicActivity` | 組織公開活動記録詳細 |

安全性は `PublicActivityQueryService`（匿名公開経路の唯一の入口）が以下 5 点で担保する:

1. **親スコープが PUBLIC** かつ未凍結・未削除であること（非公開チーム / 組織配下は一律 404）
2. **記録自身が `visibility=PUBLIC` かつ `status=PUBLISHED`** であること（下書き・会員限定・論理削除済みは 404）
3. **F00 正準の可視性判定**（`ContentVisibilityChecker#canView`・未認証 `userId=null`）を通ること。独自述語は作らない
4. **パス変数と実スコープの一致**（スコープ詐称 IDOR の拒否）
5. **返却は公開専用 DTO の 8 項目のみ** — `PublicActivityDetail` / `PublicActivitySummary`（`id` / `title` / `activityDate` / `activityTimeStart` / `activityTimeEnd` / `description` / `scopeRef` / `createdAt`）。`location` / `fieldValues` / `attachments` / `createdBy` / `visibility` / `status` / `templateId` / `venueId` / `scheduleId` / `updatedAt` は**禁則フィールド**として一切含めない

失敗はすべて `PUBLIC_013` → **404** に正規化する（403 は「存在するが権限がない」を漏らす存在オラクルになるため使わない）。未認証の ID 総当りは `PublicApiRateLimitFilter` の PUBLIC_API バケット（60 req/min/IP・200 req/min/user）で抑止する。書込（POST/PUT/PATCH/DELETE）は permitAll せず `.authenticated()` に落とす。IDOR 面を最小化するため `*`（1 階層厳格）で限定し `/**`（再帰）は使わない。

> **DTO に項目を足すときは permitAll の妥当性が崩れうる**。契約テスト `ActivityPublicContractIT`（ホワイトリスト方式）と `SecurityConfigAuthorizationTest` が機械的に守っているため、追加時は必ず両テストと本節を更新すること。設計書: `docs/features/F06.4_activity_records.md`

### 3.4 広告（F09.7 / F09.17・IP レート制限あり）
`POST /api/v1/ads/*/click`、`GET/POST /api/v1/ads/unsubscribe`、`GET /api/v1/ads/pixels/open`。

### 3.5 エラー追跡・内部
`POST /api/v1/error-reports`、`GET /api/v1/active-incidents`、`POST /api/internal/ssr-logs`（コントローラが内部トークン検証）。

### 3.6 外部 webhook（署名検証で認証＝JWT 非依存）★本 Phase で新規追加
これらは **JWT を持たない外部システムが叩く** ため、`.authenticated()` 反転時に **必ず permitAll へ追加**しないと決済・メール・LINE 連携が即死する。各エンドポイントは Controller 内で署名/トークン検証を行う。

| パス（実パス） | 許可リストパターン | メソッド | コントローラ | 検証方式 |
|---|---|---|---|---|
| `/api/v1/webhooks/stripe` | `/api/v1/webhooks/stripe` | POST | `payment/StripeWebhookController`（`@PostMapping("/stripe")`） | `Stripe-Signature` ヘッダー |
| `/api/v1/webhooks/stripe/ad-invoices` | `/api/v1/webhooks/stripe/*` | POST | `advertising/StripeAdInvoiceWebhookController`（`@PostMapping("/ad-invoices")`） | `Stripe-Signature` ヘッダー |
| `/api/v1/webhooks/stripe/connect` | `/api/v1/webhooks/stripe/*`（**既存 `*` で被覆済・新規許可不要・SecurityConfig 変更不要**） | POST | `payment/StripeWebhookController`（`@PostMapping("/stripe/connect")`・F22.1 謝礼決済 P2-a 実装済） | `Stripe-Signature` ヘッダー（Connect 用シークレット `STRIPE_CONNECT_WEBHOOK_SECRET` で**別シークレット**として検証） |
| ~~`/api/v1/webhooks/ses`~~ | **廃止（F09.6 Phase 8a）** | — | — | **SQS 内部認証へ移行**（下記注記参照） |
| `/api/v1/line/webhook/{webhookSecret}` | `/api/v1/line/webhook/*` | POST | `line/LineWebhookController`（`@PostMapping("/{webhookSecret}")`） | LINE 署名（`X-Line-Signature`）+ パスシークレット |
| `/incoming/{token}` | `/incoming/*` | POST | `webhook/IncomingWebhookController`（`@PostMapping("/incoming/{token}")`） | パストークン（DB 照合）。**トップレベルパス（`/api/` 配下でない）に注意** |

> **SES バウンス/苦情通知の SQS 移行（F09.6 Phase 8a）**: 旧 `POST /api/v1/webhooks/ses`（permitAll・SNS 署名検証なし）は廃止し、`SES → SNS Topic → SQS Queue → @SqsListener`（`directmail/listener/SesNotificationSqsListener`）方式へ移行した。HTTP 受け口を排したことで偽造バウンス注入・SubscribeURL の SSRF が構造的に不可能になり、アプリ側の SNS 署名検証も不要化した。SQS は AWS 内部認証（SigV4）+ キューアクセスポリシー（送信元 SNS Topic を限定）で認証する。SecurityConfig の当該 permitAll 行は撤去済み（deny-by-default 対象に復帰）。

> 実装注記: Stripe 系は 2 つの異なるパス（`/stripe` と `/stripe/ad-invoices`）があるため、`POST /api/v1/webhooks/stripe` と `POST /api/v1/webhooks/stripe/*` の**両方を明示的に**許可する（`/**` 再帰は使わない）。LINE と incoming はパス末尾にシークレット/トークンを持つため `*`（1 階層）で許可する。**F22.1 謝礼決済（Phase 2 後半）の Connect Webhook `/api/v1/webhooks/stripe/connect` は、この `/api/v1/webhooks/stripe/*`（1 階層 `*`）許可で被覆されるため、許可リストへの新規追記は不要**（署名検証は Connect 用シークレットで別途実施）。

> 逆に、以下は **ユーザー認証必須**（permitAll に入れない。`.authenticated()` がカバー）:
> `/api/webhooks/incoming`（トークン管理・ADMIN）、`/api/webhooks/endpoints`、`/api/webhooks`（配信ログ）、`/api/api-keys`、`/api/v1/users/me/stripe-connect`。

---

## 4. 明示的ロール制限（sensitive prefix）

許可リストでも `.authenticated()` でもなく、**ロール必須**を明示するパス。フォールバック反転後も二重ガードとして明示する。

| パターン | 要求ロール |
|---|---|
| Actuator（Health 以外: info/metrics/prometheus/caches/threaddump/loggers） | `SYSTEM_ADMIN` |
| `/api/v1/admin/age-group-settings/**` | `SYSTEM_ADMIN` |
| `/api/v1/system-admin/gdpr/**` | `SYSTEM_ADMIN` |
| **`/api/v1/system-admin/**`**（包括）★本 Phase で追加 | `SYSTEM_ADMIN` |
| `/api/v1/resumes/**` | `authenticated()`（本人のみ・F01.10） |

> `/api/v1/system-admin/**` の包括ルールを追加することで、`SecurityConfig.java:173-178` の TODO（email-outbox 等の system-admin API が permitAll フォールバック依存）を解消する。Controller 側 `@PreAuthorize` と合わせ二重ガードとなる。

---

## 5. WebSocket（/ws）

`/ws` のハンドシェイクは `WebSocketConfig` で `setAllowedOriginPatterns("*")` の上、`WebSocketAuthChannelInterceptor` が STOMP CONNECT 時に JWT を検証する。

- ハンドシェイク自体（HTTP アップグレード）は SecurityFilterChain を通る。`.authenticated()` 反転後にハンドシェイクが 401 で弾かれないことを **実装時に必ず確認** し、必要なら `/ws` を許可リストへ追加する（認証は STOMP CONNECT の interceptor が担うため、ハンドシェイクの permitAll は設計上許容）。

#### WebSocket 認証の二層構造

WebSocket は以下の二層で認証を行う:

1. **ハンドシェイク層（HTTP Upgrade）**: `permitAll()`
   - SockJS ハンドシェイクは HTTP リクエストのため、Spring Security のフィルターチェーンを通る
   - WebSocket では Cookie が利用しにくいため、ハンドシェイク自体は許可する

2. **STOMP CONNECT 層（メッセージング）**: JWT 必須
   - `WebSocketAuthChannelInterceptor` が CONNECT フレームの `Authorization` ヘッダーで JWT を検証する
   - 検証失敗時: `StompHeaderAccessor.setNativeHeader("ERROR", "認証エラー")` でエラーフレームを返し接続を拒否する
   - 検証成功後のみ SUBSCRIBE / SEND を許可する

**注意**: `setAllowedOriginPatterns` は本番環境では `MANNSCHAFT_ALLOWED_ORIGINS` と同一ドメインに限定すること。
開発環境の `"*"` を本番に持ち込まないこと。

---

## 6. 移行手順とロールバック

### 移行手順
1. **監査**: 全 `@RestController` の全マッピングを列挙し、各エンドポイントを「公開（許可リスト要）/ 認証必須 / ロール必須」に分類
2. **許可リスト確定**: §3 の表に漏れがないか照合（特に webhook・OAuth コールバック・公開 GET）
3. **明示ルール追加**: §4 の sensitive prefix を追加
4. **反転**: `.anyRequest().permitAll()` → `.anyRequest().authenticated()`
5. **テスト**: §6.2 の統合テストで担保
6. **段階確認**: ステージング環境で webhook・公開ページ・WebSocket の疎通確認

### 6.1 監査の実施方法
```
grep -rn "@RequestMapping\|@GetMapping\|@PostMapping\|@PutMapping\|@PatchMapping\|@DeleteMapping" backend/src/main/java --include=*Controller.java
```
で全マッピングを抽出し、§3 許可リストに無いものは全て認証必須であることを確認する。

### 6.2 担保するテスト（統合テスト）
- **公開到達**: webhook 3 系統（Stripe / LINE / incoming。SES は F09.6 Phase 8a で SQS 移行・HTTP 廃止）・主要な `/api/v1/public/**` GET が **未認証で 2xx/正常系** に到達すること
- **保護**: 代表的な認証必須エンドポイント（例: `/api/v1/users/me`、適当な org/team API）が **未認証で 401/403** になること
- **ロール**: `/api/v1/system-admin/**` が一般ユーザートークンで **403** になること

### 6.3 ロールバック
反転は `SecurityConfig` の **1 行変更**（`.authenticated()` ⇔ `.permitAll()`）。本番で想定外のエンドポイントが弾かれた場合、即座に `.permitAll()` に戻して影響を止め、許可リストの漏れを修正してから再反転する。

---

## 7. 実装時の確認事項（反転前チェックリスト）

> 🟢 **反転実施済み（#1266・2026-06-02）**: 以下のチェックリストは実施時に確認済み。

deny-by-default 反転は以下を実装時に確認してから行う（いずれも §6 監査の一部）。

- [x] OAuth コールバックの実パスが `/api/v1/auth/oauth/**` でカバーされているか（カバー外なら許可リストへ追加）
- [x] `/ws` ハンドシェイクが反転後も 401 で弾かれないか（`/ws/**` を permitAll へ追加済み。STOMP CONNECT の interceptor 認証は維持）
- [x] §3 許可リストに無い未認証前提エンドポイント（公開 GET・コールバック等）の取りこぼしが無いか

---

## 8. 今後の拡張（スコープ外・意思決定済み）

- **Swagger UI / `/v3/api-docs` の本番遮断**: 情報露出（A05）の観点から **本番では無効化する方針**（`springdoc.swagger-ui.enabled=false` 等を prod プロファイルで設定、または LB/リバースプロキシで遮断）。具体的な遮断機構の実装は本 Phase のスコープ外とし、本番セットアップ手順（`docs/operations/`）で対応する

---

## 9. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-06-12 | **ステータス追従**: §1 の「移行する（予定）」記述を「移行した（実施済み）」に更新。解決済み課題に取り消し線と根治済み注記を追記。§7 チェックリストを `[x]` 済みに更新。裏取り根拠（`SecurityConfig.java` への `anyRequest().authenticated()` 確認）を記載 |
| 2026-05-26 | 新規作成。deny-by-default 移行設計、webhook 許可リスト、system-admin 包括ルールを定義 |
| 2026-06-02 | §5 に WebSocket 二層認証モデル（ハンドシェイク層 permitAll／STOMP CONNECT JWT 必須）と `setAllowedOriginPatterns` の本番ドメイン限定要件を追記。§3.6 の Stripe webhook 許可リスト記述を `/**` 再帰禁止・両パス明示に修正。§3.3 の根治記録に `/public/market/categories` 新設・旧 API 維持・FE 修正済みの詳細を追記 |
| 2026-06-13 | §3.6: SES バウンス/苦情通知を HTTP webhook（`/api/v1/webhooks/ses`・permitAll・SNS 署名検証なし）から SQS 内部認証方式（SES→SNS→SQS→@SqsListener）へ移行（F09.6 Phase 8a）。HTTP 受け口廃止・permitAll 撤去・SSRF/偽造注入の構造的排除。webhook 系統を 4→3（Stripe/LINE/incoming）に更新 |
| 2026-07-18 | **認可根治 早馬（todo コメント・越境 BOLA/IDOR 閉塞）**: `TeamTodoController`/`OrgTodoController` のコメント EP（`listComments`/`addComment`/`updateComment`）が受け取った path の teamId/organizationId を Service へ渡さず、`TodoCommentService` は `verifyTodoExists`（存在確認のみ）しか行っていなかったため、TODO の内部 id を知る認証ユーザーが所属外チーム/組織のコメントを read/write できる BOLA/IDOR が Team/Org 両側で成立していた。**根治は Service 層の scope 非束縛を潰す方針**: Controller は path scope（`TodoScopeType` + 内部 teamId/orgId）を Service へ渡し、`TodoCommentService.verifyScopeAndMembership` が (1) 対象 TODO が path scope に属することを束縛（不一致は `TODO_010 NOT_FOUND`→404 で存在秘匿）、(2) `accessControlService.checkMembership`（`ScopeType` 単位）で非メンバーを 403（`COMMON_002`）にする、の二段で認可する。兄弟 `addAssignee` は `assertTodoScope`（scope 束縛）だけで membership を検証しないため、scope 束縛のみでは非メンバーが正しい teamId/orgId を推測すると通ってしまう（※ `TeamTodoController`/`OrgTodoController` の他の TODO 系 EP も同様に membership 未検証の残穴があり、本早馬の対象外。別途 Wave で棚卸し要）。`deleteComment` は `isAdminOrAbove` で認可済みのため対象外。**番人（`AuthzControllerGuardArchTest`）との関係**: 本修正は認可を **Service 層**で行い、Controller 側は引き続き `commentService.*`（`*AccessService`/`*AccessGuard` でない）を直接呼ぶだけなので、番人の認可シグナル（Controller から `AccessControlService`/`*AccessGuard`/`*AccessService` への直接呼び出し）には該当しない。したがって当該 6 EP は凍結ストア（`archunit_store`）から外れず **凍結継続（Ph3 のまま）**。BOLA は実効的に閉塞するが「Controller に認可到達マーカーが立つ」わけではない点に注意（凍結解除したい場合は将来 Controller 側で `*AccessGuard` 経由に寄せる Ph3 マーカー付与が必要）。契約テスト `TodoCommentScopeContractIT`（Team/Org 各: 非メンバー 403／別 scope メンバー 403／越境 id 404 秘匿／正当 200-201 の各象限）＋`TodoCommentServiceTest`（scope 不一致 404・非メンバー 403）で担保 |
| 2026-07-17 | **認可根治 Wave5 早馬（facility ドメイン・重大 BOLA/IDOR 根治）**: `FacilityBookingService`/`FacilityService` 等が `AccessControlService` を注入すらしておらず、F09.5 共用施設予約の全 EP（施設 CRUD・予約 CRUD/承認/取消/チェックイン/PDF・設定・統計・ルール/料金/備品・支払い）で認可が皆無だった（任意ログインユーザーが `bookingId`/`facilityId` 総当りで他組織・他チームの予約者 ID・利用目的・料金・支払を read/承認/取消/削除できた）。`FacilityAccessGuard` を新設し全 Controller の public 入口で認可を敷いた（`feedback_authz_gate_on_public_entry_not_shared_method` 準拠。内部 finder は非対象）。**entity 由来 scope**（予約は `booking → facilityId → SharedFacilityEntity.scopeType/scopeId` を辿る）で認可し、URL パスの scope と食い違う越境 id は 404（`FACILITY_001`/`FACILITY_006`）で存在秘匿（`GlobalExceptionHandler` へ 404 マップ追加。従来 WARN 既定 400 で存在漏洩していた欠陥も是正）。粒度は read=`checkMembership`／write=`checkAdminOrAbove`／予約の更新・取消のみ `checkOwnerOrAdmin`（本人操作温存）。**機微データはさらに締める**（マスター御裁可）: 統計 `/stats`（売上・プラットフォーム手数料）は **ADMIN 限定**（`checkAdminOrAbove`）、予約単位の支払い取得 `/bookings/{id}/payment`（支払額・方法）は **予約者本人 or ADMIN**（`checkOwnerOrAdmin`）。チェックイン/完了は当面 ADMIN 限定の安全既定を維持（「設定で選べる」は設定トグル＋DDL＋FE を要する別機能ゆえ後日フォローアップで正規実装）。契約テスト `FacilityScopeContractIT`（非メンバー 403／越境 404／正当 200 の 3 象限）で担保 |
