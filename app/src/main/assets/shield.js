// Runs in every frame. Removes big invisible overlays that hijack clicks.
(function () {
  if (window.__streamGate) return;
  window.__streamGate = true;
  function sweep() {
    if (!document.body) return;
    var vw = innerWidth * innerHeight;
    if (!vw) return;
    document.querySelectorAll('body > *, body > * > *').forEach(function (el) {
      if (el.tagName === 'IFRAME' || el.tagName === 'VIDEO' || el === document.fullscreenElement) return;
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
})();
