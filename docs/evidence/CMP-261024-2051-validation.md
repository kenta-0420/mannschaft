# CMP-261024-2051 フレンド申請 検証準備記録

## 現況（候補2aac・CIとmerge待ち）

実装PR #4106の最新候補headは2aac15e6e020d03cc9d26ee17d94922ab08ed8a4。完了JSON・台帳完了化・merge/pushをこの証拠担当からは行わない。F01.5の未実装見出しも最終green/merge後に同期する。

| ゲート | 現在の根拠と残り |
|---|---|
| 実装・検分 | 索引是正と3layout CSS修正55ab0adc3d（3files/5insert/3delete）、殿review finding0。新runtime自動44/44・殿28PNG/全60寸法視認・最終検分finding0。review/real/exploratoryは殿の合格判断済み |
| CI | exact2aac FE40164501261はlint/型/fullunit成功。Backend40164501249は進行中、最終CI全体成功は未取得 |
| 実機 | old app543/testU2は8/8成功。新UI/main統合2aacはflow8/8とlayout36/36がCLI0/skip0、desktop追加もCLI0。runtimeは正常releaseしcanonical receipt exit0/cleanup=removedを直接確認。U/U2は実装branchに同blobで収録済み、docs側追加cherry-pickは禁止 |
| 探索・B0 | 3住民主目的達成/全active0、unverified6観測保存。確定3layoutは修正後の44件/画像視認で検証。B0既存targetoverlay/独立UI・IDB統合成功。fullvalidatorの既存snapshot140参照不足は不合格のままscope外制限 |
| 未解明 | old543 Backend40157405430のMFA latency4件中1失敗2297ms>1500ms。他5shards成功でも総合失敗。観測付きmainを新CIへ取り込み、原因解消とは未判定 |

[新layout回帰](CMP-261024-2051/layout-regression-summary.json)は旧app543への3body assertion redを保存し、55ab修正後のexact2aac自動44件と最終画像視認はgreen、BE/merge待ち。[MFA履歴と方針](CMP-261024-2051/mfa-history-and-next-ci.json)は旧main成功/既往失敗を保ち、閾値・測定区間・認証処理を変えず新CIで判断する。old543性能v2は関連BE/social/role/schemaとproduction blob不変の範囲で維持し、全環境同一/10M規模実証へ拡張しない。

以下は取得時点の状態を残した履歴。先行節の「未実行」「候補SHA」「次の一手」は現在の最終状態ではなく、上表と後続証拠を優先する。

## FR-01〜15 最終候補トレーサ表（全15条件の対応承認済み・CIとmerge待ち）

対象は実装候補 `2aac15e6e020d03cc9d26ee17d94922ab08ed8a4`。以下は現在の実装・取得済みgreenの対応表であり、BE最終CI成功・実装PR merge・完了証拠ゲートの通過を宣言しない。殿が14条件を先に精査し、FR-06追加unitの原本green・追加検分finding0も直接確認したため、FR-01〜15全条件のfinalTraceVerifiedを承認済み。これは実装PR merge/最終CIの完了ゲート通過とは別。FR-06は初回greenのcharacterizationでありred回帰とは扱わない。過去のredやfixture失敗は後続履歴にそのまま残す。

パス表記: BE-S = backend/src/main/java/com/mannschaft/app/social/、BE-R = 同role/、BE-T = 同team/、BE-IT = backend/src/test/java/com/mannschaft/app/social/。FE-A = frontend/app/、FE-U = frontend/tests/unit/。各クラス名は表内のpackage相対ファイル名（.java）、FE名は表内の相対パスを指す。候補source APIと名前検索APIは別経路である。


表内の試験略記の正式パス: Sources.spec.ts = frontend/app/composables/useFriendRequestSources.spec.ts、FE-A Dialog.spec.ts = frontend/app/components/friends/FriendFollowDialog.spec.ts、FE-U Dialog.spec.ts = frontend/tests/unit/components/friends/FriendFollowDialog.spec.ts、FE-A Button.spec.ts = frontend/app/components/friends/TeamFriendFollowButton.spec.ts、FE-U Button.spec.ts = frontend/tests/unit/components/friends/TeamFriendFollowButton.spec.ts、Incoming.spec.ts = frontend/app/components/friends/TeamFriendIncomingFollowList.spec.ts、PendingList.spec.ts = frontend/app/components/friends/TeamFriendPendingFollowList.spec.ts。prefixのないDialog.spec.tsは同セルに明示したFE-A/FE-Uの正式パスを指す。

