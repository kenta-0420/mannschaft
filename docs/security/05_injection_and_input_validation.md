# 05. インジェクション・入力検証

> **ステータス**: 🟢 設計確定
> **実装フェーズ**: Security Hardening Phase 1
> **最終更新**: 2026-05-26
> **関連ドキュメント**: [README](README.md), [03](03_security_headers_and_csp.md), F01.2-04 セキュリティ運用

---

## 1. 概要

SQL インジェクション・XSS・SSRF など、入力起因の攻撃（OWASP A03）への横断方針を定義する。現状の実装は良好であり、本書は **既存対策の明文化 + 監査記録** を主目的とする。

---

## 2. XSS 対策（現状: 良好）

| 対策 | 実装 |
|---|---|
| Vue 標準エスケープ | テキスト補間（`{{ }}`）は自動エスケープ。原則 `v-text` / 補間を使う |
| `v-html` のサニタイズ | `dompurify v3.x` + `app/utils/sanitizeHtml.ts`。Markdown 描画（`marked`）の出力を必ず `sanitizeHtml()` 経由でレンダリング |
| 多層防御 | CSP（[03](03_security_headers_and_csp.md)）が万一のサニタイズ漏れを緩和 |

### `v-html` 使用箇所（全てサニタイズ済み）
`BlogPostDetail.vue` / `KbPageDetail.vue` / `BulletinThreadDetail.vue` などの Markdown 描画。各箇所は `renderMarkdown()` → `sanitizeHtml()` のパイプラインを通り、`eslint-disable vue/no-v-html` コメントで「サニタイズ済みのため安全」と明示する。

### ルール
- 新規に `v-html` を使う場合は **必ず `sanitizeHtml()` を通す**。素の HTML をそのまま渡さない
- SSR フォールバック（`sanitizeHtml.ts`）でも `script` / `on*` 属性 / `javascript:` スキームを除去する

---

## 3. SQL インジェクション対策

| 観点 | 方針 |
|---|---|
| ORM | Spring Data JPA を基本とし、メソッド名クエリ・`@Query`（JPQL）はパラメータバインディング |
| ネイティブクエリ | `@Query(nativeQuery = true)` は **必ず名前付き/位置パラメータ（`:param` / `?1`）** を使い、文字列連結で値を埋め込まない |
| 動的ソート/ページング | `Pageable` / `Sort` を使い、カラム名のホワイトリスト検証を行う（ユーザー入力をそのまま ORDER BY に渡さない） |

### 3.1 動的クエリ・`nativeQuery` 監査記録（✅ 監査済み 2026-05-26）

**真のリスク面はアノテーション `@Query` ではなく、`StringBuilder` 等で動的に組み立てるクエリ**である。`@Query(nativeQuery = true)`（92 箇所 / 34 ファイル）はアノテーション値＝コンパイル時定数のため実行時に値を連結できず、本質的に安全。よって `EntityManager.createNativeQuery` / `createQuery` で文字列を組み立てる箇所を監査した。

| 箇所 | 結果 |
|---|---|
| `analytics/SegmentCalculationService`（`createNativeQuery(sql)`） | ✅ `sql` はハードコードされたテキストブロック定数。値は `setParameter("fromDate"/"toDate")` でバインド |
| `schedule/ScheduleAnnualViewService` / `ScheduleAnnualCopyService`（`createQuery(jpql.toString())`） | ✅ 動的に追加するのは **句の構造のみ**（`AND s.teamId = :scopeId` 等）。値は全て `setParameter`。`eventType` は `EventType.valueOf()` で検証 |
| `social/FriendFeedQueryRepository` / `social/announcement/AnnouncementFeedQueryRepository` | ✅ 同上（構造の動的組み立て + 値は `setParameter`） |
| `advertising/campaign/.../OrgTypeSegmentEvaluator` / `DeviceSegmentEvaluator` / `LocaleSegmentEvaluator` | ✅ セグメント定義に応じた構造組み立て。ユーザー入力の文字列連結なし |
| `jobmatching/JobContractService`（`GET_LOCK`/`RELEASE_LOCK`） | ✅ 位置パラメータ `?1`/`?2` |

**結論**: 全箇所で「動的に組み立てるのはクエリの**構造**のみ、**値**は必ず `setParameter` でバインド」のパターンが守られており、ユーザー入力を文字列連結する SQL/JPQL インジェクション経路は存在しない。

**今後のルール**: 動的クエリを追加する際も本パターンを厳守する。`ORDER BY` にユーザー入力カラム名を渡す場合のみ別途ホワイトリスト検証が必要（§5）。

---

## 4. SSRF 対策

- ユーザー入力の URL（プロフィールリンク・SNS リンク等）は **表示用のみ**。サーバーから fetch しない方針（F01.2-04 と整合）
- URL バリデーション: `http(s)://` スキームのみ許可。`javascript:` / `data:` / `file:` 等を拒否
- やむを得ずサーバーが外部 URL を取得する場合（OGP 取得等）は、内部 IP レンジ（プライベートアドレス・メタデータエンドポイント）への到達を遮断する

