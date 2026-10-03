# P5 施設・住宅・マンション・見守り E2E テスト法案

> 対象: F09.5 / F09.1 / F09.3 / F09.16 / F03.12 / F08.8 / F09.14 / F09.15
> 凡例・テスト層は [README](./README.md) 参照。
> ⚠️ **裏取り品質 △（最も弱い）**: 家老が「read-only だから実装確認不可」と誤解し、**実装を実ファイルで開かず設計書中心**で進めた。判定の実装裏取りは不十分 → **実機 E2E 実行時に現地で実在確認が必須**。設計トレース自体は有用。

---

## 1. トレーサビリティ監査サマリ（※実装裏取りは要再確認）

| 機能 | 設計の核心 | 実装(家老の机上判定) | 判定 |
|---|---|---|---|
| **F09.5 共用施設予約** | §3 時間帯重複チェック SQL(`time_from < :timeTo AND ADDTIME(time_to, cleaning_buffer) > :timeFrom AND status IN PENDING/CONFIRMED/CHECKED_IN`)・楽観ロック`version` | FacilityBookingController(要実ファイル確認) | 🟢(要裏取り) |
| F09.1 住民台帳 | §3 resident_registry(move_in/out)・dwelling_units.resident_count | 実装済(Stage3 triage) | 🟢(要裏取り) |
| F09.3 駐車場区画 | §3-4 | 6系統 Controller | 🟢(要裏取り) |
| F09.16 居住実態・見守り | §5-7 ケアリンク・不在エスカレーション | S1完(設計) | 🟡 v2候補にAPI未実装あり |
| F03.12 ケア対象見守り | §4.3 2段エスカレーション(soft_check 10分/absent_alert 30分) | CareAbsentAlertBatchService | 🟢(要裏取り) |
| F08.8 修繕長期計画DB | §3-7 | 実装開始 | 🟡(要裏取り) |
| F09.14 重説出力 | §5.4/§6.3 SHA-256+電子印鑑 多層改ざん検出 | DisclosureExportService(2層AND検証) | 🟢(要裏取り) |
| F09.15 承継支援 | §7.2 二者承認状態機械・72h自動再封 | v1実装(S0-S6) | 🟢(要裏取り) |

---

## 2. E2E 実機シナリオ（代表・トレーサ付き）
- **[F09.5-E01]** 施設予約の時間帯重複制御: 集会室A「9:00-12:00」CONFIRMED に対し「11:00-13:00」申請→**409**。清掃バッファ30分考慮で「12:30」以降のみ許可。（§3 重複SQL+楽観ロック）★競合制御が肝
- **[F09.1-E01]** 居住者入退居: 101号室入居→退居処理(move_out_date)→resident_count -1→is_primary 自動委譲。（§3/§4）
- **[F09.15-E01]** 死亡確定・二者承認72h: 理事長 death_status=CONFIRMED→封緘解除申請(CREATED)→副理事長 first-approve→別メンバー second-approve(3者別人 CHECK `chk_ur_three_distinct`)→UNSEALED(auto_reseal_at=+72h)→自動再封。（§7.2/§3.5）
- **[F03.12-E01]** ケア不在2段エスカレーション: 9:00開始イベント RSVP=ATTENDING の未成年→9:10 soft_check(保護者へ軽く)→9:30 absent_alert(保護者+ADMINへ強く)。（§4.3 notification_logs 冪等）
- **[F09.14-E01]** 重説3層改ざん検出: PDF出力(output_sha256)→電子印鑑回覧→DL時に R2 SHA-256照合(第1層)+seal_hash照合(第2層)、両層AND PASS で正常/NG で503。（§5.4/§6.3）

---

## 3. このフェーズの「設計にあるが UI/導線が無い」確定（v2 候補=🔵 先送り正規）
| 機能 | 状態 |
|---|---|
| F09.16 年次キャンペーン全回答一覧・WATCHER別訪問記録 | 🔵 v2候補(§6.1) |
| F09.15 死亡状態手動入力 API | 🔵 v2(v1は stage4 自動SUSPECTEDのみ) |
| F09.1 住民台帳 CSV インポート/エクスポート | 🔵 triage で「未実装」マーク(§4) |

---

## 4. 施設予約の競合制御（要実機確認の最重点）
設計(§3): 同一施設+同一日付+時間帯重複(cleanup_buffer 含む)の PENDING 以上を 409 拒否、`version` で楽観ロック。
→ **家老は実装を開けていない。E2E で「2ユーザー同時予約→1件のみ成功」を必ず実証すること（オーバーブッキング防止の核心）。**

## 5. 既存 E2E spec ギャップ
- P5 全般、実装の実ファイル裏取りが未完。実機テスト着手時に Controller/Service/Flyway/FE を開いて 4 値判定を確定し直す。
