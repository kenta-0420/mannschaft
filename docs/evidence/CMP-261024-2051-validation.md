# CMP-261024-2051 フレンド申請 検証準備記録

2026-10-25時点の途中記録。scopeはapp。実機に不合格履歴があり、性能findingはローカル是正済み・新runtime検証待ち。最終検分・実機・探索・CIの合格記録ではなく、台帳を完了へ変える根拠には使わない。実装ドラフトPRは#4106、runtime開始時の統合HEADは46deae93b5591fcb9bd4aae59bd68aa7157b0918。最終head SHAと成功CI run IDは未確定。

正本は[F01.5 §7.1](../features/F01.5_team_friend_relationships.md)のFR-01〜15。[完了証拠ゲート](../development/completion-evidence.md)に従い、実装PRのmerge済head SHA、同SHAの成功CI、review/real/exploratory各passedが揃ってから完了JSONを作る。この準備では完了JSONもtask-listの状態変更も追加しない。

## 保存した証拠

- [BE red/green要約](CMP-261024-2051/backend-red-green-summary.json): source SHA、実測件数、suite名、SQL本数/p95、comment境界、限界を保存。
- [FE red/green要約](CMP-261024-2051/frontend-red-green-summary.json): 回帰のred/green対応、型検査の未合格、初回CI失敗を保存。
- [schema衝突回帰のred/green](CMP-261024-2051/schema-regression-red-green-summary.json): 契約2 assertion失敗から41/41成功、既存契約比較と正規原本hashを保存。
- [runtime性能・実機再試験要約](CMP-261024-2051/runtime-performance-retest-summary.json): app46のFlyway実測、受信索引finding、cleanup後履歴、失敗再試験と修正を保存。
- [受信索引回帰のred/green](CMP-261024-2051/incoming-index-regression-summary.json): 自然planの4999→100、canonical DDL hash、主56+独立347、receipt/残Testcontainers0を保存。
- [実行receipt・JUnit件数](CMP-261024-2051/selected-run-receipts.json): 原本から許可キーを選別して直接取得。全runのcleanup=removed。本文ログ、認証入力、JWT、cookie、環境ファイル、OpenAPI全文は含めない。

原本は私有実行領域と各担当campaignにあり、ここには永続的な最小要約を保存した。BEのsuite件数はprivate要約とcampaignの記載、FEのsuite件数とsource SHA/exit/cleanupは原本receipt/JUnitを直接確認した。テストコードへの参照は実装PRの最終SHAへ対応付け直す必要がある。

## 実績と限界

BE red4（fe7316ab1808e258e7aae36e92a455b67d7216ca）は81件中80 assertion失敗、1成功、error/skip=0。候補・権限・incomingの未実装404、元失効の期待403に対する201を観測した。先行red1〜3の環境起因失敗は契約redとして数えない。

BE green1（daa8a1a7a586200b59ff0bb2d42ff2b0b41431e8）は212/212成功、ArchUnit37、freeze integrity2、standalone architecture347成功（各error/skip=0）。comment1（3688afb716e58c4d214b26bf647e0858b9d7a39c）は69/69成功で、slug/数値のcomment省略/null/空/300/301境界10追加ケースを含む。HTTP契約は実JWT/Security/MockMvc/Testcontainers MySQLであり、実ブラウザ検証とは区別する。

FR-15の100行×20回は候補SQL40本（2/page）、受信SQL60本（3/page、一括概要を含む）。HTTP/JWT込みp95は候補69.069666ms、受信78.619749ms。EXPLAINはddl-auto=create、Flyway無効のEntity生成schemaであり、本番migrationの索引証明ではない。後続のFlyway適用専用DBでSHOW INDEX/EXPLAIN/HTTP性能を取得したが、下記の受信索引findingによりFR-15は不合格。

FE green1（91092f544bbc881f676f9ed4ad449221fe2ed925）は10files/131tests成功、unhandled=0、heap8192のtypecheck exit0。後続のbusy、旧件数/エラー/世代、日時、409二重更新、Paginator物理無効化を個別red→affected greenで確認した。pager-greenは15/15成功。詳細runとSHAは保存JSONにある。DOMやcomponentイベントの注入を実ブラウザ再現とは扱わない。

