# README.md vs フロントエンド ギャップ修正タスク一覧

調査日: 2026-04-05
対象リポジトリ: mannschaft-frontend (Nuxt.js 3)

---

## Phase 1: README記載漏れ追記 + レベル別不一致修正（小規模・ドキュメント中心）

### Task 1-1: README.mdに未記載の5機能を追記

**概要**: フロントエンドに実装済みだがREADME.mdに記載がない5つのチーム機能をREADMEに追記する。

**対象ファイル**: `README.md`

**追記すべき機能**:

| ページ | 機能名 | 内容 |
|--------|--------|------|
| `app/pages/teams/[id]/anniversaries.vue` | 記念日管理 | チームの記念日（設立記念等）を登録・リマインド通知。年次繰り返し対応 |
| `app/pages/teams/[id]/coin-toss.vue` | コイントス・抽選ツール | コイン/サイコロ/カスタム選択肢の3モード。結果をチャットに共有可能 |
| `app/pages/teams/[id]/duties.vue` | 当番ローテーション管理 | 日次/週次/月次の当番ローテーション。メンバー割り当て・有効/無効切替 |
| `app/pages/teams/[id]/presence.vue` | 在席管理 | メンバーの外出/帰着ステータス追跡。行き先・帰着予定時刻・履歴・統計 |
| `app/pages/teams/[id]/shopping-lists.vue` | 買い物リスト | 複数リスト管理、テンプレート、チェックボックス、数量・メモ、アーカイブ |

**作業手順**:
1. README.md の「デフォルト機能（全チーム常時有効・選択リスト非表示）」テーブル（L232-263付近）に上記5機能を No.29〜33 として追加
2. 「デフォルト機能詳細」セクション（L376以降）に各機能の詳細説明を追加
3. 「デフォルト機能のレベル別適用」テーブル（L293-322付近）に5行追加（全てチームのみ ○、組織・個人は `-`）
4. 各ページファイルの中身を `Read` で確認して正確な仕様を記載すること

---

### Task 1-2: レベル別適用の不一致を修正（2件）

**概要**: READMEで組織レベル非対応（`-`）と明記されているのに、フロントエンドに組織ページが存在する2機能を修正する。

**判断が必要**: ページを削除するか、READMEを修正するか、ユーザーに確認のこと。

| モジュール | README（組織列） | 実在ページ | 選択肢 |
|-----------|:---:|------------|--------|
| 予約管理 | `-` | `app/pages/organizations/[id]/reservations.vue` | (A) ページ削除 or (B) README修正して○に |
| シフト管理 | `-` | `app/pages/organizations/[id]/shifts.vue` | (A) ページ削除 or (B) README修正して○に |

**補足**: 両機能ともREADMEの選択式モジュールレベル別適用表（L326-342付近）で「組織: -」と記載。組織レベルでの利用シナリオが妥当であればREADMEを更新、不要であればページを削除する。

---

## Phase 2: チーム大会参加ページ追加（小規模・1ページ）

### Task 2-1: `teams/[id]/tournaments.vue` を新規作成

**概要**: READMEの「大会・リーグ管理」でチーム ○（参加）と記載があるが、チーム側の参加画面が存在しない。

**参考ファイル**:
- `app/pages/organizations/[id]/tournaments.vue` — 組織（主催）側の既存ページ
- `app/composables/useApi.ts` or `app/composables/useTournamentApi.ts` — API composable

**README仕様** (L341):
> 組織: 大会作成・ディビジョン・昇降格管理 / チーム: 参加・成績閲覧

**実装すべき画面要素**:
- チームが参加可能な大会一覧表示
- 大会への参加申請
- 参加中の大会の成績・順位表閲覧
- 得点ランキング表示

**作業手順**:
1. `app/pages/organizations/[id]/tournaments.vue` を `Read` して構造を把握
2. API composable の大会関連メソッドを確認
3. チーム参加者視点の画面を `app/pages/teams/[id]/tournaments.vue` として作成
4. 既存の他チームページ（例: `teams/[id]/surveys.vue`）のレイアウト・パターンに準拠

