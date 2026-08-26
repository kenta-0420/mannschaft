# ベータ特典 利用規約条項（第27条）条文案 提案

> **ステータス**: 🟡 提案（マスター承認待ち・弁護士レビュー前）
> **作成日**: 2026-07-08 ／ **改訂**: 2026-07-29（マスターのレビュー指摘に基づく起草段階の是正。第3項の免責を第18条の上限へ委譲／第4項の最低2年保証と第14条準用の優先関係を但書で確定／§5 に L-8 を追加。詳細は §7）
> **F20.3 本番有効化ゲート**: 本書の弁護士レビューは**条件③**（①TZ是正＝#2482 済／②しきい値投入＝#2482 済／④マスター承認／⑤DB格納時刻のUTC統一＝#2486）
> **由来**: [F20.3 ベータ特典](../features/F20.3_beta_perks/README.md) 要裁可論点 **B-1**（2026-07-08 マスター御裁可＝「条文は別 PR で起草→承認後に 6 言語反映」）
> **性格**: 本書は**提案文書**であり、規約本体（`frontend/app/locales/{ja,en,zh,ko,es,de}/landing.json` の `landing.legal.terms.sections`）への反映は行わない。

---

## 0. この文書の位置づけ・承認前提

- 本書は F20.3 §5・§9（B-1）で確定した「ベータ特典専用条項の新設」要件（4 要素）に基づく**条文ドラフト**である。
- 規約は法的効力を持つ対外文書のため、**規約本体（landing.json 6 言語）への直接反映はマスター承認＋弁護士レビュー後**に別作業で行う。本 PR ではまず提案として提示する。
- **承認の前提（2 点）**:
  1. **弁護士レビュー**（消費者契約法・特定商取引法・景品表示法（特に「無償の特典」＝景品類該当性）・定型約款（民法第548条の2/548条の4）等の観点）。本書 §5 に「弁護士レビュー要確認」論点を列挙する。
  2. **存続条項（第24条）の参照更新要否の確定**（本書 §3）。挿入方式により更新が**必須になる場合**があるため、承認時に方式を確定する。
- 承認後の反映手順は本書 §6 に記す。

---

## 1. 背景（現行規約の状態・実確認 2026-07-08）

現行規約は `landing.legal.terms.sections`（全 26 条）で管理し、6 言語（ja/en/zh/ko/es/de）に格納されている。日本語版が正文（第26条・言語条項）。

| 現状 | 内容 |
|---|---|
| **第6条（料金・支払）** | 基本機能は無料。有料は本サービス内表示。支払済みは原則返金しない。 |
| **第14条（サービスの変更・中断・終了）** | 当社は内容を変更・追加・廃止でき、重大な影響時は事前告知。サービス終了時はエクスポート機会の提供に努める。 |
| **第16条（利用停止・登録抹消）** | 規約違反時、事前通知なく投稿削除・利用停止・登録抹消でき、故意・重過失を除き免責。 |
| **第17条（権利義務の譲渡禁止・事業譲渡）** | 利用者は当社の書面承諾なく契約上の地位・権利義務を第三者に譲渡・承継・担保供与できない。**＝ベータ特典（本規約に基づく権利）の譲渡禁止は現行でも一応担保されるが、特典に即した具体化はない。** |
| **第18条（保証の否認・免責）** | 現状有姿・非保証。**賠償責任は「直接かつ通常の損害」に限り、遡って12か月間に支払った対価の総額（1万円未満なら1万円）を上限**とする。ただし故意・重過失および法令上責任制限が認められない場合は上限を適用しない。**＝一般条項は既に「一部免除（上限）型」で、全部免除ではない。** |
| **第21条（規約の変更）** | 民法第548条の4を明示的に引き、「一般の利益に適合する場合、または契約の目的に反せず合理的である場合」に変更可。**効力発生時期を定め、変更内容と効力発生時期を事前周知する**手続を規定。**＝条項追加の手続的足場は既に存在する（§5 L-3）。** |
| **第24条（存続条項）** | 第8・10・18・19・22・23・25・26条＋本条が契約終了後も存続。 |
| **第26条（言語）** | 日本語を正文とし、翻訳版との齟齬時は日本語版が優先。**条番号は末尾。** |

> **⚠️ 免責文言の二層構造（2026-07-29 実確認）**: 現行規約の免責は一様ではない。**第18条は上限型**である一方、**個別措置の条項（第3条・第4条・第5条・第13条・第16条）は「故意・重過失を除き責任を負いません」＝全部免除の外形**を採る。両者の優先関係は条文上明示されていない。本条第3項は当初この個別措置型を踏襲していたが、消費者契約法8条1項1号（債務不履行による損害賠償責任の全部免除は無効）との関係で外形を避け、**第18条の上限に委ねる形へ改めた**（§5 L-2）。既存5条項をどう扱うかは規約全体の横断論点として §5 L-8 に立てる。

