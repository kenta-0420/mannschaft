# 06. ビジネスロジック攻撃・不正利用防止

> **ステータス**: 🟡 設計確定（§4.3 レートリミットは実装完了 — Valkey 化 全 18 フィルタ＋共通基盤 #1470/1471/1472/1474・2026-06-12）
> **実装フェーズ**: Security Hardening Phase 3（ビジネスロジック強化）
> **最終更新**: 2026-06-12
> **関連ドキュメント**: [README](README.md), [01 認可基盤](01_authorization_baseline.md), [03 ロール・権限モデル](03_role_authority_model.md), F03.5 シフト, F08系 ポイント/トーナメント, F09.17 広告, F22.1 市（マーケット）

---

## 1. 概要

ビジネスロジック攻撃とは、**認証・認可を突破せずに、アプリケーションの「正しい動作」を悪用する攻撃**である。認証済みの正規ユーザーが、本来意図されていない操作を行うことで、データ完全性の破壊・不正利益の取得・他者への被害を引き起こす。

OWASP Top 10:2021 **A04（安全が考慮されない設計）** に分類される。認証・認可の強化（A01/A07）だけでは防げないため、機能設計レベルでの防止策が必要である。

### 1.1 従来の攻撃との違い

| 攻撃タイプ | 特徴 | 対策層 |
|---|---|---|
| SQL インジェクション | 入力値の問題 | 入力検証・パラメータバインディング |
| IDOR（Broken Object Level Authorization） | 認可の問題 | 所有権チェック |
| **ビジネスロジック攻撃** | 正規の操作を組み合わせた悪用 | **設計・状態管理・監査** |

---

## 2. Mannschaft 固有の攻撃面マップ

| ドメイン | 攻撃面 | リスク | 対策パターン |
|---|---|---|---|
| ポイント/ランキング（F08系） | 試合結果を不正に書き換えてスコア操作 | データ完全性破壊 | 更新者 role チェック + 更新前後 diff 監査ログ |
| シフト管理（F03.5） | 自分以外のシフト枠を奪う（シフト ID を総当たり） | IDOR 変形 | organization_id + 担当者 ID の二重チェック |
| 招待トークン（F01系） | トークンを再利用して大量アカウント生成 | スパムアカウント | トークン使用後の即時失効・使用回数制限 |
| マーケット（F22.1） | 掲載価格・条件を POST で直接書き換え | 詐欺 | 掲載者 ID == requestUser チェック |
| トーナメント（F08.7） | 組み合わせ・試合結果の不正更新 | 競技不正 | 管理者専用 API + 変更監査ログ |
| 広告（F09.17） | 自動クリック / インプレッション水増し | 広告詐欺 | IP + User-Agent 一意性チェック + 統計的異常検知 |
| サーキュレーション（F16系） | 承認フロー迂回（PENDING→APPROVED を REVIEW 飛ばし） | 内部統制破壊 | 状態機械の遷移制約（不正遷移 → 422） |
| 在席インジケーター（ロビー機能） | オンライン状態の外部公開 → ストーキング | プライバシー侵害 | 同一組織メンバーにのみ在席情報を公開 |

---

## 3. 対策パターン（共通）

### 3.1 楽観的ロック（同時更新の防止）

同一リソースへの並行更新が起きた場合、後者の更新が前者を上書きして矛盾したデータを作り出す（Last Write Wins 問題）。

```java
// 楽観的ロックの実装例
@Entity
public class MatchResultEntity {
    @Version
    private Long version;  // 更新のたびにインクリメント
    ...
}
```

- JPA `@Version` アノテーションで楽観的ロックを適用する
- クライアントはリソース取得時の `version` 値を更新リクエストに含める
- 競合時は **409 Conflict** を返し、クライアントに再取得・再操作を促す
- 対象: 試合結果・スコア・シフト確定・承認ステータス等の重要リソース

### 3.2 状態機械の入力検証

ビジネスエンティティの状態遷移は**許可された遷移のみ**を受け付ける。

