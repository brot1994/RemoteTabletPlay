(function () {
  'use strict';

  var CFG = window.FC_CONFIG || {};
  var AND = window.Android || null;
  var STORE_KEY = 'fc_settings_v1';
  var HARI = ['Minggu', 'Senin', 'Selasa', 'Rabu', 'Kamis', 'Jumat', 'Sabtu'];
  var BULAN = ['Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni', 'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember'];

  // Fields that the remote page is allowed to change
  var REMOTE_FIELDS = ['textColor', 'cardColor', 'bgColor', 'accent', 'custom', 'customText'];

  var PRESETS = [
    { id: 'dark', label: 'Gelap', v: { textColor: '#ECEAE4', cardColor: '#1C1C1F', bgColor: '#0E0E0F' } },
    { id: 'light', label: 'Terang', v: { textColor: '#F4F2EC', cardColor: '#1C1C1F', bgColor: '#E7E6E2' } },
    { id: 'amoled', label: 'AMOLED', v: { textColor: '#FFFFFF', cardColor: '#0A0A0A', bgColor: '#000000' } }
  ];

  var PALETTES = {
    textColor: [['Putih gading', '#ECEAE4'], ['Putih', '#FFFFFF'], ['Amber', '#F2A33A'], ['Biru muda', '#5AC8FA'], ['Hijau', '#7ED9A0'], ['Merah', '#FF6B5E']],
    cardColor: [['Arang', '#1C1C1F'], ['Hitam', '#0A0A0A'], ['Biru tua', '#1F2A3A'], ['Coklat tua', '#3A2A20'], ['Hijau tua', '#1E3328'], ['Putih', '#F4F2EC']],
    bgColor: [['Hitam arang', '#0E0E0F'], ['Hitam pekat', '#000000'], ['Abu terang', '#E7E6E2'], ['Biru malam', '#0F1A24'], ['Merah marun', '#2A1212'], ['Abu tua', '#2A2A2E']],
    accent: [['Amber', '#F2A33A'], ['Biru muda', '#5AC8FA'], ['Hijau', '#7ED9A0'], ['Merah', '#FF6B5E'], ['Putih gading', '#ECEAE4'], ['Ungu', '#B98CFF']]
  };
  var COLOR_GROUPS = [
    ['textColor', 'Warna huruf'],
    ['cardColor', 'Warna kartu'],
    ['bgColor', 'Warna latar (background)'],
    ['accent', 'Warna detik / aksen']
  ];
  var TOGGLES = [
    ['f24', 'Format 24 jam', 'Matikan untuk 12 jam + AM/PM'],
    ['sec', 'Tampilkan detik', 'Kartu kecil di samping menit'],
    ['date', 'Tampilkan tanggal', 'Hari dan tanggal di bawah jam'],
    ['sound', 'Suara flip', 'Bunyi klik saat angka berganti'],
    ['awake', 'Layar tetap menyala', 'Layar tidak mati sendiri selama aplikasi terbuka'],
    ['night', 'Redup otomatis malam', 'Pukul 22.00–05.00 layar jadi lebih gelap'],
    ['radioViz', 'Tampilan radio', 'Nama stasiun, judul lagu & animasi saat radio diputar']
  ];

  // ---------------------------------------------------------------- state
  function makeKey() {
    var chars = 'abcdefghjkmnpqrstuvwxyz23456789';
    var out = '';
    var arr = new Uint32Array(24);
    (window.crypto || window.msCrypto).getRandomValues(arr);
    for (var i = 0; i < arr.length; i++) out += chars.charAt(arr[i] % chars.length);
    return out;
  }

  var DEFAULTS = {
    f24: true, sec: true, date: true, sound: false, awake: true, night: true, radioViz: true,
    brightManual: false, bright: 80,
    textColor: '#ECEAE4', cardColor: '#1C1C1F', bgColor: '#0E0E0F', accent: '#F2A33A',
    custom: false, customText: '',
    remote: true, name: 'Tablet', dbUrl: '', remoteUrl: '', key: '',
    musicSrc: 'youtube', musicName: '', lastCmd: ''
  };

  var S = {};
  (function load() {
    var saved = {};
    try { saved = JSON.parse(localStorage.getItem(STORE_KEY) || '{}') || {}; } catch (e) { saved = {}; }
    for (var k in DEFAULTS) S[k] = saved.hasOwnProperty(k) ? saved[k] : DEFAULTS[k];
    if (!S.key || S.key.length < 20) S.key = makeKey();
    save();
  })();

  function save() {
    try { localStorage.setItem(STORE_KEY, JSON.stringify(S)); } catch (e) { }
  }

  // ---------------------------------------------------------------- helpers
  function $(id) { return document.getElementById(id); }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function isHex(c) { return typeof c === 'string' && /^#[0-9a-fA-F]{6}$/.test(c); }
  function lum(hex) {
    var h = String(hex || '#000000').replace('#', '');
    if (h.length === 3) h = h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
    var r = parseInt(h.substr(0, 2), 16) / 255, g = parseInt(h.substr(2, 2), 16) / 255, b = parseInt(h.substr(4, 2), 16) / 255;
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }
  function dbBase() { return String(S.dbUrl || CFG.FIREBASE_DB_URL || '').trim().replace(/\/+$/, ''); }
  function remotePage() { return String(S.remoteUrl || CFG.REMOTE_PAGE_URL || '').trim(); }
  function devCode() { return (S.key.substr(0, 4) + '-' + S.key.substr(4, 4)).toUpperCase(); }

  // ---------------------------------------------------------------- flip cards
  function FlipCard(el) {
    this.el = el;
    el.innerHTML =
      '<div class="h top"><span></span></div>' +
      '<div class="h bot"><span></span></div>' +
      '<div class="leaf ltop"><span></span></div>' +
      '<div class="leaf lbot"><span></span></div>' +
      '<div class="split"></div><div class="notch l"></div><div class="notch r"></div>';
    var sp = el.getElementsByTagName('span');
    this.sTop = sp[0]; this.sBot = sp[1]; this.lTop = sp[2]; this.lBot = sp[3];
    this.val = null; this.t = null;
  }
  FlipCard.prototype.set = function (v, animate) {
    if (v === this.val) return false;
    var old = this.val;
    this.val = v;
    var self = this;
    if (!animate || old === null) {
      clearTimeout(this.t);
      this.el.classList.remove('flip');
      this.sTop.textContent = this.sBot.textContent = this.lTop.textContent = this.lBot.textContent = v;
      return false;
    }
    this.sTop.textContent = v;
    this.sBot.textContent = old;
    this.lTop.textContent = old;
    this.lBot.textContent = v;
    this.el.classList.remove('flip');
    void this.el.offsetWidth; // restart animation
    this.el.classList.add('flip');
    clearTimeout(this.t);
    this.t = setTimeout(function () {
      self.sBot.textContent = v;
      self.el.classList.remove('flip');
    }, 600);
    return true;
  };

  var cardH = new FlipCard($('cardH'));
  var cardM = new FlipCard($('cardM'));
  var cardS = new FlipCard($('cardS'));

  // ---------------------------------------------------------------- sound
  var audioCtx = null;
  function click() {
    if (!S.sound) return;
    try {
      if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      if (audioCtx.state === 'suspended') audioCtx.resume();
      var len = Math.floor(audioCtx.sampleRate * 0.035);
      var buf = audioCtx.createBuffer(1, len, audioCtx.sampleRate);
      var d = buf.getChannelData(0);
      for (var i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 4);
      var src = audioCtx.createBufferSource();
      src.buffer = buf;
      var f = audioCtx.createBiquadFilter();
      f.type = 'bandpass'; f.frequency.value = 2200; f.Q.value = 0.8;
      var g = audioCtx.createGain();
      g.gain.value = 0.6;
      src.connect(f); f.connect(g); g.connect(audioCtx.destination);
      src.start();
    } catch (e) { }
  }

  // ---------------------------------------------------------------- render
  var root = document.documentElement;
  var clockEl = $('clock');

  function applyTheme() {
    root.style.setProperty('--bg', S.bgColor);
    root.style.setProperty('--card', S.cardColor);
    root.style.setProperty('--text', S.textColor);
    root.style.setProperty('--accent', S.accent);
    var light = lum(S.bgColor) > 0.5;
    root.style.setProperty('--info', light ? '#4A4A4F' : '#9A9AA0');
    root.style.setProperty('--custom', light ? '#1C1C1F' : '#ECEAE4');
    document.body.style.background = S.bgColor;
  }

  function hasCustom() { return !!S.custom && String(S.customText || '').trim().length > 0; }

  function layout() {
    var stage = $('stage');
    var vw = stage.clientWidth, vh = stage.clientHeight;
    if (!vw || !vh) return;
    var land = window.innerWidth >= window.innerHeight;
    document.body.classList.toggle('land', land);
    document.body.classList.toggle('port', !land);
    var isStrip = vw >= vh * 2.4;          // strip lebar (jam mengambang atas/bawah)
    clockEl.className = isStrip ? 'strip' : (vw >= vh * 0.9) ? 'land' : 'port';
    var isLand = clockEl.className === 'land';
    // pita radio di bawah jam (tidak di strip tipis)
    var rOn = radioShown() && !isStrip && vh >= 220 && vw >= 200;
    var rEl = $('radio'), bandH = 0;
    rEl.classList.toggle('hidden', !rOn);
    if (rOn) {
      bandH = Math.round(vh * (isLand ? 0.3 : 0.34));
      layoutRadio(vw, bandH, isLand && vw >= 520);
    }
    stage.style.paddingBottom = bandH + 'px';
    vh = vh - bandH;
    var base = Math.min(vw, vh);

    var info = $('info'), dateEl = $('dateLine'), custEl = $('customLine');
    dateEl.style.display = S.date ? '' : 'none';
    var custWrap = $('customWrap');
    custWrap.style.display = hasCustom() ? '' : 'none';
    custEl.textContent = hasCustom() ? String(S.customText).trim() : '';
    if (isStrip) {
      dateEl.style.fontSize = (vh * 0.1) + 'px';
      custWrap.style.fontSize = (vh * 0.17) + 'px';
      custWrap.style.maxWidth = (vw * 0.42) + 'px';
    } else {
      dateEl.style.fontSize = (base * (isLand ? 0.042 : 0.04)) + 'px';
      custWrap.style.fontSize = (base * (isLand ? 0.068 : 0.062)) + 'px';
      custWrap.style.maxWidth = (vw * 0.92) + 'px';
    }
    info.style.gap = (base * 0.025) + 'px';
    // pesan panjang: perkecil huruf sampai muat (maks. ±3 baris, strip: setinggi strip)
    if (hasCustom()) {
      info.style.display = '';
      var fs = parseFloat(custWrap.style.fontSize) || 20;
      var maxH = isStrip ? vh * 0.62 : (isLand ? vh * 0.24 : vh * 0.2);
      var minFs = isStrip ? vh * 0.07 : base * 0.028;
      var guard = 0;
      while (custWrap.offsetHeight > maxH && fs > minFs && guard < 30) {
        fs = fs * 0.92;
        custWrap.style.fontSize = fs + 'px';
        guard++;
      }
    }
    var showInfo = S.date || hasCustom();
    info.style.display = showInfo ? '' : 'none';
    var infoH = showInfo && !isStrip ? info.offsetHeight : 0;
    var infoGap = showInfo ? (isStrip ? vw * 0.03 : base * 0.055) : 0;
    clockEl.style.gap = infoGap + 'px';

    $('secwrap').style.display = S.sec ? '' : 'none';
    var ampm = $('ampm');
    ampm.style.display = S.f24 ? 'none' : '';

    var W, H, gap;
    if (isStrip) {
      gap = vh * 0.07;
      var infoW = showInfo ? Math.min(info.offsetWidth, vw * 0.44) : 0;
      var cardsW = vw * 0.94 - infoW - infoGap - gap * (S.sec ? 2 : 1);
      W = Math.min(cardsW / (2 + (S.sec ? 0.3 : 0)), vh * 0.8 / 0.89);
      H = W * 0.89;
    } else if (isLand) {
      gap = vw * 0.028;
      var availH = vh * 0.86 - infoH - infoGap;
      var availW = vw * 0.92 - gap * (S.sec ? 2 : 1);
      W = Math.min(availW / (2 + (S.sec ? 0.3 : 0)), availH / 0.89);
      H = W * 0.89;
    } else {
      gap = vh * 0.022;
      var sRow = S.sec ? 1 : 0;
      var aH = vh * 0.88 - infoH - infoGap - gap - (sRow ? gap : 0);
      W = Math.min(aH / (1.9 + (sRow ? 0.27 : 0)), vw * 0.84);
      H = W * 0.95;
    }
    W = Math.max(40, W); H = Math.max(36, H);
    $('cards').style.gap = gap + 'px';
    var sW = W * 0.3, sH = sW * 0.88;
    var sw = $('secwrap');
    sw.style.gap = (sH * 0.18) + 'px';
    ampm.style.fontSize = Math.max(11, sH * 0.22) + 'px';

    root.style.setProperty('--W', W + 'px');
    root.style.setProperty('--H', H + 'px');
    root.style.setProperty('--fs', (H * 0.8) + 'px');
    root.style.setProperty('--r', (W * 0.057) + 'px');
    root.style.setProperty('--split', Math.max(2, H * 0.012) + 'px');
    root.style.setProperty('--nw', Math.max(6, H * 0.048) + 'px');
    root.style.setProperty('--nh', Math.max(12, H * 0.096) + 'px');
    root.style.setProperty('--sW', sW + 'px');
    root.style.setProperty('--sH', sH + 'px');
    root.style.setProperty('--sfs', (sH * 0.72) + 'px');
  }

  // ---------------------------------------------------------------- radio (nama stasiun, lagu, animasi)
  var radio = { active: false, state: 'none', name: '', title: '' };
  function radioShown() { return !!S.radioViz && radio.active; }
  function layoutRadio(vw, bandH, row) {
    var rEl = $('radio'), t = $('rtext');
    rEl.classList.toggle('col', !row);
    rEl.style.height = bandH + 'px';
    var padX = Math.max(14, vw * 0.04);
    rEl.style.padding = (bandH * 0.1) + 'px ' + padX + 'px ' + (bandH * 0.08) + 'px';
    rEl.style.gap = (row ? padX : bandH * 0.04) + 'px';
    var fs = row ? Math.min(bandH * 0.2, vw * 0.034) : Math.min(bandH * 0.13, vw * 0.07);
    t.style.width = row ? Math.round(vw * 0.36) + 'px' : 'auto';
    t.style.flex = row ? '0 0 auto' : '0 0 auto';
    $('rtitle').style.fontSize = fs + 'px';
    $('rlive').style.fontSize = (fs * 0.5) + 'px';
    $('rartist').style.fontSize = (fs * 0.56) + 'px';
    sizeViz();
  }
  function splitSong(s) {
    s = String(s || '').trim();
    var m = s.match(/^(.+?)\s+[-–—]\s+(.+)$/);
    return m ? { artist: m[1], title: m[2] } : { artist: '', title: s };
  }
  function renderRadioText() {
    var waiting = radio.state === 'buffering';
    $('radio').classList.toggle('wait', waiting);
    $('rstate').textContent = waiting ? 'Menyambung' : 'On air';
    $('rname').textContent = radio.name || 'Radio';
    var song = splitSong(radio.title);
    $('radio').classList.toggle('noname', !song.title);
    $('rtitle').textContent = song.title || radio.name || 'Radio';
    $('rartist').textContent = song.title ? (song.artist || 'Siaran langsung') : 'Siaran langsung';
  }
  function pollRadio() {
    if (!AND || !AND.radioInfo) return;
    var j; try { j = JSON.parse(AND.radioInfo()); } catch (e) { return; }
    var was = radioShown();
    var key = radio.state + '|' + radio.name + '|' + radio.title;
    radio = { active: !!j.active, state: j.state || 'none', name: j.name || '', title: j.title || '' };
    if (radioShown() !== was) { layout(); if (radioShown()) startViz(); }
    if (radioShown() && key !== radio.state + '|' + radio.name + '|' + radio.title) { renderRadioText(); layout(); }
  }
  // animasi batang mengikuti suara radio
  var vizRun = false, vCur = [], vPeak = [], vRunMax = 60, vLast = 0, vIdle = 0;
  var VIZ_DELAY = 320;   // ms — kira-kira jeda dari pemutar sampai suara keluar speaker
  function sizeViz() {
    var c = $('rviz'), dpr = window.devicePixelRatio || 1;
    var w = c.clientWidth, h = c.clientHeight;
    if (!w || !h) { setTimeout(sizeViz, 60); return; }
    c.width = Math.round(w * dpr); c.height = Math.round(h * dpr);
  }
  function startViz() {
    if (vizRun) return;
    vizRun = true;
    requestAnimationFrame(drawViz);
  }
  function drawViz(ts) {
    if (!radioShown()) { vizRun = false; return; }
    requestAnimationFrame(drawViz);
    if (ts - vLast < 30) return;     // ±33 fps cukup halus dan hemat baterai
    vLast = ts;
    var c = $('rviz'), g = c.getContext('2d');
    var W = c.width, H = c.height;
    if (!W || !H) return;
    var raw = AND && AND.radioViz ? AND.radioViz(VIZ_DELAY) : '';
    var lv = raw ? raw.split(',').map(Number) : null;
    var n = 32;
    // jumlah batang menyesuaikan lebar
    var bars = Math.max(16, Math.min(48, Math.round(W / (window.devicePixelRatio || 1) / 16)));
    var tgt = [];
    var fmax = 0;
    for (var i = 0; i < bars; i++) {
      var v;
      if (lv && lv.length === n) {
        var x = i * (n - 1) / (bars - 1), a = Math.floor(x), f = x - a;
        v = lv[a] * (1 - f) + (lv[Math.min(n - 1, a + 1)] || 0) * f;
      } else {
        vIdle += 0.0009;
        v = 6 + 5 * Math.sin(vIdle * 40 + i * 0.45);   // menunggu suara: gelombang pelan
      }
      tgt.push(v);
      if (v > fmax) fmax = v;
    }
    if (lv) vRunMax = Math.max(fmax, vRunMax * 0.996);
    var scale = lv ? 1 / Math.max(vRunMax, 40) : 1 / 100;
    var gap = W / bars * 0.28, bw = W / bars - gap;
    var baseY = H * 0.97, maxH = H * 0.92;
    g.clearRect(0, 0, W, H);
    var css = getComputedStyle(document.documentElement);
    var acc = css.getPropertyValue('--accent').trim() || '#F2A33A';
    var txt = css.getPropertyValue('--text').trim() || '#ECEAE4';
    var grad = g.createLinearGradient(0, baseY - maxH, 0, baseY);
    grad.addColorStop(0, acc); grad.addColorStop(1, hexA(acc, 0.55));
    for (var k = 0; k < bars; k++) {
      var t = Math.min(1, tgt[k] * scale);
      t = Math.pow(t, 1.35);
      var cur = vCur[k] || 0;
      cur = t > cur ? cur + (t - cur) * 0.6 : cur * 0.88 + t * 0.12;
      vCur[k] = cur;
      var pk = vPeak[k] || 0;
      pk = cur > pk ? cur : Math.max(0, pk - 0.012);
      vPeak[k] = pk;
      var x0 = k * (bw + gap) + gap / 2;
      var bh = Math.max(bw * 0.35, cur * maxH);
      var r = Math.min(bw / 2, 6 * (window.devicePixelRatio || 1));
      g.fillStyle = grad;
      roundRect(g, x0, baseY - bh, bw, bh, r);
      if (lv && pk > 0.04) {
        g.fillStyle = hexA(txt, 0.75);
        g.fillRect(x0, baseY - pk * maxH - Math.max(2, bw * 0.16) - 3, bw, Math.max(2, bw * 0.16));
      }
    }
  }
  function roundRect(g, x, y, w, h, r) {
    g.beginPath();
    g.moveTo(x + r, y); g.lineTo(x + w - r, y); g.quadraticCurveTo(x + w, y, x + w, y + r);
    g.lineTo(x + w, y + h); g.lineTo(x, y + h); g.lineTo(x, y + r); g.quadraticCurveTo(x, y, x + r, y);
    g.closePath(); g.fill();
  }
  function hexA(hex, a) {
    var m = String(hex).replace('#', '');
    if (m.length === 3) m = m[0] + m[0] + m[1] + m[1] + m[2] + m[2];
    var n = parseInt(m, 16);
    if (isNaN(n)) return 'rgba(242,163,58,' + a + ')';
    return 'rgba(' + (n >> 16 & 255) + ',' + (n >> 8 & 255) + ',' + (n & 255) + ',' + a + ')';
  }
  setInterval(pollRadio, 1000);
  setTimeout(pollRadio, 300);

  function tick(animate) {
    var d = new Date();
    var h = d.getHours();
    var ap = h < 12 ? 'AM' : 'PM';
    if (!S.f24) { h = h % 12; if (h === 0) h = 12; }
    var changed = false;
    changed = cardH.set(pad(h), animate) || changed;
    changed = cardM.set(pad(d.getMinutes()), animate) || changed;
    if (S.sec) changed = cardS.set(pad(d.getSeconds()), animate) || changed;
    $('ampm').textContent = ap;
    var dl = HARI[d.getDay()] + ' · ' + d.getDate() + ' ' + BULAN[d.getMonth()] + ' ' + d.getFullYear();
    var dateEl = $('dateLine');
    if (dateEl.textContent !== dl) { dateEl.textContent = dl; layout(); }
    if (changed) click();
    applyNight(d);
  }

  var lastNight = null;
  function applyNight(d) {
    var hr = d.getHours();
    var isNight = !!S.night && (hr >= 22 || hr < 5);
    if (isNight === lastNight) return;
    lastNight = isNight;
    $('dim').classList.toggle('on', isNight);
    applyBrightness();
  }

  function applyBrightness() {
    if (!AND || !AND.setBrightness) return;
    var lvl = S.brightManual ? S.bright / 100 : -1;
    if (lastNight) lvl = Math.min(lvl < 0 ? 0.15 : lvl, 0.15);
    AND.setBrightness(lvl);
  }

  function applyAwake() {
    if (AND && AND.setKeepScreenOn) AND.setKeepScreenOn(!!S.awake);
  }

  function renderAll() {
    applyTheme();
    cardH.val = cardM.val = cardS.val = null;
    tick(false);
    layout();
  }

  // ---------------------------------------------------------------- clock loop
  function loop() {
    tick(true);
    var ms = 1000 - (Date.now() % 1000);
    setTimeout(loop, ms + 5);
  }

  // ---------------------------------------------------------------- remote (Firebase REST + SSE)
  var es = null, retryT = null, hbT = null, pushT = null;
  var netState = 'off', lastOk = 0;

  function setNet(st) {
    netState = st;
    if (st === 'online') lastOk = Date.now();
    updateNetUi();
  }

  function updateNetUi() {
    var dot = $('netDotSmall'), txt = $('netText');
    var big = $('netdot');
    if (!S.remote) {
      dot.className = 'sdot'; txt.textContent = 'Remote dimatikan';
    } else if (!dbBase()) {
      dot.className = 'sdot err'; txt.textContent = 'Alamat Firebase belum diisi (lihat Pengaturan lanjutan)';
    } else if (netState === 'online') {
      dot.className = 'sdot ok'; txt.textContent = 'Terhubung ke server';
    } else if (netState === 'connecting') {
      dot.className = 'sdot wait'; txt.textContent = 'Menghubungkan…';
    } else {
      dot.className = 'sdot err'; txt.textContent = 'Terputus — mencoba lagi otomatis';
    }
    var showBig = S.remote && !!dbBase() && netState !== 'online' && (Date.now() - lastOk > 30000);
    big.classList.toggle('on', showBig);
  }

  function stopRemote() {
    if (es) { try { es.close(); } catch (e) { } es = null; }
    if (esCmd) { try { esCmd.close(); } catch (e) { } esCmd = null; }
    clearTimeout(retryT); retryT = null;
    clearInterval(hbT); hbT = null;
  }

  function scheduleRetry() {
    if (es) { try { es.close(); } catch (e) { } es = null; }
    if (esCmd) { try { esCmd.close(); } catch (e) { } esCmd = null; }
    clearTimeout(retryT);
    retryT = setTimeout(startRemote, 8000);
  }

  // Versi aplikasi baru: perintah, status musik & heartbeat ditangani layanan latar belakang (Java)
  var NATIVE = !!(AND && AND.setRemote);
  function syncService() {
    if (NATIVE) { try { AND.setRemote(!!S.remote && !!dbBase(), dbBase(), S.key, S.name || 'Tablet'); } catch (e) { } }
  }

  function startRemote() {
    syncService();
    stopRemote();
    if (!S.remote || !dbBase()) { setNet('off'); return; }
    setNet('connecting');
    var url = dbBase() + '/clocks/' + S.key + '/config.json';
    try { es = new EventSource(url); } catch (e) { setNet('error'); scheduleRetry(); return; }

    function onData(ev, isPatch) {
      var msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      if (!msg) return;
      setNet('online');
      applyRemote(msg.path, msg.data, isPatch);
    }
    es.addEventListener('put', function (ev) { onData(ev, false); });
    es.addEventListener('patch', function (ev) { onData(ev, true); });
    es.addEventListener('keep-alive', function () { setNet('online'); });
    es.addEventListener('cancel', function () { setNet('error'); scheduleRetry(); });
    es.addEventListener('auth_revoked', function () { setNet('error'); scheduleRetry(); });
    es.onopen = function () { setNet('online'); };
    es.onerror = function () {
      setNet('error');
      if (!es || es.readyState === 2) scheduleRetry();
    };

    if (!NATIVE) {
      startCmdStream();
      heartbeat();
      hbT = setInterval(heartbeat, 60000);
    }
  }

  function applyRemote(path, data, isPatch) {
    var changed = false;
    if (path === '/' || path === '' || !path) {
      if (data === null) { pushConfig(true); return; }
      if (typeof data === 'object') {
        for (var i = 0; i < REMOTE_FIELDS.length; i++) {
          var f = REMOTE_FIELDS[i];
          if (data.hasOwnProperty(f)) changed = setRemoteField(f, data[f]) || changed;
        }
      }
    } else {
      var field = path.replace(/^\//, '').split('/')[0];
      if (REMOTE_FIELDS.indexOf(field) >= 0) changed = setRemoteField(field, data) || changed;
    }
    if (changed) {
      save();
      renderAll();
      if (panelOpen) syncPanel();
    }
  }

  function setRemoteField(f, v) {
    if (f === 'custom') { v = !!v; }
    else if (f === 'customText') { if (typeof v !== 'string') return false; v = v.substr(0, 150); }
    else { if (!isHex(v)) return false; v = v.toUpperCase(); }
    if (S[f] === v) return false;
    S[f] = v;
    return true;
  }

  function remotePayload() {
    var o = {};
    for (var i = 0; i < REMOTE_FIELDS.length; i++) o[REMOTE_FIELDS[i]] = S[REMOTE_FIELDS[i]];
    o.updatedAt = { '.sv': 'timestamp' };
    o.by = 'tablet';
    return o;
  }

  function pushConfig(now) {
    if (!S.remote || !dbBase()) return;
    clearTimeout(pushT);
    pushT = setTimeout(function () {
      fetch(dbBase() + '/clocks/' + S.key + '/config.json', {
        method: 'PUT', body: JSON.stringify(remotePayload())
      }).catch(function () { });
    }, now ? 0 : 900);
  }

  function heartbeat() {
    if (!S.remote || !dbBase()) return;
    fetch(dbBase() + '/clocks/' + S.key + '/status.json', {
      method: 'PUT',
      body: JSON.stringify({
        name: S.name || 'Tablet', lastSeen: { '.sv': 'timestamp' }, ver: '2.0',
        batt: AND && AND.battery ? AND.battery() : -1,
        charging: AND && AND.charging ? !!AND.charging() : false
      })
    }).then(function (r) {
      if (r.ok) { if (netState !== 'online') setNet('online'); }
    }).catch(function () { });
  }

  // ---------------------------------------------------------------- remote commands (v2)
  // clocks/<key>/cmd    : {id, type, ts, ...}  — ditulis halaman remote
  // clocks/<key>/cmdAck : {id, ok, msg, at}    — balasan tablet
  // clocks/<key>/now    : status musik/suara    — ditulis tablet
  var esCmd = null, cmdFirst = true;

  function startCmdStream() {
    if (!S.remote || !dbBase()) return;
    cmdFirst = true;
    try { esCmd = new EventSource(dbBase() + '/clocks/' + S.key + '/cmd.json'); } catch (e) { return; }
    function onCmd(ev) {
      var msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      if (!msg || !msg.data || typeof msg.data !== 'object') { cmdFirst = false; return; }
      var c = msg.data;
      var isFirst = cmdFirst;
      cmdFirst = false;
      if (msg.path !== '/' || !c.id || c.id === S.lastCmd) return;
      // perintah lama (tablet sempat offline) jangan dijalankan, terutama pengumuman suara
      var age = typeof c.ts === 'number' ? Math.abs(Date.now() - c.ts) : 0;
      if (isFirst && age > 120000) { S.lastCmd = c.id; save(); return; }
      if (age > 600000) { S.lastCmd = c.id; save(); return; }
      S.lastCmd = c.id; save();
      var res = runCommand(c);
      ack(c.id, res);
      setTimeout(pollNow, 1200);
      setTimeout(pollNow, 4000);
    }
    esCmd.addEventListener('put', onCmd);
    esCmd.addEventListener('patch', function () { });
    esCmd.onerror = function () { if (esCmd && esCmd.readyState === 2) { esCmd = null; setTimeout(function () { if (!esCmd && S.remote) startCmdStream(); }, 8000); } };
  }

  function ack(id, res) {
    if (!dbBase()) return;
    fetch(dbBase() + '/clocks/' + S.key + '/cmdAck.json', {
      method: 'PUT', body: JSON.stringify({ id: id, ok: res.ok, msg: res.msg || '', at: { '.sv': 'timestamp' } })
    }).catch(function () { });
  }

  var SOURCES = ['youtube', 'ytmusic', 'spotify'];

  function qp(url, name) {
    var m = new RegExp('[?&#]' + name + '=([^&#]+)').exec(url);
    return m ? decodeURIComponent(m[1]) : '';
  }

  /** Rapikan link supaya langsung diputar di aplikasi tujuan. */
  function normalizeLink(src, link) {
    link = String(link || '').trim();
    var out = { link: link, uri: '' };
    if (!link) return out;
    if (src === 'spotify') {
      var m = /open\.spotify\.com\/(?:intl-[a-z]{2}\/)?(playlist|album|track|artist|show|episode)\/([A-Za-z0-9]+)/.exec(link);
      var u = /^spotify:(playlist|album|track|artist|show|episode):([A-Za-z0-9]+)/.exec(link);
      if (m) { out.link = 'https://open.spotify.com/' + m[1] + '/' + m[2]; out.uri = 'spotify:' + m[1] + ':' + m[2]; }
      else if (u) { out.link = 'https://open.spotify.com/' + u[1] + '/' + u[2]; out.uri = link; }
      return out;
    }
    var list = qp(link, 'list');
    var v = qp(link, 'v');
    if (!v) { var yb = /youtu\.be\/([A-Za-z0-9_-]{6,})/.exec(link); if (yb) v = yb[1]; }
    var browse = /music\.youtube\.com\/browse\/VL([A-Za-z0-9_-]+)/.exec(link);
    if (!list && browse) list = browse[1];
    var host = src === 'ytmusic' ? 'https://music.youtube.com' : 'https://m.youtube.com';
    if (list) out.link = host + '/watch?list=' + encodeURIComponent(list) + (v ? '&v=' + encodeURIComponent(v) : '');
    else if (v) out.link = host + '/watch?v=' + encodeURIComponent(v);
    return out;
  }

  function homeLink(src) {
    if (src === 'spotify') return 'https://open.spotify.com/';
    if (src === 'ytmusic') return 'https://music.youtube.com/';
    return 'https://m.youtube.com/';
  }

  function runCommand(c) {
    if (!AND) return { ok: false, msg: 'Hanya jalan di aplikasi tablet' };
    try {
      switch (c.type) {
        case 'speak': {
          var text = String(c.text || '').substr(0, 200).trim();
          if (!text) return { ok: false, msg: 'Teks kosong' };
          var st = AND.ttsStatus ? AND.ttsStatus() : 'ok';
          AND.speak(text, c.bell !== false, c.times === 2 ? 2 : 1);
          if (st === 'no-lang') return { ok: true, msg: 'Dibacakan, tapi suara Indonesia belum terpasang di tablet' };
          if (st === 'not-ready') return { ok: false, msg: 'Mesin suara tablet belum siap' };
          return { ok: true, msg: 'Dibacakan' };
        }
        case 'media': {
          var r = AND.mediaCommand(S.musicSrc, String(c.cmd || ''));
          return { ok: r.indexOf('ok') === 0, msg: r };
        }
        case 'play': {
          var src = SOURCES.indexOf(c.src) >= 0 ? c.src : 'youtube';
          var n = normalizeLink(src, c.link);
          if (!n.link) return { ok: false, msg: 'Link kosong' };
          S.musicSrc = src; S.musicName = String(c.name || ''); save();
          var r2 = (src === 'spotify' && AND.playSpotify)
            ? AND.playSpotify(n.link, n.uri, String(c.name || ''), String(c.method || 'auto'))
            : AND.playLink(src, n.link, n.uri);
          var warn = AND.isSplit && !AND.isSplit() ? ' (layar tablet belum terbagi)' : '';
          return { ok: r2.indexOf('ok') === 0, msg: r2 + warn };
        }
        case 'open': {
          var src2 = SOURCES.indexOf(c.src) >= 0 ? c.src : 'youtube';
          S.musicSrc = src2; S.musicName = ''; save();
          var r3 = AND.openSource(src2, homeLink(src2));
          return { ok: r3.indexOf('ok') === 0, msg: r3 };
        }
        case 'volume': {
          AND.setVolume(Math.max(0, Math.min(100, parseInt(c.pct, 10) || 0)));
          return { ok: true, msg: 'Volume ' + c.pct + '%' };
        }
        default:
          return { ok: false, msg: 'Perintah tidak dikenal' };
      }
    } catch (e) {
      return { ok: false, msg: 'Error: ' + e.message };
    }
  }

  // ---- kirim status musik ke remote bila berubah
  var lastNowKey = '', lastArt = '', speakingNow = false;
  function pollNow() {
    if (NATIVE) { if (panelOpen) syncMusicPanel(); return; }
    if (!AND || !AND.mediaInfo || !S.remote || !dbBase()) return;
    var info = {};
    try { info = JSON.parse(AND.mediaInfo(S.musicSrc)) || {}; } catch (e) { info = {}; }
    var now = {
      src: info.src || S.musicSrc, want: S.musicSrc, playlist: S.musicName || '',
      state: info.state || 'none', title: info.title || '', artist: info.artist || '',
      dur: info.dur || 0, pos: info.pos || 0,
      access: !!info.access, split: !!info.split, vol: typeof info.vol === 'number' ? info.vol : -1,
      tts: AND.ttsStatus ? AND.ttsStatus() : '', speaking: speakingNow,
      acts: info.acts || '', spres: info.spres || '', queue: info.queue || '', ver: '1.4'
    };
    var key = [now.src, now.state, now.title, now.artist, now.access, now.split, now.vol, now.tts, now.speaking, now.playlist, now.acts, now.spres, now.queue].join('|');
    var art = info.art || '';
    if (key === lastNowKey && art === lastArt) return;
    lastNowKey = key;
    now.art = art;
    lastArt = art;
    now.at = { '.sv': 'timestamp' };
    fetch(dbBase() + '/clocks/' + S.key + '/now.json', { method: 'PUT', body: JSON.stringify(now) }).catch(function () { lastNowKey = ''; });
    if (panelOpen) syncMusicPanel();
  }
  setInterval(pollNow, 2500);

  window.onSpeakState = function (b) {
    speakingNow = !!b;
    document.body.classList.toggle('speaking', speakingNow);
    pollNow();
  };
  window.onSplitChange = function () {
    setTimeout(layout, 50);
    setTimeout(layout, 400);
    pollNow();
  };

  // ---------------------------------------------------------------- settings panel
  var panelOpen = false;
  var panel = $('panel');

  function openPanel() {
    panelOpen = true;
    panel.classList.remove('hidden');
    document.body.classList.add('panel-open');
    hideGear();
    syncPanel();
    setTimeout(layout, 30);
  }
  function closePanel() {
    panelOpen = false;
    var a = document.activeElement;
    if (a && a.blur) a.blur();
    panel.classList.add('hidden');
    document.body.classList.remove('panel-open');
    setTimeout(layout, 30);
  }
  window.onBack = function () {
    if (panelOpen) { closePanel(); return true; }
    return false;
  };

  function changed(remoteField) {
    save();
    renderAll();
    syncPanel();
    if (remoteField) pushConfig(false);
  }

  function buildPanel() {
    // theme presets
    var seg = $('themeSeg');
    PRESETS.forEach(function (p) {
      var b = document.createElement('button');
      b.textContent = p.label;
      b.setAttribute('data-id', p.id);
      b.addEventListener('click', function () {
        for (var k in p.v) S[k] = p.v[k];
        changed(true);
      });
      seg.appendChild(b);
    });

    // color groups
    var box = $('colorBox');
    COLOR_GROUPS.forEach(function (g) {
      var key = g[0];
      var wrap = document.createElement('div');
      wrap.className = 'cgroup';
      wrap.innerHTML = '<div class="chead"><div class="rl"></div><div class="chex"></div></div><div class="swatches"></div>';
      wrap.querySelector('.rl').textContent = g[1];
      var sws = wrap.querySelector('.swatches');
      PALETTES[key].forEach(function (c) {
        var b = document.createElement('button');
        b.className = 'swatch';
        b.style.background = c[1];
        b.setAttribute('aria-label', g[1] + ': ' + c[0]);
        b.setAttribute('data-hex', c[1]);
        b.addEventListener('click', function () { S[key] = c[1]; changed(true); });
        sws.appendChild(b);
      });
      var cp = document.createElement('input');
      cp.type = 'color';
      cp.className = 'cpick';
      cp.setAttribute('aria-label', g[1] + ' kustom');
      cp.addEventListener('input', function () { S[key] = cp.value.toUpperCase(); save(); renderAll(); });
      cp.addEventListener('change', function () { S[key] = cp.value.toUpperCase(); changed(true); });
      sws.appendChild(cp);
      wrap.setAttribute('data-key', key);
      box.appendChild(wrap);
    });

    // toggles
    var tb = $('toggleBox');
    TOGGLES.forEach(function (t) {
      var lab = document.createElement('label');
      lab.className = 'row';
      lab.innerHTML = '<div class="rtxt"><div class="rl"></div><div class="rd"></div></div><input type="checkbox" class="sw">';
      lab.querySelector('.rl').textContent = t[1];
      lab.querySelector('.rd').textContent = t[2];
      var cb = lab.querySelector('input');
      cb.setAttribute('data-key', t[0]);
      cb.addEventListener('change', function () {
        S[t[0]] = cb.checked;
        if (t[0] === 'awake') applyAwake();
        if (t[0] === 'night') { lastNight = null; }
        if (t[0] === 'sound' && cb.checked) click();
        if (t[0] === 'radioViz' && cb.checked) { renderRadioText(); startViz(); }
        changed(false);
      });
      tb.appendChild(lab);
    });

    // custom text
    $('customOn').addEventListener('change', function () { S.custom = this.checked; changed(true); });
    $('customText').addEventListener('input', function () {
      S.customText = this.value.substr(0, 150);
      save(); renderAll(); pushConfig(false);
    });

    // remote
    $('remoteOn').addEventListener('change', function () {
      S.remote = this.checked; save(); syncPanel(); startRemote();
    });
    $('devName').addEventListener('change', function () {
      S.name = this.value.trim() || 'Tablet'; save(); syncPanel(); syncService(); if (!NATIVE) heartbeat();
    });
    $('dbUrl').addEventListener('change', function () {
      S.dbUrl = this.value.trim(); save(); syncPanel(); startRemote();
    });
    $('remoteUrl').addEventListener('change', function () {
      S.remoteUrl = this.value.trim(); save(); syncPanel();
    });
    $('newKeyBtn').addEventListener('click', function () {
      if (!window.confirm('Buat kode perangkat baru? Link/QR lama tidak akan berfungsi lagi.')) return;
      S.key = makeKey(); save(); syncPanel(); startRemote(); pushConfig(true);
    });

    // brightness
    $('brightOn').addEventListener('change', function () {
      S.brightManual = this.checked; save(); syncPanel(); applyBrightness();
    });
    $('bright').addEventListener('input', function () {
      S.bright = parseInt(this.value, 10) || 80;
      $('brightVal').textContent = S.bright + '%';
      save(); applyBrightness();
    });

    // autostart
    $('autoOn').addEventListener('change', function () {
      if (AND && AND.setAutostart) AND.setAutostart(this.checked);
      syncPanel();
    });
    $('accBtn').addEventListener('click', function () { if (AND && AND.openMediaAccess) AND.openMediaAccess(); });
    $('ttsBtn').addEventListener('click', function () { if (AND && AND.openTtsSettings) AND.openTtsSettings(); });
    var modeBtns = $('modeSeg').getElementsByTagName('button');
    for (var mb = 0; mb < modeBtns.length; mb++) {
      modeBtns[mb].addEventListener('click', function () {
        var m = this.getAttribute('data-mode');
        if (AND && AND.setOverlay) {
          var r = AND.setOverlay(m, parseInt($('ovPct').value, 10) || 25);
          if (r !== 'ok' && m !== 'off' && AND.requestOverlayPermission) AND.requestOverlayPermission();
        }
        syncPanel();
      });
    }
    $('ovPct').addEventListener('change', function () {
      var m = AND && AND.overlayMode ? AND.overlayMode() : 'off';
      if (AND && AND.setOverlay) AND.setOverlay(m === 'off' ? 'off' : m, parseInt(this.value, 10) || 25);
      if (m !== 'off') syncPanel();
    });
    $('ovBtn').addEventListener('click', function () { if (AND && AND.requestOverlayPermission) AND.requestOverlayPermission(); });
    $('kfBtn').addEventListener('click', function () { if (AND && AND.requestOverlayPermission) AND.requestOverlayPermission(); });
    $('keepFront').addEventListener('change', function () {
      if (AND && AND.setKeepFront) AND.setKeepFront(this.checked);
      syncPanel();
    });
    $('ttsTest').addEventListener('click', function () {
      var t = hasCustom() ? String(S.customText).trim() : 'Tes suara pengumuman Flip Clock';
      if (AND && AND.speak) AND.speak(t, true, 1);
    });
    $('overlayBtn').addEventListener('click', function () {
      if (AND && AND.requestOverlayPermission) AND.requestOverlayPermission();
    });

    $('closeBtn').addEventListener('click', closePanel);
  }

  function syncMusicPanel() {
    var acc = AND && AND.hasMediaAccess ? AND.hasMediaAccess() : false;
    var split = AND && AND.isSplit ? AND.isSplit() : false;
    var tts = AND && AND.ttsStatus ? AND.ttsStatus() : 'ok';
    $('accDot').className = 'sdot ' + (acc ? 'ok' : 'err');
    $('accText').textContent = acc ? 'Izin kontrol musik: aktif' : 'Izin kontrol musik: belum diberikan';
    $('accBtn').classList.toggle('hidden', acc);
    $('splitDot').className = 'sdot ' + (split ? 'ok' : 'wait');
    $('splitText').textContent = split ? 'Layar terbagi: aktif' : 'Layar terbagi: belum aktif';
    $('ttsDot').className = 'sdot ' + (tts === 'ok' ? 'ok' : 'err');
    $('ttsText').textContent = tts === 'ok' ? 'Suara bahasa Indonesia: siap' : (tts === 'no-lang' ? 'Suara bahasa Indonesia: belum terpasang' : 'Mesin suara: belum siap');
    $('ttsBtn').classList.toggle('hidden', tts === 'ok');
    var om = AND && AND.overlayMode ? AND.overlayMode() : 'off';
    var mbs = $('modeSeg').getElementsByTagName('button');
    for (var q = 0; q < mbs.length; q++) mbs[q].classList.toggle('on', mbs[q].getAttribute('data-mode') === om);
    if (AND && AND.overlayPct) $('ovPct').value = String(AND.overlayPct());
    var canOvl = AND && AND.canOverlay ? AND.canOverlay() : true;
    $('ovWarn').classList.toggle('hidden', canOvl);
    var kf = AND && AND.getKeepFront ? AND.getKeepFront() : true;
    $('keepFront').checked = !!kf;
    var canOv = AND && AND.canAutostartFromBoot ? AND.canAutostartFromBoot() : true;
    $('kfWarn').classList.toggle('hidden', !kf || canOv);
  }

  var lastQr = '';
  function syncPanel() {
    // theme
    var btns = $('themeSeg').getElementsByTagName('button');
    for (var i = 0; i < btns.length; i++) {
      var p = PRESETS[i];
      var on = p.v.textColor === S.textColor && p.v.cardColor === S.cardColor && p.v.bgColor === S.bgColor;
      btns[i].classList.toggle('on', on);
    }
    // colors
    var groups = $('colorBox').getElementsByClassName('cgroup');
    for (var g = 0; g < groups.length; g++) {
      var key = groups[g].getAttribute('data-key');
      groups[g].querySelector('.chex').textContent = String(S[key]).toUpperCase();
      var sws = groups[g].getElementsByClassName('swatch');
      for (var s = 0; s < sws.length; s++) {
        sws[s].classList.toggle('on', sws[s].getAttribute('data-hex').toUpperCase() === String(S[key]).toUpperCase());
      }
      var cp = groups[g].querySelector('.cpick');
      if (document.activeElement !== cp) cp.value = String(S[key]).toLowerCase();
    }
    // toggles
    var cbs = $('toggleBox').getElementsByTagName('input');
    for (var c = 0; c < cbs.length; c++) cbs[c].checked = !!S[cbs[c].getAttribute('data-key')];
    // custom
    $('customOn').checked = !!S.custom;
    var ct = $('customText');
    ct.disabled = !S.custom;
    if (document.activeElement !== ct) ct.value = S.customText || '';
    // remote
    $('remoteOn').checked = !!S.remote;
    $('remoteDetail').style.display = S.remote ? '' : 'none';
    var dn = $('devName');
    if (document.activeElement !== dn) dn.value = S.name || '';
    $('devCode').textContent = devCode();
    var du = $('dbUrl');
    if (document.activeElement !== du) du.value = dbBase();
    var ru = $('remoteUrl');
    if (document.activeElement !== ru) ru.value = remotePage();
    updateQr();
    updateNetUi();
    syncMusicPanel();
    // brightness
    $('brightOn').checked = !!S.brightManual;
    $('bright').value = S.bright;
    $('bright').disabled = !S.brightManual;
    $('brightVal').textContent = S.bright + '%';
    $('bright').parentNode.classList.toggle('off', !S.brightManual);
    // autostart
    var auto = AND && AND.getAutostart ? AND.getAutostart() : true;
    $('autoOn').checked = !!auto;
    var canBoot = AND && AND.canAutostartFromBoot ? AND.canAutostartFromBoot() : true;
    $('overlayWarn').classList.toggle('hidden', !auto || canBoot);
  }

  function remoteLink() {
    var page = remotePage();
    if (!page || !dbBase()) return '';
    return page + '#db=' + encodeURIComponent(dbBase()) + '&k=' + S.key + '&n=' + encodeURIComponent(S.name || 'Tablet');
  }

  function updateQr() {
    var link = remoteLink();
    if (link === lastQr) return;
    lastQr = link;
    var box = $('qr');
    if (!link) { box.textContent = 'Isi alamat Firebase dulu'; return; }
    try {
      var qr = window.qrcode(0, 'M');
      qr.addData(link);
      qr.make();
      box.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 0, scalable: true });
    } catch (e) {
      box.textContent = 'QR gagal dibuat';
    }
  }

  // ---------------------------------------------------------------- gear button
  var gearT = null;
  function showGear() {
    $('gear').classList.add('show');
    clearTimeout(gearT);
    gearT = setTimeout(hideGear, 4000);
  }
  function hideGear() { $('gear').classList.remove('show'); }

  var IS_OVERLAY = /[?&]overlay=1/.test(location.search);
  if (IS_OVERLAY) document.body.classList.add('overlay');
  $('stage').addEventListener('click', function (e) {
    if (IS_OVERLAY) { if (AND && AND.openApp) AND.openApp(); return; }
    if (panelOpen) { closePanel(); return; }
    if (e.target.closest && e.target.closest('#gear')) return;
    showGear();
    // unlock audio on first tap
    if (S.sound && audioCtx && audioCtx.state === 'suspended') audioCtx.resume();
  });
  $('gear').addEventListener('click', function (e) { e.stopPropagation(); openPanel(); });

  // ---------------------------------------------------------------- start
  window.addEventListener('resize', function () { layout(); });
  window.onAppResume = function () {
    renderAll();
    applyAwake();
    lastNight = null;
    applyNight(new Date());
    if (S.remote && dbBase() && (!es || es.readyState === 2)) startRemote();
    if (panelOpen) syncPanel();
  };
  window.addEventListener('online', function () { if (S.remote) startRemote(); });
  setInterval(updateNetUi, 10000);

  buildPanel();
  renderAll();
  applyAwake();
  applyBrightness();
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(layout);
  setTimeout(loop, 1000 - (Date.now() % 1000) + 5);
  startRemote();
})();
