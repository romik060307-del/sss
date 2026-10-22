/* WA Cloning RK v2.2 - ekspor chat yang sedang terbuka (teks + HTML, dengan atau tanpa media).
 * Dipanggil dari MainActivity: window.__rkStartExport(withMedia) -> 'OK' | 'BUSY'.
 * Hasil dikirim ke jembatan WACloningExport: append(path, base64, truncate), progress(text),
 * finish(title) -> 'OK' | pesan galat, fail(pesan). Java membungkusnya menjadi ZIP di Download/WA Cloning.
 *
 * Keterbatasan (dijelaskan juga di layar): hanya pesan yang sudah dimuat WhatsApp Web; foto/video harus
 * sudah tampil penuh; pesan sekali lihat tidak diekspor; dokumen hanya namanya; pesan sistem dilewati.
 */
(function () {
  'use strict';
  var W = window;
  var D = document;
  if (W.__rkStartExport) return;

  var CHUNK = 512 * 1024;                 // byte mentah per panggilan jembatan
  var MAX_MEDIA_BYTES = 80 * 1024 * 1024;
  var MAX_HISTORY_ROUNDS = 300;

  var CSS = 'body{margin:0;font-family:-apple-system,Roboto,"Segoe UI",sans-serif;background:#efeae2;color:#111b21}' +
    'header{position:sticky;top:0;background:#075e54;color:#fff;padding:12px 16px;font-weight:600;z-index:1}' +
    'main{max-width:720px;margin:0 auto;padding:12px 10px 40px}' +
    '.day{text-align:center;margin:10px 0}' +
    '.day span{background:#fff;color:#54656f;font-size:12px;padding:5px 12px;border-radius:8px;box-shadow:0 1px 0.5px rgba(11,20,26,.13)}' +
    '.row{display:flex;margin:2px 0}.r-in{justify-content:flex-start}.r-out{justify-content:flex-end}' +
    '.msg{max-width:78%;padding:6px 8px 4px;border-radius:8px;background:#fff;box-shadow:0 1px 0.5px rgba(11,20,26,.13);word-wrap:break-word;overflow-wrap:anywhere}' +
    '.msg.out{background:#d9fdd3}' +
    '.who{font-size:12.5px;font-weight:600;color:#06cf9c;margin-bottom:2px}' +
    '.text{font-size:14.2px;line-height:1.35}' +
    '.time{font-size:11px;color:#667781;text-align:right;margin-top:2px}' +
    '.note,.ph{font-size:13px;color:#54656f;font-style:italic;padding:4px 0}' +
    'img,video{display:block;max-width:100%;border-radius:6px;margin-bottom:4px}' +
    'audio{display:block;max-width:100%;margin-bottom:4px}' +
    'a{color:#027eb5}';

  function bridge() { return W.WACloningExport || null; }
  function report(text) { try { var b = bridge(); if (b) b.progress(String(text)); } catch (ignore) { } }
  function sleep(ms) { return new Promise(function (resolve) { W.setTimeout(resolve, ms); }); }
  function pad4(n) { var s = String(n); while (s.length < 4) s = '0' + s; return s; }

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function linkify(escaped) {
    return escaped.replace(/(https?:\/\/[^\s<]+)/g, '<a href="$1" target="_blank" rel="noopener">$1</a>');
  }

  /* ---------- penulisan ke jembatan ---------- */
  function toBase64(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return W.btoa(s);
  }
  function writeBytes(path, bytes) {
    var b = bridge();
    if (!b) throw new Error('Jembatan ekspor tidak tersedia. Muat ulang halaman lalu coba lagi.');
    if (bytes.length === 0) { if (!b.append(path, '', true)) throw new Error('Gagal menulis ' + path); return; }
    for (var off = 0; off < bytes.length; off += CHUNK) {
      var part = bytes.subarray(off, Math.min(bytes.length, off + CHUNK));
      if (!b.append(path, toBase64(part), off === 0)) throw new Error('Gagal menulis ' + path);
    }
  }
  async function writeBlob(path, blob) {
    var b = bridge();
    if (!b) throw new Error('Jembatan ekspor tidak tersedia. Muat ulang halaman lalu coba lagi.');
    if (blob.size === 0) { if (!b.append(path, '', true)) throw new Error('Gagal menulis ' + path); return; }
    for (var off = 0; off < blob.size; off += CHUNK) {
      var part = new Uint8Array(await blob.slice(off, Math.min(blob.size, off + CHUNK)).arrayBuffer());
      if (!b.append(path, toBase64(part), off === 0)) throw new Error('Gagal menulis ' + path);
    }
  }

  /* ---------- membaca DOM WhatsApp Web ---------- */
  function chatTitle() {
    var main = D.querySelector('#main');
    if (!main) return '';
    var el = main.querySelector('header [data-testid="conversation-info-header-chat-title"]') ||
      main.querySelector('header span[title]') || main.querySelector('header [dir="auto"]');
    return el ? String(el.getAttribute('title') || el.textContent || '').trim() : '';
  }
  function messageNodes() {
    return Array.prototype.slice.call(D.querySelectorAll('#main [data-pre-plain-text]'));
  }
  function findScroller() {
    var node = D.querySelector('#main [data-pre-plain-text]');
    var el = node ? node.parentElement : null;
    while (el && el !== D.body) {
      var cs = W.getComputedStyle(el);
      if ((cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.scrollHeight > el.clientHeight + 20) return el;
      el = el.parentElement;
    }
    return D.querySelector('#main [data-testid="conversation-panel-messages"]');
  }
  function rowOf(node) { return node.closest('[data-id]') || node.parentElement; }
  function keyOf(node) {
    var row = rowOf(node);
    var id = row ? (row.getAttribute('data-id') || '') : '';
    return id || (node.getAttribute('data-pre-plain-text') + '|' + node.textContent.slice(0, 80));
  }
  function parsePre(pre) {
    // "[15.36, 21/10/2026] Nama: " -> waktu, tanggal, nama (format mengikuti bahasa WhatsApp Web)
    var m = /^\[(.+?)\]\s*(.*?):\s*$/.exec(String(pre || '').trim());
    if (!m) return { time: '', date: '', name: '' };
    var parts = m[1].split(',');
    return { time: parts[0].trim(), date: (parts[1] || '').trim(), name: m[2].trim() };
  }
  function textOf(node) {
    var out = '';
    (function walk(n) {
      for (var c = n.firstChild; c; c = c.nextSibling) {
        if (c.nodeType === 3) out += c.nodeValue;
        else if (c.nodeType === 1) {
          if (c.tagName === 'IMG') out += c.getAttribute('alt') || '';
          else if (c.tagName === 'BR') out += '\n';
          else walk(c);
        }
      }
    })(node);
    return out;
  }
  function nearView(el, sr) {
    var r = el.getBoundingClientRect();
    return r.bottom > sr.top - 400 && r.top < sr.bottom + 400;
  }

  /* ---------- pesan dan media ---------- */
  function buildEntry(node, ctx) {
    var meta = parsePre(node.getAttribute('data-pre-plain-text'));
    var row = rowOf(node);
    var rowId = row ? (row.getAttribute('data-id') || '') : '';
    var out = rowId.indexOf('true_') === 0;
    var sel = node.querySelectorAll('.selectable-text,[data-testid="selectable-text"]');
    var text = '';
    if (sel.length) {
      // Pick the last body span; quoted-message text often appears before the actual message.
      text = textOf(sel[sel.length - 1]);
    }
    if (!text) {
      // WhatsApp occasionally changes its internal class names. The metadata node itself still
      // carries the body text in many builds, so use it before silently exporting an empty message.
      text = textOf(node);
    }
    if (!text) {
      var fallback = row && row.querySelector('[dir="ltr"],[dir="auto"],[data-testid*="message-text"]');
      if (fallback) text = textOf(fallback);
    }
    text = String(text || '').replace(/\u00a0/g, ' ').replace(/[ \t]+\n/g, '\n').trim();
    var entry = {
      row: row, time: meta.time, date: meta.date, out: out,
      name: meta.name || (out ? 'Anda' : 'Tidak diketahui'),
      text: text.replace(/\s+$/, ''), items: [], seenEls: [],
      scanCount: 0, viewOnce: false, docDone: false, locDone: false
    };
    var viewIcon = row && row.querySelector('[data-icon="view-once"],[data-icon="view-once-filled"],[data-icon="viewonce"]');
    if (viewIcon || /^(foto|video|pesan|photo) sekali lihat$/i.test(entry.text)) {
      entry.viewOnce = true;
      entry.items.push({ kind: 'note', text: 'Pesan sekali lihat (tidak diekspor)' });
    }
    scanMedia(entry, ctx);
    return entry;
  }

  function scanMedia(entry, ctx) {
    var row = entry.row;
    if (!row || entry.viewOnce) return;

    function addFile(el, code, label) {
      if (entry.seenEls.indexOf(el) >= 0) return;
      entry.seenEls.push(el);
      ctx.counters[code] = (ctx.counters[code] || 0) + 1;
      var item = { kind: 'file', code: code, label: label, el: el, state: 'pending', base: code + '-WA' + pad4(ctx.counters[code]), name: '', reason: '' };
      entry.items.push(item);
      if (ctx.withMedia) ctx.pending.push(item); else item.state = 'skipped';
    }

    var hasVideo = !!row.querySelector('video');
    var isLocation = !!row.querySelector('[data-icon="location"],[data-icon="location-filled"],[data-testid*="location"]');

    var vids = row.querySelectorAll('video');
    for (var v = 0; v < vids.length; v++) addFile(vids[v], 'VID', 'Video');
    var auds = row.querySelectorAll('audio');
    for (var a = 0; a < auds.length; a++) addFile(auds[a], 'AUD', 'Suara');

    // Thumbnail video dan peta lokasi bukan file terpisah, jadi dilewati.
    if (!hasVideo && !isLocation) {
      var imgs = row.querySelectorAll('img');
      for (var i = 0; i < imgs.length; i++) {
        var img = imgs[i];
        var src = img.getAttribute('src') || '';
        if (src.indexOf('blob:') !== 0 && src.indexOf('data:') !== 0) continue;
        if (img.clientWidth < 100 && img.clientHeight < 100) continue;  // avatar dan kutipan
        var alt = (img.getAttribute('alt') || '').toLowerCase();
        if (/stiker|sticker/.test(alt)) addFile(img, 'STK', 'Stiker');
        else addFile(img, 'IMG', 'Foto');
      }
    }

    if (!entry.docDone && !hasVideo && row.querySelector('[data-icon="document"],[data-icon="document-fill"],[data-icon="document-filled-refreshed"],[data-testid*="document"]')) {
      entry.docDone = true;
      var titleEl = row.querySelector('[data-testid*="document"] span[title]') || row.querySelector('span[title]');
      var docName = titleEl ? (titleEl.getAttribute('title') || '').trim() : '';
      entry.items.push({ kind: 'file', code: 'DOC', label: 'Dokumen', el: null, state: 'unsupported',
        base: '', name: '', reason: 'dokumen tidak bisa diekspor otomatis', docName: docName });
    }

    if (!entry.locDone && isLocation) {
      entry.locDone = true;
      var link = row.querySelector('a[href*="maps"],a[href^="geo:"]');
      entry.items.push({ kind: 'note', text: 'Lokasi: ' + (link ? link.getAttribute('href') : '(tautan tidak terbaca)') });
    }
  }

  function extFor(mime, code) {
    var m = String(mime || '').toLowerCase().split(';')[0];
    var map = {
      'image/jpeg': 'jpg', 'image/jpg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/gif': 'gif',
      'video/mp4': 'mp4', 'video/3gpp': '3gp', 'video/webm': 'webm', 'video/quicktime': 'mov',
      'audio/ogg': 'ogg', 'audio/mpeg': 'mp3', 'audio/mp4': 'm4a', 'audio/aac': 'aac', 'audio/webm': 'webm', 'audio/wav': 'wav'
    };
    if (map[m]) return map[m];
    if (code === 'VID') return 'mp4';
    if (code === 'AUD') return 'ogg';
    if (code === 'STK') return 'webp';
    return 'jpg';
  }

  async function tryCapture(item, final) {
    var el = item.el;
    var src = el.getAttribute('src') || el.currentSrc || '';
    if (!src) return false;                                   // belum dimuat, coba lagi nanti
    if (src.indexOf('blob:') !== 0 && !(final && src.indexOf('data:') === 0)) return false;  // tunggu versi penuh
    try {
      var blob = await (await fetch(src)).blob();
      if (blob.size > MAX_MEDIA_BYTES) { item.state = 'unsupported'; item.reason = 'ukuran terlalu besar'; return true; }
      item.name = item.base + '.' + extFor(blob.type, item.code);
      await writeBlob('media/' + item.name, blob);
      item.state = 'done';
    } catch (e) {
      item.state = 'unsupported';
      item.reason = 'tidak bisa dibaca';
    }
    return true;
  }

  async function processPending(ctx, final) {
    var list = ctx.pending;
    for (var i = list.length - 1; i >= 0; i--) {
      if (await tryCapture(list[i], final)) list.splice(i, 1);
    }
  }

  async function collect(ctx, final) {
    var sr = ctx.scroller.getBoundingClientRect();
    var nodes = messageNodes();
    var order = [];
    var last = Object.create(null);
    for (var i = 0; i < nodes.length; i++) {
      var key = keyOf(nodes[i]);
      if (!(key in last)) order.push(key);
      last[key] = nodes[i];       // pesan kutipan muncul sebelum isi pesan; isi (yang terakhir) yang dipakai
    }
    for (var j = 0; j < order.length; j++) {
      var k = order[j];
      if (ctx.seen[k]) continue;
      ctx.seen[k] = true;
      ctx.entries.push(buildEntry(last[k], ctx));
    }
    for (var m = 0; m < ctx.entries.length; m++) {
      var en = ctx.entries[m];
      if (!en.row || !en.row.isConnected) continue;
      if (final) { scanMedia(en, ctx); continue; }
      if (en.scanCount < 4 && nearView(en.row, sr)) { en.scanCount++; scanMedia(en, ctx); }
    }
    if (ctx.withMedia) await processPending(ctx, final);
  }

  /* ---------- hasil: TXT (gaya WhatsApp) dan HTML ---------- */
  function buildTxt(entries, withMedia) {
    var lines = [];
    entries.forEach(function (e) {
      var head = '[' + e.time + (e.date ? ', ' + e.date : '') + '] ' + e.name + ': ';
      var parts = [];
      e.items.forEach(function (it) {
        if (it.kind === 'note') parts.push(it.text);
        else if (!withMedia) parts.push('<Media omitted>');
        else if (it.state === 'done') parts.push(it.name + ' (file attached)');
        else parts.push('<' + it.label + (it.docName ? ': ' + it.docName : '') + ' tidak tersedia' + (it.reason ? ' (' + it.reason + ')' : '') + '>');
      });
      if (e.text) parts.push(e.text);
      if (!parts.length) parts.push('');
      lines.push(head + parts[0]);
      for (var i = 1; i < parts.length; i++) lines.push(parts[i]);
    });
    return lines.join('\n') + '\n';
  }

  function mediaHtml(it) {
    var src = 'media/' + esc(it.name);
    if (it.code === 'VID') return '<video controls preload="metadata" src="' + src + '"></video>';
    if (it.code === 'AUD') return '<audio controls preload="metadata" src="' + src + '"></audio>';
    return '<img loading="lazy" alt="' + esc(it.label) + '" src="' + src + '">';
  }

  function buildHtml(title, entries, withMedia) {
    var out = [];
    var lastDate = null;
    entries.forEach(function (e) {
      if (e.date && e.date !== lastDate) {
        out.push('<div class="day"><span>' + esc(e.date) + '</span></div>');
        lastDate = e.date;
      }
      var body = '';
      e.items.forEach(function (it) {
        if (it.kind === 'note') body += '<div class="note">' + linkify(esc(it.text)) + '</div>';
        else if (!withMedia) body += '<div class="ph">&lt;Media omitted&gt;</div>';
        else if (it.state === 'done') body += mediaHtml(it);
        else body += '<div class="ph">' + esc('<' + it.label + (it.docName ? ': ' + it.docName : '') + ' tidak tersedia' + (it.reason ? ' (' + it.reason + ')' : '') + '>') + '</div>';
      });
      if (e.text) body += '<div class="text">' + linkify(esc(e.text)).replace(/\n/g, '<br>') + '</div>';
      out.push('<div class="row ' + (e.out ? 'r-out' : 'r-in') + '"><div class="msg' + (e.out ? ' out' : '') + '">' +
        (!e.out ? '<div class="who">' + esc(e.name) + '</div>' : '') + body +
        '<div class="time">' + esc(e.time + (e.date ? ' · ' + e.date : '')) + '</div></div></div>');
    });
    return '<!doctype html><html lang="id"><head><meta charset="utf-8">' +
      '<meta name="viewport" content="width=device-width,initial-scale=1">' +
      '<title>' + esc('Chat - ' + title) + '</title><style>' + CSS + '</style></head><body>' +
      '<header>' + esc(title) + '</header><main>' + out.join('\n') + '</main></body></html>';
  }

  /* ---------- alur utama ---------- */
  async function exportChat(withMedia) {
    var title = chatTitle() || 'WhatsApp';
    var scroller = findScroller();
    if (!scroller || messageNodes().length === 0) {
      throw new Error('Buka chat yang ingin diekspor dulu, lalu pilih Ekspor chat lagi.');
    }

    // 1) Muat riwayat sampai pesan paling awal (WhatsApp memuat pesan lama saat digulir ke atas).
    report('Memuat riwayat chat "' + title + '"…');
    var stable = 0, last = -1, rounds = 0;
    while (stable < 4 && rounds < MAX_HISTORY_ROUNDS) {
      // Setting scrollTop to the value it already has fires no scroll event, so nudge it first.
      if (scroller.scrollTop === 0) scroller.scrollTop = 2;
      scroller.scrollTop = 0;
      await sleep(900);
      var count = messageNodes().length;
      stable = count === last ? stable + 1 : 0;
      last = count;
      rounds++;
      if (rounds % 2 === 0) report('Memuat riwayat chat… ' + count + ' pesan');
    }

    // 2) Turun dari atas ke bawah: kumpulkan pesan dan media yang sudah tampil.
    var ctx = { withMedia: withMedia, scroller: scroller, entries: [], seen: Object.create(null), pending: [], counters: {} };
    scroller.scrollTop = 0;
    await sleep(500);
    var step = Math.max(200, Math.floor(scroller.clientHeight * 0.6));
    var guard = 0, stuck = 0;
    while (guard++ < 20000) {
      await collect(ctx, false);
      var max = scroller.scrollHeight - scroller.clientHeight;
      if (scroller.scrollTop >= max - 2) break;
      var before = scroller.scrollTop;
      scroller.scrollTop = Math.min(max, before + step);
      await sleep(280);
      if (scroller.scrollTop === before) { if (++stuck > 3) break; } else stuck = 0;
      if (guard % 8 === 0) report('Mengumpulkan pesan… ' + ctx.entries.length + ' pesan');
    }

    // 3) Putaran akhir: media yang baru selesai dimuat dan pratinjau yang sudah diganti versi penuh.
    report('Mengambil media terakhir…');
    for (var k = 0; k < 3; k++) { await collect(ctx, true); await sleep(400); }
    ctx.pending.forEach(function (it) {
      it.state = 'unsupported';
      it.reason = 'belum dimuat, buka dulu lalu ekspor lagi';
    });
    ctx.pending = [];

    if (!ctx.entries.length) throw new Error('Tidak ada pesan yang bisa dibaca di chat ini.');

    // 4) Tulis berkas dan bungkus menjadi ZIP.
    report('Menyusun file chat (' + ctx.entries.length + ' pesan)…');
    var enc = new TextEncoder();
    writeBytes('_chat.txt', enc.encode(buildTxt(ctx.entries, withMedia)));
    writeBytes('chat.html', enc.encode(buildHtml(title, ctx.entries, withMedia)));
    report('Membuat ZIP…');
    var result = bridge().finish(title);
    if (result !== 'OK') throw new Error(result || 'Gagal membuat ZIP.');
  }

  W.__rkStartExport = function (withMedia) {
    if (W.__rkExporting) return 'BUSY';
    W.__rkExporting = true;
    exportChat(!!withMedia).then(function () {
      W.__rkExporting = false;
    }, function (err) {
      W.__rkExporting = false;
      try {
        var b = bridge();
        if (b) b.fail(String((err && err.message) || err));
      } catch (ignore) { }
    });
    return 'OK';
  };
})();
