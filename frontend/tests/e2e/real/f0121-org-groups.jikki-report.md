# F01.2.1 組織がチームをグループ化 — 実機E2E 結果報告

- 戦役台帳: CMP-261011-0035（/実機）
- 実施日: 2026-10-20〜21
- 対象コード: origin/main `2c51ea237`（`9da2e465ba` との差分は文書のみ）
- spec:
  - `f0121-org-affiliation.real.spec.ts`（加盟: 設定・申請・承認・拒否・制限・招待・離脱・除名・権限・バッチ・API 契約）
  - `f0121-org-team-groups.real.spec.ts`（チームグループ: 作成・改名・並び替え・削除・単体/一括割当・絞り込み・申請/承認/招待のグループ）
  - `f0121-org-group-broadcast.real.spec.ts`（告知ウィザードのグループ宛て・表示・push・ジョブ）
  - 共通土台 `helpers/f0121-org-groups-world.ts`

## 実行環境

| 項目 | 内容 |
|---|---|
| BE | 検証用 `http://localhost:8081`（WSL ネイティブ clone `~/jikki-orggroups-20261020` を bootJar → `java -jar`、`MANNSCHAFT_ALLOWED_ORIGINS=http://localhost:3021,http://localhost:8081`）。DB は共有の `mannschaft-mysql`、Valkey は `mannschaft-valkey` |
| FE | 検証用 `http://localhost:3021`（同じ clone の `nuxt dev`。`NUXT_PUBLIC_API_BASE`・`NUXT_INTERNAL_API_BASE` とも 8081。BE の CORS 許可を 3021・8081 に設定） |
| Playwright | WSL 内で専用設定 `playwright-f0121.config.ts`（3 spec を 1 回の実行で流し、globalTeardown で後始末）、`BASE_URL=http://localhost:3021 API_BASE_URL=http://localhost:8081` |
| 向き先の裏取り | `ss -ltnp` で 3021・8081 が自分のプロセスであることを確認。各 spec の afterAll が「画面が呼んだ API のオリジン」を出力する（検証 BE 以外へ出た呼び出しはパス付き） |
| 本陣 8080/3000 | 使用せず・停止せず |

環境上の注記（製品欠陥ではない）:

- Windows 側 worktree（本陣 node_modules の junction）で動かした `nuxt dev` は、高負荷の共有機で 1 画面の水和に 40〜160 秒かかり、spec が時間切れで落ちた。Windows 側の `nuxt build` は 70 分を超えても終わらなかったため中止し、WSL ネイティブ clone で FE を立て直した（水和 10 秒前後）。
- 本陣 `node_modules` は main より古く `unicode-segmenter` が無いため、本番ビルドでは解決に失敗する（dev では該当ページを開かない限り顕在化しない）。clone では `npm install --no-save` で lock に揃えた。
- 3001 は途中から別セッションの検証 FE（`~/verify-friend3`、BE 8091 向き）が `0.0.0.0:3001` を占有しており、自分の FE は空きを探して 3000 で待ち受けていた。この間の実行は他セッションの FE・BE を画面が叩いていたため結果に採らず、FE を 3021 に立て直して全 spec を再実行した（下表はその最終実行）。他セッションのサーバーには触れていない。
- 前提作成の都合で、レート制限カウンタを Valkey 上で戻している（各 AC の対象はレート制限ではないため）。戻すのはテストに対応するキーだけで、パターンでの一括削除はしない: 登録（10 件/時/IP）は接続元 IP のキー `mannschaft:auth:register_attempt:127.0.0.1` だけ、申請（10 件/時/利用者）・告知送信（5 件/5 分/利用者）はテスト利用者の ID を固定した `mannschaft:rate:{zone}:u:{userId}:*` だけ。
- 利用者のメールアドレスは「spec の識別子・役割名・実行 ID」で決まる（例 `e2e-og-aff-xa-{RUN}@…`）。同じ実行で 3 spec を流しても重複登録しない。組織・チームの slug も明示的に一意な値を渡す。利用者の有効化はメール認証トークンが平文で取れないため `users.status` を直更新している。

## 実行結果（最終実行・Codex 検分 第1回の指摘対応後）

