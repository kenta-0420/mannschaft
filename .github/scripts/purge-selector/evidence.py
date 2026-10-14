"""各jobの固定source、exact selector、実数、actualexitを有限人工JUnitへ結合する。"""
import argparse, hashlib, json, re, subprocess
from pathlib import Path
from xml.parsers import expat

HEAD='8eeb33e37e22eec11d5f75b9f722b7b6f2961225'
TREE='bbd76d90e956413191bbef23e09e49998df07278'
CLASS='com.mannschaft.app.ranch.RanchPurgeServiceIT'
METHODS={
    'withdrawal-renewal':'退会取消と再申請は元参加状態を保ち旧配送で復帰しない',
    'populated-restoration':'名前XP残高と所有配置済み置物は退会申請中操作を拒否して取消後に同値復帰する',
}
CASES=['[1] original=ACTIVE','[2] original=PAUSED']

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def dump(path, value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def require(condition, code):
    if not condition: raise ValueError(code)

def git(repo, query):
    return subprocess.check_output(['git','-C',str(repo),'rev-parse',query],text=True).strip()

def check_source(repo, key, selector):
    require(key in METHODS and selector==CLASS+'.'+METHODS[key],'SELECTOR_BINDING')
    require(git(repo,'HEAD')==HEAD and git(repo,'HEAD^{tree}')==TREE,'CHECKOUT_IDENTITY')
    binding=json.loads(Path(__file__).with_name('source-binding.json').read_text(encoding='utf-8'))
    require(binding.get('schema')=='purge-exact-selector-source-binding-v2'
            and binding.get('sourceHead')==HEAD and binding.get('sourceTree')==TREE
            and binding.get('checkoutPolicy')=='EXACT_CANONICAL_BLOB_NO_DECLARED_EOL_CONVERSION','SOURCE_BINDING_POLICY')
    # 固定treeの属性blobと5対象の実効属性を別検査し、info/global変換も許可しない。
    tracked=subprocess.check_output(['git','-C',str(repo),'ls-tree','-r','--name-only','-z',HEAD]).split(b'\0')
    attribute_paths=[p.decode('utf-8') for p in tracked if p and p.decode('utf-8').split('/')[-1]=='.gitattributes']
    require(attribute_paths==binding['trackedGitAttributesFiles'],'GITATTRIBUTES_INVENTORY')
    require(set(binding['trackedGitAttributesBlobSha256'])==set(attribute_paths),'GITATTRIBUTES_PIN_SET')
    for rel,digest in binding['trackedGitAttributesBlobSha256'].items():
        canonical=subprocess.check_output(['git','-C',str(repo),'show',HEAD+':'+rel])
        path=repo/rel
        require(hashlib.sha256(canonical).hexdigest()==digest,'GITATTRIBUTES_BLOB_SHA')
        require(path.is_file() and not path.is_symlink() and path.read_bytes()==canonical,'GITATTRIBUTES_PHYSICAL_BYTES')
    attr_keys=['text','eol','filter','working-tree-encoding','ident','crlf']
    raw=subprocess.check_output(['git','-C',str(repo),'check-attr','--source='+HEAD,'-z',*attr_keys,'--',*binding['sourceFiles']]).split(b'\0')
    require(raw[-1]==b'' and (len(raw)-1)%3==0,'ATTRIBUTES_SHAPE')
    attributes={path:{} for path in binding['sourceFiles']}
    for i in range(0,len(raw)-1,3):
        path,key,value=[part.decode('utf-8') for part in raw[i:i+3]]
        require(path in attributes and key in attr_keys,'ATTRIBUTES_PATH_OR_KEY')
        attributes[path][key]=value
    require(attributes==binding['gitAttributes'] and all(values==dict.fromkeys(attr_keys,'unspecified') for values in attributes.values()),'UNDECLARED_CHECKOUT_TRANSFORM')
    observations={}
    for rel,digest in binding['sourceFiles'].items():
        path=repo/rel
        require(path.is_file() and not path.is_symlink(),'SOURCE_FILE_SHAPE')
        canonical=subprocess.check_output(['git','-C',str(repo),'show',HEAD+':'+rel])
        require(hashlib.sha256(canonical).hexdigest()==digest and b'\r' not in canonical,'CANONICAL_GIT_BLOB_SHA_OR_EOL')
        physical=path.read_bytes()
        # 属性宣言が無い固定sourceでは変換を一切認めず、物理checkoutはGit blobとbyte一致必須。
        require(physical==canonical,'PHYSICAL_CHECKOUT_NOT_CANONICAL')
        observations[rel]={'physicalSha256':hashlib.sha256(physical).hexdigest(),
                           'canonicalBlobSha256':digest,'physicalCrLfCount':physical.count(b'\r\n'),
                           'physicalEqualsCanonical':True,'normalizationUsed':False}
    source=repo/'backend/src/test/java/com/mannschaft/app/ranch/RanchPurgeServiceIT.java'
    text=source.read_text(encoding='utf-8-sig')
    predicate=(r'@ParameterizedTest\s+@EnumSource\(value = ParticipationStatus.class, names = \{"ACTIVE", "PAUSED"\}\)\s+'
               r'void '+re.escape(METHODS[key])+r'\(\s*ParticipationStatus original\) throws Exception')
    require(len(re.findall(predicate,text))==1,'METHOD_ENUM_SOURCE_PREDICATE')
    return binding,observations

def parse_xml(path):
    require(path.stat().st_size<=16*1024*1024 and not path.is_symlink(),'XML_SIZE_OR_SYMLINK')
    parser=expat.ParserCreate(); parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
    rows=[]; suites=[]; current=None; depth=0
    def start(name, attrs):
        nonlocal current, depth
        tag=name.rsplit(':',1)[-1]
        if depth==0: require(tag=='testsuite','XML_ROOT')
        if tag=='testsuite':
            require(depth==0,'NESTED_SUITE_UNEXPECTED')
            counters={}
            for key in ('tests','failures','errors','skipped'):
                value=attrs.get(key,''); require(re.fullmatch(r'\d{1,6}',value) is not None,'XML_COUNTERS')
                counters[key]=int(value)
            suites.append(counters)
        if tag=='testcase':
            require(current is None and len(rows)<4,'TESTCASE_LIMIT')
            current={'className':attrs.get('classname'),'name':attrs.get('name'),'failure':False,'error':False,'skipped':False}
        elif current is not None and tag in ('failure','error','skipped'): current[tag]=True
        depth+=1
    def end(name):
        nonlocal current,depth
        if name.rsplit(':',1)[-1]=='testcase':
            require(current is not None,'TESTCASE_STATE'); rows.append(current); current=None
        depth-=1
    def doctype(*_args): raise ValueError('DTD_FORBIDDEN')
    parser.StartElementHandler=start; parser.EndElementHandler=end
    parser.CharacterDataHandler=lambda _data:None
    parser.StartDoctypeDeclHandler=doctype; parser.ExternalEntityRefHandler=lambda *_args:0
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(65536),b''): parser.Parse(block,False)
    parser.Parse(b'',True)
    require(current is None and len(suites)==1,'XML_COMPLETE')
    return rows,suites[0]

