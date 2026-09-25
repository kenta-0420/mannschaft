# F03: 組織・チーム・メンバー・ロール管理

> **ステータス**: 🟢 設計完了
> **実装フェーズ**: Phase 2
> **最終更新**: 2026-06-14

---

## 1. 概要

組織（organizations）・チーム（teams）の作成と管理、メンバーシップの制御、ロール/パーミッション（RBAC）の定義と割り当て、招待URL/QRコードによるメンバー加入を担う中核機能。
個人・チーム・組織の3層構造と「チームAでは DEPUTY_ADMIN、チームBでは MEMBER」のようなマルチスコープ所属を実現する。
DEPUTY_ADMIN の細粒度な権限制御は、ADMIN が名前付き「権限グループ」を作成してユーザーへ割り当てる方式で実現する。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全組織・チームの参照・強制削除・ロール変更 |
| ADMIN | 担当チーム/組織の全設定・メンバー管理・招待発行・権限グループ管理 |
| DEPUTY_ADMIN | ADMIN が付与した権限グループの範囲内のみ（招待は INVITE_MEMBERS 権限が必要）|
| MEMBER | MANAGE_SCHEDULES / MANAGE_FILES / MANAGE_POSTS はすべて初期 OFF で、対象チーム・組織の ADMIN が既定権限画面から ON にするか、権限グループに含めて割り当てた場合に該当する管理操作を許可する。権限グループを割り当てた場合は既定値を含め全権限がグループ定義で置き換わる |
| SUPPORTER | 公開チームページから招待コード不要でフォロー（サポーター登録）。チームが `supporter_enabled = TRUE` の場合のみ利用可。ブロック済みユーザーは登録不可 |
| GUEST | 閲覧のみ（招待URL経由で付与）|

### 対象レベル
- [x] 組織 (Organization)
- [x] チーム (Team)
- [x] 個人 (Personal) — メンバーとして参加

---

## ドキュメント構成

| ファイル | 内容 |
|---|---|
| [01_db_design.md](01_db_design.md) | §3 DB設計 |
| [02_api_design.md](02_api_design.md) | §4 API設計 |
| [03_business_logic.md](03_business_logic.md) | §5 ビジネスロジック |
| [04_security_operations.md](04_security_operations.md) | §6 セキュリティ / §7 Flyway / §8 未解決事項 / §9 変更履歴 |
| [../F01.2.1_org_team_groups.md](../F01.2.1_org_team_groups.md) | サブ機能 F01.2.1: チーム加盟の双方向化（チームからの加盟申請・申請受付設定）とチームグループ（グループ宛てお知らせ）。🟡 設計中 |
| [05_scope_guides.md](05_scope_guides.md) | チーム・組織別の案内ページと初回権限設定 |
