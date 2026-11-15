# Reality Office on PICO Space Pro

Home, Pages, Sheets and Slides are shown with the same Saturday Print Club files. The four feature posters open their original screenshots when you click them. [Choose a language](../languages/README.md) · [Download the editable examples](../../examples/print-club/)

<p>
  <a href="cover-en.jpg">
    <picture>
      <source media="(max-width: 600px)" srcset="cover-en-mobile.jpg">
      <img src="cover-en.jpg" alt="Reality Office for PICO Space Pro with the Saturday Print Club welcome deck in Slides, the studio schedule in Pages and the materials budget in Sheets.">
    </picture>
  </a>
</p>

[![Home with templates and recently opened documents.](posters/home.webp)](en/home.webp)

[![Pages with the studio run sheet and opening schedule.](posters/pages.webp)](en/pages.webp)

[![Sheets with supply quantities, prices and the remaining budget.](posters/sheets.webp)](en/sheets.webp)

[![Slides with the Saturday Print Club welcome deck and Hokusai’s Great Wave.](posters/slides.webp)](en/slides.webp)

## How the posters were made

The four English screenshots come from the signed 1.3.4 release (build 9) running in the PICO OS 6.2.0 emulator. The room around each window is part of the simulator. Each capture was cropped from a square 3200 × 3200 view and scaled proportionally to 1500 pixels wide. The feature posters place these images at their full 1500-pixel width. All 18 product guides share the same artwork. [PICO’s device and emulator FAQ](https://developer.picoxr.com/document/discover/faqs-for-project-swan-and-pico-os-6/)

Open the [poster layout](cover-layout.html) in Chromium from a local checkout. For the cover, add `#cover` to the address, set the viewport to 1800 pixels wide at 1× and capture the 1800 × 900 element as JPEG. The mobile image uses the same framing at 900 × 450 and links to the full-size cover. For a feature poster, select `#home`, `#pages`, `#sheets` or `#slides` and capture at 1640 pixels wide at 1×. Place the original screenshot at its native size, 70 pixels from the left and 173 from the top, then export as lossless WebP. The generated paper texture is used on the feature posters. [Background texture](print-texture.webp)

The woodblock print pictured inside Slides is in the public domain. [The Metropolitan Museum of Art, object 45434](https://www.metmuseum.org/art/collection/search/45434)
