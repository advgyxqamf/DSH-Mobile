#!/usr/bin/env python3
# ============================================================================
#  往已构建好的 APK 里注入 libc++_shared.so（免重编 Node）
# ============================================================================
#  为什么需要这个脚本
#  ----------------
#  真机日志（Android 16 实机）：
#      CANNOT LINK EXECUTABLE "/data/app/.../lib/arm64-v8a/libnode.so":
#      cannot locate symbol
#      "_ZTVNSt6__ndk119basic_ostringstreamIcNS_11char_traitsIcEENS_9allocatorIcEEEE"
#
#  根因（readelf 实证）：
#      libnode.so 的 DT_NEEDED 里有 libc++_shared.so，
#      而该符号在 libnode.so 里是 UND（未定义），必须由 libc++_shared.so 提供。
#      但 APK 里只打包了 libnode.so —— libc++_shared.so 漏了。
#      （它不在 Android 系统里，不是 bionic 的组成部分，必须随包提供。）
#
#  本脚本做三件事
#  -------------
#    1) 把 APK 里所有条目【原样搬运】（不解压/不重压其它条目，避免改动其字节）；
#    2) 在 lib/arm64-v8a/libnode.so 之后插入 libc++_shared.so，
#       且【以 Stored（不压缩）方式】写入 —— 这是硬要求，见下；
#    3) 重建中央目录，去掉原 v2 签名块 —— 之后由 apksigner 重新签名。
#
#  为什么必须 Stored（不压缩）
#  --------------------------
#  android:extractNativeLibs="true" 的语义是：
#    "安装时把 lib/ 下的 .so 解压到 /data/app/.../lib/<abi>/ 落盘"。
#  但如果 .so 在 APK 里是【压缩存储】(Method=Deflate)，PackageManager 会把
#  extractNativeLibs 当作无效配置，改为直接在 APK 内 mmap 加载，
#  结果就是 nativeLibraryDir 里【根本没有这个文件】。
#  （旧 APK 里 libnode.so 是 Deflate，仍能落盘，是因为 AGP 给它单独留了
#    对齐与 extractNativeLibs=true 组合；但对新插入的条目，我们不能指望
#    任何外部工具替我们处理，最稳的是自己写成 Stored 并做 4K 对齐。）
#
#  用法
#  ----
#    python3 scripts/inject-libcxx-into-apk.py \
#        --apk in.apk --out out.apk \
#        --libcxx /path/to/ndk/.../aarch64-linux-android/libc++_shared.so
# ============================================================================

import argparse
import binascii
import os
import struct
import sys

U32 = struct.Struct('<I')
LOCAL_HDR = struct.Struct('<IHHHHHIIIHH')   # 30 字节
CENTRAL_HDR = struct.Struct('<IHHHHHHIIIHHHHHII')  # 46 字节
EOCD = struct.Struct('<IHHHHIIH')          # 22 字节
# 字段: sig, disk_no, cd_disk, disk_entries, total_entries, cd_size, cd_off, comment_len

SIG_LOCAL = 0x04034b50
SIG_CENTRAL = 0x02014b50
SIG_EOCD = 0x06054b50

# 与 AGP 输出一致的时间戳：1981-01-01 01:01
DOS_TIME = 0x0021
DOS_DATE = 0x0021

ALIGN = 4096  # .so 需要 4K 对齐才能被 mmap / 正确解压落盘


