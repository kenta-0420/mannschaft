# F03.5 シフト管理 — §10 未公開シフト表の遮断方針（CMP-260826-2127）

> **ステータス**: 🟢 設計確定（未決事項なし）・実装未着手
> **初版**: 2026-09-03（軍議やり直し版） / **v1.1**: Codex 検分 12 件を反映 / **v1.2**: U-1 決着（案B・マスター裁可）で設計確定
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
| L7 | グローバル検索の shifts 種別 | `GlobalSearchService#search`（:127-133）／`ShiftScheduleRepository#searchByKeyword`（:60-69） | 所属チーム絞りのみ。**status 条件なし・可視性フィルタなし**（schedules 種別は `contentVisibilityChecker.filterAccessible` を通すのに shifts は通していない） | **検索対象フィールドは `title` と `note` の 2 つ**（`WHERE s.title LIKE %:keyword% OR s.note LIKE %:keyword%`）。**返却フィールドは `id` と `title`**。返却に note は含まれないが、**note がヒット判定に効く**ため、備考にしか書かれていない文言（店舗名・イベント名・人名など）を総当りすれば未公開シフトの note の内容を 1 語ずつ推定できる |

L5/L6 は冒頭で `scheduleService.getSchedule(scheduleId, requesterId)` を呼ぶため、
**`ShiftScheduleService` 側を是正すれば大部分が自動的に閉じる**（ただし §3.4.1 の追加ゲートが 1 点だけ必要）。

### 1.2 今回の射程外（別起票する。§8 参照）

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
  §6 の「壊してはならないもの」に明記して保護する。

---

## 2. 隔ての軸（本設計の中核）

### 2.1 採用する軸: ステータスではなく「情報の層」で切る

シフト表が持つ情報を 3 層に分ける。**ステータス単独で API を開閉するのではなく、層ごとに開閉ステータスを変える。**

| 層 | 含まれるもの | DTO 上の位置 |
|---|---|---|
| **L-META** シフト表のメタ | 存在・`title`・`periodType`・`startDate`/`endDate`・`requestDeadline`・`status`・schedule の `note` | `ShiftScheduleResponse` 全体 |
| **L-FRAME** 枠の骨格 | `slotDate`・`startTime`・`endTime`・`positionId`/`positionName`・`requiredCount`・slot の `note` | `ShiftSlotResponse` のうち `time` / `position` / `note` |
| **L-ASSIGN** 割当内容 | **誰がどの枠に入るか** | `ShiftSlotResponse.assignedUserIds`（伏せたことは新設 `assignmentMasked` で示す）、および team レイアウトの PDF |

**非管理者に対する開閉表**（管理者＝当該チームの ADMIN/DEPUTY_ADMIN、および SYSTEM_ADMIN は全ステータス・全層を従来どおり閲覧）:

| ステータス | L-META | L-FRAME | L-ASSIGN |
|---|---|---|---|
| `DRAFT` | ✕（存在ごと秘匿） | ✕ | ✕ |
| `COLLECTING` | ○ | ○ | ✕（`[]` + `assignmentMasked=true`） |
| `ADJUSTING` | ○ | ○ | ✕（`[]` + `assignmentMasked=true`） |
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

**(e) PUBLISHED は status だけで判定し、ARCHIVED のときだけ `publishedAt` を参照する。**
§1.3 のとおり DRAFT → ARCHIVED が直接可能なため、`ARCHIVED` は「かつて公開された」ことを意味しない。
一方 `ShiftScheduleEntity#publish()`（:162-166）は `publishedAt` を必ずセットし、`archive()` はそれをクリアしない。

ただし **`publishedAt` は「唯一信頼できるオラクル」ではない**。
`V3.070__create_shift_schedules_table.sql` の `published_at` は `DATETIME`（NULL 許容）で、
`status` との整合を保証する CHECK 制約もトリガも無い。実際に
`ShiftMapperTest`（:70-78）と `ShiftSwapScopeContractIT`（:472-483）は
**`status = PUBLISHED` かつ `publishedAt = NULL` のエンティティを生成している**。
したがって「PUBLISHED なら publishedAt が入っている」という前提でコードを書くと、
これらの経路で公開済みシフトが未公開扱いになる。

そこで判定規則を次のように**非対称**に定める:

- **`status = PUBLISHED`** → `publishedAt` を**見ない**。status だけで公開扱いとする。
  （`publishedAt` は表示用の付随情報であって、可視性の判定材料にしない。）
- **`status = ARCHIVED`** → `publishedAt` を参照し、`NULL` なら **fail-closed（非公開扱い＝404）** とする。
  ARCHIVED は「PUBLISHED を経た通常のアーカイブ」と「DRAFT から直接アーカイブされたもの」の両方を含みうるため、
  判別できない側を**閉じる方向**に倒す。誤って閉じても「古いシフト表が見えない」で済むが、
  誤って開けると未公開の割当が漏れる。
- そのほかのステータスは `publishedAt` を参照しない。

**既存データに `ARCHIVED + publishedAt IS NULL` は存在しうる。**
自動アーカイブバッチ（`ShiftAutoArchiveBatchService`:55-63）は
`findPublishedExpiredBefore` で **PUBLISHED のみ**を拾うためこの組み合わせを作らないが、
管理者が画面から DRAFT のシフト表に対して `transitionStatus(ARCHIVED)` を叩けば作れる
（`transitionStatus`:227 に遷移元ガードが無い）。よって「理論上のみ」ではなく実運用で起こりうる。
fail-closed により、そうした行は非管理者から見えなくなる。**この挙動変化は意図したものである**
（作りかけを畳んだシフト表がメンバーに見えていたほうが異常）。

なお DB 制約（`CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)` 等）の追加は
**本設計では行わない**。既存行に違反があった場合にマイグレーションが失敗し、
可視性の是正という本来の目的から外れた作業を巻き込むため。§8 の B-9 として別起票する。

（`duplicateSchedule`（:258-267）は複製時に `publishedAt(null)` と `status(DRAFT)` を明示的に落としており、
複製が公開済み扱いになる穴は無い。）

### 2.3 マスク方式（L-ASSIGN の閉じ方）

`assignedUserIds` は **404 でも 403 でもなく、フィールド単位で落とす**。
枠一覧そのものは返す必要がある（L-FRAME）ためである。

