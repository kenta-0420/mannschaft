# CMP-261021-0020 試練対応表

正本: 御裁可済み陣立て v4（2026-10-21）。後段差分優先。試練時点では新pending API未実装のHTTP契約ITでredを確認した。既存follow/unfollowの正常な認可・状態境界は初回greenでよい回帰固定として区分した。以下のred対応表は試練時点の記録を保ち、green実装の進捗は末尾に記す。

## BE 試練

HTTP契約: backend/src/test/java/com/mannschaft/app/social/TeamFriendPendingFollowContractIT.java。共通AbstractMySqlIntegrationTest、実JWT/実Securityフィルタ/ScopeSlugIdConverter/実Bean/実MySQL。fixtureはRepository経由、テストTXをrollback。通知配送はこの試験の観測対象ではない。

| AC | 対応メソッド・検証 | 種別 |
|---|---|---|
| 1' | AC1_片方向は表示され相互成立するとフレンド一覧へ移る | 新規red |
| 2' | AC2_双方向のみ残る行を取消し有効相手は再フォローで成立する(false/true) | 新規red |
| 3 | AC3_解除後に相手側だけに残るフォローは自一覧に混入しない | 新規red |
| 4' | AC4_実効権限を持つ者は一覧と既存フォロー取消を同じく許可する: ADMIN/権限付与DEPUTY/MEMBER/SYSTEM兼任 | pending新規red＋既存回帰 |
| 4' | AC4_権限なしは一覧と既存フォロー取消を全て403にする: DEPUTY/MEMBER/SUPPORTER/GUEST/部外者/他チームADMIN/SYSTEM単独 | pending新規red＋既存回帰 |
| 4' | AC4_未ログインは401で不在slugは404になる | 既存認証/変換境界の回帰 |
| 5' | AC5_状態を変えても行と総数を維持し名前とslugだけを伏せる: 非公開/アーカイブ/PROVISIONED/論理削除、閲覧者の相手所属も無視 | 新規red |
| 6 | AC6_公開有効相手に限り名前とslugとフォロー日時を返す | 新規red |
| 7 | AC7_同一秒の千件境界でページ数が正しく重複欠落しない: 999/1000/1001 | 新規red |
| 8 | AC8_pageとsizeを仕様の境界へ補正する＋AC8_範囲外ページは空配列と正しい総数を返す | 新規red |
| 9 | TeamFriendPendingFollowFacadeTest.AC9_チーム概要取得例外を伏せ字や部分成功に変えずそのまま伝播する | 新規red。例外はHTTP層で既存GHEの5xxへ伝播 |
| 10 | TeamFriendPendingFollowFacadeTest.AC10_認可後にsocialを読みページ内ID集合を一度だけteamへ照会する | 新規red。順番/集合/1回を検証 |
| 11 | AC11_既存数値とslug経路は無効相手を404で拒否する | 既存green回帰 |
| 12 | 既存D-1/API境界/D-3/D-3T/D-5/認可番人の全体実行、凍結store差分0 | 実装後の品質ゲート。今回のred限定実行とは別 |
| 13 | AC13_千一件の実SQLをEXPLAINしページ取得時間を記録する | 1001件/100件取得328.65ms。テストDDLの制約あり、末尾参照 |
| 22 | AC22_同じ数値IDの逆向き異種フォローはfollowedBackにならない: USER/ORGANIZATIONを逆行の両側に配置 | 新規red |
| 23 | AC23_正規化ペアは自チームIDの大小によらず相互を除外する | 新規red |
| 24 | AC24_一覧取得後に成立した相互関係の取消は自フォローとフレンドだけを消す | 新規red＋既存取消回帰 |
| 25 | AC25_自チームの異種と別チームのフォローをdataにもCOUNTにも混ぜない: follower側USER/ORGANIZATIONも含む、0件と該当1件 | 新規red |

AC9/10は設計で指定された段取り境界の単体検査であり、協力Beanの呼出順/回数/例外伝播をモックで観測する。実DB・実認可の代替にはしない。red時点ではコンパイルに必要な7つの型骨格に機能を実装せず、入口はUnsupportedOperationExceptionを送出し、HTTPのpending経路も未追加だった。

## 出陣後に追加する FE・実機試験

試練コマンド正本の規定によりFEコンポーネント試験とE2Eは画面/API実装後に行う。以下は試練時点の計画であり、実装後の結果は末尾の進捗記録に区別して記す。