**→ ベータ特典専用条項は現行規約に存在しない。第17条は一般受け皿であり、特典の「無償性」「取消」「期間」までは定めていない。よって新設が必要（F20.3 §5）。**

---

## 2. 条文案（日本語＝正文）

### 挿入する条文: 第27条（ベータテスト特典）

> 文体は既存条項に準拠（敬体・「当社」「利用者」「チーム等」「本サービス」「本規約」の語・「〜するものとします」「〜できます」「〜しません」）。段落は `content` 配列の 4 要素として実装する想定。

**（第1項）無償提供の性質・提供内容/期間の総則**

> 当社は、ベータテスト（本サービスの正式な提供に先立ち、機能の検証を目的として試験的に提供する期間をいいます。以下同じ。）に参加する利用者またはチーム等に対し、当社が別途定める条件を満たす場合に、本サービスの機能の一部を無償で利用できる特典（以下「ベータ特典」といいます）を付与することがあります。あわせて、当社は、ベータテストへの参加を示す称号（以下「参加称号」といいます）を付与することがあります。ベータ特典および参加称号は、当社が任意に無償で提供するものであって、利用者が支払う対価に対する反対給付ではなく、また、本条に別段の定めがある場合を除き、将来にわたる継続的な提供を保証するものではありません。ベータ特典の対象となる機能の範囲、付与の条件および提供の期間は、当社が本サービス内に表示する方法その他の適切な方法により定めるものとします。

**（第2項）譲渡・売買・貸与の禁止（第17条の特則）**

> ベータ特典および参加称号は、これを付与された利用者本人のアカウント、または付与されたチーム等に帰属します。利用者は、第17条に定めるところによるほか、ベータ特典および参加称号を、第三者に譲渡し、貸与し、売買し、または承継させてはなりません。ベータ特典は、これを付与されたアカウントまたはチーム等においてのみ利用できるものとします。

**（第3項）違反時・不正取得時の取消**

> 利用者が前項に違反した場合、または不正な手段によりベータ特典もしくは参加称号を取得したことが判明した場合、当社は、事前の通知を要することなく、当該ベータ特典または参加称号の全部もしくは一部を取り消すことができます。本項の措置により利用者に生じた損害についての当社の賠償責任については、第18条（保証の否認・免責）の定めに従うものとします。

**（第4項）期間の定め（個人＝提供期間中／団体＝最低2年・自動更新しない）**

> 利用者個人に付与されるベータ特典は、本サービスの提供期間中に限り、無償で利用できるものとします。チーム等に付与されるベータ特典は、当社が別途定める最低保証期間（付与の時点から2年間を下回らないものとします）にわたり無償で利用できるものとし、当該期間の経過後は自動的には更新されません。当該期間の経過後の取扱いは、その都度、当社が本サービス内での表示その他の適切な方法により告知します。当社は、ベータテストの終了、本サービスの内容の変更その他の合理的な事由により、ベータ特典の内容を変更し、または提供を終了することがあります。この場合の取扱いについては、第14条（サービスの変更・中断・終了）の定めを準用します。ただし、当社が本サービス全体の提供を終了する場合および法令の定めによる場合を除き、当社は、前段の最低保証期間中は、チーム等に付与されたベータ特典について、本項に定める無償での利用を終了させ、または有償に転じさせないものとします。

### 4 要素とのトレーサビリティ（F20.3 §5・要裁可 B-1）

| 御裁可済の要素 | 対応する項 | 備考 |
|---|---|---|
| 1. 無償提供の性質（「永久」不使用） | 第1項 | 「無償で提供」「対価に対する反対給付ではない」「将来にわたる継続的な提供を保証しない」。**「永久」「無期限の保証」の語を使用しない**（F20.3 冒頭注記 2・§5(d) 表示ルールを文言で担保） |
| 2. 譲渡・売買・貸与の禁止（第17条の具体化） | 第2項 | アカウント／チーム等への帰属を明示し、第17条の一般規定を特典について具体化 |
| 3. 違反時の取消 | 第3項 | 事前通知なき取消。**賠償責任は第18条（上限型）に委ねる**（2026-07-29 改訂。当初案は既存第16条の全部免除型「故意・重過失を除き責任を負わない」を踏襲していたが、消費者契約法8条1項1号との関係で外形を避けた。§5 L-2/L-8 参照） |
| 4. 期間の定め（個人＝提供期間中／団体＝最低2年・自動更新なし） | 第4項 | 個人＝「本サービスの提供期間中」（F20.3 の `valid_until=NULL`＝「サービス提供期間中無償」に対応）。団体＝最低2年・自動更新なし・都度告知（B-2(a) 御裁可＝シスアド一括延長のみに整合）。**第14条準用と最低2年保証の優先関係を但書で確定**（2026-07-29 改訂。§5 L-5 参照） |

