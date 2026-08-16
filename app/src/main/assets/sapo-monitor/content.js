(() => {
  const NATIVE_APP = "sapoMonitor";
  const INBOX_TOKEN = "SU5CT1g";
  let lastSent = "__never__";

  function isInboxUrl() {
    return (location.href || "").includes(`/messages/${INBOX_TOKEN}`) ||
           (location.hash || "").includes(`/messages/${INBOX_TOKEN}`);
  }

  function normalized(value) {
    return String(value || "").replace(/\s+/g, " ").trim().toLowerCase();
  }

  function hasUnreadSemantics(el) {
    if (!el) return false;

    const text = [
      el.className || "",
      el.id || "",
      el.getAttribute?.("aria-label") || "",
      el.getAttribute?.("title") || "",
      el.getAttribute?.("data-status") || "",
      el.getAttribute?.("data-state") || ""
    ].join(" ").toLowerCase();

    return (
      text.includes("unread") ||
      text.includes("não lido") ||
      text.includes("nao lido") ||
      text.includes("por ler") ||
      text.includes("new-message") ||
      text.includes("is-new")
    );
  }

  function looksLikeMessageRow(el) {
    if (!el || el === document.body || el === document.documentElement) return false;

    const text = normalized(el.textContent);
    if (text.length < 3 || text.length > 1200) return false;

    const cls = normalized(el.className);
    const role = normalized(el.getAttribute?.("role"));
    const data = normalized(
      `${el.getAttribute?.("data-message-id") || ""} ${el.getAttribute?.("data-id") || ""}`
    );

    return (
      cls.includes("message") ||
      cls.includes("mail") ||
      cls.includes("row") ||
      cls.includes("item") ||
      role === "row" ||
      role === "listitem" ||
      data.length > 0
    );
  }

  function nearestMessageRow(el) {
    let node = el;

    for (let depth = 0; depth < 7 && node; depth++, node = node.parentElement) {
      if (looksLikeMessageRow(node)) return node;
    }

    return null;
  }

  function countUnreadRows() {
    if (!isInboxUrl()) return null;

    const candidates = Array.from(
      document.querySelectorAll(
        '[class*="unread" i], [id*="unread" i], ' +
        '[aria-label*="não lido" i], [aria-label*="nao lido" i], ' +
        '[aria-label*="por ler" i], [aria-label*="unread" i], ' +
        '[title*="não lido" i], [title*="nao lido" i], ' +
        '[title*="por ler" i], [title*="unread" i], ' +
        '[data-status*="unread" i], [data-state*="unread" i]'
      )
    );

    const rows = new Set();

    for (const candidate of candidates) {
      if (!hasUnreadSemantics(candidate)) continue;

      const row = nearestMessageRow(candidate);
      if (row) rows.add(row);
    }

    for (const el of Array.from(document.querySelectorAll("body *"))) {
      if (hasUnreadSemantics(el) && looksLikeMessageRow(el)) {
        rows.add(el);
      }
    }

    if (rows.size > 0) return rows.size;

    const bodyText = normalized(document.body?.innerText);

    if (
      bodyText.includes("caixa de entrada") ||
      bodyText.includes("sem mensagens") ||
      bodyText.includes("nenhuma mensagem")
    ) {
      return 0;
    }

    return null;
  }

  function sendState(force = false) {
    if (!isInboxUrl()) return;

    const unread = countUnreadRows();
    if (unread === null) return;

    const payload = {
      type: "sapo_state",
      unread,
      folder: "INBOX",
      url: location.href,
      title: document.title || ""
    };

    const serialized = JSON.stringify(payload);

    if (!force && serialized === lastSent) return;
    lastSent = serialized;

    browser.runtime.sendNativeMessage(NATIVE_APP, payload).catch(() => {});
  }

  sendState(true);

  const observer = new MutationObserver(() => {
    clearTimeout(window.__centralEmailsUnreadTimer);
    window.__centralEmailsUnreadTimer = setTimeout(() => sendState(false), 500);
  });

  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true,
    attributeFilter: [
      "class",
      "id",
      "aria-label",
      "title",
      "data-status",
      "data-state"
    ]
  });

  window.addEventListener(
    "hashchange",
    () => setTimeout(() => sendState(true), 800)
  );

  setInterval(() => sendState(true), 10000);
})();
