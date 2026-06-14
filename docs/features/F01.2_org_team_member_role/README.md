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
| MEMBER | デフォルトで MANAGE_SCHEDULES / MANAGE_FILES / MANAGE_POSTS を保持。ADMIN が権限グループを割り当てることで実効権限を上書き設定可能（グループ割り当て時はデフォルト権限を含め全権限がグループ定義で置き換わる）|
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
