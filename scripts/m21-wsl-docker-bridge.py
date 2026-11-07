#!/usr/bin/env python3
"""M21専用の有限Linux回収機構。正式入口の接続資格は未取得。"""
import ctypes
import errno
import hashlib
import json
import os
from pathlib import Path
import select
import signal
import socket
import struct
import sys
import time
import uuid

MAX_FRAME = 16384
OPERATIONS = {'volume-create': ('mysql','valkey'), 'container-create': ('mysql','valkey'), 'container-start': ('mysql','valkey'), 'resources-absent': ('mysql','valkey'), 'daemon-info': ('daemon',), 'container-inspect': ('mysql','valkey'),
              'container-stop': ('mysql','valkey'), 'container-remove': ('mysql','valkey'),
              'volume-inspect': ('mysql','valkey'),
              'volume-remove': ('mysql','valkey'), }

class Rejected(Exception):
    """外部入力を例外本文へ含めない。"""


class RequestDenied(Rejected):
    def __init__(self, deadline):
        super().__init__('admission-denied')
        self.deadline=deadline


def mysql_secret_env(value):
    keys={'mysql_root_password','mysql_password'}
    if type(value) is not dict or set(value)!=keys:
        raise Rejected('secret-schema')
    for key in keys:
        secret=value[key]
        if type(secret) is not str or not 1<=len(secret)<=1024 or any(c in secret for c in ('\x00','\n','\r')):
            raise Rejected('secret-schema')
    return {'MYSQL_ROOT_PASSWORD':value['mysql_root_password'],'MYSQL_PASSWORD':value['mysql_password']}


def integer(value, minimum, maximum):
    if type(value) is not int or not minimum <= value <= maximum:
        raise Rejected('integer')
    return value


def canonical_uuid(value):
    if type(value) is not str:
        raise Rejected('uuid')
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        raise Rejected('uuid') from None
    if str(parsed) != value:
        raise Rejected('uuid')
    return value


def resource_name(run, role):
    canonical_uuid(run)
    if role not in ('mysql', 'valkey'):
        raise Rejected('resource')
    return 'm21-' + run.replace('-', '') + '-' + role


class Protocol:
    def __init__(self, run, t0, owner):
        self.run = canonical_uuid(run)
        self.owner = canonical_uuid(owner)
        self.t0 = t0
        self.global_deadline = None
        self.work_deadline = None
        self.next_request = 1
        self.cleanup_count = 0
        self.cleanup_started=False

    def check(self, data, keys):
        if type(data) is not dict or set(data) != set(keys) or data.get('owner') != self.owner or data.get('run') != self.run:
            raise Rejected('schema')

    def initialize(self, data, now):
        self.check(data, ('type','owner','run','global_offset_ms','work_offset_ms'))
        if data['type'] != 'init' or self.global_deadline is not None:
            raise Rejected('init')
        g = integer(data['global_offset_ms'], 1, 3300000)
        w = integer(data['work_offset_ms'], 1, 3000000)
        if w > g:
            raise Rejected('budget')
        self.global_deadline = self.t0 + g * 1000000
        self.work_deadline = self.t0 + w * 1000000
        if now >= self.work_deadline:
            raise Rejected('expired')

    def request(self, data, now):
        if type(data) is not dict: raise Rejected('schema')
        keys = ['type','owner','run','request_id','operation','resource','deadline_offset_ms','phase']
        if data.get('operation') == 'container-create' and data.get('resource') == 'mysql': keys.append('secrets')
        self.check(data, keys)
        if self.global_deadline is None or data['type'] != 'request':
            raise Rejected('uninitialized')
        request_id = integer(data['request_id'], 1, 65535)
        operation, role = data['operation'], data['resource']
        if request_id != self.next_request or type(operation) is not str or operation not in OPERATIONS or role not in OPERATIONS[operation]:
            raise Rejected('operation')
        phase = data['phase']
        if phase not in ('work','cleanup') or (operation in ('volume-create','container-create','container-start') and phase != 'work'):
            raise Rejected('phase')
        ceiling = self.work_deadline if phase == 'work' else self.global_deadline
        deadline = min(ceiling, self.t0 + integer(data['deadline_offset_ms'],1,3300000)*1000000)
        if operation=='container-create' and role=='mysql': mysql_secret_env(data['secrets'])
        cap_ms = (15000 if role == 'mysql' else 10000) if operation == 'container-stop' else 3000
        if deadline - now > cap_ms*1000000:
            raise Rejected('deadline')
        if phase == 'cleanup' and self.cleanup_count >= 14:
            raise Rejected('cleanup-limit')
        self.next_request += 1
        if phase == 'cleanup':
            self.cleanup_count += 1
            self.cleanup_started=True
        if deadline<=now or (phase=='work' and self.cleanup_started): raise RequestDenied(deadline)
        return operation, deadline


def read_frame(fd, deadline):
    """partial frameと読取待ちを同じ絶対期限で終端する。"""
    data = bytearray()
    wanted = 4
    os.set_blocking(fd, False)
    while len(data) < wanted:
        remaining = (deadline-time.monotonic_ns())/1e9
        if remaining <= 0:
            raise Rejected('frame-timeout')
        if not select.select([fd], [], [], remaining)[0]:
            raise Rejected('frame-timeout')
        chunk = os.read(fd, wanted-len(data))
        if not chunk:
            raise Rejected('parent-eof')
        data.extend(chunk)
        if len(data) == 4 and wanted == 4:
            length = struct.unpack('!I',data)[0]
            if not 1 <= length <= MAX_FRAME:
                raise Rejected('frame-length')
            wanted = 4+length
    try:
        return json.loads(data[4:].decode('utf-8'), object_pairs_hook=unique_object)
    except (ValueError, UnicodeError):
        raise Rejected('frame-json') from None


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate')
        result[key] = value
    return result


def terminal_diagnostic_code(error):
    if isinstance(error,Rejected):
        code=error.args[0] if error.args else None
        return code if type(code) is str and code in (
            'frame-timeout','parent-eof','frame-length','frame-json','schema',
            'init-schema','init','integer','uuid','budget','expired','descriptor-bind',
            'clock-discontinuity','runtime-changed','private-length','watchdog-retained',
            'cancel','concurrent-request','operation','phase','deadline','cleanup-limit',
            'uninitialized','secret-schema') else 'other-rejected'
    if isinstance(error,UnicodeError): return 'unicode-error'
    if isinstance(error,ValueError): return 'value-error'
    return 'os-error'


def atomic_receipt(directory, name, value):
    """所有済みdirectoryのdirfdに限定、既存receiptを置換しない。"""
    raw = json.dumps(value, separators=(',', ':'), sort_keys=True).encode('utf-8')
    if len(raw) > MAX_FRAME:
        raise Rejected('receipt-length')
    dfd = os.dup(directory) if type(directory) is int else os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    temporary = name + '.pending'
    try:
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=dfd)
        try:
            with os.fdopen(fd, 'wb') as output:
                output.write(raw)
                output.flush()
                os.fsync(output.fileno())
            # hardlinkは既存nameを上書きせず、同一directoryでatomic公開する。
            os.link(temporary, name, src_dir_fd=dfd, dst_dir_fd=dfd, follow_symlinks=False)
            os.unlink(temporary, dir_fd=dfd)
            os.fsync(dfd)
        except BaseException:
            raise
    finally:
        os.close(dfd)


