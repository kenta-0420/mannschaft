"""固定した3件のnative-week selectorとJUnitを有限projectionへ束ねる。"""
import argparse, hashlib, json, re, subprocess
from pathlib import Path
from xml.parsers import expat
from xml.etree.ElementTree import Element, SubElement, tostring

BASE = Path(__file__).resolve().parent
HEAD = 'b1577a39d38b73e8f52bb18f440a206efbbe90ef'
TREE = '2b20b7c2654ba6e40666e4666c51f580d0263532'
EVENT_REF = 'refs/heads/feature/ranch-native-week-red-three-selector-20261016-02'
CASES = [
    {'key':'timeline','className':'com.mannschaft.app.timeline.service.TimelineRanchNativeTransportIT',
     'method':'同一UTC週の同本文別投稿は生成時既定TZが異なる接続でも二重受付されない','kind':'duplicate'},
    {'key':'blog','className':'com.mannschaft.app.cms.service.BlogRanchNativeTransportIT',
     'method':'同一UTC週の同タイトル本文別記事は生成時既定TZが異なる接続でも二重受付されない','kind':'duplicate'},
    {'key':'reflection','className':'com.mannschaft.app.reflection.ReflectionRanchTransportIT',
     'method':'同一UTC週の同entry別完了は生成時既定TZが異なる接続でも例外なく重複を拒否する','kind':'reflection'}]
SELECTORS = [case['className']+'.'+case['method'] for case in CASES]
HELPER_FILES = ['.github/workflows/backend-ranch-native-week-selector.yml','selector.init.gradle','evidence.py',
                'selector-result.schema.json','source-binding.json','synthetic_checks.py']
CONDITION_RE = re.compile(
    r'Native週DATE条件: driver=([^\s]{1,64}) JVM=([^\s]{1,64}) '
    r'connectionTimeZone=([^\s]{1,64}) driverDefaultA=([^\s]{1,64}) '
    r'driverDefaultB=([^\s]{1,64}) DBoffset=([^\s]{1,64})')
LABELS = {
    'utcMondayWrong':'実MySQL DATEのUTC月曜',
    'witnessCountWrong':'同一週のwitness件数',
    'outboxCountWrong':'同一週のoutbox件数',
    'reacceptanceWrong':'同一UTC週の再受付',
    'nativeSavedCountWrong':'本体の保存件数',
    'reflectionNoException':'同entry再受付の例外なし',
    'reflectionAttemptCountWrong':'完了attempt件数'}

def require(ok, code):
    if not ok: raise ValueError(code)

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def dump(path, value): path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def git(repo, query): return subprocess.check_output(['git','-C',str(repo),'rev-parse',query],text=True).strip()

def check_source(repo, selectors):
    require(selectors==SELECTORS,'SELECTOR_ORDER_OR_BINDING')
    require(git(repo,'HEAD')==HEAD and git(repo,'HEAD^{tree}')==TREE,'CHECKOUT_IDENTITY')
    binding=json.loads((BASE/'source-binding.json').read_text(encoding='utf-8'))
    require(binding.get('bindingStatus')=='BOUND' and binding.get('schema')=='ranch-native-week-three-selector-source-binding-v1'
            and binding.get('sourceHead')==HEAD and binding.get('sourceTree')==TREE
            and binding.get('checkoutPolicy')=='EXACT_CANONICAL_BLOB_NO_DECLARED_EOL_CONVERSION'
            and binding.get('helperFilesToPinAtEventCommit')==HELPER_FILES,'SOURCE_BINDING_POLICY')
    files=binding.get('sourceFiles',{})
    require(len(files)==16 and all(re.fullmatch(r'[a-f0-9]{64}',v) for v in files.values()),'SOURCE_PIN_SET')
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
    raw=subprocess.check_output(['git','-C',str(repo),'check-attr','--source='+HEAD,'-z',*attr_keys,'--',*files]).split(b'\0')
    require(raw[-1]==b'' and (len(raw)-1)%3==0,'ATTRIBUTES_SHAPE')
    attributes={path:{} for path in files}
    for i in range(0,len(raw)-1,3):
        path,attr_key,value=[part.decode('utf-8') for part in raw[i:i+3]]
        require(path in attributes and attr_key in attr_keys,'ATTRIBUTES_PATH_OR_KEY')
        attributes[path][attr_key]=value
    require(attributes==binding['gitAttributes'] and all(values==dict.fromkeys(attr_keys,'unspecified') for values in attributes.values()),'UNDECLARED_CHECKOUT_TRANSFORM')
    observations={}
    for rel,digest in files.items():
        canonical=subprocess.check_output(['git','-C',str(repo),'show',HEAD+':'+rel])
        path=repo/rel
        require(path.is_file() and not path.is_symlink() and b'\r' not in canonical
                and hashlib.sha256(canonical).hexdigest()==digest and path.read_bytes()==canonical,'SOURCE_BLOB_OR_CHECKOUT')
        observations[rel]={'canonicalBlobSha256':digest,'physicalEqualsCanonical':True,'normalizationUsed':False}
    return binding,observations