---

## 5. 入力検証の横断ルール

- API 入力は DTO + `jakarta.validation`（`@NotNull` / `@Size` / `@Pattern` 等）で境界検証
- 最大長・文字種を明示し、制御文字を拒否
- **マスアサインメント防止**: リクエスト DTO は受け付けたいフィールドのみ定義し、Entity を直接バインドしない（`id` / `organization_id` / ロール等の特権フィールドを外部入力で上書きさせない）→ 詳細は §6 参照
- 動的 `ORDER BY` にユーザー入力のカラム名を渡す場合は、許可カラムのホワイトリストで検証してから使う（識別子のため `setParameter` で防げない）
- ファイルアップロードは MIME/拡張子検証 + R2 署名付き URL 経由（直リンク禁止。`AbstractTenantAwareRepository` と合わせ所有権検証）→ 詳細は [07_file_and_storage_security.md](07_file_and_storage_security.md) 参照
- **エラー応答で内部情報を漏らさない**: 本番ではスタックトレース・生 SQL・内部パスを API 応答に含めない（A05 / 情報漏洩）

---

## 6. マスアサインメント攻撃対策

### 6.1 マスアサインメントとは

リクエスト Body の JSON フィールドを直接 Entity にバインドした場合、クライアントが意図しないフィールド（`role`、`organization_id`、`is_admin`、`deleted_at` 等）を書き換えられる攻撃。

```json
// 攻撃例: 本来 displayName だけを更新するリクエストに role フィールドを注入
{
  "displayName": "攻撃者",
  "role": "SYSTEM_ADMIN",
  "organizationId": 1
}
```

### 6.2 Mannschaft での実装方針

1. **専用 DTO クラス（`*Request.java`）を必ず使用する**
   - リクエスト受け取りには専用の DTO クラス（例: `UpdateProfileRequest`）を使用する
   - DTO が受け取るフィールドを明示的に定義し、それ以外は無視する

2. **Entity クラスを `@RequestBody` に直接使用することを禁止する**
   ```java
   // NG: Entity を直接バインド
   @PatchMapping("/users/me")
   public void updateProfile(@RequestBody UserEntity user) { ... }

   // OK: 専用 DTO を使用
   @PatchMapping("/users/me")
   public void updateProfile(@RequestBody UpdateProfileRequest request) { ... }
   ```

3. **特権フィールドの明示的除外**
   - `id` / `organization_id` / `team_id` / `role` / `deleted_at` / `created_at` 等はリクエスト DTO に定義しない
   - これらの値はサーバー側で設定する（ユーザー入力を信用しない）

---

## 7. ログインジェクション対策

### 7.1 ログインジェクションとは

ユーザー入力（User-Agent、メールアドレス、テキスト等）がログに含まれる場合、`\r\n`（CRLF）を挿入することでログエントリを分割・改ざんできる攻撃。ログファイルの解析ツールを混乱させ、攻撃痕跡を隠蔽する目的で使用される。

```
攻撃例（User-Agent ヘッダー）:
Mozilla/5.0 \r\n[2026-06-02] INFO 攻撃者が挿入した偽ログエントリ
```

### 7.2 対策

1. **CRLF のエスケープ**: ログに含めるユーザー入力は `\r`（CR）と `\n`（LF）を除去またはエスケープする

   ```java
   // 既存の SystemLogPiiMasker.java に CRLF エスケープを追加することを推奨
   private String sanitizeForLog(String input) {
       if (input == null) return null;
       return input.replaceAll("[\r\n]", "_");  // CRLF を _ に置換
   }
   ```

2. **構造化ログの使用**: JSON 形式のログ（Logback の `JsonLayout` 等）を使用すると、ユーザー入力がフィールド値として自動エスケープされる

3. **ログに含めるユーザー入力の最小化**: 不要なユーザー入力をログに含めない

### 7.3 対象となるユーザー入力

- `User-Agent` ヘッダー
- メールアドレス
- ユーザー名・表示名
- コメント・投稿本文（特に管理者向けログ）
- ファイル名

---

## 8. 今後の拡張（スコープ外・意思決定済み）

- 動的 `ORDER BY` を受け付ける個別エンドポイントのホワイトリスト網羅確認は、各機能の改修時に §5 ルールへの準拠を確認する運用とする（横断 Phase では新規違反を作らないことを担保）

---

## 9. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-06-02 | §6「マスアサインメント攻撃対策」§7「ログインジェクション対策」を追加（セキュリティ精査ギャップ反映）。§5 に §6/07 への相互参照を追加 |
| 2026-05-26 | 新規作成。XSS/SQL/SSRF/入力検証の横断方針と nativeQuery 監査計画を定義 |