def subreaper():
    signal.signal(signal.SIGCHLD, signal.SIG_DFL)
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(36, 1, 0, 0, 0) != 0:
        raise Rejected('subreaper-set')
    enabled = ctypes.c_int()
    if libc.prctl(37, ctypes.byref(enabled), 0, 0, 0) != 0 or enabled.value != 1:
        raise Rejected('subreaper-get')


def process_identity(pid):
    raw = Path('/proc/' + str(pid) + '/stat').read_text()
    fields = raw[raw.rfind(')')+2:].split()
    return dict(pid=pid, pgid=int(fields[2]), startticks=fields[19],
                boot_id=Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
                pid_namespace=os.readlink('/proc/self/ns/pid'))


def supervise(argv, env, body_deadline, control_fd, global_deadline, clock_delta=None, started=None):
    """要求専用watchdog。fork後の全失敗でも自分の子の回収をattemptする。"""
    watchdog_started_ns=time.monotonic_ns()
    if clock_delta is None: clock_delta=time.clock_gettime_ns(time.CLOCK_BOOTTIME)-time.monotonic_ns()
    if abs((time.clock_gettime_ns(time.CLOCK_BOOTTIME)-time.monotonic_ns())-clock_delta)>100000000:
        raise Rejected('clock-discontinuity')
    body_deadline=min(body_deadline,global_deadline)
    hard_deadline=min(global_deadline,body_deadline+2000000000)
    if time.monotonic_ns()>=body_deadline: raise Rejected('expired-before-spawn')
    os.setsid()
    subreaper()
    out_r,out_w=os.pipe2(os.O_CLOEXEC)
    err_r,err_w=os.pipe2(os.O_CLOEXEC)
    started_r,started_w=os.pipe2(os.O_CLOEXEC)
    if time.monotonic_ns()>=body_deadline or abs((time.clock_gettime_ns(time.CLOCK_BOOTTIME)-time.monotonic_ns())-clock_delta)>100000000:
        for fd in (out_r,out_w,err_r,err_w,started_r,started_w): os.close(fd)
        raise Rejected('expired-before-spawn')
    cli_spawn_ns=time.monotonic_ns()
    try: pid=os.fork()
    except BaseException:
        for fd in (out_r,out_w,err_r,err_w,started_r,started_w): os.close(fd)
        raise
    if pid==0:
        try:
            os.close(started_r)
            os.setsid()
            os.dup2(out_w,1); os.dup2(err_w,2)
            null=os.open('/dev/null',os.O_RDONLY); os.dup2(null,0)
            os.write(started_w,b'1')
            os.closerange(3,65536)
            os.execve(argv[0],argv,env)
        except BaseException: os._exit(127)
    pidfd=None
    acknowledged=False
    anchor_held=True
    identity={'pid':pid,'pgid':None,'startticks':None,'boot_id':None,'pid_namespace':None}
    identity_confirmed=False
    write_closed={out_w:False,err_w:False,started_w:False}
    ack_closed=False
    eof={out_r:False,err_r:False}
    captured={out_r:bytearray(),err_r:bytearray()}
    primary_eof={out_r:False,err_r:False}
    primary_children_exited=False
    primary_green=False
    primary_failure_stage=None
    secondary=[]
    stage='parent-fds'
    reason='completed'
    force_count=0
    reaped=[]
    cli_status=None
    children_reaped=False
    body_observed_ns=None
    eof_observed_ns={out_r:None,err_r:None}
    try:
        for fd in write_closed:
            os.close(fd); write_closed[fd]=True
        stage='session-ack'
        acknowledged=bool(select.select([started_r],[],[],max(0,(body_deadline-time.monotonic_ns())/1e9))[0]) and os.read(started_r,1)==b'1'
        identity['pgid']=pid if acknowledged else None
        os.close(started_r); ack_closed=True
        stage='pidfd'
        pidfd=os.pidfd_open(pid)
        stage='identity'
        identity=process_identity(pid); identity_confirmed=True
        stage='setup'
        for fd in eof: os.set_blocking(fd,False)
        os.set_blocking(control_fd,False)
        stage='started-receipt'
        if started is not None:
            publish(started[0],started[1],started[2]|{'leader_identity':identity,'watchdog_identity':process_identity(os.getpid()),'session_acknowledged':acknowledged})
        stage='observe'
        leader_seen=False
        output_full=False
        while time.monotonic_ns()<body_deadline:
            if abs((time.clock_gettime_ns(time.CLOCK_BOOTTIME)-time.monotonic_ns())-clock_delta)>100000000:
                reason='clock-discontinuity'; break
            try: status=os.waitid(os.P_PID,pid,os.WEXITED|os.WNOHANG|os.WNOWAIT)
            except ChildProcessError:
                anchor_held=False
                raise
            leader_seen=status is not None
            if leader_seen: break
            readers=[fd for fd in eof if not eof[fd]]+[control_fd]
            ready=select.select(readers,[],[],min(.01,max(0,(body_deadline-time.monotonic_ns())/1e9)))[0]
            if control_fd in ready:
                os.read(control_fd,1)
                reason='cancel-or-bridge-eof'; break
            for fd in ready:
                chunk=os.read(fd,4096)
                if not chunk: eof[fd]=True
                elif len(captured[fd])+len(chunk)>MAX_FRAME:
                    output_full=True; reason='output-full'
                else: captured[fd].extend(chunk)
            if output_full: break
        if not leader_seen and reason=='completed': reason='body-deadline'
        stage='primary-observation'
        for fd in eof:
            while not eof[fd] and time.monotonic_ns()<body_deadline and select.select([fd],[],[],0)[0]:
                chunk=os.read(fd,4096)
                if not chunk: eof[fd]=True
                elif len(captured[fd])+len(chunk)<=MAX_FRAME: captured[fd].extend(chunk)
                else:
                    output_full=True; break
            primary_eof[fd]=eof[fd]
        children=Path('/proc/self/task/'+str(os.getpid())+'/children').read_text().split()
        primary_children_exited=leader_seen and all(os.waitid(os.P_PID,int(child),os.WEXITED|os.WNOHANG|os.WNOWAIT) is not None for child in children)
        primary_green=reason=='completed' and primary_children_exited and all(primary_eof.values()) and not output_full
        if not primary_green and reason=='completed': reason='primary-child-or-eof-incomplete'
    except BaseException:
        primary_failure_stage=stage
        reason='supervision-failed'
        primary_green=False
    finally:
        # このfinallyより前にleaderをreapしない。全signal判断が済むまでanchorを保持する。
        body_observed_ns=time.monotonic_ns()
        for fd in write_closed:
            if not write_closed[fd]:
                try: os.close(fd); write_closed[fd]=True
                except OSError: secondary.append('write-close-failed')
        signal_decision_ns=time.monotonic_ns()
        if not primary_green:
            try:
                if acknowledged and anchor_held:
                    force_count=1; os.killpg(pid,signal.SIGKILL)
                elif pidfd is not None:
                    force_count=1; signal.pidfd_send_signal(pidfd,signal.SIGKILL)
                elif anchor_held:
                    # pidfd取得失敗でも、自分の未reap fork子のPIDは再利用されない。
                    force_count=1; os.kill(pid,signal.SIGKILL)
                else: secondary.append('anchor-unconfirmed')
            except ProcessLookupError: secondary.append('signal-task-already-exited')
            except OSError: secondary.append('signal-failed')
        # 以後PGIDへのsignalは禁止。失敗でもreapと各EOFを別々にattemptする。
        exit_deadline=min(hard_deadline,time.monotonic_ns()+1000000000)
        while time.monotonic_ns()<exit_deadline:
            try: child,status=os.waitpid(-1,os.WNOHANG)
            except ChildProcessError:
                children_reaped=True; break
            except OSError:
                secondary.append('reap-failed'); break
            if child:
                reaped.append(child)
                if child==pid: cli_status=os.waitstatus_to_exitcode(status)
            else: select.select([],[],[],min(.001,max(0,(exit_deadline-time.monotonic_ns())/1e9)))
        reap_observed_ns=time.monotonic_ns()
        for fd in eof:
            eof_deadline=min(hard_deadline,time.monotonic_ns()+500000000)
            try:
                os.set_blocking(fd,False)
                while not eof[fd] and time.monotonic_ns()<eof_deadline:
                    if select.select([fd],[],[],max(0,(eof_deadline-time.monotonic_ns())/1e9))[0]:
                        chunk=os.read(fd,4096)
                        if not chunk: eof[fd]=True
                        elif len(captured[fd])+len(chunk)<=MAX_FRAME: captured[fd].extend(chunk)
                eof_observed_ns[fd]=time.monotonic_ns() if eof[fd] else None
            except OSError: secondary.append('stdout-read-failed' if fd==out_r else 'stderr-read-failed')
            finally:
                try: os.close(fd)
                except OSError: secondary.append('read-close-failed')
        if not ack_closed:
            try: os.close(started_r)
            except OSError: secondary.append('ack-close-failed')
        if pidfd is not None:
            try: os.close(pidfd)
            except OSError: secondary.append('pidfd-close-failed')
        try: os.close(control_fd)
        except OSError: secondary.append('control-close-failed')
    retained=[]
    if not children_reaped:
        try:
            for child in Path('/proc/self/task/'+str(os.getpid())+'/children').read_text().split():
                try: retained.append(process_identity(int(child)))
                except (OSError,ValueError): retained.append({'pid':int(child),'identity_confirmed':False})
        except (OSError,ValueError): secondary.append('retained-set-unavailable')
    physical=primary_green and children_reaped and all(eof.values()) and not secondary
    return dict(leader_identity=identity,identity_confirmed=identity_confirmed,cli_exit=cli_status,
                primary_failure_stage=primary_failure_stage,secondary_failures=secondary,
                children_reaped=children_reaped,reaped_pids=reaped,retained_children=retained,
                stdout_eof=eof[out_r],stderr_eof=eof[err_r],write_closed=all(write_closed.values()),
                force_count=force_count,reason=reason,primary_children_exited=primary_children_exited,
                primary_stdout_eof=primary_eof[out_r],primary_stderr_eof=primary_eof[err_r],
                watchdog_started_ns=watchdog_started_ns,cli_spawn_ns=cli_spawn_ns,body_observed_ns=body_observed_ns,
                body_elapsed_ms=(body_observed_ns-watchdog_started_ns)//1000000,signal_decision_ns=signal_decision_ns,
                reap_observed_ns=reap_observed_ns,stdout_eof_observed_ns=eof_observed_ns[out_r],stderr_eof_observed_ns=eof_observed_ns[err_r],
                physical_completed=physical,green=physical and cli_status==0,
                _stdout_hex=bytes(captured[out_r]).hex(),_stderr_hex=bytes(captured[err_r]).hex())

