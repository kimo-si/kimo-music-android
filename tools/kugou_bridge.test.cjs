'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { test } = require('node:test');

// 使用临时假核心验证真实桥接脚本，不访问网络或设备账号。
test('桥接层限制超时并剔除异常堆栈和鉴权配置', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'kimo-bridge-test-'));
  try {
    prepareFixture(directory);
    const result = spawnSync(process.execPath, ['-e', testScript], { cwd: directory, encoding: 'utf8', timeout: 15000 });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /bridge checks passed/);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

// 假模块分别返回成功、连接异常和登录失效响应。
function prepareFixture(directory) {
  fs.copyFileSync(path.join(__dirname, 'kugou_bridge.cjs'), path.join(directory, 'kugou_bridge.cjs'));
  writeFixture(directory, 'device.mjs', "export function getDevice() { return { dfid: 'fixture' }; }");
  writeFixture(directory, 'node_modules/axios/index.js', 'module.exports = { defaults: { timeout: 0 } };');
  writeFixture(directory, 'node_modules/kugoumusicapi/util/index.js', 'exports.createRequest = () => {};');
  writeFixture(directory, 'node_modules/kugoumusicapi/module/top_song.js', fakeModule);
}

// 只创建本次测试独占的临时文件。
function writeFixture(directory, name, contents) {
  const target = path.join(directory, name);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, contents);
}

// 构造带敏感字段的失败响应和正常响应，模拟上游不同返回路径。
const fakeModule = `module.exports = async ({ scenario }) => {
  const secret = { stack: 'PRIVATE_STACK', config: { token: 'PRIVATE_TOKEN' } };
  if (scenario === 'returned') return { body: { status: 0, msg: secret } };
  if (scenario === 'expired') throw { body: { status: 0, error_code: 20017, msg: secret } };
  if (scenario === 'success') return { body: { status: 1, data: 'ok' } };
  throw { body: { status: 0, msg: secret } };
};`;

// 在独立进程加载桥接层，避免它的控制台设置影响测试运行器。
const testScript = `const assert = require('node:assert/strict');
require('./kugou_bridge.cjs');
// 等待一次性桥接结果，检查序列化数据不包含敏感字段。
async function run(scenario) {
  globalThis.kimoBegin(scenario, 'top_song', { scenario }, {});
  for (let index = 0; index < 100; index++) {
    await new Promise(resolve => setTimeout(resolve, 10));
    const raw = globalThis.kimoTake(scenario);
    if (raw) { assert(!raw.includes('PRIVATE_')); return JSON.parse(raw); }
  }
  throw new Error('bridge timeout');
}
(async () => {
  assert.equal(require('axios').defaults.timeout, 12000);
  assert.equal((await run('thrown')).error_code, -1);
  assert.equal((await run('returned')).error_code, -1);
  assert.equal((await run('expired')).error_code, 20017);
  assert.equal((await run('success')).data, 'ok');
  process.stdout.write('bridge checks passed\\n');
})().catch(error => { process.stderr.write(String(error)); process.exitCode = 1; });`;