def align_up(n, a):
    return (n + a - 1) // a * a


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--apk', required=True, help='输入 APK')
    ap.add_argument('--out', required=True, help='输出 APK')
    ap.add_argument('--libcxx', required=True, help='libc++_shared.so 路径')
    ap.add_argument('--abi', default='arm64-v8a', help='目标 ABI 目录名')
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        sys.exit('[error] 找不到 APK: %s' % args.apk)
    if not os.path.isfile(args.libcxx):
        sys.exit('[error] 找不到 libc++_shared.so: %s' % args.libcxx)

    data = open(args.apk, 'rb').read()

    # ---- 解析 EOCD ----
    eocd_off = data.rfind(struct.pack('<I', SIG_EOCD))
    if eocd_off < 0:
        sys.exit('[error] 不是有效 zip：找不到 EOCD')
    (_esig, _dno, _dcnt, _ent_disk, total, cd_size, cd_off, _clen) = EOCD.unpack_from(data, eocd_off)
    if cd_off + cd_size > len(data):
        sys.exit('[error] 中央目录越界，APK 可能损坏')

    # ---- 走一遍中央目录，收集条目（顺序即中央目录顺序）----
    entries = []
    p = cd_off
    for _ in range(total):
        if U32.unpack_from(data, p)[0] != SIG_CENTRAL:
            sys.exit('[error] 中央目录签名不匹配 @ %d' % p)
        (sig, vmb, vne, flag, method, mtime, mdate,
         crc, csize, usize, fnl, efl, cml, dsk, iattr, eattr, lho) = CENTRAL_HDR.unpack_from(data, p)
        name = data[p + CENTRAL_HDR.size: p + CENTRAL_HDR.size + fnl]
        entries.append(dict(name=name, method=method, mtime=mtime, mdate=mdate,
                            crc=crc, csize=csize, usize=usize, lho=lho,
                            vmb=vmb, vne=vne, flag=flag, eattr=eattr))
        p += CENTRAL_HDR.size + fnl + efl + cml
    if p != cd_off + cd_size:
        sys.exit('[error] 中央目录长度不一致（解析 %d vs 声明 %d）' % (p - cd_off, cd_size))

    # ---- 读 libc++ ----
    libcxx = open(args.libcxx, 'rb').read()
    new_name = ('lib/%s/libc++_shared.so' % args.abi).encode()
    if any(e['name'] == new_name for e in entries):
        sys.exit('[error] APK 里已经存在 %s，无需注入' % new_name.decode())

    # anchor：新条目紧跟在哪个既有条目之后写入。
    #
    # 为什么锚定 libnode.so：它是本包的主可执行资产，必定存在；
    # 且两个 lib*.so 相邻便于人工核对。
    # 这两个名字都来自 NativeAssetRegistry（libcxx / node 两项），
    # 改注册表时也要改这里 —— container-engine/test/native-assets-test.js 会拦。
    anchor = ('lib/%s/libnode.so' % args.abi).encode()
    print('==> APK       : %s (%d 字节, %d 个条目)' % (args.apk, len(data), len(entries)))
    print('==> 注入目标  : %s (%d 字节)' % (new_name.decode(), len(libcxx)))

    # ---- 重写 ----
    out = open(args.out, 'wb')
    central = []

    def copy_entry(e, first):
        """原样搬运：拷贝局部头 + 名称 + extra + 压缩后的数据

        关于 padding 的两个坑（都踩过，别再犯）：
          1) padding 必须补在【局部头之前】，绝不能插在"头"与"数据"之间 ——
             否则读方按 csize 读数据时会把 padding 当数据，zlib 直接报
             "invalid stored block lengths"。
          2) 【第一个条目绝对不能加 padding】—— 文件 offset 0 必须是合法的
             局部头签名 (PK\\x03\\x04)。若在开头插 padding，aapt2 会直接拒绝：
             "Entry at offset zero has invalid LFH signature 0"。
             需要 4K 对齐时，交给 zipalign 工具在签名前统一处理。
        """
        off = e['lho']
        hdr = data[off:off + LOCAL_HDR.size]
        (sig, vne, flag, method, mtime, mdate, crc, csize, usize, fnl, efl) = LOCAL_HDR.unpack_from(data, off)
        if sig != SIG_LOCAL:
            sys.exit('[error] 局部头签名不匹配 @ %d (%s)' % (off, e['name'].decode(errors='replace')))
        if flag & 0x08:
            sys.exit('[error] 条目使用了 data descriptor，本脚本不支持：%s' % e['name'].decode(errors='replace'))

        if not first:
            data_start = out.tell() + LOCAL_HDR.size + fnl + efl
            pad = align_up(data_start, ALIGN) - data_start
            if pad:
                out.write(b'\x00' * pad)      # padding 补在【局部头之前】

        new_off = out.tell()
        out.write(hdr)
        out.write(e['name'])
        out.write(data[off + LOCAL_HDR.size + fnl: off + LOCAL_HDR.size + fnl + efl])
        out.write(data[off + LOCAL_HDR.size + fnl + efl: off + LOCAL_HDR.size + fnl + efl + csize])
        central.append(dict(e, lho=new_off))

    for idx, e in enumerate(entries):
        copy_entry(e, first=(idx == 0))
        if e['name'] == anchor:
            # 紧跟 libnode.so 写入 libc++_shared.so，Stored 未压缩。
            # 4K 对齐交给随后的 zipalign 统一处理（自带 -p 可指定 16K 页对齐）。
            off = out.tell()
            crc = binascii.crc32(libcxx) & 0xffffffff
            out.write(LOCAL_HDR.pack(SIG_LOCAL, 20, 0, 0, DOS_TIME, DOS_DATE,
                                     crc, len(libcxx), len(libcxx), len(new_name), 0))
            out.write(new_name)
            out.write(libcxx)
            central.append(dict(name=new_name, method=0, mtime=DOS_TIME, mdate=DOS_DATE,
                                crc=crc, csize=len(libcxx), usize=len(libcxx), lho=off,
                                vmb=20, vne=20, flag=0, eattr=0))
            print('    [ok] 写入 @ %d (Stored 未压缩；对齐由 zipalign 处理)' % off)

    # ---- 中央目录 ----
    cd_start = out.tell()
    for e in central:
        out.write(CENTRAL_HDR.pack(SIG_CENTRAL, e['vmb'], e['vne'], e['flag'], e['method'],
                                   e['mtime'], e['mdate'], e['crc'], e['csize'], e['usize'],
                                   len(e['name']), 0, 0, 0, 0, e['eattr'], e['lho']))
        out.write(e['name'])
    cd_end = out.tell()

    # ---- EOCD（无注释）----
    out.write(EOCD.pack(SIG_EOCD, 0, 0, len(central), len(central),
                        cd_end - cd_start, cd_start, 0))
    out.close()

    print('==> 写出 %s : %d 字节（原 %d，+%d）'
          % (args.out, os.path.getsize(args.out), len(data), os.path.getsize(args.out) - len(data)))
    print('==> 注意：签名已被剥离（v2 块移除），请用 apksigner 重新签名后再安装。')


if __name__ == '__main__':
    main()