**確定形（U-1 案B・マスター裁可 2026-09-03）**: 伏せるときは
**`assignedUserIds` を空配列 `[]` にし、同時に新設フィールド `assignmentMasked` を `true` にする**。
空配列だけでは「本当に誰も割り当たっていない」と「伏せた」を区別できず、
一般メンバーの画面で全枠が赤の「0/N」と誤表示されるためである（経緯と3案の比較は §5.2 U-1、
確定した要求は §7 の AC-4）。

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
- ⚠️ **PDF だけは可視性判定を足しても SYSTEM_ADMIN が通らない。**
  `ShiftPdfService#checkMemberAndNotSupporter`（:100-106）は `isSystemAdmin` を短絡しないため、
  当該チームの非メンバーである SYSTEM_ADMIN は `getSchedule` を通過した直後にこの行で 403 になる。
  これは**本設計を実装しても直らない既存欠陥**であり、PDF について AC-11 を満たすには
  この 1 メソッドに `isSystemAdmin` 短絡を足す必要がある。§7 の AC-11 はその前提で書き分けてある。

---

## 3. API ごとの期待結果マトリクス

役者: **SysAdmin**（SYSTEM_ADMIN、当該チーム非メンバーでも可）／**Admin**（当該チーム ADMIN or DEPUTY_ADMIN）／
**Member**（当該チームの一般メンバー）／**Supporter**（当該チーム SUPPORTER）／**Other**（別チーム ADMIN・無所属）。

「未公開」は §3.6 の定義（DRAFT、または ARCHIVED かつ `publishedAt IS NULL`）を指す。

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
| COLLECTING | 200 全量 | 200 全量 | **200・`assignedUserIds` は `[]` かつ `assignmentMasked=true`** | 403 | 403 |
| ADJUSTING | 200 全量 | 200 全量 | **200・`assignedUserIds` は `[]` かつ `assignmentMasked=true`** | 403 | 403 |
| PUBLISHED | 200 全量 | 200 全量 | 200 全量 | 403 | 403 |
| ARCHIVED（publishedAt あり） | 200 全量 | 200 全量 | 200 全量 | 403 | 403 |
| ARCHIVED（publishedAt なし） | 200 全量 | 200 全量 | **404** | 403 | 403 |

枠単体取得のエンドポイントは存在しない（`ShiftSlotController` に `GET /slots/{id}` は無い。
`ShiftSlotService#getSlot`（:83-87）は内部利用のみ）。将来 EP を生やす場合は同じ表に従うこと。

### 3.4 `GET /shifts/schedules/{id}/pdf`（`layout=team` / `layout=personal`）

`ShiftPdfService` は `getSchedule` と `listSlots` の合成であるため、**3.2 と 3.3 の結果がそのまま伝播する**。

| ステータス | SysAdmin | Admin | Member |
|---|---|---|---|
| DRAFT / ARCHIVED(未公開) | 200（※） | 200 | **404**（`getSchedule` が投げる） |
| COLLECTING / ADJUSTING | 200（※） | 200 | **404** ← §3.4.1 の追加ゲート |
| PUBLISHED / ARCHIVED(公開済) | 200（※） | 200 | 200 |

※ SysAdmin が当該チームの**非メンバー**の場合、現行実装では `checkMemberAndNotSupporter`（:100-106）が
`isSystemAdmin` を短絡しないため 403 になる。§2.4 の警告と AC-11 を参照。

#### 3.4.1 COLLECTING / ADJUSTING の PDF は 404 とする（追加ゲート）

L-ASSIGN をマスクしたまま流すと「割当欄が全部空白のシフト表 PDF」という無意味な紙が出る。
`04_security_operations.md` §6【v2.2】は「ウォーターマーク付き PDF は DRAFT/COLLECTING/ADJUSTING かつ ADMIN 以上でのみ発行可能」と
既に宣言しており、**未公開シフトの PDF は非管理者に発行しない**が正しい。
よって `ShiftPdfService` は、非管理者かつ未公開の場合に `SHIFT_SCHEDULE_NOT_FOUND`（404）を返す。
これが `ShiftScheduleService` 側の是正だけでは閉じない唯一の点である。

**実装上の注意**: `ShiftPdfService` はエンティティを持たず `ShiftScheduleResponse`（DTO）しか受け取らない。
公開判定に必要な `status` と `publishedAt` は `ShiftStatusDto`（`ShiftScheduleResponse.java`:26）に載っているため
DTO から判定できるが、**DTO は `status` が `null` でも組み立てられる**
（`ShiftPdfServiceAuthzTest#scheduleOf`（:65-70）が実際に `status` を設定していない）。
`status == null` を「公開済み」と解釈してはならない。**null は未公開扱い（fail-closed）** とすること。

### 3.5 グローバル検索（shifts 種別）

| ステータス | 検索実行者が Admin/SysAdmin | 検索実行者が Member |
|---|---|---|
| DRAFT / ARCHIVED(未公開) | ヒットする | **ヒットしない** |
| COLLECTING / ADJUSTING / PUBLISHED / ARCHIVED(公開済) | ヒットする | ヒットする |

**検索対象フィールドと返却フィールドは別物である**（重要）:

- **検索対象**: `title` と `note`（`searchByKeyword`:62 の `WHERE s.title LIKE %:keyword% OR s.note LIKE %:keyword%`）
- **返却**: `id` と `title` のみ（`GlobalSearchService`:130-131）

返却に note は含まれないが、**note がヒット判定に効く**以上、未公開シフトの備考文言を
キーワードで総当りすれば 1 語ずつ推定できる（ヒットするか否かが 1 ビットの情報になる）。
したがって「返さないから安全」ではなく、**未公開シフトはクエリの母集団から外す**必要がある。

**実装位置**: 絞りは **`searchByKeyword` の SQL 述語側**で行う。
取得後に Java でフィルタすると、上限件数を取ってから削るため件数がさらに減る
（`GlobalSearchService` の Javadoc が「取得後にフィルタすると件数とページングが壊れる」と明記している）。
ただし「閲覧者がそのチームの ADMIN か」は SQL 1 行の述語に落ちない（チームごとに実効ロールが異なる）。
そこで **呼び出し側で `teamIds` を「管理者として所属するチーム」と「一般メンバーとして所属するチーム」の 2 集合に割り、
クエリに両方を渡して `(s.teamId IN :adminTeamIds) OR (s.teamId IN :memberTeamIds AND <公開済み条件>)` とする**。
2 集合の解決は `findAffiliatedScopeIds` の結果を `isAdminOrAbove` で仕分ければ足りる
（所属チーム数は実運用で高々数十）。

なお shifts 種別は `contentVisibilityChecker.filterAccessible` を通していないが、
シフト表は `min_view_role` を持たない（F00 の可視性テンプレート対象外）ため、これは本設計でも変更しない。

#### 3.5.1 検索の「件数」について（Codex 検分・重大1 への回答）

初版の AC-6 は「ヒット件数が絞り込み後の実件数と一致する」と書いたが、**これは達成不能である**。

`searchByKeyword`（:66-69）の戻り値は `List<ShiftScheduleEntity>` であり、`Pageable` は
`PageRequest.of(0, SEARCH_LIMIT)`（`GlobalSearchService`:78、**`SEARCH_LIMIT = 10`**）で渡される。
`GlobalSearchService`:133 は返ってきたリストの `size()` をそのまま `counts` に入れている。
**総件数を返すクエリはどこにも無い**ため、該当が 11 件以上あれば count は常に 10 になる。
これは shifts 種別だけの問題ではなく、`schedules` / `events` / `reservations` など**全 9 種別に共通の既存仕様**である。

