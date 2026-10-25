# CMP-261024-0214 最終検証記録

> 実装PR #4080・#4081はマージ済み。機能ACと出荷確認は完了。検証DB・限定grantの削除はユーザー選択待ちのため保全中であり、完全cleanup済みとは扱わない。

## 対象と出荷状態

- CMP: `CMP-261024-0214` — チーム掲示板の本文表示修正とスレッドカード一覧化。
- core PR #4080: MERGED。merge commit `f0646693b229a21cb58ae092c4511217c43b13b4`。tested head `64e3921cc823cff9030888404f01d62dc03588d6`。
- core CI: run `40006697022` の全6 backend shard・集約checkがsuccess。FE CI `40006696952`、Frontend E2E `40006696998` もsuccess。管理者権限は未使用。
- FE followup [PR #4081](https://github.com/kenta-0420/mannschaft/pull/4081): MERGED。merge commit `24366134d57c502f1a54e186f119fc2c3ee127df`。tested head `442533cba0d3d300efd06e09f6104237ad8b7a55`。FE 3ファイルのみ。実機済み3ファイルのgit blobはtested SHA `1c08c8d86a464b56769b494e37e542d26f82ce00` と一致（root確認）。最終CIは下記のとおり成功し、管理者権限は未使用。
- 追補のローカル検証commitは `98cf45d061b0735d8281d809d0da2eda86c68fab` と `1c08c8d86a464b56769b494e37e542d26f82ce00`。coreマージ後にFE-onlyブランチへcherry-pickし、最新mainの台帳1行だけを追従した。3ファイルのgit blob一致により、実機済み内容と最終headの同一性を確認した。

## 受け入れ条件と証拠

| AC | 条件 | 証拠・結果 |
|---|---|---|
| AC1 | 投稿直後と再読み込み後の両方で投稿本文を表示する | core実機E2E成功。followup実機2件も成功、skip 0。rootが画面画像を確認。 |
| AC2 | 返信APIのauthor契約をFE型と一致させ、返信本文・投稿者を表示する | API DTOの契約不一致（Issue #3431）を修正。BE red/green試験と実機E2Eで確認。 |
| AC3 | スレッドカード一覧に本文プレビューを表示する | core実機E2E成功。プレビュー表示と不要な追加network requestがないことを確認。 |
| AC4 | 390/768/1280/1600pxで1/2/3/4列を表示する | core実機E2E成功。rootが4幅画像を直接確認。 |
| AC5 | 空一覧・取得失敗・権限境界を適切に扱う | `data=[]` unit green（空一覧の独立確認はunitで行い、実機では未確認）。実ネットワークofflineでrequest failure後は空状態を誤表示せず共通接続案内を表示し、online後UI再試行で4件回復。権限外URLでは画面内に共通拒否表示、業務APIは403。権限境界はcore実機E2Eとscope探索で確認。 |
| AC6 | 詳細画面の主要操作を読みやすくし、タッチ領域を44px以上にする | 追補red 1 failed → green 1 passed。360/390/1280pxで主要5操作が44px以上かつ各1行。関連FE unit 26成功。操作領域・通信断回復などは実ブラウザ2件成功/skip 0で確認。lint成功。rootがgreen 360px画像を確認。 |

### BE / FE 検証詳細

- 返信author契約修正前: 対象8件のうち6 assertion failed、2成功、skip 0。
- 修正後: 対象7クラス計108成功、skip 0（Mapper 13 / Author IT 8 / Controller 23 / GlobalController 12 / GlobalWrite 16 / ReplyService 14 / GlobalService 22）。Arch 37成功、freeze 2成功、skip 0。JUnit XMLはrootが直接確認。
- FE追補red→green: mobile detailのactual DOM assertion 1件が修正前に失敗し、修正後成功。関連FE unit 26成功。タッチ領域の寸法・1行表示と通信断回復は実ブラウザ2件成功、skip 0で確認。lint成功。
- FE実機追補の通信失敗は実requestをofflineで失敗させ、共通接続案内が出ること、online復帰後に同じUI操作から4件再取得することを確認。空一覧表示には落ちない。

## 住民探索

- desktop / mobile / scope の3住民を**並行起動**し、**別ログイン**で探索した。
- desktop archetype: `一般×素直`。mobile: `スマホ片手操作×表示崩れ`。scope: `一般×隙間狙い`。
- mobile探索で本文の投稿/返信、長文・長いURL、360px詳細表示と主要操作の崩れを観測。scopeでは自teamが成功。権限外URLは画面内共通拒否表示、業務APIは403。rootがsource、JSON、実画像を直接検分した。
- 保全artifact: `.claude/artifacts/team-board-20261024/`。最初の住民証跡は `manifest.json`、final ACは `final-ac-manifest.json` と `team-board-final-ac/`。実装差分比較は `followup-source-comparison.json`（root保全・3ファイルblob一致を直接確認）。
- ローカルoverlayは未生成データ反映ドラフト。既存 `validateRunOverlay` 関数単体はaccepted。公式全体validatorはCMP正本行数/参照抽出数の既存整合gateで停止し未合格。生成data.jsへ未反映。B0 runner実行・coverage合格を主張しない。

## DB・権限・テストデータ

- 実運用データは使用していない。共有MySQLの既存業務DBのデータは変更していない。runtimeは初回 `CREATE DATABASE IF NOT EXISTS` を実行し、作成後table数0を確認したが、事前不存在だったことまでは証明できない。共有mysqlへ追加したgrantは `ALL PRIVILEGES ON mannschaft_board_20261024.* TO mannschaft@%`（GRANT OPTIONなし）。共有MySQLのGLOBAL設定・既存業務DBへの権限は未変更。上記の検証DB限定grantは追加したまま保全。
- 元DBとdump footer時刻を除くhash一致を確認して以後不変。dump (`/tmp/mannschaft-board-20261024-before-move.sql`, 1,208,698 bytes, SHA256 `b62413e1b062b5cd3a1dd91094f4396750b0826a536bdda5d8cfe991ada11dd0`) を保全。専用port 3307へのrestore実成功後、migration 1224完走。
- 投稿 thread 5/6/7 と reply 2/3/4、fixture team 4/5 とmember参加記録はAPI cleanup済み。
- 技術的保全事項（機能ACとは別）: shared側の専用DBとgrantのDROP/REVOKEは自動承認レビューがユーザー明示承認未確認として拒否したため未実施。rootは保全/削除のasync選択質問を出しており回答待ち。したがって完全cleanup済みとは記録しない。専用Docker/volume、owned tmp 24件、秘密設定・固定jar・279MB archiveは削除済み。FE 3001所有5 PIDを停止。runtime最終確認でBE 41900プロセス消失、8081はWindows/WSLとも待受なし。残置は専用DB・grant・保全dump・keepalive PID 1581。keepaliveは他作業稼働への影響を避けて残置。rootがWindows側でGet-NetTCPConnectionを確認し、port 3001/8081 listenerはいずれも0件。backend cleanup evidenceは `.claude/artifacts/team-board-20261024/backend/cleanup-evidence.json`。

## 未解決の観測（バグ判定・完了条件には算入しない）

- navigation tabsの高さ38pxという観測は今回未修正。
- checkbox内部20px領域のhit label操作は未確認。
- hydration時の一時的な描画欠けは再現していない。以前の観測だけで不具合と断定しない。
- AC6は今回追補済み。上記の未解決観測はこのACの失敗とは扱わず、別途確認事項として残す。

## CI最終結果

- 旧head `1e2dd6f92e5916a6d0fa0ce24a423c391a4c256c` の全21 checksはsuccess 14、条件付きskip 7、failure 0、unfinished 0（Frontend CI `40019164505`、E2E `40019164410`、Lighthouse `40019164609`）。これは旧headの履歴証拠。
- 最新head `442533cba0d3d300efd06e09f6104237ad8b7a55` のrunはすべてcompleted / success: Frontend CI `40022098910`、Frontend E2E `40022098971`、Lighthouse `40022098881`。各runのhead SHA一致を確認。
- 最新headの全22 checks: success 15、workflow条件付きskip 7、failure 0、unfinished 0。skipはBackendの変更条件外テスト群、main push限定Docker、週次full history等。
- PR #4081 はMERGED。rootがrunのcompleted / successとhead SHA一致、全22 checksの終了、通常squash mergeを独立確認した。core PR #4080とfollowup PR #4081はともにmainへ反映済みで、最終反映commitは `24366134d57c502f1a54e186f119fc2c3ee127df`。管理者権限による迂回は行っていない。
