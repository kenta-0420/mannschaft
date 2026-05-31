# F08.7.1 / 04: リーグ単位ファイル置き場（大会・ディビジョン単位）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-05-31
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・トレーサビリティ
> - [01_communication.md](./01_communication.md) — 連絡スペース（自動付帯フック・`TournamentContactAccessService` の `canView`/`canPost` を本書で流用）
> - [F05.5_file_sharing.md](../F05.5_file_sharing.md) — ファイル共有（**母体**。`shared_folders`/`shared_files`/`file_permissions`/R2 presigned/`StorageQuotaService`/版管理）
> - [docs/cross-cutting/storage_quota.md](../../cross-cutting/storage_quota.md) — クォータ制御（新スコープのクォータは主催組織に集約）
> - [docs/security/03_role_authority_model.md](../../security/03_role_authority_model.md) §15 — 新スコープの認可方針・PUBLIC 露出方針

本書は確定要件 ⑨（**リーグ単位のファイル置き場**＝既存 F05.5 ファイル共有を再利用し、大会・ディビジョン単位を新設）を具体化する。

---

## 1. 中核思想 — 既存 F05.5 を再利用、新規は大会・ディビジョン単位のみ

既存 **F05.5 ファイル共有**（`com.mannschaft.app.filesharing`）を全面再利用する。再実装しない。

| 既存資産 | 流用方法 |
|---------|---------|
| `shared_folders` / `shared_files` / `shared_file_versions` | スコープ値を増やすだけ（新規テーブルなし） |
| `file_permissions`（ポリモーフィック VIEW/DOWNLOAD/UPLOAD/MANAGE） | アクセス権限を表現 |
| R2 presigned URL（単発 PUT 100MB / Multipart 5TB） | そのまま使用 |
| `StorageQuotaService.checkQuota / recordUpload / recordDeletion` | クォータ計量はそのまま使用 |
| 版管理（20 件上限）・ゴミ箱・完全削除バッチ | そのまま使用 |

- **組織単位は実装済み**（`/api/v1/organizations/{id}/folders/*`）。tournament 文脈ではこの導線を出すだけ。
- **新規対象は大会・ディビジョン単位**のフォルダ／ファイル置き場。

---

## 2. `FileScopeType` への新スコープ追加（DDL 不要・VARCHAR 値追加）

`shared_folders.scope_type` を支える JPA enum は **`com.mannschaft.app.filesharing.FileScopeType`**（filesharing ドメイン、現状 `TEAM` / `ORGANIZATION` / `PERSONAL`）である。この **`FileScopeType` に `TOURNAMENT` / `TOURNAMENT_DIVISION` を追加**する。`scope_type VARCHAR(20)` ゆえ DDL 変更は不要（enum 値の追加のみ。`TOURNAMENT_DIVISION` ＝19 字で VARCHAR(20) に収まる）。

> **⚠️ enum 2 層の区別（混同厳禁）**: 本機能には名前の似た enum が 2 つ関与する。役割が異なるため取り違えないこと。
> - **フォルダスコープ enum ＝ `FileScopeType`**（filesharing ドメイン。`shared_folders.scope_type` を支える。**本章で `TOURNAMENT` / `TOURNAMENT_DIVISION` を追加する対象はこちら**）
> - **クォータ計量 enum ＝ `StorageScopeType`**（`com.mannschaft.app.common.storage.quota`。`storage_subscriptions.scope_type`＝`ORGANIZATION` / `TEAM` / `PERSONAL` に対応。**新値は追加しない**。大会ファイルのクォータは主催組織に集約するため、既存の `StorageScopeType.ORGANIZATION` ＋ `organization_id` で `StorageQuotaService` を呼ぶ。§6 参照）

### 2.1 スコープ帰属カラム（フォルダスコープ＝`FileScopeType`）

`shared_folders` には現状 `team_id` / `organization_id` / `user_id` の参照カラムがある。新スコープは**専用カラムを増やさず**、既存の汎用機構に乗せる方針とする:

| scope_type | スコープ ID の保持 | クォータ帰属（課金） |
|------------|-------------------|---------------------|
| `TOURNAMENT` | `organization_id` に**主催組織 ID** を保持し、別途 `scope_ref_id`（tournament_id）を保持 | **主催組織**（`organization_id`） |
| `TOURNAMENT_DIVISION` | `organization_id` に**主催組織 ID** を保持し、`scope_ref_id`（division_id）を保持 | **主催組織**（`organization_id`） |

- 大会・ディビジョンの実 ID（tournament_id / division_id）は、`shared_folders` に **`scope_ref_id BIGINT UNSIGNED NULL`** を 1 列追加して保持する（既存スコープでは NULL）。クロスドメイン FK は張らない（原則 1・ID 参照のみ）。
  - この 1 列追加が **F05.5 への唯一の DDL 変更**（`ALTER TABLE shared_folders ADD COLUMN scope_ref_id BIGINT UNSIGNED NULL`）。
  - index: `INDEX idx_shared_folders_tournament (organization_id, scope_type, scope_ref_id, parent_id, name)` を追加（大会/ディビジョン別フォルダ一覧）。
- `organization_id` に主催組織を入れることで、**クォータ計量 enum＝`StorageScopeType.ORGANIZATION`** ＋ `organization_id` で組織クォータ計量（`organizations.storage_used_bytes` / `storage_subscriptions(scope_type=ORGANIZATION)`）に**そのまま乗る**（領域④の要件「クォータは主催組織に集約」を満たす。`StorageScopeType` には新値を追加しない）。

### 2.2 スコープ制約（Service 層バリデーション・`FileScopeType` 値ごと）

> ここで言う `scope_type` 列の値は **フォルダスコープ enum＝`FileScopeType`** の値。クォータ計量の `StorageScopeType` とは別レイヤ（§2 冒頭の区別を参照）。

| scope_type（`FileScopeType`） | `organization_id` | `scope_ref_id` | `team_id` | `user_id` |
|------------|-------------------|----------------|-----------|-----------|
| `TOURNAMENT` | 主催組織 ID（NOT NULL） | tournament_id（NOT NULL） | NULL | NULL |
| `TOURNAMENT_DIVISION` | 主催組織 ID（NOT NULL） | division_id（NOT NULL） | NULL | NULL |

---

## 3. folder / file API のスコープ拡張

既存 team/org folder コントローラを範に複製し、tournament 文脈の導線を新設する。

| メソッド | パス | 説明 |
|---------|-----|------|
| GET | `/api/v1/tournaments/{tId}/folders` | 大会スコープのルートフォルダ一覧 |
| POST | `/api/v1/tournaments/{tId}/folders` | 大会スコープのフォルダ作成 |
| GET | `/api/v1/tournaments/{tId}/divisions/{divId}/folders` | ディビジョンスコープのルートフォルダ一覧 |
| POST | `/api/v1/tournaments/{tId}/divisions/{divId}/folders` | ディビジョンスコープのフォルダ作成 |
| GET/PUT/DELETE | `/api/v1/tournaments/{tId}/folders/{folderId}` ほか | 既存 F05.5 folder/file CRUD（VIEW/MANAGE 等）をスコープ解決経由で流用 |
| POST | `/api/v1/tournaments/{tId}/files`（presigned 取得） | 既存 F05.5 アップロードフロー（`StorageQuotaService` 経由）を流用 |

- フォルダ/ファイル個別操作（`GET/PUT/DELETE /folders/{id}`、`/files/{id}` 系）は **既存 F05.5 のエンドポイントをそのまま使い**、`scope_type`/`scope_ref_id` の帰属検証だけ tournament 用に追加する（パスは大会文脈ルートからのリダイレクトでも、folderId 直叩きでも可。実装時に F05.5 既存ルーティングと整合させる）。
- **IDOR 検証チェーン**（Service 層必須）: `tId → orgId`（主催組織帰属）→（ディビジョンなら）`divId → tId` → `folderId → (scope_type, scope_ref_id)` 帰属。

