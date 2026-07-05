# F08.9 P3-view 保護者による子データ閲覧専用見守り（Guardian Child View）

> **ステータス**: 🟢 設計確定（マスター御裁可済・件2）／**2026-07-05 起草**
> **親機能**: [F08.9 会員決済・後見つきマルチ受益者](README.md) の P3（年齢段階つき後見切替）に付随する **閲覧専用（read-only）** 派生
> **関連**: [02_api_design.md](02_api_design.md)（後見切替 §2.2 / 自立移行 §2.3）／[03_security.md](03_security.md)（年齢ゲート §3）／[F01.9 年齢確認・保護者同意](../F01.9_age_verification_parental_consent.md)／[F03.12 見守り通知](../F03.12_care_recipient_event_watch_notification.md)／[F14.1 代理入力](../F14.1_proxy_input_for_offline_residents.md)／[F00 可視性基盤](../F00_content_visibility_resolver.md)

---

## 1. 目的・スコープ

### 1.1 目的

保護者（親）が **12歳未満（小学生以下）の子** のアプリ内データを、**閲覧専用**でまとめて見守れるようにする。
「後見切替（acting-as）＝子として操作する」重い経路（[README §3.2](README.md)）とは別に、**操作せず見るだけ** の軽量な導線を提供する。

保護者が 1 画面で把握したいのは次の 4 面である。

| # | 面 | 委譲先ドメイン |
|---|---|---|
| ① | **今後の予定**（横断カレンダー） | schedule |
| ② | **出欠状況**（出席率統計） | schedule |
| ③ | **所属チーム/組織** | membership |
| ④ | **お知らせ受信**（掲示板スレッド） | bulletin |

加えて、代理入力（件3・proxy-actions）の履歴を「誰が子の代わりに何をしたか」の透明化のために返す。

### 1.2 スコープ

**対象（in）**

- [x] 保護者が有効な保護者リンクを持つ **12歳未満** の子の、上記 4 面＋代理履歴の **読み取り専用 API**
- [x] 認可は既存 `GuardianshipSwitchService.evaluateSwitch` を再利用（副作用なし・権原＋年齢ゲート）
- [x] F00 可視性は **子基準**（viewer=子）で自動評価

**対象外（out）**

- [ ] 書き込み（作成・更新・削除）— 本機能は **一切の write EP を持たない**（AC-7）
- [ ] 12歳以上の子の閲覧（年齢封印で 403）— 自立段階の子はプライバシーを持つ（[README §3.2](README.md)）
- [ ] 後見切替セッション（acting-as）そのもの — 既存 P3c（[02_api §2.2](02_api_design.md)）で提供済
- [ ] 会費・決済・ペイウォール（本書は閲覧見守りのみ）

---

## 2. 二本立て年齢モデルの位置づけ

Mannschaft の「子ども」に対する年齢しきい値は **2 系統** あり、両者は目的が異なる。本機能は **②の 12歳未満のみ** を対象とする。

| モデル | しきい値 | 目的 | 根拠 |
|---|---|---|---|
| **① 利用開始承認** | **18歳未満**（未成年） | アカウント作成時に保護者同意を要する | [F01.9 年齢確認・保護者同意](../F01.9_age_verification_parental_consent.md)（`parental_consent_links`） |
| **② 継続監視（本機能・後見切替）** | **12歳未満**（小学生以下・国別 `GuardianshipAgePolicy`） | 保護者が子アカウントを強権管理／見守りできる段階を年齢で封印 | [README §3.2](README.md)（`JapanGuardianshipAgePolicy`＝満12歳年度末で封印） |

- 12〜17歳の子は「①で保護者同意を得て利用開始した」が「②の継続監視段階は既に卒業した」状態であり、**本見守り API では 403（AGE_LOCKED）** となる（自立・プライバシー付与）。
- しきい値は **国別ポリシーのからくり**で解決（`users.country_code`）。本機能は独自にしきい値を持たず、`evaluateSwitch` の年齢ゲート（`JapanGuardianshipAgePolicy` 等）をそのまま流用する（焼き付け禁止）。

---

## 3. エンドポイント一覧

すべて `@RequestMapping("/api/v1/me/guardianship")` 配下の **GET のみ**。払い手（保護者）は常に `SecurityUtils.getCurrentUserId()` に固定し、`childUserId` はパスで受ける。他人の子の `childUserId` を指定しても認可段で **同一の 403** に落ち、存在有無を漏らさない（列挙不可・IDOR 防止）。

