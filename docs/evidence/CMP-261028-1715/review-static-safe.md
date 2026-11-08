# CMP-261028-1715 静的検分・検証対応表

- 最終対象: PR #4205、head4697a0f99045a2218dbfe6eb8c7e1d894962d8d0、製品・試験・仕様の8ファイル。初期6ファイルはc787で検分し、追加Nav差分と後続spec-only修正を別途照合。配布物の実sourceは488e4。
- 検分者: 殿である現在の Codex 自身。追加 Codex の起動・同じ差分の二重監査なし。
- 最終所見: 本件変更範囲の検分は指摘0件で合格。head4697のCI17成功・7条件skip・失敗/未完了0、正式実機8件、正式実機後の独立3住民探索を殿が確認。mobile目的の部分達成と未実証観測を保持する。全体速度改善は未実証であり、本件合格を全待ち時間の解消と扱わない。取り込み・完了証跡・清掃は別ゲートとする。
- main追従は67171を通常mergeして1回実施済み。その後のmain1d56/5a2ccは台帳1ファイルのみで、本件8ファイル・製品build入力と重ならないことをマージ前GitHub APIで再確認した。
- 以下は各時点の履歴。各節内の「未達」はその記録時点を示し、現在の最終判定は上記と最終CI証跡を正とする。

## 各部隊の確認

| 対象 | 確認結果 |
|---|---|
| 初期取得 | 既存のレイヤー取得・予定取得を Promise.all で開始。データ取得の内容・認可・ID/slugの用途は変更しない。初期化完了まではloadingを維持する。 |
| 設定復元 | storageReadyまで予定表示・永続化・fallback自動追加を待つ。復元済みの既知fallback除外と全解除を維持。ready変更もwatch対象にし、予定先着時の新fallbackを初期化後に再評価する。 |
| 離脱 | effect scopeの破棄を検知し、レイヤー応答後の旧キー移行・保存と新しいinitializeを防ぐ。既に開始したHTTP要求の強制取消へは拡張しない。 |
| Widget | 表示対象のmount後に既存LazyCalendarGridを読み込み、実DOMのv-showで準備前・エラー時は隠す。準備前のeventsは空、日付・月移動の操作はguardする。CalendarGridに自動フォーカス・global key listenerの追加はなく、focusTodayは既存の明示呼出のみ。 |
| 独立画面 | 保存viewの有無を初期化前に読む順序、mobile初回agenda、gantt、通知queryの処理を初期化後に維持。離脱済みなら後続処理へ進まない。 |
| 仕様 | F03.19の初期取得・設定確定の順序へ追記。汎用prefetch機構・依存・SQL・API・認可の追加変更なし。 |

## 受け入れ条件の対応

| AC | 自動試験・実装の根拠 | 残る確認 |
|---|---|---|
| AC1 | Widget準備中契約の実アサーションred → c787でgreen。既存の未訪問panel/非表示widget試験は前headCIで成功。 | 最終headCI、productionのmodule/data開始時刻、隠れた表示対象の実API取得。 |
| AC2 | initialization.specのlayers先着/events先着、復元前の空表示・保存不変。Widgetの非表示と操作guard。c787対象13件green。 | 実画面での表示確認。 |
| AC3 | 全解除・既知fallback除外・新fallback、hidden除外・壊れJSON・storage読み書き不可を確認。障害注入には陽性対照とfinally復元を置く。旧キー移行・保存view等の既存試験は前headCI成功。 | 最終headCI、savedview/mobileの実画面。 |
| AC4 | layer/core/TODOの失敗とretry、0件回復を対象greenで確認。新たなmodule catch/独自loaderは導入せず既存例外経路を維持。 | 最終headCIと実画面操作。 |
| AC5 | 取得中unmount後の旧キー不変・保存なし・新initializeの追加要求なし。対象green。 | 正式実機での遷移。 |
| AC6 | Widgetの日付・作成・既存導線は対象green。page初期化の後続順序を静的照合。 | 全画面・月移動・詳細・作成、mobile/savedview/gantt/query、ロールと他アカウント・URL直打ち。 |
| AC7 | b7/c787の本番ビルドと各2回の同条件計測を完了。画面DOM準備はbaseline 11.08/6.93秒、c787 5.67/14.26秒。layers→personal要求開始の直列待ちは約0.74–0.86秒から約0–0.002秒へ減少。全体改善は未実証。 | 追加Nav修正を含む488e4の本番ビルド・同条件2回計測。旧production/devの数値を流用しない。 |
| AC8 | meaningful redと13件green、静的検分を記録。 | 全CI、正式実機後の独立3住民探索、通常merge、完了証拠・本人資産の後始末。 |
| AC9 | GlobalNavItemの可視先読みをinteractionへ変更。実NuxtLinkの陽性対照を含む1569の2件meaningful redを確認。 | 488e4 CIの対象2件green、本番の可視先読み・hover/focus・クリック確認。 |