`playwright-f0121.config.ts` で 3 spec を 1 回の実行で流した結果: **28 passed / 3 failed（17.7 分）**。globalTeardown は「削除 21/21 件（途中削除済み 16 件）」で全件 204。

| spec | 結果 | 失敗の内容 |
|---|---|---|
| f0121-org-affiliation | 11 passed / 1 failed | ORGAFF-06 の soft 判定 1 件のみ（製品欠陥 D-1） |
| f0121-org-team-groups | 10 passed / 0 failed | — |
| f0121-org-group-broadcast | 7 passed / 2 failed | BC-07 の送信履歴の宛先表示（改名後 soft・削除後 hard。製品欠陥 D-2）、BC-09 のチーム指定（製品欠陥 D-3） |

失敗 3 件はすべて既知の製品欠陥（D-1〜D-3。別 PR で修正中）による想定どおりのもの。欠陥が直れば同じ判定が PASS に転じる。
画面が呼んだ API は検証 BE `http://localhost:8081` のほか、FE 自身の `/api/v1/security/csp-reports`（CSP 違反の報告先。FE 同一オリジン）だけ。

`test.skip` は無い。作成した組織・チームは作成時に記録し（`test-results/f0121-assets.jsonl`）、Playwright の globalTeardown（`helpers/f0121-global-teardown.ts`）が、テストの成否にかかわらず実行の最後に削除して 204 を検証する（1 件でも 204 以外なら実行を失敗にする）。テストの途中で削除するもの（`archiveScope`）も 204 を検証し、記録に削除済みと書く。

## AC ごとの結果

凡例: PASS / FAIL（製品欠陥）/ 未実施（理由は後述）。「UI」は画面操作で対象操作を行ったもの、「API契約」は AC 自体が API 直打ちの応答を定めているもの。

