# F03.5 シフト管理 — §10 未公開シフト表の遮断方針（CMP-260826-2127）

> **ステータス**: 🟡 設計完了・実装未着手
> **初版**: 2026-09-03（軍議やり直し版）
> **正本課題**: CMP-260826-2127「シフト表の未公開ステータスが API では絞られていない」
> **関連**: 本ディレクトリ `04_security_operations.md` §6（PDF・IDOR・情報隠蔽の既存方針）、`02_api_design.md` §4（API 一覧）

---

## 0. この文書が扱う問題

シフト表は `DRAFT → COLLECTING → ADJUSTING → PUBLISHED → ARCHIVED` の 5 状態を持つ。
FE は非管理者に `PUBLISHED` のみを表示するが（`ShiftScheduleList.vue:21-23`）、**BE の API はステータスを一切見ていない**。
そのため非管理者でも、`GET /api/v1/shifts/schedules?teamId=` を直接叩けば下書き段階のシフト表が返り、
`GET /api/v1/shifts/schedules/{id}/slots` で調整中の割当（誰がどの枠に入る予定か）が読め、
`GET /api/v1/shifts/schedules/{id}/pdf` で未公開シフトの PDF が取れる。

これは `04_security_operations.md` §6【v2.2】が既に宣言している
「ウォーターマーク付き PDF は DRAFT/COLLECTING/ADJUSTING かつ ADMIN 以上でのみ発行可能」という設計と
**実装が食い違っている**（設計側が正しく、実装が追いついていない）。

### 0.1 一度目の軍議が差し戻された理由（同じ轍を踏まないための記録）

当初案の受け入れ条件は「非管理者に DRAFT / COLLECTING / ADJUSTING を返さない」だった。これは機能回帰を起こす:

- `frontend/app/pages/my/shift-request.vue:70-72` は `listSchedules` の結果を `status === 'COLLECTING'` で絞って
  希望提出画面に並べている。COLLECTING を返さないと、**一般メンバーの希望提出画面が必ず空になる**。
- BE 側も `ShiftRequestService#submitRequest`（:98-102）が `validateCollectingStatus` で COLLECTING を
  正規の提出条件としており、COLLECTING はメンバーが能動的に関わる状態である。
- さらに `shift-request.vue:85` は選んだスケジュールに対して `listSlots(schedule.id)` を呼ぶ。
  枠の骨格（日付・時刻・ポジション・必要人数）が見えないと希望を出す対象が特定できないため、
  **COLLECTING の枠一覧を丸ごと閉じることもできない**。

したがって「ステータスで丸ごと閉じる」軸は誤りである。本文書は隔ての軸を引き直す。

---

## 1. 現状の漏洩経路一覧（実コードで裏取り済み）

行番号は 2026-09-03 時点の `origin/main`。

### 1.1 今回の射程（マスター御裁可 A）

| # | 経路 | 実装位置 | 現状 | 漏れる内容 |
|---|---|---|---|---|
| L1 | `GET /shifts/schedules?teamId=` | `ShiftScheduleService#listSchedules`（:86-90） | `checkTeamReadAccess` のみ。ステータス条件なし | DRAFT を含む全ステータスのシフト表のメタ（存在・タイトル・期間・状態・note） |
| L2 | `GET /shifts/schedules?teamId=&from=&to=` | 同 `#listSchedulesByPeriod`（:101-106） | 同上（L1 の迂回経路） | 同上 |
| L3 | `GET /shifts/schedules/{id}` | 同 `#getSchedule`（:118-122） | `findScheduleOrThrow` → `checkTeamReadAccess` のみ | 単体のメタ。ID 総当りで他状態の存在も観測可 |
| L4 | `GET /shifts/schedules/{id}/slots` | `ShiftSlotService#listSlots`（:71-75） | `checkScheduleReadAccess` のみ | **枠の骨格に加えて `assignedUserIds`**（誰がどの枠に入る予定か）。調整中の割当が本丸 |
| L5 | `GET /shifts/schedules/{id}/pdf?layout=team` | `ShiftPdfService#generateTeamPdf`（:43-57） | `getSchedule` + `listSlots` の合成。ステータス条件なし | 未公開シフト表の PDF 全体。§6【v2.2】の宣言と矛盾 |
| L6 | 同 `?layout=personal` | 同 `#generatePersonalPdf`（:68-88） | 同上（自分の割当のみに絞るが未公開である事実は同じ） | 未公開段階の「自分がどこに入る予定か」 |
| L7 | グローバル検索の shifts 種別 | `GlobalSearchService#search`（:127-133）／`ShiftScheduleRepository#searchByKeyword`（:60-69） | 所属チーム絞りのみ。**status 条件なし・可視性フィルタなし**（schedules 種別は `contentVisibilityChecker.filterAccessible` を通すのに shifts は通していない） | DRAFT のタイトル・note がキーワードで引ける |

