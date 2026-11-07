# CMP-260917-1351 完了証拠

実装PR [4368](https://github.com/kenta-0420/mannschaft/pull/4368) はhead `838be3334e162c6e684b8fc9c31aad468c8d8ad2`、2026-11-07T14:25:33Zに通常merge済み（merge `af2dc66cd2f925193f67fd7161541aef267ae9d1`）。[CI 42379474193](https://github.com/kenta-0420/mannschaft/actions/runs/42379474193) はexact headでsuccess。

## 正本ACと実装・検証

| AC | 実装 | green根拠 |
|---|---|---|
| 1 管理者/メンバーレンズにSidebarが追従 | 原PR3319の親レンズbinding→ScopePageShell→OrganizationSidebar→BaseSidebar | 実機r3のADMINレンズ操作でSignage入口件数1→0→1。R8 ADMINでも招待/権限等のタブ差を通常操作で観測 |
| 2 MEMBERの管理16ページ直URLをFEで防御 | 原PR3319のguardと既存管理コンソール防御。追加PR4368でinvitesにadmin-console、permission-groupsにorg-role-guardを結線 | 実機r3 MEMBER16経路すべて退避。unit12件green。実機r6のforeign利用者はbudget/invites/permission-groups/adminの4経路がOrgAトップへ30秒以内に退避 |
| 3 Webhook scope-idを数値化 | 原PR3319のscope route同期・数値ID props→useWebhookApi | 実機r3でOrgA/OrgB各3本、計6本のUI発行GETが200。数値scopeIdを照合 |

原実装は [PR3319](https://github.com/kenta-0420/mannschaft/pull/3319)、merge commit `86434de78516faf03fa16e1339a591359e2c74da`。今回の追加修正は正常に解決した未所属null境界であり、既存watchや権限を一般化・拡張していない。invitesはADMIN/DEPUTY_ADMINを許可、permission-groupsはADMINのみを許可し、MEMBER拒否・取得失敗503を維持する。

AC1/AC3の根拠は実在する私有実機試験である。自動単体テストがあると捏造せず、今回の公開単体試験は [admin-tabs-access.spec.ts](https://github.com/kenta-0420/mannschaft/blob/f48f83dea0b33e6240110d7b42f5785370238420/frontend/app/pages/organizations/%5Bslug%5D/admin-tabs-access.spec.ts) の12件として区別する。

## null境界のredとgreen

- 修正前実機r5では、正常foreign利用者のOrgA権限metadataはHTTP200・roleName=null・scopeAdmin=falseだった。budgetは退避したがinvitesは30秒後も同ページに残った。metadata200を管理認可許可と読み替えていない。
- test-only source `721781921a1d400259e7312a43569114704741ed` の公式runner結果は12件中10pass、invites/permission-groupsの指定2件だけがgenuine assertion red。exit1、pending/todo/skip0、runtime0、reportErrors0。ENOENTだった旧unit実行はこのredに含めない。
- fix source `0f9e473d2d7ec1c12fe0041704156ee048bdad9f` では同12件すべてpass、exit0、failed/pending/todo/skip/runtime/reportErrors0。正常ADMIN/招待DEPUTYの許可、権限グループDEPUTY/MEMBERの拒否、読込前nullと正常解決後nullの区別、取得失敗503を含む。
- 修正後実機r6は9段階pass、foreign4経路すべてOrgAトップへ実退避。pageErrors/blockedWrites0、postguard成功、API/context/browserのcloseはすべてfulfilled。rootがNode/Chrome世代不在を独立確認した。

## 実機・探索の原本照合

原本は私有のまま保持し、ここへ認証値、メール、実slug、raw body、PNGをコピーしない。以下は匿名の役割・件数と原本SHA256だけを記録する。

| 原本識別子 | 結果 | SHA256 |
|---|---|---|
| cmp1351-ui-r3-once.private.json | 31/31段階pass、pageErrors0、blockedWrites0、postguard成功 | `91b943fed3b26193cc217afb3400326cf4b2d225dc7689762885acfaf7a8e167` |
| cmp1351-ui-r5-once.private.json | invites未退避の有効red。未到達経路を合格換算しない | `a120288c0a61078002e8619143cd9c4a496269bfa6747c4be96f78cfab0d9c34` |
| cmp1351-ui-r6-once.private.json | 9/9段階pass、foreign4/4退避 | `854fa831e5c41c29bc9303fa5047d93a90785fc10ca198ab60487b912c792770` |
| red classification.json（source721、r2） | 10pass/2指定assertfail、validRed=true | `720e7c87bd7cd4cdbdfe8f50bc56b4a3e5b67268c2b8865bfe25b82a0d867e3f` |
| green classification.json（source0f9） | 12pass、validGreen=true | `d004cb2b6ce73736c9193db3f743692e642ff01ea021d10691dfad49d7d211c5` |

R8の独立fresh3役割は各1回の正常loginとusers/me一致、closed/browserProcessesGone/pendingWaitersAllSettledすべてtrue。未分類API write・appRuntimeErrors・blockedWrites・previewOpensは3役割とも0。ADMINは通常レンズ切替による管理タブ差、MEMBERは運営導線非表示、DEPUTYは通常role適用と復帰をrootが確認した。探索passedは1351に関係する観測・閉鎖・root裏取りを意味し、全操作成功・全目的達成・製品無欠陥を意味しない。

commandFailureCountはADMIN/MEMBERが0、DEPUTYが1。DEPUTYはgeometry操作の必須name欠落で1回失敗し、次の操作は成功した。foregroundの終了codeはADMIN/MEMBERが0、DEPUTYが1（root実行15700=0、74695=0、54347=1）であり、3役割すべて終了0とは記録しない。rootはformal3とsmoke2の全5 Nodeが不在であることを別途独立確認した。

既知CSP eventはMEMBER/DEPUTYで各1件あり、productCspQualityPassはfalseである。appRuntimeErrors0や機械的閉鎖成功からCSP無欠陥を主張しない。これは1351の3ACに限定した受け入れと区別して保持する。

| R8役割 | resident-observations.private.md SHA256 | closed.private.json SHA256 |
|---|---|---|
| ADMIN | `6d5431438ad5c10990c6e361bbff8da0299f1cb6c0fc8530ecef13dd3e9f3e2b` | `f22a4d5a4e6d53f73775731f0df9ecd2a44c9056208ddaa45b144598f00be4ce` |
| MEMBER | `b58aaa6c0a9d7367f6073660dd47fae972a3af78b8c47bee597930297b2382df` | `ff6136b7a84395f5c494b6aa62b5acb529ee83ff0075d2a698f3b1596b974885` |
| DEPUTY | `ce36d4669face3914ae7738e9b1dab523dbea4230dbf59d6dbf691e90fa0bb49` | `d6b528c373b510b5b4df69ecc0aa8d189944e4c9c596ee91d15ba30d314e58a9` |

Archive初期previewは自動承認審査がcommand設置前に即時変更リスクで拒否した。未送信・迂回0で、AC外かつ成功件数外。既に拒否された旧14観測・6PNGの公開も今回の対象ではない。DP予算widget403、TeamDashboard no-op、TeamSearch label等は別scopeの未確定観測として保持し、1351や1606/1609の完了へ転用しない。

## sourceの橋渡しとCI

旧実機r3はFE `2a18c86dc1a36541bf49cb50752c15ef3a944fbe`、r6/R8はFE `7de2005ec90237da4bf560742156f78a18fda703`、BEは `83c98637f0e75f293d5640b35707b26d3848896a`。レンズ/既存guard/Webhookの対象blobは維持し、r6は追加した2ページmetaを含むsourceで検証した。旧0f9からf48の追加修正4pathsは同一で、main追従差分は台帳だけである。その後のf48から現ffcへの差分は `LoyaltyPointsWriterIT.java` の9行だけで、同4pathsのblobは引き続き同一。以後838までの差分は台帳だけで、4featureblobは採用後も同一。公開実装の識別は838とし、本文のf48固定ソースURLは同一blobの参照として区別する。1351の呼び出し元・認可依存を照合した範囲でcomponent証拠を限定再利用する。whole treeや公開merged head実機一致は主張しない。

[Frontend CI 42379474193](https://github.com/kenta-0420/mannschaft/actions/runs/42379474193) はexact head838でcompleted/successを一次確認した。マージ時点の全23checkはroot一次確認でSUCCESS16・SKIPPED7、失敗/未完了0。旧f48/ffcのCIとは区別し、SKIPPEDを個別試験の実行成功へ換算しない。

reviewはrootによる正本AC・source・unit red/green・実機r3/r6・R8観測と閉鎖の照合を根拠とする。実装PRの通常mergeと記録head・マージ時点checkの照合は完了した。ローカルvalidatorの構造検査と、実GitHubのマージ時点check世代・CI照合を分離して確認した。

ローカル準備ではbase `fb354245bcd7c09f8d4a88304404072d76d55ca7` のtrusted validator（blob `a952f2cd46c29e73f492b50e5b2c6a5d3a914970`）を使用した。未commitの作業ファイルを `validateEvidence` / `validateCompletionChanges` へ渡し、構造・参照先・台帳の対象1行だけの変更を確認した。一方、一次確認した `merged=false` を同じvalidatorへ渡した検査は未mergeとして拒否された。これは旧候補段階の正当な拒否履歴であり、実装PRの現在のmerge済み事実へ更新しても消さない。その後、実装merge・CIと次のPhase7 r2裏取りが揃った。git refを読むCLIを未commitのHEADへ実行して「差分なしの合格」とする方法は採っていない。

## Phase7 気づき箱の正式記録

匿名4件を正式local-sourceへ保存し、Linuxの独立ボードUI r1成功をrootが確認した。写真参照をdirからexact fileへ具体化したmetadata補正r2も一回の独立UI検証で成功（root foreground69650終了0）。DOM4件可視、IndexedDBのsource fields完全一致、trusted=false・observed=unverified、reload後の追加0、全Chrome不在を確認した。network/API write/PDF/download/pageerror/consoleerrorはすべて0。rootは4カードとexact PNG参照表示、および元画像とviewportの対応も私有範囲で目視確認した。

最終r2 proof SHA256は `35db51eaa89ced5838fc21d032d266593a1a90262b296f1c24f569c3a8785ff0`、canonical overlay SHA256は `b376080b910e1f8d381c725ec2ccbd917032922503841da94acf0ef400378494`。r1原本は不変で、同4件の画像参照metadataだけを具体化した。既存Windows DBは未操作であり、ツールkernelのassets失敗をWindows側の保存成功へ換算しない。専用profileはrootが所有・元Node/Chrome不在・参照0を確認してr1/r2とも撤去した。別cleanup receiptのSHA256はr1 `33034f9ff6fd2c9ba9af27d27dbd7f9504a8bee677b78aac613348d984b566e4`、r2 `6ddd9b5a8e62d6df707a319210a3737ed485fb6f4fae9e98e1ca0f85dea3d165`。通常UIDの参照検査で非dumpableなsystemd/sd-pamの2PIDに権限例外があったが、rootの読取auditとPID世代固定で参照0を確認し、未確認エラー0で撤去した。原r1/r2 resultのretained記述は変更せず、後続のcleanup別記録として扱う。共有FE/BEは他の9月実機に必要なため継続保持する。

4件の所見は別scopeのunverified観測で、blocker昇格0。タイトル・写真・raw本文・メール・実slugはここへ転載せず、匿名役割・件数・hashだけを証拠とする。r1成功をr2成功へ読み替えず、それぞれの独立検証を区別する。
