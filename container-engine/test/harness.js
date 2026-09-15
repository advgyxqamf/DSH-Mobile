'use strict';

// 测试运行器（镜像内核仓风格）：results[] + check() 打印 PASS/FAIL，结尾打印「结果: X passed, Y failed」。
module.exports = function makeRunner(name) {
  const results = [];
  const check = (n, c, x) => {
    results.push(!!c);
    console.log((c ? 'PASS' : 'FAIL') + ' ' + n + (x !== undefined && x !== '' ? '  <- ' + x : ''));
  };
  const finish = () => {
    const failed = results.filter((r) => !r);
    console.log(String.fromCharCode(10) + '结果: ' + (results.length - failed.length) + ' passed, ' + failed.length + ' failed' + (name ? '  (' + name + ')' : ''));
    process.exit(failed.length ? 1 : 0);
  };
  return { results, check, finish };
};