生成型合流後types-green（0c8444c0d534f3d1a31f429cae67c6d67f47cd0e）は22/22成功・対象ESLint成功だが、typecheckはEntityCreateDialogの生成schema欠落3件でexit2。このschema衝突は下記BE契約修正で是正済み。後続FE types-green2（e2545f9d8bf5100791d820043ccfeb84138b1ba1）は3files/22tests green、heap8192のtypecheck exit0、ESLint max-warnings=0 exit0、runner exit0/cleanup=removedを確認した。receipt/JUnitおよび必要なログ集計・hashは選別保存済み。typecheckの既存Duplicated imports警告は残るがTS error行は0、eslint.logは0bytes。個別コマンドexit0は殿の正式報告、全runner exit0はreceipt直接読取による根拠として区別する。PR #4106初回CI run40149069435/job121036557968も新E2E specのno-empty-pattern 7件で失敗した。E2E修正9eec97be14では2filesのlintがmax-warnings=0でexit0（private結果直接確認）。最終統合SHAの再CIは未確認であり、lint成功をCI全体成功とは扱わない。

OpenAPI schema是正は先行red834a71df8395f49897593dd0e035051a90cf7af4で契約2 assertion失敗・error/skip=0、ArchUnit37とfreeze2は成功。fix e1836e16b4後、正規生成元5880333e21fa0570f2c7b4b354de73c11f61b881のschema-green1は契約2+ArchUnit37+freeze2の41/41成功（error/skip=0）、両receipt cleanup=removed。一般エラーをCommonErrorDetailへ分離し、既存同名確認4schema、フレンド5schema/2pathsの不変をcomparisonで確認。正規OA commit5fe9c33d1af8c869c3b1154b3bfd60f3ef86d5b7、原本SHA256はb773146251944012149159a56011c4da966b83edc57329d69eb85368b256a20d（6367683bytes）。原本/WT/gitblob一致は殿確認報告として記録した。

## FRと現時点の根拠

BEの表記はbackend/src/test/java/com/mannschaft/app/social/配下、FEはfrontend/配下。以下は試験への対応であり、各FR全体を合格と宣言するものではない。

| FR | 自動試験の根拠 | 残る実観測・最終確認 |
|---|---|---|
| 01 | TeamFriendsControllerの数値path/排他契約、app/components/friends/FriendFollowDialog.spec.ts、nav-friend-toolbar.spec.ts | nav固定元の表示・確認方向と送信 |
| 02 | FriendRequestSourceTeamContractIT.FR02、FriendRequestEntry.spec.ts、useFriendRequestSources.spec.ts、Dialogの0/1/複数 | 両プロフィールURL入口と自己除外後候補 |
| 03 | SourceTeamContractIT.FR03の21件・安定順・総数・補正、Sourcesのページ跨ぎ/旧応答/空成功 | 実UI選択保持とbusy中Paginator |
| 04 | FriendRequestSourceTeamPermissionContractIT 21ケース、RoleService同値、既存nav権限 | 権限あり/なし/他テナント/匿名の直URL・HTTP |
| 05 | TeamFriendRequestSourceValidityContractIT 12ケース、両body・6失効・warm cache後403/write0、Dialog再選択 | 実UI選択後の剥奪・入力保持 |
| 06 | tests/unit/components/friends/FriendFollowDialog.spec.tsの1/2文字・299/300ms・同名slug・0/失敗、検索世代 | 実UI検索と失敗案内 |
| 07 | service/TeamFriendFollowSlugIT、既存404 SOCIAL_104写像 | 既知private slug成功、両プロフィール閲覧境界 |
| 08 | Dialogの確認前/取消POST0、Buttonの同tick再送、busy-red→greenとpager-red→green | 実ブラウザの入力/ページ物理抑止とキーボード |
| 09 | TeamFriendIncomingFollowContractIT.FR09、Incomingの方向/返し、Dialog固定方向 | A→B→B→A成立と送受信/成立一覧反映 |
| 10 | IncomingContractIT.FR10の非開示null/COUNT/認可/同Instant、Incoming伏せ字・datetime | 伏せ字リンクなし、時刻表示、他team拒否 |
| 11 | 既存Controller 202/409、Button最大3回/途中失敗/unmount、Dialog409の単経路 | 実API状態反映。202/通信失敗はモックなし実機と別のFE障害注入根拠 |
| 12 | TeamFriendPendingFollowContractIT.AC24の実DB HTTP interleaving、PendingList確認 | 取消/返しと一覧後成立の実状態。真の同時TX試験済とは記さない |
| 13 | comment1の10追加HTTP、XOR/slug400、friendFollowLocaleと6locale案内 | 配送保証なしのガイド/実表示 |
| 14 | Entry初期候補/認証復元0・クリック復元/安全復帰・自動送信0、locale番人 | SSR HTTP本人埋込0、360/390px、6言語、keyboard、再認可 |
| 15 | FriendRequestQueryPerformanceContractIT 2ケース、100行SQL定数2/3・p95、FE単一候補API | Flyway実索引/EXPLAIN/HTTP性能と最終実装N+1確認 |

