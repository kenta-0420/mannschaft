# 履歴書 Excel テンプレート（rirekisho.xlsx）— 作成メモ

## 概要

F01.10 Phase 3 で必要な履歴書 Excel テンプレートファイル。

`ExcelGeneratorService.fillTemplateWithRows()` が読み込む xlsx ファイル。

## テンプレートファイルの配置場所

```
backend/src/main/resources/templates/excel/rirekisho.xlsx
```

## テンプレートの仕様

実際の `.xlsx` ファイルは Excel/LibreOffice で作成する必要があります。
（バイナリファイルのため自動生成不可）

### セルのプレースホルダー（ヘッダ値）

| セル（例） | プレースホルダー | 内容 |
|-----------|----------------|------|
| B2 | `${fullName}` | 氏名 |
| B3 | `${fullNameKana}` | フリガナ |
| B4 | `${birthDate}` | 生年月日 |
| B5 | `${gender}` | 性別（任意） |
| B6 | `${currentAddress}` | 現住所 |
| B7 | `${contactPhone}` | 電話番号 |
| B8 | `${contactEmail}` | メールアドレス |
| B9 | `${motivation}` | 志望動機 |
| B10 | `${selfPr}` | 自己PR |
| B11 | `${personalRequest}` | 本人希望 |

### 繰り返し行マーカー（学歴・職歴）

学歴・職歴欄の先頭行に以下のマーカーを記載する:

| A 列 | B 列 |
|------|------|
| `${rows[].yearMonth}` | `${rows[].description}` |

`fillTemplateWithRows()` がこの行を検出し、データ行数分だけ複製する。

## 代替処理

テンプレートファイルが存在しない場合、`ResumeExportService.generateRirekishoExcel()` は
プログラム生成（`generateMultiSheetExcel()`）に自動フォールバックする。

フォールバック時は「基本情報」シートと「学歴・職歴」シートの 2 シート構成で出力される。
