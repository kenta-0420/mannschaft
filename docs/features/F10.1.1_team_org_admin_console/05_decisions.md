# F10.1.1 / 05: 論点と決定

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-17
> **関連**: [README.md](./README.md)

設計上判断を要した論点と、その決定・根拠を記録する。宙ぶらりんの「要検討」「あれば/無ければ」「将来一般化の余地」は残さない（すべて結論まで書き切る）。

---

## §1. 横スワイプに第4タブ『管理』を作るか、レンズ切替にするか

**決定**: レンズ切替（チーム/組織パネル内のトグル）。第4タブは作らない。

**根拠**:
- F22.1 の横スワイプの軸は「スコープ（個人/チーム/組織）」という単一の概念で確定している。ここに「管理」を第4タブとして足すと、軸が「スコープ ∪ 視点」という異種混合になり、循環スワイプ（個人→チーム→組織→個人）の意味論が壊れる。
- 「管理者表示」は**スコープ（どのチーム/組織か）を選んだ上での視点切替**であり、スコープと直交する。直交概念はタブ（同一軸の並び）ではなくトグル（軸に対する修飾）で表すのが UX 的に正しい。
- マスター御裁可（背景）でもレンズ切替方式が確定済み。

**却下案**: 第4タブ『管理』を追加 → 軸混合・循環破綻のため却下。

---

## §2. F10.1 を分割するか、母体に章追加するか

**決定**: F10.1 母体は維持（システム管理章を温存）し、冒頭にスコープ整理（A/B 棲み分け）を追記したうえで、チーム/組織管理コンソールの詳細は**新規ディレクトリ `F10.1.1_team_org_admin_console/`** に分割（F22.1 形式）。

**根拠**:
- F10.1 は既に大規模で、通報・モデレーション・システム管理（AB）で密度が高い。ここに L1/L2/L3＋新 API を全文追記すると可読性が崩壊する。
- F22.1 が README + 01〜04 の分割で成功している前例がある。同形式に倣う。
- F10.1 母体には「本ドキュメントのスコープ」表に A=F10.1.1 への参照を1行追記するだけで、既存のシステム管理設計（実装済み Controller 群への参照含む）を壊さない（実施済み）。

**却下案**: F10.1 に直接全章追記 → 肥大化で却下。F10.1 を全面分割 → 既存被リンク破壊リスクが大きく却下。

---

## §3. 承認待ち集約をメンバー向け action-required に相乗りさせるか、別 API にするか

**決定**: 別 API（`admin-action-required`）・別ファサード（`AdminActionRequiredFacade`）。集約ドメインは**スコープ別に動的**（team=予約/シフト/マッチング、org=支払）。

**根拠**:
- 視点が根本的に異なる（「私が回答すべき」vs「管理者が承認すべき」）。認可も MEMBER 以上 vs ADMIN/DEPUTY で異なる。
- 偵察（[03](./03_admin_action_required_api.md) §3.2）で「固定5ドメインが両スコープで取れる」前提が誤りと判明した。reservation は team 専用、team/org への入会申請ドメインは不在、payment に承認ワークフロー無し。よって**実在する集約元のみをスコープ別に集約**する設計に確定した。
- 集約元ドメインも視点も異なるため、メンバー向け `ScopeActionRequiredFacade` への相乗りは認可・集約ロジックの二重化を招き却下。

**入会申請（MEMBERSHIP_APPLICATION）を集約対象から外す決定**: team/org は招待（`InviteService`）のみで「入会申請（join request）」ドメインを持たない（村の `VillageJoinRequestService` のみ実在。村は本コンソールの対象外）。実体の無いドメインを集約しようとすると未実装を握りつぶすことになるため、集約対象から除外した（CLAUDE.md 障害対応原則3＝未実装は未実装として扱う／無いものは無いと正直に）。将来 team/org に入会申請機能が実装されたら、その時点で本集約 API に追加する（別機能の依存・本機能では起票しない）。

---

## §4. DEPUTY の予算ゲートを ORG 専用メソッドだけで賄えるか・checkAdminOrHasPermission の恒久対応