---

## 3. 挿入位置の提案（26と27の順序・存続条項§24の参照更新要否）

現行の末尾は「第25条（準拠法・裁判管轄）→ 第26条（言語）」であり、いずれも一般条項（雑則）に相当する。新条項を「一般条項の後ろ」に置くか「一般条項の前」に差し込むかで 2 案ある。

### 案B（推奨・最小改変）: 末尾に **第27条** として追加

- 現行の第26条（言語）の**後ろ**に新設。既存条番号を一切変更しない。
- **メリット**: 既存条番号・相互参照（特に第24条 存続条項が参照する「第26条」）を**一切壊さない**。差分が最小で、弁護士レビュー・実装ミスの risk が最も低い。改正条項を末尾に追補するのは定型約款の一般的作法。
- **デメリット**: 実体条項（特典）が言語条項（雑則）の後ろに来る形になり、体裁上はやや不自然。ただし第21条（規約の変更）・第14条が改正を許容しており、実務上許容範囲。
- **存続条項（第24条）の更新要否**: 本案では**必須ではない**（下記 §3.1 参照）。**任意で存続対象に加えるかは弁護士レビュー論点**とする。

### 案A（体裁重視・改変大）: **第26条**として差し込み、現・言語条項を第27条へ繰り下げ

- 「第25条（準拠法・裁判管轄）」の後、現・言語条項の**前**に新設し、現・言語条項を第26条→**第27条**へリネーム。
- **メリット**: 実体条項→一般条項（準拠法・言語）の順序が保たれ、体裁が自然。
- **デメリット**: **第24条（存続条項）が参照する「第26条（言語）」が第27条にずれるため、第24条の条番号参照を「第26条」→「第27条」に更新することが必須**（更新漏れは正文の内部矛盾になる）。6 言語すべてで条番号の振り直しと相互参照の追随が必要で、改変範囲・レビュー範囲が広い。

### 推奨

**案B（末尾に第27条を追加）を推奨する。** 施行済み（v1.0.0・2026-07-05 施行）の正文に対する改正であり、既存の相互参照を壊さない案が最も安全。体裁の不自然さは、第21条の改正手続で追補される条項として許容できる。本書 §2 の条文は案B（第27条）を前提に条番号を記載している。

> 案A を採る場合は、本書 §2/§4 の「第27条」を「第26条」に、条文中の相互参照（第17条・第14条）はそのまま、加えて**第24条の「第26条」を「第27条」に更新**すること。

### 3.1 存続条項（第24条）に第27条を加えるべきか（弁護士レビュー論点）

- ベータ特典条項が定める義務（譲渡禁止・取消・期間）は、**特典が存在する間＝利用契約の存続中に意味を持つ**。退会（契約終了）時には特典は取り消され失効する（F20.3 AC-19）。よって条項全体を存続させる必要性は低い。
- 免責（第3項後段の「取消による損害を故意・重過失を除き負わない」）は、既存**第18条（免責）が第24条により存続**するため別途カバーされる。
- **暫定結論**: 案Bなら第24条の更新は**必須ではない**。ただし「取消の効力・免責を契約終了後も明確に残すべきか」は**弁護士レビューで最終確認**する（§5 論点 L-4）。案Aなら**言語条項の存続を維持するための条番号更新は必須**。

---

## 4. 6 言語 対訳案（暫定訳・法務レビュー前）

> **重要**: 以下 en/zh/ko/es/de は**法務レビュー前の暫定訳**である。既存第26条（言語）のとおり**日本語版が正文であり、翻訳版との齟齬時は日本語版が優先**する（正文優先は既存§26で担保済み）。各言語の条番号・用語（当社／利用者／本サービス／本規約）は既存訳の対訳に合わせた。
> 条番号は案B（第27条）前提。案A採用時は各言語で 26 条へ読み替え、各言語の言語条項を 27 条へ繰り下げること。

### 4.1 English（第27条）

**Article 27 (Beta Test Benefits)**

