/* ===========================================================================
 * openUC2 ODMR — Web Serial driver
 * ---------------------------------------------------------------------------
 * Talks to the ESP32-S3/C3 ODMR firmware over USB-CDC serial, mirroring the
 * full command set implemented in firmware processSerialLine():
 *
 *   PING                          -> PONG
 *   VERSION                       -> VERSION {json}
 *   STATUS                        -> STATUS  {json}
 *   MEASURE <f>                   -> DATA <f> <intensity> <bfield>
 *   INTENSITY                     -> INT <intensity>
 *   RATIO <f1> <f2> <f3|0> <avg>  -> RATIO {json}
 *   SWEEP <fb> <fe> <fs> [avg] [settle]
 *                                 -> SWEEP START {json}
 *                                    SWEEP DATA <idx> <total> <f> <intensity>
 *                                    SWEEP DONE <count> | SWEEP STOP <count>
 *   SWEEPSTOP                     -> stops a running sweep
 *   GAIN <0x00|0x10|0x20|0x30>    -> OK GAIN 0xXX
 *   INTTIME <0..5>                -> OK INTTIME <v>
 *   GETTSL                        -> TSL {json}
 *   ADFON / ADFOFF                -> OK ADF ON|OFF
 *
 * Commands are serialised through a queue so their line-based responses never
 * interleave. A sweep is a single long-running streaming task; SWEEPSTOP is
 * written directly (bypassing the queue, which the sweep is holding).
 * =========================================================================== */

export const BAUD_RATE = 115200;

export class SerialDevice {
  constructor() {
    this.port = null;
    this.writer = null;
    this.reader = null;
    this.connected = false;
    this._lineHandlers = new Set();
    this._stateHandlers = new Set();
    this._queue = Promise.resolve();
    this._encoder = new TextEncoder();
    this._readClosed = null;
  }

  static get supported() {
    return typeof navigator !== "undefined" && "serial" in navigator;
  }

  /* ---- connection lifecycle ------------------------------------------- */

  // Reconnect to a port the user already granted (no prompt). Returns true on
  // success. Safe to call on page load.
  async tryReconnect() {
    if (!SerialDevice.supported) return false;
    try {
      const ports = await navigator.serial.getPorts();
      if (ports.length === 0) return false;
      await this._open(ports[0]);
      return true;
    } catch (e) {
      console.warn("Auto-reconnect failed:", e);
      return false;
    }
  }

  // Prompt the user to pick a port, then open it. Requires a user gesture.
  async connect() {
    if (!SerialDevice.supported) {
      throw new Error("Web Serial API not supported in this browser.");
    }
    const port = await navigator.serial.requestPort();
    await this._open(port);
  }

  async _open(port) {
    this.port = port;
    await this.port.open({ baudRate: BAUD_RATE });
    this.writer = this.port.writable.getWriter();
    this._startReadLoop();
    this.connected = true;
    this._emitState();
  }

  async disconnect() {
    this.connected = false;
    try {
      if (this.reader) await this.reader.cancel().catch(() => {});
      if (this._readClosed) await this._readClosed.catch(() => {});
      if (this.writer) {
        await this.writer.close().catch(() => {});
        this.writer.releaseLock();
      }
      if (this.port) await this.port.close().catch(() => {});
    } finally {
      this.reader = null;
      this.writer = null;
      this.port = null;
      this._emitState();
    }
  }

  async _startReadLoop() {
    const decoder = new TextDecoderStream();
    // pipeTo resolves/rejects when the stream ends (disconnect / unplug)
    this._readClosed = this.port.readable
      .pipeTo(decoder.writable)
      .catch(() => {})
      .finally(() => this._onPortLost());
    this.reader = decoder.readable.getReader();

    let buf = "";
    try {
      while (true) {
        const { value, done } = await this.reader.read();
        if (done) break;
        buf += value;
        let nl;
        while ((nl = buf.indexOf("\n")) >= 0) {
          const line = buf.slice(0, nl).replace(/\r$/, "");
          buf = buf.slice(nl + 1);
          if (line.length) this._dispatch(line);
        }
      }
    } catch (e) {
      /* reader cancelled on disconnect */
    }
  }

  _onPortLost() {
    if (this.connected) {
      this.connected = false;
      this.port = null;
      this.writer = null;
      this.reader = null;
      this._emitState();
    }
  }

  /* ---- line / state subscriptions ------------------------------------- */

  onLine(fn) {
    this._lineHandlers.add(fn);
    return () => this._lineHandlers.delete(fn);
  }

  onStateChange(fn) {
    this._stateHandlers.add(fn);
    return () => this._stateHandlers.delete(fn);
  }

  _dispatch(line) {
    for (const h of this._lineHandlers) {
      try { h(line); } catch (e) { console.error(e); }
    }
  }

  _emitState() {
    for (const h of this._stateHandlers) {
      try { h(this.connected); } catch (e) { console.error(e); }
    }
  }

  /* ---- low-level write ------------------------------------------------ */

  // Direct write, bypassing the command queue. Used for SWEEPSTOP.
  async writeRaw(str) {
    if (!this.writer) throw new Error("not connected");
    await this.writer.write(this._encoder.encode(str));
  }

  /* ---- command queue -------------------------------------------------- */

