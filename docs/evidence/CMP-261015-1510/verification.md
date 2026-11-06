# CMP-261015-1510 検分・実機・探索証跡

実装PR [#4133](https://github.com/kenta-0420/mannschaft/pull/4133) はhead `0042f17ea50daf539e1c16ddfc0d123ecbc0726b` で2026-10-26T11:01:44Zにマージ済み。merge commitは `bd573ee5c26c341d5d55cd11ad500b74977b945d`。同headのCIをrootが独立確認し、製品・実機・3住民の検分結果をもとに完了登録する。

## 変更範囲とソースの同一性

製品PRの変更は `frontend/app/pages/login.vue`、実機spec、仕様書の3ファイル。実装は補助3リンクのmobile限定CSSで、入力欄は既存global CSSの44px指定を利用する。desktopの入力42px・送信44pxを維持する。

実機は2026-10-26、frontend base `13c6cc3d4ad5f3952f8f51e5715e4c6b05a69dc6` に `c717d0ac54695df915879aa28f6123cfbc9c363b` のlogin.vueと実機specを適用した専有環境で実施した。製品SHA256は `25a9d91a8853e6375d8611ab4ab5611ad516c6beada10d341fd533700a41f8e0`、spec SHA256は `75c55f350da0bbcafb6f6f045ccb095a9d0b3037d5a9494a35d7f1697ad58f42`。frontend app 3,071ファイルをGit blobと比較して不一致0（NULなしUTF-8テキストはCRLF→LF正規化。全ファイルのraw-byte一致ではない）。

main PR #4049追従後の0042と実機c717はfrontend全treeとspecが一致する。実機のbackend sourceは `2d17eff087ad59e5b354caf2808f81ec4f52b1d6`、実行jar SHA256は `a7e3d57baeee6e811a862699a317502ae5ea6747bc28c142761b8b87f2469457`。0042のbackend treeは実機時と異なる。#4049の認可service interface移設は既存CI全6shard・ArchUnit・配線契約の成功をrootが別途確認している。c717実機を新版backendで再実行したとは記さない。

非秘密の集計・20case名・12条件の5操作bbox・source proofは [実行集計](verification-summary.json) に記録した。private原本の場所は本陣 `.claude/runtime/october-login-1510-20261026/`。巨大raw、資格情報、認証済み画像はコピーしない。

## 受け入れ条件と実測対応

実機specは `frontend/tests/e2e/real/login-touch-targets.real.spec.ts`。正式結果は20 expected / 0 unexpected / 0 skipped / 0 flaky、exit 0。開始は2026-10-26T07:58:18.452Z。同case寸法GREENも1 expected、他0、exit 0。

| AC | 条件と確認内容 | 正式case |
|---|---|---|
| L1 | 360x800・390x844 × ja/en/de × 案内有無の12条件で、email/passwordの実input、ログイン画面の「←トップページへ」、再設定、登録の5操作bboxの幅・高さが各44px以上。最小98x44px。desktop768/1280ではinput42px・submit44px。 | mobile寸法12 + desktop2 |
| L2 | 同じmobile12条件で文字矩形の収まり、中心hit、document座標の重なり、横パンなし、縦スクロールによる操作到達を確認。 | L1のmobile12と共通 |
| L3 | 補助3リンクを360/390で各1caseずつ自然tapし、トップ・再設定・登録の実URL遷移を確認。 | 2 |
| L4 | MEMBER・SYSTEM_ADMIN・別組織MEMBERを別contextで実ログイン200、実app header、表示切替、認証済みlogin直打ちのguest guardを確認。MEMBERは不正認証400かつAUTH_001→入力保持・通知→正しい認証200まで確認。 | 3 |
| L5 | 空email/password、不正email、password空でHTML検証によりlogin POST 0。既存3方式・MFA・busyは既存UTを最終0042のFrontend CIで別確認した。 | HTML1 + 既存UT（0042 Frontend CI成功） |

正式20件は14寸法（mobile12・desktop2）+ 自然tap2 + HTML1 + ロール3。CIの既存UTはこの20件に含めない。

匿名layout画像12枚はresults.jsonのbase64添付からrootが復元した。rootが実画像を確認した代表01（ja360案内なし）、06（de360案内あり）、12（de390案内あり）の3枚だけを公開する。全12枚を人が目視したとは主張しない。[画像由来・SHA256](anonymous-png-index.json) を参照。

| 画像 | 条件 |
|---|---|
| [360px ja](screenshots/login-360-ja.png) | 360x800、ja、案内なし |
| [360px de](screenshots/login-360-de-notice.png) | 360x800、de、案内あり |
| [390px de](screenshots/login-390-de-notice.png) | 390x844、de、案内あり |

## RED・旧試行と最終GREEN

旧RED `formal-worker-acd-r2/dimension-red` はspec source `acd2743e3b6be7a60bc0747906d37e78c2cd63fd`、旧main `17cab5899b86216190b2c13d31c19812d88593d5` のlogin.vueを専有copyへ適用した対照試験。旧Git blobのSHA256 `0a4db57906fc548f047a16394a86dab1cb2e713ebe0d0aca3315fbebe86e6e01` が実行記録のredProductSha256と一致することを確認した。RED後は元13c製品bytes `6f457c4e7ab727f134f21b5a6592faf89df9af4887bda25ca59118d8a744854a` へ復元して旧同caseをGREENにした。最終c717の製品SHA256 `25a9d91a…` と最終specの正式20件は、その後の別実行である。

- 旧CSS REDの失敗はログイン画面の `a[href='/']`（「←トップページへ」）の高さ20px対要求44px。トップ画面のloginリンクではない。入力のassertは通過している。最初の失敗assertで停止したため、復旧リンク20pxをこのREDで測定したとは記さない。最初のgrepアンカー不一致0件はREDに含めない。
- 元bytesへ復元した同360px日本語・案内なしcaseは1件GREEN。正式20件もすべて成功した。
- 13c CI Smoke run `40284942508` は99 pass / 2 fail。desktop入力42px維持が44px化で壊れた回帰であり、最終実装は冗長input指定を撤去し補助3リンクmobile限定に直した。既存desktop期待値を緩めていない。
- 13c/acdの旧実機15 pass / 1 fail / 2 skipは、テストの不正認証期待401に対し既存契約が400だった別の失敗。最終specは400かつAUTH_001へ厳密修正し、400/401双方許容やbackend変更で通していない。
- c717のFE CI `40288354270`、Lighthouse `40288354416`、Smoke `40288354312` は成功をroot確認済み。これらは0042のCIではない。0042の成功runは下記CI記録とJSONへ登録済み。

## 最終headのCIとマージ

rootがGitHub workflow-runs/check-runsとpull_request_targetを独立確認した [CI記録](ci-summary.json) に基づく。以下はいずれも0042に対応するcompleted/success。JSONのci配列はこの成功runだけを収録する。

| 種別 | run |
|---|---|
| Frontend CI | [40305628270](https://github.com/kenta-0420/mannschaft/actions/runs/40305628270) |
| Smoke E2E | [40305628263](https://github.com/kenta-0420/mannschaft/actions/runs/40305628263) |
| Lighthouse | [40305628387](https://github.com/kenta-0420/mannschaft/actions/runs/40305628387) |
| Backend CI | [40305628186](https://github.com/kenta-0420/mannschaft/actions/runs/40305628186) |
| FE lockfile | [40305628173](https://github.com/kenta-0420/mannschaft/actions/runs/40305628173) |
| OpenAPI Drift | [40305628471](https://github.com/kenta-0420/mannschaft/actions/runs/40305628471) |
| API Drift | [40305628314](https://github.com/kenta-0420/mannschaft/actions/runs/40305628314) |
| gitleaks | [40305628545](https://github.com/kenta-0420/mannschaft/actions/runs/40305628545) |
| Harness | [40305628215](https://github.com/kenta-0420/mannschaft/actions/runs/40305628215) |
| Worktree cleanup guard | [40305628217](https://github.com/kenta-0420/mannschaft/actions/runs/40305628217) |
| 最新Task-List Gate | [40305645334](https://github.com/kenta-0420/mannschaft/actions/runs/40305645334) |

旧Task-List Gate `40305625464` はcancelled。後発success `40305645334` と区別し、ci配列へ入れない。

BackendのCompile & Testはsuccessだが、test shard matrix、coverage guard、Non-JST、ArchUnit、D-5 guardはdiffによりskipped。これらを実テスト成功数やフルシャード合格へ数えない。#4049側の既存全6shard等CIと今回のdiff-skip、c717/BE2dのブラウザー実機はそれぞれ別の根拠である。新版BEで実機をしたとの主張は追加しない。

mainのPR #4138による台帳1行追加で再BEHINDとなり通常マージが拒否された後、ユーザーがPR #4133/head0042への `--admin` を明示許可した。rootはhead・CI・MERGEABLE・先行差分を再確認してadminマージし、MERGED・merge SHA・時刻とmain反映を独立確認した。この許可は実装PR限定で、完了登録PRは通常マージを使用する。

## 独立住民探索（すべて未実証候補）

3名は全員c717の画面で探索し、各人のログイン成功を確認した。以下8件は発見時点の画面・導線・条件に限った記述で、原因や仕様違反は未確定。スクリーンショットはローカルruntime内のprivate資料で、公開せずpathだけを記す。代表匿名画像3枚は実機結果JSONのbase64から復元してrootが目視確認した。12画像すべてを人が目視したとは主張しない。

| ID | persona / journey / locale・viewport | 観測候補 | source / private image |
|---|---|---|---|
| LOGIN-R1-OBS-1 | LOGIN-R1 / LOGIN-J1 / ja・360x800, 390x844 | 復旧画面の「ログインに戻る」は98x20px。タップ遷移は成功。 | `residents/R1/summary.json`、`residents/R1/04-recovery-360.png`, `05-recovery-390.png` |
| LOGIN-R1-OBS-2 | LOGIN-R1 / LOGIN-J1 / ja・360x800 | トップのlogin導線は56x20px。タップ遷移は成功。 | `residents/R1/summary.json`、`residents/R1/01-initial-360.png` |
| LOGIN-R2-OBS-1（原ID R2-N1） | LOGIN-R2 / LOGIN-J2 / ja・390x844 | メール入力後にトップへ離脱し、ブラウザー戻るでloginへ戻るとメールが空だった。別タブを開閉した試行ではメール保持を確認しており、別事象。 | `residents/R2/report.md`; private `02-email-before-interruption.png`, `04-top-interruption.png`, `05-browser-back-login.png` |
| LOGIN-R2-OBS-2（原ID R2-N2、対象外参考） | LOGIN-R2 / LOGIN-J2 / ja・390x844 | SYSTEM_ADMIN画面の外部GitHub詳細linkが112x32pxだった。ログイン画面外でlink未操作。 | `residents/R2/report.md`; private `14-achieved-stable.png` |
| R3-O1 | LOGIN-R3 / LOGIN-J3 / de・360x800, 390x844 | `/forgot-password` は `lang=de` だが本文・入力ラベル・送信・戻るlinkが日本語。説明理解は未達。 | `residents/R3/report.json`; private `02-recovery-360-de.png`, `03-recovery-390-de.png` |
| R3-O2 | LOGIN-R3 / LOGIN-J3 / de・360x800, 390x844 | 復旧画面の戻るlinkは98x20px。横パン・文字切れなし。 | `residents/R3/report.json`; private `02-recovery-360-de.png`, `03-recovery-390-de.png` |
| R3-O3 | LOGIN-R3 / LOGIN-J3 / de・390x844 | トップlogin文字linkは69x20px、menu内linkは279x44pxで遷移成功。 | `residents/R3/report.json`; private `05-home-390-de.png`, `06-home-menu-390-de.png` |
| R3-O4 | LOGIN-R3 / LOGIN-J3 / login de → dashboard ja | login後に `lang=ja` とdashboard UIが観測された。個人設定との関係は未調査で不備と断定しない。 | `residents/R3/report.json`; private `08-filled-login-390-de.png`, `10-arrival-settled-390.png` |


## 撤収と検証限界

専有FE/BEプロセス、copy、jar、tools、DB/Valkey各containerとvolume、holderを所有確認して撤去済み。専有listener残0。共有frontend node_modulesと他ownerの資産は維持した。秘密env5ファイル撤去、既知秘密値の残存scan 0。raw/proof/report/PNGは監査用private原本として保持する。

共有verification checkは他owner copyがあるためexit 1であり、全体のclean合格とはしない。今回ownerの資産撤去は個別証拠で確認した。原本ready.jsonは過去の稼働記録で、現在の稼働を表さない。

新規登録・メール送信・外部OAuth・パスキー登録削除は実行していない。住民観測8件は同条件での原因検証をしておらず、対象5操作の回帰と断定しない。最終headのCI・実装PRマージを確認し、全stageをpassedとして記録した。


住民探索と正式試験はヘッドレスのmobileエミュレーションで、物理端末のOSキーボードは未確認。入力値は撮影時にmaskしており、匿名画像から長い入力文字列の視認性は判定しない。