| AC | 結果 | テスト ID | 方式 |
|---|---|---|---|
| A01 A02 A05 L01 | PASS | ORGAFF-01 | UI（組織トップ→所属チーム→加盟の設定、管理コンソールのカード） |
| A03 A04 K01 | PASS | ORGAFF-02 | UI（XD・XM 直打ち→組織トップへ、未ログイン→/login）＋API 403/401 |
| A06 | PASS | GRP-01 | UI（グループ 0 件で「必ず選ばせる」保存→422 ORG_070・保存失敗表示） |
| A07 | 未実施 | — | |
| A08 A09 A10 A11 L02 B01 | PASS | ORGAFF-03 | UI（所属組織→加盟先を探す→受付中の組織→申請送信。TM・TD・N・未ログインはボタン無し） |
| A12 A13 | PASS | ORGAFF-11 | API契約 |
| B02 B03 B04 B05 B06 B07 B08 B09 B11 | PASS | ORGAFF-11 | API契約 |
| B10 B12 | PASS | GRP-10 | UI（REQUIRED でグループ未選択だと送信ボタン無効）＋API 400 TEAM_067 / TEAM_072 |
| B13 | PASS | ORGAFF-06 | UI（取下げ→XA の一覧から消える、25 時間ずらして再申請 201） |
| B14 | 未実施 | — | |
| B15 | PASS | ORGAFF-03 / ORGAFF-11 | UI（501 文字で「501 / 500」・送信不可）＋API 400 / 201 |
| B16 | PASS | ORGAFF-10 | API（前提の申請） |
| C01 | PASS | ORGAFF-04 / GRP-10 | UI（通知から着地して承認、グループ希望のまま承認） |
| C02 C03 | PASS | GRP-10 | UI（承認ダイアログで変更・未分類。監査ログに希望と確定） |
| C04 | 一部 PASS | ORGAFF-11 | (a)(b)(d) は API で観測。(c) は未実施 |
| C05 C06 P08 | PASS | ORGAFF-11 | API契約 |
| C07 C09 C10 C11 C12 C08 | PASS（D-1 あり） | ORGAFF-06 | UI（理由・ブロック付き拒否、制限一覧から解除、申請ダイアログで「現在この組織には申請できません」） |
| C13 | 未実施 | — | |
| C14 | 前半 PASS | ORGAFF-11 | 申請中チームの削除→行が消え XA に取消通知 |
| D01 D02 L06 D06 E02 E10 P07 | PASS | ORGAFF-08 | UI（除名、画面から招待、通知ベル→?view=invites→承諾） |
| D03 D04 D05 | PASS | ORGAFF-09 | UI（D05: 取消直後の再招待は画面で 403 とエラー表示、時計を 25 時間進めたあと画面から再招待→TA の受信一覧に出る。辞退＋今後受け付けない→再招待エラー表示→招待の制限を解除→取消） |
| D07 D09 | PASS | ORGAFF-11 | API契約 |
| D08 | 未実施 | — | |
| D10 | PASS | ORGAFF-11 / GRP-10 | API契約 |
| D11 | PASS | GRP-10 | UI（グループ付き招待→グループ削除→承諾→未分類） |
| E01 E09 | PASS | ORGAFF-05 | UI（離脱、XA が通知から所属チームへ着地）。E01 の「告知がダッシュボードに出ない」部分は未観測 |
| E03 K04 | PASS | ORGAFF-11 | API契約 |
| E04 E06 | PASS | ORGAFF-10 | 期限切れバッチ・3 バッチの `trigger?sync=true` は画面が無いため API |
| E05 | 未実施 | — | |
| F01 F02 F07 | PASS | GRP-02 / GRP-07 | UI（作成は末尾、同名 409 の文言、改名の保持更新、並び替え） |
| F03 | PASS | GRP-09 | UI（100 件目を画面で作成、作成ボタン無効と「100件まで」）＋API 422 ORG_066 |
| F04 L04 | PASS | GRP-01 | UI |
| F05 F06 F12 G06 | PASS | GRP-04 | UI（XM・XD・SYSTEM_ADMIN は割当 UI 無し、groups 直打ち→一覧）＋API 6 経路 403 |
| F08 F11 | PASS | GRP-07 | UI（2 チーム所属のグループ削除→未分類表示）＋修復バッチ |
| F09 | PASS | GRP-08 | UI |
| F10 | PASS | GRP-06 | UI（チーム側の所属組織に自グループ名のみ） |
| G01 G02 G03 | PASS | GRP-03 | UI（未分類で絞り込み、単体割当、一括割当 2 件） |
| G04 G05 G07 K06 | PASS | GRP-05 | API契約 |
| H01 L05 H15 H19 H26 | PASS | BC-01 / BC-02 | UI（組織内告知→G2 以前→掲示板。チームダッシュボードで T1・T2 のみ表示） |
| H04 H20 H23 | PASS | BC-07 | 表示は UI で観測（グループ移動・削除は前提として API） |
| H06 | PASS | BC-05 | UI（「該当する人がいません」で次へ無効） |
| H07 H08 | PASS | BC-03 | UI 送信＋ジョブ表と通知一覧の観測 |
| H09 | PASS | BC-04 | UI 送信＋通知一覧 |
| H11 H28（すべてのチーム） | PASS | BC-09 | UI 送信＋ジョブ表 |
| H13 | PASS | BC-06 | UI（XM の push 無し注記、ジョブ無し） |
| H05 H12 K05 H14b K07 | PASS | BC-08 | API契約 |
| H16 | 未実施 | — | |
| H17 | 一部 FAIL | BC-01 / BC-07 | 完了パネルの宛先表示は PASS（「宛先: G2 以前（並び順で先頭から）（送信時 2 チーム）」を全文一致）。送信履歴（`/dashboard` の組織タブ）の B1 の項目に限った宛先表示は、改名後・削除後とも FAIL（D-2） |
| H18 H28（チームを選ぶ） | FAIL | BC-09 | 画面からチーム指定の告知を送れない（D-3）。修正後は T1 宛てアンケートのジョブ・宛先集合・push と、T1 宛て掲示板のジョブなしを判定 |
| H27 | FAIL | BC-10 | XM が「チームを選ぶ」で T1 宛てアンケートを画面から送る手順が D-3 で宛先プレビューから先に進めない。修正後は「ジョブなし・T1 と XO に push なし・T1 にだけ表示」を判定 |
| H29 | FAIL | BC-11 | XA が「チームを選ぶ」で T1 宛て掲示板を画面から送る手順が D-3 で止まる。修正後は「T1 にだけ表示・T1 を G1→G3 へ移しても T1 に出続け、T3 には出ない」を判定 |
| H02 H03 H22 H24 H25 H30 H31 J01 J02 J03 | 未実施 | — | |
| K02 | PASS | ORGAFF-04 | UI（XM・XD の 4 view 直打ち→一覧） |
| K03 P01 P02 P03 P04 P06 P09 P10 P11 | PASS | ORGAFF-07 | UI（権限グループの作成とメンバー一覧からの割当、タブ・直打ちの可否） |
| P05 P12 | PASS | ORGAFF-12 | UI（割当解除→タブ消失・直打ち追い返し）＋API 403 |
| L03 | PASS | ORGAFF-04 | UI（通知ベル→?view=applications に着地） |
| M01 M02 | 未実施 | — | |
| N03 N14 | PASS | ORGAFF-11 | API |
| N02 N04 N05 N08 N09 N10 N12 N15 | 未実施 | — | |

