# CMP-261017-0854 回帰試験

状態: **passed**。以下のred/green自体はrootが元XML・必要画像を独立確認済み。最終CI・実装mergeも別途確認済み（検分文書参照）。

| 回帰 | red | green |
|---|---|---|
| 対象3文言と作成者補間 | 最終f140単体spec＋変更前ea6製品8filesで5件中4翻訳assert失敗・1成功、errors0/timeout0 | 同spec＋修正8filesで5件成功・failure0/errors0、77.87秒 |
| de360/390のheader横パン | 旧nowrapで各scrollWidth509・追加right508.53125、合計4geometry assert失敗、前提成立/timeout0 | 最終真mobileでscroll/clientが360/360と390/390、追加button全端が画面内 |
| 追加操作の高さ44px | 折返し後・dialogをUIで閉じtrial click成立の状態で42px、両幅の2assertだけ失敗、timeout0 | min-h-11追加で192.8125×44px、left40/right232.8125/top599/bottom643。1case成功・soft失敗0/timeout0、53.299秒 |

## 正式環境と実行対象

正規npm ci後、installed1419件を全version/integrity/実package版照合。非ホストoptional224件のみ非導入。主要版はi18n10.4.0/Nuxt3.21.11/Vue3.5.43/test-utils4.1.0/Vitest4.1.11。単体2specはde表示・実setLocale・MEMBER権限分岐・特殊文字安全表示・空名/null防御・予定内容保持を実Vueで確認。辞書cold準備はbeforeAll、実setLocale/DOM確認はcase内で維持する。

geometry specは真mobile（isMobile:true/hasTouch:true/de-DE/AsiaTokyo、高844）、同じ最終specでred/greenを取得。権限dialogがあればSpäter entscheidenをUI操作し、trial click後に測定・画像化する。最終Vue SHA256 5c8fc0dca6cec0a24a20a3f92999232bee6d27424898de50fb777dfaa94e8b22、spec 7a01e90f818c9d3add92b48123687d3e46a25f34af994eeaa74f26a09c38af24。

実行時の元は専用ownedrootのprivate/unit-canonical-{red,green}/vitest.xml、safe-evidence/geometry-red/、geometry-tap-{red,green}/。恒久退避先 `.claude/private-archives/schedule-i18n-20261017-0854/` のunit-canonical-originals/{red,green}/vitest.xmlとsafe-evidence/geometry-*を一次参照する。127files/5,774,660bytesは退避先再hashでrootが全件長さ/SHA256一致を確認済み。旧installed depsの試行、hard前提timeout、desktop-only、旧spec余分な実行、dialogが覆った中間画像は参考に分離する。公開PRへ元credentials・認証state・private transcriptは含めない。

CSSだけの追加でscript/権限/localeロジックは不変。正式単体5件の再実行をroot判断で省略し、最終CSSは実geometryと最終CIへ対応させる。周辺の既存未翻訳や別操作の小tap等は本回帰の成功で解消扱いしない。
