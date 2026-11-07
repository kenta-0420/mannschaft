# CMP-261107-0551 受入証拠対応案

状態: IN_PROGRESS。2026-11-08の選別draft。完了台帳・完了証拠JSONではない。実装PR #4353はdraft、main追従head `9e8e9ba15cc7040d88d88067646be2d6a51ec862` のCIは進行中、新production build/scannerは未確認。下記の肯定証拠は原則として旧head `4ce3aa26785871ee363c90b0a514f023ca20723a` に固定する。protected 4309 blob一致のsource監査は元scope継承の根拠であり、新headのCI/build成功には代用できない。

採用時のrepo targetは `docs/evidence/CMP-261107-0551/`。以下のファイル名は同directoryへの相対参照。原本source/hash対応は `source-manifest.json`。原本manifest/PNGの再生成・新試験は本stagingで行っていない。

| AC | 選別根拠 | 範囲・残件 |
|---|---|---|
| I01 | `renderer-summary.json`、`coverage.jsonl`: 6144 unique draws/6144 PASS。原本照合4608 care+1536 walkの既存記録。 | direct rendererの比較。全実アプリUIの網羅とはしない。SHA/寸法/tuple負例の個別test出力は別途正本へ結ぶ。 |
| I02 | 既存1920 PNG validator、atlas decoded RGBA一致。6144描画との対応はcoverage。 | N±1/3・5frame/不正時間の個別負例test名・repo pathを採用時に確定する。 |
| I03 | FE catalog/resolver既存unitと旧head CI。 | 全負例のtest名を製品specへ結び付ける。CIは旧head限定。 |
| I04 | `backend-summary.json`: same 3 filesでred 2 assertions→green4。 | rootのlegacy最終補足でv3 AIR保存tuple不変・V1 idle/happy原本RGBA完全一致・同id成功ACK・reload維持を限定採択。v2互換の個別参照を確定する。 |
| I05 | BE catalog/selection先行red/green、regression19うちMySQL13。 | 単体・IT範囲を維持し、全habitat全候補の実UI測定と主張しない。 |
| I06 | 旧production build/scanner、正式512gate・64/81mapping不変のsource監査。 | main追従後の新build/scanner待ち。公開裁可はこのcampaignの範囲外。 |
| I07 | r15自然入口/孵化、牧場/widget、両画風、JUVENILE/ADULT表示の個別肯定結果。 | 成長段階はguarded own-row fixtureで自然日数成長ではない。legacy再読込はroot最終補足で限定採択。 |
| I08 | r15牧場/widget feed/touch成功ACK同本人と原本RGBA照合、failed feed無反応。 | 35 positivesには外部renderer等の再照合を含む。全35を新規実UI操作としない。連打/遅延raceは既存unit等の範囲と区別する。 |
| I09 | 6144 direct renderer、r15 idle-walk-idle、r10代表care ABCD各約500ms/合計約2023ms。 | 複合根拠。raw care-phase-timing未実装はNOT_EXECUTEDを維持する。 |
| I10 | `root-visual-review.json` と6PNG、既存effects比較。r17 SEA ambientはroot PNG視認採択。 | 6代表stillは全位相final-pixel goldenではない。legacy AIR idle/happy原本比較と1200ms ambient位置変化はroot採択。 |
| I11 | 28 unit+同期取消source+r15停止/復帰の複合技術受入。 | 実background tab document.hidden遷移は未成立/NOT_EXECUTED。raw PARTIALを維持。全OS/browser網羅とはしない。 |
| I12 | r15 atlas404/破損/寸法違い/fallback失敗。Atlas unitの古callback/decode世代guard。 | 古onload raceの実ブラウザ直接測定はNOT_EXECUTED。unit/sourceに基づく限定採択。 |
| I13 | 初回同tuple必要画像、inactive停止、identity Map.clear/decoded=nullのsource+unit。 | scene所有schedulerとapp cache参照releaseの契約。全browser scheduler/GC/GPU/HTTP/global cacheの物理解放は未測定。 |
| I14 | `renderer-summary.json`: 6144 isolated paint p95 1.1000000089ms、idle atlas HTTP body最大比1.27018761395、variant比1.48912912913。同固定browser/1280×720/no-cache比較。 | 恐竜素材body/isolated CPUの限定指標。アプリ全通信量、TTI、full-frame presentation、本番SLOではない。 |
| I15 | 旧build scanner4484 files/14 internal links、Windows/WSL scanner tests各2の既存記録。 | main追従後production build/scanner未確認。旧成功を新headへ昇格しない。 |
| I16 | r15本人/未認証直URL、r17補足別本人HttpOnly cookieでown200/other404をroot採択。 | Bearer必須はハーネス誤前提で原FAIL保持。personal feature userId境界と他tenant全機能網羅を混同しない。 |
| I17 | 独立reviewとroot補完、旧head CI、実UI個別証拠を分離。 | 正式3人は原report上全目的達成。resident-observations-20261108.jsonに選別保存し、rootが正式探索の実施完了を採択。未実証4観測と非再現・原因UNKNOWNを保持。最新CI、新build、cleanup、通常merge、完了台帳更新は未完。 |

回帰欄の方針: BEの先行redは新featureの受入条件。既存regression19 greenは既存機能の回帰確認。既存bug修正のred/greenとして記録しない。既存bug修正を後から完了証拠へ追加する場合だけ、その同一対象のred/green原本を別に結ぶ。

原失敗は改変しない。r15 raw FAIL、r17 raw FAIL、legacy supplement FAILを `ui-status-extract.json` に原本SHA付きで保持する。r15の旧document body取得CDP失敗はroot既存診断の範囲、r17の未解明部分はunknownのまま扱う。個別PASSの根拠とrun集計成功は別である。
