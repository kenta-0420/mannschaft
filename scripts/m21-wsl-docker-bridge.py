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
import struct
import sys
import time
import uuid

OWNER = '1d767b20-825c-4edc-b397-8d4367036462'
MAX_FRAME = 16384
OPERATIONS = {'daemon-info': ('daemon',), 'container-inspect': ('mysql','valkey'),
              'container-stop': ('mysql','valkey'), 'container-remove': ('mysql','valkey'),
              'container-absent': ('mysql','valkey'), 'volume-inspect': ('mysql','valkey'),
              'volume-remove': ('mysql','valkey'), 'volume-absent': ('mysql','valkey')}

class Rejected(Exception):
    """外部入力を例外本文へ含めない。"""


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
    def __init__(self, run, t0):
        self.run = canonical_uuid(run)
        self.t0 = t0
        self.global_deadline = None
        self.work_deadline = None
        self.next_request = 1
        self.cleanup_count = 0

    def check(self, data, keys):
        if type(data) is not dict or set(data) != set(keys) or data.get('owner') != OWNER or data.get('run') != self.run:
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
        self.check(data, ('type','owner','run','request_id','operation','resource','deadline_offset_ms'))
        if self.global_deadline is None or data['type'] != 'request':
            raise Rejected('uninitialized')
        request_id = integer(data['request_id'], 1, 65535)
        operation, role = data['operation'], data['resource']
        if request_id != self.next_request or type(operation) is not str or operation not in OPERATIONS or role not in OPERATIONS[operation]:
            raise Rejected('operation')
        deadline = min(self.global_deadline, self.t0 + integer(data['deadline_offset_ms'],1,3300000)*1000000)
        cap_ms = (15000 if role == 'mysql' else 10000) if operation == 'container-stop' else 3000
        if deadline <= now or deadline - now > cap_ms*1000000:
            raise Rejected('deadline')
        if self.cleanup_count >= 14:
            raise Rejected('cleanup-limit')
        self.next_request += 1
        self.cleanup_count += 1
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


def atomic_receipt(directory, name, value):
    """所有済みdirectoryのdirfdに限定、既存receiptを置換しない。"""
    raw = json.dumps(value, separators=(',', ':'), sort_keys=True).encode('utf-8')
    if len(raw) > MAX_FRAME:
        raise Rejected('receipt-length')
    dfd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
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


def supervise(argv, env, body_deadline, control_fd):
    """要求専用watchdog内部専用。wireからargv/envを受け取らない。

    呼出側はforkした専用子でのみ呼ぶ。watchdogはbridgeと別session、
    subreaperをCLIより先に設定する。CLI leaderは全group signal判断が終わるまで未reap。
    """
    os.setsid()
    subreaper()
    out_r, out_w = os.pipe2(os.O_CLOEXEC)
    err_r, err_w = os.pipe2(os.O_CLOEXEC)
    started_r, started_w = os.pipe2(os.O_CLOEXEC)
    pid = os.fork()
    if pid == 0:
        try:
            os.close(started_r)
            os.setsid()
            os.dup2(out_w, 1)
            os.dup2(err_w, 2)
            null = os.open('/dev/null', os.O_RDONLY)
            os.dup2(null, 0)
            os.write(started_w, b'1')
            os.closerange(3, 65536)
            os.execve(argv[0], argv, env)
        except BaseException:
            os._exit(127)
    os.close(out_w); os.close(err_w); os.close(started_w)
    # child session成立のack前にPGIDへsignalしない。
    acknowledged = bool(select.select([started_r], [], [], max(0,(body_deadline-time.monotonic_ns())/1e9))[0]) and os.read(started_r,1) == b'1'
    os.close(started_r)
    pidfd = os.pidfd_open(pid)
    identity = process_identity(pid)
    eof = {out_r: False, err_r: False}
    captured = {out_r: bytearray(), err_r: bytearray()}
    for fd in eof:
        os.set_blocking(fd, False)
    os.set_blocking(control_fd, False)
    reason = 'completed'
    leader_seen = False
    output_full = False
    force_count = 0
    # WNOWAITはleaderのPID/PGID anchorを保持する。poll/waitは使用しない。
    while time.monotonic_ns() < body_deadline:
        status = os.waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        leader_seen = status is not None
        if leader_seen:
            break
        readers = [fd for fd in eof if not eof[fd]] + [control_fd]
        ready = select.select(readers, [], [], min(.01,max(0,(body_deadline-time.monotonic_ns())/1e9)))[0]
        if control_fd in ready:
            os.read(control_fd, 1)
            reason = 'cancel-or-bridge-eof'
            break
        for fd in ready:
            if fd == control_fd:
                continue
            chunk = os.read(fd,4096)
            if not chunk:
                eof[fd] = True
            elif len(captured[fd])+len(chunk) > MAX_FRAME:
                output_full = True
                reason = 'output-full'
            else:
                captured[fd].extend(chunk)
        if output_full:
            break
    if not leader_seen and reason == 'completed':
        reason = 'body-deadline'
    # leaderが既にexitしていても孫を残せるので、anchor保持中に固定group停止。
    if acknowledged:
        try:
            os.killpg(pid, signal.SIGKILL)
            force_count = 1
        except ProcessLookupError:
            pass
    else:
        signal.pidfd_send_signal(pidfd, signal.SIGKILL)
        force_count = 1
        reason = 'session-unconfirmed'
    # ここ以降groupへのsignal禁止。reapは全signal判断の後だけ。
    reaped = []
    cli_status = None
    children_reaped = False
    exit_deadline = time.monotonic_ns()+1000000000
    while time.monotonic_ns() < exit_deadline:
        try:
            child, status = os.waitpid(-1, os.WNOHANG)
        except ChildProcessError:
            children_reaped = True
            break
        if child:
            reaped.append(child)
            if child == pid:
                cli_status = os.waitstatus_to_exitcode(status)
        else:
            select.select([], [], [], .001)
    for fd in eof:
        eof_deadline = time.monotonic_ns()+500000000
        while not eof[fd] and time.monotonic_ns() < eof_deadline:
            if select.select([fd], [], [], max(0,(eof_deadline-time.monotonic_ns())/1e9))[0]:
                chunk = os.read(fd,4096)
                if not chunk:
                    eof[fd] = True
                elif len(captured[fd])+len(chunk) <= MAX_FRAME:
                    captured[fd].extend(chunk)
                else:
                    output_full = True
        os.close(fd)
    os.close(pidfd)
    os.close(control_fd)
    return dict(leader_identity=identity, cli_exit=cli_status, children_reaped=children_reaped,
                reaped_pids=reaped, stdout_eof=eof[out_r], stderr_eof=eof[err_r],
                write_closed=True, force_count=force_count, reason=reason,
                green=reason=='completed' and cli_status==0 and children_reaped and all(eof.values()) and not output_full)


def main():
    # TEMP owner.json/no-reparse/nonceを既存Parent契約へ接続するまではspawn0。
    # 任意pathを暫定公開すると固定境界を壊すため、正式入口はfailclosedを保持。
    sys.stderr.write('M21_BRIDGE_NOT_ADMITTED\n')
    return 78

if __name__ == '__main__':
    raise SystemExit(main())