L5/L6 は冒頭で `scheduleService.getSchedule(scheduleId, requesterId)` を呼ぶため、
**`ShiftScheduleService` 側を是正すれば大部分が自動的に閉じる**（ただし §3.4.1 の追加ゲートが 1 点だけ必要）。

### 1.2 今回の射程外（別起票する。§7 参照）

| # | 経路 | 実装位置 | 裏取り結果 |
|---|---|---|---|
| X1 | `GET /shifts/my/slots` | `ShiftMyService#getMyConfirmedSlots`（:53-121） | **事実**。`findAllByUserIdAndStatus(userId, CONFIRMED)` から出発し、schedule は名前解決のためだけに引いている（:73-74, :111）。schedule.status を一切見ない |
| X2 | ダッシュボードの直近予定 | `ShiftAssignmentRepository#findUpcomingByUserIdBetween`（:61-71）／`DashboardController`（:334, :351-352） | **事実**。JPQL に `a.status = 'CONFIRMED'` はあるが `sc.status` 条件が無い |
| X3 | `GET /shifts/requests/my` | `ShiftRequestService#listMyRequests`（:84-87） | **事実**（ただし自分が出した希望のみ。実害は低い） |
| X4 | 変更依頼の一覧・詳細 | `ShiftChangeRequestService#list`（:110-122）／`#get`（:141-153） | **事実**。認可（本人 or scope ADMIN）はあるが status 境界は無い |
| X5 | `GET /shifts/swap-requests/my` | `ShiftSwapService#listMySwapRequests`（:91-94） | **事実**（自分の申請のみ） |
| X6 | オープンコール作成の認可欠落 | `ShiftSwapService#createOpenCall`（:222-235） | **事実。本件とは別系統の欠陥**。メソッド本体に `accessControlService` 呼び出しが 1 つも無く、`slotId` の所属チーム検証もしていない |
| X7 | PDF の SYSTEM_ADMIN 非対称 | `ShiftPdfService#checkMemberAndNotSupporter`（:100-107） | **事実**。同ドメインの `ShiftScheduleService#checkTeamReadAccess`（:431-433）・`#checkScheduleAdminAccess`（:412-414）は `isSystemAdmin` で短絡するのに、ここだけしない |

### 1.3 Codex 指摘の判定まとめ

- **事実だったもの**: X1・X2・X3・X4・X5・X6・X7、および「403 と 404 が同ドメインで混在」
  （`ShiftScheduleScopeContractIT` / `ShiftSlotScopeContractIT` は 403、
  `ShiftChangeRequestService#get`（:152）と `ShiftAutoAssignService#checkRunAdminAccessConcealed`（:402-411）は 404）、
  および「`ARCHIVED` は status だけでは過去に PUBLISHED だったか判定できない」。
  最後の点は `ShiftScheduleService#transitionStatus`（:227）が **どの状態からでも `ARCHIVED` を受け付ける**ことで裏取りできた
  （`ShiftScheduleEntity#archive()`（:171-173）にも遷移元のガードは無い）。DRAFT → ARCHIVED が直接可能である。
- **誤認だったもの**: 無し。指摘はすべて実コードで再現した。
- **未検証**: `ShiftPreferenceReminderBatchService`（:229-262）が COLLECTING の希望提出リマインドでシフト名を通知する点は
  コードを読んで正規仕様であることを確認したが、本設計はバッチ通知経路には触れないため詳細な影響調査は行っていない。
  §5 の「壊してはならないもの」に明記して保護する。

---

## 2. 隔ての軸（本設計の中核）

### 2.1 採用する軸: ステータスではなく「情報の層」で切る

シフト表が持つ情報を 3 層に分ける。**ステータス単独で API を開閉するのではなく、層ごとに開閉ステータスを変える。**

| 層 | 含まれるもの | DTO 上の位置 |
|---|---|---|
| **L-META** シフト表のメタ | 存在・`title`・`periodType`・`startDate`/`endDate`・`requestDeadline`・`status`・schedule の `note` | `ShiftScheduleResponse` 全体 |
| **L-FRAME** 枠の骨格 | `slotDate`・`startTime`・`endTime`・`positionId`/`positionName`・`requiredCount`・slot の `note` | `ShiftSlotResponse` のうち `time` / `position` / `note` |
| **L-ASSIGN** 割当内容 | **誰がどの枠に入るか** | `ShiftSlotResponse.assignedUserIds`、および team レイアウトの PDF |

**非管理者に対する開閉表**（管理者＝当該チームの ADMIN/DEPUTY_ADMIN、および SYSTEM_ADMIN は全ステータス・全層を従来どおり閲覧）:

