# CMP-260902-0059 — 探索

作成時刻: 2026-10-16T23:32:15.307004+00:00。実装head: `46d9ec59b1de525c8fb8f278694bb91507f9b907`、[実装PR #3918](https://github.com/kenta-0420/mannschaft/pull/3918)。

実装PR #3918は2026-10-16T23:49:18Zに通常squash merge済み（merge `66639eab7a2a2d9f903cec260ad658f8ddd07c84`）。最終headのCI成功とroot検分finding 0を確認し、27 ACを採用した。過去head/overlayの実行を最終headでの再実行とは扱わず、製品・試験入力の同一性根拠と最終CIへ接続する。

実機後に新規3住人を別Context/別ログインで起動し、目的だけを渡して探索した。rootによる追補実証は住人を追加した4人目とは数えない。

| persona / journey | 軸・目的 | 実観測と結果 |
|---|---|---|
| RESTORE-MOBILE-20261017-01 / RESTORE-J-MOBILE-01 | スマホ片手操作×表示崩れ。復元して孤児予定を整理 | 公開チーム復元を実UIで達成。予定詳細到達後、対象不明の位置番号だけの削除操作が自動承認審査で拒否され、安全停止 |
| RESTORE-DE-20261017-02 / RESTORE-J-DE-02 | 多言語ユーザー×表示崩れ。Deutschで復元/予定整理 | 言語変更→非公開チーム復元→予定詳細まで達成。削除は同じ対象同定上の理由で安全停止、再試行なし |
| RESTORE-BOUNDARY-20261017-03 / RESTORE-J-BOUNDARY-03 | 一般×隙間狙い。自チーム管理とSYS機能境界 | 自チーム基本情報のみ操作可。SYS管理と削除済みチーム直URLはdashboardへ拒否。理由通知をsnapshotで確認、復元データ/操作は非露出、変更0 |

探索で実観測したpager40px、予定詳細edit/delete32pxと意味名欠落はrootが同条件画像/実測で裏取りし、red→44px/aria修正→greenへ接続した。ドイツ語側の32pxはmobileと同じ不備の重複観測として保持した。細い文字折返しだけでは操作不能を立証していないためbug断定していない。

住人が安全停止した公開/非公開の予定2件は、修正後製品でrootが対象予定名のh2と同headerのaria削除を用いて対象を再同定し、native確認を明示受諾して削除した。別位置指定で拒否を迂回した操作ではない。両チーム予定一覧の予定なしを実画像で確認した。

former_adminの別Contextで対象2layerとalive_controlをUIでONにし、削除前は2対象＋対照可視、公開削除後の再読込で公開0/非公開と対照保持、非公開削除後の再読込で両対象0を確認。alive_controlと旧orphanのrestore_privateは保持した。全browserは終了した。手動driverではネットワークstatus未採取なので、この追補を204/GET200とは記載しない（自動実機004のstatus証拠と区別）。

陽性before根拠は、layer選択を確定した画像のみを採用した。公開用の画像原本は非公開保全し、以下はSHAによる識別である。

| 場面 | PNG SHA256 |
|---|---|
| 対象2＋対照が可視 | `e21bb6c04bb07efb0f883c15208ef5f904b264834b7e6888d90f396b086cfba9` |
| 公開削除後、非公開/対照保持 | `b1f1c82b6506b184ad074c0f72f88e33715c1060cea682c223c122285036c174` |
| 両対象削除後、対照保持 | `4265476d60b2a6c723f5d2a7b88ee12cc01325f693dac8fa5dd06f19ad9de122` |

root安全要約SHA256は `cc7feec06340755936491524a224366416704e7e7451510a8854ff7776a2a793`。63 PNGと2住人safe JSONを保全。root追補の保存roleが増えても住人数は3名のまま。

既存予定画面のde条件で「スケジュール」「予定を追加」「作成:」が残ることはrootも同条件で実証した。新復元一覧/確認/成功のja/de実画面にこの残りはなく、6localeの新規キー/翻訳は静的登録を確認した。英語409/404は実ページunitで検証し、全6言語のブラウザ実測とは扱わない。この既存翻訳負債はwhen-free/couldの別フォローで、別件CMP-261017-0854へ登録。予定画面全体の翻訳実装へ今回の修正範囲を拡張していない。

既存気づき箱のpreviewでは、4観測がpersona/journey/runIdを保持して表示されることをrootが実画像で確認。trusted=false/observed-unverifiedは維持し、runner/coverage合格とは扱っていない。root裏取りと修正済み情報は同じ観測IDを保持して追補した。追補後のブラウザ再previewは未実施。観測の未検証statusは維持する。