1. The Company may, where the conditions separately established by the Company are met, grant to Users or Teams, etc. participating in the beta test (meaning the period during which the Service is provided on a trial basis for the purpose of verifying its features prior to its official release; the same applies hereinafter) a benefit allowing the free use of certain features of the Service (the "Beta Benefit"). The Company may also grant a title indicating participation in the beta test (the "Participation Title"). The Beta Benefit and the Participation Title are provided by the Company free of charge at its discretion; they do not constitute consideration in return for any fees paid by the User and, except as otherwise provided in this Article, do not guarantee continued provision into the future. The scope of the features covered by the Beta Benefit, the conditions for its grant, and the period of its provision shall be determined by the Company by displaying them within the Service or by other appropriate means.
2. The Beta Benefit and the Participation Title belong to the account of the User to whom, or the Team, etc. to which, they were granted. In addition to Article 17, Users may not assign, lend, sell, or transfer the Beta Benefit or the Participation Title to any third party. The Beta Benefit may be used only within the account or the Team, etc. to which it was granted.
3. If a User breaches the preceding paragraph, or if it comes to light that a User has obtained the Beta Benefit or the Participation Title by improper means, the Company may revoke all or part of the relevant Beta Benefit or Participation Title without prior notice. The Company's liability for damages caused to Users by measures under this paragraph shall be governed by Article 18 (No Warranty; Limitation of Liability).
4. A Beta Benefit granted to an individual User may be used free of charge only during the period in which the Service is provided. A Beta Benefit granted to a Team, etc. may be used free of charge for a minimum guaranteed period separately established by the Company (which shall not be shorter than two years from the time of grant) and will not be renewed automatically after that period. The treatment after the expiry of that period shall be announced by the Company on each occasion by displaying it within the Service or by other appropriate means. The Company may change the content of, or terminate the provision of, the Beta Benefit due to the end of the beta test, changes to the content of the Service, or other reasonable grounds; Article 14 (Change, Suspension, and Termination of the Service) shall apply mutatis mutandis to the treatment in such cases. Provided, however, that except where the Company terminates the Service as a whole or where otherwise required by law, the Company shall not, during the minimum guaranteed period referred to above, terminate the free-of-charge use of a Beta Benefit granted to a Team, etc. under this paragraph, or convert such use into a paid one.

### 4.2 简体中文（第二十七条）

**第二十七条（Beta测试特典）**

1. 对于参加Beta测试（指本服务正式提供之前，为验证功能而试行提供的期间，以下同）的用户或团队等，在满足本公司另行规定的条件时，本公司可以授予其无偿使用本服务部分功能的特典（以下称"Beta特典"）。此外，本公司可以授予表明参加Beta测试的称号（以下称"参加称号"）。Beta特典及参加称号系本公司自主无偿提供，并非对用户所支付对价的反对给付；除本条另有规定外，亦不保证未来的持续提供。Beta特典所涵盖功能的范围、授予条件及提供期间，由本公司以在本服务内表示的方法或其他适当方法确定。
2. Beta特典及参加称号归属于被授予的用户本人账户或被授予的团队等。除第十七条的规定外，用户不得将Beta特典及参加称号转让、出借、买卖给第三方或使其承继。Beta特典仅可在被授予的账户或团队等内使用。
3. 用户违反前款，或查明用户以不正当手段取得Beta特典或参加称号时，本公司可以不经事先通知，取消该Beta特典或参加称号的全部或一部分。对因本款措施给用户造成的损害，本公司的赔偿责任依照第十八条（不保证与免责）的规定。
4. 授予用户个人的Beta特典，仅在本服务的提供期间内可无偿使用。授予团队等的Beta特典，在本公司另行规定的最低保证期间（自授予时起不少于两年）内可无偿使用，该期间经过后不自动更新。该期间经过后的处理，由本公司每次以在本服务内表示或其他适当方法告知。本公司可因Beta测试结束、本服务内容变更或其他合理事由，变更Beta特典的内容或终止其提供；此种情形的处理，准用第十四条（服务的变更、中断与终止）的规定。但是，除本公司终止本服务全体的提供的情形及法令另有规定的情形外，在上述最低保证期间内，本公司不得终止授予团队等的Beta特典依本款所定的无偿使用，亦不得将其转为有偿。

### 4.3 한국어（제27조）

**제27조(베타 테스트 특전)**