## runtime・実機・実性能の後続履歴

runtime run1はbootJar後のcold Flyway V13.045 CREATE TRIGGERでMySQL1419（binlog/SUPER不足）となりexit1/cleanup=removed。seed/FE/実機/住民は0。所有controller一時SIGSTOP/Java継続・同世代CONTはインフラ対応履歴であり合格証拠ではない。旧共有MySQL3306のfailed DBをDROP/再作成し既存root資格情報を注入する案はauto-reviewに拒否され、未実行のまま旧DBを残置した。その拒否を迂回せず、新たにtask-owned Docker MySQL3307・新volume・新task credentialを作成し、log-bin-trust-function-creatorsはこの専用serverだけに設定した。run2はこの専用MySQLでBE UP/FE200まで進んだ。共有serverのglobal設定・SUPER権限・既存root資格情報は変更していない（殿の訂正報告、接続情報は保存しない）。

migration旧報告1226は私有progress helper固定値の誤表示であり、「SQLファイル総数1226」という中間説明も訂正する。app46の実SQLfiles・正本validated/applied・historyはすべて1224との殿訂正。直接採取historyも1224成功（versioned1223+repeatable1）。runtime直下のmigration-count-definition-proof原本で実SQL数・seed前/性能history一致・validated/applied1224を確認し選別保存済み。1226適用済とは記さない。

app46の実機run2は2pass/2fail/1interrupted/3notrun、Playwright CLI exit130。初回メンバー権限案内maskのテスト前提漏れで、実UIの「あとで決める」を6言語で扱うtest-only修正5c1cf7503fを追加。終了後は所有fr-% active0/browser children0、app46のBE/FEを維持した（殿報告）。次の独立immutable再試験H=8faf1a1644507bb9905320a6cca5d767f8a7463bはapp46へ接続し5pass/3fail・exit1/cleanup=removed。過去不合格を上書きしない。

Hのcase5/8は存在しないvisibility enum PRIVATEをAPIへ送ったテスト前提誤り（期待200/201に対し400）。PRIVATEは非公開という概念名で、実APIではMEMBERS_AND_ABOVEを使用する。case7は44px閾値に対し43.74755859375px。Dialog enterのscale中計測が疑われるが元のtransform/stable値がなくappbugとは未確定。b35bac63b7は実enum、animation完了後の連続stable計測、当該pageの失敗前PNGを追加し、44px閾値を維持。root差分レビューはfinding0。新2GETのAPI台帳登録b9e123be84は番人52/52（error/skip=0）・receipt exit0/cleanup=removed。app46とfixのmain tree hashは一致しておりtest-onlyだが、CI全体成功の根拠にはしない。7a9a2fd0940c3dafb1d6ae16455c3de067d87fd1までのapp46差分はBE番人/freeze/E2E specの3filesだけとの殿確認だが、下記BE index migration追加予定により7aは最終HEADではない。

