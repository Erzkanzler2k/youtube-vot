/*
 * bootstrap.js — среда выполнения VoT в Android WebView.
 *
 * Запускается в контексте страницы (main world) через evaluateJavascript ДО загрузки
 * основного бандла vot.user.js. Задача:
 *   1) определить минимальные GM-полифиллы (localStorage-бэкенд);
 *   2) предзаполнить настройки VoT локальным хранилищем (язык, авто-перевод);
 *   3) затем исполняется сам vot.user.js.
 *
 * Если GM-* не определены вообще — VoT сам делает то же самое (VOTStorage->localStorage,
 * fetch вместо GM_xmlhttpRequest). Полифиллы добавлены только для отсутствия
 * ReferenceError'ов и включения «страничного» режима.
 */
(function () {
  'use strict';
  if (window.__VOT_BOOTED__) return;
  window.__VOT_BOOTED__ = true;

  var LS = function () { try { return window.localStorage; } catch (e) { return null; } };

  /* ---------- GM-полифиллы ---------- */
  if (typeof globalThis.GM_info === 'undefined') {
    globalThis.GM_info = {
      script: {
        name: 'VOT (Android WebView)',
        version: '1.11.15',
        namespace: 'vot',
        description: 'Voice-over translation bundled into WebView client',
        matches: ['*://*.youtube.com/*', '*://*.youtube-nocookie.com/*', '*://youtu.be/*']
      },
      scriptHandler: 'WebView',
      version: '1.0',
      scriptWillUpdate: false,
      scriptMetaStr: '',
      scriptSource: 'bundled',
      scriptUpdateURL: '',
      scriptDownloadURL: ''
    };
  }

  if (typeof globalThis.GM_addStyle === 'undefined') {
    globalThis.GM_addStyle = function (css) {
      var style = document.createElement('style');
      style.setAttribute('type', 'text/css');
      style.textContent = css;
      (document.head || document.documentElement).appendChild(style);
      return style;
    };
  }

  if (typeof globalThis.GM_getValue === 'undefined') {
    globalThis.GM_getValue = function (name, def) {
      var ls = LS(); if (!ls) return def;
      try {
        var raw = ls.getItem(name);
        return raw === null ? def : JSON.parse(raw);
      } catch (e) { return def; }
    };
  }
  if (typeof globalThis.GM_setValue === 'undefined') {
    globalThis.GM_setValue = function (name, value) {
      var ls = LS(); if (!ls) return;
      try { ls.setItem(name, JSON.stringify(value)); } catch (e) {}
    };
  }
  if (typeof globalThis.GM_deleteValue === 'undefined') {
    globalThis.GM_deleteValue = function (name) {
      var ls = LS(); if (!ls) return;
      try { ls.removeItem(name); } catch (e) {}
    };
  }
  if (typeof globalThis.GM_listValues === 'undefined') {
    globalThis.GM_listValues = function () {
      var ls = LS(); if (!ls) return [];
      try { return Object.keys(ls); } catch (e) { return []; }
    };
  }
  if (typeof globalThis.GM_notification === 'undefined') {
    globalThis.GM_notification = function () {};
  }

  /* ---------- Предзаполнение настроек (только если ещё не заданы) ---------- */
  var preset = {
    responseLanguage: 'ru',        // язык перевода озвучки
    responseLanguageSubtitles: 'auto',
    autoTranslate: true,           // автоматический перевод при открытии видео
    autoSubtitles: true,           // автоматическое включение субтитров
    translateProxyEnabledDefault: true
  };
  var ls = LS();
  if (ls) {
    try {
      for (var key in preset) {
        if (Object.prototype.hasOwnProperty.call(preset, key) && ls.getItem(key) === null) {
          ls.setItem(key, JSON.stringify(preset[key]));
        }
      }
    } catch (e) {}
  }

  /* ---------- Косметическая блокировка: реклама + кнопка «Войти» ---------- */
  (function () {
    var css = [
      /* реклама в ленте/поиске */
      'ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer,ytm-promoted-sparkles-web-renderer,ytd-in-feed-ad-layout-renderer,ytd-ad-slot-renderer,ytd-companion-slot-renderer,ytd-rich-item-renderer[is-ad],ytd-merchandised-item-compact-renderer,ytd-mealbar-promo-renderer,ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"]{display:none!important}',
      /* реклама в плеере */
      '#player-ads,#masthead-ad,.video-ads,.ad-container,.ad-div,.ytp-ad-overlay-container,.ytp-ad-text-overlay,.ytp-ad-image-overlay,.ytp-ad-player-overlay,.ytp-ad-skip-button-container{display:none!important}',
      /* кнопка «Войти» (CTA-кнопки) */
      '.yt-spec-button-shape-next--call-to-action{display:none!important}'
    ].join('\n');
    if (typeof globalThis.GM_addStyle === 'function') {
      globalThis.GM_addStyle(css);
    } else {
      var st = document.createElement('style');
      st.textContent = css;
      (document.head || document.documentElement).appendChild(st);
    }
  })();
})();