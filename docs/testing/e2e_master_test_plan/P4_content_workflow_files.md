# P4 コンテンツ・ワークフロー・ファイル・掲示板・TODO E2E テスト法案

> 対象: F05.1 / F05.2 / F05.3 / F05.5 / F05.6 / F05.7 / F06.1/3/4/5 / F02.3(+3.1)
> 凡例・テスト層は [README](./README.md) 参照。
> ⚠️ **裏取り品質 △**: 家老が設計書精読に予算を費やし、FE 実装を開ききれず「🟡確認保留」が多い。実機 reachability で確定する。

---

## 1. トレーサビリティ監査サマリ

### F05.2 回覧板（`docs/features/F05.2_circular.md`）— BE 実装確認
| 機能要素 | BE | 判定 |
|---|---|---|
| 回覧文書 CRUD(DRAFT/PUBLISHED) | TeamCirculationDocumentController | 🟡 FE要確認 |
| 回覧方式(順番/ハイブリッド/一斉) | circulation_mode | 🟡 |
| 押印(F05.3連携)・お辞儀ハンコ(tilt_angle) | CirculationStampController | 🟡 |
| 自動リマインド・強制完了 | CirculationAdminController | 🟡 |
| PDF エクスポート(押印証跡・非同期) | CirculationExportController | 🟡 |
| **自分宛未確認回覧一覧** (`GET /me/circulations/pending`) | 未実装 | 🔴 Phase2 |
| ⚠️ 管理者API(remind/force-complete/duplicate) | `@PreAuthorize("hasRole('ADMIN')")` で**一時 ADMIN 限定**(F09.18 `@EnableMethodSecurity` 待ちの安全側倒し) | — |

### F05.7 書類テンプレート・フォームビルダー / F06.1 CMS / F02.3.1 TODO
| 機能 | BE | 判定 |
|---|---|---|
| F05.7 フォームテンプレCRUD/提出/PDF/CSV | form-templates/form-submissions Controller | 🟡 FE要確認 |
| F05.7 承認必須(requires_approval→F05.6) | F05.6 依存 | 🟡 連携待ち |
| F06.1 ブログ CRUD/公開/承認/セルフレビュー/リビジョン/メディア孤立クリーンアップ | blog Controller 群 | 🟡 FE要確認 |
| F02.3.1 カスタムステータスラベル/キャッチボール(handoff)/履歴 | todo Controller + todo_handoffs | 🟡 FE要確認 |
| F06.3 タイムラインダイジェスト(AI) | feature flag 制御 | 🟡 `FEATURE_DIGEST_AI` |
| F06.4/F06.5 Org スコープ ナレッジベース | Team のみ実装 | 🔴 別PR |

---

## 2. E2E 実機シナリオ（代表・トレーサ付き）
- **[F05.2-E01]** 順番回覧: 作成(DRAFT)→公開(IN_PROGRESS)→部長押印→課長アンロック→全員押印→自動 COMPLETED→アーカイブキュー。（§4.1-4.3）
- **[F05.2-E02]** お辞儀ハンコ(0.1%確率 is_flipped)→訂正(二重線SVG+隣に訂正印)。（§1/§3）
- **[F05.2-E04]** PDF エクスポート(非同期 PENDING→COMPLETED、Pre-signed URL TTL1h)。（§4.8）
- **[F05.7-E01]** フォームテンプレ作成→公開→MEMBER 提出(DRAFT→SUBMITTED)→PDF生成(@Async/R2)→ダウンロードURL。（§4）
- **[F05.7-E02]** CSV エクスポート(UTF-8 BOM・CSVインジェクション対策の先頭`'`前置)。（§4）
- **[F06.1-E01]** ブログ ライフサイクル(DRAFT→公開でtimeline_post_id連携/reading_time算出→編集でリビジョン自動保存→1年後ARCHIVED→削除)。（§4/§5）
- **[F06.1-E02]** MEMBER 投稿の承認＋セルフレビュー(深夜23-6時は PENDING_SELF_REVIEW→翌朝確認→PENDING_REVIEW→ADMIN承認/却下)。（§1/§5）
- **[F02.3.1-E01]** チーム TODO キャッチボール(handoff で assignee 入替+status_label変更+todo_handoffs記録+TODO_HANDED_OFF通知)。（§4/§5.4）
- **[F02.3.1-E02]** ラベル削除制約(使用中は409 LABEL_IN_USE+in_use_count)。（§5.2）

---

## 3. このフェーズの「設計にあるが UI/導線が無い」確定
| 機能 | 状態 |
|---|---|
| F05.2 自分宛未確認回覧一覧 | 🔴 Phase2 |
| F06.3 AI ダイジェスト生成 | 🟡 機能フラグ制御(初期OFF) |
| F06.4/F06.5 Org スコープ ナレッジベース | 🔴 Team のみ実装 |
| 回覧 受取側「回覧受取」遷移導線 | 🟡 要確認 |

---

## 4. 既存 E2E spec ギャップ / 注意
- **認可テスト**: F05.2 管理者 API は現状 ADMIN 限定(F09.18 待ち)。DEPUTY_ADMIN 操作テストは Phase18-d 以降。実機テストは ADMIN で実行。
- **WF連携**: F05.7 `requires_approval=true` は F05.6 実装状態に依存 → まず `=false` で先行検証。
- **非同期PDF**: `GET /pdf/status` polling(5分×3リトライ)を基本パターンに。
- **裏取り再確認**: 上記 🟡 は実機 reachability で UI 実在を確定すること。
