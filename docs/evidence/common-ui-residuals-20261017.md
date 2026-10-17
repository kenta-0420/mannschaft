# 共通UI 第1段の検証記録（2026-10-17）

対象はThemeSelectorの3ラベルと、モバイルの共通Button/InputText/Paginator/ToggleSwitchである。既存AppHeader・通知ベルは変更せず回帰対象とした。UI直書きの横断課題全体の完了記録ではない。

## 原因と変更

- ThemeSelectorの日本語固定ラベルを既存6言語のappearanceキーへ移し、LIGHT/DARKの値・保存契約を維持した。実部品の6言語renderを先行番人とした。
- 共通操作領域の下限をscreen・767px以下で44×44pxにする。ToggleSwitchは実inputを広げ、sliderの40×24pxとhandleの既存位置を維持する。768px以上と印刷は対象外である。
- ドイツ語360pxでdocument幅447pxを観測した。幅328pxのnowrap flexに見出し301.578pxと操作群129.734pxが並んでいたため、領収書一覧の当該親wrapperをモバイルで縦積みにした。言語による特例・文字縮小・overflowの隠蔽は行わない。

## ローカル試験

| 段階 | 結果と限界 |
|---|---|
| ThemeSelector先行試験 | 準備完了後6失敗・3成功。6言語の表示・言語切替で実assertionのredを確認 |
| ThemeSelector修正後 | 対象9件＋既存locale検査46件、計55件成功。保存済み非空cacheを保持する補強後も対象9件成功 |
| 共通寸法の先行試験 | 入力42px、Paginator40px、行icon32×35px、ToggleSwitch40×24px等の実寸redを確認 |
| desktop基準 | 変更前source ea6aaee20bde9cf9ecfb31c8c0b00cbe803b1008、Nuxt3.21.11・PrimeVue4.5.5。768/1280pxの実測値を追跡fixtureへ保存。幅の過剰固定を避け、対象の高さ・visual寸法・handle位置を比較 |
| 最初のCSS適用後23件 | 15成功・8失敗、skip/flaky各0。失敗はAUX・設定のAPI stub前提4件、handle位置の実寸2件、ドイツ語横pan2件。単一runの全成功とは扱わない |
| ドイツ語の原因採取 | 360pxの1件で横pan失敗を再現し、実DOMの幅・親flex・min-width・折返し制約を採取 |
| 局所修正後の再試験 | 21件を選択したが、最初の2件がpage.gotoの120秒上限で失敗。所有runnerを穏当に中断。修正後の実寸・操作は未検証。起動前提の失敗を製品assertionのredへ読み替えない |

ブラウザ番人は外部API境界をstubした実Nuxt/PrimeVueのviewport・mouse/keyboard試験である。未知APIを正常空へ偽装せず500で拒否する。AUXだけはstub済みentryから実Nuxt routerで画面初期化するため、自然導線を検証したものではない。

## 正式CIで判明した残りと局所修正

source `37204ebcbcb6d80015e9329afd862fdb6b87d5c7` のSmoke E2E（run `39019219376`）では当該23件中17成功・6失敗だった。360/390pxの各幅で次の3類型が失敗し、retryでも解消せず、jobは既存30分上限に達した。これを全件合格とは扱わない。

- SWITCH: 実input四隅の最初の通常clickが親rootのpointer interceptで120秒失敗。44pxの透明inputにAura由来の30px丸角が残り、四隅のhit領域が欠けていた。mobileの透明inputだけを角形にし、slider/handleの丸い見た目は維持する。番人は四隅の `elementFromPoint` と通常click・checked・Space/focusを検査する。
- ACCOUNT: 詳細ボタンの遷移がcalendar-syncではなくdashboardへ戻った。対象routeは `FEATURE_WEBHOOK_SYNC_ENABLED` の公開gate配下であり、空flag fixtureでは既存gateが正しく拒否していた。当該flagだけを正契約で有効にする試験前提の修正とし、製品gateは変更しない。
- AUX: 第2scopeの組織で通常click後にDrawerが表示されず失敗。局所観測ではURL・Nuxt route・`page:finish` が更新済みでも旧Team componentのbuttonが残り、click後に新Org componentへ差し替わっていた。team/orgのfixture名を分け、対象scopeの実見出しを待ってから操作する。内部hookの追加では解決しなかったため採用しない。固定sleep・再click・forceや共有helperの変更で補わない。

祖先DOM診断の変数型は `HTMLElement | null` を明示する一行修正を別途実施した。対象lintとBundler解決のstrict限定型検査は成功したが、正式CIのfull型検査・unit・Smokeの合格を代替しない。

局所追試は、透明input・対象flag修正後の10件runが8成功・AUX2失敗、その後の対象見出し待ち修正でAUX2件が成功した（各runのskip/flakyは0）。先行8件では360/390pxの四隅hitと通常click・checked・Space/focus、disabled、ACCOUNT通常遷移、768/1280pxの既存control寸法を確認した。後続で変更したのはAUX用team/org詳細fixture名とAUXの描画前提だけで、共通CSSと先行8件の試験本体は同一byteである。単一runで10件全成功と称さない。最終対象lint・Bundler strict限定型検査・差分検査は成功した。

Windows局所環境の旧NuxtでIPC切断・画面500を観測した試行は前提未達のまま保持した。所有FEだけを同source/dependencies/SSR/API境界のまま一度再起動し、3分以内のHTTP200到達後に上記追試を実施した。キャッシュ削除やSPA化、依存再導入による回避は行っていない。

## transition取消と最終寸法の番人

source `9d5eb15dcaf17acb155809ebb2ff61606e49086e` の正式Smoke（run `39023499750`）ではSWITCHの360/390pxが `animation.finished` の `AbortError` で失敗した。四隅click後のchecked確認には到達しており、取消可能なCSS transitionのPromiseをそのまま待つ試験が、目的とする最終handle位置の確認前に中断していた。

mobileとdesktopの待機2か所を、実boundingBoxから求めたhandle offsetが既存baselineに一致する `expect.poll` へ変更した。取消例外を握りつぶさず、従来のslider内bounds・checked/unchecked offset・四隅hit・Space/focus・desktop寸法のassertionを保持する。製品CSS、共有helper、timeout/retry上限、baselineは変更していない。局所ではAPI境界をstubした所有SSRでSWITCH2件・DESKTOP2件を確認し、正式CIの全23件判定とは区別する。

## 出荷判定の残条件

既存Smoke E2Eへcommon-controlsを追加した。標準Chromium設定、既存120秒上限、1workerのLinux正式CIで全23件の合否を判定する。ローカルの未到達分や修正後の未検証を合格扱いにしない。修正後のドイツ語360px画像による目視確認、モックなしの実機・権限横断・自然導線・isMobile/hasTouch確認は別途必要である。

親の国際化課題は第1段のみ。全Vueの直書き検出や全画面・全操作要素の網羅を保証しない。後続段階も対象と受入条件を先に決め、その番人を先行させる。