本設計は**この仕様を変更しない**。理由:

- 総件数を出すには count query の追加か `Page<T>` への戻り値変更が要り、9 種別すべてに波及する。
  これは「未公開シフトを非管理者に見せない」という本件の目的から外れた横断改修であり、
  1 つの PR に目的の違う変更を混ぜることになる。
- 未公開シフトを母集団から外す目的は「件数の正確さ」ではなく「ヒットさせないこと」であり、
  上限 10 件の切り捨てがあっても目的は達成できる。

よって **AC-6 の検証は「返却された結果の中に未公開シフトが 1 件も含まれないこと」で行う**（件数の一致は要求しない）。
検索の総件数が上限値で頭打ちになる既存仕様は §8 の B-10 として別起票する。

### 3.6 「公開済み」判定述語（実装で共有する唯一の定義）

```
公開済み（非管理者に L-ASSIGN まで開いてよい） :=
    status = PUBLISHED                                   -- publishedAt は見ない
    OR (status = ARCHIVED AND publishedAt IS NOT NULL)

非管理者に存在ごと秘匿する :=
    status = DRAFT
    OR (status = ARCHIVED AND publishedAt IS NULL)       -- fail-closed
    OR status IS NULL                                    -- DTO 経由の判定時（fail-closed）

非管理者にメタと骨格だけ開く :=
    status IN (COLLECTING, ADJUSTING)
```

この 3 分類は**必ず 1 か所に定義して全経路が参照する**こと。想定する実装形:

- エンティティ側: `ShiftScheduleEntity#isPubliclyVisible()` / `#isHiddenFromNonAdmin()`
- 検索クエリ側: 同値の JPQL 断片を 1 つの定数として持ち、`searchByKeyword` から参照する
- PDF 側: DTO から同じ判定を行うヘルパ（`status == null` を未公開扱いにする）

経路ごとに条件式を書き写すと、後日ステータスが増えたときに一部経路だけ直され、直っていない経路が静かに残る。
ただし**これを機械的に検証する手段は無い**（AC ではなく指針とする理由は §7 の「設計指針」節を参照）。

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

### 4.2 二層の順序（既存 403 契約を 1 件も変えないための要）

1. **認可（誰が）の 403 が先。** `checkTeamReadAccess` が走り、
   非メンバー・SUPPORTER・別 scope は従来どおり **403**。
2. **可視性（何が）の 404 が後。** その関門を通過した「当該チームの正当な閲覧者」に対してのみ
   可視性判定が働き、未公開なら **404**。
3. **SYSTEM_ADMIN 短絡は 1 より前。** 全ステータス・全層を開ける。

この順序により既存の 403 契約は 1 件も変わらない。**403 テストを 404 に書き換える必要は無い。**
逆順にすると、別チームの ADMIN が 403/404 の差で「そのチームに未公開シフトがある」ことを観測できる
（存在オラクルが場所を変えて残る）。

---

## 5. 既存テストへの影響（実ファイルを開いて 1 件ずつ判定）

### 5.1 影響表

判定日: 2026-09-03。**ビルド・テストは実行していない**（設計フェーズのため）。
以下は各テストのフィクスチャとモック設定をソース上で読んで導いた見積りである。

