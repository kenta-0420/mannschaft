"""各jobの固定source、exact selector、実数、actualexitを原XMLの有限投影（原XMLではない）へ結合する。"""
import argparse, hashlib, json, re, subprocess
from pathlib import Path
from xml.parsers import expat

HEAD='2b6ee65337982ae36723da41f160679ddf4a11b0'
TREE='358c29f1fae43fb8d0f8797827f12291e7e3b6be'
CLASS='com.mannschaft.app.ranch.RanchCareDateRoundTripHttpIT'
METHOD='無料care保存後のDATEと本人GETがUTC週を保ち再操作でも親密度は一日一回'
METHODS={'utc-red':METHOD,'jst-control':METHOD}
TZ={'utc-red':'UTC','jst-control':'Asia/Tokyo'}

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
    require(binding.get('schema')=='ranch-date-exact-selector-source-binding-v1'
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
    source=repo/'backend/src/test/java/com/mannschaft/app/ranch/RanchCareDateRoundTripHttpIT.java'
    text=source.read_text(encoding='utf-8-sig')
    predicate=r'@Test\s+void '+re.escape(METHODS[key])+r'\(\) throws Exception'
    require(len(re.findall(predicate,text))==1,'METHOD_SOURCE_PREDICATE')
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
    # JUnit本文は有限分類の入力だけに使い、本文やstackを保存・出力しない。
    parser=expat.ParserCreate();parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
    capture=None;chunks=[];texts={'failure':[],'system-out':[]};size=0
    def start(name,attrs):
        nonlocal capture,chunks
        tag=name.rsplit(':',1)[-1]
        if tag in texts: capture=tag;chunks=[]
    def data(value):
        nonlocal size
        if capture is not None:
            size+=len(value);require(size<=8*1024*1024,'XML_TEXT_LIMIT');chunks.append(value)
    def end(name):
        nonlocal capture,chunks
        if name.rsplit(':',1)[-1]==capture:
            texts[capture].append(''.join(chunks));capture=None;chunks=[]
    parser.StartElementHandler=start;parser.EndElementHandler=end;parser.CharacterDataHandler=data
    parser.StartDoctypeDeclHandler=lambda *_: require(False,'DTD_FORBIDDEN')
    parser.ExternalEntityRefHandler=lambda *_:0
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(65536),b''):parser.Parse(block,False)
    parser.Parse(b'',True)
    stdout='\n'.join(texts['system-out']);failure='\n'.join(texts['failure'])
    pattern=r'Ranch DATE条件: driver=([^\r\n]{1,256}) JVM=(Asia/Tokyo) connectionTimeZone=(UTC) driverDefault=(UTC|GMT|Etc/UTC|Asia/Tokyo|GMT\+09:00) DBoffset=(UTC)'
    # 実ログ文字列は返さず、許可分類へ射影する。
    matches=re.findall(pattern,stdout)
    require(len(matches)<=1,'CONDITION_LINE_COUNT')
    conditions=None
    if matches:
        driver,jvm,connection,cached,db=matches[0]
        conditions={'driverVersion':'9.6.0' if re.search(r'(?<![0-9])9\.6\.0(?![0-9])',driver) else 'OTHER_VERSION','jvmDefault':jvm,'connectionTimeZone':connection,'driverDefault':cached,'dbOffset':db}
    labels={'rawWeek':'実MySQL DATEのUTC月曜','rawDay':'実MySQL DATEのUTC親密度日','jpaWeek':'JPA読戻しのUTC週DATE','independentGet':'care commit後の独立GET'}
    categories={key:label in failure for key,label in labels.items()}
    get_section=failure[failure.find(labels['independentGet']):][:512] if labels['independentGet'] in failure else ''
    categories['get500']=re.search(r'expected:\s*200\s*but was:\s*500',get_section) is not None
    categories['cleanupFailure']='removeOnlyOwnedSyntheticRows' in failure or '本人fixture残存行' in failure
    dates={date:date in failure for date in ('2026-10-04','2026-10-05','2026-10-10','2026-10-11')}
    return conditions,categories,dates


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
        require(args.event_name=='push' and args.workflow_ref=='refs/heads/feature/ranch-date-roundtrip-8ced-20261015-01','WORKFLOW_EVENT_BINDING')
        header={'schema':'ranch-date-exact-selector-header-v1','sourceHead':HEAD,'sourceTree':TREE,'sourceFiles':binding['sourceFiles'],'selector':args.selector,'key':args.key,'initialJvmTimezone':TZ[args.key],'sourcePinKind':'GIT_CANONICAL_BLOB_SHA256','checkoutPolicy':binding['checkoutPolicy'],'checkoutPhysicalObservations':physical,'gitAttributes':binding['gitAttributes'],'trackedGitAttributesBlobSha256':binding['trackedGitAttributesBlobSha256'],'expectedTestCount':1,'runId':args.run_id,'runAttempt':args.run_attempt,'workflowSha':args.workflow_sha,'workflowRef':args.workflow_ref,'eventName':args.event_name,'wholeAcceptanceGreen':False,'formalPublicGateOpen':False}
        if args.mode=='prepare':dump(out/'source-selector-header.json',header);return 0
        require(json.loads((out/'source-selector-header.json').read_text(encoding='utf-8'))==header,'HEADER_MISMATCH')
        exit_text=(out/'run-tests-exit.txt').read_text().strip();require(re.fullmatch(r'\d{1,3}',exit_text),'RUN_TESTS_EXIT_MISSING');actual_exit=int(exit_text)
        actual=json.loads((out/'actual-test-counts.json').read_text());filters=json.loads((out/'effective-filter.json').read_text())
        require(filters=={'selector':args.selector,'includePatterns':[args.selector],'failOnNoMatchingTests':True,'testCacheDisabled':True,'testUpToDateDisabled':True,'initialJvmTimezone':TZ[args.key],'expectedJvmTimezone':TZ[args.key]},'EFFECTIVE_FILTER')
        require(actual['selector']==args.selector and actual['testCount']==1 and actual['skippedTestCount']==0,'ACTUAL_TEST_COUNT')
        require(actual['successfulTestCount']+actual['failedTestCount']==1,'ACTUAL_COUNT_SUM')
        require(len(actual['descriptors'])==1 and actual['descriptors'][0]['className']==CLASS and actual['descriptors'][0]['name']==METHOD+'()','DESCRIPTOR_NOT_EXACT')
        xmls=list((args.repo_root/'backend/build/test-results/test').glob('TEST-*.xml'));require(len(xmls)==1 and xmls[0].name=='TEST-'+CLASS+'.xml','OTHER_TEST_XML_OR_MISSING')
        rows,counters=parse_xml(xmls[0]);require(len(rows)==1 and rows[0]['className']==CLASS and rows[0]['name']==METHOD+'()','XML_METHOD_NOT_EXACT')
        require(counters['tests']==1 and counters['errors']==0 and counters['skipped']==0,'XML_CASE_COUNTERS')
        require(counters=={'tests':len(rows),'failures':sum(row['failure'] for row in rows),'errors':sum(row['error'] for row in rows),'skipped':sum(row['skipped'] for row in rows)},'XML_ROW_COUNTER_MISMATCH')
        require(actual['testCount']==counters['tests'] and actual['failedTestCount']==counters['failures']+counters['errors'] and actual['skippedTestCount']==counters['skipped'] and actual['successfulTestCount']==counters['tests']-counters['failures']-counters['errors']-counters['skipped'],'XML_ACTUAL_OUTCOME_MISMATCH')
        conditions,categories,dates=finite_xml_conditions(xmls[0]);outcome='REPRODUCTION_CONDITION_MISSING'
        ready=conditions is not None and conditions['driverVersion']=='9.6.0' and conditions['jvmDefault']=='Asia/Tokyo' and conditions['connectionTimeZone']=='UTC' and conditions['dbOffset']=='UTC'
        if ready:
            if args.key=='utc-red':
                if conditions['driverDefault'] in ('UTC','GMT','Etc/UTC'):
                    outcome='EXPECTED_BUSINESS_RED' if actual_exit!=0 and actual['failedTestCount']==1 and counters['failures']==1 and all(categories[key] for key in ('rawWeek','rawDay','jpaWeek','independentGet','get500')) and not categories['cleanupFailure'] and all(dates.values()) else ('UTC_GREEN_NO_RED' if actual_exit==0 and actual['successfulTestCount']==1 and counters['failures']==0 else 'OTHER_FAILURE_NOT_RED')
            elif conditions['driverDefault'] in ('Asia/Tokyo','GMT+09:00'):
                outcome='CONTROL_GREEN' if actual_exit==0 and actual['successfulTestCount']==1 and counters['failures']==0 else 'CONTROL_FAILED'
        # raw XMLはhashだけを結合。artifact化するJUnitには固定methodと有限categoryのみを出す。
        from xml.etree.ElementTree import Element,SubElement,tostring
        suite=Element('testsuite',name=CLASS,**{k:str(v) for k,v in counters.items()});case=SubElement(suite,'testcase',classname=CLASS,name=METHOD+'()')
        if counters['failures']:SubElement(case,'failure',type=outcome,message='FINITE_DATE_CLASSIFICATION')
        SubElement(suite,'system-out').text=json.dumps({'conditions':conditions,'assertionCategories':categories,'knownDateTokens':dates},sort_keys=True)
        (out/'finite-junit.xml').write_bytes(tostring(suite,encoding='utf-8',xml_declaration=True))
        dump(out/'selector-result.json',{**header,'schema':'ranch-date-exact-selector-result-v1','runTestsActualExit':actual_exit,'actualTestCounts':actual,'effectiveFilter':filters,'rawXmlSha256':sha(xmls[0]),'finiteXmlSha256':sha(out/'finite-junit.xml'),'cases':rows,'runtimeDateConditions':conditions,'assertionCategories':categories,'knownDateTokens':dates,'outcome':outcome,'evidenceComplete':True,'rawLogPublished':False,'productAcceptanceProven':False})
        return 0 if outcome in ('EXPECTED_BUSINESS_RED','CONTROL_GREEN','UTC_GREEN_NO_RED') else 2
    except Exception as error:
        dump(out/'selector-rejection.json',{'schema':'ranch-date-selector-rejection-v1','sourceHead':HEAD,'selector':args.selector,'key':args.key,'evidenceComplete':False,'reason':str(error) if isinstance(error,ValueError) else type(error).__name__,'wholeAcceptanceGreen':False,'formalPublicGateOpen':False});return 2

if __name__=='__main__':raise SystemExit(main())
