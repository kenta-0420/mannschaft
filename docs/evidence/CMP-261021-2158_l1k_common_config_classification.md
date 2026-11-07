# CMP-261021-2158 L1-K common・config仕分けの証拠

検証日: 2026-10-21。対象は `docs/architecture/modular_monolith_boundary_debt_repayment.md` 付録Aのみ。登録PR #4032はmain反映済み、固定基準は `cc751710b98b64dc8ed72524e72062c21d47631e`。Java/API/DB/ビルド設定・凍結ストアは変更していない。台帳の完了同期は別PRで行う。

## 対象と数値

- `git ls-tree -r` でcommon 247本、config 47本、合計294本の本番Javaを列挙した。commonのpackage-info 2本には宣言型がなく、型宣言を持つソースは292本。
- 独立した字句/brace列挙ではcommon 273型、config 55型、計328型。nestedはcommon 28/config 8、計36型。匿名/コンパイラ生成型は含まない。付録の `$` はbinary name。
- commonのトップレベル245型はkernel 80・認可interface 1・業務返却164。nested込みはkernel 93・認可interface側3・業務返却177。configは所有業務へ9本、横断`:app`残置38本（nested込みは9型/46型）で、kernelへの追加は0。
- 業務直接import文はcommon 166・config 41。同一ファイルのnested行は外側と同じimport集合を記載し、集計はファイル単位で重複除外した。調査案167には `common/pdf/PdfGeneratorService.java` の `config.PdfFontConfig` 1件が含まれていた。これは業務importではなく`:app`逆依存として別途解消する。計画書§2の旧基準167は変更していない。
- 固定SHAの全ソースblob ID＋pathの改行区切りmanifestのSHA-256は `8881615627a1f0e1d692396cad0dc5a615255e697b80e5ba7df0b04a171e2bfb`。下の検査がGit objectから再計算するため、調査JSONや一時ファイルがなくても追試できる。

## red → greenと検査の実効性

付録編集前に実在の付録2行に対する不足326型・import不一致2件を確認した。検査起動時のPowerShell文字コード不一致は検査前提のエラーとして修正し、次の文書未実装のredと区別した。`0b01123c19` までの当初検証で純粋な文書未実装のredを確認した。現在も `--baseline` で固定SHAの未記入付録による欠落326・型328を再現できる。下のred/green JSONは当初検証の実測値であり、スコープ基準追加前の出力である。

```json
{
  "sha": "cc751710b98b64dc8ed72524e72062c21d47631e",
  "files": 294,
  "common_files": 247,
  "config_files": 47,
  "types": 328,
  "nested": 36,
  "rows": 2,
  "missing": 326,
  "unknown": 0,
  "duplicates": 0,
  "blank_or_unresolved": 1,
  "import_errors": 2,
  "business_import_statements": {
    "common": 166,
    "config": 41
  },
  "appendix_only": true,
  "out_of_scope_changes": [],
  "blob_manifest_sha256": "8881615627a1f0e1d692396cad0dc5a615255e697b80e5ba7df0b04a171e2bfb"
}
```

当初検証（`0b01123c19` まで）の編集後の同一検査はexit 0。§0と付録A以外の前後本文は固定SHAと一致し、固定SHAとの差分は担当2文書だけだった。最新main追従後は他戦役の変更が含まれるため、自PRの範囲をmainとの共通祖先から作業木までで検査し、差分2文書とcommon/configの固定SHAからのsource blob不変を別に確認した。

```json
{
  "sha": "cc751710b98b64dc8ed72524e72062c21d47631e",
  "files": 294,
  "common_files": 247,
  "config_files": 47,
  "types": 328,
  "nested": 36,
  "rows": 328,
  "missing": 0,
  "unknown": 0,
  "duplicates": 0,
  "blank_or_unresolved": 0,
  "import_errors": 0,
  "business_import_statements": {
    "common": 166,
    "config": 41
  },
  "appendix_only": true,
  "out_of_scope_changes": [],
  "blob_manifest_sha256": "8881615627a1f0e1d692396cad0dc5a615255e697b80e5ba7df0b04a171e2bfb"
}
```

main追従後の再検証は、証拠本文から上記2つのPythonを実抽出してgreenと負例5種を再実行した。`--baseline` は欠落326によるexit 1で、範囲外変更0のまま失敗することも確認した。検証対象headは `d713380a567f711e486a86893c32f46f94ef51d3` に本証拠の未commit修正を加えた作業木。common/configの固定SHAからのblob差分は0。

