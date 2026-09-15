'use strict';

// 内核 OTA 引擎（单写入者 = 容器）。对齐 docs/BASE_SPEC.md §5 通道一。
//
// 设备端流程：poll manifest → download zip → sha256 校验 → 解包取 kernel.json →
//   验签(焊死公钥) → engines.node 比对固定运行时 → requires ⊆ 设备能力 →
//   原子解包到 files/kernel/<new>/ → 切 CURRENT 指针(tmp+rename) → 杀旧 :node、spawn 新。
// 坏包永不生效：任一校验不过直接抛错，绝不切指针。
// 回滚：保留上一版本；新包健康检查失败 → 指针回退 + 重启。

const fs = require('fs');
const path = require('path');
const { extractZip } = require('./zip');
const { sha256, verifyManifest } = require('./verify');

class OtaEngine {
  constructor({ baseDir, httpGet, publicKeyPem, capabilities, runtime, log }) {
    this.baseDir = baseDir;                 // files/（应用沙箱）
    this.httpGet = httpGet;                 // (url) => Promise<Buffer>
    this.publicKeyPem = publicKeyPem;       // 焊死公钥（设备端唯一信任源）
    this.capabilities = capabilities || [];  // 设备已预置能力 token
    this.runtime = runtime || { node: process.version };
    this.log = log || (() => {});
    this.kernelDir = path.join(baseDir, 'kernel');
    this.currentPointer = path.join(this.kernelDir, 'CURRENT');
  }

  currentVersion() {
    try { return fs.readFileSync(this.currentPointer, 'utf8').trim(); } catch (_e) { return null; }
  }

  _setPointer(v) {
    fs.mkdirSync(this.kernelDir, { recursive: true });
    const tmp = path.join(this.kernelDir, 'CURRENT.tmp');
    fs.writeFileSync(tmp, v);
    fs.renameSync(tmp, this.currentPointer); // 原子切换
  }

  installedVersions() {
    try {
      return fs.readdirSync(this.kernelDir)
        .filter((d) => d !== 'CURRENT' && fs.statSync(path.join(this.kernelDir, d)).isDirectory());
    } catch (_e) { return []; }
  }

  async fetchManifest(url) {
    const buf = await this.httpGet(url);
    return JSON.parse(buf.toString('utf8'));
  }

  /**
   * 校验一包。坏包返回 { ok:false, reason }，绝不抛错到调用方之外。
   * @param {Buffer} zipBuf
   * @param {object} manifest 对应 kernel-manifest 条目（含 sha256 / version）
   */
  verifyPackage(zipBuf, manifest) {
    if (sha256(zipBuf) !== manifest.sha256) return { ok: false, reason: 'sha256-mismatch' };
    const tmp = path.join(this.baseDir, '.ota-verify-' + process.pid + '-' + Date.now());
    try {
      extractZip(zipBuf, tmp);
      const kjPath = path.join(tmp, 'kernel', manifest.version, 'kernel.json');
      if (!fs.existsSync(kjPath)) return { ok: false, reason: 'no-kernel-json' };
      const kernelJson = JSON.parse(fs.readFileSync(kjPath, 'utf8'));
      if (!verifyManifest(this.publicKeyPem, kernelJson, kernelJson.signature))
        return { ok: false, reason: 'signature-invalid' };
      if (!this._nodeSatisfied(kernelJson.engines && kernelJson.engines.node))
        return { ok: false, reason: 'node-engine-unsatisfied' };
      const missing = (kernelJson.requires || []).filter((r) => !this.capabilities.includes(r));
      if (missing.length) return { ok: false, reason: 'capability-missing', missing };
      return { ok: true, kernelJson };
    } catch (e) {
      return { ok: false, reason: 'extract-failed', error: String(e && e.message) };
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true });
    }
  }

  _nodeSatisfied(range) {
    if (!range) return true;
    const m = /v?(\d+)(?:\.(\d+))?(?:\.(\d+))?/.exec(this.runtime.node || process.version);
    if (!m) return false;
    const cur = [+m[1], +(m[2] || 0), +(m[3] || 0)];
    const cmp = (a, b) => { for (let i = 0; i < 3; i += 1) { if (a[i] !== b[i]) return a[i] - b[i]; } return 0; };
    const parse = (s) => { const x = /v?(\d+)(?:\.(\d+))?(?:\.(\d+))?/.exec(s); return [+x[1], +(x[2] || 0), +(x[3] || 0)]; };
    let ok = true;
    for (const p of range.split(/\s+/).filter(Boolean)) {
      // 支持 >=24 / <25 / >=24.5.0 等部分版本写法
      const mm = /^([<>]=?|=)?\s*v?(\d+)(?:\.(\d+))?(?:\.(\d+))?$/.exec(p);
      if (!mm) continue;
      const op = mm[1] || '=';
      const t = parse(mm[2] + '.' + (mm[3] || 0) + '.' + (mm[4] || 0));
      const c = cmp(cur, t);
      if (op === '>=') ok = ok && c >= 0;
      else if (op === '>') ok = ok && c > 0;
      else if (op === '<=') ok = ok && c <= 0;
      else if (op === '<') ok = ok && c < 0;
      else if (op === '=') ok = ok && c === 0;
    }
    return ok;
  }

  /**
   * 原子解包 + 切指针（调用前须已 verifyPackage 通过）。
   * @returns {string} 新内核目录绝对路径
   */
  apply(version, zipBuf) {
    const dest = path.join(this.kernelDir, version);
    const tmp = dest + '.tmp-' + process.pid + '-' + Date.now();
    fs.rmSync(tmp, { recursive: true, force: true });
    extractZip(zipBuf, tmp);
    const inner = path.join(tmp, 'kernel', version);
    if (!fs.existsSync(inner)) { fs.rmSync(tmp, { recursive: true, force: true }); throw new Error('包内缺少 kernel/' + version + ' 目录'); }
    fs.rmSync(dest, { recursive: true, force: true });
    fs.renameSync(inner, dest);
    fs.rmSync(tmp, { recursive: true, force: true });
    this._setPointer(version);
    this.log('ota: 已切换到内核 ' + version);
    return dest;
  }

  /** 回滚到最近一个非当前的已安装版本；无可回滚返回 null。 */
  rollback() {
    const cur = this.currentVersion();
    const prev = this.installedVersions().filter((v) => v !== cur).sort().pop();
    if (prev) { this._setPointer(prev); this.log('ota: 回滚到 ' + prev); return prev; }
    return null;
  }

  /** 是否有可用更新（manifest 版本 ≠ 当前且非空）。 */
  checkUpdate(manifest) {
    const cur = this.currentVersion();
    if (cur && cur === manifest.version) return { available: false };
    return { available: true, version: manifest.version, manifest };
  }
}

module.exports = { OtaEngine };