| ステータス | L-META | L-FRAME | L-ASSIGN |
|---|---|---|---|
| `DRAFT` | ✕（存在ごと秘匿） | ✕ | ✕ |
| `COLLECTING` | ○ | ○ | ✕（空配列にマスク） |
| `ADJUSTING` | ○ | ○ | ✕（空配列にマスク） |
| `PUBLISHED` | ○ | ○ | ○ |
| `ARCHIVED` かつ `publishedAt != null` | ○ | ○ | ○ |
| `ARCHIVED` かつ `publishedAt == null` | ✕（DRAFT 相当として扱う） | ✕ | ✕ |

### 2.2 なぜこの軸か（根拠）

**(a) 守るべき実害は L-ASSIGN にある。**
情報漏洩として本当に問題なのは「確定していない割当が既成事実として一人歩きすること」である。
調整中に「自分が外されている」「A さんとだけ組まされている」が見えると、公開前に人間関係トラブルが起きる。
これは `04_security_operations.md` §6【v2】が自動割当スコアを ADMIN 限定にした理由
（「なぜ選ばれ/選ばれなかったかが他メンバーに見えると人間関係トラブルになる」）と**同じ危険**であり、
同ドメインの既存判断と首尾一貫する。逆に L-META は「3月第1週のシフトを今作っている」という程度の情報で、実害が小さい。

**(b) COLLECTING/ADJUSTING で L-META と L-FRAME を閉じると機能が壊れる。**
§0.1 のとおり、希望提出画面は COLLECTING のメタ（一覧に出す）と枠の骨格（希望を出す対象）の両方を必要とする。
「COLLECTING だけ特例で開ける」という書き方も可能だが、それでは ADJUSTING に遷移した瞬間に
メンバーの画面から自分が希望を出したシフト表が消え、「消えた」というサポート問い合わせを生む。
層で切れば ADJUSTING でも「調整中です」と表示し続けられる。

**(c) DRAFT だけは存在ごと秘匿する。**
DRAFT は「管理者が作りかけて、まだメンバーに知らせるつもりが無い」状態である。
`transitionStatus` で COLLECTING へ遷移する行為が「メンバーに知らせる」という意思表示であり、
その前に存在が見えるのは管理者の意図に反する。作りかけを消しても誰も気付かない状態を保つ必要がある。

**(d) ADJUSTING のメタは見せる。**
希望を出した本人にとって「自分の希望がどう処理されているか」は正当な関心であり、
`status = ADJUSTING` が見えること自体が「今調整中」という有用なフィードバックになる。
隠す実害（調整中の割当）は L-ASSIGN のマスクで既に閉じている。

**(e) ARCHIVED は status ではなく `publishedAt` で判定する。**
§1.3 のとおり DRAFT → ARCHIVED が直接可能なため、`ARCHIVED` は「かつて公開された」ことを意味しない。
一方 `ShiftScheduleEntity#publish()`（:162-166）は `publishedAt` を必ずセットし、
`archive()` はそれをクリアしない。したがって **`publishedAt != null` が「かつて公開された」の唯一信頼できるオラクル**である。
新カラムも Flyway マイグレーションも不要。
（`duplicateSchedule`（:258-267）は複製時に `publishedAt(null)` を明示的に落としており、複製が公開済み扱いになる穴も無い。）

### 2.3 マスク方式（L-ASSIGN の閉じ方）

`assignedUserIds` は **404 でも 403 でもなく、空配列 `[]` にマスクする**。
枠一覧そのものは返す必要がある（L-FRAME）ため、フィールド単位で落とすしかない。

- マスクは `ShiftSlotService#toSlotResponse`（:316 付近）ではなく、**呼び出し側の `listSlots` / `getSlot` で
  「閲覧者が管理者か」「schedule が公開済みか」を解決したうえで適用する**。
  `toSlotResponse` は管理系メソッド（`createSlot` / `updateSlot` / 差分割当）からも呼ばれており、
  そこでマスクすると管理画面の D&D 編集が壊れる。
- `null` ではなく空配列にするのは、FE が `slot.assignedUserIds.length`（`shift/[id]/index.vue:130,351`、
  `shift/[id]/edit.vue:282,287`）と `.forEach`（`ShiftSwapRequestFormDialog.vue:114,124`）を
  **null チェック無しで呼んでいる**ため。`null` にすると FE が TypeError で落ちる。

### 2.4 「非管理者」の判定に使うメソッド

`AccessControlService` の既存メソッドのみを使い、新設しない。

| 用途 | メソッド | 備考 |
|---|---|---|
| SYSTEM_ADMIN 短絡 | `isSystemAdmin(userId)`（:489-491） | **最初に評価する**。全ステータス・全層を開ける |
| 管理者判定（＝未公開も見てよい側） | `isAdminOrAbove(userId, teamId, "TEAM")`（:391-394） | 真偽を返す版を使う。`checkAdminOrAbove`（:426-430）は例外を投げるため、フィルタ判定には使わない（例外を握り潰す実装を誘発する） |
| 参照可否（既存・変更しない） | `isMember`（:76-79）+ `isSupporter`（:383-386） | 現行 `checkTeamReadAccess`（:430-440）をそのまま維持 |

