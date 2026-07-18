## 5. ビジネスロジック

### チーム作成フロー

```
1. POST /api/v1/teams を受付
2. リクエストボディをバリデーション
3. teams に INSERT
4. user_roles に (user_id=作成者, role_id=ADMIN, team_id=新チームID) を INSERT（権限ロール）
   + memberships に (user_id=作成者, scope_type='TEAM', scope_id=新チームID, role_kind='MEMBER', joined_at=NOW()) を INSERT（F00.5 Phase 2 以降）
5. audit_logs に TEAM_CREATED を記録（team_id = 新チームID）
6. 201 Created を返す
```

> - チームは常に独立した状態で作成される。組織への所属は作成後に組織側からの招待フロー（`POST /organizations/{id}/team-invites`）経由で行う
> - 1つのチームが複数の組織に同時所属することが可能

### 組織作成フロー

```
1. POST /api/v1/organizations を受付
2. parent_organization_id が指定された場合（子組織として作成）:
   a. 親組織が存在・未削除か確認 → 存在しない / 論理削除済みの場合は 404
   b. 操作者が親組織の ADMIN（または SYSTEM_ADMIN）か確認 → ADMIN 未満は 403
   c. 循環参照チェック + 深さチェック（`app.org.max-depth` を参照; 超過時は 422）
3. organizations に INSERT
4. user_roles に (user_id=作成者, role_id=ADMIN, organization_id=新組織ID) を INSERT（権限ロール）
   + memberships に (user_id=作成者, scope_type='ORGANIZATION', scope_id=新組織ID, role_kind='MEMBER', joined_at=NOW()) を INSERT（F00.5 Phase 2 以降）
5. audit_logs に ORGANIZATION_CREATED を記録（metadata: {"org_type": "NONPROFIT" or "FORPROFIT"}）
6. 201 Created を返す
```

> - `parent_organization_id` を指定しない場合（トップレベル組織）: 認証済みユーザーであれば誰でも作成可

### org_type 変更フロー

`org_type` は自己申告制であり、ADMIN が任意のタイミングで変更できる（SYSTEM_ADMIN の承認不要）。UI 側では NONPROFIT / FORPROFIT を色分け等で視覚的に区別する（フロントエンド実装の責務）。

```
1. PATCH /api/v1/organizations/{id} を受付（body に "org_type" フィールドが含まれる場合に本フローを適用）
2. 操作者が当該組織の ADMIN か確認（ADMIN 未満は 403）
3. org_type の値が NONPROFIT / FORPROFIT のいずれかであることをバリデーション（それ以外は 400）
4. 変更前の org_type と同値であれば 200 OK をそのまま返す（UPDATE 省略）
5. organizations.org_type を UPDATE（即時反映）
6. audit_logs に ORGANIZATION_ORG_TYPE_CHANGED を記録
   metadata: {"before": "FORPROFIT", "after": "NONPROFIT"}
7. 200 OK を返す
```

> 課金ロジックは `org_type` の現在値をそのまま参照する。変更後は次の課金サイクルから新しい種別が適用される（課金機能 feature doc で詳細設計）。

---

### チーム論理削除フロー

```
1. DELETE /api/v1/teams/{id} を受付
2. 操作者が当該チームの ADMIN か確認（ADMIN 未満は 403）
3. teams.deleted_at = NOW() を UPDATE（論理削除）
4. invite_tokens に UPDATE SET revoked_at = NOW()
   WHERE team_id = チームID AND revoked_at IS NULL
   （削除済みチームへの参加導線を即時遮断）
5. team_org_memberships を DELETE WHERE team_id = チームID
   （論理削除では ON DELETE CASCADE が発動しないため明示的に削除）
6. audit_logs に TEAM_DELETED を記録
7. 204 No Content を返す
```

> - `user_roles` はチーム論理削除後も保持する（削除済みチームはアプリ層でフィルタリング）
> - 子チームは存在しない（チームは階層を持たない）ため、カスケード処理は不要
> - チームに紐付く他のデータ（スケジュール・ファイル・投稿等）の扱いは各 feature doc で設計

### 組織論理削除フロー

```
1. DELETE /api/v1/organizations/{id} を受付
2. 操作者が当該組織の ADMIN か確認（ADMIN 未満は 403）
3. organizations.deleted_at = NOW() を UPDATE（論理削除）
4. invite_tokens に UPDATE SET revoked_at = NOW()
   WHERE organization_id = 組織ID AND revoked_at IS NULL
   （削除済み組織への参加導線を即時遮断）
5. team_org_memberships を DELETE WHERE organization_id = 組織ID
   （論理削除では ON DELETE CASCADE が発動しないため明示的に削除。チーム自体は存続）
6. audit_logs に ORGANIZATION_DELETED を記録
7. 204 No Content を返す
```

> - 当該組織に所属する子組織・チームは削除しない（子組織はそのまま存続。チームも独立して存続し引き続き他の組織に所属可能）
> - 子チームの `invite_tokens` は本フローでは失効させない（チームが独立して存続するため）。チームを合わせて削除する場合はチーム削除フローを別途実行する
> - `team_org_memberships` の当該組織エントリは step 5 で明示的に削除する（論理削除では ON DELETE CASCADE が発動しないため）。チームの所属記録は消えるが、チーム自体は独立して存続する
> - `user_roles`（`organization_id` スコープ）は組織論理削除後も保持する（削除済み組織はアプリ層でフィルタリング）

### チーム-組織所属招待フロー

**① 組織からチームへ招待を送信**

```
1. POST /api/v1/organizations/{orgId}/team-invites を受付（body: {"team_id": X}）
2. 組織が存在・未削除か確認 → なければ 404
3. 操作者が当該組織の ADMIN か確認 → ADMIN 未満は 403
4. 招待対象チームが存在・未削除か確認 → なければ 404
5. team_org_memberships に当該 (team_id, organization_id) のエントリが存在するか確認
   → ACTIVE 存在: 409（すでに所属済み）
   → PENDING 存在: 409（招待送信済み）
6. team_org_memberships に INSERT（status = PENDING）
7. チームの ADMIN に通知（通知機能 feature doc 参照）
8. audit_logs に TEAM_ORG_INVITE_SENT を記録
9. 201 Created を返す
```

**② チームが招待を承認**

```
1. POST /api/v1/teams/{teamId}/org-invites/{membershipId}/accept を受付
2. チームが存在・未削除か確認 → なければ 404
3. 操作者が当該チームの ADMIN か確認 → ADMIN 未満は 403
4. team_org_memberships の当該エントリが存在しかつ status = PENDING か確認 → なければ 404
5. team_org_memberships の status = ACTIVE、responded_by、responded_at を UPDATE
6. audit_logs に TEAM_ORG_MEMBERSHIP_CREATED を記録
7. 200 OK を返す
```

**③ チームが招待を拒否**

```
1. POST /api/v1/teams/{teamId}/org-invites/{membershipId}/reject を受付
2. チームが存在・未削除か確認 → なければ 404
3. 操作者が当該チームの ADMIN か確認 → ADMIN 未満は 403
4. team_org_memberships の当該エントリが存在しかつ status = PENDING か確認 → なければ 404
5. team_org_memberships を DELETE（物理削除）
6. audit_logs に TEAM_ORG_INVITE_REJECTED を記録
7. 200 OK を返す
```

**④ 組織が招待を取消**

```
1. DELETE /api/v1/organizations/{orgId}/team-invites/{teamId} を受付
2. 組織が存在・未削除か確認 → なければ 404
3. 操作者が当該組織の ADMIN か確認 → ADMIN 未満は 403
4. team_org_memberships の (team_id, organization_id) PENDING エントリを確認 → なければ 404
5. team_org_memberships を DELETE（物理削除）
6. audit_logs に TEAM_ORG_INVITE_CANCELLED を記録
7. 204 No Content を返す
```

**⑤ チームが組織から自主離脱**

```
1. DELETE /api/v1/teams/{teamId}/organizations/{orgId} を受付
2. チームが存在・未削除か確認 → なければ 404
3. 操作者が当該チームの ADMIN か確認 → ADMIN 未満は 403
4. team_org_memberships の (team_id, organization_id) ACTIVE エントリを確認 → なければ 404
5. team_org_memberships を DELETE（物理削除）
6. audit_logs に TEAM_ORG_MEMBERSHIP_REMOVED を記録（metadata: {"reason": "TEAM_LEFT"}）
7. 204 No Content を返す
```

