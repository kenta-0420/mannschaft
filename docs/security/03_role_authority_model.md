# 03. ロール・権限モデル（Role / Authority Model）— 認可基盤完全根治

> **ステータス**: 🟢 Phase 1〜3 実装完了（Phase 4〜5 未着手）
> **実装フェーズ**: Security Hardening Phase 2（認可基盤完全根治）
> **最終更新**: 2026-06-12
> **重大度**: ~~🔴 最重要セキュリティインシデント~~ → **🟢 根治済み（2026-06-02 点火）**
> **関連ドキュメント**: [README](README.md), [01 認可基盤](01_authorization_baseline.md), [02 Cookie とセッション](02_cookie_and_session.md), F01.1 認証, F01.2-04 セキュリティ運用, F03.5-04 セキュリティ運用

> 🟢 **認可基盤根治 Phase 1〜3 完了（#1266・2026-06-02 点火）**
> - Phase 1: JWT 全 5 発行経路で SYSTEM_ADMIN をロールに注入するよう修正
> - Phase 2: per-scope EP を `@accessGuard.isScopeAdmin(...)` SpEL ガードへ移行、生穴 5 EP を明示 Service 層認可で封鎖
> - Phase 3: `@EnableMethodSecurity(prePostEnabled = true)` を `SecurityConfig` に付与し 97 個の `@PreAuthorize` を実効化
> - 残課題: Phase 4（幻ロール・負論理の最終確認・特にシフト PDF 負論理(E)）、Phase 5（統合テスト確定・本書ステータス更新）

> ※2026-06-02 時点の調査記録（下記 §2 の病巣カタログ）は実装当時の状態を記録したものです。その後 Phase 1〜3 で根治済み。

---

## 1. 概要

本書は **「どのロールが、どの権限（authority）を、どの強制ポイントで持つか」** を単一の正典として定義する。

セキュリティ調査（2026-05-29）で、認証（ログイン・トークン失効）は堅牢に実装されている一方、**認可（誰が何をできるか）の強制が全面的に機能不全**であることが判明した。具体的には:

- JWT に載るロールが **全経路で `["MEMBER"]` 固定**であり、SYSTEM_ADMIN が誰にも付与されない
- `@EnableMethodSecurity` が未有効のため、コード上に存在する **97 個の `@PreAuthorize` がすべて実機で no-op**
- SecurityConfig フィルタ層の `hasRole("SYSTEM_ADMIN")` ルールは、JWT に SYSTEM_ADMIN が載らないため **全員 403（機能不全）**
- `TEACHER` 等の **存在しないロール（幻ロール）** を参照する宣言が残存

> **🟢 根治済み（2026-06-02 / #1266）**: 上記の機能不全は Phase 1〜3 の実装で根治された。§2 の病巣カタログは調査当時（2026-05-29）の記録を保存したもの。現在の実装状態は §8 の段階計画 Phase 表を参照すること。

この状態は「鍵のかかっていない金庫に『施錠済み』の札を貼っている」に等しい。本書はこれを **案①完全根治**（宣言＝強制を単一真実源とする）で塞ぐための正典モデル・段階計画・テスト戦略を定める。

### 1.1 本書の位置づけ

| 文書 | 役割 |
|---|---|
| [01](01_authorization_baseline.md) | **パス単位**の粗い境界（公開/認証必須/ロール必須）。SecurityFilterChain の許可リスト |
| **本書（03）** | **ロール・権限の意味論**と**強制ポイント**の正典。JWT claims のロール設計、SpEL ガード、`@PreAuthorize` カタログ |
| [02](02_cookie_and_session.md) | トークンの保存・属性・失効。本書の「失効保証」はここに依存 |
| F01.1 | 認証フロー（発行・MFA・OAuth）。本書は JWT roles claim の意味論を F01.1 と同期させる |

01 が「ドア」、本書が「鍵と権限証」である。両者は多層防御（§4）として併存する。

---

## 2. 現状分析（病巣カタログ）

本章は **実コードの file:line を根拠**に、認可基盤の機能不全を網羅的に列挙する。すべて 2026-05-29 時点の `origin/main` を対象とする。

### 2.1 病巣①: JWT 発行全 5 経路がロール固定

JWT を発行する経路はすべて `List.of("MEMBER")` をハードコードしている。SYSTEM_ADMIN を含む実ロールが一切 claims に載らない。

| # | 経路 | file:line | 発行内容 |
|---|---|---|---|
| 1 | 通常ログイン | `auth/service/AuthService.java:414` | `issueAccessToken(user.getId(), List.of("MEMBER"))` |
| 2 | 2FA 検証後 | `auth/service/Auth2faService.java:462` | `issueAccessToken(userId, List.of("MEMBER"))` |
| 3 | OAuth ログイン | `auth/service/AuthOAuthService.java:320` | `issueAccessToken(userId, List.of("MEMBER"))` |
| 4 | WebAuthn 検証後 | `auth/service/AuthWebAuthnService.java:581` | `issueAccessToken(userId, List.of("MEMBER"))` |
| 5 | リフレッシュ（ローテーション） | `auth/service/AuthTokenRotationService.java:88` | `issueAccessToken(userId, List.of("MEMBER"))` |

発行本体 `AuthTokenService#issueAccessToken`（`auth/service/AuthTokenService.java:74-87`）は受け取った `roles` をそのまま `.claim("roles", roles)` に格納するだけで、**呼び出し側が固定値を渡している限り SYSTEM_ADMIN は永遠に載らない**。

> 補足: F01.1 §JWT ペイロード仕様（`docs/features/F01.1_auth.md:1246-1286`）は **「`SYSTEM_ADMIN` のみ特別扱いで JWT に載せる」設計を既に明文化している**。つまり設計意図は正しく、**実装だけが設計に追いついていない**（ドキュメントとコードの乖離）。本根治は「実装を設計に合わせる」作業である。

### 2.2 病巣②: JwtAuthenticationFilter が DB 補完しない

`config/JwtAuthenticationFilter.java:63-69` は claims の `roles` からのみ authority を構築する。

```java
List<String> roles = claims.get("roles", List.class);                       // ["MEMBER"] のみ
List<SimpleGrantedAuthority> authorities = roles != null
        ? roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()
        : List.of();
```

DB から SYSTEM_ADMIN を補完するロジックは存在しない。結果として、**SecurityContext には `ROLE_MEMBER` しか入らず、誰も `ROLE_SYSTEM_ADMIN` / `ROLE_ADMIN` を持たない**。

### 2.3 病巣③: `@EnableMethodSecurity` が未有効 → 全 `@PreAuthorize` が no-op

プロジェクト全体を走査した結果、`@EnableMethodSecurity` / `@EnableGlobalMethodSecurity` は **どの `@Configuration` にも付与されていない**（コメント内の言及のみ。`faq/service/FaqAdminService.java:37` ほか 12 箇所が「未有効」と明記）。

Spring Security はメソッドセキュリティが有効化されていない限り `@PreAuthorize` を **完全に無視**する。よってコード上の **97 個の `@PreAuthorize`（メソッド/クラスレベル合算）がすべて実機で強制力ゼロ**である。内訳は §5 のカタログ参照。

### 2.4 病巣④: SecurityConfig フィルタ層 `hasRole("SYSTEM_ADMIN")` が全員 403

`config/SecurityConfig.java` のフィルタ層には SYSTEM_ADMIN を要求するルールが 4 系統ある。

| ルール | file:line | 対象 |
|---|---|---|
| Actuator（Health 以外） | `SecurityConfig.java:138-139` | info/metrics/prometheus/caches/threaddump/loggers |
| 年齢区分設定 | `SecurityConfig.java:211` | `/api/v1/admin/age-group-settings/**` |
| GDPR パージ管理 | `SecurityConfig.java:215` | `/api/v1/system-admin/gdpr/**` |
| system-admin 包括 | `SecurityConfig.java:234` | `/api/v1/system-admin/**` |

