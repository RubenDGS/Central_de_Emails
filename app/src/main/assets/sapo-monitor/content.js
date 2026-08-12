(() => {
  const nativeApp = "sapoMonitor";
  let lastSent = null;

  function numericText(el) {
    if (!el) return null;
    const text = (el.textContent || "").trim();
    return /^\d{1,5}$/.test(text) ? parseInt(text, 10) : null;
  }

  function unreadFromTitle() {
    const title = document.title || "";
    const m = title.match(/^\s*\((\d+)\)/);
    return m ? parseInt(m[1], 10) : null;
  }

  function unreadNearInbox() {
    const all = Array.from(document.querySelectorAll("body *"));

    const inbox = all.find(el => {
      const t = (el.textContent || "").trim().toLowerCase();
      return t === "caixa de entrada" || t === "recebido" || t === "inbox";
    });

    if (!inbox) return null;

    let node = inbox;
    for (let depth = 0; depth < 5 && node; depth++, node = node.parentElement) {
      const candidates = Array.from(node.querySelectorAll("*"));

      // Primeiro, elementos que parecem badges/contadores.
      for (const el of candidates) {
        const cls = `${el.className || ""} ${el.getAttribute("aria-label") || ""} ${el.title || ""}`.toLowerCase();
        if (
          cls.includes("unread") ||
          cls.includes("badge") ||
          cls.includes("count") ||
          cls.includes("não lido") ||
          cls.includes("nao lido")
        ) {
          const n = numericText(el);
          if (n !== null) return n;
        }
      }

      // Depois, um número curto muito próximo do texto da Caixa de Entrada.
      for (const el of candidates.slice(0, 80)) {
        const n = numericText(el);
        if (n !== null) return n;
      }
    }

    return null;
  }

  function unreadFromAria() {
    const selectors = [
      '[aria-label*="não lido" i]',
      '[aria-label*="nao lido" i]',
      '[aria-label*="unread" i]',
      '[title*="não lido" i]',
      '[title*="nao lido" i]',
      '[title*="unread" i]'
    ];

    for (const selector of selectors) {
      const elements = Array.from(document.querySelectorAll(selector));

      for (const el of elements) {
        const aria = `${el.getAttribute("aria-label") || ""} ${el.title || ""}`;
        const m = aria.match(/(\d{1,5})/);
        if (m) return parseInt(m[1], 10);

        const n = numericText(el);
        if (n !== null) return n;
      }
    }

    return null;
  }

  function detectUnread() {
    const values = [
      unreadFromTitle(),
      unreadNearInbox(),
      unreadFromAria()
    ].filter(v => Number.isInteger(v) && v >= 0);

    if (!values.length) return null;
    return values[0];
  }

  function sendState() {
    const unread = detectUnread();
    if (unread === null || unread === lastSent) return;

    lastSent = unread;

    browser.runtime.sendNativeMessage(nativeApp, {
      type: "sapo_state",
      unread: unread,
      url: location.href,
      title: document.title || ""
    }).catch(() => {});
  }

  sendState();

  const observer = new MutationObserver(() => {
    clearTimeout(window.__centralEmailsTimer);
    window.__centralEmailsTimer = setTimeout(sendState, 400);
  });

  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true
  });

  setInterval(sendState, 5000);
})();