1. 회사는 베타 테스트(본 서비스의 정식 제공에 앞서 기능 검증을 목적으로 시험적으로 제공하는 기간을 말하며, 이하 같음)에 참가하는 이용자 또는 팀 등에 대하여, 회사가 별도로 정하는 조건을 충족하는 경우, 본 서비스 기능의 일부를 무상으로 이용할 수 있는 특전(이하 "베타 특전")을 부여할 수 있습니다. 아울러 회사는 베타 테스트 참가를 나타내는 칭호(이하 "참가 칭호")를 부여할 수 있습니다. 베타 특전 및 참가 칭호는 회사가 임의로 무상으로 제공하는 것으로서, 이용자가 지급하는 대가에 대한 반대급부가 아니며, 본 조에 별도의 정함이 있는 경우를 제외하고 장래에 걸친 계속적인 제공을 보장하지 않습니다. 베타 특전의 대상이 되는 기능의 범위, 부여 조건 및 제공 기간은 회사가 본 서비스 내에 표시하는 방법 기타 적절한 방법으로 정합니다.
2. 베타 특전 및 참가 칭호는 이를 부여받은 이용자 본인의 계정 또는 부여받은 팀 등에 귀속됩니다. 이용자는 제17조에서 정하는 바에 따르는 것 외에, 베타 특전 및 참가 칭호를 제3자에게 양도, 대여, 매매하거나 승계시켜서는 안 됩니다. 베타 특전은 이를 부여받은 계정 또는 팀 등에서만 이용할 수 있습니다.
3. 이용자가 전항을 위반한 경우, 또는 부정한 수단으로 베타 특전이나 참가 칭호를 취득한 것이 판명된 경우, 회사는 사전 통지 없이 해당 베타 특전 또는 참가 칭호의 전부 또는 일부를 취소할 수 있습니다. 본 항의 조치로 이용자에게 발생한 손해에 대한 회사의 배상책임에 대해서는 제18조(보증의 부인·면책)의 정함에 따릅니다.
4. 이용자 개인에게 부여되는 베타 특전은 본 서비스의 제공 기간 중에 한하여 무상으로 이용할 수 있습니다. 팀 등에 부여되는 베타 특전은 회사가 별도로 정하는 최저 보장 기간(부여 시점부터 2년을 밑돌지 않습니다) 동안 무상으로 이용할 수 있으며, 해당 기간 경과 후에는 자동으로 갱신되지 않습니다. 해당 기간 경과 후의 취급은 그때마다 회사가 본 서비스 내 표시 기타 적절한 방법으로 고지합니다. 회사는 베타 테스트의 종료, 본 서비스 내용의 변경 기타 합리적인 사유로 베타 특전의 내용을 변경하거나 제공을 종료할 수 있으며, 이 경우의 취급에 대해서는 제14조(서비스의 변경·중단·종료)의 규정을 준용합니다. 다만, 회사가 본 서비스 전체의 제공을 종료하는 경우 및 법령에 정함이 있는 경우를 제외하고, 회사는 상기 최저 보장 기간 중에는 팀 등에 부여된 베타 특전에 대하여 본 항에 정하는 무상 이용을 종료시키거나 유상으로 전환하지 않습니다.

### 4.4 Español（Artículo 27）

**Artículo 27 (Beneficios de la prueba beta)**

1. La Compañía podrá, cuando se cumplan las condiciones que la Compañía establezca por separado, otorgar a los Usuarios o Equipos, etc. que participen en la prueba beta (entendida como el periodo durante el cual el Servicio se presta con carácter de prueba, con el fin de verificar sus funciones, antes de su lanzamiento oficial; en lo sucesivo, lo mismo) un beneficio que permite el uso gratuito de determinadas funciones del Servicio (el "Beneficio Beta"). Asimismo, la Compañía podrá otorgar un título que indique la participación en la prueba beta (el "Título de Participación"). El Beneficio Beta y el Título de Participación son proporcionados gratuitamente por la Compañía a su discreción; no constituyen contraprestación por importe alguno pagado por el Usuario y, salvo que este Artículo disponga otra cosa, no garantizan una prestación continuada en el futuro. El alcance de las funciones cubiertas por el Beneficio Beta, las condiciones de su otorgamiento y el periodo de su prestación serán determinados por la Compañía mediante su visualización dentro del Servicio u otros medios apropiados.
2. El Beneficio Beta y el Título de Participación pertenecen a la cuenta del Usuario al que, o al Equipo, etc. al que, se otorgaron. Además de lo dispuesto en el Artículo 17, el Usuario no podrá ceder, prestar, vender ni transmitir a terceros el Beneficio Beta ni el Título de Participación. El Beneficio Beta solo podrá utilizarse dentro de la cuenta o del Equipo, etc. al que se otorgó.
3. Si el Usuario incumple el párrafo anterior, o si se descubre que ha obtenido el Beneficio Beta o el Título de Participación por medios indebidos, la Compañía podrá revocar la totalidad o parte del Beneficio Beta o del Título de Participación correspondiente sin previo aviso. La responsabilidad de la Compañía por los daños causados al Usuario por las medidas adoptadas conforme a este párrafo se regirá por el Artículo 18 (Exclusión de garantías; limitación de responsabilidad).
4. El Beneficio Beta otorgado a un Usuario individual podrá utilizarse de forma gratuita únicamente durante el periodo en que se preste el Servicio. El Beneficio Beta otorgado a un Equipo, etc. podrá utilizarse de forma gratuita durante un periodo mínimo garantizado que la Compañía establezca por separado (que no será inferior a dos años desde el momento del otorgamiento) y no se renovará automáticamente tras dicho periodo. El tratamiento tras la expiración de dicho periodo será anunciado por la Compañía en cada ocasión mediante su visualización dentro del Servicio u otros medios apropiados. La Compañía podrá modificar el contenido del Beneficio Beta o poner fin a su prestación por la finalización de la prueba beta, cambios en el contenido del Servicio u otros motivos razonables; al tratamiento en tales casos se aplicará por analogía el Artículo 14 (Modificación, suspensión y terminación del Servicio). No obstante, salvo que la Compañía ponga fin a la prestación del Servicio en su conjunto o cuando así lo exija la ley, la Compañía no pondrá fin, durante el periodo mínimo garantizado antes indicado, al uso gratuito conforme a este párrafo del Beneficio Beta otorgado a un Equipo, etc., ni lo convertirá en un uso de pago.

