const languageMenu = document.querySelector(".switch");

if (languageMenu) {
  document.addEventListener("click", (event) => {
    if (!languageMenu.contains(event.target) || event.target.closest("a")) {
      languageMenu.open = false;
    }
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && languageMenu.open) {
      languageMenu.open = false;
      languageMenu.querySelector("summary").focus();
    }
  });
}

const film = document.querySelector("#film video");
const watchLink = document.querySelector("[data-watch]");

if (film && watchLink) {
  watchLink.addEventListener("click", () => {
    if (film.ended) film.currentTime = 0;
    // Native controls remain available when playback requires a second gesture.
    film.play().catch(() => {});
  });
}