**⑥ 組織がチームを除名**

```
1. DELETE /api/v1/organizations/{orgId}/teams/{teamId} を受付
2. 組織が存在・未削除か確認 → なければ 404
3. 操作者が当該組織の ADMIN か確認 → ADMIN 未満は 403
4. team_org_memberships の (team_id, organization_id) ACTIVE エントリを確認 → なければ 404
5. team_org_memberships を DELETE（物理削除）
6. audit_logs に TEAM_ORG_MEMBERSHIP_REMOVED を記録（metadata: {"reason": "ORG_REMOVED"}）
7. 204 No Content を返す
```

> - 再招待（拒否・取消後）は新規 INSERT で再開始する（UNIQUE KEY により (team_id, organization_id) のエントリは常に最大1件）
> - 1つのチームが複数組織に同時 ACTIVE 所属することは可能

---

### 招待トークン作成フロー

```
1. POST /api/v1/teams/{id}/invite-tokens（または /organizations/{id}/invite-tokens）を受付
2. チーム/組織が存在・未削除・未アーカイブか確認 → なければ 404 / アーカイブ済みなら 422
3. 操作者が ADMIN か確認（DEPUTY_ADMIN の場合は INVITE_MEMBERS かつ MANAGE_INVITE_TOKENS 権限が必要）
   → 権限不足は 403
4. role_id のバリデーション:
   a. 指定された role_id が roles テーブルに存在するか確認 → なければ 400
   b. 操作者が ADMIN の場合: role_id の priority >= 3（DEPUTY_ADMIN 以下）か確認
      → ADMIN（priority=2）/ SYSTEM_ADMIN（priority=1）のトークン作成は 403
   c. 操作者が SYSTEM_ADMIN の場合: role_id の priority >= 2（ADMIN 以下）まで許可
5. expires_in のバリデーション（1d / 7d / 30d / 90d / unlimited のいずれか）→ 不正値は 400
6. invite_tokens に INSERT（token = UUID v4 生成、expires_at を expires_in から計算）
7. audit_logs に TEAM_INVITE_TOKEN_CREATED または ORGANIZATION_INVITE_TOKEN_CREATED を記録
8. 201 Created を返す
```

> - ADMIN は自分と同等以上のロール（priority <= 2）のトークンを作成できない（ロール昇格制限と同一ポリシー）
> - SYSTEM_ADMIN は ADMIN 以下のトークンを作成可能（SYSTEM_ADMIN 自身のトークン作成は不可・プラットフォームスコープのため招待対象外）

### 招待参加フロー

```
1. GET /api/v1/invite/{token} でプレビュー（オプション）
2. POST /api/v1/invite/{token}/join を受付
3. invite_tokens を SELECT ... FOR UPDATE で取得（排他ロック）
4. 有効性チェック: revoked_at IS NULL かつ
   (expires_at IS NULL OR expires_at > NOW()) かつ
   (max_uses IS NULL OR used_count < max_uses)
   → いずれか失敗で 400
5. 招待先チーム/組織のアーカイブチェック:
   招待先エンティティの archived_at IS NOT NULL → 422（アーカイブ済みのため参加不可）
6. ブロック済みチェック:
   - チーム招待（invite_tokens.team_id IS NOT NULL）の場合:
     team_blocks に (team_id = invite_tokens.team_id, user_id = current_user_id) が存在すれば 403
   - 組織招待（invite_tokens.organization_id IS NOT NULL）の場合:
     organization_blocks に (organization_id = invite_tokens.organization_id, user_id = current_user_id) が存在すれば 403
7. memberships に当該スコープのアクティブエントリが既存か確認（left_at IS NULL） → 存在すれば 409
8. user_roles に INSERT（role_id = invite_tokens.role_id）（権限ロールが ADMIN/DEPUTY_ADMIN の場合）
   + memberships に INSERT（scope_type/scope_id/role_kind/joined_at。role_kind は MEMBER または SUPPORTER）（F00.5 Phase 2 以降）
9. invite_tokens.used_count を +1 UPDATE
10. audit_logs に TEAM_MEMBER_JOINED または ORGANIZATION_MEMBER_JOINED を記録
    metadata: {"join_method": "INVITE", "invite_token_id": トークンID}
11. 200 OK を返す
```

> - `invite_token_id` をメタデータに含めることで、どの招待トークン経由で参加したかを事後追跡可能にする（トークン発行者の特定・悪用調査に有用）

### オーナー委譲 承諾フロー（2ステップ・承諾型 / 2026-07-18 マスター御裁可）

**方針転換の背景**: 従来のオーナー委譲は「即時型」（操作者が押した瞬間に対象ユーザーを ADMIN 昇格＋自分を降格し、事後通知のみ）だった。しかし **指名相手の承諾がないまま管理責任を押し付けられる** 問題があり、[`account_purge_last_admin_succession.md` §10.11](../../architecture/account_purge_last_admin_succession.md) で未解決事項として残されていた。**2026-07-18 のマスター御裁可により、オーナー委譲も承諾型（オファー→承諾）に統一する。** これは F04.12（チャットからの承諾型招待）・team-invites/org-invites の PENDING→ACTIVE と同一の「承諾型オファー」思想である。

#### 状態機械

```
        打診(POST transfer-ownership-offers)
              │
              ▼
          [PENDING]
          /   │    \   \
   accept  decline expire cancel(発行者取消)
      │       │      │      │
      ▼       ▼      ▼      ▼
 [ACCEPTED][DECLINED][EXPIRED][CANCELLED]
 = 委譲実行   = いずれも現状維持（ロール不変）
```

**指名相手だけが承諾できる（宛先照合 = IDOR 防止）。** 承諾があって初めて対象ユーザーを ADMIN 昇格＋発行者を降格する。辞退・期限切れ・取消のいずれでもロールは一切変わらない。

#### ステップ1: オファー作成

```
1. POST /api/v1/teams/{slug}/transfer-ownership-offers を受付（body: {"targetUserId": X}）
2. 操作者が対象チームの ADMIN か確認 → ADMIN 未満は 403
3. チームがアーカイブ済みでないか確認 → アーカイブ済みは 422
4. 対象ユーザーが当該チームのメンバーか、かつ操作者 ≠ 対象 か確認 → 否なら 404 / 422
5. 【2FA必須チェック】対象ユーザーが 2FA を設定済みか確認 → 未設定は 422
   （承諾時に再チェックもするが、無駄なオファー作成を防ぐため作成時にも確認）
6. 同一スコープに PENDING オファーが既存なら 409（重複打診防止・古いものを取消してから）
7. ownership_transfer_offers に INSERT（status=PENDING, target_user_id=X, expires_at=発行から7日）
8. audit_logs に TEAM_OWNERSHIP_TRANSFER_OFFERED を記録
   metadata: {"from_user_id": 操作者ID, "to_user_id": 対象ユーザーID}
9. 対象ユーザーへ到達通知（F04.3/F04.9）「管理者への就任を打診されています」
10. 201 Created を返す（オファー ID・PENDING）
```

#### ステップ2: 承諾（委譲実行）／辞退／取消

```
承諾: POST /api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/accept
1. オファーを取得。status=PENDING かつ未期限か確認 → 否なら 409/410（EXPIRED/CANCELLED/既処理）
2. 【宛先照合 = IDOR 防止】offer.target_user_id == 実行ユーザー ID か確認 → 不一致は 403
3. 【2FA再チェック】実行ユーザーが 2FA 設定済みか確認 → 未設定は 422（FE は 2FA 設定画面へ誘導・U2）
4. チームが依然アーカイブ済みでないか・発行者が依然 ADMIN か再確認 → 否なら 409
5. 【薄いラッパで既存 RoleService#transferOwnership を呼ぶ（H-3・下記「実装ノート」参照）】
   a. transferOwnership(scopeId, scopeType, currentUserId=発行者ID, targetUserId=実行ユーザーID) を呼ぶ
      → 対象ユーザーを ADMIN 昇格・発行者を MEMBER 降格・MembershipChangedEvent(CHANGED)×2（既存挙動）
   b. offer.status を ACCEPTED に UPDATE、accepted_at=NOW()
6. audit_logs に TEAM_ADMIN_TRANSFERRED を記録（metadata に offer_id を含める）
7. 200 OK を返す

辞退: POST .../transfer-ownership-offers/{offerId}/decline
- 宛先照合（target_user_id == 実行ユーザー）→ status を DECLINED に。ロール不変。発行者へ通知。

取消: DELETE .../transfer-ownership-offers/{offerId}
- 発行者（または対象スコープ ADMIN）のみ。status を CANCELLED に。ロール不変。
```