### 3.1 R2 パス prefix

F05.5 の単一バケット（`mannschaft-storage`）＋プレフィックス分割規約に合わせる:

```
files/TOURNAMENT/{tId}/...
files/TOURNAMENT_DIVISION/{divId}/...
```

`StorageQuotaService` の計量・`storage_usage_logs` は主催組織（`organization_id`）に帰属させるが、**物理パスは大会/ディビジョン単位**で分離し、運用時に大会単位の容量内訳を可視化できるようにする。

---

## 4. 自動付帯（連絡スペースと同じ provisioning フック）

連絡スペース（[01_communication.md](./01_communication.md) §3）の provisioning と**同一フック**で、大会/ディビジョン作成時にデフォルトフォルダを 1 つ払い出す。

| フック | 払い出すデフォルトフォルダ |
|--------|--------------------------|
| `TournamentService.createTournament`（save 後） | 大会スコープに「大会要項」フォルダ |
| `DivisionService.createDivision`（save 後） | ディビジョンスコープに「規約」フォルダ |
| `TournamentService.continueTournament`（シーズン継続） | 複製ディビジョンにもデフォルトフォルダを払い出し（漏れ防止・テストで検証） |

- **冪等化**: `(scope_type, scope_ref_id, parent_id=NULL, name)` の組で既存チェック → なければ作成（連絡スペース provisioning と同一トランザクション内で実施し、`DataIntegrityViolationException` catch → 再取得）。
- **原則 5（@Transactional ドメイン内）**: provisioning フックは tournament ドメインから filesharing ドメインを呼ぶ越境となるため、`// TODO: tournament → filesharing 越境。将来は TournamentCreatedEvent で分離予定` を明記する。

---

## 5. アクセス権限（連絡スペースと同規則）

連絡スペースと**同じ規則**を `file_permissions` のポリモーフィック機構で表現する。判定は [01_communication.md](./01_communication.md) の `TournamentContactAccessService` の `canView` / `canPost` を**そのまま流用**する。

| 操作 | 許可主体 | 流用元 |
|------|---------|--------|
| 閲覧（VIEW / DOWNLOAD） | (a) 公開トグル ON のスペースは PUBLIC・未ログイン含め全員（**read-only**）、(b) 参加チーム（`tournament_participants` status=REGISTERED/ACTIVE）のメンバー、(c) 主催組織 ADMIN、(d) SYSTEM_ADMIN | `TournamentContactAccessService.canView(scopeType, scopeRefId, userId)` |
| アップロード/編集（UPLOAD / MANAGE） | (a) 各チームの ADMIN/DEPUTY_ADMIN、(b) 主催組織 ADMIN、(c) SYSTEM_ADMIN のみ | `TournamentContactAccessService.canPost(scopeType, scopeRefId, userId)` |

- 公開トグルは連絡スペースと共通の `tournament_contact_space.is_public`（[01](./01_communication.md) §2）を参照する。**ファイル置き場専用の公開フラグは持たず**、同一スコープの連絡スペースの公開状態に追従する（運用と認可の一貫性を担保）。
- 公開（PUBLIC）時も**閲覧のみ**（アップロード/編集は常に代表＋主催者）。PUBLIC 公開時の露出方針は [docs/security/03_role_authority_model.md §15.2](../../security/03_role_authority_model.md) に集約。
- 存在しない/論理削除済みフォルダ・ファイルは一律 **404**（IDOR 対策）。

---

## 6. クォータ（主催組織に集約・`StorageScopeType` に新値は追加しない）