**決定**: ORG は既存 `checkAdminOrHasPermission`（ORGANIZATION 専用）を使う。TEAM は `isAdmin(userId, teamId, "TEAM")` ∨ `hasPermission(... "TEAM" ...)` の**明示判定で恒久対応する**（`checkAdminOrHasPermission` のチーム一般化は本機能では行わない）。

**根拠**:
- 偵察（R7）で `checkAdminOrHasPermission` が実コードで ORGANIZATION 専用（TEAM を渡すと `IllegalArgumentException`）と確定。
- メソッドのチーム対応一般化は `AccessControlService` という認可基盤の挙動変更であり、影響範囲が広く本機能の検分で安全性を保証しきれない。明示判定（`isAdmin ∨ hasPermission`）で同等の安全性を確保できる。
- **「将来一般化の余地」という宙ぶらりんを廃し、本機能では明示判定で恒久対応すると確定する**。基盤の一般化が必要になった場合は `AccessControlService` を所有するドメイン（common）の別タスクとして起票する（本機能では起票しない＝本機能の依存ではない。明示判定で完結するため）。

**却下案**: 本機能で `checkAdminOrHasPermission` をチーム対応に書き換える → 認可基盤の挙動変更は影響範囲が広く却下。

---

## §5. FE のロール取得失敗を「権限なし」に倒すか、エラーとして扱うか

**決定**: 区別する。`useRoleAccess.loadPermissions` の**無言 catch を廃し**、成否（`{ ok: boolean }`）を返すよう拡張する。`admin-console` ミドルウェアは「`ok:true` かつ非管理者」→ アクセス拒否（スコープトップへリダイレクト）、「`ok:false`（取得失敗）」→ エラー画面（再試行）とする。

**根拠**:
- 偵察（R8）で `loadPermissions` が例外を無言 catch し `roleName=null`（=権限なし扱い）にしていると確認。これをそのまま使うと「BE 障害でロールが取れなかった」管理者を誤って弾く（症状を隠す対処療法）。
- CLAUDE.md 障害対応原則2（症状を隠さない）に従い、取得失敗を「権限なし」に倒さず、正直にエラー表示＋再試行とする。
- `loadPermissions` の戻り値拡張は本機能のミドルウェアが安全に判断するための最小変更であり、既存呼び出し元は戻り値を無視すれば従来どおり動く（後方互換）。

---

## §6. 既存 point-cards を新作法 `admin-console` へ寄せるか、据え置くか

**決定**: **据え置く**。新規ページ（`/admin` ハブ・新 L3）のみ `admin-console` ミドルウェア＋`useRoleAccess` で統一する。point-cards 配下（F18 Phase2 実装済み）は現状の認可作法（`definePageMeta({ middleware: 'auth' })` ＋ `orgStore.myOrganizations.role` インライン判定＋amber 警告表示）のまま温存する。

**根拠**:
- 偵察（R4）で point-cards は「共通作法」を持たず、`orgStore.myOrganizations.role` を直接見るインライン実装＋amber 表示（リダイレクト・404・useRoleAccess なし）と判明。先行版の「point-cards 作法踏襲」は虚偽だったため撤回（[01](./01_console_routes.md) §2.2）。
- point-cards 全ページを新作法へ書き換えるのは F18 実装済みコードの広範改修であり、本機能（管理コンソール新設）の検分で回帰安全性を保証しきれない。**基盤改修を本機能に持ち込まない**方針（§4 と同じ判断軸）。
- 新規ページは `admin-console`（`useRoleAccess`＋slug＋取得失敗区別＋スコープトップ・リダイレクト）で統一し、認可作法の正準をこちらに置く。point-cards の作法統一は将来 F18 側の改修タスクとして扱う（本機能では起票しない＝据え置きで実害なし。BE 認可が最終防衛のため）。

**却下案**: point-cards を本機能で `admin-console` 化 → 実装済みコードの広範改修・回帰リスクで却下。

---

## §7. 既存 settings/member/トップ直下ルートを物理移設するか、リダイレクト/導線追加か

**決定**: トップ直下ルート（reservations/budget/payments/matching/shifts）と member-*（被リンク多）はリダイレクト、settings-*（被リンク少）は導線追加。物理移設はしない。budget は既存 budget.vue を正本とし二重実装を作らない。