#### 実装ノート: accept は既存 `transferOwnership` の「薄いラッパ」であり無改修流用ではない（H-3）

現行 `RoleService#transferOwnership(scopeId, scopeType, currentUserId, targetUserId)` は退会・即時委譲を前提に作られており、accept から流用する際は**以下の差分を薄いラッパ層（承諾 Service）で埋める**こと。「既存流用」の一語で無改修と誤読しないための明示:

1. **引数の組み替え**: `currentUserId` には **発行者（オファーの `issued_by`）** を渡す（実行ユーザー=承諾者ではない）。`targetUserId` には実行ユーザー（承諾者）を渡す。現行実装は `currentUserId` が ADMIN であることを前提に降格対象とするため、発行者を渡さないと降格対象を誤る。
2. **2FA チェックの新規追加**: 現行 `transferOwnership` は **2FA を一切チェックしない**。ADMIN 昇格には 2FA 必須（§6 セキュリティ）のため、**ラッパ層で承諾者の 2FA 設定を検証**してから呼ぶ（未設定は 422）。
3. **エラーの再マッピング**: 現行 `transferOwnership` は違反（自己譲渡・ADMIN でない・対象未所属・ロール未検出）を**すべて `ROLE_001` で投げる**。承諾 API では文脈に応じて **403（宛先/権限）/ 404（スコープ・メンバー不在）/ 409（状態不整合）/ 422（2FA・アーカイブ）** に再マッピングして返す（`ROLE_001` を素通しにしない）。
4. **降格先は MEMBER**（現行実装どおり。下記「降格先ロール」参照）。

> - 組織の場合（`.../organizations/{slug}/transfer-ownership-offers`）も同一フロー（`team_id` → `organization_id` に読み替え、イベントは `ORGANIZATION_OWNERSHIP_TRANSFER_OFFERED` / `ORGANIZATION_ADMIN_TRANSFERRED`）
> - 複数 ADMIN が存在する場合でも委譲は可能（承諾時に発行者のみ降格し、他の ADMIN はそのまま）
> - 承諾（accept）は `checkLastAdmin` を呼ばない（委譲完了後は新 ADMIN が存在するため正当）
>
> **⚠️ 降格先ロールは MEMBER（実装が正・旧 doc 記述が誤り / 実装時に統一）**: 実装 `RoleService#transferOwnership` は発行者を **MEMBER** に降格している（コード確認済み・javadoc「現オーナーは MEMBER にダウングレード」）。旧 F01.2 記述の「DEPUTY_ADMIN 降格」は実装と乖離した誤記であり、マスター御裁可（2026-07-18「発行者 MEMBER 降格」）とも一致する **MEMBER に統一**する。02_api_design のレスポンス例（`previous_admin.role`）も MEMBER に修正済み。
>
> **⚠️ FE-BE 不一致は「方式ごとの乖離」で既存バグ（M-4・実装時に刷新）**: 旧 BE `POST /{scope}/{slug}/transfer-ownership` は **`@RequestParam Long targetUserId`（クエリパラメータ）** でボディを読まない（`TeamController.transferOwnership` 実装確認済み）。一方 FE composable [`useTeamCrud.ts`](../../../frontend/app/composables/team/useTeamCrud.ts) は `transferOwnership(slug, newAdminUserId)` で **body `{ newAdminUserId }` のみ**送っており、**クエリ未付与のため現行は 400 になる既存バグ**。承諾型化に伴い FE を 2 ステップ API（オファー作成→承諾/辞退）へ**方式ごと刷新**し、新 API は **JSON body `{ targetUserId }`** に統一する（クエリパラメータ方式は廃止）。

#### 退会 purge 経由の承継は「承諾スキップの強制委譲」（H-2・GDPR 30日タイムリミット順守）

通常のオーナー委譲は上記の承諾型2段だが、**退会（アカウント purge）に伴う最後の ADMIN 承継だけは承諾を待てない**。承諾待ちで退会が詰まる／承継先が 2FA 未設定で承諾不能なら退会不能となり、GDPR Art.17 の 30 日タイムリミットに抵触する（[`account_purge_last_admin_succession.md` §10.11](../../architecture/account_purge_last_admin_succession.md) で決着）。よって:

| 経路 | 委譲方式 | 承諾 | 2FA | 監査 |
|---|---|---|---|---|
| 通常のオーナー委譲（本節）| 承諾型2段（オファー→accept）| **必要**（指名相手が accept）| accept 時に必須チェック | `*_ADMIN_TRANSFERRED`（metadata に offer_id）|
| 退会 purge 経由の最後の ADMIN 承継 | **システム強制の即時委譲**（承諾スキップ）| **不要**（本人不在で完結）| **チェックしない**（退会完遂を優先）| `*_ADMIN_TRANSFERRED`（metadata に `forced=true` / 承継元退会 user_id）|

- purge 経路は既存 `AccountPurgeService` / `RolePurgeEventListener` から `transferOwnership`（または承継バッチ）を**同期即時**で呼ぶ現行設計を維持し、承諾型オファーを介さない。強制委譲であることを **audit に FORCED（`forced=true`）で明示**する。
- 通常委譲との使い分けを実装・レビューで取り違えないため、**承諾型 accept と強制委譲は別メソッド**（例: 承諾 Service の `acceptOffer` と purge 経路の `forceTransferForPurge`）として分離する。

#### i18n 新規キー（承諾/辞退 UI・打診通知 / U1・U2・6言語 ja 初版同値）

オーナー委譲の承諾型化で必要な UI 文言は直書き禁止。以下を 6 言語（ja/en/zh/ko/es/de）に追加（未翻訳は ja 同値で投入）:

| キー | ja 値（初版）| 用途 |
|---|---|---|
| `role.transfer.offer.button` | 管理者を引き継ぐ | 打診ボタン（ADMIN 側）|
| `role.transfer.offer.notification` | {scope} の管理者への就任を打診されています | 打診到達通知（宛先）|
| `role.transfer.offer.pending` | 管理者就任の打診が届いています | オファーカード見出し |
| `role.transfer.offer.accept` | 引き受ける | 承諾ボタン |
| `role.transfer.offer.decline` | 辞退する | 辞退ボタン |
| `role.transfer.offer.accepted` | 管理者を引き継ぎました | 承諾完了 |
| `role.transfer.offer.declined` | 打診を辞退しました | 辞退完了 |
| `role.transfer.offer.expired` | 打診は期限切れです | EXPIRED |
| `role.transfer.offer.cancelled` | 打診は取り消されました | CANCELLED |
| `role.transfer.error.notTarget` | この打診はあなた宛てではありません | 宛先不一致 403 |
| `role.transfer.error.need2fa` | 管理者になるには2段階認証の設定が必要です | 2FA 未設定 422（U2）|
| `role.transfer.error.need2fa.cta` | 2段階認証を設定する | 2FA 設定画面への導線（U2）|

> **U2（2FA 未設定で accept 422 の導線）**: 承諾者が 2FA 未設定で accept が 422 になった場合、エラー表示に留めず **2FA 設定画面（`/settings/security` 等）への CTA** を提示し、設定後に再度承諾できる導線を UX 要件とする。上記 `role.transfer.error.need2fa` / `.cta` を用いる。

---

### ロール変更フロー

