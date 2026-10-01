#!/usr/bin/env bash
# 從已 build 的 Jenkins image 讀出實際安裝的 plugin 版本,產出鎖版 plugins.txt(v0.7 決策 #12:base image 與 plugins 皆鎖版才可重現)。
# 用法:pin-plugins.sh [image=ticket-jenkins:local] [output=ci/jenkins/plugins.txt]
# 升級流程:改 plugins.txt 的版本(或暫時去掉版本讓它解析最新)→ docker compose --profile ci build → 跑本腳本寫回 → 再 build 一次確認可重現。
set -euo pipefail

image="${1:-ticket-jenkins:local}"
out="${2:-$(cd "$(dirname "$0")/.." && pwd)/plugins.txt}"

list=$(docker run --rm --entrypoint bash "$image" -c 'jenkins-plugin-cli --list -d /usr/share/jenkins/ref/plugins 2>/dev/null' \
    | tr -d '\r' \
    | awk '/^(Installed plugins|Resulting plugin list):/ {f=1; next} /^$/ {if (f) exit} f && NF >= 2 {print $1 ":" $2}' \
    | sort)

if [ -z "$list" ]; then
    echo "沒有讀到 plugin 清單(image $image 存在嗎?)" >&2
    exit 1
fi

{
    echo "# 鎖版 plugin 清單(name:version),由 ci/jenkins/scripts/pin-plugins.sh 從 image ${image} 產出($(date +%F))。"
    echo "# 頂層需求:configuration-as-code job-dsl workflow-aggregator git junit pipeline-stage-view timestamper;其餘為相依。"
    echo "# 升級:改版本或暫時去掉版本 → docker compose --profile ci build → 重跑本腳本 → 再 build 確認可重現。"
    printf '%s\n' "$list"
} > "$out"

echo "寫入 $(grep -vc '^#' "$out") 個鎖版 plugin → $out"