```json
{
  "sha": "cc751710b98b64dc8ed72524e72062c21d47631e",
  "scope_base": "1ae1ee1ca526c0103b66117571447967a79a6d8a",
  "changed_paths": [
    "docs/architecture/modular_monolith_boundary_debt_repayment.md",
    "docs/evidence/CMP-261021-2158_l1k_common_config_classification.md"
  ],
  "files": 294,
  "common_files": 247,
  "config_files": 47,
  "types": 328,
  "nested": 36,
  "rows": 328,
  "missing": 0,
  "unknown": 0,
  "duplicates": 0,
  "blank_or_unresolved": 0,
  "import_errors": 0,
  "business_import_statements": {
    "common": 166,
    "config": 41
  },
  "appendix_only": true,
  "out_of_scope_changes": [],
  "blob_manifest_sha256": "8881615627a1f0e1d692396cad0dc5a615255e697b80e5ba7df0b04a171e2bfb"
}
```

原本を変更せず、一時コピーに欠落/重複/空欄/未知FQCN/業務import改変をそれぞれ注入し、全てexit 1を確認した。これらをgreenとは数えていない。型列挙はソースtokenとbraceから独立生成し、付録/分類JSONを期待値に使用しない。検査は業務意味の正しさを自動証明するものではなく、次の行単位設計照合を別途行った。

## 受け入れ条件との照合

| AC | 成果/照合箇所 | 検証 |
|---|---|---|
| AC-1 | 付録A.2の全328行、package-infoとnested境界はA.1 | ソース独立列挙と集合一致。欠落/未知/重複0、負例3種はfail |
| AC-2 | 全行の区分・行き先・相手FQCN・レーン・依存解消 | 6列空欄0、ファイル単位import集合が全行一致、空欄/import改変負例はfail |
| AC-3 | A.1、S3ObjectDeleteEventListener/PdfGeneratorService/AccessGuard/Holiday各行 | kernel→返却対象の現存2辺、app逆依存、import無しの業務判断を個別照合。現依存0とは主張しない |
| AC-4 | AccessControlServiceとnested3行、RolePriority/ScopeUserRoleProjection行 | core内部実装・core所有family/gdpr SPI・GDPR_006/外部契約/Bean名/TX不変を設計照合 |
| AC-5 | NameResolver/ActivitySchedule/VisibilityMapper/StorageUsage/config各行、A.1 | 所有者・公開窓口と内部実体・record/enum/annotation/Entity/Repository制約を照合。不要な入口Apiを追加しない |
| AC-6 | 本文前後比較・許可パス検査 | §0/他節不変、コード/ストア/ビルド/台帳の変更0 |
| AC-7 | 本証拠の固定SHA/blob manifest/再現検査/将来ゲート | red/green/故障注入5種と本表。追跡ファイルはMarkdownのみ |

空/0/nullは空欄禁止とimport無し明示、境界は全件一致/nested、途中失敗は不一致時failに対応。認可は実挙動を変えず設計制約を照合した。性能は実行挙動の変更がないため新規N+1/負荷試験の対象外。Java UT/Gradle・実機E2E/アリシゼーションは文書のみのため未実施であり、合格したとは扱わない。PR/CI確認は出荷側で別途行う。

## 依存解消の根拠と将来実装ゲート

- kernel候補の明示import・完全修飾参照・同一packageの返却対象型名を照合した。現存する返却対象への参照は `common.storage.S3ObjectDeleteEventListener → common.backgroundgate.BackgroundFeatureMode/BackgroundFeaturePolicy` の2辺。旧FQCNの実体は純削除だけとし、gate付き入口をadmin内部adapterへ分離する（L1-R＋L2 admin）。AFTER_COMMIT/Async入口を二重化しない。
- `PdfGeneratorService → config.PdfFontConfig` はcommon純値への変換注入で除去する。汎用HTML/PDF生成/署名とsuccession業務テンプレートを分け、旧橋渡しをkernelに数えない。
- `AccessGuard` のSYSTEM_ADMIN/ADMIN判断、Holidayのscope別DB業務、RolePriorityのseed規則はimport無しでもkernelに残さない。core実装が使うRolePriority/ScopeUserRoleProjectionはrole所有とし、visibilityへ逆依存させない。既存role Repositoryの2signature変更はL4 role担当、L1-A/L1-Rの編集範囲を独断拡張しない。
- NameResolverのEntity引数2本（`resolveUserFullName(UserEntity)` :116、`resolveDisplayName(UserEntity, DisplayScope)` :164）は外部main/test呼出0・メソッド参照0。前者の内部呼出1は実装内で整理する。Long版はmain 9ファイル10呼出、test 10ファイル13呼出で維持対象。L1-R着手時に全参照を再確認し、caller差分0・既存テストgreenを必須とする。
- AccessControlServiceのfamily SPIはACTIVE/null/不正値falseを維持し、gdpr SPIは既存GDPR_006を返す。実装の不在/複数は起動時fail。role内部実装、既存Bean名、public nested record、外部HTTP/エラー本文、tx/readOnly振分はL1-Aの実契約試験で固定する。
- record/enumは明示変換、annotationは移行中の旧/新認識、Entity/Repositoryは二重mapping/Bean無し。内部listener/batch/aspect/controller/filterに不要な公開Apiを作らない。final公開窓口と所有内部を分け、公開値契約の純変換に別serviceを追加しない。
- L1-Gの既定番人はkernelから業務への直接import 0を凍結なしで強制する。推移依存と`:app`逆依存の残留は本仕分けの依存照合とL3のkernel単独compileで別途検証する。L2/L4で旧bridge参照を0にし、Phase 5でbridge/cycleの0と全jar重複FQCN0、L3でDI/scan/OpenAPI不変を検証する。本レーンはその実装/実行の完了を宣言しない。