```
1. PATCH /api/v1/teams/{id}/members/{userId}/role を受付
2. 操作者が対象チームの ADMIN か確認
3. 変更先ロールが priority >= 3（DEPUTY_ADMIN 以下）か確認
   → ADMIN への昇格（priority <= 2）は SYSTEM_ADMIN のみ
4. 【2FA必須チェック】変更先ロールが ADMIN の場合:
   two_factor_auth に対象ユーザーの有効な TOTP レコードが存在するか確認
   → 存在しない（2FA 未設定）であれば 422（「ADMIN ロールには2FA設定が必要です」）
5. 【最後のADMIN保護】変更先ロールが ADMIN でない場合:
   user_roles WHERE team_id = X AND role_id = ADMIN の件数を確認
   → 1件（対象ユーザーのみ）であれば 422（「最後の管理者は降格できません」）
6. 変更先ロールが現在のロールと同一の場合: DB 更新・audit_logs 記録をスキップし 200 OK を返す（冪等処理）
7. user_roles を UPDATE（role_id を変更）
8. 権限グループのクリーンアップ:
   - 変更前ロールが DEPUTY_ADMIN かつ変更後ロールが MEMBER の場合:
     `user_permission_groups` のうち `target_role = 'DEPUTY_ADMIN'` のグループ割り当てを削除（MEMBER 用グループは存在しないためクリア後は割り当てなし）
   - 変更前ロールが MEMBER かつ変更後ロールが DEPUTY_ADMIN の場合:
     `user_permission_groups` のうち `target_role = 'MEMBER'` のグループ割り当てを削除（DEPUTY_ADMIN 用グループへの再割り当ては ADMIN が別途実施）
   - 変更後ロールが DEPUTY_ADMIN でも MEMBER でもない場合（SUPPORTER / GUEST 等）:
     `user_permission_groups` を全削除
9. audit_logs に TEAM_MEMBER_ROLE_CHANGED を記録（target_user_id = 対象ユーザー）
10. 200 OK を返す
```

### メンバー除名フロー

```
1. DELETE /api/v1/teams/{id}/members/{userId} を受付
2. 操作者が対象チームの ADMIN か確認
3. 【最後のADMIN保護】対象ユーザーのロールが ADMIN の場合:
   user_roles WHERE team_id = X AND role_id = ADMIN の件数を確認
   → 1件（対象ユーザーのみ）であれば 422（「最後の管理者は除名できません」）
4. 対象ユーザーの user_roles（team_id スコープ）を DELETE
5. user_permission_groups（当該チームのグループ）を DELETE
6. audit_logs に TEAM_MEMBER_REMOVED を記録（target_user_id = 対象ユーザー）
7. 204 No Content を返す
```

### 権限解決ロジック

リクエストごとに以下の手順で実効パーミッションを決定する:

```
1. JWT から user_id を取得
2. JWT の is_system_admin = true → SYSTEM_ADMIN として全権限付与（DB 参照不要）
3. 対象 team_id / organization_id に対応する user_roles を SELECT
4. ロールが ADMIN →
     role_permissions WHERE role_id = ADMIN から全パーミッションを取得
5. ロールが DEPUTY_ADMIN →
     user_permission_groups（当該ユーザー・スコープ）
       → permission_group_permissions
       → permissions
     を結合して実効パーミッションセットを取得
     ※ role_permissions は参照しない（権限グループ未割り当ての場合は実効パーミッション 0）
     ※ スコープ: team_id スコープなら permission_groups.team_id = チームID で絞り込み
               organization_id スコープなら permission_groups.organization_id = 組織ID で絞り込み
6. ロールが MEMBER →
   a. user_permission_groups（当該ユーザー・スコープ）に割り当てグループが存在するか確認
   b. 割り当てグループなし →
        role_permissions WHERE role_id = MEMBER AND is_default = TRUE（基本3件）を実効パーミッションとして取得
   c. 割り当てグループあり（1件以上）→
        is_default を完全に無視し、user_permission_groups
          → permission_groups WHERE target_role = 'MEMBER'（AND team_id/organization_id でスコープ絞り込み）
          → permission_group_permissions → permissions
        の UNION のみを実効パーミッションとする（グループに基本3件が含まれていなければそれらも失われる）
        ※ ADMIN がお知らせ権限だけ追加したい場合は MANAGE_SCHEDULES/MANAGE_FILES/MANAGE_POSTS も含むグループを作成する必要がある
7. ロールが SUPPORTER / GUEST → パーミッションなし（ロールチェックのみで制御）
8. パーミッションセットを元に操作可否を判定
```

### 権限解決キャッシュ戦略

権限解決ロジックは全 API リクエストで実行されるため、Valkey キャッシュによるパフォーマンス最適化を行う。

**キャッシュ設計**

| 項目 | 値 |
|------|-----|
| キーフォーマット | `perm:{user_id}:{scope_key}` （例: `perm:42:team:1`）|
| 値 | パーミッション名の Set（JSON 配列）|
| TTL | 5分（`app.permission-cache.ttl`）|
| ストア | Valkey（Spring Cache + `@Cacheable`）|

**キャッシュ無効化タイミング**

以下の操作時に該当ユーザー・スコープのキャッシュを `@CacheEvict` で明示的に削除する:

| 操作 | 無効化対象 |
|------|-----------|
| ロール変更（`PATCH /members/{userId}/role`）| 対象ユーザーの該当スコープ |
| 権限グループ割り当て変更（`PUT /members/{userId}/permission-groups`）| 対象ユーザーの該当スコープ |
| 権限グループ内容更新（`PATCH /permission-groups/{groupId}`）| そのグループに割り当てられた全ユーザーの該当スコープ |
| 権限グループ削除（`DELETE /permission-groups/{groupId}`）| 同上 |
| メンバー除名 / 退会 | 対象ユーザーの該当スコープ |
| ADMIN 権限移譲 | 移譲元・移譲先ユーザーの該当スコープ |

> - キャッシュミス時は DB から権限解決ロジックを実行し結果をキャッシュに書き込む
> - SYSTEM_ADMIN はキャッシュ不要（JWT 判定で全権限付与のためスキップ）
> - グループ内容更新時のバルク無効化: `user_permission_groups WHERE group_id = X` で対象ユーザーリストを取得し、各キーを個別に削除する

---

### 論理削除復元フロー

```
1. PATCH /api/v1/teams/{id}/restore（または /organizations/{id}/restore）を受付
2. 操作者が SYSTEM_ADMIN か確認 → SYSTEM_ADMIN 以外は 403
3. 対象エンティティが存在するか確認 → なければ 404
4. deleted_at IS NULL → 論理削除されていない → 422
5. deleted_at = NULL で UPDATE（復元）
6. audit_logs に TEAM_RESTORED（または ORGANIZATION_RESTORED）を記録
   metadata: {"restored_by": SYSTEM_ADMIN の user_id, "originally_deleted_at": 元の deleted_at}
7. 204 No Content を返す
```

> - 復元後、招待トークンは自動復元しない（論理削除時に失効済み）。ADMIN が必要に応じて新規トークンを発行すること
> - 復元後、`user_roles` は論理削除後も保持されているためそのまま利用可能
> - `team_org_memberships` は論理削除時に明示的に DELETE されているため復元されない。必要に応じて再招待が必要
> - 誤削除からの復旧を想定した運用機能。通常の ADMIN には公開しない（SYSTEM_ADMIN 専用）

---

### サポーター登録フロー（フォロー）

```
1. POST /api/v1/teams/{id}/follow を受付
2. チームが存在・未削除・未アーカイブか確認
3. teams.visibility = 'PUBLIC' か確認（ORGANIZATION_ONLY / PRIVATE は 403）
4. teams.supporter_enabled = TRUE か確認 → FALSE で 403
5. team_blocks に user_id が存在するか確認 → 存在すれば 403（ブロック済み）
6. memberships に当該チームのアクティブエントリがあるか確認（left_at IS NULL） → 存在すれば 409（既に何らかのロールで所属済み）
7. memberships に (user_id=リクエスト者, scope_type='TEAM', scope_id=チームID, role_kind='SUPPORTER', joined_at=NOW()) を INSERT（F00.5 Phase 2 以降）
8. audit_logs に TEAM_MEMBER_JOINED を記録（metadata: {"join_method": "FOLLOW"}）
9. 200 OK を返す
```

### フォロー解除フロー

```
1. DELETE /api/v1/teams/{id}/follow を受付
2. memberships に SUPPORTER ロールのアクティブエントリが存在するか確認（scope_type='TEAM' AND scope_id=X AND role_kind='SUPPORTER' AND left_at IS NULL） → なければ 404
3. memberships を UPDATE SET left_at=NOW()（F00.5 Phase 2 以降）
4. audit_logs に TEAM_MEMBER_REMOVED を記録（metadata: {"reason": "UNFOLLOW"}）
5. 204 No Content を返す
```

### ブロックフロー