def main():
    p=argparse.ArgumentParser()
    p.add_argument('mode',choices=['prepare','verify'])
    p.add_argument('--repo-root',required=True,type=Path); p.add_argument('--key',required=True)
    p.add_argument('--selector',required=True); p.add_argument('--run-id',required=True,type=int)
    p.add_argument('--run-attempt',required=True,type=int); p.add_argument('--workflow-sha',required=True)
    p.add_argument('--workflow-ref',required=True); p.add_argument('--event-name',required=True)
    p.add_argument('--output-dir',required=True,type=Path)
    args=p.parse_args(); out=args.output_dir; out.mkdir(parents=True,exist_ok=True)
    try:
        binding,physical_observations=check_source(args.repo_root.resolve(),args.key,args.selector)
        require(args.run_id>0 and args.run_attempt>0 and re.fullmatch(r'[0-9a-f]{40}',args.workflow_sha),'RUN_IDENTITY')
        require(args.event_name=='push' and args.workflow_ref=='refs/heads/feature/ranch-purge-selector-8eeb-20261014-01','WORKFLOW_EVENT_BINDING')
        header={'schema':'purge-exact-selector-header-v1','sourceHead':HEAD,'sourceTree':TREE,
                'sourceFiles':binding['sourceFiles'],'selector':args.selector,'key':args.key,
                'sourcePinKind':'GIT_CANONICAL_BLOB_SHA256','checkoutPolicy':binding['checkoutPolicy'],
                'gitAttributes':binding['gitAttributes'],'checkoutPhysicalObservations':physical_observations,
                'trackedGitAttributesBlobSha256':binding['trackedGitAttributesBlobSha256'],
                'enumValues':['ACTIVE','PAUSED'],'expectedCases':CASES,'expectedTestCount':2,
                'runId':args.run_id,'runAttempt':args.run_attempt,'workflowSha':args.workflow_sha,
                'workflowRef':args.workflow_ref,'eventName':args.event_name,
                'methodSelectorScopeOnly':True,'wholeAcceptanceGreen':False}
        if args.mode=='prepare': dump(out/'source-selector-header.json',header); return 0
        require(json.loads((out/'source-selector-header.json').read_text(encoding='utf-8'))==header,'HEADER_MISMATCH')
        exit_text=(out/'run-tests-exit.txt').read_text(encoding='utf-8').strip()
        require(re.fullmatch(r'\d{1,3}',exit_text),'RUN_TESTS_EXIT_MISSING')
        actual_exit=int(exit_text)
        actual=json.loads((out/'actual-test-counts.json').read_text(encoding='utf-8'))
        filters=json.loads((out/'effective-filter.json').read_text(encoding='utf-8'))
        require(filters=={'selector':args.selector,'includePatterns':[args.selector],
                         'failOnNoMatchingTests':True,'testCacheDisabled':True,'testUpToDateDisabled':True},'EFFECTIVE_FILTER')
        require(actual['selector']==args.selector and actual['testCount']==2 and actual['successfulTestCount']==2
                and actual['failedTestCount']==0 and actual['skippedTestCount']==0,'ACTUAL_TEST_COUNT')
        require(len(actual['descriptors'])==2 and all(row['className']==CLASS for row in actual['descriptors']),'OTHER_CLASS_OR_CASE')
        xmls=list((args.repo_root/'backend/build/test-results/test').glob('TEST-*.xml'))
        require(len(xmls)==1 and xmls[0].name=='TEST-'+CLASS+'.xml','OTHER_TEST_XML_OR_MISSING')
        rows,counters=parse_xml(xmls[0])
        require(counters=={'tests':2,'failures':0,'errors':0,'skipped':0},'XML_CASE_COUNTERS')
        require(len(rows)==2 and sorted(row['name'] for row in rows)==sorted(CASES),'CASE_SET_NOT_EXACT')
        require(all(row['className']==CLASS and not (row['failure'] or row['error'] or row['skipped']) for row in rows),'CASE_OUTCOME_OR_CLASS')
        require(actual_exit==0,'RUN_TESTS_FAILED')
        dump(out/'selector-result.json',{**header,'schema':'purge-exact-selector-result-v1',
             'runTestsActualExit':actual_exit,'actualTestCounts':actual,'effectiveFilter':filters,
             'xmlSha256':sha(xmls[0]),'xmlFileName':xmls[0].name,'cases':rows,
             'selectorEvidenceComplete':True,'wholeAcceptanceGreen':False,'formalPublicGateOpen':False})
        return 0
    except Exception as error:
        dump(out/'selector-rejection.json',{'schema':'purge-exact-selector-rejection-v1','key':args.key,
            'sourceHead':HEAD,'selector':args.selector,'selectorEvidenceComplete':False,
            'reason':str(error) if isinstance(error,ValueError) else type(error).__name__,
            'wholeAcceptanceGreen':False,'formalPublicGateOpen':False})
        return 2

if __name__=='__main__': raise SystemExit(main())