class DockerBoundary:
    def __init__(self, run, config, descriptor, owner):
        import re
        canonical_uuid(run)
        if type(descriptor) is not dict or set(descriptor) != {'daemon_id','mysql_image_id','valkey_image_id'}:
            raise Rejected('descriptor')
        if type(descriptor['daemon_id']) is not str or not 1 <= len(descriptor['daemon_id']) <= 256 or any(ord(c)<33 for c in descriptor['daemon_id']):
            raise Rejected('daemon-id')
        for key in ('mysql_image_id','valkey_image_id'):
            if type(descriptor[key]) is not str or not re.fullmatch(r'sha256:[0-9a-f]{64}',descriptor[key]):
                raise Rejected('image-id')
        self.owner = canonical_uuid(owner)
        self.run, self.config, self.descriptor = run, config, descriptor.copy()
        self.inspected = {}
        self.ports = {}
        self.daemon_confirmed = False
        self.absence_candidates=set()
        self.valkey_started_id=None

    def command(self, operation, role, secrets=None):
        name = resource_name(self.run,role) if role != 'daemon' else None
        base = ['/usr/bin/docker','--config',self.config,'--host','unix:///var/run/docker.sock']
        env = {'PATH':'/usr/bin:/bin','LANG':'C'}
        if operation != 'container-create' and secrets is not None:
            raise Rejected('unexpected-secret')
        if operation == 'daemon-info' and role == 'daemon':
            self.daemon_confirmed=False
            self.inspected.clear()
            self.valkey_started_id=None
            return base+['info','--format','{{.ID}}'], env
        if role not in ('mysql','valkey'):
            raise Rejected('role')
        volume=name+'-data'
        if operation=='container-create' and role=='valkey': self.valkey_started_id=None
        if operation in ('container-inspect','volume-inspect'):
            self.inspected.pop((operation.split('-')[0],role),None)
        if operation in ('container-create','volume-create','container-start','container-stop','container-remove','volume-remove'):
            self.absence_candidates.discard(role)
        if operation == 'volume-create':
            args = ['volume','create','--label','m21.owner='+self.owner,'--label','m21.run='+self.run,'--label','m21.purpose='+role,volume]
        elif operation == 'container-create':
            args = ['container','create','--pull=never','--name',name,'--label','m21.owner='+self.owner,'--label','m21.run='+self.run,'--label','m21.purpose='+role,
                    '--publish', '127.0.0.1::'+('3306' if role=='mysql' else '6379'),
                    '--mount','type=volume,src='+volume+',dst='+('/var/lib/mysql' if role=='mysql' else '/data')]
            if role == 'mysql':
                env.update(mysql_secret_env(secrets))
                args += ['--env','MYSQL_ROOT_PASSWORD','--env','MYSQL_PASSWORD','--env','MYSQL_DATABASE=m21_'+self.run.replace('-',''),'--env','MYSQL_USER=m21']
            elif secrets is not None:
                raise Rejected('secret')
            args += [self.descriptor[role+'_image_id']]
        elif operation == 'container-inspect':
            template = '{"id":{{json .Id}},"labels":{{json .Config.Labels}},"image":{{json .Image}},"ports":{{json .NetworkSettings.Ports}},"bindings":{{json .HostConfig.PortBindings}},"mounts":{{json .Mounts}},"running":{{json .State.Running}}}'
            args = ['container','inspect','--format',template,name]
        elif operation == 'volume-inspect':
            args = ['volume','inspect','--format','{"name":{{json .Name}},"labels":{{json .Labels}}}',volume]
        elif operation in ('container-stop','container-remove','container-start'):
            evidence = self.inspected.get(('container',role))
            if evidence is None or not self.daemon_confirmed:
                raise Rejected('unverified-container')
            verb = operation.split('-')[1]
            args = ['container',verb]
            if verb == 'stop':
                args += ['--time','15' if role=='mysql' else '10']
            elif verb == 'remove':
                args[1] = 'rm'
                args += ['--force']
            args += [evidence['id']]
        elif operation == 'volume-remove':
            if ('volume',role) not in self.inspected or not self.daemon_confirmed:
                raise Rejected('unverified-volume')
            args = ['volume','rm',volume]
        elif operation == 'resources-absent':
            args=['inspect','--format','M21_PRESENT',name,volume]
        else:
            raise Rejected('operation')
        return base+args, env

    def adopt(self, operation, role, code, output, stderr=b''):
        import re
        if operation=='resources-absent':
            if not self.daemon_confirmed: raise Rejected('daemon-unconfirmed')
            expected=['error: no such object: '+resource_name(self.run,role),'error: no such object: '+resource_name(self.run,role)+'-data']
            if code!=1 or output!=b'\n' or stderr!=('\n'.join(expected)+'\n').encode('utf-8'):
                raise Rejected('resources-retained-or-unknown')
            self.absence_candidates.add(role)
            return {'absence_candidate':True,'absent':False,'requires_same_daemon_after':True}
        if code != 0 or not isinstance(output,bytes) or len(output)>MAX_FRAME:
            raise Rejected('cli-result')
        text = output.decode('utf-8').strip()
        if operation == 'daemon-info':
            if text != self.descriptor['daemon_id']:
                self.daemon_confirmed=False
                self.inspected.clear()
                self.absence_candidates.clear()
                raise Rejected('daemon-changed')
            self.daemon_confirmed = True
            return {'daemon_confirmed':True,'confirmed_absent_resources':[{'role':role,'container':resource_name(self.run,role),'volume':resource_name(self.run,role)+'-data'} for role in sorted(self.absence_candidates)]}
        if operation in ('container-create','volume-create'):
            expected = resource_name(self.run,role)+'-data'
            if operation == 'volume-create' and text != expected:
                raise Rejected('created-volume')
            if operation == 'container-create' and not re.fullmatch('[0-9a-f]{64}',text):
                raise Rejected('created-container')
            return {'created_identity':text,'ownership_inspected':False}
        if operation in ('container-inspect','volume-inspect'):
            try:
                value = json.loads(text,object_pairs_hook=unique_object)
            except ValueError:
                raise Rejected('inspect-json') from None
            expected_labels = {'m21.owner':self.owner,'m21.run':self.run,'m21.purpose':role}
            if type(value) is not dict or type(value.get('labels')) is not dict or any(value['labels'].get(k)!=v for k,v in expected_labels.items()):
                raise Rejected('ownership')
            kind = operation.split('-')[0]
            if kind == 'volume':
                if set(value) != {'name','labels'} or value['name'] != resource_name(self.run,role)+'-data':
                    raise Rejected('volume-identity')
            else:
                if set(value) != {'id','labels','image','ports','bindings','mounts','running'} or not re.fullmatch('[0-9a-f]{64}',str(value['id'])) or value['image'] != self.descriptor[role+'_image_id'] or type(value['running']) is not bool:
                    raise Rejected('container-identity')
                port_key = '3306/tcp' if role=='mysql' else '6379/tcp'
                bindings=value['bindings']
                if type(bindings) is not dict or set(bindings)!={port_key} or type(bindings[port_key]) is not list or len(bindings[port_key])!=1:
                    raise Rejected('binding-shape')
                binding=bindings[port_key][0]
                if type(binding) is not dict or set(binding)!={'HostIp','HostPort'} or binding['HostIp']!='127.0.0.1' or type(binding['HostPort']) is not str or (binding['HostPort'] and (not re.fullmatch('[0-9]{1,5}',binding['HostPort']) or not 1<=int(binding['HostPort'])<=65535)):
                    raise Rejected('binding-loopback')
                ports=value['ports']
                if ports is None: ports={}
                if type(ports) is not dict or any(key!=port_key and entry is not None for key,entry in ports.items()):
                    raise Rejected('unexpected-published-port')
                port=None
                if ports.get(port_key) is not None:
                    if type(ports[port_key]) is not list or len(ports[port_key])!=1:
                        raise Rejected('port-shape')
                    port=ports[port_key][0]
                    if type(port) is not dict or set(port)!={'HostIp','HostPort'} or port['HostIp']!='127.0.0.1' or type(port['HostPort']) is not str or not re.fullmatch('[0-9]{1,5}',port['HostPort']) or not 1<=int(port['HostPort'])<=65535:
                        raise Rejected('port-binding')
                    if role in self.ports and self.ports[role]!=port:
                        raise Rejected('port-changed')
                elif value['running'] or role in self.ports:
                    raise Rejected('missing-published-port')
                mounts = value['mounts']
                if type(mounts) is not list or len(mounts) != 1:
                    raise Rejected('mount-shape')
                mount = mounts[0]
                if type(mount) is not dict or mount.get('Type') != 'volume' or mount.get('Name') != resource_name(self.run,role)+'-data' or mount.get('Destination') != ('/var/lib/mysql' if role=='mysql' else '/data') or mount.get('RW') is not True:
                    raise Rejected('mount-identity')
                if port is not None: self.ports[role] = port.copy()
            self.inspected[(kind,role)] = value
            return {'ownership_inspected':True,'identity':value.get('id',value.get('name')),'loopback_port':self.ports.get(role)}
        if operation=='container-start' and role=='valkey':
            evidence=self.inspected.get(('container','valkey'))
            if evidence is not None: self.valkey_started_id=evidence['id']
        return {'command_completed':True,'absent':False}

