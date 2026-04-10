# Ethereum ERC-20 Transfer Indexer 區塊鏈轉帳索引器 (專業增強版)

這是一個基於 **Java 21** 與 **Spring Boot 3** 開發的高級以太坊鏈上數據索引服務。它不僅能抓取資料，還具備了應對區塊鏈複雜網路環境（如分岔、連線延遲）的自動修復能力。

## 🚀 專業級亮點

- **分岔偵測與自動回退 (Re-org Handling)**：
  - 每輪掃描前自動比對鏈上與資料庫的區塊雜湊 (`blockHash`)。
  - 當偵測到以太坊發生分岔時，自動執行資料回滾，刪除「髒區塊」並重新抓取。
- **安全確認數機制 (Confirmations)**：
  - 實作安全延遲掃描，固定落後最新區塊 3 格，大幅降低遇到微型分岔的機率。
- **異步生產者-消費者架構 (Async Decoupling)**：
  - 核心監控執行緒負責發現數據（Producer）。
  - 非同步執行緒池負責解析與存檔數據（Consumer），確保掃描任務不被資料庫 IO 堵塞。
- **斷點續傳 (State Persistence)**：
  - 重啟後自動從資料庫讀取最後進度，無縫恢復同步。
- [x] Chunked Catch-up Logic
    - [x] Add `MAX_POLL_RANGE` and range clamping logic
    - [x] Update README with catch-up details
- [x] Verification & Demo

## 🛠 技術棧

- **Core**: Java 21, Spring Boot 3.2.4 (Async, Scheduling)
- **Blockchain**: Web3j (Ethereum JSON-RPC)
- **Database**: H2 (Dev), MySQL 8 (Prod)
- **Data Integrity**: JPA Transactions

## 📋 快速開始

### 1. 執行專案
```bash
mvn spring-boot:run
```

### 2. 查看專業日誌
- `【掃描中】範圍: 2484... -> 2484... | 鏈高度: 2485...`：顯示目前的安全處理範圍與鏈上真實高度。
- `【非同步處理】開始解析... (執行緒: task-1)`：代表非同步處理機制正在運作。

## 🔬 核心邏輯架構

### A. 資料一致性 (Re-org Logic)
索引器會記錄每一筆資料所在的「區塊雜湊」。每次 Poll 開始前：
1. 取出 DB 最後一筆資料。
2. 呼叫 `eth_getBlockByNumber` 驗證該號碼的雜湊是否與 DB 一致。
3. 若不一致，執行 `DELETE >= currentBlock` 並重置進度。

### B. 效能優化 (Async Logic)
使用 `@EnableAsync` 將 `TransferEventProcessor` 解耦。監控任務只需要發現資料並「分發」，處理細節交由執行緒池完成，這對於高流量代幣（如 WETH）的抓取至關重要。

## 🧪 如何測試「分岔回退」？

1. 啟動程式抓取部分資料後關閉。
2. 開啟 H2 Console (`http://localhost:8801/h2-console`)。
3. 手動修改最後一筆資料的雜湊值：
   ```sql
   UPDATE TRANSFER_EVENTS SET BLOCK_HASH = '0xINVALID' WHERE ID = (SELECT MAX(ID) FROM TRANSFER_EVENTS);
   ```
4. 再次啟動程式，觀察日誌中的 `⚠️ 【偵測到分岔！】` 與 `🔄 【自動回退】`。

### 4. 分段同步與自動補抓 (Chunked Catch-up)
*   **問題**：若程式關機過久，重啟後一次抓取數千個區塊會被 RPC 節點封鎖或回傳錯誤。
*   **對策**：實作了 `MAX_POLL_RANGE=100` 的限制。系統會自動偵測進度差距，並以每 100 區塊為一組進行「分段下載」，直到追上最新高度為止。

---

## 🎓 技術細節與面試 Q&A (Technical Interview)

本專案在設計時考慮了多個生產環境中的實際問題，以下是相關的設計決策與應對方案：

### 1. 數據丟失與一致性 (Data Integrity)
*   **問題**：若寫入時斷電或 RPC 閃斷導致數據重複或遺漏，如何處理？
*   **對策**：
    *   **冪等性 (Idempotency)**：我們在 `transactionHash` 欄位加上了唯一約束 (`unique=true`)，確保同一筆交易絕不會重複存入。
    *   **檢查點機制 (Checkpointing)**：系統啟動時會自動讀取資料庫中已同步的最後區塊高度，並以此作為起始點進行增量同步，實現「斷點續傳」。

