#!/usr/bin/env bash
# 重新生成九宫格 T9 的 schema + prism（云端/本机均可，需 x86_64 Linux）。
#
# 做的事（与 build-cmake-native-engines.sh 的 host 段同一批钉死输入）：
#   1. 解出 pinned librime + 依赖 + boost，编出 host 版 rime_deployer；
#   2. 搭出与正式引擎数据同源的 shared 目录（luna/essay/prelude + frost 词库
#      + 笔画词典）——prism 与 table 的 syllable_id 必须同源，不能随便编；
#   3. 调 scripts/generate-t9-schema.py --compile：生成 schema、用上面的
#      deployer 编 prism、拷进 assets 并更新 MANIFEST.json。
#
# 用法：
#   ./scripts/research/fetch-native-engine-inputs.sh ~/tmp/feelime-native-inputs
#   FEELIME_NATIVE_INPUTS=~/tmp/feelime-native-inputs \
#   FEELIME_NATIVE_WORK=~/tmp/t9-work bash scripts/research/rebuild-t9-prism.sh
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
inputs="${FEELIME_NATIVE_INPUTS:?set FEELIME_NATIVE_INPUTS to the verified archive directory}"
work="${FEELIME_NATIVE_WORK:?set FEELIME_NATIVE_WORK to a new empty build directory}"

[[ "$(uname -m)" == x86_64 ]] || { echo "requires x86_64" >&2; exit 2; }
[[ ! -e "$work" ]] || { echo "FEELIME_NATIVE_WORK must not already exist: $work" >&2; exit 2; }

mkdir -p "$work/src/rime/librime/deps" "$work/src/rime-data"
export SOURCE_DATE_EPOCH=1724457600
reproducible_paths="-ffile-prefix-map=$work=/feelime-build -fmacro-prefix-map=$work=/feelime-build"
export CFLAGS="${CFLAGS:-} $reproducible_paths"
export CXXFLAGS="${CXXFLAGS:-} $reproducible_paths"
extract_into() {
  local archive="$1" destination="$2"
  mkdir -p "$destination"
  tar -xf "$inputs/$archive" --strip-components=1 -C "$destination"
}

extract_into librime-417db2385f732cb0fa194b497042c42abb897d99.tar.gz "$work/src/rime/librime"
extract_into glog-7b134a5c82c0c0b5698bb6bf7a835b230c5638e4.tar.gz "$work/src/rime/librime/deps/glog"
extract_into yaml-cpp-2f86d13775d119edbb69af52e5f566fd65c6953b.tar.gz "$work/src/rime/librime/deps/yaml-cpp"
extract_into leveldb-99b3c03b3284f5886f9ef9a4ef703d57373e61be.tar.gz "$work/src/rime/librime/deps/leveldb"
extract_into marisa-trie-3e87d53b78e15f2f43783d5e376561a8c9722051.tar.gz "$work/src/rime/librime/deps/marisa-trie"
extract_into opencc-e9f3bb3fa1d058fe2fd4f096503222854bdc31ab.tar.gz "$work/src/rime/librime/deps/opencc"
extract_into boost-1.89.0-cmake.tar.xz "$work/src/rime/boost"
cp -R "$repo_root/spikes/native-engine-smoke/native-src/rime/." "$work/src/rime/"

for package in prelude essay luna-pinyin stroke rime-frost; do
  mkdir -p "$work/src/rime-data/$package"
done
extract_into rime-prelude-082425ea0684bca36474415d4a0e8db9b016487e.tar.gz "$work/src/rime-data/prelude"
extract_into rime-essay-e9b1a374a6ea015fca5bdd04318924b4483ac35a.tar.gz "$work/src/rime-data/essay"
extract_into rime-luna-pinyin-56b934b099dfbeab842320f13aa8b461a6ab3e42.tar.gz "$work/src/rime-data/luna-pinyin"
extract_into rime-stroke-1e8fff9b9494ddec23b0cbc526bcfd8171a6fd48.tar.gz "$work/src/rime-data/stroke"
patch --silent -d "$work/src/rime-data/luna-pinyin" -p1 \
  < "$repo_root/scripts/research/patches/luna-pinyin-zh-hans-reset.patch"
extract_into rime-frost-96278d8.tar.gz "$work/src/rime-data/rime-frost"

cmake -S "$work/src/rime" -B "$work/build/rime-host" -G Ninja \
  -DFEELIME_HOST_DEPLOYER=ON -DCMAKE_BUILD_TYPE=Release -DENABLE_LOGGING=OFF \
  -DCMAKE_C_FLAGS="$reproducible_paths" -DCMAKE_CXX_FLAGS="$reproducible_paths"
cmake --build "$work/build/rime-host" --target rime_deployer

rime_shared="$work/rime-data/shared"
mkdir -p "$rime_shared/cn_dicts"
cp -R "$work/src/rime-data/prelude/." "$rime_shared/"
cp -R "$work/src/rime-data/essay/." "$rime_shared/"
cp -R "$work/src/rime-data/luna-pinyin/." "$rime_shared/"
for piece in 8105 41448 base ext others corrections; do
  cp "$work/src/rime-data/rime-frost/cn_dicts/$piece.dict.yaml" "$rime_shared/cn_dicts/"
done
cp "$repo_root/scripts/research/rime-dicts/rime-frost-umbrella.dict.yaml" \
  "$rime_shared/luna_pinyin.dict.yaml"
python3 "$repo_root/scripts/generate-stroke-dict.py" \
  --output "$rime_shared/stroke.dict.yaml" \
  --src "$work/src/rime-data/stroke/stroke.dict.yaml" \
  --essay "$work/src/rime-data/essay/essay.txt"
cp "$repo_root/app/src/main/assets/engine-data/rime/feelime_stroke.schema.yaml" "$rime_shared/"

cd "$repo_root"
FEELIME_T9_DEPLOYER="$work/build/rime-host/librime/bin/rime_deployer" \
FEELIME_T9_SHARED="$rime_shared" \
  python3 scripts/generate-t9-schema.py --compile
