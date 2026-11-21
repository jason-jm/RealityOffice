# Reality Office dla PICO Space Pro

[English](../../README.md) · [Deutsch](de.md) · [Español](es.md) · [Français](fr.md) · [Bahasa Indonesia](id.md) · [Italiano](it.md) · [Nederlands](nl.md) · **Polski** · [Português (Brasil)](pt-BR.md) · [Русский](ru.md) · [Türkçe](tr.md) · [Tiếng Việt](vi.md) · [हिन्दी](hi.md) · [ไทย](th.md) · [日本語](ja.md) · [한국어](ko.md) · [简体中文](zh-CN.md) · [繁體中文](zh-TW.md)

[Oficjalna strona](https://jason-jm.github.io/RealityOffice/guides/pl/) · [Film o aplikacji](https://jason-jm.github.io/RealityOffice/#film)

Reality Office to bezpłatny pakiet biurowy o otwartym kodzie źródłowym, stworzony dla **PICO Space Pro**. W [Pages](#pages-editor) możesz pisać i recenzować dokumenty, w [Sheets](#sheets-editor) analizować dane, a w [Slides](#slides-editor) przygotowywać i wyświetlać prezentacje. Wszystkie trzy edytory działają bez internetu. Podczas pracy w drugim oknie masz pod ręką dokument źródłowy, a te same pliki możesz później edytować na komputerze.

**[Pobierz wersję 1.3.4 (APK)](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4-build10/RealityOffice-v1.3.4-build10.apk)** · [Instrukcja instalacji (EN)](../INSTALL.md#english)

Wymagany jest system **PICO OS 6.2 lub nowszy** na urządzeniu arm64. Interfejs jest dostępny w 18 językach.

<p>
  <a href="../gallery/cover-en.jpg">
    <picture>
      <source media="(max-width: 600px)" srcset="../gallery/cover-en-mobile.jpg">
      <img src="../gallery/cover-en.jpg" alt="Reality Office dla PICO Space Pro. Pages, Sheets, Slides.">
    </picture>
  </a>
</p>

## Praca w kilku oknach

Otwórz drugi plik w nowym oknie. Możesz czytać wymagania podczas pisania oferty albo sprawdzać wartości w arkuszu, gdy poprawiasz wykres w prezentacji. Na ekranie głównym znajdziesz szablony raportów, budżetów i prezentacji. Możesz przeszukiwać ostatnio używane pliki i przypinać te, do których często wracasz. Każdy edytor ma wykaz skrótów do pracy z fizyczną klawiaturą.

[![Na ekranie głównym widać szablony i ostatnio otwierane pliki Saturday Print Club.](../gallery/posters/home.webp)](../gallery/en/home.webp)

<a name="pages-editor"></a>

## Pages · Układ dokumentu i recenzowanie

Nadaj rozdziałom raportu style nagłówków, aby przechodzić między nimi w konspekcie i wstawić spis treści z odsyłaczami oraz numerami stron. Do tekstu możesz dodawać ilustracje, tabele i przypisy dolne. W nagłówkach i stopkach umieścisz powtarzający się tytuł lub numer strony.

Po otrzymaniu poprawionej wersji możesz porównać oba dokumenty i obejrzeć różnice w osobnym pliku. Śledzenie zmian pokazuje autora każdej poprawki i pozwala osobno ją zaakceptować lub odrzucić. Do zaznaczonego fragmentu możesz dodać komentarz, odpowiedzieć w tym samym wątku, a po uzgodnieniu treści oznaczyć sprawę jako rozwiązaną.

[![Pages · Ułóż program spotkania (DOCX)](../gallery/posters/pages.webp)](../gallery/en/pages.webp)

<a name="sheets-editor"></a>

## Sheets · Formuły, wykresy i analiza danych

Twórz budżety, zamówienia i prognozy za pomocą formuł odwołujących się do innych arkuszy. `SUMIFS` sumuje wartości spełniające kilka warunków, a `XLOOKUP` pozwala pobrać odpowiednią cenę z cennika. Jeśli znasz oczekiwany wynik, funkcja szukania wyniku może wyznaczyć wartość wejściową, na przykład cenę jednostkową, przy której zamówienie zmieści się w budżecie.

Zablokuj wiersz nagłówków na czas przewijania, sortuj dane według kilku kolumn i odfiltruj potrzebne rekordy. Listy rozwijane ułatwiają spójne wprowadzanie danych. Formatowanie warunkowe wyróżni kwoty przekraczające limit. Wykres kolumnowy pozwala porównać kategorie, liniowy pokazuje zmiany w czasie, a kołowy przedstawia udziały w całości.

[![Sheets · Sprawdź koszt dodatkowej paczki papieru (€120 / €96 / €24)](../gallery/posters/sheets.webp)](../gallery/en/sheets.webp)

<a name="slides-editor"></a>

## Slides · Projektowanie slajdów, próby i pokaz w przestrzeni

Wybierz układ slajdu, wyrównaj tekst i zdjęcia za pomocą prowadnic oraz dodaj tabele lub wykresy. Możesz ustawić przejścia między slajdami i animować obiekty tak, aby pojawiały się w odpowiedniej chwili. Do każdego slajdu możesz dołączyć notatki i zarejestrować czas poświęcony mu podczas próby.

Pokaz działa w osobnym panelu, a w widoku prezentera widzisz swoje notatki, bieżący slajd i podgląd następnego. Po udzieleniu dostępu do danych przestrzennych gogle mogą umieścić panel na wykrytej ścianie. Jeśli ściany nie wykryto lub nie udzielisz zgody, slajdy pozostaną na panelu unoszącym się przed tobą.

[![Slides · Saturday Print Club · Przećwicz powitanie](../gallery/posters/slides.webp)](../gallery/en/slides.webp)

## Formaty plików i eksport do PDF

| Edytor | Odczyt i zapis | Dodatkowo odczytuje |
| --- | --- | --- |
| Pages | DOCX, ODT, TXT | DOC, RTF |
| Sheets | XLSX, ODS, CSV | XLS |
| Slides | PPTX, ODP | PPT |

W każdym edytorze możesz wyeksportować gotowy materiał do PDF. Zmiany w starszych plikach DOC, XLS i PPT zapisuje się w nowszym formacie obok oryginału. CSV nadaje się do wymiany wartości z pojedynczego arkusza. Aby zachować formuły, formatowanie i kilka arkuszy, wybierz XLSX lub ODS.

## Praca offline, prywatność i odzyskiwanie danych

Pliki możesz otwierać i zapisywać w goglach bez konta w aplikacji. Opublikowany pakiet APK nie żąda uprawnienia Androida do korzystania z internetu. Jeśli w systemowym oknie wyboru pliku wskażesz dysk w chmurze, wybrane pliki obsłuży dostawca tej usługi.

Automatyczny zapis utrwala zmiany, a mechanizm odzyskiwania przechowuje lokalną wersję roboczą na wypadek przerwy w pracy. Jeśli inna aplikacja zmieni otwarty plik, automatyczny zapis zostanie wstrzymany, aby umożliwić wybór wersji. Gdy Reality Office wykryje zawartość, której nie może edytować, na przykład makra, proponuje zapisanie kopii przed zastąpieniem oryginału.

## Wypróbuj edytory

Zrzuty ekranu przedstawiają angielską wersję 1.3.4 w emulatorze PICO OS 6.2. Pobierz widoczne na nich pliki, by zmienić harmonogram, przeliczyć budżet lub przećwiczyć pokaz trzech slajdów z notatkami prelegenta.

[Pobierz pliki przykładowe (ZIP)](https://raw.githubusercontent.com/jason-jm/RealityOffice/main/examples/print-club/Saturday-Print-Club.zip)

[Obsługiwane formaty (EN)](../FEATURES.md) · [Prywatność (EN)](../../DATA.md#english) · [Budowanie z kodu źródłowego (EN)](../BUILDING.md) · [Licencja MIT](../../LICENSE)
