(() => {
  const NATIVE_APP = "sapoMonitor";
  const INBOX_TOKEN = "SU5CT1g";
  let lastSent = null;
  let ignoreMoveUntil = 0;

  document.addEventListener("click", event => {
    const control = event.target?.closest?.(
      'button, [role="button"], a, [role="menuitem"]'
    );
    const label = norm([
      control?.textContent,
      control?.getAttribute?.("title"),
      control?.getAttribute?.("aria-label")
    ].join(" "));
    if (/mover|move to|apagar|eliminar|lixo|trash|restaurar|archive|arquivar/.test(label)) {
      ignoreMoveUntil = Date.now() + 12000;
    }
  }, true);


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

  function stableRowId(row) {
    if (!row) return null;

    const attrs = [
      row.getAttribute?.("data-message-id"),
      row.getAttribute?.("data-id"),
      row.getAttribute?.("data-uid"),
      row.id
    ];

    for (const value of attrs) {
      if (value && String(value).trim()) {
        return `id:${String(value).trim()}`;
      }
    }

    const link =
      row.querySelector?.(
        'a[href*="/message"], a[href*="/messages/"], a[href*="messageId"], a[href*="uid"]'
      );

    const href =
      link?.getAttribute?.("href");

    if (href) {
      return `href:${href}`;
    }

    /*
     * Último recurso: hash do conteúdo da linha.
     * É estável enquanto a mesma mensagem continuar na lista.
     */
    const text =
      norm(row.textContent);

    if (!text) return null;

    let hash = 2166136261;

    for (let i = 0; i < text.length; i++) {
      hash ^= text.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }

    return `text:${hash >>> 0}`;
  }

  function unreadInboxState() {
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
      const unreadIds =
        Array.from(rows)
          .map(stableRowId)
          .filter(Boolean)
          .sort();

      return {
        unread: rows.size,
        unreadIds
      };
    }

    const body =
      norm(document.body?.innerText);

    if (
      body.includes("caixa de entrada") &&
      document.readyState === "complete"
    ) {
      return {
        unread: 0,
        unreadIds: []
      };
    }

    return null;
  }

  function send(force = false) {
    if (!isInbox() || Date.now() < ignoreMoveUntil) return;

    const state =
      unreadInboxState();

    if (state === null) return;

    const payload = {
      type: "sapo_state",
      folder: "INBOX",
      unread: state.unread,
      unreadIds: state.unreadIds,
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


  function findContactsDialog() {
    const candidates =
      Array.from(
        document.querySelectorAll(
          '[role="dialog"], .modal, .dialog, [class*="popup" i], body > div'
        )
      );

    return candidates.find(el => {
      const text =
        norm(el.textContent);

      return (
        text.includes("contactos") &&
        text.includes("grupos") &&
        el.querySelector('input[placeholder*="pesquisar" i], input[type="search"]')
      );
    }) || null;
  }

  function contactRows(dialog) {
    if (!dialog) return [];

    const checkboxes =
      Array.from(
        dialog.querySelectorAll(
          'input[type="checkbox"]'
        )
      );

    const rows = [];

    for (const checkbox of checkboxes) {
      let node =
        checkbox.parentElement;

      for (
        let i = 0;
        i < 6 && node;
        i++, node = node.parentElement
      ) {
        const text =
          norm(node.textContent);

        if (
          text.length >= 2 &&
          text.length <= 500 &&
          (
            text.includes("@") ||
            node.querySelector?.('input[type="checkbox"]')
          )
        ) {
          rows.push(node);
          break;
        }
      }
    }

    return Array.from(
      new Set(rows)
    );
  }

  function installContactSearchFix() {
    const dialog =
      findContactsDialog();

    if (!dialog) return;

    const input =
      dialog.querySelector(
        'input[placeholder*="pesquisar" i], input[type="search"]'
      );

    if (
      !input ||
      input.dataset.centralEmailsSearchFixed === "1"
    ) {
      return;
    }

    input.dataset.centralEmailsSearchFixed =
      "1";

    const rows =
      contactRows(dialog);

    const filter = () => {
      const query =
        norm(input.value);

      for (const row of rows) {
        if (!query) {
          row.style.removeProperty(
            "display"
          );
          continue;
        }

        const haystack =
          norm(row.textContent);

        row.style.display =
          haystack.includes(query)
            ? ""
            : "none";
      }
    };

    input.addEventListener(
      "input",
      filter
    );

    input.addEventListener(
      "keydown",
      event => {
        if (
          event.key === "Enter"
        ) {
          event.preventDefault();
          event.stopPropagation();
          filter();
        }
      },
      true
    );

    const searchButton =
      input.parentElement
        ?.querySelector(
          'button, [role="button"], input[type="submit"]'
        ) ||
      input.parentElement
        ?.nextElementSibling;

    if (searchButton) {
      searchButton.addEventListener(
        "click",
        event => {
          event.preventDefault();
          event.stopPropagation();
          filter();
        },
        true
      );
    }
  }

  // Destinatários SAPO: confirmar endereços escritos manualmente.
  // Não substituímos o editor SAPO: usamos os eventos nativos para que
  // a aplicação Web crie os seus próprios chips/destinatários.
  const emailPattern = /^[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+$/;
  function recipientInput(input) {
    if (!input || !input.matches?.('input, [contenteditable="true"]')) return false;
    const parent = input.closest?.('[class*="recipient" i], [class*="address" i], [class*="destinat" i], [class*="to-field" i], [class*="cc-field" i], [class*="bcc-field" i]');
    const hint = norm([
      input.getAttribute('placeholder'),
      input.getAttribute('aria-label'),
      input.getAttribute('name'),
      parent?.getAttribute('class'),
      parent?.getAttribute('aria-label')
    ].join(' '));
    return /destinat|recipient|endereço|email|e-mail|\\bpara\\b|\\bto\\b|\\bcc\\b|\\bbcc\\b|\\bcco\\b/.test(hint);
  }
  function commitRecipient(input) {
    if (!recipientInput(input)) return;
    const value = (input.value ?? input.textContent ?? '').trim();
    if (!emailPattern.test(value)) return;
    // O editor SAPO é responsável por validar e criar o destinatário.
    input.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
    }));
    input.dispatchEvent(new KeyboardEvent('keyup', {
      key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
    }));
  }
  document.addEventListener('focusout', event => {
    commitRecipient(event.target);
  }, true);
  document.addEventListener('keydown', event => {
    if (!recipientInput(event.target)) return;
    if (event.key === 'Tab') commitRecipient(event.target);
  }, true);

  send(true);
  installContactSearchFix();

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

      clearTimeout(
        window.__centralEmailsContactsTimer
      );

      window.__centralEmailsContactsTimer =
        setTimeout(
          installContactSearchFix,
          250
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
    () => {
      send(true);
      installContactSearchFix();
    },
    10000
  );
})();
