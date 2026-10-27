#!/bin/sh
# hu-agent 安装脚本（macOS / Linux）：
#   curl -fsSL https://raw.githubusercontent.com/lihu-001/hu-agent/main/install.sh | sh
#
# 安装布局（重复运行即升级，配置与信任记录保留）：
#   ~/.hu-agent/app/       启动脚本与 hu-agent.jar，每次整体替换
#   ~/.hu-agent/runtime/   私有 Java 运行时（Temurin JRE，版本固定，变化时才重新下载）
#   ~/.local/bin/hu        指向 app/bin/hu 的链接
#
# 可选环境变量：
#   HU_VERSION         安装指定版本（如 v0.1.0），默认最新发布
#   HU_HOME            安装目录，默认 ~/.hu-agent
#   HU_BIN_DIR         hu 命令链接所在目录，默认 ~/.local/bin
#   HU_DOWNLOAD_BASE   应用包下载地址前缀（镜像用），默认 GitHub Releases
#   HU_JRE_BASE        Java 运行时下载地址前缀（镜像用），默认 Adoptium 的 GitHub Releases
#   HU_NO_MODIFY_PATH  设为 1 时不修改 shell 配置文件
#
# 变量后紧跟中文时必须写成 ${var}：macOS 的 /bin/sh（bash 3.2）会把多字节字符算进变量名，配合 set -u 直接退出。
set -eu

REPO="lihu-001/hu-agent"
# 锁定的 Java 运行时版本；升级时同时更新下面各平台的 sha256（来自 Adoptium API 的 package.checksum）。
JRE_RELEASE="jdk-17.0.20.1+1"
JRE_FILE_VERSION="17.0.20.1_1"

say() { printf 'hu-agent> %s\n' "$*"; }
fail() {
    printf 'hu-agent> 错误：%s\n' "$*" >&2
    exit 1
}

command -v curl >/dev/null 2>&1 || fail "需要 curl"
command -v tar >/dev/null 2>&1 || fail "需要 tar"

download() {
    curl -fL --retry 3 --progress-bar -o "$2" "$1" || fail "下载失败：$1"
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d ' ' -f 1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d ' ' -f 1
    else
        fail "需要 sha256sum 或 shasum"
    fi
}

verify() {
    actual=$(sha256_of "$1")
    [ "$actual" = "$2" ] || fail "校验失败：$(basename "$1")（期望 $2，实际 ${actual}）"
}

# ---- 平台 ----
os=$(uname -s)
arch=$(uname -m)
case $os in
    Linux)
        jre_os=linux
        if [ -f /etc/alpine-release ]; then
            jre_os=alpine-linux
        fi
        ;;
    Darwin) jre_os=mac ;;
    *) fail "不支持的系统：${os}（Windows 请在 PowerShell 中使用 install.ps1）" ;;
esac
case $arch in
    x86_64 | amd64) jre_arch=x64 ;;
    arm64 | aarch64) jre_arch=aarch64 ;;
    *) fail "不支持的 CPU 架构：$arch" ;;
esac
# Apple 芯片上通过 Rosetta 运行的终端会报告 x86_64，改用原生 arm64 运行时。
if [ "$jre_os" = mac ] && [ "$jre_arch" = x64 ] && [ "$(sysctl -n sysctl.proc_translated 2>/dev/null || echo 0)" = 1 ]; then
    jre_arch=aarch64
fi
case "$jre_os-$jre_arch" in
    linux-x64) jre_sha256=0b2b640e3046b64c8ec504de0ab9d91bb5610182bda21fad454681ce54d45a62 ;;
    linux-aarch64) jre_sha256=b8efcd5acc9109fe8d35bed132499643048a257b4f6042906ece37d03c839d77 ;;
    alpine-linux-x64) jre_sha256=72423b1464907857f4e7ca854a2b8fd9f7cf6b9dd15bd3ea31ef6cf33b097aea ;;
    mac-x64) jre_sha256=333cb81123c36568586646c73c8fa2326dab8badc43f5ea388a90fff59c9df27 ;;
    mac-aarch64) jre_sha256=190480874ccceb358cbc840393207f77ac3e63a4c5f8129d0e23e9518b96ad05 ;;
    *) fail "没有适用于 $jre_os/$jre_arch 的 Java 运行时" ;;
esac

# ---- 位置 ----
version=${HU_VERSION:-latest}
if [ "$version" = latest ]; then
    app_base="https://github.com/$REPO/releases/latest/download"
else
    app_base="https://github.com/$REPO/releases/download/$version"