| # | テスト | 該当箇所 | フィクスチャ | 影響 | 対処 |
|---|---|---|---|---|---|
| T1 | `ShiftScheduleServiceTest`（UT） | `GetSchedule.スケジュール単体取得_正常_レスポンス返却`（:231-246） | `createScheduleEntity()`（:94-105）が **`status(DRAFT)`**。`isMember=true` を stub し `isSystemAdmin` は stub していない（＝false） | **赤**。可視性判定で 404 になる | フィクスチャに PUBLISHED 版を足して正常系を移す。DRAFT に対する 404 を新規ケースで追加 |
| T2 | `ShiftScheduleServiceTest`（UT） | `ListSchedules` 系（:180-220 付近） | 同じ DRAFT エンティティを返すモック | **赤の可能性**。返却リストが空になり件数アサーションが落ちる（書き方次第） | T1 と同じ方針。一覧の正常系は PUBLISHED フィクスチャへ |
| T3 | `ShiftSlotServiceTest`（UT） | `ListSlots`（:116-132）／`GetSlot`（:166-175） | `@BeforeEach`（:87）で **`isSystemAdmin(ACTOR)` を `true`** に stub | **影響なし**。SYSTEM_ADMIN 短絡が認可・可視性の両方より前に来るため可視性判定に到達しない。`scheduleRepository.findById` の追加 stub も不要 | 変更不要。ただし非 SYSTEM_ADMIN の伏せ挙動（`[]` + `assignmentMasked=true`）を見る新規テストは別途要る |
| T4 | `ShiftPdfServiceAuthzTest`（UT） | `MEMBER_認可通過`（:133-148）／`MEMBER_personalPdf_認可通過`（:185-199） | `scheduleOf()`（:65-70）が `id` と `teamId` だけを設定し **`status` を設定していない（null）** | **赤**。§3.4.1 の fail-closed 規則により未公開扱いで 404 | `scheduleOf` に `ShiftStatusDto("PUBLISHED", ...)` を持たせる。加えて未公開 PDF の 404 ケースを新規追加 |
| T5 | `ShiftPdfServiceAuthzTest`（UT） | `SUPPORTER_COMMON_002`（:117-131）／`IDOR_他チームのscheduleId`（:150-165） | 同上 | **影響なし**。認可 403 が可視性判定より前で発火するため status が null でも結論が変わらない | 変更不要（二層順序の正しさを裏付ける証拠でもある） |
| T6 | `ShiftScheduleScopeContractIT`（IT） | `GetSchedule.一般メンバーは200`（:401 以降） | `scheduleA` が **`ShiftScheduleStatus.DRAFT`**（:126-135） | **赤**。404 になる | 期待値でなく**フィクスチャを直す**。PUBLISHED で 200 を固定し、DRAFT の 404 を新規ケースで追加 |
| T7 | `ShiftScheduleScopeContractIT`（IT） | `ListSchedules.一般メンバーは200`（:331-337） | 同 DRAFT | **緑のまま。ただし素通り**。ステータスコードしか見ておらず返却が空配列でも 200 で通る | 中身（件数・id）を検証する新規ケースを足す。AC-1 はここを見る |
| T8 | `ShiftScheduleScopeContractIT`（IT） | 403 系すべて（別 scope ADMIN / SUPPORTER / 無所属） | — | **影響なし**。二層順序により認可 403 が先 | 変更不要 |
| T9 | `ShiftSlotScopeContractIT`（IT） | `ListSlots.正当メンバーは200`（:165-170） | schedule が **DRAFT**（:108-117） | **赤**。404 になる | T6 と同じ。PUBLISHED へ移し、DRAFT の 404 と COLLECTING のマスクを新規追加 |
| T10 | `ShiftSlotScopeContractIT`（IT） | 403 系・書込系すべて | — | **影響なし**。書込は可視性判定の対象外（管理者しか到達しない） | 変更不要 |
| T11 | `ShiftRequestServiceTest`（UT） | :98 COLLECTING / :110 DRAFT / :120 COLLECTING | 希望提出の対象スケジュール | **影響なし**。`submitRequest` は `findScheduleOrThrow`（可視性判定を持たない）を直接呼ぶ。DRAFT フィクスチャは「DRAFT には提出できない」の検証で期待は例外のまま | 変更不要。ただし `findScheduleOrThrow` に可視性判定を足してはならない（R2） |
| T12 | `ShiftRequestProxyInputTest`（UT） | :98 COLLECTING | 代理入力 | **影響なし**。同上 | 変更不要 |
| T13 | `ShiftChangeRequestScopeContractIT`（IT） | schedule が **DRAFT**（:103） | 変更依頼の一覧・詳細 | **影響なし**。変更依頼 API は射程外（§8 B-4）で可視性判定を足さない | 変更不要 |
| T14 | `ShiftAutoAssignScopeContractIT`（IT） | schedule が **DRAFT**（:556） | 自動割当・run 参照 | **影響なし**。すべて管理者専用 EP で可視性判定の対象外（R7） | 変更不要 |
| T15 | `ShiftSwapScopeContractIT`（IT） | schedule が **PUBLISHED**（:478）だが **`publishedAt` を設定していない**（:472-483） | 交代リクエスト | **影響なし**。§2.2(e) で PUBLISHED は `publishedAt` を見ないと定めたため。**初版のように「publishedAt が唯一のオラクル」と書いていたらここが赤くなっていた** | 変更不要（この設計判断の妥当性を裏付ける証拠） |
| T16 | `ShiftSwapServiceTest`（UT） | schedule が **DRAFT**（:97） | 交代リクエスト | **影響なし**。交代 API は射程外（§8 B-3/B-5/B-11） | 変更不要 |
| T17 | `ShiftMapperTest`（UT） | :70-78 が **`status(PUBLISHED)` かつ `publishedAt` 未設定** | DTO 変換のみ | **影響なし**。Mapper は可視性を判定しない。PUBLISHED は `publishedAt` を見ない規則のため矛盾も生じない | 変更不要 |
| T18 | `ShiftPreferenceReminderBatchServiceTest`（UT） | :289 DRAFT / :390 COLLECTING | リマインドバッチ | **影響なし**。バッチ経路には可視性判定を入れない（R3）。バッチに「閲覧者」は存在しない | 変更不要 |
| T19 | `ShiftChangeRequestServiceAuthzTest`（UT） | `ShiftScheduleStatus` の参照なし | — | **影響なし**。射程外 | 変更不要 |
| T20 | `ShiftHourlyRateScopeContractIT`（IT） | `ShiftScheduleStatus` の参照なし | — | **影響なし**。時給 API は射程外 | 変更不要 |
| T21 | `ShiftRequestPositionScopeContractIT`（IT） | schedule が **COLLECTING**（:112） | 希望提出・ポジション | **影響なし**。COLLECTING は非管理者にもメタ・骨格を開くため結論が変わらない | 変更不要 |
| T22 | FE 単体テスト | `ShiftScheduleList.vue` のフィルタを検証するテスト | — | **未確認**。`frontend` 側に当該コンポーネントの単体テストがあるかは調べていない | 実装時に `frontend/tests` を走査して判定すること |

**確実に赤になる: 4 件**（T1・T4・T6・T9）。**書き方次第で赤: 1 件**（T2）。
**新規追加が要る: T3・T7 ほか AC ごとのケース。**
**影響なしと判定: 16 件**（T5・T8・T10〜T21）。**未確認: 1 件**（T22）。

初版の「IT 2 件」は**過少見積りだった**。単体テスト 2 件（T1・T4）を数え落としていた。

### 5.2 未決事項（1 件は決着済み・1 件は調査済み）

#### U-1: 割当を伏せたことを、どう表現するか — **決着（案B・マスター裁可 2026-09-03）**

> **結論**: **案B を採用する。** `ShiftSlotResponse` に `assignmentMasked: boolean` を追加し、
> FE はそのフラグに従って表示を切り替える。確定した要求は **§7 の AC-4** に記述した。
> 以下は裁可に至るまでの調査と選択肢の記録であり、経緯として残す（削除しない）。

**問題**: `assignedUserIds` を空配列 `[]` にすると、
「本当に誰も割り当たっていない枠」と「伏せられた枠」を画面が区別できない。

**実コードで起きる具体的な誤表示**（`frontend/app/pages/shift/[id]/index.vue` を実読して確認）:

- :129-131 の `isUnderStaffed(slot)` は `slot.assignedUserIds.length < slot.requiredCount` で判定する。
- :344-352 はその結果で**赤バッジ／緑バッジ**を出し分け、`{{ slot.assignedUserIds.length }}/{{ slot.requiredCount }}` を表示する。
- この表は `canManage`（:31-38）で囲われて**いない**。`canManage` が制御するのは編集ボタン（:217）だけで、
  **一覧表そのものは一般メンバーにも描画される**。

したがって空配列のまま入れると、一般メンバーが COLLECTING/ADJUSTING のシフト表を開いたとき
**全枠が赤で「0/2」「0/3」と並び、あたかも全枠が人員不足であるかのように見える**。
実際には割当が進んでいるかもしれないのに、である。
`shift/[id]/edit.vue`（:277-289）も同じ赤緑バッジを出すが管理者向け画面なので影響は無い。
`ShiftSwapRequestFormDialog.vue`（:110-128）は交代相手の候補を `assignedUserIds` から作るが、
交代申請は公開後の運用であり未公開シフトで開かれる想定が無いため実害は薄い。

**選択肢**:

| 案 | 内容 | 得失 |
|---|---|---|
| **案A: 空配列のまま許容し、FE に「非公開」表示を足す** | BE は `[]` を返す。FE は人数バッジを出さず「まだ公開されていません」に置き換える | BE の変更が最小。ただし FE が「未公開かどうか」を `schedule.status` から自分で判断する必要があり、**BE と FE で判定規則が二重化する**（§7 の AC-15 と同じ問題）。判定を誤れば「0/2」の誤表示が残る |
| **案B: 伏せたことを別フィールドで示す** | `ShiftSlotResponse` に `assignmentMasked: boolean`（既定 false）を足し、伏せたとき true にする。FE はこれを見て人数バッジを非表示にする | 誤表示が構造的に起きない。BE が真実を 1 か所で決め FE はそれに従うだけになる。API 契約の変更（1 フィールド追加）と生成型の再生成が要る。**推奨** |
| **案C: 一般メンバーには人数系を全部落とす** | `requiredCount` も返さない | 最も安全だが `requiredCount` は「この枠は何人必要か」という希望提出の判断材料であり、落とすと提出画面の情報が減る。過剰 |

