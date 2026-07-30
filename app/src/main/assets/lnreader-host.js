/*
 * Host glue for LNReader novel plugins. Reimplements the parts of LNReader's
 * src/plugins/pluginManager.ts that a plugin sees at runtime: the `require` shim, @libs/fetch,
 * @libs/storage, and the promise<->native call bridge.
 *
 * LNReader is MIT licensed (c) 2021 Rajarshee Chatterjee - see licenses/lnreader-MIT.md.
 *
 * MUST be evaluated BEFORE lnreader-libs.js: it overrides globalThis.fetch, and the bundle would
 * otherwise capture the real one and bypass OkHttp (losing UA/DoH/proxy/cookies, and hitting CORS).
 */
(function () {
  'use strict';

  var host = (globalThis.__host = {});
  var plugins = {};

  /* ------------------------------------------------------------------ fetch */

  var httpPending = Object.create(null);
  var httpSeq = 0;

  function normHeaders(init) {
    var out = {};
    if (!init || !init.headers) return out;
    var h = init.headers;
    if (typeof Headers !== 'undefined' && h instanceof Headers) {
      h.forEach(function (v, k) { out[k] = v; });
    } else {
      Object.keys(h).forEach(function (k) { out[k] = String(h[k]); });
    }
    return out;
  }

  function hasHeader(headers, name) {
    name = String(name).toLowerCase();
    return Object.keys(headers).some(function (key) { return key.toLowerCase() === name; });
  }

  function b64ToBytes(b64) {
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }

  // Minimal Response. ok/status/headers.get/text/json/arrayBuffer/blob is everything the plugin
  // corpus touches; widen only when a real plugin fails on it.
  function mkResponse(r) {
    var lower = {};
    var headers = r.headers || {};
    Object.keys(headers).forEach(function (k) { lower[k.toLowerCase()] = headers[k]; });
    var bytes = function () {
      return r.base64 ? b64ToBytes(r.base64) : new TextEncoder().encode(r.body || '');
    };
    return {
      ok: r.status >= 200 && r.status < 300,
      status: r.status,
      statusText: r.statusText || '',
      url: r.url || '',
      headers: {
        get: function (k) {
          var v = lower[String(k).toLowerCase()];
          return v === undefined ? null : v;
        },
        has: function (k) { return lower[String(k).toLowerCase()] !== undefined; },
      },
      text: function () { return Promise.resolve(r.body || ''); },
      json: function () {
        try { return Promise.resolve(JSON.parse(r.body || 'null')); }
        catch (e) { return Promise.reject(e); }
      },
      arrayBuffer: function () { return Promise.resolve(bytes().buffer); },
      blob: function () { return Promise.resolve(new Blob([bytes()])); },
    };
  }

  function nativeFetch(url, init, pluginId) {
    return new Promise(function (resolve, reject) {
      var id = ++httpSeq;
      httpPending[id] = { resolve: resolve, reject: reject };
      var body = init && init.body;
      var bodyIsBase64 = false;
      var formData = null;
      var headers = normHeaders(init);
      if (typeof FormData !== 'undefined' && body instanceof FormData) {
        formData = [];
        body.forEach(function (value, key) {
          if (typeof value !== 'string') {
            throw new TypeError('File and Blob FormData values are not supported');
          }
          formData.push([key, value]);
        });
        body = null;
      } else if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
        body = body.toString();
        if (!hasHeader(headers, 'Content-Type')) {
          headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
        }
      } else if (body !== undefined && body !== null && typeof body !== 'string') {
        if (body instanceof Uint8Array || body instanceof ArrayBuffer) {
          var u8 = body instanceof ArrayBuffer ? new Uint8Array(body) : body;
          var s = '';
          for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
          body = btoa(s);
          bodyIsBase64 = true;
        } else {
          body = String(body);
        }
      }
      if (init && init.referrer && !hasHeader(headers, 'Referer')) {
        var referrer = String(init.referrer);
        if (init.referrerPolicy === 'origin') {
          try { referrer = new URL(referrer).origin + '/'; } catch (e) { /* keep original */ }
        }
        headers.Referer = referrer;
      }
      try {
        Native.httpRequest(String(id), JSON.stringify({
          url: String(url),
          method: (init && init.method) || 'GET',
          headers: headers,
          body: body === undefined ? null : body,
          bodyIsBase64: bodyIsBase64,
          formData: formData,
          wantBase64: !!(init && init.__binary),
          pluginId: pluginId || null,
        }));
      } catch (e) {
        delete httpPending[id];
        reject(e);
      }
    });
  }

  host.httpResolve = function (id, json) {
    var p = httpPending[id];
    delete httpPending[id];
    if (!p) return;
    var r;
    try { r = JSON.parse(json); } catch (e) { p.reject(e); return; }
    if (r.err) {
      var error = new Error(r.err);
      error.isCloudFlare = !!r.isCloudFlare;
      p.reject(error);
    } else p.resolve(mkResponse(r));
  };

  globalThis.fetch = nativeFetch;

  /* ---------------------------------------------------------------- @libs/* */

  var DEFAULT_HEADERS = {
    'Connection': 'keep-alive',
    'Accept': '*/*',
    'Accept-Language': '*',
    'Sec-Fetch-Mode': 'cors',
    'Cache-Control': 'max-age=0',
  };

  function makeInit(init) {
    init = init || {};
    var headers = normHeaders(init);
    Object.keys(DEFAULT_HEADERS).forEach(function (k) {
      if (headers[k] === undefined) headers[k] = DEFAULT_HEADERS[k];
    });
    var out = {};
    Object.keys(init).forEach(function (k) { out[k] = init[k]; });
    out.headers = headers;
    return out;
  }

  function fetchApi(url, init, pluginId) {
    return nativeFetch(url, makeInit(init), pluginId);
  }

  function fetchText(url, init, encoding, pluginId) {
    var i = makeInit(init);
    if (encoding && !/^utf-?8$/i.test(encoding)) {
      i.__binary = true;
      return nativeFetch(url, i, pluginId).then(function (res) {
        if (!res.ok) return '';
        return res.arrayBuffer().then(function (buf) {
          try { return new TextDecoder(encoding).decode(buf); }
          catch (e) { return new TextDecoder('utf-8').decode(buf); }
        });
      }).catch(function (e) {
        if (e && e.isCloudFlare) throw e;
        return '';
      });
    }
    return nativeFetch(url, i, pluginId)
      .then(function (res) { return res.ok ? res.text() : ''; })
      .catch(function (e) {
        if (e && e.isCloudFlare) throw e;
        return '';
      });
  }

  function fetchProto() {
    // protobufjs is not bundled - see tools/lnreader-bundle/README.md.
    return Promise.reject(new Error('fetchProto is not supported by this app'));
  }

  var NovelStatus = {
    Unknown: 'Unknown',
    Ongoing: 'Ongoing',
    Completed: 'Completed',
    Licensed: 'Licensed',
    PublishingFinished: 'Publishing Finished',
    Cancelled: 'Cancelled',
    OnHiatus: 'On Hiatus',
  };

  var FilterTypes = {
    TextInput: 'Text',
    Picker: 'Picker',
    CheckboxGroup: 'Checkbox',
    Switch: 'Switch',
    ExcludableCheckboxGroup: 'XCheckbox',
  };

  function isUrlAbsolute(url) {
    if (!url) return false;
    if (url.indexOf('//') === 0) return true;
    return url.indexOf('://') > 0;
  }

  function mkStorage(pluginId, ns) {
    return {
      get: function (key, raw) {
        var s = Native.storageGet(pluginId, ns, String(key));
        if (!s) return undefined;
        var item;
        try { item = JSON.parse(s); } catch (e) { return undefined; }
        if (!item) return undefined;
        if (item.expires && Date.now() > item.expires) {
          Native.storageSet(pluginId, ns, String(key), null);
          return undefined;
        }
        return raw ? item : item.value;
      },
      set: function (key, value, expires) {
        Native.storageSet(pluginId, ns, String(key), JSON.stringify({
          created: new Date(),
          value: value,
          expires: expires instanceof Date ? expires.getTime() : expires,
        }));
      },
      delete: function (key) { Native.storageSet(pluginId, ns, String(key), null); },
      clearAll: function () { Native.storageClear(pluginId, ns); },
      getAllKeys: function () {
        try { return JSON.parse(Native.storageKeys(pluginId, ns)) || []; }
        catch (e) { return []; }
      },
    };
  }

  function mkRequire(pluginId) {
    var libs = globalThis.__lnlibs || {};
    return function (name) {
      switch (name) {
        case '@libs/fetch':
          return {
            fetchApi: function (url, init) { return fetchApi(url, init, pluginId); },
            fetchText: function (url, init, encoding) { return fetchText(url, init, encoding, pluginId); },
            fetchProto: fetchProto,
          };
        case '@libs/novelStatus':
          return { NovelStatus: NovelStatus };
        case '@libs/filterInputs':
          return { FilterTypes: FilterTypes };
        case '@libs/isAbsoluteUrl':
          return { isUrlAbsolute: isUrlAbsolute };
        case '@libs/defaultCover':
          return { defaultCover: host.defaultCover };
        case '@libs/storage':
          return {
            storage: mkStorage(pluginId, 'db'),
            localStorage: mkStorage(pluginId, 'local'),
            sessionStorage: mkStorage(pluginId, 'session'),
          };
        default:
          return libs[name];
      }
    };
  }

  /* ---------------------------------------------------------- plugin install */

  host.defaultCover = '';

  host.install = function (pluginId, rawCode) {
    try {
      var mod = { exports: {} };
      var plugin = Function(
        'require',
        'module',
        'fetch',
        'const exports = module.exports = {};\n' + rawCode + '\n;return exports.default'
      )(
        mkRequire(pluginId),
        mod,
        function (url, init) { return nativeFetch(url, init, pluginId); }
      );
      if (!plugin) throw new Error('plugin did not export a default');
      plugins[pluginId] = plugin;
      return JSON.stringify({
        ok: {
          id: plugin.id || pluginId,
          name: plugin.name || pluginId,
          site: plugin.site || '',
          lang: plugin.lang || '',
          version: String(plugin.version || '0'),
          iconUrl: plugin.iconUrl || '',
          imageRequestInit: plugin.imageRequestInit || null,
          filters: plugin.filters || null,
          pluginSettings: plugin.pluginSettings || null,
          hasParsePage: typeof plugin.parsePage === 'function',
          hasResolveUrl: typeof plugin.resolveUrl === 'function',
        },
      });
    } catch (e) {
      return JSON.stringify({ err: String((e && e.stack) || e) });
    }
  };

  host.uninstall = function (pluginId) {
    delete plugins[pluginId];
  };

  host.isInstalled = function (pluginId) {
    return !!plugins[pluginId];
  };

  /* -------------------------------------------------------------- call bridge */

  host.call = function (id, pluginId, fn, argsJson) {
    var reply = function (payload) {
      try { Native.resolve(String(id), JSON.stringify(payload)); } catch (e) { /* host gone */ }
    };
    var plugin, args;
    try {
      plugin = plugins[pluginId];
      if (!plugin) throw new Error('plugin ' + pluginId + ' is not loaded');
      if (typeof plugin[fn] !== 'function') throw new Error(pluginId + ' has no ' + fn + '()');
      args = JSON.parse(argsJson);
    } catch (e) {
      reply({ err: String((e && e.stack) || e) });
      return;
    }
    try {
      Promise.resolve(plugin[fn].apply(plugin, args)).then(
        function (v) { reply({ ok: v === undefined ? null : v }); },
        function (e) { reply({ err: String((e && e.stack) || e) }); }
      );
    } catch (e) {
      reply({ err: String((e && e.stack) || e) });
    }
  };

  // resolveUrl is sync in the plugin contract, so it gets a sync path with no round trip.
  host.resolveUrl = function (pluginId, path, isNovel) {
    try {
      var p = plugins[pluginId];
      if (!p || typeof p.resolveUrl !== 'function') return '';
      return String(p.resolveUrl(path, isNovel) || '');
    } catch (e) {
      return '';
    }
  };
})();