## 攻め口5類型・横断確認

- 空・0・null: 保存済み全解除、予定0件回復、既存の正規化を維持。破損JSONとstorage不可も境界として試験。
- 境界: 応答の両先着順、設定未確定、初期化中の離脱、既知/新規fallbackを区別。
- 途中失敗: layers/core/TODOの独立失敗とretryを検証。実DOM非表示と取得状態を区別。
- 認可: アクセス制御を変更しない。実機の正・負・他アカウント確認は未達。security-review起動条件となる新API・auth/security/filter・認可注釈・操作EP変更なし。
- 性能: 表示対象だけ既存部品読込と取得を重ねる最小変更。計測前に改善秒数を断定しない。
- BE/DB: 本件変更なし。ユーザーAGENTSの変更範囲に比例する検証方針により、関連FE試験を対象とする。

## 実行証跡と未実行の区別

- Linux red: source b027c4babd3007c0b41fcba08f4f3997756a5fbe、Node22.23.2/npm10.9.8、11件中3成功8失敗0skip、hook timeout0。Widgetの先行loadEvents契約がAssertionErrorで失敗したことを確認。missing initialize6件とempty wrapper1件は根拠から分離。
- Linux green: source c7879b0244b617ab363927122ab88f801413ecc1、同toolchain・対象2spec、13成功0失敗0skip、exit0。両runとも公式runnerのsource一致・cleanupremoved・errorなしを殿が実読。
- 正本は `docs/evidence/CMP-261028-1715/unit/red-green-evidence-safe.json` の正式receipt射影とsafe summary。rawログはprivate-logsへ隔離し、公開報告へ載せない。
- Windowsのred2回はtsconfig欠損とNuxt共通beforeAll timeoutであり、仕様red/greenの証拠に採用しない。主要依存とaliasの整合はadvisorが読み取り確認。旧所有プロセスは全終了。
- be85 CIの新規3失敗はstorage Proxyへの障害注入と未接続DOMのisVisible判定の問題として、小さい実Vue/HappyDOM probeで確認しc787で是正。旧CIの5skipはappearance.specのserver条件で、本件対象外。最終headでも照合予定。
- c787 現行CIは15成功・7条件skip・失敗/実行中0を殿がPR APIで独立確認。Task-List旧世代のcancelled2件は最新成功とは分離する。FE run40666212633のUnit成功、本件13件実行、既存appearanceのserver条件5skipを担当がログ照合。Smoke run40666212617 attempt2は100成功/1flakyと後続I18N1成功で全体成功。既存360px TEAMナビのflaky1件を残存リスクとし、期待値緩和・製品nav修正・追加再実行は行っていない。

## 殿様所見

設計・エラー処理・設定保存の境界に静的な欠陥は見つからない。未達のCI/実機/性能/探索を合格扱いにせず、残る検証へ進める。

## 再開時点の到達状況（4697）