**家老の推奨: 案B。**
案A は「BE と FE の両方が同じ規則を実装する」構造を作る。本設計は §7 の AC-15 で、
まさにその二重化を避けるために FE のステータスフィルタを削除する方針を採っている。
案A はそこで削った二重化を別の場所に作り直すことになり、一貫しない。
案B ならフィールドが 1 つ増えるだけで、FE は `assignmentMasked` を見るだけでよく、判定規則は BE の 1 か所に閉じる。
コストは OpenAPI 型の再生成 1 回である。

**マスターへの問い**（2026-09-03 に上申）: 「割当を伏せたシフト表を、メンバーの画面でどう見せますか。
（B）サーバーが『伏せました』という印を付けて返し、画面はそれに従う ← 推奨 /
（A）サーバーは空で返し、画面側が状態を見て自前で表示を切り替える /
（C）人数の情報自体をメンバーには返さない」

**裁可（2026-09-03）: 案B。** 家老の推奨どおり。以後この設計書は案B前提で読むこと。
AC-4 は暫定を外して確定形に書き換えた（§7）。

#### U-2: 交代申請 API からの代替漏洩は無い（Codex 中8 への回答）

**確認したこと**（`ShiftSwapService` と `ShiftSwapController` を実読）:

- `listSwapRequests`（:74-84）は冒頭で `checkTeamAdminAccess` を呼ぶ**管理者専用**。非管理者は 403。
- `listMySwapRequests`（:91-94）は `findByRequesterIdOrderByCreatedAtDesc(userId)` で
  **自分が出した申請しか返らない**。
- `SwapRequestResponse`（全 13 フィールド）は `slotId` / `requesterId` / `accepterId` / `targetUserIds` /
  `claimedBy` を持つが、**枠の割当一覧（`assignedUserIds`）に相当するフィールドは無い**。
- `ShiftSwapController` の GET は `listSwapRequests` の 1 本のみで、
  「ある枠に誰が入っているか」を問い合わせる読み取り EP は存在しない。

**結論: 交代 API から未公開シフトの割当内容は出ていない。今回の遮断は骨抜きにならない。**

ただし副次的な観測余地が 1 点ある。`createSwapRequest`（:112-139）は
`checkTeamMemberAccess(resolveTeamIdBySlotId(req.getSlotId()), userId)` しか見ないため、
**未公開シフトの slotId に対しても交代申請を作れてしまう**。
これは「その slotId が自チームに存在する」ことの確認（存在オラクル）にはなるが、
誰が割り当たっているかは返らない。射程外に回してよい性質であり、
本件の目的である L-ASSIGN の遮断は損なわれない。§8 に B-11 として起票する。

---

## 6. 壊してはならないもの（回帰リスク）

実装者・検分者はこの一覧を必ず踏むこと。

| # | 守るもの | 壊れる原因 | 確認方法 |
|---|---|---|---|
| R1 | 一般メンバーの希望提出画面（`my/shift-request.vue`） | COLLECTING を一覧から落とす／COLLECTING の枠一覧を 404 にする | メンバーで COLLECTING のシフト表が一覧に出て、枠が並び、希望を提出できる |
| R2 | 希望提出 API | `submitRequest`（:98-102）が使う `findScheduleOrThrow`（:392-395）に可視性 404 を混ぜる。**このメソッドは可視性判定を持たない素の取得口として保つこと** | メンバーが COLLECTING に希望を出せる（T11 が緑のまま） |
| R3 | 希望提出リマインド通知 | `ShiftPreferenceReminderBatchService`（:229-262）は COLLECTING のシフト名を通知する**正規仕様**。バッチ経路に閲覧者依存の可視性判定を持ち込むと通知が壊れる | バッチ経路には可視性判定を入れない（バッチに「閲覧者」は存在しない）。T18 が緑のまま |
| R4 | 管理者の調整画面・D&D ボード | `toSlotResponse`（:316 付近）自体でマスクすると `board.vue`・`shift/[id]/edit.vue` の割当編集が空になる。マスクは `listSlots` / `getSlot` の側で行う | 管理者で ADJUSTING のボードに既存割当が表示される |
| R5 | FE の null 安全 | `assignedUserIds` を `null` にする（案B でも `null` は禁止。空配列 + `assignmentMasked=true`） | メンバーで COLLECTING の枠一覧を開いて TypeError が出ない |
| R6 | 既存の 403 契約 | 可視性判定を認可判定より前に置く | `ShiftScheduleScopeContractIT` / `ShiftSlotScopeContractIT` の 403 系（T8・T10）が全て緑のまま |
| R7 | 自動割当・公開ゲート・サマリ | `getScheduleSummary`（:290-292）・`assertNoUnreviewedRuns` は管理者専用経路。可視性判定を足す必要は無い | 管理者の summary が従来どおり。T14 が緑のまま |
| R8 | 検索の既存挙動 | 取得後 Java フィルタで実装する（上限 10 件を取ってから削るため結果がさらに減る） | 絞りが SQL 述語側で行われている |
| R9 | 交代リクエスト作成ダイアログ | `ShiftSwapRequestFormDialog.vue`（:110-128）が `assignedUserIds` を走査して候補者を作る。公開済みシフトでマスクが誤発火すると候補が空になる | 公開済みシフトで交代相手の候補が出る |
| R10 | `PUBLISHED` かつ `publishedAt = NULL` の行 | 「publishedAt が入っていること」を PUBLISHED の判定条件にする | `ShiftSwapScopeContractIT`（:472-483）・`ShiftMapperTest`（:70-78）が緑のまま（T15・T17） |

### 6.1 FE 側に必要な変更

`ShiftScheduleList.vue`（:21-23）は非管理者に `status === 'PUBLISHED'` のみを表示しており、**ARCHIVED も隠している**。
BE を締めたあとこの FE フィルタを残すと、メンバーは COLLECTING/ADJUSTING/ARCHIVED を画面で見られないままになり、
「BE は返すのに画面に出ない」という二重基準が残る。

方針: **FE のフィルタを削除し、表示可否は BE の返却に一本化する。**
多層防御としてフィルタを残す選択もあるが、その場合は BE と同じ規則に書き換えねばならず、規則が 2 か所に分裂する。
BE が未公開を返さなくなる以上、FE 側の絞り込みは冗長である。
`statusConfig`（:24-30）は 5 状態すべてのラベルを既に持っているため、バッジ表示の追加実装は不要。

