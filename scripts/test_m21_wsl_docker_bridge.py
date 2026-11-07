"""固定bridge境界の有限試験。実Dockerは起動しない。"""
import importlib.util
import pathlib
import unittest
import sys
sys.dont_write_bytecode=True
spec = importlib.util.spec_from_file_location('bridge', pathlib.Path(__file__).with_name('m21-wsl-docker-bridge.py'))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)

class BoundaryTests(unittest.TestCase):
    def test_admission_and_replay(self):
        p = b.Protocol('11111111-1111-4111-8111-111111111111', 100)
        p.initialize(dict(type='init', owner=b.OWNER, run=p.run, global_offset_ms=1000, work_offset_ms=800), 100)
        r = dict(type='request', owner=b.OWNER, run=p.run, request_id=1, operation='daemon-info', resource='daemon', deadline_offset_ms=200,phase='cleanup')
        self.assertEqual(p.request(r, 150)[1], 200000100)
        with self.assertRaises(b.Rejected): p.request(r, 150)
    def test_unknown_input_and_secret_api(self):
        for extra in ({'argv': []}, {'env': {}}, {'path':'/tmp'}, {'operation':'exec'}):
            p = b.Protocol('11111111-1111-4111-8111-111111111111', 100)
            p.initialize(dict(type='init', owner=b.OWNER, run=p.run, global_offset_ms=1000, work_offset_ms=800), 100)
            r = dict(type='request', owner=b.OWNER, run=p.run, request_id=1, operation='daemon-info', resource='daemon', deadline_offset_ms=200,phase='cleanup')
            with self.assertRaises(b.Rejected): p.request(r | extra, 150)
    def test_zero_null_bool_and_expiry(self):
        for value in (0, None, '', True):
            with self.assertRaises(b.Rejected): b.integer(value,1,3000000)
        p = b.Protocol('11111111-1111-4111-8111-111111111111',100)
        with self.assertRaises(b.Rejected): p.initialize(dict(type='init',owner=b.OWNER,run=p.run,global_offset_ms=1,work_offset_ms=1),2000000)
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
            raw = os.read(receipt_r,16384)
            _, status = os.waitpid(pid,0)
            self.assertEqual(os.waitstatus_to_exitcode(status),0)
            self.assertEqual(os.read(receipt_r,1),b'')
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
        boundary=b.DockerBoundary('11111111-1111-4111-8111-111111111111','/owned/config',{'daemon_id':'fixture','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64})
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
                             {'daemon_id':'daemon:local','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64})
        argv, env = d.command('container-create','mysql',{'mysql_root_password':'OWNED_SENTINEL','mysql_password':'APP_SENTINEL'})
        self.assertNotIn('OWNED_SENTINEL',' '.join(argv))
        self.assertEqual(env['MYSQL_ROOT_PASSWORD'],'OWNED_SENTINEL')
        self.assertEqual(set(env),{'PATH','LANG','MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD'})
        self.assertIn('127.0.0.1::3306',argv)
        self.assertNotIn('HOME',env)

    def test_daemon_change_and_nonzero_are_not_absence(self):
        d = b.DockerBoundary('11111111-1111-4111-8111-111111111111','/owned/config',
                             {'daemon_id':'daemon:local','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64})
        with self.assertRaises(b.Rejected): d.adopt('daemon-info','daemon',0,b'other-daemon\n')
        with self.assertRaises(b.Rejected): d.adopt('resources-absent','mysql',1,b'')
        missing=('error: no such object: '+b.resource_name(d.run,'mysql')+'\nerror: no such object: '+b.resource_name(d.run,'mysql')+'-data\n').encode()
        candidate=d.adopt('resources-absent','mysql',1,b'',missing)
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
        owner=b.OWNER; run='11111111-1111-4111-8111-111111111111'
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
                send(dict(type='bind',owner=owner,run=run,descriptor=descriptor))
                wait_file('bound.json',limit)
                if mode=='active-eof':
                    elapsed=(time.monotonic_ns()-ready['t0_ns'])//1000000
                    send(dict(type='request',owner=owner,run=run,request_id=1,phase='cleanup',operation='daemon-info',resource='daemon',deadline_offset_ms=elapsed+400))
            if mode!='no-init': os.close(w); w=None
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
            if mode=='active-eof':
                linux=wait_file('request-00001.json',limit)['linux']
                self.assertTrue(linux['children_reaped'])
                self.assertTrue(linux['stdout_eof'] and linux['stderr_eof'])

    def test_idle_parent_eof(self): self.resident_case('idle-eof')
    def test_partial_parent_eof(self): self.resident_case('partial')
    def test_active_parent_eof(self): self.resident_case('active-eof')
    def test_initial_handshake_deadline(self): self.resident_case('no-init')



@unittest.skipUnless(sys.platform=='linux' and pathlib.Path('/mnt/c/Users/kenta/AppData/Local/Temp').is_dir(),'WSL NTFS実測専用')
class NtfsReceiptFiniteTests(unittest.TestCase):
    def test_guarded_ntfs_receipt_create_publish_read(self):
        import hashlib,json,os,uuid
        run=str(uuid.uuid4()); nonce=uuid.uuid4().hex
        root=b.TEMP_ROOT/('m21-windows-bridge-fixture-'+uuid.uuid4().hex)
        child=root/'bridge'
        os.mkdir(root,0o700); os.mkdir(child,0o700)
        owned={'owner':b.OWNER,'nonce':nonce}
        def fresh(path,value):
            fd=os.open(str(path),os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
            with os.fdopen(fd,'w') as out: json.dump(value,out)
        fresh(root/'owner.json',owned)
        fresh(child/'owner.json',owned|{'run':run,'purpose':'m21-docker-bridge'})
        dfd=b.guard_run(str(child),b.OWNER,run,nonce)
        identity=os.fstat(dfd)
        value=owned|{'run':run,'source_sha256':hashlib.sha256(b.Path(b.__file__).read_bytes()).hexdigest(),'case':'ntfs-receipt-once'}
        try:
            b.publish(dfd,'fixture.json',value)
            self.assertEqual(b.read_owned_json(dfd,'fixture.json'),value)
            with self.assertRaises(FileExistsError): b.publish(dfd,'fixture.json',value)
            print(json.dumps({'case':'ntfs-receipt-once','owner':b.OWNER,'run':run,'nonce':nonce,'source_sha256':value['source_sha256'],'dev':identity.st_dev,'ino':identity.st_ino,'create_new':True,'atomic_public':True,'directory_fsync':True,'read_exact':True}))
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
                    active=b.spawn_watchdog(['/usr/bin/python3','-I','-c','import time; time.sleep(60)'],{'PATH':'/usr/bin:/bin','LANG':'C'},time.monotonic_ns()+500000000,time.monotonic_ns()+2500000000,dfd,{'owner':b.OWNER,'run':'11111111-1111-4111-8111-111111111111'},request)
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
        d=b.DockerBoundary(run,'/owned/config',{'daemon_id':'fixture','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64})
        v={'id':'c'*64,'labels':{'m21.owner':b.OWNER,'m21.run':run,'m21.purpose':'mysql'},'image':'sha256:'+'a'*64,'ports':{},'bindings':{'3306/tcp':[{'HostIp':'127.0.0.1','HostPort':''}]},'mounts':[{'Type':'volume','Name':b.resource_name(run,'mysql')+'-data','Destination':'/var/lib/mysql','RW':True}],'running':False}
        d.adopt('container-inspect','mysql',0,json.dumps(v).encode())
        self.assertNotIn('mysql',d.ports)
        v['running']=True
        v['ports']={'3306/tcp':[{'HostIp':'127.0.0.1','HostPort':'45123'}],'33060/tcp':None}
        d.adopt('container-inspect','mysql',0,json.dumps(v).encode())
        self.assertEqual(d.ports['mysql']['HostPort'],'45123')
        v['ports']['3306/tcp'][0]['HostIp']='0.0.0.0'
        with self.assertRaises(b.Rejected): d.adopt('container-inspect','mysql',0,json.dumps(v).encode())

    def test_cleanup_count_and_work_deadline(self):
        p=b.Protocol('11111111-1111-4111-8111-111111111111',100)
        p.initialize(dict(type='init',owner=b.OWNER,run=p.run,global_offset_ms=1000,work_offset_ms=100),100)
        for index in range(1,15):
            p.request(dict(type='request',owner=b.OWNER,run=p.run,request_id=index,phase='cleanup',operation='daemon-info',resource='daemon',deadline_offset_ms=500),200000000)
        with self.assertRaises(b.Rejected): p.request(dict(type='request',owner=b.OWNER,run=p.run,request_id=15,phase='cleanup',operation='daemon-info',resource='daemon',deadline_offset_ms=500),200000000)
        with self.assertRaises(b.Rejected): p.request(dict(type='request',owner=b.OWNER,run=p.run,request_id=15,phase='work',operation='daemon-info',resource='daemon',deadline_offset_ms=500),200000000)



@unittest.skipUnless(sys.platform=='linux' and '--real-readonly-absence-once' in sys.argv,'明示read-only既存daemon試験専用')
class RealDaemonAbsenceFiniteTests(unittest.TestCase):
    def test_exact_missing_object_shape_and_three_second_cap(self):
        import hashlib,json,os,select,tempfile,time,uuid
        run=str(uuid.uuid4()); nonce=uuid.uuid4().hex
        with tempfile.TemporaryDirectory(prefix='m21-windows-readonly-',dir=str(b.TEMP_ROOT)) as parent:
            root=pathlib.Path(parent); child=root/'bridge'; child.mkdir()
            owner={'owner':b.OWNER,'nonce':nonce}
            with (root/'owner.json').open('x') as f: json.dump(owner,f)
            with (child/'owner.json').open('x') as f: json.dump(owner|{'run':run,'purpose':'m21-docker-bridge'},f)
            dfd=b.guard_run(str(child),b.OWNER,run,nonce)
            os.mkdir('config',dir_fd=dfd); os.close(dfd)
            boundary=b.DockerBoundary(run,str(child/'config'),{'daemon_id':'syntax-fixture-only','mysql_image_id':'sha256:'+'a'*64,'valkey_image_id':'sha256:'+'b'*64})
            argv,env=boundary.command('resources-absent','mysql')
            control_r,control_w=os.pipe2(os.O_CLOEXEC); result_r,result_w=os.pipe2(os.O_CLOEXEC)
            pid=os.fork(); began=time.monotonic_ns()
            if pid==0:
                os.close(control_w); os.close(result_r)
                result=b.supervise(argv,env,began+3000000000,control_r,began+5000000000)
                os.write(result_w,json.dumps(result).encode()); os._exit(0)
            os.close(control_r); os.close(result_w)
            self.assertTrue(select.select([result_r],[],[],5)[0])
            result=json.loads(os.read(result_r,65536)); os.close(result_r); os.close(control_w)
            os.waitpid(pid,0)
            self.assertTrue(result['physical_completed'])
            adopted=boundary.adopt('resources-absent','mysql',result['cli_exit'],bytes.fromhex(result['_stdout_hex']),bytes.fromhex(result['_stderr_hex']))
            self.assertTrue(adopted['absence_candidate']); self.assertFalse(adopted['absent'])
            print(json.dumps({'case':'real-readonly-absence-once','owner':b.OWNER,'run':run,'nonce':nonce,'source_sha256':hashlib.sha256(b.Path(b.__file__).read_bytes()).hexdigest(),'body_cap_ms':3000,'elapsed_ms':(time.monotonic_ns()-began)//1000000,'cli_exit':result['cli_exit'],'children_reaped':result['children_reaped'],'stdout_eof':result['stdout_eof'],'stderr_eof':result['stderr_eof'],'physical_completed':result['physical_completed'],'exact_two_no_such_objects':True,'daemon_absence_confirmed':False}))

if __name__ == '__main__':
    if '--relay-fixture' in sys.argv: raise SystemExit(relay_fixture())
    if '--real-readonly-absence-once' in sys.argv:
        suite=unittest.defaultTestLoader.loadTestsFromTestCase(RealDaemonAbsenceFiniteTests)
        raise SystemExit(0 if unittest.TextTestRunner().run(suite).wasSuccessful() else 1)
    unittest.main()
