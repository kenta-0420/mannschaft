# CMP-261001-0839 CSP Content-Type 回帰検証

この記録はCSP Content-Type契約の実DB回帰を要約する。実機操作は[real.md](real.md)、resident Cの別source観測もsource区別して記録する。PR #4127はhead `819bfcdbeadcc0d13d3c246f7de5fea60c796a56`、merge `1a1220d8fec79d984d64829de4805080e794e197`で通常MERGED。

## 実装PRと最新headの検証状態

[最新head CI summary](evidence/runtime/implementation-ci-summary.json)によるとhead 819のBackend/Frontend/Smokeは成功。Backend 6 shardはFROM-CACHE、Smokeは100 passedに加えて `INSIGHT-INVITE-EN` がflaky 1件・retry success、Frontendは追加Vitest 27件のみ確認でき全体件数は不明。過去head d969のBackend run 40265251942（attempt 1）は6 shard job success。shard1は`:test`実行72m02sでMFA 4 case・failure 0を確認したが、他5 shardのfresh/cache状態は未照合。main-follow bridgeの12 object不変とともに補助証拠として記録する。過去のIT/red-greenや実機を819で再実行したとは扱わない。

## 初回red/greenの検証来歴

- 実装PR #4127 の当時のheadは da6da6a7b6ce8768ae564a8da607dcd26a83c1ae。
- RedはLinux専有検証copyのtest-first clean source 805e23a8ece3f141fb474afaa2bc8a9e4d7cbd7a を使い、receipt exit 0 / scratch cleanup完了。JUnit XML全体は保存対象にせず、ハッシュ・件数・失敗のみをここに記す。
- Red確定後、Controllerをclean product source 80d714b3fdd1d41b0afae73e0a790cf4ea02258f から同期し、Controller SHA-256 01c5e591120c8dfe7f12de845ad46ecce3b188caf185f1bfbf72198d74392ed4 の一致を確認してgreenを実行。
- Red XML SHA-256 db37fecce91781a484a15369ef7341dac5133d6ce408c816247f1e6b2b4896d6。33件中24 passed / 9 failed / error 0 / skip 0。9件は全て application/reports+json で、期待204に対し500。
- Green XML SHA-256 6591a8c7824891365f94b686194a95e2b35dee942e68ef40dbb5e840f6a4f9c6。33 passed / failure 0 / error 0 / skip 0。ArchUnit 37、freeze整合性2、skip 0。OpenAPI生成差分は5行、型差分1行（openapi-typescript 7.13.0）。
- Red/green raw XMLは元のLinux /tmp/cmp2610010839-linux-evidence-20261026-r2/{red-test.xml,green-test.xml,receipt.json} にある想定だったが、今回の専有runtime teardown後にそのpathは現行WSLでも正本Windows runtime内でも見つからなかったため複製していない。原本を保持していた時点で殿がXMLの件数とfailed MIMEを独立照合済み。SHA/件数の記録は[initial red-green summary](evidence/runtime/initial-red-green-summary.json)。raw JUnitを含むと偽らず、この要約と殿の独立照合を残存根拠として扱う。

## 最新headへの橋渡しと実機再検証

最新main追従後、Backend product source d969c21a5ef184112bdd50957e20d6e7f73f4b1a のjar SHA-256 906d0e6a8a6bf758d5544972b60bb005c8f0e2b03e85e698cef36243dfae498d を実機へ投入した。Latest-main baseは cd14cf155f90e78d8709135bb08765f9bb450478、対応FE sourceはTimeline実装済み e202bb96c03fc1b9197bfad8d27217dc7b4e8687。[artifact record](evidence/runtime/latestmain-backend-artifact.json)は、初回80d productと最新d969のCSP Controller blobが同一（27f5b1df95925d7ac570745c324e6a4345a409f2）、CSP main-test package diffなしと記録する。d969からfollowed source 819への12 object bridgeは[evidence/runtime/main-follow-bridge.json](evidence/runtime/main-follow-bridge.json)。

