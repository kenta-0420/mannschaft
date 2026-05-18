# Stage3 第五陣 succession ドメイン triage 作業ログ

> 担当: 足軽5-β（feature/api-drift-cleanup-succession）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v5
> 関連ドメイン: `/api/v1/succession/*` (18 件) + section 2 中の `/api/v1/organizations/{_}/succession/*` 実装側計上 18 件 + section 4 (V4-1 スコープ階層プレフィックス逆引き準一致) 中の `GET /api/v1/succession/covenants/me` 1 件
> 引き継ぎ: 第四陣 4-β（dwelling-units）から `POST /api/v1/succession/residents/{_}/death-status` 1 件のバトンを受領（dwelling-units.md §4 事例 D 参照）

---

## 0. 取扱方針

F09.15 区分所有者承継支援は **memory `project_f0915_s5_s6_complete.md` により v1 実装フェーズ完全クローズ済（2026-05-16）** の案件である。S5（滞納エスカレーション + Stage4 死亡疑い自動起票）と S6（法的ナビ + 申立 PDF + 証拠 ZIP）がすべて main にマージされており、これ以上 v1 範囲で新規 Controller を追加する予定はない。

しかし設計書 `docs/features/F09.15_resident_succession_support.md` §6.1 のエンドポイント一覧 19 行は **当初設計（フラットパス `/api/v1/succession/...`）のまま** で、実装が確立した **組織スコープ階層 `/api/v1/organizations/{orgId}/succession/...`** および **承認用語のリネーム（first-approve → approve / reject → cancel）** / **証拠パッケージ用語のリネーム（evidence-zip → evidence-package/download-url / evidence-rebuild → evidence-package）** に追随できていない。

設計書冒頭の §6 にはスコープ移行注記が既に存在するが、表側はそのままなので scanner v5 で 18 件が「設計あり・実装なし」として計上され、対応する 18 件の組織スコープ実装が「実装あり・設計なし」として計上される（双子検出）。

すなわち triage 観点では:

1. 設計書 §6.1 の 19 行を **実装パスへ全件書き換え**（13 件は組織スコープ移行 / リネーム、6 件は v2 候補として 🔵 マーカ付与）
2. §6.2「主要エンドポイント仕様」の `####` 見出しも書き換え
3. F09.15 v2（収益化後）候補の **5 種類のエンドポイント** を §6.1 表上で 🔵 マーカ + 「v2: 収益化後」明示
4. 第四陣からバトンされた `POST /residents/{_}/death-status` は memory より「Stage4 死亡疑い自動起票は `ResidentRegistryService.markDeathSuspected()` 内部メソッド経由で実装。外部公開 API 化は v2」と判定 → 🔵
5. baseline §1 L1650 の `POST /api/v1/succession/covenants/{_}/verify` は F12.1 設計書 §5.14 由来の「保存中 PDF を取得して再ハッシュ + token 検証する API」だが、実装は無く、F12.1 側でも参照されるのみ。F09.15 §9.4「改ざん防止」に **設計済 / 未実装** として記載があるため 🔵 マーカ + §6.1 表へ追記
6. section 4 (V4-1) の 1 件 `GET /api/v1/succession/covenants/me` は scanner 動作正常。triage 観点でアクション不要（設計書側を実装に揃えれば準一致リストからも除外される）

exclusions.yml は更新しない。succession ドメインの実装側エンドポイントは将来も維持される本物の API であり、scanner で除外する性質ではないため。

---

## 1. 集計

| 分類 | 件数 | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加） | 0 | v1 全件実装済み（memory `project_f0915_s5_s6_complete.md` 参照） |
| 🟡 設計書更新要 | 13 | §6.1 全 19 行のうち実装済 13 件をパス書き換え + §6.2 見出しリネーム |
| 🔵 将来機能（マーカ付与） | 6 | v2（収益化後）の covenant-templates / pre-registrations 3 件 / audit-views / frozen-account-guidance / death-status / covenants verify を 🔵 化 |
| ⚪ 除外（exclusions.yml） | 0 | 全エンドポイントが正式機能 |
| 🐞 スキャナ偽陽性 / 改善余地 | 2 | 詳細は §4 |
| **合計** | **19 件**（baseline §1 18 件 + §6.1 但し書きで設計に追加すべき verify 1 件） | |