これらは正しく書かれているが、**病巣①②により誰も `ROLE_SYSTEM_ADMIN` authority を持たない**ため、SYSTEM_ADMIN 本人を含む**全員が 403**になる。すなわち system-admin 系 API は「誰も使えない」状態で稼働している。

加えて `SecurityConfig.java:137` の以下のコメントは **事実無根**である:

```java
// JwtAuthenticationFilter が "ROLE_SYSTEM_ADMIN" として authority を付与するため hasRole を使用
```

§2.2 のとおりフィルタは DB 補完しないため、この付与は起きていない。**このコメントの是正を Phase 2 のタスクとする**（本 PR ではコードを触らず、是正対象として明記するに留める）。

### 2.5 病巣⑤: 幻ロール・負論理

| 種別 | file:line | 内容 | 実機での挙動 |
|---|---|---|---|
| 幻ロール `TEACHER` | `school/controller/AttendanceDisclosureController.java:46,66,84` | `@PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")` 3 EP | `TEACHER` は `roles` シードに存在しない（`db/migration/V2.014__seed_roles.sql` は SYSTEM_ADMIN/ADMIN/DEPUTY_ADMIN/MEMBER/SUPPORTER/GUEST のみ）。method-security 有効化後は `hasRole('TEACHER')` も `hasRole('ADMIN')`（per-scope ロールゆえ JWT に載らない）も常に false → **3 EP が全員 403** |
| 負論理 `!hasRole('SUPPORTER')` | `shift/controller/ShiftPdfController.java:43` | SUPPORTER を弾く意図 | SUPPORTER は per-scope ロールで JWT に載らないため `hasRole('SUPPORTER')` は常に false → 否定は常に true → **SUPPORTER 排除が機能せず、かつ scope 検証もない**。誰でも任意 `scheduleId` の PDF にアクセスし得る（IDOR 兆候） |

### 2.6 病巣⑥: per-scope ロールを `hasRole` で表現している矛盾

`hasRole('ADMIN')` / `hasRole('DEPUTY_ADMIN')` は **どの組織・チームの ADMIN か**という文脈を持てない。ADMIN/DEPUTY_ADMIN は本質的に per-scope（`user_roles.team_id` / `organization_id` に紐づく）であり、JWT グローバルロールとして表現するのは設計的に誤りである。

該当: `hasRole('ADMIN')` 単独 17 件 + `hasRole('ADMIN') or hasRole('SYSTEM_ADMIN')` 10 件（§5 カタログ）。これらは method-security 有効化後も **JWT に ADMIN が載らない限り恒常 403** となるため、SpEL ガードによる per-scope 判定へ移行する（§3.3）。

### 2.7 唯一の救い: 失効機構と判定基盤は堅牢

根治の足場として、以下は **既に正しく実装済み**であり再利用できる:

| 基盤 | file:line | 提供機能 |
|---|---|---|
| JTI ブラックリスト | `AuthTokenService.java:185-191,219-227` | 単一デバイスログアウトで即時失効 |
| 全デバイス無効化タイムスタンプ | `AuthTokenService.java:199-203,237-250` | `iat < user_invalidated_at` で全 Access Token 無効化 |
| access token 15 分寿命 | `application.yml`（`mannschaft.jwt.access-token-expiration`） | ロール変更が最悪でも 15 分で反映 |
| SYSTEM_ADMIN 即判定 | `role/repository/UserRoleRepository.java:204-209`（`existsSystemAdminByUserId`） | 単一 SQL で SYSTEM_ADMIN 判定可 |
| per-scope 判定の集約 | `common/AccessControlService.java`（`isSystemAdmin` / `isAdminOrAbove` / `checkAdminOrAbove` / `getRoleName`） | per-scope ロール判定の単一窓口 |
| 既存 SpEL ガード前例 | `admin/security/AdminRoleChecker.java`, `quickmemo/security/QuickMemoAccessGuard.java` | `@Component` Bean を `@PreAuthorize("@bean.method(...)")` で参照する定石 |

**ロール変更の即時反映**は、ロール付与/剥奪時に `setUserInvalidationTimestamp(userId)` を発火することで、対象ユーザーの全既存トークンを失効させて担保する（§6）。

---

## 3. 正典モデル（採用＝案①完全根治）

認可は **2 つの軸**で構成する。グローバル権限（SYSTEM_ADMIN）と per-scope 権限（team/org ADMIN/DEPUTY_ADMIN）で**強制の置き場所を変える**ことが核心である。

### 3.1 ロールの分類と強制ポイント

| ロール種別 | 具体ロール | スコープ | JWT に載せるか | 強制ポイント | 判定ソース |
|---|---|---|---|---|---|
| **プラットフォーム権限** | `SYSTEM_ADMIN` | グローバル（テナント非依存） | **載せる**（roles 配列に追加） | フィルタ層 `hasRole('SYSTEM_ADMIN')` ＋ メソッド層 `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` | 発行時に `user_roles`（`existsSystemAdminByUserId`）から判定 |
| **per-scope 管理権限** | `ADMIN` / `DEPUTY_ADMIN` | team または organization | **載せない** | メソッド層 SpEL ガード（`@accessGuard.isScopeAdmin(...)`）→ `AccessControlService` 委譲 | リクエスト時に `user_roles` を都度参照 |
| **per-scope 一般** | `MEMBER` / `SUPPORTER` / `GUEST` | team または organization | **載せない** | Service 層メンバーシップ検証（`checkMembership` 等） | リクエスト時に `memberships` を都度参照 |
| **所有者** | （ロールではない） | リソース単位 | — | 所有者スコープのクエリ（`findByIdAndUserId` 等）＋ 所有者ガード Bean | リソースの `user_id` 照合 |

### 3.2 SYSTEM_ADMIN — JWT に載せる（roles 配列に追加する方式）

#### 3.2.1 採用方式と理由

**roles 配列に文字列 `"SYSTEM_ADMIN"` を追加する方式を正とする**（boolean claim 方式は採用しない）。

| 方式 | 内容 | 採否 | 理由 |
|---|---|---|---|
| **A. roles 配列追加（採用）** | `roles: ["MEMBER", "SYSTEM_ADMIN"]` | ✅ | フィルタの既存変換 `new SimpleGrantedAuthority("ROLE_" + role)`（`JwtAuthenticationFilter.java:68`）にそのまま乗る。SecurityConfig の `hasRole("SYSTEM_ADMIN")`（4 系統）が**コード変更なしで機能**する。Spring Security の `hasRole` は `ROLE_` プレフィクスを前提とするため、変換経路を一切いじらず済む |
| B. boolean claim（不採用） | `system_admin: true` | ❌ | フィルタに「claim を読んで authority に変換する」分岐を新設する必要がある。`hasRole` ではなく `hasAuthority('SYS')` 等へ全 SecurityConfig ルールを書き換える派生変更が発生し、根治の差分が無用に拡大する |

#### 3.2.2 発行時のロード注入（全 5 経路）

§2.1 の 5 経路すべてで、固定値 `List.of("MEMBER")` を**「常に MEMBER ＋ SYSTEM_ADMIN なら追加」**へ置き換える。発行ロジックは 1 箇所のヘルパーに集約し、5 経路はそれを呼ぶ形にする（重複・付け忘れ防止）。

```text
擬似コード（実装は Phase 1 / コードは本 PR 対象外）:

List<String> roles = new ArrayList<>();
roles.add("MEMBER");                                  // 全ユーザーの基底ロール
if (accessControlService.isSystemAdmin(userId)) {     // user_roles を 1 SQL 参照
    roles.add("SYSTEM_ADMIN");
}
String accessToken = authTokenService.issueAccessToken(userId, roles);
```