def parse_xml(path):
    require(path.stat().st_size<=16*1024*1024 and not path.is_symlink(),'XML_SIZE_OR_SYMLINK')
    parser=expat.ParserCreate(); parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
    suites=[]; rows=[]; current=None; depth=0; text_target=None; text_size=0; suite_system_out=''
    def start(name,attrs):
        nonlocal current,depth,text_target
        tag=name.rsplit(':',1)[-1]
        if depth==0: require(tag=='testsuite','XML_ROOT')
        if tag=='testsuite':
            require(depth==0,'NESTED_SUITE_UNEXPECTED')
            counters={}
            for key in ('tests','failures','errors','skipped'):
                value=attrs.get(key,''); require(re.fullmatch(r'\d{1,6}',value) is not None,'XML_COUNTERS'); counters[key]=int(value)
            suites.append(counters)
        if tag=='testcase':
            require(current is None and len(rows)<4,'TESTCASE_LIMIT')
            current={'className':attrs.get('classname'),'name':attrs.get('name'),'failure':False,'error':False,'skipped':False,
                     'failureText':'','errorText':'','systemOut':''}
        elif current is not None and tag in ('failure','error','skipped'):
            current[tag]=True
            if tag in ('failure','error'):
                text_target=tag
                current[tag+'Text'] += attrs.get('message','')+' '+attrs.get('type','')+' '
        elif tag=='system-out': text_target='systemOut'
        depth+=1
    def data(value):
        nonlocal text_size,suite_system_out
        if text_target:
            text_size+=len(value); require(text_size<=8*1024*1024,'XML_TEXT_LIMIT')
            if text_target=='systemOut': suite_system_out+=value
            elif current is not None: current[text_target+'Text']+=value
    def end(name):
        nonlocal current,depth,text_target
        tag=name.rsplit(':',1)[-1]
        if tag in ('failure','error','system-out'): text_target=None
        if tag=='testcase': require(current is not None,'TESTCASE_STATE'); rows.append(current); current=None
        depth-=1
    parser.StartElementHandler=start; parser.EndElementHandler=end; parser.CharacterDataHandler=data
    parser.StartDoctypeDeclHandler=lambda *_: require(False,'DTD_FORBIDDEN'); parser.ExternalEntityRefHandler=lambda *_:0
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(65536),b''): parser.Parse(block,False)
    parser.Parse(b'',True)
    require(current is None and len(suites)==1,'XML_COMPLETE')
    require(len(rows)==1,'ONE_CLASS_ONE_CASE')
    rows[0]['systemOut']=suite_system_out
    return rows,suites[0]

def condition_projection(system_out):
    matches=CONDITION_RE.findall(system_out)
    require(len(matches)==1,'CONDITION_MARKER_COUNT')
    driver,jvm,connection,default_a,default_b,db_offset=matches[0]
    return {
        'driverVersion':'9.6.0' if driver=='9.6.0' else 'OTHER_VERSION',
        'jvmAsiaTokyo':jvm=='Asia/Tokyo','connectionTimeZoneUTC':connection=='UTC',
        'driverDefaultAUTC':default_a=='UTC','driverDefaultBAsiaTokyo':default_b=='Asia/Tokyo',
        'dbOffsetUTC':db_offset=='UTC'}

