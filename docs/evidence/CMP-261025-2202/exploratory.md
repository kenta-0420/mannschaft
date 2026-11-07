# 表示設定の住民探索・統合判定

2026-10-26、実機TEAM/ORGの2件が成功した後、3名を並行起動した。各担当は別のテスト利用者・独立BrowserContextでログインし、操作列ではなく目的を与えられて実UIを探索した。対象製品コードは`48d6fc7349`、実行時headは試験初期化だけを直した`c6431d71d8bb8371b9f68fada63b4a3f7fbc5c22`。Terraが利用できないため、既存役割規約に従いSol / mediumへ代替した。別Codexによる入れ子の検分ではない。

| 担当・人格×攻め口 | 独立した目的・結果 | 記録 |
|---|---|---|
| id3 ADMIN、高齢・低リテラシー×中断・放棄 | 掲示板をMEMBER OFF/SUPPORTER ONで保存。別画面から戻って保持を確認し、MEMBERプレビューから通常管理者表示へ復帰。達成。 | [管理者](resident-admin.md) |
| id12 MEMBER、スマホ片手操作×表示崩れ | 組織の予定と掲示板へ通常UIから到達し閲覧。info入口は非表示、掲示板入口は表示。390/360幅の両方で達成。 | [メンバー](resident-member.md) |
| id5 SUPPORTER、一般×隙間狙い | info入口は表示、掲示板入口は非表示。TEAM/ORG往復で混在なし。掲示板直URLの既存許可を維持し、管理`/modules`は通常画面へ復帰し保存要素なし。達成。 | [サポータ](resident-supporter.md) |

各担当のcontext/browser/runnerは終了済み。DB直接操作・ワイルドカードDML・APIによる設定変更・他利用者とのセッション共有は行っていない。親は住民専用TEAM25/ORG22の名称・slug・内部IDを照合し、各DELETE204→GET404を確認した（[cleanup結果](resident-cleanup.json)）。専用FE/APIのPID・開始世代を照合して停止し、専用ポート13316/16389を照合したMySQL/Valkeyコンテナも撤去済み。

## 視覚確認と親の照合

管理者とメンバーは`isMobile/hasTouch=true`、390×844・360×800、jaで実画像とDOM寸法を確認した。サポータは1280×720の1条件のみ。全desktop条件を確認したとは扱わない。親も代表画像を実視認し、新規設定UIの2列と復帰結果を確認した。

- [390pxの設定画面](screenshots/admin/390-04-settings.png)、[360pxの設定画面](screenshots/admin/360-04-settings.png)、[保存成功](screenshots/admin/360-05-saved.png)。ページ横はみ出し0。switch44×44、関連label88×44、保存buttonは高さ44以上。
- [メンバーの通常メニュー](screenshots/member/390-feature-menu.png)、[360pxの予定詳細](screenshots/member/360-event-readable.png)。省略された予定名も日セルから詳細を読め、目的は達成。
- [サポータのORG戻り](screenshots/supporter/10-org-return-stable.png)、[管理URLからの復帰](screenshots/supporter/14-modules-return-stable.png)。掲示板の入口非表示と、基本情報・通常ホームへの復帰を確認。

親は全住民のcontext閉鎖後、別の単一MEMBER contextで掲示板を390/360幅に再表示した。タブ72×38・86×38、ページ幅390/390・360/360を再現し、[実画像](screenshots/parent/360-bulletin.png)と[DOM/computed style](screenshots/parent/bulletin-metrics.json)を照合。最初の親試行はiconを含むaccessible nameのexact locator不一致で終了し、表示文字列で再確認した。これは製品の操作不能とは区別する。再現用contextもfinallyで閉鎖した。

## 既存部品の改善候補

今回変更した表示制御について、新たな確定不備・未解消blockerはない。次の観測は[住民観測JSON](resident-observations.json)へ、登録元・人格・journey・runId・source・証拠を保持して記録した。`trusted=false`、`observed-unverified`の改善候補であり、固定B0計画のcoverage合格や機能のblockerへ昇格させない。

- 設定の配置で一度迷い、機能設定一覧下部で発見した。目的は達成しており、導線不能ではない。
- 既存掲示板タブの高さ38px。親が再現したが`bulletin.vue`は今回差分なしで、操作不能は観測していない。
- 既存月カレンダーの日セル幅34pxと予定名省略。日セルから名称・日時を読める。`CalendarGrid.vue`は今回差分なしで、省略仕様と使いやすさの判断を要する。
- 既存共通toastの閉じる領域28px。保存と復帰を阻害しない補助操作で、新規設定の主要操作は44px以上。

未保存の編集で画面を離れると元の保存値へ戻ることも観測した。未保存draftの画面間保持・離脱警告は本仕様の受け入れ条件ではなく、保存後の保持と途中失敗時の同画面draft保持を別に確認している。

## 実行環境の制限

最初の住民起動前に、旧担当のFE/APIプロセスが消失したため全員をログイン前に停止した。DBコンテナは継続。親管理で同じworktree・同SHA jar・同専用DBを再起動し、health200とFE login200の確認後に3名を再開した。担当終了との時間的対応はあるが、プロセス終了原因は確定していない。初回routeの30秒timeoutは自然解消しており、成功に偽装したり製品不備に数えたりしない。

実機Windows Node24.15.0/npm11.12.1と、正式CI Node22.23.2/npm10.9.8の差は[FE記録](frontend-validation.md)に明記した。代表画像と測定JSONを本ディレクトリへ保存し、過渡状態や失敗した補助runnerの全ログは永続証拠へ含めない。
