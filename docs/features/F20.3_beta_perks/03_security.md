# F20.3 — 03 セキュリティ

> **ステータス**: 🟢 設計完了（マスター御裁可済・実装待ち／営利自動切替・オーナー変更は Phase 2 保留）
> **⚠️ Phase 2 保留（マスター 2026-07-08）**: オーナー変更**自動イベント**による review_flag（B-4）は初期スコープ外（README §3・冒頭 Phase 2 保留ブロック）。初期は手動 `flag-review` で立てる。review_flag の秘匿・IDOR・売買対策の主防壁（USER スコープ限定）は初期スコープに残る。
> 認可基盤は F20.1 03 と同じ `@accessGuard` パターン（`docs/security/03_role_authority_model.md`）を用いる。横断方針は [docs/security/README.md](../../security/README.md)。

---

## 1. 認可マトリクス

| 操作 | 許可される主体 | 検証（`@PreAuthorize` レベル） |
|---|---|---|
| 自分の特典・充足状況照会（`GET /me/beta-perks`） | 本人 | 認証のみ（scopeId を受けない・本人固定） |
| チーム/組織の特典照会 | 当該スコープのメンバー以上 | `@accessGuard.isScopeMember(authentication, #teamId, 'TEAM')` / `@accessGuard.isScopeMember(authentication, #orgId, 'ORGANIZATION')` |
| 付与・取消・延長・審査・候補一覧・条件マスタ CRUD | SYSTEM_ADMIN のみ | `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`（全運用 EP） |
| 自動付与 | システム（日次バッチ）のみ | API 経由なし（`@SchedulerLock` バッチ） |
| review_flag 手動設定（`flag-review`・初期スコープ） | SYSTEM_ADMIN | 運用 API（02 §4） |
| review_flag 自動設定（オーナー変更イベント）**【Phase 2 保留】** | システム（イベントリスナー）のみ | **初期スコープ外**（B-4・README §3）。API 経由なし。Phase 2 で実装 |

- **チーム ADMIN にも付与系操作を許可しない**（特典は運営からの供与であり団体側の自己操作対象ではない。団体側は照会のみ）。
- **バッジ授与は本機能のサービス経由のみ**（F04.7 の手動授与 API `POST /badges/{id}/award` を BETA_TESTER に流用しない運用ルール。system badge の授与経路を一本化し、`awarded_by='SYSTEM'` の意味を保つ）。

---

## 2. IDOR 対策

F20.1 03 §2 の原則（`getCurrentUserId()` の scopeId 流用禁止・子リソースからのスコープ解決・`findByIdAndXxx` 物理封鎖）を全面適用したうえで、本機能固有の点:

1. **`/me/beta-perks` は scopeId をリクエストから受けない**（本人固定）。`eligibility` の評価対象も常に本人（他人の活動実績・充足状況は**いかなる形でも返さない**・AC-17。「あのユーザーはあと何日で特典か」はソーシャルエンジニアリング/売買の材料になる）。
2. チーム/組織照会はメンバー限定（横断列挙防止）。**`review_flag`/`review_reason`/`criteria_snapshot` は運用 EP 以外で返さない**（§3）。
3. シスアド EP の `{grantId}` は UUID 直指定だが SYSTEM_ADMIN 限定のため列挙リスクなし。それでも `GRANT_NOT_FOUND`(404) で存在秘匿を統一。

---

## 3. 審査情報の秘匿

- `review_flag` 立ての事実・理由は**対象団体・本人に通知しない**（審査中に売買行為者へ「検知された」ことを教えない）。利用者向けレスポンス（02 §1）から review 系フィールドを除外。
- 審査の結果**取消に至った場合のみ**、取消通知（理由は規約条項の一般文言・04 §2）を送る。
- 運営向け通知（flag 設定時）は SYSTEM_ADMIN のみ。

---

## 4. アカウント売買対策の設計位置づけ（再掲・正準）

| 層 | 実装 | 限界の明示 |
|---|---|---|
| 主防壁 | 個人特典 `scope_kind='USER'` 限定（DB CHECK `chk_bg_kind_scope`）＝買っても団体課金に充当不可 | 個人利用目的の売買は残るが経済価値が小さい |
| 規約 | 譲渡・売買・貸与禁止＋違反時取消（第 17 条＋新設特典条項・README §5） | 事後対応（発見ベース） |
| 検知 | オーナー変更イベント → review_flag（02 §5）**【自動イベントは Phase 2 保留・B-4。初期は手動 flag-review】**。`SUSPECTED_TRANSFER` は将来の検知拡張の予約値（メール変更＋パスワード変更の短期連続等・本設計では実装しない） | **検知は保険であり主防壁ではない**。誤検知前提で「フラグ＝停止ではない」（AC-08） |

- **フラグ中も権利は有効のまま**（無実の団体の業務を止めない）。停止（revoke）は人間（運営）の判断のみで発生する。

---

## 5. 監査ログ

| イベント | 記録先 | 内容 |
|---|---|---|
| 付与（自動/手動） | `audit_logs`（`BETA_GRANT_ISSUED`） | grant_kind/phase/scope・criteria_snapshot・granted_by（SYSTEM/シスアド）・skipCriteriaCheck の別 |
| 取消 | `audit_logs`（`BETA_GRANT_REVOKED`）＋`beta_grants.revoked_*` | 理由・操作者 |
| 延長 | `audit_logs`（`BETA_GRANT_EXTENDED`） | 旧/新 valid_until |
| review_flag 設定/解決 | `audit_logs`（`BETA_GRANT_REVIEW_FLAGGED`/`_RESOLVED`） | 理由・解決者（再フラグ履歴はここが真実源・01 §1 設計判断 5） |
| 例外付与（skipCriteriaCheck=true） | `audit_logs` | 明示フラグ＋note 必須（運用ガイドラインで note 空を禁止） |