- 判定は既存 `AccessControlService#isSystemAdmin(userId)` → `UserRoleRepository#existsSystemAdminByUserId` を再利用（新規クエリ不要）。
- **リフレッシュ時も再判定する**（`AuthTokenRotationService:88`）。これにより SYSTEM_ADMIN を剥奪されたユーザーは、次回リフレッシュ（最長 15 分以内）で SYSTEM_ADMIN authority を失う。即時失効が必要な場合は §6 の無効化タイムスタンプを併用する。

#### 3.2.3 フィルタ層の付与

`JwtAuthenticationFilter`（`:67-69`）の変換ロジックは **変更不要**。roles に `"SYSTEM_ADMIN"` が含まれれば自動的に `ROLE_SYSTEM_ADMIN` authority が付く。これが §2.4 のコメントを「事実無根」から「事実」へと変える（コメント文言自体の是正は Phase 2 タスク）。

### 3.3 team/org ADMIN・DEPUTY_ADMIN — JWT に載せず SpEL ガードで都度判定

per-scope ロールを JWT に載せない理由は **マルチテナントでの破綻**である。1 ユーザーが複数組織・複数チームに異なるロールで所属し得るため、全スコープ分のロールを JWT に詰めるとトークンが肥大化し、かつロール変更の反映が遅延する。よって **リクエストのパス変数（teamId/organizationId）と突き合わせて都度 DB 判定**する。

#### 3.3.1 新設 SpEL ガード Bean の設計

既存 `AdminRoleChecker` / `QuickMemoAccessGuard` の定石（`@Component("name")` ＋ `Authentication` 引数）に倣い、**per-scope 認可専用の単一ガード Bean** を新設する。`AccessControlService` に判定を委譲し、ロジックの二重化を避ける。

```java
package com.mannschaft.app.common.security;

/**
 * @PreAuthorize の SpEL からパス変数（scopeId）を参照して per-scope 認可を行うガード。
 * 判定本体は AccessControlService に委譲し、ロジックを一元化する。
 * 使用例: @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
 */
@Component("accessGuard")
@RequiredArgsConstructor
public class AccessGuard {

    private final AccessControlService accessControlService;

    /** SYSTEM_ADMIN もしくは当該スコープの ADMIN/DEPUTY_ADMIN なら true。 */
    boolean isScopeAdmin(Authentication authentication, Long scopeId, String scopeType);

    /** SYSTEM_ADMIN もしくは当該スコープの ADMIN（DEPUTY 除く）なら true。 */
    boolean isScopeStrictAdmin(Authentication authentication, Long scopeId, String scopeType);

    /** 当該スコープのメンバー（MEMBER 以上）なら true。 */
    boolean isScopeMember(Authentication authentication, Long scopeId, String scopeType);

    /** 当該スコープで指定 permission を保有するなら true（DEPUTY_ADMIN の細粒度権限用）。 */
    boolean hasScopePermission(Authentication authentication, Long scopeId, String scopeType, String permission);
}
```

**設計上の取り決め:**

1. **SYSTEM_ADMIN は常に通す** — `isScopeAdmin` 等は内部で先に `accessControlService.isSystemAdmin(userId)` を確認し、true なら無条件許可（プラットフォーム管理者は全スコープを操作可能）。
2. **null/非認証は false** — `authentication == null || !authentication.isAuthenticated()` で早期 false。`authentication.getName()` のパース失敗（`NumberFormatException`）も false（既存ガードと同挙動）。
3. **scopeType は文字列リテラル** — SpEL 内で `'TEAM'` / `'ORGANIZATION'` を渡す。`AccessControlService` の既存シグネチャ（`String scopeType`）と一致。
4. **委譲先の再利用** — `isScopeAdmin` → `AccessControlService.isAdminOrAbove` ＋ `isSystemAdmin` の OR。`hasScopePermission` → `AccessControlService.checkPermission` の boolean 版（`roleService.hasPermission`）。新規 SQL は原則追加しない。
5. **boolean を返す（例外を投げない）** — `@PreAuthorize` の SpEL は boolean を期待する。`checkXxx`（void・例外送出）系ではなく `isXxx`（boolean）系に委譲する。

#### 3.3.2 適用形（per-scope EP）

```java
// Before（実機 no-op・per-scope 文脈なし）
@PreAuthorize("hasRole('ADMIN')")
@PostMapping("/teams/{teamId}/circulations")

// After（method-security 有効＋パス変数で都度判定）
@PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
@PostMapping("/teams/{teamId}/circulations")
```

#### 3.3.3 既存の明示 Service 層呼出との関係（多重防御として残置可）

circulation / shift / chat / faq の一部 EP は、`@EnableMethodSecurity` 未有効の現状を踏まえ **既に Service 層で `AccessControlService` を明示呼出している**（PR #1183 circulation / #1189 shift・chat / #1178 faq 系。`CirculationService.java:109` ほかにコメントあり）。

これらは SpEL ガード化した後も **そのまま残置してよい**（多重防御＝宣言と Service 層の二重で安全側）。method-security が将来何らかの理由で無効化されても Service 層が最後の砦として機能するため、**削除はリスクであり推奨しない**。新規 EP は「宣言（SpEL ガード）を第一防御、Service 層明示呼出を第二防御」とする方針を標準とする。

### 3.4 所有者リソース

ユーザー本人のみがアクセスすべきリソース（メモ・履歴書・個人時間割等）は、**所有者スコープのクエリで限定**する。

- 第一防御: `@PreAuthorize("@quickMemoAccessGuard.canAccess(#id, authentication)")` 等の所有者ガード（既存）。
- 第二防御: Repository が `findByIdAndUserId(id, currentUserId)` で所有者外を物理的に取得不能にする。

`isAuthenticated()` のみの 36 EP（§5）は「ログインさえしていれば誰でも」を意味するため、**本来は所有者ガードが必要な EP が紛れていないか Phase 3 で個別精査**する（特に media upload・personal timetable・announcement feed 系）。

---

## 4. 多層防御の全体像

| 層 | 強制対象 | 実装 | 本根治での扱い |
|---|---|---|---|
| **L1 フィルタ層**（粗い境界） | パス単位の公開/認証必須/SYSTEM_ADMIN | `SecurityConfig` `authorizeHttpRequests` | SYSTEM_ADMIN を JWT に載せることで `hasRole('SYSTEM_ADMIN')` 4 系統が機能回復 |
| **L2 メソッド層**（宣言＝強制） | EP 単位の SYSTEM_ADMIN / per-scope ADMIN / 所有者 | `@EnableMethodSecurity` ＋ `@PreAuthorize`（hasRole / SpEL ガード） | `@EnableMethodSecurity` 有効化で 97 個の宣言が実効化。per-scope は SpEL ガードへ移行 |
| **L3 Service 層**（最後の砦） | per-scope 認可・メンバーシップ・所有権 | `AccessControlService` 明示呼出・所有者スコープクエリ | 既存呼出は残置（多重防御）。新規 EP も二重化を標準とする |

**原則: L1〜L3 のどれか単独に依存しない。** 特に L2 は「宣言＝強制」を単一真実源とするが、L3 を最後の砦として温存する。

---

## 5. `@PreAuthorize` 分類カタログ（Phase 3 点火後の状態）

> ※2026-06-02 時点の調査記録。その後 Phase 1〜3 の根治が完了（#1266）し、下記の「現状の実機挙動」列の no-op 状態は解消された。

`@EnableMethodSecurity` 未有効により実機で無効化されていた `@PreAuthorize` の全数（2026-05-29 時点 `origin/main`、メソッド/クラスレベル合算 **97 個**）を分類した。各 Phase での処置方針と現在の実装状態を記載する。

> 注: 本タスク発令時の snapshot は「78 個」であったが、その後の機能追加（navsettings #1186 等）で増加し、調査時点では 97 個であった。**個数は時点で変動する**ため、確認時は再走査（§9.1 のコマンド）で最新数を確定すること。