**境界の明示:**

- **親組織 ADMIN・組織階層上位ロールは「管理者」に含めない。** `isAdminOrAbove` は当該 `teamId` の実効ロールだけを見る。
  `isMemberOrDescendant`（:104-142）のような配下ツリー救済は使わない。シフト表は TEAM スコープ専用
  （`ShiftScheduleRepository#searchByKeyword` の Javadoc :49-50 が明言）であり、
  同ドメインの既存書込系認可（`checkScheduleAdminAccess`）も配下概念を持ち込んでいない。ここだけ広げると非対称になる。
- **SUPPORTER は従来どおり全ステータスで参照不可**（`checkTeamReadAccess` が先に 403 を投げる）。本設計は SUPPORTER の扱いを変えない。
- **SYSTEM_ADMIN 短絡は必ず「非メンバー判定より前」に置く。** X7 の非対称（`ShiftPdfService`）と同じ罠を新設しないため。

---

## 3. API ごとの期待結果マトリクス

役者: **SysAdmin**（SYSTEM_ADMIN、当該チーム非メンバーでも可）／**Admin**（当該チーム ADMIN or DEPUTY_ADMIN）／
**Member**（当該チームの一般メンバー）／**Supporter**（当該チーム SUPPORTER）／**Other**（別チーム ADMIN・無所属）。

### 3.1 `GET /shifts/schedules?teamId=`（および `from`/`to` 付き）

| ステータス | SysAdmin | Admin | Member | Supporter | Other |
|---|---|---|---|---|---|
| DRAFT | 含む | 含む | **除外** | 403 | 403 |
| COLLECTING | 含む | 含む | 含む | 403 | 403 |
| ADJUSTING | 含む | 含む | 含む | 403 | 403 |
| PUBLISHED | 含む | 含む | 含む | 403 | 403 |
| ARCHIVED（publishedAt あり） | 含む | 含む | 含む | 403 | 403 |
| ARCHIVED（publishedAt なし） | 含む | 含む | **除外** | 403 | 403 |

一覧は「除外」であって 403 ではない（一覧に対する 403 は認可の話で、既存契約どおり）。

### 3.2 `GET /shifts/schedules/{id}`

| ステータス | SysAdmin | Admin | Member | Supporter | Other |
|---|---|---|---|---|---|
| DRAFT | 200 | 200 | **404** | 403 | 403 |
| COLLECTING / ADJUSTING / PUBLISHED / ARCHIVED(公開済) | 200 | 200 | 200 | 403 | 403 |
| ARCHIVED（publishedAt なし） | 200 | 200 | **404** | 403 | 403 |

### 3.3 `GET /shifts/schedules/{id}/slots`

| ステータス | SysAdmin | Admin | Member | Supporter | Other |
|---|---|---|---|---|---|
| DRAFT | 200 全量 | 200 全量 | **404** | 403 | 403 |
| COLLECTING | 200 全量 | 200 全量 | **200・`assignedUserIds` は `[]`** | 403 | 403 |
| ADJUSTING | 200 全量 | 200 全量 | **200・`assignedUserIds` は `[]`** | 403 | 403 |
| PUBLISHED | 200 全量 | 200 全量 | 200 全量 | 403 | 403 |
| ARCHIVED（publishedAt あり） | 200 全量 | 200 全量 | 200 全量 | 403 | 403 |
| ARCHIVED（publishedAt なし） | 200 全量 | 200 全量 | **404** | 403 | 403 |

枠単体取得のエンドポイントは存在しない（`ShiftSlotController` に `GET /slots/{id}` は無い。
`ShiftSlotService#getSlot`（:83-87）は内部利用のみ）。将来 EP を生やす場合は同じ表に従うこと。

### 3.4 `GET /shifts/schedules/{id}/pdf`（`layout=team` / `layout=personal`）

`ShiftPdfService` は `getSchedule` と `listSlots` の合成であるため、**3.2 と 3.3 の結果がそのまま伝播する**。

| ステータス | SysAdmin | Admin | Member |
|---|---|---|---|
| DRAFT / ARCHIVED(未公開) | 200 | 200 | **404**（`getSchedule` が投げる） |
| COLLECTING / ADJUSTING | 200 | 200 | **404** ← §3.4.1 の追加ゲート |
| PUBLISHED / ARCHIVED(公開済) | 200 | 200 | 200 |

#### 3.4.1 COLLECTING / ADJUSTING の PDF は 404 とする（追加の遮断が必要）

L-ASSIGN マスクをそのまま流すと「割当欄が全部空白のシフト表 PDF」という無意味な紙が出る。
`04_security_operations.md` §6【v2.2】は「ウォーターマーク付き PDF は DRAFT/COLLECTING/ADJUSTING かつ ADMIN 以上でのみ発行可能」と
既に宣言しており、**未公開シフトの PDF は非管理者に発行しない**が正しい。
よって `ShiftPdfService` は、非管理者かつ `publishedAt == null` の場合に
`SHIFT_SCHEDULE_NOT_FOUND`（404）を返す。
これが `ShiftScheduleService` 側の是正だけでは閉じない唯一の点である。