---

## Phase 3: API composable既存の画面追加（中規模・2ページ）

### Task 3-1: 領収書ページ新規作成

**概要**: Phase 8 (F08.4) の領収書機能。API composable (`useReceiptApi.ts`) と型定義 (`app/types/receipt.ts`) は存在するがUIページがない。

**参考**:
- `app/composables/useReceiptApi.ts` — API呼び出しロジック
- `app/types/receipt.ts` — 型定義
- `docs/features/F08.4_receipt.md` — 詳細設計書

**README (L886)**:
> `/admin/receipts/**`

**作成すべきページ**:
- `app/pages/teams/[id]/receipts.vue` or 決済画面(`payments.vue`)内に領収書タブ追加
- 領収書一覧表示、PDF出力、再発行機能

**作業手順**:
1. `app/composables/useReceiptApi.ts` と `app/types/receipt.ts` を読んでAPI構造を把握
2. `docs/features/F08.4_receipt.md` を読んで設計仕様を確認
3. 既存の `teams/[id]/payments.vue` を確認し、領収書タブを統合するか独立ページにするか判断
4. 組織版も必要であれば `organizations/[id]/receipts.vue` も作成

---

### Task 3-2: プロモーション配信ページ新規作成

**概要**: Phase 9 (F09.2) のプロモーション配信機能。API composable (`usePromotionApi.ts`) と型定義 (`app/types/promotion.ts`) は存在するがUIページがない。

**参考**:
- `app/composables/usePromotionApi.ts` — API呼び出しロジック
- `app/types/promotion.ts` — 型定義
- `docs/features/F09.2_promotion_targeting.md` — 詳細設計書

**README (L856)**:
> プロモーション配信（郵便番号ターゲティング・クーポン）

**作成すべきページ**:
- `app/pages/admin/promotions.vue` or 管理画面内にセクション追加
- プロモーション一覧、作成・編集、ターゲティング設定、クーポン管理、配信状況確認

**作業手順**:
1. `app/composables/usePromotionApi.ts` と `app/types/promotion.ts` を読む
2. `docs/features/F09.2_promotion_targeting.md` を読む
3. 管理者向けのプロモーション管理画面を作成
4. system-admin/index.vue のクイックリンクにも追加

---

## Phase 4: API未整備の画面追加（大規模・3機能）

### Task 4-1: ナレッジベースページ新規作成

**概要**: Phase 6 (F06.5) のナレッジベース（Wiki）機能。ページ・API composable共に未実装。

**参考**:
- `docs/features/F06.5_knowledge_base.md` — 詳細設計書
- README (L853): `ナレッジベース | [F06.5](docs/features/F06.5_knowledge_base.md)`
- README API (L884): `/kb/**`

**作成すべきファイル**:
- `app/composables/useKnowledgeBaseApi.ts` — API composable
- `app/types/knowledgeBase.ts` — 型定義
- `app/pages/teams/[id]/kb.vue` — チーム版ナレッジベース
- `app/pages/organizations/[id]/kb.vue` — 組織版ナレッジベース（READMEのレベル別適用を確認）
- 必要に応じてコンポーネント（`app/components/kb/`）

**作業手順**:
1. `docs/features/F06.5_knowledge_base.md` を読んで全仕様を把握
2. swagger.json からナレッジベース関連エンドポイントを確認
3. API composable を作成
4. ページ・コンポーネントを作成

---

### Task 4-2: スキル・資格管理ページ新規作成

**概要**: Phase 7 (F07.5) のスキル・資格管理機能。ページ・API composable共に未実装（swagger.jsonにはEP存在）。

**参考**:
- `docs/features/F07.5_skill_certification.md` — 詳細設計書
- README (L854): `スキル・資格管理 | [F07.5](docs/features/F07.5_skill_certification.md)`
- README API (L889): `/skills/**`