- 最終候補4697a0f99045a2218dbfe6eb8c7e1d894962d8d0はmain67171を通常merge済み。殿が488からのfrontend・root package・scripts・OpenAPI差分を独立確認し、GlobalNavItem.spec.tsのみと確認。既存488配布物の実source表記を保ち、製品相当の検証へ継続利用する。
- de50の新規2件はanchor未描画で失敗。c8b5615は既存成功例に合わせたローカルmemory routerのglobal.plugins登録へ変更し、実RouterLink型・注入router一致・先読みの陽性対照と陰性・interaction・クリック・importerのassertionを保持。殿が全差分を実読し静的指摘0件。公式対象greenを先に確認してから全CIへ戻す。
- 正式実機UI6はdesktop6/mobile2の8件成功。所有者による個人予定の作成・更新・削除、別アカウントと他テナントの非露出・直接詳細403、既存共有query、mobile初回agenda・保存week・gantt復帰を原safeで照合。全logout200・context/browser閉鎖・pageError0。正式画面の合格と探索完了を混同しない。
- 最終補完画像のdesktop widget corrected2、mobile agenda corrected1、outsider denialを殿が実際に視認。旧過剰マスク画像とヘッダー重複画像は視覚合格に採用しない。desktop補完は1280x1600であり、旧性能条件と区別する。
- Nav製品probe3は構造で確認した異なる2対象のhover/focus各1回とcalendarクリック成功。初期calendar宛先hook0、別宛先1回は発生元未帰属。全体hookゼロとは扱わない。native API診断の初回TTFB約0.09–0.89秒は独立1回の補助診断で、SQL単独原因やA/B改善量の根拠ではない。
- 住民3体の探索、最終head全CI、通常merge、完了証跡と取り込み・本人資産清掃は未達。

## 正式実機後の独立住民探索

- 正式UI合格後、一般×素直、スマホ片手×表示崩れ、ADHD×中断復帰をそれぞれ新規fork_none・Sol mediumで起動。3体が同時に稼働し、別actor・別contextで自律選択した。既知の試験手順や実装内容は渡していない。
- 一般は1440x900/enで近日・来月・詳細を確認して目的達成。rootは来月一覧/詳細のマスク画像を実視認。元ログのcleanupと公開静的routeのみ・email/URL/tokenパターンなしを独立確認。画像の日時はマスクされているため、画像だけで日時を再確認したとは扱わない。
- 中断復帰は1280x720/jaでリストから空タブへ注意を移し、復帰後の同じ表示と詳細確認を達成。rootはsafe JSONと復帰画像を実読・実視認。短時間・同contextの中断だけであり、長時間失効等は未検証。
- スマホは390x844/360x800・mobile/touchで個人scopeの直近週と翌月空状態を確認。scope横断は未探索で目的は部分達成として保持。rootは原safe報告、最終390の月一覧と360の翌月空状態画像を実読・実視認。横パンなし、主要操作外枠44px以上。combobox内部42pxは親44pxと区別し、文字省略はtruncateと区別する。
- スマホの月一覧383 DOM行・高さ36444px・直近日付までの移動の迷いは未実証の観測として保持。一意の予定件数・遅延との因果は未確認。今回の製品差分による回帰と断定せず、blockerへ昇格しない。
- 全員業務変更0・logout200・context/browser閉鎖true・pageError0。自動操作のselector/heading/遷移待ちTimeoutは製品エラーと区別。rootで新たに確定した製品不具合0件。探索を実施したことと全目的達成・性能解消は別の判定とする。
- 最終head対象試験/全CI、merge、証跡採用と本人資産清掃は引き続き未達。

## 追加Nav差分の検分（488e4）

- 追加の製品変更はGlobalNavItem.vueのprefetch-on="interaction" 1属性のみ。本文リンク/AppHeader/グローバル設定/依存/認可/DBは変更しない。F03.19はscopeとhover/focus/click維持の2行追記。
- 新規GlobalNavItem.spec.ts 69行を殿が実読。実NuxtLink、可視先読み陽性対照、observer/hook、pointerenter/focus、実router importerと遷移、finallyの復元を確認。propsスナップショットやNuxtLink全stubに依存しない。dev環境ではhover importerが省略される制限を明示し、本番補完は未達。
- source1569の公式Linux nav-redは2件失敗/0成功/0skip、両方spec-assertion+zeroLengthExpectation=true。可視対照hook CalledWith後のtarget hook長さ0期待で失敗したことをソース順序とsafe正本で殿が照合。receipt exit1/errorなし/cleanupremoved。環境エラーをredに数えない。
- この追加差分に静的指摘0件。新head CI green・新本番artifact・Nav本番操作・正式実機・独立3住民・取り込みは未達として保持。

