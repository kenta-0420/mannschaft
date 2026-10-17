# CMP-261017-1629 回帰試験

回帰証拠: **root確認済み**。実装PR #3952 / head `3a40f88ca967c0127dd6e29f40534be7913189af`。rootがreview/real/exploratoryを明記限界付きでpassedと判定した。以下は実XMLの集計であり、最終headのCI4run成功と実装mergeをAPIで照合済み（検分文書参照）。安全原本の相対pathは `.claude/evidence/schedule-surroundings-20261017-1629/` を基準とする。

| 回帰 | 正式red | green・分類 |
|---|---|---|
| フォーム6locale/表示中切替・曜日/本人TZ跨日・実BE状態/未知値fallback | unit-red全4spec49件中21assertion失敗/28成功、errors0/timeouts0 | 最終locale/form2spec60/60成功・failures0/errors0/skip0。payload/validation・初期作成先・繰返し更新の既存試験も保持 |
| delegationの誤events経路・event.id・payload.total | 同redにdelegation16件中14失敗。scheduleId=nullや同数値/異数値IDの混線を検査 | 最終18case全成功。原XMLは48件中他suite3失敗を含むため、ファイル全体greenとは扱わない。locale/formは別の最終60greenで解消 |
| scope/role/skip切替・遅延旧応答・0件/error/retry | delegation red、旧権限応答から中間API発行を実assertionで検出 | 同18green。失敗は補助領域表示、retryは権限取得から1回、再描画で重複発行なし |
| dashboardReturnの高さ24px | geometry-red4失敗のうち3件が24px assertion。team390初期loading待機失敗は製品redに数えない | 最終真mobile4case成功、44px/帰還確認。中間team360 navigation待ち失敗は保全し、最終結果で区別 |
| Keep/Record/comments/retry/calendar前後/TabList前後の限定44px・追加翻訳 | i18n-tap-red-*の到達したassertionのみ正式red。誤h2 locator/初期dialog intercept/regex開始0件は製品redにしない。TabListはroot真mobile40×77実測 | de8/en3green、対象ボタン44px以上。ScopeTabBarとPrimeVue TabListを混同せず、修正は後者ptへ。全画面44pxの主張ではない |
| orgAdd360のglyphclip | 121px文字幅>client107px、390は137/137。実assertion失敗 | de/en org/date計4green。横積みをmobile縦積みへ限定変更し、sm既存横配置を保持 |
| DatePicker360の日付末尾clip | 実value文字幅93.875px>入力83px、実assertion失敗 | 同4green。既存であってもAC6内なので免除せず修正 |
| 個人calendar header横溢れ→modal左欠落/close画面外 | de646/client360・en535/client360。calendar red1assertion失敗/errors0 | de1green。root de360独立実機はpage360/360、modal x8/w344、close44×44実click成功。en原XML1green/errors0、report31.152秒。下記hashを照合済み |
| Upcoming View allが/eventsへ誤遷移 | unit TEAM/ORG2件で実href /events≠/scheduleの2assertion失敗/errors0 | 同unit2green。実UI TEAM10.093秒/ORG13.331秒の各case成功を原XML照合済み。ORGと同runのTEAMはerror1で、run全体greenではない。後続TEAM単独1green/errors0を区別。既存タイトルクリック機能を新設しない |

## 正式XMLのhashと件数

委任suiteが含まれるXMLの他suite失敗を隠さず記す。初期失敗や中間timeoutの原本を削除・上書きしていない。次表はXMLそのもののSHA256と集計であり、秘密値・認証stateを含まない。

