# 代理出席設定保存の回帰記録

PR [#3963](https://github.com/kenta-0420/mannschaft/pull/3963)、最終実装HEAD `5b4b9c3d4a63b60d2b1fdc6c42f37a0ab8a4d31a`（N）。予定フォームの両設定がDTO/保存/応答/編集payloadを往復しない問題を対象とした。main取込前の開発過程で実AC redを確認し、greenの詳細結果はD4時点で採取した。redをD4実装HEAD上で出した、またはM/Nでローカル再実行したとは扱わない。

## バックエンド

実装前の共通 `ScheduleProxySettingsContractIT` は29件の実AC assertion failure、error/skip 0を確認した。fixture slug不備や依存起動失敗をredへ算入していない。29件のfailureは1つの保存経路問題から複数ACへ波及するため、29個の独立製品バグとは数えない。旧CIのcancelled runもred/green根拠に使わない。

実装後は共通IT29件、`ActivityScheduleSyncConcurrencyIntegrationTest` 5件、`ActivityAutomaticCreationPathsIntegrationTest` 6件の独立40件がpass、failure/error/skip 0、Gradle exit 0。その後AC-8/AC-13の既存2試験を補強して再実行し、2/2 pass。独立試験を42件へ水増ししない。出欠4行のOFF直前/直後一致と、一覧1予定/9予定の実queryがともに25件であることを確認した。

対象は両Boolean全組合せ、POST省略/null、PATCH省略/null/明示false、snake alias、PERSONAL、POST/PATCH/GET/list応答、Mapper/クロス招待、繰返し対象範囲、実scope権限、保存後の実同期validation・自動活動INSERT失敗rollback、不正string/object/arrayの400、既存syncConfirmation競合409を含む。新Versionや全非Boolean token拒否へ仕様を拡張していない。

テスト参照：

- `backend/src/test/java/com/mannschaft/app/schedule/ScheduleProxySettingsContractIT.java`
- `backend/src/test/java/com/mannschaft/app/activity/ActivityScheduleSyncConcurrencyIntegrationTest.java`
- `backend/src/test/java/com/mannschaft/app/activity/ActivityAutomaticCreationPathsIntegrationTest.java`

## フロントエンド

検査口を正しく補正したredで、共有予定の編集保持/OFFのpayload欠落4件を実証した。初回のstub不備・翻訳待機timeoutは別履歴として残し、製品redへ数えない。修正後は `frontend/tests/unit/components/schedule/ScheduleEventForm.spec.ts` の53/53 pass、担当2ファイルのESLint exit 0。許可OFF時auto=false、PERSONAL非送信、保存済み設定の復元/送信を確認した。

## Mへの対応と実機の補強

D4からmain `9c1fdb1b1e84ca7b7acbe10da5e67c50bfed2541` を通常mergeしたMでも、台帳以外の機能差分17ファイルのblobと対象台帳行は不変とrootが独立確認した。Mの同HEAD 15 workflow runsは全success、最終checksはsuccess 28/通常skip 2。[選定M CI run](https://github.com/kenta-0420/mannschaft/actions/runs/39097041997) を参照。D4詳細テストとこの不変性・M CIを併記し、D4のCIをMのrunとして流用しない。

さらに出荷前にmain `6bb6238b2f12c27c90757ca3d63e95f0edb4fb16` を通常mergeしたNは、上流文書2パスのみの変更。rootはbackend/frontend/scriptsのGit treeと対象1726行がM/Nで同一と[確認](CMP-261017-1726-review.md)した。Nの同head CIは30 checks中success 28・通常skip 2、failure/pending 0。rootが [BE run 39102408984](https://github.com/kenta-0420/mannschaft/actions/runs/39102408984) と [FE run 39102408996](https://github.com/kenta-0420/mannschaft/actions/runs/39102408996) のsuccess・head一致を確認した。Mの実機をNで再実行したとは記録しない。

Mのscripted実機は [実機記録](CMP-261017-1726-real.md) の4 UI/4 API補完/8 foreign/PERSONALで成功した。Mの先行2試行は遅延する権限初期案内・保存時の活動同期確認をハーネスが扱わず失敗。既存UI操作の処理を補い3試行目で成功し、製品コードはその補正で変更していない。前段のWSL I/O・容量不足や、同代理人再利用による既存GLOBAL `SCHEDULE_075` は環境/fixtureの前提不備として分離した。

探索の未達と周辺の既存表示は [住民観測](CMP-261017-1726-exploratory.md) へ残し、この回帰修正の成功と混同しない。

## merge後mainの別条件での失敗と当時の保留

main `7f6e6852f0e9062f88e688abf7bff3ea44bc1457` の [Backend run 39103867155](https://github.com/kenta-0420/mannschaft/actions/runs/39103867155) はfailure。[shard 0 job 117605554320](https://github.com/kenta-0420/mannschaft/actions/runs/39103867155/job/117605554320) は4985 tests中20 failed・1 skippedで、最初の `OwnershipTransferOfferScopeContractIT$MyOffersInbox.memberCannotListPendingScopeOffer` のcontext起動OOMと19件のthreshold連鎖を確認した。4 GB heapで実行され、shard 1/3/5 success、2/4 cancelled、BE deploy skippedだった。

比較対象のM run 39097041997 shard 0は `excludeMigrationTests=true` でmigration 79 classを除外した42分50秒の実走success（FROM-CACHE 0）。N run 39102408984のshard 0は同profileの `test` がFROM-CACHEだった。mainではfull migration（重み2729のclass）を実走してOOMとなった。この条件差を残し、M/Nのgreenをmainの同量・同条件成功とみなさない。

新規 `ScheduleProxySettingsContractIT` は共有base継承と既定の `@AutoConfigureMockMvc`・`@Transactional`・`@EnabledIf` を使い、独自 `@MockitoBean`・`@SpringBootTest`・`@DynamicPropertySource`・`@DirtiesContext` はない。不要なcontext追加の根拠は未確認だが、heap dump保持objectが未解析のため、既存CMP-261004-2047と同一根因・環境flaky・今回変更無関係のいずれも断定しない。全Testのcontext cache上限10の設定は存在するが、失敗PIDの実行時読取値は未実証。

機能差分の検分合格とmain CI合格を混同せず、この時点では既存CI課題を未着手として保持し、本人の方針回答とroot判断まで完了登録PRをdraftに保った。これは当時の保留履歴であり、現在のCI改善結果は次節に記録する。無根拠retry・heap増量・dump読取は行っていない。

## CI改善後の全量回帰確認

R2 mock field名統一とnightlyのcache5/job120分/test105分への改訂後、固定source head `522bfb7fe6ea4b665c384b86411120cc5f755bc8` の [run 39135193568](https://github.com/kenta-0420/mannschaft/actions/runs/39135193568) / [run 39170400055](https://github.com/kenta-0420/mannschaft/actions/runs/39170400055) は、migration込み6 shard＋専用Archを2回連続実走success。各31658 case、failure/error 0、既存skip 17、追加11 caseはskip 0。各migration 80 class/195 case、専用Arch 299 case、shard内engine 37 case、後段guard 12 caseを照合した。

各7対象jobの安全ログはcompleteRead、実OOM/未分類marker 0、対象task実走・migration除外false、outer class複数artifact配置flag 0。rootの全task case識別と状態の多重集合比較は欠落・追加・状態差分0だった。既存skipを成功へ数えず、表示名衝突233群のJava method完全同定は未検証とする。[検分記録](CMP-261017-1726-review.md) に安全原本SHA-256と計測限界を記録した。

旧5aadの全量失敗・ローカル353 caseは履歴として保持し、条件改訂前後を同条件性能比較には使わない。runtimeの全context数・heap内訳は未計測で、CMP-261004-2047全体の約135 IT改善は実装中のまま。今回のCI/test-only変更の実機・探索は対象外とし、代理設定保存のM実機証拠は元の検証範囲を維持する。

[CI改善PR #3986](https://github.com/kenta-0420/mannschaft/pull/3986) はhead `a920be6259e8fd7fdba2f0436cfefc5f160b4dab` を通常mergeし、main merge commitは `33caf032d4ea8d33f28225326ac4610f90004646`。全量実走head `522bfb7fe6ea4b665c384b86411120cc5f755bc8` とは別headだが、backend Git tree `92badf96720ea2c15d188d4ff46ff25c28d9318d` とnightly workflow blob `c24139e65721091bf79b79dcb6d19d919c563fd6` は一致する。PR headの[必須Backend CI run 39179907298](https://github.com/kenta-0420/mannschaft/actions/runs/39179907298) と[Task-List PR Gate run 39186764926](https://github.com/kenta-0420/mannschaft/actions/runs/39186764926) はsuccess。2回の全量実走をPR head自身の結果と混同せず、当時の保留は解除する。
