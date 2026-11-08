"""固定bridge境界の有限試験。実Dockerは起動しない。"""
import importlib.util
import pathlib
import unittest
import sys
sys.dont_write_bytecode=True
SELF_OWNER='f6efb6d3-51e1-4408-a760-92f7d3642122'
spec = importlib.util.spec_from_file_location('bridge', pathlib.Path(__file__).with_name('m21-wsl-docker-bridge.py'))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)

class BoundaryTests(unittest.TestCase):
    def test_admission_and_replay(self):
        p = b.Protocol('11111111-1111-4111-8111-111111111111', 100, owner=SELF_OWNER)
        p.initialize(dict(type='init', owner=SELF_OWNER, run=p.run, global_offset_ms=1000, work_offset_ms=800), 100)
        r = dict(type='request', owner=SELF_OWNER, run=p.run, request_id=1, operation='daemon-info', resource='daemon', deadline_offset_ms=200,phase='cleanup')
        self.assertEqual(p.request(r, 150)[1], 200000100)
        with self.assertRaises(b.Rejected): p.request(r, 150)
    def test_unknown_input_and_secret_api(self):
        for extra in ({'argv': []}, {'env': {}}, {'path':'/tmp'}, {'operation':'exec'}):
            p = b.Protocol('11111111-1111-4111-8111-111111111111', 100, owner=SELF_OWNER)
            p.initialize(dict(type='init', owner=SELF_OWNER, run=p.run, global_offset_ms=1000, work_offset_ms=800), 100)
            r = dict(type='request', owner=SELF_OWNER, run=p.run, request_id=1, operation='daemon-info', resource='daemon', deadline_offset_ms=200,phase='cleanup')
            with self.assertRaises(b.Rejected): p.request(r | extra, 150)
    def test_zero_null_bool_and_expiry(self):
        for value in (0, None, '', True):
            with self.assertRaises(b.Rejected): b.integer(value,1,3000000)
        p = b.Protocol('11111111-1111-4111-8111-111111111111',100, owner=SELF_OWNER)
        with self.assertRaises(b.Rejected): p.initialize(dict(type='init',owner=SELF_OWNER,run=p.run,global_offset_ms=1,work_offset_ms=1),2000000)
    def test_exact_resource(self):
        self.assertEqual(b.resource_name('11111111-1111-4111-8111-111111111111','mysql'),'m21-11111111111141118111111111111111-mysql')
        with self.assertRaises(b.Rejected): b.resource_name('11111111-1111-4111-8111-111111111111','../foreign')