---

## 7. 受け入れ条件（AC）— v1.1 修正版

各 AC に「これが無いと何が壊れるか」を添える。**AC-4 は U-1 の裁可（案B・2026-09-03）を受けて確定済み。**

### 遮断（漏洩を閉じる）

- **AC-1**: 非管理者（当該チームの一般メンバー）に対し、`GET /shifts/schedules?teamId=` の返却から
  未公開のシフト表が除外される。`from`/`to` 付きの期間指定経路（`listSchedulesByPeriod`）でも同様に除外される。
  検証は「200 が返ること」ではなく**返却配列の中身**（件数と id）で行う。
  → 無いと、下書き段階のシフト表の存在・タイトル・期間が一覧 API で丸見えのまま残る。
     期間指定は L1 の迂回路なので両方必要。ステータスコードだけを見る既存テスト（T7）はこの欠陥を素通りする。
- **AC-2**: 非管理者が `GET /shifts/schedules/{id}` で未公開のシフト表を要求すると **404**（`SHIFT_001`）を返す。
  → 無いと、一覧から消しても ID 直打ちで読める。単体経路を閉じないと AC-1 は見せかけになる。
- **AC-3**: 非管理者が `GET /shifts/schedules/{id}/slots` で未公開のシフト表の枠を要求すると **404** を返す。
  → 無いと、schedule 単体を閉じても枠一覧から日程・ポジション・割当が読める。
- **AC-4（確定・U-1 案B / マスター裁可 2026-09-03）**: 非管理者が `COLLECTING` または `ADJUSTING` の
  シフト表の枠一覧を取得したとき、**200 が返り、割当内容が次の形で伏せられている**。

  1. **`ShiftSlotResponse` に `assignmentMasked: boolean` を新設する。**
     非管理者かつ `COLLECTING` / `ADJUSTING` のとき **`true`**、それ以外（管理者・SYSTEM_ADMIN の全ステータス、
     および非管理者の `PUBLISHED` / 公開済み `ARCHIVED`）は **`false`**。既定値は `false` とし、
     フィールド未設定を「伏せている」と解釈しない（fail-open にしないための既定値の向き）。
  2. **`assignedUserIds` は空配列 `[]` を返す。`null` は禁止。**
     `assignmentMasked=true` のときは常に `[]`、`false` のときは実際の割当。
  3. **枠の骨格は従来どおり返る。** `slotDate` / `startTime` / `endTime` / `positionId` / `positionName` /
     `requiredCount` / slot の `note` は伏せない（希望提出の判断材料であるため。§2.1 の L-FRAME）。
  4. **FE は `assignmentMasked` に従って表示を切り替える。**
     `frontend/app/pages/shift/[id]/index.vue` の `isUnderStaffed`（:129-131）と割当バッジ（:344-352）は
     `assignmentMasked` を見て分岐し、**`true` のときは赤緑の充足バッジ（`0/2` 等）を出さず、
     「調整中」相当の中立な表示に置き換える**。`assignedUserIds.length` を人数として描画しない。
     判定に `schedule.status` を使ってはならない（それをすると案A になり、BE と FE で規則が二重化する）。
  5. **OpenAPI 生成型の再生成が要る。** フィールド追加のため、実装時に
     `cd frontend && npm run generate:types` を実行し、`frontend/app/types/generated/index.ts` の差分をコミットする。
     手書き型 `frontend/app/types/shift.ts`（`assignedUserIds` を持つ :83 / :265）にも追随させること。

  → **1 が無いと**、「本当に誰も割り当たっていない枠」と「伏せた枠」を画面が区別できない。
  → **2 が無いと**（`null` にすると）、FE が `.length`（`shift/[id]/index.vue`:130,351、`edit.vue`:282,287）と
     `.forEach`（`ShiftSwapRequestFormDialog.vue`:114,124）を null チェック無しで呼んでいるため TypeError で落ちる。
  → **3 が無いと**、希望提出画面が「何人必要な枠か」を出せなくなる（AC-8 の回帰）。
  → **4 が無いと**、一般メンバーの画面で**全枠が赤の「0/2」「0/3」となり、全枠が人員不足であるかのように誤表示される**
     （`shift/[id]/index.vue` の一覧表は `canManage`:31-38 の外にあり、一般メンバーにも描画されるため。§5.2 U-1）。
  → **5 が無いと**、FE が新フィールドを型として認識できず `assignmentMasked` を参照できない
     （`types/generated/` は自動生成であり手編集禁止）。
  → そもそも伏せないと、調整中の割当（本件の実害の中心）が漏れたままになる。

- **AC-5**: 非管理者が未公開または `COLLECTING` / `ADJUSTING` のシフト表に対して
  `GET /shifts/schedules/{id}/pdf`（`layout=team` / `layout=personal` の両方）を要求すると **404** を返す。
  DTO の `status` が `null` の場合も未公開扱い（fail-closed）とする。
  → 無いと、未公開シフト表がそのまま紙になるか、割当欄が空の無意味な PDF が出る。
     §6【v2.2】の既存宣言との矛盾も解消されない。`status == null` を公開扱いにすると、
     DTO を部分的にしか組み立てない経路（T4 の `scheduleOf` が実例）から遮断が抜ける。
- **AC-6（修正）**: 非管理者のグローバル検索の shifts 結果に、**未公開のシフト表が 1 件も含まれない**。
  絞り込みは `searchByKeyword` の **SQL 述語側**で行われる。
  検索対象は `title` と `note` の両方であるため、**note にしか含まれない語で検索してもヒットしない**ことを確認する。
  **件数（`counts.shifts`）の正確さは要求しない**（§3.5.1 のとおり、現行実装は総件数を持たず
  上限 10 件で頭打ちになる既存仕様であり、本件の射程外）。
  → 無いと、一覧・単体・枠・PDF を全部閉じても検索から未公開シフトのタイトルが読め、
     note の文言も総当りで 1 語ずつ推定できる。
     取得後フィルタで実装すると上限 10 件をさらに削るため結果が痩せる。
- **AC-7**: `status = ARCHIVED` かつ `publishedAt IS NULL` のシフト表は、非管理者に対して
  AC-1〜AC-6 のすべてで `DRAFT` と同一に扱われる（一覧から除外・単体 404・枠 404・PDF 404・検索ヒットせず）。
  → 無いと、DRAFT を作って直接 ARCHIVED へ遷移させるだけで全遮断を迂回できる
     （`transitionStatus`:227 が遷移元を検査しないため実際に可能。管理者が画面から実行できる）。
