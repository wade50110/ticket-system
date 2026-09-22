# k8s 部署(本機 Docker Desktop)

搶票系統的容器化 + k8s 部署 runbook。整體設計見 [`../docs/deployment/architecture.md`](../docs/deployment/architecture.md)。

架構:前端 nginx(serve CSR 靜態 + 反代 `/api`)→ 後端 Service(ClusterIP,負載均衡)→ backend pods(Deployment + HPA)。MySQL/Redis 留在 k8s 外(docker-compose)。

## 前置

### 0. 起 MySQL / Redis(k8s 外)

```powershell
cd ticket-system
docker compose up -d          # MySQL 3307、Redis 6380
```

### 1. 啟用 Docker Desktop 的 Kubernetes(⚠️ 需手動,GUI 操作)

Docker Desktop → **Settings → Kubernetes → 勾 Enable Kubernetes → Apply & Restart**。等右下角 k8s 變綠。驗證:

```powershell
kubectl cluster-info          # 應顯示 control plane 在 https://kubernetes.docker.internal:6443
kubectl get nodes             # 應有一個 Ready 的 docker-desktop node
```

> 若 `kubectl` 連到 `localhost:8080` 被拒,就是 k8s 還沒啟用或 context 沒選對(`kubectl config use-context docker-desktop`)。

## 部署步驟

### 2. build images(本機 docker,不推 registry)

Docker Desktop k8s 直接用本機 docker daemon 的 image,`imagePullPolicy: IfNotPresent` 不會去 registry 拉。

```powershell
cd ticket-system
docker build -t ticket-backend:local  ./backend
docker build -t ticket-frontend:local ./frontend
```

> ⚠️ **改程式碼後重新部署**:tag 固定 `:local` + `imagePullPolicy: IfNotPresent`,重 build 後單純 `kubectl apply` 不會換版(Deployment spec 沒變、不觸發 rollout,舊 pod 續跑舊 image)。重 build 後要 `kubectl rollout restart deployment ticket-backend`(或 `ticket-frontend`)才會拉起用新 image 的 pod。

### 3. 裝 metrics-server(HPA 的 CPU 指標來源)

Docker Desktop k8s 預設沒有 metrics-server,且本機 kubelet 是自簽憑證,需加 `--kubelet-insecure-tls`(僅開發用):

```powershell
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
# patch:略過 kubelet TLS 驗證(本機自簽)
kubectl patch deployment metrics-server -n kube-system --type=json `
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

等它就緒後,`kubectl top nodes` / `kubectl top pods` 有數字,HPA 才問得到 CPU。

### 4. apply manifests

```powershell
cd ticket-system
kubectl apply -f k8s/backend.yaml
kubectl apply -f k8s/frontend.yaml
```

### 5. 驗證

```powershell
kubectl get pods              # backend ×2、frontend ×2 都 Running/Ready
kubectl get svc              # ticket-frontend 的 EXTERNAL-IP 應是 localhost
kubectl get hpa              # ticket-backend 應顯示 TARGETS(如 5%/50%),不是 <unknown>
```

`<unknown>` = metrics-server 沒裝好或還沒收集到,HPA 不會動作。

打開 **http://localhost** 就是完整系統(nginx serve 前端 + 反代 /api 到後端多 pod)。

## 實測 HPA 開關 pod

```powershell
# 一個視窗持續觀察
kubectl get hpa,pods -w
```

另一個視窗打壓測製造 CPU 負載(擇一):

```powershell
# 用叢集內的臨時 pod 對後端 Service 狂打
kubectl run load --image=williamyeh/hey --restart=Never -- `
  -z 3m -c 50 http://ticket-backend:8099/api/health
```

觀察:backend 平均 CPU 上升 → HPA 幾十秒內把 replicas 從 2 往上拉 → 新 pod 起來分攤。停止壓測後約 5 分鐘冷卻 → 縮回 2。清理:`kubectl delete pod load`。

## 疑難排解

### backend pod CrashLoop / 連不到 DB 或 Redis

pod 內解析不到 `host.docker.internal`(隨 Docker Desktop 版本而異,pod 走 CoreDNS 不是 Docker DNS)。三種解法擇一:

1. **改用 host IP**:把 `k8s/backend.yaml` ConfigMap 的 `DB_HOST`/`REDIS_HOST` 改成主機在 Docker 網段的實際 IP。
2. **hostAliases**:在 backend Deployment 的 `spec.template.spec` 加:
   ```yaml
   hostAliases:
     - ip: "192.168.65.254"        # Docker Desktop host-gateway,實際值以 `kubectl exec` 內 ping 確認
       hostnames: ["host.docker.internal"]
   ```
3. **CoreDNS rewrite**:editk `kube-system/coredns` ConfigMap 加 rewrite 規則。

先 `kubectl logs <backend-pod>` 看是哪個(MySQL 還是 Redis)連不上。

### HPA TARGETS 顯示 `<unknown>`

metrics-server 沒就緒。`kubectl -n kube-system get pods | Select-String metrics-server` 看狀態;`kubectl top pods` 若報錯,通常是還沒加 `--kubelet-insecure-tls` 或還在啟動。

### 前端能開但 /api 502

nginx 反代不到 backend Service。確認 `kubectl get svc ticket-backend` 存在、backend pod Ready;service 名稱要和 nginx.conf 的 `proxy_pass http://ticket-backend:8099` 一致。