- `unit-red/surroundings-red.xml`: tests=49, failures=21, errors=0; SHA256 `9ace8ceb9c4f9d1ac65ae6af4e82cc3d73a42c9591b5d8855f6171adf95a580a`.
- `unit-green/surroundings-green-locales-final.xml`: tests=60, failures=0, errors=0; SHA256 `4169cc854616110a9a4b7e2ae241c911830c8beef8ba286eaba34cdaf4b47b54`.
- `unit-green/surroundings-green-repaired.xml`: tests=48, failures=3, errors=0; SHA256 `39c92f8d9182fe3c67d3bfa76b94cf52d4261f8136253b970310d3818f44108f`.
- `unit-upcoming-red/junit.xml`: tests=2, failures=2, errors=0; SHA256 `3c727096e8b9f729045fe0b0eb471031fc7c666bc8e3b40815ea3241b41e7189`.
- `unit-upcoming-green/junit.xml`: tests=2, failures=0, errors=0; SHA256 `34cf2fedd7eb3246a1863cea19a3af41a308ee80af17fc714d10b485e2bb1635`.
- `geometry-red/junit.xml`: tests=4, failures=4, errors=0; SHA256 `ff55cd3712fa3ebdd72456744d393d67573b7fe2f578dd89a928675ebf8982f3`.
- `geometry-green-a58/junit.xml`: tests=4, failures=0, errors=0; SHA256 `cb44bf7a07dda3ff3af11d2716ebb23a79768b8c48d6c5040f38ea5723299a51`.
- `i18n-tap-red-org/junit.xml`: tests=1, failures=1, errors=0; SHA256 `2f3fdc6a344a00e56cf5dd10188b93f314bacdaa30ed998c27bc045d91e89a86`.
- `i18n-tap-red-date/junit.xml`: tests=1, failures=1, errors=0; SHA256 `f184708fa9657742f30af86ae3a3bd6fcc5000b0f0f99f7efa2ecf05cd7fd725`.
- `i18n-tap-green-org-date/junit.xml`: tests=2, failures=0, errors=0; SHA256 `df948c73817adeb6bd191a7b0c090d0e4fda867428e677beca9db6b8199fe17d`.
- `i18n-tap-green-date-en/junit.xml`: tests=2, failures=0, errors=0; SHA256 `6d92ad51d6ae0e7bf2cf946a0be454b4ab915f0074d3f8ece1bf1bf6ebb6f480`.
- `i18n-tap-red-calendar/junit.xml`: tests=1, failures=1, errors=0; SHA256 `c7d1304fc8266047ee5db73d61a6f0470b2ecf13b785beaf6de9125796a8f656`.
- `i18n-tap-green-calendar/junit.xml`: tests=1, failures=0, errors=0; SHA256 `e793c12fdf9258d6e8bc8f38a2fc38eec6510017bdb15794d62531879652f790`.
- `i18n-tap-green/junit.xml`: tests=8, failures=0, errors=0; SHA256 `004d395c1433db030f8b762a14ff8985b3e5a4d13957903430420fd3c5a63021`.
- `i18n-tap-green-en/junit.xml`: tests=3, failures=0, errors=0; SHA256 `bbcb7fe3f4f068e9f8ac885fb03bf22b712b21e1457b75d531ccce1b36a717c7`.

## CIと環境要因の限界

旧head fc8109b042bf638cff1e5a7bbb26257cdb9a1314のFE run39024702524は626files/6189tests成功・991.64秒。これは最終headのCI代替ではない。CalendarWeekGridの初回5秒timeoutの原因は未確定で、共通mount helper初回準備を空component beforeAllに分離したtest-only対応。本体25case/assertion/5秒/config/製品component不変、対象25greenを確認した。hook既定120000msを変更して合格にしたものではない。

SSR/OOMの再起動後応答回復は環境観測に留める。root最終stage判定はroot-final-stage-verification.json。安全観測archiveは全361entryの独立読戻し一致を確認済み（実機文書参照）。最終head全CIと実装mergeは検分文書へ対応する。

## calendar・Upcoming最終XMLの照合

- `i18n-tap-green-calendar-en/junit.xml`: tests1/failures0/errors0、SHA256 `c31ae4a173fcef3b047433a4befdff4b05109610accc5aa3543334646c82e14b`。report全体31.152秒・case29.931秒。
- `i18n-tap-green-upcoming-team/junit.xml`: tests1/failures0/errors0、case10.093秒、SHA256 `5efb1e9428f1e43ab67bb3cc56511bcb321899f3dbd14281d732356f3a91e181`。
- `i18n-tap-green-upcoming-org-pass-team-stability-attempt-03/junit.xml`: tests2/failures0/errors1。ORG testcaseは13.331秒成功だが、TEAMは19.281秒のclick readiness TimeoutError。run全体はgreenではない。SHA256 `1990fbcced7e9921091be1ea0e2897d2a0364bc825d0fd88870bb978d9a4cb4c`。後続TEAM単独成功が最終対応。

正しい可視CalendarGrid titleからclick→headingへ到達する。旧schedule-list-row locator誤り、標準権限案内mask、再描画unstable/detachedの前提失敗を別保全し、製品assertion redと混ぜない。calendar-upcoming-final-summary.jsonの各report hash・sourceと対応する。ORG成功はTEAM clickability前提1行追記前だが製品source同一、de calendar成功も診断変更前で製品同一。