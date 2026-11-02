/* Työmatka-raportin tehostus: osio kerrallaan, valikko, haku, välilehdet, päivät erissä ja kuvanäkymä.
   Raportti on valmis HTML, eikä skripti tee dataa: ilman skriptiä kaikki näkyy pitkänä sivuna. */
(function () {
  'use strict';
  const NB = String.fromCharCode(160);
  const PAGE = 31;
  const doc = document;
  const root = doc.documentElement;
  const $ = id => doc.getElementById(id);
  const all = (selector, base) => Array.prototype.slice.call((base || doc).querySelectorAll(selector));
  const T = JSON.parse($('report-text').textContent);
  const lang = root.lang;
  const report = $('report');
  const bar = doc.querySelector('.bar');
  const searchwrap = $('searchwrap');
  const drawer = $('drawer');
  const lb = $('lightbox');
  const menuBtn = $('menuBtn');
  const q = $('q');
  const baseTitle = doc.title;

  // Luvut ja mallitekstit kuten raportissa: suomeksi ja ruotsiksi sitova väli 10 000:sta alkaen, englanniksi pilkku.
  const num = n => lang === 'en' ? n.toLocaleString('en-GB')
    : (n < 10000 ? String(n) : String(n).replace(/\B(?=(\d{3})+(?!\d))/g, NB));
  const count = (n, word) => num(n) + NB + (n === 1 ? word[0] : word[1]);
  const fill = (template, ...args) => args.reduce((text, arg, i) => text.split('{' + i + '}').join(arg), template);

  // Kiinnitetyn yläpalkin ja haun korkeus: haku palkin alle ja ankkurit niiden alle (WCAG 2.4.11).
  function stickyOffsets() {
    const h = bar.offsetHeight;
    root.style.setProperty('--bar-height', h + 'px');
    root.style.scrollPaddingTop = getComputedStyle(bar).position === 'sticky' ? (h + searchwrap.offsetHeight + 8) + 'px' : '';
  }
  if (window.ResizeObserver) {
    const observer = new ResizeObserver(stickyOffsets);
    observer.observe(bar);
    observer.observe(searchwrap);
  }
  window.addEventListener('resize', stickyOffsets);

  // Taustan sisältö on modaalin aikana inert: ei kohdistettavissa eikä ruudunlukijan luettavissa (WCAG 2.4.3, 4.1.2).
  const background = [doc.querySelector('.skip'), bar, searchwrap, $('main')];
  const setModal = on => background.forEach(el => { el.inert = on; });

  // ---------- Osio kerrallaan ----------
  const sections = all('main > section');
  const sectionIds = sections.map(s => s.id);
  let current = 's-summary';

  function show(id, focusHeading) {
    if (sectionIds.indexOf(id) < 0) id = 's-summary';
    current = id;
    sections.forEach(s => s.classList.toggle('active', s.id === id));
    all('a', drawer).forEach(a => {
      if (a.getAttribute('href') === '#' + id) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    });
    const title = $(id).dataset.title;
    $('where').textContent = id === 's-summary' ? '' : title;
    doc.title = id === 's-summary' ? baseTitle : title + ' – ' + baseTitle;
    if (focusHeading) focusSection();
  }

  function focusSection() {
    window.scrollTo(0, 0);
    $(current).querySelector('h2').focus({ preventScroll: true });
  }

  function goTo(id) {
    if (query()) { q.value = ''; runSearch(); }
    if (location.hash !== '#' + id) history.pushState(null, '', '#' + id);
    show(id, true);
  }

  // Avoin valikko tai kuvanäkymä saa oman historiamerkinnän: puhelimen takaisin-ele sulkee sen eikä vaihda osiota
  // taustalla. Napista suljettaessa merkintä poistetaan, eikä sen paluuta käsitellä.
  let overlayEntry = false;
  let ignorePop = false;
  function pushOverlay() {
    history.pushState({ overlay: true }, '');
    overlayEntry = true;
  }
  function dropOverlay() {
    if (!overlayEntry) return;
    overlayEntry = false;
    ignorePop = true;
    history.back();
  }
  // Selain voi siirtää kohdistuksen historiassa liikuttaessa vasta tämän käsittelijän jälkeen: kohdistus palautetaan
  // sen jälkeen, ja vierityksen hoitaa raportti itse.
  if ('scrollRestoration' in history) history.scrollRestoration = 'manual';
  window.addEventListener('popstate', () => {
    if (ignorePop) { ignorePop = false; return; }
    if (overlayEntry) {
      overlayEntry = false;
      if (!lb.hidden) {
        closePhotos(false, true);
        setTimeout(restoreFocus, 0);
      } else if (root.classList.contains('menu-open')) {
        closeMenu(false, true);
        setTimeout(() => menuBtn.focus(), 0);
      }
      return;
    }
    show(location.hash.slice(1), false);
    setTimeout(focusSection, 0);
  });
  doc.addEventListener('click', ev => {
    const a = ev.target.closest('a[href^="#s-"]');
    if (!a) return;
    ev.preventDefault();
    const id = a.getAttribute('href').slice(1);
    // Hakutuloksen linkki vie osion otsikkoon haun pysyessä päällä.
    if (a.closest('#found')) {
      const h = $(id).querySelector('h2');
      h.scrollIntoView({ block: 'start' });
      h.focus({ preventScroll: true });
      return;
    }
    if (root.classList.contains('menu-open') && overlayEntry && id !== current) {
      // Valikon merkintä vaihtuu valituksi osioksi: takaisin palaa edelliseen osioon.
      closeMenu(false, true);
      overlayEntry = false;
      if (query()) { q.value = ''; runSearch(); }
      history.replaceState(null, '', '#' + id);
      show(id, true);
      return;
    }
    if (root.classList.contains('menu-open')) closeMenu(false);
    goTo(id);
  });
  doc.querySelector('.skip').addEventListener('click', ev => {
    ev.preventDefault();
    const section = report.classList.contains('searching') ? $('s-days') : $(current);
    section.querySelector('h2').focus();
  });

  // ---------- Valikko ----------
  let lastFocus = null;
  function openMenu() {
    lastFocus = doc.activeElement;
    root.classList.add('menu-open');
    menuBtn.setAttribute('aria-expanded', 'true');
    setModal(true);
    drawer.inert = false;
    pushOverlay();
    setTimeout(() => (drawer.querySelector('[aria-current="page"]') || drawer.querySelector('a')).focus(), 20);
  }
  function closeMenu(restore, fromHistory) {
    root.classList.remove('menu-open');
    menuBtn.setAttribute('aria-expanded', 'false');
    setModal(false);
    drawer.inert = true;
    if (!fromHistory) dropOverlay();
    // Sulkeminen palauttaa kohdistuksen valikkonappiin (WCAG 2.4.3).
    if (restore !== false) menuBtn.focus();
  }
  menuBtn.addEventListener('click', openMenu);
  $('scrim').addEventListener('click', () => closeMenu());
  $('drawerClose').addEventListener('click', () => closeMenu());

  // ---------- Haku ----------
  const dayCards = all('#dayList > li');
  const entryCards = all('#fillList > li');
  const rows = all('#fillTable tbody tr');
  const query = () => q.value.trim().toLowerCase().split(NB).join(' ');
  let sayTimer = 0;
  // Tilailmoitus viiveellä, ettei ruudunlukija lue jokaista näppäilyä (WCAG 4.1.3).
  function say(build) {
    clearTimeout(sayTimer);
    sayTimer = setTimeout(() => {
      const found = $('found');
      found.textContent = '';
      build(found);
    }, 450);
  }
  function link(href, text) {
    const a = doc.createElement('a');
    a.href = href;
    a.textContent = text;
    return a;
  }
  function match(elements, text) {
    let hits = 0;
    elements.forEach(el => {
      const hit = !text || el.dataset.search.indexOf(text) >= 0;
      el.classList.toggle('nomatch', !hit);
      if (hit) hits++;
    });
    return hits;
  }
  function runSearch() {
    const text = query();
    const searching = text !== '';
    report.classList.toggle('searching', searching);
    $('clearQ').hidden = !searching;
    const days = match(dayCards, text);
    const entries = match(entryCards, text);
    match(rows, text);
    $('daysCount').textContent = searching ? num(days) + '/' + num(dayCards.length) : num(dayCards.length);
    $('fillsCount').textContent = searching ? num(entries) + '/' + num(entryCards.length) : num(entryCards.length);
    $('daysNoHits').hidden = !searching || days > 0 || dayCards.length === 0;
    $('fillsNoHits').hidden = !searching || entries > 0 || entryCards.length === 0;
    if (!searching) {
      clearTimeout(sayTimer);
      $('found').textContent = '';
      return;
    }
    say(found => {
      const parts = T.hits.split(/(\{[01]\})/);
      parts.forEach(part => {
        if (part === '{0}') found.appendChild(link('#s-days', count(days, T.day)));
        else if (part === '{1}') found.appendChild(link('#s-fills', count(entries, T.entry)));
        else if (part) found.appendChild(doc.createTextNode(part));
      });
    });
  }
  q.addEventListener('input', runSearch);
  $('clearQ').addEventListener('click', () => { q.value = ''; runSearch(); q.focus(); });

  // ---------- Päivät erissä ----------
  const more = $('moreDays');
  let shown = PAGE;
  function paginate() {
    dayCards.forEach((li, i) => li.classList.toggle('later', i >= shown));
    if (more) more.hidden = shown >= dayCards.length;
  }
  if (more) {
    more.addEventListener('click', () => {
      const first = shown;
      shown += PAGE;
      paginate();
      // Kohdistus ensimmäiseen uuteen päivään ja tilailmoitus ruudunlukijalle (WCAG 2.4.3, 4.1.3).
      say(found => { found.textContent = fill(T.shown, num(Math.min(shown, dayCards.length)), num(dayCards.length)); });
      if (dayCards[first]) dayCards[first].focus();
    });
  }

  // ---------- Välilehdet (ARIA-välilehtimalli) ----------
  const tabs = all('#periodTabs [role="tab"]');
  function selectTab(tab, focus) {
    tabs.forEach(t => {
      const on = t === tab;
      t.setAttribute('aria-selected', on ? 'true' : 'false');
      t.tabIndex = on ? 0 : -1;
      $(t.getAttribute('aria-controls')).classList.toggle('inactive', !on);
    });
    if (focus) tab.focus();
  }
  tabs.forEach(t => {
    const panel = $(t.getAttribute('aria-controls'));
    panel.setAttribute('role', 'tabpanel');
    panel.setAttribute('aria-labelledby', t.id);
    panel.tabIndex = 0;
  });
  if (tabs.length) {
    $('periodTabs').addEventListener('click', ev => {
      const t = ev.target.closest('[role="tab"]');
      if (t) selectTab(t, false);
    });
    // Nuolet, Home ja End.
    $('periodTabs').addEventListener('keydown', ev => {
      const i = tabs.indexOf(doc.activeElement);
      if (i < 0) return;
      const next = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: tabs.length - 1 }[ev.key];
      if (next === undefined) return;
      ev.preventDefault();
      selectTab(tabs[(next + tabs.length) % tabs.length], true);
    });
    selectTab(tabs[0], false);
  }

  // ---------- Kuvanäkymä ----------
  const thumbs = all('.thumb');
  let lbList = [];
  let lbAt = 0;
  // Täysikokoinen kuva luetaan piilotetusta säiliöstä vasta avattaessa; puuttuessa näytetään pienoiskuva.
  function fullSrc(id) {
    const store = $('full-photos');
    const img = store && store.content.getElementById('kuva-' + id);
    return img ? img.getAttribute('src') : null;
  }
  function showPhoto() {
    const thumb = lbList[lbAt];
    const small = thumb.querySelector('img.tn');
    const img = $('lbImg');
    const src = fullSrc(thumb.dataset.photo) || (small ? small.getAttribute('src') : null);
    if (src) img.setAttribute('src', src); else img.removeAttribute('src');
    img.alt = thumb.dataset.alt;
    $('lbTitle').textContent = fill(T.photoOf, num(lbAt + 1), num(lbList.length));
    $('lbCap').textContent = thumb.dataset.cap;
    const prev = $('lbPrev');
    const next = $('lbNext');
    const focused = doc.activeElement;
    prev.disabled = lbAt === 0;
    next.disabled = lbAt === lbList.length - 1;
    // Selauksen päässä kohdistus siirtyy toiseen nappiin, ettei se katoa piilotetun napin mukana.
    if (focused === prev && prev.disabled) (next.disabled ? $('lbClose') : next).focus();
    if (focused === next && next.disabled) (prev.disabled ? $('lbClose') : prev).focus();
  }
  // Avaaja otetaan napautetusta napista: Safari ja macOS:n Firefox eivät kohdista napautettua nappia, joten
  // activeElement olisi body.
  function openPhotos(list, at, opener) {
    if (!list.length) return;
    lastFocus = opener || doc.activeElement;
    lbList = list;
    lbAt = Math.max(0, at);
    showPhoto();
    $('lbStatus').textContent = '';
    lb.hidden = false;
    root.classList.add('modal-open');
    setModal(true);
    pushOverlay();
    $('lbClose').focus();
  }
  function closePhotos(restore, fromHistory) {
    lb.hidden = true;
    root.classList.remove('modal-open');
    setModal(false);
    $('lbImg').removeAttribute('src');
    if (!fromHistory) dropOverlay();
    if (restore !== false) restoreFocus();
  }
  // Avaaja voi olla piilossa, jos osio vaihtui: kohdistus näkyvän osion otsikkoon (WCAG 2.4.3). Kohdistus ei vieritä
  // sivua, joten pitkä galleria ei hyppää alkuun kuvan sulkeutuessa.
  function restoreFocus() {
    if (lastFocus && lastFocus !== doc.body && lastFocus.offsetParent !== null) lastFocus.focus({ preventScroll: true });
    else $(current).querySelector('h2').focus({ preventScroll: true });
  }
  const step = delta => {
    const at = lbAt + delta;
    if (at < 0 || at >= lbList.length) return;
    lbAt = at;
    showPhoto();
    // Selaus luetaan ruudunlukijalle: otsikko ja kuvateksti vaihtuvat muuten äänettä.
    $('lbStatus').textContent = $('lbTitle').textContent + ': ' + $('lbCap').textContent;
  };
  report.addEventListener('click', ev => {
    const button = ev.target.closest('.photo-btn');
    if (button) {
      openPhotos(thumbs.filter(t => t.dataset.entry === button.dataset.entry), 0, button);
      return;
    }
    const thumb = ev.target.closest('.thumb');
    if (thumb) openPhotos(thumbs, thumbs.indexOf(thumb), thumb);
  });
  $('lbClose').addEventListener('click', () => closePhotos());
  $('lbPrev').addEventListener('click', () => step(-1));
  $('lbNext').addEventListener('click', () => step(1));
  $('lbGo').addEventListener('click', () => {
    const entry = lbList[lbAt].dataset.entry;
    if (query()) { q.value = ''; runSearch(); }
    if (current !== 's-fills') {
      // Kuvanäkymän merkintä vaihtuu kirjausten osioksi: takaisin palaa osioon, josta kuva avattiin.
      closePhotos(false, true);
      overlayEntry = false;
      history.replaceState(null, '', '#s-fills');
      show('s-fills', false);
    } else {
      closePhotos(false);
    }
    const row = $('r-' + entry);
    const target = row && row.offsetParent !== null ? row : $('e-' + entry);
    if (!target) return;
    target.scrollIntoView({ block: 'center' });
    target.focus({ preventScroll: true });
    target.classList.add('hit');
    setTimeout(() => target.classList.remove('hit'), 2500);
  });

  // Esc sulkee kuvan tai valikon; nuolet selaavat kuvia (WCAG 2.1.1).
  doc.addEventListener('keydown', ev => {
    if (!lb.hidden) {
      if (ev.key === 'Escape') { ev.preventDefault(); closePhotos(); }
      else if (ev.key === 'ArrowLeft') { ev.preventDefault(); step(-1); }
      else if (ev.key === 'ArrowRight') { ev.preventDefault(); step(1); }
    } else if (root.classList.contains('menu-open') && ev.key === 'Escape') {
      ev.preventDefault();
      closeMenu();
    }
  });

  drawer.inert = true;
  // Uudelleenlatauksen jälkeen nykyinen merkintä voi olla avoimen kuvanäkymän tai valikon jäänne: palataan sen ohi,
  // jotta ensimmäinen takaisin-ele toimii.
  if (history.state && history.state.overlay) {
    ignorePop = true;
    history.back();
  }
  report.classList.add('ready');
  paginate();
  runSearch();
  show(location.hash.slice(1), false);
  stickyOffsets();
})();
