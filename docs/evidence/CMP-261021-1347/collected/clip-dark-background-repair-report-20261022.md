# ダーク背景巻戻り修正の証拠（2026-10-22）

対象 worktree: work/ranch-avatar-clips-finalize-20261021
元 HEAD: bd85fe18eac5ecd16e6d6e77ffba87e88f8ad079

## 原因と変更境界

appearance.ts が client にも非リアクティブな初期 cookie の htmlAttrs を登録していた。実際の @unhead/vue 2.1.17 の useHead / renderDOMHead を使う試験で、store が保存色を適用した直後は正しく、その後別 entry の title 更新で --bg-color が #f3efe0 に戻ることを確認した。非リアクティブだから初回 patch だけという既存コメントは実挙動と異なった。

製品修正は frontend/app/plugins/appearance.ts の先頭に SSR 限定ガードを置き、client の初期 cookie entry を登録しない変更と誤コメントの修正だけ。Cookie の形式/属性、localStorage 正本、store の API 同期、色プリセット、layout/ranch/CSS は変更しない。SSR は従来通り cookie から class/style と Pinia 初期値を出力する。新設定・endpoint・依存追加なし。

## 回帰試験

frontend/app/plugins/appearance.spec.ts（FE規約の同階層配置）で、Nuxt request/context と API 境界だけ mock。head の解決、別 entry の patch、DOM 属性適用、SSR htmlAttrs 生成は実 Unhead。store も実物。

- client: DARK既定 #18181b、DARK独自 #14241c、LIGHT独自 #ecf7f0。cookieなし→localStorage復元→API同期→別entry初回render→title patch→再render。色/class/store/localStorageを確認。
- SSR: cookieなし、不正JSON、LIGHT独自、DARK既定、DARK独自の5条件。renderSSRHead の htmlAttrs を DOM parse し class/style を確認。
- 既存 useAppearanceStore.spec.ts 12件も実行。

実red: clip-dark-background-client-red-20261022.log。3 clientすべて「store適用直後の色assertion通過、その後head再patchでcreamへ戻るassertion」が失敗し、既存store12件は通過。試験配置移動・API呼出assert追加・global stub cleanupはその後の試験改善で、対象の回帰assertionは同じ。

green: clip-dark-background-client-green-20261022.log（15 passed = client3/store12、SSR条件5は別実行のためskip）、clip-dark-background-ssr-green-20261022.log（SSR5 passed、client3は別実行のためskip）。

## 再実行コマンド

Node: [local-home]/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe
cwd: 上記 worktree

```powershell
$node='[local-home]/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
$env:TEMP=(Resolve-Path outputs/clip-dark-background-temp).Path
$env:TMP=$env:TEMP
# client（APPEARANCE_TEST_SSRは未設定）
& $node frontend/node_modules/vitest/vitest.mjs run --root 'work/ranch-avatar-clips-finalize-20261021/frontend' --config ../outputs/clip-dark-background-vitest.config.ts
# SSR
$env:APPEARANCE_TEST_SSR='true'
& $node frontend/node_modules/vitest/vitest.mjs run --root 'work/ranch-avatar-clips-finalize-20261021/frontend' --config ../outputs/clip-dark-background-vitest.config.ts app/plugins/appearance.spec.ts
```

専用 config: outputs/clip-dark-background-vitest.config.ts。Nuxt 全アプリ起動を行わない pure 実Unhead検証。import.meta.client/server の分岐を Nuxt 同様に置換する test-only transform を対象の plugin/store/spec に適用する。Windows sandbox の既定 TEMP rename EPERM を避けるため、TEMP/TMP は専用outputsディレクトリへ向ける。製品コードへの環境回避分岐はない。

## 制約

公式 frontend/vitest.config.ts でも対象2ファイルを試したが、既知の /@fs/C:/.../@nuxt/test-utils/dist/runtime/entry.mjs 解決失敗で0 tests。ログ clip-dark-background-official-config-20261022.log。この未実行を合格には数えない。Linux公式CIでのこの追加specの検証は統合後に必要。

lintは frontend cwd で node node_modules/eslint/bin/eslint.js app/plugins/appearance.ts app/plugins/appearance.spec.ts。diff checkはworktreeで git diff --check。

runtime worktree、常駐サービス、DB、共有browser、loginは操作していない。実機への採用と同条件greenはRoot担当。commit/pushなし。outputsは証拠用で製品commit対象は上記製品1ファイルとspec1ファイルだけ。

最終結果: client exit0 / SSR exit0 / ESLint exit0 / diffcheck exit0。成果はfreeze済み。


## 最終specのred同一性と公式autoimport境界の照合

最終specのまま、test-only configの APPEARANCE_TEST_BASELINE=true で git show HEAD:frontend/app/plugins/appearance.ts の原本snapshot（outputs/clip-dark-background-original-plugin-20261022.ts）をtransform入力に差し替え、修正前productで再redを取った。製品ファイル自体は書き戻していない。結果ログは clip-dark-background-final-spec-baseline-red-20261022.log。最終specの再patch後の背景色assertion（67行目）で同じ3条件が失敗する。修正後のgreenと同じspecを使うため、配置移動やassertion弱体化による見せかけのgreenではない。

初回red→最終specの変更は (1) FE規約の同階層配置 (2) API moduleの静的mockとAPI呼出assertionを追加 (3) afterEachのvi.unstubAllGlobalsで汚染防止 (4) SSRの未使用要素を削除、だけ。再patch前後の色assertion、dark/p-dark class、store/localStorageのassertionは維持。

公式 .nuxt/imports.d.ts を照合: useCookie は #app/composables/cookie、useHead は #app/composables/head、useApi は ../app/composables/useApi、useAppearanceStore は ../app/stores/useAppearanceStore。specの静的vi.mockはこのcookie/head moduleと、同一useApiに解決する~/composables/useApiを指す。Pinia storeは実物。defineNuxtPluginはNuxtではそのまま実関数を使い、pure runnerではglobal境界を注入する。公式Windowsの0tests障害により実CI実行の合格までは主張しない。

matrixは client run=client3+store12 pass/SSR5 skip、SSR run=SSR5 pass/client3 skip。skip 0ではない。二つの環境を別実行する意図的partitionである。