```
1. POST /api/v1/teams/{id}/blocks を受付（body: {"user_id": 42, "reason": "迷惑行為のため"}）
2. 操作者が ADMIN、または REMOVE_MEMBERS 権限を持つ DEPUTY_ADMIN か確認
3. 対象ユーザーが自分自身でないか確認
4. 対象ユーザーが ADMIN / SYSTEM_ADMIN でないか確認（上位ロールはブロック不可）
5. 対象ユーザーが当該チームのアクティブメンバー（memberships WHERE scope_type='TEAM' AND scope_id=X AND user_id=対象 AND left_at IS NULL）であれば memberships を UPDATE SET left_at=NOW()（自動除名）
6. user_permission_groups（当該チームのグループに紐付く対象ユーザーの割り当て）を DELETE
7. team_blocks に INSERT（reason は任意。既にブロック済みの場合は 409）
8. audit_logs に TEAM_MEMBER_BLOCKED を記録（target_user_id = 対象ユーザー、metadata に reason を含む）
9. 204 No Content を返す
```

### ブロック解除フロー（チーム）

```
1. DELETE /api/v1/teams/{id}/blocks/{userId} を受付
2. 操作者が ADMIN、または REMOVE_MEMBERS 権限を持つ DEPUTY_ADMIN か確認
3. team_blocks に当該ユーザーのレコードが存在するか確認 → なければ 404
4. team_blocks から DELETE
5. audit_logs に TEAM_MEMBER_UNBLOCKED を記録（target_user_id = 対象ユーザー）
6. 204 No Content を返す
```

### 組織ロール変更・除名フローの最後のADMIN保護

組織メンバーのロール変更（`PATCH /organizations/{id}/members/{userId}/role`）および除名（`DELETE /organizations/{id}/members/{userId}`）は、チームフローと同一の手順を適用する。**2FA必須チェック・最後のADMIN保護チェックも同様に必須**:

- ロール変更: ADMIN 昇格時は対象ユーザーの 2FA 有効状態を確認（未設定なら 422）。`user_roles WHERE organization_id = X AND role_id = ADMIN` が1件のみの場合、その ADMIN の降格を 422 で拒否
- 除名: 対象ユーザーが唯一の ADMIN の場合、除名を 422 で拒否

---

### 組織サポーター登録フロー（フォロー）

```
1. POST /api/v1/organizations/{id}/follow を受付
2. 組織が存在・未削除・未アーカイブか確認
3. organizations.visibility = 'PUBLIC' か確認（PRIVATE は 403）
4. organizations.supporter_enabled = TRUE か確認 → FALSE で 403
5. organization_blocks に user_id が存在するか確認 → 存在すれば 403（ブロック済み）
6. memberships に当該組織のアクティブエントリがあるか確認（left_at IS NULL） → 存在すれば 409
7. memberships に (user_id=リクエスト者, scope_type='ORGANIZATION', scope_id=組織ID, role_kind='SUPPORTER', joined_at=NOW()) を INSERT（F00.5 Phase 2 以降）
8. audit_logs に ORGANIZATION_MEMBER_JOINED を記録（metadata: {"join_method": "FOLLOW"}）
9. 200 OK を返す
```

### 組織フォロー解除フロー

```
1. DELETE /api/v1/organizations/{id}/follow を受付
2. memberships に当該組織の SUPPORTER アクティブエントリが存在するか確認（scope_type='ORGANIZATION' AND role_kind='SUPPORTER' AND left_at IS NULL） → なければ 404
3. memberships を UPDATE SET left_at=NOW()（F00.5 Phase 2 以降）
4. audit_logs に ORGANIZATION_MEMBER_REMOVED を記録（metadata: {"reason": "UNFOLLOW"}）
5. 204 No Content を返す
```

### 組織ブロックフロー

```
1. POST /api/v1/organizations/{id}/blocks を受付（body: {"user_id": 42, "reason": "迷惑行為のため"}）
2. 操作者が ADMIN、または REMOVE_MEMBERS 権限を持つ DEPUTY_ADMIN か確認
3. 対象ユーザーが自分自身・ADMIN・SYSTEM_ADMIN でないか確認
4. 対象ユーザーが当該組織のアクティブメンバー（memberships WHERE scope_type='ORGANIZATION' AND scope_id=X AND user_id=対象 AND left_at IS NULL）であれば memberships を UPDATE SET left_at=NOW()（自動除名）
5. user_permission_groups（当該組織のグループに紐付く対象ユーザーの割り当て）を DELETE
6. organization_blocks に INSERT（reason は任意。既にブロック済みの場合は 409）
7. audit_logs に ORGANIZATION_MEMBER_BLOCKED を記録（target_user_id = 対象ユーザー、metadata に reason を含む）
8. 204 No Content を返す
```

### ブロック解除フロー（組織）

```
1. DELETE /api/v1/organizations/{id}/blocks/{userId} を受付
2. 操作者が ADMIN、または REMOVE_MEMBERS 権限を持つ DEPUTY_ADMIN か確認
3. organization_blocks に当該ユーザーのレコードが存在するか確認 → なければ 404
4. organization_blocks から DELETE
5. audit_logs に ORGANIZATION_MEMBER_UNBLOCKED を記録（target_user_id = 対象ユーザー）
6. 204 No Content を返す
```

### 自主退会フロー（チーム）

```
1. DELETE /api/v1/teams/{id}/me を受付
2. memberships に当該チームの自ユーザーのアクティブエントリが存在するか確認（left_at IS NULL） → なければ 404
3. ロールが SUPPORTER の場合 → 422（「SUPPORTER のフォロー解除は DELETE /api/v1/teams/{id}/follow を使用してください」）
4. 【最後のADMIN保護】ロールが ADMIN の場合:
   user_roles WHERE team_id = X AND role_id = ADMIN の件数を確認（権限ロールは user_roles で管理）
   → 1件（自分のみ）であれば 422（「最後の管理者のため退会できません。先に別のメンバーを ADMIN に昇格させるか、チームを削除してください」）
5. memberships を UPDATE SET left_at=NOW()（F00.5 Phase 2 以降）
6. user_permission_groups（当該チームのグループに紐付く自ユーザーの割り当て）を DELETE
7. audit_logs に TEAM_MEMBER_LEFT を記録（metadata: {"reason": "SELF_DEPARTURE"}）
8. 支払いデータ（member_payments 等）は削除しない（F04 詳細設計参照）
9. 204 No Content を返す
```

> - 再参加した場合は「新規加入」として扱う。以前の支払い有効期限の引き継ぎ等は F04 で設計する
> - チームへの直接所属のみ対象（組織経由の子チームへの所属は変更しない）

### 自主退会フロー（組織）

```
1. DELETE /api/v1/organizations/{id}/me を受付
2. memberships に当該組織の自ユーザーのアクティブエントリが存在するか確認（left_at IS NULL） → なければ 404
3. ロールが SUPPORTER の場合 → 422（「SUPPORTER のフォロー解除は DELETE /api/v1/organizations/{id}/follow を使用してください」）
4. 【最後のADMIN保護】ロールが ADMIN の場合:
   user_roles WHERE organization_id = X AND role_id = ADMIN の件数を確認（権限ロールは user_roles で管理）
   → 1件（自分のみ）であれば 422（「最後の管理者のため退会できません。先に別のメンバーを ADMIN に昇格させるか、組織を削除してください」）
5. memberships を UPDATE SET left_at=NOW()（organization スコープ）（F00.5 Phase 2 以降）
6. user_permission_groups（当該組織のグループに紐付く自ユーザーの割り当て）を DELETE
7. audit_logs に ORGANIZATION_MEMBER_LEFT を記録（metadata: {"reason": "SELF_DEPARTURE"}）
8. 支払いデータ（member_payments 等）は削除しない（F04 詳細設計参照）
9. 204 No Content を返す
```

> - 組織退会は組織スコープの memberships（`scope_type='ORGANIZATION' AND scope_id=X`）の left_at をセットするのみ。当該組織配下のチームへの所属（scope_type='TEAM' の memberships）は独立して残る。組織と配下チームの両方から抜けたい場合はそれぞれ個別に操作する
> - 再参加・支払いデータの扱いはチームフローに同じく F04 参照

**エラーレスポンス（自主退会共通）**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 404 | 対象チーム/組織に所属していない |
| 422 | SUPPORTER が `/me` を呼んだ（`/follow` を案内）|
| 422 | 唯一の ADMIN が退会しようとした（先に昇格または削除を促す）|

---

### DEPUTY_ADMIN / MEMBER 権限の 3 層制御

