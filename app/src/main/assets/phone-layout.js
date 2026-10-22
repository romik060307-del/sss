/* WA Cloning RK v2.3 - full-screen profile/sections, hierarchical back, and media picker support, full-screen section navigation, and reduced layout churn.
 * Keep WhatsApp's own message/composer DOM intact; only switch outer panes.
 */
(function () {
  'use strict';
  try {
    var W = window;
    var D = document;
    var N = W.navigator;
    var PHONE_VIEWPORT = 'width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover';
    // Width WhatsApp sees on the dedicated feature screen. 1000 CSS px keeps its three-column desktop layout.
    var DESKTOP_WIDTH = 1000;
    var DESKTOP_VIEWPORT = 'width=' + DESKTOP_WIDTH + ',user-scalable=yes,maximum-scale=4';
    if (typeof W.__rkForceMode === 'undefined') W.__rkForceMode = null;
    var lastNotifiedMode = null;

    var viewport = D.querySelector('meta[name="viewport"]');
    if (!viewport) {
      viewport = D.createElement('meta');
      viewport.name = 'viewport';
      (D.head || D.documentElement).appendChild(viewport);
    }
    function setViewport(content) { viewport.setAttribute('content', content); }
    setViewport(W.__rkDesktopMode ? DESKTOP_VIEWPORT : PHONE_VIEWPORT);

    // Remove old injected styles if the page survived a WebView restore.
    try { Array.prototype.forEach.call(D.querySelectorAll('style[id^=\"rk-phone-layout-v\"]'), function (oldStyle) { if (oldStyle.id !== 'rk-phone-layout-v22') oldStyle.remove(); }); } catch (ignore) { }
    var styleId = 'rk-phone-layout-v22';
    var style = D.getElementById(styleId);
    if (!style) {
      style = D.createElement('style');
      style.id = styleId;
      (D.head || D.documentElement).appendChild(style);
    }
    var PHONE_CSS = [
      /* Do not lock the document's vertical scroll globally; WhatsApp owns inner scrollers. */
      'html,body{width:100%!important;min-width:0!important;max-width:100%!important;min-height:100%!important;margin:0!important;padding:0!important;overflow-x:hidden!important;}',
      'html.rk-phone-active{--navbar-width:0px!important;}',
      '#app,.app-wrapper-web{box-sizing:border-box!important;width:100%!important;min-width:0!important;max-width:100%!important;height:100%!important;min-height:0!important;max-height:100%!important;margin:0!important;}',
      '.rk-phone-root{box-sizing:border-box!important;position:fixed!important;inset:0!important;display:block!important;width:auto!important;min-width:0!important;max-width:none!important;height:auto!important;min-height:0!important;max-height:none!important;margin:0!important;padding:0!important;transform:none!important;overflow:hidden!important;}',
      '.rk-phone-root .rk-phone-side-slot,.rk-phone-root .rk-phone-main-slot{box-sizing:border-box!important;position:fixed!important;inset:0!important;display:block!important;flex:none!important;width:auto!important;min-width:0!important;max-width:none!important;height:auto!important;min-height:0!important;max-height:none!important;margin:0!important;padding:0!important;transform:none!important;}',
      '.rk-phone-root.rk-phone-list .rk-phone-main-slot{display:none!important;}',
      '.rk-phone-root.rk-phone-chat .rk-phone-side-slot,.rk-phone-root.rk-phone-panel .rk-phone-side-slot{display:none!important;}',
      '.rk-phone-root.rk-phone-list .rk-phone-side-slot,.rk-phone-root.rk-phone-chat .rk-phone-main-slot,.rk-phone-root.rk-phone-panel .rk-phone-main-slot,.rk-phone-root.rk-phone-navpanel .rk-phone-side-slot{display:block!important;}',
      '.rk-phone-root.rk-phone-navpanel .rk-phone-main-slot{display:none!important;}',
      '.rk-phone-root.rk-phone-navpanel .rk-phone-side-slot,.rk-phone-root.rk-phone-navpanel #side,.rk-phone-root.rk-phone-navpanel #pane-side{position:fixed!important;inset:0!important;left:0!important;right:0!important;width:100vw!important;min-width:100vw!important;max-width:100vw!important;height:100%!important;max-height:100%!important;margin:0!important;transform:none!important;box-sizing:border-box!important;}',
      '.rk-phone-root.rk-phone-navpanel .rk-phone-side-slot>div,.rk-phone-root.rk-phone-navpanel #side>div,.rk-phone-root.rk-phone-navpanel #pane-side>div{box-sizing:border-box!important;width:100%!important;min-width:0!important;max-width:100%!important;}',
      '.rk-phone-root.rk-phone-navpanel [data-testid*="drawer"],.rk-phone-root.rk-phone-navpanel [data-testid*="archiv"],.rk-phone-root.rk-phone-navpanel [data-testid*="calls"],.rk-phone-root.rk-phone-navpanel [data-testid*="status"],.rk-phone-root.rk-phone-navpanel [data-testid*="channel"],.rk-phone-root.rk-phone-navpanel [data-testid*="community"]{box-sizing:border-box!important;max-width:100vw!important;min-width:0!important;}',
      /* v2.1: profile / contact info (opened from the chat header) covers the whole phone screen. */
      'html.rk-phone-active .rk-phone-drawer-right{position:fixed!important;inset:0!important;top:0!important;bottom:0!important;left:0!important;right:0!important;width:100vw!important;max-width:100vw!important;min-width:100vw!important;height:100%!important;max-height:100%!important;margin:0!important;padding-bottom:env(safe-area-inset-bottom,0px)!important;transform:none!important;z-index:2147483000!important;overflow-x:hidden!important;overflow-y:auto!important;box-sizing:border-box!important;background:#fff!important;}',
      /* v2.0: park the desktop rail off-screen instead of display:none. It keeps its real size and position, so the
         sidebar buttons can still be found and clicked, but it takes no width and cannot be touched. */
      '.rk-phone-root .rk-phone-nav-rail{position:fixed!important;left:-400px!important;top:0!important;width:80px!important;min-width:80px!important;max-width:80px!important;height:100%!important;margin:0!important;padding:0!important;border:0!important;visibility:hidden!important;pointer-events:none!important;}',
      /* Avoid setting display:flex or overflow:hidden on WhatsApp's internal pane wrappers. */
      '#side,#main{box-sizing:border-box!important;min-width:0!important;max-width:100%!important;width:100%!important;height:100%!important;min-height:0!important;}',
      '#pane-side{min-width:0!important;max-width:100%!important;min-height:0!important;overflow-x:hidden!important;overflow-y:auto!important;overscroll-behavior:contain!important;-webkit-overflow-scrolling:touch!important;}',
      '#main [data-testid*=\"conversation-panel-messages\"]{min-height:0!important;overscroll-behavior:contain!important;-webkit-overflow-scrolling:touch!important;}',
      '#main footer [contenteditable=\"true\"],#main [data-testid=\"conversation-compose-box-input\"]{font-size:16px!important;}',
      '#main [data-testid=\"conversation-compose-box-input\"],[contenteditable=\"true\"]{max-width:100%!important;}',
      '@media (max-width:760px){.app-wrapper-web{top:0!important;}}',
      /* The QR/link screen is allowed to use normal document flow and vertical scrolling. */
      'html.rk-login-active{width:100%!important;min-width:0!important;max-width:100%!important;height:auto!important;min-height:100%!important;max-height:none!important;overflow-x:hidden!important;overflow-y:auto!important;margin:0!important;padding:0!important;}',
      'html.rk-login-active body{width:100%!important;min-width:0!important;max-width:100%!important;height:auto!important;min-height:100vh!important;max-height:none!important;overflow-x:hidden!important;overflow-y:auto!important;margin:0!important;padding:0!important;}',
      'html.rk-login-active #app,html.rk-login-active #app>div,html.rk-login-active .app-wrapper-web,html.rk-login-active .landing-wrapper,html.rk-login-active .landing-main{box-sizing:border-box!important;width:100%!important;min-width:0!important;max-width:100%!important;height:auto!important;min-height:100%!important;max-height:none!important;margin-left:auto!important;margin-right:auto!important;overflow:visible!important;position:relative!important;left:auto!important;right:auto!important;transform:none!important;}',
      'html.rk-login-active #app *{box-sizing:border-box!important;min-width:0!important;}',
      'html.rk-login-active #app .rk-login-fit{min-width:0!important;max-width:100vw!important;}',
      'html.rk-login-active #app .rk-login-too-wide{width:100%!important;min-width:0!important;max-width:100vw!important;flex-shrink:1!important;}',
      'html.rk-login-active #app .rk-login-stack{flex-direction:column!important;align-items:center!important;justify-content:flex-start!important;gap:14px!important;}',
      'html.rk-login-active #app .rk-login-qr-code{display:block!important;width:var(--rk-login-qr-size,240px)!important;height:var(--rk-login-qr-size,240px)!important;max-width:78vw!important;max-height:78vw!important;object-fit:contain!important;flex:0 0 auto!important;margin:12px auto!important;}',
      'html.rk-login-active #app .rk-login-qr-wrap{width:auto!important;min-width:0!important;max-width:86vw!important;height:auto!important;max-height:none!important;overflow:visible!important;margin-left:auto!important;margin-right:auto!important;flex:0 1 auto!important;}',
      'html.rk-login-active #app [data-testid*=\"qr\"],html.rk-login-active #app [aria-label*=\"QR\"]{width:auto!important;min-width:0!important;max-width:86vw!important;margin-left:auto!important;margin-right:auto!important;}',
      'html.rk-login-active #app button,html.rk-login-active #app a{max-width:100%!important;}'
    ].join('\n');
    style.textContent = W.__rkDesktopMode ? '' : PHONE_CSS;

    function first(selectors) {
      for (var i = 0; i < selectors.length; i++) {
        try {
          var found = D.querySelector(selectors[i]);
          if (found) return found;
        } catch (ignore) { }
      }
      return null;
    }

    function directChildOf(node, ancestor) {
      if (!node || !ancestor) return null;
      var current = node;
      while (current && current.parentElement && current.parentElement !== ancestor) {
        current = current.parentElement;
      }
      return current && current.parentElement === ancestor ? current : null;
    }

    function resolvePanes() {
      var side = first(['#side', '#pane-side', '[data-testid="chat-list"]']);
      var main = first(['#main', '[data-testid="conversation-panel-wrapper"]']);
      var root = (side && side.closest && side.closest('div.two')) ||
        (main && main.closest && main.closest('div.two')) ||
        first(['#app .app-wrapper-web .app.two', '#app .two', 'div.two']);
      if (!root) root = first(['#app .app-wrapper-web .app', '#app .app-wrapper-web', '#app']);
      if (root) root.classList.add('rk-phone-root');

      var sideSlot = directChildOf(side, root) || side;
      var mainSlot = directChildOf(main, root) || main;
      if (sideSlot) sideSlot.classList.add('rk-phone-side-slot');
      if (mainSlot) mainSlot.classList.add('rk-phone-main-slot');

      // v1.9: never tag the chat-list header itself as the rail. Only a root-level element that
      // holds the icon buttons (and neither pane) may be hidden as the desktop navigation rail.
      var NAV_BTN = 'button,[role="button"],[aria-label]';
      var rail = null;
      var tagged = D.querySelector('.rk-phone-nav-rail');
      if (tagged && root && root.contains(tagged) && tagged !== sideSlot && tagged !== mainSlot) rail = tagged;
      var railAnchor = first(['header[data-testid="chatlist-header"]']);
      var viaAnchor = directChildOf(railAnchor, root);
      if (!rail) {
        var pool = [];
        if (viaAnchor) pool.push(viaAnchor);
        var kids = (root && root.children) || [];
        for (var c = 0; c < kids.length; c++) pool.push(kids[c]);
        for (var p = 0; p < pool.length && !rail; p++) {
          var cand = pool[p];
          if (!cand || cand === sideSlot || cand === mainSlot) continue;
          if (cand.contains(sideSlot) || cand.contains(mainSlot)) continue;
          var btnCount = cand.querySelectorAll ? cand.querySelectorAll(NAV_BTN).length : 0;
          var box = cand.getBoundingClientRect ? cand.getBoundingClientRect() : null;
          if (btnCount >= 4 && box && box.width > 0 && box.width <= 150) rail = cand;
        }
      }
      if (!rail && viaAnchor && viaAnchor !== sideSlot && viaAnchor !== mainSlot &&
          !viaAnchor.contains(sideSlot) && !viaAnchor.contains(mainSlot)) rail = viaAnchor;
      if (rail && rail !== sideSlot && rail !== mainSlot) rail.classList.add('rk-phone-nav-rail');

      return { root: root, side: side, main: main, sideSlot: sideSlot, mainSlot: mainSlot, rail: rail };
    }

    function hasLoginQrScreen() {
      var side = first(['#side', '#pane-side', '[data-testid="chat-list"]']);
      if (side) return false;
      var main = first(['#main', '[data-testid="conversation-panel-wrapper"]']);
      var qr = first([
        'canvas', 'img[alt*="QR"]', 'svg[aria-label*="QR"]',
        '[data-testid*="qrcode"]', '[data-testid*="qr-code"]',
        '[aria-label*="QR"]', '[data-ref] canvas'
      ]);
      if (!qr) return false;
      var text = ((D.body && D.body.innerText) || '').toLowerCase().slice(0, 9000);
      var loginWords = /scan the qr code|scan this qr code|scan qr code|link with phone number|link a device|pindai kode qr|pindai qr|tautkan perangkat|pindai untuk menautkan|scan to link/.test(text);
      return !main || loginWords;
    }

    function resetPhoneLayoutClasses() {
      try {
        clearFitDrawers();
        markRightDrawer(null);
        D.documentElement.classList.remove('rk-phone-active');
        var stale = D.querySelectorAll('.rk-phone-root,.rk-phone-side-slot,.rk-phone-main-slot,.rk-phone-nav-rail,.rk-login-fit,.rk-login-too-wide,.rk-login-stack,.rk-login-qr-code,.rk-login-qr-wrap');
        for (var i = 0; i < stale.length; i++) {
          stale[i].classList.remove('rk-phone-root','rk-phone-side-slot','rk-phone-main-slot','rk-phone-nav-rail','rk-phone-list','rk-phone-chat','rk-phone-panel','rk-phone-navpanel','rk-login-fit','rk-login-too-wide','rk-login-stack','rk-login-qr-code','rk-login-qr-wrap');
        }
      } catch (ignore) { }
    }

    function clearLoginQrLayout() {
      try {
        D.documentElement.classList.remove('rk-login-active');
        D.documentElement.style.removeProperty('--rk-login-qr-size');
        if (D.body) D.body.classList.remove('rk-login-body');
        var nodes = D.querySelectorAll('.rk-login-fit,.rk-login-too-wide,.rk-login-stack,.rk-login-qr-code,.rk-login-qr-wrap');
        for (var i = 0; i < nodes.length; i++) nodes[i].classList.remove('rk-login-fit','rk-login-too-wide','rk-login-stack','rk-login-qr-code','rk-login-qr-wrap');
      } catch (ignore) { }
    }

    function prepareLoginQrScreen() {
      try {
        resetPhoneLayoutClasses();
        D.documentElement.classList.add('rk-login-active');
        var width = Math.min(W.innerWidth || D.documentElement.clientWidth || 360,
          D.documentElement.clientWidth || W.innerWidth || 360);
        var height = W.innerHeight || D.documentElement.clientHeight || 640;
        var qrSize = Math.round(Math.max(150, Math.min(264, width * 0.72, height * 0.44)));
        D.documentElement.style.setProperty('--rk-login-qr-size', qrSize + 'px');

        var app = first(['#app']) || D.body;
        var qr = first([
          'canvas', 'img[alt*="QR"]', 'svg[aria-label*="QR"]',
          '[data-testid*="qrcode"]', '[data-testid*="qr-code"]',
          '[aria-label*="QR"]', '[data-ref] canvas'
        ]);
        if (qr) {
          qr.classList.add('rk-login-qr-code');
          var node = qr;
          var depth = 0;
          var stacked = 0;
          while (node && node !== app && depth < 10) {
            if (node.classList) node.classList.add('rk-login-fit');
            var rect = node.getBoundingClientRect ? node.getBoundingClientRect() : null;
            if (rect && rect.width > width + 6 && node.classList) node.classList.add('rk-login-too-wide');
            if (node !== qr && node.tagName === 'DIV' && node.classList) {
              node.classList.add('rk-login-qr-wrap');
              var computed = W.getComputedStyle ? W.getComputedStyle(node) : null;
              if (computed && computed.display.indexOf('flex') === 0 && computed.flexDirection === 'row' && rect && rect.width > width * 0.8 && stacked < 2) {
                node.classList.add('rk-login-stack');
                stacked++;
              }
            }
            node = node.parentElement;
            depth++;
          }
        }
        if (D.body) D.body.classList.add('rk-login-body');
      } catch (ignore) { }
    }

    /* v2.1: the chat that is open right now (its name), and whether a conversation is really shown. */
    var lastConversationKey = null;
    function conversationTitle(main) {
      if (!main) return '';
      var el = main.querySelector('header [data-testid="conversation-info-header-chat-title"]') ||
        main.querySelector('header span[title]') || main.querySelector('header [dir="auto"]');
      return el ? String(el.getAttribute('title') || el.textContent || '').trim() : '';
    }
    function hasConversation(main) {
      if (!main) return false;
      if (main.querySelector('footer [contenteditable="true"],[data-testid="conversation-compose-box-input"]')) return true;
      return !!main.querySelector('header') && conversationTitle(main) !== '';
    }

    /* v2.1: WhatsApp's profile / contact-info drawer (the panel that opens from the chat header). */
    function findRightDrawer() {
      var explicit = first([
        '[data-testid="drawer-right"]', '[data-testid="contact-info-drawer"]',
        '[data-testid="group-info-drawer"]', '[data-testid*="contact-info"]',
        '[data-testid*="group-info"]', '[data-testid*="profile-drawer"]',
        '[data-testid*="contact-details"]', '[data-testid*="profile-info"]'
      ]);
      if (explicit && explicit.getBoundingClientRect) {
        var er = explicit.getBoundingClientRect();
        if (er.width > 0 && er.height > 0) return explicit;
      }
      // WhatsApp sometimes changes the test id. Match only a large visible panel that names contact/profile info.
      var candidates = D.querySelectorAll('#main [role="dialog"],#main [data-testid*="contact-info"],#main [data-testid*="group-info"],#main [data-testid*="profile"],#main [aria-label*="contact" i],#main [aria-label*="profile" i],#main>div>div');
      var width = W.innerWidth || D.documentElement.clientWidth || 360;
      var height = W.innerHeight || D.documentElement.clientHeight || 640;
      for (var i = 0; i < candidates.length; i++) {
        var c = candidates[i];
        if (!c || c === D.getElementById('main') || (c.classList && c.classList.contains('rk-phone-drawer-right'))) continue;
        var r = c.getBoundingClientRect();
        if (r.width < width * 0.48 || r.height < height * 0.60 || r.width > width * 1.15) continue;
        var head = c.querySelector('header');
        var label = ((c.getAttribute('aria-label') || '') + ' ' + (c.getAttribute('data-testid') || '') + ' ' +
          (head ? head.innerText : '') + ' ' + (c.innerText || '').slice(0, 360)).toLowerCase();
        if (/contact info|info kontak|informasi kontak|group info|info grup|profil kontak|profile info|informasi profil|\bprofile\b|\bprofil\b/.test(label)) return c;
      }
      return null;
    }
    function closeRightDrawer() {
      var drawer = findRightDrawer();
      if (!drawer) return false;
      var icon = drawer.querySelector('[data-icon="x"],[data-icon="x-alt"],[data-icon="back"],[data-icon="x-viewer"]');
      var btn = icon ? (icon.closest('button,[role="button"]') || icon.parentElement) : null;
      if (!btn) btn = drawer.querySelector('button[aria-label*="Tutup"],button[aria-label*="Close"],button[aria-label*="Kembali"],button[aria-label*="Back"]');
      if (!btn) return false;
      fireClick(btn);
      return true;
    }
    function markRightDrawer(drawer) {
      var old = D.querySelectorAll('.rk-phone-drawer-right');
      for (var i = 0; i < old.length; i++) if (old[i] !== drawer) old[i].classList.remove('rk-phone-drawer-right');
      if (!drawer) return;
      drawer.classList.add('rk-phone-drawer-right');
      try {
        // A transparent drawer would show the dark page behind it; give it the normal panel colour instead.
        var bg = W.getComputedStyle(drawer).backgroundColor;
        if (!bg || bg === 'transparent' || /^rgba\(0,\s*0,\s*0,\s*0\)$/.test(bg)) {
          drawer.style.setProperty('background-color', '#ffffff', 'important');
        }
      } catch (ignore) { }
    }

    /* v2.1: left-column panels (Panggilan, Status, Saluran, Komunitas, Diarsipkan, ...) were drawn at about
       40% of the width and shifted. Stretch any layered panel in the root to the full phone width. */
    function fitLeftDrawers(panes) {
      var root = panes.root;
      if (!root) return;
      var rootRect = root.getBoundingClientRect();
      if (!rootRect.width) return;
      var viewH = W.innerHeight || D.documentElement.clientHeight || 640;
      var chatList = D.getElementById('pane-side');
      walk(root, 0);

      function walk(el, depth) {
        if (depth > 4) return;
        var kids = el.children || [];
        for (var i = 0; i < kids.length; i++) {
          var c = kids[i];
          if (c === panes.main || c === panes.mainSlot || (panes.main && c.contains(panes.main))) continue;
          if (c === chatList || c.hasAttribute('data-rk-fit')) continue;
          var cs = W.getComputedStyle(c);
          var r = c.getBoundingClientRect();
          var layered = cs.position === 'absolute' || cs.position === 'fixed';
          var visible = cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity || '1') > 0.01;
          if (layered && visible && r.height > viewH * 0.6 && r.width > 80 &&
              r.width < rootRect.width - 4 && r.right > 0 && r.left < rootRect.width) {
            fillWidth(c);
            continue;
          }
          walk(c, depth + 1);
        }
      }
      function fillWidth(c) {
        c.setAttribute('data-rk-fit', '1');
        // Percent widths inherit the narrow (desktop sidebar) parent; use the actual phone viewport instead.
        c.style.setProperty('position', 'fixed', 'important');
        c.style.setProperty('top', '0px', 'important');
        c.style.setProperty('bottom', '0px', 'important');
        c.style.setProperty('left', '0px', 'important');
        c.style.setProperty('right', 'auto', 'important');
        c.style.setProperty('width', '100vw', 'important');
        c.style.setProperty('max-width', '100vw', 'important');
        c.style.setProperty('min-width', '100vw', 'important');
        c.style.setProperty('height', '100%', 'important');
        c.style.setProperty('max-height', '100%', 'important');
        c.style.setProperty('transform', 'none', 'important');
        c.style.setProperty('z-index', '2147482500', 'important');
        c.style.setProperty('box-sizing', 'border-box', 'important');
        c.style.setProperty('overflow-x', 'hidden', 'important');
        c.style.setProperty('overflow-y', 'auto', 'important');
      }
    }
    function clearFitDrawers() {
      try {
        var nodes = D.querySelectorAll('[data-rk-fit]');
        for (var i = 0; i < nodes.length; i++) {
          var props = ['position', 'top', 'bottom', 'left', 'right', 'width', 'height', 'max-width', 'min-width', 'max-height', 'transform', 'z-index', 'box-sizing', 'overflow-x', 'overflow-y'];
          for (var p = 0; p < props.length; p++) nodes[i].style.removeProperty(props[p]);
          nodes[i].removeAttribute('data-rk-fit');
        }
      } catch (ignore) { }
    }

    function isNarrowPhone() {
      var widths = [W.innerWidth, D.documentElement && D.documentElement.clientWidth,
        W.screen && W.screen.width].filter(function (n) { return n && n > 0; });
      var heights = [W.innerHeight, W.screen && W.screen.height].filter(function (n) { return n && n > 0; });
      return widths.length > 0 && Math.min.apply(Math, widths) <= 760 &&
        (heights.length === 0 || Math.max.apply(Math, heights) >= Math.min.apply(Math, widths));
    }

    function inferMode(panes) {
      var main = panes.main;
      if (!main) return 'list';
      var text = (main.innerText || '').toLowerCase().slice(0, 3200);
      var special = !!main.querySelector('[data-testid*="status-v3-viewer"],[data-testid*="media-viewer"],[data-testid*="status-viewer"],[data-testid*="settings-view"],[data-testid*="settings-main"],[data-testid*="profile-view"],[data-testid*="profile-info"],[role="dialog"]') || !!findRightDrawer();
      if (special) return 'panel';

      var composer = !!main.querySelector('footer [contenteditable="true"],[data-testid="conversation-compose-box-input"],[data-tab="10"][contenteditable="true"]');
      var header = !!main.querySelector('[data-testid="conversation-panel-header"],[data-testid="conversation-header"],[data-testid="conversation-info-header"],header');
      var intro = !!main.querySelector('#intro,[data-testid="intro"],[data-testid*="intro"]') ||
        /select a chat to start messaging|pilih chat untuk mulai mengirim pesan|start a conversation|mulai percakapan/.test(text);
      var empty = !(main.innerText || '').trim() && !main.querySelector('video,canvas');
      if (composer) return 'chat';
      if (intro || empty) return 'list';
      if (header) return 'chat';
      return 'list';
    }


    var lightStyleId = 'rk-phone-light-mode';
    W.__rkSetLightMode = function (enabled) {
      try {
        var light = D.getElementById(lightStyleId);
        if (enabled) {
          if (!light) { light = D.createElement('style'); light.id = lightStyleId; (D.head || D.documentElement).appendChild(light); }
          light.textContent = [
            '*,*::before,*::after{scroll-behavior:auto!important;animation-duration:0.001ms!important;animation-iteration-count:1!important;transition-duration:0.001ms!important;}',
            '#app *,#main *,#side *{text-shadow:none!important;filter:none!important;backdrop-filter:none!important;box-shadow:none!important;}',
            'video{will-change:auto!important;}'
          ].join('\n');
        } else if (light) light.remove();
        return true;
      } catch (e) { return false; }
    };

    function notifyNative(mode) {
      if (mode === lastNotifiedMode) return;
      lastNotifiedMode = mode;
      try {
        if (W.WACloningApp && typeof W.WACloningApp.onPhoneScreenChanged === 'function') {
          W.WACloningApp.onPhoneScreenChanged(mode);
        }
      } catch (ignore) { }
    }

    function syncMode() {
      try {
        // The feature screen shows WhatsApp's own desktop layout; the phone adapter must not touch it.
        if (W.__rkDesktopMode) {
          resetPhoneLayoutClasses();
          notifyNative('desktop');
          return;
        }
        if (hasLoginQrScreen()) {
          prepareLoginQrScreen();
          notifyNative('login');
          return;
        }
        clearLoginQrLayout();
        var panes = resolvePanes();
        var root = panes.root;
        if (!root) return;
        if (!isNarrowPhone() || !panes.sideSlot || !panes.mainSlot) {
          root.classList.remove('rk-phone-list', 'rk-phone-chat', 'rk-phone-panel', 'rk-phone-navpanel');
          D.documentElement.classList.remove('rk-phone-active');
          markRightDrawer(null);
          clearFitDrawers();
          notifyNative('list');
          return;
        }
        D.documentElement.classList.add('rk-phone-active');
        var drawer = findRightDrawer();
        markRightDrawer(drawer);
        var inferredMode = inferMode(panes);
        // v2.1: a conversation that WhatsApp opened by itself (an archived chat, a search result) must show,
        // even while an older "list" lock is still set. A chat that stays the same keeps the lock.
        var openKey = hasConversation(panes.main) ? conversationTitle(panes.main) : '';
        if (W.__rkForceMode !== 'navpanel' && openKey && openKey !== lastConversationKey) W.__rkForceMode = 'chat';
        lastConversationKey = openKey || null;
        var mode;
        if (drawer) {
          // Contact/group info opens from a chat and belongs to the main pane.
          mode = 'panel';
        } else if (W.__rkForceMode === 'list') {
          mode = 'list';
        } else if (W.__rkForceMode === 'navpanel') {
          // Calls, Status, Channels, Communities, Settings and Archived live in the left WhatsApp pane.
          mode = 'navpanel';
        } else if (W.__rkForceMode === 'panel') {
          mode = 'panel';
        } else if (W.__rkForceMode === 'chat') {
          mode = inferredMode === 'panel' ? 'panel' : 'chat';
        } else {
          mode = inferredMode;
        }
        if (mode === 'chat' && !hasConversation(panes.main)) mode = 'list';
        if (mode !== 'chat' && mode !== 'panel' && mode !== 'navpanel') mode = 'list';
        root.classList.toggle('rk-phone-list', mode === 'list');
        root.classList.toggle('rk-phone-chat', mode === 'chat');
        root.classList.toggle('rk-phone-panel', mode === 'panel');
        root.classList.toggle('rk-phone-navpanel', mode === 'navpanel');
        // Fit layered list/section drawers only when that pane is actually visible; avoid scanning chat DOM on typing.
        if (mode === 'list' || mode === 'navpanel') fitLeftDrawers(panes);
        // Keep the Java UI in detail/back mode for all full-screen secondary sections.
        notifyNative(mode === 'navpanel' ? 'panel' : mode);
      } catch (ignore) { }
    }


    /* ===== v2.0: bottom bar -> original WhatsApp Web sidebar =====
       Why v1.9 could still say "menu Panggilan tidak ditemukan":
       - the hidden rail used display:none, so it had no geometry and the order fallback could not work;
       - rows/list items anywhere were excluded, which also skipped a rail built from list items;
       - the order fallback skipped the Chats icon because its unread badge ("62") is text, which shifted every
         index by one (Calls would have resolved to Status);
       - the click was sent to a wrapper (data-navbar-item) instead of the real button inside it.
       Strategy (most to least reliable):
       1. aria-label / title / <title> text, in English and Indonesian (exact or "starts with").
       2. data-icon on the control OR on any child.
       3. data-testid fragments.
       4. Geometry: the left-most column of >=5 icon-only controls, sorted top to bottom
          (Chats, Calls, Status, Channels, Communities). Class names are never used. */
    var NAV_SPECS = {
      // deny: words that turn a prefix match into a different control ("Chat baru" = new chat, "Panggilan baru" = new call).
      chats:       { terms: ['chats', 'chat', 'pesan', 'obrolan'], icons: ['chats', 'chat'], deny: /\b(baru|new|archived|diarsipkan)\b/ },
      archive:     { terms: ['archived', 'archive', 'diarsipkan', 'arsip'], icons: ['archived', 'archive'], deny: /\b(search|cari|hapus|clear)\b/ },
      calls:       { terms: ['calls', 'call', 'panggilan', 'telepon'], icons: ['calls', 'call', 'phone'], deny: /\b(baru|new|link|tautan|video|suara|voice)\b/ },
      status:      { terms: ['status', 'updates', 'pembaruan'], icons: ['status', 'updates'], deny: /\b(saya|my|baru|new)\b/ },
      channels:    { terms: ['channels', 'channel', 'saluran'], icons: ['channels', 'channel', 'newsletter'], deny: /\b(baru|new|find|temukan|cari)\b/ },
      communities: { terms: ['communities', 'community', 'komunitas'], icons: ['communities', 'community'], deny: /\b(baru|new)\b/ },
      business:    { terms: ['business tools', 'alat bisnis', 'catalog', 'katalog'], icons: ['business', 'catalog', 'shop', 'storefront'] },
      advertise:   { terms: ['advertise', 'ads', 'iklan'], icons: ['ads', 'advertise', 'megaphone', 'campaign'] },
      media:       { terms: ['media', 'links', 'documents', 'tautan', 'dokumen'], icons: ['media', 'gallery'] },
      settings:    { terms: ['settings', 'pengaturan', 'setelan'], icons: ['settings', 'gear'] },
      profile:     { terms: ['profile', 'profil'], icons: ['profile', 'avatar', 'default-user'] }
    };
    var NAV_ORDER = ['chats', 'calls', 'status', 'channels', 'communities'];
    var NAV_CLICKABLE = 'button,[role="button"],[role="tab"],a,[data-navbar-item]';
    var NAV_CANDIDATES = NAV_CLICKABLE + ',[tabindex],[aria-label],[title],[data-icon],[data-testid]';
    // Only the chat list, the open conversation, the composer and dialogs are off limits.
    var NAV_CONTENT = '#pane-side,#main,footer,[role="dialog"],[data-testid="chat-list"],.rk-native-bottom-nav';

    function lc(v) { return (v === null || v === undefined ? '' : String(v)).toLowerCase().replace(/\s+/g, ' ').trim(); }

    function rectOf(el) {
      try { return el.getBoundingClientRect(); } catch (e) { return { left: 0, top: 0, width: 0, height: 0 }; }
    }

    function isNavClickable(el) {
      try { return !!(el.matches && el.matches(NAV_CLICKABLE)); } catch (e) { return false; }
    }

    // Rail buttons are small. A wide element (a chat row, a panel) is never a rail icon.
    function isIconish(el) {
      var r = rectOf(el);
      if (r.width > 120 || r.height > 120) return false;
      var text = (el.textContent || '').replace(/\s+/g, ' ').trim();
      return text.length <= 40;
    }

    function isIconOnly(el) {
      if (!el.querySelector || !el.querySelector('svg,[data-icon],img')) return false;
      var text = (el.textContent || '').replace(/\s+/g, '');
      return text.length <= 4; // an unread badge such as "62" must not disqualify the Chats icon
    }

    function navLabels(el) {
      var out = [];
      function push(v) { v = lc(v); if (v && out.indexOf(v) < 0) out.push(v); }
      try {
        push(el.getAttribute('aria-label'));
        push(el.getAttribute('title'));
        var kids = el.querySelectorAll('[aria-label],[title],svg title,img[alt]');
        for (var i = 0; i < kids.length && i < 6; i++) {
          var k = kids[i];
          var tag = (k.tagName || '').toLowerCase();
          if (tag === 'title') push(k.textContent);
          else if (tag === 'img') push(k.getAttribute('alt'));
          else { push(k.getAttribute('aria-label')); push(k.getAttribute('title')); }
        }
        // Visible text only counts on real controls; a heading such as <h1>Chats</h1> inside a header must not match.
        var text = el.textContent || '';
        if (isNavClickable(el) && text.length <= 40) push(text);
      } catch (ignore) { }
      return out;
    }

    function navIcons(el) {
      var out = [];
      try {
        var own = el.getAttribute('data-icon');
        if (own) out.push(lc(own));
        var inner = el.querySelectorAll('[data-icon]');
        for (var i = 0; i < inner.length && i < 4; i++) out.push(lc(inner[i].getAttribute('data-icon')));
      } catch (ignore) { }
      return out;
    }

    function scoreNav(spec, el) {
      var labels = navLabels(el), icons = navIcons(el), score = 0, i, j;
      for (i = 0; i < labels.length; i++) {
        for (j = 0; j < spec.terms.length; j++) {
          var l = labels[i], t = spec.terms[j];
          if (l === t) score = Math.max(score, 100);
          else if (l.indexOf(t) === 0 && !/[a-z0-9]/.test(l.charAt(t.length)) &&
                   !(spec.deny && spec.deny.test(l.slice(t.length)))) score = Math.max(score, 85);
        }
      }
      for (i = 0; i < icons.length; i++) {
        var lead = icons[i].split(/[^a-z0-9]+/)[0];
        if (spec.icons.indexOf(icons[i]) >= 0 || spec.icons.indexOf(lead) >= 0) score = Math.max(score, 75);
      }
      if (!score && isNavClickable(el)) {
        var tid = lc(el.getAttribute('data-testid'));
        if (tid) {
          for (j = 0; j < spec.terms.length; j++) {
            if (tid.indexOf(spec.terms[j].replace(/ /g, '-')) >= 0) { score = 40; break; }
          }
        }
      }
      if (score) {
        if (el.hasAttribute && el.hasAttribute('data-navbar-item')) score += 30;
        else if (el.closest && el.closest('nav,[role="navigation"],aside,[data-navbar-item]')) score += 15;
      }
      return score;
    }

    function collectNavCandidates(scope) {
      var list = [], nodes;
      try { nodes = scope.querySelectorAll(NAV_CANDIDATES); } catch (e) { return list; }
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (!el || el.nodeType !== 1) continue;
        if (el.closest && el.closest(NAV_CONTENT)) continue;
        var click = (el.closest && el.closest(NAV_CLICKABLE)) || el;
        if (click.closest && click.closest(NAV_CONTENT)) continue;
        if (!isNavClickable(click) && !click.hasAttribute('tabindex') &&
            !click.hasAttribute('aria-label') && !click.hasAttribute('data-icon')) continue;
        if (list.indexOf(click) >= 0) continue;
        if (click.childElementCount > 10) continue;
        if (!isIconish(click)) continue;
        list.push(click);
      }
      return list;
    }

    // Geometry fallback: the left-most column that holds at least 5 icon-only controls is the desktop rail.
    function findNavByGeometry(name) {
      var idx = NAV_ORDER.indexOf(name);
      if (idx < 0) return null;
      var all = collectNavCandidates(D), pool = [], i, j;
      for (i = 0; i < all.length; i++) {
        var el = all[i], r = rectOf(el);
        if (!isIconOnly(el)) continue;
        if (r.width < 14 || r.height < 14 || r.width > 100 || r.height > 100) continue;
        pool.push({ el: el, cx: r.left + r.width / 2, cy: r.top + r.height / 2 });
      }
      // A wrapper and the button inside it both qualify; keep the innermost one so each icon counts once.
      var inner = [];
      for (i = 0; i < pool.length; i++) {
        var hasChild = false;
        for (j = 0; j < pool.length; j++) {
          if (i !== j && pool[i].el.contains(pool[j].el)) { hasChild = true; break; }
        }
        if (!hasChild) inner.push(pool[i]);
      }
      var columns = {};
      for (i = 0; i < inner.length; i++) {
        var key = Math.round(inner[i].cx / 12);
        (columns[key] = columns[key] || []).push(inner[i]);
      }
      var bestKey = null;
      for (var k in columns) {
        if (!Object.prototype.hasOwnProperty.call(columns, k)) continue;
        if (columns[k].length < 5) continue;
        if (bestKey === null || Number(k) < Number(bestKey)) bestKey = k;
      }
      if (bestKey === null) return null;
      var col = columns[bestKey].slice().sort(function (a, b) { return a.cy - b.cy; });
      return col[idx] ? col[idx].el : null;
    }

    // Last resort: group icon controls by shared ancestor (same idea as v1.9, with the badge bug fixed).
    function findNavByOrder(name) {
      var idx = NAV_ORDER.indexOf(name);
      if (idx < 0) return null;
      var all = collectNavCandidates(D), icons = [], i;
      for (i = 0; i < all.length; i++) {
        if (isIconOnly(all[i])) icons.push(all[i]);
      }
      if (icons.length < 5) return null;
      var group = null;
      for (i = 0; i < icons.length && !group; i++) {
        var node = icons[i].parentElement, depth = 0;
        while (node && depth < 6 && !group) {
          var inside = [];
          for (var k = 0; k < icons.length; k++) if (node.contains(icons[k])) inside.push(icons[k]);
          if (inside.length >= 5 && inside.length <= 14) group = inside;
          node = node.parentElement; depth++;
        }
      }
      return group && group[idx] ? group[idx] : null;
    }

    function findNavTarget(name, spec) {
      var scopes = [], rail = D.querySelector('.rk-phone-nav-rail');
      if (rail) scopes.push(rail);
      scopes.push(D);
      for (var s = 0; s < scopes.length; s++) {
        var list = collectNavCandidates(scopes[s]), best = null, bestScore = 0;
        for (var i = 0; i < list.length; i++) {
          var sc = scoreNav(spec, list[i]);
          if (sc > bestScore) { best = list[i]; bestScore = sc; }
        }
        if (best && bestScore >= 70) return best;
      }
      return findNavByGeometry(name) || findNavByOrder(name);
    }

    // Click the real button inside a wrapper, and start the pointer events on the innermost node,
    // exactly where a finger would land. Events bubble, so whichever ancestor owns the handler still runs.
    function fireClick(el) {
      var target = el, leaf, guard = 0;
      try {
        var inner = el.querySelector('button,[role="button"],[role="tab"],a[href]');
        if (inner) target = inner;
      } catch (ignore) { }
      leaf = target;
      while (leaf.children && leaf.children.length === 1 && guard < 6) { leaf = leaf.children[0]; guard++; }
      try { if (target.focus) target.focus({ preventScroll: true }); } catch (ignore) { }
      var seq = ['pointerdown', 'mousedown', 'pointerup', 'mouseup'];
      for (var i = 0; i < seq.length; i++) {
        try {
          var Ctor = (seq[i].indexOf('pointer') === 0 && W.PointerEvent) ? W.PointerEvent : W.MouseEvent;
          leaf.dispatchEvent(new Ctor(seq[i], { bubbles: true, cancelable: true, view: W, button: 0 }));
        } catch (ignore) { }
      }
      if (typeof target.click === 'function') target.click();
      else target.dispatchEvent(new W.MouseEvent('click', { bubbles: true, cancelable: true, view: W, button: 0 }));
    }

    W.__rkNavSeq = 0;
    W.__rkNativeNavigate = function (name) {
      try {
        if (W.__rkDesktopMode) return false;
        var spec = NAV_SPECS[name];
        if (!spec) return false;
        var target = findNavTarget(name, spec);
        if (!target) return false;
        fireClick(target);
        // Chat uses the list pane. Secondary items start in full-screen section mode; profile/info drawers can promote to panel.
        if (name === 'chats') {
          W.__rkForceMode = 'list';
          W.__rkArchiveContext = false;
          W.__rkFromArchiveChat = false;
        } else {
          W.__rkForceMode = 'navpanel';
          W.__rkArchiveContext = name === 'archive';
          W.__rkFromArchiveChat = false;
        }
        var seq = ++W.__rkNavSeq;
        syncMode();
        scheduleSync();
        W.setTimeout(function () {
          try {
            if (seq !== W.__rkNavSeq) return;
            var p = resolvePanes();
            if (findRightDrawer() || (p.main && inferMode(p) === 'panel')) {
              W.__rkForceMode = 'panel';
              syncMode();
            } else if (name !== 'chats') {
              W.__rkForceMode = 'navpanel';
              syncMode();
            }
          } catch (ignore) { }
        }, 520);
        return true;
      } catch (e) { return false; }
    };

    // Used by the app to show what the page really exposes when a menu cannot be found.
    W.__rkNavDebug = function () {
      try {
        var list = collectNavCandidates(D), out = [];
        for (var i = 0; i < list.length && out.length < 40; i++) {
          var el = list[i], r = rectOf(el);
          out.push((el.tagName || '?').toLowerCase() +
            ' label=' + (el.getAttribute('aria-label') || el.getAttribute('title') || '-') +
            ' icon=' + (navIcons(el).join(',') || '-') +
            ' testid=' + (el.getAttribute('data-testid') || '-') +
            ' box=' + Math.round(r.left) + ',' + Math.round(r.top) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height));
        }
        return 'versi=2.0 kandidat=' + list.length + ' rail=' + (D.querySelector('.rk-phone-nav-rail') ? 'ya' : 'tidak') +
          ' lebar=' + W.innerWidth + ' desktop=' + (W.__rkDesktopMode ? 'ya' : 'tidak') + '\n' + out.join('\n');
      } catch (e) { return 'debug gagal: ' + e; }
    };

    /* ===== v2.0: call-support diagnostics =====
       WhatsApp itself greys out the call buttons when it decides the browser cannot place calls.
       This report shows what the WebView exposes so the real cause can be read instead of guessed. */
    W.__rkDiag = {};
    W.__rkCallDiagStart = function () {
      var R = W.__rkDiag = { mic: '?', cam: '?', devices: '?' };
      try {
        if (N.permissions && N.permissions.query) {
          ['microphone', 'camera'].forEach(function (perm) {
            var slot = perm === 'microphone' ? 'mic' : 'cam';
            try {
              N.permissions.query({ name: perm }).then(
                function (s) { R[slot] = s.state; },
                function (e) { R[slot] = 'error:' + (e && e.name); });
            } catch (e) { R[slot] = 'error'; }
          });
        } else { R.mic = R.cam = 'tidak ada Permissions API'; }
      } catch (ignore) { }
      try {
        if (N.mediaDevices && N.mediaDevices.enumerateDevices) {
          N.mediaDevices.enumerateDevices().then(function (list) {
            var c = { audioinput: 0, videoinput: 0, audiooutput: 0 };
            for (var i = 0; i < list.length; i++) if (c[list[i].kind] !== undefined) c[list[i].kind]++;
            R.devices = 'mikrofon=' + c.audioinput + ' kamera=' + c.videoinput + ' speaker=' + c.audiooutput;
          }, function (e) { R.devices = 'error:' + (e && e.name); });
        } else { R.devices = 'navigator.mediaDevices tidak ada'; }
      } catch (e) { R.devices = 'error'; }
      return 'started';
    };

    function callButtonInfo() {
      var out = [];
      try {
        var scope = D.querySelector('#main') || D;
        var nodes = scope.querySelectorAll('header button,header [role="button"],header [aria-label],header [data-icon]');
        var seen = [];
        for (var i = 0; i < nodes.length && out.length < 8; i++) {
          var el = (nodes[i].closest && nodes[i].closest('button,[role="button"]')) || nodes[i];
          if (seen.indexOf(el) >= 0) continue;
          seen.push(el);
          var meta = lc(el.getAttribute('aria-label') + ' ' + el.getAttribute('title') + ' ' + navIcons(el).join(' '));
          if (!/(video|voice|call|phone|telepon|panggil)/.test(meta)) continue;
          var cs = W.getComputedStyle ? W.getComputedStyle(el) : null;
          out.push('  - ' + (el.tagName || '?').toLowerCase() +
            ' label=' + (el.getAttribute('aria-label') || '-') +
            ' title=' + (el.getAttribute('title') || '-') +
            ' icon=' + (navIcons(el).join(',') || '-') +
            ' disabled=' + (el.disabled ? 'ya' : 'tidak') +
            ' aria-disabled=' + (el.getAttribute('aria-disabled') || '-') +
            ' opacity=' + (cs ? cs.opacity : '?') +
            ' pointer-events=' + (cs ? cs.pointerEvents : '?'));
        }
      } catch (e) { out.push('  gagal membaca tombol: ' + e); }
      return out.length ? out.join('\n') : '  (tidak ada tombol telepon/video di header chat yang terbuka. Buka sebuah chat dahulu.)';
    }

    W.__rkCallDiagReport = function () {
      try {
        var uad = N.userAgentData, lines = [], R = W.__rkDiag || {};
        lines.push('UA: ' + N.userAgent);
        lines.push('platform: ' + N.platform + ' | userAgentData: ' + (uad ? ('mobile=' + uad.mobile + ' platform=' + uad.platform) : 'tidak ada'));
        lines.push('maxTouchPoints: ' + N.maxTouchPoints + ' | layar: ' + W.innerWidth + 'x' + W.innerHeight + ' @' + W.devicePixelRatio);
        lines.push('secureContext: ' + W.isSecureContext + ' | crossOriginIsolated: ' + W.crossOriginIsolated);
        lines.push('RTCPeerConnection: ' + (typeof W.RTCPeerConnection) +
          ' | getUserMedia: ' + (N.mediaDevices && typeof N.mediaDevices.getUserMedia) +
          ' | AudioWorklet: ' + (typeof W.AudioWorkletNode) +
          ' | WebAssembly: ' + (typeof W.WebAssembly) +
          ' | SharedArrayBuffer: ' + (typeof W.SharedArrayBuffer));
        lines.push('Insertable streams: ' + ((W.RTCRtpSender && W.RTCRtpSender.prototype && typeof W.RTCRtpSender.prototype.createEncodedStreams) || 'tidak ada') +
          ' | RTCRtpScriptTransform: ' + (typeof W.RTCRtpScriptTransform));
        lines.push('Izin web: mikrofon=' + R.mic + ' kamera=' + R.cam + ' | perangkat: ' + R.devices);
        var calls = null;
        try { calls = findNavTarget('calls', NAV_SPECS.calls); } catch (ignore) { }
        lines.push('Menu Panggilan di sidebar: ' + (calls ? ('ketemu label=' + (calls.getAttribute('aria-label') || '-')) : 'TIDAK ketemu'));
        lines.push('Tombol telepon/video di header chat:\n' + callButtonInfo());
        return lines.join('\n');
      } catch (e) { return 'laporan gagal: ' + e; }
    };

    /* ===== v2.0: "Layar fitur" = the real desktop layout on a dedicated screen =====
       If a sidebar feature cannot be reached from the bottom bar, the app switches to this mode:
       the page gets a desktop-width viewport, the phone adapter steps aside, and WhatsApp shows the same
       left rail and header buttons as on a laptop. The app rotates to landscape and allows pinch zoom. */
    W.__rkEnterDesktop = function () {
      try {
        W.__rkDesktopMode = true;
        W.__rkForceMode = null;
        clearLoginQrLayout();
        resetPhoneLayoutClasses();
        style.textContent = '';
        setViewport(DESKTOP_VIEWPORT);
        notifyNative('desktop');
        W.setTimeout(function () { try { W.dispatchEvent(new W.Event('resize')); } catch (ignore) { } }, 300);
        W.setTimeout(function () { try { W.dispatchEvent(new W.Event('resize')); } catch (ignore) { } }, 900);
        return true;
      } catch (e) { return false; }
    };

    W.__rkExitDesktop = function () {
      try {
        W.__rkDesktopMode = false;
        style.textContent = PHONE_CSS;
        setViewport(PHONE_VIEWPORT);
        lastNotifiedMode = null;
        W.__rkForceMode = 'list';
        syncMode();
        scheduleSync();
        W.setTimeout(function () { try { W.dispatchEvent(new W.Event('resize')); } catch (ignore) { } }, 300);
        return true;
      } catch (e) { return false; }
    };

    /* v2.1: copyable layout report, used by the "Salin info layar" menu entry. */
    W.__rkLayoutDebug = function () {
      try {
        var panes = resolvePanes();
        var lines = [];
        function box(label, el) {
          if (!el || !el.getBoundingClientRect) return label + ': tidak ditemukan';
          var r = el.getBoundingClientRect();
          return label + ': x=' + Math.round(r.left) + ' lebar=' + Math.round(r.width) + ' tinggi=' + Math.round(r.height) +
            ' posisi=' + W.getComputedStyle(el).position + ' kelas=' + String(el.className).slice(0, 80);
        }
        var mode = !panes.root ? 'root tidak ditemukan' : (panes.root.classList.contains('rk-phone-list') ? 'list' :
          panes.root.classList.contains('rk-phone-chat') ? 'chat' : panes.root.classList.contains('rk-phone-panel') ? 'panel' : 'belum ditetapkan');
        lines.push('Layar: ' + W.innerWidth + 'x' + W.innerHeight + ' | mode HP aktif: ' +
          (D.documentElement.classList.contains('rk-phone-active') ? 'ya' : 'tidak'));
        lines.push('Mode sekarang: ' + mode);
        lines.push('Percakapan terbuka: ' + (hasConversation(panes.main) ? 'ya, "' + conversationTitle(panes.main) + '"' : 'tidak'));
        lines.push('Drawer kanan (info kontak): ' + (findRightDrawer() ? 'terbuka' : 'tidak ada'));
        lines.push(box('root', panes.root));
        lines.push(box('side', panes.side));
        lines.push(box('main', panes.main));
        lines.push('Drawer kiri yang dilebarkan: ' + D.querySelectorAll('[data-rk-fit]').length);
        var hits = D.querySelectorAll('[data-testid*="drawer"],[data-testid*="archiv"],[data-testid*="arsip"]');
        for (var i = 0; i < hits.length && i < 12; i++) {
          lines.push('  testid=' + hits[i].getAttribute('data-testid') + ' | ' + box('elemen', hits[i]).replace(/^elemen: /, ''));
        }
        return lines.join('\n');
      } catch (e) { return 'Gagal membuat laporan: ' + e; }
    };

    W.__rkPhoneSync = syncMode;
    W.__rkArchiveContext = false;
    W.__rkFromArchiveChat = false;
    function returnFromChat() {
      if (W.__rkFromArchiveChat && W.__rkArchiveContext) {
        W.__rkFromArchiveChat = false;
        W.__rkForceMode = 'navpanel';
      } else {
        W.__rkForceMode = 'list';
        W.__rkArchiveContext = false;
        W.__rkFromArchiveChat = false;
      }
      syncMode();
      scheduleSync();
      return true;
    }
    W.__rkReturnToList = function () {
      try {
        var panes = resolvePanes();
        if (!panes.root || !panes.sideSlot || !panes.mainSlot) return false;
        if (closeRightDrawer()) { scheduleSync(); return true; }
        if (W.__rkForceMode === 'chat' || panes.root.classList.contains('rk-phone-chat')) return returnFromChat();
        W.__rkForceMode = 'list';
        W.__rkArchiveContext = false;
        W.__rkFromArchiveChat = false;
        syncMode();
        scheduleSync();
        return true;
      } catch (ignore) { return false; }
    };
    W.__rkHandleBack = function () {
      try {
        var panes = resolvePanes();
        if (closeRightDrawer()) { scheduleSync(); return 'HANDLED'; }
        if (!panes.root) return 'EXIT';
        var mode = panes.root.classList.contains('rk-phone-chat') ? 'chat' :
          panes.root.classList.contains('rk-phone-navpanel') ? 'navpanel' :
          panes.root.classList.contains('rk-phone-panel') ? 'panel' : 'list';
        if (mode === 'chat') { returnFromChat(); return 'HANDLED'; }
        if (mode === 'panel' || mode === 'navpanel') {
          W.__rkForceMode = 'list';
          W.__rkArchiveContext = false;
          W.__rkFromArchiveChat = false;
          syncMode(); scheduleSync();
          return 'HANDLED';
        }
        return 'EXIT';
      } catch (ignore) { return 'EXIT'; }
    };

    function closestRow(target) {
      if (!target || !target.closest) return null;
      return target.closest(
        '#side [data-testid*="cell-frame-container"],#side [data-testid*="chat-list-item"],#side [data-testid*="cell-frame"],#side [role="gridcell"],#side [role="row"],#side [role="listitem"],' +
        '#pane-side [data-testid*="cell-frame-container"],#pane-side [role="gridcell"],#pane-side [role="row"],#pane-side [role="listitem"],' +
        '[data-testid="chat-list"] [data-testid*="cell-frame-container"],[data-testid="chat-list"] [role="gridcell"],[data-testid="chat-list"] [role="row"],[data-testid="chat-list"] [role="listitem"]'
      );
    }

    var syncTimer = null;
    var settleTimer = null;
    function scheduleSync() {
      if (syncTimer) W.clearTimeout(syncTimer);
      if (settleTimer) W.clearTimeout(settleTimer);
      // One quick pass + one settle pass, instead of repeatedly scanning the React tree.
      syncTimer = W.setTimeout(function () { syncTimer = null; syncMode(); }, 110);
      settleTimer = W.setTimeout(function () { settleTimer = null; syncMode(); }, 430);
    }

    if (!W.__rkPhoneClickHookV22) {
      W.__rkPhoneClickHookV22 = true;
      D.addEventListener('click', function (event) {
        try {
          if (W.__rkDesktopMode) return;
          var target = event.target;
          if (target && target.nodeType !== 1) target = target.parentElement;
          if (!target || !target.closest) return;
          var panes = resolvePanes();
          var semanticControl = target.closest('button,[role="button"],[role="tab"],a,[data-testid],[role="listitem"],[role="row"]') || target;
          var metaText = ((target.innerText || '') + ' ' + (semanticControl.innerText || '') + ' ' +
            (target.getAttribute('aria-label') || '') + ' ' + (semanticControl.getAttribute('aria-label') || '') + ' ' +
            (semanticControl.getAttribute('title') || '') + ' ' + (semanticControl.getAttribute('data-testid') || '')).toLowerCase();
          var archiveHeadingClick = !!target.closest('[data-testid*="archiv" i],[aria-label*="archiv" i],[data-testid*="archive" i],[aria-label*="archive" i]') ||
            /(^|\b)(archived|archive|diarsipkan|arsip)(\b|$)/i.test(metaText);
          archiveHeadingClick = archiveHeadingClick && !target.closest('input,textarea,[contenteditable="true"]');
          // The Archived list is a section, but rows inside it are real conversations.
          if (W.__rkForceMode === 'navpanel') {
            if (W.__rkArchiveContext && !archiveHeadingClick) {
              var archivedRow = target.closest('[data-testid*="cell-frame-container"],[data-testid*="chat-list-item"],[role="listitem"],[role="row"],[role="gridcell"]');
              if (archivedRow && !(target.closest('button,[role="button"]') && /archiv|archive|kembali|back|menu/i.test(metaText))) {
                W.__rkFromArchiveChat = true;
                W.__rkForceMode = 'chat';
                scheduleSync();
                return;
              }
            }
            scheduleSync();
            return;
          }
          if (archiveHeadingClick) {
            W.__rkArchiveContext = true;
            W.__rkFromArchiveChat = false;
            W.__rkForceMode = 'navpanel';
            scheduleSync();
            return;
          }

          // v2.1: a tapped row opens its chat wherever it sits (chat list, archived drawer, search results).
          if (!(panes.main && panes.main.contains(target)) && !target.closest('input,textarea,[contenteditable="true"],button')) {
            var anyRow = target.closest('[data-testid*="cell-frame-container"],[data-testid*="chat-list-item"],[role="listitem"],[role="row"],[role="gridcell"]');
            if (anyRow) {
              var anyId = ((anyRow.getAttribute('data-testid') || '') + ' ' + (anyRow.getAttribute('aria-label') || '')).toLowerCase();
              W.__rkForceMode = (anyId.indexOf('status-viewer') >= 0 || anyId.indexOf('settings-view') >= 0) ? 'panel' : 'chat';
              scheduleSync();
              return;
            }
          }

          if (panes.side && panes.side.contains(target)) {
            var blocked = target.closest('input,textarea,[contenteditable="true"],header,button,[role="button"],[data-testid*="search"],[data-testid*="filter"],[data-testid*="menu"],[aria-label*="Search"],[aria-label*="search"]');
            if (!blocked) {
              var row = closestRow(target);
              if (row) {
                var rowId = ((row.getAttribute('data-testid') || '') + ' ' + (row.getAttribute('aria-label') || '')).toLowerCase();
                W.__rkForceMode = (rowId.indexOf('status-viewer') >= 0 || rowId.indexOf('settings-view') >= 0) ? 'panel' : 'chat';
              } else {
                var rect = panes.side.getBoundingClientRect();
                var targetRect = target.getBoundingClientRect();
                var relativeY = targetRect.top + targetRect.height / 2 - rect.top;
                if (relativeY > Math.min(110, rect.height * 0.20)) W.__rkForceMode = 'chat';
              }
              scheduleSync();
            }
            return;
          }

          if (panes.main && panes.main.contains(target)) {
            var clickEl = target.closest('button,[role="button"],[aria-label],[data-testid],[data-icon]') || target;
            var meta = ((clickEl.getAttribute('aria-label') || '') + ' ' + (clickEl.getAttribute('title') || '') + ' ' +
              (clickEl.getAttribute('data-testid') || '') + ' ' + (clickEl.getAttribute('data-icon') || '') + ' ' +
              (clickEl.innerText || '')).toLowerCase();
            var inHeader = !!target.closest('#main header,[data-testid*="conversation-panel-header"],[data-testid*="conversation-header"]');
            if (inHeader && (/(^|\s)(back|go back|kembali|return|close chat)(\s|$)/.test(meta) ||
                meta.indexOf('data-icon back') >= 0 || meta.indexOf('back-button') >= 0)) {
              returnFromChat();
              return;
            }
            if (/contact info|info kontak|informasi kontak|group info|info grup|profil|profile/i.test(meta) &&
                /header|contact|info|profile|avatar/i.test(meta)) {
              W.__rkForceMode = 'panel';
              scheduleSync();
            }
          }
        } catch (ignore) { }
      }, true);
      W.addEventListener('resize', function () { scheduleSync(); });
      W.addEventListener('orientationchange', function () { scheduleSync(); });
    }

    if (W.__rkPhoneObserverV20 && W.__rkPhoneObserverV20.disconnect) { try { W.__rkPhoneObserverV20.disconnect(); } catch (ignore) { } }
    if (!W.__rkPhoneObserverV22 && D.body) {
      W.__rkPhoneObserverV22 = new MutationObserver(function (records) {
        // Message-by-message DOM churn should not trigger repeated full scans of the chat tree.
        var relevant = false;
        for (var i = 0; i < records.length; i++) {
          var target = records[i].target;
          if (target && target.nodeType !== 1) target = target.parentElement;
          if (target && target.closest && target.closest('#main [data-testid*="conversation-panel-messages"],#main .copyable-area,#main footer,#main [contenteditable="true"]')) continue;
          relevant = true;
          break;
        }
        if (relevant) scheduleSync();
      });
      W.__rkPhoneObserverV22.observe(D.body, { childList: true, subtree: true });
    }

    // Cleanup any classes from earlier versions before the first layout pass.
    try { resetPhoneLayoutClasses(); } catch (ignore) { }
    W.__rkForceMode = null;
    syncMode();
    return 'ok';
  } catch (ignore) {
    return 'ignored';
  }
})();