### 3.5 グローバル検索（shifts 種別）

| ステータス | 検索実行者が Admin/SysAdmin | 検索実行者が Member |
|---|---|---|
| DRAFT / ARCHIVED(未公開) | ヒットする | **ヒットしない** |
| COLLECTING / ADJUSTING / PUBLISHED / ARCHIVED(公開済) | ヒットする | ヒットする |

検索は `title` と `id` しか返さない（`GlobalSearchService`:129-131）ため L-META の話であり、§3.1 の一覧と同じ規則になる。

**実装位置の注意**: 絞りは **`searchByKeyword` の SQL 述語側**で行う。
取得後に Java でフィルタすると、`SEARCH_LIMIT` 件を取ってから削るため件数が過少になる
（`GlobalSearchService` の Javadoc が「取得後にフィルタすると件数とページングが壊れる」と明記している）。
ただし「閲覧者がそのチームの ADMIN か」は SQL 1 行の述語に落ちない（チームごとに実効ロールが異なる）。
そこで **呼び出し側で `teamIds` を「管理者として所属するチーム」と「一般メンバーとして所属するチーム」の 2 集合に割り、
クエリに両方を渡して `(s.teamId IN :adminTeamIds) OR (s.teamId IN :memberTeamIds AND <公開済み条件>)` とする**。
2 集合の解決は `findAffiliatedScopeIds` の結果を `isAdminOrAbove` で仕分ければ足りる
（所属チーム数は実運用で高々数十であり、既存 `schedules` 種別が行っている取得後フィルタより安全）。
なお shifts 種別は `contentVisibilityChecker.filterAccessible` を通していないが、
シフト表は `min_view_role` を持たない（F00 の可視性テンプレート対象外）ため、これは本設計でも変更しない。

### 3.6 「公開済み」判定述語（実装で共有する唯一の定義）

```
公開済み（非管理者に L-ASSIGN まで開いてよい） :=
    status = PUBLISHED
    OR (status = ARCHIVED AND publishedAt IS NOT NULL)

非管理者に存在ごと秘匿する :=
    status = DRAFT
    OR (status = ARCHIVED AND publishedAt IS NULL)

非管理者にメタと骨格だけ開く :=
    status IN (COLLECTING, ADJUSTING)
```

この 3 分類は**必ず 1 か所に定義して全経路が参照する**こと（例: `ShiftScheduleEntity` のドメインメソッド
`isVisibleToNonAdmin()` / `isAssignmentVisibleToNonAdmin()`、および検索クエリが使う同値の JPQL 断片）。
経路ごとに条件を書き写すと、後日ステータスが増えたときに一部経路だけ直され、直っていない経路が静かに残る。

---

## 4. 404 / 403 の方針と既存契約との整合

### 4.1 方針（マスター御裁可 B）

**未公開シフト表への単体アクセスは 404（`SHIFT_SCHEDULE_NOT_FOUND` / `SHIFT_001`）とする。**

403 にすると「存在するが未公開」を「存在しない ID の 404」と区別でき、
scheduleId を総当りすることで「このチームに未公開シフトが何本あるか」が観測できる（存在オラクル）。
同ドメインには既に同じ判断の前例が 2 つある:

- `ShiftChangeRequestService#get`（:141-153）— 越境は `CHANGE_REQUEST_NOT_FOUND`
- `ShiftAutoAssignService#checkRunAdminAccessConcealed`（:402-411）— 越境は `ASSIGNMENT_RUN_NOT_FOUND`

さらに `04_security_operations.md` §6【v2.2】が
「他チームのスケジュール ID を URL 直打ちされた場合は `404`（情報隠蔽）」と宣言している。

### 4.2 既存 403 契約テストとの衝突と、その解き方

**衝突は「別 scope の 403」ではなく「自チーム DRAFT の 200」で起きる。** ここが実コード確認で判明した要点である。

| テスト | 該当箇所 | 現状 | 本設計適用後 |
|---|---|---|---|
| `ShiftScheduleScopeContractIT` — `GetSchedule.一般メンバーは200`（:401 以降） | フィクスチャ `scheduleA` は **`ShiftScheduleStatus.DRAFT`**（:126-135） | 200 を期待 | **404 になる → 赤くなる** |
| `ShiftSlotScopeContractIT` — `ListSlots.正当メンバーは200`（:165-170） | フィクスチャの schedule も **DRAFT**（:108-117） | 200 を期待 | **404 になる → 赤くなる** |
| `ShiftScheduleScopeContractIT` — `ListSchedules.一般メンバーは200`（:331-337） | 一覧は 200 のまま（中身が空配列になるだけ） | 200 | **200 のまま**（ただし中身を検証していないため素通りする。AC-1 で件数を見る新テストが要る） |
| 両 IT の「別 scope ADMIN は 403」「SUPPORTER は 403」「無所属は 403」 | — | 403 | **403 のまま。変更しない** |

