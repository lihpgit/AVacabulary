#!/usr/bin/env python3
"""
zpk_strip_images.py

扫描 zpack/ 下所有 .zpk 文件，删除其中的 .jpg/.png 图片，重新打包。
ZPAK0300 格式（无加密无压缩）：
  0x00-0x07  magic "ZPAK0300"
  0x08-0x0B  data_start (uint32) — 文件数据起始偏移（通常 0x80=128）
  0x0C-0x0F  numEntries (int32)
  0x10-0x17  indexOffset (int64)
  0x18-0x1F  nameOffset (int64)
  0x20-...   扩展头（保留，整段原样写回）
  data_start-...  文件数据（各 entry 内容连续存放）
  indexOffset-... 索引表（每条 48 字节）
    +0  offset (int64)  — 文件数据偏移
    +8  unknown (8B)    — 保留原值
    +16 size   (int32)  — 文件数据大小
    +20 unknown (28B)   — 保留原值
  nameOffset-... 文件名表（\n 分隔，\0 结尾）

用法：
  python3 zpk_strip_images.py            # 处理同级 zpack/ 目录下所有 zpk
  python3 zpk_strip_images.py --dry-run  # 只统计，不修改文件
"""

import struct
import sys
from pathlib import Path

ZPAK_MAGIC = b'ZPAK0300'
INDEX_ENTRY_SIZE = 48
IMAGE_EXTS = {'.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp'}


def parse_zpk(data: bytes):
    """
    解析 zpk 文件。
    返回 (header: bytes, entries: list[(name, raw48, file_data)]) 或 None。
    """
    if len(data) < 128:
        return None
    if data[:8] != ZPAK_MAGIC:
        return None

    data_start   = struct.unpack_from('<I', data, 0x08)[0]
    num_entries  = struct.unpack_from('<i', data, 0x0C)[0]
    index_offset = struct.unpack_from('<q', data, 0x10)[0]
    name_offset  = struct.unpack_from('<q', data, 0x18)[0]

    if not (0 < data_start <= index_offset <= name_offset < len(data)):
        return None
    if not (0 < num_entries <= 10_000):
        return None

    # 保留完整原始头部（data_start 字节）
    header = data[:data_start]

    # 读文件名表
    name_end = name_offset
    while name_end < len(data) and data[name_end] != 0:
        name_end += 1
    names = (data[name_offset:name_end]
             .decode('utf-8', errors='replace')
             .split('\n'))
    names = [n.strip() for n in names if n.strip()]

    # 读索引条目 + 文件数据
    entries = []
    for i in range(num_entries):
        base = index_offset + i * INDEX_ENTRY_SIZE
        if base + INDEX_ENTRY_SIZE > len(data):
            break
        raw48     = data[base: base + INDEX_ENTRY_SIZE]
        file_off  = struct.unpack_from('<q', raw48, 0)[0]
        file_size = struct.unpack_from('<i', raw48, 16)[0]
        name      = names[i] if i < len(names) else f'unknown_{i}'

        end = file_off + file_size
        if 0 <= file_off and end <= len(data) and file_size > 0:
            file_data = data[file_off:end]
        else:
            file_data = b''
        entries.append((name, raw48, file_data))

    return header, entries


def write_zpk(header: bytes, entries: list) -> bytes:
    """
    用过滤后的 entries 重建 zpk。
    文件数据紧接在 header 之后，索引表和文件名表追加在末尾。
    """
    data_start = len(header)

    # 计算各文件数据偏移
    current = data_start
    new_offsets = []
    for _, _, file_data in entries:
        new_offsets.append(current)
        current += len(file_data)

    index_offset = current
    name_offset  = index_offset + len(entries) * INDEX_ENTRY_SIZE

    # 更新 header 中的字段
    new_header = bytearray(header)
    struct.pack_into('<i', new_header, 0x0C, len(entries))
    struct.pack_into('<q', new_header, 0x10, index_offset)
    struct.pack_into('<q', new_header, 0x18, name_offset)

    # 拼装输出
    parts = [bytes(new_header)]
    for _, _, file_data in entries:
        parts.append(file_data)
    for i, (_, raw48, file_data) in enumerate(entries):
        new_entry = bytearray(raw48)
        struct.pack_into('<q', new_entry, 0,  new_offsets[i])
        struct.pack_into('<i', new_entry, 16, len(file_data))
        parts.append(bytes(new_entry))

    names_str = '\n'.join(name for name, _, _ in entries)
    parts.append(names_str.encode('utf-8') + b'\x00')

    return b''.join(parts)


def process_zpk(path: Path, dry_run: bool) -> tuple:
    """
    处理单个 zpk。返回 (orig_size, new_size, images_removed)。
    如果无图片 new_size == orig_size，images_removed == 0。
    """
    data = path.read_bytes()
    orig_size = len(data)

    parsed = parse_zpk(data)
    if parsed is None:
        return orig_size, orig_size, 0

    header, entries = parsed

    filtered  = [(n, r, d) for n, r, d in entries
                 if Path(n).suffix.lower() not in IMAGE_EXTS]
    n_removed = len(entries) - len(filtered)

    if n_removed == 0:
        return orig_size, orig_size, 0

    if not dry_run:
        new_data = write_zpk(header, filtered)
        path.write_bytes(new_data)
        return orig_size, len(new_data), n_removed

    # dry-run: 估算新大小
    img_bytes = sum(len(d) for n, _, d in entries if Path(n).suffix.lower() in IMAGE_EXTS)
    return orig_size, orig_size - img_bytes, n_removed


def main():
    dry_run = '--dry-run' in sys.argv

    zpack_dir = Path(__file__).parent / 'zpack'
    if not zpack_dir.exists():
        print(f"ERROR: {zpack_dir} not found")
        sys.exit(1)

    zpk_files = sorted(zpack_dir.rglob('*.zpk'))
    total = len(zpk_files)
    print(f"{'[DRY RUN] ' if dry_run else ''}Found {total} zpk files in {zpack_dir}")

    modified   = 0
    skipped    = 0
    errors     = 0
    saved_bytes = 0

    for i, path in enumerate(zpk_files):
        try:
            orig, new, n_img = process_zpk(path, dry_run)
            if n_img > 0:
                modified    += 1
                saved_bytes += orig - new
            else:
                skipped += 1
        except Exception as e:
            errors += 1
            print(f"  ERROR {path}: {e}")

        if (i + 1) % 1000 == 0 or (i + 1) == total:
            print(f"  [{i+1:5d}/{total}] modified={modified} skipped={skipped} "
                  f"saved={saved_bytes/1024/1024:.1f}MB errors={errors}")

    print(f"\n{'DRY RUN ' if dry_run else ''}Done!")
    print(f"  Modified : {modified}")
    print(f"  Skipped  : {skipped}  (no images)")
    print(f"  Errors   : {errors}")
    print(f"  Space saved: {saved_bytes/1024/1024:.1f} MB")


if __name__ == '__main__':
    main()
