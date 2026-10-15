"""cache5専用比較のsource/実argv/exit/有限JUnit記録。normal CI成功へ読み替えない。"""
import argparse, hashlib, json, os, re, subprocess, sys
from pathlib import Path
from xml.parsers import expat
from decimal import Decimal

HEAD='1c6411fcb13cbee4cfd4de7e2a972a2426746735'
TREE='4db5181aaa860916c2edb0a09de6e04c766dc92b'
BRANCH='refs/heads/feature/ranch-shard0-cache5-1c6411fc-20261015-02'
COMPILE=['./gradlew','compileJava','compileTestJava','--no-daemon','--build-cache']
NORMAL_TEST=['./gradlew','test','--no-daemon','--continue','--build-cache','-Pshard.total=6','-Pshard.index=0','-PexcludeMigrationTests=false']
TEST=NORMAL_TEST+['-Pspring.test.context.cache.maxSize=5']
ASSETS={'backend-shard0-cache5-1c6411fc.yml':'.github/workflows/backend-shard0-cache5-1c6411fc.yml',
        'evidence.py':'.github/scripts/shard0-cache5-1c6411fc/evidence.py',
        'observe.init.gradle':'.github/scripts/shard0-cache5-1c6411fc/observe.init.gradle',
        'source-binding.json':'.github/scripts/shard0-cache5-1c6411fc/source-binding.json'}
HERE=Path(__file__).resolve().parent
class Refused(Exception):pass
class Parser(argparse.ArgumentParser):
    def error(self,message):raise Refused('CLI_SHAPE')
def need(ok,code):
    if not ok:raise Refused(code)
