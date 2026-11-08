// Remote control for the real ppv.tj site. Runs in the top frame only.
// The D-pad moves a highlight between things on the page, OK selects, and
// OK on a video player taps it, then fills the screen with it.
// The app sends remote presses here through window.__sgKey and __sgBack.
(function () {
  if (window.top !== window || window.__sgTv) return;
  if (!/(^|\.)ppv\.[a-z]{2,4}$/i.test(location.hostname)) return;
  window.__sgTv = true;

  var TARGETS = 'a[href],button,input,select,textarea,summary,iframe,video,' +
    '[role=button],[role=link],[role=tab],[role=menuitem],[onclick],[tabindex]:not([tabindex="-1"])';
  var current = null, filled = null, tapped = null;

  function addStyle() {
    if (document.getElementById('sg-style') || !document.documentElement) return;
    var s = document.createElement('style');
    s.id = 'sg-style';
    s.textContent = window.__sgCss;
    (document.head || document.documentElement).appendChild(s);
  }

  function visible(n) {
    var r = n.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    var cs = getComputedStyle(n);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && cs.opacity !== '0';
  }

  // Sites often use plain boxes with click handlers, so pointer-styled ones count too.
  function targets() {
    var list = Array.prototype.slice.call(document.querySelectorAll(TARGETS));
    document.querySelectorAll('div,li,span,img,article,section').forEach(function (n) {
      if (list.length > 1500) return;
      if (getComputedStyle(n).cursor !== 'pointer') return;
      var p = n.parentElement;
      if (p && getComputedStyle(p).cursor === 'pointer') return;
      list.push(n);
    });
    return list.filter(function (n) {
      if (!visible(n)) return false;
      // Skip a box that only wraps another target, so focus lands on one thing.
      return !list.some(function (o) { return o !== n && n.contains(o) && o.tagName === 'A' && n.tagName !== 'A'; });
    });
  }

  function isPlayer(n) {
    return n && (n.tagName === 'IFRAME' || n.tagName === 'VIDEO');
  }

  function set(n) {
    if (current) current.classList.remove('sg-generic-focus');
    current = n;
    if (!n) return;
    n.classList.add('sg-generic-focus');
    var r = n.getBoundingClientRect();
    if (r.top < 60 || r.bottom > innerHeight - 60) {
      n.scrollIntoView({ block: isPlayer(n) ? 'start' : 'center', inline: 'nearest' });
    }
  }

  function centre(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function move(k) {
    var all = targets();
    if (!current || all.indexOf(current) < 0 || !visible(current)) {
      var first = all.filter(function (n) { var r = n.getBoundingClientRect(); return r.top >= 0 && r.top < innerHeight; })[0];
      set(first || all[0]);
      return;
    }
    var a = current.getBoundingClientRect(), ac = centre(a);
    var best = null, score = Infinity;
    all.forEach(function (n) {
      if (n === current || n.contains(current) || current.contains(n)) return;
      var b = n.getBoundingClientRect(), bc = centre(b);
      var dx = bc.x - ac.x, dy = bc.y - ac.y;
      var main, side, overlap;
      if (k === 'left' || k === 'right') {
        main = k === 'left' ? a.left - b.right : b.left - a.right;
        if ((k === 'left' ? dx : -dx) >= 0) return;
        overlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        side = overlap > 0 ? 0 : Math.abs(dy);
      } else {
        main = k === 'up' ? a.top - b.bottom : b.top - a.bottom;
        if ((k === 'up' ? dy : -dy) >= 0) return;
        overlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        side = overlap > 0 ? 0 : Math.abs(dx);
      }
      // Stay in the same row or column when anything there is available.
      var s = Math.max(main, 0) + side * 3 + (overlap > 0 ? 0 : 2000);
      if (s < score) { score = s; best = n; }
    });
    if (best) set(best);
    else if (k === 'up' || k === 'down') window.scrollBy({ top: k === 'up' ? -innerHeight * 0.6 : innerHeight * 0.6 });
  }

  function tap(n) {
    var r = n.getBoundingClientRect(), c = centre(r), z = window.devicePixelRatio || 1;
    if (window.SGNative) SGNative.tapAt(Math.round(c.x * z), Math.round(c.y * z));
  }

  function fill(n) {
    filled = n;
    n.classList.add('sg-pin');
    for (var p = n.parentElement; p && p !== document.body; p = p.parentElement) p.classList.add('sg-chain');
    document.documentElement.classList.add('sg-filled');
  }

  function unfill() {
    if (!filled) return;
    filled.classList.remove('sg-pin');
    document.querySelectorAll('.sg-chain').forEach(function (p) { p.classList.remove('sg-chain'); });
    document.documentElement.classList.remove('sg-filled');
    set(filled);
    filled = null;
  }

  function select() {
    if (filled) { tap(filled); return; }
    if (!current) { move('down'); return; }
    if (isPlayer(current)) {
      // First OK starts the player, the next one fills the screen with it.
      if (tapped === current) fill(current);
      else { tapped = current; tap(current); }
      return;
    }
    if (/^(INPUT|TEXTAREA|SELECT)$/.test(current.tagName)) { current.focus(); return; }
    current.click();
  }

  window.__sgKey = function (k) {
    addStyle();
    if (k === 'ok') select();
    else if (filled) { if (k === 'up' || k === 'down') unfill(); }
    else move(k);
  };
  window.__sgBack = function () {
    if (filled) { unfill(); return 'handled'; }
    return 'history';
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addStyle);
  else addStyle();
})();