**根拠**:
- 偵察（R3）で実在ルートを確定（全て `[slug]`・member-* はフラットファイル・budget.vue は team/org 両方実在）。先行版の「members/ サブディレクトリへ移設」「admin/budget を新規ハブ画面として新設」は実態と乖離（budget 二重定義）。
- 物理移設（ファイル階層変更）は import パス・E2E・通知ディープリンクを広範に破壊する（メモリ `project_slug_e2e_open_issues` の [id]→[slug] 移設で多層破壊が起きた前例）。
- 「散在解消」の目的は、ハブ index からのリンク集約＋旧パスからの転送で達成できる。各ページの実装を触らないため回帰リスクが最小。
- budget は既存 `budget.vue`（team/org）を正本に温存し、`/admin/budget` はそこへのリダイレクトとする（実装重複＝二重定義を解消・[01](./01_console_routes.md) §3.2）。
- リダイレクトと導線追加は段階適用（P2 で導線、P4 でリダイレクト＋E2E）し、E2E 破壊を後段に隔離する。

---

## §8. 403/404 の使い分け（FE/BE 二枚舌の解消）

**決定**: **BE API は権限不足・他テナントを 403（COMMON_002）で返す**。**FE は 404 による存在秘匿を行わず**、`admin-console` ミドルウェアで権限不足をスコープトップへリダイレクト＋エラートースト、取得失敗を再試行画面とする。

**根拠**:
- 偵察（R9）でプロジェクト慣習を確定: 管理ページは権限不足時に 404 でブラウザを弾く慣習を持たない（point-cards は amber 表示、公開リソース不在のみ `createError(404)`、システム管理は BE 403）。
- 先行版は「FE ミドルウェアが 404（存在秘匿）」「BE は 403」と二枚舌だった。FE の 404 はプロジェクト慣習に無く、列挙防止の本丸は BE の scope 絞り込み＋F00 認可であるため、**FE の 404 存在秘匿を撤回**し慣習（リダイレクト＋トースト）に統一する。
- BE が 403、FE がリダイレクト（管理ページを描画しない）で層が異なり矛盾しない。API レベルの 404 存在秘匿も不要（管理 API は元来管理者しか叩かない・[03](./03_admin_action_required_api.md) §7）。

---

## §9. 縮退（degradation）はどこまで許すか

**決定**: 集約 API の縮退は**一時障害（DB 接続断・タイムアウト）に限定**し、当該ドメインのみ `pending_count=0`・`items=[]`・`degraded=true`・WARN ログ。認可例外（COMMON_002）・プログラミングエラー（NPE 等）は**縮退させず伝播**（API 全体を 403/500 にする）。`degraded` の件数は `total_pending` に加算せず、FE は 0 件と視覚的に区別する。

**根拠**:
- 先行版は「いずれかのドメインが例外 → 当該ドメインのみ 0 件にフォールバック」と書き、認可例外まで握りつぶす設計だった。これは CLAUDE.md 障害対応原則2（症状を隠さない）違反。
- 一時障害で集約 API 全体を落とすと他ドメインの承認待ちも見えなくなり運用が止まるため、一時障害に限り当該ドメインだけ縮退する。ただし「集計失敗」を 0 件と偽らず `degraded` フラグで正直に表示する（[03](./03_admin_action_required_api.md) §4.3）。
- 認可エラー・バグは縮退で隠すと検知できず根治できないため、必ず顕在化させる。

---

## §10. 新規テーブル・マイグレーションは必要か

**決定**: 新規**テーブル**は不要。**チームスコープの予算権限については、同名 `BUDGET_VIEW`/`BUDGET_MANAGE` の TEAM 行追加が `permissions.name` 単独 UNIQUE（V2.002）により不可能なため、別名 `TEAM_BUDGET_VIEW` / `TEAM_BUDGET_MANAGE`（scope=TEAM）を新設する Flyway マイグレーション（V116.001）を追加した**（§13・PR #1659 で実装済み）。