def digest(data):return hashlib.sha256(data).hexdigest()
def sha(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def read(path):
    need(path.is_file() and not path.is_symlink() and path.stat().st_size<=8*1024**2,'FINITE_JSON_FILE')
    return json.loads(path.read_bytes())
def save(path,value):
    with path.open('x',encoding='utf8') as f:json.dump(value,f,indent=2);f.write('\n')
def git(repo,*args):return subprocess.check_output(['git','-C',str(repo),*args],stderr=subprocess.DEVNULL)
def identity(repo):
    need(git(repo,'rev-parse','HEAD').decode().strip()==HEAD and git(repo,'rev-parse','HEAD^{tree}').decode().strip()==TREE,'PRODUCT_CHECKOUT_IDENTITY')
    status=git(repo,'status','--porcelain','--untracked-files=no').strip()
    if status:
        # Normal backend-ci chmod changes only this tracked file's executable bit.
        need(status==b'M backend/gradlew','PRODUCT_TRACKED_SOURCE_CHANGED')
        need(git(repo,'diff','--summary','HEAD').strip()==b'mode change 100644 => 100755 backend/gradlew','NORMAL_WRAPPER_MODE_ONLY')
        need(git(repo,'diff','--numstat','HEAD').strip()==b'0\t0\tbackend/gradlew','NORMAL_WRAPPER_BYTES_UNCHANGED')
    binding=read(HERE/'source-binding.json');need(binding['sourceHead']==HEAD and binding['sourceTree']==TREE,'SOURCE_BINDING_IDENTITY')
    observations={}
    for rel,row in binding['sourceFiles'].items():
        path=repo/rel;need(path.is_file() and not path.is_symlink(),'SOURCE_FILE_SHAPE')
        blob=git(repo,'show',HEAD+':'+rel)
        need(len(blob)==row['bytes'] and digest(blob)==row['sha256'] and path.read_bytes()==blob,'SOURCE_CANONICAL_PHYSICAL_PIN')
        observations[rel]={'bytes':len(blob),'canonicalSha256':row['sha256'],'physicalEqualsCanonical':True}
    return observations
def destination(repo):return repo/'backend/build/shard0-cache5-1c6411fc'
def prepare(args,repo):
    out=destination(repo);out.mkdir(parents=True,exist_ok=False)
    observations=identity(repo)
    harness=args.harness_root.resolve(strict=True)
    need(re.fullmatch('[a-f0-9]{40}',args.workflow_sha) and git(harness,'rev-parse','HEAD').decode().strip()==args.workflow_sha,'HELPER_EVENT_COMMIT')
    need(args.workflow_ref==BRANCH and args.event_name=='push' and args.run_id>0 and args.run_attempt>0,'HELPER_EVENT_IDENTITY')
    helper={}
    for name,rel in ASSETS.items():
        path=harness/rel;blob=git(harness,'show',args.workflow_sha+':'+rel)
        need(path.is_file() and not path.is_symlink() and path.read_bytes()==blob,'PUBLISHED_HELPER_BYTES')
        helper[rel]={'bytes':len(blob),'sha256':digest(blob)}
    gradle_home=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle'))).resolve()
    init_dir=gradle_home/'init.d';init_dir.mkdir(parents=True,exist_ok=True)
    init=init_dir/f'mannschaft-cache5-{args.run_id}-{args.run_attempt}.gradle'
    need(not init.exists() and not init.is_symlink(),'OWN_INIT_COLLISION')
    with init.open('xb') as f:f.write((HERE/'observe.init.gradle').read_bytes())
    save(out/'source-proof.json',{'schema':'shard0-cache5-source-proof-v1','sourceCommit':HEAD,'sourceTree':TREE,
        'actualCheckoutSha':HEAD,'actualCheckoutTree':TREE,'sourcePhysicalPins':observations,
        'helperEventCommit':args.workflow_sha,'helperRef':args.workflow_ref,'eventName':'push',
        'runId':args.run_id,'runAttempt':args.run_attempt,'publishedHelperAssets':helper,
        'normalCompileArgv':COMPILE,'normalShard0TestArgv':NORMAL_TEST,'diagnosticTestArgv':TEST,
        'onlyTestArgumentDelta':['-Pspring.test.context.cache.maxSize=5'],
        'migrationTestsIncluded':True,'normalWrapperExecutableBitChangeAllowed':True,'jobDeadlineMinutes':105,'observerInitSha256':sha(init),
        'sourceBindingSha256':sha(HERE/'source-binding.json'),'baselineNormalRun':38666544949,
        'baselineConclusion':'cancelled','baselineShard0Conclusion':'cancelled','hypothesisOnly':True,
        'currentHeapDominatorMeasured':False,'normalCiSuccessInherited':False,'productGreen':False})
    print('{"phase":"SOURCE_PREPARED","sourceIdentityVerified":true,"testExecuted":false,"productGreen":false}')
def run(args,repo):
    out=destination(repo);read(out/'source-proof.json');identity(repo)
    command=args.command
    if command and command[0]=='--':command=command[1:]
    expected=COMPILE if args.phase=='compile' else TEST
    need(command==expected,'EXACT_NORMAL_ARGUMENTS_PLUS_CACHE5_ONLY')
    private=repo/'backend/build/cache5-private';private.mkdir(mode=0o700,exist_ok=True)
    need(not private.is_symlink(),'PRIVATE_DIRECTORY')
    save(out/(args.phase+'-started.json'),{'schema':'cache5-process-start-v1','phase':args.phase,'actualArgv':command,'sourceCommit':HEAD,'sourceTree':TREE})
    with (private/(args.phase+'.log')).open('xb') as log:
        result=subprocess.run(command,cwd=repo/'backend',stdout=log,stderr=subprocess.STDOUT)
    save(out/(args.phase+'-exit.json'),{'schema':'cache5-process-exit-v1','phase':args.phase,'actualExit':result.returncode,'rawLogPublished':False})
    print(json.dumps({'phase':args.phase,'actualExit':result.returncode,'rawLogPublished':False,'productGreen':False}))
    return result.returncode
def parse_xml(path):
    need(not path.is_symlink() and path.stat().st_size<=256*1024**2,'XML_FILE_BOUND')
    parser=expat.ParserCreate();parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
    suites=[];ranch={'tests':0,'failures':0,'errors':0,'skipped':0};current=None;cases=0
    def forbidden(*_):raise Refused('XML_DTD_OR_ENTITY')
    def start(name,attrs):
        nonlocal current,cases
        tag=name.rsplit(':',1)[-1]
        if tag=='testsuite':
            row={}
            for key in ['tests','failures','errors','skipped']:
                value=attrs.get(key,'');need(re.fullmatch('[0-9]{1,9}',value) is not None and int(value)<=300000,'XML_COUNTER_BOUND');row[key]=int(value)
            value=attrs.get('time','');need(len(value)<=32 and re.fullmatch('[0-9]+(?:\\.[0-9]+)?',value) is not None,'XML_TIME_SHAPE')
            need(not suites,'XML_NESTED_OR_MULTIPLE_SUITE')
            number=Decimal(value);need(number<=Decimal(1000000000),'XML_TIME_BOUND')
            row['timeSeconds']=str(number);suites.append(row)
        elif tag=='testcase':
            cases+=1;need(cases<=300000 and current is None,'XML_CASE_BOUND')
            cls=attrs.get('classname','');need(len(cls)<=512,'XML_CLASS_BOUND')
            current={'ranch':cls.startswith('com.mannschaft.app.ranch.'),'failures':False,'errors':False,'skipped':False}
        elif current is not None and tag in ['failure','error','skipped']:current[{'failure':'failures','error':'errors','skipped':'skipped'}[tag]]=True
    def end(name):
        nonlocal current
        if name.rsplit(':',1)[-1]=='testcase':
            need(current is not None,'XML_CASE_STATE')
            if current['ranch']:
                ranch['tests']+=1
                for key in ['failures','errors','skipped']:ranch[key]+=int(current[key])
            current=None
    parser.StartElementHandler=start;parser.EndElementHandler=end;parser.CharacterDataHandler=lambda _:None
    parser.StartDoctypeDeclHandler=forbidden;parser.EntityDeclHandler=forbidden;parser.ExternalEntityRefHandler=forbidden
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(65536),b''):parser.Parse(chunk,False)
    parser.Parse(b'',True);need(suites and current is None,'XML_INCOMPLETE')
    return {'suiteCounters':{k:sum(s[k] for s in suites) for k in ['tests','failures','errors','skipped']},'ranchObservedCounts':ranch}
