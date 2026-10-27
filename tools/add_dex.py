"""把 classes.dex 写入 aapt2 link 产出的 APK，并保证 resources.arsc 不压缩。

用法：
    python add_dex.py <base.apk> <classes.dex> <out.apk>

aapt2 link 产出的 APK 里没有 classes.dex；本脚本负责：
  1. 逐条复制原 APK 条目（保留原压缩方式），
  2. 强制 resources.arsc 为 STORED（targetSdk 30+ 要求未压缩，否则安装报错），
  3. 追加 classes.dex。
"""

import os
import sys
import zipfile


def main() -> int:
    if len(sys.argv) != 4:
        print("用法: python add_dex.py <base.apk> <classes.dex> <out.apk>")
        return 2

    base_apk, dex_path, out_apk = sys.argv[1], sys.argv[2], sys.argv[3]
    if not os.path.isfile(base_apk):
        print("找不到 base apk:", base_apk)
        return 1
    if not os.path.isfile(dex_path):
        print("找不到 classes.dex:", dex_path)
        return 1

    with zipfile.ZipFile(base_apk, "r") as zin, zipfile.ZipFile(out_apk, "w") as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == "resources.arsc":
                # 必须未压缩（Android 11+ 安装校验）
                zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                zi.compress_type = zipfile.ZIP_STORED
                zi.external_attr = item.external_attr
                zout.writestr(zi, data)
            else:
                zout.writestr(item, data)

        with open(dex_path, "rb") as f:
            dex_data = f.read()
        zi = zipfile.ZipInfo("classes.dex", date_time=(2024, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        zout.writestr(zi, dex_data)

    print("已打包 {0}（{1} 字节）".format(out_apk, os.path.getsize(out_apk)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
