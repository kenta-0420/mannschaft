"""各jobの固定source、exact selector、実数、actualexitを原XMLの有限投影（原XMLではない）へ結合する。"""
import argparse, hashlib, json, re, subprocess
from pathlib import Path
from xml.parsers import expat

HEAD='2d569905b8c204d57bd4b86af9d92ab1efd4e57e'
TREE='83e1a6a56c95b7c03ee0e68771f4a4b83a84de8e'
CLASS='com.mannschaft.app.reflection.RecallRewardWeekTemporalIT'
METHOD='physicalWeekAndNativeGetSurviveCompletionAndNextWeekReplay'
ACK_METHOD='未参加の実native返値を源ACKへ渡し開始後も終端を保ち別entryだけ報酬を得る'
METHODS={'utc-initial':METHOD,'jst-initial':METHOD}
TZ={'utc-initial':'UTC','jst-initial':'Asia/Tokyo'}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def dump(path, value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def require(condition, code):
    if not condition: raise ValueError(code)

def git(repo, query):
    return subprocess.check_output(['git','-C',str(repo),'rev-parse',query],text=True).strip()

def check_source(repo, key, selector):
    require(isinstance(HEAD,str) and re.fullmatch('[a-f0-9]{40}',HEAD) and isinstance(TREE,str) and re.fullmatch('[a-f0-9]{40}',TREE),'SOURCE_W_UNBOUND')
    require(key in METHODS and selector==CLASS,'SELECTOR_BINDING')
    require(git(repo,'HEAD')==HEAD and git(repo,'HEAD^{tree}')==TREE,'CHECKOUT_IDENTITY')
    binding=json.loads(Path(__file__).with_name('source-binding.json').read_text(encoding='utf-8'))
    require(binding.get('schema')=='recall-week-exact-selector-source-binding-v1'
            and binding.get('sourceHead')==HEAD and binding.get('sourceTree')==TREE
            and binding.get('checkoutPolicy')=='EXACT_CANONICAL_BLOB_NO_DECLARED_EOL_CONVERSION','SOURCE_BINDING_POLICY')
    # 固定treeの属性blobと15対象の実効属性を別検査し、info/global変換も許可しない。
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
        path,attr_key,value=[part.decode('utf-8') for part in raw[i:i+3]]
        require(path in attributes and attr_key in attr_keys,'ATTRIBUTES_PATH_OR_KEY')
        attributes[path][attr_key]=value
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
    source=repo/'backend/src/test/java/com/mannschaft/app/reflection/RecallRewardWeekTemporalIT.java'
    text=source.read_text(encoding='utf-8-sig')
    predicate=r'void '+re.escape(METHODS[key])+r'\(\s*String scenario, Instant completion, LocalDate expectedWeek\) throws Exception'
    require('@ParameterizedTest(name = "{0}")' in text and all(text.count(label+',')==1 for label in ('SUNDAY_CONTROL','MONDAY_BOUNDARY')), 'PARAMETER_SOURCE_SET')
    require(len(re.findall(predicate,text))==1 and len(re.findall(r'void '+re.escape(ACK_METHOD)+r'\(\) throws Exception',text))==1,'METHOD_SOURCE_PREDICATE')
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


def finite_xml_conditions(path):
    # Failure text is consumed only for closed assertion categories; no body/stack/log is emitted.
    parser=expat.ParserCreate(); parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
    active=False; parts=[]; size=0
    def start(name,attrs):
        nonlocal active
        if name in ('failure','error'): active=True
    def data(value):
        nonlocal size
        if active:
            size+=len(value);require(size<=8*1024*1024,'FAILURE_TEXT_LIMIT');parts.append(value)
    def end(name):
        nonlocal active
        if name in ('failure','error'): active=False
    parser.StartElementHandler=start;parser.EndElementHandler=end;parser.CharacterDataHandler=data
    parser.StartDoctypeDeclHandler=lambda *_: require(False,'DTD_FORBIDDEN')
    parser.ExternalEntityRefHandler=lambda *_:0
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(65536),b''):parser.Parse(block,False)
    parser.Parse(b'',True);text=''.join(parts)
    labels={'COMPLETE_ACK':' complete ACK','SQL_PHYSICAL_DATE':' SQL CAST DATE','NATIVE_GET_WEEK':' fresh native GET'}
    return {code:label in text for code,label in labels.items()}


