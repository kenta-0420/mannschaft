# 正式17ACへの対応・完了検査の準備

状態は正式化前の準備履歴。production/Windows撤収receipt/殿GOを受けた現正式JSONは親directoryのCMP-261107-0551.json。以下の未入力一覧・コマンド計画は準備時点の内容として保持する。実装PR4353のheadはH=`9e8e9ba15cc7040d88d88067646be2d6a51ec862`、通常merge commitはM=`e2b4744f438d0d376fb3c243e28aaa5ad2ff545d`。production成功の殿GO後に、以下を17件のacceptanceCriteriaへ対応させるための準備である。Hのテスト名/存在はGit objectから確認した。実行証拠は `ci-current-head.json` のH固定成功と、各選別実測を分ける。製品テストをこの文書作成で再実行したとは扱わない。

| ID | 正式化する受入内容・根拠 | テスト名とリポジトリ参照、範囲 |
|---|---|---|
| I01 | 承認care4608/walk1536のSHA・寸法・一意tupleを照合し、欠落・重複・不正path・未知tupleを拒否。 | `v4 validator rejects count, frame, phase, hash and traversal changes` / `inventory rejects missing and extra files` — `frontend/scripts/verify-ranch-development-appearances.test.mjs`。6144draw/unique tupleと原本比較の実測は `renderer-summary.json` / `coverage.jsonl`。 |
| I02 | v4 logical384/clip1536/fallback384、ABCD4セルの原本decoded RGBA一致、same-tuple idle A fallback、件数±1・3/5frame・不正phase/duration拒否。 | I01と同validator負例2test、および `frontend/scripts/verify-ranch-development-appearances.mjs`。`native4frame境界を96/384単位で切り出し、範囲外や破損寸法を拒否する` — `frontend/app/utils/ranch-development-clips.spec.ts`。全atlas/cell照合を個々の実アプリ画面表示へ拡張しない。 |
| I03 | species×4variant×3stage×2styleの完全一致解決、UUID不正/null/EGG/未知version・variant拒否。 | `16種3stage2styleが同個体の4actionとfallbackへ明示解決される`（名前は旧v3由来だがcatalogVersion4全entryをloop）、`identityの欠損・未知・旧catalogを推定しない %j`、`未知stage/styleは画像を返さない` — `frontend/app/utils/ranch-development-clips.spec.ts`。 |
| I04 | v3入力identity/API保存値を保持して同species/stage/styleの最新V1へ明示alias、v2既存経路維持。 | `v3 identityを変更せず同種同段階同画風のv4 V1へ描画aliasする` / `既存v2のidentity・bytehash・8frameと本番512gateを維持する` — `frontend/app/utils/ranch-development-clips.spec.ts`。BE `v4候補は各種四つで全外見と保存済みv3を厳密に解決する` — `backend/src/test/java/com/mannschaft/app/ranch/service/RanchApproved64AcceptanceTest.java`。本体v3 AIR原本RGBA/同個体ACK/reloadは `root-adoption-draft.json` の最終補足。 |
| I05 | HABITAT_RANDOMのLAND48/SEA12/AIR4組を生息地内だけで候補化し、実variant/version/basisへ保存。 | 前行BE候補test、`各生息地の選定結果とbasisは実際の候補に一致する` — `backend/src/test/java/com/mannschaft/app/ranch/service/RanchApproved64SelectionAcceptanceTest.java`。先行red2 assertion→same-files green4と既存回帰19/MySQL13は `backend-summary.json`。確率的全64出現の実UI試験ではない。 |
| I06 | production/profile混在・flag off・非隔離でDEV候補/素材を無効化し、正式512gate・64/81対応・公開状態を不変に保つ。 | `不正な組合せと本番混在は解決しない` — 上記BE AcceptanceTest、`旧版と新外見の交差および空の種は拒否する` — `backend/src/test/java/com/mannschaft/app/ranch/service/RanchApproved64BoundaryTest.java`、`productionまたは無効flagは公開しない %j` / v2本番512gate test — FE resolver spec。production実artifactはM Docker job成功証拠待ち。DEVflag=true config guard観測とDocker標準build条件を混同しない。 |
| I07 | 牧場/widgetで孵化後stage/style/本人identityに対応した最新素材を表示し、旧個体reloadも同一性を維持。 | `growth後はcurrentstageを反映してからそのstageのfeedを通知する` — `frontend/app/components/ranch/RanchWidgetContent.clips.spec.ts`。本体r15の自然入口/孵化・両画風・JUVENILE/ADULTとr17legacy最終補足 — `ui-status-extract.json` / `root-adoption-draft.json`。成長stageはguarded own-row fixtureであり自然日数成長ではない。 |
| I08 | same-individual成功ACKだけでfeed/happyを1回再生しidle復帰、失敗・別個体・切替後遅延・再通知/連打抑制を保持。 | `pendingの間は予測通知せず拒否ACKでは通知しない` / `actor変更後の旧成功応答を拒否し新actorへ通知しない` / `保存済みcommandIdの再通知は反応sequenceを増やさない` / `XP0の成功feedもreload完了後に同IDのfeedを1回通知する` — `frontend/app/components/ranch/RanchWidgetContent.clips.spec.ts`。`二重送信を防ぎ完了後だけ次のkeyを発行する` — `frontend/app/composables/useRanchCommand.spec.ts`。実UI牧場/widget成功・失敗ACK/原本RGBAは `ui-status-extract.json`。race網羅を実ブラウザで再現したとはしない。 |
| I09 | 承認duration/phaseStarts/ABCD順とidle→walk→idleを保持し、全6144sourceを本体rendererのdirect drawで原本比較。 | `idleを2完全cycle後walkを1cycle、phaseStarts境界ちょうどで次frameとなる` / `feed/happyがcadenceを中断し有限終了後idleから2cycleを再開する` — `frontend/app/composables/useRanchClipPlayback.spec.ts`。`renderer-summary.json` / `coverage.jsonl` と `ac-map.md` のr10約500ms×ABCD/約2023ms・r15遷移の複合証拠。raw care-phase-timing NOT_EXECUTEDを維持し、全アプリ場面の6144操作とはしない。 |
| I10 | frame/style別の接地影・尾線3本・種別SEA全身揺れ・AIR揺れ・泡/雲を保持。 | `LAND happyはmetadata座標から3本のtailMarksだけを描きgenericbounceしない` / `SEA rollはPIXEL step保持/2D補間、AIR swayはPIXEL整数/2D連続値となる` / `v4 SEAはplesiosaurusを7度、他種を5度とし承認viewerの軸を使う` / `fallbackは要求actionにかかわらずidle Aの影を使い、海と空には影を描かない` — `frontend/app/composables/useRanchClipAtlas.spec.ts`。effects比較/6代表PNG/root視認とSEA/AIR ambient実UI補足 — `root-visual-review.json` / `root-adoption-draft.json`。全phaseのfinal-pixel goldenではない。 |
| I11 | REDUCED/OS reduce/STOPPED/hidden/offscreen/inactive/unmount停止と、復帰時追いつき再生をしない契約。 | `hidden/identity変更後は追いつかずidleから再開しREDUCED/STOPPEDはRAFを作らない` / `初回hiddenはRAF0、scope破棄は稼働中RAFと旧callbackを無効化する` — Playback spec。`初回hidden/inactive/viewport外はimage0/RAF0、非表示とunmountで全資源を止める` — `frontend/app/components/ranch/DinosaurScene.clips.spec.ts`。既存28unit+source+実UI停止復帰のroot限定複合受入。real background document.hidden未成立/NOT_EXECUTEDは保持。 |
| I12 | atlas404/破損/寸法不正→同identity fallback、fallback失敗→文字/操作保持、古load/decode拒否。 | `寸法不正は同entry fallbackだけへ移行しfallback失敗はassetPreparingとなる` / `同entryでもindividual変更はpending世代を破棄し旧onloadが新個体を描かない` / `decode失敗はsame-entry fallbackへ移行し古decode完了は新actionをpaintしない` — Atlas spec。本体fault実UIは `ui-status-extract.json`。旧onload raceの実ブラウザ直接測定NOT_EXECUTEDは、unit/sourceの技術受入へ置き換えず併記。 |
| I13 | 現tupleの必要action/fallbackだけ取得、別種/外見の先読み0、scene所有停止/切替cache参照破棄の契約。 | `初回不可視は0取得、低速action画像はready待機しhiddenでpending/旧callbackを取消する` / `要求action欠損は別actionを取らずsame-entry静止だけを取得する` / `STOPPED/REDUCEDは同entry静止だけ取得、切替は進行中atlasを直ちに取消する` — Atlas spec、Scene/Playback停止test。初回要求tupleと停止実UI+Map.clear/decoded=null source監査の複合受入。全browser scheduler/timer/GC/GPU/HTTP/global cache物理解放やglobal process0は主張しない。 |
| I14 | **恐竜素材**の固定browser/1280×720/no-cache初回idle atlas HTTP body比≤2、同tuple/action variant body比≤1.5、isolated renderer paint CPU p95≤16.7ms。 | 実測名 `6144 isolated paintRanchClip CPU timings and atlas HTTP body comparison`、証拠 `renderer-summary.json`。p95=1.10000000894ms、最大idle比1.27018761395、variant比1.48912912913。全アプリ通信/TTI/frame presentation/SLO、新head全転送やWindows/Docker同artifact bytesを測定したとはしない。これはrootが限定採択した測定定義であり、未測定をPASSへ変更しない。 |
| I15 | 通常production artifactへDEV画像bytes/絶対path/promptを混入せず、公開publicへ配置しない。 | `production artifact rejects matching PNG bytes and development paths` / `inventory permits internal links and rejects external links and cycles` — `frontend/scripts/verify-ranch-production-artifact.test.mjs`、`frontend/scripts/verify-ranch-production-artifact.mjs`。旧4484files/14internal links成功は歴史。M Docker run42406375715/job128504862033のbuild後scanner/self-contained/HTTP/health成功と出力原本が最後の未完入力。 |
| I16 | 実BE/FE本人・未認証・他人（別team所属）/URL直打ちの個人機能境界を確認。 | r15本人/未認証、本物の別ログインcontextの同command本人200/他人404 — `root-adoption-draft.json` / `ui-status-extract.json`。mock APIは実機成功に使わない。Bearer必須はharness誤前提で原FAILを保持。userId個人境界であり、teamId分岐のない個人機能を全tenant全機能認可網羅としない。 |
| I17 | 実機後の独立正式3住民探索（発見/ケア/ようす/設定/再訪）とtriage、H CI、検分・実機・探索を分離して通常PR/closure手順を完了。 | `resident-observations-20261108.json` / `resident-root-disposition.md`（3人全目的達成、新確定bug0、未実証4観測保持）、`board-ui-safe.json`、`ci-current-head.json`、実装通常mergeの `postmerge-production-input-bridge.json`。rootの最終production採択/正式JSON/台帳/文書PRはまだ未実施。静的board UIを製品探索の代わりにしない。 |