baseline §1 計上は 18 件だが、設計書 §6.1 に明示掲載されているのは sign / revoke を含む 19 件。sign / revoke の 2 件は実装側 (`SuccessionCovenantController#signCovenant` / `revokeCovenant`) が同じフラットパスで実装済で、scanner は「設計と一致」と判定するため baseline §1 計上の対象外。

---

## 2. 内訳

### 2.1 F09.15 §6.1 全 19 行の実装対応マトリクス

| # | 設計書のメソッド + パス（旧） | 実装の有無 + 実装パス（正） | Controller | 判定 |
|---|---|---|---|---|
| 1 | GET `/api/v1/succession/covenant-templates` | **未実装**（誓約テンプレート版数管理は運用バッチで対応中、API 化は v2） | — | 🔵 v2 |
| 2 | POST `/api/v1/succession/covenants/sign` | ✅ 実装済（**フラットパス維持**。MEMBER 本人 API のためスコープ不要） | `SuccessionCovenantController#signCovenant` (L55) | ✅ 一致（変更不要） |
| 3 | POST `/api/v1/succession/covenants/{id}/revoke` | ✅ 実装済（**フラットパス維持**。MEMBER 本人 API） | `SuccessionCovenantController#revokeCovenant` (L68) | ✅ 一致（変更不要） |
| 4 | GET `/api/v1/succession/pre-registrations/me` | **未実装**（事前登録 CRUD は v2） | — | 🔵 v2 |
| 5 | PUT `/api/v1/succession/pre-registrations/me` | **未実装** | — | 🔵 v2 |
| 6 | GET `/api/v1/succession/pre-registrations/{id}` | **未実装** | — | 🔵 v2 |
| 7 | POST `/api/v1/succession/residents/{id}/death-status` | **未実装**（Stage4 死亡疑い自動起票は `ResidentRegistryService.markDeathSuspected()` 内部メソッド経由。外部 API 公開は v2）| — | 🔵 v2（第四陣バトン消化）|
| 8 | POST `/api/v1/succession/unseal-requests` | ✅ POST `/api/v1/organizations/{orgId}/succession/unseal-requests` | `UnsealRequestController#createRequest` (L51) | 🟡 組織スコープへ移行 |
| 9 | POST `/api/v1/succession/unseal-requests/{id}/first-approve` | ✅ POST `/api/v1/organizations/{orgId}/succession/unseal-requests/{id}/approve` | `UnsealRequestController#approve` (L74) | 🟡 スコープ移行 + **リネーム** |
| 10 | POST `/api/v1/succession/unseal-requests/{id}/second-approve` | ✅ POST `/api/v1/organizations/{orgId}/succession/unseal-requests/{id}/second-approve` | `UnsealRequestController#secondApprove` (L97) | 🟡 スコープ移行 |
| 11 | POST `/api/v1/succession/unseal-requests/{id}/reject` | ✅ POST `/api/v1/organizations/{orgId}/succession/unseal-requests/{id}/cancel` | `UnsealRequestController#cancel` (L118) | 🟡 スコープ移行 + **リネーム** |
| 12 | GET `/api/v1/succession/unseal-requests/{id}/audit-views` | **未実装**（開封中閲覧履歴の参照 API は v2。書き込みは S2 で auto-record 済） | — | 🔵 v2 |
| 13 | GET `/api/v1/succession/delinquency-escalations` | ✅ GET `/api/v1/organizations/{orgId}/succession/delinquency-escalations` | `DelinquencyEscalationController#listActive` (L51) | 🟡 スコープ移行 |
| 14 | POST `/api/v1/succession/delinquency-escalations/{id}/freeze` | ✅ POST `/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/freeze` | `DelinquencyEscalationController#freeze` (L97) | 🟡 スコープ移行 |
| 15 | POST `/api/v1/succession/delinquency-escalations/{id}/resolve` | ✅ POST `/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/resolve` | `DelinquencyEscalationController#resolve` (L121) | 🟡 スコープ移行 |
| 16 | POST `/api/v1/succession/legal-filings` | ✅ POST `/api/v1/organizations/{orgId}/succession/legal-filings` | `LegalFilingController#createLegalFiling` (L96) | 🟡 スコープ移行 |
| 17 | GET `/api/v1/succession/legal-filings/{id}/evidence-zip` | ✅ GET `/api/v1/organizations/{orgId}/succession/legal-filings/{id}/evidence-package/download-url` | `LegalFilingController#getEvidenceDownloadUrl` (L164) | 🟡 スコープ移行 + **リネーム**（用語 evidence-zip → evidence-package。実体は ZIP のまま）|
| 18 | POST `/api/v1/succession/legal-filings/{id}/evidence-rebuild` | ✅ POST `/api/v1/organizations/{orgId}/succession/legal-filings/{id}/evidence-package` | `LegalFilingController#buildEvidencePackage` (L144) | 🟡 スコープ移行 + **リネーム** + メソッド責務再定義（再生成も新規生成も同 endpoint で吸収）|
| 19 | GET `/api/v1/succession/frozen-account-guidance` | **未実装**（凍結口座導線テンプレート + FAQ 配信 API は v2） | — | 🔵 v2 |