- **AC-17（新規）**: `status = PUBLISHED` かつ `publishedAt IS NULL` のシフト表は、
  非管理者に対して**公開済みとして扱われる**（一覧に含まれ、単体 200、枠は全量、PDF 200、検索ヒット）。
  → 無いと、`ShiftSwapScopeContractIT`（:472-483）や `ShiftMapperTest`（:70-78）が作る形のデータ、
     および `published_at` に制約が無い DB（`V3.070`）が許す本番データで、
     公開済みシフトが誤って非表示になる。§2.2(e) の非対称規則の番人。

### 非回帰（機能を壊さない）

- **AC-8**: 当該チームの一般メンバーが `COLLECTING` のシフト表を一覧で取得でき、その枠一覧を取得でき、
  `POST /shifts/requests` で希望を提出できる（`my/shift-request.vue` の一連のフローが通る）。
  → これが無いと、一度目の軍議と同じ機能回帰（希望提出画面が空になる）を再発させる。
- **AC-9**: 当該チームの一般メンバーが `ADJUSTING` のシフト表を一覧・単体で取得でき、`status` として `ADJUSTING` を受け取れる。
  → 無いと、希望を出したシフト表が調整開始と同時に画面から消え、メンバーから見て機能が壊れたように見える。
- **AC-10**: 当該チームの ADMIN / DEPUTY_ADMIN は、全ステータス（DRAFT を含む）で
  一覧・単体・枠一覧・PDF・検索のすべてを従来どおり全量取得でき、枠の割当が伏せられない
  （`assignedUserIds` は実際の割当、`assignmentMasked` は `false`）。
  → 無いと、管理者の調整画面・D&D ボードが空になり、シフト作成そのものができなくなる。
- **AC-11（修正）**: SYSTEM_ADMIN は、当該チームの**メンバーでなくても**全ステータスで AC-10 と同じ結果を得る。
  これを満たすには 2 つの実装が要る:
  **(a)** 新設する可視性判定を `isSystemAdmin` で最初に短絡すること。
  **(b)** `ShiftPdfService#checkMemberAndNotSupporter`（:100-106）に `isSystemAdmin` 短絡を追加すること。
  (b) は既存欠陥（§1.2 X7）の是正であり、**(a) だけでは PDF について本 AC を満たせない**
  （非メンバーの SYSTEM_ADMIN は可視性判定を通過した直後にこの行で 403 になる）。
  → 無いと、`ShiftPdfService` の非対称が温存され、「SYSTEM_ADMIN は全部見られる」という
     ドメイン全体の約束が PDF だけ破れたまま残る。
- **AC-12**: 既存の認可 403 契約が 1 件も変わらない。すなわち
  別 scope の ADMIN・SUPPORTER・無所属ユーザーは、**シフト表のステータスに依らず常に 403** を受け取る。
  → 403 より先に 404 を返すと、別チームの ADMIN が応答コードの差で
     「そのチームに未公開シフトがある」ことを観測できる（存在オラクルが場所を変えて残る）。
- **AC-13（修正）**: §5.1 の影響表で「赤」と判定した **T1・T4・T6・T9（および T2）** が、
  **期待値の書き換えではなく `PUBLISHED` のフィクスチャを用意する形で緑化される**。
  加えて未公開に対する 404 のケースが新規に足される。
  「影響なし」と判定した T5・T8・T10〜T21 は**変更なしで緑のまま**であること
  （変更が必要になった場合、二層順序か判定規則のどちらかを実装で誤っている）。
  → 期待値だけを 404 に書き換えると「一般メンバーが自チームの公開シフトを読める」という日常正常系の番人が消える。
     また「影響なし」群が赤くなるなら、それは設計どおりに実装できていないことの信号である。
- **AC-14**: `ShiftPreferenceReminderBatchService` の COLLECTING 希望提出リマインド通知が従来どおり
  シフト表名を含んで送信される（バッチ経路に可視性判定が入っていない）。
  → 無いと、正規仕様の通知が沈黙する。バッチには「閲覧者」がおらず可視性判定を適用する主体が存在しない。
- **AC-15**: `ShiftScheduleList.vue` の非管理者向けフィルタ（:21-23）が削除され、
  メンバーの画面に `COLLECTING` / `ADJUSTING` / `PUBLISHED` / `ARCHIVED`（公開済み）が表示される。
  **FE は「どのシフト表を出すか」をステータスで判断しない**（BE が返したものをそのまま並べる）。
  → 無いと、BE を正しく直しても画面上は PUBLISHED しか出ず、BE と FE で規則が二重化したまま残る。

  **AC-4(4) との棲み分け（矛盾しないことの確認）**: AC-15 は「シフト表を一覧に出すか否か」、
  AC-4(4) は「出したシフト表の枠で、割当の充足バッジを出すか否か」であり、対象が異なる。
  どちらも「FE がステータスを見て自前で判断しない」という同じ原則に立つ:
  AC-15 では BE の返却有無に従い、AC-4(4) では BE が返す `assignmentMasked` に従う。
  したがって AC-15 で削除するのは `ShiftScheduleList.vue` の **`status === 'PUBLISHED'` フィルタだけ**であり、
  同ファイルの `statusConfig`（:24-30）によるステータスバッジ表示は**残す**
  （ラベルの出し分けは判定ではなく表示であり、BE の返した `status` をそのまま描画しているにすぎない）。

### 設計指針（AC ではない）

- **G-1（旧 AC-16 を格下げ）**: §3.6 の 3 分類は単一の定義として実装し、
  一覧・単体・枠・PDF・検索の全経路がそれを参照すること（条件式の書き写しをしない）。

  **これを AC から外した理由**: 「条件式が書き写されていないこと」を機械的に判定する手段が無い。
  ArchUnit は「あるクラスがあるメソッドを呼んでいること」は言えるが、
  「どこにも同値の条件式を別途書いていないこと」は言えない。
  ステータスの enum 比較は通常の Java 式であり、静的解析で網羅的に検出できない。
  判定不能な条件を AC に置くと、検分で「満たしている」と主張されても反証できず、番人として機能しない。
  よって**検分時の目視レビュー項目**（レビュアーが全 5 経路を開いて参照先を確認する）に格下げする。

  **将来 AC に昇格させる道**: 可視性判定を専用クラス（例: `ShiftScheduleVisibilityPolicy`）に切り出せば、
  「shift ドメインで `ShiftScheduleStatus.PUBLISHED` を直接比較してよいのはこのクラスと
  `transitionStatus` だけ」という ArchUnit ルールが書けるようになり、判定可能になる。
  実装者がその形を採るなら、そのとき AC へ昇格させてよい。

---

## 8. 別起票すべき残件（今回の射程外）

いずれも本設計では**触らない**。`docs/task-list.md` への起票は殿が別途行う。

