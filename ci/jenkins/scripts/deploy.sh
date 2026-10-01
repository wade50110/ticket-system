#!/usr/bin/env bash
# k8s 換版腳本(v0.7 CI/CD,需求書 F-6 / F-7 / 決策 #8)。兩個 Jenkinsfile 共用。
# 用法:deploy.sh <deployment> <container> <image:tag> <change-cause> <rollout-timeout> <app-label>
# 流程:kubectl set image → annotate change-cause → rollout status
#       逾時/失敗 → 收集診斷(pods、非 Ready pod 的 describe 含 Events、logs)→ rollout undo → 等回滾完成 → exit 1
# 成功 exit 0(呼叫端在這之後才把 :local 指到新 image)。
set -uo pipefail

dep="${1:?deployment}"
ctr="${2:?container}"
img="${3:?image:tag}"
cause="${4:?change-cause}"
timeout="${5:-300s}"
app="${6:-$1}"

current_image() {
    kubectl get deployment "$dep" -o jsonpath="{.spec.template.spec.containers[?(@.name==\"$ctr\")].image}"
}

prev_img=$(current_image)
replicas=$(kubectl get deployment "$dep" -o jsonpath='{.spec.replicas}') \
    || { echo "::讀不到 Deployment $dep(kubectl 失敗:連線 / RBAC / kubeconfig),中止部署"; exit 1; }
if ! [[ "$replicas" =~ ^[0-9]+$ ]] || [ "$replicas" -le 0 ]; then
    echo "::Deployment $dep 的 replicas=${replicas:-?}:新版不會真的啟動、rollout status 會假成功,無法驗證;請先 scale 回來再部署"
    exit 1
fi
echo "== Deploy $dep/$ctr:$prev_img → $img(timeout $timeout,replicas $replicas)"

if ! kubectl set image "deployment/$dep" "$ctr=$img"; then
    echo "::set image 失敗(Deployment/container 名稱或 RBAC?)"
    exit 1
fi
kubectl annotate "deployment/$dep" "kubernetes.io/change-cause=$cause" --overwrite > /dev/null \
    || echo "(annotate change-cause 失敗,忽略)"

if kubectl rollout status "deployment/$dep" --timeout="$timeout"; then
    echo "== rollout 完成:$dep 現在是 $(current_image)"
    kubectl get pods -l "app=$app" -o wide
    exit 0
fi

echo "== rollout 未在 $timeout 內完成,收集診斷後自動回滾 =="
kubectl get pods -l "app=$app" -o wide || true
not_ready=$(kubectl get pods -l "app=$app" \
    -o jsonpath='{range .items[*]}{.metadata.name}{" "}{range .status.containerStatuses[*]}{.ready}{end}{"\n"}{end}' \
    | awk '$2 != "true" {print $1}')
for p in $not_ready; do
    echo "---- describe pod $p(尾段含 Events)----"
    kubectl describe pod "$p" | tail -n 40 || true
    echo "---- logs $p(最後 50 行)----"
    kubectl logs "$p" --all-containers --tail=50 2>&1 || true
    # CrashLoop 的當前容器 log 常是空的,補上一次重啟前的 log
    if kubectl logs "$p" --previous --all-containers --tail=50 > /tmp/prev-log.$$ 2>/dev/null && [ -s /tmp/prev-log.$$ ]; then
        echo "---- logs $p --previous(重啟前,最後 50 行)----"
        cat /tmp/prev-log.$$
    fi
    rm -f /tmp/prev-log.$$
done

echo "== kubectl rollout undo deployment/$dep =="
if kubectl rollout undo "deployment/$dep"; then
    if kubectl rollout status "deployment/$dep" --timeout=180s; then
        echo "== 已回滾:$dep 現在是 $(current_image)(部署前為 $prev_img)"
    else
        echo "::回滾後 rollout 仍未就緒,請人工介入(kubectl get pods -l app=$app)"
    fi
else
    echo "::rollout undo 失敗,請人工介入"
fi
kubectl get pods -l "app=$app" -o wide || true
echo "::Deploy 失敗(已嘗試回滾)"
exit 1