fi
app_base=${HU_DOWNLOAD_BASE:-$app_base}
jre_base=${HU_JRE_BASE:-https://github.com/adoptium/temurin17-binaries/releases/download/$(printf '%s' "$JRE_RELEASE" | sed 's/+/%2B/')}
hu_home=${HU_HOME:-$HOME/.hu-agent}
bin_dir=${HU_BIN_DIR:-$HOME/.local/bin}
mkdir -p "$hu_home" "$bin_dir"
hu_home=$(cd "$hu_home" && pwd)
bin_dir=$(cd "$bin_dir" && pwd)

# 临时目录放在安装目录内，最后一步只是同一文件系统内的重命名。
work=$(mktemp -d "$hu_home/.install.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 1' INT TERM

# ---- 下载并校验（全部成功后才替换已安装的文件）----
say "下载 hu-agent（${version}）"
download "$app_base/hu-agent.tar.gz" "$work/hu-agent.tar.gz"
download "$app_base/hu-agent.tar.gz.sha256" "$work/hu-agent.tar.gz.sha256"
verify "$work/hu-agent.tar.gz" "$(cut -d ' ' -f 1 < "$work/hu-agent.tar.gz.sha256")"
mkdir "$work/app"
tar -xzf "$work/hu-agent.tar.gz" -C "$work/app"
[ -f "$work/app/hu-agent/lib/hu-agent.jar" ] || fail "应用包内容不完整"

runtime="$hu_home/runtime"
jre_home=
if [ -x "$runtime/bin/java" ] && [ "$(cat "$runtime/.hu-runtime" 2>/dev/null || true)" = "$JRE_RELEASE" ]; then
    say "Java 运行时已是 ${JRE_RELEASE}，跳过下载"
else
    jre_file="OpenJDK17U-jre_${jre_arch}_${jre_os}_hotspot_${JRE_FILE_VERSION}.tar.gz"
    say "下载 Java 运行时 ${JRE_RELEASE}（$jre_os/${jre_arch}，约 45MB）"
    download "$jre_base/$jre_file" "$work/jre.tar.gz"
    verify "$work/jre.tar.gz" "$jre_sha256"
    mkdir "$work/jre"
    tar -xzf "$work/jre.tar.gz" -C "$work/jre"
    jre_home=$(find "$work/jre" -mindepth 1 -maxdepth 1 -type d | head -n 1)
    # macOS 的包是 jdk-xxx-jre/Contents/Home 结构。
    if [ -d "$jre_home/Contents/Home" ]; then
        jre_home="$jre_home/Contents/Home"
    fi
    [ -x "$jre_home/bin/java" ] || fail "Java 运行时内容不完整"
    printf '%s\n' "$JRE_RELEASE" > "$jre_home/.hu-runtime"
fi

# ---- 安装 ----
replace() {
    rm -rf "$2.old"
    if [ -e "$2" ]; then
        mv "$2" "$2.old"
    fi
    mv "$1" "$2"
    rm -rf "$2.old"
}
if [ -n "$jre_home" ]; then
    replace "$jre_home" "$runtime"
fi
replace "$work/app/hu-agent" "$hu_home/app"
chmod +x "$hu_home/app/bin/hu"
ln -sf "$hu_home/app/bin/hu" "$bin_dir/hu"

installed=$("$bin_dir/hu" --version) || fail "安装后无法运行 hu --version"

# ---- PATH ----
case ":$PATH:" in
    *":$bin_dir:"*) ;;
    *)
        if [ "${HU_NO_MODIFY_PATH:-0}" = 1 ]; then
            say "请把 $bin_dir 加入 PATH"
        else
            case $(basename "${SHELL:-sh}") in
                zsh)
                    rc="$HOME/.zshrc"
                    line="export PATH=\"$bin_dir:\$PATH\""
                    ;;
                bash)
                    if [ "$jre_os" = mac ]; then rc="$HOME/.bash_profile"; else rc="$HOME/.bashrc"; fi
                    line="export PATH=\"$bin_dir:\$PATH\""
                    ;;
                fish)
                    rc="$HOME/.config/fish/config.fish"
                    line="fish_add_path \"$bin_dir\""
                    ;;
                *)
                    rc="$HOME/.profile"
                    line="export PATH=\"$bin_dir:\$PATH\""
                    ;;
            esac
            if ! grep -qs "# hu-agent PATH" "$rc"; then
                mkdir -p "$(dirname "$rc")"
                printf '\n# hu-agent PATH\n%s\n' "$line" >> "$rc"
            fi
            say "已把 $bin_dir 加入 ${rc}；请重新打开终端（或执行 source ${rc}）后使用 hu"
        fi
        ;;
esac

say "安装完成：${installed}（${hu_home}）"
say "进入项目目录运行 hu 即可开始；首次运行会引导填写模型接口与 API Key。"