  // Serialise a task so responses never interleave.
  _enqueue(task) {
    const run = this._queue.then(task, task);
    this._queue = run.catch(() => {});
    return run;
  }

  // Send a command, resolve with the first response line whose leading token is
  // one of `tags`. Tokens are matched on the first word (e.g. "DATA", "INT").
  request(cmd, tags, timeoutMs = 4000) {
    const tagList = Array.isArray(tags) ? tags : [tags];
    return this._enqueue(
      () =>
        new Promise((resolve, reject) => {
          const handler = (line) => {
            const tag = line.split(/\s+/, 1)[0];
            if (tagList.includes(tag)) {
              cleanup();
              resolve(line);
            }
          };
          const timer = setTimeout(() => {
            cleanup();
            reject(new Error("Timeout waiting for " + tagList.join("/") + " (" + cmd + ")"));
          }, timeoutMs);
          const cleanup = () => {
            clearTimeout(timer);
            this._lineHandlers.delete(handler);
          };
          this._lineHandlers.add(handler);
          this.writeRaw(cmd + "\n").catch((e) => {
            cleanup();
            reject(e);
          });
        })
    );
  }

  /* ---- high-level helpers -------------------------------------------- */

  async ping() {
    await this.request("PING", "PONG", 2000);
    return true;
  }

  async getVersion() {
    const line = await this.request("VERSION", "VERSION");
    return JSON.parse(line.slice(line.indexOf("{")));
  }

  async getStatus() {
    const line = await this.request("STATUS", "STATUS");
    return JSON.parse(line.slice(line.indexOf("{")));
  }

  // Tune to freq (MHz) and read intensity. Returns {f, intensity, bfield}.
  // Pass freq <= 0 for a plain live read at the current frequency.
  async measure(freqMHz) {
    const line = await this.request("MEASURE " + freqMHz, "DATA");
    const p = line.split(/\s+/);
    return {
      f: parseFloat(p[1]),
      intensity: parseInt(p[2], 10),
      bfield: p.length > 3 ? parseFloat(p[3]) : 0,
    };
  }

  // Fast cached intensity read for alignment.
  async intensity() {
    const line = await this.request("INTENSITY", "INT", 2500);
    return parseInt(line.split(/\s+/)[1], 10);
  }

  // 2- or 3-point ratio. f3 = 0 -> 2-point mode. Returns parsed JSON.
  async ratio(f1, f2, f3, avg) {
    const cmd = `RATIO ${f1} ${f2} ${f3 || 0} ${avg || 3}`;
    const line = await this.request(cmd, "RATIO", 6000);
    if (line.includes("ERR")) throw new Error(line);
    return JSON.parse(line.slice(line.indexOf("{")));
  }

  async getTSL() {
    const line = await this.request("GETTSL", "TSL");
    return JSON.parse(line.slice(line.indexOf("{")));
  }

  async setGain(gainHex) {
    return this.request("GAIN " + gainHex, ["OK", "ERR"]);
  }

  async setIntegration(value) {
    return this.request("INTTIME " + value, ["OK", "ERR"]);
  }

  async adf(on) {
    return this.request(on ? "ADFON" : "ADFOFF", "OK");
  }

  /* ---- streaming sweep ------------------------------------------------ */

  // Run a sweep. onPoint(idx, total, f, intensity) is called per point.
  // Resolves with {stopped, count}. Call stopSweep() to abort early.
  sweep(fBegin, fEnd, fStep, avg, settle, { onStart, onPoint } = {}) {
    const cmd = `SWEEP ${fBegin} ${fEnd} ${fStep} ${avg || 1} ${settle || 10}`;
    return this._enqueue(
      () =>
        new Promise((resolve, reject) => {
          let timer;
          const POINT_TIMEOUT = 8000; // generous: covers long settle/averaging
          const resetTimer = () => {
            clearTimeout(timer);
            timer = setTimeout(() => {
              cleanup();
              reject(new Error("Sweep timed out (no data)"));
            }, POINT_TIMEOUT);
          };
          const cleanup = () => {
            clearTimeout(timer);
            this._lineHandlers.delete(handler);
          };
          const handler = (line) => {
            const p = line.split(/\s+/);
            if (p[0] !== "SWEEP") return;
            resetTimer();
            switch (p[1]) {
              case "START":
                if (onStart) onStart(JSON.parse(line.slice(line.indexOf("{"))));
                break;
              case "DATA":
                if (onPoint)
                  onPoint(parseInt(p[2], 10), parseInt(p[3], 10), parseFloat(p[4]), parseInt(p[5], 10));
                break;
              case "DONE":
                cleanup();
                resolve({ stopped: false, count: parseInt(p[2], 10) });
                break;
              case "STOP":
                cleanup();
                resolve({ stopped: true, count: parseInt(p[2], 10) });
                break;
              case "ERR":
                cleanup();
                reject(new Error(line));
                break;
            }
          };
          this._lineHandlers.add(handler);
          resetTimer();
          this.writeRaw(cmd + "\n").catch((e) => {
            cleanup();
            reject(e);
          });
        })
    );
  }

  // Abort a running sweep. Written directly because the sweep task holds the
  // queue; the firmware consumes SWEEPSTOP inside its sweep loop.
  async stopSweep() {
    if (this.writer) await this.writeRaw("SWEEPSTOP\n").catch(() => {});
  }
}