### 4.5 Deutsch（Artikel 27）

**Artikel 27 (Beta-Test-Vorteile)**

1. Das Unternehmen kann Nutzern oder Teams usw., die am Beta-Test teilnehmen (gemeint ist der Zeitraum, in dem der Dienst vor seiner offiziellen Bereitstellung zu Testzwecken zur Überprüfung der Funktionen bereitgestellt wird; nachfolgend ebenso), bei Erfüllung der vom Unternehmen gesondert festgelegten Bedingungen einen Vorteil gewähren, der die kostenlose Nutzung bestimmter Funktionen des Dienstes ermöglicht (der „Beta-Vorteil"). Ferner kann das Unternehmen einen Titel gewähren, der die Teilnahme am Beta-Test kennzeichnet (der „Teilnahmetitel"). Der Beta-Vorteil und der Teilnahmetitel werden vom Unternehmen nach seinem Ermessen unentgeltlich bereitgestellt; sie stellen keine Gegenleistung für vom Nutzer gezahlte Entgelte dar und gewährleisten, soweit in diesem Artikel nicht anders bestimmt, keine fortgesetzte künftige Bereitstellung. Den Umfang der vom Beta-Vorteil erfassten Funktionen, die Bedingungen seiner Gewährung und den Zeitraum seiner Bereitstellung legt das Unternehmen durch Anzeige innerhalb des Dienstes oder auf andere geeignete Weise fest.
2. Der Beta-Vorteil und der Teilnahmetitel stehen dem Konto des Nutzers bzw. dem Team usw. zu, dem sie gewährt wurden. Über Artikel 17 hinaus dürfen Nutzer den Beta-Vorteil und den Teilnahmetitel nicht an Dritte abtreten, verleihen, verkaufen oder übertragen. Der Beta-Vorteil darf nur innerhalb des Kontos oder des Teams usw. genutzt werden, dem er gewährt wurde.
3. Verstößt ein Nutzer gegen den vorstehenden Absatz oder stellt sich heraus, dass ein Nutzer den Beta-Vorteil oder den Teilnahmetitel mit unlauteren Mitteln erlangt hat, kann das Unternehmen den betreffenden Beta-Vorteil oder Teilnahmetitel ganz oder teilweise ohne vorherige Ankündigung widerrufen. Für die Haftung des Unternehmens für Schäden, die dem Nutzer durch Maßnahmen nach diesem Absatz entstehen, gilt Artikel 18 (Gewährleistungsausschluss; Haftungsbeschränkung).
4. Ein einem einzelnen Nutzer gewährter Beta-Vorteil darf nur während des Zeitraums der Bereitstellung des Dienstes kostenlos genutzt werden. Ein einem Team usw. gewährter Beta-Vorteil darf für einen vom Unternehmen gesondert festgelegten Mindestgarantiezeitraum (der zwei Jahre ab dem Zeitpunkt der Gewährung nicht unterschreitet) kostenlos genutzt werden und wird nach diesem Zeitraum nicht automatisch verlängert. Die Behandlung nach Ablauf dieses Zeitraums gibt das Unternehmen jeweils durch Anzeige innerhalb des Dienstes oder auf andere geeignete Weise bekannt. Das Unternehmen kann den Inhalt des Beta-Vorteils ändern oder dessen Bereitstellung beenden, etwa wegen der Beendigung des Beta-Tests, Änderungen des Inhalts des Dienstes oder aus anderen angemessenen Gründen; auf die Behandlung in solchen Fällen findet Artikel 14 (Änderung, Unterbrechung und Beendigung des Dienstes) entsprechende Anwendung. Ausgenommen sind jedoch der Fall, dass das Unternehmen die Bereitstellung des Dienstes insgesamt beendet, sowie gesetzlich vorgeschriebene Fälle; im Übrigen wird das Unternehmen während des vorstehend genannten Mindestgarantiezeitraums die nach diesem Absatz unentgeltliche Nutzung eines einem Team usw. gewährten Beta-Vorteils weder beenden noch in eine entgeltliche Nutzung umwandeln.

