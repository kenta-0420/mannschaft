# モバイル通常メニューの検証証跡（CMP-261016-1009）

実装PR [#3923](https://github.com/kenta-0420/mannschaft/pull/3923) は2026-10-16 17:37 JSTに通常squash merge済み。merge commitは`2e60b15a39d05733c51d1912a03abdb03d92eadb`。最終検分finding0、対象18テストを含む609ファイル／5991テストと必須CI、実画面再試験、3住民探索を完了した。

## 対象と因果の限界

チーム／組織の通常トップからモバイルDrawerを開き、管理コンソール、メンバー管理、QR会員証管理と使い方へ到達する。権限・モジュール取得中の明示表示、取得失敗の再試行、スコープ切替後の再取得を修正する。住民R2が追加発見した組織メンバー一覧の旧`?tab=2`導線も同PRで修正する。

最初の「ADMIN応答200なのに空Drawer」という観測の初期例外stackは保存されておらず、その直接原因は未確定。正常ADMIN応答の初期テストは元からgreenであり、red回帰の証拠として扱わない。確定した取得中・失敗・スコープ更新の欠陥と、追加の組織旧tab導線だけを根拠とする。

## 証跡の読み方

- [manual-final-report.json](manual-final-report.json) はhead `12a58857f80535a8bc2f675eef0b74c4abf7e62a`時点のroot実機11ケースPASSと住民探索待ちの履歴。`pending`を最終結果と混同しない。
- [manual-api-summary.json](manual-api-summary.json) は実ブラウザで観測したAPI応答。認証レスポンス、Cookie、本文、個人データを除外した。失敗誘発中502と権限拒否403を含む。
- [regressions.json](regressions.json) と[unit/](unit/) は修正前assertion失敗と修正後成功。CI旧headの`remaining`は当時の履歴であり書き換えない。
- [residents/](residents/) は原報告のpersonaId・journeyId・runIdを保持した抜粋。原観測の未確定表現は保存し、rootの裏取りは別の集約判定に記録する。

## 受け入れ条件と証跡

| 条件 | 実装・自動テスト | 実アプリ確認・住民 |
|---|---|---|
| 390/360px、Team A／Organization Aの通常トップ→Drawer→管理コンソール→メンバー | `frontend/app/components/BaseSidebar.vue`、`BaseSidebar.spec.ts` | root TEAM390／TEAM360／ORG390／ORG360、[Teamメンバー](manual/admin-team390-members-loaded.png)、[Orgメンバー](manual/admin-org360-members.png) |
| QR会員証管理→使い方 | 通常Drawerの管理リンク、既存会員証ページ | root同4ケース、[Team QR](manual/admin-team390-qr-loaded.png)、[Team360使い方](manual/admin-team360-qr-guide.png)、[Org360使い方](manual/admin-org360-qr-guide.png) |
| 取得中、失敗、再試行・回復 | `BaseSidebar.spec.ts`取得中・権限失敗・モジュール保留中失敗、実assertのred→green JSON | [読み込み](manual/admin-team360-menu-loading.png)、[エラー](manual/admin-team360-menu-error.png)、[同Drawer回復](manual/admin-team360-menu-recovered.png) |
| スコープ切替で再取得し前スコープの管理リンクを残さない | `BaseSidebar.spec.ts`チーム／組織切替、業者リンク更新 | root SCOPE_SWITCH、[ADMIN→MEMBER](manual/admin360-scope-switch-to-member-team-b.png) |
| MEMBER／非所属／未認証は管理情報を取得できない | 権限に応じたリンク表示と既存認可境界 | root MEMBER_TEAM390／MEMBER_ORG360／OTHER_ADMIN_OWN_TEAM360／OTHER_ADMIN_FOREIGN_SCOPE／UNAUTHENTICATED360、R3自チーム到達・他スコープ403・復帰 |
| 3住民が別ログインで自律探索しrootが実証 | 3住民探索とroot裏取り完了 | R2（ADHD傾向×中断・放棄）、R3（一般×隙間狙い）、R1（スマホ片手操作×表示崩れ）：原探索では目的部分達成・Orgメンバー安定未確認。後続root実機で両幅の安定表示を補完 |
| 追加発見：組織メンバー一覧の旧tabリンクを現行routeへ統一 | `BaseSidebar.spec.ts`追加7ケース：0pass/7fail→7pass/0fail。head `25ece9b77159e117c631ddb82e88311bc7665570`、[CI](https://github.com/kenta-0420/mannschaft/actions/runs/38867635983)609ファイル／5991テストPASS（対象18）。[追加実機360](root-final-routes/org-member360-route-green.png)／[390](root-final-routes/org-member390-route-green.png)PASS、[ADMIN追加3リンク](root-final-admin-routes/admin-final-summary.json)も実画面PASS（招待390、権限グループ390、機能設定360） | R2-O1をroot同条件再現。[修正前360px](root-org-route/org-member360-route-red-closed.png)：URLは`?tab=2`、ダッシュボード1、メンバー名0 |

## 実行環境と未実施範囲

Playwright Chromiumのmobile/touch viewport（390×844、360×800）、日本語、実FE `localhost:3003`・実API `127.0.0.1:8082`・専用合成DB schema／Redis DB13で検証した。物理スマートフォンの実機試験ではない。ログインと事前fixture作成だけAPIを補助利用し、導線はUIから操作した。APIのmock応答は使っていない。

APIソースは分離したbase main `e12dc538ec21834b7e28905cfcc97900f5b40fdb`。Java21で起動後native crashが起き、最初のretryはtimeoutで不合格の履歴として残した。Java25でAPI回復後、同Drawerの再試行で権限／モジュール200とADMINメニュー復帰を確認した。故障誘発の通信条件とJava21停止が重なったため、実機失敗の原因を通信条件だけに限定できない。

既存32/36/40pxの小タップ領域は進行中の別戦役の関連観測とし、本件で重複修正しない。R3の重複権限エラートーストと28px閉じるは差分外の未確定観測。表内スクロールはページ横パンと区別した。課金リンクは既存MEMBER閲覧仕様をソース確認し、クリック遷移は自動承認レビュー拒否で未実施。

公開物は選択した小さなJSONと代表PNGのみ。ローカル認証設定、生成パスワード、Java補助、backend標準出力、raw runtime-events、全DOM／コンソール記録は含めない。
## 気づき箱との連携

[resident-overlay.json](resident-overlay.json)を既存形式で作成し、tracked `beta-inventory-board-data.js`の`b0RunOverlay`だけを限定更新した。他のtop-level値、既存カタログ／戦役／GitHubスナップショット／生成metadataは完全一致を機械確認した。全面棚卸更新は実施していない。

専用headless Chromiumの新規・非永続BrowserContextとOS割当の独自loopbackポートで、この隔離worktreeのHTMLを開いた。通常のページ初期化だけで既存「気づき箱」へ3住民12観測が入り、UIカード12件とIndexedDB12件を照合した。personaId／類型／journeyId／証跡／PNGを保持し、再読込後も重複なし。既存取込はoverlay登録runIdを保存するため、元探索runIdは各detail本文にも明記して保持した。[取込画面](board-import/normal-insight-import.png)を実見し、[要約](board-import/summary.json)に保存した。専用browser/contextとreadonly serverは終了済み。ユーザーの既存browser/profile/IndexedDBや共有本陣HTMLは使用していない。

公式validator `--self-test-overlay`全体は未合格。既存tracked生成物447戦役と現在正本498戦役が不一致で、全面生成時にもGitHubスナップショット39参照不足がある。[原因調査](board-generation-diagnostic.json)に記録した。これはoverlayの検証合格や通常取込確認とは別の既存同期制限であり、合格へ読み替えない。公式`validateRunOverlay`関数の単独実行では宣言整合と不正入力4類型の拒否を確認した。
追加Org導線は最終headで360/390pxとも通常トップからDrawerの「メンバー一覧」をクリックし、`/members`、Drawer0、合成メンバー2名とmembers API200を確認した。MEMBERの管理リンクは0のまま。実ログイン画面へ入力・送信して認証200→users/me200を確認し、この追加ケースはAPI補助ログインを使用していない。[追加要約](root-final-routes/member-final-summary.json)を参照。

専用NuxtのPostCSS読込停滞は旧Jiti1がnested NuxtKitのESM `import.meta.dev`をCJSとして評価する検証環境の問題だった。Node22の標準native preloadで同CSS146727bytesの読込に成功し、専用launcherだけを修正して回復した。製品コード・依存は変更していない。原住民のtimeout／未確認と当時のCI・regressionsのpendingは履歴として残し、追加root実機greenと分けて評価する。
最終head `25ece9b77159e117c631ddb82e88311bc7665570`の[正式検分](final-release-review-25ece9b771.json)はfinding0、必須CI成功、実画面再試験完了。追加ADMIN導線は通常Drawerから招待390px（一覧API200）、権限グループ390px（正常空一覧）、機能設定360px（catalog200・42 switches）へ到達し、いずれもDrawer0。画面ログインを使用し、3枚のPNGはroot視認済み。[最終回帰一覧](regressions-final.json)は6欠陥のred／green／実画面を対応付ける。core merge実体を最新origin/mainで確認し、台帳の対象1行へ完了証拠を反映した。
## テスト資源の後片付け

ユーザーの「削除してよい」という明示承認後、専用合成schema `mobile_menu_20261016`・専用appuser・Redis DB13の削除を完了した。別のread-only確認でschema不在・appuser不在・Redis DB13空を確認した。専用FE／BE／REPLは停止済み。この先行専用cleanupの段階では共有書込・outbox読取・Redis DB14への操作は行っていない。[最終sanitized結果の原値](cleanup-completed.md)を参照。

初期共有fixtureのテストteam1件・organization1件・pending user2名も、具体的な「削除可」の明示承認後に処理し、別read-only接続で確認した。team／organizationは通常DELETE API204による論理削除、pending userは既存cleanup相当の限定トランザクションによる論理削除であり、物理削除・即時匿名化ではない。pending userのstatus／合成PIIと将来reminder／purgeの通常フローは保持し、将来配送抑止は確認していない。

当初API helperのlogout200後の`completed=false`は履歴維持し、後続の固定cleanup用refresh1行の通常失効相当処理と別read-only最終確認で補完した。他セッション失効数は変わらず、全端末logout・user無効化・失敗後の新規loginは行っていない。通常login／logoutに付随するaudit・`last_login`更新はあり、配送有無は未検証。共有cleanup全体について「外部メールなし」とは断定しない。原値・範囲・確認結果は[cleanup記録](cleanup-completed.md)を参照。
当初の[cleanup承認待ち](data-cleanup-status.json)、[阻害記録](owned-cleanup-blocked.sanitized.json)、[当時の完了要約](completion-summary.json)に残る未削除・承認待ちは記録時点の履歴であり、現在状態は上記の完了記録を参照する。これらの原記録は書き換えていない。

共有worktreeのcleanup checkが他作業のdirty／locked／junction／稼働中資産を理由に非0（exit2）、削除0だった[保全証跡](worktree-cleanup-preservation.json)も当時の履歴として保持し、共有環境全体のゲートを合格へ読み替えない。他者資産は変更していない。