証拠略号: [BE](CMP-261024-2051/backend-red-green-summary.json)は実JWT/MockMvc/MySQLの212件とcomment境界69件、[FE](CMP-261024-2051/frontend-red-green-summary.json)は131件と後続個別回帰・types-green2、[実機](CMP-261024-2051/new2aac/green44-desktop-selected.json)はexact2aac flow8＋layout36、[殿QA](CMP-261024-2051/new2aac/coordinator-final-qa.json)は28PNG/60寸法・最終検分。ローカルBE greenのsource SHAは各要約のもの。新候補のFE fullunit CIは成功、BE exact CIは現在進行中として区別する。flow番号は frontend/tests/e2e/real/team-friend-request-flow.spec.ts の下記各ケースを指す。

| 条件 | 正確な実装経路 | greenテスト・観測の対応 | 証拠・範囲 |
|---|---|---|---|
| FR-01 固定元の管理入口 | FE-A pages/teams/[slug]/nav.vue の sourceTeam/openFollow/respondToRequest → components/friends/FriendFollowDialog.vue → composables/useFriendTeamsApi.ts の follow。BE-S controller/TeamFriendsController.follow → service/TeamFriendRequestFacade.follow | FE-A components/friends/FriendFollowDialog.spec.ts「選択→元から先の確認→送信の順だけで数値元pathへPOSTしcommentを送らない」。FE-U pages/teams/nav-friend-toolbar.spec.ts「AC18/24 取消は兄弟だけ、成功と409は双方を1回ずつ取得し、再フォローにslugを渡す」。flow1「FR-01/08/09/12: 管理入口から申請→受信元を確認して返し→相互成立→UI解除が再読込後も残る」 | [FE]、[実機] flow1。固定元の表示と実POST pathを対応付け、他所属へ送信しない |
| FR-02 両プロフィール・0/1/複数候補 | FE-A components/public/PublicTeamHeader.vue（公開pages/public/teams/[slug]/index.vue内）とpages/teams/[slug]/info.vue → components/friends/FriendRequestEntry.vue の start → FriendFollowDialog.vue。BE-S service/TeamFriendRequestFacade.getSourceTeams → BE-R service/FriendRequestSourceTeamQueryService.findSourceTeams、repository/FriendRequestSourceTeamRepository.findEligibleSources/countEligibleSources | BE-IT FriendRequestSourceTeamContractIT.FR02_本人の有効権限チームだけ返し相手自身を除外する。FE-A composables/useFriendRequestSources.spec.ts「0件と取得失敗を区別する」「総数1だけ明示的に自動選択する」、Dialog.spec.ts「0/1/複数の候補を総数で分岐し、1件は選択済みと明示する」。flow2公開21候補、flow3認可プロフィール1候補/MEMBER0候補 | [BE]、[FE]、[実機] flow2/3。自己除外後の総数に基づく選択、省略可能なのは総数1だけ |
| FR-03 21候補・境界・世代破棄 | BE-R repository/FriendRequestSourceTeamRepository の LIST_SOURCES/COUNT_SOURCES、BE-S service/TeamFriendRequestFacade.getSourceTeams。FE-A composables/useFriendRequestSources.ts の load/reset・targetSlug/opened watch、FriendFollowDialog.vue の onPage/onSourceChange | BE-IT FriendRequestSourceTeamContractIT.FR03_二十一件をDB絞込後の総数とID昇順で二ページ返す、FR03_pageとsize境界を補正する、FR03_巨大offsetでも総数を失わない。FE Sources.spec.ts「21件の2ページ目が1件でも未選択を維持し、跨ページの選択を保持する」「21→1の空成功だけpage0へ1回補正する」「取得失敗では補正せず入力を保持する」「相手変更・閉じて再度開く世代の遅延応答を捨てる」「同一相手でもpageが古い応答を捨てる」。flow2で実Paginator/選択保持/選択元だけPOST | [BE]、[FE]、[実機] flow2。空成功の1回補正と通信失敗を分ける。遅応答注入はFE試験であり、実機で通信遅延を注入したとはしない |
| FR-04 実効権限・role横断 | BE-R repository/FriendRequestSourceTeamRepository の SCOPED_TEAMS/PERMISSION_FILTER/ELIGIBLE_SOURCES、service/FriendRequestSourceTeamQueryService.isEligibleSource。POSTはBE-S TeamFriendRequestFacade.follow、受信はTeamFriendIncomingFollowFacade.getIncomingFollowsのcheckPermission。FE nav.vue の canManageFriends | BE-IT FriendRequestSourceTeamPermissionContractIT.FR04_実効権限を既存resolverと同値に解決する（21例: ADMIN/DEPUTY/MEMBERのdefault/group/override/ceiling、wrong team/role/deleted group、scoped強弱、SYSTEM_ADMIN単独/併有）。SourceTeamContractIT.FR04_実効権限者の候補を返す・権限なし本人は空候補を返す、TeamFriendIncomingFollowContractIT.FR10_実効権限者だけ受信を取得できる・権限なし403・未認証401。BE-IT service/TeamFriendFollowSlugIT の ac6_MEMBERは403、ac6_SUPPORTERは403、ac6_権限なしDEPUTY_ADMINは403、ac6_非メンバーは403、ac6_他テナントは403、ac6b_権限付きDEPUTY_ADMINは成功。flow3/4で非委任MEMBER、直URL拒否、委任MEMBER成功 | [BE]、[実機] flow3/4。FE非表示やrole文字列だけの認可とはせず、本人の現在のDB権限と既存resolver同値を確認 |
| FR-05 POST直前の元失効 | BE-S service/TeamFriendRequestFacade.follow がslug解決/書込より前にBE-R FriendRequestSourceTeamQueryService.isEligibleSourceを照会。FE-A FriendFollowDialog.vue の onForbidden → 選択解除/候補再取得（自動再選択なし） | BE-IT TeamFriendRequestSourceValidityContractIT.FR05_元の現在状態を両POST形式で検証し失効なら書込ゼロ（ARCHIVED/DELETED/PROVISIONED/LEFT/MEMBERSHIP_ARCHIVED/REVOKED×slug/数値=12例、warm role cache後の剥奪も403/書込0）。FE Dialog.spec.ts「キャンセルでPOSTせず、403は選択解除・再取得・明示再選択を必要とする」。flow4「FR-04/05: 委任MEMBERは操作可能、確認後の権限剥奪は実POST403・再選択を要求し自動送信しない」 | [BE]、[FE]、[実機] flow4。アーカイブ等6種は実DB契約、実UIでは権限剥奪を観測 |
| FR-06 名前検索・空/失敗・遅応答 | FE-A components/friends/FriendTargetPicker.vue の onComplete/runSearch/retrySearch、searchSeq、searchModel/initialSlug watch。composables/team/useTeamCrud.ts の searchTeams | FE-U components/friends/FriendFollowDialog.spec.ts「AC-9: 1文字では検索 API を呼ばない」「C3 2文字は299msでは0回、300msで検索APIを1回呼ぶ」「C3 1→2→3文字の連続入力は最後の3文字だけを300ms後に1回検索する」「AC-8: 名前を入力すると候補（名前＋slug）が出て、選んで送信すると body に targetTeamSlug が入る」「AC-9: 候補0件のとき『見つからない』案内と slug 直接入力の案内が出る」「AC-20: 検索の %s は0件と区別したエラー表示になり、再検索で復旧する」。実機layoutのFR-LAYOUT-CANDIDATEで100字名＋slug選択/表示 | [FE]、[実機] layout候補12例。[追加unit選別証拠](CMP-261024-2051/fr06-name-response-characterization.json): 同FE-U Dialog.spec.tsの「FR06: 旧検索の成功応答が後着しても最新候補・選択方向・POST先を維持する」「FR06: 旧検索の失敗応答が後着しても最新候補・選択方向・POST先を維持する」が各passed、対象18/18成功/失敗0。旧A→新B選択→後着A後もB方向/POST先を保持。source78b6643cd7・app2aacとunit1file75行以外diff0、CLI0/cleanup=removed。本人候補APIの遅延試験とは別の名前検索characterizationで、初回green（red回帰ではない） |
| FR-07 公開検索と既知非公開slug互換 | BE-T service/TeamService.searchTeams → repository/TeamRepository.searchByKeyword、resolveFollowableTeamId。BE-S service/TeamFriendFollowBySlugService.followBySlug → TeamFriendsService.follow。FE公開/認可プロフィールの既存取得・認可境界、FriendTargetPicker.vueのslugInput | BE-IT service/TeamFriendFollowSlugIT の ac1_slug指定でフォローできる、ac7_アーカイブ済みは404、ac7_非ACTIVEは404、ac7_論理削除済みは404、controller/TeamFriendsControllerFollowSlugTest の ac2_不在slugは404・ac7_利用不可slugは404。flow8「FR-07: PRIVATEプロフィールの入口は非表示でも既知slugから実UI申請でき、受信元を本人が確認できる」で公開404/認可情報403も待機して確認。flow5で非公開化後の受信名保護 | [BE]、[実機] flow5/8。[殿QA]で公開/認可安定画像も確認。PRIVATEは概念表記、実API enumはMEMBERS_AND_ABOVE。既知slugによる申請可能性とプロフィール閲覧許可は別 |
| FR-08 確認・キャンセル・送信中固定 | FE-A FriendFollowDialog.vue の onPage/onSourceChange/確認state、FriendTargetPicker.vueのdisabled fieldset、TeamFriendFollowButton.vue の execute/snapshot/送信guard | FE Dialog.spec.ts「選択→元から先の確認→送信の順だけで数値元pathへPOSTしcommentを送らない」「FR-08:送信中のpageイベントは候補GETを増やさず選択元を維持する」「FR-08:送信中は実Paginatorのpage2 buttonもnative disabledまたはinert配下に置く」「FR-08:送信中のSelect更新イベントでも確認した申請元を変えない」。FE Button.spec.ts「disabledと同一tick連打を送信入口でも拒否する」。flow2でキャンセルPOST0/選択元一致、flow6キーボード送信 | [FE]、[実機] flow1/2/6。別元の件数/エラー/ABA世代もDialog.spec.tsの個別回帰green参照 |
| FR-09 受信→返し→成立 | BE-S repository/FollowRepository.findIncomingTeamFollows（TEAM/逆follow/成立除外）、service/TeamFriendIncomingFollowQueryService.findIncomingFollows、TeamFriendIncomingFollowFacade.getIncomingFollows、TeamFriendsService.follow。FE-A TeamFriendIncomingFollowList.vue の load、nav.vue respondToRequest/onPendingChanged/refreshLists | BE-IT TeamFriendIncomingFollowContractIT.FR09_受信だけを表示し成立後に除外する、FR09_TEAM受信のみで異種と送信を混ぜず逆followを判定する。FE Incoming.spec.ts「元→受信先を明示し、返しは申請元を確認ダイアログに渡す」、Dialog.spec.ts「受信の返しは元・先を固定し候補APIを取得しない」。flow1でA→B、B→A確認、相互成立/両一覧反映/再読込 | [BE]、[FE]、[実機] flow1。USER followをTEAM一覧に混ぜない契約を実DBで確認 |
| FR-10 受信の開示/時刻/認可 | BE-S TeamFriendIncomingFollowFacade.getIncomingFollows → BE-T service/TeamFollowTargetQueryService.findDisclosableSummaries → repository/TeamRepository.findDisclosableFollowTargetSummaries。開示不可は行/COUNTを残しnull/null/nameHidden。FE-A TeamFriendIncomingFollowList.vue の日時整形・条件付きリンク/返し | BE-IT TeamFriendIncomingFollowContractIT.FR10_無効非公開元は所属していても名前を伏せ行を保持する（非公開/ARCHIVED/PROVISIONED/DELETED）、FR10_受信チーム権限なしは403、FR10_未認証は401。FR09_受信だけを表示し成立後に除外する内で送受信followedAt同値/Z終端。FE Incoming.spec.ts「伏せ字行は維持しプロフィールリンクと返し操作を出さない」「UTCの受信Instantはdatetimeに保持し本文は本人timezoneのTokyo10時に表示する」。flow5非公開後に名前/slug/link/返し非表示 | [BE]、[FE]、[実機] flow5。元へ所属していても公開有効以外は開示しない。公開有効は所属有無を開示条件にしない |
| FR-11 409/202/途中失敗 | BE-S controller/TeamFriendsController.follow の retryAfterSeconds非null→202。FE-A TeamFriendFollowButton.vue の execute/startCountdown/clearRetryTimer とunmount、FriendFollowDialog.vue の onAlreadyFollowing/refreshState | BE-IT controller/TeamFriendsControllerTest.follow_NOWAIT競合_202・follow_既フォロー_409。FE-A TeamFriendFollowButton.spec.ts「followId非nullの202でも成功を出さず3回まで再送し手動再取得を提供する」「retryAfterSeconds=0でも未確定として扱い1秒以上待機する」「再試行中のprops変更でも元と先のsnapshotを維持する」「FR-11:離脱後に遅延202が返ってもtimerを作らず追加POSTしない」。FE-U Button.spec.ts AC18の409 SOCIAL_102情報toast/専用emit、Dialog.spec.ts AC-11送信失敗時入力保持、FE-A Dialog.spec.ts固定元409単一更新 | [BE]、[FE]。202/非nullfollowId/retry0/通信断/遅延離脱は障害注入FE＋HTTP契約の範囲。モックなし実機でNOWAITを自然発生させたとは記さない |
| FR-12 一覧後成立した関係の取消 | BE-S TeamFriendsService.unfollow、controller/TeamFriendsController.unfollow。FE-A TeamFriendPendingFollowList.vue の cancelFollow/load、nav.vue onPendingChanged | BE-IT TeamFriendPendingFollowContractIT.AC24_一覧取得後に成立した相互関係の取消は自フォローとフレンドだけを消す。FE-A PendingList.spec.ts「AC17/24 公開相手の取消は成立済み解除も明示し、toast操作で同じslugの再フォローを要求する」、FE-U nav-friend-toolbar.spec.ts AC18/24。flow1で取得→返し成立→UI解除→再読込の実状態を確認 | [BE]、[FE]、[実機] flow1。FR-12のOR条件の後者を既存IT/HTTP interleavingで担保。true parallel TX競合ストレスは未実施であり、『既存同時実行ITで証明』とはしない |
| FR-13 body/コメント互換とガイド | BE-S dto/FollowTeamRequest の @Size(max=300)/isExactlyOneTargetSpecified/isTargetTeamSlugFormatValid、controller/TeamFriendsController.follow。FEはcommentを送らず6locale guideを参照 | BE-IT controller/TeamFriendsControllerFollowSlugTest.コメントの既存HTTP境界を保つ（省略/null/空/300/301×slug/数値=10、301は400 COMMON_001かつquery/service前）、ac4_両方指定は400・両方なしは400、ac18_不正slugは400・境界の正しいslugは受け付ける。FE-U components/friends/friendFollowLocale.spec.ts 「AC-12: guide.follow.step2 に『ID（数字）』相当の記述が無い」「friends.follow_dialog の必須キーが空でない文字列で揃っている」（6言語）、FE Dialog.spec.ts comment未送信 | [BE] comment1の69/69、[FE]。0〜300を維持し301/不正組合せ400。受理は配送保証ではなく、ガイドもコメント入力を案内しない |
| FR-14 SSR/認証復帰/6言語/操作領域 | FE-A FriendRequestEntry.vue の start（クリック時のみloadFromStorage/useLocalePath）、両プロフィール入口、FriendFollowDialog/FriendTargetPickerのlabel/disabled/エラー、nav.vueのスクロールtabs、6locale friends.json（今回のフレンド表示正本。common.jsonは言語Save確認用で今回未変更）。UI fix55abは3filesの局所CSS | FE-A FriendRequestEntry.spec.ts「初期描画では認証復元・本人候補API・ダイアログを実行しない」「未認証%s入口は既知プロフィールパスだけでログイン復帰先を作る」「認証済みクリックだけで先固定の選択ダイアログを開く」。FE-U i18n/locale-no-japanese-residue.spec.ts。flow6「FR-14: 未認証プロフィール→安全なログイン復帰→再選択→キーボード送信、SSR候補0・自動POST0」、flow7の6locale×360/390px。layout specのFR-LAYOUT-TABS/CANDIDATE/SOURCE計36 | [実機]8＋36、[殿QA]28PNG/60寸法: tabs36 minHeight44/clip0、candidate12 clip0、source12 minHeight44。desktop2PNGも正常。原観測のdesktop42/Close40は規約のmobile44対象外として棄却、KO方向疑いは安定後非再現。SSR本人所属埋込0/再認可/自動POST0をflow6で確認 |
| FR-15 SQL計数/安定順/自然索引 | BE-R repository/FriendRequestSourceTeamRepository.findEligibleSources/countEligibleSources のDB絞込。BE-S repository/FollowRepository.findIncomingTeamFollows/countIncomingTeamFollows＋service/TeamFriendIncomingFollowFacade.getIncomingFollowsの一括概要。migration V252.20261025024939__add_follows_incoming_page_index.sql（followed_type,followed_id,follower_type,created_at DESC,id DESC,follower_id） | BE-IT FriendRequestQueryPerformanceContractIT.FR15_候補百件の実SQLは一覧COUNT二本で全所属ロードをしない、FR15_受信百件も一覧COUNTと公開概要一括の三本で取得する。TeamFriendIncomingFollowContractIT.FR15_同時刻二十一件をID降順で欠落せず返し巨大offsetでも総数を返す。BE-IT migration/FriendIncomingIndexMigrationIT.前方三列と降順二列と申請元の被覆を実DDLで保持する・他宛一万行でも受信百件の一覧COUNTを自然索引で絞り同時刻ID降順を保つ | [索引red/green](CMP-261024-2051/incoming-index-regression-summary.json): 主56/独立Arch347成功、自然plan rows4999→100、一覧/COUNTともidx/keyprefix3/using_index=true、一覧filesort=false。[性能v2](CMP-261024-2051/u2-real-performance-v2-intermediate.json): 各100×20、p95候補27.93/受信69.96ms。本人scopeはSQLで拘束するがsourceplanは小表/本人履歴434/503の偏り（COUNT ALL456、list range455＋temporary/filesort）。本人所属先行plan/10M実負荷は未実証。既存名前検索の行別件数取得は本FRの本人候補API性能測定と別 |

