# アンケート代理回答の本人紐付け — CMP-260820-1018 実機で発見した依存不具合

根拠は F14.1 §1「本人アカウントに紐付け」、§2 機能スコープと実行権限、§3 同意組合、§5・§10 の永続化整合。CMP-260820-1017 は ResidentRegistryEntity builder の別件であり本件へ帰属させない。

## 症状と範囲

SurveyResponseController は JWT の actor ID を Service へ渡し、Service は回答・配信・重複にその ID を使用する。ProxyInputRecord の subject ID のみ Context の本人になる。代理者と本人を同一 ID にした既存 UT では検知できない。CMP1018 実機 fixture の survey199 は未回答のまま保存し、誤主体の回答送信を止めた。

製品修正は RED 確認後に Survey の送信・自己回答取得と、その同意を検証する狭い proxy Service 窓口へ限定する。global SecurityUtils、他の代理機能、匿名投票、権限カタログ、DB migration、共有 DB を変更しない。

## 受け入れ条件と先行試練

実 JWT / SecurityFilterChain / Testcontainers MySQL の SurveyProxyIdentityHttpContractIT を用いる。HTTP をテスト外側の transaction で包まず、別 transaction の DB 再読取を使う。

| 条件 | 試練 |
| --- | --- |
| actor≠subject、回答を本人へ保存し記録は両 ID を正しく保持、件数1 | 代理回答は本人へ保存し記録の代理者と本人を分離する |
| 代理 GET は本人、通常 GET は actor | 代理の回答取得は本人を返し通常の回答取得は代理者を返す |
| actor の過去回答は本人初回を阻害せず不変 | 代理者自身の既回答は本人の初回回答を妨げず変更しない |
| 本人の重複409で回答・記録・件数不変 | 本人の既回答は重複409で保存も記録も増やさない |
| TARGETED は本人だけ対象なら許可、actorだけ対象なら拒否 | 限定配信は代理者でなく本人の対象指定で判定する（2対照） |
| ALL の本人所属を確認 | 全員配信も本人が配信母集団外なら拒否する |
| 本人 SUPPORTER の包含トグルを保存 | 全員配信の本人サポーターは包含設定に従う（2対照） |
| 再回答は本人だけ置換、actorの回答不変 | 本人の再回答だけを置換し代理者の既回答を維持する |
| 別機能/PAYMENT-only、別組合、実行権限無し、撤回、期限切れ、未承認、actor/subject不一致は POST/GET とも拒否・DB不変 | 不適合な同意では代理の保存も閲覧も拒否する（18対照） |
| TEAM の ACTIVE加盟組合だけ同意範囲、PENDING は不可 | チームは同意組合へ有効加盟している場合だけ代理回答できる（2対照） |
| 終了後の GET は有効同意で本人の回答を参照できる | 終了後も有効同意で本人の既回答を参照できる |
| 本人未回答なら actor の回答を返さず空 | 本人未回答なら代理の回答取得は空配列を返す |
| 通常の本人回答は actorへ保存、代理記録なし | 通常回答は認証本人へ保存し代理記録を作らない |
| 途中設問エラーは部分回答・記録・件数を全 rollback | 途中の不正設問は全回答と記録と件数をロールバックする |

設置上の予定は34ケース。コンパイル、RED、JUnit 件数は実測前に達成扱いにしない。既存の正常な挙動は characterization として GREEN を保存し、失敗原因を製品差分と fixture/環境不備に分ける。

## 設計判断

殿の設計判断と読み取り専用 advisor の助言により、代理時だけ SURVEY を要求し、proxy Service で有効同意の actor/subject/organization と同意組合の PROXY_INPUT_EXECUTE を検証する。SYSTEM_ADMIN は既存 isSystemAdmin を併用。Survey は同意組合と実体 scope を束縛する（ORGANIZATION 完全一致、TEAM は既存 OrganizationHierarchyService の ACTIVE anchor 組合）。配信/重複/削除/保存に使用する ID だけ本人へ置き換え、監査の actor は JWT のまま保つ。通常 GET の空配列、終了後 GET、管理者個別回答の認可は保存する。

F14.1 の「SUPPORTER が代理者として条件付き実行」と現 RoleService の SUPPORTER 空権限には不整合がある。今回、同意を権限の代用にして SUPPORTER 代理者を通す変更は行わず、既存権限判定へ従う。この不整合は別途台帳へ記録する。本人が SUPPORTER の配信母集団判定とは別問題である。

## 現在

- main 1c36ff40a736df023e9ff060a9315cae9883b269 から専用 worktree 作成済み。
- 製品コード変更なし。先行試練を設置した段階。
- 共有 DB、稼働中サービス、他 worktree は保全。
- 32760bd の compileTestJava は exit0、11993 backend blob/mode を照合。初回実測は34 tests/34 failures/0 errors/0 skippedだったが、全失敗が fixture slug の30文字上限超過であり、製品 RED ではない。原 XML/log/manifest を保持し、slug を23文字へ修正して再試練する。初回の予定35件という見積りも実測34件へ訂正した。