| 分類 | 件数 | 代表 file | 調査時（2026-05-29）の実機挙動 | 根治後の正 | 処置 Phase | 実装状態 |
|---|---|---|---|---|---|---|
| **(A) SYSTEM_ADMIN（グローバル）** | 19 | `SystemAdminNavFeaturesController`(4), `SystemAdminSecurityScanController`, `SystemAdminAdCampaignController`, `SystemAdminAdCreativeController`, `AdminActionMemoController`, `SystemAdminEmailOutboxController`, `SystemAdminSystemLogController` | no-op（method-security off）。さらに JWT に SYSTEM_ADMIN 不在で、有効化しても全員 403 | JWT 載せ後に正常動作。`hasRole('SYSTEM_ADMIN')` のまま | Phase 1（JWT）→ Phase 3（有効化） | **🟢 実装済み（Phase 1 JWT 注入 + Phase 3 点火・#1266）** |
| **(B) per-scope ADMIN** | 17 | `CirculationAdminController`(5), `CirculationRecipientController`, `ShiftScheduleController`(2), `ShiftChangeRequestController`, `ChatChannelController`, `ContentTranslationController`(2), `PdfSignatureVerifyController`, `AttendanceBatchController` | no-op | SpEL ガード `@accessGuard.isScopeAdmin(...)` へ移行（パス変数で scope 判定）。スコープがパス変数で表せない EP（`ShiftChangeRequestController` の review・`PdfSignatureVerifyController`）は Service 層明示認可を真の強制点とする | Phase 2 | **🟢 実装済み（Phase 2 SpEL 化・Phase 3 点火）** |
| **(C) per-scope ADMIN or SYSTEM_ADMIN** | 10 | `AdminFaqController`(4), `AdminSupporterNameDisclosureController`(4), `AdminPublicSettingsController`(2) | no-op | SpEL ガード（SYSTEM_ADMIN は内部で常に許可）へ統合 | Phase 2 | **🟢 実装済み（Phase 2 SpEL 化・Phase 3 点火）** |
| **(D) 幻ロール TEACHER** | 3 | `AttendanceDisclosureController`(3) | no-op（現状フリーパス）。有効化後は TEACHER/ADMIN いずれも JWT 不在で全員 403 | §7.1 決定（A-1）= 学校チームの ADMIN/DEPUTY_ADMIN ＝教員相当。`@accessGuard.isScopeAdmin(..., #teamId, 'TEAM')` へ置換＋ Service 層明示認可 | Phase 2 | **🟢 実装済み（Phase 2 SpEL 化・Phase 3 点火）** |
| **(E) 負論理 SUPPORTER** | 1 | `ShiftPdfController` | no-op。有効化後も `!hasRole('SUPPORTER')` は常に true（排除不能）。scope 検証なし | SUPPORTER 排除＋ scope 検証を SpEL ガードで正しく表現（§7.2） | Phase 2 | **🔴 未着手（Phase 4 対象）** |
| **(F) SpEL 所有者ガード** | 10 | `QuickMemoController`(7), `QuickMemoAttachmentController`(3) | no-op | `@EnableMethodSecurity` 有効化でそのまま実効化（所有者ガードは設計正） | Phase 3 | **🟢 実装済み（Phase 3 点火・#1266）** |
| **(G) SpEL ロールチェッカー** | 1 | `AdminBusinessAlertController`（`@adminRoleChecker...`） | no-op | 有効化でそのまま実効化 | Phase 3 | **🟢 実装済み（Phase 3 点火・#1266）** |
| **(H) isAuthenticated()** | 36 | `*TimetableController` 群, `*ProfileMediaController` 群, `Announcement*Controller` 群, `NavSettingsController`, `ScheduleMediaController`(4), `BlogMediaController`, `MultipartUploadController`, `VillageCategoryController`, `DashboardWidgetVisibilityController`, `UserAdPreferencesController`(2) | no-op（ただし L1 `.anyRequest().authenticated()` が認証は担保） | 有効化で実効化。ただし **所有者ガードが必要な EP が紛れていないか個別精査**（§3.4） | Phase 3（有効化）＋ Phase 2（精査） | **🟢 実装済み（Phase 3 点火・#1266）** |

**合計: 19 + 17 + 10 + 3 + 1 + 10 + 1 + 36 = 97**

### 5.1 「生穴」リスト（無認可・誤認可で稼働中の EP）

method-security が無効な現状、以下は **認可が事実上ゼロ**で動いている。**Phase 2（per-scope SpEL 化＋生穴封鎖）で method-security OFF のまま明示 Service 層認可を注入して即封鎖**する（点火＝Phase 3 を待たない）。

| EP | file:line | 現状リスク | 封鎖方針 | 状態 |
|---|---|---|---|---|
| 出席開示 3 EP | `AttendanceDisclosureController.java:43,63,82` | 教員以外でも開示/非開示/履歴操作が可能（現状フリーパス。有効化すると逆に全員 403） | §7.1 決定（A-1）で `DisclosureService` に `checkAdminOrAbove(teamId, "TEAM")` を注入＋注釈 SpEL 化 | ✅ Phase 2 完了（Phase 3-a） |
| シフト PDF | `ShiftPdfController.java:39` | 任意 `scheduleId` の PDF にアクセス可（負論理＋ scope 検証なし＝ IDOR） | §7.2 の方針で scope 検証付き SpEL ガード化。Service 層でも `scheduleId` の所属チェックを追加 | ⏳ 未着手（負論理 (E)） |
| シフト変更依頼 review | `ShiftChangeRequestController.java:82` | 誰でも任意の変更依頼を承認/却下可能 | `ShiftChangeRequestService.review` で `scheduleId → teamId` 解決し `checkAdminOrAbove`（IDOR 封鎖込み） | ✅ Phase 2 完了（Phase 3-a） |
| 公開設定 PATCH 2 EP | `AdminPublicSettingsController` | 誰でも他団体のタイムライン/イベント公開設定を変更可能 | `AdminPublicSettingsService` に `checkAdminOrAbove` 注入＋注釈 SpEL 化 | ✅ Phase 2 完了（Phase 3-a） |
| 投稿者識別モード切替・履歴 4 EP | `AdminSupporterNameDisclosureController` | 誰でも他団体の識別モード切替・履歴閲覧が可能 | `SupporterNameDisclosureService` に `checkAdminOrAbove` 注入＋注釈 SpEL 化 | ✅ Phase 2 完了（Phase 3-a） |
| PDF 署名検証 | `PdfSignatureVerifyController.java:39` | 誰でも内部署名検証 API を叩ける（スコープ不在） | スコープ不在のため SYSTEM_ADMIN 限定（`checkSystemAdmin`）で厳格化。per-scope ADMIN への緩和は要件次第で再調整 | ✅ Phase 2 完了（Phase 3-a・要再判断） |
| translation mark-stale 2 EP | `ContentTranslationController` | — | 既に Service 層 `checkAdminOrAbove` 注入済。注釈のみ SpEL 化 | ✅ Phase 2 完了（注釈是正） |
| attendance batch 2 EP | `AttendanceBatchController` | — | 既に Service 層 `checkSystemAdmin` 注入済。クラス注釈を `hasRole('SYSTEM_ADMIN')` へ是正 | ✅ Phase 2 完了（注釈是正） |

> 注: (B)(C) のうち circulation/shift/chat/faq の主要 EP は PR #1183/#1189/#1178 で Service 層認可が注入済のため、生穴は緩和されている。**Service 層注入がまだ無かった EP**（publicview 公開設定・識別モード・出席開示・shift change-request review・pdf-verify）を Phase 2（Phase 3-a 実装陣）で明示認可注入して封鎖済み。残るは負論理 (E) のシフト PDF（`ShiftPdfController`）。

---

## 6. 失効保証（ロール変更の反映）

ロール（特に SYSTEM_ADMIN）は JWT に焼き込まれるため、**付与・剥奪の反映タイミング**を明示する。

