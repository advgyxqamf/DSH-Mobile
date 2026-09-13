#!/usr/bin/env python3
"""
严格校验 GitHub Actions workflow。

为什么需要：PyYAML 的 safe_load 对【重复键】是宽容的（静默保留最后一个），
而 GitHub 的解析器是严格的 —— 重复键会让整个 workflow 加载失败，表现为
Actions 页面「红色 ✗ + 0s/几秒」，且不产生任何步骤日志。
这个坑真实发生过：向 workflow 注入步骤时误复制了 `uses:` / `with:` 行，
safe_load 通过、GitHub 拒绝，白白浪费了一轮排查。

本脚本用「重写 compose 钩子」的方式让 YAML 解析器在遇到重复键时直接报错，
并额外检查每个 step 只能出现一次 name/uses/run。
"""
import sys, yaml

class StrictLoader(yaml.SafeLoader):
    pass

def _no_dup_keys(loader, node, deep=False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping", node.start_mark,
                "found duplicate key %r (GitHub rejects duplicate keys; "
                "PyYAML would silently keep the last one)" % (key,),
                key_node.start_mark)
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping

StrictLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _no_dup_keys)

def main(path):
    with open(path, encoding="utf-8") as f:
        try:
            doc = yaml.load(f, Loader=StrictLoader)
        except yaml.YAMLError as e:
            print("[FAIL] YAML error:\n%s" % e)
            return 1
    print("[ok] no duplicate keys")

    errs = []
    jobs = doc.get("jobs") or {}
    if not jobs:
        errs.append("no jobs defined")
    for jname, job in jobs.items():
        if "runs-on" not in job:
            errs.append("job %r missing runs-on" % jname)
        tm = job.get("timeout-minutes")
        if tm is not None and not isinstance(tm, int):
            errs.append("job %r timeout-minutes must be an integer, got %r" % (jname, tm))
        steps = job.get("steps") or []
        if not steps:
            errs.append("job %r has no steps" % jname)
        seen_names = {}
        for i, s in enumerate(steps):
            if not isinstance(s, dict):
                errs.append("job %r step %d is not a mapping" % (jname, i))
                continue
            if not ({"uses", "run"} & set(s)):
                errs.append("job %r step %d (%s) has neither uses nor run"
                            % (jname, i, s.get("name")))
            if "uses" in s and "run" in s:
                errs.append("job %r step %d (%s) has both uses and run"
                            % (jname, i, s.get("name")))
            n = s.get("name")
            if n:
                if n in seen_names:
                    errs.append("job %r has duplicate step name %r (steps %d and %d)"
                                % (jname, n, seen_names[n], i))
                seen_names[n] = i
    if errs:
        for e in errs:
            print("[FAIL] " + e)
        return 1
    print("[ok] structure sane (%d job(s), %d step(s))"
          % (len(jobs), sum(len(j.get('steps') or []) for j in jobs.values())))
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1
                  else ".github/workflows/build-apk.yml"))