def classify_case(case,row,counters,conditions):
    prereq=(conditions['driverVersion']=='9.6.0' and conditions['jvmAsiaTokyo'] and conditions['connectionTimeZoneUTC']
            and conditions['driverDefaultAUTC'] and conditions['driverDefaultBAsiaTokyo'] and conditions['dbOffsetUTC'])
    body=row['failureText']+' '+row['errorText']
    categories={key:(label in body) for key,label in LABELS.items()}
    collision=('uk_reflection_ranch_event' in body and 'Duplicate entry' in body) if case['kind']=='reflection' else False
    if not prereq: outcome='PRECONDITION_MISSING'
    elif row['skipped']: outcome='SKIPPED_NOT_ACCEPTED'
    elif row['error']: outcome='GENERIC_ERROR_NOT_EXPECTED_RED'
    elif row['failure']:
        if case['kind']=='duplicate':
            red=(categories['utcMondayWrong'] and categories['witnessCountWrong'] and categories['outboxCountWrong']
                 and categories['reacceptanceWrong'] and not categories['nativeSavedCountWrong'])
        else:
            red=(categories['utcMondayWrong'] and categories['reflectionNoException']
                 and not categories['nativeSavedCountWrong'] and not categories['reflectionAttemptCountWrong'] and collision)
        outcome='EXPECTED_RED' if red else 'GENERIC_FAILURE_NOT_EXPECTED_RED'
    else: outcome='PASS' if counters['tests']==1 else 'UNKNOWN'
    return outcome,prereq,categories,collision

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('mode',choices=['prepare','verify'])
    parser.add_argument('--repo-root',required=True,type=Path); parser.add_argument('--selector',action='append',required=True)
    parser.add_argument('--run-id',required=True,type=int); parser.add_argument('--run-attempt',required=True,type=int)
    parser.add_argument('--workflow-sha',required=True); parser.add_argument('--workflow-ref',required=True)
    parser.add_argument('--event-name',required=True); parser.add_argument('--output-dir',required=True,type=Path)
    args=parser.parse_args(); out=args.output_dir; out.mkdir(parents=True,exist_ok=True)
    try:
        repo=args.repo_root.resolve(); binding,physical=check_source(repo,args.selector)
        require(args.run_id>0 and args.run_attempt>0 and re.fullmatch(r'[0-9a-f]{40}',args.workflow_sha),'RUN_IDENTITY')
        require(args.event_name=='push' and args.workflow_ref==EVENT_REF,'WORKFLOW_EVENT_BINDING')
        helper_hashes={name:sha(BASE/name) for name in HELPER_FILES}
        header={'schema':'ranch-native-week-three-selector-header-v1','sourceHead':HEAD,'sourceTree':TREE,
                'sourceFiles':binding['sourceFiles'],'sourcePhysicalProjection':physical,'selectors':SELECTORS,
                'checkoutPolicy':binding['checkoutPolicy'],'gitAttributes':binding['gitAttributes'],
                'trackedGitAttributesBlobSha256':binding['trackedGitAttributesBlobSha256'],
                'expectedTestCount':3,'expectedSkippedCount':0,'helperSha256':helper_hashes,
                'eventCommit':args.workflow_sha,'runId':args.run_id,'runAttempt':args.run_attempt,
                'workflowRef':args.workflow_ref,'eventName':args.event_name,
                'wholeAcceptanceGreen':False,'formalPublicGateOpen':False}
        if args.mode=='prepare': dump(out/'source-selector-header.json',header); return 0
        require(json.loads((out/'source-selector-header.json').read_text(encoding='utf-8'))==header,'HEADER_MISMATCH')
        exit_text=(out/'run-tests-exit.txt').read_text(encoding='ascii').strip()
        require(re.fullmatch(r'\d{1,3}',exit_text),'RUN_TESTS_EXIT_MISSING'); actual_exit=int(exit_text)
        actual=json.loads((out/'actual-test-counts.json').read_text(encoding='utf-8'))
        filters=json.loads((out/'effective-filter.json').read_text(encoding='utf-8'))
        require(filters=={'selectors':SELECTORS,'includePatterns':sorted(SELECTORS),'failOnNoMatchingTests':True,
                          'testCacheDisabled':True,'testUpToDateDisabled':True,'maxParallelForks':1,'maxGradleWorkers':2},'EFFECTIVE_FILTER')
        require(actual.get('selectors')==SELECTORS and actual.get('testCount')==3 and actual.get('skippedTestCount')==0,'ACTUAL_COUNT')
        require(actual.get('successfulTestCount')+actual.get('failedTestCount')==3,'ACTUAL_COUNT_SUM')
        descriptors=sorted((row.get('className'),row.get('name')) for row in actual.get('descriptors',[]))
        expected_descriptors=sorted((case['className'],case['method']+'()') for case in CASES)
        require(descriptors==expected_descriptors,'ACTUAL_DESCRIPTORS')
        xml_dir=repo/'backend/build/test-results/test'
        xmls=list(xml_dir.glob('TEST-*.xml'))
        expected_names={'TEST-'+case['className']+'.xml' for case in CASES}
        require({path.name for path in xmls}==expected_names and len(xmls)==3,'XML_SET_NOT_EXACT')
        by_class={}; results=[]
        for case in CASES:
            path=xml_dir/('TEST-'+case['className']+'.xml'); rows,counters=parse_xml(path)
            require(len(rows)==1 and rows[0]['className']==case['className'] and rows[0]['name']==case['method']+'()','XML_DESCRIPTOR')
            row=rows[0]
            require(counters['tests']==1 and counters['skipped']==0 and counters['errors']==0,'XML_COUNTERS_NOT_ONE')
            require(counters=={'tests':len(rows),'failures':int(row['failure']),'errors':int(row['error']),'skipped':int(row['skipped'])},'XML_COUNTER_ROW_MISMATCH')
            conditions=condition_projection(row['systemOut'])
            outcome,prereq,categories,collision=classify_case(case,row,counters,conditions)
            finite=Element('testsuite',name=case['className'],tests='1',failures=str(int(row['failure'])),errors=str(int(row['error'])),skipped=str(int(row['skipped'])))
            finite_case=SubElement(finite,'testcase',classname=case['className'],name=case['method']+'()')
            if row['failure'] or row['error']:
                SubElement(finite_case,'failure' if row['failure'] else 'error',type=outcome,message='FINITE_NATIVE_WEEK_CLASSIFICATION')
            SubElement(finite,'system-out').text=json.dumps({'caseKey':case['key'],'conditions':conditions,'prerequisiteMatched':prereq,'assertionCategories':categories,'canonicalOutboxUniqueCollision':collision,'outcome':outcome},sort_keys=True)
            finite_path=out/('finite-junit-'+case['key']+'.xml'); finite_path.write_bytes(tostring(finite,encoding='utf-8',xml_declaration=True))
            projection={'caseKey':case['key'],'className':case['className'],'method':case['method'],'outcome':outcome,
                        'actualTestCount':1,'success':not row['failure'] and not row['error'] and not row['skipped'],
                        'failure':row['failure'],'error':row['error'],'skipped':row['skipped'],
                        'rawXmlSha256':sha(path),'finiteXmlSha256':sha(finite_path),'conditions':conditions,
                        'prerequisiteMatched':prereq,'assertionCategories':categories,'canonicalOutboxUniqueCollision':collision}
            results.append(projection); by_class[case['key']]=outcome
        require(actual['failedTestCount']==sum(int(x['failure'] or x['error']) for x in [parse_xml(xml_dir/('TEST-'+case['className']+'.xml'))[0][0] for case in CASES]),'ACTUAL_FAILURE_COUNT_MISMATCH')
        if all(value=='EXPECTED_RED' for value in by_class.values()) and actual_exit!=0:
            overall='EXPECTED_NATIVE_WEEK_THREE_CASE_RED'
        elif all(value=='PASS' for value in by_class.values()) and actual_exit==0 and actual['successfulTestCount']==3:
            overall='NATIVE_WEEK_THREE_CASE_GREEN'
        else: overall='MIXED_OR_UNCLASSIFIED_NOT_ACCEPTED'
        dump(out/'selector-result.json',{**header,'schema':'ranch-native-week-three-selector-result-v1','runTestsActualExit':actual_exit,
            'actualTestCounts':actual,'effectiveFilter':filters,'caseResults':results,'overallOutcome':overall,
            'wholeAcceptanceGreen':False,'formalPublicGateOpen':False,'rawLogsPublished':False,'rawXmlPublished':False})
        return 0 if overall in ('EXPECTED_NATIVE_WEEK_THREE_CASE_RED','NATIVE_WEEK_THREE_CASE_GREEN') else 2
    except Exception as error:
        dump(out/'selector-rejection.json',{'schema':'ranch-native-week-three-selector-rejection-v1','sourceHead':HEAD,
            'selectorCount':len(args.selector),'evidenceComplete':False,
            'reason':str(error) if isinstance(error,ValueError) else type(error).__name__,
            'wholeAcceptanceGreen':False,'formalPublicGateOpen':False})
        return 2

if __name__=='__main__': raise SystemExit(main())