したがって解き方は次のとおり:

1. **認可（誰が）の 403 と、可視性（何が）の 404 は別レイヤであると定義する。**
   - まず `checkTeamReadAccess` が走り、非メンバー・SUPPORTER・別 scope は従来どおり **403**。
   - その関門を通過した「当該チームの正当な閲覧者」に対してのみ可視性判定が働き、未公開なら **404**。
   - この順序により、既存の 403 契約は 1 件も変わらない。**403 テストを 404 に書き換える必要は無い。**
2. **赤くなる 2 件は、期待値ではなくフィクスチャを直す。**
   「一般メンバーが自チームのシフト表を読める」という日常正常系を守るのが元テストの意図
   （`ShiftScheduleScopeContractIT` Javadoc :56-58 に明記）であり、フィクスチャが DRAFT だったのは
   **その意図に対して不正確だった**にすぎない。`PUBLISHED` のスケジュールを別途用意して 200 を固定し、
   DRAFT に対する 404 は新規ケースとして足す。
3. **404 と 403 の撃ち分け順序を逆にしてはならない。** 先に可視性で 404 を返すと、
   別チームの ADMIN が「そのチームに未公開シフトがある」ことを 403/404 の差で観測できるようになる。
   別 scope からのアクセスはステータスに依らず常に 403 で一定であること。

---

## 5. 壊してはならないもの（回帰リスク）

実装者・検分者はこの一覧を必ず踏むこと。

| # | 守るもの | 壊れる原因 | 確認方法 |
|---|---|---|---|
| R1 | 一般メンバーの希望提出画面（`my/shift-request.vue`） | COLLECTING を一覧から落とす／COLLECTING の枠一覧を 404 にする | メンバーで COLLECTING のシフト表が一覧に出て、枠が並び、希望を提出できる |
| R2 | 希望提出 API | `submitRequest`（:98-102）が使う `findScheduleOrThrow` 経路に可視性 404 を混ぜる | メンバーが COLLECTING に希望を出せる |
| R3 | 希望提出リマインド通知 | `ShiftPreferenceReminderBatchService`（:229-262）は COLLECTING のシフト名を通知する**正規仕様**。バッチ経路に閲覧者依存の可視性判定を持ち込むと通知が壊れる | バッチ経路には可視性判定を入れない（バッチに「閲覧者」は存在しない） |
| R4 | 管理者の調整画面・D&D ボード | `toSlotResponse` 自体でマスクすると `board.vue`・`shift/[id]/edit.vue` の割当編集が空になる | 管理者で ADJUSTING のボードに既存割当が表示される |
| R5 | FE の null 安全 | `assignedUserIds` を `null` にする | メンバーで COLLECTING の枠一覧を開いて TypeError が出ない |
| R6 | 既存の 403 契約 | 可視性判定を認可判定より前に置く | `ShiftScheduleScopeContractIT` / `ShiftSlotScopeContractIT` の 403 系が全て緑のまま |
| R7 | 自動割当・公開ゲート・サマリ | `getScheduleSummary`（:290-292）・`assertNoUnreviewedRuns` は管理者専用経路。可視性判定を足す必要は無い | 管理者の summary が従来どおり |
| R8 | 検索の件数 | 取得後 Java フィルタで実装する | 検索結果の件数が絞り込み後の実件数と一致する（§3.5） |
| R9 | 交代リクエスト作成ダイアログ | `ShiftSwapRequestFormDialog.vue:114,124` が `assignedUserIds` を走査して候補者を作る。公開済みシフトでマスクが誤発火すると候補が空になる | 公開済みシフトで交代相手の候補が出る |

### 5.1 FE 側に必要な変更

`ShiftScheduleList.vue:21-23` は非管理者に `status === 'PUBLISHED'` のみを表示しており、**ARCHIVED も隠している**。
BE を締めたあとこの FE フィルタを残すと、メンバーは COLLECTING/ADJUSTING/ARCHIVED を画面で見られないままになり、
「BE は返すのに画面に出ない」という二重基準が残る。

方針: **FE のフィルタを削除し、表示可否は BE の返却に一本化する。**
多層防御としてフィルタを残す選択もあるが、その場合は BE と同じ規則（DRAFT を除外・それ以外は表示）に
書き換えなければならず、規則が 2 か所に分裂する。BE が DRAFT を返さなくなる以上、FE 側の絞り込みは冗長である。
`statusConfig`（:24-30）は 5 状態すべてのラベルを既に持っているため、バッジ表示の追加実装は不要。

---

## 6. 受け入れ条件（AC）

各 AC に「これが無いと何が壊れるか」を添える。

### 遮断（漏洩を閉じる）