---

## 5. 弁護士レビューで要確認の論点（握りつぶさず明示）

| # | 論点 | 懸念・検討の観点 |
|---|---|---|
| **L-1** | **景品表示法（景品類）該当性** | 「無償の特典」が景品表示法上の「景品類」に該当し、総付景品・懸賞の価額規制の対象となるか。特典＝サービス機能の無償提供が「取引に付随する経済上の利益」に当たるかを確認。該当時は付与条件・価額の運用に制約が生じうる。 |
| **L-2** | **消費者契約法 8条・10条（不当条項）** | 第3項「事前通知なき取消」が消費者契約法10条（消費者の利益を一方的に害する条項）に抵触しないか。**免責については 2026-07-29 に条文を改訂済み**＝当初案の「故意・重過失を除き責任を負いません」（全部免除の外形。消費者契約法8条1項1号は債務不履行による賠償責任の全部免除を無効とし、軽過失でも全部免除は無効と解するのが一般的理解）を廃し、**第18条（上限型）に委ねる**形にした。残る確認点は ①第18条の上限（直接かつ通常の損害／12か月対価総額または1万円）が無償特典の取消という場面に妥当か ②そもそも無償特典の取消が「債務不履行に基づく損害賠償」の場面に当たるか（入口論点）。 |
| **L-3** | **定型約款の変更（民法548条の4）** | 本条の新設＝規約変更が、既存利用者に対し民法548条の4の変更要件を満たすか。**手続的足場は既に存在する**（第21条が548条の4を明示的に引き、効力発生時期を定めた事前周知を規定。§1 参照）。残る論点は**どちらの要件に依拠するか**＝特典付与自体は利用者に有利だが、譲渡禁止の具体化・取消権の新設という**不利益側面**も含むため「一般の利益に適合」で押し切るのは微妙で、**「契約目的に反せず合理的」＋第21条の手続履践**で整理するのが現実的か。あわせて**既存ユーザーへの周知方法・効力発生時期**を確定する必要。 |
| **L-4** | **存続条項（第24条）への追加要否** | §3.1 のとおり、取消の効力・免責を契約終了後に残すべきか。案B採用時に第24条へ第27条を加えるか否かの最終判断。 |
| **L-5** | **団体特典「最低2年」の拘束力** | **2026-07-29 に条文で解消済み**＝当初案は同一項の中で「2年間を下回らない」と保証しつつ末尾で第14条（内容の変更・追加・**廃止**の広い裁量）を無限定に準用しており、**2年保証が第14条経由で骨抜きにできる読み方が成立**していた（保証としての意味が不明確＝景品表示法の有利誤認の火種にもなり得る）。**但書で優先関係を確定**し、本サービス全体の終了と法令による場合を除き、最低保証期間中は無償利用の終了・有償転化をしない旨を明記した。残る確認点は、この但書の書きぶりで保証として十分か、および「法令の定めによる場合」の範囲。 |
| **L-6** | **「反対給付ではない」旨と有償プランの関係** | 第1項「対価に対する反対給付ではない」が、将来の有償プラン（第6条）加入者への特典付与時に、対価性を否定して返金・二重取りの論点を生まないか。 |
| **L-7** | **翻訳版の正確性** | §4 の 5 言語は暫定訳。反映前に各言語のリーガル翻訳レビューを推奨（正文は日本語＝第26条で担保済みだが、誤訳による誤認防止のため）。 |
| **L-8** | **規約全体の横断論点: 個別措置条項の全部免除と第18条の上限型の関係**（本条の範囲外・2026-07-29 追加） | 現行規約の免責は**二層構造**になっている。**第18条は上限型**（直接かつ通常の損害／12か月対価総額または1万円を上限・故意重過失は除外）である一方、**個別措置の条項＝第3条（アカウント管理）・第4条（未成年者の利用）・第5条（チーム等と管理者の責務）・第13条（業務委託・広告掲載）・第16条（利用停止・登録抹消）は「故意・重過失を除き責任を負いません」＝全部免除の外形**を採り、両者の優先関係が条文上明示されていない。第18条の上限が個別条項の全部免除を救う（＝全体として全部免除ではないと読める）のか、それとも個別条項が独立に消費者契約法8条1項1号の無効判断を受けるのかは解釈に委ねられている。**本条第3項は第18条に委ねる形へ改めたため、この外形を新たに増やしていない**。既存5条項を上限型へ統一すべきかは規約全体の改訂論点であり、本条の承認とは切り離して弁護士に諮ること。 |