| 操作 | 反映経路 | 最大遅延 |
|---|---|---|
| **SYSTEM_ADMIN 剥奪（即時必要）** | ロール剥奪処理で `AuthTokenService#setUserInvalidationTimestamp(userId)` を発火 → 対象ユーザーの全既存 Access Token を `iat < user_invalidated_at` で即失効。次回リクエストは 401 → リフレッシュ → 再発行時に SYSTEM_ADMIN が外れた roles で再判定 | 即時（次リクエストで失効） |
| **SYSTEM_ADMIN 剥奪（無効化発火を忘れた場合のフェイルセーフ）** | access token 15 分寿命 → リフレッシュ時 `AuthTokenRotationService` が `isSystemAdmin` を再判定し SYSTEM_ADMIN を外す | 最大 15 分 |
| **SYSTEM_ADMIN 付与** | 次回リフレッシュ（最長 15 分）で roles に SYSTEM_ADMIN 追加。即時付与が必要なら再ログインを促す | 最大 15 分（再ログインで即時） |
| **per-scope ADMIN 変更** | JWT に載せないため **常に最新**（リクエスト毎に `user_roles` を都度参照） | 即時 |

**設計判断:** SYSTEM_ADMIN の剥奪は権限縮小（安全側）であり、**剥奪処理に `setUserInvalidationTimestamp` 発火を必須化**することで即時失効を保証する。これを Phase 1 のロール剥奪経路に組み込む。既存の全デバイス無効化基盤（§2.7）をそのまま流用するため新規機構は不要。

---

## 7. 論点と決定（2026-05-30 マスター裁可済み）

> **裁可サマリ**: 論点A=**A-1**（出席開示＝学校チームの ADMIN/DEPUTY_ADMIN 相当）／論点B=**SUPPORTER 排除方針は維持し、チーム正式メンバー限定＋scope 検証へ是正**／論点C=**MEMBER に統一**。以下、各論点の決定を確定する。

### 7.1 【論点 A・要判断】出席開示の正規認可（幻ロール TEACHER）

`AttendanceDisclosureController` の 3 EP が参照する `TEACHER` ロールは **DB に存在しない**。出席開示は「教員が生徒・保護者へ評価を開示する」ドメイン操作だが、現状のロールモデル（SYSTEM_ADMIN/ADMIN/DEPUTY_ADMIN/MEMBER/SUPPORTER/GUEST）に「教員」が無い。選択肢:

| 案 | 内容 | 長所 | 短所 |
|---|---|---|---|
| **A-1. ADMIN/DEPUTY_ADMIN に寄せる** | 「学校チームの ADMIN/DEPUTY_ADMIN ＝教員相当」とみなし `@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')` に置換 | 新ロール不要・即実装可 | 「教員」と「管理者」の概念がずれる組織では過剰/過少権限になり得る |
| **A-2. permission ベースにする** | `ATTENDANCE_DISCLOSE` 等の permission を定義し `@accessGuard.hasScopePermission(..., 'ATTENDANCE_DISCLOSE')` で判定 | 細粒度・将来拡張に強い | permission シード・付与 UI の整備が必要（スコープ増） |
| **A-3. TEACHER ロールを新規シードする** | `roles` に TEACHER を追加し per-scope ロールとして付与 | ドメイン語彙と一致 | ロール体系の拡張＝広範な影響。付与フロー新設が必要 |

**✅ 決定（2026-05-30 マスター裁可）= A-1 を採用。** 出席開示の 3 EP（disclose/withhold/disclosure-history）は、`@PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")` を **`@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')`** に置換し、「学校チーム（クラス）の ADMIN/DEPUTY_ADMIN ＝教員相当」として per-scope 認可する。新ロールは追加しない。将来「教員＝管理者ではない」運用要件（F03.13 学校ドメイン）が確定した場合に A-2（permission `ATTENDANCE_DISCLOSE`）へ発展させる余地を残す。**Phase 2（per-scope SpEL 化＋生穴封鎖）で実装済み（Phase 3-a）**: `DisclosureService` に `checkAdminOrAbove(teamId, "TEAM")`（SYSTEM_ADMIN 短絡）を注入し、3 EP の注釈を `@accessGuard.isScopeAdmin(...)` へ置換。

### 7.2 【論点 B・要判断】シフト PDF の認可（負論理 SUPPORTER）

`ShiftPdfController` の `!hasRole('SUPPORTER')` は「SUPPORTER 以外は誰でも」という意図だが、(1) per-scope ロールを `hasRole` で表現する誤り、(2) `scheduleId` の所属スコープ検証が無い、の二重欠陥がある。方針:

- **SUPPORTER 排除を正しく表現**: `@accessGuard` に「当該スケジュールの所属チームで MEMBER 以上 **かつ** SUPPORTER でない」判定メソッドを追加するか、Service 層 `ShiftPdfService` で `scheduleId → teamId` を解決して `isSupporter` を弾く。
- **scope 検証を必須化**: PDF 取得前に `scheduleId` がリクエスタの所属チームのものか検証（IDOR 封鎖）。

**✅ 決定（2026-05-30 マスター裁可）= SUPPORTER 排除方針は維持し、正しい per-scope 表現へ是正。** `ShiftPdfController` の `!hasRole('SUPPORTER')` を撤廃し、「**当該スケジュールの所属チームの正式メンバー（MEMBER 以上）であり、かつ SUPPORTER ではない**」を満たす場合のみ PDF を取得可能にする。`ShiftPdfService` で `scheduleId → teamId` を解決し（IDOR 封鎖の scope 検証）、`@accessGuard` ないし `AccessControlService` でメンバー判定＋SUPPORTER 除外を行う。**Phase 2 で実装予定（負論理 (E)・本 Phase 3-a 陣では未着手）。**

### 7.3 【論点 C・確認のみ】MEMBER を全ユーザーに付与する妥当性

現状 JWT は全ユーザーに `["MEMBER"]` を付与している。本根治でも「全ユーザーの基底ロール＝ MEMBER」を踏襲する（§3.2.2）。ただし F01.1 §1269 のドキュメントは `["USER"]` 表記とゆれている。**✅ 決定（2026-05-30 マスター裁可）= `MEMBER` で統一**し、F01.1 のゆれを是正する（§8.2）。ロール名 `USER` は `roles` シードに存在しないため誤記と確定。

---

## 8. 段階計画（Phase 表）

各 Phase は **test-first（テスト先行）→ 実装 → 実機テスト** の順で進める（マスター方針）。Phase 間は依存順序があり、原則として前 Phase の実機テスト合格を確認してから次へ進む。

> **順序是正（2026-05-30）**: 当初 Phase 2＝method-security 有効化 → Phase 3＝per-scope SpEL 化 の順だったが、**Phase 3（per-scope SpEL 化＋生穴封鎖）を先、Phase 4（method-security 有効化）を後** に並べ替えた（番号もこの順に振り直し）。理由は §11 のリスク（点火の瞬間に未変換の `hasRole` が一斉 403 化する窓）を**構造的に消す**ためである。先に全 per-scope EP の注釈を `@accessGuard` の正しい SpEL へ変換し、明示 Service 層認可で生穴を塞ぎ切ってから method-security を点火すれば、点火時点では全注釈が既に正しい状態であり、一斉 403 の窓が生じない。なお per-scope の **生穴封鎖（明示 Service 層認可）は method-security OFF のままでも即効く**ため、Phase 3 を先行する利点は「点火前にセキュリティ穴が塞がる」点でも大きい。

