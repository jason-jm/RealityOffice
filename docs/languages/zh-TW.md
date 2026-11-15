# 專為 PICO Space Pro 打造的 Reality Office

[English](../../README.md) · [Deutsch](de.md) · [Español](es.md) · [Français](fr.md) · [Bahasa Indonesia](id.md) · [Italiano](it.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Русский](ru.md) · [Türkçe](tr.md) · [Tiếng Việt](vi.md) · [हिन्दी](hi.md) · [ไทย](th.md) · [日本語](ja.md) · [한국어](ko.md) · [简体中文](zh-CN.md) · **繁體中文**

Reality Office 是專為 **PICO Space Pro** 打造的免費開源辦公軟體。用 [Pages](#pages-editor) 撰寫、排版和審閱文件，用 [Sheets](#sheets-editor) 計算與分析資料，再用 [Slides](#slides-editor) 製作及播放簡報。三個編輯器都能離線使用。你可以把參考資料放在正在編輯的檔案旁，完成後再把檔案帶回電腦接著編輯。

**[下載 1.3.4 安裝檔（APK）](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4/RealityOffice-v1.3.4.apk)** · [安裝指南（简体中文）](../INSTALL.md#简体中文)

適用於執行 **PICO OS 6.2 或更新版本**的 arm64 裝置。應用程式介面提供 18 種語言。

<p>
  <a href="../gallery/cover-en.jpg">
    <picture>
      <source media="(max-width: 600px)" srcset="../gallery/cover-en-mobile.jpg">
      <img src="../gallery/cover-en.jpg" alt="專為 PICO Space Pro 打造的 Reality Office。右側是 Slides 中的 Saturday Print Club 歡迎簡報，左下是 Pages 活動流程表與 Sheets 材料預算。">
    </picture>
  </a>
</p>

## 在空間中並排使用視窗

選擇在新視窗開啟檔案，就能同時看見兩份資料。寫提案時把需求文件放在旁邊，更新簡報圖表時則可以對照試算表。首頁提供報告、預算和簡報範本，也能搜尋最近使用的檔案，或將常用文件釘選到清單。搭配實體鍵盤時，可在各編輯器查看適用的快速鍵。

[![首頁列出檔案範本和最近開啟的 Saturday Print Club 文件。](../gallery/posters/home.webp)](../gallery/en/home.webp)

<a name="pages-editor"></a>

## Pages · 長篇文件的排版與審閱

使用標題樣式劃分章節，透過大綱跳到要修改的段落，並建立標示頁碼、可點選跳轉的目錄。你可以在內文插入圖片、表格和註腳，利用頁首與頁尾在每一頁顯示報告名稱或頁碼。

收到修改後的草稿，使用比較功能即可在另一份文件中查看兩版差異。開啟追蹤修訂後，可以查看每一處變更的作者，逐項接受或拒絕。針對某段文字留下註解，在同一則討論中回覆，定稿後再標記為已解決。

[![Pages · 安排開場流程 (DOCX)](../gallery/posters/pages.webp)](../gallery/en/pages.webp)

<a name="sheets-editor"></a>

## Sheets · 公式、圖表與資料分析

用跨工作表公式編列預算、整理訂單或估算未來支出。`SUMIFS` 可加總符合多個條件的資料，`XLOOKUP` 可從價目表查出相符的單價。若已知道目標金額，還可以用目標搜尋反推一個輸入值，例如單價要降到多少才符合預算。

瀏覽長表時可以凍結標題列、依多欄排序，或篩選出所需的記錄。下拉式清單有助統一輸入值，設定格式化條件則能標出超支的數字。要說明資料時，可用直條圖比較類別、用折線圖觀察趨勢，或用圓餅圖呈現比例。

[![Sheets · 多買一包紙要花多少 (€120 / €96 / €24)](../gallery/posters/sheets.webp)](../gallery/en/sheets.webp)

<a name="slides-editor"></a>

## Slides · 簡報設計、排練與空間放映

從投影片版面配置開始，用對齊輔助線安排文字和圖片，加入表格與圖表。設定換頁轉場，或用物件動畫配合講解逐步揭示內容。每張投影片都可加入講者備忘稿，並在排練時記錄停留時間。

播放時，簡報會顯示在獨立面板，講者控制台則顯示目前投影片、下一張預覽和備忘稿。允許空間資料存取後，可以把放映面板貼到頭戴裝置偵測到的牆面。若沒有偵測到牆面，或你選擇不授權，投影片會顯示在面前的浮動面板上。

[![Slides · Saturday Print Club · 排練歡迎致詞](../gallery/posters/slides.webp)](../gallery/en/slides.webp)

## 檔案格式與 PDF 匯出

| 編輯器 | 可開啟及儲存 | 另可讀取 |
| --- | --- | --- |
| Pages | DOCX, ODT, TXT | DOC, RTF |
| Sheets | XLSX, ODS, CSV | XLS |
| Slides | PPTX, ODP | PPT |

三個編輯器都能匯出 PDF，方便將定稿交給他人閱讀。舊版 DOC、XLS、PPT 檔案編輯後會以新格式另存於原檔旁。要交換單張工作表的數值可選用 CSV。若要保留公式、格式及多張工作表，請選 XLSX 或 ODS。

## 離線編輯、隱私與草稿復原

不必註冊應用程式帳號，就能在頭戴裝置上開啟及儲存檔案。正式版安裝檔沒有要求 Android 網際網路權限。若透過系統檔案選擇器開啟雲端硬碟，選取的檔案會由該服務處理。

自動儲存會將變更寫回檔案，自動復原則保留本機草稿，方便工作意外中斷後還原。若其他應用程式更動了正在編輯的檔案，自動儲存會暫停，讓你決定要保留的版本。檔案包含巨集等無法編輯的內容時，Reality Office 會先提供儲存副本的選項。

## 下載範例動手試

圖片擷取自正式版 1.3.4 在 PICO OS 6.2 模擬器中的英文介面。下載圖中使用的三份檔案，即可編排活動流程表、調整會自動重算的預算，或參照講者備忘稿排練三張投影片。

[下載範例檔案（ZIP）](https://raw.githubusercontent.com/jason-jm/RealityOffice/main/examples/print-club/Saturday-Print-Club.zip)

[支援的格式 (EN)](../FEATURES.md) · [隱私權說明（简体中文）](../../DATA.md#简体中文) · [從原始碼建置 (EN)](../BUILDING.md) · [MIT 授權條款](../../LICENSE)
