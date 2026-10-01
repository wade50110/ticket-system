#!/usr/bin/env bash
# 舊 image 清理(v0.7 CI/CD,需求書 F-14 / 決策 #11)。
# 用法:prune-images.sh <repo> <keep> [protected-tag ...]
#   - 只處理 <repo> 名下 tag 為 7~12 碼 hex(git sha)的 image
#   - 以 image ID 分組、依 image 建立時間排序,保留最新 <keep> 個 ID 的全部 tag
#   - 另保護 "local" 與參數列出的 tag(pipeline 傳入 Deployment 既存 ReplicaSet 引用的 image,確保 rollout undo 有 image)
#   - 其餘 docker rmi <repo>:<tag>(不加 -f:只解 tag;若是最後一個 tag 且被容器使用會失敗 → 只警告)
#   - 冪等;單筆失敗只警告;永遠 exit 0(清理不應讓 build 失敗)
set -uo pipefail

repo="${1:?用法:prune-images.sh <repo> <keep> [protected-tag ...]}"
keep="${2:?用法:prune-images.sh <repo> <keep> [protected-tag ...]}"
shift 2
protected=" local $* "

declare -A id_of=()
declare -A created_of=()
tags=()

while read -r tag id; do
    [[ "$tag" =~ ^[0-9a-f]{7,12}$ ]] || continue
    tags+=("$tag")
    id_of["$tag"]="$id"
    if [ -z "${created_of[$id]:-}" ]; then
        created_of["$id"]=$(docker image inspect -f '{{.Created}}' "$id" 2>/dev/null || echo "0000")
    fi
done < <(docker images --no-trunc --format '{{.Tag}} {{.ID}}' "$repo" 2>/dev/null)

if [ ${#tags[@]} -eq 0 ]; then
    echo "prune: $repo 沒有 sha tag,略過"
    exit 0
fi

# 依建立時間新→舊排序 image ID,取前 keep 個
keep_ids=$(for id in "${!created_of[@]}"; do echo "${created_of[$id]} $id"; done | sort -r | head -n "$keep" | awk '{print $2}')

kept=0
removed=0
for tag in "${tags[@]}"; do
    id="${id_of[$tag]}"
    if [[ "$protected" == *" $tag "* ]] || grep -qx "$id" <<< "$keep_ids"; then
        kept=$((kept + 1))
        continue
    fi
    if docker rmi "$repo:$tag" > /dev/null 2>&1; then
        echo "prune: 移除 $repo:$tag"
        removed=$((removed + 1))
    else
        echo "prune: 警告:無法移除 $repo:$tag(可能仍被容器使用),略過"
        kept=$((kept + 1))
    fi
done

echo "prune: $repo → 保留 $kept 個 sha tag、移除 $removed 個(keep=$keep,protected=[$(echo $protected)])"
exit 0
