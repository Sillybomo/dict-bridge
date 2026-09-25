#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
extract_sogou.py —— 解析搜狗输入法安卓端个人词库 sgim_gd_usr.bin（SGPU 新格式）。

用途: 把搜狗小米版/安卓版的用户学习词库解成 "词\t拼音(空格分隔)\t词频" 的 TSV。
      格式逻辑移植自 studyzy/imewlconverter 的 SougouBinParser.cs "新格式"分支（GPLv3）。
参数: argv[1] = sgim_gd_usr.bin 路径; argv[2] = 输出 tsv 路径
返回: 打印解析条数; 输出文件每行 "词\t拼音\t词频"
异常/边界: 魔数不是 0x55504753('SGPU') 时抛 ValueError（旧格式未支持）;
          单条记录解析失败静默跳过并计数报告。
@author bomo
"""
import struct
import sys

sys.path.insert(0, __file__.rsplit('/', 1)[0] if '/' in __file__ else '.')
from pinyin_table import PINYIN


def extract(path):
    """解析 SGPU 容器，返回 [(word, pinyin_space_sep, freq), ...]。"""
    b = open(path, 'rb').read()
    if struct.unpack_from('<I', b, 0)[0] != 0x55504753:
        raise ValueError('不是 SGPU 新格式（魔数不匹配），可能是旧版备份 bin')
    idx_begin, idx_size, word_count, dict_begin = struct.unpack_from('<4I', b, 0x38)
    out, bad = [], 0
    for i in range(word_count):
        try:
            idx = struct.unpack_from('<I', b, idx_begin + 4 * i)[0]
            p = idx + dict_begin
            freq = struct.unpack_from('<H', b, p)[0]
            p += 9
            n = struct.unpack_from('<H', b, p)[0] // 2
            p += 2
            syls = []
            for _ in range(n):
                q = struct.unpack_from('<H', b, p)[0]
                p += 2
                if q >= len(PINYIN):
                    raise ValueError('音节 ID 越界: %d' % q)
                syls.append(PINYIN[q])
            p += 2
            wlen = struct.unpack_from('<H', b, p)[0]
            p += 2
            word = b[p:p + wlen].decode('utf-16-le')
            out.append((word, ' '.join(syls), freq))
        except Exception:
            bad += 1
    return out, bad


def main():
    src, dst = sys.argv[1], sys.argv[2]
    rows, bad = extract(src)
    import os
    parent = os.path.dirname(dst)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(dst, 'w', encoding='utf-8') as f:
        f.write('\n'.join('%s\t%s\t%d' % r for r in rows))
    print('extracted=%d bad=%d -> %s' % (len(rows), bad, dst))


if __name__ == '__main__':
    main()