| AC | 検証先・必須観点 |
|---|---|
| 14' | nav-friend-toolbar.spec: can(MANAGE_FRIEND_TEAMS)のみ。付与MEMBER表示、無権限DEPUTY/SYSTEM単独非表示、実効権限兼任表示 |
| 15 | 新一覧unit（frontend/app/components/friends/TeamFriendPendingFollowList.spec.ts）: 0件と403/5xx/通信断を区別、DashboardErrorStateから同じ取得処理を再試行 |
| 16' | 新旧両一覧unit: 取消/取消404/相互成立/409/ページ移動の成功空ページはtotalPages-1へ1回だけ再取得。総数0はpage=0。失敗では補正しない。Paginator first=page*size |
| 17' | pending一覧unit: followedBack案内はnameHidden別。公開行はチームリンク。取消成功toastの「もう一度フォロー」から同slug事前入力FriendFollowDialogを開く。hidden行は再フォローを約束しない |
| 18' | navと新旧一覧unit: 取消/フォロー成功/409後に両一覧refresh。409 SOCIAL_102はi18n情報toast＋alreadyFollowing emit。404 SOCIAL_104文言 |
| 19 | 6言語キー・mutual=false成功文言・ガイド節の整合 |
| 20 | 実機: 360px/deの折返し、横パンなし、44pxターゲット |
| 21 | 実機＋アリシゼーション: ロール横断（付与MEMBER含む）/URL直打ち/伏せ字相手の表示取消/取消→行消滅→画面操作だけで同じ相手を再フォロー→成立 |
| 24 | pending取消確認に「この間に相手がフォローし返していた場合、フレンド関係も解除されます」の6言語案内。成立済み取消後に両一覧refresh |

## 再フォローtoastの既存金型（殿承認済み）

useUndoToast.showUndoToast({summary, undoLabel, onUndo, severity})を再利用する。undoLabelへi18n済み「もう一度フォロー」、onUndoへ公開相手slugでFriendFollowDialogを開く処理、severity=successを渡す。AppToastMessageはボタン押下でtoast.removeを先行し多重押下を防ぐ。共有toast基盤の新設・新依存は不要。44px不足を実測した場合だけAppToastMessageのbuttonへ最小class追加を行い既存回帰を検証する。

## 文書整合

F01.5とdocs/security/03_role_authority_model.md §3.5へ、このAPIのcheckPermission実効権限経路についてのみ最小是正を実装PRに含める（殿承認済み）。広いロール仕様は変更しない。

## red実測

2026-10-21実測: 専用WSLコピー /home/kenta/verify-friend0020 で共通turnstile（/home/kenta/gradle-turnstile）を通し42件実行、成功5・失敗37・errors0・skip0、Gradle exit1。HTTP契約40件の35redは未実装pending URLの404に対する200/403期待のAssertionFailedError。起動・compile・fixture失敗なし。Facade2件は機能なし入口によるred。成功5件はAC11の4回帰とAC4の認証/slug境界。詳細は [red証拠](observations/CMP-261021-0020-red.json)。

最初の未実装redより後のアサーションはgreen化後に到達するため、このredだけを各条件の挙動確認済みとは扱わない。対象クラス限定実行では凍結番人を除外し、AC12全体検証は出陣後に別途行う。凍結ストア差分0は確認したがruntime解析成功の代わりにはしない。

## green実装・検証経過（継続中）

2026-10-21、復旧後の正式BE実行は64件（成功63・失敗1・errors0・skip0）。初期red42件は全件greenになった。唯一の失敗は追加したAC8の `page=2147483647&size=100` で、JPAの `Page offset exceeds Integer.MAX_VALUE` による500。通常範囲外99ページは成功した。失敗を受け、JPA offset上限を超える場合だけ通常ページと同一のCOUNTクエリから元のpage/sizeを保った空ページを返す修正を追加した。最大pageのsize=1/100、状態別・異種混在・正規化pair除外のCOUNT回帰を追加し、修正後は同製品入力を実checkoutしたCIで65件全成功・failure/error/skip0を確認した。詳細は [BE CI観測](observations/CMP-261021-0020-backend-ci.json)。