### 2. 高併發下的效能瓶頸
*   **問題**：區塊鏈交易量極大時，資料庫寫入可能會成為瓶頸。
*   **對策**：
    *   **非同步解耦**：將監控與處理拆分為「生產者-消費者」模型，確保 RPC 抓取不被 DB 延時拖慢。
    *   **批次寫入 (Batch Insert)**：在配置中啟用了 `hibernate.jdbc.batch_size`，將多筆記錄濃縮為單次資料庫請求，大幅提升吞吐量。

### 3. 處理「分岔回滾」 (Chain Reorg)
*   **問題**：若某個區塊被鏈撤銷了，資料庫裡的「假帳」怎麼辦？
*   **對策**：
    *   **回溯機制**：每輪掃描前會執行 `checkAndHandleReorg()`，比對本地 Hash 與鏈上 Hash。若不一致則自動執行 `DELETE >= currentBlock` 並回退重新掃描。
    *   **安全確認數**：設定 `confirmations=3` 作為緩衝，只讀取「已成熟」的區塊數據，大幅降低遇到微型分岔的機率。
    *   **終極防線**：在大額交易場景下，可進一步對接以太坊 PoS 的 `Finalized` 狀態標記，提供數學意義上的不可逆保證。

---
## 🚀 進階面試挑戰：高併發與數據一致性 (Advanced Challenges)

在面對極端情境（如 10 萬 TPS 的 Aster Chain）時，本專案的架構可進一步演進以應對以下挑戰：

### Q1：如何解決資料庫寫入瓶頸 (Write Amplification)？
*   **挑戰**：單筆 `save()` 會導致連線池耗盡與 IO 負載過重。
*   **應對**：引入 **Batch Processing (批次處理)** 模式。在 `EventProcessor` 中加入緩衝區（如 RingBuffer），當累積滿 500 筆或超過 100ms 時，執行一次 `saveAll()` 或 JDBC 批量更新，將 500 次 IO 濃縮為 1 次。

### Q2：高併發下如何保證數據順序性 (Ordering & Parallelism)？
*   **挑戰**：多執行緒處理時，同一用戶的交易順序可能錯亂。
*   **應對**：引入 **Partition Key** 概念。
    *   若使用 Kafka，以「用戶地址」作為 Partition Key，確保同一用戶的事件永遠進入同一個分區並由固定執行緒處理。
    *   若是單機版，可實作 **Striped Lock (分段鎖)** 或對用戶地址取模分發給固定 Thread。

### Q3：如何處理深層鏈上重組 (Deep Chain Reorg)？
*   **挑戰**：已顯示「交易成功」的數據被回滾掉，導致系統產生假帳。
*   **應對**：
    *   **多階段確認機制**：資料初次寫入時標記為 `UNCONFIRMED`。
    *   **Reorg Monitor 執行緒**：持續比對已存區塊的 `blockHash`。若不一致則觸發同步回滾，並讓 `last_synced_block` 退回到分叉點重新同步。

### Q4：若資料來源 (RPC) 反應過慢，導致資料堆積 (Backlog) 怎麼辦？
*   **下法**：實作 **Backpressure (背壓機制)**。當內部隊列達到警戒水位時，暫停監聽器的抓取速度，避免 JVM 發生 OOM。同時，透過橫向擴展 (Scale-out) 更多的 Consumer 來加速消化堆積的數據。

---

## 💡 附錄：核心概念白話文解釋 (Analogies)

如果您對上述技術術語感到陌生，可以用以下生活場景來理解：

### 1. 批次寫入 (Batch Insert) — 送貨員比喻
*   **普通模式**：送貨員每件快遞都跑一趟客戶家（500 件跑 500 趟）。油錢（系統資源）會貴死。
*   **優化模式**：送貨員準備一台大貨車，裝滿 500 件一次送達。效率提升數十倍。

### 2. 資料順序性 (Sequence) — 收銀員比喻
*   **問題**：如果同時開 5 個視窗幫同一個客戶存錢與提錢，順序錯亂可能導致提款失敗。
*   **解法**：規定「同一個客戶的業務，必須排隊給同一個櫃檯處理」。這就是 **Partition Key** 的核心價值。

### 3. 背壓機制 (Backpressure) — 洗手台比喻
*   **問題**：水龍頭（區塊鏈數據）水開太大，排水孔（資料庫）排太慢，水會滿出來（系統崩潰）。
*   **解法**：洗手台裝感應器，水快滿時通知水龍頭「關小一點」，等排掉了再開大。這就是 **自動調節負載**。

### 4. 鏈上重組 (Reorg) — 平行時空比喻
*   **問題**：你原本以為這場比賽贏了（區塊 A），結果裁判（共識機制）突然說：「剛才那個不算，另一個時空的結果才是真的（區塊 B）。」
*   **對策**：隨時保持警覺，不斷驗證昨天的歷史是否與今天公認的歷史一致。若不一致則「倒帶」重來。

---
**歡迎對此專案提出任何改進建議或技術探討！**