```java
// 状態遷移の例（サーキュレーション）
public enum CirculationStatus {
    DRAFT, PENDING_REVIEW, REVIEWING, APPROVED, REJECTED;

    public boolean canTransitionTo(CirculationStatus next) {
        return switch (this) {
            case DRAFT -> next == PENDING_REVIEW;
            case PENDING_REVIEW -> next == REVIEWING || next == DRAFT;
            case REVIEWING -> next == APPROVED || next == REJECTED;
            case APPROVED, REJECTED -> false;  // 終端状態
        };
    }
}

// Service 層での適用
if (!current.canTransitionTo(requested)) {
    throw new InvalidStateTransitionException(current, requested);  // 422
}
```

- 不正な状態遷移リクエスト → **422 Unprocessable Entity**
- 許可される遷移を enum または専用クラスで明示する
- テストでは全許可/不許可遷移パターンを網羅する（§6 参照）

### 3.3 冪等性トークン（重複操作の防止）

送信ボタン二重クリック、ネットワーク再送等による重複処理を防ぐ。

```
クライアント: Idempotency-Key: "550e8400-e29b-41d4-a716-446655440000"

サーバー処理:
1. Valkey に `idempotency:{key}` が存在するか確認
2. 存在する → 前回の結果を返す（409 ではなく 200/201 の前回レスポンスを返す）
3. 存在しない → 処理実行 → 結果を Valkey に TTL 付きで保存
```

- 決済・ポイント付与・重要な状態変更 API で採用を検討する
- `Idempotency-Key` は UUID v4 以上のランダム性を要求する
- TTL: 24時間（再送攻撃の有効窓を制限）

### 3.4 監査ログ必須化

ビジネスロジック攻撃は「正規の操作」として実行されるため、事後の検知・証拠保全に監査ログが不可欠。

以下の操作には全て `AuditLog` エントリを作成すること:

| 操作種別 | 対象 |
|---|---|
| 金額・費用の変更 | 広告予算・招待料金・決済金額 |
| スコア・成績の変更 | 試合結果・ランキングポイント |
| 権限・ロールの変更 | ADMIN 昇格・降格・SYSTEM_ADMIN 付与/剥奪 |
| 承認ステータスの変更 | サーキュレーション・シフト申請・保護者同意 |
| 削除・論理削除 | ユーザー退会・コンテンツ削除 |

---

## 4. レートリミット統一戦略

### 4.1 現状の問題

- 25+ 個の `RateLimitFilter` が各機能に散在している
- 閾値の基準が統一されておらず、機能によって異なる値が設定されている
- レートリミット超過時のレスポンスヘッダーが統一されていない
- 制限主体（IP vs ユーザー ID）の選択が機能ごとに異なる

### 4.2 標準閾値テーブル

| エンドポイント種別 | 制限主体 | 標準閾値 | 超過時レスポンス |
|---|---|---|---|
| 公開 API（認証不要） | IP アドレス | 60 req/分 | 429 Too Many Requests |
| 認証済み READ 系 | ユーザー ID | 300 req/分 | 429 |
| 認証済み WRITE 系 | ユーザー ID | 60 req/分 | 429 |
| 送信系（メール・通知） | ユーザー ID | 10 req/分 | 429 |
| 管理者 API | ユーザー ID | 120 req/分 | 429 |
| 認証（ログイン・register） | IP + メール | **5 req/分**、5 失敗でアカウントロック（30分） | 423 Locked / 429 |
| パスワードリセット申請 | IP + メール | **3 req/分** | 429 |
| メール認証コード送信 | ユーザー ID | **3 req/分** | 429 |

> 各機能の固有要件（F01.9 保護者同意招待は 24h 10回等）は上記標準閾値より厳しい制限を設けてよい。標準閾値は上限の目安である。
>
> ログイン・パスワードリセット・メール認証コード送信の数値は [02 Cookie とセッション §5](02_cookie_and_session.md) と統一している。

### 4.3 実装方針

```
Valkey (Redis 互換) を使用した固定ウィンドウ方式:
（旧記述は「スライディングウィンドウ」としていたが、擬似コード・実装とも
windowStart 切り捨てキーによる固定ウィンドウであり、文言を実態に合わせて訂正。
ウィンドウ境界での最大2倍バーストは固定ウィンドウの既知特性として許容する）

1. INCR  mannschaft:rate:{userId}:{windowStart}
2. EXPIRE mannschaft:rate:{userId}:{windowStart}  60
3. IF count > threshold THEN return 429

レスポンスヘッダー（統一）:
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 42
X-RateLimit-Reset: 1735689600  （Unix timestamp）
Retry-After: 18  （429/423 の場合のみ）
```