## 再現手順

本証拠を追加したPRの検証済みhead（成果ツリー）を別worktreeへcheckoutし、そのルートで実行する。後続mainの任意HEADではなく、当時の成果ツリーを対象とする。ソース列挙・blob manifest・付録外本文比較は固定SHAを基準にする。変更範囲は `git merge-base HEAD origin/main` を `scope_base` とし、`git diff --name-only <scope_base>` によりcommit済み差分と未commitの作業木差分を合わせて検査する。これによりmainから取り込んだ他戦役のREADME/shift/台帳変更を自PR変更へ混入させない。検査がfailした場合は無視せず原因を確認する。common/configのsource blob不変は `git diff --exit-code cc751710b98b64dc8ed72524e72062c21d47631e HEAD -- backend/src/main/java/com/mannschaft/app/common backend/src/main/java/com/mannschaft/app/config` で別確認する。次のPythonを `%TEMP%/l1k-verify.py` にUTF-8で保存し、`python "$env:TEMP/l1k-verify.py"` でgreen、`python "$env:TEMP/l1k-verify.py" --baseline` で固定基準redを確認する。スクリプトをリポジトリへ追加しない。`git cat-file --batch` で固定SHAのblobを読み、ローカルJavaの有無やsparse checkoutには依存しない。

```python
import collections, hashlib, json, pathlib, re, subprocess, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')
SHA = 'cc751710b98b64dc8ed72524e72062c21d47631e'
DOC = 'docs/architecture/modular_monolith_boundary_debt_repayment.md'
def git(*args):
    return subprocess.check_output(['git',*args]).decode('utf-8')
def appendix(text):
    before, tail = text.split('## 付録A.',1)
    section, after = tail.split('\n---\n',1)
    return before, section, after
paths = git('ls-tree','-r','--name-only',SHA,'backend/src/main/java/com/mannschaft/app/common','backend/src/main/java/com/mannschaft/app/config').splitlines()
paths = [p for p in paths if p.endswith('.java')]
proc=subprocess.run(['git','cat-file','--batch'],input=('\n'.join(SHA+':'+p for p in paths)+'\n').encode(),stdout=subprocess.PIPE,check=True)
raw=proc.stdout; offset=0; sources={}; blobs={}
for path in paths:
    end=raw.index(b'\n',offset); header=raw[offset:end].decode().split(); size=int(header[2]); offset=end+1
    sources[path]=raw[offset:offset+size].decode('utf-8'); blobs[path]=header[0]; offset+=size+1
mask = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*'
expected={}
for path,source in sources.items():
    clean=re.sub(mask,lambda m:'\n'*m.group().count('\n')+' ',source)
    pkg=re.search(r'\bpackage\s+([\w.]+)\s*;',clean).group(1)
    tokens=list(re.finditer(r'[A-Za-z_$][\w$]*|[^\s]',clean))
    depth=0; stack=[]; pending=None
    for i,m in enumerate(tokens):
        token=m.group()
        if token in ('class','interface','enum','record') and i+1<len(tokens) and (i==0 or tokens[i-1].group()!='.'):
            name=tokens[i+1].group()
            if re.fullmatch(r'[A-Za-z_$][\w$]*',name):
                fqcn=pkg+'.'+'$'.join([x[0] for x in stack]+[name])
                assert fqcn not in expected, fqcn
                expected[fqcn]=(path,clean[:m.start()].count('\n')+1)
                pending=name
        if token=='{':
            depth+=1
            if pending: stack.append((pending,depth)); pending=None
        elif token=='}':
            if stack and stack[-1][1]==depth: stack.pop()
            depth-=1
text=(git('show',SHA+':'+DOC) if '--baseline' in sys.argv else pathlib.Path(sys.argv[sys.argv.index('--input')+1] if '--input' in sys.argv else DOC).read_text(encoding='utf-8'))
_,section,_=appendix(text)
rows=[]
for line in section.splitlines():
    if re.match(r'^\| `com\.mannschaft\.app\.',line):
        cells=[c.strip().strip('`') for c in re.split(r'(?<!\\)\|',line)[1:-1]]
        rows.append(cells)
