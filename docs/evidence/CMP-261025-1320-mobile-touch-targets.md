# CMP-261025-1320 マイシフトのモバイル操作領域

チーム絞り込み2定義と日別詳細closeのnative buttonをモバイルで幅・高さ44px以上へ修正した。[実装PR #4119](https://github.com/kenta-0420/mannschaft/pull/4119)のheadは`35801c64f0a2aff9123a4bdcbd8b931776fc3a37`。2026-10-25T15:21:21Z通常merge、mainは`67ec2f72889612e916bda97b68f30db4ecb5f8a9`。[正式完了証拠](CMP-261025-1320.json)はscope appでreview/real/exploratoryを対応付ける。

`my/shift.vue`の3定義へ`max-md:min-h-11 max-md:min-w-11`、closeへ既存6言語の`button.close`のaria-labelを追加。API・共通CSS・依存変更なし。

## ACとgreen証拠

| AC | 条件 | 証拠 |
|---|---|---|
| AC1 | 360/390で全team・短名を含むチップ44px以上 | fixture360/390/767、実機360/390 |
| AC2 | close44px、端click/実tapで開閉、既存翻訳名 | fixture、実機、探索360/390可視close44×44 |
| AC3 | team絞り込み/全team復帰、月次/週次維持 | fixture、実BE本人2slot、mobile住民 |
| AC4 | 767で44px、768/1280の既存desktop密度維持 | 旧版と修正版の矩形/computed比較、正式CI同spec |
| AC5 | 横パンなし、縦scrollでclose到達/操作 | fixture、実機、mobile360/390実tap |
| AC6 | 0件filterなし、1teamは全team+1chip、空日close | fixtureEMPTY0/1、同team非担当0、独立T3の1件、ADHD空状態/中断復帰 |
| AC7 | 本人自然導線・未認証・別利用者・独立tenantのself境界 | 実機5、root未認証GET401、scope住民の実tap/query改変 |

空/0/nullはAC6、境界はAC1/4/5、認可はAC7。途中失敗・性能はAPI/永続化・DOM計算・リクエストを増やさないCSS修正という理由を軍議で整理。環境停止と前提失敗は下記で分ける。

## 回帰番人とCI

[fixture集約](CMP-261025-1320/fixture-summary.json): 旧UIはchip高さ32px・短名幅33.296875px・close32×32pxの実寸assertで4失敗/3成功、timeoutなし。最小修正で7/7成功。公開flag未指定、BEなし通知WS境界、丸角外クリックの前提失敗は製品の寸法redに数えない。painted領域内の中心軸端をforceなしclickした。

768/1280のchip矩形/computedは旧版と一致。closeは幅高さ/min寸法/padding/font-size一致で、enter transition中に採取した旧x/yは同値判定から除く。package/lock/main.cssと専有依存46,845fileのfingerprint不変。fixtureはAPI/通知WS境界を置いた実Nuxt/CSS番人で、実BE認可を代替しない。

[CI集約](CMP-261025-1320/ci-summary.json): 実装head21checkは14SUCCESS/7SKIPPED。Frontend E2E run40200753771のsmoke101成功に新spec7が実行され全成功。[main67ecの実績](CMP-261025-1320/main-ci-receipt.md)はFE/unit成功、Docker self-contained・実container HTTP/health成功（run40206539292）、gitleaks回帰/差分scan成功（40206539110）。Build & Deploy（40206539213）はAWS credential check成功後のECR/ECS等cloud実stepsがすべてskippedで、本番変更なし。週次full-history scanは未実行。通常ログ本文/secret値は取得せずstep/job metadataで確認。本番作成・権限付与・gate変更は行っていない。

[正式完了gate](CMP-261025-1320/completion-gate-validation.json)はGitHub実APIのPR4119 merge/head・マージ時点check世代21・CI run2件をtrusted validatorへ渡し、scope app・AC1〜7・回帰証拠・3stage・参照実在・台帳完了遷移を検証して合格した。巨大な過去gitrefのCLI検査や保存スナップショットだけでGitHub照合を代替していない。

## モックなし実機

[実機5](CMP-261025-1320/real-summary.json)はAPI/WSモックなしの実Nuxt→実BE→専有新MySQL/Valkey。Chromium mobile/touchで360/390の本人自然導線・実寸・端click・filter/月週・空日・横パン、同team非担当0件、独立T3の1件、未認証直接URL→loginが全成功。[root cookieなしGET](CMP-261025-1320/root-unauth-probe.json)も401。

setupは正規API35操作で3actor/3team/3slot。本人T1/T2各1slot、別利用者はT1非担当MEMBER、他tenantは独立T3だけに所属して1slot、本人/他tenantの所属intersection空。SQL直INSERT・交換create/accept/approve・決済なし。

[探索後runtime実測](CMP-261025-1320/runtime-summary.json): BE/FE health200、BE追跡ソース13,223file/JAR一致。探索後FE UI SHA`a65f040e1243034de335ea446a0d77f0a5972eea5ce2d7a3012136e78bc288ab`、lock SHA`8110ac9ebaae00170f362f808395d9346061b42954feeccac8b0791c1dfc632c`を実読確認。loopback・productionfalse・配送worker/SQS/外部連携停止・SES/storage loopback:9を維持。

業務件数はusers5/team3/membership4/roles4/schedules3/slots3不変、交換0/push subscription0。setup通知SHIFT_PUBLISHED4とauth新端末通知NEW_DEVICE_LOGIN3の計7、outbox3は不変。内部CSP record2/occurrence sum2、error_report1/occurrence sum34を別記する。業務write0であり、auth/session/自動診断を含む全DB write0とはしない。

## 独立3住民の探索と視覚証拠

[探索集約](CMP-261025-1320/exploratory-summary.json): 復旧後run `cmp1320-recovered-20261026`、別context/各自loginの3住民を並列起動し目的だけ与えた。全context閉鎖。物理スマートフォンではなくChromium mobile/touch。

- スマホ片手×表示崩れ: 本人2team勤務を把握し360/390で日別詳細/closeをforceなし実tap。360は下部詳細へscroll後close44×44、390は長名filter168×44・close44×44。月週復帰・横パンなし。[360可視close](CMP-261025-1320/images/08-detailvisible360.png)、[390可視close](CMP-261025-1320/images/11-filterdetail390.png)を住民/rootが画像・DOM・tap結果で照合。
- ADHD傾向×中断/放棄: 非担当の予定なしを自然導線で確認。別about:blankタブへ5秒中断後もURL/期間/空表示を保持し月週確認。7画像を実際に確認、390幅で横パンなし。
- 一般×隙間狙い: 独立T3がメニューから実tapし自身の1件のみ確認。他userId・T1/T2のteamId/teamSlugをURL/queryに指定してもbaselineとGET body完全一致、T3/slot3のみ。全任意IDパスの包括的認可保証とはしない。

mobileは自動CSP POST1件のみwrite guardが遮断し未送信、業務APIは実BE。ADHD/scope attempt2は自動CSP POST204各1件。未分類nonGETカウンターを業務writeと誤記しない。初回実機360close画像はviewport外で不足だったが、後続の上記安定可視画像/実tapで補完した。

2観測を[登録可能な気づきoverlay](CMP-261025-1320/resident-observations.overlay.json)へ保存した。詳細は下部追加でscrollを要し、共通戻るリンクのaccnameは`U+E91A + 空白 + 戻る`でexact locatorに不一致。rootはhref実tapでURL`/my`遷移まで確認したが安定描画は未確認。操作不能/製品不具合/新CMP/blockerへ昇格せず、`actual-resident-artifacts / trusted:false / observed-unverified`とする。既存validator関数で宣言整合を確認する登録用payloadのみで、既存board/overlay/IndexedDBや気づき箱UIへ未反映。runner/coverage合格も意味しない。

## 保存した失敗と限界

[独立失敗履歴](CMP-261025-1320/attempt-history.json): 初回3住民はWSL停止後に接続拒否、login0/UI到達0。復旧後の共有証跡cleanup/ASCII selector/古いDOM/strict mode/非表示要素のrunner不備と中断も成功へ読み替えない。WSL正常shutdown連鎖・OOMreaper記録なしで発火元未特定。root所有keeperで復旧したが根治済みとはしない。物理端末・全任意IDパス・禁止された交換/承認/決済は未試験。

[原本SHA manifest](CMP-261025-1320/source-evidence-manifest.json)でprivate原本と集約を対応付ける。資格情報・token・秘密rawlogは収録しない。

## 元の既存main観測

CMP0647探索で発見した既存main別件。chip2個高さ32px・close32×32をrootが390実画像/DOM確認し、CMP0647差分とは分けた。[当時の照合](CMP-261021-0647/existing-mobile-observation.json)、[chip](CMP-261021-0647/images/mobile-existing-chips-390.png)、[close](CMP-261021-0647/images/mobile-existing-close-390.png)。