def finalize(repo):
    out=destination(repo);header=read(out/'source-proof.json');observations=identity(repo)
    inventory=[];main={'tests':0,'failures':0,'errors':0,'skipped':0};ranch=dict(main);total=0;parse_errors=0
    files=sorted((repo/'backend/build/test-results').glob('**/TEST-*.xml'))
    need(len(files)<=50000,'XML_COUNT_BOUND')
    for path in files:
        rel=path.relative_to(repo/'backend/build/test-results');need(len(rel.parts)==2 and re.fullmatch('[A-Za-z][A-Za-z0-9_]{0,100}',rel.parts[0]) is not None,'XML_TASK_DIRECTORY')
        total+=path.stat().st_size;need(total<=2*1024**3,'XML_TOTAL_BOUND')
        row={'task':rel.parts[0],'bytes':path.stat().st_size,'sha256':sha(path),'relativePathSha256':digest(str(rel).encode())}
        try:
            values=parse_xml(path);row.update(values,parseStatus='PARSED_CHARACTER_DATA_DISCARDED')
            if row['task']=='test':
                for key in main:main[key]+=values['suiteCounters'][key];ranch[key]+=values['ranchObservedCounts'][key]
        except Exception:row['parseStatus']='REJECTED';parse_errors+=1
        inventory.append(row)
    maybe=lambda name:read(out/name) if (out/name).is_file() else None
    state=maybe('test-task-state.json');counts=maybe('root-test-counts.json');exit_record=maybe('test-exit.json');compile_record=maybe('compile-exit.json')
    cache_or_no_work=None if state is None else (state['skipped'] or not state['didWork'])
    executed=state is not None and not cache_or_no_work and counts is not None and counts['testCount']>0
    dump_metadata=[]
    for path in sorted((repo/'backend/build/heap-dumps').glob('java_pid*.hprof')):
        if re.fullmatch('java_pid[0-9]+\\.hprof',path.name) and not path.is_symlink():dump_metadata.append({'bytes':path.stat().st_size})
    joined=None if counts is None or parse_errors else (counts['testCount']==main['tests'] and counts['failedTestCount']==main['failures']+main['errors'] and counts['skippedTestCount']==main['skipped'])
    report={'schema':'shard0-cache5-diagnostic-result-v1','scope':'DIAGNOSTIC_SINGLE_VARIABLE_CACHE5_NOT_NORMAL_CI',
        'sourceCommit':HEAD,'sourceTree':TREE,'runId':header['runId'],'runAttempt':header['runAttempt'],
        'actualCompileExit':compile_record['actualExit'] if compile_record else None,'actualTestExit':exit_record['actualExit'] if exit_record else None,
        'effectiveConfiguration':maybe('effective-test-configuration.json'),'taskState':state,
        'observedWorkerProgress':maybe('observed-worker-progress.json'),'rootTestCounts':counts,
        'actualTestExecutionObserved':executed,'fromCacheOrUpToDateOrNoWork':cache_or_no_work,
        'testExitStillMissing':exit_record is None,'rootCompletionStillMissing':counts is None,
        'mainTestXmlFiles':sum(r['task']=='test' for r in inventory),'mainTestXmlCounters':main,
        'mainRootAndXmlCountsMatch':joined,
        'ranchNamespaceObservedCounts':ranch,'ranchNamespaceSkipZero':ranch['skipped']==0 if ranch['tests']>0 else None,
        'junitInventory':inventory,'xmlParseRejectionCount':parse_errors,'heapDumpMetadataOnly':dump_metadata,
        'sourceUnchanged':observations==header['sourcePhysicalPins'],'rawLogsUploaded':False,
        'intentionalSkipsPreserved':True,'hypothesisOnly':True,'rootCauseProven':False,
        'normalCiSuccessInherited':False,'sourceOrAcAcceptanceEvaluated':False,'wholeAcceptanceGreen':False,'productGreen':False}
    save(out/'diagnostic-result.json',report)
    print(json.dumps({'phase':'DIAGNOSTIC_RECORDED','actualTestExecutionObserved':executed,'testExitStillMissing':report['testExitStillMissing'],'mainTestXmlFiles':report['mainTestXmlFiles'],'normalCiSuccessInherited':False,'productGreen':False}))