| メソッド | パス | 委譲先 | 返却 DTO |
|---|---|---|---|
| GET | `/children/{childUserId}/schedules?from=&to=` | `ScheduleQueryService.getMyCalendar(childUserId, from, to)` | `List<CalendarEntryResponse>`（schedule ドメイン既存 DTO を再利用） |
| GET | `/children/{childUserId}/attendance/stats?from=&to=` | `ScheduleAttendanceService.getMyAttendanceStats(childUserId, from, to)` | `AttendanceStatsResponse`（既存 DTO 再利用） |
| GET | `/children/{childUserId}/memberships` | `MembershipService.getActiveTeamIdsByUser` / `getActiveOrgIdsByUser` ＋ `NameResolverService` で名称合成 | `GuardianChildMembershipsResponse` |
| GET | `/children/{childUserId}/announcements?page=&size=` | 子の所属スコープ列挙 → `BulletinThreadService.listThreads(TEAM/ORG, scopeId, childUserId, pageable)` を合成 | `GuardianChildAnnouncementsResponse` |
| GET | `/children/{childUserId}/proxy-actions` | `ProxyInputQueryService.getActionsBySubject(childUserId)`（proxy ドメインの取得系サービス経由・`ProxyActionView` を受領） | `GuardianChildProxyActionsResponse` |

`from`/`to` は `@DateTimeFormat(iso = DATE_TIME)` の `LocalDateTime`（schedule 系既存 EP と同一流儀）。

### 3.1 返却 DTO 概略

```
GuardianChildMembershipsResponse
  teams:         List<ScopeRef>   // 所属チーム（scopeId + name）
  organizations: List<ScopeRef>   // 所属組織（scopeId + name）
  ScopeRef { scopeId: Long, name: String }

GuardianChildAnnouncementsResponse
  items:         List<AnnouncementItem>
  page, size:    int
  totalElements: long             // 全所属スコープの合算件数
  AnnouncementItem { threadId, scopeType, scopeId, scopeName, title, priority, createdAt, updatedAt }

GuardianChildProxyActionsResponse
  items:         List<ProxyActionItem>
  ProxyActionItem { id, proxyUserId, featureScope, targetEntityType, targetEntityId, inputSource, createdAt }
  // subjectUserId は常に子（= childUserId）ゆえ冗長返却しない
```

---

## 4. 認可モデル

### 4.1 evaluateSwitch 再利用（単一の権原判定）

閲覧の各 EP は、委譲の**前に必ず** `GuardianshipSwitchService.evaluateSwitch(guardianUserId, childUserId)` を通す（副作用なし・監査を打たない純判定）。`SwitchVerdict` を HTTP に写像する。

| Verdict | 意味 | HTTP | エラーコード |
|---|---|---|---|
| `ALLOWED` | 保護者リンク有効 かつ 12歳未満（切替可段階） | 委譲続行（200） | — |
| `LINK_NOT_FOUND` | 有効な保護者リンクなし（他人の子・存在しない子・不整合を含む） | **403** | `GUARDIANSHIP_LINK_NOT_FOUND`（MEMBERSHIP_BILLING_005） |
| `AGE_LOCKED` | 12歳以上（自立段階）で封印／生年月日解決不能の安全側封印 | **403** | `GUARDIANSHIP_SWITCH_AGE_LOCKED`（MEMBERSHIP_BILLING_004） |

- 権原（リンク）は `parental_consent_links(APPROVED)` ∪ `care_links(ACTIVE, PARENT)` の和集合で成立（`evaluateSwitch` 内部）。
- 年齢ゲートは `JapanGuardianshipAgePolicy`（学齢12封印）等の国別ポリシー。**本機能は独自の年齢判定を持たない**。

### 4.2 IDOR 防止・列挙不可

- `childUserId` の実在有無・年齢・所属をレスポンス差分から推測されないよう、**リンク不成立・子不在・不整合はすべて `LINK_NOT_FOUND`（403）** に一本化する（`evaluateSwitch` の実装がこれを保証）。
- 404 を返さない（存在の有無を漏らさない）。他人の子の列挙・総当りは 403 で塞ぐ。

### 4.3 read-only 境界（AC-7）

- 本コントローラは **GET のみ** を宣言する。POST/PUT/DELETE は一切マッピングしない（誤って write 経路が生えないことをコントローラ契約テストで固定）。
- Service も委譲先の **read メソッドのみ** を呼ぶ（`getMyCalendar` / `getMyAttendanceStats` / `getActive*IdsByUser` / `listThreads` / `findBySubjectUserId...`）。監査・状態変更を起こさない。

---

## 5. F00 可視性 = 子基準

- 予定（①）は `ScheduleQueryService.getMyCalendar(childUserId, ...)` が **viewer=childUserId** として `ContentVisibilityChecker.filterAccessible` を通す。したがって返る予定は **子が本来見える範囲** に厳密に一致する。
- お知らせ（④）は `BulletinThreadService.listThreads(..., userId=childUserId, ...)` が **子の所属メンバーシップ**でゲートする。
- **保護者だけに見える隠しコンテンツは出ない**。これは意図した正しい挙動である（保護者は「子が見ているものを、子の目線で」見守る）。保護者固有の可視性を足し込まない。