判定サマリ:
- ✅ 一致（変更不要）: 2 件（sign / revoke は本人 API のためフラットパスのまま）
- 🟡 スコープ移行 / リネーム: 13 件
- 🔵 v2 候補（マーカ付与）: 5 件（covenant-templates / pre-registrations の me 系 3 件 / audit-views / frozen-account-guidance 5 件 + 第四陣バトン death-status 1 件 = 計 6 件）

加えて F09.15 §9.4 で言及されている `POST /api/v1/succession/covenants/{id}/verify` は **§6.1 表に未掲載** だが baseline §1 L1650 で計上されているため、§6.1 表に追加 + 🔵 v2 マーカを付与する。

実装側 18 件（baseline §2 計上）の内訳:

| Controller | 件数 | エンドポイント |
|---|---:|---|
| `SuccessionCovenantController` | 4 | `/api/v1/succession/covenants/sign`, `/api/v1/succession/covenants/{id}/revoke`, `/api/v1/organizations/{orgId}/succession/covenants/{id}`, `/api/v1/organizations/{orgId}/succession/covenants` |
| `UnsealRequestController` | 6 | `POST .../unseal-requests`, `POST .../unseal-requests/{id}/approve`, `POST .../unseal-requests/{id}/second-approve`, `POST .../unseal-requests/{id}/cancel`, `GET .../unseal-requests`, `GET .../unseal-requests/{id}` |
| `LegalFilingController` | 6 | `GET .../legal-filings`, `GET .../legal-filings/by-resident/{id}`, `POST .../legal-filings`, `GET .../legal-filings/{id}`, `POST .../legal-filings/{id}/evidence-package`, `GET .../legal-filings/{id}/evidence-package/download-url` |
| `DelinquencyEscalationController` | 4 | `GET .../delinquency-escalations`, `GET .../delinquency-escalations/{id}`, `POST .../delinquency-escalations/{id}/freeze`, `POST .../delinquency-escalations/{id}/resolve` |
| **合計** | **20**（うちフラットパス sign/revoke を含む。baseline §2 計上は組織スコープのみ 18 件）| |

これらのうち、設計書 §6.1 に **未掲載** の組織スコープ追加実装（scanner では「実装あり・設計なし」として §2 へ計上）は次の 5 件:

| メソッド | 実装パス | Controller | 判定 |
|---|---|---|---|
| GET | `/api/v1/organizations/{orgId}/succession/covenants/{id}` | `SuccessionCovenantController#getCovenant` (L82) | 🟡 §6.1 表に追加 |
| GET | `/api/v1/organizations/{orgId}/succession/covenants` | `SuccessionCovenantController#listOrgCovenants` (L93) | 🟡 §6.1 表に追加 |
| GET | `/api/v1/organizations/{orgId}/succession/unseal-requests` | `UnsealRequestController#listRequests` (L134) | 🟡 §6.1 表に追加 |
| GET | `/api/v1/organizations/{orgId}/succession/unseal-requests/{id}` | `UnsealRequestController#getRequest` (L154) | 🟡 §6.1 表に追加 |
| GET | `/api/v1/organizations/{orgId}/succession/legal-filings/by-resident/{id}` | `LegalFilingController#listByResident` (L73) | 🟡 §6.1 表に追加 |

