import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, realpathSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// M21 の JDK-only 試練だけ。Gradle・DB・アプリを起動しない。
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const parent = realpathSync(tmpdir());
const work = mkdtempSync(path.join(parent, 'm21-cleanup-test-'));
const source = path.join(root, 'backend/src/test/java/com/mannschaft/app/moduleevent/e2e');
const executable = (name) => process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? `${name}.exe` : name)
  : name;
const invoke = (name, args) => {
  const result = spawnSync(executable(name), args, {
    cwd: root, encoding: 'utf8', timeout: 30_000, maxBuffer: 65_536, windowsHide: true,
  });
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  console.log(`M21 ${name}: exit=${result.status}, signal=${result.signal ?? 'none'}`);
  if (result.error) throw result.error;
  return result.status ?? 1;
};

try {
  const compiled = invoke('javac', ['-J-Xmx128m', '-encoding', 'UTF-8', '-d', work,
    path.join(source, 'ModuleEventRealE2eCleanup.java'),
    path.join(source, 'ModuleEventRealE2eCleanupTest.java')]);
  process.exitCode = compiled || invoke('java', ['-Xmx128m', '-Dfile.encoding=UTF-8', '-cp', work,
    'com.mannschaft.app.moduleevent.e2e.ModuleEventRealE2eCleanupTest']);
} finally {
  // 今回作った exact directory だけを所有確認して撤去する。
  const resolved = realpathSync(work);
  if (path.dirname(resolved) !== parent || !path.basename(resolved).startsWith('m21-cleanup-test-')) {
    throw new Error('M21 試練一時ディレクトリの所有境界不一致');
  }
  rmSync(resolved, { recursive: true });
  if (existsSync(resolved)) throw new Error('M21 試練一時ディレクトリが残留');
  console.log('M21 temporary: removed');
}
