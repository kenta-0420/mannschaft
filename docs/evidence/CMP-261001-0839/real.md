# CMP-261001-0839 CSP実機HTTP検証

## 初回の実機試験（実装PR内の混成runtime）

2026-10-26 02:36:54 UTC開始、Playwright 1.60.0 / Chromium 148.0.7778.96、28秒、1 passed / 0 failed / skip 0。最初の検証は単一commitのcheckoutではない。

- FE: Timeline実装作業の専有copy、source ba96fb596d7093d7f054ccd368b7e4cd5684ee3f、http://localhost:3001。
- BE: CSP product source 80d714b3fdd1d41b0afae73e0a790cf4ea02258f のbootJar、SHA-256 7afe470f32cd2378b4496d99817d8c7f1f0376b0e1545c2b23cc449b550b39fb、http://localhost:8083。
- 実機specは当時の実装PR #4127 sourceで、spec SHA-256 48864ccea6b776504a852e1c7f6af34155f674ed302ce365390cfe0cbe162c41。DBはrun UUID付きdocument URIに限定し、before/after cleanupは同runの行だけを対象とした。

この初回試験の実行詳細は以降の最新main追従後試験と混ぜず保持する。初回のsummary/resultは[evidence/runtime/initial-real-summary.json](evidence/runtime/initial-real-summary.json)と[evidence/runtime/initial-playwright-result.json](evidence/runtime/initial-playwright-result.json)、33件回帰の初回build記録は[evidence/runtime/initial-red-green-summary.json](evidence/runtime/initial-red-green-summary.json)。

## 最新main追従後の再実機

2026-10-26 03:56:54 UTCに同じspecを改めて実行し、1 passed / 0 failed / skip 0、Playwright実測23.885秒。sourceを区別した実物の構成は次の通り。

- FE source e202bb96c03fc1b9197bfad8d27217dc7b4e8687、専有copyを http://localhost:3001 で提供。
- BE product source d969c21a5ef184112bdd50957e20d6e7f73f4b1a、jar SHA-256 906d0e6a8a6bf758d5544972b60bb005c8f0e2b03e85e698cef36243dfae498d、http://localhost:8083 で提供。latest-main baseは cd14cf155f90e78d8709135bb08765f9bb450478。
- 実行spec sourceも d969c21a5ef184112bdd50957e20d6e7f73f4b1a、SHA-256は初回と同じ 48864ccea6b776504a852e1c7f6af34155f674ed302ce365390cfe0cbe162c41。
- DBはowner october0839-0338-20261026 の専有DB october_first_wave。試験はUUID付きdocument URIを使い、自分の行だけをbefore/afterAllで削除し、latest-main runのafterAll cleanup成功を確認した。
- 起動前ready確認ではAPI UP、V253 applied / failed 0、既存seed維持、FE管理画面200。追加fixture INSERTなし。

最新実行の要約・result・input・ready原本はcanonical evidenceの[latestmain-real-summary](evidence/runtime/latestmain-real-summary.json)、[Playwright result](evidence/runtime/latestmain-playwright-result.json)、[input](evidence/runtime/latestmain-input.json)、[readiness](evidence/runtime/latestmain-readiness.json)。

### 操作結果

1. 匿名ブラウザでlogin実ページを開き、CSP headerの相対report endpoint /api/v1/security/csp-reports を確認。
2. 禁止iframeを挿入し、実際のCSP違反をブラウザに自動送信させた。Content-Type application/csp-report、同一originへのPOST、204を確認し、専有DBへの保存を確認。
3. 同じページからwrapper付き本文を application/csp-report、application/reports+json、application/json でPOST。それぞれ204・空応答、各1行の保存を確認。
4. application/reports+json の空文字、JSON null、不正JSON、[] は204を確認。実機specはこれら4入力のDB未保存を検査していない。無保存は regression 文書の33組実DB ITが検査する。

### 住民Cの関連観測（別source）