app46の専用Flyway適用DBで100行・各20回（warmup1回除外、HTTP/JSON decode込み）を実測し、候補p95=46.419706ms、受信p95=95.398035ms。incoming mainはALL/key=null/推定105行、COUNTはuk_followsの先頭follower_type部分だけで推定105行。既存uk_followsはfollower_type/follower_id/followed_type/followed_id順で受信先team/type先頭の索引がない。全TEAM followsに比例する計画のため、p95の小ささだけでFR-15を合格にしない。新findingは下記index回帰でローカル是正済みだが、旧app46実測の不合格は保持し、新HEAD/新runtimeで再検証する。

性能用所有team101/101のAPI softdeleteは成功、activeTeams0/activeFollows0、候補baseline0に戻った。一方物理follow履歴100は残り、削除targetの元管理者によるincoming取得が200・100行/総数100となる現行履歴挙動も観測した。cleanup成功を物理履歴0と記さない。専用DBを最終廃棄する方針で追加DMLは行わない。実機全8合格・3独立住民探索・最終CI/レビュー・完了JSONは未取得。


## 受信索引の是正と残る検証

試練c33fb3e807e562ccdd25ef9399dd6b7ad8123f0eは対象100・他宛10000・全follow10101の実MySQLで、結果100/COUNT・同時刻ID降順・reverseを通した後、自然選択keyが期待idx_follows_incoming_pageに対しuk_follows、期待索引列に対し空で2 assertion red（org.opentest4j.AssertionFailedError、error/skip=0）。全主検査では41件中2失敗。fixtureや起動失敗はredに数えない。

修正91543867bcc666a7f32a3312e8e7436cf1bbe9bcはV252.20261025024939__add_follows_incoming_page_index.sqlを追加。canonical列は(followed_type,followed_id,follower_type,created_at DESC,id DESC,follower_id)、SQL SHA256=8daea4249e80033f208088e29cd960944ae3a5d69d1d0ca26530d23416b8658f。既存schema2本と新SQLは実適用resourceとgit blobが一致。seedは同じ10101行/SQL/全assertionを維持し、setAutoCommit(false)/commitの2行だけtransaction化した。

一覧/COUNTの自然planは旧uk_follows/follower_typeだけ・推定4999行から、新idx_follows_incoming_page/ref・前方3列followed_type/followed_id/follower_type・推定100行へ変わり、両using_index=true、一覧filesort=false。主56/56と独立Arch347/347はすべてerror/skip=0、receipt exit0/cleanup=removed、読取docker監査の残Testcontainers0。API/OA/業務Javaは変更せずsecondary index1本の是正で、OA再生成は不要。

543d63324e2756931d1b72591bdf4ae347198552は915との差docs-only。rootレビューで見つかった「DDL追加なし/Flywayなし」の古い2bullet矛盾も設計書側で同期した。runtime run3は543でbootJar進行中との殿報告。runtimeは旧1224本from-scratch済みDBへ新1本をincremental適用し旧version/checksum/successを照合する。ローカル小型試験はcanonical2本+新DDLであり、全Flyway再生の代用ではない。最終Backend CI40157405430はmigrations_changed=true/excludeMigrationTests=falseで全from-scratchを検証予定だが、最終成功は未取得。FE40157405464/real-smoke40157405425/OpenAPI40157405387/types40157405442も進行中との報告で、合格扱いにしない。

旧Backend CI40150260637のshard3は既存MfaBackupCodeLatencyITが4件中2失敗（error/skip=0）。全不一致1675ms・最後一致2558msで閾値1500msを超えた。main最新run40148629302/head d4f8b6ece263fb0ea7f6e7d367e6fefb364cea12の同4件は4/4成功、全shard成功。auth差分なしと裏取ったが、同時workerの集計試験を負荷原因と断定するCPU/IOサンプルはない。過去不合格の原本を保持し、新最終CIの再結果で確認する。