`Atlas spec` は `frontend/app/composables/useRanchClipAtlas.spec.ts`、`Playback spec` は `frontend/app/composables/useRanchClipPlayback.spec.ts`。表中の証拠ファイル名は `docs/evidence/CMP-261107-0551/` にあるrepo内pathへ展開して正式JSONから参照する。sourceのspec名と実測JSONの両方が必要なACではtests配列へそれぞれ記載する。新機能受入の先行redと既存回帰確認を既存bugfixのred/greenへ偽変換せず、今回regressionsは空配列の方針を維持する。

## 正式化GOに必要な最後の入力

1. M固定・同repository・event push・run42406375715/job128504862033の最終SUCCESS原本。build→production scanner→runtime self-contained→HTTP/healthy/healthy後HTTPがすべて実行成功した結果・完了時刻と、選別可能なscanner集計/ログhash。skip/cancel/途中は成功にしない。H/M入力scope橋は既に確定済み。
2. 殿の最終review/real/exploratory採択とproduction成功GO。既採択のraw FAIL/未測定・I14限界・未実証住民4件を保持した上で採用する。正式stage証拠は完成後のrepo内最終MDへ束ねる。
3. 未完Windows H buildの最終扱いと所有資源の撤収/保持裁定。Docker成功をWindows PASSへ変更しない。中止ならaborted/not-completed、原因未確定を保持し、所有PID/startとreceipt/cleanupだけを選別する。追加native NO_STARTは作成0の歴史のまま。
4. 正式validatorの実装API照合用にPR4353の**秒まで正確なmerged_at**、headH/merged=true、changed_filesと全ページfiles一覧一致、Hの全check/statusからmerge時世代を選んだ結果。現bridgeの19:28UTCはrootの分精度であり正確なmerged_atを捏造しない。CI12成功metadataは既にあり、formal ci[]はH成功runのみ。M push/Task-List targetを混ぜない。