- **AC-1**: 非管理者（当該チームの一般メンバー）に対し、`GET /shifts/schedules?teamId=` の返却から
  `DRAFT` のシフト表が除外される。`from`/`to` 付きの期間指定経路（`listSchedulesByPeriod`）でも同様に除外される。
  検証は「200 が返ること」ではなく**返却配列の中身**で行う。
  → 無いと、下書き段階のシフト表の存在・タイトル・期間が一覧 API で丸見えのまま残る。
     期間指定は L1 の迂回路なので両方必要。ステータスコードだけを見る既存テストはこの欠陥を素通りする（§4.2 の表）。
- **AC-2**: 非管理者が `GET /shifts/schedules/{id}` で `DRAFT` のシフト表を要求すると **404**（`SHIFT_001`）を返す。
  → 無いと、一覧から消しても ID 直打ちで読める。単体経路を閉じないと AC-1 は見せかけになる。
- **AC-3**: 非管理者が `GET /shifts/schedules/{id}/slots` で `DRAFT` のシフト表の枠を要求すると **404** を返す。
  → 無いと、schedule 単体を閉じても枠一覧から日程・ポジション・割当が読める。
- **AC-4**: 非管理者が `COLLECTING` または `ADJUSTING` のシフト表の枠一覧を取得したとき、
  **200 が返り、各枠の `assignedUserIds` が空配列 `[]` である**（`null` ではない）。
  枠の `slotDate` / `startTime` / `endTime` / `positionName` / `requiredCount` は従来どおり返る。
  → 空配列でなく `null` にすると FE が `.length` / `.forEach` で落ちる（§2.3）。
     マスクしないと調整中の割当（本件の実害の中心）が漏れたままになる。
- **AC-5**: 非管理者が `DRAFT` / `COLLECTING` / `ADJUSTING` のシフト表に対して
  `GET /shifts/schedules/{id}/pdf`（`layout=team` / `layout=personal` の両方）を要求すると **404** を返す。
  → 無いと、未公開シフト表がそのまま紙になる（DRAFT）か、
     割当欄が空の無意味な PDF が出る（COLLECTING/ADJUSTING）。§6【v2.2】の既存宣言との矛盾も解消されない。
- **AC-6**: 非管理者のグローバル検索で `DRAFT` のシフト表がヒットしない。
  かつ、絞り込みは SQL 述語側で行われ、**ヒット件数が絞り込み後の実件数と一致する**。
  → 無いと、一覧・単体・枠・PDF を全部閉じても検索から DRAFT のタイトルが読める。
     取得後フィルタで実装すると件数が過少になる（`GlobalSearchService` の Javadoc が警告している罠）。
- **AC-7**: `status = ARCHIVED` かつ `publishedAt IS NULL` のシフト表は、非管理者に対して
  AC-1〜AC-6 のすべてで `DRAFT` と同一に扱われる（一覧から除外・単体 404・枠 404・PDF 404・検索ヒットせず）。
  → 無いと、DRAFT を作って直接 ARCHIVED へ遷移させるだけで全遮断を迂回できる
     （`transitionStatus`:227 が遷移元を検査しないため実際に可能）。

### 非回帰（機能を壊さない）

- **AC-8**: 当該チームの一般メンバーが `COLLECTING` のシフト表を一覧で取得でき、その枠一覧を取得でき、
  `POST /shifts/requests` で希望を提出できる（`my/shift-request.vue` の一連のフローが通る）。
  → これが無いと、一度目の軍議と同じ機能回帰（希望提出画面が空になる）を再発させる。
- **AC-9**: 当該チームの一般メンバーが `ADJUSTING` のシフト表を一覧・単体で取得でき、`status` として `ADJUSTING` を受け取れる。
  → 無いと、希望を出したシフト表が調整開始と同時に画面から消え、メンバーから見て機能が壊れたように見える。
- **AC-10**: 当該チームの ADMIN / DEPUTY_ADMIN は、全ステータス（DRAFT を含む）で
  一覧・単体・枠一覧・PDF・検索のすべてを従来どおり全量取得でき、枠の `assignedUserIds` がマスクされない。
  → 無いと、管理者の調整画面・D&D ボードが空になり、シフト作成そのものができなくなる。
- **AC-11**: SYSTEM_ADMIN は、当該チームの**メンバーでなくても**全ステータスで AC-10 と同じ結果を得る
  （可視性判定は `isSystemAdmin` で最初に短絡する）。
  → 無いと、`ShiftPdfService`（§1.2 X7）と同じ非対称を新たに作り込む。
- **AC-12**: 既存の認可 403 契約が 1 件も変わらない。すなわち
  別 scope の ADMIN・SUPPORTER・無所属ユーザーは、**シフト表のステータスに依らず常に 403** を受け取る。
  → 403 より先に 404 を返すと、別チームの ADMIN が応答コードの差で
     「そのチームに未公開シフトがある」ことを観測できる（存在オラクルが場所を変えて残る）。