## 完了前に埋める証拠

1. 最終統合PRのhead SHA、merge結果、同SHAの成功CI run ID、殿の検分結果。生成型typecheckとE2E lintの修正後ローカル結果は取得済みだが、統合CI全体の成功は未確認。
2. 固定clean SHAの実API/実DBで8シナリオの実機結果。権限横断/直URL、private既知slug、匿名復帰、SSR、mobileを実績に対応付ける。
3. 受信索引finding修正後の新HEAD/新runtimeでhistory・索引/EXPLAIN/HTTP性能を再取得。旧1224件のversion/checksum/success一致と新migration追加分も確認し、所有データのcleanupを記録する。
4. 実機の後に独立3住民（一般、中断、多言語/スマホ）の探索。各runId/persona/journey/source SHA/目的/証拠、殿裏取り、確定不具合のred/green。
5. 上記成立後だけdocs/evidence/CMP-261024-2051.jsonと台帳完了PRを作る。実装変更を含むappのreal/exploratoryにnot-applicableは使わない。

## B0気づき箱への登録経路

[B0_RUNNER](../prototypes/B0_RUNNER.md)の既存actual-resident-artifacts overlayを使う。3住民の原本を1つのdocs/prototypes/.b0-local/run-*.jsonへ束ねる。generatorはrun-*.json/blocked-*.jsonのmtime最新1件だけを読むため、住民ごとの別ファイルだけでは全員を取り込めない。

登録にはregistrationSource=actual-resident-artifacts、trusted=false、status=observed-unverified、runId、environment.sourceHeadが必要。personasは一意id/personaArchetype/runId/sourceHead/evidencePath、journeysは一意id/personaId/purpose/runId/sourceHead/evidencePathを持つ。insightsはid/featureKey/title/detailに加え対応personaId/journeyId/personaArchetype/originalRunId/evidencePath/status=observed-unverifiedを保持する。urgencyはurgent/normal/when-free。原本にIDがない場合は登録時付与と明記し、固定P01〜P20へ変換しない。気づき0件でも探索を行った宣言と原本証拠を保持する。

専用worktreeでB0_INCLUDE_LOCAL_OVERLAY=trueを指定してgenerate-beta-inventory-board-data.pyを実行し、validate-beta-inventory-board.mjs --self-test-overlayで整合を確認する。生成ボードを開くとb0RunOverlay.insightsが既存IndexedDBへ重複なしで入る。runner/dynamic runner実行やcoverage合格を意味しない。本陣の既存dirty beta-inventory-board-data.jsは上書きしない。現在は住民未実行のため観測登録も未実施。

専用ボード用の既存serverスクリプトは見つからない。空きport確認後、専用WTでPython標準http.serverを使い127.0.0.1へ限定公開できる（例: python -m http.server 8767 --bind 127.0.0.1 --directory docs/prototypes）。同じブラウザプロファイルでhttp://127.0.0.1:8767/beta-inventory-board.htmlを開くと既存初期化readInsights→syncAlicizationInsightsがoverlayを取込む。手順特定のみで、server起動・生成・疑似登録は未実施。DB名はmannschaft-beta-inventory-boardだが独立portは別originなので、既存ユーザーの他originの箱と同一とは扱わない。JSON取込UIはなく、既存ユーザーと同じ箱へ追加するには元boardタブで検証済overlayをメモリ上へ渡して既存同期関数を呼ぶ経路の実確認が必要。生成board-data.jsはcommitせず、観測原本と検証済overlayを永続保存する。

## U2実機成功・性能v2の中間追記

[選別中間証拠](CMP-261024-2051/u2-real-performance-v2-intermediate.json)を保存。app543/testU2=4d43a774の独立再試験は8/8pass・CLI0・receipt0/cleanupremoved・contextsclosed/fractive0。app543との差はtest2filesだけ。locale前提のrun3（130）とU（6pass/2fail）の失敗を保持し、70a61f65/4d43a774はfixture・観測訂正として扱いappbug redgreenに数えない。158PNG・24metrics（最小122×44px）・79URLが実file保存されたが、zh/ko画像tofuを殿が確認しており私有font補正/再撮影前。全locale視覚合格は保留。