---

## 6. 代理マーク（件3・proxy-actions）との結線

- `proxy-actions` は `proxy_input_records`（[F14.1 代理入力](../F14.1_proxy_input_for_offline_residents.md)）から **subject=子** のレコードのみを新しい順で返す。
- 用途は「子の代わりに誰（`proxyUserId`）がどの機能（`featureScope`）で何（`targetEntityType`/`targetEntityId`）を、どの入力元（`inputSource`：紙／電話／対面／後見切替）で行ったか」を保護者が透明に確認するための **代理バッジ用データ**（件3 のマーク表示土台）。
- 後見切替セッション由来（`inputSource=GUARDIANSHIP_SWITCH`）も含めて subject=子 で網羅されるため、保護者は自らの代理操作履歴も本 API で追える。

---

## 7. 受け入れ条件（AC）

| # | 条件 | 検証 |
|---|---|---|
| **AC-1** | 有効な保護者リンクを持つ親が `GET /children/{childUserId}/schedules` → 200 で子の可視予定が返る | Service UT（ALLOWED→委譲）／Controller 契約テスト（200） |
| **AC-2** | リンク非該当の他人 → 403 `GUARDIANSHIP_LINK_NOT_FOUND`（404 でなく 403・列挙不可） | Service UT（LINK_NOT_FOUND→403）／Controller（403 + code） |
| **AC-3** | 存在しない／不整合 `childUserId` → 情報を漏らさず 403（`LINK_NOT_FOUND`） | Service UT（evaluateSwitch=LINK_NOT_FOUND）で 403 |
| **AC-4** | **12歳以上**の子 → 403（AGE_LOCKED・封印）／12歳未満は 200 | Service UT（AGE_LOCKED→403 / ALLOWED→200） |
| **AC-5** | 返る予定は **子基準 F00** で絞られる（親限定公開は含まれない） | Service UT（`getMyCalendar(childUserId,...)` に childUserId が渡ることを verify） |
| **AC-6** | `proxy-actions` が **subject=子** のレコードのみ返す（代理マーク用） | Service UT（`findBySubjectUserIdOrderByCreatedAtDesc(childUserId)` を verify・DTO 写像） |
| **AC-7** | 閲覧 API は **読み取り専用**（POST/PUT/DELETE 経路を一切持たない） | Controller 契約テスト（GET パスへの POST が 405） |

加えて 4 面それぞれ（schedules / attendance / memberships / announcements）の **200 正常系＋認可 403** を最低 1 本ずつ確認する。

---

## 8. 実装メモ（ドメイン境界）

- 新規 Service `GuardianChildViewService` は **他ドメインの Entity/Repository を直接参照せず、必ず各ドメインの Service メソッドを ID 経由で呼ぶ**（[CLAUDE.md ドメイン境界]／既存 `GuardianshipSwitchService` が `ParentalConsentService`/`CareLinkService` を呼ぶのと同型）。代理履歴は proxy ドメインに新設した `ProxyInputQueryService.getActionsBySubject`（`ProxyActionView` プリミティブ DTO を返す）経由で取得し、`proxy.entity`/`proxy.repository` を一切 import しない（ArchUnit D-1/D-3 遵守）。
- 返却は schedule ドメインの既存 DTO（`CalendarEntryResponse`/`AttendanceStatsResponse`）を再利用し、membership/bulletin/proxy 面は auth ドメイン側の軽量 DTO に写像する（bulletin の `ThreadResponse` を素で外へ出さず `AnnouncementItem` へ縮約）。
- `SecurityConfig` 等に新フィルタ／新必須 Bean 依存を足さない（フルシャード `@SpringBootTest` context を壊さないため）。通常の `@RestController` ＋ 純 Mockito UT ＋ 契約テストで検証する。
- お知らせ（④）は「子の所属スコープ列挙 → 各スコープで `listThreads` → 合算 → `updatedAt` 降順 → ページ窓で切り出し」で全体ページングを近似する（各スコープから `(page+1)*size` 件を取り、グローバル最新 `offset+size` 件を保証）。4 面で最も高コストのため、規模拡大時は専用集約クエリへの置換余地を残す。

---

## 9. 変更履歴

| 日付 | 内容 |
|---|---|
| 2026-07-05 | 初版。件2（保護者の子データ閲覧専用見守り）をマスター御裁可方針で起草。4 面（予定・出欠・所属・お知らせ）＋代理履歴の read-only API・`evaluateSwitch` 再利用の認可・12歳未満のみ・F00 子基準を確定。AC-1〜7 を列挙し test-first で BE 実装 |
