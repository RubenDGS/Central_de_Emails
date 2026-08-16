(() => {
  const NATIVE_APP = "sapoMonitor";
  const INBOX_TOKEN = "SU5CT1g";
  let lastSent = null;

  const norm = v =>
    String(v || "")
      .replace(/\s+/g, " ")
      .trim()
      .toLowerCase();

  function isInbox() {
    return `${location.href} ${location.hash}`
      .includes(`/messages/${INBOX_TOKEN}`);
  }

  function unreadMarked(el) {
    if (!el) return false;

    const s = norm([
      el.className || "",
      el.id || "",
      el.getAttribute?.("aria-label") || "",
      el.getAttribute?.("title") || "",
      el.getAttribute?.("data-status") || "",
      el.getAttribute?.("data-state") || ""
    ].join(" "));

    return (
      s.includes("unread") ||
      s.includes("não lido") ||
      s.includes("nao lido") ||
      s.includes("por ler") ||
      s.includes("is-new") ||
      s.includes("new-message")
    );
  }

  function plausibleRow(el) {
    if (
      !el ||
      el === document.body ||
      el === document.documentElement
    ) {
      return false;
    }

    const content = norm(el.textContent);

    if (
      content.length < 3 ||
      content.length > 1600
    ) {
      return false;
    }

    const s = norm([
      el.className || "",
      el.id || "",
      el.getAttribute?.("role") || "",
      el.getAttribute?.("data-message-id") || "",
      el.getAttribute?.("data-id") || ""
    ].join(" "));

    return (
      s.includes("message") ||
      s.includes("mail") ||
      s.includes("row") ||
      s.includes("listitem") ||
      s.includes("list-item") ||
      el.getAttribute?.("role") === "row" ||
      el.getAttribute?.("role") === "listitem"
    );
  }

  function nearestRow(el) {
    let node = el;

    for (
      let i = 0;
      i < 8 && node;
      i++, node = node.parentElement
    ) {
      if (plausibleRow(node)) {
        return node;
      }
    }

    return null;
  }

  function countUnreadInbox() {
    if (!isInbox()) return null;

    const selectors = [
      '[class*="unread" i]',
      '[id*="unread" i]',
      '[aria-label*="unread" i]',
      '[aria-label*="não lido" i]',
      '[aria-label*="nao lido" i]',
      '[aria-label*="por ler" i]',
      '[title*="unread" i]',
      '[title*="não lido" i]',
      '[title*="nao lido" i]',
      '[title*="por ler" i]',
      '[data-status*="unread" i]',
      '[data-state*="unread" i]'
    ];

    const rows = new Set();

    for (const selector of selectors) {
      for (
        const candidate of
        document.querySelectorAll(selector)
      ) {
        if (!unreadMarked(candidate)) {
          continue;
        }

        const row = nearestRow(candidate);

        if (row) {
          rows.add(row);
        }
      }
    }

    for (
      const el of
      document.querySelectorAll("body *")
    ) {
      if (
        unreadMarked(el) &&
        plausibleRow(el)
      ) {
        rows.add(el);
      }
    }

    if (rows.size > 0) {
      return rows.size;
    }

    const body =
      norm(document.body?.innerText);

    if (
      body.includes("caixa de entrada") &&
      document.readyState === "complete"
    ) {
      return 0;
    }

    return null;
  }

  function send(force = false) {
    if (!isInbox()) return;

    const unread =
      countUnreadInbox();

    if (unread === null) return;

    const payload = {
      type: "sapo_state",
      folder: "INBOX",
      unread,
      url: location.href
    };

    const serialized =
      JSON.stringify(payload);

    if (
      !force &&
      serialized === lastSent
    ) {
      return;
    }

    lastSent = serialized;

    browser.runtime
      .sendNativeMessage(
        NATIVE_APP,
        payload
      )
      .catch(() => {});
  }

  send(true);

  const observer =
    new MutationObserver(() => {
      clearTimeout(
        window.__centralEmailsUnreadTimer
      );

      window.__centralEmailsUnreadTimer =
        setTimeout(
          () => send(false),
          500
        );
    });

  observer.observe(
    document.documentElement,
    {
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
    }
  );

  window.addEventListener(
    "hashchange",
    () => {
      lastSent = null;
      setTimeout(
        () => send(true),
        800
      );
    }
  );

  setInterval(
    () => send(true),
    10000
  );
})();
