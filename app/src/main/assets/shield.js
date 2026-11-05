// Runs in every frame. Removes big invisible overlays that hijack clicks.
(function () {
  if (window.__streamGate) return;
  window.__streamGate = true;
  function sweep() {
    if (!document.body) return;
    var vw = innerWidth * innerHeight;
    if (!vw) return;
    document.querySelectorAll('body > *, body > * > *').forEach(function (el) {
      if (el.id === 'sg-root' || el.tagName === 'IFRAME' || el.tagName === 'VIDEO' || el === document.fullscreenElement) return;
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'absolute') return;
      if ((parseInt(cs.zIndex, 10) || 0) < 1000) return;
      var r = el.getBoundingClientRect();
      if (r.width * r.height < vw * 0.6) return;
      if (el.querySelector('video, iframe')) return;
      el.remove();
    });
  }
  setInterval(sweep, 2000);

  // Inside the player's frame: fill the screen, start playing, and turn sound on.
  if (window.top !== window) {
    var added = false, unmuted = false;
    setInterval(function () {
      var v = document.querySelector('video');
      if (!v) return;
      if (!added && document.head) {
        added = true;
        var st = document.createElement('style');
        st.textContent = 'video{position:fixed!important;inset:0!important;width:100vw!important;' +
          'height:100vh!important;object-fit:contain;background:#000;z-index:2147483000!important}';
        document.head.appendChild(st);
      }
      if (v.paused) {
        var p = v.play();
        if (p && p.catch) p.catch(function () {});
      } else if (!unmuted) {
        unmuted = true;
        v.muted = false;
        v.volume = 1;
      }
    }, 1000);
  }
})();