## 対象操作の UI 証拠

| テスト ID | 開始 URL / 入口 | クリック・入力・送信 | 最終画面 / 永続化 | API 補助用途 |
|---|---|---|---|---|
| ORGAFF-01 | `/organizations/{X}` → タブ「所属チーム」→ `team-affiliation-settings-link` | 受付トグル、案内文、グループ選択「必ず選ばせる」、`settings-save` | 「設定を保存しました」、再読込で on・案内文保持、保持のまま再保存しても on | ログイン・前提 |
| ORGAFF-02 | XD・XM で `/organizations/{X}/settings/team-affiliation` 直打ち | — | `/organizations/{X}` へ戻され設定フォーム無し、未ログインは `/login` | 403/401 の確認 |
| ORGAFF-03 | `/teams/{T}` → タブ「所属組織」→ `discover-organizations-link` | 組織名で検索、カードの「詳細を見る」、`team-affiliation-apply-button`、チーム選択、添え書き、`apply-submit` | 「加盟を申請しました…」、チームの申請中一覧に X、DB は PENDING/TEAM_APPLY | ログイン・前提 |
| ORGAFF-04 | XA の通知ベル→通知一覧の `TEAM_ORG_APPLICATION_RECEIVED` | 申請行の「承認」→`approve-submit` | `?view=applications` に着地、承認後に所属チーム一覧に T、DB ACTIVE | 通知一覧の観測 |
| ORGAFF-05 | `/teams/{T}/affiliations` | `org-leave-{X}`→`org-leave-confirm` | 行が消え再読込でも無い、XA は通知から `/organizations/{X}/member-teams` へ | 通知一覧の観測 |
| ORGAFF-06 | `/organizations/{X}`（TA）・`?view=applications`/`restrictions`（XA） | 申請、拒否（理由・ブロック）、制限の「解除」、`/teams/{T}/affiliations?view=applications` の取下げ | 制限中は申請ダイアログで T が選べない、解除後 201、取下げ後 XA の一覧が空 | 時計を進める DB 更新・403 の確認 |
| ORGAFF-07 | `/teams/{T}/permission-groups` | `permission-group-create`・名前・対象ロール・権限・`pf-submit`、`/teams/{T}/members` の `member-permission-groups-{id}`→`pg-assign-submit` | TG が「所属組織」タブから画面に入れる、TM・TD（付与前）は直打ちで `/teams/{T}` へ | me/permissions の観測 |
| ORGAFF-08 | `/organizations/{X}/member-teams`（管理レンズ） | `team-remove-{T}`→確認「除名」、`invite-open`・検索・`invite-submit`、TA の通知ベル→招待行の「承諾」 | 「チームを除名しました」、招待行、`?view=invites` に着地、承諾後に所属組織一覧に X | 通知一覧の観測 |
| ORGAFF-09 | `?view=invites`（XA）・`/teams/{T}/affiliations?view=invites`（TA） | 招待、辞退（今後受け付けない）、再招待（エラー表示）、`?view=restrictions` の解除、招待の取消 | `invite-error` 表示、制限一覧「無期限（今後受け付けない）」、取消後 TA の一覧が空 | 時計を進める DB 更新 |
| ORGAFF-12 | `/teams/{T}/members` | TG の権限グループのチェックを外して保存 | TG の直打ちが `/teams/{T}` へ、タブ消失 | 403 の確認 |
| GRP-01〜10 | `/organizations/{X}/member-teams`（`view=groups` / 一覧）・設定画面 | 作成・改名・↑・削除、行の割当 Select、チェック＋一括割当、絞り込み Select、承認ダイアログのグループ変更、招待ダイアログのグループ | 一覧のグループ列、件数、再読込後の並び、DB `group_id` | 前提（加盟・グループ・割当の一部）・DB 観測 |
| BC-01〜09 | `/organizations/{X}` の「組織内告知」 | ステップ1（グループで選ぶ・範囲・個別・チームを選ぶ）→ステップ2（掲示板/アンケート）→ステップ3→「告知を送る」 | 完了パネルの「宛先: …（送信時 N チーム）」、`/dashboard` のチーム/組織タブでの表示、通知一覧、ジョブ表 | グループ移動・削除の前提、DB・通知の観測 |

