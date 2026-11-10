// Tetra's built-in extension, in every page's top frame (BrowserEngine): it tells the Browser which form fields are
// there and what a sent form held, fills on the user's tap, tells the media's state and presses its keys. It only tells
// and proposes; nothing is saved or filled without the user. The Browser answers on the same port (BrowserScreen.kt).
(function () {
if (window.top !== window) return;
// a page kept in the back-forward cache loses its port: a new one when it is shown again
var port = null;
function connect() {
  port = browser.runtime.connectNative('tetra');
  port.onDisconnect.addListener(function () { port = null; });
  port.onMessage.addListener(heard);
}
function post(o) { try { if (!port) connect(); port.postMessage(o); } catch (e) {} }
// background play: while the Browser says so, the page always believes it is shown (YouTube stops a hidden video);
// the page's own document is changed through Firefox's Xray waiver, the content script's view stays the true one
var bg = false;
try { [['visibilityState', 'visible'], ['webkitVisibilityState', 'visible'], ['hidden', false], ['webkitHidden', false]].forEach(function (p) {
  Object.defineProperty(document.wrappedJSObject, p[0], { configurable: true, get: exportFunction(function () { return bg ? p[1] : document[p[0]]; }, window) }); }); } catch (e) {}
['visibilitychange', 'webkitvisibilitychange'].forEach(function (t) { window.addEventListener(t, function (e) {
  if (bg) e.stopImmediatePropagation(); }, true); });
// a long press on a link is the browser's menu (the user, 4 Nov): YouTube cancels it on its links in every browser, so on
// its pages the page's own handler never hears it there (the player's hold for 2× is a touch, not this)
if (/(^|\.)youtube\.com$/.test(location.hostname)) window.addEventListener('contextmenu', function (e) {
  if (e.target && e.target.closest && e.target.closest('a[href]')) e.stopImmediatePropagation(); }, true);
// the page's icon for the favourites' and tabs' circles (BrowserIcons): the largest it names that Android draws, else /favicon.ico
function icon() { try { var best = null, size = 0;
  document.querySelectorAll('link[rel~="icon"],link[rel="shortcut icon"],link[rel~="apple-touch-icon"],link[rel~="apple-touch-icon-precomposed"]').forEach(function (l) {
    if (!l.href || l.type == 'image/svg+xml' || /\.svg(\?|#|$)/i.test(l.href)) return;
    var s = parseInt((l.getAttribute('sizes') || '').split(/x/i)[0], 10) || (/apple-touch/.test(l.rel) ? 180 : 32);
    if (s > size) { size = s; best = l.href; } });
  if (/^https?:$/.test(location.protocol)) post({ icon: best || (location.origin + '/favicon.ico') }); } catch (e) {} }
window.addEventListener('load', function () { setTimeout(icon, 500); });
window.addEventListener('pageshow', function (e) { if (e.persisted) { last = null; lastMedia = ''; report(); media(); } });

// forms: which kinds of fields there are, a form's values on its way, a fill
var ADDR = ['name','given-name','additional-name','family-name','email','tel','organization','street-address','address-line1','address-line2','address-level1','address-level2','postal-code','country','country-name'];
function tok(e) {
  var a = (e.getAttribute('autocomplete') || '').toLowerCase().split(/\s+/).filter(function (w) { return w && !/^(section-|shipping$|billing$|home$|work$|mobile$|on$|off$)/.test(w); })[0] || '';
  var t = (e.type || '').toLowerCase(), n = ((e.name || '') + ' ' + (e.id || '')).toLowerCase();
  if (t == 'password') return 'password';
  if (a == 'cc-csc' || /cvc|cvv|csc|security.?code/.test(n)) return '';
  if (a.indexOf('cc-') == 0) return a;
  if (/card.?num|cc.?num/.test(n)) return 'cc-number';
  if (a == 'username') return 'username';
  if (ADDR.indexOf(a) >= 0) return a;
  if (t == 'email') return 'email';
  if (t == 'tel') return 'tel';
  return '';
}
function inputs(r) { return Array.prototype.filter.call((r || document).querySelectorAll('input,select'), function (e) { return (e.offsetWidth > 0 || e.offsetHeight > 0) && !e.disabled && e.type != 'hidden'; }); }
function user(list, p) { for (var k = list.indexOf(p) - 1; k >= 0; k--) { var t = (list[k].type || 'text').toLowerCase(); if (t == 'text' || t == 'email' || t == 'tel') return list[k]; } return null; }
var last = null;
function report() { var s = {}; inputs().forEach(function (e) { var k = tok(e); if (k) s[k] = 1; }); var k = Object.keys(s).join(','); if (k !== last) { last = k; post({ kinds: k }); } }
function send(root) {
  var list = inputs(root), o = { fields: {} };
  list.forEach(function (e) { var k = tok(e), v = e.value; if (!k || !v) return;
    if (k == 'password') { if (!o.pass) { o.pass = v; var u = user(list, e); if (u) o.user = u.value; } } else if (k == 'username') o.user = v; else o.fields[k] = v; });
  if (o.pass || Object.keys(o.fields).length) post({ form: JSON.stringify(o) });
}
document.addEventListener('submit', function (e) { send(e.target); }, true);
document.addEventListener('click', function (e) { var b = e.target.closest && e.target.closest('button,input[type=submit]'); if (!b) return; var f = b.form || document; if (f.querySelector('input[type=password]')) send(b.form); }, true);
document.addEventListener('keydown', function (e) { if (e.key == 'Enter' && e.target.type == 'password') send(e.target.form); }, true);
document.addEventListener('focusin', report, true);
document.addEventListener('DOMContentLoaded', report);
window.addEventListener('load', report);
// the prototype's own setter, so a page's framework (React) sees the change as typed
function set(e, v) { if (v == null || v === '') return; var p = Object.getOwnPropertyDescriptor(e.tagName == 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype, 'value'); p.set.call(e, v);
  e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true })); }
function fill(d) { var list = inputs(); list.forEach(function (e) { var k = tok(e);
  if (k == 'password') { if (d.password != null) { set(e, d.password); var u = user(list, e); if (u) set(u, d.username); } }
  else if (k == 'username') set(e, d.username); else if (k && (k in d)) set(e, d[k]); }); }

// media: its state told to the Browser; play, pause and a site's own ‹ › buttons (a page's media keys go through Gecko's media session)
function els() { return Array.prototype.slice.call(document.querySelectorAll('video,audio')); }
function cur() { var l = els(); return l.filter(function (m) { return !m.paused; })[0] || l.filter(function (m) { return m.currentSrc && m.duration > 0; })[0] || l[0]; }
var lastMedia = '';
function media() { try { var m = cur(), title = '';
  try { var ms = navigator.mediaSession; title = (ms && ms.metadata && ms.metadata.title) || ''; } catch (e) {}
  var s = JSON.stringify({ has: !!(m && (m.currentSrc || m.src)), playing: !!(m && !m.paused && !m.ended), title: title || document.title || '' });
  if (s !== lastMedia) { lastMedia = s; post({ media: s }); } } catch (e) { post({ error: 'media: ' + e }); } }
['play','playing','pause','ended','loadedmetadata','emptied'].forEach(function (t) { document.addEventListener(t, media, true); });
setInterval(media, 2000);
function click(sel) { var e = sel && document.querySelector(sel); if (!e) return false; e.click(); return true; }
var prevAt = 0;
function mediaDo(a, b) { var m = cur(); b = b || {};
  if (a == 'toggle') a = (m && !m.paused) ? 'pause' : 'play';
  if (a == 'play') { if (m) m.play(); }
  else if (a == 'pause') { if (m) m.pause(); }
  else if (a == 'next') click(b.next);
  else if (a == 'like') { if (!click(b.like)) post({ error: 'like: no button on ' + location.host }); }
  else if (a == 'prev') { var now = Date.now(), again = now - prevAt < 2000; prevAt = now;
    if (m && m.currentTime > 5 && !again) m.currentTime = 0; else if (!click(b.prev)) { if (again && history.length > 1) history.back(); else if (m) m.currentTime = 0; } }
  setTimeout(media, 300); }
function autoplay() { var n = 0, said = 0, t = setInterval(function () { var m = cur();
  if (m && !m.paused) { clearInterval(t); return; }
  if (m && (m.currentSrc || m.src)) m.play().catch(function (e) { if (!said) { said = 1; post({ error: 'autoplay: ' + e }); } }); else if (n == 4) click('[data-testid=play-button],.jp-play,[class*=play-button],button[aria-label*=Play i],button[title*=Play i]');
  if (++n > 30) clearInterval(t); }, 500); }
// AI Assist's search: its first result, past shorts and ads. Looked for as the page draws its results, for up to a minute:
// YouTube's mobile page draws them seconds after it has loaded, slower in Gecko than the WebView's 10 s allowed (4 Nov)
var firstOff = null;
// text: only links that say it (Spotify's "Daily Mix"); random: any one of them, not the first
function first(sel, wait, text, random) { if (firstOff) firstOff(); var lastN = -1, stableAt = 0;
  function look() { var l = Array.prototype.filter.call(document.querySelectorAll(sel), function (a) { return !text || ((a.textContent || '') + ' ' + (a.getAttribute('aria-label') || '') + ' ' + (a.title || '')).indexOf(text) >= 0; });
    if (random) { if (l.length != lastN) { lastN = l.length; stableAt = Date.now(); return; } // the page still drawing its mixes: all of them first
      if (!l.length || Date.now() - stableAt < 1500) return; l = [l[Math.floor(Math.random() * l.length)]]; }
    for (var i = 0; i < l.length; i++) { var a = l[i]; if (!a.href || a.href.indexOf('/shorts/') >= 0 || a.closest('[class*=promoted],[class*=ad-slot],ytm-reel-shelf-renderer,ytm-shorts-lockup-view-model')) continue;
      firstOff(); post({ first: a.href }); location.href = a.href; return; } }
  var seen = new MutationObserver(look), t = setInterval(look, 500), end = setTimeout(function () { firstOff(); post({ error: 'first: no result on ' + location.host, none: 1 }); }, wait || 60000);
  firstOff = function () { seen.disconnect(); clearInterval(t); clearTimeout(end); document.removeEventListener('visibilitychange', look); firstOff = null; };
  seen.observe(document.documentElement, { childList: true, subtree: true }); document.addEventListener('visibilitychange', look); look(); }

function heard(m) {
  if ('bg' in m) bg = !!m.bg;
  if (m.fill) fill(m.fill);
  if (m.media) mediaDo(m.media, m.buttons);
  if (m.autoplay) autoplay();
  if (m.first) first(m.first, m.wait, m.text, m.random);
  if (m.report) { last = null; report(); }
}
connect();
})();
