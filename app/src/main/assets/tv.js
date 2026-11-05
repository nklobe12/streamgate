// TV interface. Runs in the top frame of the site only. Reads the site's own
// page, then draws clean rows (listings) or a full-screen player (game pages).
// The app sends remote presses here through window.__sgKey and __sgBack.
(function () {
  if (window.top !== window || window.__sgTv) return;
  if (location.hostname.indexOf('buffstreams.') < 0) return;
  window.__sgTv = true;

  var HOME = '/home7';
  var TABS = [
    ['Home', HOME], ['NFL', '/nflstreams2'], ['NBA', '/nbastreams2'], ['NHL', '/nhlstreams2'],
    ['MLB', '/mlb-live-streams'], ['Soccer', '/soccer-live-streams'], ['College FB', '/cfbstreams2'],
    ['NCAA', '/ncaastreams'], ['UFC / MMA', '/mmastreams2'], ['Boxing', '/boxingstreams2'],
    ['F1', '/f1streams2'], ['WWE', '/wwestreams'], ['WNBA', '/wnbastreams']
  ];
  var GAME_PATH = /^\/[^/]+\/[^/]+\/\d+\/?$|^\/title-game\//;
  var SERVER_LABEL = /^(server\s*\d*|backup\s*\d*|link\s*\d+|stream\s*\d+|mirror\s*\d*)$/i;
  var LIVE = /in progress|halftime|live now|minutes? ago|\bq[1-4]\b|\bot\b/i;
  var mode = 'pending', keyHandler = null, backHandler = null, root = null;

  function store(k, v) {
    try {
      if (v === undefined) return sessionStorage.getItem(k);
      sessionStorage.setItem(k, v);
    } catch (e) {}
    return null;
  }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }

  // Hide the raw page from the first frame so it never flashes on screen.
  function addStyle() {
    var s = document.createElement('style');
    s.id = 'sg-style';
    s.textContent = window.__sgCss;
    (document.head || document.documentElement).appendChild(s);
  }
  function boot() {
    document.documentElement.classList.add('sg-boot');
    addStyle();
  }
  // Scripts can start before the document has a root element.
  if (document.documentElement) boot();
  else new MutationObserver(function (m, obs) {
    if (!document.documentElement) return;
    obs.disconnect();
    boot();
  }).observe(document, { childList: true });
  var isGame = GAME_PATH.test(location.pathname);

  // If anything goes wrong, show the real page rather than a blank screen.
  setTimeout(function () {
    if (mode === 'pending') {
      document.documentElement.classList.remove('sg-boot');
      startGeneric();
    }
  }, 8000);

  function ready(fn) {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
    else fn();
  }

  // ---------- Reading the site's pages ----------

  function parseGame(a) {
    var url = a.href, text = clean(a.textContent).replace(/\s*Live Streams?\s*$/i, '');
    var title = clean(a.getAttribute('title')).replace(/^\S*streams\s+/i, '');
    var teams = title.split(/\s+vs\.?\s+/i), status = text, name;
    if (teams.length === 2) {
      status = clean(text.replace(teams[0], '').replace(teams[1], ''));
      name = teams[0] + ' vs ' + teams[1];
    } else {
      var dash = text.split(/\s+-\s+/);
      teams = [dash[0]];
      name = dash[0];
      status = clean(dash.slice(1).join(' - '));
    }
    return { url: url, teams: teams, name: name, status: status, live: LIVE.test(status) };
  }

  function readRows() {
    var rows = [], current = null, seen = {};
    document.querySelectorAll('h1,h2,h3,h4,a[href]').forEach(function (n) {
      if (n.closest('#sg-root')) return;
      if (n.tagName !== 'A') {
        var title = clean(n.textContent).replace(/^Upcoming\s+/i, '').replace(/\s+Streams?\s+Links?$/i, '');
        current = { title: title, games: [] };
        rows.push(current);
        return;
      }
      if (n.host !== location.host || !GAME_PATH.test(n.pathname) || seen[n.href]) return;
      seen[n.href] = true;
      if (!current) { current = { title: 'Featured', games: [] }; rows.push(current); }
      current.games.push(parseGame(n));
    });
    rows = rows.filter(function (r) { return r.games.length; });
    rows.forEach(function (r) {
      r.games.sort(function (x, y) { return (y.live ? 1 : 0) - (x.live ? 1 : 0); });
    });
    var live = [];
    rows.forEach(function (r) { r.games.forEach(function (g) { if (g.live) live.push(g); }); });
    if (live.length && rows.length > 1) {
      rows = rows.filter(function (r) {
        return r.title !== 'Featured' || !r.games.every(function (g) { return g.live; });
      });
      rows.unshift({ title: 'Live now', games: live });
    }
    return rows;
  }

  // ---------- Game rows ----------

  function showListing(rows) {
    mode = 'listing';
    document.documentElement.classList.remove('sg-boot');
    document.documentElement.classList.add('sg-list');
    root = root || el('div');
    root.id = 'sg-root';
    root.textContent = '';
    document.body.appendChild(root);

    var path = location.pathname.replace(/\/$/, '');
    var activeTab = 0;
    var tabs = el('div', 'sg-tabs'), tabEls = [];
    TABS.forEach(function (t, i) {
      var b = el('div', 'sg-tab', t[0]);
      if (t[1] === path) { b.classList.add('sg-active'); activeTab = i; }
      tabs.appendChild(b);
      tabEls.push(b);
    });
    root.appendChild(tabs);

    var scroller = el('div', 'sg-rows');
    root.appendChild(scroller);
    var grid = [tabEls];
    rows.forEach(function (r) {
      var sec = el('section', 'sg-row');
      sec.appendChild(el('h2', 'sg-row-title', r.title));
      var track = el('div', 'sg-track'), cards = [];
      r.games.forEach(function (g) {
        var c = el('div', 'sg-card');
        c.dataset.url = g.url;
        c.dataset.name = g.name;
        var meta = el('div', g.live ? 'sg-status sg-live' : 'sg-status', g.live ? 'LIVE' : g.status);
        c.appendChild(meta);
        g.teams.forEach(function (t) { c.appendChild(el('div', 'sg-team', t)); });
        track.appendChild(c);
        cards.push(c);
      });
      sec.appendChild(track);
      scroller.appendChild(sec);
      grid.push(cards);
    });
    if (!rows.length) scroller.appendChild(el('div', 'sg-empty', 'No games listed right now'));

    var pos = { r: rows.length ? 1 : 0, c: rows.length ? 0 : activeTab };
    var saved = store('sg-focus:' + path);
    if (saved) grid.forEach(function (cards, r) {
      cards.forEach(function (c, i) { if (c.dataset.url === saved) pos = { r: r, c: i }; });
    });

    function focus() {
      var old = root.querySelector('.sg-focus');
      if (old) old.classList.remove('sg-focus');
      var item = grid[pos.r][pos.c];
      item.classList.add('sg-focus');
      if (pos.r === 0) {
        tabs.scrollLeft = Math.max(0, item.offsetLeft - tabs.clientWidth / 2 + item.offsetWidth / 2);
      } else {
        var sec = item.closest('.sg-row'), track = item.parentNode;
        scroller.scrollTop = sec.offsetTop;
        track.scrollLeft = Math.max(0, item.offsetLeft - track.offsetLeft - innerWidth * 0.03);
      }
    }
    keyHandler = function (k) {
      if (k === 'up' && pos.r > 0) {
        pos.r--;
        pos.c = pos.r === 0 ? activeTab : Math.min(pos.c, grid[pos.r].length - 1);
      } else if (k === 'down' && pos.r < grid.length - 1) {
        pos.r++;
        pos.c = Math.min(pos.r === 1 ? 0 : pos.c, grid[pos.r].length - 1);
      } else if (k === 'left' && pos.c > 0) {
        pos.c--;
      } else if (k === 'right' && pos.c < grid[pos.r].length - 1) {
        pos.c++;
      } else if (k === 'ok') {
        var item = grid[pos.r][pos.c];
        if (pos.r === 0) { location.href = TABS[pos.c][1]; return; }
        store('sg-focus:' + path, item.dataset.url);
        store('sg-name', item.dataset.name);
        location.href = item.dataset.url;
        return;
      }
      focus();
    };
    backHandler = function () {
      if (path !== HOME) { location.href = HOME; return 'handled'; }
      return 'exit';
    };
    focus();
  }

  // ---------- Player ----------

  function findServers() {
    var out = [], seen = [];
    document.querySelectorAll('a,button,li,span,div').forEach(function (n) {
      if (n.closest('#sg-root') || !SERVER_LABEL.test(clean(n.textContent))) return;
      // Keep the outermost element with this label so clicks reach its handler.
      if (seen.some(function (s) { return s.contains(n); })) return;
      seen.push(n);
      out.push({ label: clean(n.textContent).replace(/^server\s*/i, 'Server '), node: n });
    });
    return out;
  }

  function findPlayer(skip) {
    var best = null, area = 0;
    document.querySelectorAll('video,iframe').forEach(function (n) {
      if (n === skip || n.closest('#sg-root')) return;
      var r = n.getBoundingClientRect(), a = r.width * r.height;
      if (n.tagName === 'VIDEO') a += 1e7;
      if (r.width >= 300 && r.height >= 160 && a > area) { best = n; area = a; }
    });
    return best;
  }

  function hostOf(n) {
    try { return new URL(n.src, location.href).hostname; } catch (e) { return ''; }
  }

  function showGame() {
    mode = 'game';
    var html = document.documentElement;
    html.classList.remove('sg-boot');
    html.classList.add('sg-game');
    root = el('div');
    root.id = 'sg-root';
    document.body.appendChild(root);

    var name = store('sg-name') || clean((document.querySelector('h1') || {}).textContent)
      .replace(/\s*Live Streams?$/i, '') || 'Game';
    var status = el('div', 'sg-loading');
    var statusTitle = el('div', 'sg-load-title', name);
    var statusText = el('div', 'sg-load-text', 'Loading stream');
    status.appendChild(statusTitle);
    status.appendChild(statusText);
    root.appendChild(status);

    var panel = el('div', 'sg-panel');
    panel.appendChild(el('div', 'sg-panel-title', name));
    var list = el('div', 'sg-options');
    panel.appendChild(list);
    root.appendChild(panel);

    var hint = el('div', 'sg-hint');
    root.appendChild(hint);

    var servers = [], selected = 0, open = false, player = null, options = [], attempts = 0, timer = null;
    var playing = false, watch = null, watchTicks = 0;

    function say(text) {
      hint.textContent = text;
      hint.classList.toggle('sg-show', !!text);
    }
    // Moving the player out of the site's layout stops that layout hiding it.
    function pin(p) {
      if (player && player !== p) { player.classList.remove('sg-pin'); player.remove(); }
      player = p;
      p.classList.add('sg-pin');
      p.setAttribute('allow', 'autoplay; fullscreen; encrypted-media; picture-in-picture');
      if (p.parentNode !== document.body) document.body.appendChild(p);
      status.classList.add('sg-hidden');
      watchPlayback();
    }
    // Many players wait for a tap. Two taps at most, so a stream that is already
    // playing but can't be detected ends up playing again.
    function watchPlayback() {
      clearInterval(watch);
      playing = false;
      watchTicks = 0;
      say('Starting stream');
      watch = setInterval(function () {
        if (playing) { clearInterval(watch); return; }
        watchTicks++;
        if ((watchTicks === 3 || watchTicks === 8) && window.SGNative) SGNative.tap();
        if (watchTicks === 18) {
          clearInterval(watch);
          var from = player && hostOf(player);
          say('No video yet' + (from ? ' from ' + from : '') + '. Pick another server, or wait if it has started.');
          if (!open) { selected = Math.min(selected + 1, Math.max(servers.length - 1, 0)); togglePanel(true); }
        }
      }, 1000);
    }
    window.addEventListener('message', function (e) {
      if (e.data && e.data.sgPlaying && !playing) {
        playing = true;
        clearInterval(watch);
        say('');
      }
    });
    function renderOptions() {
      list.textContent = '';
      options = servers.map(function (s, i) { return { label: s.label, run: function () { pick(i); } }; });
      options.push({ label: 'Tap player', run: function () { if (window.SGNative) SGNative.tap(); } });
      options.push({ label: 'Full page', run: fullPage });
      options.forEach(function (o, i) {
        var b = el('div', 'sg-option', o.label);
        if (i === selected) b.classList.add('sg-focus');
        list.appendChild(b);
      });
    }
    function togglePanel(show) {
      open = show;
      panel.classList.toggle('sg-open', show);
      if (show) renderOptions();
    }
    function pick(i) {
      var s = servers[i];
      if (!s) return;
      selected = i;
      togglePanel(false);
      say('Loading ' + s.label);
      s.node.click();
      setTimeout(function () {
        var p = findPlayer(player);
        if (p) pin(p);
        else if (player) {
          // Some sites change the current player's address instead of adding a new one.
          watchPlayback();
        }
      }, 1200);
    }
    function fullPage() {
      clearInterval(timer);
      clearInterval(watch);
      html.classList.remove('sg-game');
      root.remove();
      if (player) player.classList.remove('sg-pin');
      startGeneric();
    }
    function poll() {
      attempts++;
      if (!servers.length) servers = findServers();
      if (attempts === 2 && servers.length) {
        // Some sites only load the real stream once a server is chosen. Only do
        // this once per page, in case the button reloads the page.
        var key = 'sg-auto:' + location.pathname + location.search;
        if (!store(key)) { store(key, '1'); servers[0].node.click(); }
      }
      if (attempts < 4) return;
      var p = findPlayer();
      if (p) { pin(p); clearInterval(timer); return; }
      if (attempts >= 40) {
        clearInterval(timer);
        statusText.textContent = 'This stream isn’t available yet.';
        togglePanel(true);
      }
    }
    timer = setInterval(poll, 500);
    poll();

    keyHandler = function (k) {
      if (!open) {
        if (k === 'ok' || k === 'up' || k === 'down') togglePanel(true);
        return;
      }
      if (k === 'left') selected = Math.max(0, selected - 1);
      else if (k === 'right') selected = Math.min(options.length - 1, selected + 1);
      else if (k === 'down') { togglePanel(false); return; }
      else if (k === 'ok') { options[selected].run(); return; }
      renderOptions();
    };
    backHandler = function () {
      if (open) { togglePanel(false); return 'handled'; }
      return 'history';
    };
  }

  // ---------- Fallback for pages without game rows ----------

  function startGeneric() {
    mode = 'generic';
    document.documentElement.classList.remove('sg-boot');
    var current = null;
    function targets() {
      return Array.prototype.filter.call(document.querySelectorAll('a[href],button,[role=button],input'), function (n) {
        var r = n.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      });
    }
    function set(n) {
      if (current) current.classList.remove('sg-generic-focus');
      current = n;
      if (n) {
        n.classList.add('sg-generic-focus');
        n.scrollIntoView({ block: 'center', inline: 'nearest' });
      }
    }
    keyHandler = function (k) {
      var all = targets();
      if (!current || all.indexOf(current) < 0) { set(all[0]); return; }
      if (k === 'ok') { current.click(); return; }
      var a = current.getBoundingClientRect(), ax = a.left + a.width / 2, ay = a.top + a.height / 2;
      var best = null, score = Infinity;
      all.forEach(function (n) {
        if (n === current) return;
        var b = n.getBoundingClientRect(), dx = b.left + b.width / 2 - ax, dy = b.top + b.height / 2 - ay;
        var main = k === 'left' ? -dx : k === 'right' ? dx : k === 'up' ? -dy : dy;
        var side = k === 'left' || k === 'right' ? Math.abs(dy) : Math.abs(dx);
        if (main <= 0) return;
        var s = main + side * 2;
        if (s < score) { score = s; best = n; }
      });
      if (best) set(best);
      else window.scrollBy(0, k === 'up' ? -300 : k === 'down' ? 300 : 0);
    };
    backHandler = function () { return 'history'; };
  }

  window.__sgKey = function (k) { if (keyHandler) keyHandler(k); };
  window.__sgBack = function () { return backHandler ? backHandler() : 'history'; };

  ready(function () {
    if (!document.getElementById('sg-style')) addStyle();
    if (isGame) { showGame(); return; }
    var tries = 0;
    (function attempt() {
      var rows = readRows();
      if (rows.length || tries >= 10) {
        var known = TABS.some(function (t) { return t[1] === location.pathname.replace(/\/$/, ''); });
        var challenge = /just a moment|attention required|checking your browser/i.test(document.title);
        if (!challenge && (rows.length || known)) showListing(rows); else startGeneric();
        return;
      }
      tries++;
      setTimeout(attempt, 300);
    })();
  });
})();