33組のred/green IT自体は初回のtest-first/product-source上で実行されたもので、d969上で33件を再実行したとは主張しない。d969で新たに再実行したのはPlaywright実機spec 1件で、2026-10-26 03:56:54 UTC開始、23.885秒、1 passed / failed 0 / skip 0。匿名login実ページからCSP headerを確認し、禁止iframeによるブラウザ自動report-uri POST 204・DB保存、3 MIMEの204/空応答・各1行保存、空/null/不正JSON/配列の204を確認した。無効payloadのDB未保存まではこの実機specのassert対象でない。詳細とsource内訳は[real.md](real.md)、実行正本は[latestmain-real-summary](evidence/runtime/latestmain-real-summary.json)と[latestmain Playwright result](evidence/runtime/latestmain-playwright-result.json)。

## 実DB HTTP回帰シナリオ

テストsourceは backend/src/test/java/com/mannschaft/app/cspreport/CspReportContentTypeContractIT.java（suite CSPレポートContent-Type受信契約）。MockMvc経由で実MVC・Security・Service・専有MySQLを通す。各runは自ら生成したUser-Agentの行だけを削除する。

- 未認証で3種のMIME型とcharsetを受理し有効レポートを保存する: MIME application/csp-report、application/reports+json、application/json、各charset=UTF-8、wrapper有無の12組。全て204・空応答、保存1行と主要値/occurrence countを検証。
- 空・null・不正JSON・未対応配列は204で保存しない: MIMEごとにbody未送信、空、空白、JSON null、不正JSON、空配列、Reporting API配列の計21組。全て204・空応答・保存0行を検証。
- 初回Redで今回の旧観測 application/csp-report が500 は再現していない。Redで再現したreports+jsonの契約不一致だけを原因根拠とする。
- Reporting API配列は204で受理するが保存形式として未対応で保存しない。配列保存機能は今回追加していない。

## AC照合と境界

- CSP-1（3 MIMEで204、wrapper有無で既存保存契約を維持）: 実DB ITの有効入力12組と最新d969実機specの3 MIME POSTが根拠。未認証リクエストでも保存を確認。
- CSP-2（charset・空/null・不正入力・未対応配列）: 実DB ITでcharset付き有効12組、保存しない21組を検証。実機specの無効payloadは204のみをassertし、未保存の主張はITに限定。
- CSP-3（公開書込・rate limit・保存経路）: Controllerは受理Content-Type列挙に限る変更で、@IntentionallyPublic と既存Service呼出しを維持。ITは未認証の受信/保存を通す。rate limit閾値や負荷試験は未実施。取得系権限は新設・変更していない。
- CSP-4（実HTTP・住民観察）: d969実機のbrowser自動report-uri経路は[real.md](real.md)に記録。resident C（MEMBER8）は別source 03beの共有報告アクセス制限から通常利用へ戻るjourneyでobject-src違反を起こし、securitypolicyviolation、browser自動POST 204とQ&A/メニュー継続を観察。手動fetchなし・DB保存未確認、MIME網羅や無効payloadは対象外。[canonical resident記録](../CMP-261022-0338/residents/member.md)と[CSP観測JSON](evidence/resident-c/csp-object-observation.json)を参照。

補助記録: latest-main migration V253 success=1 / failed=0、seed維持、追加fixture INSERTなし。専有runtimeは終了し、所有物撤去・他owner保全・global checkの限定失敗を[cleanup summary](evidence/runtime/runtime-cleanup-summary.json)に保持する。実装source worktreeもMERGED/clean確認後に撤去済み。共有cleanup gateは他所有を含むstale 66/total 77によりexit 2、削除0であり、CSP作業木自身は撤去済みである（[cleanup manifest](evidence/runtime/csp-worktree-cleanup.json)）。