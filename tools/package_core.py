"""打包同一份锁定版本的酷狗接口核心，不包含桌面凭据、设备数据或原生插件。"""
from pathlib import Path
import hashlib
import json
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / 'CoreRuntime'


# 仅选取运行所需的依赖文件，排除缓存、源仓库与电脑专用二进制。
def runtime_files() -> list[Path]:
    modules = CORE / 'node_modules'
    if not modules.is_dir():
        raise SystemExit('请先在 CoreRuntime 运行 npm ci --omit=dev')
    return sorted(p for p in modules.rglob('*') if p.is_file()
                  and not any(part in ('.git', '.cache', '__pycache__') for part in p.parts)
                  and p.suffix not in ('.node', '.pyc'))


# 生成可校验的核心包标识，应用升级时只在包内容变化后重新解压。
def package(output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output / 'kugou_core.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for source in runtime_files():
            archive.write(source, source.relative_to(CORE).as_posix())
        archive.write(CORE / 'device.mjs', 'device.mjs')
        archive.write(Path(__file__).with_name('kugou_bridge.cjs'), 'kugou_bridge.cjs')
        archive.writestr('package.json', json.dumps({'type': 'commonjs', 'private': True}))
    digest = hashlib.sha256((output / 'kugou_core.zip').read_bytes()).hexdigest()
    (output / 'kugou_core.sha256').write_text(digest)
    print('酷狗核心已打包，接口模块数：', len(list((CORE / 'node_modules/kugoumusicapi/module').glob('*.js'))))


if __name__ == '__main__':
    package(Path(sys.argv[1]))
