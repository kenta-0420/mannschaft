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

## 出荷判定の残条件

既存Smoke E2Eへcommon-controlsを追加した。標準Chromium設定、既存120秒上限、1workerのLinux正式CIで全23件の合否を判定する。ローカルの未到達分や修正後の未検証を合格扱いにしない。修正後のドイツ語360px画像による目視確認、モックなしの実機・権限横断・自然導線・isMobile/hasTouch確認は別途必要である。

親の国際化課題は第1段のみ。全Vueの直書き検出や全画面・全操作要素の網羅を保証しない。後続段階も対象と受入条件を先に決め、その番人を先行させる。
