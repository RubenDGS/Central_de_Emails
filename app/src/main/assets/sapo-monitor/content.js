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

  function looksLikeInbox(el) {
    const text = (el?.textContent || "").replace(/\s+/g, " ").trim().toLowerCase();
    const aria = (el?.getAttribute?.("aria-label") || "").toLowerCase();
    const title = (el?.getAttribute?.("title") || "").toLowerCase();
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

    const semantic = [
      el.className || "",
      el.getAttribute?.("aria-label") || "",
      el.getAttribute?.("title") || "",
      el.id || ""
    ].join(" ").toLowerCase();

    if (
      semantic.includes("unread") ||
      semantic.includes("não lido") ||
      semantic.includes("nao lido") ||
      semantic.includes("badge") ||
      semantic.includes("counter") ||
      semantic.includes("count")
    ) {
      const n = asInt(`${semantic} ${el.textContent || ""}`);
      if (n !== null) return n;
    }

    const raw = (el.textContent || "").trim();
    return /^\d{1,5}$/.test(raw) ? parseInt(raw, 10) : null;
  }

  function fromInboxArea() {
    const elements = Array.from(document.querySelectorAll("body *"));
    const inboxes = elements.filter(looksLikeInbox).slice(0, 12);

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

  function detectUnread() {
    const title = fromTitle();
    if (title !== null) return title;

    const inbox = fromInboxArea();
    if (inbox !== null) return inbox;

    return null;
  }

  function sendState(force = false) {
    const unread = detectUnread();

    const payload = {
      type: "sapo_state",
      unread,
      url: location.href,
      title: document.title || ""
    };

    const serialized = JSON.stringify(payload);
    if (!force && serialized === lastPayload) return;
    lastPayload = serialized;

    browser.runtime.sendNativeMessage(NATIVE_APP, payload).catch(() => {});
  }

  sendState(true);

  const observer = new MutationObserver(() => {
    clearTimeout(window.__centralEmailsTimer);
    window.__centralEmailsTimer = setTimeout(() => sendState(false), 400);
  });

  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true
  });

  window.addEventListener("hashchange", () => setTimeout(() => sendState(true), 500));
  window.addEventListener("focus", () => setTimeout(() => sendState(true), 300));
  setInterval(() => sendState(true), 10000);
})();