def main():
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['prepare','verify'])
    p.add_argument('--repo-root',required=True,type=Path);p.add_argument('--key',required=True)
    p.add_argument('--selector',required=True);p.add_argument('--run-id',required=True,type=int)
    p.add_argument('--run-attempt',required=True,type=int);p.add_argument('--workflow-sha',required=True)
    p.add_argument('--workflow-ref',required=True);p.add_argument('--event-name',required=True)
    p.add_argument('--output-dir',required=True,type=Path);args=p.parse_args()
    out=args.output_dir;out.mkdir(parents=True,exist_ok=True)
    try:
        binding,physical=check_source(args.repo_root.resolve(),args.key,args.selector)
        require(args.run_id>0 and args.run_attempt>0 and re.fullmatch(r'[0-9a-f]{40}',args.workflow_sha),'RUN_IDENTITY')
        require(args.event_name=='push' and args.workflow_ref=='refs/heads/feature/ranch-recall-ack-week-fixture-w-20261015-01','WORKFLOW_EVENT_BINDING')
        header={'schema':'recall-week-exact-selector-header-v1','sourceHead':HEAD,'sourceTree':TREE,'sourceFiles':binding['sourceFiles'],'selector':args.selector,'key':args.key,'initialJvmTimezone':TZ[args.key],'sourcePinKind':'GIT_CANONICAL_BLOB_SHA256','checkoutPolicy':binding['checkoutPolicy'],'checkoutPhysicalObservations':physical,'gitAttributes':binding['gitAttributes'],'trackedGitAttributesBlobSha256':binding['trackedGitAttributesBlobSha256'],'expectedTestCount':3,'runId':args.run_id,'runAttempt':args.run_attempt,'workflowSha':args.workflow_sha,'workflowRef':args.workflow_ref,'eventName':args.event_name,'wholeAcceptanceGreen':False,'formalPublicGateOpen':False}
        if args.mode=='prepare':dump(out/'source-selector-header.json',header);return 0
        require(json.loads((out/'source-selector-header.json').read_text(encoding='utf-8'))==header,'HEADER_MISMATCH')
        exit_text=(out/'run-tests-exit.txt').read_text().strip();require(re.fullmatch(r'\d{1,3}',exit_text),'RUN_TESTS_EXIT_MISSING');actual_exit=int(exit_text)
        actual=json.loads((out/'actual-test-counts.json').read_text());filters=json.loads((out/'effective-filter.json').read_text())
        require(filters=={'selector':args.selector,'includePatterns':[args.selector],'failOnNoMatchingTests':True,'testCacheDisabled':True,'testUpToDateDisabled':True,'initialJvmTimezone':TZ[args.key],'expectedJvmTimezone':TZ[args.key]},'EFFECTIVE_FILTER')
        require(actual['selector']==args.selector and actual['testCount']==3 and actual['skippedTestCount']==0,'ACTUAL_TEST_COUNT')
        require(actual['successfulTestCount']+actual['failedTestCount']==3,'ACTUAL_COUNT_SUM')
        descriptors=actual['descriptors'];labels={'SUNDAY_CONTROL','MONDAY_BOUNDARY',ACK_METHOD+'()'}
        require(len(descriptors)==3 and {d['displayName'] for d in descriptors}==labels and all(d['className']==CLASS and isinstance(d['name'],str) and len(d['name'])<=256 and (d['name'].startswith(METHOD+'(') if d['displayName'] in {'SUNDAY_CONTROL','MONDAY_BOUNDARY'} else d['displayName']==ACK_METHOD+'()' and d['name']==ACK_METHOD+'()') for d in descriptors),'DESCRIPTOR_NOT_EXACT')
        xmls=list((args.repo_root/'backend/build/test-results/test').glob('TEST-*.xml'));require(len(xmls)==1 and xmls[0].name=='TEST-'+CLASS+'.xml','OTHER_TEST_XML_OR_MISSING')
        rows,counters=parse_xml(xmls[0]);require(len(rows)==3 and all(row['className']==CLASS for row in rows),'XML_METHOD_NOT_EXACT')
        remaining=list(descriptors)
        for row in rows:
            matches=[d for d in remaining if row['name'] in (d['name'],d['displayName'])]
            require(len(matches)==1,'XML_DESCRIPTOR_JOIN');descriptor=matches[0];remaining.remove(descriptor);row['name']=descriptor['displayName']
        require(not remaining and {row['name'] for row in rows}==labels,'XML_CASE_SET')
        require(counters['tests']==3 and counters['skipped']==0,'XML_CASE_COUNTERS')
        require(counters=={'tests':len(rows),'failures':sum(row['failure'] for row in rows),'errors':sum(row['error'] for row in rows),'skipped':sum(row['skipped'] for row in rows)},'XML_ROW_COUNTER_MISMATCH')
        require(actual['testCount']==counters['tests'] and actual['failedTestCount']==counters['failures']+counters['errors'] and actual['skippedTestCount']==counters['skipped'] and actual['successfulTestCount']==counters['tests']-counters['failures']-counters['errors']-counters['skipped'],'XML_ACTUAL_OUTCOME_MISMATCH')
        categories=finite_xml_conditions(xmls[0])
        passed=actual_exit==0 and actual['successfulTestCount']==3 and counters['failures']==0 and counters['errors']==0
        require((actual_exit==0 and passed) or (actual_exit!=0 and actual['failedTestCount']>0),'ACTUAL_EXIT_OUTCOME_MISMATCH')
        outcome='THREE_CASES_PASSED' if passed else 'TEST_FAILURE_OBSERVED'
        from xml.etree.ElementTree import Element,SubElement,tostring
        suite=Element('testsuite',name=CLASS,**{k:str(v) for k,v in counters.items()})
        for row in rows:
            case=SubElement(suite,'testcase',classname=CLASS,name=row['name'])
            for kind in ('failure','error'):
                if row[kind]:SubElement(case,kind,type='FINITE_RECALL_WEEK_FAILURE',message='PRIVATE_DETAIL_OMITTED')
        (out/'finite-junit.xml').write_bytes(tostring(suite,encoding='utf-8',xml_declaration=True))
        # Descriptor raw names are needed only for the local XML join. Export fixed labels only.
        actual['descriptors']=[{'className':CLASS,'name':d['displayName']} for d in descriptors]
        dump(out/'selector-result.json',{**header,'schema':'recall-week-exact-selector-result-v1','runTestsActualExit':actual_exit,'actualTestCounts':actual,'effectiveFilter':filters,'rawXmlSha256':sha(xmls[0]),'finiteXmlSha256':sha(out/'finite-junit.xml'),'cases':rows,'assertionCategories':categories,'outcome':outcome,'evidenceComplete':True,'rawLogPublished':False,'productAcceptanceProven':False})
        return 0

    except Exception as error:
        failure_stage='TEST_EXECUTED_EVIDENCE_REJECTED' if (out/'actual-test-counts.json').is_file() else ('GRADLE_FAILED_BEFORE_ROOT_SUITE' if (out/'run-tests-exit.txt').is_file() else 'TEST_NOT_RUN')
        dump(out/'selector-rejection.json',{'schema':'recall-week-selector-rejection-v1','sourceHead':HEAD,'selector':args.selector,'key':args.key,'evidenceComplete':False,'failureStage':failure_stage,'reason':str(error) if type(error) is ValueError and re.fullmatch(r'[A-Z_]{1,64}',str(error)) else 'UNCLASSIFIED_EVIDENCE_ERROR','wholeAcceptanceGreen':False,'formalPublicGateOpen':False});return 2

if __name__=='__main__':raise SystemExit(main())