新runtimeは旧1224履歴/checksum/success不変と指定新1成功=1225、同owned container/seed record・seed hash不変を確認。性能v2は100行×20回、p95候補27.930907ms/受信69.956305ms。自然incomingはidx_follows_incoming_page/ref/推定100・filesortなし。所有100followをAPI unlinkして物理残0、101team softdelete後active0。旧v1履歴100を削除した意味ではない。

source新planはCOUNTでteams ALL推定456、一覧range455とtemporary/filesort。本人userId束縛、SQL/索引は新旧不変だが、本人TEAM履歴434/503=86.28%（現在active team紐付1）の小表偏りであり、本人所属先行や10M規模の性能を実証したとは記さない。住民探索・最終CI/mergeは未記録、完了JSONと台帳完了化は行わない。先行節の待ち状態は各当時の履歴として残す。

## 3住民の探索とB0独立統合確認

3住民がapp543で主目的を達成し、全context/browser active0まで終了した。[探索概要](CMP-261024-2051/residents/exploration-registration-summary.json)と3選別原本・選定PNG8を保存。R1 jaの長名候補はみ出し/42px操作/R3返し後reload成立、R2 enの送信直後空一覧で迷い/Close40、R3 deの360/390タブclipを計6件のobserved-unverifiedとして[単一actual overlay](CMP-261024-2051/residents/actual-resident-overlay.json)へ登録した。統括EN390再現は別coordinatorSupplementで分離。バグ確定やcoverage昇格とは扱わない。R2厳密context開始未採取は原本どおり維持し、R1/R3のcontext区間にあるR2操作時刻を同時探索の区間根拠にした。R3補助入口の成立後再操作はauto-review拒否で未実行のまま。

自dwtだけで既存generatorは検算成功。full validatorはtracked GitHub snapshot515参照に対しtask-list655参照（missing140/extra0、snapshotはHEAD blobと一致）の既存版ずれでline46停止。[exact差分](CMP-261024-2051/residents/b0-full-validator-discrepancy.json)を保存し未解決として扱う。補助の既存validateRunOverlay/self-testブロック無変更実行は宣言/空insights受入・8不正入力拒否に成功したがfull合格へ置換しない。生成board-data.jsはcommitしない。

[既存UI/IndexedDB統合試験](CMP-261024-2051/residents/b0-indexeddb-integration.json)は127.0.0.1:8767の独立origin/fresh contextで、実overlay6件自動同期・既存手入力フォーム1件との同store共存・再読込後7件/unique7（重複なし）を確認。登録元/persona/journey/runId/evidencePathを照合、正規UI「すべて」で7カードを表示した。元ユーザーの気づき箱へ書いたことは意味しない。cross-cuttingは既存fallback未設定stageのため初期B0ではフィルタ外となり、手入力保存で未設定へ移る既存UI制約を記録。既存取込はstatus=inbox/registrationSource=alicizationへ正規化し、trusted/sourceHead/originalRunIdはoverlay原本で保持する。画像はscreenshotPathのみでBlob自動取込なし。試験手入力は住民観測に混ぜずfresh context内だけに置き、所有browser/HTTPserverは終了した。

統括の後続裏取りではEN390の長名候補viewport外はみ出しとタブラベルclipの2件を確定し、FE別WTでbrowser red先行の修正準備中。[統括補足](CMP-261024-2051/residents/coordinator-confirmations.json)へ分離し元住民観測のunverifiedは保持した。KO方向行は安定後DOM/元→先全文/opacity1を確認し再現せず、元frame欠落原因は断定しない。glyph判読は確認済み。2件未修正のため実装完了とは扱わない。full validatorの140参照版ずれは殿が本戦役外と判断し、targeted既存overlayvalidatorと実UI/IDB経路でB0登録を評価するが、full validatorの不合格自体は残す。