keys=[r[0] for r in rows]; counts=collections.Counter(keys)
missing=sorted(set(expected)-set(keys)); unknown=sorted(set(keys)-set(expected))
duplicates=[k for k,v in counts.items() if v!=1]
blank=[r[0] for r in rows if len(r)!=6 or any(not v for v in r) or any(x in ' '.join(r) for x in ('TODO','未決','（L1-K で決定）'))]
imports={}; import_count=collections.Counter(); manifest=[]
for path,source in sources.items():
    project=re.findall(r'^import\s+(?:static\s+)?(com\.mannschaft\.app\.[\w.*]+)\s*;',source,re.M)
    imports[path]=sorted(x for x in project if x.split('.')[3] not in ('common','config'))
    import_count['common' if '/common/' in path else 'config']+=len(imports[path])
    manifest.append(blobs[path]+' '+path)
import_errors=[]
for row in rows:
    if row[0] not in expected or len(row)!=6: continue
    wanted=imports[expected[row[0]][0]]
    found=sorted(re.findall(r'com\.mannschaft\.app\.[\w.*]+',row[3]))
    if found!=wanted or (not wanted and 'なし' not in row[3]): import_errors.append(row[0])
base=git('show',SHA+':'+DOC)
scope_ok=appendix(base)[::2]==appendix(text)[::2]
scope_base=git('merge-base','HEAD','origin/main').strip()
changed=git('diff','--name-only',scope_base).splitlines()
code_changes=[p for p in changed if p!=DOC and not(p.startswith('docs/evidence/') and p.endswith('.md'))]
result={'sha':SHA,'scope_base':scope_base,'changed_paths':changed,'files':len(paths),'common_files':sum('/common/' in p for p in paths),'config_files':sum('/config/' in p for p in paths),'types':len(expected),'nested':sum('$' in k for k in expected),'rows':len(rows),'missing':len(missing),'unknown':len(unknown),'duplicates':len(duplicates),'blank_or_unresolved':len(blank),'import_errors':len(import_errors),'business_import_statements':dict(import_count),'appendix_only':scope_ok,'out_of_scope_changes':code_changes,'blob_manifest_sha256':hashlib.sha256(('\n'.join(manifest)+'\n').encode()).hexdigest()}
print(json.dumps(result,ensure_ascii=True,indent=2))
if '--inventory' in sys.argv:
    pathlib.Path(sys.argv[sys.argv.index('--inventory')+1]).write_text(json.dumps(expected,ensure_ascii=False,indent=2),encoding='utf-8')
if missing: print('missing sample:',missing[:8])
if unknown: print('unknown:',unknown)
if import_errors: print('import errors:',import_errors)
assert not(missing or unknown or duplicates or blank or import_errors or code_changes) and scope_ok

```

故障注入の追試も一時コピーだけを使う。

```python
import os, pathlib, subprocess
base = pathlib.Path(os.environ['TEMP'])
verify = base / 'l1k-verify.py'
source = pathlib.Path('docs/architecture/modular_monolith_boundary_debt_repayment.md').read_text(encoding='utf-8')
row = next(x for x in source.splitlines() if x.startswith('| `com.mannschaft.app.common.AccessControlService`'))
variants = {
    'missing': source.replace(row+'\n', '', 1),
    'duplicate': source.replace(row, row+'\n'+row, 1),
    'blank': source.replace(row, row.replace('認可 interface', '', 1), 1),
    'unknown': source.replace(row, row.replace('common.AccessControlService`', 'common.UnknownType`', 1), 1),
    'import': source.replace(row, row.replace('com.mannschaft.app.family.CareLinkStatus', 'com.mannschaft.app.family.UnknownImport', 1), 1),
}
for name, text in variants.items():
    candidate = base / ('l1k-negative-'+name+'.md')
    candidate.write_text(text, encoding='utf-8')
    result = subprocess.run(['python', str(verify), '--input', str(candidate)], capture_output=True)
    assert result.returncode != 0, name
    print(name, 'expected fail', result.returncode)
```
