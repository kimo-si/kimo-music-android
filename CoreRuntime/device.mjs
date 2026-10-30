/**
 * 设备指纹：移植自 MoeKoeMusic/electron/util/kugouapi/deviceUtil.js
 * 原实现用 electron-store，这里改成落在 CoreRuntime 目录下的 JSON 文件，
 * 避免为一份指纹数据引入 Electron 运行时。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import util from 'kugoumusicapi/util/util.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const STORE_DIR = process.env.KIMO_CORE_DATA || path.join(__dirname, '.data');
const STORE_FILE = path.join(STORE_DIR, 'device.json');

const readStore = () => {
  try {
    return JSON.parse(fs.readFileSync(STORE_FILE, 'utf8'));
  } catch {
    return {};
  }
};

const writeStore = (data) => {
  fs.mkdirSync(STORE_DIR, { recursive: true });
  fs.writeFileSync(STORE_FILE, JSON.stringify(data, null, 2));
};

export const generateNewDevice = () => {
  const uuid = util.getGuid();
  const device = {
    dev: util.randomString(10).toUpperCase(),
    uuid,
    mac: '02:00:00:00:00:00',
    mid: util.calculateMid(uuid),
    webgl: util.generateWebGLHash(),
  };
  const store = readStore();
  store.device = device;
  writeStore(store);
  return device;
};

export const getDevice = () => {
  const store = readStore();
  return store.device || generateNewDevice();
};

/** 把 /register/dev 拿到的 dfid 写回同一份设备，避免每次启动换指纹。 */
export const rememberDfid = (dfid) => {
  if (!dfid) return getDevice();
  const store = readStore();
  const device = store.device || generateNewDevice();
  device.dfid = String(dfid);
  store.device = device;
  writeStore(store);
  return device;
};