**作成すべきファイル**:
- `app/composables/useSkillApi.ts` — API composable
- `app/types/skill.ts` — 型定義
- `app/pages/teams/[id]/skills.vue` — チーム版
- 必要に応じて `app/pages/organizations/[id]/skills.vue`
- 必要に応じてコンポーネント（`app/components/skill/`）

**作業手順**:
1. `docs/features/F07.5_skill_certification.md` を読む
2. swagger.json のスキル関連エンドポイントを確認
3. API composable・型定義を作成
4. ページ・コンポーネントを作成

---

### Task 4-3: インシデント管理ページ新規作成

**概要**: Phase 7 (F07.6) のインシデント管理機能。完全未実装。

**参考**:
- `docs/features/F07.6_incident_management.md` — 詳細設計書
- README (L854): `インシデント管理 | [F07.6](docs/features/F07.6_incident_management.md)`

**作成すべきファイル**:
- `app/composables/useIncidentApi.ts` — API composable
- `app/types/incident.ts` — 型定義
- `app/pages/teams/[id]/incidents.vue` — チーム版
- 必要に応じて `app/pages/organizations/[id]/incidents.vue`
- 必要に応じてコンポーネント（`app/components/incident/`）

**作業手順**:
1. `docs/features/F07.6_incident_management.md` を読む
2. swagger.json のインシデント関連エンドポイントを確認
3. API composable・型定義を作成
4. ページ・コンポーネントを作成

---

## Phase 5: 後期Phase機能の画面追加（中規模・2機能）

### Task 5-1: デジタルサイネージ管理ページ新規作成

**概要**: Phase 9 (F09.10) のデジタルサイネージ機能。ページ・composable共に不在（swagger.jsonにはEP存在）。

**参考**:
- `docs/features/F09.10_digital_signage.md` — 詳細設計書
- README (L856): `デジタルサイネージ | [F09.10](docs/features/F09.10_digital_signage.md)`

**作成すべきファイル**:
- `app/composables/useSignageApi.ts` — API composable
- `app/types/signage.ts` — 型定義
- 管理画面ページ（admin or team/org配下）
- 必要に応じてコンポーネント

**作業手順**:
1. `docs/features/F09.10_digital_signage.md` を読んで仕様確認
2. swagger.json から関連エンドポイントを確認
3. composable・型定義・ページを作成

---

### Task 5-2: Webhook/外部API管理ページ新規作成

**概要**: Phase 9 (F09.9) のWebhook/外部API管理機能。型定義 (`app/types/webhook.ts`) のみ存在。

**参考**:
- `app/types/webhook.ts` — 既存の型定義
- `docs/features/F09.9_webhook_api.md` — 詳細設計書
- README (L856): `Webhook/外部API | [F09.9](docs/features/F09.9_webhook_api.md)`
- README API (L887): `/webhooks/**`

**作成すべきファイル**:
- `app/composables/useWebhookApi.ts` — API composable
- `app/pages/teams/[id]/webhooks.vue` — チーム版Webhook管理
- `app/pages/organizations/[id]/webhooks.vue` — 組織版
- 必要に応じてコンポーネント

**作業手順**:
1. `app/types/webhook.ts` を確認
2. `docs/features/F09.9_webhook_api.md` を読む
3. swagger.json から関連エンドポイントを確認
4. composable・ページを作成

---

## Phase 6: Admin管理画面の不足機能追加（大規模・11件）

### Task 6-1: DEPUTY_ADMIN 権限グループ管理UI

**概要**: 管理者がパーミッションを個別選択した名前付き権限グループを作成・編集・複製・削除できるUI。

**README仕様** (L740-741):
> ADMIN は操作権限を個別選択した名前付きグループ（`permission_groups`）を複数作成・テンプレートとして保存できる。ユーザーへ割り当てる。

