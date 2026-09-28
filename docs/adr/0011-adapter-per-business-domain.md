# ADR-0011：同一業務域共用一個 MCP server（adapter），以 bulkhead 隔離各舊系統

- 狀態：已採納（部分取代 [ADR-0001](0001-adapter-pod-instead-of-changing-legacy.md) 的部署方式）
- 日期：2026-09-28

## 背景

ADR-0001 決定「同一個 adapter image，**每個舊系統**配一份 `tools.yaml` 部署一次」，但沒有評估系統數量變多時的成本：

- 企業常有數十個舊系統；每個 adapter 為了高可用至少 2 個 pod，50 個系統就是 100+ 個 JVM pod（每個約 200–300MB）
- 維運數量隨系統數線性增加：監控、升級、排查
- Agent 要連的 MCP server 數量等於舊系統數量，tool 註冊與管理變複雜

## 選項

| 拓撲 | 優點 | 缺點 |
|---|---|---|
| 一系統一 adapter（ADR-0001） | pod 邊界即故障隔離；NetworkPolicy 最小權限到單一系統 | 資源與維運成本隨系統數線性增加 |
| **依業務域分組** | 部署單位對應組織與 namespace；成本隨業務域數量增加 | 同組內 NetworkPolicy 放寬到整個業務域；故障隔離需在程式內處理 |
| 全部共用一個 adapter | 最省資源 | 失去故障隔離與最小權限；所有團隊共用一個部署 |

## 決策

### 1. 一個業務域共用一個 MCP server（adapter Deployment）

- 同一業務域的所有舊系統由**同一個 MCP server** 對外提供 tool；Agent 每個業務域只連一個 MCP 端點

- 業務域以 **K8s namespace** 為邊界，例如 `domain-cs`（訂單、物流、工單）、`domain-finance`
- 每個 adapter 讀取 ConfigMap 掛載的 **`tools/` 目錄**，一份 yaml 對應一個舊系統
- Demo 只部署客服域：`adapter-cs`

### 2. 以 bulkhead 取代 pod 邊界做故障隔離

- 每個舊系統各自擁有：RestClient、**bulkhead**（併發上限）、**circuit breaker**、timeout（Resilience4j）
- 物流系統變慢時只會用完物流自己的配額，訂單與工單的 tool 不受影響

### 3. 最小權限改以業務域為單位

- **NetworkPolicy**：adapter 只能連到同一業務域的舊系統與 profile API（修正 ADR-0009 的「只能連到自己那個舊系統」）
- 權限判斷不變：token 仍一路傳到舊系統，由舊系統查 profile API（ADR-0009）

### 4. 程式不綁死部署拓撲

- adapter 讀「一個目錄下的 N 份 yaml」；N=1 就是一系統一 adapter，N=全部就是共用
- 拓撲是部署設定（ConfigMap 放哪些 yaml），不需改程式；高安全要求的系統仍可單獨部署
- Tool 名稱加系統前綴，避免衝突：`order.get_order_status`、`logistics.get_shipment`

## 取捨

- 同一業務域內，一個舊系統的 token 外洩影響範圍與 adapter 可連線範圍擴大到整個業務域；業務域內的系統通常由相同單位使用，風險可接受
- 同一 pod 內的故障隔離依賴 bulkhead 設定正確；需以測試驗證「一個系統逾時不影響其他系統」
- 業務域的 yaml 由多個團隊共同維護，改其中一份會重啟整個 adapter；以 ConfigMap 熱載入（ADR-0005）降低影響
- 選配的逐段 token exchange（ADR-0007）中，每個 adapter 的 client 改為每個業務域一個

## 延伸（未實作）

- Spring Boot AOT／GraalVM native image，降低單一 adapter 的記憶體用量
- 很少使用的業務域以 KEDA 做 scale-to-zero
