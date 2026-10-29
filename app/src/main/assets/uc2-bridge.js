// Loaded before the ODMR WebSerial app in the ODMR tab (OdmrTab.java).
// Gives the app navigator.serial on top of UC2 Control's own USB connection
// (window.UC2Android), and hands its downloads (CSV, PNG) to the app, which
// saves them and offers to share them. The webapp itself stays unchanged.
(() => {
  const A = window.UC2Android;
  if (!A) return;
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  const events = new EventTarget();
  let rx = null;        // ReadableStream controller while the port is open
  let pending = null;   // requestPort() waiting for the app to connect

  const closeRx = () => {
    if (rx) {
      try { rx.close(); } catch (e) { /* already closed */ }
      rx = null;
    }
  };

  // One port: the board the app is connected to.
  const port = {
    readable: null,
    writable: null,
    async open() {
      this.readable = new ReadableStream({ start(c) { rx = c; }, cancel() { rx = null; } });
      this.writable = new WritableStream({ write(chunk) { A.write(dec.decode(chunk)); } });
    },
    async close() {
      closeRx();
      A.disconnect();
    },
    getInfo() { return {}; },
  };

  // Called by the app: received text, and connection changes.
  window.__uc2Serial = {
    receive(text) { if (rx) rx.enqueue(enc.encode(text)); },
    state(connected) {
      if (connected) {
        if (pending) { pending.resolve(port); pending = null; }
        return;
      }
      if (pending) { pending.reject(new Error("USB connection failed")); pending = null; }
      if (!rx) return;   // no port open: nothing was lost
      closeRx();         // ends the webapp's read loop: it shows "not connected"
      events.dispatchEvent(new Event("disconnect"));
    },
  };

  Object.defineProperty(navigator, "serial", {
    configurable: true,
    value: {
      getPorts: async () => (A.isConnected() ? [port] : []),
      requestPort: () => new Promise((resolve, reject) => {
        if (A.isConnected()) { resolve(port); return; }
        pending = { resolve, reject };
        A.connect();   // the app's Connect: USB permission, then state(true|false)
      }),
      addEventListener: (type, fn) => events.addEventListener(type, fn),
      removeEventListener: (type, fn) => events.removeEventListener(type, fn),
    },
  });

  // Downloads: <a download href="blob:…">.click() goes to the app instead of the
  // WebView, which cannot download blob URLs. Blobs are remembered at creation,
  // since the webapp may revoke the URL right after the click.
  const blobs = new Map();
  const createObjectURL = URL.createObjectURL.bind(URL);
  URL.createObjectURL = (obj) => {
    const url = createObjectURL(obj);
    if (obj instanceof Blob) blobs.set(url, obj);
    return url;
  };
  const click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    const blob = this.download && blobs.get(this.href);
    if (!blob) { click.call(this); return; }
    blobs.delete(this.href);
    const name = this.download;
    const reader = new FileReader();
    reader.onload = () => A.save(name, String(reader.result).split(",")[1] || "", blob.type);
    reader.readAsDataURL(blob);
  };

  // First start: follow the phone's language until the user picks one.
  try {
    if (!localStorage.getItem("language")) {
      localStorage.setItem("language", (navigator.language || "de").startsWith("de") ? "de" : "en");
    }
  } catch (e) { /* storage blocked */ }
})();