README 記載の 3 層制御は DEPUTY_ADMIN と MEMBER の両ロールに適用される。チームスコープ・組織スコープともに同一の仕組みを使用する（`permission_groups.team_id` または `permission_groups.organization_id` でスコープを区別）。

**DEPUTY_ADMIN の 3 層制御**

1. **SYSTEM_ADMIN が天井を設定**: `role_permissions WHERE role_id = DEPUTY_ADMIN AND is_default = FALSE`（Phase 2 時点で11件、Phase 3 以降 12件）が付与可能な上限。ADMIN は天井に含まれるパーミッションのみを権限グループに追加できる
2. **ADMIN が権限グループを構成**: `permission_groups（target_role = 'DEPUTY_ADMIN', team_id/organization_id = スコープID）` + `permission_group_permissions` で天井内のパーミッションを選択して名前付きグループを作成
3. **ADMIN がユーザーへ割り当て**: `user_permission_groups` で DEPUTY_ADMIN ユーザーと権限グループを紐付け（未割り当て = 実効パーミッション 0）

**MEMBER の 3 層制御**

1. **SYSTEM_ADMIN が天井を設定**: `role_permissions WHERE role_id = MEMBER`（is_default 問わず全6件）が権限グループに含められる上限
2. **ADMIN が権限グループを構成**: `permission_groups（target_role = 'MEMBER', team_id/organization_id = スコープID）` + `permission_group_permissions` で天井内のパーミッションを選択してグループを作成
3. **ADMIN がユーザーへ割り当て**: `user_permission_groups` で対象 MEMBER と権限グループを紐付け
   - 未割り当て → `is_default = TRUE` の基本3件のみが実効パーミッション
   - 1件以上割り当て → グループ内権限の UNION のみが実効パーミッション（基本3件を含むかどうかはグループ定義次第）

> **オーバーライドモデル**: グループが1件以上割り当てられると `is_default` は無視され、グループ内権限のみが実効パーミッションとなる。これにより「ADMIN がグループを割り当てるだけでデフォルト権限を含む完全な権限セットを上書き設定できる」設計になっている。権限を絞りたい場合は基本3件を含まないグループを割り当てればよく、マイナス計算のロジックが不要。
>
> **`DELETE_OTHERS_CONTENT` の扱い**: DEPUTY_ADMIN / MEMBER 双方の天井に含める。いかなるデフォルト権限グループにも含めない。ADMIN が意図的に付与した場合のみ有効。

### MEMBER 権限グループ設定フロー

**例1: MANAGE_ANNOUNCEMENTS を追加しつつ基本権限も維持したい場合**
```
1. ADMIN が MEMBER 用権限グループを作成
   POST /api/v1/teams/{id}/permission-groups  body: { "target_role": "MEMBER", "name": "お知らせ編集担当" }
2. 権限グループにパーミッションを設定（基本3件 + MANAGE_ANNOUNCEMENTS を含める）
   PATCH /api/v1/teams/{id}/permission-groups/{groupId}
   body: { "permission_ids": [MANAGE_SCHEDULES, MANAGE_FILES, MANAGE_POSTS, MANAGE_ANNOUNCEMENTS の各 id] }
3. 対象 MEMBER に権限グループを割り当て
   PUT /api/v1/teams/{id}/members/{userId}/permission-groups  body: { "group_ids": [groupId] }
4. 権限解決ロジックがグループ内パーミッション（4件）のみを実効権限として返す
5. audit_logs に TEAM_MEMBER_PERMISSION_GROUP_ASSIGNED を記録
```

**例2: 特定 MEMBER を制限（読み取り専用に近い状態）にしたい場合**
```
1. ADMIN が空（または最小限）の権限グループを作成・割り当て
   POST /api/v1/teams/{id}/permission-groups  body: { "target_role": "MEMBER", "name": "閲覧専用" }
2. パーミッションを設定しない（または最小限のみ）
3. 対象 MEMBER に割り当て → グループ未設定 MEMBER でデフォルト3件が消え実効権限0（または最小限）に
4. audit_logs に TEAM_MEMBER_PERMISSION_GROUP_ASSIGNED を記録
```

---

### 組織階層とカスケード通知

#### 階層構造

`organizations.parent_organization_id`（自己参照 FK）により任意深さの組織ツリーを構成する。上限は `app.org.max-depth` 設定値（デフォルト: 5）で管理する。スキーマを変更することなく設定値の変更だけで深さを調整可能にするための設計。

```
例（全国規模連盟 / depth 0〜4）:
  全国協会（組織 / depth 0）
    └── 関東支部（組織 / depth 1）
          └── 東京都連盟（組織 / depth 2）
                └── 渋谷区支部（組織 / depth 3）
                      └── 渋谷FC（組織 / depth 4）
                            └── 渋谷FCユース（チーム）── 個人メンバー
```

- **循環参照防止**: 親組織設定時にアプリ層で祖先を遡り、自組織が含まれないことを確認する
- **最大深さ**: `app.org.max-depth`（デフォルト: 5）。depth が `max-depth - 1` の組織を親とする組織の作成は 422 エラー。チームは depth に関係なく任意の組織に所属可
- **深度チェック**: 組織作成・`parent_organization_id` 更新時に祖先を遡って depth を計算し、`max-depth` を超える場合は 422（`"組織階層の最大深さを超えています"`）を返す

#### カスケード通知フロー

通知発行時に2つのスコープを独立して指定することで、**プッシュ通知の宛先**と**掲示板への表示範囲**をそれぞれ制御できる。

**① `notification_scope`（プッシュ通知の宛先範囲）**

| 値 | 収集対象 | ユースケース例 |
|----|---------|-------------|
| `ORGANIZATION` | サブツリー内の**組織に直接所属するメンバー**のみ | 学年の代表・管理者に連絡し、クラス内周知は各組織に任せる |
| `TEAM` | サブツリー内の**チームに所属するメンバー**のみ | クラスの全生徒・部員に直接プッシュ送信 |
| `INDIVIDUAL` | 組織直属 ＋ チームメンバー（全員）| 全関係者に一括プッシュ送信 |

**② `announcement_scope`（お知らせ掲示板への表示範囲）**

| 値 | 掲示板への投稿先 | ユースケース例 |
|----|---------------|-------------|
| `SELF` | 送信元組織の掲示板のみ（子には伝搬しない）| 組織内部向けの連絡 |
| `ORGANIZATIONS` | 送信元組織 ＋ サブツリー内の全子組織の掲示板 | 学校→各学年の掲示板に表示（クラスには出さない）|
| `TEAMS` | 送信元組織 ＋ サブツリー内の全組織 ＋ 全チームの掲示板 | 学校→学年→クラス全掲示板に表示 |

```
1. ADMIN（または SEND_SAFETY_CONFIRMATION / MANAGE_ANNOUNCEMENTS 権限を持つ DEPUTY_ADMIN）が
   上位組織から一括通知を発行し、以下を指定:
   - notification_scope（ORGANIZATION / TEAM / INDIVIDUAL）: プッシュ通知の宛先範囲
   - announcement_scope（SELF / ORGANIZATIONS / TEAMS）: お知らせ掲示板への表示範囲
2. WITH RECURSIVE CTE で対象組織のサブツリー（全子組織 ID）を再帰取得
3. [プッシュ通知] notification_scope に応じてメンバーを収集（下記 CTE 参照）
4. [掲示板投稿] announcement_scope に応じて投稿先エンティティリストを収集し、お知らせレコードを作成
   - SELF        → 起点組織のみ
   - ORGANIZATIONS → サブツリー内の全組織
   - TEAMS       → サブツリー内の全組織 ＋ 全チーム
5. 通知サービスに user_id リストを渡して一括プッシュ送信
6. audit_logs に発行組織・notification_scope・announcement_scope・対象メンバー数を記録
```

**MySQL 8.0 WITH RECURSIVE CTE（スコープ別メンバー取得）**