これらは memory `project_f0915_s5_s6_complete.md` 内「REST エンドポイント（6 種・ADMIN 以上）」セクションに記載されているが、設計書 §6.1 表には反映されていない。S5/S6 実装時に追加された 6 種類目（`getById` 系）が §6.1 表に追記漏れしている。本第五陣で §6.1 表に追記する。

### 2.2 section 4 (V4-1 逆引き準一致) の 1 件

baseline v5 §4 で既に「準一致」として一致集計済の succession 系:

- `GET /api/v1/succession/covenants/me` ↔ 設計コアパスなし（実装はフラットパスで MEMBER 本人 API）

判定: scanner 動作正常。triage 観点でアクション不要。本来は設計書 §6.1 に `GET /api/v1/succession/covenants/me`（本人の誓約履歴）を追記すれば準一致リストからも除外され、純粋な一致集計に繰入される。本第五陣で §6.1 表に追加する。

---

## 3. 修正ファイル一覧

| ファイル | 種別 | 内容 |
|---|---|---|
| `docs/features/F09.15_resident_succession_support.md` | 修正 | §6 冒頭注記強化（第五陣 triage 完了の明示）+ §6.1 表全件リライト（実装パスへ書き換え / 🔵 v2 マーカ付与 / 未掲載 6 行追加）+ §6.2 主要エンドポイント仕様の `####` 見出し書き換え |
| `docs/internal/triage_log/succession.md` | 新規 | 本ファイル |

`docs/internal/api_drift_exclusions.yml` は **更新しない**:
- succession 系の実装は将来も維持される本物のエンドポイント
- 設計書側を実装に揃えるのが正解であり、scanner で除外する性質ではない

---

## 4. 難しい事例 / スキャナ改善余地

### 事例 A: リネーム検出（first-approve → approve / reject → cancel / evidence-rebuild → evidence-package）

実装フェーズ S2/S6 で API の用語をシンプルに整理した結果、設計書のパス末尾セグメントが実装と乖離している:

| 設計 | 実装 | 意図 |
|---|---|---|
| `unseal-requests/{id}/first-approve` | `unseal-requests/{id}/approve` | 一次承認は通常の「approve」、二次承認のみ `/second-approve` と区別する命名に整理 |
| `unseal-requests/{id}/reject` | `unseal-requests/{id}/cancel` | 「拒否（reject）」は承認失敗のニュアンス、実装は申請取消も含むため「キャンセル（cancel）」を採用 |
| `legal-filings/{id}/evidence-zip` | `legal-filings/{id}/evidence-package/download-url` | DL URL 取得操作と分離した命名へ |
| `legal-filings/{id}/evidence-rebuild` | `legal-filings/{id}/evidence-package` | 「再生成」と「新規生成」が同 endpoint で吸収できる設計に整理。冪等 POST |

scanner v5 は **末尾セグメントの意味的同等性** を判定する辞書を持たないため、これらは個別の漏れとして検出される。事例 B（dwelling-units 4-β）と同じく、設計書側を実装に揃えることが正攻法。scanner で対処しようとすると過剰一致のリスクが高い。

### 事例 B: F12.1 由来の verify API が §6.1 表未掲載

baseline §1 L1650 で `POST /api/v1/succession/covenants/{_}/verify` が F12.1 設計書 L406 起点で検出されている。

- F12.1 §5.14「`signWithInternalToken` 仕様」L406 行に「F09.15 が提供する `POST /api/v1/succession/covenants/{id}/verify` で保存中 PDF を取得 → 再ハッシュ → token 検証」と記載
- F09.15 §9.4「改ざん防止」L758 にも同じパスが言及
- ただし F09.15 §6.1 のエンドポイント表には **掲載されていない**

実装側にも該当 Controller は無いため、これは「設計あり・実装なし」の本物の漏れ。ただし F09.15 §11.1 で「v1 は内部署名のみ（RFC3161 TSA は v2 / 収益化後）」とあり、verify API も改ざん検証の高度化と一緒に v2 で実装する想定。

判定: 🔵 v2 マーカ付与 + §6.1 表に追記。

### 事例 C: 第四陣からの death-status バトン消化

dwelling-units 4-β triage_log §4 事例 D に「F09.15 succession ドメイン専用 triage（第五陣以降）で対応推奨。本ログでは記録のみ」と引継ぎ記載がある。