## ロール横断

方式の凡例: **画面**＝そのロールのブラウザセッションで URL を開き・操作し、表示・追い返し先・文言を確認。**API**＝そのロールの API 呼び出しで応答を確認（画面と別に、BE 側の拒否を裏取りするもの）。

| 視点 | 確認内容 | 方式 | 結果 |
|---|---|---|---|
| 組織 ADMIN（XA） | 設定・申請一覧・招待・制限・グループ・割当・除名・告知（グループ宛て push あり） | 画面（観測に API・DB） | PASS |
| 組織 DEPUTY_ADMIN（XD） | 設定直打ち→`/organizations/{X}` へ戻され設定フォーム無し、管理 view 4 種の直打ち→一覧（切替・グループ管理無し）、割当・除名 UI 無し | 画面 | PASS |
| 同上 | 設定 GET 403、申請一覧 403、承認 403、グループ 6 経路 403 | API | PASS |
| 組織 MEMBER（XM） | XD と同じ画面確認、告知ウィザードで「push 通知は送られません」注記 | 画面 | PASS |
| 同上 | グループ一覧 200・変更系 403、グループ宛てアンケートでジョブ無し | API・DB | PASS |
| チーム ADMIN（TA） | 申請・取下げ・承諾・辞退・離脱・制限解除・権限グループの付与/解除 | 画面 | PASS |
| チーム DEPUTY/MEMBER（TD・TM） | 「所属組織」タブ無し、`/teams/{T}/affiliations` 直打ち→`/teams/{T}`、TD の `/teams/{T}/permission-groups` 直打ち→`/teams/{T}` | 画面 | PASS |
| 同上 | 申請・承諾 403、自己昇格（権限グループ作成・他者への割当・更新）403 | API | PASS |
| 付与 MEMBER（TG） | 付与後はタブから画面に入れる、解除後は直打ちで `/teams/{T}` へ | 画面 | PASS |
| 非メンバー（N）・未ログイン | 公開組織ページに申請ボタン無し、未ログインで設定画面直打ち→`/login` | 画面 | PASS |
| SYSTEM_ADMIN 単独 | 所属チーム画面で割当・管理 UI 無し、`?view=groups` 直打ち→一覧 | 画面 | PASS |
| 同上 | グループ 6 経路 403 | API | PASS |
| 他組織 ADMIN（YA） | X の `/settings/team-affiliation` 直打ち→「アクセスできません」の表示と `/organizations/{X}` への追い返し・設定フォーム無し。`/member-teams?view=groups/applications/invites/restrictions` 直打ち→view が外れ、切替・グループ作成・割当 UI 無し（GRP-04） | 画面 | PASS |
| 同上 | X の設定・グループ一覧 403、Y の slug で X の行を承認・PATCH・制限解除→不在と同じ 404 | API | PASS |

## 発見した製品欠陥