## 最終runtime片付け（実装merge・最終CIは待ち）

[最終cleanup選別receipt](CMP-261024-2051/cleanup-final-receipt.json)はexact2aacのCLI0/cleanup=removed、新旧所有scratchなし、8081/3001/3307空、所有container/volumeなし、旧canonical receipt不変を確認する。殿が原本を直接確認し合格判断済み。[私有4ファイル廃棄選別receipt](CMP-261024-2051/private-secret-disposal-receipt.json)はUTF-8 BOMを正規化し、basename/hashと削除後不存在だけを保存した。原本の秘密値は読取・保存せず、他copy検査hit0。transcript消去は主張しない。

[Redis選別receipt](CMP-261024-2051/redis-cleanup-receipt.json)のDB13は18keysの厳密ownership不明のため保持し、key hashのみ記録。FLUSH/ワイルドカード削除なし、reservationも保持。font/cache/npm toolsは追加unit用hold後、unit終了と殿のhold解除を受けて片付け済み（次段の補足receipt）。worktree/branchはmerge後までhold。旧共有3306 failed schemaは過去のauto-review拒否により未変更・今回対象外。最終片付けの成功と残置資産を分けて記録する。

[font/tools片付け補足receipt](CMP-261024-2051/cleanup-font-tools-supplement-receipt.json)ではFR-06 unit CLI0/cleanup=removed/scratch不存在を確認後、所有UID1000の専用font/cache/npm tools計4dirを削除しafterAbsent=true。参照/未解決項目はいずれも空、旧receiptとWindows font targets不変。DB13未知18keys/reservation、共有資産、証拠、worktree/branchは対象外として保持した。