- **クォータ計量 enum＝`StorageScopeType`（`com.mannschaft.app.common.storage.quota`）には新値（TOURNAMENT 等）を追加しない。** 大会/ディビジョンのファイルクォータは主催組織に集約するため、**既存の `StorageScopeType.ORGANIZATION` ＋ `organization_id`（主催組織）で `StorageQuotaService` を呼ぶ**。フォルダスコープ enum＝`FileScopeType` を拡張するのは §2 のとおりだが、クォータ層はこの拡張に追従しない（2 層を分離）。
- `StorageQuotaService.checkQuota / recordUpload / recordDeletion` を**そのまま使用**。`FileScopeType.TOURNAMENT` / `TOURNAMENT_DIVISION` のフォルダでも、クォータ解決時は `StorageScopeType.ORGANIZATION` に丸めて `shared_folders.organization_id`（主催組織）を渡す。
- これにより、新スコープのアップロードは主催組織のサブスク（`storage_subscriptions(scope_type=ORGANIZATION)`）に紐付く（[storage_quota.md](../../cross-cutting/storage_quota.md) のスコープ帰属ルールに「大会/ディビジョン → 主催組織」を追記済み）。
- 容量超過時は既存 F05.5 / storage_quota の 409 Conflict ＋プラン更新導線をそのまま返す。

---

## 7. 削除・退会

- 大会/ディビジョン削除時、フォルダ・ファイルは **soft delete / archive で残す**（履歴保持・クロスドメイン CASCADE なし／原則 2）。連絡スペース（[01](./01_communication.md) §6）と同じ archive フックに乗せる。
- ユーザー退会時は `created_by` を匿名化に追従（F05.5 の既存挙動）。

---

## 8. 精査ログ

### 8.1 1 回目
- **不備**: スコープ追加（`TOURNAMENT`/`TOURNAMENT_DIVISION`）・folder/file API 拡張・R2 prefix・自動付帯・アクセス権限・クォータ・削除を網羅。F05.5 への DDL 変更は `scope_ref_id` 1 列のみと明示。
- **セキュリティ**: 閲覧/書込を `TournamentContactAccessService` の `canView`/`canPost` で連絡スペースと統一。公開は連絡スペースの `is_public` に追従し read-only。IDOR チェーン（tId→orgID→divId→folderId）を明記。404 統一。クロスドメイン FK なし（原則 1）。
- **ユーザビリティ**: デフォルトフォルダ自動付帯で「いきなり置ける」。導線は既存 org folder の複製で学習コストゼロ。
- **見落とし**: continueTournament の provisioning 漏れ（§4・テスト検証）、クォータ帰属の主催組織集約（§6）、storage_quota.md / F05.5 への追記同期。
- **保守性**: 既存 F05.5 を最大限流用（新規テーブルなし・DDL は 1 列のみ）。越境 TODO 明記（原則 5）。公開フラグを連絡スペースと共有して二重管理を回避。

### 8.2 2 回目（検分 2 周目・殿の独立確認）

- **根治**: フォルダスコープ enum を `StorageScopeType` と誤記していた箇所を **`FileScopeType`（`com.mannschaft.app.filesharing`）** に全面訂正。`shared_folders.scope_type` を支える実 enum は `FileScopeType`（実コードで確認済み）。
- **2 層の区別を明記**: フォルダスコープ enum＝`FileScopeType`（本章で `TOURNAMENT` / `TOURNAMENT_DIVISION` を追加）と、クォータ計量 enum＝`StorageScopeType`（`com.mannschaft.app.common.storage.quota`・`storage_subscriptions.scope_type` 対応）を §2 冒頭・§2.1・§2.2・§6 で明確に分離。
- **クォータは新値追加なし**: 大会/ディビジョンのファイルクォータは主催組織に集約するため、`StorageScopeType` には新値を追加せず、既存 `StorageScopeType.ORGANIZATION` ＋ `organization_id`（主催組織）で `StorageQuotaService` を呼ぶ方式に統一（§6）。`scope_ref_id` 列追加（`shared_folders`）は据え置き（正しい）。
- README.md / F05.5_file_sharing.md の対応箇所も同時に `FileScopeType` へ訂正。storage_quota.md はクォータ＝`StorageScopeType` 文脈で名称が正しく、新値追加なしの集約方針とも齟齬がないため据え置き。

### 8.3 未解決事項

**現時点でなし。**