| Phase | 名称 | 主タスク | test-first の要点 | 依存 | 実装状態 |
|---|---|---|---|---|---|
| **0** | 設計書（本 PR） | 本書新規＋ 02/F01.1 更新＋ README 参照追加＋ §2.4 嘘コメント是正をタスク化 | — | — | 🟢 完了 |
| **1** | 要石: JWT に SYSTEM_ADMIN | 発行ヘルパー集約 → 5 経路で `isSystemAdmin` ロード注入 → リフレッシュ再判定 → 剥奪経路に `setUserInvalidationTimestamp` 発火 | 「SYSTEM_ADMIN ユーザーの JWT に SYSTEM_ADMIN が載る / 一般ユーザーには載らない / 剥奪後リフレッシュで外れる」を先にテスト化 | 0 | 🟢 完了（#1266・2026-06-02） |
| **2** | per-scope SpEL ガード化＋生穴封鎖 | `AccessGuard` Bean 新設 → (B)(C) per-scope EP を `@accessGuard.isScopeAdmin(...)` 化 → 既存 Service 層呼出と統合（残置）→ §5.1 生穴のうち**未注入 EP を明示 Service 層認可で最優先封鎖**（method-security OFF でも即効く）→ §7.1（TEACHER＝A-1）/§7.2（ShiftPdf 負論理）の正規化も併せて実施 | 「他団体 ADMIN が別団体の管理 EP で 403」「自団体 ADMIN は 2xx」「非権限者が生穴 EP で COMMON_002」「SYSTEM_ADMIN 短絡」を先に | 1 | 🟢 完了（#1266・2026-06-02）。ただし ShiftPdf 負論理(E) は未着手 |
| **3** | method-security 有効化 | `@EnableMethodSecurity(prePostEnabled=true)` を `SecurityConfig` に付与 → (A)(F)(G)(H) が実効化 → `@WebMvcTest` 互換改修（method-security 有効下でのスライステスト方針）→ §2.4 の嘘コメント文言是正 | 「SYSTEM_ADMIN 系 EP が SYSTEM_ADMIN で 2xx・一般で 403」「所有者ガード EP が他人で 403」を先に | 2（全 per-scope EP の注釈が正しい SpEL に変換済みかつ SYSTEM_ADMIN が JWT に載っていること） | 🟢 完了（#1266・2026-06-02。`@EnableMethodSecurity(prePostEnabled = true)` 付与・WebMvcTest 103 件修正・統合テスト 11 件追加） |
| **4** | 幻ロール・負論理の最終確認＋残整理 | Phase 2 で正規化した出席開示（A-1）/ ShiftPdf 負論理の **method-security 有効下での実機検証** → 取りこぼし EP の点検 | 「教員相当のみ開示可」「SUPPORTER は PDF 403・他団体は IDOR 403」を method-security 有効下で再確認 | 3 | ⏳ 未着手（ShiftPdfController 負論理 (E) が残存） |
| **5** | 認可統合テスト＋確定 | 横断統合テスト（SYSTEM_ADMIN 通る/非権限 403/他団体 ADMIN 弾く）→ docs/security 確定（本書ステータス 🟢 へ）→ README 表更新 | 統合テストマトリクス（§10）を網羅 | 4 | ⏳ 未着手 |
| **6** | 認可層性能最適化 | per-scope 判定結果を Valkey にキャッシュ（TTL: 60秒）。N+1 クエリを解消。大規模組織（1万人以上）での認可性能を担保 | キャッシュ HIT/MISS を判定し、MISS 時は DB 参照し一致することを確認。ロール変更後 60 秒以内にキャッシュ失効することを検証 | ⏳ Phase 5 完了後に検討 | ⏳ 未着手 |

> **点火（Phase 3）の前提**: `@EnableMethodSecurity` 有効化は **破壊的変更**である（97 個の宣言が一斉に実効化する）。点火の瞬間に system-admin 系が全員 403 化しないために Phase 1（SYSTEM_ADMIN を JWT に載せる）が完了していること、**かつ点火の瞬間に未変換の `hasRole('ADMIN')` 等が一斉 403 化しないために Phase 2（全 per-scope EP の注釈を `@accessGuard` の正しい SpEL に変換済み）であること**を必須前提とする。すなわち **Phase 3 は「全注釈が正しい状態である」前提でのみ実施してよい**。Phase 1・2 の実機テスト合格を確認するまで Phase 3 を先行してはならない（**Phase 順序の逆転を禁止**）。
>
> **🟢 2026-06-02 点火済み**: Phase 1・2 が完了した状態で Phase 3 を点火（#1266）。`@WebMvcTest` スライステスト 103 件の改修・統合テスト 11 件追加も完了済み。

---

## 9. テスト戦略

### 9.1 再走査コマンド（実装時に最新数を確定）

```
# @PreAuthorize の全数と分類
grep -rP '^\s*@PreAuthorize\(' backend/src/main/java --include=*.java | grep -c .
# 幻ロール検出（roles シードに無いロール名の参照）
grep -rn "hasRole('TEACHER')\|hasRole('USER')" backend/src/main/java --include=*.java
# 負論理検出
grep -rn "!hasRole" backend/src/main/java --include=*.java
# @EnableMethodSecurity の付与確認（Phase 3 後に 1 件になること）
grep -rn "@EnableMethodSecurity" backend/src/main/java --include=*.java
```

### 9.2 Phase 別テスト（test-first）

- **Phase 1（JWT）**: `AuthTokenService` / 各 `Auth*Service` のユニットテストで「SYSTEM_ADMIN ユーザーの発行 JWT に `SYSTEM_ADMIN` claim が含まれる / 一般ユーザーには含まれない / リフレッシュで再判定される」を検証。剥奪後の `user_invalidated_at` 発火を検証。
- **Phase 2（per-scope SpEL 化＋生穴封鎖）**: `AccessGuard` の各メソッド（SYSTEM_ADMIN 短絡 / per-scope ADMID 許可・拒否 / null・非認証 false）をユニットで検証。生穴 EP の Service 層認可を「非権限者→COMMON_002（他団体 ADMIN 含む）/ SYSTEM_ADMIN 短絡で通過 / 取得・保存より前に弾く」でユニット/スライス検証（method-security OFF でも効く）。出席開示・shift review の IDOR（`scheduleId/teamId` 解決後の他団体 403）もここで検証。
- **Phase 3（method-security）**: 代表的な (A) EP を `@SpringBootTest` + MockMvc で「SYSTEM_ADMIN トークンで 2xx / 一般トークンで 403 / 未認証で 401」。(F) 所有者ガード EP で「他人の memoId に対し 403」。`@WebMvcTest` スライスは method-security 有効下で `@PreAuthorize` の SpEL Bean（`@accessGuard` 等）をモック注入する方針を確立する。点火後に per-scope EP（Phase 2 で SpEL 化済み）が「自団体 ADMIN 2xx / 他団体 ADMIN・MEMBER 403」となることを実機検証。
- **Phase 4（幻ロール・負論理の実機確認）**: 出席開示（A-1）・シフト PDF の IDOR テスト（他団体リソースへのアクセス 403）を method-security 有効下で再確認。
- **Phase 5（統合）**: §10 のマトリクスを横断統合テストで網羅。

### 9.3 `@WebMvcTest` 互換改修方針（Phase 2）

`@EnableMethodSecurity` 有効化後、`@WebMvcTest` スライステストは `@PreAuthorize` を評価しようとするため、SpEL が参照する Bean（`@accessGuard` / `@quickMemoAccessGuard` / `@adminRoleChecker`）が ApplicationContext に存在しないと起動失敗する。方針:

- スライステストでは対象ガード Bean を `@MockBean` で注入し、認可可否を明示的にスタブする。
- 認可ロジック自体の検証は `AccessGuard` / `AccessControlService` の専用ユニットテストに集約し、Controller スライステストは「認可が通る/通らない時の HTTP 挙動」に限定する（関心の分離）。

---

## 10. 認可統合テストマトリクス（Phase 5）