| # | 内容 | 根拠 | 所見 |
|---|---|---|---|
| B-1 | `ShiftMyService#getMyConfirmedSlots`（:53-121）が schedule のステータスを見ずに CONFIRMED 割当を返す。未公開シフトの日時・チーム名・シフト表名が本人に露出する | §1.2 X1 | 中。公開前に「自分のシフトが決まった」と誤認させる。本件と同じ根（ステータス無視） |
| B-2 | ダッシュボード直近予定（`ShiftAssignmentRepository#findUpcomingByUserIdBetween`:61-71 / `DashboardController`:334,351-352）が同様にステータス非考慮 | §1.2 X2 | 中。B-1 と同一原因・別経路。**B-1 と同じ戦役で一緒に直すべき** |
| B-3 | `ShiftRequestService#listMyRequests`（:84-87）・`ShiftSwapService#listMySwapRequests`（:91-94）に status 境界が無い | §1.2 X3/X5 | 低。自分が出したものしか返らない |
| B-4 | `ShiftChangeRequestService#list`（:110-122）/ `#get`（:141-153）に status 境界が無い | §1.2 X4 | 低〜中。認可自体は効いている |
| B-5 | **`ShiftSwapService#createOpenCall`（:222-235）に認可が一切無い。** `slotId` の所属チーム検証もしていないため、任意の slotId に対してオープンコールを作成できる | §1.2 X6 | **高。本件とは別系統の欠陥で、書込 API の無認可**。単独で早期起票を推奨 |
| B-6 | `ShiftPdfService#checkMemberAndNotSupporter`（:100-106）が SYSTEM_ADMIN を短絡しない | §1.2 X7 | 低。ただし**本設計の AC-11(b) で今回まとめて直す**ため、別起票は不要になる可能性がある。射程判断は殿に委ねる |
| B-7 | shift ドメイン内で越境時の応答が 403（`ShiftScheduleService` / `ShiftSlotService`）と 404（`ShiftChangeRequestService` / `ShiftAutoAssignService`）に割れている | §1.3 | 中。本設計は「認可 403・可視性 404」の二層で当座を整理するが、越境そのものの応答は未統一のまま残る |
| B-8 | `transitionStatus`（:214-241）/ `ShiftScheduleEntity#archive()`（:171-173）が遷移元を検査せず、DRAFT → ARCHIVED / PUBLISHED → COLLECTING 等の逆行・飛び越しを許す。`04_security_operations.md` §6 の「許可されていないステータス遷移はアプリ層で厳密にブロック」という宣言と矛盾する | §1.3・§2.2(e) | 中。本設計は fail-closed でこの欠陥に耐えるが、欠陥自体は残る |
| B-9 | `shift_schedules.published_at`（`V3.070`）に `status` との整合制約が無く、`PUBLISHED + published_at IS NULL` を作れる。DB 制約または投入経路の一元化 | §2.2(e) | 低〜中。本設計は「PUBLISHED は publishedAt を見ない」ことで回避しているが、不変条件が無い状態は残る。制約追加は既存違反行のマイグレーション失敗リスクを伴うため単独で扱うべき |
| B-10 | グローバル検索の全 9 種別が総件数を持たず、`counts` が上限 `SEARCH_LIMIT = 10` で頭打ちになる（`GlobalSearchService`:78,133）。count query または `Page<T>` 返却への移行 | §3.5.1 | 中。ユーザーからは「11 件以上あっても 10 件と表示される」と見える。9 種別横断の改修になるため単独の戦役が要る |
| B-11 | `ShiftSwapService#createSwapRequest`（:112-139）がチームメンバーシップしか見ず、未公開シフトの slotId に対しても交代申請を作れる（slotId の存在オラクル） | §5.2 U-2 | 低。割当内容は返らないため本件の遮断は骨抜きにならない |

---

## 9. 変更履歴

- **v1.2 (2026-09-03)**: 未決事項 U-1 にマスターの裁可（**案B**）が下りたため設計を締めた。
  - AC-4 を暫定から**確定形**へ書き換え（`assignmentMasked: boolean` の新設・空配列の維持・
    FE の分岐箇所・OpenAPI 生成型の再生成を 5 項目で明記）。
  - §2.1 / §2.3 / §3.3 の「マスク」表記を案B前提（`[]` + `assignmentMasked=true`）へ統一。
  - AC-10 に `assignmentMasked=false` を、R5 に案B でも `null` 禁止であることを追記。
  - AC-15 に AC-4(4) との棲み分けを追記し、削除対象が `status === 'PUBLISHED'` フィルタのみで
    `statusConfig` によるバッジ表示は残すことを明確化（重複・矛盾の解消）。
  - §5.2 U-1 は削除せず「決着（案B・マスター裁可 2026-09-03）」として経緯を残した。
- **v1.1 (2026-09-03)**: Codex 検分の指摘 12 件を反映。
  - 重大1: AC-6 の「件数一致」は現行実装では達成不能（総件数を返すクエリが無く上限 10 件で頭打ち）。
    AC を「未公開が 1 件も含まれないこと」に定義し直し、件数問題は B-10 として別起票。
  - 重大2: 検索の記述を「検索対象（`title` + `note`）」と「返却（`id` + `title`）」に分離。
    note がヒット判定に効くため総当りで推定可能である点を明記。
  - 重大3: 既存テスト影響を IT 2 件から**全 22 件の判定表**に作り直し（§5.1）。
    単体テスト T1・T4 の数え落としを是正。
  - 重大4: `publishedAt` は DB 制約が無く「唯一のオラクル」ではない。
    「PUBLISHED は status のみ、ARCHIVED のときだけ publishedAt を参照し NULL は fail-closed」へ規則を変更。
    AC-17 を新設して `PUBLISHED + publishedAt NULL` の非回帰を固定。B-9 を別起票。
  - 重大5: 空配列マスクの業務意味を §5.2 U-1 として未決事項に整理。
    `shift/[id]/index.vue`（:129-131, :344-352）で全枠が赤「0/N」と誤表示される具体例を確認し、3 案と推奨（案B）を提示。
  - 重大6: 旧 AC-16 は機械的に判定できないため AC から外し、設計指針 G-1 へ格下げ。昇格の道筋も併記。
  - 中7: PDF の DTO 経由判定で `status == null` を fail-closed とする規則を §3.4.1 に明記。
  - 中8: 交代 API からの代替漏洩を実コードで確認し「無い」と結論（§5.2 U-2）。副次的な B-11 を起票。
  - 中9: `checkMemberAndNotSupporter` の SYSTEM_ADMIN 非短絡により AC-11 が満たせない点を反映し、
    AC-11 を (a)(b) 2 段構成へ書き換え。
  - 中10・軽微11・12: §3 の「未公開」定義の統一、回帰表への R10 追加、影響表の「影響なし」理由の明記。
- **v1.0 (2026-09-03)**: 初版。CMP-260826-2127 の軍議やり直しとして、隔ての軸を「ステータスで開閉」から
  「情報の層 × ステータス」へ引き直し、漏洩経路の棚卸し・期待結果マトリクス・AC・別起票残件を定める。