def parse_valkey_info_frame(data):
    """単一RESP bulkを検証し、INFO本文を返さずrun_idだけを抽出する。"""
    import re
    if len(data)>MAX_FRAME: raise Rejected('info-size')
    if not data: return None
    if data[0]!=ord('$'): raise Rejected('info-framing')
    header_end=data.find(b'\r\n')
    if header_end<0:
        if len(data)>7: raise Rejected('info-framing')
        return None
    header=re.fullmatch(rb'\$(0|[1-9][0-9]{0,4})',bytes(data[:header_end]))
    if header is None: raise Rejected('info-framing')
    payload_start=header_end+2
    payload_length=int(header.group(1))
    expected=payload_start+payload_length+2
    if expected>MAX_FRAME: raise Rejected('info-size')
    if len(data)<expected: return None
    if len(data)!=expected: raise Rejected('info-extra')
    if data[-2:]!=b'\r\n': raise Rejected('info-framing')
    fields=[line for line in bytes(data[payload_start:-2]).split(b'\r\n') if line.startswith(b'run_id:')]
    if len(fields)!=1: raise Rejected('info-run-id')
    match=re.fullmatch(rb'run_id:([0-9a-f]{40})',fields[0])
    if match is None: raise Rejected('info-run-id')
    return match.group(1).decode('ascii')