**作成すべきファイル**:
- `app/pages/admin/permission-groups.vue` — 権限グループ管理ページ
- `app/components/admin/PermissionGroupEditor.vue` — グループ編集コンポーネント

**機能要件**:
- 権限グループ一覧（名前・割り当て人数・作成日）
- 新規作成: パーミッションを個別チェックボックスで選択して名前付きグループを作成
- 編集・複製・削除
- ユーザーへの割り当て（ユーザー選択 → グループ選択）
- SYSTEM_ADMINが定めた権限上限（天井）の範囲内で設定

---

### Task 6-2: MEMBER権限調整UI

**概要**: チェックボックスでMEMBERのデフォルト操作をON/OFF。

**README仕様** (L742):
> チェックボックスで MEMBER のデフォルト操作を ON/OFF（`team_role_permissions` に保存）

**追加先**: `app/pages/admin/dashboard.vue` 内にセクション追加、または独立ページ `app/pages/admin/member-permissions.vue`

---

### Task 6-3〜6-11: 各種管理設定UI

以下は個別ページまたは既存adminページ内セクションとして追加:

| Task | 機能 | 推奨ページパス | 備考 |
|------|------|--------------|------|
| 6-3 | スケジュール管理設定 | `admin/schedule-settings.vue` | カレンダー設定・カテゴリ管理 |
| 6-4 | ブログ管理 | `admin/blog-management.vue` | 記事管理・カテゴリ・編集フロー |
| 6-5 | 掲示板カテゴリ管理 | `admin/bulletin-categories.vue` | カテゴリCRUD・権限設定 |
| 6-6 | 備品管理（admin） | `admin/equipment.vue` | 備品カタログ・在庫・貸出 |
| 6-7 | メンバー紹介管理 | `admin/member-profiles.vue` | プロフィール項目カスタマイズ |
| 6-8 | 予約管理設定 | `admin/reservation-settings.vue` | 枠設定・ルール・リマインド |
| 6-9 | Googleカレンダー設定 | `admin/google-calendar.vue` | OAuth・同期方向・マッピング |
| 6-10 | LINE設定 | `admin/line-settings.vue` | Messaging API・通知テンプレート |
| 6-11 | SNS設定 | `admin/sns-settings.vue` | Instagram/X API・フィードキャッシュ |

**共通の作業手順**:
1. README.md の該当セクションを読んで仕様を確認
2. 対応する `docs/features/F*.md` 設計書を確認
3. swagger.json から管理系エンドポイントを確認
4. 既存の admin ページ（例: `admin/modules.vue`）のレイアウト・パターンに準拠して作成
5. `system-admin/index.vue` のクイックリンクに追加が必要か確認

---

## Phase 7: System-Admin管理画面の不足機能追加（大規模・8件）

### Task 7-1: モジュール価格管理UI

**README仕様** (L752):
> 選択式モジュールごとの月額・年額価格をリアルタイムに設定変更

**作成すべきファイル**: `app/pages/admin/module-pricing.vue`

**機能要件**:
- モジュール一覧（名前・現在の月額・年額）
- 価格編集フォーム（月額・年額・トライアル日数）
- 価格変更履歴

---

### Task 7-2: パッケージ管理UI

**README仕様** (L753):
> モジュールをまとめたパッケージの作成・編集・公開/非公開・価格設定

**作成すべきファイル**: `app/pages/admin/packages.vue`

**機能要件**:
- パッケージ一覧（名前・含有モジュール・価格・公開状態）
- パッケージ作成: モジュール選択 → 名前・価格・割引率設定
- 編集・公開/非公開切替・削除

---

### Task 7-3: 割引キャンペーン・クーポン管理UI

**README仕様** (L221-222, L754):
> 期間限定割引の作成・対象指定・クーポンコード発行・利用状況確認

**作成すべきファイル**: `app/pages/admin/campaigns.vue`

