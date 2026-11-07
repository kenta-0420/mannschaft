"""固定bridge境界の有限試験。実Dockerは起動しない。"""
import importlib.util
import pathlib
import unittest
import sys
spec = importlib.util.spec_from_file_location('bridge', pathlib.Path(__file__).with_name('m21-wsl-docker-bridge.py'))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)

class BoundaryTests(unittest.TestCase):
    def test_admission_and_replay(self):
        p = b.Protocol('11111111-1111-4111-8111-111111111111', 100)
        p.initialize(dict(type='init', owner=b.OWNER, run=p.run, global_offset_ms=1000, work_offset_ms=800), 100)
        r = dict(type='request', owner=b.OWNER, run=p.run, request_id=1, operation='daemon-info', resource='daemon', deadline_offset_ms=200)
        self.assertEqual(p.request(r, 150)[1], 200000100)
        with self.assertRaises(b.Rejected): p.request(r, 150)
    def test_unknown_input_and_secret_api(self):
        for extra in ({'argv': []}, {'env': {}}, {'path':'/tmp'}, {'operation':'exec'}):
            p = b.Protocol('11111111-1111-4111-8111-111111111111', 100)
            p.initialize(dict(type='init', owner=b.OWNER, run=p.run, global_offset_ms=1000, work_offset_ms=800), 100)
            r = dict(type='request', owner=b.OWNER, run=p.run, request_id=1, operation='daemon-info', resource='daemon', deadline_offset_ms=200)
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
    def run_case(self, code, cancel=False):
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
                                          {'PATH':'/usr/bin:/bin','LANG':'C'},
                                          time.monotonic_ns()+200000000,control_r)
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
        self.assertTrue(r['stdout_eof'] and r['stderr_eof'])

    def test_grandchild_holding_pipes_is_reaped(self):
        r = self.run_case('import os,time; p=os.fork(); time.sleep(60) if p==0 else None')
        self.assertTrue(r['children_reaped'])
        self.assertGreaterEqual(len(r['reaped_pids']),2)
        self.assertTrue(r['stdout_eof'] and r['stderr_eof'])

    def test_bridge_control_eof_does_not_abandon_child(self):
        r = self.run_case('import time; time.sleep(60)',cancel=True)
        self.assertFalse(r['green'])
        self.assertTrue(r['children_reaped'])
        self.assertEqual(r['force_count'],1)

if __name__ == '__main__': unittest.main()