実装PR merge/最終CI成功は引き続き待ち。FR-06追加unitはsource78b6643cd7で18/18、npm-ci/nuxt-prepare/target-vitest/assertion-gate全0、DB起動/secretEnv読込なし、receipt CLI0/cleanup=removed。単独採用commit d831d3ccd6555c58396f02050a3a8e664cb6d838は実装PRのCIgreen・merge後にmainを取り込んでから完成証拠branchへcherry-pickする予定で、今は未採用。後続PRはtest＋docs（純docsとは記さない）。実機44件/28PNGはexact2aacの実績として維持。git diffで検証headが2aacに対して対象unit1file75行だけであることを直接確認し、production byte不変の範囲で補完試験を対応付ける。

## 検証履歴


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

## 3layout redと統括判定の追補

旧red9f9cb259f3はfixture PUT405で1fail/2notrunとなり本体red不成立。正規PATCH/version0へ訂正した7a320c5c1e892af7b97064e3284e9f228c1274fdでtabs clip、長名候補viewport外、sourceSelect42<44の3body assertion red（CLI1/cleanupremoved）。SOURCE末尾Escapeのfocus前提failureは寸法assertと別であり、後続1b520734ddはlocale保存/html.lang、overlay消滅待、通常Cancel、候補左visibleclickを補強して44/境界assertを維持した。殿は新fixture6/全fr active0、DEBUGlogin3/429=0を確認。原本metrics3種類/PNG3とreceiptを選別保存。