**根拠**:
- 承認待ちは各ドメインの既存テーブルの集計で取れる（新規テーブル不要）。L1 レンズ状態は localStorage で十分。
- 偵察（R5）で `BUDGET_VIEW`/`BUDGET_MANAGE` は **scope=ORGANIZATION のみ** seed 済みと判明。チームの予算ウィジェットで DEPUTY を権限付与で解放するには TEAM スコープ専用の権限 seed が必要（[04](./04_security_authorization.md) §4.3）。
- ただし同名 `BUDGET_VIEW` を scope=TEAM で追加しようとすると `uq_permissions_name`（V2.002）の UNIQUE 違反でマイグレーションが失敗する（§13 で確定）。そのため **TEAM スコープ専用の別名 `TEAM_BUDGET_VIEW` / `TEAM_BUDGET_MANAGE` を新設する案(A)** を採用し、Flyway V116.001 として実装した（PR #1659 で main マージ済み）。
- Flyway 採番は origin/main 全体の最大 major +1 を使用（メモリ `feedback_flyway_version_sort_after_global_max` / `feedback_migration_version_collision`）。seed 形式は F02.2.1 §9 を手本にした。

> **補足（§13 参照）**: 当初「チームスコープ seed を追加する Flyway マイグレーションを伴う」と記述したが、実装軍議で同名 TEAM 行追加が不可能と判明した（§13 で詳細を記録）。最終的に §13 案(A)（別名権限新設）を採用し、PR #1659 / V116.001 にて実装完了済み。

---

## §11. 影響する既存設計書の更新範囲

**決定**: 以下を本シリーズで整合更新する（本 PR で実施済み）。

| 設計書 | 更新内容 |
|--------|---------|
| `F10.1_admin_dashboard.md` | §1 にスコープ整理表（A=F10.1.1 / B=システム管理）を追記済（既存）。関連ドキュメントに F10.1.1（実施済み） |
| `F22.1_swipe_scope_dashboard/README.md` | L1 管理者レンズ（トグル）を本文に追記済。第4タブを足さない旨明記済 |
| `F22.1_swipe_scope_dashboard/03_security_ux.md` | 管理者レンズの可視性を `ADMINS_AND_ABOVE` コード固定（min_role ではない）に整合更新 |
| `F22.1_swipe_scope_dashboard/04_widgets.md` | 管理者レンズの `ADMIN_*` ウィジェット群へのポインタを整合更新 |
| `F02.2.1_dashboard_widget_role_visibility.md` | §1/§2/§3 を「管理者ウィジェット（ADMIN_*）は min_role 管理対象外・コードで `ADMINS_AND_ABOVE` 固定・既存 ADMIN 限定ウィジェットと同扱い」に改訂（3値バリデーションは壊さない） |

---

## §13. TEAMスコープ予算権限seedは`permissions.name`単独UNIQUEにより同名TEAM行追加が不可能（P3で方針確定・P1スコープ外）

**決定**: §10・[04](./04_security_authorization.md) §4.3 で「チームスコープの `BUDGET_VIEW`/`BUDGET_MANAGE` seed を追加する Flyway を P1 で追加する」と記述していたが、**実装軍議でこれが不可能と判明した**。最終方針は**P3（L1 管理者レンズの予算ウィジェット着工時）の軍議で確定する**。P1 の承認待ち集約 API は予算権限を使わないため P1 に実害はなく、P1 スコープから除外確定とする。

**判明した制約**:

- `permissions` テーブルのスキーマ（`V2.002__create_permissions_table.sql`）の制約は `CONSTRAINT uq_permissions_name UNIQUE (name)` = **name 列の単独 UNIQUE**。
- Flyway `V11.034` で `BUDGET_VIEW` / `BUDGET_MANAGE` が `scope='ORGANIZATION'` で 1 行ずつ seed 済み。
- よって**同名 `BUDGET_VIEW` を `scope='TEAM'` の別行として追加することはスキーマ上不可能**（UNIQUE 違反）。§10 末尾・[04](./04_security_authorization.md) §4.3 が前提にした「TEAMスコープ seed 追加 Flyway」は現スキーマでは成立しない。

**P3 軍議で選ぶべき候補**:

