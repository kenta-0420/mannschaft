# CMP-261021-0020 試練対応表

正本: 御裁可済み陣立て v4（2026-10-21）。後段差分優先。新pending APIは未実装のためHTTP契約ITでredを確認する。既存follow/unfollowの正常な認可・状態境界は初回greenでよい回帰固定として区分する。

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
| 13 | 1001件で実SQLのEXPLAIN・所要時間を記録 | 実装後の実測。未実施 |
| 22 | AC22_同じ数値IDの逆向き異種フォローはfollowedBackにならない: USER/ORGANIZATIONを逆行の両側に配置 | 新規red |
| 23 | AC23_正規化ペアは自チームIDの大小によらず相互を除外する | 新規red |
| 24 | AC24_一覧取得後に成立した相互関係の取消は自フォローとフレンドだけを消す | 新規red＋既存取消回帰 |
| 25 | AC25_自チームの異種と別チームのフォローをdataにもCOUNTにも混ぜない: follower側USER/ORGANIZATIONも含む、0件と該当1件 | 新規red |

AC9/10は設計で指定された段取り境界の単体検査であり、協力Beanの呼出順/回数/例外伝播をモックで観測する。実DB・実認可の代替にはしない。コンパイルに必要な7つの型骨格は機能を実装せず、入口はUnsupportedOperationExceptionを送出する。HTTPのpending経路は未追加。

## 出陣後に追加する FE・実機試験

試練コマンド正本の規定によりFEコンポーネント試験とE2Eは画面/API実装後に行う。以下は未実施であり、完了・greenとは扱わない。

| AC | 検証先・必須観点 |
|---|---|
| 14' | nav-friend-toolbar.spec: can(MANAGE_FRIEND_TEAMS)のみ。付与MEMBER表示、無権限DEPUTY/SYSTEM単独非表示、実効権限兼任表示 |
| 15 | 新一覧unit: 0件と403/5xx/通信断を区別、DashboardErrorStateから同じ取得処理を再試行 |
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

2026-10-21実測: 専用WSLコピー /home/kenta/verify-friend0020 で共通turnstile（/home/kenta/gradle-turnstile）を通し42件実行、成功5・失敗37・errors0・skip0、Gradle exit1。HTTP契約40件の35redは未実装pending URLの404に対する200/403期待のAssertionFailedError。起動・compile・fixture失敗なし。Facade2件は機能なし入口によるred。成功5件はAC11の4回帰とAC4の認証/slug境界。詳細は [red証拠](CMP-261021-0020-red.json)。

最初の未実装redより後のアサーションはgreen化後に到達するため、このredだけを各条件の挙動確認済みとは扱わない。対象クラス限定実行では凍結番人を除外し、AC12全体検証は出陣後に別途行う。凍結ストア差分0は確認したがruntime解析成功の代わりにはしない。
