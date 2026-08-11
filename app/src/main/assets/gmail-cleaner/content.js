(() => {
  const phrases = [
    "O Gmail funciona melhor na app",
    "Gmail funciona melhor na app",
    "Gmail works better in the app"
  ];

  function hidePromo() {
    const nodes = document.querySelectorAll("div, section, aside");

    for (const el of nodes) {
      const text = (el.innerText || el.textContent || "").trim();
      if (!phrases.some(p => text.includes(p))) continue;

      const rect = el.getBoundingClientRect();
      if (rect.top >= 700 || rect.width <= window.innerWidth * 0.65) continue;

      let target = el;

      for (let i = 0; i < 4 && target.parentElement; i++) {
        const parent = target.parentElement;
        const r = parent.getBoundingClientRect();

        if (
          r.top < 700 &&
          r.width > window.innerWidth * 0.80 &&
          r.height > 70 &&
          r.height < 420
        ) {
          target = parent;
        } else {
          break;
        }
      }

      target.style.setProperty("display", "none", "important");
    }
  }

  hidePromo();

  const observer = new MutationObserver(hidePromo);
  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true
  });

  setInterval(hidePromo, 2500);
})();