memory `project_f0915_s5_s6_complete.md` に「**autoMarkDeathSuspected()** (S5-C): STAGE_4 到達時に `ResidentRegistryService.markDeathSuspected()` を best-effort 呼び出し（例外キャッチ → ログ記録、エスカレーション処理は継続）」とある。すなわち **死亡疑いの自動起票は実装済**だが、それは公開 API ではなく **エスカレーションバッチ内部の Service 直接呼び出し** で実現されている。

設計書 §6.1 で示される `POST /api/v1/succession/residents/{id}/death-status` は ADMIN が手動で死亡状態を入力するための公開 API であり、これは現状未実装。memory にも該当 PR は無い。

判定: 🔵 v2 マーカ付与。「ADMIN UI から手動で死亡確認できる API」として v2 で正式実装する旨を明記する。v1 では Stage4 到達による自動 SUSPECTED 化のみで運用する。

### 事例 D: 組織スコープ getById 系の §6.1 表追記漏れ

memory にもある通り S5/S6 で実装された 6 種のエンドポイント（list / by-resident / getById / create / evidence-package POST / download-url GET）のうち、設計書 §6.1 表には **list / create / evidence-package / evidence-zip しか掲載されておらず**、getById と by-resident と download-url の参照系 3 件が漏れている。同様に unseal-requests の list / getById、covenants の組織内 list / getById も §6.1 表に未掲載。

判定: 🟡 §6.1 表に追記。これらは「実装あり・設計なし」として scanner §2 へも計上されており、表追記で両方解消する。

---

## 5. F09.15 設計書整備状況の所感

### 5.1 良かった点

- **§6 冒頭にスコープ移行注記が既に存在**（L486〜492）。第五陣着手時点で「全行を `/api/v1/organizations/{orgId}/succession/...` に書き換える PR が必要」と明示済みで意図が明確
- **memory に S0〜S6 の実装 PR 履歴が完全に揃っている**（`project_f0915_s5_s6_complete.md`）。triage 着手から実装パス特定までのタイムロスがほぼゼロ
- **S6-C で 🟢 ステータス更新済**。v1 設計と実装の差分は API パス記法のみで、機能仕様の食い違いは無い

### 5.2 課題点

- §6.1 表 19 行が全て **旧フラットパス記載のまま**。S6-C の 🟢 マーキング時に設計書のパス記法も同時に追随すべきだった
- **memory 記載の 6 種 vs 設計書 §6.1 の 4 種** に追記漏れ（getById / by-resident / download-url の 3 件 + covenants の 4 件 + unseal-requests の list/getById の 2 件）
- F12.1 連携の `POST /covenants/{id}/verify` が §9.4 にだけ書かれて §6.1 表に **未掲載**。F12.1 ↔ F09.15 のクロスリファレンスが片方向のみで「F09.15 が提供する」と F12.1 側が宣言しているのに F09.15 §6.1 表に存在しないという食い違い
- §6.1 の v1 / v2 区分が不明瞭。「ADMIN 側 17 件のうち 5 件は v2 候補で未実装」という情報がメイン表に出ていないため、新人実装者が誤って実装漏れと判断するリスクがある

### 5.3 推奨される後続作業

1. **F09.15 v2 軍議**: covenant-templates / pre-registrations CRUD 3 件 / audit-views / death-status / frozen-account-guidance / covenants/verify の正式着工計画
2. **F12.1 ↔ F09.15 クロスリファレンス整備**: covenants/verify の責務境界を改めて両設計書で同期。検証ロジックは F12.1 か F09.15 か明確化
3. **scanner v6 設計タスク**:
   - 末尾セグメントのリネーム検出辞書（first-approve ↔ approve / reject ↔ cancel / evidence-zip ↔ evidence-package など）。これは事例 A の根治
   - ただし過剰一致のリスクが高いため、設計書側書き換えが基本対応で、scanner はあくまで補助

---

## 6. PR / コミット

- ブランチ: `feature/api-drift-cleanup-succession`
- 派生元: `main` (e9ad91e15 — Stage3 4-ε safety-checks マージ済 commit)
- コミットメッセージ:
  ```
  Stage3 5-β: succession ドメイン triage 統合（漏れ0件 / 設計書更新13件 / 将来6件 / 除外0件）
  ```

2 ファイル変更（triage_log 新規 + F09.15 設計書 §6 全面リライト）で完結。