**機能要件**:
- キャンペーン一覧（名前・期間・対象・割引率/額・ステータス）
- 新規作成: 開始/終了日時・割引種別（%/固定額）・対象（全体/特定モジュール/パッケージ）
- クーポンコード発行・利用上限回数設定
- 利用状況確認（利用回数・利用者）

---

### Task 7-4: ストレージプラン管理UI

**README仕様** (L755):
> ストレージプランの作成・編集（無料枠・月額/年額・超過従量単価・ハードキャップ）。各チームのストレージ使用状況の一覧確認

**作成すべきファイル**: `app/pages/admin/storage-plans.vue`

**機能要件**:
- プラン一覧（名前・無料枠・月額/年額・超過単価・上限）
- プラン作成・編集
- チーム別ストレージ使用状況ダッシュボード

---

### Task 7-5: シーズナル壁紙管理UI

**README仕様** (L756):
> 期間限定壁紙の作成・画像アップロード・公開期間設定。プレビュー確認後に公開。有効期間中は全ユーザーへ自動適用

**作成すべきファイル**: `app/pages/admin/seasonal-wallpapers.vue`

**機能要件**:
- 壁紙一覧（画像サムネイル・期間・ステータス）
- 新規作成: 画像アップロード・開始/終了日時設定
- プレビュー表示
- 公開/非公開切替

---

### Task 7-6: 組織数課金設定UI

**README仕様** (L757):
> 組織種別（非営利/営利）ごとの無料枠チーム数・超過課金単価を設定。各組織の現在のチーム数・課金状況の一覧確認

**作成すべきファイル**: `app/pages/admin/org-billing.vue`

**機能要件**:
- 組織種別ごとの設定（NONPROFIT: 無料枠・超過単価、FORPROFIT: 無料枠・超過単価）
- 各組織のチーム数・課金状況一覧テーブル
- 変更は翌月反映のアラート表示

---

### Task 7-7: 消費税設定UI

**README仕様** (L759):
> 税名称・税率・表示方式（税込/税抜）の設定

**作成すべきファイル**: `app/pages/admin/tax-settings.vue`

**機能要件**:
- 税率一覧（名称・税率%・有効/無効）
- 新規追加・編集
- `is_included_in_price` フラグ切替（税込表示/税抜表示）
- 複数税率対応

---

### Task 7-8: アフィリエイト設定UI

**README仕様** (L761):
> AmazonアソシエイトのタグIDと表示配置を管理画面から設定・切り替え

**作成すべきファイル**: `app/pages/admin/affiliate-settings.vue`

**機能要件**:
- Amazonアソシエイト タグID入力
- 表示配置選択（サイドバー右・バナーフッター等）
- プレビュー表示
- 将来の楽天アフィリエイト等の追加を見越した構造

---

## 共通注意事項

### コーディング規約
- `FRONTEND_CODING_CONVENTION.md` に必ず準拠すること
- 既存ページのレイアウトパターン（`PageHeader`, `ScopeSelector` 等の共通コンポーネント利用）に統一
- Tailwind CSS でスタイリング
- Zod + VeeValidate でフォームバリデーション
- API呼び出しは `app/composables/use*Api.ts` パターンに準拠

### 参照すべきファイル（共通）
- `README.md` — 全体仕様
- `FRONTEND_CODING_CONVENTION.md` — フロントエンド規約
- `docs/features/F*.md` — 各機能の詳細設計書
- `app/swagger.json` — API仕様（エンドポイント・リクエスト/レスポンス型）
- 既存の類似ページ — UI/UXパターンの参考

### ページ作成の共通パターン
```
1. docs/features/F*.md を読んで仕様を理解
2. swagger.json で対応するAPIエンドポイントを確認
3. 既存の類似ページを参考にレイアウト構造を決定
4. API composable がなければ作成（app/composables/use*Api.ts）
5. 型定義がなければ作成（app/types/*.ts）
6. ページ作成（app/pages/.../*.vue）
7. 必要に応じてコンポーネント分離（app/components/*/*.vue）
```