```sql
WITH RECURSIVE org_subtree AS (
  -- ベースケース: 起点組織
  SELECT id, 0 AS depth
  FROM organizations
  WHERE id = :rootOrgId AND deleted_at IS NULL

  UNION ALL

  -- 再帰ケース: 子組織を追加（:maxDepth - 1 まで; app.org.max-depth を Spring 側で bind）
  SELECT o.id, ot.depth + 1
  FROM organizations o
  INNER JOIN org_subtree ot ON o.parent_organization_id = ot.id
  WHERE o.deleted_at IS NULL AND ot.depth < :maxDepth - 1
)

-- scope = ORGANIZATION: 組織直属メンバーのみ（チームメンバーは含めない）
SELECT DISTINCT ur.user_id FROM user_roles ur
WHERE ur.organization_id IN (SELECT id FROM org_subtree);

-- scope = TEAM: 所属チームメンバーのみ（組織直属は含めない）
-- チームと組織の関係は team_org_memberships（status = ACTIVE）で解決する
SELECT DISTINCT ur.user_id FROM user_roles ur
INNER JOIN teams t ON ur.team_id = t.id
INNER JOIN team_org_memberships tom
  ON t.id = tom.team_id AND tom.status = 'ACTIVE'
WHERE tom.organization_id IN (SELECT id FROM org_subtree)
  AND t.deleted_at IS NULL AND t.archived_at IS NULL;

-- scope = INDIVIDUAL: 上記2クエリの UNION（全員）
SELECT DISTINCT ur.user_id FROM user_roles ur
WHERE ur.organization_id IN (SELECT id FROM org_subtree)
UNION
SELECT DISTINCT ur.user_id FROM user_roles ur
INNER JOIN teams t ON ur.team_id = t.id
INNER JOIN team_org_memberships tom
  ON t.id = tom.team_id AND tom.status = 'ACTIVE'
WHERE tom.organization_id IN (SELECT id FROM org_subtree)
  AND t.deleted_at IS NULL AND t.archived_at IS NULL;
```

> **パフォーマンス注意**: 大規模組織（数百チーム・数千メンバー）への同期実行はタイムアウトのリスクあり。通知発行は Spring `@Async` または MQ を使った非同期処理を推奨（詳細は通知機能 feature doc で設計）。

#### お知らせ掲示板への伝搬

`announcement_scope` は**送信時に1度だけ決定**し、お知らせレコードとともに保存する。各組織/チームの掲示板ロード時に、上位組織からの伝搬対象かどうかを動的に判定する（プルモデル）。

**掲示板表示の解決ロジック（お知らせ機能で実装・F03 はスコープ定義のみ規定）**

```
組織/チームの掲示板ロード時:
1. 当該エンティティ自身が発行したお知らせを取得
2. WITH RECURSIVE で祖先組織を上方向に遡り、各祖先のお知らせを取得
3. 各お知らせの announcement_scope に基づき表示可否を判定:
   - SELF        → 発行元エンティティのみに表示（子孫には表示しない）
   - ORGANIZATIONS → 発行元 + 子孫「組織」に表示（チームには表示しない）
   - TEAMS       → 発行元 + 子孫「組織」「チーム」すべてに表示
4. 表示対象のお知らせを発行日時降順でマージして返す
```

**スコープ × 表示先エンティティの対応表**

| announcement_scope | 発行元組織 | 子組織 | 子チーム |
|-------------------|:---:|:---:|:---:|
| `SELF` | ✓ | - | - |
| `ORGANIZATIONS` | ✓ | ✓ | - |
| `TEAMS` | ✓ | ✓ | ✓ |

> `announcement_scope` は通知発行後に変更不可とする（変更すると掲示板の表示/非表示が事後に変わり、ユーザーの混乱を招くため）。

#### `hierarchy_visibility` による上位組織閲覧制御

子組織・チームのメンバーが上位組織を参照する場合（`GET /api/v1/organizations/{id}`）、上位組織の `hierarchy_visibility` に従って返却内容を制限する。

| hierarchy_visibility | 返却内容（リクエスト者が子孫メンバーの場合）|
|---------------------|------------------------------------------|
| `NONE` | 404（組織の存在自体を露出しない）|
| `BASIC` | `id` / `name` / `description` / `icon_url` のみ |
| `FULL` | `visibility` 設定に従った通常レスポンス |

- 組織に**直接所属**しているメンバー（`user_roles.organization_id = 対象組織`）には `hierarchy_visibility` は影響しない
- 外部ユーザー（どの子孫にも所属していない）には `visibility` による通常制御を適用

```
閲覧制御判定フロー:
1. GET /api/v1/organizations/{targetOrgId} を受付
2. リクエスト者が targetOrgId に直接所属（user_roles に当該組織エントリあり）
   → visibility に従い通常レスポンス
3. リクエスト者が targetOrgId の子孫（子組織または子チーム）のメンバー:
   a. hierarchy_visibility = NONE  → 404
   b. hierarchy_visibility = BASIC → id / name / description / icon_url のみ返却
   c. hierarchy_visibility = FULL  → visibility に従い通常レスポンス
4. いずれにも該当しない外部ユーザー → visibility による通常制御
```

---

### 自動アーカイブバッチ（チーム）

**スケジュール**: 毎月1日 03:00 JST（Spring `@Scheduled(cron = "0 0 3 1 * *")`）

**アーカイブ判定条件**: チームに所属する全メンバー（任意ロール・`user_roles.team_id = 対象チームID`）の最終ログイン日時（`users.last_login_at`）のうち最大値が 12ヶ月以上前である場合にアーカイブ対象とする。

> - `users.last_login_at` は F02 スコープのカラム。本バッチは F02 テーブルをクロスフィーチャーで参照する
> - `last_login_at` が NULL のユーザー（ログイン記録なし）は `COALESCE(last_login_at, '1970-01-01')` で処理し、未ログインユーザーを最も古い日時として扱う
> - SUPPORTER / GUEST を含む全ロールのメンバーを対象とする（「アクティブな関係者」の基準を広く取る）

**バッチ SQL**（概略）:
```sql
UPDATE teams t
SET archived_at = NOW()
WHERE t.archived_at IS NULL
  AND t.deleted_at IS NULL
  AND (
    SELECT COALESCE(MAX(u.last_login_at), '1970-01-01')
    FROM user_roles ur
    JOIN users u ON u.id = ur.user_id
    WHERE ur.team_id = t.id
  ) < DATE_SUB(NOW(), INTERVAL 12 MONTH);
```

**バッチフロー**:
```
1. 上記 SQL で対象チームを一括 UPDATE（archived_at = NOW()）
2. 対象チームの invite_tokens を一括失効:
     UPDATE invite_tokens SET revoked_at = NOW()
     WHERE team_id IN (...対象チームID...) AND revoked_at IS NULL
3. audit_logs に TEAM_ARCHIVED（reason: AUTO_INACTIVE）を件数分 INSERT
4. バッチ実行ログにアーカイブ件数を記録
```

> バッチの排他制御は ShedLock 等の分散ロックライブラリで保証する（複数インスタンス構成での二重実行を防止）

---

### 自動アーカイブバッチ（組織）

**スケジュール**: チームバッチと同一（毎月1日 03:00 JST）。チームバッチの完了後に実行する。

**アーカイブ判定条件**: 以下の**すべて**を満たす組織をアーカイブ対象とする。

| # | 条件 |
|---|------|
| C1 | 組織に**直接所属する全メンバー**（`user_roles.organization_id = 対象組織ID`）の最終ログイン最大値が 12ヶ月以上前 |
| C2 | 組織に **ACTIVE 所属する全チーム**（`team_org_memberships.status = 'ACTIVE'`）の**全メンバー**の最終ログイン最大値が 12ヶ月以上前 |
| C3 | 当該組織に関連する**有効な支払い**（`member_payments.valid_until >= CURDATE()` または `valid_until IS NULL`）が存在しない（F04 クロスフィーチャー参照）|

> - C1・C2 の `last_login_at` NULL 処理は `COALESCE(last_login_at, '1970-01-01')` で統一
> - 子組織（`parent_organization_id` で連なる下位組織）はカスケード対象外。子組織は独自の条件でバッチ判定される
> - `member_payments` は F04 スコープ。F04 設計完了まで C3 はバッチに組み込まず、C1・C2 のみで先行実装可