- **AC-13**: 既存 IT のフィクスチャ由来で赤くなる 2 件
  （`ShiftScheduleScopeContractIT#GetSchedule.一般メンバーは200`、`ShiftSlotScopeContractIT#ListSlots.正当メンバーは200`）は、
  **期待値の書き換えではなく `PUBLISHED` のフィクスチャを用意する形で緑化される**。
  加えて DRAFT に対する 404 のケースが新規に足される。
  → 期待値だけを 404 に書き換えると「一般メンバーが自チームの公開シフトを読める」という日常正常系の番人が消える。
- **AC-14**: `ShiftPreferenceReminderBatchService` の COLLECTING 希望提出リマインド通知が従来どおり
  シフト表名を含んで送信される（バッチ経路に可視性判定が入っていない）。
  → 無いと、正規仕様の通知が沈黙する。バッチには「閲覧者」がおらず可視性判定を適用する主体が存在しない。
- **AC-15**: `ShiftScheduleList.vue` の非管理者向けフィルタが削除され、
  メンバーの画面に `COLLECTING` / `ADJUSTING` / `PUBLISHED` / `ARCHIVED`（公開済み）が表示される。
  → 無いと、BE を正しく直しても画面上は PUBLISHED しか出ず、BE と FE で規則が二重化したまま残る。

### 構造（同じ穴を再発させない）

- **AC-16**: §3.6 の 3 分類（公開済み／存在秘匿／メタと骨格のみ）が**単一の定義**として実装され、
  一覧・単体・枠・PDF・検索の全経路がそれを参照している（条件式の書き写しが無い）。
  → 分散させると、後日ステータスが増えたときに一部経路だけ直され、直っていない経路が静かに残る。

---

## 7. 別起票すべき残件（今回の射程外）

いずれも本設計では**触らない**。`docs/task-list.md` への起票は殿が別途行う。

| # | 内容 | 根拠 | 所見 |
|---|---|---|---|
| B-1 | `ShiftMyService#getMyConfirmedSlots`（:53-121）が schedule のステータスを見ずに CONFIRMED 割当を返す。未公開シフトの日時・チーム名・シフト表名が本人に露出する | §1.2 X1 | 中。公開前に「自分のシフトが決まった」と誤認させる。本件と同じ根（ステータス無視） |
| B-2 | ダッシュボード直近予定（`ShiftAssignmentRepository#findUpcomingByUserIdBetween`:61-71 / `DashboardController`:334,351-352）が同様にステータス非考慮 | §1.2 X2 | 中。B-1 と同一原因・別経路。**B-1 と同じ戦役で一緒に直すべき** |
| B-3 | `ShiftRequestService#listMyRequests`（:84-87）・`ShiftSwapService#listMySwapRequests`（:91-94）に status 境界が無い | §1.2 X3/X5 | 低。自分が出したものしか返らない |
| B-4 | `ShiftChangeRequestService#list`（:110-122）/ `#get`（:141-153）に status 境界が無い | §1.2 X4 | 低〜中。認可自体は効いている |
| B-5 | **`ShiftSwapService#createOpenCall`（:222-235）に認可が一切無い。** `slotId` の所属チーム検証もしていないため、任意の slotId に対してオープンコールを作成できる | §1.2 X6 | **高。本件とは別系統の欠陥で、書込 API の無認可**。単独で早期起票を推奨 |
| B-6 | `ShiftPdfService#checkMemberAndNotSupporter`（:100-107）が SYSTEM_ADMIN を短絡しない。同ドメインの他サービスは短絡する | §1.2 X7 | 低。非メンバー SYSTEM_ADMIN が 403 になる非対称 |
| B-7 | shift ドメイン内で越境時の応答が 403（`ShiftScheduleService` / `ShiftSlotService`）と 404（`ShiftChangeRequestService` / `ShiftAutoAssignService`）に割れている。ドメイン全体としてどちらへ寄せるかの決着 | §1.3 | 中。本設計は「認可 403・可視性 404」の二層で当座を整理するが、越境そのものの応答は未統一のまま残る |
| B-8 | `transitionStatus`（:214-241）/ `ShiftScheduleEntity#archive()`（:171-173）が遷移元を検査せず、DRAFT → ARCHIVED / PUBLISHED → COLLECTING 等の逆行・飛び越しを許す。`04_security_operations.md` §6 の「許可されていないステータス遷移はアプリ層で厳密にブロック」という宣言と矛盾する | §1.3・§2.2(e) | 中。本設計は `publishedAt` を使うことでこの欠陥に依存せず成立させているが、欠陥自体は残る |

---

## 8. 変更履歴

- **v1.0 (2026-09-03)**: 初版。CMP-260826-2127 の軍議やり直しとして、隔ての軸を「ステータスで開閉」から
  「情報の層 × ステータス」へ引き直し、漏洩経路の棚卸し・期待結果マトリクス・AC 16 件・別起票残件 8 件を定める。