共有障害報告から通常利用へ戻るresident C（MEMBER8）のjourney中、Q&Aへ一時的にobject-src違反要素を加えた。実securitypolicyviolation（object-src / blockedURI=data / enforce）とconsole遮断、ブラウザ自動POST /api/v1/security/csp-reports の204、Q&Aとメニュー操作の継続を観測。手動fetchは使わず、DB保存の有無も確認していない。この探索runはsourceHead 03be10c6d87ceeec29d4b09f0bac4db0d391c514で、d969の再実機sourceとは別である。MIME網羅・異常payloadの保存有無・管理者側履歴閲覧はresident Cの探索対象外。詳細は[resident C記録](../CMP-261022-0338/residents/member.md)と[住民探索overview](../CMP-261022-0338/residents/overview.md)、[CSP観測JSON](evidence/resident-c/csp-object-observation.json)。選択コピーのSHA-256は AACDF27C1A096956EBB4A615BB1BB990DB1C9784131158394F43A67E2DF2D473。sourcePathは探索時の旧worktree pathで、現在の保存先は[worktree cleanup manifest](evidence/resident-c/timeline-worktree-cleanup.json)記載のcanonical runtime archive。

## ACと適用範囲

- CSP-1: 3 MIMEの実POSTで204・空応答、各有効報告のDB保存を確認。
- CSP-2: 最新実機は空/null/不正JSON/配列に204を確認。保存0は実機の主張に含めず、実DB ITの検証範囲とする。
- CSP-3: 匿名ブラウザの公開書込専用endpointを確認。閲覧操作や複数ロール横断はこのspecの対象外。レート制限閾値・負荷試験も未実施。
- CSP-4: d969実機では禁止iframeによるCSP自動report-uri POST 204とDB保存を確認。resident Cは別source 03beの実browser探索でobject-src violation、自動POST 204、通常UI継続を確認した。手動fetch・DB裏取り・MIME網羅・無効payload探索はしておらず、この範囲の観察として扱う。

## 専有環境の後片付け

[cleanup summary](evidence/runtime/runtime-cleanup-summary.json)と[secret cleanup summary](evidence/runtime/runtime-sensitive-cleanup.json)によると、専有FE copy、BE process、専有container 2件/volume 2件、専有tool cacheを撤去し、専用port 3001/8083/13339/16339のlistenerは0。他所有53 containersは保持した。秘密envとjar 2本はhash記録と照合して削除し、手順・要約等の残存記録は秘密値を除去した。自身の専有環境は撤去済み。

共通verification checkはexit 1だったが、残存copyは別owner l1a の /var/tmp/mannschaft-verification-1000/run-55fg9V。他owner資産に操作せず保持した結果であり、共通環境全体のcleanup合格とは扱わない。

PR #4127は2026-10-26 05:23:22 UTCに通常MERGED。実装head `819bfcdbeadcc0d13d3c246f7de5fea60c796a56`、merge commitおよび現行main `1a1220d8fec79d984d64829de4805080e794e197`。d969からfollowed source 819へのmain追従bridgeは対象12 object不変、BE tree `7040e7e33604c171831ffb4932ead6952fae88a0`、workflow tree `7bf380b86e06374ab2b8508a785bd88576e8fbe1`でroot独立照合済み（[bridge](evidence/runtime/main-follow-bridge.json)）。最新headのBackend [run 40272198959](https://github.com/kenta-0420/mannschaft/actions/runs/40272198959)、Frontend [run 40272198978](https://github.com/kenta-0420/mannschaft/actions/runs/40272198978)、Smoke [run 40272198960](https://github.com/kenta-0420/mannschaft/actions/runs/40272198960) はすべて成功。Backendは6 shardがFROM-CACHE、d969の旧head [run 40265251942](https://github.com/kenta-0420/mannschaft/actions/runs/40265251942) は6 shard job success。shard1は`:test`実行72m02sでMFA 4 case・failure 0を確認したが、他5 shardのfresh/cache状態は未照合。main追従bridgeとあわせて補助根拠として扱う。Smokeは100 passed、`INSIGHT-INVITE-EN` 1件がflakyとなったがretry成功。Frontendでは追加Vitest 27件の成功を確認したが全体件数は記録しない。実機・IT・住民Cのsourceは各節に明記したとおり別sourceの実測であり、819上で再実行したとは主張しない。worktree撤去結果は[cleanup manifest](evidence/runtime/csp-worktree-cleanup.json)を参照。