> 上記のうち **L-1・L-3 は「そもそも本条を設けてよいか／どの手続で設けるか」に関わる根本論点**であり、承認前に必ず弁護士確認を得ること。
>
> **L-2・L-5 は 2026-07-29 の条文改訂で外形上の危険を除いた**（マスターのレビュー指摘に基づく起草段階での是正）。ただし**最終的な適法性判断は必ず弁護士確認を得ること**。改訂で消えたのは「明らかに危ない書きぶり」であって、適法性の担保ではない。

---

## 6. 承認後の反映手順（この PR ではやらない）

マスター承認＋弁護士レビュー完了後、別 PR で以下を実施する。

1. **挿入方式の確定**（§3 案B/案A）。
2. `frontend/app/locales/{ja,en,zh,ko,es,de}/landing.json` の `landing.legal.terms.sections` に、確定条番号で新条項（`{ "title": ..., "content": [第1項, 第2項, 第3項, 第4項] }`）を追加。6 言語すべてに追加（i18n ルール）。
3. **案A採用時のみ**: 各言語の言語条項を第27条へ繰り下げ、**第24条（存続条項）の「第26条」参照を「第27条」に更新**。案Bでも第24条へ第27条を加える判断（L-4）をした場合は第24条の存続対象列挙を更新。
4. `version_notice`・`revised_date`・`revised_label` を改定（例: バージョンを 1.1.0 に、`revised_date` を反映日に）。
5. 表示は既存レンダラ（`frontend/app/components/legal/TermsContent.vue` が `tm('landing.legal.terms.sections')` を `rt()` 解決）でそのまま描画されるため、コンポーネント変更は不要。
6. F20.3 README §5・§9（B-1）の参照を「反映済み」に更新。

---

## 7. 改訂履歴

### 2026-07-29 — 起草段階の是正（マスターのレビュー指摘・弁護士レビュー前）

マスターによる条文レビューで「このまま反映すると危ない」と指摘された 2 点を、**弁護士レビュー待ちにせず条文案の段階で是正**した。いずれも法的判断ではなく、**明らかに危ない書きぶりを除く**ための起草上の修正である。

| # | 指摘 | 是正内容 |
|---|---|---|
| 1 | **第3項の免責は軽過失による賠償責任の全部免除**にあたる書き方（消費者契約法8条1項1号は事業者の債務不履行による賠償責任の全部免除を無効とし、軽過失でも全部免除は無効と解するのが一般的理解） | 「故意または重大な過失がある場合を除き、責任を負いません」を削除し、**「第18条（保証の否認・免責）の定めに従うものとします」へ委譲**。第18条は既に上限型のため、新条項が独自の免責を持たず、規約内で上限額が二重管理にならない（マスター裁可＝「第18条の上限に委ねる」） |
| 2 | **第4項の「最低2年」保証が同一項末尾の第14条準用で骨抜きになる**（保証としての意味が不明確・景品表示法の有利誤認の火種） | 末尾に**但書を追加**し、本サービス全体の終了と法令による場合を除き、最低保証期間中は無償利用の終了・有償転化をしない旨を明記して**優先関係を条文上確定** |

あわせて実施したこと:

- **§1 に第18条・第21条の実態を追記**。現行規約の免責が**二層構造**（第18条＝上限型／個別措置条項＝全部免除の外形）であることを明記した。「既存の第16条・第18条が同じ文言」という当初の前提は不正確で、**第18条は既に上限型**である（12か月対価総額または1万円を上限。無料利用者でもゼロにならない下限床つき）
- **§5 に L-8 を追加**（第3条・第4条・第5条・第13条・第16条の全部免除と第18条の上限型の優先関係。本条の範囲外の横断論点）
- **§4 の 5 言語対訳を第3項・第4項について追随**（各言語の第18条の正式訳名は現行 landing.json の実体から採った）
- L-2・L-5 を「条文で解消済み・残る確認点」に書き換え

---

## 8. 参照

- F20.3 設計書: [`docs/features/F20.3_beta_perks/README.md`](../features/F20.3_beta_perks/README.md) §5（規約改訂要件）・§9（要裁可 B-1）
- 現行規約実体: `frontend/app/locales/ja/landing.json` › `landing.legal.terms.sections`（6 言語）
- レンダラ: `frontend/app/components/legal/TermsContent.vue` ／ 表示ページ: `frontend/app/pages/terms.vue`
