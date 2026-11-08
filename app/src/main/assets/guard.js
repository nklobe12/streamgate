// Runs at document start in every frame, before the page's own scripts.
(function () {
  'use strict';
  if (window.__cineshield) return;
  Object.defineProperty(window, '__cineshield', { value: true });

  var SITES = ['cinejoy.pk', 'cinejoy.to'];
  var isTop = window === window.top;

  function hostOf(url) {
    try { return new URL(url, location.href).hostname.toLowerCase(); } catch (e) { return ''; }
  }
  function isSite(host) {
    return SITES.some(function (s) { return host === s || host.slice(-s.length - 1) === '.' + s; });
  }
  // A link may go to the site, or stay on the page it is already on.
  function linkAllowed(url) {
    if (!url || /^(#|javascript:)/i.test(url)) return true;
    var host = hostOf(url);
    return isSite(host) || host === location.hostname.toLowerCase();
  }

  function fakeWindow() {
    var noop = function () {};
    return {
      closed: true, close: noop, focus: noop, blur: noop, postMessage: noop,
      document: { write: noop, writeln: noop, close: noop, open: noop },
      location: { href: '', assign: noop, replace: noop }
    };
  }

  var blockedOpen = function (url) {
    if (url && isTop && isSite(hostOf(String(url)))) location.href = new URL(String(url), location.href).href;
    return fakeWindow();
  };
  try {
    Object.defineProperty(window, 'open', { value: blockedOpen, writable: false, configurable: false });
  } catch (e) { window.open = blockedOpen; }

  // Web notification and push prompts are a common ad channel.
  try { if (window.Notification) window.Notification.requestPermission = function () { return Promise.resolve('denied'); }; } catch (e) {}

  function guardClick(e) {
    var node = e.target;
    while (node && node.nodeType === 1 && node.tagName !== 'A') node = node.parentNode;
    if (!node || node.nodeType !== 1 || node.tagName !== 'A') return;
    var href = node.getAttribute('href');
    var newTab = (node.getAttribute('target') || '').toLowerCase() === '_blank';
    if (!linkAllowed(href)) {
      e.preventDefault();
      e.stopImmediatePropagation();
    } else if (newTab && isTop && href) {
      // Same-site "open in new tab" links open here instead.
      e.preventDefault();
      location.href = node.href;
    }
  }
  window.addEventListener('click', guardClick, true);

  // Scripts that build a link and click it with no element in the page.
  var nativeClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (!linkAllowed(this.getAttribute('href'))) return;
    return nativeClick.call(this);
  };
  var nativeSubmit = HTMLFormElement.prototype.submit;
  HTMLFormElement.prototype.submit = function () {
    if (!linkAllowed(this.getAttribute('action'))) return;
    return nativeSubmit.call(this);
  };

  // Lightweight mode: on the site's own pages only, never inside video players.
  var bridge = window.CineShieldBridge;
  var lite = isTop && isSite(location.hostname.toLowerCase()) && (!bridge || bridge.lite());
  var LITE_CSS =
    '*,*::before,*::after{animation-duration:.001s!important;animation-delay:0s!important;' +
    'animation-iteration-count:1!important;transition-duration:.001s!important;' +
    'transition-delay:0s!important;backdrop-filter:none!important;' +
    '-webkit-backdrop-filter:none!important;filter:none!important;box-shadow:none!important;' +
    'text-shadow:none!important;scroll-behavior:auto!important}' +
    'img[src*=".gif"]{visibility:hidden!important}' +
    'html,body{background-image:none!important;background-color:#0b0d10!important}' +
    'nav{background-color:#101418!important}';

  // The site's moving background is drawn by the graphics chip every frame. Skipping
  // the draw calls removes that work without breaking the script that runs it.
  function stopGpuDrawing() {
    [window.WebGLRenderingContext, window.WebGL2RenderingContext].forEach(function (type) {
      if (!type) return;
      ['drawArrays', 'drawElements', 'drawArraysInstanced', 'drawElementsInstanced',
        'drawRangeElements'].forEach(function (name) {
        if (type.prototype[name]) type.prototype[name] = function () {};
      });
    });
  }
  function hideBackdrop(el) {
    el.style.setProperty('display', 'none', 'important');
    if (el.tagName === 'CANVAS') {
      // A tiny drawing surface makes any remaining drawing almost free.
      el.width = 1;
      el.height = 1;
    } else if (el.tagName === 'VIDEO') {
      el.pause();
      el.removeAttribute('src');
      el.load();
    }
  }
  function isBackdrop(el) {
    if (el.hasAttribute('data-cs-fill') || el.style.display === 'none') return false;
    var r = el.getBoundingClientRect();
    if (r.width * r.height < 0.5 * innerWidth * innerHeight) return false;
    var s = getComputedStyle(el);
    if (s.position !== 'fixed' && s.position !== 'absolute') return false;
    if (el.tagName === 'CANVAS') return true;
    return !el.controls && (el.muted || s.pointerEvents === 'none' || (parseInt(s.zIndex, 10) || 0) < 0);
  }
  function clearBackdrops() {
    var list = document.querySelectorAll('canvas,video');
    for (var i = 0; i < list.length; i++) {
      if (isBackdrop(list[i])) hideBackdrop(list[i]);
    }
  }
  function addLiteStyle() {
    var style = document.createElement('style');
    style.textContent = LITE_CSS;
    (document.head || document.documentElement).appendChild(style);
  }
  // Decorative clips are looped or short. Films, episodes and live streams are not.
  function stopBackgroundVideo(e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO') return;
    if (!v.loop && !(isFinite(v.duration) && v.duration > 0 && v.duration < 300)) return;
    v.pause();
    v.removeAttribute('src');
    v.load();
    v.style.setProperty('display', 'none', 'important');
  }
  if (lite) {
    if (document.documentElement) addLiteStyle();
    else document.addEventListener('readystatechange', addLiteStyle, { once: true });
    window.addEventListener('loadedmetadata', stopBackgroundVideo, true);
    window.addEventListener('play', stopBackgroundVideo, true);
    stopGpuDrawing();
    setInterval(clearBackdrops, 1500);
  }

  var MAX_Z = 2147480000;
  function isOverlay(el) {
    if (el.nodeType !== 1 || el === document.documentElement || el === document.body) return false;
    if (el.querySelector && el.querySelector('video')) return false;
    if (el.tagName === 'VIDEO') return false;
    if (el.tagName === 'A' && linkAllowed(el.getAttribute('href'))) return false;
    var s = getComputedStyle(el);
    if (s.position !== 'fixed' && s.position !== 'absolute') return false;
    var r = el.getBoundingClientRect();
    var area = (r.width * r.height) / Math.max(1, innerWidth * innerHeight);
    if (el.tagName === 'A' && area > 0.25) return !linkAllowed(el.getAttribute('href'));
    var z = parseInt(s.zIndex, 10) || 0;
    return z >= MAX_Z && area > 0.25 && !(el.textContent || '').trim();
  }
  function solidify(el) {
    // With blur off, see-through search and menu layers need a plain backing.
    if (el.nodeType !== 1 || el.tagName === 'IFRAME' || el.tagName === 'VIDEO') return;
    var s = getComputedStyle(el);
    if (s.position !== 'fixed') return;
    var r = el.getBoundingClientRect();
    if (r.width * r.height < 0.5 * innerWidth * innerHeight) return;
    var m = s.backgroundColor.match(/[\d.]+/g);
    var alpha = !m ? 0 : (m.length > 3 ? parseFloat(m[3]) : 1);
    if (alpha < 0.9) el.style.setProperty('background-color', '#0b0d10', 'important');
  }
  function sweep(root) {
    if (root.nodeType !== 1) return;
    if (isOverlay(root)) { root.remove(); return; }
    if (lite) solidify(root);
    var kids = root.children || [];
    for (var i = 0; i < kids.length && i < 40; i++) {
      if (lite) solidify(kids[i]);
      if (kids[i].tagName === 'DIV' && isOverlay(kids[i])) kids[i].remove();
    }
    var links = root.querySelectorAll ? root.querySelectorAll('a[href^="http"]') : [];
    for (var j = 0; j < links.length; j++) {
      if (!linkAllowed(links[j].getAttribute('href')) && isOverlay(links[j])) links[j].remove();
    }
    if (lite) {
      var frames = root.tagName === 'IFRAME' ? [root] : root.querySelectorAll('iframe[src*="youtube"]');
      for (var k = 0; k < frames.length; k++) {
        if (/[?&]mute=1/.test(frames[k].src)) stopFrame(frames[k]);
      }
    }
  }
  function stopFrame(f) {
    f.removeAttribute('src');
    f.style.setProperty('display', 'none', 'important');
  }
  // Pages change in bursts, so check them in batches rather than per element.
  var queue = [], timer = 0;
  function flush() {
    timer = 0;
    var batch = queue;
    queue = [];
    batch.forEach(function (n) { if (n.isConnected) sweep(n); });
  }
  function startObserver() {
    new MutationObserver(function (records) {
      records.forEach(function (rec) {
        for (var i = 0; i < rec.addedNodes.length; i++) {
          if (rec.addedNodes[i].nodeType === 1) queue.push(rec.addedNodes[i]);
        }
      });
      if (queue.length && !timer) timer = setTimeout(flush, 150);
    }).observe(document.documentElement, { childList: true, subtree: true });
    if (document.body) sweep(document.body);
  }
  if (document.documentElement) startObserver();
  else document.addEventListener('readystatechange', startObserver, { once: true });
  window.addEventListener('load', function () { if (document.body) sweep(document.body); });

  // ---- Video: reports to the app and takes commands, including in cross-origin frames.
  var FILL_CSS =
    '[data-cs-fill]{position:fixed!important;inset:0!important;width:100vw!important;' +
    'height:100vh!important;max-width:none!important;max-height:none!important;' +
    'min-width:0!important;margin:0!important;border:0!important;background:#000!important;' +
    'z-index:2147483647!important;transform:none!important;visibility:visible!important;' +
    'display:block!important;opacity:1!important}' +
    '[data-cs-chain]{transform:none!important;filter:none!important;contain:none!important;' +
    'overflow:visible!important;will-change:auto!important;z-index:2147483647!important}' +
    'video::-webkit-media-controls{display:none!important}';
  var fillStyle = null;

  function activeVideo() {
    var videos = document.getElementsByTagName('video'), best = null, size = 0;
    for (var i = 0; i < videos.length; i++) {
      var v = videos[i];
      if (!v.currentSrc || v.style.display === 'none') continue;
      var area = v.videoWidth * v.videoHeight + 1;
      if (area > size) { size = area; best = v; }
    }
    return best;
  }
  function setFill(el, on) {
    if (on) {
      if (!fillStyle) {
        fillStyle = document.createElement('style');
        fillStyle.textContent = FILL_CSS;
        (document.head || document.documentElement).appendChild(fillStyle);
      }
      el.setAttribute('data-cs-fill', '');
      for (var p = el.parentElement; p && p !== document.documentElement; p = p.parentElement) {
        p.setAttribute('data-cs-chain', '');
      }
    } else {
      var marked = document.querySelectorAll('[data-cs-fill],[data-cs-chain]');
      for (var i = 0; i < marked.length; i++) {
        marked[i].removeAttribute('data-cs-fill');
        marked[i].removeAttribute('data-cs-chain');
      }
    }
  }
  function fillUp(on) {
    if (!isTop) window.parent.postMessage({ cineshield: 'fill-frame', s: on ? 1 : 0 }, '*');
  }
  function players() {
    var p = null;
    try { if (window.hls && window.hls.levels) p = { hls: window.hls }; } catch (e) {}
    try {
      if (!p && window.jwplayer && window.jwplayer().getQualityLevels) p = { jw: window.jwplayer() };
    } catch (e) {}
    return p || {};
  }
  function qualities() {
    var p = players(), out = [];
    if (p.hls) {
      p.hls.levels.forEach(function (l, i) {
        out.push({ i: i, label: (l.height ? l.height + 'p' : 'Level ' + (i + 1)), on: p.hls.currentLevel === i });
      });
    } else if (p.jw) {
      var cur = p.jw.getCurrentQuality();
      p.jw.getQualityLevels().forEach(function (l, i) {
        out.push({ i: i, label: l.label || 'Level ' + (i + 1), on: cur === i });
      });
    }
    return out;
  }
  function subtitles(v) {
    var out = [];
    for (var i = 0; i < v.textTracks.length; i++) {
      var t = v.textTracks[i];
      if (t.kind !== 'subtitles' && t.kind !== 'captions') continue;
      out.push({ i: i, label: t.label || t.language || 'Track ' + (i + 1), on: t.mode === 'showing' });
    }
    return out;
  }
  if (bridge) {
    setInterval(function () {
      var v = activeVideo();
      if (!v) return;
      bridge.state(JSON.stringify({
        playing: !v.paused && !v.ended,
        t: v.currentTime || 0,
        d: isFinite(v.duration) ? v.duration : -1,
        rate: v.playbackRate,
        muted: v.muted || v.volume === 0,
        fit: v.style.objectFit === 'cover' ? 1 : 0,
        subs: subtitles(v),
        q: qualities()
      }));
    }, 1000);
  }

  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || !d.cineshield) return;
    if (d.cineshield === 'fill-frame') {
      var frames = document.getElementsByTagName('iframe');
      for (var f = 0; f < frames.length; f++) {
        if (frames[f].contentWindow === e.source) {
          setFill(frames[f], !!d.s);
          fillUp(!!d.s);
        }
      }
      return;
    }
    if (d.cineshield === 'unfill') {
      setFill(document.documentElement, false);
      return;
    }
    var v = activeVideo();
    if (!v) return;
    switch (d.cineshield) {
      case 'toggle': if (v.paused) v.play(); else v.pause(); break;
      case 'pause': v.pause(); break;
      case 'mute':
        v.muted = !!d.s;
        if (!d.s && v.volume === 0) v.volume = 1;
        break;
      case 'seek': v.currentTime = Math.max(0, v.currentTime + d.s); break;
      case 'fill': setFill(v, true); fillUp(true); break;
      case 'rate': v.playbackRate = d.s; break;
      case 'fit': v.style.objectFit = d.s ? 'cover' : 'contain'; break;
      case 'sub':
        for (var i = 0; i < v.textTracks.length; i++) {
          v.textTracks[i].mode = i === d.s ? 'showing' : 'disabled';
        }
        break;
      case 'quality':
        var p = players();
        if (p.hls) p.hls.currentLevel = d.s;
        else if (p.jw && d.s >= 0) p.jw.setCurrentQuality(d.s);
        break;
    }
  });

  // ---- Remote navigation: arrow keys move between things you can select.
  if (!isTop) return;
  var BASE = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],' +
    '[role=link],[role=tab],[role=menuitem],[role=option],[onclick],[tabindex]:not([tabindex="-1"]),iframe';
  var FOCUS_CSS =
    '[data-cs-focus]{outline:4px solid #95FF50!important;outline-offset:3px!important;' +
    'border-radius:6px;position:relative;z-index:50}' +
    '[data-cs-focus]:not(iframe):not(input){transform:scale(1.05)!important}';
  var current = null, cache = null, cacheAt = 0;

  function ensureFocusStyle() {
    if (document.getElementById('cs-focus')) return;
    var st = document.createElement('style');
    st.id = 'cs-focus';
    st.textContent = FOCUS_CSS;
    (document.head || document.documentElement).appendChild(st);
  }
  function usable(el) {
    if (el.disabled) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 10 || r.height < 10) return false;
    if (el.tagName === 'IFRAME' && (r.width < 200 || r.height < 120)) return false;
    var s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity) > 0.05;
  }
  // When a full-screen layer such as search is open, stay inside it.
  function activeLayer() {
    var el = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
    for (; el && el !== document.body; el = el.parentElement) {
      if (getComputedStyle(el).position === 'fixed') {
        var r = el.getBoundingClientRect();
        if (r.width * r.height > 0.5 * innerWidth * innerHeight) return el;
      }
    }
    return document;
  }
  function targets() {
    var now = Date.now();
    if (cache && now - cacheAt < 400) return cache;
    var layer = activeLayer(), out = [], list = layer.querySelectorAll(BASE), i;
    for (i = 0; i < list.length; i++) {
      var el = list[i];
      if (el.parentElement && el.parentElement.closest(BASE)) continue;
      if (usable(el)) out.push(el);
    }
    // Cards built from plain elements that only look clickable.
    var plain = layer.querySelectorAll('div,li,article,img,span');
    for (i = 0; i < plain.length && i < 1500; i++) {
      var c = plain[i];
      if (getComputedStyle(c).cursor !== 'pointer') continue;
      var parent = c.parentElement;
      if (parent && getComputedStyle(parent).cursor === 'pointer') continue;
      if (c.closest(BASE) || c.querySelector(BASE)) continue;
      if (usable(c)) out.push(c);
    }
    cache = out;
    cacheAt = now;
    return out;
  }
  function setFocus(el) {
    ensureFocusStyle();
    if (current) current.removeAttribute('data-cs-focus');
    current = el;
    if (!el) return;
    el.setAttribute('data-cs-focus', '');
    el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'auto' });
    try { if (!/^(INPUT|TEXTAREA|SELECT|IFRAME)$/.test(el.tagName)) el.focus({ preventScroll: true }); } catch (e) {}
  }
  function firstTarget() {
    var best = null, score = Infinity, all = targets();
    for (var i = 0; i < all.length; i++) {
      var r = all[i].getBoundingClientRect();
      if (r.bottom < 0 || r.top > innerHeight) continue;
      var sc = r.top * 3 + r.left;
      if (sc < score) { score = sc; best = all[i]; }
    }
    return best || all[0] || null;
  }
  function move(dir) {
    cache = null;
    var layer = activeLayer();
    if (!current || !current.isConnected || !usable(current) ||
        (layer !== document && !layer.contains(current))) {
      setFocus(firstTarget());
      return;
    }
    var from = current.getBoundingClientRect();
    var fx = from.left + from.width / 2, fy = from.top + from.height / 2;
    var horizontal = dir === 'left' || dir === 'right';
    var sign = dir === 'left' || dir === 'up' ? -1 : 1;
    var best = null, score = Infinity, all = targets();
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el === current) continue;
      var r = el.getBoundingClientRect();
      var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      var ahead = (horizontal ? cx - fx : cy - fy) * sign;
      if (ahead <= 4) continue;
      // Clearing the current item matters: the next row, not a neighbour's edge.
      var edge = horizontal
        ? (sign > 0 ? r.left - from.right : from.left - r.right)
        : (sign > 0 ? r.top - from.bottom : from.top - r.bottom);
      if (edge < -Math.min(horizontal ? from.width : from.height, 40) / 2) continue;
      var overlap = horizontal
        ? Math.min(r.bottom, from.bottom) - Math.max(r.top, from.top)
        : Math.min(r.right, from.right) - Math.max(r.left, from.left);
      var side = horizontal ? Math.abs(cy - fy) : Math.abs(cx - fx);
      var sc = Math.max(edge, 0) + side * (overlap > 0 ? 0.3 : 2.5);
      if (sc < score) { score = sc; best = el; }
    }
    var limit = (horizontal ? innerWidth : innerHeight) * 1.3;
    if (best && score < limit * 2) {
      setFocus(best);
    } else if (!horizontal) {
      // Nothing that way yet: scroll, which also loads more rows.
      window.scrollBy(0, sign * innerHeight * 0.6);
    }
  }
  // Fractions of the screen for the app to press, so the page sees a real press.
  function press() {
    if (!current || !current.isConnected) { setFocus(firstTarget()); return null; }
    var r = current.getBoundingClientRect();
    cache = null;
    return [(r.left + r.width / 2) / innerWidth, (r.top + r.height / 2) / innerHeight];
  }
  window.__cs = { move: move, press: press, init: function () { if (!current || !current.isConnected) setFocus(firstTarget()); } };
})();