- 将来的には Gateway 層（Spring Cloud Gateway 等）への集約を検討
- 現在の `RateLimitFilter` 実装の閾値を本テーブルに揃えるリファクタリングを推奨

#### 4.3.1 実装状況（Valkey 化 全陣完了 — 2026-06-12 / PR #1470・#1471・#1472）

共通基盤を `com.mannschaft.app.common.ratelimit` パッケージに実装した。

| クラス | 責務 |
|---|---|
| `ValkeyRateLimiter` | 中核。`tryConsume(zone, key, limit, window)` で固定ウィンドウカウント。キーは `mannschaft:rate:{zone}:{key}:{windowStart}`。**Lua スクリプト（DefaultRedisScript）で INCR + 初回 EXPIRE を原子化**（TTL = ウィンドウ長 + 5 秒マージン） |
| `AbstractRateLimitFilter` | フィルタ基底。キー解決（認証時 `u:{userId}` / 未認証時 `ip:{ip}`、§4.4 の X-Forwarded-For 優先）、§4.3 標準ヘッダー付与（成功時も付与）、429 応答（JSON ボディ + `Retry-After`）を提供。各フィルタはエンドポイント判定と `(zone, limit, window)` 宣言のみ持つ |
| `RateLimitRule` / `RateLimitResult` | 規則・判定結果の record |

**fail-open 方針（可用性優先）**: Valkey 障害（`DataAccessException` 系）・Redis Bean 不在時は
リクエストを通す（`allowed=true`）。レートリミット基盤の障害でサービス全体を止めないための
設計判断であり、発生は `log.warn` + Micrometer カウンタ **`mannschaft.ratelimit.failopen`**
（tag: `zone` / `reason`）で必ず可視化する（静かな無効化にしない）。
auth 系の既存 Valkey fail-open（`AuthService` / `AuthTokenService`）と同方針。

依存解決は `ObjectProvider` の遅延解決で統一（`StringRedisTemplate` / `ValkeyRateLimiter` とも）。
`@WebMvcTest` 等の最小テストコンテキストで Redis Bean が無くてもフィルタ生成・コンテキストロードを阻害しない。

**移行状況: 全 18 フィルタ移行完了（Bucket4j + Caffeine のプロセス内カウントは全廃）**:

| 陣 | フィルタ |
|---|---|
| 第一陣 (#1470) | `ActionMemoRateLimitFilter` / `PublicApiRateLimitFilter` |
| 第二陣A (#1471) | `SyncRateLimitFilter` / `AuditLogRateLimitFilter` / `FavoriteRateLimitFilter` / `PointCardRateLimitFilter` / `QuickMemoRateLimitFilter` / `AuthWebAuthnReauthRateLimitFilter` / `VisibilityTemplateRateLimitFilter` / `MemberInfoRateLimitFilter` |
| 第二陣B (#1472) | `DashboardScopeTabRateLimitFilter` / `ErrorReportRateLimitFilter` / `BroadcastRateLimitFilter` / `AdPublicEndpointRateLimitFilter` / `RepairPlanCsvImportRateLimitFilter` / `RepairPlanSimulateRateLimitFilter` / `ScheduleDelegationRateLimitFilter` / `EventDelegationRateLimitFilter` |

注: Bucket4j 依存自体は `ResumeExportService` / 天気 API クライアント等のフィルタ外用途で
正当に使用が残るため build.gradle からは除去しない。

**移行に伴う意図的な挙動変更（互換性注記）**:

- **429 応答の統一**: 各フィルタ独自の JSON / 固定 `Retry-After` → §4.3 標準形
  （`{"error":"Too many requests"}` + 動的 `Retry-After` + `X-RateLimit-*` 3 ヘッダー）。
  `RepairPlanSimulateRateLimitFilter` の旧 `errorCode: REPAIR_PLAN_009` ボディも標準形に
  統一した（FE 参照ゼロを確認済み）。
- **`SyncRateLimitFilter` のキーを per-IP → per-user に統一**: 同期 API は認証必須であり、
  NAT/プロキシ配下の複数ユーザーが同一 IP で巻き添え制限される問題を解消。
- **`RepairPlanSimulateRateLimitFilter` の二重制限は短絡評価**: user 制限（20/分）超過時は
  scope 制限（100/分）を消費しない。1 ユーザーの連打で同一スコープの他ユーザーが
  巻き添え 429 になることを防ぐ（旧 Bucket4j 実装と同じ意味論）。
- **IP 解決は §4.4 の X-Forwarded-For 先頭値優先に統一**: XFF 先頭値はクライアント詐称可能
  という全社横断の既知トレードオフがある（§4.4 注記）。本番の Cloudflare 経由構成では
  エッジが XFF を実クライアント IP で付与するため緩和されるが、信頼ホップ数を考慮した
  右端値方式への根治は別課題として残す。

テスト: `ValkeyRateLimiterTest`（純 Mockito: キー/TTL 計算・fail-open）+
`ValkeyRateLimiterIntegrationTest`（Testcontainers 実 Redis: 実カウント・ウィンドウ境界・TTL）。
移行済みフィルタの UT は `ValkeyRateLimiter` モックで「N 回目まで allowed / N+1 回目 429」を検証する形に変更（実カウント検証は IT の責務）。

### 4.4 IP アドレスの取得

```java
// プロキシ経由の場合は X-Forwarded-For を優先
private String getClientIp(HttpServletRequest request) {
    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isBlank()) {
        return xForwardedFor.split(",")[0].trim();  // 最初のIPが元クライアント
    }
    return request.getRemoteAddr();
}
```

> 注意: `X-Forwarded-For` はスプーフィング可能なため、信頼できるリバースプロキシからのリクエストのみを前提とする。

---

## 5. ドメイン別の詳細実装ガイド

### 5.1 広告クリック詐欺対策（F09.17）

自動クリック・インプレッション水増しはプラットフォームの広告収益モデルを毀損する。

```
検知方式:
1. 同一 IP アドレスからの同一広告クリック: 60分以内に 3 回まで有効（4 回目以降カウントしない）
2. ヘッドレスブラウザ判定: User-Agent パターンマッチ（Headless Chrome 等を弾く）
3. 統計的異常検知: 同一広告の CTR（クリック率）が業界平均の 10 倍を超えた場合にフラグ
```

- 重複クリックの判定キー: `{adId}:{ip}:{60分ウィンドウ}`
- Valkey に `mannschaft:ad_click:{adId}:{hashedIp}:{window}` で管理

### 5.2 招待トークンの再利用防止（F01系）

```java
// トークン使用後の即時失効（概念）
@Transactional
public void approveInvitation(String rawToken) {
    String tokenHash = hashToken(rawToken);
    InvitationLink link = invitationRepo.findByTokenHashAndStatus(tokenHash, "PENDING")
        .orElseThrow(() -> new InvalidTokenException());  // 使用済み・期限切れも AUTH_060 で統一

    link.setStatus("USED");
    link.setUsedAt(Instant.now());
    invitationRepo.save(link);  // トークン即時無効化
    // 以降の処理...
}
```

### 5.3 シフト IDOR 変形防止（F03.5）

シフト ID を総当たりして他者のシフトを操作する IDOR 変形攻撃への対策。

```java
// NG: シフト ID のみで取得
ShiftAssignment shift = shiftRepo.findById(shiftId).orElseThrow();

// OK: シフト ID + organization_id の二重チェック
ShiftAssignment shift = shiftRepo
    .findByIdAndOrganizationIdAndDeletedAtIsNull(shiftId, currentUser.getOrganizationId())
    .orElseThrow(() -> new ResourceNotFoundException());  // 403 ではなく 404（存在を漏らさない）
```

---

## 6. テスト戦略

### 6.1 状態機械の遷移テスト

全許可/不許可の遷移パターンをユニットテストで網羅する。

```java
@ParameterizedTest
@MethodSource("invalidTransitions")
void 不正な状態遷移は422を返す(CirculationStatus from, CirculationStatus to) {
    assertThatThrownBy(() -> circulationService.transition(circulationId, to))
        .isInstanceOf(InvalidStateTransitionException.class);
}
```

### 6.2 ビジネスロジック攻撃の統合テスト

| テストケース | 確認内容 |
|---|---|
| シフト横取り（IDOR 変形） | 他組織のシフト ID を使った操作が 404 になること |
| 試合結果の不正更新 | ADMIN 以外が試合結果を変更すると 403 になること |
| 承認フロー迂回 | PENDING→APPROVED のスキップが 422 になること |
| 楽観的ロック競合 | 同一リソースを並行更新したとき、一方が 409 になること |
| 招待トークン再利用 | 使用済みトークンで再利用すると AUTH_060 になること |

### 6.3 広告クリック詐欺テスト

```
シナリオ: 同一 IP から同一広告に 60 分以内に 4 回クリック
期待: 1〜3 回目はカウントされ、4 回目以降はカウントされない（重複カウントなし）
```

---

## 7. JWT Refresh Token ローテーションの競合制御（2026-07-02 実装済みに更新）

### 7.1 問題

複数デバイス／タブが同一 Refresh Token でほぼ同時に refresh を叩くと、片方が旧トークンを
revoke した直後にもう片方が使用済みの旧トークンを再提示し得る（並行更新）。従来ロジックは
これを一律リプレイ攻撃と誤判定し `AuthSessionService.logoutAllDevices()` で全セッションを
永久無効化していた。この結果ユーザーが 401 から回復不能に陥る「自爆バグ」が実機で確認された
（F01.1）。並行更新の正規化とリプレイ攻撃検出は本質的に区別が必要であり、下記 §7.2 の旧方針
（Valkey 分散ロック）ではこれを解決できないため不採用とし、DB 行ロック + grace window 方式へ
設計変更した（§7.3〜§7.6 が現行実装）。

### 7.2 不採用: Valkey `SET NX` 分散ロック方式

検討段階では以下を設計していたが、**実装には至らず不採用**とした。

- ロックキー `mannschaft:refresh_lock:{userId}:{oldTokenHash}` を Valkey `SET NX` で獲得、TTL 5 秒、獲得失敗時は 409 Conflict
- 不採用理由:
  - **(a) fail-open と両立しない** — 本サービスの Valkey 依存箇所は全て可用性優先の fail-open 方針（[02 §4.1](02_cookie_and_session.md#41-valkey-障害時の動作方針fail-open-vs-fail-closed)）。Valkey 障害時にロックが機能しなければ、並行更新の自爆バグはロック未整備時と同じ頻度で再発する。ロック方式は「Valkey が生きている前提」でしか根治にならない
  - **(b) ロックで負けた側を救えない** — ロック獲得に失敗したリクエストに単に 409 を返すだけでは、そのクライアントは古い（既に revoke された）Refresh Token のまま再試行することになり、次の試行で結局リプレイ判定に落ちて全デバイス無効化を招く。ロックは「同時実行を防ぐ」だけで「負けた側の Refresh Token をどう正規に救済するか」という本質的な問題を解いていない

### 7.3 採用方式: DB 行ロック（PESSIMISTIC_WRITE）+ grace window

- **DB 行ロックで直列化**: `RefreshTokenRepository#findByTokenHashForUpdate`（`@Lock(LockModeType.PESSIMISTIC_WRITE)`）で取得することで、同一トークンへの並行 refresh リクエストをトランザクション内で直列化する。Valkey のような外部ミドルウェアに依存せず、DB トランザクションの ACID 特性だけで正しさを保証する（fail-open の対象外）
- **後継ポインタ（`replaced_by_token_hash`）**: `refresh_tokens` テーブルに列を追加（Flyway `V134.001__add_replaced_by_to_refresh_tokens.sql`）。正規ローテーション成功時、旧トークンは `RefreshTokenEntity#markRotated(後継hash)` で `revoked_at` と同時に後継トークンのハッシュを記録する
- **grace window（既定 60 秒）**: `mannschaft.jwt.refresh-rotation-grace-seconds`（環境変数 `MANNSCHAFT_JWT_REFRESH_ROTATION_GRACE`、`application.yml`）でプロパティ化。旧トークンが `revoked_at` からこの秒数以内に再提示された場合のみ「並行更新の負け側」として扱う

### 7.4 判定フロー（`AuthTokenRotationService#rotate` 相当）

行ロック付きで取得したトークンが失効済み（`revoked_at != null`）の場合、以下の 3 パターンに分岐する:

| ケース | 条件 | 挙動 | エラーコード |
|---|---|---|---|
| 並行更新の正規化 | 後継ポインタ有り × grace window 内 | `logoutAllDevices` は呼ばない。負け側にも新しい Access Token + Refresh Token を発行して返す（2 タブとも独立した有効トークンへ収束させる。後継の生トークンは復元不能なため新規発行が最も安全） | なし（200 で新トークンを返す） |
| 真リプレイ攻撃 | 後継ポインタ有り × grace window 超過 | `TokenReuseDetectedEvent` 発行 + `AuthSessionService.logoutAllDevices()` で全デバイス無効化（**別トランザクションで確実にコミット。§7.7 参照**） | `AUTH_026`（401） |
| 失効済み（後継無し） | 後継ポインタ無し（明示ログアウト等） | grace window の対象外。全無効化はしない | `AUTH_007`（401） |

有効期限切れのトークン再提示も、退会申請不存在の意味を持つ `AUTH_032` の誤用をやめ、
「無効/失効済み」の意味論に沿う `AUTH_007` へ是正した。

### 7.5 HTTP ステータスマッピング

`GlobalExceptionHandler` の `ERROR_CODE_STATUS_MAP` に以下を追加し、セッション失効系のレスポンスを
401（クライアントが再ログイン導線へ遷移できる）に統一した（`AuthErrorCode` の `Severity.WARN` 既定は 400 のため明示上書きが必要）。

| エラーコード | HTTP ステータス | 意味 |
|---|---|---|
| `AUTH_007` | 401 | リフレッシュトークンが無効／リボーク済み（Cookie 欠落・DB 不在・明示ログアウト済み・期限切れ）|
| `AUTH_026` | 401 | リプレイ検出・全セッション無効化 |
| `AUTH_039` | 401 | 全デバイスセッション無効化後のアクセス |

`AUTH_007` は §7.4 の表で当初から 401 と規定していたが、`ERROR_CODE_STATUS_MAP` への登録が漏れており
実際には Severity.WARN 既定の 400 が返っていた（実機のログイン画面で `POST /api/v1/auth/refresh` が 400 を
返すことを確認）。監視・アラートで認証失敗として集計できず、フロントエンドも「400 も認証失敗とみなす」
特例分岐で補償していたため、登録を追加して設計どおり 401 に是正した。
フロントエンドの 400 受理は旧 BE・旧モバイルクライアント互換のため当面残す。

### 7.7 真リプレイ検出時の全デバイス無効化は別トランザクションで確実にコミットする（REQUIRES_NEW）

`AuthTokenRotationService#refreshAccessToken` は `@Transactional`（`REQUIRED`）で動作し、真リプレイ検出時は
`AuthSessionService.logoutAllDevices()` で全トークンを revoke した**直後に** `BusinessException(AUTH_026)`
（`RuntimeException`）を送出してこのトランザクションをロールバックさせる（新トークンは発行しない）。

ここで `logoutAllDevices` が呼び出し元と**同一トランザクション**（`REQUIRED`）で動いていると、この throw による
ロールバックで全トークンの revoke（JPA のダーティ状態でありコミット時にフラッシュされる）が**巻き戻り**、
盗難トークン検出時の全デバイス無効化が実際には永続化されない。結果として「`AUTH_026`/401 は返るが、その後も
生存トークンで refresh が 200 通ってしまう」（＝過小無効化・防御の無力化）という状態に陥る（実機 E2E で確認）。

**根治**: `AuthSessionService.logoutAllDevices()`（両オーバーロード）を `@Transactional(propagation = REQUIRES_NEW)`
とし、セッションの一斉無効化を**独立したトランザクションで即コミット**する。これにより呼び出し元トランザクションの
ロールバックでは巻き戻らない。セッション kill はセキュリティ上「呼び出し元トランザクションの結末に関わらず必ず永続化
されねばならない」操作であり、REQUIRES_NEW は同メソッドの全呼び出し元（真リプレイ検出・パスワードリセット完了・
セッション画面からの一斉ログアウト）で意味論的に正しい。呼び出し元がロールバックしてもセッション kill は残る
（fail-closed で安全側）。逆に無効化処理自体が失敗すれば例外が呼び出し元へ伝播し、呼び出し元も含めてロールバックされる。

> 悲観ロックとの共存: 真リプレイ経路の呼び出し元トランザクションは再提示トークン（既に `revoked_at != null`）の行に
> `PESSIMISTIC_WRITE` ロックを保持するが、REQUIRES_NEW の新トランザクションが無効化対象として読むのは「生存中
> （`revoked_at IS NULL`）」のトークンのみで、再提示トークン行は対象外・かつ非ロック読み取り（MVCC）のためロック競合は生じない。

### 7.8 テスト戦略

`AuthTokenRotationServiceTest` は悲観ロック版ファインダ（`findByTokenHashForUpdate`）をスタブし、
grace window 内正規化・grace window 超過リプレイ・後継無し失効の 3 分岐と、有効期限切れ時の
エラーコード是正（`AUTH_032`→`AUTH_007`）を STRICT モードで検証する。

§7.7 のトランザクション巻き戻しは**純 Mockito UT では検知できない**（`logoutAllDevices` を mock で `verify` する
だけでトランザクション境界＝ロールバックを踏まないため、REQUIRES_NEW を外しても緑のまま = false-green）。そのため
実 MySQL（Testcontainers）＋実トランザクション境界を踏む結合テスト `AuthTokenReplayLogoutPersistenceIT` を追加し、
`AUTH_026` 送出**後**に実 DB を（JPA 一次キャッシュを介さず JDBC で）読んで、当該ユーザーの全 `refresh_token` の
`revoked_at` が確定的に NOT NULL であること（生存トークン 0 件）をアサートする。

---

## 8. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-06-02 | 新規作成（Security Hardening Phase 3 ビジネスロジック攻撃防止）。Mannschaft 固有の攻撃面マップ・共通対策パターン・レートリミット統一戦略（[02 §5](02_cookie_and_session.md) と数値統一）・ドメイン別実装ガイド・テスト戦略を定義 |
| 2026-06-02 | §7 JWT Refresh Token 競合制御を追加（Valkey SET NX 分散ロック・リプレイ攻撃対応） |
| 2026-06-12 | §4.3.1 追加: レートリミット共通基盤の Valkey 化 第一陣完了。`com.mannschaft.app.common.ratelimit`（`ValkeyRateLimiter` + `AbstractRateLimitFilter`）新設、Lua で INCR+EXPIRE 原子化、fail-open（`mannschaft.ratelimit.failopen` メトリクス）。18 フィルタ中 `ActionMemoRateLimitFilter` / `PublicApiRateLimitFilter` の 2 つを移行（残 16 は第二陣） |
| 2026-06-12 | §4.3.1 更新: 第二陣A (#1471)・第二陣B (#1472) マージで**全 18 フィルタの Valkey 移行完了**（Bucket4j+Caffeine プロセス内カウント全廃・ECS 複数タスクで実効上限が正確に）。§4.3 の「スライディングウィンドウ」文言を実態（固定ウィンドウ）に訂正。意図的挙動変更（429 標準形統一 / Sync per-user 化 / RepairPlanSimulate 短絡評価 / XFF 統一）を互換性注記として明文化 |
| 2026-07-02 | §7 JWT Refresh Token 競合制御を実装に同期。並行更新を一律リプレイ誤判定して全デバイス無効化する自爆バグ（F01.1）を根治。旧設計（Valkey `SET NX` 分散ロック・409 Conflict）は fail-open と両立しない／負け側を救済できないため不採用と明記し、DB `PESSIMISTIC_WRITE` 行ロック + grace window（`replaced_by_token_hash` 後継ポインタ・既定 60 秒）方式に更新。`AUTH_026`/`AUTH_039` の 401 マッピングを追記 |
| 2026-07-02 | §7.7 追加: 真リプレイ検出時の全デバイス無効化が呼び出し元トランザクションのロールバックで巻き戻り永続化されない過小無効化バグ（実機 E2E で発見）を根治。`AuthSessionService.logoutAllDevices()` を `@Transactional(REQUIRES_NEW)` 化し独立トランザクションで即コミット。純 Mockito UT では検知不能なため実 tx 境界を踏む結合テスト `AuthTokenReplayLogoutPersistenceIT` を追加（§7.8） |