| シナリオ | 期待 | 検証する病巣 |
|---|---|---|
| SYSTEM_ADMIN が system-admin 系 EP を叩く | 2xx | ①②③④ |
| 一般ユーザーが system-admin 系 EP を叩く | 403 | ③④ |
| 未認証が認証必須 EP を叩く | 401 | L1 |
| 組織 X の ADMIN が組織 X の管理 EP | 2xx | ⑥ |
| 組織 X の ADMIN が組織 Y の管理 EP | 403（他団体弾き） | ⑥・IDOR |
| MEMBER が per-scope 管理 EP | 403 | ⑥ |
| 他人のメモ（所有者リソース）にアクセス | 403/404 | (F) |
| 教員相当でない者が出席開示 EP | 403 | ⑤・生穴 |
| SUPPORTER がシフト PDF | 403 | ⑤（負論理） |
| 他団体の `scheduleId` でシフト PDF | 403 | IDOR |
| SYSTEM_ADMIN 剥奪後の旧トークンで system-admin EP | 401（無効化）or 403（リフレッシュ後） | 失効保証 |

---

## 11. リスクと留意点

| リスク | 内容 | 緩和 |
|---|---|---|
| **Phase 3（点火）の一斉実効化** | 97 個の宣言が同時に効くため、Phase 1（JWT に SYSTEM_ADMIN）未完だと system-admin 系が全員 403 化、Phase 2（注釈の SpEL 化）未完だと per-scope `hasRole` 系が一斉 403 化 | Phase 順序厳守（§8 注記）。**Phase 3 点火前に Phase 1・2 の実機テスト合格を必須化**。Phase 2 で全注釈を正しい SpEL に変換済みにしておくことで点火の窓を構造的に消す |
| **幻ロール EP の挙動反転** | 現状フリーパス → 有効化で全員 403。利用中なら機能停止 | **Phase 2 で §7.1 決定（A-1）と同時に正規化済み**（`@accessGuard.isScopeAdmin(..., #teamId, 'TEAM')`）。加えて Service 層で明示認可を注入し、method-security OFF の現状でも生穴を即封鎖。Phase 3 点火時には既に正しい状態 |
| **per-scope 判定の N+1** | SpEL ガードがリクエスト毎に `user_roles` を参照 | `AccessControlService` の既存クエリは単一 SQL。ホットパスでは必要に応じキャッシュを検討（本根治のスコープ外） |
| **`@WebMvcTest` 大量改修** | method-security 有効化で既存スライステストが軒並み起動失敗 | §9.3 の方針で `@MockBean` 注入を定型化。Phase 3 のテスト工数を見込む |
| **ロール変更の反映遅延（付与）** | SYSTEM_ADMIN 付与は最大 15 分 | 即時付与が要件なら再ログイン導線。剥奪は §6 で即時化 |

---

## 12. 今後の拡張（スコープ外・意思決定済み）

- **per-scope ロールの細粒度 permission 全面移行** — DEPUTY_ADMIN の permission 駆動（`existsDeputyAdminWithPermissionInOrganization` 等）は既に部分導入済。全 per-scope EP の permission 化は本根治のスコープ外とし、ロール粒度で先行根治する。
- **認可判定のキャッシュ層** — per-scope 判定のリクエスト毎 DB 参照を Valkey でキャッシュする最適化は、機能回復後の性能フェーズで評価。
- **監査強化** — 認可拒否（403）の監査イベント化は F10.3 と連携して別途検討。

---

## 13. 自己精査（§22 相当・網羅性と矛盾チェック）

本書の完全性・整合性を以下の観点で自己点検した。

### 13.1 網羅性

- ✅ **病巣 6 件**（①JWT 固定 / ②filter 非補完 / ③method-security 未有効 / ④filter 全員 403 ＋嘘コメント / ⑤幻ロール・負論理 / ⑥per-scope の hasRole 誤用）を file:line 根拠付きで網羅。
- ✅ **`@PreAuthorize` 全数 97 個**を 8 分類（A〜H）で完全分類し、合計が一致することを検算済（19+17+10+3+1+10+1+36=97）。
- ✅ **生穴リスト**（出席開示 3 / シフト PDF 1 / per-scope 未注入 EP）を明示し封鎖 Phase を割当。
- ✅ **JWT claims 設計**（roles 配列追加 vs boolean の比較と採用理由）、**SpEL ガード Bean 設計**（4 メソッドのシグネチャ・取り決め 5 件）、**失効保証**（即時/15 分/per-scope 即時）を記載。
- ✅ **段階計画 Phase 0〜5** に test-first を明記。**統合テストマトリクス 11 シナリオ**を病巣に対応付け。
- ✅ **要マスター判断 3 件**（TEACHER / ShiftPdf 負論理 / MEMBER vs USER 表記）を論点として分離。

### 13.2 矛盾チェック

- ✅ **F01.1 との整合**: F01.1 §1269/§1286 は「SYSTEM_ADMIN を JWT に載せる」設計を既に記述しており、本書の方針（roles 配列追加）と一致。実装がそれに追従していなかった点を病巣①として整理。F01.1 の例 `["MEMBER"]`／本文 `["USER"]` のゆれは §7.3/§8.2 で `MEMBER` 統一として是正対象に。
- ✅ **01 との整合**: 01 はパス単位境界（L1）、本書は意味論と強制ポイント（L2/L3）。多層防御（§4）で両者を併存させ、重複定義なし。01 の `hasRole("SYSTEM_ADMIN")` 4 系統は本書 Phase 1 で機能回復することを明記。
- ✅ **02 との整合**: 失効保証（§6）は 02 の全デバイス無効化タイムスタンプ／JTI ブラックリストに依存。02 側に roles claim の現状と改善を追記（§8.1 の更新で同期）。
- ✅ **既存コードとの整合**: SpEL ガード設計は既存 `AdminRoleChecker`／`QuickMemoAccessGuard` の定石に準拠。判定は `AccessControlService` 既存メソッドへ委譲し新規ロジックを増やさない。circulation/shift/chat/faq の Service 層呼出（#1183/#1189/#1178）は多重防御として残置と明記。
- ✅ **個数の時点依存性**: snapshot 78 個 → 調査時 97 個の差異を明記し、実装時の再走査（§9.1）を必須化。固定値に依存しない記述とした。

### 13.3 残課題（本書では決め切らない事項）

- ⚠️ §7.1 TEACHER の正規認可定義（A-1/A-2/A-3）— **要マスター判断**。
- ⚠️ §7.2 ShiftPdf の SUPPORTER 排除要件の現行有効性 — **要マスター判断（F03.5 最新仕様確認）**。
- ⚠️ Phase 2 の `@WebMvcTest` 改修工数 — 実装着手時にスライステスト総数を確定して見積る。

---

## 15. トーナメント連絡・成績・移籍スコープの認可方針（F08.7.1）

F08.7.1 で新設する **大会連絡スペース**（掲示板・チャット）・**成績ウィジェット**・**組織またぎリーグ移籍**・**リーグ単位ファイル置き場**・**試合メンバー表**は、既存の TEAM/ORGANIZATION スコープに収まらない新スコープ（`TOURNAMENT` / `TOURNAMENT_DIVISION` / 組織またぎ移籍）を導入する。本節はその認可方針の正典を定める。設計詳細は [F08.7.1_tournament_extensions/](../features/F08.7.1_tournament_extensions/) を参照。

### 15.1 連絡スペースの read/write 分離（`TournamentContactAccessService`）

tournament ドメインに `TournamentContactAccessService` を新設し、read（閲覧）と write（投稿）を分離する。村の `VillageBulletinAccessService`（二段認可）を範とする。

| 操作 | 許可主体 | 根拠 |
|------|---------|------|
| 閲覧（`canView`） | (a) 公開トグル ON のスペースは PUBLIC・未ログイン含め全員（**read-only**）、(b) 参加チーム（`tournament_participants` の status=REGISTERED/ACTIVE）のメンバー、(c) 主催組織 ADMIN、(d) SYSTEM_ADMIN | チーム解決源泉＝`tournament_participants`。WITHDRAWN/DISQUALIFIED は除外 |
| 投稿（`canPost`） | (a) 各チームの ADMIN/DEPUTY_ADMIN、(b) 主催組織 ADMIN、(c) SYSTEM_ADMIN のみ | 権限昇格防止（MEMBER が代表になりすませない） |
| 公開トグル切替 | 主催組織 ADMIN / SYSTEM_ADMIN のみ | チーム代表には開放しない |

