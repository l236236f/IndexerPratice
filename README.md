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

---
**本專案展示了從基礎監聽模型到工業級資料流水線的演進過程，適合作為區塊鏈後端架構的學習範本。**