統括は3件目のsourceSelectKO390stable outer348x42/computed42/minHeightauto/animations0を確定。tabsKO390の42pxもtabsfinding内に含む。反対にdesktop toolbar42/Close40は規約の768px以上44下限対象外で、mobile5controls44/Close44x44を根拠に不備棄却した。元住民観測のunverifiedは保持し、[統括補足](CMP-261024-2051/residents/coordinator-confirmations.json)だけを更新。KO方向行はstable public/固定元で正常、初回hydration待ち不足は試験前提でありappbug認定なし。

## exact2aac 新44件とdesktop追加確認

[選別正式proof](CMP-261024-2051/new2aac/green44-desktop-selected.json)はapp/testとも2aac15e6e020d03cc9d26ee17d94922ab08ed8a4。flow8/8（5.8分）とlayout36/36（7.1分）は各CLI0/skip0、all44Passed=true。afterAll context終了/所有Playwright process0、flow新23teamとlayout新2teamのactive0/allfractive0を確認。これはsuiteの自動合格であり、hold中runtime90507のcanonical最終receipt cleanupremovedを意味しない。

desktop追加は1280×720/en、CLI0、1login/friendPOST0、所有2teamのexact cleanup/context/browser終了を確認。option460pxをdialog512px内へ折り返し、clipなし/document panなし。殿はdesktop2PNGとzh/ko390 public/navconfirmed4PNGを視認し、字体・方向表示・長名折返しが正常と報告。選定18PNGとlayout metrics12をdurable保存したが、mobile layout画像の殿視認は未完のため、最終review/real/exploratory stageのpassedはまだ付けない。Backend40164501249成功・impl merge・runtime release後receipt待ち。

## 統括最終視認・検分とruntime正常release

[最終QA判断](CMP-261024-2051/new2aac/coordinator-final-qa.json)へ、殿の選別26PNG（6locale390長候補6、EN/DE×360/390×tab0/1/2の12、en/ko申請元2、zh/koのpublic/auth/navconfirmed6）＋desktop2=28画像実視認を保存。glyph正常、元/先slug全文、100文字候補の画面内折返し、タブはコンテナ内scroll後に各全文を読める。全60canonical metricsを当方も再集計し、tab36は最小高さ44/clip0、候補12clip0/right−viewport最大−26、source12は最小44で殿の自読結果と一致。既存44px閾値を下げていない。

殿の最終コード検分はsourceDB再認可・非公開名保護・応答世代破棄を含め追加finding0で、review/real/exploratoryは合格判断済み。runtime90507正常release後の原本receiptはsourceHead exact2aac/exit0/signalnull/cleanup=removedを直接確認・選別保存。アプリ/DBの最終専用cleanupは別作業として待つ。BE CI40164501249成功とimpl mergeは未取得のため完了JSON・台帳完了化はまだ行わない。本番公開成功は主張しない。fullB0validatorの既知snapshot不一致と独立origin限定は残存制限として維持する。