def read_owned_valkey_identity(boundary, deadline, clock_delta=None):
    """fresh inspect済みのowned portへ固定INFOを1回送る。追加子とCLIは0。"""
    import re
    proof={'transport':'owned-loopback-info','response_complete':False,'socket_closed':None,
           'within_deadline':False,'cli_spawned':False,'childproof':None,'green':False,
           'reason':'info-unavailable','response_bytes':0,'response_completed_ns':None,'observed_ns':None,'deadline_ns':deadline}
    connection=None
    identity=None
    def admitted():
        return time.monotonic_ns()<deadline and (clock_delta is None or abs((time.clock_gettime_ns(time.CLOCK_BOOTTIME)-time.monotonic_ns())-clock_delta)<=100000000)
    def wait_for(writing):
        if not admitted(): raise Rejected('timeout')
        remaining=max(0,(deadline-time.monotonic_ns())/1e9)
        readable,writable,exceptional=select.select([] if writing else [connection],[connection] if writing else [],[connection],remaining)
        if not admitted(): raise Rejected('timeout')
        if exceptional: raise Rejected('info-socket-error')
        if not (writable if writing else readable): raise Rejected('timeout')
    try:
        container=boundary.inspected.get(('container','valkey'))
        port=boundary.ports.get('valkey')
        labels={'m21.owner':boundary.owner,'m21.run':boundary.run,'m21.purpose':'valkey'}
        if boundary.daemon_confirmed is not True or type(container) is not dict or container.get('running') is not True or container.get('image')!=boundary.descriptor['valkey_image_id'] or type(container.get('id')) is not str or not re.fullmatch('[0-9a-f]{64}',container['id']) or type(container.get('labels')) is not dict or any(container['labels'].get(k)!=v for k,v in labels.items()):
            raise Rejected('info-owner-unverified')
        if type(port) is not dict or set(port)!={'HostIp','HostPort'} or port['HostIp']!='127.0.0.1' or type(port['HostPort']) is not str or not re.fullmatch('[0-9]{1,5}',port['HostPort']) or not 1<=int(port['HostPort'])<=65535 or type(container.get('ports')) is not dict or container['ports'].get('6379/tcp')!=[port]:
            raise Rejected('info-owner-unverified')
        if not admitted(): raise Rejected('timeout')
        connection=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
        connection.setblocking(False)
        status=connection.connect_ex(('127.0.0.1',int(port['HostPort'])))
        if status not in (0,errno.EISCONN):
            if status not in (errno.EINPROGRESS,errno.EALREADY,errno.EWOULDBLOCK): raise Rejected('info-socket-error')
            wait_for(True)
            if connection.getsockopt(socket.SOL_SOCKET,socket.SO_ERROR)!=0: raise Rejected('info-socket-error')
        command=b'*2\r\n$4\r\nINFO\r\n$6\r\nserver\r\n'
        offset=0
        while offset<len(command):
            wait_for(True)
            try: sent=connection.send(command[offset:])
            except BlockingIOError: continue
            if sent==0: raise Rejected('info-socket-error')
            offset+=sent
        response=bytearray()
        while True:
            wait_for(False)
            try: chunk=connection.recv(min(4096,MAX_FRAME+1-len(response)))
            except BlockingIOError: continue
            if not chunk: raise Rejected('info-partial')
            response.extend(chunk); proof['response_bytes']=len(response)
            run_id=parse_valkey_info_frame(response)
            if run_id is not None:
                # 取得済みと直後queuedの余剰bytesを拒否する。将来の余剰bytes不在は主張しない。
                if select.select([connection],[],[],0)[0] and connection.recv(1): raise Rejected('info-extra')
                proof['response_complete']=True
                proof['response_completed_ns']=time.monotonic_ns()
                identity={'run_id':run_id,'loopback_port':port.copy()}
                proof['reason']='ok'
                break
    except Rejected as rejected:
        proof['reason']=rejected.args[0]
    except OSError:
        proof['reason']='info-socket-error'
    finally:
        if connection is not None:
            try: connection.close(); proof['socket_closed']=True
            except OSError: proof['socket_closed']=False; proof['reason']='info-close'
        proof['observed_ns']=time.monotonic_ns()
        proof['within_deadline']=proof['response_complete'] and proof['response_completed_ns'] is not None and proof['response_completed_ns']<deadline and (clock_delta is None or abs((time.clock_gettime_ns(time.CLOCK_BOOTTIME)-time.monotonic_ns())-clock_delta)<=100000000)
    proof['green']=identity is not None and proof['response_complete'] and proof['socket_closed'] is True and proof['within_deadline'] and proof['observed_ns']<deadline
    if identity is not None and (not proof['within_deadline'] or proof['observed_ns']>=deadline): proof['reason']='timeout'
    return (identity if proof['green'] else None),proof


def with_valkey_identity(boundary, request, adopted, deadline, clock_delta=None):
    if adopted is None or request['phase']!='work' or request['operation']!='container-inspect' or request['resource']!='valkey':
        return adopted,True
    container=boundary.inspected.get(('container','valkey'))
    if type(container) is not dict or container.get('running') is not True or boundary.valkey_started_id!=container.get('id'):
        return adopted,True
    identity,proof=read_owned_valkey_identity(boundary,deadline,clock_delta)
    result=adopted.copy()
    result.pop('run_id',None); result.pop('socket_proof',None)
    result['socket_proof']=proof
    if identity is not None: result.update(identity)
    return result,proof['green']


TEMP_ROOT = Path('/mnt/c/Users/kenta/AppData/Local/Temp')

def read_owned_json(dfd, name):
    import stat
    fd = os.open(name,os.O_RDONLY|os.O_NOFOLLOW,dir_fd=dfd)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_FRAME:
            raise Rejected('owned-file')
        raw = os.read(fd,MAX_FRAME+1)
        return json.loads(raw.decode('utf-8-sig'),object_pairs_hook=unique_object)
    except (ValueError,UnicodeError):
        raise Rejected('owned-json') from None
    finally:
        os.close(fd)


