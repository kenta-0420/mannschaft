# CMP-261026-1912 完了証拠と検証範囲

本件は特定のSQL・取得・表示契約改善を完了した。**速度完全解消の宣言ではない。** 最新統合production UIには約7秒/14秒が残り、既知403等を保持する。本陣c6のready後通常UIもlogin/dashboard/TEAM・核心0・logout200・閉鎖を確認した。再生成中の初回503や、devの個人calendarが12秒窓で未readyだったことを成功へ読み替えない。

## 採用済みの変更

| PR | 実装head / 採用main | 確認済みの範囲 |
| --- | --- | --- |
| [4140](https://github.com/kenta-0420/mannschaft/pull/4140) | head `7fc48f6f74282229b1c5bfe8bcbb180e44377807` / main `abaab7f7d9320ca4bae33faed8819e41cc98d17a` | 組織一覧・Inbox読取・みたよDTO・初期FE。[BE全量](https://github.com/kenta-0420/mannschaft/actions/runs/40344558901)、[FE](https://github.com/kenta-0420/mannschaft/actions/runs/40344558999)、[E2E](https://github.com/kenta-0420/mannschaft/actions/runs/40344558905)成功。 |
| [4148](https://github.com/kenta-0420/mannschaft/pull/4148) | head `765a3709c3547d2fa4c65467bc3ada03f2a64a61` / main `79789dbbbc81f0961a82469a5c4bf88115df8295` | 詳細みたよ・祝日再利用・カレンダー読込。[FE](https://github.com/kenta-0420/mannschaft/actions/runs/40359090017)、[E2E](https://github.com/kenta-0420/mannschaft/actions/runs/40359089977)成功。実機FE763と製品同値。 |
| [4149](https://github.com/kenta-0420/mannschaft/pull/4149) | head `a0fa43232274de50adc68634e6863519d12b4fb6` / main `ef1e9e2ad2de973efba29f5ee380d03a8b60196e` | Inboxの直接所属ロールを最新スナップショットで一括解決。[BE全量](https://github.com/kenta-0420/mannschaft/actions/runs/40370850099)全6 shard・ArchUnit・Non-JST・集約成功。ready後を含むマージ時点のcheckは成功23・正当skip1。 |
| [4151](https://github.com/kenta-0420/mannschaft/pull/4151) | head `c46be0d7b725559d23d8cbb4323af559f906c31c` / main `fbca619e6522dfb5e5e7b3820b9069f4a2caae1b` | SSR機会的prefetchのみ抑制。FE製品はedと同値。[FE](https://github.com/kenta-0420/mannschaft/actions/runs/40429713586)、[E2E](https://github.com/kenta-0420/mannschaft/actions/runs/40429713625)、Lighthouse、ready後新gate成功後に通常squash採用。 |
| [4153](https://github.com/kenta-0420/mannschaft/pull/4153) | head `03866c0456dbf69dec3950e7225001d8fc45483a` / main `c6f33de14d2bb07d01449e8cc5f4144bc8d7e167` | 自変更4filesにLazy化とAC9を統合。[FE](https://github.com/kenta-0420/mannschaft/actions/runs/40437841436)、[E2E](https://github.com/kenta-0420/mannschaft/actions/runs/40437841246)、[Lighthouse](https://github.com/kenta-0420/mannschaft/actions/runs/40437841184)とready後新gate成功。マージ時点は成功15・正当skip7。 |

実装headと採用main、実機成果物のsourceは区別する。schemaVersion 1の完了証拠はPR4153の40桁head038と同headの成功CI3runだけを記録する。先行PRや85aのCIはtopciへ混ぜない。

## AC1–9とgreenの対応

| AC | 条件と確認内容 | 成功テスト・出典 |
| --- | --- | --- |
| AC-1 | 本人所属、ACTIVE/SYSTEM_ADMIN、役割、distinct人数、参加順、archive除外、空・0・size/metaを維持し、組織数によるSQL増加を解消。日時のAPI表現も保持。 | `backend/src/test/java/com/mannschaft/app/role/controller/MeOrganizationsBatchQueryIT.java`、同`MeOrganizationsFailureTest.java`、`organization/OrganizationQueryMapperTest.java`、`team/TeamQueryMapperTest.java`。PR4140の全量CI。 |
| AC-2 | 全ソース・scope別window・canonicalRef・triage/snooze/archive・本人認可とsummary全mapを保持。出現IDだけのoverlay、ラベル系SQL0、500/501・1000件分割・空・途中失敗、role SQL一定を検証。 | `backend/src/test/java/com/mannschaft/app/inbox/service/InboxAggregationReadCostTest.java`、`inbox/InboxScopeContractIT.java`、`inbox/AnnouncementInboxRoleBatchIT.java`、`dashboard/service/RoleResolverBatchTest.java`、`RoleResolverPreviewCacheTest.java`。PR4140/4149。 |
| AC-3 | scope/my双方へ指定limitを伝播し、通常カーソル・終端・再試行を維持。 | `frontend/tests/unit/components/timeline/TimelineFeed.spec.ts`、実画面で初期limit=3。PR4140。 |
| AC-4 | 組織storeの同時取得共有、空・再試行、clear後の旧成功/旧失敗破棄、別Pinia分離。 | `frontend/app/stores/useOrganizationStore.spec.ts`。PR4140。追加teamstoreはAC-9として別記。 |
| AC-5 | BE配信元enum全値と互換値を6言語で表示。 | `frontend/app/components/announcement/AnnouncementItem.source-type.spec.ts`。BE enum/FE型とja/en/zh/ko/es/deを列挙。PR4140。 |
| AC-6 | 全PostResponse読取経路で実件数・操作者状態を返す。対象投稿/閲覧者限定、0・匿名・非所属拒否、作成経路の不要SQL/N+1抑制、詳細再読込を維持。 | `backend/src/test/java/com/mannschaft/app/timeline/TimelineMitayoContractIT.java`、`timeline/service/TimelinePostServiceTest.java`、`frontend/app/pages/timeline/[postId].spec.ts`、OpenAPI/生成型。PR4140/4148。詳細みたよ往復・reloadも実機確認。 |
| AC-7 | lock不変の正規npm ciでZod4.6.7/Vee4.15.1へ復旧。実useForm7件、SSR login/reset、nonce整合・CSP核心0、既存MinIO health200。CSP緩和/Zod3 fallbackなし。 | root確認済みの依存復旧・実useForm・SSR運用試験。環境復旧を恒久アプリテストと混同しない。通常UI結果は次節。 |
| AC-8 | 権限あり/非所属/他tenant実機、実機後3目的探索、条件を区別したAPI/UI小標本。 | 同ディレクトリの`real-exploratory-summary.json`と`source-bridge.json`。旧8b/afbdの完全3旅程はpassed。統合2eの測定は完走、最新resume/settledは部分観測、fast6操作だけgoal達成。source同値とDOMで補完。 |
| AC-9 | 通常fetchMyTeamsのsignalなしwrapperだけ共有。0件・再試行・clear後旧成功/失敗破棄・別Pinia・signal中断/last-wins・更新後の直接WithResult freshnessを保持。 | `frontend/app/stores/useTeamStore.spec.ts`、source `2ec684fb63c6fa65946d95f43861d0239bf28a35`。正式1spec/8件・ESLint・8GB full typecheck成功、runner exit0/cleanup removed。統合2eの通常UIでteams取得1回/HTTP200・表示完走を確認。最終head038の全CIと採用mergeも確認済み。 |

role一括化はSYSTEM_ADMIN優先、inactive→PUBLIC、直接所属のみ、MEMBER/SUPPORTER/ADMIN/GUEST/未知値、null/空、解除直後のタイトル・本文非漏洩を検証した。既存正本が禁止する両scope ID非NULLは作成経路と現DBを照合済み。snapshot全体・DDL・Dashboardの60秒キャッシュは変更していない。

## 先行失敗と成功

- 組織一覧は1→21組織のSQL9→129を実DBで検出後、件数不変の試験を成功させた。
- Inboxの不要COUNT・表示用読込・無関係overlayを検出後、PR4140で成功。role解決は別試験で1→21 scopeの8→128照会を検出し、PR4149で一定回数へ変更。必要なfeed queryは別集計で維持。
- みたよのJSON欠落と詳細reload不整合はBE契約・詳細specの失敗確認後に修正。
- 公開契約catalogの新active照会欠落でCIが失敗。旧39件を全保持して40件の完全一致へ同期し、局所47＋ArchUnit37＋凍結整合2＝86件と全量CIが成功。番人緩和・免除なし。
- native rendererの試験は旧configでlazy/lazy-sharedのprefetch残存を検出し、最終configでhint0・preload/scripts/import edge保持を確認した。これは不成立のHTML interception試行とは別根拠である。
- CRLF、fixture/helper初期化、heap不足、誤った観測条件の失敗は製品red/greenから除外。HTML interceptionのcontrol2試行も不成立で速度効果・回帰根拠に不採用。

## 実機・探索・反映

FE763/BE afbdで、通常ログイン、3scope、告知/詳細みたよfalse/0→true/1→reload保持→false/0、再ログイン、非所属/他tenant直接アクセス拒否を確認。自己fixture6件は全204削除。

FE8b/BE afbdのproduction成果物で、中断復帰・モバイル・連続scope切替の3旅程を完了。別目的の3役柄であり、実ユーザー3名の観測ではない。別の順次contextで通常ログイン、logout200、context/browser閉鎖、page error/CSP/prop/hydration各0。個人通信の初回/再展開3widget、360px document幅一致、6操作のselected tab1件とARIA active panel1件を確認。

過去のPR4149反映時点では、両mainを797→ef1e9の当該mergeだけへFF。保護tracked、stash、既存untrackedは前後一致、FE/package差分0。既存loopによる新APIはhealth200/UP、コンパイル済みRoleResolverのfresh batch参照あり。既存Node22でlogin/TEAM/核心0/logout200/閉鎖を確認。dev反映確認でありproduction速度比較ではない。この段落は当時の確認であり、最終c6反映は末尾の「最終採用とソース対応」を参照。

## 統合2eの最終観測

warmup/block/allowの3runはいずれもlogin完了・TEAM ready・logout200・context/browser閉鎖・核心エラー0。通常teams GETは論理イベントで各1回/HTTP200（旧8bは2回）だった。実wire量や一般化した速度率の証拠ではない。

最新のresume/settledはgoal falseの部分旅程、fastだけ6操作でgoal true。旧8bの完全3旅程passedと区別する。rootは初回通信3cardsとTEAM settled画像を目視確認。再展開画像はアニメーション途中で最終画像証拠に不採用とし、最新ARIA true・regionHidden false・DOM3、旧8bの完全探索、8b→2eのPersonalWidgetGrid差分0で補完した。正式build2e→85aの過去FE差分0と、85a→038のmain追従は別の対応関係である。最終038では自変更4filesだけが2eと同一で、大会エントリーの独立差分を含むため全FE/app同値とは言わない。独立PR4143の成功CIと最終本陣の通常UI完走で補完する。追加実機は行わない。

検証FE/APIは所有確認してSIGTERM停止し、本陣を保持。通常FEはWindows側のlisten所有情報で生存確認済みであり、WSL ssに出ないことを停止と扱わない。

## 小標本の性能と限界

| 条件 | 観測値 | 限界 |
| --- | --- | --- |
| 初期API、同DB/主体、直列IPv4、各3回 | 中央値: 組織2434→55ms、Inbox1606→1035ms。canonical payload同値。 | 旧bootRun/localと新Jar/local+e2eではscheduler条件が異なる。SQL単独効果率にしない。 |
| role batch summary、同主体/helper、各3回 | 旧12.339/1.345/1.675秒、新2.051/1.195/0.892秒。全200・全6件同じhash。 | 初回を除外せず、一般化率を算出しない。 |
| 統合2e/afbd、warmup後block/allow各1回 | 最新block: main6.783秒/calendar13.644秒。allow: main7.105秒/calendar13.972秒。 | 両run完走、allowはworker1・背景取得305件。小標本/別SW条件であり改善率・完全解消・本番SLA達成を主張しない。 |
| SSR prefetch抑制の7b成果物 | 匿名login prefetch0、modulepreload11維持。旧169 JS先読みとの集合一致を確認。 | 論理request数・reported sizeをwire量や二重転送量と同一視しない。不成立controlを因果根拠にしない。 |

旧8bの5.708/9.791秒や4.854/10.079秒は過去runであり最新値として使わない。旧dev48.9秒/12.1秒や更新直後dev値もproduction比較率に使わない。概算TABLE_ROWS、別主体EXPLAIN、全DB digestを個別request実測へ転用しない。機能契約の成功と、全体待ち時間の完全解消は区別する。

## 最終採用とソース対応

- PR4151はhead c46で全CI・ready後gate成功後に採用済み。PR4153は最終head `03866c0456dbf69dec3950e7225001d8fc45483a`、採用main `c6f33de14d2bb07d01449e8cc5f4144bc8d7e167`。FE/E2E/Lighthouseとready後gate40439769982成功、通常squash MERGEDをGitHubで照合した。
- 正式統合production build source `2e5876867b5d1c60b386c3f512838e2626c87325`はexit0/cleanup removed/4446files。2e→85aは過去の全frontend差分0。85a→038はmain追従を含むため、最終038ではREADME/Grid/store/specの自変更4files同値だけを主張する。大会エントリーの独立PR4143はMERGED/成功CIを別途照合しており、全app同値の主張に置き換えない。
- AC9の旧16d候補は直接WithResultまで共有してarchive更新後freshness回帰で差戻し。採用2ecは通常無signal wrapperだけ共有し、直接WithResultはbase同一。正式8件・lint・8GB型確認に加え、統合2eのteams論理1GET/200とUI表示を確認した。
- Windows/WSL mainはef→0ae→fbca→13b→d96→c6の採用済みmain範囲をFF反映。保護語句SHA・stash・既存未追跡・backupを保持。BE/package/lock差分0でAPIは再起動せずhealth200/UPを確認。Nuxtは既存Node22を保持しconfig再生成、匿名login200/root/form/inline4nonce整合を確認した。
- 本陣通常UI初回は再生成中/login503→timeoutで未成功、logout401/閉鎖済み。ready後の担当を一本化した1回はcompleted/dashboard/TEAM ready、page error/CSP/mitayo/hydration各0、logout200/context+browser閉鎖。その他error1/warning1は既知のまま保持し、個人calendarは12秒窓で未ready。API/Node22の手動再起動は行わず、dev値をproduction比較へ使わない。
- 完了JSONのscopeはapp、implementationはPR4153最終head038、ciは同headのFE40437841436/E2E40437841246/Lighthouse40437841184の成功3runのみ。先行PR・旧headのCIを混ぜない。
- 台帳の「完了」は今回の特定改善を指す。最新約7秒/14秒、既知feed403、CSP報告404、国設定nullクリアの既存問題を解消したとは扱わない。完了はこの範囲の改善に限定し、未解消の待ち時間や既知別件を残課題として保持する。

公開必須証拠は同一repoのPR/CI、この可搬summary、実在テスト/仕様書に限定する。private raw、アカウント、Cookie/token、API本文、nonce値、画像全量、絶対local pathは必須参照にしない。旧draftと原本出典は非公開opsで保全済み。