def main():
    ap=Parser();ap.add_argument('action',choices=['prepare','run','finalize']);ap.add_argument('--repo-root',type=Path,required=True)
    ap.add_argument('--harness-root',type=Path);ap.add_argument('--workflow-sha');ap.add_argument('--workflow-ref');ap.add_argument('--event-name');ap.add_argument('--run-id',type=int);ap.add_argument('--run-attempt',type=int)
    ap.add_argument('--phase',choices=['compile','test'])
    argv=sys.argv[1:];cut=argv.index('--') if '--' in argv else len(argv)
    args=ap.parse_args(argv[:cut]);args.command=argv[cut+1:] if cut<len(argv) else []
    repo=args.repo_root.resolve(strict=True);need(repo.is_dir() and not repo.is_symlink(),'REPO_ROOT')
    if args.action=='prepare':prepare(args,repo);return 0
    if args.action=='run':need(args.phase is not None,'PHASE_REQUIRED');return run(args,repo)
    finalize(repo);return 0
if __name__=='__main__':
    try:raise SystemExit(main())
    except Refused as error:print(json.dumps({'status':'REFUSED','reason':str(error),'rawTextEmitted':False}));raise SystemExit(2)
    except Exception:print('{"status":"REFUSED","reason":"FINITE_EVIDENCE_FAILURE","rawTextEmitted":false}');raise SystemExit(2)