FEは親のrefresh重複取得を実際の回帰試験が検出し、兄弟一覧だけを更新する結線へ修正した後、5スイート49件成功。追加2件を含むローカル51件はWSL接続リセットで集計を取得できず合格に数えなかった。その後、head `fd54c73af1abc2e1dc48be434905f6060a5bffad` の [Frontend CI run 39564699382](https://github.com/kenta-0420/mannschaft/actions/runs/39564699382) でNode22.23.2、Lint、typecheck、全665ファイル・6529件が成功し、対象5スイート51件も全成功・skip0を確認した。新旧一覧、Dialogのslug事前入力と409、実効権限表示、翻訳済み通知、ページ補正再取得の失敗/総数0を含む。新specのmock呼出配列4箇所の型エラーは期待値を変えず修正した。詳細は [FE CI観測](observations/CMP-261021-0020-frontend-ci.json)。同等以上のCI実証によりローカル51件の重複再実行は省略する。

AC13は1001件のpending fixtureで、100件取得・total=1001を確認し、一覧とCOUNTの実SQLにEXPLAINを実施した。MockMvcリクエスト所要時間は328.65ms。詳細と制約は [性能実測](observations/CMP-261021-0020-performance.json)。これはFlyway無効・Hibernate createのテストDBで、FollowEntityが本番のuk_follows索引を宣言していない。EXPLAINのALL/filesort・推定rows=1を本番の計画や実行行数と同一視しない。fixtureは未commitのテストTX内でrollbackし、DDL/ANALYZEを実行していない。現証拠だけではfilesort支配と断定せず、本番DDL追加は行っていない。

全工程終了前のため、本節は進捗記録でありAC全件完了・台帳完了を表さない。

### 観測JSONの移設（内容不変）

`docs/evidence/`直下のJSONはcompletion証拠schema対象のため、raw観測は`observations/`へ移設した。検査器は変更していない。redの元コミットは`1bbcff59793adc3b45f0d96ff4b81013e456f384`。

| 旧パス | 新パス | SHA-256（移設前後一致） |
|---|---|---|
| `docs/evidence/CMP-261021-0020-red.json` | [docs/evidence/observations/CMP-261021-0020-red.json](observations/CMP-261021-0020-red.json) | `77d1f315a5d2fdaa9dc55721c248d9b5a9ecbca8ac238d849b3f396c0f7d205b` |
| `docs/evidence/CMP-261021-0020-performance.json` | [docs/evidence/observations/CMP-261021-0020-performance.json](observations/CMP-261021-0020-performance.json) | `cbf627d96126d8210fea9cc82973d9befa570f521d689b9ec0126e87ddb3bd92` |

### main追従・生成同期

main `3bad46f846cd094a64bd46524aed69479b0c76bf` を取り込んだhead `4c184472990bf1db2a4202bdc980083558e9979c` で、CI run `39561958899` の生成artifactを採用した。実checkout mergeとheadのGit tree SHAはともに `d4a400d3708b12d8a06bc1e9e544abc001852ff7`。新pending URL1件とDTO2件以外は既存path/schemaに意味差分なし。名前/slugはOpenAPI3.1のtype=[string,null]、Node22.23.2/npm10.9.8による生成型でもstring | nullを確認した。既存Paginationの出力位置移動は定義の重複ではない。詳細は [生成観測](observations/CMP-261021-0020-openapi-generation.json)。

初回headのnullable=true注記ではnull unionが生成されないことを実生成JSONで検出し、既存ActivityRecordResponseのtypes={string,null}へ2項目だけ揃えた。初回artifactは観測として保存し、現入力へ採用していない。ローカルOpenAPI重複生成は省略した。

最新main `7e9f82b3cae1ddd89b8a18add75217adcc811ce1` の追加分は文書/台帳7パスのみで、追従head `0872146200da916249f7c3575626fe09dfb5ccb6` のbackend treeは `0cada4cbd94208d6c0345af6bc9e8af727d30e6c`、frontend treeは `ceca7924e24f59cfca0badb3062a94c6b4b13ae6`。FE成功headの製品入力と等価である。生成同期後のheadではOpenAPI Drift、生成型、Harness、秘密情報scanが成功した。同製品treeを実checkoutしたCI run `39564699511` のBE65件（HTTP43＋Facade2＋既存Controller20）は全成功・failure/error/skip0。MAX_INT pageのsize=1/100、認可、伏せ字、型混在、正規化pair除外のCOUNTも含む。CI実checkout merge `ef2c22ac5894a5355a06ff17871338c7ba244489` のbackend/frontend treeが上記と一致することをGitHub APIで照合した。BE65/FE51のローカル重複再実行は省略する。全体CIの番人失敗と是正の状況は次節に記す。追従・是正後headの最終CI・実機/アリシゼーションは未完了。台帳完了・最終completion証拠は追加していない。

### 全体CIの番人失敗と是正（再検証待ち）

先行head `fd54c73af1` の対象BE65件は全成功したが、全体CIはshard1/3で失敗した。API Gate総数は新pending1本で3723、APIクラス数は783となる一方、総数期待3722/coverage台帳782が未反映だった。また新規Row/ViewのLocalDateTime字段各1が日時凍結番人へ違反していた。対象限定のgreenをAC12達成とは扱わない。

新pendingへ理由付き `AlwaysReachable(CORE)` を宣言し、API総数3723・coverage台帳へ宣言済み0|1を反映した。未宣言例外件数の増加は0で、日時等の違反凍結台帳は変更していない。新Row/Viewは `Instant`、既存JPAのLocalDateTime引数から唯一の正 `UserZoneLocalDateTimeParser.SERVER_ZONE` で同じ瞬間へ変換する。AC6/Facadeは非空に留めず固定日時からのInstant一致を検査するよう強化した。

この是正後のBE65/番人・生成schema・最新JARは未検証。FE入力は成功済みtreeのままだが、最終headの全CI・実機・アリシゼーションは未完了。旧JARは是正前観測として保持し、現在の実機入力へ使用しない。

## 最終結果（2026-10-22）

上記は当時の進捗履歴として保持する。最終CI・実機修正/再確認・3住民探索・復元と制約は[完了証拠](CMP-261021-0020-completion-20261022.md)、25AC対応は[正式JSON](CMP-261021-0020.json)を参照。
