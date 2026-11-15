# PICO Space Pro를 위한 Reality Office

[English](../../README.md) · [Deutsch](de.md) · [Español](es.md) · [Français](fr.md) · [Bahasa Indonesia](id.md) · [Italiano](it.md) · [Nederlands](nl.md) · [Polski](pl.md) · [Português (Brasil)](pt-BR.md) · [Русский](ru.md) · [Türkçe](tr.md) · [Tiếng Việt](vi.md) · [हिन्दी](hi.md) · [ไทย](th.md) · [日本語](ja.md) · **한국어** · [简体中文](zh-CN.md) · [繁體中文](zh-TW.md)

Reality Office는 **PICO Space Pro**를 위해 만든 무료 오픈 소스 오피스 앱입니다. [Pages](#pages-editor)에서 문서를 작성하고 검토하고, [Sheets](#sheets-editor)에서 데이터를 분석하고, [Slides](#slides-editor)에서 발표 자료를 만들어 상영할 수 있습니다. 세 편집기 모두 오프라인으로 작동합니다. 참고 문서를 펼쳐 둔 채 옆 창에서 작업하고, 같은 파일을 컴퓨터로 옮겨 이어서 편집할 수 있습니다.

**[1.3.4 다운로드(APK)](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4/RealityOffice-v1.3.4.apk)** · [설치 안내 (EN)](../INSTALL.md#english)

arm64 기기에서 **PICO OS 6.2 이상**이 필요합니다. 앱은 18개 언어를 지원합니다.

<p>
  <a href="../gallery/cover-en.jpg">
    <picture>
      <source media="(max-width: 600px)" srcset="../gallery/cover-en-mobile.jpg">
      <img src="../gallery/cover-en.jpg" alt="PICO Space Pro를 위한 Reality Office. Pages, Sheets, Slides.">
    </picture>
  </a>
</p>

## 여러 창을 나란히 놓고 작업하기

파일을 새 창에서 열면 두 문서를 동시에 볼 수 있습니다. 요구 사항을 읽으면서 제안서를 쓰거나, 스프레드시트와 대조하면서 발표 자료의 수치를 고쳐 보세요. 홈 화면에서 보고서, 예산표, 프레젠테이션 서식을 선택하고, 최근 파일을 검색하거나 자주 쓰는 문서를 고정할 수 있습니다. 실제 키보드를 연결했다면 편집기별 단축키 안내도 이용할 수 있습니다.

[![홈 화면에 템플릿과 최근에 연 Saturday Print Club 파일이 표시됩니다.](../gallery/posters/home.webp)](../gallery/en/home.webp)

<a name="pages-editor"></a>

## Pages · 문서 편집과 검토

제목 스타일로 보고서의 장과 절을 나누면 개요에서 원하는 부분으로 이동하고, 페이지 번호가 있는 목차를 삽입해 해당 제목으로 바로 갈 수 있습니다. 본문에 그림, 표, 각주를 넣고 머리글이나 바닥글에 문서 제목과 페이지 번호를 표시할 수 있습니다.

수정본을 받으면 문서 비교 기능으로 두 버전의 차이를 별도 문서에서 확인하세요. 변경 내용 추적을 켜면 누가 무엇을 고쳤는지 살피고 수정 사항을 하나씩 적용하거나 거부할 수 있습니다. 구절을 선택해 메모를 달고 답글을 주고받은 뒤 논의가 끝나면 해결됨으로 표시할 수 있습니다.

[![Pages · 공방 일정을 정리하기 (DOCX)](../gallery/posters/pages.webp)](../gallery/en/pages.webp)

<a name="sheets-editor"></a>

## Sheets · 수식과 차트로 데이터 분석하기

여러 시트를 참조하는 수식으로 예산이나 주문 내역을 관리할 수 있습니다. `SUMIFS`로 조건에 맞는 행만 더하거나 `XLOOKUP`으로 가격표에서 단가를 불러오세요. 목표값 찾기 기능을 사용하면 예산에 맞추려면 단가를 얼마로 바꿔야 하는지 역으로 계산할 수 있습니다.

제목 행을 고정한 채 아래로 스크롤하고, 여러 열을 기준으로 정렬하거나 원하는 기록만 필터링할 수 있습니다. 드롭다운 목록으로 입력을 통일하고, 조건부 서식으로 한도를 넘긴 금액을 강조하세요. 세로 막대형, 꺾은선형, 원형 차트로 항목별 차이, 추이, 구성 비율을 나타낼 수 있습니다.

[![Sheets · 종이를 한 묶음 더 주문하기 (€120 / €96 / €24)](../gallery/posters/sheets.webp)](../gallery/en/sheets.webp)

<a name="slides-editor"></a>

## Slides · 슬라이드 디자인과 발표 연습

레이아웃을 고르고 맞춤 안내선으로 텍스트와 이미지를 배치한 다음 표나 차트를 추가하세요. 슬라이드 사이에 전환 효과를 넣고, 설명 순서에 맞춰 개체가 나타나도록 애니메이션을 설정할 수 있습니다. 슬라이드마다 발표자 노트를 작성하고 연습 중 각 장에 쓴 시간을 기록할 수 있습니다.

발표 중 슬라이드는 별도 패널에 표시되고 발표자 화면에는 현재 슬라이드, 다음 슬라이드, 노트가 함께 보입니다. 공간 데이터 접근을 허용하면 헤드셋이 감지한 벽에 발표 패널을 배치할 수 있습니다. 벽을 찾지 못하거나 권한을 허용하지 않으면 눈앞의 플로팅 패널에서 발표를 이어 갑니다.

[![Slides · Saturday Print Club · 환영 인사 연습하기](../gallery/posters/slides.webp)](../gallery/en/slides.webp)

## 지원 파일 형식과 PDF 내보내기

| 편집기 | 열기 및 저장 | 추가로 열 수 있는 형식 |
| --- | --- | --- |
| Pages | DOCX, ODT, TXT | DOC, RTF |
| Sheets | XLSX, ODS, CSV | XLS |
| Slides | PPTX, ODP | PPT |

세 편집기 모두 완성본을 PDF로 내보낼 수 있습니다. 이전 형식인 DOC, XLS, PPT를 편집하면 원본과 같은 위치에 새 형식으로 저장합니다. 한 시트의 값만 전달할 때는 CSV, 수식과 서식, 여러 시트를 함께 보관할 때는 XLSX나 ODS가 적합합니다.

## 오프라인 편집과 파일 복구

앱 계정 없이 헤드셋에서 파일을 열고 저장할 수 있습니다. 정식 APK는 Android 인터넷 권한을 요청하지 않습니다. 시스템 파일 선택기에서 클라우드 드라이브를 직접 선택하면 해당 서비스가 선택한 파일을 처리합니다.

자동 저장은 편집한 내용을 파일에 기록하고, 자동 복구는 작업이 중단됐을 때 되살릴 수 있도록 기기에 초안을 보관합니다. 다른 앱이 열린 파일을 변경하면 자동 저장을 일시 중지해 어느 버전을 보관할지 선택할 수 있습니다. 매크로처럼 편집할 수 없는 요소가 발견되면 원본을 덮어쓰기 전에 복사본으로 저장할 수 있습니다.

## 예제로 직접 사용해 보기

사진은 PICO OS 6.2 에뮬레이터에서 실행한 정식 버전 1.3.4의 영어 화면입니다. 화면에 나온 진행표와 수식이 담긴 예산표, 발표자 노트가 있는 세 장짜리 프레젠테이션을 내려받아 직접 편집해 보세요.

[예제 파일 다운로드(ZIP)](https://raw.githubusercontent.com/jason-jm/RealityOffice/main/examples/print-club/Saturday-Print-Club.zip)

[지원 형식 (EN)](../FEATURES.md) · [개인정보 처리방침 (EN)](../../DATA.md#english) · [소스에서 빌드 (EN)](../BUILDING.md) · [MIT 라이선스](../../LICENSE)
