# PICO Space Pro のための Reality Office

[English](../../README.md) · [Deutsch](de.md) · [Español](es.md) · [Français](fr.md) · [Bahasa Indonesia](id.md) · [Italiano](it.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Русский](ru.md) · [Türkçe](tr.md) · [Tiếng Việt](vi.md) · [हिन्दी](hi.md) · [ไทย](th.md) · **日本語** · [한국어](ko.md) · [简体中文](zh-CN.md) · [繁體中文](zh-TW.md)

Reality Office は **PICO Space Pro** 向けの無料で使えるオープンソースのオフィスソフトです。[Pages](#pages-editor) で文書を執筆・校閲し、[Sheets](#sheets-editor) でデータを集計し、[Slides](#slides-editor) で資料を作って発表できます。3 つのエディターはオフラインで動作します。参照資料を開いたまま隣のウィンドウで作業し、編集可能なファイルをパソコンに持ち帰れます。

**[1.3.4 をダウンロード（APK）](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4/RealityOffice-v1.3.4.apk)** · [インストールガイド (EN)](../INSTALL.md#english)

arm64 の **PICO OS 6.2 以降**に対応。アプリの表示言語は 18 種類から選べます。

[![PICO Space Pro のための Reality Office. Pages, Sheets, Slides.](../gallery/cover-en.jpg)](../gallery/cover-en.jpg)

## 複数のウィンドウで作業する

新しいウィンドウでファイルを開くと、2 つの資料を並べられます。要件を読みながら提案書を書いたり、表計算の数値を確かめながらスライドのグラフを直したりできます。ホーム画面には報告書、予算表、プレゼンテーションのテンプレートがあり、最近使ったファイルの検索やピン留めもできます。各エディターには物理キーボード用のショートカット一覧があります。

<details>
<summary>ホーム画面を見る</summary>

[![ホーム画面を見る · Pages, Sheets, Slides](../gallery/en/home.webp)](../gallery/en/home.webp)

</details>

<a name="pages-editor"></a>

## Pages · 文書のレイアウトと校閲

見出しスタイルを適用すると、アウトラインから目的の章へ移動でき、ページ番号付きのリンクを備えた目次も挿入できます。本文に画像、表、脚注を加え、ヘッダーやフッターに文書名やページ番号を載せられます。

修正稿を受け取ったら、文書の比較機能で差分を別の文書に表示できます。変更履歴では編集者を確認し、修正を一件ずつ承諾または却下できます。文章を選んでコメントを付け、同じスレッドに返信し、表現が決まったら解決済みにできます。

[![Pages · 当日の進行表を整える (DOCX)](../gallery/en/pages.webp)](../gallery/en/pages.webp)

<a name="sheets-editor"></a>

## Sheets · 関数、グラフ、データ分析

シートをまたぐ数式で予算や発注を管理できます。`SUMIFS` で条件に合う行だけを合計し、`XLOOKUP` で価格表から単価を取得できます。目標値が決まっているなら、ゴールシークで予算に収まる単価などの入力値を逆算できます。

見出し行を固定してスクロールし、複数の列で並べ替え、フィルターでデータを絞り込めます。ドロップダウンリストで入力を統一し、条件付き書式で予算を超えた金額を強調できます。項目の比較には縦棒グラフ、推移には折れ線グラフ、構成比には円グラフが使えます。

[![Sheets · 用紙を一包み追加する (€120 / €96 / €24)](../gallery/en/sheets.webp)](../gallery/en/sheets.webp)

<a name="slides-editor"></a>

## Slides · スライドの作成、リハーサル、空間での上映

レイアウトを選び、ガイドに沿って文字や写真を配置し、表やグラフを挿入できます。画面切り替えやオブジェクトのアニメーションを設定し、話す順番に合わせて内容を表示できます。スライドごとに発表者ノートを記入でき、リハーサルでは各スライドにかかった時間も記録できます。

発表中はスライドが別のパネルに映り、発表者用の画面には現在のスライド、次のスライド、ノートが並びます。空間データへのアクセスを許可すると、ヘッドセットが検出した壁にパネルを配置できます。壁が見つからない場合や許可しない場合は、目の前に浮かぶパネルで上映できます。

[![Slides · Saturday Print Club · 開会の挨拶を練習する](../gallery/en/slides.webp)](../gallery/en/slides.webp)

## ファイル形式と PDF 書き出し

| エディター | 読み書きできる形式 | 読み込みにも対応 |
| --- | --- | --- |
| Pages | DOCX, ODT, TXT | DOC, RTF |
| Sheets | XLSX, ODS, CSV | XLS |
| Slides | PPTX, ODP | PPT |

完成した文書、表、スライドはいずれも PDF に書き出せます。旧形式の DOC、XLS、PPT を編集すると、新しい形式のファイルが元のファイルと同じ場所に保存されます。1 枚のシートの値を受け渡すには CSV、数式、書式、複数のシートを残すには XLSX または ODS を選べます。

## オフラインでの保存、プライバシー、復元

アプリのアカウントなしで、ヘッドセット上のファイルを開いて保存できます。正式版 APK は Android のインターネット権限を要求しません。システムのファイル選択画面からクラウドストレージを選んだ場合は、そのサービスが選択したファイルを扱います。

自動保存は変更をファイルに書き込み、自動回復用の下書きは端末内に残ります。作業中のファイルが別のアプリで変更された場合は自動保存を一時停止し、残す版を選べます。マクロなど編集できない要素を検出したときは、元のファイルを置き換える前にコピーとして保存できます。

## サンプルで試す

画像は PICO OS 6.2 エミュレーターで動作する正式版 1.3.4 の英語画面です。画面と同じファイルをダウンロードして、進行表の編集、予算表の再計算、発表者ノート付きの 3 枚のスライドのリハーサルを試せます。

[サンプルファイル（ZIP）](https://raw.githubusercontent.com/jason-jm/RealityOffice/main/examples/print-club/Saturday-Print-Club.zip)

[対応形式 (EN)](../FEATURES.md) · [プライバシー (EN)](../../DATA.md#english) · [ソースからビルド (EN)](../BUILDING.md) · [MIT ライセンス](../../LICENSE)