## 性能・検証準備の追加確認

- 旧4sampleは全てカレンダーHTTP200。baselineでもGrid/holidaysのHTTP開始はlayers応答より早く、Gridのネットワーク開始が新たに並列になったとは判断しない。リソース要求開始までの遅延は観測したが、CPU・優先度・接続枠への寄与は未確定。
- c787の実Nuxt link:prefetch hookを使った別probeで、カレンダーDOM準備前の可視・未訪問Nav対象15イベント/14宛先を観測。これはhook呼出の証拠であり、実ネットワーク要求数や全体遅延への因果量を示さない。
- 488e4用guard/停止/起動処理を殿が実読。bootstrap3/UI6/measure2/mapper2は旧新版SHAと許容置換回数を独立照合し、置換後全文一致を確認。測定の観測ロジック・画面サイズ・ログイン・ブラウザflagsの変更なし。実行合格の証拠とは区別する。
- c787の旧UI4マスク済み画像を殿が実視認。最終488e4の正式実機合格としては採用しない。旧UI1–4のdriver起因失敗と後始末の記録を保持する。

## 49c2の試験側ルーター修正と配布物の採用

- 488e4のCIは新規Nav2件がclick後のroute一致で失敗。陽性対照・可視先読み0・hover/focus先読み1の前段assertionは通過し、旧calendar13件はgreen。失敗したrunを全緑とは扱わない。
- 殿がtest-utils一次コードを実読。mountSuspendedは独自RouterLink adapterをdefaults.componentsへ登録し、useLink取得不可の場合にnavigate noop/クリックpreventDefaultへ落ちる。今回のCIでfallback branchが実行されたという観測までは無く、ソースと失敗結果の整合を根拠とする。
- 49c2b5f6061b969e8e7bff6c0b5551000be65f41はGlobalNavItem.spec.tsだけの5追加1削除。実RouterLink登録・stub無効・実component型と注入router一致の陽性assertを追加し、既存の先読み・クリック・async importer・後始末のassertionは維持。殿が全diffを独立実読、静的指摘0件。greenはまだ未達。
- 488→49c2は同spec以外の全Git tree不変。Nuxt既定ignoreの **/*.{spec,test}.{js,cts,mts,ts,jsx,tsx}、scanComponentsのisIgnored除外、nuxt._ignore適用を殿が一次コードで確認。project nuxt.configにignore上書きなし。製品build入力不変として、完成済み配布物488を49c2の製品相当検証に採用し、再buildは省略する。配布物の実sourceを49c2へ書き換えない。
- 488の公式receipt source一致/exit0/cleanupremoved/errorなし、4484files/123344144B/16内部linksを確認。entry/Nitro/clientの実SHA256を殿がLinux native sha256sumで独立照合し全一致。資格情報0のbrowser destination proofを実読し、logical FE3000→本人3011/API8081→既存8080/public runtime期待値/全閉鎖を確認。
- 単独green用source/helperは準備のみ。Git sparse準備の遅延とCI同等検証を踏まえ、公式targeted runnerは未起動のまま省略し、49c2のremote全CIと実機検証を並行する。未実行のローカルgreenを証拠として採用しない。
- 49c2のCIはspec48行のTS2339（ComponentInternalInstanceがprovidesを公開型に含めない）でTypecheck失敗、Unit未実行。de50は同assertionをvm.$.appContext.providesへ変える1追加1削除だけ。殿がdiffとVue公開型AppContext.provides/ComponentInternalInstance.appContextを独立実読。488→de50も同spec1fileだけなので上記配布物再利用判断を維持。de50のLint/TypecheckはCI成功、Unit/全CI/正式実機/探索の最終合格は未達。
- 新Nav製品版488の凍結2sampleはDOM10110.8/4741ms、main2123.6/1139.7ms。全calendar HTTP200、pageError0、logout200/閉鎖を殿が原safeから独立確認。baseline/c787各n2との比較を保持し、全体の遅さ解消は断定しない。APIのbrowser ResourceTimingはcross-origin制限によりrequestStart/TTFB未知であり、0やSQL待ちと解釈しない。
