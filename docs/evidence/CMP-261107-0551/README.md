# CMP-261107-0551 選別証拠・完了準備

状態は IN_PROGRESS。実装PR #4353 の新head CI、production build/scanner、環境撤収、通常merge、殿の最終裁定は別ゲートであり、この文書は完了宣言ではない。正式完了JSONとtask-list完了遷移は含めない。

担当はclosure文書/B0追記準備。殿は製品・実機採否・住民裏取り・最終出荷判断を担当する。所有worktreeは `work/ranch-approved64-closure-20261108`、branchは `docs/ranch-approved64-closure-20261108`。開始時HEAD `9e8e9ba15cc7040d88d88067646be2d6a51ec862`、作業木cleanを確認。originはローカル本陣のためpushしていない。製品source・既存helper・live runtimeは変更しない。

`coverage.jsonl` は6144行のdirect renderer比較で、原本SHA `b9f424ff57647ae4a0998446e794e2b26e5991f38f1e7ffcab5251683deadd0b` を保つ。6枚のcanvas PNGは既存root視認済み代表stillのbytes同一copyであり、全アプリの全位相goldenではない。`renderer-summary.json`、`backend-summary.json`、`root-test-reference-notes.md`、`ac-map.md` が範囲と制限を示す。private原本とのhash対応は `source-manifest.json`。

`root-adoption-draft.json` は最終legacy AIR/boundary補足の採択を選別した。v3保存tuple不変、V1 idle/happy原本RGBA完全一致、成功ACK同個体、reload維持、AIR ambient位置変化、本人200/他人404を採択する。r15/r17/先行supplementのraw FAIL、実background document.hidden未成立、古onload raceの直接未測定、cold timeout原因UNKNOWNは保持する。I11–I13は既存28unit・source・UI停止復帰の複合限定受入で、全browser scheduler・強制GC・全cache物理解放の証明ではない。

`resident-observations-20261108.json` は正式3住民の原reportからallowlistで再構築した。原run `ranch64-3fe7b1454ec1c5086db2c581`、source `4ce3aa26785871ee363c90b0a514f023ca20723a`、原persona/journey IDを維持する。3人とも各目的を達成、専用page採取範囲のpageerror/HTTP400以上は0。PCの5 viewportには125%相当のviewport近似2条件を含み、実ブラウザzoomではない。mobileは390×844/360×800、returnは1440×900のみ。4枚の住民PNGを原hash維持で保存し、return helpはtransition参考画像の制限を残した。資格情報、絶対home path、全errorlog、account/process metadataは持ち込まない。

殿の住民裏取りは `resident-root-disposition.md`。小さい操作領域の実寸観測は有効だが当該4componentは統合前後で同blob、今回の回帰とは確定しない。mobile19の準備中表示は元画像を保持。root再試行ではACK201同個体、happy画像HTTP200/21ms、ABCD実draw、58sample/約6.085秒でcanvas可視・placeholder0、idle復帰を確認した。元直接原因UNKNOWN、100ms未満の変化の不在は主張しない。新確定bug0、正式探索は実施完了として殿が採択した。未実証4観測を修復済み/誤報へ書き換えない。

B0正本 `docs/evidence/CMP-261015-1510/resident-overlay.json` は旧58 persona/58 journey/114 insightの全fieldをdeep保持し、3/3/4だけ追記して61/61/118とした。トップrunId/environmentも不変。全追加は `actual-resident-artifacts` / `trusted=false` / `observed-unverified`、coverage=falseであり、P01–P20への変換はない。

通常generatorは初回sparseにfrontend spec34参照がなく失敗。その履歴を保持し、owned WTへfrontend/testsだけ物理展開後、`B0_INCLUDE_LOCAL_OVERLAY=true` で正規実行に成功した。ignored `.b0-local/run-cmp0551-observations-20261108.json` は正本同一copy・唯一のrun候補。通常出力の無関係なcampaign/snapshot同期差分は採用せず、開始時data.jsの全fieldを保持して生成済みb0RunOverlayのみ置換した。正本/copy/通常生成overlay/採用overlayのdeepEqualとoverlay外baseline deepEqualを確認。`b0-overlay-checks.json` に限定検査を保存した。

通常生成全体validatorと自己検証全体は既知GitHub snapshot参照集合不一致でFAIL。overlay限定採用データではbaseline由来CMP行数不一致でFAIL。これらをoverlay限定の宣言検査・異なるhead/run正規受理・不正10例拒否の合格と混同しない。静的ボードUIは殿GO待ちで未実行。既存importerのstored runIdが累積トップ旧runIdを使う精度制限も未検証のまま保持する。