**バッチ SQL**（概略）:
```sql
-- Step 1: 対象組織 ID リストを取得
SELECT o.id FROM organizations o
WHERE o.archived_at IS NULL
  AND o.deleted_at IS NULL
  -- C1: 直接所属メンバー全員が 12ヶ月超ログインなし
  AND NOT EXISTS (
    SELECT 1 FROM user_roles ur
    JOIN users u ON u.id = ur.user_id
    WHERE ur.organization_id = o.id
      AND COALESCE(u.last_login_at, '1970-01-01') >= DATE_SUB(NOW(), INTERVAL 12 MONTH)
  )
  -- C2: ACTIVE 所属チームの全メンバーも 12ヶ月超ログインなし
  AND NOT EXISTS (
    SELECT 1 FROM user_roles ur
    JOIN users u ON u.id = ur.user_id
    JOIN team_org_memberships tom
      ON tom.team_id = ur.team_id AND tom.status = 'ACTIVE'
    WHERE tom.organization_id = o.id
      AND COALESCE(u.last_login_at, '1970-01-01') >= DATE_SUB(NOW(), INTERVAL 12 MONTH)
  )
  -- C3: 有効な支払いなし（F04 確定後に追加）
  -- AND NOT EXISTS (
  --   SELECT 1 FROM member_payments mp
  --   JOIN payment_items pi ON pi.id = mp.payment_item_id
  --   WHERE pi.organization_id = o.id
  --     AND mp.status = 'PAID'
  --     AND (mp.valid_until IS NULL OR mp.valid_until >= CURDATE())
  -- )
;
```

**バッチフロー**:
```
1. 上記 SQL で対象組織 ID リストを取得
2. organizations.archived_at = NOW() を一括 UPDATE
3. カスケードアーカイブ対象チームを抽出:
     「対象組織ID にのみ ACTIVE 所属し、他に ACTIVE な org 所属を持たないチーム」
     （複数組織に所属するチームはカスケード対象外・他組織配下での継続稼働を保護）
4. 対象チームの teams.archived_at = NOW() を一括 UPDATE
5. 対象組織・カスケードチームの invite_tokens を一括失効:
     UPDATE invite_tokens SET revoked_at = NOW()
     WHERE (organization_id IN (...対象組織ID...)
        OR team_id IN (...カスケードチームID...))
     AND revoked_at IS NULL
6. audit_logs に ORGANIZATION_ARCHIVED（reason: AUTO_INACTIVE）を件数分 INSERT
7. カスケードアーカイブされたチームの audit_logs に
     TEAM_ARCHIVED（reason: AUTO_CASCADE_ORG）を件数分 INSERT
8. バッチ実行ログに組織・チームそれぞれのアーカイブ件数を記録
```

---

### 手動アーカイブフロー

**チームの場合:**
```
1. PATCH /api/v1/teams/{id}/archive を受付
2. ADMIN 権限チェック → ADMIN 未満は 403
3. archived_at IS NOT NULL → すでにアーカイブ済み → 422
4. teams.archived_at = NOW() で UPDATE
5. invite_tokens を一括失効:
     UPDATE invite_tokens SET revoked_at = NOW()
     WHERE team_id = 対象ID AND revoked_at IS NULL
6. audit_logs に TEAM_ARCHIVED（reason: MANUAL）を記録
7. 204 No Content を返す
```

**組織の場合（カスケードあり）:**
```
1. PATCH /api/v1/organizations/{id}/archive を受付
2. ADMIN 権限チェック → ADMIN 未満は 403
3. archived_at IS NOT NULL → すでにアーカイブ済み → 422
4. organizations.archived_at = NOW() で UPDATE
5. カスケードアーカイブ対象チームを抽出:
     「この組織にのみ ACTIVE 所属し、他に ACTIVE な org 所属を持たないチーム」
     ※ 複数組織に所属するチームはカスケード対象外
6. 対象チームの teams.archived_at = NOW() を UPDATE
7. 組織・カスケードチームの invite_tokens を一括失効:
     UPDATE invite_tokens SET revoked_at = NOW()
     WHERE (organization_id = 対象ID OR team_id IN (...カスケードチームID...))
     AND revoked_at IS NULL
8. audit_logs に ORGANIZATION_ARCHIVED（reason: MANUAL）を記録
9. カスケードされたチームの audit_logs に TEAM_ARCHIVED（reason: MANUAL_CASCADE_ORG）を記録
10. 204 No Content を返す
```

---

### アーカイブ解除フロー（チーム / 組織共通）

```
1. PATCH /api/v1/teams/{id}/unarchive（または /organizations/{id}/unarchive）を受付
2. ADMIN 権限チェック → ADMIN 未満は 403
3. archived_at IS NULL → アーカイブ状態でない → 422
4. archived_at = NULL で UPDATE
5. audit_logs に TEAM_UNARCHIVED（または ORGANIZATION_UNARCHIVED）を記録
6. 204 No Content を返す
```

> - アーカイブ解除後、招待トークンは自動復元しない（アーカイブ時に失効済み）。ADMIN が必要に応じて新規トークンを発行すること
> - アーカイブ解除後、書き込み制限は即時解除される

---

### アーカイブ状態における書き込み制限

アーカイブ済みチーム / 組織（`archived_at IS NOT NULL`）に対する以下の書き込み操作は、Service 層の入り口で `archived_at` を確認し 422（`"TEAM_ARCHIVED"` / `"ORGANIZATION_ARCHIVED"`）を返してブロックする。

**F03 スコープでのブロック対象操作:**

| 操作 | 対象エンドポイント |
|------|-----------------|
| チーム情報更新 | `PATCH /teams/{id}` |
| メンバーロール変更 | `PATCH /teams/{id}/members/{userId}/role` |
| 権限グループ割り当て | `PUT /teams/{id}/members/{userId}/permission-groups` |
| 招待トークン新規発行 | `POST /teams/{id}/invite-tokens` |
| 招待 URL 参加 | `POST /invite/{token}/join`（招待先チーム / 組織がアーカイブ済みの場合）|
| SUPPORTER 自己登録 | `POST /teams/{id}/follow` |
| 組織からのチーム招待送信 | `POST /organizations/{id}/team-invites`（招待先チームがアーカイブ済みの場合）|
| チームへの組織招待承認 | `POST /teams/{id}/org-invites/{id}/accept`（承認するチームがアーカイブ済みの場合）|
| 組織情報更新 | `PATCH /organizations/{id}` |
| 組織メンバーロール変更 | `PATCH /organizations/{id}/members/{userId}/role` |
| 組織権限グループ割り当て | `PUT /organizations/{id}/members/{userId}/permission-groups` |
| 組織招待トークン新規発行 | `POST /organizations/{id}/invite-tokens` |
| 組織 SUPPORTER 自己登録 | `POST /organizations/{id}/follow` |
| 組織権限グループ作成 | `POST /organizations/{id}/permission-groups` |
| 組織権限グループ更新 | `PATCH /organizations/{id}/permission-groups/{groupId}` |

**アーカイブ中も許可する操作（読み取り・クリーンアップ系）:**

| 操作 | 理由 |
|------|------|
| 全 GET 操作 | 読み取り専用のため影響なし |
| `DELETE /teams/{id}` | 論理削除は引き続き可（アーカイブ済みチームも削除可能）|
| `DELETE /teams/{id}/members/{userId}` | ADMIN によるクリーンアップ目的 |
| `DELETE /teams/{id}/me` | メンバー自身の離脱意思は尊重する |
| `DELETE /teams/{id}/follow` | フォロー解除は常に許可 |
| `PATCH /teams/{id}/unarchive` | 解除操作自体はアーカイブ中に呼ばれるべき |
| `DELETE /teams/{id}/invite-tokens/{id}` | 残存トークンの手動失効は許可 |
| `DELETE /organizations/{id}` | 論理削除は引き続き可（アーカイブ済み組織も削除可能）|
| `DELETE /organizations/{id}/members/{userId}` | ADMIN によるクリーンアップ目的 |
| `DELETE /organizations/{id}/me` | メンバー自身の離脱意思は尊重する |
| `DELETE /organizations/{id}/follow` | 組織フォロー解除は常に許可 |
| `PATCH /organizations/{id}/unarchive` | 解除操作自体はアーカイブ中に呼ばれるべき |
| `DELETE /organizations/{id}/invite-tokens/{id}` | 残存トークンの手動失効は許可 |
| ブロック操作（`/teams/{id}/blocks`・`/organizations/{id}/blocks` 系）| ADMIN によるアカウント管理目的 |

> F04（支払い）・F05（スケジュール）等の他フィーチャーの書き込み操作も同様にアーカイブチェックが必要。各フィーチャードキュメントで `archived_at IS NOT NULL` の場合に 422 を返すよう明記すること