| 案 | 内容 | 備考 |
|----|------|------|
| (A) 別名権限を新設 | `TEAM_BUDGET_VIEW` / `TEAM_BUDGET_MANAGE` 等、TEAM スコープ専用の新規 permission 名を追加。name 単独 UNIQUE を回避しつつ、TEAM でも粒度の細かい DEPUTY 権限ゲートを維持できる | Flyway 1本（新名の INSERT のみ・既存 ORGANIZATION 行に触らない）|
| (B) TEAM は `isAdmin` のみでゲート | チームの予算ウィジェットは `isAdmin(userId, teamId, "TEAM")` で守り、DEPUTY への権限付与ゲートを設けない。`checkAdminOrHasPermission` が ORGANIZATION 専用・`BUDGET_VIEW` が ORGANIZATION 専用という既存実態と整合。Flyway 不要 | DEPUTY に予算を開放できない。要件レベルで許容できるかをP3 軍議で確認 |

**P1 への影響**: P1 の承認待ち集約 API（[03](./03_admin_action_required_api.md)）は予算権限（`BUDGET_VIEW`/`BUDGET_MANAGE`）を判定に使わないため、本制約による P1 の実害はゼロ。P1 スコープから予算権限 Flyway を除外する（§12 依存タスク表の「TEAM スコープ予算権限 seed」行の P1 記述を削除）。

**却下案**: 同名 `BUDGET_VIEW` を `scope='TEAM'` で追加 → `uq_permissions_name`（V2.002）により UNIQUE 違反でマイグレーション失敗するため却下（実装軍議で確定）。

---

## §12. 本機能の依存タスク（前提で済ませない・起票先明記）

「前提とする」「未対応なら是正する」という宙ぶらりんを廃し、本機能が依存する他ドメインの作業を明示する。

| 依存タスク | 内容 | 起票先ドメイン | 本機能との関係 |
|-----------|------|--------------|--------------|
| シフト team 集約クエリ | `ShiftRequestAdminQueryService.pendingForTeam`（OPEN 変更依頼＋PENDING 交代申請の team 単位集約・読み取り専用） | shift | 本機能の P1 で新設（[03](./03_admin_action_required_api.md) §4.4） |
| マッチング募集側集約クエリ | `MatchingAdminQueryService.pendingReceivedForTeam`（募集側=受け手視点の PENDING 応募・読み取り専用） | matching | 本機能の P1 で新設 |
| 予約プレビュークエリ | `ReservationAdminQueryService.pendingForTeam`（LIMIT プレビュー・件数は既存メソッド流用） | reservation | 本機能の P1 で薄く新設 |
| 支払プレビュークエリ | `PaymentAdminQueryService.unsettledForOrg`（未完了請求のプレビュー） | payment | 本機能の P1 で薄く新設 |
| TEAM スコープ予算権限 seed | `BUDGET_VIEW`/`BUDGET_MANAGE` を scope=TEAM で seed する Flyway | budget | **P1 から除外**。`permissions.name` 単独 UNIQUE（V2.002）により同名 TEAM 行追加は不可（§13）。最終方針は P3 軍議で確定 |
| メンバー一覧の退会状態露出 | `admin/dashboard/users` 応答に `withdrawalPending` 状態を含める | F10.1 母体 / user | 在籍/退会申請中/退会済みの区別に必要（[04](./04_security_authorization.md) §6）。未露出なら本機能 P2 で当該ドメインに依頼 |

> `checkAdminOrHasPermission` のチーム一般化は依存タスク**ではない**（§4 で明示判定の恒久対応に確定済み・基盤改修を持ち込まない）。

---

## §14. L1 ウィジェット（管理者レンズ）の導線は `/admin/*` 経由でなく既存正本ルートへ直結する（P4 A-1 決定）

**決定**: ウィジェット（`DashboardAdmin*Widget.vue`）の `:to` は `/admin/*` サブページを経由せず、**ハブカードと同様に既存正本ルートへ直結する**。`/admin/reservations`・`/admin/payments`・`/admin/members` 等の L3 サブページは新設しない（物理移設しない方針の一貫適用）。

**各ウィジェットの確定導線先**（P4 A-1 実装済み、2026-06-19）:

| ウィジェット | 旧導線（デッドリンク） | 新導線（既存正本） | 備考 |
|-------------|----------------------|-------------------|------|
| `DashboardAdminReservationSummaryWidget` | `/teams/{slug}/admin/reservations` | `/teams/{slug}/reservations` | 実在確認済み |
| `DashboardAdminPaymentsWidget` | `/organizations/{slug}/admin/payments` | `/organizations/{slug}/payments` | 実在確認済み |
| `DashboardAdminMemberStatsWidget` | `/{base}/{slug}/admin/members` | `/{base}/{slug}/member-cards` | team/org 両方に実在確認済み |
| `DashboardAdminReportsWidget` | `/{base}/{slug}/admin/reports` | `/{base}/{slug}/admin`（暫定） | スコープ別通報ページ未整備につき管理コンソールハブを暫定設定。F10.1 母体モデレーション画面（スコープ別）が整備された時点で切替 |
| `DashboardAdminModulesWidget` | `/teams/{slug}/admin/settings/modules` | `/teams/{slug}/admin`（暫定） | チームモジュール設定ページ（`/admin/settings/modules`）未整備につき管理コンソールハブを暫定設定。整備後に切替 |

**ハブカードの approvals（`to: null`）については据え置き**。承認待ちドメインがチームでは予約/シフト/マッチングの複数に分散しており一意な既存正本へ向けられないため、`to: null`（近日公開プレースホルダ）のままとする。404 にはならない。`/admin/approvals` が整備された時点で点火する。

**根拠**:
- `[01_console_routes.md](./01_console_routes.md) §4`（既存ルート再編マッピング）で「物理移設しない・リダイレクト/導線追加」と既に確定している。ウィジェット導線も同じ精神に従い、未整備の `/admin/*` サブページへリンクを張ってデッドリンクを作るのではなく、現時点で実在する正本ルートへ直結する。
- デッドリンク（クリックして 404 に着地する）は許容しない（CLAUDE.md 禁止事項の対処療法禁止、根治治療の原則）。
- 設計書 `[02_admin_lens_widgets.md](./02_admin_lens_widgets.md) §2.2`・`§2.3` に記載の「導線先ルート」欄は**将来のL3整備後を想定した目標URL**であり、現時点の実装URLとは異なる。L3 各セクションが整備されたタイミングでウィジェット導線を更新し、設計書と実装を同期させること。

---

## §15. scope-tabs が孤児 membership を一覧に出す不具合の根治（P4 後追い修正）

**決定**: `DashboardScopeTabService.getScopeTabs` に、membership は存在するが team/org が論理削除済み（または FK撤廃後の孤児）のスコープを一覧から除外するフィルタを追加した。

**根本原因**:
- クロスドメインFK撤廃キャンペーン（孤児保持方針）の完了後、削除済み team/org への membership 行が `leftAt IS NULL` のまま残存するようになった。
- `getScopeTabs` は `membershipRepository.findActiveByUserAndScopeType` の結果をそのまま並べており、team/org の実在チェックを行っていなかった。
- `buildItem` 内の `teamRepository.findById` が空を返しても `name=null, slug=null` の項目を一覧に追加してしまい、FE 側で `hasResolvedSlug=false` → 永久スピナー・管理者レンズトグル不発という症状として顕在化した。
- `GET /me/teams` は `deleted_at` で除外するため正しい結果を返すが、scope-tabs は除外していない点が齟齬の原因。

**修正内容**（`DashboardScopeTabService.getScopeTabs` / PR P4後追い）:
- `activeScopeIds` 確定後に `teamRepository.findAllById(activeScopeIds)`（ORGANIZATION の場合は `organizationRepository.findAllById`）を **1回のバッチ照会**で呼び、`existingScopeIds` 集合を確定する。
- `@SQLRestriction("deleted_at IS NULL")` により、論理削除済み team/org の id は `findAllById` の結果に含まれない。
- `isEligible` に `existingScopeIds.contains(scopeId)` 条件を追加。孤児 membership は `activeScopeIds` には含まれるが `existingScopeIds` には含まれないため除外される。
- `buildItem` 内の `team==null` フォールバック（`name=null/slug=null`）は安全網として残存するが、上記フィルタで到達しなくなる。
- FE `ScopeTabBar.vue` に `item.name?.charAt(0) ?? '?'` の null ガードを多層防御として追加（型上は string だが実データ由来の null を runtime で握る保険）。

**孤児 membership 行自体は削除しない**（FK撤廃の孤児保持方針と整合）。