def guard_run(directory, owner, run, nonce):
    import re
    canonical_uuid(owner); canonical_uuid(run)
    if not re.fullmatch('[0-9a-f]{32}',nonce):
        raise Rejected('nonce')
    path = Path(directory)
    if not path.is_absolute() or '..' in path.parts or str(path) != directory or path.name != 'bridge' or path.parent.parent != TEMP_ROOT or not re.fullmatch('m21-windows-[A-Za-z0-9_-]+',path.parent.name):
        raise Rejected('run-path')
    fd = os.open('/',os.O_RDONLY|os.O_DIRECTORY)
    parent = None
    try:
        for component in path.parts[1:]:
            newfd = os.open(component,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
            if component == 'bridge':
                parent = os.dup(fd)
            os.close(fd); fd = newfd
        expected = {'owner':owner,'nonce':nonce}
        if read_owned_json(parent,'owner.json') != expected:
            raise Rejected('parent-owner')
        if read_owned_json(fd,'owner.json') != expected|{'run':run,'purpose':'m21-docker-bridge'}:
            raise Rejected('bridge-owner')
        if set(os.listdir(fd)) != {'owner.json'}:
            raise Rejected('run-not-fresh')
        return fd
    except BaseException:
        os.close(fd)
        raise
    finally:
        if parent is not None: os.close(parent)


def publish(dfd, name, value):
    atomic_receipt(dfd,name,value)


def runtime_identity():
    import stat
    socket = os.stat('/var/run/docker.sock',follow_symlinks=False)
    if not stat.S_ISSOCK(socket.st_mode):
        raise Rejected('docker-socket')
    return {'boot_id':Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
            'pid_namespace':os.readlink('/proc/self/ns/pid'),
            'socket_dev':socket.st_dev,'socket_ino':socket.st_ino}


def send_private(fd, value, deadline):
    raw = json.dumps(value,separators=(',',':')).encode()
    if len(raw)>65536: raise Rejected('private-length')
    os.set_blocking(fd,False)
    offset=0
    while offset<len(raw) and time.monotonic_ns()<deadline:
        if select.select([], [fd], [], max(0,(deadline-time.monotonic_ns())/1e9))[1]:
            try: offset += os.write(fd,raw[offset:])
            except BrokenPipeError: return False
    return offset==len(raw)


def spawn_watchdog(argv, env, deadline, global_deadline, directory_fd, common, request):
    import fcntl
    if common.get('clock_delta') is not None and abs((time.clock_gettime_ns(time.CLOCK_BOOTTIME)-time.monotonic_ns())-common['clock_delta'])>100000000:
        raise Rejected('clock-discontinuity')
    control_r,control_w = os.pipe2(os.O_CLOEXEC)
    result_r,result_w = os.pipe2(os.O_CLOEXEC)
    if time.monotonic_ns() >= min(deadline,global_deadline):
        for fd in (control_r,control_w,result_r,result_w): os.close(fd)
        raise Rejected('expired-before-watchdog')
    pid = os.fork()
    if pid==0:
        try:
            # 継承stdioを持ち続けずWindows relayのEOFとLinux subtreeを分離する。
            kept=[fcntl.fcntl(fd,fcntl.F_DUPFD_CLOEXEC,10) for fd in (control_r,directory_fd,result_w)]
            for target,source in zip((3,4,5),kept): os.dup2(source,target,inheritable=False)
            null=os.open('/dev/null',os.O_RDWR)
            for target in (0,1,2): os.dup2(null,target)
            os.closerange(6,65536)
            prefix=request.get('receipt_prefix','request-%05d'%request['request_id'])
            started=(4,prefix+'-started.json',common|{'type':'request-started','request_id':request['request_id'],'operation':request['operation'],'resource':request['resource'],'deadline_ns':deadline})
            result=supervise(argv,env,deadline,3,global_deadline,common.get('clock_delta'),started)
            private=result.pop('_stdout_hex')
            private_stderr=result.pop('_stderr_hex')
            receipt=common|{'type':'request','request_id':request['request_id'],
                    'operation':request['operation'],'resource':request['resource'],'phase':request['phase'],
                    'deadline_ns':deadline,'linux':result,'daemon_resource_confirmed':False}
            prefix=request.get('receipt_prefix','request-%05d'%request['request_id'])
            publish(4,prefix+'.json',receipt)
            send_private(5,{'linux':result,'stdout_hex':private,'stderr_hex':private_stderr},min(global_deadline,deadline+2000000000,time.monotonic_ns()+500000000))
            os.close(5); os.close(4)
            os._exit(0)
        except BaseException:
            os._exit(91)
    os.close(control_r); os.close(result_w)
    os.set_blocking(result_r,False)
    try: pidfd=os.pidfd_open(pid)
    except OSError: pidfd=None
    active={'pid':pid,'pidfd':pidfd,'control':control_w,'result':result_r,'parent_primary':None if pidfd is not None else 'watchdog-pidfd-unavailable',
            'data':bytearray(),'request':request,'deadline':deadline,'cancelled':False}
    if pidfd is None: close_active_control(active)
    return active


def close_active_control(active):
    if not active['cancelled']:
        os.close(active['control'])
        active['cancelled']=True


def publish_denial(dfd, common, protocol, frame, deadline, code, spawn_upper_bound):
    if code not in ('admission-denied','ownership-unverified','daemon-unconfirmed'):
        raise Rejected('deny-code')
    value=common|{'request_id':frame['request_id'],'operation':frame['operation'],'resource':frame['resource'],
            'phase':frame['phase'],'deadline_ns':deadline,'code':code,'not_spawned':True,'green':False,
            'childproof':None,'linux':None,'watchdog_exited':None,'watchdog_exit':None,'result':None,
            'cleanup_request_slots':protocol.cleanup_count,'cleanup_cli_spawn_upper_bound':spawn_upper_bound}
    publish(dfd,'request-%05d.json'%frame['request_id'],value|{'type':'request'})
    publish(dfd,'request-%05d-observed.json'%frame['request_id'],value|{'type':'observed'})


def bridge_loop(dfd, directory, owner, run, nonce, input_commit):
    t0=time.monotonic_ns()
    clock_delta=time.clock_gettime_ns(time.CLOCK_BOOTTIME)-t0
    identity=runtime_identity()
    common={'version':1,'owner':owner,'run':run,'nonce':nonce,'inputCommit':input_commit,
            'bridgeSourceSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'pythonSha256':hashlib.sha256(Path('/usr/bin/python3').resolve().read_bytes()).hexdigest(),
            'dockerSha256':hashlib.sha256(Path('/usr/bin/docker').read_bytes()).hexdigest(),
            't0_ns':t0,'clock_delta':clock_delta,'bridge_identity':process_identity(os.getpid())}|identity
    publish(dfd,'ready.json',common|{'type':'ready','ready':True})
    protocol=Protocol(run,t0,owner)
    active=None
    terminal='shutdown'
    overall_green=True
    terminal_watchdog=None
    cleanup_spawn_upper_bound=0
    last_completed_id=None
    last_completed_deadline=None
    cancelled_ids=set()
    phase='init-read'
    phase_started_ns=time.monotonic_ns()
    phase_deadline_ns=t0+2000000000
    terminal_ns=None
    terminal_code=None
    try:
        frame=read_frame(0,phase_deadline_ns)
        phase='init-validation'
        phase_started_ns=time.monotonic_ns()
        if type(frame) is not dict or set(frame)!={'type','owner','run','global_offset_ms','work_offset_ms'}:
            raise Rejected('init-schema')
        protocol.initialize(frame,time.monotonic_ns())
        phase='init-config'
        phase_started_ns=time.monotonic_ns()
        phase_deadline_ns=protocol.work_deadline
        os.mkdir('config',mode=0o700,dir_fd=dfd)
        phase='init-preflight'
        phase_started_ns=time.monotonic_ns()
        descriptor=preflight(dfd,directory,common,protocol,identity)
        boundary=DockerBoundary(run,directory+'/config',descriptor,owner)
        phase='initialized-publish'
        phase_started_ns=time.monotonic_ns()
        publish(dfd,'initialized.json',common|{'type':'initialized','global_deadline_ns':protocol.global_deadline,'work_deadline_ns':protocol.work_deadline,'descriptor':descriptor,'green':True,'preflight':{'count':3,'green':True,'ids':[1,2,3]}})
        phase='bind-read'
        phase_started_ns=time.monotonic_ns()
        phase_deadline_ns=min(protocol.work_deadline,phase_started_ns+2000000000)
        binding=read_frame(0,phase_deadline_ns)
        phase='bind-validation'
        phase_started_ns=time.monotonic_ns()
        protocol.check(binding,('type','owner','run','descriptor'))
        if binding['type']!='bind' or binding['descriptor']!=descriptor: raise Rejected('descriptor-bind')
        boundary.daemon_confirmed=True
        phase='bound-publish'
        phase_started_ns=time.monotonic_ns()
        publish(dfd,'bound.json',common|{'type':'bound','descriptor':descriptor,'green':True})
        phase='resident'
        phase_started_ns=time.monotonic_ns()
        phase_deadline_ns=protocol.global_deadline
        while time.monotonic_ns()<protocol.global_deadline:
            if abs((time.clock_gettime_ns(time.CLOCK_BOOTTIME)-time.monotonic_ns())-clock_delta)>100000000:
                raise Rejected('clock-discontinuity')
            if runtime_identity()!=identity:
                raise Rejected('runtime-changed')
            now=time.monotonic_ns()
            readers=[0]+([active['result']] if active else [])
            ready=select.select(readers,[],[],min(.05,max(0,(protocol.global_deadline-now)/1e9)))[0]
            if active and active['result'] in ready:
                chunk=os.read(active['result'],8192)
                if chunk:
                    active['data'].extend(chunk)
                    if len(active['data'])>65536: raise Rejected('private-length')
                else:
                    # receiptとwatchdog自身の実waitは別結果。
                    result=None
                    try: result=json.loads(active['data'])
                    except (ValueError,UnicodeError): pass
                    waited,status=os.waitpid(active['pid'],os.WNOHANG)
                    if not waited:
                        # EOF直後のexit schedulingだけを既存exit枠へclampして待つ。
                        cap=min(protocol.global_deadline,active['deadline']+2000000000)
                        while not waited and time.monotonic_ns()<cap:
                            waited,status=os.waitpid(active['pid'],os.WNOHANG)
                            if not waited: select.select([],[],[],.001)
                    adopted=None
                    request=active['request']
                    if waited and os.waitstatus_to_exitcode(status)==0 and result is not None and (result.get('linux',{}).get('green') is True or (request['operation']=='resources-absent' and result.get('linux',{}).get('physical_completed') is True and result['linux'].get('cli_exit')==1)):
                        try: adopted=boundary.adopt(request['operation'],request['resource'],result['linux']['cli_exit'],bytes.fromhex(result['stdout_hex']),bytes.fromhex(result['stderr_hex']))
                        except (Rejected,ValueError,UnicodeError): pass
                    socket_green=True
                    if not active['cancelled']:
                        adopted,socket_green=with_valkey_identity(boundary,request,adopted,min(active['deadline'],protocol.work_deadline,protocol.global_deadline),common['clock_delta'])
                    green=adopted is not None and not active['cancelled'] and socket_green
                    overall_green=overall_green and green
                    publish(dfd,'request-%05d-observed.json'%request['request_id'],common|{'type':'observed','request_id':request['request_id'],
                            'operation':request['operation'],'resource':request['resource'],'deadline_ns':active['deadline'],
                            'watchdog_parent_primary':active['parent_primary'],'watchdog_exited':bool(waited),'watchdog_exit':os.waitstatus_to_exitcode(status) if waited else None,
                            'result':adopted,'green':green,'not_spawned':False,'cleanup_request_slots':protocol.cleanup_count,'cleanup_cli_spawn_upper_bound':cleanup_spawn_upper_bound})
                    os.close(active['result'])
                    if active['pidfd'] is not None: os.close(active['pidfd'])
                    close_active_control(active)
                    if not waited: raise Rejected('watchdog-retained')
                    if result is not None and type(result.get('linux')) is dict and result['linux'].get('cli_exit') is not None and result['linux'].get('children_reaped') is True:
                        last_completed_id=request['request_id']
                        last_completed_deadline=active['deadline']
                    active=None
            if 0 in ready:
                phase='request-read'
                phase_started_ns=time.monotonic_ns()
                phase_deadline_ns=min(protocol.global_deadline,phase_started_ns+1000000000)
                frame=read_frame(0,phase_deadline_ns)
                phase='request-validation'
                phase_started_ns=time.monotonic_ns()
                if type(frame) is not dict: raise Rejected('schema')
                if frame.get('type')=='shutdown':
                    protocol.check(frame,('type','owner','run'))
                    if active: close_active_control(active)
                    break
                if frame.get('type')=='cancel':
                    protocol.check(frame,('type','owner','run','request_id'))
                    cancel_id=integer(frame['request_id'],1,65535)
                    if cancel_id in cancelled_ids: raise Rejected('cancel')
                    if active is None:
                        if cancel_id!=last_completed_id: raise Rejected('cancel')
                        cancelled_ids.add(cancel_id)
                        overall_green=False
                        publish(dfd,'request-%05d-late-cancel.json'%cancel_id,common|{'type':'late-cancel-ack','request_id':cancel_id,
                                'deadline_ns':last_completed_deadline,'green':False,'late_cancel':True,'no_signal':True,
                                'signal_count':0,'cli_spawned':False,'childproof':None,'deadline_reset':False})
                        continue
                    if active['cancelled'] or cancel_id!=active['request']['request_id']: raise Rejected('cancel')
                    cancelled_ids.add(cancel_id)
                    close_active_control(active)
                    overall_green=False
                    continue
                if active: raise Rejected('concurrent-request')
                last_completed_id=None
                last_completed_deadline=None
                try:
                    operation,deadline=protocol.request(frame,time.monotonic_ns())
                except RequestDenied as denied:
                    publish_denial(dfd,common,protocol,frame,denied.deadline,'admission-denied',cleanup_spawn_upper_bound)
                    overall_green=False
                    continue
                writes=('container-create','volume-create','container-start','container-stop','container-remove','volume-remove')
                if not boundary.daemon_confirmed and operation in writes:
                    publish_denial(dfd,common,protocol,frame,deadline,'daemon-unconfirmed',cleanup_spawn_upper_bound)
                    overall_green=False
                    continue
                if operation=='container-create' and ('volume',frame['resource']) not in boundary.inspected:
                    publish_denial(dfd,common,protocol,frame,deadline,'ownership-unverified',cleanup_spawn_upper_bound)
                    overall_green=False
                    continue
                try:
                    argv,env=boundary.command(operation,frame['resource'],frame.pop('secrets',None))
                except Rejected as denied:
                    if denied.args[0] not in ('unverified-container','unverified-volume'): raise
                    publish_denial(dfd,common,protocol,frame,deadline,'ownership-unverified',cleanup_spawn_upper_bound)
                    overall_green=False
                    continue
                if time.monotonic_ns()>=deadline:
                    publish_denial(dfd,common,protocol,frame,deadline,'admission-denied',cleanup_spawn_upper_bound)
                    env.clear()
                    overall_green=False
                    continue
                try:
                    active=spawn_watchdog(argv,env,deadline,protocol.global_deadline,dfd,common,frame)
                except Rejected as denied:
                    if denied.args[0]!='expired-before-watchdog': raise
                    publish_denial(dfd,common,protocol,frame,deadline,'admission-denied',cleanup_spawn_upper_bound)
                    env.clear()
                    overall_green=False
                    continue
                if frame['phase']=='cleanup': cleanup_spawn_upper_bound+=1
                env.clear()
        else:
            terminal='global-deadline'
            overall_green=False
    except (Rejected,OSError,ValueError,UnicodeError) as error:
        terminal_ns=time.monotonic_ns()
        terminal_code=terminal_diagnostic_code(error)
        terminal='bridge-terminal'
        overall_green=False
    finally:
        if terminal_ns is None: terminal_ns=time.monotonic_ns()
        if active:
            close_active_control(active)
            limit=min(protocol.global_deadline or t0+2000000000,active['deadline']+2000000000,time.monotonic_ns()+2000000000)
            waited=0
            while time.monotonic_ns()<limit:
                waited,status=os.waitpid(active['pid'],os.WNOHANG)
                if waited: break
                select.select([],[],[],.001)
            terminal_watchdog={'pid':active['pid'],'request_id':active['request']['request_id'],'exited':bool(waited),'exit_code':os.waitstatus_to_exitcode(status) if waited else None}
            overall_green=False
            os.close(active['result'])
            if active['pidfd'] is not None: os.close(active['pidfd'])
        publish(dfd,'bridge-exit.json',common|{'type':'bridge-exit','reason':terminal,'green':overall_green,
                'terminal_phase':phase,'terminal_code':terminal_code,'phase_started_ns':phase_started_ns,
                'phase_deadline_ns':phase_deadline_ns,'terminal_ns':terminal_ns,
                'cleanup_request_slots':protocol.cleanup_count,'cleanup_cli_spawn_upper_bound':cleanup_spawn_upper_bound,'terminal_watchdog':terminal_watchdog,'linux_watchdog_retained':bool(active and not waited),'windows_actual_exit_confirmed':False,
                'windows_stdout_eof_confirmed':False,'windows_stderr_eof_confirmed':False})
    return 0 if overall_green else 1


def main():
    import re
    try:
        arguments=sys.argv[1:]
        keys={'--run-directory','--owner','--run','--nonce','--input-commit'}
        if len(arguments)!=10 or set(arguments[::2])!=keys:
            raise Rejected('arguments')
        values=dict(zip(arguments[::2],arguments[1::2]))
        if not re.fullmatch('[0-9a-f]{40}',values['--input-commit']): raise Rejected('input-commit')
        dfd=guard_run(values['--run-directory'],values['--owner'],values['--run'],values['--nonce'])
        try: return bridge_loop(dfd,values['--run-directory'],values['--owner'],values['--run'],values['--nonce'],values['--input-commit'])
        finally: os.close(dfd)
    except (Rejected,OSError,ValueError,UnicodeError):
        return 78


# bootstrap専用。stdin wireから任意commandへ到達する経路を持たない。
def preflight_one(argv, dfd, common, number, global_deadline, work_deadline):
    deadline=min(work_deadline,time.monotonic_ns()+3000000000)
    if time.monotonic_ns()>=deadline: raise Rejected('preflight-expired')
    request={'request_id':number,'phase':'work','operation':'preflight','resource':'daemon','receipt_prefix':'preflight-%d'%number}
    active=spawn_watchdog(argv,{'PATH':'/usr/bin:/bin','LANG':'C'},deadline,global_deadline,dfd,common,request)
    waited=0
    try:
        while time.monotonic_ns()<global_deadline:
            ready=select.select([0,active['result']],[],[],.01)[0]
            if 0 in ready:
                raise Rejected('preflight-parent-eof-or-premature-frame')
            if active['result'] in ready:
                chunk=os.read(active['result'],8192)
                if chunk:
                    active['data'].extend(chunk)
                    if len(active['data'])>65536: raise Rejected('private-length')
                else: break
        else: raise Rejected('global-deadline')
        result=json.loads(active['data'])
        cap=min(global_deadline,deadline+2000000000)
        waited=0
        while time.monotonic_ns()<cap:
            waited,status=os.waitpid(active['pid'],os.WNOHANG)
            if waited: break
            select.select([],[],[],.001)
        green=bool(waited) and os.waitstatus_to_exitcode(status)==0 and result.get('linux',{}).get('green') is True and not active['cancelled']
        publish(dfd,'preflight-%d-observed.json'%number,common|{'type':'preflight-observed','preflight_id':number,
                'deadline_ns':deadline,'watchdog_parent_primary':active['parent_primary'],'watchdog_exited':bool(waited),'watchdog_exit':os.waitstatus_to_exitcode(status) if waited else None,'green':green})
        if not green: raise Rejected('preflight-incomplete')
        return bytes.fromhex(result['stdout_hex']).decode('utf-8').strip()
    finally:
        close_active_control(active)
        limit=min(global_deadline,deadline+2000000000,time.monotonic_ns()+2000000000)
        while not waited and time.monotonic_ns()<limit:
            waited,status=os.waitpid(active['pid'],os.WNOHANG)
            if not waited: select.select([],[],[],.001)
        if not waited: common.setdefault('retained_watchdogs',[]).append({'pid':active['pid'],'request_id':number})
        os.close(active['result'])
        if active['pidfd'] is not None: os.close(active['pidfd'])


def preflight(dfd,directory,common,protocol,identity):
    base=['/usr/bin/docker','--config',directory+'/config','--host','unix:///var/run/docker.sock']
    commands=[base+['info','--format','{{.ID}}'],base+['image','inspect','--format','{{.Id}}','mysql:8.0'],
              base+['image','inspect','--format','{{.Id}}','valkey/valkey:8-alpine']]
    values=[]
    for number,argv in enumerate(commands,1):
        if runtime_identity()!=identity: raise Rejected('runtime-changed')
        values.append(preflight_one(argv,dfd,common,number,protocol.global_deadline,protocol.work_deadline))
    return {'daemon_id':values[0],'mysql_image_id':values[1],'valkey_image_id':values[2]}

if __name__=='__main__':
    raise SystemExit(main())