| ID | 内容 | 再現手順 | 証拠 | 重大度の見立て |
|---|---|---|---|---|
| D-1 | 組織の「申請の制限」一覧に、拒否理由コードの生値（`REJECTED` 等）がそのまま表示される | XA が申請を拒否（今後受け付けない）→ `/organizations/{X}/member-teams?view=restrictions` | 行の表示が「{チーム名}ブロック中REJECTED解除」。`MemberTeamsRestrictionsView.vue` は `restriction.reason` を自由文として描画するが、BE は列挙値（REJECTED/DECLINED/WITHDRAWN/CANCELLED）を返す | 低〜中（i18n 規約違反・利用者に意味不明な英字コード。拒否時に入力した理由文はここには出ない） |
| D-2 | 送信履歴（組織のお知らせ一覧）に「宛先: …（送信時 N チーム）」が出ない（AC-H17 後半） | XA が G2 以前で掲示板告知を送信 → `/dashboard` の「組織」タブのお知らせ一覧 | 項目は出るが `broadcast-audience-summary` が無い。ダッシュボード API の `orgNotices`（`DashboardService#toAnnouncementFeedMap`）が `target_audience` を返さず、FE の `toDashboardAnnouncementItem` も写さない。組織のお知らせ一覧はこの経路しか無いため、宛先表示に到達する画面が無い（完了パネルには出る） | 中（AC-H17 の一覧表示が画面から観測できない） |
| D-3 | 告知ウィザードの「チームを選ぶ」でチームにチェックすると宛先プレビューが 400 になり、「次へ」が押せない（チーム指定の告知を画面から送れない） | 組織の「組織内告知」→「チームを選ぶ」→チームにチェック | `POST /api/v1/organizations/{X}/broadcast/audience-preview` 本文 `{"targetRole":"MEMBERS_AND_ABOVE","targetTeamIds":["t1-…"]}` → 400 `COMMON_001`。FE（`BroadcastStep1Audience.vue` の `orgTeams`）が `GET /organizations/{X}/teams` の `id`（slug）を宛先 ID に使い、BE の `targetTeamIds` は `List<Long>`。数値 ID なら 200 | 高（AC-H18・H27・H28 後半・H29 と、既存の「チームを選ぶ」告知そのものが画面から使えない） |

### 対象外で見つけた欠陥（参考）

| ID | 内容 | 再現手順 | 証拠 | 重大度の見立て |
|---|---|---|---|---|
| D-4 | チーム作成で、名前から自動生成された slug が既存（削除済みを含む）と衝突すると 500 `COMMON_999` になる | 名前の英数字部分だけが同じ 2 チーム（例「重複甲 dupqXXXX」「重複乙 dupqXXXX」）を slug 指定なしで続けて作成（1 件目は削除済みでも同じ） | 2 件目が 500。BE ログに `Duplicate entry '…' for key 'teams.uq_teams_slug'`。F01.2.1 の範囲外のため spec は slug を明示して回避した | 中（日本語名のチーム作成で偶発的に 500） |

## スキップ・未実施

| AC | 理由 | Next step |
|---|---|---|
| A07 | 受付 off 後の既存申請の承認・拒否は今回の spec に入れていない | 申請2件→受付 off→承認・拒否の UI 手順を ORGAFF に追加 |
| B14 C13 E05 | 取下げと承認の逐次競合、アーカイブ前に開いた承認ダイアログ、組織アーカイブ時の PENDING 削除は未実装 | 2 文脈を並べる手順で追加 |
| D08 | 移行前の招待行は現データと区別できない（direction の既定 ORG_INVITE の行を承諾する経路は ORGAFF-08 と同じ） | 移行前データのフィクスチャを用意して追加 |
| E01（告知が出ない部分）・C04(c)・N02 N04 N05 N08 N09 N10 N12 N15 | 複数加盟時の読み手（投稿・行事・予算・試合など）は今回の対象画面外 | 読み手ごとの spec を別に起こす |
| H02 H03 H16 H22 H24 H25 H30 H31 | 時間の都合で範囲の別パターン・SUPPORTER・XD2 などは未実施 | BC に追加（XD2 は組織の権限グループで MANAGE_CONTENT を外した DEPUTY を作る） |
| H18 H27 H29（と H28 のチーム指定） | 製品欠陥 D-3 により画面から送れない（UI 検証は BC-09・BC-10・BC-11 として実装済み） | D-3 の修正後に BC-09〜BC-11 を再実行 |
| J01 J02 J03 | 範囲テンプレートは未実施 | テンプレート保存→グループ並び替え・削除→適用の UI 手順を追加 |
| M01 M02 | 移行直後の状態は共有 DB では再現できない（以降の行と混在） | 移行の IT（FlywayFromScratchMigrationTest 等）に委ねる |
