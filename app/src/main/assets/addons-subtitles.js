/* SONIX Stremio Addons + Online Subtitle Search.
 * Runs in the existing WebView and delegates network/file/player operations to Android.
 */
(function () {
  'use strict';
  const STORAGE_KEY = 'sonix_stremio_addons_v1';
  const STYLE_KEY = 'sonix_subtitle_style_v1';
  const LANGUAGE_KEY = 'sonix_subtitle_language_v1';
  const DEFAULT_ADDONS = [
    { id:'com.linvo.cinemeta', name:'Cinemeta', version:'3.0.14',
      description:'The official addon for movie and series catalogs',
      url:'https://v3-cinemeta.strem.io/manifest.json',
      types:['movie','series'], resources:['catalog','meta','addon_catalog'], enabled:true },
    { id:'org.stremio.opensubtitlesv3', name:'OpenSubtitles V3', version:'1.0.0',
      description:'Official OpenSubtitles v3',
      url:'https://opensubtitles-v3.strem.io/manifest.json',
      types:['movie','series'], resources:['subtitles'], enabled:true },
    { id:'opensubtitles-pro', name:'OpenSubtitles PRO', version:'0.1.0',
      description:'Community subtitle addon',
      url:'https://opensubtitlesv3-pro.dexter21767.com/manifest.json',
      types:['movie','series'], resources:['subtitles'], enabled:true }
  ];
  const LANGUAGES = [
    {label:'All',code:'all'},{label:'English',code:'en'},
    {label:'Arabic',code:'ar'},{label:'Urdu',code:'ur'},
    {label:'Hindi',code:'hi'},{label:'Spanish',code:'es'},
    {label:'French',code:'fr'},{label:'German',code:'de'},
    {label:'Portuguese',code:'pt'},{label:'Italian',code:'it'},
    {label:'Turkish',code:'tr'},{label:'Russian',code:'ru'}
  ];
  const FONTS = ['Reem Kufi','Aref Ruqaa','Lalezar','Amiri','Rakkas','Marhey','Noto Naskh','Changa','Noto Kufi','Lemonada'];
  let installed = [];
  let market = {official:[], community:[]};
  let activeTab = 'installed';
  let searchText = '';
  let subtitleResults = [];
  let subtitleBusy = false;
  let subtitleLanguage = (() => {
    try {
      const saved = localStorage.getItem(LANGUAGE_KEY);
      return LANGUAGES.some(language => language.code === saved) ? saved : 'ar';
    } catch (_) { return 'ar'; }
  })();
  let currentSubtitleLabel = '';
  let subtitleStyle = readStyle();

  function readStyle() {
    try {
      return Object.assign({font:'Reem Kufi',size:24,color:'#FFFFFF',background:'off',outline:'shadow',position:'bottom',delay:0},
        JSON.parse(localStorage.getItem(STYLE_KEY) || '{}'));
    } catch (_) {
      return {font:'Reem Kufi',size:24,color:'#FFFFFF',background:'off',outline:'shadow',position:'bottom',delay:0};
    }
  }
  function saveStyle() {
    try { localStorage.setItem(STYLE_KEY, JSON.stringify(subtitleStyle)); } catch (_) {}
    applyStyle();
  }
  let nativeRequestSequence = 0;
  const pendingNativeRequests = new Map();
  function nativeRequest(method, args) {
    return new Promise((resolve,reject) => {
      if (!window.NativePlayback || typeof window.NativePlayback[method] !== 'function') {
        reject(new Error('خدمة الاتصال غير متاحة')); return;
      }
      const id = 'sonix_sub_' + (++nativeRequestSequence);
      const timeoutMs = method === 'applySubtitleAsync' ? 65000 : 30000;
      const timer = setTimeout(() => {
        const pending = pendingNativeRequests.get(id);
        if (!pending) return;
        pendingNativeRequests.delete(id);
        reject(new Error(method === 'applySubtitleAsync'
          ? 'انتهت مهلة تنزيل الترجمة أو تفعيلها. تحقق من اتصال المصدر ثم حاول مجدداً.'
          : 'انتهت مهلة الاتصال بمصدر الإضافة.'));
      }, timeoutMs);
      pendingNativeRequests.set(id,{resolve,reject,timer});
      try { window.NativePlayback[method](...args,id); }
      catch (error) {
        const pending = pendingNativeRequests.get(id);
        if (pending) clearTimeout(pending.timer);
        pendingNativeRequests.delete(id);
        reject(error);
      }
    });
  }
  async function nativeText(url) {
    const value = await nativeRequest('fetchTextAsync',[String(url)]);
    if (!value || value.success !== true) {
      throw new Error(value && value.error ? value.error : 'تعذر الاتصال بمصدر الإضافة.');
    }
    return value.body || '';
  }
  async function nativeJson(url) { return JSON.parse(await nativeText(url)); }
  async function nativeApplySubtitle(url,label) {
    return nativeRequest('applySubtitleAsync',[String(url),String(label || 'Subtitle')]);
  }
  function parseList(value) {
    if (Array.isArray(value)) return value;
    if (!value || typeof value !== 'object') return [];
    for (const key of ['addons','data','results','items']) if (Array.isArray(value[key])) return value[key];
    return [];
  }
  function normalizeAddon(raw, source) {
    if (!raw || typeof raw !== 'object') return null;
    const manifest = raw.manifest || raw;
    const url = String(raw.url || raw.manifestUrl || raw.transportUrl || raw.manifest_url ||
      (raw.transport && raw.transport.url) || '').trim();
    const name = String(raw.name || manifest.name || raw.title || '').trim();
    if (!url || !name || !/^https?:\/\//i.test(url)) return null;
    const resources = Array.isArray(manifest.resources) ? manifest.resources.map(x => typeof x === 'string' ? x : x && x.name || '') : [];
    const types = Array.isArray(manifest.types) ? manifest.types : ['movie','series'];
    return {
      id:String(raw.id || manifest.id || url), name,
      version:String(raw.version || manifest.version || ''),
      description:String(raw.description || manifest.description || ''),
      url:url.endsWith('/manifest.json') ? url : url.replace(/\/+$/,'') + '/manifest.json',
      types, resources, enabled:raw.enabled !== false,
      source:source || raw.source || 'community',
      configurationRequired:raw.configurationRequired === true ||
        (manifest.behaviorHints && manifest.behaviorHints.configurationRequired === true) || url.includes('/[config]/'),
      configureUrl:raw.configureUrl || ''
    };
  }
  function ensureDefaults(list) {
    const result = Array.isArray(list) ? list.slice() : [];
    DEFAULT_ADDONS.forEach(seed => {
      const index = result.findIndex(x => x && (x.id === seed.id || x.url === seed.url));
      if (index < 0) result.push({...seed});
      else result[index] = {...seed, ...result[index], id:seed.id, name:seed.name, version:seed.version, url:seed.url,
        resources:seed.resources, types:seed.types, enabled:result[index].enabled !== false};
    });
    return result;
  }
  function saveInstalled() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(installed)); } catch (_) {}
  }
  function loadInstalled() {
    try { installed = ensureDefaults(JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')); }
    catch (_) { installed = ensureDefaults([]); }
    saveInstalled();
  }
  function isInstalled(addon) {
    return installed.some(x => x.id === addon.id || x.url === addon.url);
  }
  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  }
  function renderAddons() {
    const host = document.getElementById('addons-list');
    if (!host) return;
    const source = activeTab === 'installed' ? installed : market[activeTab] || [];
    const manualRow = document.getElementById('addons-manifest-row');
    if (manualRow) manualRow.hidden = activeTab === 'installed';
    const q = searchText.trim().toLowerCase();
    const visible = source.filter(x => !q || (x.name+' '+x.description+' '+x.id).toLowerCase().includes(q));
    host.replaceChildren();
    if (!visible.length) {
      const empty = document.createElement('div');
      empty.className = 'addon-empty';
      empty.textContent = activeTab === 'installed' ? 'لا توجد إضافات مطابقة' : 'لا توجد نتائج. جرّب البحث بكلمات أخرى.';
      host.appendChild(empty);
      return;
    }
    visible.forEach(addon => {
      const row = document.createElement('article');
      row.className = 'addon-card';
      const meta = document.createElement('div');
      meta.className = 'addon-card-meta';
      const title = document.createElement('strong');
      title.textContent = addon.name;
      const version = document.createElement('small');
      version.textContent = addon.version ? 'v' + addon.version : 'إضافة';
      const description = document.createElement('p');
      description.textContent = addon.description || 'لا يوجد وصف متاح';
      const resources = document.createElement('small');
      resources.textContent = (addon.resources || []).join(', ');
      meta.append(title,version,description,resources);
      const actions = document.createElement('div');
      actions.className = 'addon-card-actions';
      if (activeTab === 'installed') {
        const toggle = document.createElement('button');
        toggle.className = 'action-btn focusable settings-action-secondary';
        toggle.textContent = addon.enabled === false ? 'تفعيل' : 'تعطيل';
        toggle.addEventListener('click', () => {
          addon.enabled = addon.enabled === false;
          saveInstalled(); renderAddons();
        });
        actions.appendChild(toggle);
        if (!DEFAULT_ADDONS.some(x => x.id === addon.id)) {
          const remove = document.createElement('button');
          remove.className = 'action-btn focusable addon-remove';
          remove.textContent = 'إزالة';
          remove.addEventListener('click', () => {
            installed = installed.filter(x => x.id !== addon.id);
            saveInstalled(); renderAddons();
          });
          actions.appendChild(remove);
        }
      } else {
        const button = document.createElement('button');
        button.className = 'action-btn focusable settings-action-primary';
        button.textContent = isInstalled(addon) ? 'مثبتة' : 'تثبيت';
        button.disabled = isInstalled(addon);
        button.addEventListener('click', () => installAddon(addon, button));
        actions.appendChild(button);
      }
      row.append(meta,actions);
      host.appendChild(row);
    });
  }
  async function loadMarket(force) {
    const status = document.getElementById('addons-status');
    if (status) status.textContent = 'جارٍ تحميل الإضافات الرسمية والمجتمعية...';
    try {
      const official = parseList(await nativeJson('https://api.strem.io/addonscollection.json'))
        .map(x => normalizeAddon(x,'official')).filter(Boolean);
      let community = [];
      try {
        const data = await nativeJson('https://stremio-addons.net/api/v0/addons?sort_by=stars&order=desc&limit=100&page=1');
        community = parseList(data).map(x => normalizeAddon(x,'community')).filter(Boolean);
      } catch (_) {
        try {
          const data = await nativeJson('https://stremio-addons.com/addon_catalog/all/community.json');
          community = parseList(data).map(x => normalizeAddon(x,'community')).filter(Boolean);
        } catch (_) {}
      }
      market = {official,community};
      if (status) status.textContent = official.length + ' إضافات رسمية و' + community.length + ' إضافات مجتمعية';
    } catch (error) {
      if (status) status.textContent = 'تعذر تحديث السوق حالياً. الإضافات المثبتة تعمل بشكل مستقل.';
    }
    renderAddons();
  }
  function openMarketplace() {
    loadInstalled();
    activeTab = 'installed';
    searchText = '';
    const input = document.getElementById('addons-search');
    if (input) input.value = '';
    renderAddons();
    openModal('modal-addons');
    if (!market.official.length && !market.community.length) loadMarket();
  }
  async function installAddon(addon, button) {
    if (isInstalled(addon)) return;
    button.disabled = true;
    button.textContent = 'جارٍ التحقق...';
    try {
      if (addon.configurationRequired || addon.url.includes('/[config]/')) {
        let configureUrl = addon.configureUrl || addon.url.replace('/[config]','').replace(/\/manifest\.json$/,'').replace(/\/$/,'') + '/configure';
        const bridge = window.AndroidBridge;
        if (bridge && bridge.openExternalUrl) bridge.openExternalUrl(configureUrl);
        if (typeof showToast === 'function') showToast('افتح صفحة إعداد الإضافة، ثم ثبّت رابط Manifest الناتج من هذا القسم.');
        button.disabled = false;
        button.textContent = 'تثبيت';
        return;
      }
      const manifest = await nativeJson(addon.url);
      if (!manifest || !manifest.id || !manifest.name) {
        throw new Error('ملف Manifest غير مكتمل أو غير صالح.');
      }
      const clean = normalizeAddon({...addon,...manifest,url:addon.url},addon.source);
      if (!clean) throw new Error('تعذر قراءة بيانات الإضافة.');
      clean.enabled = true;
      installed.push(clean);
      saveInstalled();
      renderAddons();
      if (typeof showToast === 'function') showToast('تم تثبيت ' + clean.name);
    } catch (error) {
      button.disabled = false;
      button.textContent = 'تثبيت';
      if (typeof showToast === 'function') showToast(error.message || 'تعذر تثبيت الإضافة');
    }
  }
  function activeIdentity() {
    const item = videoPlaybackController && videoPlaybackController.currentItem;
    if (!item) throw new Error('شغّل فيلماً أو حلقة أولاً للبحث عن ترجمتها.');
    if (typeof currentAppMode !== 'undefined' && currentAppMode === 'live') {
      throw new Error('البحث عن الترجمة متاح للأفلام والمسلسلات فقط.');
    }
    const title = String(item.subtitleTitle || item.seriesTitle || item.name || '').trim();
    if (!title) throw new Error('اسم المحتوى غير متوفر للبحث.');
    const episodeMatch = title.match(/(?:S(\d{1,2})E(\d{1,3})|(\d{1,2})x(\d{1,3}))/i);
    // Lumen treats an explicit season/episode marker or episode progress key as
    // authoritative series evidence even when the player item lacks series metadata.
    const progressKey = String(item.progressKey || '');
    const isSeries = !!(item.subtitleSeries || item.seriesTitle ||
      (typeof currentAppMode !== 'undefined' && currentAppMode === 'series') ||
      episodeMatch || progressKey.startsWith('ep:'));
    const season = Number(item.subtitleSeason || item.season || episodeMatch?.[1] || episodeMatch?.[3] || 0);
    const episode = Number(item.subtitleEpisode || item.episode || episodeMatch?.[2] || episodeMatch?.[4] || 0);
    const searchTitle = title
      .replace(/\b(19|20)\d{2}\b/g, '')
      .replace(/\bS\d{1,2}E\d{1,3}\b|\b\d{1,2}x\d{1,3}\b/gi, '')
      .replace(/[._]+/g, ' ')
      .replace(/\s+/g, ' ').trim() || title;
    return {item,isSeries,title,searchTitle,season,episode};
  }
  function normalizeTitle(value) { return String(value || '').toLowerCase().replace(/[^\p{L}\p{N}]/gu,''); }
  async function resolveImdb(identity, providerErrors) {
    const preferredType = identity.isSeries ? 'series' : 'movie';
    const searchTitle = identity.searchTitle || identity.title;
    const target = normalizeTitle(searchTitle);
    let candidates = [];

    // Match Lumen's identity resolution: try Cinemeta first, then search-enabled
    // catalogs from installed addons if Cinemeta has no usable IMDb identity.
    try {
      const url = 'https://v3-cinemeta.strem.io/catalog/' + preferredType +
        '/top/search=' + encodeURIComponent(searchTitle) + '.json';
      const data = await nativeJson(url);
      candidates = data && Array.isArray(data.metas) ? data.metas : [];
    } catch (error) {
      providerErrors.push('Cinemeta: ' + (error.message || 'تعذر الوصول إلى كتالوج Cinemeta'));
    }

    candidates = candidates.filter(item =>
      /^tt\d+$/.test(String(item.imdb_id || item.id || ''))
    );

    if (!candidates.length) {
      const allowedTypes = identity.isSeries ? ['series'] : ['movie', 'series'];
      const addonBatches = await Promise.all(installed
        .filter(addon => addon.enabled !== false)
        .map(async addon => {
          try {
            const manifest = await nativeJson(addon.url);
            const base = addon.url
              .replace(/\/manifest\.json(?:\?.*)?$/, '')
              .replace(/\/+$/, '');
            const catalogs = (Array.isArray(manifest.catalogs) ? manifest.catalogs : [])
              .filter(catalog => allowedTypes.includes(catalog.type) && catalog.id &&
                Array.isArray(catalog.extra) &&
                catalog.extra.some(extra => extra && extra.name === 'search'));

            const catalogBatches = await Promise.all(catalogs.map(async catalog => {
              try {
                const url = base + '/catalog/' + catalog.type + '/' +
                  encodeURIComponent(catalog.id) + '/search=' +
                  encodeURIComponent(searchTitle) + '.json';
                const data = await nativeJson(url);
                return (data && Array.isArray(data.metas) ? data.metas : [])
                  .map(meta => ({...meta, type:meta.type || catalog.type}));
              } catch (error) {
                providerErrors.push('Stremio catalog (' + catalog.id + '): ' + (error.message || 'تعذر البحث في الكتالوج'));
                return [];
              }
            }));
            return catalogBatches.flat();
          } catch (error) {
            providerErrors.push('Stremio (' + addon.name + '): ' + (error.message || 'تعذر قراءة Manifest'));
            return [];
          }
        }));
      candidates = addonBatches.flat().filter(item =>
        /^tt\d+$/.test(String(item.imdb_id || item.id || '')) &&
        allowedTypes.includes(String(item.type || '').toLowerCase())
      );
    }

    candidates.sort((a,b) => {
      const aa = normalizeTitle(a.name || a.title);
      const bb = normalizeTitle(b.name || b.title);
      const score = value => value === target ? 0 :
        (value.includes(target) || target.includes(value) ? 1 : 2);
      return score(aa) - score(bb);
    });

    const match = candidates[0];
    if (!match) {
      throw new Error('لم يتم العثور على معرف المحتوى في Cinemeta أو كتالوجات الإضافات المثبتة.');
    }

    const id = String(match.imdb_id || match.id);
    const resolvedType = String(match.type || preferredType).toLowerCase();
    if (resolvedType === 'series' && identity.season > 0 && identity.episode > 0) {
      return {
        type:'series',
        id,
        resourceId:id + ':' + identity.season + ':' + identity.episode
      };
    }
    return {type:resolvedType,id,resourceId:id};
  }
  function normalizeLang(value) {
    const v = String(value || '').toLowerCase().replace('_','-');
    const aliases = {
      ara:'ar', ar:'ar', eng:'en', hin:'hi', urd:'ur',
      spa:'es', fre:'fr', fra:'fr', deu:'de', ger:'de', ita:'it',
      por:'pt', rus:'ru', nld:'nl', tur:'tr', zho:'zh', chi:'zh',
      jpn:'ja', kor:'ko', fas:'fa', per:'fa'
    };
    return aliases[v] || v;
  }
  async function searchOnline() {
    if (subtitleBusy) return;
    const status = document.getElementById('subtitle-search-status');
    const list = document.getElementById('subtitle-results');
    if (!list) return;
    subtitleBusy = true;
    list.replaceChildren();
    if (status) status.textContent = 'جارٍ البحث في مصادر الترجمة...';
    try {
      const identity = activeIdentity();
      const results = [];
      const seen = new Set();
      const providerErrors = [];

      const rawTitle = identity.title || '';
      const cleanTitle = rawTitle
        .replace(/^(?:\[[A-Za-z0-9_-]+\]|\|[A-Za-z0-9_-]+\||[A-Za-z]{2,3}\s*[:\-|])\s*/g, '')
        .replace(/\b(?:4k|uhd|fhd|hd|sd|1080p|720p|2160p|h264|h265|hevc|x264|x265|web-?dl|bluray|rip|aac|mp3|dubbed|subbed)\b/gi, '')
        .replace(/(?:مترجم|مدبلج|نسخة|أصلية|حصريا|جودة|عالية|كاملة)/g, '')
        .replace(/\[.*?\]|\(.*?\)|[\{|\}]/g, ' ')
        .replace(/\s+/g, ' ').trim() || rawTitle;

      // 1. Direct OpenSubtitles API search from Lumen-App engine
      if (window.NativePlayback && typeof window.NativePlayback.searchSubtitlesAsync === 'function') {
        try {
          const openSubsData = await nativeRequest('searchSubtitlesAsync', [cleanTitle, subtitleLanguage]);
          if (openSubsData && openSubsData.success && Array.isArray(openSubsData.results)) {
            openSubsData.results.forEach(sub => {
              const url = sub.downloadUrl || ('opensubtitles://' + sub.fileId);
              if (!seen.has(url)) {
                seen.add(url);
                results.push({
                  url: url,
                  label: sub.name || ('OpenSubtitles · ' + sub.langName),
                  language: normalizeLang(sub.iso || subtitleLanguage),
                  addon: 'OpenSubtitles'
                });
              }
            });
          }
        } catch (e) {
          providerErrors.push('OpenSubtitles: ' + (e.message || 'تعذر البحث'));
          console.warn('Native OpenSubtitles search error:', e);
        }
      }

      // 2. Query configured Stremio subtitle addons concurrently, matching Lumen's
      // parallel addon fan-out so one slow provider does not delay every other result.
      try {
        const imdb = await resolveImdb(identity, providerErrors);
        const addons = installed.filter(x => x.enabled !== false && (x.resources || []).some(r => String(r).toLowerCase() === 'subtitles'));
        const batches = await Promise.all(addons.map(async addon => {
          try {
            const base = addon.url.replace(/\/manifest\.json(?:\?.*)?$/,'').replace(/\/+$/,'');
            const url = base + '/subtitles/' + imdb.type + '/' + encodeURIComponent(imdb.resourceId) + '.json';
            const payload = await nativeJson(url);
            const rows = payload && Array.isArray(payload.subtitles) ? payload.subtitles : [];
            return rows.map(row => {
              const lang = normalizeLang(row.lang);
              if (subtitleLanguage !== 'all' && lang !== subtitleLanguage && !lang.startsWith(subtitleLanguage+'-')) return null;
              if (!/^https?:\/\//i.test(String(row.url || ''))) return null;
              return {
                url: row.url,
                label: row.label || row.lang || addon.name,
                language: lang,
                addon: addon.name
              };
            }).filter(Boolean);
          } catch (error) {
            providerErrors.push('Stremio (' + addon.name + '): ' + (error.message || 'تعذر تحميل الترجمات'));
            console.warn('Subtitle addon failed: ' + addon.name, error);
            return [];
          }
        }));
        batches.forEach(batch => batch.forEach(entry => {
          if (!seen.has(entry.url)) {
            seen.add(entry.url);
            results.push(entry);
          }
        }));
      } catch (imdbErr) {
        providerErrors.push('Stremio: ' + (imdbErr.message || 'تعذر تحديد هوية الفيلم أو الحلقة'));
        console.warn('IMDb resolution skipped or failed:', imdbErr);
      }

      subtitleResults = results;
      subtitleResults.sort((a,b) => (a.language === subtitleLanguage ? -1 : 0) - (b.language === subtitleLanguage ? -1 : 0));
      renderSubtitleResults();

      if (status) {
        const providerWarning = providerErrors.length
          ? ' · تعذر الاتصال ببعض المصادر: ' + providerErrors.join('؛ ')
          : '';
        status.textContent = subtitleResults.length
          ? 'تم العثور على ' + subtitleResults.length + ' ترجمة' + providerWarning
          : (providerErrors.length
              ? 'لم تُرجع المصادر نتائج. ' + providerErrors.join('؛ ') + ' · جرّب إضافة مصدر Stremio أو ملف ترجمة محلي.'
              : 'لم يتم العثور على ترجمة لهذه اللغة. جرّب اختيار (الكل) أو إضافة ملف ترجمة محلي.');
      }
    } catch (error) {
      if (status) status.textContent = error.message || 'تعذر البحث عن الترجمة.';
    } finally { subtitleBusy = false; }
  }
  function updateSubtitleStateUI(enabled, label, hasTrack) {
    const powerBtn = document.getElementById('subtitle-toggle-power');
    const powerText = document.getElementById('subtitle-toggle-power-text');
    const badge = document.getElementById('subtitle-active-track-badge');
    const activeLabel = label || currentSubtitleLabel;

    if (badge) {
      if (hasTrack && activeLabel) {
        if (enabled) {
          badge.textContent = '✓ مفعّلة (' + activeLabel + ')';
          badge.classList.add('active');
        } else {
          badge.textContent = 'معطلة مؤقتاً (' + activeLabel + ')';
          badge.classList.remove('active');
        }
      } else {
        badge.textContent = 'لا توجد ترجمة مفعّلة';
        badge.classList.remove('active');
      }
    }

    if (powerBtn && powerText) {
      if (hasTrack && enabled) {
        powerText.textContent = 'إيقاف الترجمة';
        powerBtn.classList.remove('is-disabled-state');
      } else {
        powerText.textContent = 'تفعيل الترجمة';
        powerBtn.classList.add('is-disabled-state');
      }
    }
  }

  function renderSubtitleResults() {
    const host = document.getElementById('subtitle-results');
    if (!host) return;
    host.replaceChildren();
    if (!subtitleResults.length) {
      return;
    }
    subtitleResults.forEach((entry, index) => {
      const card = document.createElement('button');
      card.className = 'subtitle-result-card focusable';
      card.type = 'button';

      const info = document.createElement('div');
      info.className = 'subtitle-result-info';

      const title = document.createElement('span');
      title.className = 'subtitle-result-title';
      title.textContent = entry.label;

      const tags = document.createElement('div');
      tags.className = 'subtitle-result-tags';

      const langTag = document.createElement('span');
      langTag.className = 'subtitle-tag subtitle-tag-lang';
      langTag.textContent = entry.language.toUpperCase();

      const addonTag = document.createElement('span');
      addonTag.className = 'subtitle-tag subtitle-tag-addon';
      addonTag.textContent = entry.addon;

      tags.append(langTag, addonTag);
      info.append(title, tags);

      const statusBadge = document.createElement('span');
      statusBadge.className = 'subtitle-action-status';
      const isActive = currentSubtitleLabel && currentSubtitleLabel === entry.label;
      if (isActive) {
        statusBadge.textContent = '✓ مفعّلة';
        statusBadge.classList.add('active');
      } else {
        statusBadge.textContent = 'تحميل';
      }

      card.append(info, statusBadge);
      card.addEventListener('click', () => applyOnlineSubtitle(index, card, statusBadge));
      host.appendChild(card);
    });
  }

  async function applyOnlineSubtitle(index, card, statusBadge) {
    const entry = subtitleResults[index];
    if (!entry || !window.NativePlayback || !window.NativePlayback.applySubtitleAsync) {
      if (typeof showToast === 'function') showToast('تطبيق الترجمة غير متاح');
      return;
    }
    card.disabled = true;
    statusBadge.textContent = 'جارٍ التحميل...';
    statusBadge.className = 'subtitle-action-status loading';
    try {
      const result = await nativeApplySubtitle(entry.url, entry.label);
      if (!result.success) throw new Error(result.error || 'تعذر تحميل الترجمة');
      card.disabled = false;
      document.querySelectorAll('#subtitle-results .subtitle-action-status').forEach(b => {
        if (b !== statusBadge) {
          b.textContent = 'تحميل';
          b.className = 'subtitle-action-status';
        }
      });
      statusBadge.textContent = '✓ مفعّلة حالياً';
      statusBadge.className = 'subtitle-action-status active';
      currentSubtitleLabel = entry.label;
      updateSubtitleStateUI(true, entry.label, true);
      const status = document.getElementById('subtitle-search-status');
      if (status) status.textContent = 'تم تفعيل الترجمة وظهورها على الفيديو: ' + entry.label;
      if (typeof showToast === 'function') showToast('تم تنزيل الترجمة وتفعيلها على الفيديو');
    } catch (error) {
      card.disabled = false;
      statusBadge.textContent = error.stage === 'activation' ? 'تم التنزيل' : 'فشل';
      statusBadge.className = 'subtitle-action-status ' + (error.stage === 'activation' ? 'downloaded' : 'failed');
      if (typeof showToast === 'function') showToast(error.stage === 'activation'
        ? 'تم تنزيل ملف الترجمة، لكن تعذر تفعيله: ' + (error.message || '')
        : (error.message || 'تعذر تنزيل الترجمة'));
    }
  }

  function applyStyle() {
    if (!window.NativePlayback || !window.NativePlayback.setSubtitleStyle) return;
    const colors = {white:'#FFFFFF',yellow:'#FFFF00',cyan:'#00FFFF',green:'#00FF66'};
    const color = colors[subtitleStyle.color] || subtitleStyle.color;
    try {
      window.NativePlayback.setSubtitleStyle(subtitleStyle.font,String(subtitleStyle.size),color,
        subtitleStyle.background,subtitleStyle.outline,subtitleStyle.position);
      if (typeof window.NativePlayback.setSubtitleDelay === 'function') {
        window.NativePlayback.setSubtitleDelay(Number(subtitleStyle.delay) || 0);
      }
    } catch (error) { console.warn('Unable to apply subtitle style',error); }
  }

  function syncSubtitleLanguageButtons() {
    document.querySelectorAll('[data-subtitle-language]').forEach(button => {
      const active = button.dataset.subtitleLanguage === subtitleLanguage;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
    });
  }

  function openSubtitleSearch() {
    subtitleResults = [];
    syncSubtitleLanguageButtons();
    const title = document.getElementById('subtitle-content-title');
    const status = document.getElementById('subtitle-search-status');
    const list = document.getElementById('subtitle-results');
    try {
      const identity = activeIdentity();
      if (title) title.textContent = identity.title;
      if (status) status.textContent = 'اختر اللغة ثم ابحث عن الترجمة عبر الإضافات المثبتة.';
      if (list) list.replaceChildren();
    } catch (error) {
      if (title) title.textContent = 'لا يوجد محتوى قيد التشغيل';
      if (status) status.textContent = error.message;
    }

    // Sync active subtitle state with native player
    try {
      const hasTrack = !!(window.NativePlayback && window.NativePlayback.hasActiveSubtitle && window.NativePlayback.hasActiveSubtitle());
      const isEnabled = !window.NativePlayback || !window.NativePlayback.isSubtitlesEnabled || window.NativePlayback.isSubtitlesEnabled();
      const nativeLabel = (window.NativePlayback && window.NativePlayback.getActiveSubtitleLabel && window.NativePlayback.getActiveSubtitleLabel()) || currentSubtitleLabel;
      if (nativeLabel) currentSubtitleLabel = nativeLabel;
      updateSubtitleStateUI(isEnabled, currentSubtitleLabel, hasTrack);
    } catch (_) {}

    renderSubtitleStyle();
    openModal('modal-subtitles');
  }

  function renderSubtitleStyle() {
    const font = document.getElementById('subtitle-font');
    const size = document.getElementById('subtitle-size');
    const color = document.getElementById('subtitle-color');
    const background = document.getElementById('subtitle-background');
    const outline = document.getElementById('subtitle-outline');
    const position = document.getElementById('subtitle-position');
    const delay = document.getElementById('subtitle-delay');
    if (font) font.value = subtitleStyle.font;
    if (size) size.value = String(subtitleStyle.size);
    if (color) color.value = subtitleStyle.color;
    if (background) background.value = subtitleStyle.background;
    if (outline) outline.value = subtitleStyle.outline;
    if (position) position.value = subtitleStyle.position;
    if (delay) delay.value = String(subtitleStyle.delay || 0);
  }

  function bind() {
    loadInstalled();
    document.getElementById('settings-open-addons')?.addEventListener('click', openMarketplace);
    document.querySelectorAll('[data-addon-tab]').forEach(button => button.addEventListener('click', () => {
      activeTab = button.dataset.addonTab;
      document.querySelectorAll('[data-addon-tab]').forEach(x => x.classList.toggle('active',x === button));
      renderAddons();
    }));
    document.getElementById('addons-search')?.addEventListener('input', event => {
      searchText = event.target.value || ''; renderAddons();
    });
    document.getElementById('addons-refresh')?.addEventListener('click', () => loadMarket(true));
    document.getElementById('addons-install-url')?.addEventListener('click', () => {
      const field = document.getElementById('addons-manifest-url');
      const value = String(field && field.value || '').trim();
      if (!/^https?:\/\//i.test(value)) {
        if (typeof showToast === 'function') showToast('أدخل رابط Manifest صالحاً يبدأ بـ https:// أو http://');
        return;
      }
      const addon = normalizeAddon({id:value,name:value,url:value,resources:[],types:[]},'custom');
      if (!addon) {
        if (typeof showToast === 'function') showToast('رابط Manifest غير صالح');
        return;
      }
      const button = document.getElementById('addons-install-url');
      installAddon(addon,button);
      if (field) field.value = '';
    });
    document.getElementById('subtitle-search-button')?.addEventListener('click', searchOnline);
    document.getElementById('subtitle-language-list')?.addEventListener('click', event => {
      const button = event.target.closest('[data-subtitle-language]');
      if (!button) return;
      subtitleLanguage = button.dataset.subtitleLanguage;
      try { localStorage.setItem(LANGUAGE_KEY, subtitleLanguage); } catch (_) {}
      syncSubtitleLanguageButtons();
    });

    // Toggle Subtitles On/Off without destroying state
    document.getElementById('subtitle-toggle-power')?.addEventListener('click', () => {
      if (!window.NativePlayback) return;
      const hasTrack = !!(window.NativePlayback.hasActiveSubtitle && window.NativePlayback.hasActiveSubtitle());
      if (!hasTrack && !currentSubtitleLabel) {
        if (typeof showToast === 'function') showToast('لا يوجد ملف ترجمة محمّل حالياً. اختر ملفاً أو ابحث عبر الإنترنت');
        return;
      }
      if (typeof window.NativePlayback.toggleSubtitles === 'function') {
        const newState = window.NativePlayback.toggleSubtitles();
        updateSubtitleStateUI(newState, currentSubtitleLabel, true);
        if (typeof showToast === 'function') {
          showToast(newState ? 'تم تفعيل الترجمة' : 'تم إيقاف عرض الترجمة');
        }
      } else if (typeof window.NativePlayback.setSubtitlesEnabled === 'function') {
        const currentEnabled = typeof window.NativePlayback.isSubtitlesEnabled === 'function' ? window.NativePlayback.isSubtitlesEnabled() : true;
        const newState = !currentEnabled;
        window.NativePlayback.setSubtitlesEnabled(newState);
        updateSubtitleStateUI(newState, currentSubtitleLabel, true);
        if (typeof showToast === 'function') {
          showToast(newState ? 'تم تفعيل الترجمة' : 'تم إيقاف عرض الترجمة');
        }
      }
    });

    // Add Subtitle File
    document.getElementById('subtitle-add-file')?.addEventListener('click', () => {
      if (window.NativePlayback && window.NativePlayback.pickSubtitle) {
        window.NativePlayback.pickSubtitle();
      } else if (typeof showToast === 'function') {
        showToast('اختيار الملفات غير متاح');
      }
    });

    document.getElementById('subtitle-font')?.addEventListener('change', e => { subtitleStyle.font=e.target.value; saveStyle(); });
    document.getElementById('subtitle-size')?.addEventListener('change', e => { subtitleStyle.size=Number(e.target.value); saveStyle(); });
    document.getElementById('subtitle-color')?.addEventListener('change', e => { subtitleStyle.color=e.target.value; saveStyle(); });
    document.getElementById('subtitle-background')?.addEventListener('change', e => { subtitleStyle.background=e.target.value; saveStyle(); });
    document.getElementById('subtitle-outline')?.addEventListener('change', e => { subtitleStyle.outline=e.target.value; saveStyle(); });
    document.getElementById('subtitle-position')?.addEventListener('change', e => { subtitleStyle.position=e.target.value; saveStyle(); });
    document.getElementById('subtitle-delay')?.addEventListener('change', e => {
      subtitleStyle.delay = Number(e.target.value) || 0;
      saveStyle();
      if (window.NativePlayback && window.NativePlayback.setSubtitleDelay) {
        window.NativePlayback.setSubtitleDelay(subtitleStyle.delay);
      }
    });
    applyStyle();
  }

  window.SonixSubtitles = {
    openSearch: openSubtitleSearch,
    openMarketplace,
    applyStyle,
    refresh: loadMarket,
    onStateChanged: function (enabled, label, hasTrack) {
      if (label) currentSubtitleLabel = label;
      updateSubtitleStateUI(enabled, label || currentSubtitleLabel, hasTrack);
    },
    onNativeResponse: function (requestId, payload) {
      const pending = pendingNativeRequests.get(requestId);
      if (!pending) return;
      clearTimeout(pending.timer);
      pendingNativeRequests.delete(requestId);
      try {
        const value = JSON.parse(payload || '{}');
        if (!value.success) {
          const error = new Error(value.error || 'فشل طلب الشبكة');
          error.stage = value.stage || 'download';
          throw error;
        }
        pending.resolve(value);
      } catch (error) { pending.reject(error); }
    },
    onLocalSubtitleResult: function (success, label, message) {
      const status = document.getElementById('subtitle-search-status');
      if (status) status.textContent = success ? 'تمت إضافة الترجمة: ' + label : (message || 'تعذر إضافة ملف الترجمة');
      if (success) {
        currentSubtitleLabel = label || 'ترجمة محلية';
        updateSubtitleStateUI(true, currentSubtitleLabel, true);
        if (typeof showToast === 'function') showToast('تم تفعيل الترجمة المحلية: ' + currentSubtitleLabel);
      } else {
        if (typeof showToast === 'function') showToast(message || 'تعذر قراءة ملف الترجمة');
      }
    }
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bind);
  else bind();
})();