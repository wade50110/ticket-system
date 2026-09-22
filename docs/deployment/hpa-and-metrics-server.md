# HPA 與 metrics-server 是什麼、怎麼運作

> 對應決策 4(架構總覽 architecture.md)。這份講清楚:HPA 是什麼、metrics-server 為什麼必要、兩者怎麼配合、這個專案怎麼設定與實測。

## 一、HPA 是什麼

**HPA = Horizontal Pod Autoscaler(水平 Pod 自動擴縮)。**

「水平擴縮」= 增減 **pod 的數量**(多開幾個一模一樣的 pod 分攤流量),對比「垂直擴縮」是把單一 pod 的 CPU/記憶體配額調大。搶票尖峰要的是前者:瞬間湧入大量請求時多開幾個 backend pod 一起扛,離峰再縮回去省資源。

HPA 是 k8s 內建的一個物件,你給它三件事:

- **盯著哪個 Deployment**(這裡是 backend)。
- **看什麼指標、目標值多少**(這裡:CPU 平均使用率目標 50%)。
- **pod 數量範圍**(min / max,例如 min=2、max=10)。

然後它會自動調整那個 Deployment 的 pod 數,讓實際指標往目標值靠。

## 二、HPA 怎麼運作(控制迴圈)

HPA 是一個**每隔約 15 秒跑一次的控制迴圈**:

```
  每 ~15 秒:
    1. 向 metrics API 問:backend 這些 pod 現在平均 CPU 用多少?
    2. 套公式算出「應該要幾個 pod」:
         期望 pod 數 = ceil( 現在 pod 數 × (目前指標 / 目標指標) )
    3. 把 Deployment 的 replicas 調成那個數(夾在 min..max 之間)
```

**舉例**(目標 CPU 50%、目前 3 個 pod):

- 壓測打進來,3 個 pod 平均 CPU 飆到 90% → `ceil(3 × 90/50) = ceil(5.4) = 6` → 擴到 6 個 pod。
- 流量退了,6 個 pod 平均 CPU 掉到 20% → `ceil(6 × 20/50) = ceil(2.4) = 3` → 縮回 3 個。

**擴容快、縮容慢**:k8s 預設「擴容」反應積極(尖峰要立刻扛),「縮容」有冷卻時間(預設穩定 5 分鐘才縮,避免流量抖動時 pod 數一直上下跳)。這兩個行為都可調。

## 三、metrics-server 是什麼、為什麼一定要裝

上面第 1 步「問 pod 現在 CPU 用多少」——**k8s 本身不會回答這個問題**。k8s 預設不收集 pod 的即時 CPU/記憶體用量,所以 HPA 問下去會得到「metrics not available」,然後**完全不動作**。

**metrics-server** 就是補上這塊的元件:它是一個跑在叢集裡的小服務,定期向每個 node 的 kubelet 收集各 pod 的 CPU/記憶體用量,彙整後透過 **Metrics API**(`metrics.k8s.io`)提供出來。裝了它之後:

- `kubectl top pods` 才有數字(可以手動看用量)。
- HPA 才問得到 CPU 指標,才會運作。

**關係圖:**

```
   kubelet(每個 node,知道各 pod 用量)
        │  收集
        ▼
   metrics-server ──提供──► Metrics API (metrics.k8s.io)
                                  │  查詢
                                  ▼
                                 HPA ──調整──► Deployment 的 replicas
```

所以:**沒有 metrics-server,CPU-based HPA 就是個不會動的擺設。** 這就是決策 4 要「CPU-based + 裝 metrics-server」的原因——兩個是一組的。

### Docker Desktop 本機要注意

Docker Desktop 內建的 k8s **預設沒有 metrics-server**,要自己裝。而且本機叢集的 kubelet 憑證是自簽的,標準 metrics-server 會因為 TLS 驗證失敗連不上 kubelet,需要加一個參數 `--kubelet-insecure-tls`(只在本機/開發這樣用,正式環境不要)。實作階段我會把安裝步驟寫成可直接執行的指令。

## 四、這個專案的 HPA 設定(草案,實作時確認)

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: ticket-backend
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: ticket-backend
  minReplicas: 2          # 離峰也保留 2 個(避免單點、給滾動更新空間)
  maxReplicas: 10         # 尖峰上限
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 50   # 目標:平均 CPU 50%
```

**前提**:Deployment 的容器必須設 `resources.requests.cpu`(例如 `250m`)。因為 HPA 的「CPU 使用率 50%」是相對於 **request** 算的(用了 125m / request 250m = 50%)。沒設 request,HPA 算不出百分比。這個實作時會一起設。

## 五、怎麼實測 HPA 開關 pod(實作後)

1. 裝好 metrics-server,`kubectl top pods` 有數字。
2. 部署 backend Deployment(帶 resources.requests)+ HPA。
3. 開一個 watch:`kubectl get hpa,pods -w`。
4. 用壓測工具對 `/api/health` 或登入/瀏覽票券打大量請求(製造 CPU 負載)。
5. 觀察:pod 平均 CPU 上升 → HPA 幾十秒內把 replicas 從 2 拉到更多 → 新 pod 起來分攤。
6. 停止壓測 → 幾分鐘冷卻後 → HPA 把 replicas 縮回 2。

## 六、搶票場景的實務考量(知道就好,本次先用 CPU)

- **CPU HPA 對突發尖峰反應會有延遲**:metrics-server 收集有間隔(約 15 秒)+ HPA 迴圈 15 秒 + 新 pod 啟動(Spring Boot 冷啟動要時間)。搶票是「開賣瞬間」的突刺,等 HPA 反應過來可能尖峰已過。真實高併發搶票常搭配:**提前預熱(開賣前手動/定時把 replicas 拉高)**、以 QPS/佇列長度等自訂指標而非 CPU、甚至前面加排隊系統(這個專案 v0.4 規劃過搶票排隊)。
- **本次目標是把 autoscale 機制建起來並能 demo**,用 CPU 指標最直接;自訂指標(需 Prometheus Adapter 等)是後續的進階題。
- **縮容冷卻**:預設 5 分鐘穩定窗口是好事,避免 pod 數在流量抖動時反覆震盪。
