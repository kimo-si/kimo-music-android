'use strict';
const path = require('node:path');
const fs = require('node:fs');
// 上游少数模块会打印完整请求异常；客户端禁止将可能包含令牌的对象写入系统日志。
for (const method of ['log', 'warn', 'error', 'debug']) console[method] = () => {};
process.env.platform = 'lite';
process.env.KIMO_CORE_DATA = path.join(__dirname, '..', 'device-data');
// 上游请求封装未传递 timeout；设置 Axios 默认值，避免断线请求无限等待。
const NETWORK_TIMEOUT_MS = 12000;
require('axios').defaults.timeout = NETWORK_TIMEOUT_MS;
const { createRequest } = require('kugoumusicapi/util/index.js');
const moduleDirectory = path.join(__dirname, 'node_modules/kugoumusicapi/module');
const modules = new Set(fs.readdirSync(moduleDirectory).filter(name => name.endsWith('.js')).map(name => name.slice(0, -3)));
const completed = new Map();
const active = new Set();
const deviceReady = import('./device.mjs');
deviceReady.then(registerDevice).catch(() => {});

// 首次安装补齐 dfid，注册失败保留既有设备并允许后续接口返回可见错误。
async function registerDevice(store) {
  const device = store.getDevice();
  if (device.dfid) return;
  const register = require(path.join(moduleDirectory, 'register_dev.js'));
  const response = await register({ cookie: requestCookie(device, {}) }, createRequest);
  const dfid = response?.body?.data?.dfid || response?.body?.dfid;
  if (dfid) store.rememberDfid(dfid);
}

// 设备信息与账号令牌在每次调用时合并，不共享不同账号的鉴权响应缓存。
function requestCookie(device, credentials) {
  return { ...device, KUGOU_API_DEV: device.dev, KUGOU_API_GUID: device.uuid,
    KUGOU_API_MAC: device.mac, KUGOU_API_MID: device.mid, KUGOU_API_WEBGL: device.webgl,
    ...credentials };
}

// 保留完整核心模块，安卓端通过同一条受控桥接调用原有签名与加密实现。
async function invoke(moduleName, parameters, credentials) {
  if (moduleName === '__health') return { status: 1, data: { node: process.versions.node, modules: modules.size } };
  if (!modules.has(moduleName)) throw new Error('未知接口');
  const deviceStore = await deviceReady;
  const cookie = requestCookie(deviceStore.getDevice(), credentials);
  const handler = require(path.join(moduleDirectory, `${moduleName}.js`));
  const response = await handler({ ...parameters, cookie }, createRequest);
  return response.body;
}

// 异步结果只在调用方读取前短暂保留，不把登录信息写入日志或磁盘。
globalThis.kimoBegin = function(id, moduleName, parameters, credentials) {
  active.add(id);
  invoke(moduleName, parameters, credentials).then(body => finish(id, safeResponse(body)), error => {
    finish(id, safeFailure(error));
  });
};

// 有些模块返回失败对象而不是抛出，也不能把其中的异常配置传出桥接层。
function safeResponse(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return body;
  const codes = [body.error_code, body.errcode].map(Number).filter(Number.isFinite);
  const code = codes.find(value => value !== 0) || 0;
  const status = Number(body.status);
  const lyricSuccess = status === 200 && code === 200;
  const failed = (code !== 0 && !lyricSuccess) ||
    (Object.hasOwn(body, 'status') && status !== 1 && status !== 200);
  return failed ? safeFailure({ body }) : body;
}

// 异常对象可能含堆栈、请求配置或令牌；只保留登录失效所需的错误码。
function safeFailure(error) {
  const codes = [error?.body?.error_code, error?.body?.errcode].map(Number);
  const expired = codes.find(code => code === 20017 || code === 20018);
  return { status: 0, error_code: expired || -1 };
}

// 已取消的请求直接丢弃响应，防止网络超时后残留结果占用内存。
function finish(id, body) {
  if (!active.delete(id)) return;
  completed.set(id, JSON.stringify(body));
}

// 读取即删除一次性结果，避免鉴权数据长期留在桥接层。
globalThis.kimoTake = function(id) {
  const value = completed.get(id);
  completed.delete(id);
  return value || '';
};

// 取消标记随上游完成而释放，调用方不等待已经离开的页面。
globalThis.kimoCancel = function(id) {
  completed.delete(id);
  active.delete(id);
};