---

## 6. レート制限・濫用対策

- `GET /me/beta-perks` の `eligibility` 評価は F10.8 生ログの集計を伴うため、**ユーザー単位で結果を短期キャッシュ**（Valkey・TTL 10 分・キー `betaPerk:eligibility:{userId}`）。リロード連打で集計クエリを乱発させない。
- 自動付与バッチはページング＋`@SchedulerLock`（多重起動防止）。1 万人規模（第 4 段階）で 1 走査が現実時間で終わることを P2 実装時に計測（`page_view_logs` 集計はユーザー毎ではなく**ウィンドウ内の一括 GROUP BY で先読み**する実装ノートを付す）。
  - **【2026-07-28 追記】** activeDays 集計はユーザー各自の TZ で日境界を切る（02 §3.1）が、**クエリ本数はユーザー数ではなくオフセットの種類数に比例**する（同一オフセットのユーザーを 1 群に束ねて群ごと 1 クエリ）。TZ 解決も `UserTimezoneCache#getTimezones`（TTL 5 分キャッシュ＋ミス分 1 クエリ）でページ 1 回に畳むため、N+1 非退行は維持される。
  - **【2026-07-28 追記】活動実績ゲート必須**: `min_active_days=NULL` の criteria ではユーザー走査に入らず付与 0 で終了する。在籍日数のみを条件にした自動付与（＝休眠アカウントへのバラ撒き）をコードで機械的に禁止した（README §2 主原則・02 §3）。
- シスアド EP は audit_logs 記録のみ（専用レート制限なし）。

---

## 7. GDPR・退会（イベント駆動・猶予整合）

- **イベント駆動**（実在の仕組み・01 §8）: `UserWithdrawalService`（架空）ではなく、`WithdrawalRequestedEvent`（申請・猶予開始）／`AccountPurgedEvent`（物理削除）を `BetaPerkPurgeEventListener` が購読する（02 §5.1）。
- **退会猶予との整合（M-5）**: 退会は撤回可能な猶予窓を持つのに、grant の revoke は終端で復活できない。よって:
  - **申請（猶予開始）時**: grant を revoke **しない**（撤回で復活できないため）。新規自動付与のみ抑止（退会申請中を自動付与バッチ対象から除外）。
  - **撤回時**: 何もしない（権利維持のまま自動付与対象へ復帰）。
  - **確定（purge）時**: INDIVIDUAL grant を `revoke(reason='WITHDRAWAL')`＋entitlements 失効（撤回窓は閉じており復活不可で問題ない・AC-19）。
- `beta_grants` は統計価値のため行を保持（scope_id 参照のみで PII 非含有・匿名化方針と整合）。`criteria_snapshot` は活動集計値のみで PII を含まない。
- バッジ（`user_badges`）は F04.7 の退会時方針に従う（本機能で特則を作らない）。
- 区分は CLAUDE.md PII 二段モデルの**猶予対象（強匿名化・30 日）側の purge タイミングで失効**（金銭記録はないが退会撤回窓を尊重するため即時消去側には置かない）。

---

## 8. 文言統制（法的リスク）

- **「永久」「一生」「無期限保証」の語を UI・通知・規約・設計書で禁止**。個人特典の期間表現は常に i18n キー `billing.manage.noExpiry`（「サービス提供期間中無償」）を参照する（直書き禁止と二重で統制・AC-13）。
- 検分時に `grep -rn "永久" frontend/app/locales backend/src/main/resources/messages* docs/features/F20*` および `landing.json`（`frontend/app/locales/*/landing.json`）を実施する（トレーサビリティ照合の機械的チェック項目・AC-13）。

---

## 9. ステータス確定条件 → マスター御裁可済（2026-07-08）

論点（README §9 と対応）はマスター裁可済み。実装前確定条件（B-5・計測源）のみ着手前に確定する:

| # | 論点 | **御裁可済（2026-07-08）** |
|---|---|---|
| B-1 | 規約改訂（特典専用条項の新設） | **✅ 確定**: 条文は**別 PR で起草**→マスター承認後に `landing.json` 6 言語反映。第 17 条が一般受け皿ゆえ本実装は非ブロック |
| B-2 | 2 年後更新の運用方式 | **✅ (a) 確定**: シスアド一括延長のみ・自動更新しない |
| B-3 | 取消時のバッジ剥奪 | **✅ (a) 確定**: 剥奪しない（不正取得断定時のみ手動剥奪） |
| B-4 `[P2]` | オーナー変更イベントの有無・新設 | **✅ 新設確定 → 2026-07-08 マスター決定で Phase 2 保留（初期スコープ外）**（README 冒頭 Phase 2 保留ブロック）: team ドメインに最小 publish を新設するのは Phase 2。**初期は規約第 17 条＋手動 flag-review が代替**（ブロックしない・実装時に既存確認） |
| B-5 | ベータ称号 system badge の scope | 🔧 **実装前確定条件として残置**（御裁可）: gamification grep で sentinel scope 可否確定（主フロー非依存・§F20.3 README §9.1） |
| —  | F10.8 実装（activeDays 指標・FEATURE ビーコン） | 🕒 F10.8 実装完了まで `min_active_days=NULL` 運用で自動付与本番有効化せず手動審査のみ（ブロックしない・README §2）。**2026-07-28 追記**: マスター御裁可で `INDIVIDUAL` の `min_active_days` に 14 を投入済み（V169・TEAM_ORG は引き続き NULL）だが、計測経路未実装ゆえ `mannschaft.beta.auto-grant.enabled=false` のまま変更なし |