class LinuxFiniteTests(unittest.TestCase):
    def run_case(self, code, cancel=False, child_env=None):
        import json, os, select, signal, time
        if sys.platform != 'linux':
            self.skipTest('Linux実子の証明はWindowsでは代替しない')
        control_r, control_w = os.pipe2(os.O_CLOEXEC)
        receipt_r, receipt_w = os.pipe2(os.O_CLOEXEC)
        pid = os.fork()
        if pid == 0:
            os.close(control_w); os.close(receipt_r)
            try:
                result = b.supervise(['/usr/bin/python3','-I','-c',code],
                                          child_env if child_env is not None else {'PATH':'/usr/bin:/bin','LANG':'C'},
                                          time.monotonic_ns()+200000000,control_r,time.monotonic_ns()+2200000000)
                os.write(receipt_w,json.dumps(result).encode())
                os._exit(0)
            except BaseException:
                os._exit(91)
        os.close(control_r); os.close(receipt_w)
        if cancel:
            os.close(control_w)
            control_w = None
        pidfd = os.pidfd_open(pid)
        try:
            if not select.select([receipt_r],[],[],4)[0]:
                signal.pidfd_send_signal(pidfd,signal.SIGKILL)
                self.fail('owned watchdog result deadline')
            raw=bytearray()
            limit=time.monotonic()+4
            while time.monotonic()<limit:
                self.assertTrue(select.select([receipt_r],[],[],max(0,limit-time.monotonic()))[0])
                chunk=os.read(receipt_r,8192)
                if not chunk: break
                raw.extend(chunk)
                self.assertLessEqual(len(raw),65536)
            else: self.fail('private fixture EOF deadline')
            _, status = os.waitpid(pid,0)
            self.assertEqual(os.waitstatus_to_exitcode(status),0)
            return json.loads(raw)
        finally:
            os.close(pidfd); os.close(receipt_r)
            if control_w is not None: os.close(control_w)

    def test_immediate_exit(self):
        r = self.run_case('pass')
        self.assertTrue(r['green'])
        self.assertEqual(r['cli_exit'],0)
        self.assertTrue(r['children_reaped'])
        self.assertTrue(r['stdout_eof'] and r['stderr_eof'])

    def test_hang_is_forced_once_and_reaped(self):
        r = self.run_case('import time; time.sleep(60)')
        self.assertFalse(r['green'])
        self.assertEqual(r['force_count'],1)
        self.assertEqual(r['cli_exit'],-9)
        self.assertTrue(r['children_reaped'])

    def test_grandchild_closing_pipes_still_requires_reap(self):
        code = 'import os,time; p=os.fork(); (os.close(1),os.close(2),time.sleep(60)) if p==0 else None'
        r = self.run_case(code)
        self.assertTrue(r['children_reaped'])
        self.assertGreaterEqual(len(r['reaped_pids']),2)
        self.assertFalse(r['green'])
        self.assertTrue(r['stdout_eof'] and r['stderr_eof'])

    def test_grandchild_holding_pipes_is_reaped(self):
        r = self.run_case('import os,time; p=os.fork(); time.sleep(60) if p==0 else None')
        self.assertTrue(r['children_reaped'])
        self.assertGreaterEqual(len(r['reaped_pids']),2)
        self.assertFalse(r['green'])
        self.assertTrue(r['stdout_eof'] and r['stderr_eof'])

    def test_stderr_nonzero_is_not_green(self):
        r=self.run_case('import os,sys; os.close(1); sys.stderr.write("OWNED"); sys.exit(7)')
        self.assertFalse(r['green']); self.assertEqual(r['cli_exit'],7)
        self.assertTrue(r['physical_completed'])
        self.assertTrue(r['primary_stdout_eof'] and r['primary_stderr_eof'])

    def test_output_full_is_finite_and_not_green(self):
        r=self.run_case('import os; os.write(1,b"x"*32768)')
        self.assertFalse(r['green'])
        self.assertTrue(r['children_reaped'])
        self.assertTrue(r['stdout_eof'] and r['stderr_eof'])

    def test_secret_sentinel_reaches_only_limited_child_env(self):
        import hashlib,json
        secret='M21_OWNED_SENTINEL_APP'
        boundary=b.DockerBoundary('11111111-1111-4111-8111-111111111111','/owned/config',{'daemon_id':'fixture','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64}, owner=SELF_OWNER)
        argv,env=boundary.command('container-create','mysql',{'mysql_root_password':'M21_OWNED_SENTINEL_ROOT','mysql_password':secret})
        expected=hashlib.sha256(secret.encode()).hexdigest()
        code='import hashlib,os; print(hashlib.sha256(os.environ["MYSQL_PASSWORD"].encode()).hexdigest()=="'+expected+'" and (set(os.environ)-{"LC_CTYPE"})=={"PATH","LANG","MYSQL_ROOT_PASSWORD","MYSQL_PASSWORD"})'
        r=self.run_case(code,child_env=env)
        self.assertTrue(r['green'])
        self.assertEqual(bytes.fromhex(r['_stdout_hex']).strip(),b'True')
        self.assertNotIn(secret,' '.join(argv))
        self.assertNotIn(secret,json.dumps(r))

    def test_bridge_control_eof_does_not_abandon_child(self):
        r = self.run_case('import time; time.sleep(60)',cancel=True)
        self.assertFalse(r['green'])
        self.assertTrue(r['children_reaped'])
        self.assertEqual(r['force_count'],1)



class FixedDockerTests(unittest.TestCase):
    def test_fixed_docker_identity_and_secret_argv(self):
        d = b.DockerBoundary('11111111-1111-4111-8111-111111111111','/owned/config',
                             {'daemon_id':'daemon:local','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64}, owner=SELF_OWNER)
        argv, env = d.command('container-create','mysql',{'mysql_root_password':'OWNED_SENTINEL','mysql_password':'APP_SENTINEL'})
        self.assertNotIn('OWNED_SENTINEL',' '.join(argv))
        self.assertEqual(env['MYSQL_ROOT_PASSWORD'],'OWNED_SENTINEL')
        self.assertEqual(set(env),{'PATH','LANG','MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD'})
        self.assertIn('127.0.0.1::3306',argv)
        self.assertIn('--pull=never',argv)
        self.assertNotIn('HOME',env)

    def test_daemon_change_and_nonzero_are_not_absence(self):
        d = b.DockerBoundary('11111111-1111-4111-8111-111111111111','/owned/config',
                             {'daemon_id':'daemon:local','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64}, owner=SELF_OWNER)
        with self.assertRaises(b.Rejected): d.adopt('daemon-info','daemon',0,b'other-daemon\n')
        with self.assertRaises(b.Rejected): d.adopt('resources-absent','mysql',1,b'')
        d.daemon_confirmed=True
        missing=('error: no such object: '+b.resource_name(d.run,'mysql')+'\nerror: no such object: '+b.resource_name(d.run,'mysql')+'-data\n').encode()
        candidate=d.adopt('resources-absent','mysql',1,b'\n',missing)
        self.assertTrue(candidate['absence_candidate']); self.assertFalse(candidate['absent'])
        self.assertEqual(len(d.adopt('daemon-info','daemon',0,b'daemon:local\n')['confirmed_absent_resources']),1)

    @unittest.skipUnless(sys.platform == 'linux', 'Linux pipe EOFを実測する')
    def test_frame_partial_eof_is_terminal(self):
        import os,time
        r,w = os.pipe()
        os.write(w,b'\x00\x00'); os.close(w)
        try:
            with self.assertRaises(b.Rejected): b.read_frame(r,time.monotonic_ns()+100000000)
        finally: os.close(r)



@unittest.skipUnless(sys.platform=='linux','Linux bridge実子を実測する')
class ResidentFiniteTests(unittest.TestCase):
    def test_expired_global_has_zero_fork(self):
        import os,time
        from unittest.mock import patch
        r,w=os.pipe()
        try:
            with patch.object(os,'fork',side_effect=AssertionError('fork after expiry')):
                with self.assertRaises(b.Rejected):
                    b.supervise(['/usr/bin/python3'],{},time.monotonic_ns()-1,r,time.monotonic_ns()-1)
        finally: os.close(r); os.close(w)

    def resident_case(self,mode):
        import json,os,select,signal,struct,tempfile,time
        from unittest.mock import patch
        owner=SELF_OWNER; run='11111111-1111-4111-8111-111111111111'
        descriptor={'daemon_id':'fixture-daemon','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64}
        with tempfile.TemporaryDirectory(prefix='m21-bridge-fixture-') as directory:
            dfd=os.open(directory,os.O_RDONLY|os.O_DIRECTORY)
            r,w=os.pipe2(os.O_CLOEXEC)
            pid=os.fork()
            if pid==0:
                os.close(w); os.dup2(r,0); os.close(r)
                try:
                    with patch.object(b,'runtime_identity',return_value={'boot_id':'fixture','pid_namespace':'pid:[fixture]','socket_dev':1,'socket_ino':1}), patch.object(b.Path,'read_bytes',return_value=b'fixture-tool'), patch.object(b,'preflight',return_value=descriptor), patch.object(b.DockerBoundary,'command',return_value=(['/usr/bin/python3','-I','-c','import time; time.sleep(60)'],{'PATH':'/usr/bin:/bin','LANG':'C'})):
                        code=b.bridge_loop(dfd,directory,owner,run,'a'*32,'0'*40)
                    os._exit(code)
                except BaseException: os._exit(92)
            os.close(r); os.close(dfd)
            pidfd=os.pidfd_open(pid)
            def wait_file(name,limit):
                path=pathlib.Path(directory)/name
                while time.monotonic()<limit:
                    if path.exists(): return json.loads(path.read_text())
                    select.select([],[],[],.002)
                self.fail('owned receipt deadline '+name)
            def send(value):
                raw=json.dumps(value).encode(); os.write(w,struct.pack('!I',len(raw))+raw)
            limit=time.monotonic()+4
            ready=wait_file('ready.json',limit)
            self.assertEqual(ready['owner'],owner)
            if mode=='partial':
                os.write(w,b'\x00\x00')
            elif mode!='no-init':
                send(dict(type='init',owner=owner,run=run,global_offset_ms=1800,work_offset_ms=1500))
                wait_file('initialized.json',limit)
                if mode!='bind-timeout':
                    send(dict(type='bind',owner=owner,run=run,descriptor={} if mode=='bind-descriptor' else descriptor))
                    if mode!='bind-descriptor': wait_file('bound.json',limit)
                if mode=='active-eof':
                    elapsed=(time.monotonic_ns()-ready['t0_ns'])//1000000
                    send(dict(type='request',owner=owner,run=run,request_id=1,phase='cleanup',operation='daemon-info',resource='daemon',deadline_offset_ms=elapsed+400))
            if mode not in ('no-init','bind-timeout'): os.close(w); w=None
            if not select.select([pidfd],[],[],4)[0]:
                signal.pidfd_send_signal(pidfd,signal.SIGKILL)
                os.waitpid(pid,0)
                self.fail('owned bridge exit deadline')
            _,status=os.waitpid(pid,0)
            if w is not None: os.close(w)
            os.close(pidfd)
            self.assertNotEqual(os.waitstatus_to_exitcode(status),0)
            receipt=wait_file('bridge-exit.json',limit)
            self.assertFalse(receipt['green'])
            self.assertFalse(receipt['linux_watchdog_retained'])
            self.assertFalse(receipt['windows_actual_exit_confirmed'])
            if mode in ('bind-timeout','bind-descriptor'):
                self.assertEqual(receipt['reason'],'bridge-terminal')
                self.assertEqual(receipt['terminal_phase'],'bind-read' if mode=='bind-timeout' else 'bind-validation')
                self.assertEqual(receipt['terminal_code'],'frame-timeout' if mode=='bind-timeout' else 'descriptor-bind')
                self.assertLessEqual(receipt['phase_started_ns'],receipt['terminal_ns'])
                self.assertLessEqual(receipt['phase_deadline_ns'],ready['t0_ns']+1500000000)
                self.assertEqual(receipt['terminal_watchdog'],None)
                self.assertNotIn('fixture-daemon',json.dumps(receipt.get('terminal_code')))
            if mode=='active-eof':
                linux=wait_file('request-00001.json',limit)['linux']
                self.assertTrue(linux['children_reaped'])
                self.assertTrue(linux['stdout_eof'] and linux['stderr_eof'])

    def test_idle_parent_eof(self): self.resident_case('idle-eof')
    def test_partial_parent_eof(self): self.resident_case('partial')
    def test_active_parent_eof(self): self.resident_case('active-eof')
    def test_initial_handshake_deadline(self): self.resident_case('no-init')
    def test_bind_deadline_diagnostic(self): self.resident_case('bind-timeout')
    def test_bind_descriptor_diagnostic(self):
        self.resident_case('bind-descriptor')
        self.assertEqual(b.terminal_diagnostic_code(b.Rejected('secret-arbitrary-value')),'other-rejected')
        self.assertEqual(b.terminal_diagnostic_code(b.Rejected('schema')),'schema')
        self.assertEqual(b.terminal_diagnostic_code(OSError('secret-arbitrary-value')),'os-error')



@unittest.skipUnless(sys.platform=='linux' and pathlib.Path('/mnt/c/Users/kenta/AppData/Local/Temp').is_dir(),'WSL NTFS実測専用')
class NtfsReceiptFiniteTests(unittest.TestCase):
    def test_guarded_ntfs_receipt_create_publish_read(self):
        import hashlib,json,os,uuid
        run=str(uuid.uuid4()); nonce=uuid.uuid4().hex
        root=b.TEMP_ROOT/('m21-windows-bridge-fixture-'+uuid.uuid4().hex)
        child=root/'bridge'
        os.mkdir(root,0o700); os.mkdir(child,0o700)
        owned={'owner':SELF_OWNER,'nonce':nonce}
        def fresh(path,value):
            fd=os.open(str(path),os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
            with os.fdopen(fd,'w') as out: json.dump(value,out)
        fresh(root/'owner.json',owned)
        fresh(child/'owner.json',owned|{'run':run,'purpose':'m21-docker-bridge'})
        dfd=b.guard_run(str(child),SELF_OWNER,run,nonce)
        identity=os.fstat(dfd)
        value=owned|{'run':run,'source_sha256':hashlib.sha256(b.Path(b.__file__).read_bytes()).hexdigest(),'case':'ntfs-receipt-once'}
        try:
            b.publish(dfd,'fixture.json',value)
            self.assertEqual(b.read_owned_json(dfd,'fixture.json'),value)
            with self.assertRaises(FileExistsError): b.publish(dfd,'fixture.json',value)
            print(json.dumps({'case':'ntfs-receipt-once','owner':SELF_OWNER,'run':run,'nonce':nonce,'source_sha256':value['source_sha256'],'dev':identity.st_dev,'ino':identity.st_ino,'create_new':True,'atomic_public':True,'directory_fsync':True,'read_exact':True}))
        finally:
            current=os.stat(child,follow_symlinks=False)
            self.assertEqual((current.st_dev,current.st_ino),(identity.st_dev,identity.st_ino))
            for name in ('fixture.json.pending','fixture.json','owner.json'):
                try: os.unlink(name,dir_fd=dfd)
                except FileNotFoundError: pass
            os.close(dfd)
            os.rmdir(child); os.unlink(root/'owner.json'); os.rmdir(root)



@unittest.skipUnless(sys.platform=='linux','Linux owned descendant実測')
class LossFiniteTests(unittest.TestCase):
    def test_bridge_sigkill_watchdog_is_independent(self):
        import json,os,select,signal,tempfile,time
        b.subreaper()
        with tempfile.TemporaryDirectory(prefix='m21-loss-fixture-') as directory:
            dfd=os.open(directory,os.O_RDONLY|os.O_DIRECTORY)
            report_r,report_w=os.pipe2(os.O_CLOEXEC)
            bridge_pid=os.fork()
            if bridge_pid==0:
                os.close(report_r)
                try:
                    request={'request_id':1,'operation':'daemon-info','resource':'daemon','phase':'cleanup'}
                    active=b.spawn_watchdog(['/usr/bin/python3','-I','-c','import time; time.sleep(60)'],{'PATH':'/usr/bin:/bin','LANG':'C'},time.monotonic_ns()+500000000,time.monotonic_ns()+2500000000,dfd,{'owner':SELF_OWNER,'run':'11111111-1111-4111-8111-111111111111'},request)
                    os.write(report_w,json.dumps({'watchdog_pid':active['pid']}).encode())
                    select.select([],[],[],60)
                finally: os._exit(90)
            os.close(report_w); os.close(dfd)
            bridge_fd=os.pidfd_open(bridge_pid)
            self.assertTrue(select.select([report_r],[],[],1)[0])
            known=json.loads(os.read(report_r,1024)); os.close(report_r)
            watchdog_fd=os.pidfd_open(known['watchdog_pid'])
            signal.pidfd_send_signal(bridge_fd,signal.SIGKILL)
            os.waitpid(bridge_pid,0); os.close(bridge_fd)
            self.assertTrue(select.select([watchdog_fd],[],[],3)[0])
            _,status=os.waitpid(known['watchdog_pid'],0); os.close(watchdog_fd)
            self.assertEqual(os.waitstatus_to_exitcode(status),0)
            receipt=json.loads((pathlib.Path(directory)/'request-00001.json').read_text())
            self.assertTrue(receipt['linux']['children_reaped'])
            self.assertTrue(receipt['linux']['stdout_eof'] and receipt['linux']['stderr_eof'])
            self.assertFalse(receipt['linux']['green'])

    def test_known_escape_is_retained_then_fixture_owner_reaps(self):
        import json,os,select,signal,time
        b.subreaper()
        r,w=os.pipe2(os.O_CLOEXEC); result_r,result_w=os.pipe2(os.O_CLOEXEC)
        watchdog=os.fork()
        if watchdog==0:
            os.close(w); os.close(result_r)
            code='import os,time; p=os.fork(); (os.setsid(),os.close(2),print(os.getpid(),flush=True),os.close(1),time.sleep(60)) if p==0 else None'
            result=b.supervise(['/usr/bin/python3','-I','-c',code],{'PATH':'/usr/bin:/bin','LANG':'C'},time.monotonic_ns()+200000000,r,time.monotonic_ns()+2500000000)
            os.write(result_w,json.dumps(result).encode()); os._exit(0)
        os.close(r); os.close(result_w)
        self.assertTrue(select.select([result_r],[],[],3)[0])
        result=json.loads(os.read(result_r,16384)); os.close(result_r); os.close(w)
        os.waitpid(watchdog,0)
        known_pid=int(bytes.fromhex(result['_stdout_hex']).decode().strip())
        self.assertFalse(result['green']); self.assertFalse(result['children_reaped'])
        # 固定fixture自身が通知した既知childだけ。prodにescape kill機構を足さない。
        known_fd=os.pidfd_open(known_pid)
        signal.pidfd_send_signal(known_fd,signal.SIGKILL)
        self.assertTrue(select.select([known_fd],[],[],1)[0])
        os.waitpid(known_pid,0); os.close(known_fd)


def relay_fixture():
    import argparse,os
    from unittest.mock import patch
    parser=argparse.ArgumentParser()
    parser.add_argument('--relay-fixture',action='store_true')
    for name in ('run-directory','owner','run','nonce','input-commit'): parser.add_argument('--'+name,required=True)
    args=parser.parse_args()
    dfd=b.guard_run(args.run_directory,args.owner,args.run,args.nonce)
    descriptor={'daemon_id':'fixture-daemon','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64}
    try:
        with patch.object(b,'preflight',return_value=descriptor), patch.object(b.DockerBoundary,'command',return_value=(['/usr/bin/python3','-I','-c','import time; time.sleep(60)'],{'PATH':'/usr/bin:/bin','LANG':'C'})):
            return b.bridge_loop(dfd,args.run_directory,args.owner,args.run,args.nonce,args.input_commit)
    finally: os.close(dfd)



class InspectContractTests(unittest.TestCase):
    def test_created_then_started_mysql_freezes_only_published_loopback(self):
        import json
        run='11111111-1111-4111-8111-111111111111'
        d=b.DockerBoundary(run,'/owned/config',{'daemon_id':'fixture','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64}, owner=SELF_OWNER)
        v={'id':'c'*64,'labels':{'m21.owner':SELF_OWNER,'m21.run':run,'m21.purpose':'mysql'},'image':'sha256:'+'a'*64,'ports':{},'bindings':{'3306/tcp':[{'HostIp':'127.0.0.1','HostPort':''}]},'mounts':[{'Type':'volume','Name':b.resource_name(run,'mysql')+'-data','Destination':'/var/lib/mysql','RW':True}],'running':False}
        d.adopt('container-inspect','mysql',0,json.dumps(v).encode())
        self.assertNotIn('mysql',d.ports)
        v['running']=True
        v['ports']={'3306/tcp':[{'HostIp':'127.0.0.1','HostPort':'45123'}],'33060/tcp':None}
        d.adopt('container-inspect','mysql',0,json.dumps(v).encode())
        self.assertEqual(d.ports['mysql']['HostPort'],'45123')
        v['ports']['3306/tcp'][0]['HostIp']='0.0.0.0'
        with self.assertRaises(b.Rejected): d.adopt('container-inspect','mysql',0,json.dumps(v).encode())

    def test_cleanup_count_and_work_deadline(self):
        p=b.Protocol('11111111-1111-4111-8111-111111111111',100, owner=SELF_OWNER)
        p.initialize(dict(type='init',owner=SELF_OWNER,run=p.run,global_offset_ms=1000,work_offset_ms=100),100)
        for index in range(1,15):
            p.request(dict(type='request',owner=SELF_OWNER,run=p.run,request_id=index,phase='cleanup',operation='daemon-info',resource='daemon',deadline_offset_ms=500),200000000)
        with self.assertRaises(b.Rejected): p.request(dict(type='request',owner=SELF_OWNER,run=p.run,request_id=15,phase='cleanup',operation='daemon-info',resource='daemon',deadline_offset_ms=500),200000000)
        with self.assertRaises(b.Rejected): p.request(dict(type='request',owner=SELF_OWNER,run=p.run,request_id=15,phase='work',operation='daemon-info',resource='daemon',deadline_offset_ms=500),200000000)



def readonly_diagnostic_child(argv,env,body_deadline,global_deadline,control_r,control_w,result_r,result_w):
    """移管controlはsuperviseが閉じる。子はcleanup異常でも親処理へ戻さない。"""
    import os
    code=1
    try:
        os.close(control_w); os.close(result_r)
        result=b.supervise(argv,env,body_deadline,control_r,global_deadline)
        if b.send_private(result_w,result,global_deadline): code=0
    except BaseException:
        # 子境界での失敗は必ず非0。例外をunittestへ再送しない。
        code=1
    finally:
        try: os.close(result_w)
        except BaseException: code=1
        finally: os._exit(code)


def collect_readonly_result(pid,result_r,control_w,body_deadline,global_deadline):
    """readonly診断の保持forkだけを同一G内で観測し、未確認を保持する。"""
    import hashlib,json,os,select,time
    data=bytearray(); eof=False; reaped=False; exit_code=None; error=None
    try:
        try: os.set_blocking(result_r,False)
        except OSError: error='result-read-error'
        while time.monotonic_ns()<global_deadline:
            now=time.monotonic_ns()
            if control_w is not None and (now>=body_deadline or eof or error):
                os.close(control_w); control_w=None
            if not reaped:
                try:
                    waited,status=os.waitpid(pid,os.WNOHANG)
                    if waited: reaped=True; exit_code=os.waitstatus_to_exitcode(status)
                except OSError: error='wait-error'
            if reaped and (eof or error): break
            remaining=(global_deadline-time.monotonic_ns())/1e9
            if remaining<=0: break
            pause=min(remaining,.001 if eof or error else .01)
            if control_w is not None: pause=min(pause,max(0,(body_deadline-time.monotonic_ns())/1e9))
            try:
                ready=select.select([] if eof or error else [result_r],[],[],pause)[0]
                if ready:
                    chunk=os.read(result_r,min(4096,65537-len(data)))
                    if not chunk: eof=True
                    else:
                        data.extend(chunk)
                        if len(data)>65536: error='result-length'
            except BlockingIOError: pass
            except OSError: error='result-read-error'
    finally:
        if control_w is not None: os.close(control_w)
        os.close(result_r)
    result=None
    if error is None:
        try:
            value=json.loads(data)
            if type(value) is dict: result=value
        except (ValueError,UnicodeError): pass
    complete=result is not None
    observed=time.monotonic_ns()
    green=complete and eof and reaped and exit_code==0 and error is None and observed<=global_deadline
    reason=error or ('completed' if green else 'result-json' if eof and not complete else
                    'supervisor-nonzero' if reaped and exit_code!=0 else
                    'result-timeout' if observed>=global_deadline else 'result-unconfirmed')
    return {'result':result,'result_complete':complete,'result_eof':eof,
            'supervisor_actual_exit':exit_code,'supervisor_reaped':reaped,
            'cli_children_reaped':result.get('children_reaped') is True if complete else None,
            'transport_green':green,'transport_error':error,'transport_reason':reason,'result_bytes':len(data),
            'result_sha256':hashlib.sha256(data).hexdigest(),'observed_ns':observed,
            'body_deadline_ns':body_deadline,'global_deadline_ns':global_deadline}


@unittest.skipUnless(sys.platform=='linux','Linux所有forkの有限診断受信試験')
class ReadonlyDiagnosticTransportTests(unittest.TestCase):
    def test_actual_child_branch_closes_transferred_fd_without_fallthrough(self):
        import errno,json,os,select,signal,time
        from unittest.mock import patch
        for close_failure in (False,True):
            with self.subTest(result_close_failure=close_failure):
                control_r,control_w=os.pipe2(os.O_CLOEXEC); result_r,result_w=os.pipe2(os.O_CLOEXEC)
                sentinel_r,sentinel_w=os.pipe2(os.O_CLOEXEC)
                close=os.close; began=time.monotonic_ns(); deadline=began+500000000
                def supervise(argv,env,body,control,global_end):
                    close(control)
                    return {'children_reaped':True,'physical_completed':True,'cli_exit':0}
                def closing(fd):
                    if close_failure and fd==result_w: raise OSError(errno.EIO,'fixed cleanup fault')
                    close(fd)
                pid=os.fork()
                if pid==0:
                    close(sentinel_r)
                    try:
                        with patch.object(b,'supervise',side_effect=supervise),patch.object(os,'close',side_effect=closing):
                            readonly_diagnostic_child([],{},began+300000000,deadline,control_r,control_w,result_r,result_w)
                        os.write(sentinel_w,b'child-returned')
                    except BaseException:
                        os.write(sentinel_w,b'child-fallthrough')
                    finally: os._exit(93)
                close(control_r); close(result_w); close(sentinel_w)
                observed=None
                try:
                    observed=collect_readonly_result(pid,result_r,control_w,began+300000000,deadline)
                    self.assertTrue(observed['result_complete']); self.assertTrue(observed['result_eof'])
                    self.assertTrue(observed['supervisor_reaped'])
                    os.set_blocking(sentinel_r,False)
                    self.assertEqual(os.read(sentinel_r,64),b'')
                    self.assertEqual(observed['supervisor_actual_exit'],1 if close_failure else 0)
                    self.assertEqual(observed['transport_green'],not close_failure)
                finally:
                    close(sentinel_r)
                    if not observed or not observed['supervisor_reaped']:
                        os.kill(pid,signal.SIGKILL)
                        limit=time.monotonic_ns()+500000000; reaped=False
                        while time.monotonic_ns()<limit:
                            waited,_=os.waitpid(pid,os.WNOHANG)
                            if waited: reaped=True; break
                            select.select([],[],[],.001)
                        self.assertTrue(reaped,'owned branch fixture cleanup unconfirmed')

    def transport_case(self,partial):
        import json,os,select,signal,time,uuid
        collector=collect_readonly_result
        run=str(uuid.uuid4()); nonce=uuid.uuid4().hex
        control_r,control_w=os.pipe2(os.O_CLOEXEC)
        result_r,result_w=os.pipe2(os.O_CLOEXEC)
        began=time.monotonic_ns(); deadline=began+200000000
        pid=os.fork()
        if pid==0:
            os.close(control_w); os.close(result_r)
            if partial:
                os.write(result_w,b'{"children_reaped":')
                os.close(result_w)
                select.select([control_r],[],[],1)
            else:
                os.write(result_w,json.dumps({'children_reaped':True}).encode())
                select.select([],[],[],2)
            os._exit(0)
        os.close(control_r); os.close(result_w)
        observed=None; cleanup_reaped=False
        try:
            observed=collector(pid,result_r,control_w,began+100000000,deadline)
            self.assertFalse(observed['transport_green'])
            self.assertEqual(observed['result_complete'],not partial)
            self.assertEqual(observed['result_eof'],partial)
            self.assertEqual(observed['supervisor_reaped'],partial)
            self.assertEqual(observed['supervisor_actual_exit'],0 if partial else None)
            self.assertEqual(observed['transport_reason'],'result-json' if partial else 'result-timeout')
            self.assertLess(observed['observed_ns'],deadline+100000000)
        finally:
            if not observed or not observed['supervisor_reaped']:
                # 診断Gの後処理。保持した自分の未reap dummyだけを別枠で回収する。
                os.kill(pid,signal.SIGKILL)
                cleanup_deadline=time.monotonic_ns()+500000000
                while time.monotonic_ns()<cleanup_deadline:
                    waited,_=os.waitpid(pid,os.WNOHANG)
                    if waited: cleanup_reaped=True; break
                    select.select([],[],[],.001)
                self.assertTrue(cleanup_reaped,'owned dummy cleanup unconfirmed')
        if not partial:
            self.assertTrue(cleanup_reaped)
            self.assertFalse(observed['supervisor_reaped'])
        print(json.dumps({'case':'readonly-transport-partial' if partial else 'readonly-transport-no-exit',
                          'owner':SELF_OWNER,'run':run,'nonce':nonce,'body_deadline_ns':began+100000000,
                          'diagnostic_deadline_ns':deadline,'diagnostic_result_complete':observed['result_complete'],
                          'diagnostic_eof':observed['result_eof'],'diagnostic_reaped':observed['supervisor_reaped'],
                          'diagnostic_green':observed['transport_green'],'dummy_cleanup_after_diagnostic':not partial,
                          'dummy_cleanup_reaped':cleanup_reaped if not partial else observed['supervisor_reaped']}))

    def test_partial_json_and_eof_are_separate(self): self.transport_case(True)
    def test_complete_json_without_eof_or_exit_is_unknown(self): self.transport_case(False)


@unittest.skipUnless(sys.platform=='linux' and '--real-readonly-absence-once' in sys.argv,'明示read-only既存daemon試験専用')
class RealDaemonAbsenceFiniteTests(unittest.TestCase):
    def test_exact_missing_object_shape_and_three_second_cap(self):
        import hashlib,json,os,shutil,tempfile,time,uuid
        arguments=sys.argv[1:]
        self.assertEqual(arguments[:2],['--real-readonly-absence-once','--input-commit'])
        self.assertEqual(len(arguments),3)
        self.assertRegex(arguments[2],r'^[0-9a-f]{40}\Z')
        input_commit=arguments[2]
        run=str(uuid.uuid4()); nonce=uuid.uuid4().hex
        cli_sha=hashlib.sha256(pathlib.Path('/usr/bin/docker').read_bytes()).hexdigest()
        self.assertEqual(cli_sha,'2fc3b56f978ff6951845be1e4ce352256c0ce126eeda8b84d5713009da3771ca')
        root=pathlib.Path(tempfile.mkdtemp(prefix='m21-windows-readonly-',dir=str(b.TEMP_ROOT)))
        child=root/'bridge'; records=[]; guarded=False
        evidence={'case':'real-readonly-absence-diagnostic-once','owner':SELF_OWNER,'input_commit':input_commit,
                  'run':run,'nonce':nonce,'source_sha256':hashlib.sha256(b.Path(b.__file__).read_bytes()).hexdigest(),
                  'test_sha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),'docker_sha256':cli_sha,
                  'socket':'unix:///var/run/docker.sock','config_path':str(child/'config'),'run_directory':str(child),
                  'body_cap_ms':3000,'global_cap_ms':5000,'cli_limit':3,'daemon_absence_confirmed':False,
                  'same_daemon_before_after':False,'green':False,'fixture_error':None}
        def public_record(record):
            value={key:value for key,value in record.items() if key!='result'}
            result=record['result']
            if result is not None:
                value['linux']={key:result.get(key) for key in ('reason','cli_exit','children_reaped','stdout_eof','stderr_eof','physical_completed','force_count','body_elapsed_ms','reap_observed_ns','stdout_eof_observed_ns','stderr_eof_observed_ns')}
                for stream in ('stdout','stderr'):
                    raw=bytes.fromhex(result.get('_'+stream+'_hex',''))
                    value[stream+'_len']=len(raw); value[stream+'_sha256']=hashlib.sha256(raw).hexdigest()
            return value
        try:
            child.mkdir()
            owner={'owner':SELF_OWNER,'nonce':nonce}
            with (root/'owner.json').open('x') as f: json.dump(owner,f)
            with (child/'owner.json').open('x') as f: json.dump(owner|{'run':run,'purpose':'m21-docker-bridge'},f)
            dfd=b.guard_run(str(child),SELF_OWNER,run,nonce)
            try: os.mkdir('config',dir_fd=dfd)
            finally: os.close(dfd)
            guarded=True
            boundary=b.DockerBoundary(run,str(child/'config'),{'daemon_id':'syntax-fixture-only','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64}, owner=SELF_OWNER)
            base=['/usr/bin/docker','--config',str(child/'config'),'--host','unix:///var/run/docker.sock']
            def once(operation,argv,env):
                control_r,control_w=os.pipe2(os.O_CLOEXEC); result_r,result_w=os.pipe2(os.O_CLOEXEC)
                began=time.monotonic_ns(); body_deadline=began+3000000000; global_deadline=began+5000000000
                pid=os.fork()
                if pid==0:
                    readonly_diagnostic_child(argv,env,body_deadline,global_deadline,control_r,control_w,result_r,result_w)
                os.close(control_r); os.close(result_w)
                record={'operation':operation,'argv':argv,'supervisor_pid':pid,'began_ns':began,
                        'result':None,'result_complete':False,'result_eof':False,
                        'supervisor_reaped':False,'supervisor_actual_exit':None,'cli_children_reaped':None}
                records.append(record)
                record.update(collect_readonly_result(pid,result_r,control_w,body_deadline,global_deadline))
                record['fixture_elapsed_ms']=(time.monotonic_ns()-began)//1000000
                self.assertTrue(record['transport_green'],'readonly transport unconfirmed')
                self.assertTrue(record['cli_children_reaped'],'readonly CLI children unconfirmed')
                self.assertTrue(record['result'].get('physical_completed'),'readonly CLI completion unconfirmed')
                return record['result']
            before=once('daemon-before',base+['info','--format','{{.ID}}'],{'PATH':'/usr/bin:/bin','LANG':'C'})
            self.assertEqual(before['cli_exit'],0)
            argv,env=boundary.command('resources-absent','mysql')
            result=once('resources-absent',argv,env)
            after=once('daemon-after',base+['info','--format','{{.ID}}'],{'PATH':'/usr/bin:/bin','LANG':'C'})
            stdout=bytes.fromhex(result['_stdout_hex']); stderr=bytes.fromhex(result['_stderr_hex'])
            expected=('error: no such object: '+b.resource_name(run,'mysql')+'\nerror: no such object: '+b.resource_name(run,'mysql')+'-data\n').encode()
            known_stdout={b'':'empty',b'\n':'single-newline',b'[]\n':'json-empty-array-newline',b'[]':'json-empty-array',b'M21_PRESENT\n':'present-sentinel'}
            same_daemon=before['cli_exit']==after['cli_exit']==0 and before['physical_completed'] and after['physical_completed'] and before['_stdout_hex']==after['_stdout_hex'] and bool(bytes.fromhex(before['_stdout_hex']).strip())
            evidence.update(stdout_shape=known_stdout.get(stdout,'unknown'),stderr_line_count=len(stderr.splitlines()),
                            stderr_shape='exact-two-lowercase-error-no-such-object' if stderr==expected else 'unknown',
                            same_daemon_before_after=bool(same_daemon))
            self.assertTrue(result['physical_completed'])
            self.assertEqual(result['cli_exit'],1)
            self.assertEqual(stdout,b'\n')
            self.assertEqual(stderr,expected)
            self.assertTrue(same_daemon)
            evidence['green']=True
        except BaseException as error:
            evidence['fixture_error']='assertion' if isinstance(error,AssertionError) else 'fixture-exception'
            raise
        finally:
            evidence['cli_records']=[public_record(record) for record in records]
            evidence['cli_attempt_count']=len(records)
            evidence['cli_spawn_count_upper_bound']=len(records)
            safe_cleanup=guarded and all(record['transport_green'] and record['supervisor_reaped'] and record['result_eof'] and record['result_complete'] and record['cli_children_reaped'] is True for record in records)
            evidence['temp_retained']=not safe_cleanup
            out=pathlib.Path(__file__).resolve().parent.parent/'.claude'/'campaigns'
            out.mkdir(parents=True,exist_ok=True)
            with (out/('m21-bridge-readonly-'+run+'.json')).open('x') as f: json.dump(evidence,f,sort_keys=True)
            print(json.dumps(evidence,sort_keys=True))
            if safe_cleanup: shutil.rmtree(root)

@unittest.skipUnless(sys.platform=='linux','Linux公開receipt実子試験')
class PublicReceiptFiniteTests(unittest.TestCase):
    def test_public_receipt_bounded_and_secret_free(self):
        import json,os,select,tempfile,time
        with tempfile.TemporaryDirectory(prefix='m21-public-fixture-') as directory:
            dfd=os.open(directory,os.O_RDONLY|os.O_DIRECTORY)
            secret='OWNED_PUBLIC_SENTINEL_MUST_NOT_APPEAR'
            argv=['/usr/bin/python3','-I','-c','import os; print("MYSQL_PASSWORD" in os.environ)']
            active=b.spawn_watchdog(argv,{'PATH':'/usr/bin:/bin','LANG':'C','MYSQL_PASSWORD':secret},time.monotonic_ns()+500000000,time.monotonic_ns()+2500000000,dfd,{'owner':SELF_OWNER,'run':'11111111-1111-4111-8111-111111111111'},{'request_id':1,'phase':'cleanup','operation':'daemon-info','resource':'daemon'})
            os.close(dfd)
            raw=bytearray(); limit=time.monotonic()+3
            while time.monotonic()<limit:
                self.assertTrue(select.select([active['result']],[],[],max(0,limit-time.monotonic()))[0])
                chunk=os.read(active['result'],8192)
                if not chunk: break
                raw.extend(chunk); self.assertLessEqual(len(raw),65536)
            else: self.fail('owned private EOF deadline')
            os.waitpid(active['pid'],0)
            os.close(active['result']); os.close(active['pidfd']); b.close_active_control(active)
            for name in ('request-00001-started.json','request-00001.json'):
                public=(pathlib.Path(directory)/name).read_bytes()
                self.assertLessEqual(len(public),b.MAX_FRAME)
                self.assertNotIn(secret.encode(),public)
                self.assertNotIn(b'_stdout_hex',public)
                self.assertNotIn(b'_stderr_hex',public)
            self.assertTrue(json.loads((pathlib.Path(directory)/'request-00001.json').read_text())['linux']['green'])



@unittest.skipUnless(sys.platform=='linux','Linux frame/clock境界')
class RemainingBoundaryTests(unittest.TestCase):
    def test_oversize_zero_and_duplicate_json_are_terminal(self):
        import os,struct,time
        for raw in (struct.pack('!I',0),struct.pack('!I',b.MAX_FRAME+1),struct.pack('!I',13)+b'{"a":1,"a":2}'):
            r,w=os.pipe(); os.write(w,raw); os.close(w)
            try:
                with self.assertRaises(b.Rejected): b.read_frame(r,time.monotonic_ns()+100000000)
            finally: os.close(r)

    def test_clock_discontinuity_has_zero_watchdog_or_cli_spawn(self):
        from unittest.mock import patch
        with patch.object(b.time,'clock_gettime_ns',return_value=1000000000),patch.object(b.time,'monotonic_ns',return_value=0),patch.object(b.os,'fork',side_effect=AssertionError('spawn after clock change')):
            with self.assertRaises(b.Rejected): b.spawn_watchdog([],{},100,200,0,{'clock_delta':0},{})
            with self.assertRaises(b.Rejected): b.supervise([],{},100,0,200,0)

    def test_subreaper_failure_has_zero_cli_spawn(self):
        from unittest.mock import patch
        import time
        with patch.object(b.os,'setsid',return_value=None),patch.object(b,'subreaper',side_effect=b.Rejected('subreaper-set')),patch.object(b.os,'fork',side_effect=AssertionError('spawn without subreaper')):
            with self.assertRaises(b.Rejected): b.supervise([],{},time.monotonic_ns()+100000000,0,time.monotonic_ns()+200000000)



class ArgumentBoundaryTests(unittest.TestCase):
    def test_unknown_argument_has_no_echo_and_no_run_admission(self):
        import contextlib,io
        from unittest.mock import patch
        arguments=['bridge','--run-directory','/forbidden','--owner',SELF_OWNER,'--run','11111111-1111-4111-8111-111111111111','--nonce','a'*32,'--input-commit','0'*40,'--env','OWNED_ARGUMENT_SENTINEL']
        error=io.StringIO()
        with patch.object(sys,'argv',arguments),patch.object(b,'guard_run',side_effect=AssertionError('unknown argument reached run admission')),contextlib.redirect_stderr(error):
            self.assertEqual(b.main(),78)
        self.assertNotIn('OWNED_ARGUMENT_SENTINEL',error.getvalue())

class FreshOwnershipTests(unittest.TestCase):
    def boundary(self):
        return b.DockerBoundary('11111111-1111-4111-8111-111111111111','/owned/config',{'daemon_id':'fixture','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64},SELF_OWNER)

    def test_fresh_volume_inspection_invalidates_old_ownership(self):
        d=self.boundary(); d.daemon_confirmed=True
        d.inspected[('volume','mysql')]={'name':'old-owned'}
        d.command('volume-inspect','mysql')
        with self.assertRaises(b.Rejected): d.command('volume-remove','mysql')

    def test_daemon_query_invalidates_old_confirmation_until_adopted(self):
        d=self.boundary(); d.daemon_confirmed=True
        d.command('daemon-info','daemon')
        self.assertFalse(d.daemon_confirmed)




class AdmissionSlotTests(unittest.TestCase):
    def test_valid_expired_request_consumes_id_and_slot(self):
        p=b.Protocol('11111111-1111-4111-8111-111111111111',100,SELF_OWNER)
        p.initialize(dict(type='init',owner=SELF_OWNER,run=p.run,global_offset_ms=1000,work_offset_ms=100),100)
        with self.assertRaises(b.Rejected): p.request(dict(type='request',owner=SELF_OWNER,run=p.run,request_id=1,phase='cleanup',operation='daemon-info',resource='daemon',deadline_offset_ms=1),2000000)
        self.assertEqual(p.next_request,2)
        self.assertEqual(p.cleanup_count,1)

@unittest.skipUnless(sys.platform=='linux','Linux回収継続の実子試験')
class RecoveryContinuationFiniteTests(unittest.TestCase):
    def recovery_case(self,cancel_when='after',invalid_cancel=None):
        import hashlib,json,os,select,signal,socket,struct,tempfile,threading,time
        from unittest.mock import patch
        owner=SELF_OWNER; run='11111111-1111-4111-8111-111111111111'
        descriptor={'daemon_id':'fixture-daemon','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64}
        listener=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
        listener.bind(('127.0.0.1',0)); listener.listen(1); listener.settimeout(2)
        port=listener.getsockname()[1]
        received=threading.Event(); release=threading.Event(); server_errors=[]
        def server():
            try:
                connection,_=listener.accept()
                with connection:
                    connection.settimeout(2)
                    command=bytearray()
                    while len(command)<26:
                        part=connection.recv(26-len(command))
                        if not part: raise AssertionError('fixture INFO truncated')
                        command.extend(part)
                    if command!=b'*2\r\n$4\r\nINFO\r\n$6\r\nserver\r\n': raise AssertionError('fixture INFO command')
                    received.set()
                    if not release.wait(2): raise AssertionError('fixture response release')
                    connection.sendall(b'$50\r\nx'); connection.shutdown(socket.SHUT_WR)
                    while connection.recv(256): pass
            except BaseException as error: server_errors.append(type(error).__name__)
            finally: listener.close()
        original_init=b.DockerBoundary.__init__; original_command=b.DockerBoundary.command
        def seeded(boundary,*args,**kwargs):
            original_init(boundary,*args,**kwargs)
            boundary.inspected[('volume','mysql')]={'name':b.resource_name(run,'mysql')+'-data'}
            boundary.valkey_started_id='c'*64
        def command(boundary,operation,role,secrets=None):
            _,env=original_command(boundary,operation,role,secrets)
            if operation=='container-inspect':
                value={'id':'c'*64,'labels':{'m21.owner':owner,'m21.run':run,'m21.purpose':'valkey'},'image':'sha256:'+'b'*64,'ports':{'6379/tcp':[{'HostIp':'127.0.0.1','HostPort':str(port)}]},'bindings':{'6379/tcp':[{'HostIp':'127.0.0.1','HostPort':''}]},'mounts':[{'Type':'volume','Name':b.resource_name(run,'valkey')+'-data','Destination':'/data','RW':True}],'running':True}
                code='print('+repr(json.dumps(value))+')'
            elif operation=='volume-inspect' and role=='mysql': code='import time; time.sleep(60)' if invalid_cancel=='other-active' else 'raise SystemExit(1)'
            elif operation=='volume-inspect':
                value={'name':b.resource_name(run,role)+'-data','labels':{'m21.owner':owner,'m21.run':run,'m21.purpose':role}}
                code='print('+repr(json.dumps(value))+')'
            else: code='print('+repr(b.resource_name(run,role)+'-data')+')'
            return ['/usr/bin/python3','-I','-c',code],env
        with tempfile.TemporaryDirectory(prefix='m21-recovery-fixture-') as directory:
            dfd=os.open(directory,os.O_RDONLY|os.O_DIRECTORY); r,w=os.pipe2(os.O_CLOEXEC)
            pid=os.fork()
            if pid==0:
                listener.close()
                os.close(w); os.dup2(r,0); os.close(r)
                try:
                    with patch.object(b,'runtime_identity',return_value={'boot_id':'fixture','pid_namespace':'pid:[fixture]','socket_dev':1,'socket_ino':1}),patch.object(b.Path,'read_bytes',return_value=b'fixture-tool'),patch.object(b,'preflight',return_value=descriptor),patch.object(b.DockerBoundary,'__init__',seeded),patch.object(b.DockerBoundary,'command',command):
                        result=b.bridge_loop(dfd,directory,owner,run,'a'*32,'0'*40)
                    os._exit(result)
                except BaseException: os._exit(92)
            os.close(r); os.close(dfd); pidfd=os.pidfd_open(pid)
            thread=threading.Thread(target=server); thread.start()
            limit=time.monotonic()+6
            def wait_file(name):
                path=pathlib.Path(directory)/name
                while time.monotonic()<limit:
                    if path.exists(): return json.loads(path.read_text())
                    select.select([],[],[],.002)
                self.fail('owned recovery receipt deadline '+name)
            def send(value):
                raw=json.dumps(value).encode(); os.write(w,struct.pack('!I',len(raw))+raw)
            try:
                ready=wait_file('ready.json')
                send(dict(type='init',owner=owner,run=run,global_offset_ms=6000,work_offset_ms=5000))
                wait_file('initialized.json')
                send(dict(type='bind',owner=owner,run=run,descriptor=descriptor)); wait_file('bound.json')
                def request(index,operation,role,phase='cleanup',expired=False,wait=True):
                    elapsed=(time.monotonic_ns()-ready['t0_ns'])//1000000
                    send(dict(type='request',owner=owner,run=run,request_id=index,phase=phase,operation=operation,resource=role,deadline_offset_ms=1 if expired else elapsed+1000))
                    return wait_file('request-%05d-observed.json'%index) if wait else None
                denied=request(1,'daemon-info','daemon',phase='work',expired=True)
                self.assertTrue(denied['not_spawned']); self.assertFalse(denied['green']); self.assertIsNone(denied['childproof'])
                request(2,'container-inspect','valkey',phase='work',wait=False)
                self.assertTrue(received.wait(2))
                if cancel_when=='before': send(dict(type='cancel',owner=owner,run=run,request_id=2))
                release.set()
                observed=wait_file('request-00002-observed.json')
                cli=wait_file('request-00002.json')['linux']
                self.assertTrue(cli['green'])
                self.assertFalse(observed['green'])
                self.assertFalse(observed['result']['socket_proof']['green'])
                self.assertTrue(observed['result']['socket_proof']['socket_closed'])
                self.assertNotIn('run_id',observed['result'])
                completed=(pathlib.Path(directory)/'request-00002-observed.json').read_bytes()
                if invalid_cancel=='other-active':
                    request(3,'volume-inspect','mysql',wait=False)
                    wait_file('request-00003-started.json')
                    send(dict(type='cancel',owner=owner,run=run,request_id=2))
                elif invalid_cancel not in (None,'replay'):
                    value={'unknown':99,'old':1,'new':3,'bool':True}[invalid_cancel]
                    send(dict(type='cancel',owner=owner,run=run,request_id=value))
                else:
                    if cancel_when=='after': send(dict(type='cancel',owner=owner,run=run,request_id=2))
                    ack=wait_file('request-00002-late-cancel.json')
                    self.assertFalse(ack['green']); self.assertTrue(ack['late_cancel']); self.assertTrue(ack['no_signal'])
                    self.assertEqual(ack['signal_count'],0); self.assertFalse(ack['cli_spawned'])
                    self.assertIsNone(ack['childproof']); self.assertFalse(ack['deadline_reset'])
                    self.assertEqual(ack['deadline_ns'],observed['deadline_ns'])
                    self.assertEqual((pathlib.Path(directory)/'request-00002-observed.json').read_bytes(),completed)
                    if invalid_cancel=='replay': send(dict(type='cancel',owner=owner,run=run,request_id=2))
                if invalid_cancel is not None:
                    exited=wait_file('bridge-exit.json')
                    self.assertFalse(exited['green'])
                    if invalid_cancel=='other-active':
                        active=wait_file('request-00003.json')['linux']
                        self.assertTrue(active['children_reaped']); self.assertTrue(active['stdout_eof'] and active['stderr_eof'])
                    return
                bad=request(3,'volume-inspect','mysql'); self.assertFalse(bad['green'])
                denied=request(4,'volume-remove','mysql')
                self.assertTrue(denied['not_spawned']); self.assertIsNone(denied['childproof'])
                self.assertEqual(denied['code'],'ownership-unverified')
                self.assertTrue(request(5,'volume-inspect','valkey')['green'])
                last=request(6,'volume-remove','valkey'); self.assertTrue(last['green'])
                self.assertEqual(last['cleanup_request_slots'],4)
                self.assertEqual(last['cleanup_cli_spawn_upper_bound'],3)
            finally:
                release.set()
                try: send(dict(type='shutdown',owner=owner,run=run))
                except BrokenPipeError: pass
                os.close(w)
                if not select.select([pidfd],[],[],7)[0]: signal.pidfd_send_signal(pidfd,signal.SIGKILL)
                os.waitpid(pid,0); os.close(pidfd)
                thread.join(2); listener.close()
                self.assertFalse(thread.is_alive()); self.assertFalse(server_errors)

    def test_expired_work_and_one_role_failure_preserve_other_recovery(self): self.recovery_case()
    def test_cancel_before_socket_completion_preserves_recovery(self): self.recovery_case(cancel_when='before')
    def test_invalid_or_replayed_cancel_is_terminal(self):
        for kind in ('unknown','old','new','bool','replay','other-active'):
            with self.subTest(kind=kind): self.recovery_case(invalid_cancel=kind)


@unittest.skipUnless(sys.platform=='linux','Linux fork後faultの実子試験')
class PostForkFailureFiniteTests(unittest.TestCase):
    def fault_case(self,kind):
        import json,os,select,signal,time
        from unittest.mock import patch
        b.subreaper()
        control_r,control_w=os.pipe2(os.O_CLOEXEC)
        result_r,result_w=os.pipe2(os.O_CLOEXEC)
        notify_r,notify_w=os.pipe2(os.O_CLOEXEC)
        ack_r,ack_w=os.pipe2(os.O_CLOEXEC)
        original_fork=os.fork
        watchdog=original_fork()
        if watchdog==0:
            os.close(control_w); os.close(result_r); os.close(notify_r); os.close(ack_w)
            def tracked_fork():
                child=original_fork()
                if child>0:
                    os.write(notify_w,str(child).encode())
                    if select.select([ack_r],[],[],1)[0]: os.read(ack_r,1)
                return child
            result=None; code=0
            try:
                with patch.object(b.os,'fork',tracked_fork):
                    target=patch.object(b.os,'pidfd_open',side_effect=OSError('owned pidfd failure')) if kind=='pidfd' else patch.object(b,'process_identity',side_effect=OSError('owned identity failure'))
                    with target:
                        result=b.supervise(['/usr/bin/python3','-I','-c','import time; time.sleep(60)'],{'PATH':'/usr/bin:/bin','LANG':'C'},time.monotonic_ns()+500000000,control_r,time.monotonic_ns()+2500000000)
            except BaseException:
                result={'fixture_failure':True}; code=91
            os.write(result_w,json.dumps(result).encode()); os._exit(code)
        os.close(control_r); os.close(result_w); os.close(notify_w); os.close(ack_r)
        known_fd=None; known_pid=None
        try:
            self.assertTrue(select.select([notify_r],[],[],2)[0])
            known_pid=int(os.read(notify_r,32))
            # direct fork通知→ownerがpidfd取得→ACK。未reap anchorの世代だけを保持する。
            known_fd=os.pidfd_open(known_pid)
            os.write(ack_w,b'1')
            self.assertTrue(select.select([result_r],[],[],3)[0])
            result=json.loads(os.read(result_r,65536))
            _,status=os.waitpid(watchdog,0); watchdog=None
            self.assertEqual(os.waitstatus_to_exitcode(status),0)
            self.assertNotIn('fixture_failure',result)
            self.assertFalse(result['green'])
            self.assertTrue(result['children_reaped'])
            self.assertTrue(result['stdout_eof'] and result['stderr_eof'])
            self.assertIsNotNone(result['primary_failure_stage'])
            self.assertTrue(select.select([known_fd],[],[],0)[0])
        finally:
            os.close(control_w); os.close(result_r); os.close(notify_r); os.close(ack_w)
            if watchdog is not None: os.waitpid(watchdog,0)
            if known_fd is not None:
                if not select.select([known_fd],[],[],0)[0]: signal.pidfd_send_signal(known_fd,signal.SIGKILL)
                try: os.waitpid(known_pid,0)
                except ChildProcessError: pass  # watchdog自身がreap済みならownerの子ではない。
                os.close(known_fd)

    def test_pidfd_open_failure_still_reaps_known_cli(self): self.fault_case('pidfd')
    def test_identity_failure_still_reaps_known_cli(self): self.fault_case('identity')



class WorkRecoveryBudgetTests(unittest.TestCase):
    def test_work_twelve_then_recovery_fourteen_keeps_global_ids_and_limits(self):
        p=b.Protocol('11111111-1111-4111-8111-111111111111',100,SELF_OWNER)
        p.initialize(dict(type='init',owner=SELF_OWNER,run=p.run,global_offset_ms=3300000,work_offset_ms=3000000),100)
        original_deadlines=(p.work_deadline,p.global_deadline)
        index=1
        for role in ('mysql','valkey'):
            for operation in ('volume-create','volume-inspect','container-create','container-inspect','container-start','container-inspect'):
                request=dict(type='request',owner=SELF_OWNER,run=p.run,request_id=index,phase='work',operation=operation,resource=role,deadline_offset_ms=1000)
                if operation=='container-create' and role=='mysql': request['secrets']={'mysql_root_password':'W_SLOT_ROOT','mysql_password':'W_SLOT_APP'}
                p.request(request,100); index+=1
        self.assertEqual(p.cleanup_count,0)
        self.assertEqual(p.next_request,13)
        recovery=[('daemon-info','daemon'),('container-inspect','mysql'),('volume-inspect','mysql'),('container-inspect','valkey'),('volume-inspect','valkey'),('container-stop','mysql'),('container-stop','valkey'),('container-remove','mysql'),('container-remove','valkey'),('volume-remove','mysql'),('volume-remove','valkey'),('resources-absent','mysql'),('resources-absent','valkey'),('daemon-info','daemon')]
        for operation,role in recovery:
            p.request(dict(type='request',owner=SELF_OWNER,run=p.run,request_id=index,phase='cleanup',operation=operation,resource=role,deadline_offset_ms=1000),100); index+=1
        self.assertEqual(p.cleanup_count,14)
        self.assertEqual(p.next_request,27)
        self.assertEqual((p.work_deadline,p.global_deadline),original_deadlines)
        with self.assertRaises(b.Rejected): p.request(dict(type='request',owner=SELF_OWNER,run=p.run,request_id=27,phase='cleanup',operation='daemon-info',resource='daemon',deadline_offset_ms=1000),100)
        self.assertEqual(p.next_request,27)



class ValkeyInfoFrameTests(unittest.TestCase):
    @staticmethod
    def frame(payload): return b'$'+str(len(payload)).encode()+b'\r\n'+payload+b'\r\n'

    def test_only_single_run_id_is_returned(self):
        payload=b'# Server\r\nos:OWNED_PRIVATE_OS_SENTINEL\r\nrun_id:'+b'a'*40+b'\r\n'
        result=b.parse_valkey_info_frame(self.frame(payload))
        self.assertEqual(result,'a'*40)
        self.assertNotIn('OWNED_PRIVATE_OS_SENTINEL',result)

    def test_incomplete_header_and_bulk_stay_incomplete(self):
        wire=self.frame(b'run_id:'+b'a'*40+b'\r\n')
        for size in (0,1,3,len(wire)-1):
            self.assertIsNone(b.parse_valkey_info_frame(wire[:size]))

    def test_missing_duplicate_or_noncanonical_id_is_rejected(self):
        for payload in (b'# Server\r\n',b'run_id:'+b'a'*39+b'\r\n',b'run_id:'+b'A'*40+b'\r\n',b'run_id:'+b'a'*40+b'\r\nrun_id:'+b'b'*40+b'\r\n'):
            with self.assertRaises(b.Rejected): b.parse_valkey_info_frame(self.frame(payload))

    def test_resp_error_extra_bad_crlf_and_oversize_are_rejected(self):
        wire=self.frame(b'run_id:'+b'a'*40+b'\r\n')
        for malformed in (b'-ERR private\r\n',b'$-1\r\n',b'$16384\r\n',wire+b'+EXTRA\r\n',wire[:-2]+b'xx'):
            with self.assertRaises(b.Rejected): b.parse_valkey_info_frame(malformed)

    def test_prestart_cleanup_and_mysql_do_not_add_socket_work(self):
        from unittest.mock import patch
        d=b.DockerBoundary('11111111-1111-4111-8111-111111111111','/owned/config',{'daemon_id':'fixture','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64},SELF_OWNER)
        d.inspected[('container','valkey')]={'running':False}
        adopted={'ownership_inspected':True}
        for request in ({'phase':'work','operation':'container-inspect','resource':'valkey'}, {'phase':'cleanup','operation':'container-inspect','resource':'valkey'}, {'phase':'work','operation':'container-inspect','resource':'mysql'}):
            with patch.object(b,'read_owned_valkey_identity',side_effect=AssertionError('unexpected socket work')):
                result,green=b.with_valkey_identity(d,request,adopted,1)
            self.assertIs(result,adopted); self.assertTrue(green)


    def test_exact_total_limit_and_split_largest_header(self):
        base=b'run_id:'+b'a'*40+b'\r\n'
        payload=base+b'x'*(16374-len(base))
        self.assertEqual(len(self.frame(payload)),b.MAX_FRAME)
        self.assertEqual(b.parse_valkey_info_frame(self.frame(payload)),'a'*40)
        self.assertIsNone(b.parse_valkey_info_frame(b'$16374\r'))
        with self.assertRaises(b.Rejected): b.parse_valkey_info_frame(self.frame(payload+b'x'))

    def test_failed_refresh_never_reuses_old_identity_or_socket_proof(self):
        from unittest.mock import patch
        d=b.DockerBoundary('11111111-1111-4111-8111-111111111111','/owned/config',{'daemon_id':'fixture','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64},SELF_OWNER)
        d.inspected[('container','valkey')]={'running':True,'id':'c'*64}
        d.valkey_started_id='c'*64
        old={'ownership_inspected':True,'run_id':'a'*40,'socket_proof':{'green':True}}
        failed={'green':False,'reason':'timeout'}
        with patch.object(b,'read_owned_valkey_identity',return_value=(None,failed)):
            result,green=b.with_valkey_identity(d,{'phase':'work','operation':'container-inspect','resource':'valkey'},old,1)
        self.assertFalse(green); self.assertNotIn('run_id',result)
        self.assertIs(result['socket_proof'],failed)
        self.assertNotIn('run_id',d.inspected[('container','valkey')])


    def test_running_prestart_without_own_start_never_reads_info(self):
        from unittest.mock import patch
        d=b.DockerBoundary('11111111-1111-4111-8111-111111111111','/owned/config',{'daemon_id':'fixture','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64},SELF_OWNER)
        d.inspected[('container','valkey')]={'running':True,'id':'c'*64}
        adopted={'ownership_inspected':True}
        with patch.object(b,'read_owned_valkey_identity',side_effect=AssertionError('prestart INFO')):
            result,green=b.with_valkey_identity(d,{'phase':'work','operation':'container-inspect','resource':'valkey'},adopted,1)
        self.assertIs(result,adopted); self.assertTrue(green)


class OwnedValkeySocketTests(unittest.TestCase):
    @staticmethod
    def boundary(port):
        import json
        run='11111111-1111-4111-8111-111111111111'
        d=b.DockerBoundary(run,'/owned/config',{'daemon_id':'fixture','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64},SELF_OWNER)
        d.daemon_confirmed=True
        value={'id':'c'*64,'labels':{'m21.owner':SELF_OWNER,'m21.run':run,'m21.purpose':'valkey'},'image':'sha256:'+'b'*64,'ports':{'6379/tcp':[{'HostIp':'127.0.0.1','HostPort':str(port)}]},'bindings':{'6379/tcp':[{'HostIp':'127.0.0.1','HostPort':''}]},'mounts':[{'Type':'volume','Name':b.resource_name(run,'valkey')+'-data','Destination':'/data','RW':True}],'running':True}
        d.adopt('container-inspect','valkey',0,json.dumps(value).encode())
        return d

    def exchange(self,response,deadline_ms=1000,partial=False):
        import socket,threading,time
        listener=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
        listener.bind(('127.0.0.1',0)); listener.listen(1); listener.settimeout(1)
        port=listener.getsockname()[1]
        received=[]; errors=[]; peer_closed=threading.Event()
        def server():
            try:
                connection,_=listener.accept()
                with connection:
                    connection.settimeout(1)
                    received.append(connection.recv(256))
                    if response is not None:
                        if partial:
                            connection.sendall(response[:3]); connection.sendall(response[3:])
                        else: connection.sendall(response)
                    if response is not None and len(response)<10: connection.shutdown(socket.SHUT_WR)
                    while connection.recv(256): pass
                    peer_closed.set()
            except (BrokenPipeError,ConnectionResetError): peer_closed.set()
            except BaseException as error: errors.append(type(error).__name__)
            finally: listener.close()
        thread=threading.Thread(target=server)
        thread.start()
        try:
            deadline=time.monotonic_ns()+deadline_ms*1000000
            result,proof=b.read_owned_valkey_identity(self.boundary(port),deadline)
        finally:
            thread.join(2)
            listener.close()
        self.assertFalse(thread.is_alive())
        self.assertFalse(errors)
        self.assertEqual(received,[b'*2\r\n$4\r\nINFO\r\n$6\r\nserver\r\n'])
        self.assertTrue(peer_closed.is_set())
        self.assertTrue(proof['socket_closed'])
        self.assertFalse(proof['cli_spawned']); self.assertIsNone(proof['childproof'])
        return result,proof

    def test_owned_endpoint_complete_identity_closes_and_hides_info(self):
        import json
        payload=b'# Server\r\nos:OWNED_PRIVATE_OS_SENTINEL\r\nrun_id:'+b'a'*40+b'\r\n'
        result,proof=self.exchange(ValkeyInfoFrameTests.frame(payload),partial=True)
        self.assertEqual(result['run_id'],'a'*40)
        self.assertTrue(proof['green']); self.assertTrue(proof['response_complete']); self.assertTrue(proof['within_deadline'])
        self.assertNotIn('OWNED_PRIVATE_OS_SENTINEL',json.dumps(result|proof))

    def test_partial_extra_oversize_and_duplicate_are_non_green(self):
        valid=ValkeyInfoFrameTests.frame(b'run_id:'+b'a'*40+b'\r\n')
        for response in (b'$50\r\nx',valid+b'+EXTRA\r\n',b'$16384\r\n',ValkeyInfoFrameTests.frame(b'run_id:'+b'a'*40+b'\r\nrun_id:'+b'b'*40+b'\r\n')):
            result,proof=self.exchange(response)
            self.assertIsNone(result); self.assertFalse(proof['green'])

    def test_timeout_uses_existing_deadline_and_closes(self):
        result,proof=self.exchange(None,deadline_ms=50)
        self.assertIsNone(result); self.assertFalse(proof['green']); self.assertFalse(proof['within_deadline'])

    def test_expired_or_unverified_cache_never_opens_socket(self):
        import time
        from unittest.mock import patch
        for mutation in ('expired','daemon','stopped','label','wrong-port'):
            d=self.boundary(45123)
            deadline=time.monotonic_ns()+100000000
            if mutation=='expired': deadline=time.monotonic_ns()-1
            elif mutation=='daemon': d.daemon_confirmed=False
            elif mutation=='stopped': d.inspected[('container','valkey')]['running']=False
            elif mutation=='label': d.inspected[('container','valkey')]['labels']['m21.owner']='wrong'
            else: d.ports['valkey']={'HostIp':'127.0.0.1','HostPort':'45124'}
            with patch.object(b.socket,'socket',side_effect=AssertionError('unverified endpoint opened')):
                result,proof=b.read_owned_valkey_identity(d,deadline)
            self.assertIsNone(result); self.assertFalse(proof['green']); self.assertIsNone(proof['socket_closed'])

if __name__ == '__main__':
    if '--relay-fixture' in sys.argv: raise SystemExit(relay_fixture())
    if '--real-readonly-absence-once' in sys.argv:
        suite=unittest.defaultTestLoader.loadTestsFromTestCase(RealDaemonAbsenceFiniteTests)
        raise SystemExit(0 if unittest.TextTestRunner().run(suite).wasSuccessful() else 1)
    unittest.main()