- 存在しない/論理削除済みスペースは一律 **404**（IDOR 対策・存在を漏らさない）。
- クロスドメインの所属判定は `TeamMembershipRepository` / `AccessControlService` を **ID 参照の読み取り**でのみ呼ぶ（クロスドメイン FK なし＝アーキ原則 1）。

### 15.2 PUBLIC 公開時の露出方針（成績 / chat）

- **chat の公開は既定 OFF**（`is_private=TRUE`）。公開トグル ON でも **PUBLIC は read-only**（投稿は常に代表＋主催者）。スペクテーター（観戦者）への露出は閲覧のみに限定する。
- 公開スペースには「広報目的の連絡」のみを置く運用を推奨し、内部連絡（未確定の対戦相手・運営内部連絡）は非公開カテゴリ/チャンネルに保つ。
- **成績ウィジェット**（F02.2.1 で min_role を PUBLIC に下げ得る）では、ウィジェット API 側で**大会 visibility を再チェック**し、非公開（DRAFT/private）大会の成績が PUBLIC 閲覧者へ漏れないようにする（特に `ORG_TOURNAMENT_SUMMARY` は DRAFT 大会を PUBLIC レスポンスから除外）。
- **ファイル置き場**（F08.7.1 領域④・`TOURNAMENT` / `TOURNAMENT_DIVISION` スコープ）の PUBLIC 露出は、**連絡スペースの公開トグル（`tournament_contact_space.is_public`）に追従**する（ファイル専用の公開フラグは持たない）。公開時も**閲覧（VIEW/DOWNLOAD）のみ**で、アップロード/編集（UPLOAD/MANAGE）は常にチーム代表＋主催者に限定する。公開フォルダには広報用資料（大会要項・規約等）のみを置く運用を推奨し、内部資料は非公開フォルダに保つ。判定は連絡スペースと同じ `TournamentContactAccessService.canView/canPost` を流用し、認可規則を二重定義しない。

### 15.3 リーグ移籍 API の認可（プッシュ＋承認の対称モデル）

昇格・降格はどちらも「**手放す側 org が送り出し（DISPATCHED）→ 受け入れる側 org が承認（PLACED）**」の対称モデル（F08.7.1 §1.1）。

| 操作 | 許可ロール |
|------|-----------|
| 昇格送り出し（`promote`） | **下位（手放す側）リーグ大会の主催組織 ADMIN** / SYSTEM_ADMIN のみ |
| 降格送り出し（`relegate`） | **上位（手放す側）リーグ大会の主催組織 ADMIN** / SYSTEM_ADMIN のみ |
| 承認・配属（`approve`） | **受け入れ側組織 ADMIN**（昇格=上位 org / 降格=下位 org）のみ |
| 受け入れ拒否（`decline`） | 受け入れ側組織 ADMIN のみ |
| 送り出し取消（`cancel`） | 手放す側組織 ADMIN のみ（応答前のみ） |
| チーム側閲覧（`GET /teams/{teamId}/league-transfers`） | 当該チーム MEMBER 以上（**閲覧のみ**。承認・拒否は org が行う） |

- 送り先の正当性を `OrganizationHierarchyService` で必須検証: 昇格の受け入れ先は送り出し元の **親 org 系列（祖先）** に限定、降格の受け入れ先は送り出し元の **子孫 ASSOCIATION** に限定（無関係 org への送り出しを防ぐ）。該当 0 件なら保留して ADMIN へ警告（症状を隠さない）。
- 送り出し（DISPATCHED 起票）は手放す側 org ADMIN のみ・承認（PLACED）は受け入れ側 org ADMIN のみ＝役割を非対称に分離し、片方の org が単独で移籍を完結できないようにする。
- 二重起票は `league_transfer` の `UNIQUE(team_id, season, direction)` で抑止。存在しない対象は 404。team_id/division_id/organization_id は ID 参照のみ（クロスドメイン FK なし）。

### 15.4 試合メンバー表の認可（F08.7.1 領域⑤）

| 操作 | 許可ロール |
|------|-----------|
| 自チーム roster の提出/テンプレ適用 | **当該チームの ADMIN/DEPUTY のみ**（他チームの roster は操作不可・403） |
| 全チーム roster 閲覧 | 主催組織 ADMIN / SYSTEM_ADMIN |
| 締切（`roster_deadline`）設定 | 主催組織 ADMIN / SYSTEM_ADMIN |

- **既定は代理入力なし**（主催者は閲覧・締切管理のみ）。締切超過の提出は 409（締切後ロック）。提出操作は監査ログに残す。存在しない match/roster は 404。

---

## 14. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-06-12 | **ステータス追従**: Phase 1〜3 完了（#1266・2026-06-02 点火）を反映。冒頭ステータス・§1 概要・§5 カタログの「現状挙動」列・§8 段階計画の実装状態列を更新。根治済みの病巣記述に「根治済み注記」を追記。Phase 4（ShiftPdf 負論理）・Phase 5（統合テスト）が未着手であることを明示。 |
| 2026-05-31 | §15 を F08.7.1 最新版に**改訂**: (a) §15.2 に**ファイル置き場の PUBLIC 露出方針**を追加（連絡スペースの `is_public` に追従・PUBLIC は VIEW/DOWNLOAD のみ・書込は代表＋主催者・`TournamentContactAccessService.canView/canPost` 流用）。(b) §15.3 リーグ移籍を**プッシュ＋承認の対称モデル**に改訂（昇格=下位 org 送り出し／降格=上位 org 送り出し → 受け入れ側 org が承認。送り先は親系列/子孫 ASSOCIATION に限定。チームは閲覧のみ）。(c) §15.4 **試合メンバー表**の認可を新設（提出=自チーム ADMIN/DEPUTY・閲覧/締切=主催組織 ADMIN・代理入力なし・締切後 409）。 |
| 2026-06-02 | §8 段階計画テーブルに Phase 6「認可層性能最適化」（per-scope 判定結果を Valkey にキャッシュ TTL:60秒・N+1 解消）を追加 |
| 2026-05-31 | §15「トーナメント連絡・成績・移籍スコープの認可方針（F08.7.1）」を追加: (1) 連絡スペースの read/write 分離（`TournamentContactAccessService`・閲覧=参加チーム＋公開時 PUBLIC read-only、投稿=各チーム代表＋主催組織 ADMIN）(2) PUBLIC 公開時の成績/chat 露出方針（chat 既定 OFF・PUBLIC は read-only・非公開大会成績の再チェック）(3) リーグ移籍 API の認可。詳細は [F08.7.1](../features/F08.7.1_tournament_extensions/) |
| 2026-05-30 | 新規作成。認可基盤完全根治（案①）の正典モデル・病巣カタログ（file:line 根拠）・SpEL ガード設計・JWT claims 設計・`@PreAuthorize` 97 個分類カタログ・生穴リスト・段階計画 Phase 0〜5・テスト戦略・統合テストマトリクスを定義 |
| 2026-05-30 | **§8 段階計画の順序是正**: Phase 3（per-scope SpEL 化＋生穴封鎖）を Phase 4（method-security 有効化）より前に並べ替え（番号も振り直し）。点火の瞬間に未変換 `hasRole` が一斉 403 化する窓を構造的に消す方針を明記。§5/§5.1/§7.1/§7.2/§9/§11 の Phase 参照を追従更新。**Phase 3-a 実装**（per-scope 生穴封鎖）として `AccessGuard` Bean 新設、AdminPublicSettings/SupporterNameDisclosure/Disclosure/ShiftChangeRequest.review/PdfSignatureVerify の生穴を明示 Service 層認可で封鎖、対象 EP の `@PreAuthorize` を `@accessGuard.isScopeAdmin(...)`/`hasRole('SYSTEM_ADMIN')` へ是正済み（§5.1 状態列参照）。残: 負論理 (E) シフト PDF |
