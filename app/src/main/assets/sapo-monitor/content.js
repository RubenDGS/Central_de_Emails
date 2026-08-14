(() => {
  const NATIVE_APP = "sapoMonitor";
  let lastPayload = "";

  function asInt(value) {
    const text = String(value == null ? "" : value).trim();
    const match = text.match(/(?:^|\D)(\d{1,5})(?:\D|$)/);
    return match ? parseInt(match[1], 10) : null;
  }

  function fromTitle() {
    const title = document.title || "";
    const match = title.match(/^\s*\((\d{1,5})\)/);
    return match ? parseInt(match[1], 10) : null;
  }

  function elementLooksLikeInbox(el) {
    if (!el) return false;
    const text = (el.textContent || "").replace(/\s+/g, " ").trim().toLowerCase();
    const aria = (el.getAttribute?.("aria-label") || "").trim().toLowerCase();
    const title = (el.getAttribute?.("title") || "").trim().toLowerCase();
    const joined = `${text} ${aria} ${title}`;

    return (
      joined.includes("caixa de entrada") ||
      joined.includes("recebido") ||
      joined.includes("recebidos") ||
      joined.includes("inbox")
    );
  }

  function candidateNumber(el) {
    if (!el) return null;

    const classText = String(el.className || "").toLowerCase();
    const aria = String(el.getAttribute?.("aria-label") || "").toLowerCase();
    const title = String(el.getAttribute?.("title") || "").toLowerCase();
    const id = String(el.id || "").toLowerCase();
    const semantic = `${classText} ${aria} ${title} ${id}`;

    if (
      semantic.includes("unread") ||
      semantic.includes("não lido") ||
      semantic.includes("nao lido") ||
      semantic.includes("badge") ||
      semantic.includes("counter") ||
      semantic.includes("count")
    ) {
      const n = asInt(`${aria} ${title} ${el.textContent || ""}`);
      if (n !== null) return n;
    }

    const raw = (el.textContent || "").trim();
    return /^\d{1,5}$/.test(raw) ? parseInt(raw, 10) : null;
  }

  function fromInboxArea() {
    const elements = Array.from(
      document.querySelectorAll(
        '[aria-label], [title], nav *, aside *, [role="navigation"] *, body *'
      )
    );

    const inboxes = elements.filter(elementLooksLikeInbox).slice(0, 12);

    for (const inbox of inboxes) {
      const directText = (inbox.textContent || "").replace(/\s+/g, " ").trim();
      const directMatch = directText.match(
        /(?:caixa de entrada|recebidos?|inbox)\D{0,20}(\d{1,5})/i
      );
      if (directMatch) return parseInt(directMatch[1], 10);

      let node = inbox;
      for (let depth = 0; depth < 5 && node; depth++, node = node.parentElement) {
        const candidates = [
          node.previousElementSibling,
          node.nextElementSibling,
          ...Array.from(node.children || []),
          ...Array.from(node.querySelectorAll?.("*") || []).slice(0, 120)
        ];

        for (const candidate of candidates) {
          const n = candidateNumber(candidate);
          if (n !== null) return n;
        }
      }
    }
    return null;
  }

  function fromUnreadSemantics() {
    const selectors = [
      '[aria-label*="não lido" i]',
      '[aria-label*="nao lido" i]',
      '[aria-label*="unread" i]',
      '[title*="não lido" i]',
      '[title*="nao lido" i]',
      '[title*="unread" i]',
      '[class*="unread" i]',
      '[class*="badge" i]',
      '[class*="counter" i]'
    ];

    for (const selector of selectors) {
      for (const el of Array.from(document.querySelectorAll(selector)).slice(0, 100)) {
        const n = candidateNumber(el);
        if (n !== null) return n;
      }
    }
    return null;
  }

  function detectUnread() {
    const title = fromTitle();
    if (title !== null) return title;

    const inbox = fromInboxArea();
    if (inbox !== null) return inbox;

    const semantic = fromUnreadSemantics();
    if (semantic !== null) return semantic;

    return null;
  }

  function sendState(force = false) {
    const unread = detectUnread();
    const payload = {
      type: "sapo_state",
      unread: unread,
      url: location.href,
      title: document.title || "",
      ready: document.readyState
    };

    const serialized = JSON.stringify(payload);
    if (!force && serialized === lastPayload) return;
    lastPayload = serialized;

    browser.runtime.sendNativeMessage(NATIVE_APP, payload).catch(() => {});
  }

  sendState(true);

  const observer = new MutationObserver(() => {
    clearTimeout(window.__centralEmailsTimer);
    window.__centralEmailsTimer = setTimeout(() => sendState(false), 350);
  });

  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true,
    attributeFilter: ["class", "aria-label", "title"]
  });

  window.addEventListener("hashchange", () => setTimeout(() => sendState(true), 500));
  window.addEventListener("focus", () => setTimeout(() => sendState(true), 300));
  setInterval(() => sendState(true), 10000);
})();