これは新しい製品テスト/追加CIを求める一覧ではない。完了に必要な既定production結果・所有資源の最終扱い・GitHub正確metadata・殿GOの入力である。

## 正式化後のvalidatorコマンドと限界

以下は**GO後**、正式JSON/台帳/最終証拠をowned branchへcommitしてから実行する予定。現在の未完draftに実行して新たな完了遷移が無いことを検査しても、将来の完了証拠検査の代用にはしない。

```powershell
node --test scripts/completion-evidence.test.mjs
$env:GITHUB_REPOSITORY='kenta-0420/mannschaft'
node scripts/completion-evidence.mjs --base e2b4744f438d0d376fb3c243e28aaa5ad2ff545d --head HEAD
```

比較baseはclosureの基準M。mainが後続進行して最終docs branchへ通常mergeされた場合は、その実base commitへ置き換える。`--base/--head` はGit objectを読むため、作業木だけにある正式JSONを検査したと扱わない。このCLIは構造/遷移/存在pathを検査し、GitHub APIのPR/CI状態を取得しない。

提供GitHub metadataも照合する場合だけ、private safe入力を組み立てて以下を追加する。

```powershell
node scripts/completion-evidence.mjs --input <private-safe-input-file>
```

inputはbaseTaskList/headTaskList/evidenceByPath/repository/existingFiles/implementations/runs/evidenceChanged/evidenceDeletedを持つ。implementations[4353]はrepository・merged・shaH・**正確なmerged_atでsummarizeImplementationChecksしたchecks**・全filesを持ち、runsはH固定success runだけのrunId/repository/sha/conclusionとする。`--input`も渡されたmetadataの検査であり、独立したlive API取得とは区別する。後続docs PRのTask-List PR Gateはtrusted base validatorが実GitHub APIを検査する最終ゲートとなる。bootstrap/skipや自作の緩いvalidatorへ切り替えない。
