import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
const repository = fileURLToPath(new URL('../', import.meta.url));
const fixturePath = 'test-fixtures/unicode/17.0.0/GraphemeBreakTest.txt';
const expectedSha256 = 'e2d134d2c52919bace503ebb6a551c1855fe1a1faec18478c78fff254a1793ec';
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

async function git(cwd, args) {
  return execFileAsync('git', args, { cwd, encoding: 'buffer' });
}

test('Git checkoutの改行設定にかかわらず公式Unicode fixtureの生バイトSHAを保持する', async (t) => {
  // 作業木のCRLFを期待値に流用せず、Gitに保存された公式データを使う。
  const fixture = (await git(repository, ['show', `HEAD:${fixturePath}`])).stdout;
  assert.equal(sha256(fixture), expectedSha256);
  const attributes = await readFile(join(repository, '.gitattributes')).catch((error) => {
    if (error.code === 'ENOENT') return Buffer.alloc(0);
    throw error;
  });

  for (const autocrlf of ['true', 'false', 'input']) {
    await t.test(`core.autocrlf=${autocrlf}`, async (t) => {
      const tempParent = resolve(tmpdir());
      const root = await mkdtemp(join(tempParent, 'mannschaft-unicode-checkout-'));
      t.after(async () => {
        const ownedPath = relative(tempParent, resolve(root));
        assert.ok(ownedPath.startsWith('mannschaft-unicode-checkout-') && !ownedPath.includes(sep));
        await rm(root, { recursive: true, force: true });
      });
      await git(root, ['init']);
      await git(root, ['config', 'core.eol', 'lf']);
      // 利用者の属性ファイルは持ち込まず、リポジトリの実際の属性だけを検査する。
      await writeFile(join(root, 'empty-attributes'), '');
      await git(root, ['config', 'core.attributesFile', join(root, 'empty-attributes')]);
      await writeFile(join(root, '.gitattributes'), attributes);
      await mkdir(dirname(join(root, fixturePath)), { recursive: true });
      await writeFile(join(root, fixturePath), fixture);
      await writeFile(join(root, 'unrelated.txt'), 'unrelated\n');
      await git(root, ['-c', 'core.autocrlf=false', 'add', '.gitattributes', fixturePath, 'unrelated.txt']);

      await rm(join(root, fixturePath));
      await rm(join(root, 'unrelated.txt'));
      await git(root, ['-c', `core.autocrlf=${autocrlf}`, 'checkout', '--', fixturePath, 'unrelated.txt']);

      assert.equal(sha256(await readFile(join(root, fixturePath))), expectedSha256);
      assert.equal(await readFile(join(root, 'unrelated.txt'), 'utf8'), autocrlf === 'true' ? 'unrelated\r\n' : 'unrelated\n');
    });
  }
});
