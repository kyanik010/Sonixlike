    /**
     * =========================================================================
     * SECTION 6: UNIFIED DATA MODELS (CHANNELS, MOVIES, SERIES)
     * =========================================================================
     */
    // =========================================================================
    // SONIX TV: CINEMATIC WELCOME / SPLASH SCREEN CONTROLLER
    // =========================================================================
    let splashDismissed = false;
    let splashAutoTimer = null;

    window.isSplashActive = function() {
      const splash = document.getElementById('sonix-splash-screen');
      return Boolean(splash && !splash.classList.contains('hidden') && !splashDismissed);
    };

    window.dismissSplashScreen = function() {
      if (splashDismissed) return;
      splashDismissed = true;
      if (splashAutoTimer) {
        clearTimeout(splashAutoTimer);
        splashAutoTimer = null;
      }

      const splash = document.getElementById('sonix-splash-screen');
      if (!splash) return;

      splash.classList.add('splash-fading-out');

      setTimeout(() => {
        splash.classList.add('hidden');
        splash.style.display = 'none';

        // Seamless focus handover to the active screen
        const loginScreen = document.getElementById('sonix-login-screen');
        if (loginScreen && !loginScreen.classList.contains('hidden')) {
          const firstLoginField = document.getElementById('login-server') || document.getElementById('login-username');
          if (firstLoginField && window.remoteNavigator) {
            window.remoteNavigator.focusElement(firstLoginField);
          }
        } else {
          const homeTarget = document.getElementById('card-movies') || document.querySelector('.main-card');
          if (homeTarget && window.remoteNavigator) {
            window.remoteNavigator.focusElement(homeTarget);
          }
        }
      }, 650);
    };

    function initSplashScreen() {
      const splash = document.getElementById('sonix-splash-screen');
      if (!splash) return;

      splash.addEventListener('click', () => {
        window.dismissSplashScreen();
      });

      // Scheduled cinematic entrance duration: ~2.7s presentation + 0.65s fade-out ≈ 3.35s
      splashAutoTimer = setTimeout(() => {
        window.dismissSplashScreen();
      }, 2700);
    }

    // Mode State: 'live' | 'movies' | 'series'
    let currentAppMode = 'live';

    const SONIX_SETTINGS_KEY = 'sonix_user_settings_v1';
    const SONIX_DEFAULT_SETTINGS = {
      quality: 720,
      subtitles: true,
      favoritesFirst: false,
      rememberSection: true,
      rememberItem: true,
      textScale: 100
    };
    function readSonixSettings() {
      try {
        const saved = JSON.parse(localStorage.getItem(SONIX_SETTINGS_KEY) || '{}');
        const value = {...SONIX_DEFAULT_SETTINGS, ...(saved && typeof saved === 'object' ? saved : {})};
        value.quality = [480,720,1080].includes(Number(value.quality)) ? Number(value.quality) : 720;
        value.textScale = [90,100,110,120].includes(Number(value.textScale)) ? Number(value.textScale) : 100;
        ['subtitles','favoritesFirst','rememberSection','rememberItem'].forEach(key => value[key] = value[key] !== false);
        return value;
      } catch (_) { return {...SONIX_DEFAULT_SETTINGS}; }
    }
    let sonixSettings = readSonixSettings();
    function saveSonixSettings() {
      try { localStorage.setItem(SONIX_SETTINGS_KEY, JSON.stringify(sonixSettings)); }
      catch (error) { console.warn('Unable to save SONIX settings', error); }
    }
    function applySonixAppearance() {
      document.documentElement.style.fontSize = (16 * sonixSettings.textScale / 100) + 'px';
    }
    function syncSonixSettingsControls() {
      const values = {
        'settings-quality': sonixSettings.quality,
        'settings-subtitles': sonixSettings.subtitles,
        'settings-favorites-first': sonixSettings.favoritesFirst,
        'settings-remember-section': sonixSettings.rememberSection,
        'settings-remember-item': sonixSettings.rememberItem,
        'settings-text-scale': sonixSettings.textScale
      };
      Object.entries(values).forEach(([id,value]) => {
        const control = document.getElementById(id);
        if (!control) return;
        if (control.type === 'checkbox') control.checked = Boolean(value);
        else control.value = String(value);
      });
      if (window.NativePlayback && window.NativePlayback.getVideoEngine) {
        const engine = window.NativePlayback.getVideoEngine();
        const el = document.getElementById('settings-video-engine');
        if (el) el.value = engine || 'auto';
      }
    }
    function updateSonixSetting(key, value) {
      sonixSettings[key] = value;
      saveSonixSettings();
      if (key === 'textScale') applySonixAppearance();
      if (key === 'favoritesFirst' && currentActiveCategory === 'all') renderItems();
      if (key === 'subtitles' && videoPlaybackController) {
        videoPlaybackController.subtitlesEnabled = Boolean(value);
        if (window.NativePlayback && window.NativePlayback.setSubtitlesEnabled) {
          window.NativePlayback.setSubtitlesEnabled(Boolean(value));
        }
        const button = document.getElementById('btn-toggle-subtitles');
        if (button) {
          button.classList.toggle('is-active', Boolean(value));
          button.setAttribute('aria-pressed', String(Boolean(value)));
        }
        const label = document.getElementById('subtitle-label');
        if (label) label.textContent = value ? 'إخفاء الترجمة' : 'الترجمة';
      }
      if (key === 'quality' && window.NativePlayback && window.NativePlayback.isActive &&
          window.NativePlayback.isActive() && window.NativePlayback.setVideoQuality) {
        window.NativePlayback.setVideoQuality(Number(value));
        const label = document.getElementById('quality-label');
        if (label) label.textContent = Number(value) + 'p';
        document.querySelectorAll('.player-quality-option').forEach(option =>
          option.setAttribute('aria-pressed', String(Number(option.dataset.quality) === Number(value))));
      }
    }
    function initializeSonixSettingsControls() {
      syncSonixSettingsControls();
      applySonixAppearance();
      const bindings = [
        ['settings-quality','quality',value => Number(value)],
        ['settings-subtitles','subtitles',Boolean],
        ['settings-favorites-first','favoritesFirst',Boolean],
        ['settings-remember-section','rememberSection',Boolean],
        ['settings-remember-item','rememberItem',Boolean],
        ['settings-text-scale','textScale',value => Number(value)]
      ];
      bindings.forEach(([id,key,convert]) => {
        const control = document.getElementById(id);
        if (control) control.addEventListener('change', () => updateSonixSetting(key, convert(control.type === 'checkbox' ? control.checked : control.value)));
      });
      const engineControl = document.getElementById('settings-video-engine');
      if (engineControl) {
        engineControl.addEventListener('change', () => {
          const newEngine = engineControl.value;
          if (window.NativePlayback && window.NativePlayback.setVideoEngine) {
            window.NativePlayback.setVideoEngine(newEngine);
          }
          showToast('تم حفظ اختيار محرك الفيديو: ' + (engineControl.options[engineControl.selectedIndex]?.text || newEngine));
        });
      }
    }

    // 1. LIVE CHANNELS
    // Provider data stays empty until the user authenticates with Xtream Codes.
    let liveCategories = [], liveChannels = [];
    let movieCategories = [], movies = [];
    let seriesCategories = [], series = [];
    let activeCredentials = null, accountInfo = null;
    const FAVORITES_STORAGE_KEY = 'sonix_favorites_v2';
    function readSavedFavorites() {
      try {
        const values = JSON.parse(localStorage.getItem(FAVORITES_STORAGE_KEY) || '[]');
        return Array.isArray(values) ? values.filter(value => typeof value === 'string') : [];
      } catch (_) { return []; }
    }
    const memoryFavorites = new Set(readSavedFavorites());
    try { localStorage.removeItem('sonix_favorites_v1'); } catch (_) {}
    const saveFavorites = () => {
      try { localStorage.setItem(FAVORITES_STORAGE_KEY, JSON.stringify([...memoryFavorites])); }
      catch (error) { console.warn('Unable to persist favorites', error); }
    };
    function favoriteKey(itemId, mode = currentAppMode) {
      const account = activeCredentials
        ? String(activeCredentials.server || '') + '|' + String(activeCredentials.username || '')
        : 'unbound';
      return account + ':' + mode + ':' + String(itemId);
    }
    function isFavorite(itemId, mode = currentAppMode) {
      return memoryFavorites.has(favoriteKey(itemId, mode));
    }
    function resolveProviderImageUrl(value) {
      const raw = String(value || '').trim();
      if (!raw) return '';
      try {
        const base = activeCredentials && activeCredentials.server ? activeCredentials.server : document.baseURI;
        const url = new URL(raw, base);
        return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : '';
      } catch (_) { return ''; }
    }
    function setProviderImage(container, value, alt, fallbackText = '') {
      if (!container) return;
      container.textContent = fallbackText || '';
      const src = resolveProviderImageUrl(value);
      if (!src) return;
      const image = document.createElement('img');
      image.className = 'provider-logo';
      image.alt = alt || '';
      image.loading = 'lazy';
      image.referrerPolicy = 'no-referrer';
      image.src = src;
      image.onerror = () => {
        image.remove();
        if (!container.querySelector('img')) container.textContent = fallbackText || '';
      };
      container.replaceChildren(image);
    }

    /**
     * =========================================================================
     * SECTION 11 & 12: VIDEO ENGINE & DUAL AUDIO
     * =========================================================================
     */
    class VideoPlaybackController {
      constructor() {
        this.viewport = document.querySelector('.video-viewport');
        this.loaderEl = document.getElementById('player-loader');
        this.loaderText = document.getElementById('player-loader-text');
        this.errorEl = document.getElementById('player-error');
        this.currentItem = null;
        this.retryAttempts = 0;
        this.maxRetries = 3;
        this.isFullscreen = false;
        this.fullscreenOriginalParent = null;
        this.fullscreenOriginalNextSibling = null;
        this.isMuted = false;
        this.subtitlesEnabled = sonixSettings.subtitles;
        this.controlsHideTimer = null;
        this.isSeekingTimeline = false;
        this.timelineDurationMs = 0;
        this.pendingPlayToken = 0;
        this.viewportResizeObserver = null;
        if (this.viewport && typeof ResizeObserver !== 'undefined') {
          this.viewportResizeObserver = new ResizeObserver(() => this.syncNativeBounds());
          this.viewportResizeObserver.observe(this.viewport);
        }
        this.bindEvents();
      }

      bindEvents() {
        if (this.viewport) {
          this.viewport.addEventListener('pointerdown', () => this.showPlaybackControls());
          this.viewport.addEventListener('click', (event) => {
            if (!event.target.closest('.player-playback-controls')) {
              this.showPlaybackControls();
            }
          });
        }
        const playbackControls = document.getElementById('player-playback-controls');
        if (playbackControls) {
          playbackControls.addEventListener('pointerdown', () => this.showPlaybackControls());
          playbackControls.addEventListener('click', () => this.showPlaybackControls());
        }
        document.getElementById('player-retry-action').addEventListener('click', () => {
          if (this.currentItem) {
            this.syncNativeBounds();
            this.loadItem(this.currentItem);
          }
        });
        document.getElementById('ctrl-mute-btn').addEventListener('click', () => this.toggleMute());
        document.getElementById('ctrl-seek-back').addEventListener('click', () => this.seekBy(-10000));
        document.getElementById('ctrl-seek-forward').addEventListener('click', () => this.seekBy(10000));
        const seekBar = document.getElementById('player-seek-bar');
        if (seekBar) {
          seekBar.addEventListener('pointerdown', () => {
            this.isSeekingTimeline = true;
            this.showPlaybackControls();
          });
          seekBar.addEventListener('input', () => {
            this.isSeekingTimeline = true;
            this.showPlaybackControls();
            const target = this.timelineDurationMs * (Number(seekBar.value) / 1000);
            const currentLabel = document.getElementById('player-current-time');
            if (currentLabel) currentLabel.textContent = this.formatTimelineTime(target);
            seekBar.style.setProperty('--progress', (Number(seekBar.value) / 10) + '%');
            seekBar.setAttribute('aria-valuenow', seekBar.value);
          });
          seekBar.addEventListener('change', () => {
            const target = this.timelineDurationMs * (Number(seekBar.value) / 1000);
            this.isSeekingTimeline = false;
            this.seekToPosition(target);
          });
          seekBar.addEventListener('pointercancel', () => { this.isSeekingTimeline = false; });
          seekBar.addEventListener('focus', () => this.showPlaybackControls());
          seekBar.addEventListener('keydown', (event) => {
            if (event.key !== 'ArrowLeft' && event.key !== 'Left' &&
                event.key !== 'ArrowRight' && event.key !== 'Right') return;
            event.preventDefault();
            event.stopPropagation();
            this.showPlaybackControls();
            this.seekBy((event.key === 'ArrowRight' || event.key === 'Right') ? 10000 : -10000);
          });
        }
        window.addEventListener('keydown', () => {
          if (this.isFullscreen) this.showPlaybackControls();
        }, true);
        document.getElementById('btn-toggle-subtitles').addEventListener('click', () => this.toggleSubtitles());
        document.getElementById('btn-quality').addEventListener('click', () => this.toggleQualityMenu());
        document.querySelectorAll('.player-quality-option').forEach(option => option.addEventListener('click', () => {
          const quality=Number(option.dataset.quality);
          if (window.NativePlayback && window.NativePlayback.setVideoQuality) {
            window.NativePlayback.setVideoQuality(quality);
            updateSonixSetting('quality', quality);
            document.querySelectorAll('.player-quality-option').forEach(el => el.setAttribute('aria-pressed', String(el===option)));
            document.getElementById('quality-label').textContent=option.textContent;
            document.getElementById('player-quality-menu').classList.remove('open');
            document.getElementById('btn-quality').setAttribute('aria-expanded','false');
          }
        }));
        document.getElementById('btn-playback-fav').addEventListener('click', () => {
          if (this.currentItem) toggleFavorite(this.currentItem.id);
        });
        if (this.viewport) {
          let lastTapTime = 0;
          this.viewport.addEventListener('touchend', (e) => {
            // Toolbar taps operate their controls; only a double tap on the video
            // surface itself may leave fullscreen.
            if (e.target.closest('.player-playback-controls, .player-timeline, .control-btn, .action-btn')) return;
            const now = Date.now();
            if (now - lastTapTime < 350 && now - lastTapTime > 40) {
              e.preventDefault();
              this.toggleFullscreen();
            }
            lastTapTime = now;
          });
        }
        window.addEventListener('resize', () => this.syncNativeBounds());
        window.addEventListener('scroll', () => this.syncNativeBounds(), true);
      }

      showPlaybackControls() {
        // Playback controls are fullscreen-only. Ignore taps and remote navigation
        // while the native video is embedded in the preview panel.
        if (!this.isFullscreen || !this.currentItem || !window.NativePlayback || !window.NativePlayback.isActive()) return;
        const controls = document.getElementById('player-playback-controls');
        if (!controls) return;
        controls.classList.add('visible');
        const timeline = document.getElementById('player-timeline');
        if (timeline && this.viewport && this.viewport.classList.contains('has-vod-timeline')) {
          timeline.classList.add('visible');
        }
        if (this.viewport) this.viewport.classList.add('playback-controls-visible');
        if (this.controlsHideTimer) clearTimeout(this.controlsHideTimer);
        this.controlsHideTimer = setTimeout(() => this.hidePlaybackControls(), 10000);
      }

      hidePlaybackControls() {
        // Keep the fullscreen toolbar available behind the external-audio dialog.
        // Otherwise its 10-second auto-hide timer can leave the close action
        // without a visible/focusable return target on TV remotes.
        const audioDialog = document.getElementById('modal-ext-audio');
        const subtitleDialog = document.getElementById('modal-subtitles');
        if (this.isFullscreen && ((audioDialog && audioDialog.classList.contains('active')) ||
            (subtitleDialog && subtitleDialog.classList.contains('active')))) {
          if (this.controlsHideTimer) clearTimeout(this.controlsHideTimer);
          this.controlsHideTimer = null;
          return;
        }
        const controls = document.getElementById('player-playback-controls');
        if (controls) {
          controls.classList.remove('visible');
          const timeline = document.getElementById('player-timeline');
          if (timeline) timeline.classList.remove('visible');
          const menu = document.getElementById('player-quality-menu');
          if (menu) menu.classList.remove('open');
          const qualityButton = document.getElementById('btn-quality');
          if (qualityButton) qualityButton.setAttribute('aria-expanded', 'false');
        }
        if (this.viewport) this.viewport.classList.remove('playback-controls-visible');
        if (this.controlsHideTimer) clearTimeout(this.controlsHideTimer);
        this.controlsHideTimer = null;
      }

      loadItem(item) {
        if (!item) return;
        if (dualAudioEngine && dualAudioEngine.isActive &&
            dualAudioEngine.selectedSourceChannel &&
            String(dualAudioEngine.selectedSourceChannel.id) === String(item.id)) {
          dualAudioEngine.stopExternalAudio(false);
        }
        this.currentItem = item;
        this.hidePlaybackControls();
        const playerPanel=document.querySelector('.live-player-col'); if(playerPanel) playerPanel.classList.remove('has-active-playback');
        this.retryAttempts = 0;
        this.errorEl.classList.remove('active');
        this.showLoader('جارٍ الاتصال بـ ' + (item.name || 'المحتوى') + '...');
        const streamUrl = String(item.streamUrl || '').trim();
        if (!streamUrl) {
          if (window.NativePlayback) window.NativePlayback.stop();
          this.showError('رابط التشغيل غير متوفر من اشتراك IPTV.');
          return;
        }
        if (!window.NativePlayback || typeof window.NativePlayback.play !== 'function') {
          this.showError('واجهة مشغل Android الأصلية غير متاحة.');
          return;
        }
        this.updateUiInfo(item);
        // The native bridge owns player replacement and stops the previous stream
        // exactly once, after the new viewport bounds have been validated.
        // The preview host is a dedicated, non-shrinking layout slot. Still wait
        // for WebView layout to publish real bounds; transient zero-size frames are
        // retried instead of being mistaken for a stream/player failure.
        const playToken = ++this.pendingPlayToken;
        const tryStartNativePlayback = (attempt = 0) => {
          if (playToken !== this.pendingPlayToken || this.currentItem !== item) return;
          const viewport = this.viewport;
          if (!viewport || !viewport.isConnected) {
            if (window.NativePlayback && typeof window.NativePlayback.stop === 'function') window.NativePlayback.stop();
            this.showError('تعذر تحديد مساحة عرض الفيديو.');
            return;
          }
          const style = window.getComputedStyle(viewport);
          const rect = viewport.getBoundingClientRect();
          const visible = style.display !== 'none' && style.visibility !== 'hidden' &&
            Number(style.opacity) !== 0;
          const isWithinWindow = rect.width > 0 && rect.height > 0 && rect.right > 0 && rect.bottom > 0 && rect.left < window.innerWidth && rect.top < window.innerHeight;
          if (!visible || !isWithinWindow) {
            if (attempt < 15) {
              requestAnimationFrame(() => setTimeout(() => tryStartNativePlayback(attempt + 1), 80));
              return;
            }
            console.error('[SONIXSurface] preview host did not obtain visible bounds', JSON.stringify({
              rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
              display: style.display, visibility: style.visibility, opacity: style.opacity,
              parent: viewport.parentElement ? {
                width: viewport.parentElement.getBoundingClientRect().width,
                height: viewport.parentElement.getBoundingClientRect().height,
                display: window.getComputedStyle(viewport.parentElement).display
              } : null
            }));
            if (window.NativePlayback && typeof window.NativePlayback.stop === 'function') window.NativePlayback.stop();
            this.showError('تعذر تجهيز مساحة معاينة الفيديو. أعد المحاولة.');
            return;
          }
          const pixelRatio = window.devicePixelRatio || 1;
          const cornerRadius = this.isFullscreen ? 0 : 16;
          console.info('[SONIXSurface] valid preview host before native play', JSON.stringify({
            rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
            display: style.display, visibility: style.visibility, opacity: style.opacity,
            dpr: pixelRatio, cornerRadius, attempt
          }));
          // Native Media3 remains the sole video renderer; Android applies the matching clip.
          if (item.streamDescriptor && window.NativePlayback.playStream) {
            window.NativePlayback.playStream(item.streamDescriptor.type, String(item.streamDescriptor.id), item.streamDescriptor.ext, rect.left, rect.top, rect.width, rect.height, pixelRatio, cornerRadius);
          } else {
            window.NativePlayback.play(streamUrl, rect.left, rect.top, rect.width, rect.height, pixelRatio, cornerRadius);
          }
          if (window.NativePlayback.setVideoQuality) window.NativePlayback.setVideoQuality(Number(sonixSettings.quality));
          if (window.NativePlayback.setSubtitlesEnabled) window.NativePlayback.setSubtitlesEnabled(Boolean(sonixSettings.subtitles));
        };
        const currentRect = this.viewport ? this.viewport.getBoundingClientRect() : null;
        if (currentRect && currentRect.width > 0 && currentRect.height > 0) {
          requestAnimationFrame(() => tryStartNativePlayback());
        } else {
          requestAnimationFrame(() => setTimeout(() => tryStartNativePlayback(), 40));
        }
      }

      syncNativeBounds() {
        if (!this.currentItem || !window.NativePlayback || typeof window.NativePlayback.resize !== 'function' || !this.viewport) return;
        requestAnimationFrame(() => {
          if (!this.currentItem || !window.NativePlayback || !this.viewport) return;
          const rect = this.viewport.getBoundingClientRect();
          // Always synchronize visibility as well as size. Keeping the last non-zero
          // bounds when the viewport is hidden/collapsed leaves a stale native overlay.
          window.NativePlayback.resize(
            rect.left || 0, rect.top || 0,
            rect.width || 0, rect.height || 0,
            window.devicePixelRatio || 1, this.isFullscreen ? 0 : 16
          );
        });
      }

      showLoader(msg) {
        this.loaderText.textContent = msg || 'جارٍ التحميل...';
        this.loaderEl.classList.add('active');
      }

      hideLoader() {
        this.loaderEl.classList.remove('active');
        this.errorEl.classList.remove('active');
      }

      showError(msg) {
        this.loaderEl.classList.remove('active');
        const message = this.errorEl.querySelector('div:nth-child(2)');
        if (message) message.textContent = msg || 'تعذر تشغيل المحتوى.';
        this.errorEl.classList.add('active');
      }

      updatePlaybackState(isPlaying) {
        const icon = document.getElementById('icon-play-state');
        const button = document.getElementById('ctrl-play-pause');
        if (icon && button) {
          icon.innerHTML = isPlaying
            ? '<rect x="6" y="4" width="4" height="16" fill="currentColor"/><rect x="14" y="4" width="4" height="16" fill="currentColor"/>'
            : '<polygon points="5,3 19,12 5,21" fill="currentColor"/>';
          button.title = isPlaying ? 'إيقاف مؤقت' : 'تشغيل';
        }
      }

      togglePlayPause() {
        if (!window.NativePlayback || !window.NativePlayback.isActive()) {
          if (this.currentItem) this.loadItem(this.currentItem);
          return;
        }
        window.NativePlayback.toggle();
      }

      toggleMute() {
        if (dualAudioEngine && dualAudioEngine.isActive) {
          showToast('الصوت الأساسي مكتوم تلقائياً أثناء تشغيل مصدر صوت خارجي');
          return;
        }
        this.isMuted = !this.isMuted;
        if (window.NativePlayback) window.NativePlayback.setMuted(this.isMuted);
        const label=document.getElementById('mute-label');
        if(label) label.textContent=this.isMuted ? 'إلغاء الكتم' : 'كتم الصوت';
        const button=document.getElementById('ctrl-mute-btn');
        if(button) button.classList.toggle('is-active',this.isMuted);
      }

      formatTimelineTime(milliseconds) {
        const totalSeconds = Math.max(0, Math.floor(Number(milliseconds) / 1000));
        const hours = Math.floor(totalSeconds / 3600);
        const minutes = Math.floor((totalSeconds % 3600) / 60);
        const seconds = totalSeconds % 60;
        const mm = String(minutes).padStart(2, '0');
        const ss = String(seconds).padStart(2, '0');
        return hours > 0 ? hours + ':' + mm + ':' + ss : mm + ':' + ss;
      }

      updateTimeline(positionMs, durationMs, seekable) {
        const timeline = document.getElementById('player-timeline');
        const seekBar = document.getElementById('player-seek-bar');
        if (!timeline || !seekBar) return;
        const duration = Number(durationMs);
        const position = Math.max(0, Number(positionMs) || 0);
        const usable = currentAppMode !== 'live' && !!seekable && Number.isFinite(duration) && duration > 0;
        timeline.classList.toggle('available', usable);
        seekBar.disabled = !usable;
        if (!usable) {
          this.timelineDurationMs = 0;
          return;
        }
        this.timelineDurationMs = duration;
        const totalLabel = document.getElementById('player-total-time');
        if (totalLabel) totalLabel.textContent = this.formatTimelineTime(duration);
        if (!this.isSeekingTimeline) {
          const ratio = Math.max(0, Math.min(1, position / duration));
          seekBar.value = String(Math.round(ratio * 1000));
          seekBar.setAttribute('aria-valuenow', seekBar.value);
          seekBar.style.setProperty('--progress', (ratio * 100) + '%');
          const currentLabel = document.getElementById('player-current-time');
          if (currentLabel) currentLabel.textContent = this.formatTimelineTime(position);
        }
      }

      seekToPosition(positionMs) {
        if (currentAppMode === 'live' || !window.NativePlayback) return;
        const target = Math.max(0, Math.min(this.timelineDurationMs, Number(positionMs) || 0));
        if (typeof window.NativePlayback.seekTo === 'function') {
          window.NativePlayback.seekTo(Math.round(target));
        } else if (typeof window.NativePlayback.seekBy === 'function') {
          const current = this.timelineDurationMs * (Number(document.getElementById('player-seek-bar')?.value || 0) / 1000);
          window.NativePlayback.seekBy(Math.round(target - current));
        }
        this.showPlaybackControls();
      }

      seekBy(offsetMs) {
        if (currentAppMode === 'live') {
          showToast('التقديم والإرجاع متاحان للأفلام والمسلسلات فقط');
          return;
        }
        if (!window.NativePlayback || !window.NativePlayback.seekBy) return;
        window.NativePlayback.seekBy(offsetMs);
      }

      toggleSubtitles() {
        if (window.SonixSubtitles && window.SonixSubtitles.openSearch) {
          window.SonixSubtitles.openSearch();
          return;
        }
        showToast('واجهة الترجمة غير جاهزة');
      }

      toggleQualityMenu() {
        const menu=document.getElementById('player-quality-menu');
        const button=document.getElementById('btn-quality');
        if(!menu||!button) return;
        const open=!menu.classList.contains('open');
        menu.classList.toggle('open',open);
        button.setAttribute('aria-expanded',String(open));
      }

      pause() {
        if (window.NativePlayback) window.NativePlayback.pause();
        if (dualAudioEngine) dualAudioEngine.pause();
      }

      toggleFullscreen() {
        if (!this.viewport) return;
        const enteringFullscreen = !this.isFullscreen;

        if (enteringFullscreen) {
          this.fullscreenPreviousFocus = remoteNavigator
            ? remoteNavigator.currentFocusedElement
            : document.activeElement;
          // Move the viewport out of the app's multi-column layout. This avoids
          // fixed-position elements being clipped by transformed/overflow ancestors.
          this.fullscreenOriginalParent = this.viewport.parentNode;
          this.fullscreenOriginalNextSibling = this.viewport.nextSibling;
          // Preserve live-channel control layout after the viewport is portaled
          // out of .live-player-col into document.body.
          const playerPanel = document.querySelector('.live-player-col');
          this.viewport.classList.toggle('is-live-mode', !!(playerPanel && playerPanel.classList.contains('is-live-mode')));
          document.body.appendChild(this.viewport);
          document.body.classList.add('sonix-video-fullscreen');
          this.isFullscreen = true;
          this.viewport.classList.add('is-fullscreen');
          // Ask Android to hide system bars and let the native video surface fill
          // the physical display, not just the WebView's content area.
          if (window.NativePlayback && typeof window.NativePlayback.setFullscreen === 'function') {
            window.NativePlayback.setFullscreen(true);
          }
        } else {
          // Hide controls and dismiss any open quality menu before restoring preview.
          this.hidePlaybackControls();
          this.isFullscreen = false;
          this.viewport.classList.remove('is-fullscreen', 'is-live-mode');
          document.body.classList.remove('sonix-video-fullscreen');
          // Restore Android system bars and the normal uncropped preview scaling.
          if (window.NativePlayback && typeof window.NativePlayback.setFullscreen === 'function') {
            window.NativePlayback.setFullscreen(false);
          }
          const parent = this.fullscreenOriginalParent;
          if (parent) {
            if (this.fullscreenOriginalNextSibling &&
                this.fullscreenOriginalNextSibling.parentNode === parent) {
              parent.insertBefore(this.viewport, this.fullscreenOriginalNextSibling);
            } else {
              parent.appendChild(this.viewport);
            }
          }
          this.fullscreenOriginalParent = null;
          this.fullscreenOriginalNextSibling = null;
          const previousFocus = this.fullscreenPreviousFocus;
          this.fullscreenPreviousFocus = null;
          if (remoteNavigator) {
            const canRestoreFocus = previousFocus && previousFocus.isConnected &&
              previousFocus.getClientRects().length > 0;
            const selectedRow = document.querySelector('.channel-row.selected');
            const fallbackFocus = selectedRow || remoteNavigator.getVisibleFocusableElements()[0];
            remoteNavigator.focusElement(canRestoreFocus ? previousFocus : fallbackFocus);
          }
        }

        // Wait for the portal/layout transition, then resize the native surface
        // to exactly match the new viewport rectangle.
        requestAnimationFrame(() => this.syncNativeBounds());
        if (this.isFullscreen) {
          this.showPlaybackControls();
          showToast('تم التكبير إلى ملء الشاشة');
          const firstControl = document.querySelector('#player-playback-controls .playback-control-btn');
          if (firstControl && remoteNavigator) remoteNavigator.focusElement(firstControl);
        } else {
          showToast('تم الخروج من وضع ملء الشاشة');
        }
      }

      exitFullscreen() {
        if (this.isFullscreen) {
          this.toggleFullscreen();
          return true;
        }
        return false;
      }

      updateUiInfo(item) {
        document.getElementById('ticker-cat').textContent = item.categoryName || '';
        document.getElementById('ticker-ch').textContent = item.name || '';
        const scoreOverlay = document.getElementById('player-score-overlay');
        const playerPanel = document.querySelector('.live-player-col');
        if (playerPanel) playerPanel.classList.toggle('is-live-mode', currentAppMode === 'live');
        if (this.viewport) this.viewport.classList.toggle('has-vod-timeline', currentAppMode !== 'live');
        if (scoreOverlay) scoreOverlay.style.display = currentAppMode === 'live' ? 'flex' : 'none';
        if (currentAppMode === 'live') {
          document.getElementById('epg-modal-channel-name').textContent = 'دليل البرامج - ' + item.name;
          document.getElementById('epg-curr-title').textContent = item.epgTitle || 'بث مباشر';
          document.getElementById('epg-curr-desc').textContent = item.epgDesc || 'لا توجد تفاصيل إضافية لهذا البرنامج.';
          document.getElementById('epg-next-title').textContent = item.epgNext || 'البرنامج التالي';
        }
        updateFavoriteButtons(item.id);
      }

      stopAndRelease() {
        this.pendingPlayToken++;
        if (dualAudioEngine) dualAudioEngine.stopExternalAudio(false);
        if (window.NativePlayback) window.NativePlayback.stop();
        this.hidePlaybackControls();
        const playerPanel=document.querySelector('.live-player-col'); if(playerPanel) playerPanel.classList.remove('has-active-playback');
        this.currentItem = null;
        this.updatePlaybackState(false);
      }
    }

    /**
     * =========================================================================
     * SECTION 12: DUAL AUDIO SYSTEM
     * =========================================================================
     */
    class DualAudioSystem {
      constructor(videoController) {
        this.videoController = videoController; this.selectedSourceChannel = null; this.isActive = false;
        this.delayMs = 0; this.volume = 1; this.originalVideoMuted = false;
        this.statusTextEl = document.getElementById('ext-audio-status-text'); this.statusPillEl = document.getElementById('ext-audio-status-pill');
        this.delayLabelEl = document.getElementById('delay-value-label'); this.activeBadgeEl = document.getElementById('ext-audio-badge');
        this.btnExtAudioText = document.getElementById('ext-audio-btn-text'); this.btnExtAudio = document.getElementById('btn-open-ext-audio');
        this.sourceNameEl = document.getElementById('selected-audio-source-name'); this.bindEvents(); this.updateDelayLabel();
      }
      bindEvents() {
        document.getElementById('btn-start-audio').addEventListener('click', () => this.startExternalAudio());
        document.getElementById('btn-stop-audio').addEventListener('click', () => this.stopExternalAudio());
        document.getElementById('delay-plus-100').addEventListener('click', () => this.adjustDelay(100));
        document.getElementById('delay-minus-100').addEventListener('click', () => this.adjustDelay(-100));
        document.getElementById('delay-plus-500').addEventListener('click', () => this.adjustDelay(500));
        document.getElementById('delay-minus-500').addEventListener('click', () => this.adjustDelay(-500));
        document.getElementById('delay-reset').addEventListener('click', () => this.resetDelay());
        const slider = document.getElementById('external-audio-volume');
        if (slider) slider.addEventListener('input', () => { this.volume = Math.max(0, Math.min(1, Number(slider.value) / 100)); document.getElementById('external-audio-volume-label').textContent = Math.round(this.volume * 100) + '%'; if (window.NativePlayback) window.NativePlayback.setExternalAudioVolume(this.volume); });
      }
      setSourceChannel(channel) {
        if (!channel || !channel.id) return;
        const currentVideo = this.videoController.currentItem;
        // Compare stable Xtream stream IDs only. streamUrl is deliberately not
        // used here because native playback stores the placeholder native://stream
        // on every item, not the resolved provider URL.
        if (currentVideo && String(currentVideo.id) === String(channel.id)) {
          showToast('اختر قناة صوتية مختلفة عن قناة الفيديو');
          return;
        }
        this.selectedSourceChannel = channel;
        this.sourceNameEl.textContent = channel.name + ' (' + channel.categoryName + ')';
        document.querySelectorAll('.audio-picker-item').forEach(el =>
          el.classList.toggle('active', el.dataset.channelId === String(channel.id))
        );
        showToast('تم اختيار ' + channel.name + ' — جارٍ تشغيل الصوت الخارجي');
        this.startExternalAudio();
      }
      startExternalAudio() {
        const source = this.selectedSourceChannel;
        if (!source || !source.id) {
          showToast('يرجى اختيار قناة صوتية من beIN SPORTS أو ALWAN أولاً');
          return;
        }
        if (!window.NativePlayback || typeof window.NativePlayback.attachExternalAudioStream !== 'function') {
          this.setStatus('failed', 'مصدر الصوت غير متاح');
          showToast('تعذر الحصول على رابط القناة الصوتية من الاشتراك');
          return;
        }
        if (!this.isActive) {
          this.originalVideoMuted = !!this.videoController.isMuted;
          this.originalVideoMutedRecorded = true;
        }
        this.isActive = true;
        this.setStatus('connecting', 'جارٍ الاتصال...');
        this.activeBadgeEl.classList.add('visible');
        this.btnExtAudio.classList.add('is-active');
        this.btnExtAudioText.textContent = 'الصوت الخارجي: ' + source.name;
        window.NativePlayback.setExternalAudioVolume(this.volume);
        window.NativePlayback.setExternalAudioSyncOffset(this.delayMs);
        // Pass the real provider stream ID to the native secondary player.
        // Never overwrite channel.streamUrl with the shared native://stream placeholder.
        window.NativePlayback.attachExternalAudioStream(
          String(source.id),
          source.containerExtension || 'ts'
        );
      }
      onBuffering() { if (this.isActive) this.setStatus('connecting', 'جارٍ الاتصال أو إعادة المحاولة...'); }
      onPlaying() {
        if (this.isActive) {
          if (!this.originalVideoMutedRecorded) {
            this.originalVideoMuted = !!this.videoController.isMuted;
            this.originalVideoMutedRecorded = true;
          }
          this.videoController.isMuted = true;
          this.setStatus('playing', 'يعمل');
          showToast('بدأ تشغيل القناة الصوتية المستقلة بنجاح');
        }
      }
      onFailure(message) {
        this.stopExternalAudio(false, false);
        this.setStatus('failed', 'فشل الاتصال');
        showToast(message || 'فشل تشغيل مصدر الصوت الخارجي؛ تمت استعادة صوت الفيديو');
      }
      stopExternalAudio(showMessage = true, notifyNative = true) {
        if (notifyNative && window.NativePlayback) window.NativePlayback.detachExternalAudio();
        this.isActive = false;
        if (this.originalVideoMutedRecorded) {
          this.videoController.isMuted = this.originalVideoMuted;
          this.originalVideoMutedRecorded = false;
        }
        this.originalVideoMuted = false; this.delayMs = 0; this.updateDelayLabel();
        this.activeBadgeEl.classList.remove('visible'); this.btnExtAudio.classList.remove('is-active'); this.btnExtAudioText.textContent = 'الصوت الخارجي'; this.setStatus('stopped', 'متوقف');
        if (showMessage) showToast('تم إيقاف الصوت الخارجي واستعادة صوت القناة الأساسية');
      }
      adjustDelay(deltaMs) {
        this.delayMs = Math.max(-10000, Math.min(10000, this.delayMs + deltaMs)); this.updateDelayLabel();
        if (window.NativePlayback) window.NativePlayback.setExternalAudioSyncOffset(this.delayMs);
        showToast(this.delayMs > 0 ? 'تأخير الصوت ' + this.delayMs + ' مللي ثانية' : this.delayMs < 0 ? 'تقديم الصوت ' + Math.abs(this.delayMs) + ' مللي ثانية' : 'فرق التوقيت صفر');
      }
      resetDelay() { this.delayMs = 0; this.updateDelayLabel(); if (window.NativePlayback) window.NativePlayback.setExternalAudioSyncOffset(0); }
      updateDelayLabel() { this.delayLabelEl.textContent = (this.delayMs > 0 ? '+' : '') + this.delayMs + ' ms'; }
      setStatus(type, label) { this.statusPillEl.className = 'audio-status-pill status-' + type; this.statusTextEl.textContent = label; }
      pause() { if (window.NativePlayback) window.NativePlayback.pauseExternalAudio(); }
      resume() { if (this.isActive && window.NativePlayback) window.NativePlayback.resumeExternalAudio(); }
      releaseAll() { this.stopExternalAudio(false); }
    }
    window.onExternalAudioBuffering = function() { if (dualAudioEngine) dualAudioEngine.onBuffering(); };
    window.onExternalAudioPlaying = function() { if (dualAudioEngine) dualAudioEngine.onPlaying(); };
    window.onExternalAudioError = function(message) { if (dualAudioEngine) dualAudioEngine.onFailure(message); };
    window.onExternalAudioStopped = function() { if (dualAudioEngine && dualAudioEngine.isActive) dualAudioEngine.stopExternalAudio(false, false); };
    window.onExternalAudioDetached = function() { if (dualAudioEngine && dualAudioEngine.isActive) dualAudioEngine.stopExternalAudio(false, false); };

    /**
     * =========================================================================
     * SECTION 7.5: PROFESSIONAL ITEM PREVIEW PANEL ENGINE (SONIX TV)
     * =========================================================================
     */
    const previewDetailsCache = new Map(); // key: `${mode}:${id}` -> cached details object
    let previewDebounceTimer = null;
    let previewCurrentToken = 0;
    let previewActiveRequestId = null;
    let previewLastRenderedKey = null;

    function escapeHtml(str) {
      if (!str) return '';
      return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
    }

    function decodeEpgText(val) {
      if (!val) return '';
      try {
        return decodeURIComponent(escape(atob(val)));
      } catch (_) {
        return String(val);
      }
    }

    function formatEpgTime(timestampSec) {
      if (!timestampSec || isNaN(timestampSec) || timestampSec <= 0) return '';
      try {
        const d = new Date(timestampSec * 1000);
        let hours = d.getHours();
        let mins = d.getMinutes();
        const ampm = hours >= 12 ? 'م' : 'ص';
        hours = hours % 12;
        if (hours === 0) hours = 12;
        const minsStr = mins < 10 ? '0' + mins : String(mins);
        return `${hours}:${minsStr} ${ampm}`;
      } catch (_) {
        return '';
      }
    }

    function parseProgramTimestamps(prog) {
      let startSec = Number(prog.start_timestamp);
      let stopSec = Number(prog.stop_timestamp);
      if (isNaN(startSec) || startSec <= 0) {
        if (prog.start) {
          const d = new Date(String(prog.start).replace(/-/g, '/'));
          if (!isNaN(d.getTime())) startSec = Math.floor(d.getTime() / 1000);
        }
      }
      if (isNaN(stopSec) || stopSec <= 0) {
        if (prog.end) {
          const d = new Date(String(prog.end).replace(/-/g, '/'));
          if (!isNaN(d.getTime())) stopSec = Math.floor(d.getTime() / 1000);
        }
      }
      return { startSec, stopSec };
    }

    function calculateCurrentAndNextEpg(listings) {
      if (!Array.isArray(listings) || !listings.length) return null;
      const nowSec = Math.floor(Date.now() / 1000);
      let currentProg = null;
      let nextProg = null;

      for (let i = 0; i < listings.length; i++) {
        const prog = listings[i];
        const { startSec, stopSec } = parseProgramTimestamps(prog);
        if (startSec > 0 && stopSec > 0) {
          if (nowSec >= startSec && nowSec < stopSec) {
            currentProg = {
              title: decodeEpgText(prog.title),
              desc: decodeEpgText(prog.description),
              timeWindow: `${formatEpgTime(startSec)} - ${formatEpgTime(stopSec)}`,
              startSec,
              stopSec
            };
            if (i + 1 < listings.length) {
              const nextRaw = listings[i + 1];
              const nextTimes = parseProgramTimestamps(nextRaw);
              nextProg = {
                title: decodeEpgText(nextRaw.title),
                time: formatEpgTime(nextTimes.startSec)
              };
            }
            break;
          }
        }
      }

      if (!currentProg && listings.length > 0) {
        const first = listings[0];
        const firstTimes = parseProgramTimestamps(first);
        const timeWindow = (firstTimes.startSec > 0 && firstTimes.stopSec > 0)
          ? `${formatEpgTime(firstTimes.startSec)} - ${formatEpgTime(firstTimes.stopSec)}`
          : '';
        currentProg = {
          title: decodeEpgText(first.title),
          desc: decodeEpgText(first.description),
          timeWindow
        };
        if (listings.length > 1) {
          const sec = listings[1];
          const secTimes = parseProgramTimestamps(sec);
          nextProg = {
            title: decodeEpgText(sec.title),
            time: formatEpgTime(secTimes.startSec)
          };
        }
      }

      return { current: currentProg, next: nextProg };
    }

    function renderItemPreview(item, mode, extraDetails = null) {
      const container = document.getElementById('sonix-preview-content');
      if (!container || !item) return;

      if (mode === 'movies' || mode === 'series') {
        const isMovie = mode === 'movies';
        const typeLabel = isMovie ? 'فيلم' : 'مسلسل';
        const name = item.name || '';
        const posterUrl = (extraDetails && extraDetails.poster) || item.logo || '';
        const year = (extraDetails && extraDetails.year) || item.year || '';
        const genre = (extraDetails && extraDetails.genre) || item.genre || item.categoryName || '';
        const rating = (extraDetails && extraDetails.rating) || item.rating || '';
        const plot = (extraDetails && extraDetails.plot) || item.desc || '';

        let badgesHtml = `<span class="preview-badge preview-badge-type">${typeLabel}</span>`;
        if (year && String(year).trim()) {
          badgesHtml += `<span class="preview-badge preview-badge-year">${escapeHtml(year)}</span>`;
        }
        if (genre && String(genre).trim() && !genre.startsWith('movie-') && !genre.startsWith('series-')) {
          badgesHtml += `<span class="preview-badge preview-badge-genre">${escapeHtml(genre)}</span>`;
        }
        if (rating && String(rating).trim() && String(rating) !== '0') {
          const numRating = Number(rating);
          const ratingText = !isNaN(numRating) ? numRating.toFixed(1) : String(rating);
          badgesHtml += `<span class="preview-badge preview-badge-rating">
            <svg viewBox="0 0 24 24"><path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"/></svg>
            ${escapeHtml(ratingText)}
          </span>`;
        }

        let posterHtml = '';
        if (posterUrl) {
          posterHtml = `
            <div class="preview-poster-wrapper">
              <img class="preview-poster-img" src="${escapeHtml(posterUrl)}" alt="${escapeHtml(name)}"
                   onerror="this.style.display='none'; if(this.nextElementSibling) this.nextElementSibling.style.display='flex';" />
              <div class="preview-poster-fallback" style="display:none;">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg>
                <span>${escapeHtml(name)}</span>
              </div>
            </div>`;
        } else {
          posterHtml = `
            <div class="preview-poster-wrapper">
              <div class="preview-poster-fallback">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg>
                <span>${escapeHtml(name)}</span>
              </div>
            </div>`;
        }

        let plotHtml = '';
        if (plot && String(plot).trim()) {
          plotHtml = `
            <div class="preview-plot-box">
              <p class="preview-plot-text">${escapeHtml(plot)}</p>
            </div>`;
        }

        container.innerHTML = `
          <div class="preview-vod-card">
            <div class="preview-vod-header">
              ${posterHtml}
              <div class="preview-vod-meta">
                <h3 class="preview-title">${escapeHtml(name)}</h3>
                <div class="preview-badges-row">${badgesHtml}</div>
              </div>
            </div>
            ${plotHtml}
          </div>`;
      } else {
        const name = item.name || '';
        const logoUrl = item.logo || '';
        const number = item.number || '';
        const categoryName = item.categoryName || '';

        let badgesHtml = '';
        if (number && String(number).trim()) {
          badgesHtml += `<span class="preview-badge preview-badge-category">CH ${escapeHtml(String(number))}</span>`;
        }
        if (categoryName && String(categoryName).trim() && !categoryName.startsWith('live-')) {
          badgesHtml += `<span class="preview-badge preview-badge-category">${escapeHtml(categoryName)}</span>`;
        }

        let logoHtml = '';
        if (logoUrl) {
          logoHtml = `
            <div class="preview-channel-logo-wrapper">
              <img class="preview-channel-logo-img" src="${escapeHtml(logoUrl)}" alt="${escapeHtml(name)}"
                   onerror="this.style.display='none'; if(this.nextElementSibling) this.nextElementSibling.style.display='block';" />
              <div class="preview-channel-logo-fallback" style="display:none;">${escapeHtml(name.substring(0, 3))}</div>
            </div>`;
        } else {
          logoHtml = `
            <div class="preview-channel-logo-wrapper">
              <div class="preview-channel-logo-fallback">${escapeHtml(name.substring(0, 3))}</div>
            </div>`;
        }

        let epgContentHtml = '';
        if (extraDetails && extraDetails.epg && extraDetails.epg.current) {
          const curr = extraDetails.epg.current;
          const next = extraDetails.epg.next;
          let timeSpan = curr.timeWindow ? `<span class="preview-epg-time">${escapeHtml(curr.timeWindow)}</span>` : '';
          let nextHtml = '';
          if (next && next.title) {
            let nextTime = next.time ? ` (${escapeHtml(next.time)})` : '';
            nextHtml = `
              <div class="preview-epg-next">
                <div class="preview-epg-next-label">البرنامج التالي${nextTime}:</div>
                <div class="preview-epg-next-title">${escapeHtml(next.title)}</div>
              </div>`;
          }

          epgContentHtml = `
            <div class="preview-epg-box">
              <div class="preview-epg-current">
                <div class="preview-epg-label-row">
                  <div class="preview-epg-tag-group">
                    <span class="preview-live-dot"></span>
                    <span class="preview-epg-label">البرنامج الحالي:</span>
                  </div>
                  ${timeSpan}
                </div>
                <div class="preview-epg-title">${escapeHtml(curr.title || 'بث مباشر')}</div>
              </div>
              ${nextHtml}
            </div>`;
        } else {
          epgContentHtml = `
            <div class="preview-epg-box">
              <div class="preview-epg-live-note">
                <span class="preview-live-dot"></span>
                <span>بث مباشر للقناة</span>
              </div>
            </div>`;
        }

        container.innerHTML = `
          <div class="preview-channel-card">
            <div class="preview-channel-header">
              ${logoHtml}
              <div class="preview-channel-meta">
                <h3 class="preview-channel-name">${escapeHtml(name)}</h3>
                <div class="preview-badges-row">${badgesHtml}</div>
              </div>
            </div>
            ${epgContentHtml}
          </div>`;
      }
    }

    function handleItemFocusForPreview(element) {
      if (!element) return;
      const row = element.closest('.channel-row');
      if (!row) return;
      const itemId = row.dataset.itemId;
      if (!itemId) return;

      const items = getActiveItemList();
      const item = items.find(it => String(it.id) === String(itemId));
      if (!item) return;

      const mode = currentAppMode;
      const cacheKey = `${mode}:${itemId}`;
      const token = ++previewCurrentToken;

      const cached = previewDetailsCache.get(cacheKey);
      renderItemPreview(item, mode, cached || null);
      previewLastRenderedKey = cacheKey;

      if (cached && (cached.fullyLoaded || cached.epg)) return;

      if (previewDebounceTimer) {
        clearTimeout(previewDebounceTimer);
        previewDebounceTimer = null;
      }

      previewDebounceTimer = setTimeout(() => {
        if (token !== previewCurrentToken) return;

        if (!window.XtreamBridge || typeof window.XtreamBridge.requestDetailsAsync !== 'function') return;

        if (previewActiveRequestId) {
          try { window.XtreamBridge.cancelDetailsLoad(previewActiveRequestId); } catch (_) {}
          previewActiveRequestId = null;
        }

        const requestId = `prev-${mode}-${itemId}-${Date.now()}`;
        previewActiveRequestId = requestId;

        if (mode === 'movies') {
          window.XtreamBridge.requestDetailsAsync('get_vod_info', 'vod_id', itemId, requestId);
        } else if (mode === 'series') {
          window.XtreamBridge.requestDetailsAsync('get_series_info', 'series_id', itemId, requestId);
        } else if (mode === 'live') {
          window.XtreamBridge.requestDetailsAsync('get_short_epg', 'stream_id', itemId, requestId);
        }
      }, 250);
    }

    window.onXtreamDetailsLoaded = function(requestId, rawResult) {
      let response;
      try {
        response = typeof rawResult === 'string' ? JSON.parse(rawResult) : rawResult;
      } catch (_) {
        return;
      }

      if (!response || !response.success || !response.data) return;

      const action = response.action;
      const targetId = String(response.id || '');
      let mode = 'live';
      if (action === 'get_vod_info') mode = 'movies';
      else if (action === 'get_series_info') mode = 'series';

      const cacheKey = `${mode}:${targetId}`;
      let detailObj = {};

      if (mode === 'movies' || mode === 'series') {
        const info = response.data.info || {};
        const year = info.year || (info.release_date ? String(info.release_date).substring(0, 4) : '') ||
                     (info.releasedate ? String(info.releasedate).substring(0, 4) : '') ||
                     (info.releaseDate ? String(info.releaseDate).substring(0, 4) : '') || '';
        const genre = info.genre || '';
        const rating = info.rating || info.rating_5based || '';
        const plot = info.plot || info.description || '';
        const poster = info.movie_image || info.cover || '';
        detailObj = { year, genre, rating, plot, poster, fullyLoaded: true };
      } else if (mode === 'live') {
        const listings = response.data.epg_listings || [];
        const epgData = calculateCurrentAndNextEpg(listings);
        detailObj = { epg: epgData, fullyLoaded: true };
      }

      previewDetailsCache.set(cacheKey, detailObj);

      if (previewLastRenderedKey === cacheKey) {
        const items = getActiveItemList();
        const activeItem = items.find(it => String(it.id) === targetId);
        if (activeItem) {
          renderItemPreview(activeItem, mode, detailObj);
        }
      }
    };

    /**
     * =========================================================================
     * SECTION 8: D-PAD & TV REMOTE SPATIAL NAVIGATION SYSTEM
     * =========================================================================
     */
    class RemoteControlNavigator {
      constructor() {
        this.currentFocusedElement = null;
        window.remoteNavigator = this; // Expose on window for Android key forwarding
        this.bindKeyEvents();
      }

      bindKeyEvents() {
        window.addEventListener('keydown', (e) => {
          if (window.isSplashActive && window.isSplashActive()) {
            if (e.key === 'Enter' || e.key === ' ' || e.key === 'Escape' || e.key === 'Backspace' || e.key.startsWith('Arrow')) {
              window.dismissSplashScreen();
            }
            e.preventDefault();
            return;
          }
          this.handleKeyEvent(e);
        });

        // Direct fast invocation from Android MainActivity via window.handleRemoteKey
        window.handleRemoteKey = (keyName) => {
          if (window.isSplashActive && window.isSplashActive()) {
            if (keyName === 'Enter' || keyName === 'Back' || keyName === 'Escape' || keyName === 'MediaPlayPause' ||
                keyName.startsWith('Arrow') || keyName === 'Down' || keyName === 'Up') {
              window.dismissSplashScreen();
            }
            return;
          }
          this.onRemoteKey(keyName);
        };
      }

      moveSelectSelection(select, direction) {
        if (!select || !select.options || select.options.length === 0) return;
        const delta = direction === 'down' ? 1 : -1;
        const nextIndex = Math.max(0, Math.min(select.options.length - 1, select.selectedIndex + delta));
        if (nextIndex === select.selectedIndex) return;
        select.selectedIndex = nextIndex;
        select.dispatchEvent(new Event('change', { bubbles: true }));
      }

      onRemoteKey(keyName) {
        const editingElement = document.activeElement;
        if (editingElement && editingElement.tagName === 'SELECT' &&
            (editingElement.id === 'login-server' || editingElement.id === 'iptv-server-url')) {
          if (keyName === 'ArrowDown' || keyName === 'Down') {
            this.navigateSpatial('down');
            return;
          }
          if (keyName === 'ArrowUp' || keyName === 'Up') {
            this.navigateSpatial('up');
            return;
          }
          if (keyName === 'ArrowRight' || keyName === 'Right') {
            this.moveSelectSelection(editingElement, 'down');
            return;
          }
          if (keyName === 'ArrowLeft' || keyName === 'Left') {
            this.moveSelectSelection(editingElement, 'up');
            return;
          }
          if (keyName === 'Back' || keyName === 'Escape') {
            editingElement.blur();
            return;
          }
          if (keyName === 'Enter' && editingElement.id === 'login-server') {
            const submitBtn = document.getElementById('login-submit');
            if (submitBtn) submitBtn.click();
            return;
          }
          return;
        }
        const isEditingText = editingElement &&
          (editingElement.matches('input, textarea') || editingElement.isContentEditable);

        if (isEditingText) {
          if (keyName === 'ArrowDown') {
            this.navigateSpatial('down');
            return;
          }
          if (keyName === 'ArrowUp') {
            this.navigateSpatial('up');
            return;
          }
          if (keyName === 'Back' || keyName === 'Escape') {
            editingElement.blur();
            return;
          }
          if (keyName === 'Enter') {
            if (editingElement.id === 'login-password' || editingElement.id === 'login-username' || editingElement.id === 'login-server') {
              const submitBtn = document.getElementById('login-submit');
              if (submitBtn) submitBtn.click();
              return;
            }
          }
          return;
        }

        switch (keyName) {
          case 'ArrowUp':
          case 'Up':
            this.handleDirectionAction('up');
            break;
          case 'ArrowDown':
          case 'Down':
            this.handleDirectionAction('down');
            break;
          case 'ArrowLeft':
          case 'Left':
            this.handleDirectionAction('left');
            break;
          case 'ArrowRight':
          case 'Right':
            this.handleDirectionAction('right');
            break;
          case 'Enter':
            this.handleEnterAction();
            break;
          case 'Back':
          case 'Escape':
            this.handleBackAction();
            break;
          case 'Menu':
            this.handleMenuAction();
            break;
          case 'MediaPlayPause':
            if (videoPlaybackController) videoPlaybackController.togglePlayback();
            break;
          case 'MediaPlay':
            if (videoPlaybackController) videoPlaybackController.resume();
            break;
          case 'MediaPause':
            if (videoPlaybackController) videoPlaybackController.pause();
            break;
          case 'MediaFastForward':
            if (videoPlaybackController) videoPlaybackController.seekBy(10000);
            break;
          case 'MediaRewind':
            if (videoPlaybackController) videoPlaybackController.seekBy(-10000);
            break;
        }
      }

      handleKeyEvent(e) {
        const editingElement = document.activeElement;
        if (editingElement && editingElement.tagName === 'SELECT' &&
            (editingElement.id === 'login-server' || editingElement.id === 'iptv-server-url')) {
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            this.navigateSpatial('down');
            return;
          }
          if (e.key === 'ArrowUp') {
            e.preventDefault();
            this.navigateSpatial('up');
            return;
          }
          if (e.key === 'ArrowRight') {
            e.preventDefault();
            this.moveSelectSelection(editingElement, 'down');
            return;
          }
          if (e.key === 'ArrowLeft') {
            e.preventDefault();
            this.moveSelectSelection(editingElement, 'up');
            return;
          }
          if (e.key === 'Escape' || e.key === 'Back') {
            e.preventDefault();
            editingElement.blur();
            return;
          }
          if (e.key === 'Enter' && editingElement.id === 'login-server') {
            e.preventDefault();
            const submitBtn = document.getElementById('login-submit');
            if (submitBtn) submitBtn.click();
            return;
          }
          return;
        }
        const isEditingText = editingElement &&
          (editingElement.matches('input, textarea') || editingElement.isContentEditable);
        if (isEditingText) {
          if (e.key === 'Escape' || e.key === 'Back') {
            e.preventDefault();
            editingElement.blur();
            return;
          }
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            this.navigateSpatial('down');
            return;
          }
          if (e.key === 'ArrowUp') {
            e.preventDefault();
            this.navigateSpatial('up');
            return;
          }
          if (e.key === 'Enter') {
            if (editingElement.id === 'login-password' || editingElement.id === 'login-username' || editingElement.id === 'login-server') {
              e.preventDefault();
              const submitBtn = document.getElementById('login-submit');
              if (submitBtn) submitBtn.click();
              return;
            }
          }
          return;
        }

        const activeModal = document.querySelector('.modal-backdrop.active');
        if (activeModal) {
          this.handleModalKey(e, activeModal);
          return;
        }

        switch (e.key) {
          case 'ArrowUp':
          case 'Up':
            e.preventDefault();
            this.handleDirectionAction('up');
            break;
          case 'ArrowDown':
          case 'Down':
            e.preventDefault();
            this.handleDirectionAction('down');
            break;
          case 'ArrowLeft':
          case 'Left':
            e.preventDefault();
            this.handleDirectionAction('left');
            break;
          case 'ArrowRight':
          case 'Right':
            e.preventDefault();
            this.handleDirectionAction('right');
            break;
          case 'Enter':
            e.preventDefault();
            this.handleEnterAction();
            break;
          case 'Escape':
          case 'Back':
          case 'Backspace':
            e.preventDefault();
            this.handleBackAction();
            break;
        }
      }

      handleDirectionAction(direction) {
        if (videoPlaybackController && videoPlaybackController.currentItem && videoPlaybackController.isFullscreen) {
          const controls = document.getElementById('player-playback-controls');
          if (controls && !controls.classList.contains('visible')) {
            videoPlaybackController.showPlaybackControls();
            const seekBar = document.getElementById('player-seek-bar');
            const firstControl = seekBar && !seekBar.disabled &&
              videoPlaybackController.viewport.classList.contains('has-vod-timeline')
              ? seekBar
              : controls.querySelector('.playback-control-btn:not([disabled])');
            if (firstControl) this.focusElement(firstControl);
            return;
          }
          if (controls && controls.classList.contains('visible')) {
            videoPlaybackController.showPlaybackControls();
          }
        }
        this.navigateSpatial(direction);
      }

      handleEnterAction() {
        if (this.currentFocusedElement &&
            this.currentFocusedElement.matches('.channel-row.selected') &&
            videoPlaybackController && videoPlaybackController.currentItem &&
            !videoPlaybackController.isFullscreen &&
            window.NativePlayback && window.NativePlayback.isActive()) {
          videoPlaybackController.toggleFullscreen();
        } else if (this.currentFocusedElement) {
          this.currentFocusedElement.click();
        }
      }

      handleMenuAction() {
        if (videoPlaybackController && videoPlaybackController.isFullscreen) {
          videoPlaybackController.showPlaybackControls();
          return;
        }
        if (this.currentFocusedElement && this.currentFocusedElement.classList.contains('channel-row')) {
          const chId = this.currentFocusedElement.dataset.id;
          if (chId) toggleFavorite(chId);
          return;
        }
        openModal('modal-settings');
      }

      handleSeriesModalNavigation(e, modal) {
        const current=document.activeElement;
        const tab=current.closest && current.closest('.series-season-tab');
        const episode=current.closest && current.closest('.episode-card');
        if(!tab && !episode) return false;

        const directionMap={
          ArrowLeft:'left',ArrowRight:'right',ArrowUp:'up',ArrowDown:'down',
          Left:'left',Right:'right',Up:'up',Down:'down'
        };
        const direction=directionMap[e.key];
        if(!direction) return false;
        e.preventDefault();

        const moveGeometrically=(items,dir)=>{
          const rect=current.getBoundingClientRect();
          const cx=rect.left+rect.width/2, cy=rect.top+rect.height/2;
          return items.filter(target=>target!==current && target.getClientRects().length>0)
            .map(target=>{
              const r=target.getBoundingClientRect(), x=r.left+r.width/2, y=r.top+r.height/2;
              const primary=dir==='left'?cx-x:dir==='right'?x-cx:dir==='up'?cy-y:y-cy;
              if(primary<=0) return null;
              const cross=(dir==='left'||dir==='right')?Math.abs(y-cy):Math.abs(x-cx);
              return {target,score:primary+cross*1.65};
            }).filter(Boolean).sort((a,b)=>a.score-b.score)[0]?.target || null;
        };

        if(tab) {
          if(direction==='left'||direction==='right') {
            const target=moveGeometrically(Array.from(modal.querySelectorAll('.series-season-tab')),direction);
            if(target) {
              this.focusElement(target);
              target.scrollIntoView({block:'nearest',inline:'nearest',behavior:'smooth'});
            }
          } else if(direction==='down') {
            const first=modal.querySelector('.episode-card.focusable');
            if(first) {
              this.focusElement(first);
              first.scrollIntoView({block:'nearest',inline:'nearest',behavior:'smooth'});
            }
          } else if(direction==='up') {
            const close=modal.querySelector('.modal-close-btn.focusable');
            if(close) this.focusElement(close);
          }
          return true;
        }

        const episodes=Array.from(modal.querySelectorAll('.episode-card.focusable'));
        const target=moveGeometrically(episodes,direction);
        if(target) {
          this.focusElement(target);
          target.scrollIntoView({block:'nearest',inline:'nearest',behavior:'smooth'});
        } else if(direction==='up') {
          const selected=modal.querySelector('.series-season-tab[aria-selected="true"]');
          if(selected) {
            this.focusElement(selected);
            selected.scrollIntoView({block:'nearest',inline:'nearest',behavior:'smooth'});
          }
        }
        return true;
      }

      handleModalKey(e, modal) {
        if (e.key === 'Escape' || e.key === 'Backspace' || e.key === 'Back') {
          e.preventDefault();
          closeAllModals();
          return;
        }

        if (modal.id === 'series-episodes-modal' && this.handleSeriesModalNavigation(e, modal)) return;

        const focusables = Array.from(modal.querySelectorAll('.focusable:not([disabled])'));
        if (!focusables.length) return;

        let currentIndex = focusables.indexOf(document.activeElement);
        if (currentIndex === -1) currentIndex = 0;

        if (e.key === 'ArrowDown' || e.key === 'ArrowLeft') {
          e.preventDefault();
          const nextIndex = (currentIndex + 1) % focusables.length;
          this.focusElement(focusables[nextIndex]);
        } else if (e.key === 'ArrowUp' || e.key === 'ArrowRight') {
          e.preventDefault();
          const prevIndex = (currentIndex - 1 + focusables.length) % focusables.length;
          this.focusElement(focusables[prevIndex]);
        }
      }

      handleBackAction() {
        if (window.isSplashActive && window.isSplashActive()) {
          window.dismissSplashScreen();
          return;
        }
        if (closeRemoteMessage()) return;
        if (cancelActiveCatalogSync()) return;
        // The login screen has no previous in-app page; Back follows Android's
        // normal behavior and closes the app instead of silently doing nothing.
        const loginScreen = document.getElementById('sonix-login-screen');
        if (loginScreen && !loginScreen.classList.contains('hidden')) {
          if (window.AndroidBridge && typeof window.AndroidBridge.closeApp === 'function') {
            window.AndroidBridge.closeApp();
          }
          return;
        }

        const editingElement = document.activeElement;
        if (editingElement &&
            (editingElement.matches('input, textarea, select') || editingElement.isContentEditable)) {
          editingElement.blur();
          return;
        }

        // A dialog opened over fullscreen video owns Back until it is dismissed.
        // Exiting fullscreen first would strand the audio dialog in the hidden app tree.
        const activeModal = document.querySelector('.modal-backdrop.active');
        if (activeModal) {
          closeAllModals();
          return;
        }

        // Only exit fullscreen when no modal is currently covering the video.
        if (videoPlaybackController && videoPlaybackController.isFullscreen) {
          videoPlaybackController.exitFullscreen();
          return;
        }

        // If in Live/Movies/Series content browser, return to Home screen!
        const currentActiveView = document.querySelector('.view-screen.active');
        if (currentActiveView && currentActiveView.id !== 'view-home') {
          switchView('view-home');
        } else {
          openModal('modal-power');
        }
      }

      navigateSpatial(direction) {
        if (!this.currentFocusedElement || !this.currentFocusedElement.isConnected ||
            this.currentFocusedElement.getClientRects().length === 0) {
          const visibleFocusables = this.getVisibleFocusableElements();
          if (visibleFocusables.length) {
            this.focusElement(visibleFocusables[0]);
          }
          return;
        }

        const current = this.currentFocusedElement;
        const inSidebar = current.closest('.live-sidebar');
        const inCategories = current.closest('.categories-list');
        const inChannels = current.closest('.channels-list');
        const inPlayerCol = current.closest('.live-player-col');

        // Movie/series cards form a three-column grid. Resolve horizontal DPAD
        // movement inside that grid before considering transitions to other
        // application columns. Use rendered geometry so RTL and screen sizing
        // are handled by the actual visual positions rather than DOM assumptions.
        const mediaGrid = current.closest('.channels-list.media-grid');
        if (mediaGrid && (direction === 'left' || direction === 'right')) {
          const currentRect = current.getBoundingClientRect();
          const currentCenterX = currentRect.left + currentRect.width / 2;
          const currentCenterY = currentRect.top + currentRect.height / 2;
          const candidates = Array.from(mediaGrid.querySelectorAll('.channel-row.focusable:not([disabled])'))
            .filter(target => target !== current && target.getClientRects().length > 0)
            .map(target => {
              const rect = target.getBoundingClientRect();
              const centerX = rect.left + rect.width / 2;
              const centerY = rect.top + rect.height / 2;
              const verticalOverlap = Math.max(0,
                Math.min(currentRect.bottom, rect.bottom) - Math.max(currentRect.top, rect.top));
              const isInRequestedDirection = direction === 'left'
                ? centerX < currentCenterX
                : centerX > currentCenterX;
              return {
                target,
                horizontalGap: Math.abs(centerX - currentCenterX),
                verticalDistance: Math.abs(centerY - currentCenterY),
                verticalOverlap,
                isInRequestedDirection
              };
            })
            .filter(candidate => candidate.isInRequestedDirection && candidate.verticalOverlap > 0)
            .sort((a, b) =>
              (a.horizontalGap + a.verticalDistance * 2) -
              (b.horizontalGap + b.verticalDistance * 2));

          if (candidates.length) {
            this.focusElement(candidates[0].target);
            return;
          }
        }

        // 1. Column-to-column transition in Unified Content Browser (RTL-aware):
        if (direction === 'left') {
          // In RTL layout: Left moves from Sidebar -> Categories -> Channels -> Player
          if (inSidebar) {
            const activeCategory = document.querySelector('.categories-list .category-item.active') ||
              document.querySelector('.categories-list .focusable:not([disabled])');
            if (activeCategory) { this.focusElement(activeCategory); return; }
          } else if (inCategories) {
            const activeChannel = document.querySelector('.channels-list .channel-row.selected') ||
              document.querySelector('.channels-list .focusable:not([disabled])');
            if (activeChannel) { this.focusElement(activeChannel); return; }
          } else if (inChannels) {
            const playerBtn = document.querySelector('#btn-toggle-fullscreen') ||
              document.querySelector('#btn-toggle-play') ||
              document.querySelector('.live-player-col .focusable:not([disabled])');
            if (playerBtn) { this.focusElement(playerBtn); return; }
          }
        } else if (direction === 'right') {
          // In RTL layout: Right moves from Player -> Channels -> Categories -> Sidebar
          if (inPlayerCol) {
            const activeChannel = document.querySelector('.channels-list .channel-row.selected') ||
              document.querySelector('.channels-list .focusable:not([disabled])');
            if (activeChannel) { this.focusElement(activeChannel); return; }
          } else if (inChannels) {
            const activeCategory = document.querySelector('.categories-list .category-item.active') ||
              document.querySelector('.categories-list .focusable:not([disabled])');
            if (activeCategory) { this.focusElement(activeCategory); return; }
          } else if (inCategories) {
            const activeSidebar = document.querySelector('.live-sidebar .sidebar-nav-item.active') ||
              document.querySelector('.live-sidebar .focusable:not([disabled])');
            if (activeSidebar) { this.focusElement(activeSidebar); return; }
          }
        }

        // 2. Sequential vertical navigation inside lists
        const currentListContainer = current.closest('.categories-list, .channels-list, .audio-channel-picker, #global-search-results, .tv-quick-bar');
        if (currentListContainer && (direction === 'up' || direction === 'down')) {
          const listItems = Array.from(currentListContainer.querySelectorAll('.focusable:not([disabled])'));
          const idx = listItems.indexOf(current);
          if (idx !== -1 && currentListContainer.classList.contains('media-grid')) {
            const rowStep = 3;
            if (direction === 'up' && idx >= rowStep) {
              this.focusElement(listItems[idx - rowStep]);
              return;
            }
            if (direction === 'down') {
              if (listItems[idx + rowStep]) {
                this.focusElement(listItems[idx + rowStep]);
                return;
              }
              if (loadMoreChannelRows()) {
                const expanded = Array.from(currentListContainer.querySelectorAll('.focusable:not([disabled])'));
                if (expanded[idx + rowStep]) {
                  this.focusElement(expanded[idx + rowStep]);
                  return;
                }
              }
            }
          } else if (idx !== -1) {
            if (direction === 'down' && idx < listItems.length - 1) {
              this.focusElement(listItems[idx + 1]);
              return;
            } else if (direction === 'up' && idx > 0) {
              this.focusElement(listItems[idx - 1]);
              return;
            } else if (direction === 'down' && currentListContainer.classList.contains('channels-list')) {
              if (loadMoreChannelRows()) {
                const expanded = Array.from(currentListContainer.querySelectorAll('.focusable:not([disabled])'));
                if (expanded[idx + 1]) this.focusElement(expanded[idx + 1]);
                return;
              }
            }
          }
        }

        // 3. Spatial geometric fallback across different screen regions
        const visibleFocusables = this.getVisibleFocusableElements();
        if (!visibleFocusables.length) return;

        const currentRect = current.getBoundingClientRect();
        let bestTarget = null;
        let minDistance = Infinity;

        visibleFocusables.forEach(target => {
          if (target === current) return;
          const rect = target.getBoundingClientRect();

          let isCandidate = false;
          let primaryDist = 0;
          let secondaryDist = 0;

          if (direction === 'up' && rect.bottom <= currentRect.top + 8) {
            isCandidate = true;
            primaryDist = currentRect.top - rect.bottom;
            secondaryDist = Math.abs((rect.left + rect.width / 2) - (currentRect.left + currentRect.width / 2));
          } else if (direction === 'down' && rect.top >= currentRect.bottom - 8) {
            isCandidate = true;
            primaryDist = rect.top - currentRect.bottom;
            secondaryDist = Math.abs((rect.left + rect.width / 2) - (currentRect.left + currentRect.width / 2));
          } else if (direction === 'left' && rect.right <= currentRect.left + 8) {
            isCandidate = true;
            primaryDist = currentRect.left - rect.right;
            secondaryDist = Math.abs((rect.top + rect.height / 2) - (currentRect.top + currentRect.height / 2));
          } else if (direction === 'right' && rect.left >= currentRect.right - 8) {
            isCandidate = true;
            primaryDist = rect.left - currentRect.right;
            secondaryDist = Math.abs((rect.top + rect.height / 2) - (currentRect.top + currentRect.height / 2));
          }

          if (isCandidate) {
            const distance = primaryDist * 1.0 + secondaryDist * 1.5;
            if (distance < minDistance) {
              minDistance = distance;
              bestTarget = target;
            }
          }
        });

        if (bestTarget) {
          this.focusElement(bestTarget);
        }
      }

      getVisibleFocusableElements() {
        if (window.isSplashActive && window.isSplashActive()) {
          return [];
        }
        const loginScreen = document.getElementById('sonix-login-screen');
        if (loginScreen && !loginScreen.classList.contains('hidden')) {
          return Array.from(loginScreen.querySelectorAll('.focusable:not([disabled])'))
            .filter(element => element.getClientRects().length > 0);
        }
        const activeModal = document.querySelector('.modal-backdrop.active');
        if (activeModal) {
          const selector = '.focusable:not([disabled])';
          return Array.from(activeModal.querySelectorAll(selector)).filter(element => element.getClientRects().length > 0);
        }
        const fullscreenViewport = videoPlaybackController && videoPlaybackController.isFullscreen
          ? videoPlaybackController.viewport
          : null;
        const selector = '.focusable:not([disabled])';
        const candidates = fullscreenViewport
          ? Array.from(fullscreenViewport.querySelectorAll(selector))
          : Array.from(document.querySelectorAll('.view-screen.active ' + selector));
        return candidates.filter(element => element.getClientRects().length > 0);
      }

      focusElement(element) {
        if (!element) return;
        if (this.currentFocusedElement) {
          this.currentFocusedElement.classList.remove('keyboard-focused');
        }
        this.currentFocusedElement = element;
        element.classList.add('keyboard-focused');
        try {
          element.focus({ preventScroll: true });
          if (typeof element.scrollIntoView === 'function') {
            element.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
          }
        } catch (err) {
          console.warn('focusElement non-critical error', err);
        }
        if (typeof handleItemFocusForPreview === 'function') {
          handleItemFocusForPreview(element);
        }
      }
    }

    // Expose global back handler for Android MainActivity
    window.handleAndroidBack = function() {
      if (window.remoteNavigator) {
        window.remoteNavigator.handleBackAction();
      } else {
        switchView('view-home');
      }
    };

    // Global reset to home screen whenever app is resumed
    window.ensureHomeScreen = function() {
      closeAllModals();
      if (videoPlaybackController && videoPlaybackController.isFullscreen) {
        videoPlaybackController.exitFullscreen();
      }
      switchView('view-home');
    };

    /**
     * =========================================================================
     * SECTION 1: ARABIC-INDIC CLOCK FORMATTER
     * =========================================================================
     */
    function toArabicIndicDigits(str) {
      const arabicDigits = ['٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩'];
      return String(str).replace(/[0-9]/g, w => arabicDigits[+w]);
    }

    function updateLiveClock() {
      const now = new Date();
      let hours = now.getHours();
      const minutes = now.getMinutes();
      const isPm = hours >= 12;
      hours = hours % 12;
      hours = hours ? hours : 12;

      const formattedHours = hours < 10 ? '0' + hours : hours;
      const formattedMins = minutes < 10 ? '0' + minutes : minutes;
      const ampm = isPm ? 'م' : 'ص';

      const timeText = `${toArabicIndicDigits(formattedHours)}:${toArabicIndicDigits(formattedMins)} ${ampm}`;
      const clockDigitsEl = document.getElementById('home-clock-digits');
      if (clockDigitsEl) clockDigitsEl.textContent = timeText;

      const days = ['الأحد', 'الاثنين', 'الثلاثاء', 'الأربعاء', 'الخميس', 'الجمعة', 'السبت'];
      const months = ['يناير', 'فبراير', 'مارس', 'أبريل', 'مايو', 'يونيو', 'يوليو', 'أغسطس', 'سبتمبر', 'أكتوبر', 'نوفمبر', 'ديسمبر'];
      const dayName = days[now.getDay()];
      const monthName = months[now.getMonth()];
      const dayNum = toArabicIndicDigits(now.getDate());

      const clockDateEl = document.getElementById('home-clock-date');
      if (clockDateEl) clockDateEl.textContent = `${dayName}، ${dayNum} ${monthName}`;
    }

    /**
     * =========================================================================
     * SECTION 3 & 5: VIEW SWITCHING & APP MODES (LIVE, MOVIES, SERIES)
     * =========================================================================
     */
    function switchView(viewId) {
      if (viewId === 'view-home') {
        if (videoPlaybackController) {
          if (videoPlaybackController.isFullscreen) videoPlaybackController.exitFullscreen();
          videoPlaybackController.stopAndRelease();
        } else if (window.NativePlayback) {
          window.NativePlayback.stop();
        }
        if (dualAudioEngine) dualAudioEngine.stopExternalAudio(false);
      }

      document.querySelectorAll('.view-screen').forEach(el => el.classList.remove('active'));
      const target = document.getElementById(viewId);
      if (target) {
        target.classList.add('active');
        let elementToFocus = null;
        if (viewId === 'view-home') {
          elementToFocus = document.getElementById('card-channels');
        } else if (viewId === 'view-live') {
          elementToFocus = target.querySelector('.category-item.selected') || document.getElementById('live-btn-back');
        }
        if (!elementToFocus) elementToFocus = target.querySelector('.focusable');
        if (elementToFocus && remoteNavigator) remoteNavigator.focusElement(elementToFocus);
      }
    }

    function openModal(modalId) {
      closeAllModals();
      if (modalId === 'modal-settings') syncSonixSettingsControls();
      const modal = document.getElementById(modalId);
      if (!modal) return;

      const isFullscreenAudioDialog = modalId === 'modal-ext-audio' &&
        !!(videoPlaybackController && videoPlaybackController.isFullscreen);
      const isFullscreenSubtitleDialog = modalId === 'modal-subtitles' &&
        !!(videoPlaybackController && videoPlaybackController.isFullscreen);

      // Both playback dialogs must escape the hidden app subtree after the
      // video viewport is portaled to <body> in fullscreen.
      if ((isFullscreenAudioDialog || isFullscreenSubtitleDialog) && modal.parentElement !== document.body) {
        modal._sonixOriginalParent = modal.parentElement;
        modal._sonixOriginalNextSibling = modal.nextSibling;
        document.body.appendChild(modal);
        modal.dataset.sonixFullscreenPortal = 'true';
      }

      modal.setAttribute('aria-hidden', 'false');
      modal.classList.add('active');
      if ((isFullscreenAudioDialog || isFullscreenSubtitleDialog) && videoPlaybackController) {
        videoPlaybackController.showPlaybackControls();
      }

      const focusDialog = () => {
        if (!modal.classList.contains('active')) return;
        if (modalId === 'modal-subtitles') {
          const powerBtn = document.getElementById('subtitle-toggle-power');
          const searchBtn = document.getElementById('subtitle-search-button');
          const target = powerBtn || searchBtn;
          if (target && remoteNavigator) {
            remoteNavigator.focusElement(target);
            return;
          }
        }
        if (modalId === 'modal-power') {
          const cancelBtn = document.getElementById('btn-cancel-exit');
          if (cancelBtn && remoteNavigator) { remoteNavigator.focusElement(cancelBtn); return; }
        }
        if (modalId === 'modal-search') {
          const searchInput = document.getElementById('global-search-input');
          if (searchInput && remoteNavigator) { remoteNavigator.focusElement(searchInput); return; }
        }
        const bodyFocusable = modal.querySelector('.modal-body .focusable:not([disabled]), .modal-footer .focusable:not([disabled])');
        const firstFocusable = bodyFocusable || modal.querySelector('.focusable:not([disabled])');
        if (firstFocusable && remoteNavigator) remoteNavigator.focusElement(firstFocusable);
      };
      if (isFullscreenAudioDialog || isFullscreenSubtitleDialog) {
        requestAnimationFrame(() => {
          modal.getBoundingClientRect();
          focusDialog();
        });
      } else {
        focusDialog();
      }
    }

    function closeAllModals() {
      let closedFullscreenAudio = false;
      let closedFullscreenSubtitles = false;

      document.querySelectorAll('.modal-backdrop').forEach(el => {
        if (el.dataset.sonixFullscreenPortal === 'true') {
          if (el.id === 'modal-ext-audio') closedFullscreenAudio = true;
          if (el.id === 'modal-subtitles') closedFullscreenSubtitles = true;
        }

        el.classList.remove('active');
        el.setAttribute('aria-hidden', 'true');

        // Restore fullscreen dialogs to their original modal layer before the
        // player or another dialog takes focus. This keeps touch and D-pad
        // navigation on the same visible UI tree on phones and Android TV.
        if (el.dataset.sonixFullscreenPortal === 'true' && el._sonixOriginalParent) {
          const parent = el._sonixOriginalParent;
          const next = el._sonixOriginalNextSibling;
          if (next && next.parentNode === parent) parent.insertBefore(el, next);
          else parent.appendChild(el);
          delete el._sonixOriginalParent;
          delete el._sonixOriginalNextSibling;
          delete el.dataset.sonixFullscreenPortal;
        }
      });

      if ((closedFullscreenAudio || closedFullscreenSubtitles) &&
          videoPlaybackController && videoPlaybackController.isFullscreen) {
        videoPlaybackController.showPlaybackControls();

        if (remoteNavigator) {
          const returnButtonId = closedFullscreenSubtitles
            ? 'btn-toggle-subtitles'
            : 'btn-open-ext-audio';
          const returnButton = document.getElementById(returnButtonId);
          if (returnButton) remoteNavigator.focusElement(returnButton);
        }
      }
    }

    let toastTimer = null;
    function showToast(message) {
      const toast = document.getElementById('toast-msg');
      const text = document.getElementById('toast-text');
      if (!toast || !text) return;

      text.textContent = message;
      toast.classList.add('show');
      if (toastTimer) clearTimeout(toastTimer);
      toastTimer = setTimeout(() => {
        toast.classList.remove('show');
      }, 2500);
    }

    // Remote messages use a centered glass dialog instead of the native browser alert.
    const remoteMessageQueue = [];
    let remoteMessageVisible = false;
    function enqueueRemoteMessage(title, body) {
      remoteMessageQueue.push({ title, body });
      if (!remoteMessageVisible) showNextRemoteMessage();
    }
    function showNextRemoteMessage() {
      const overlay = document.getElementById('sonix-remote-message');
      const titleElement = document.getElementById('sonix-remote-message-title');
      const bodyElement = document.getElementById('sonix-remote-message-body');
      if (!overlay || !titleElement || !bodyElement || !remoteMessageQueue.length) {
        remoteMessageVisible = false;
        return;
      }
      const message = remoteMessageQueue.shift();
      titleElement.textContent = message.title || 'رسالة من الإدارة';
      titleElement.hidden = !message.title;
      bodyElement.textContent = message.body || '';
      bodyElement.hidden = !message.body;
      overlay.classList.add('visible');
      overlay.setAttribute('aria-hidden', 'false');
      remoteMessageVisible = true;
      const closeButton = document.getElementById('sonix-remote-message-close');
      if (closeButton) {
        closeButton.focus();
        if (window.remoteNavigator) window.remoteNavigator.focusElement(closeButton);
      }
    }
    function closeRemoteMessage() {
      const overlay = document.getElementById('sonix-remote-message');
      if (!overlay || !remoteMessageVisible) return false;
      overlay.classList.remove('visible');
      overlay.setAttribute('aria-hidden', 'true');
      remoteMessageVisible = false;
      if (remoteMessageQueue.length) {
        window.setTimeout(showNextRemoteMessage, 180);
      }
      return true;
    }
    document.addEventListener('DOMContentLoaded', () => {
      const closeButton = document.getElementById('sonix-remote-message-close');
      if (closeButton) closeButton.addEventListener('click', closeRemoteMessage);
    });

    function applySonixRemoteTheme(theme) {
      if (!theme || !theme.palette) return;
      const palette = theme.palette;
      const root = document.documentElement;
      const validColor = value => typeof value === 'string' && /^#[0-9a-fA-F]{6}$/.test(value);
      const rgba = (hex, alpha) => {
        const value = hex.slice(1);
        return 'rgba(' + parseInt(value.slice(0, 2), 16) + ', ' +
          parseInt(value.slice(2, 4), 16) + ', ' + parseInt(value.slice(4, 6), 16) + ', ' + alpha + ')';
      };
      const cssMap = {
        accent: ['--accent-gold'],
        background: ['--bg-screen'],
        backgroundTop: ['--bg-main-start'],
        backgroundBottom: ['--bg-main-end'],
        panel: ['--bg-panel'],
        surface: ['--surface-panel'],
        selected: ['--selected-item-bg'],
        border: ['--border-panel'],
        focus: ['--focus-blue'],
        danger: ['--danger-red'],
        success: ['--success-green'],
        text: ['--text-white', '--text-primary'],
        textSecondary: ['--text-silver', '--text-secondary'],
        muted: ['--text-muted']
      };
      Object.entries(cssMap).forEach(([key, variables]) => {
        const value = palette[key];
        if (validColor(value)) variables.forEach(variable => root.style.setProperty(variable, value));
      });
      if (validColor(palette.card)) {
        root.style.setProperty('--card-glass', rgba(palette.card, 0.82));
        root.style.setProperty('--card-glass-hover', rgba(palette.card, 0.94));
      }
      if (validColor(palette.border)) root.style.setProperty('--card-border', rgba(palette.border, 0.30));
      if (validColor(palette.waveOne)) root.style.setProperty('--teal-wave', rgba(palette.waveOne, 0.16));
      if (validColor(palette.waveTwo)) root.style.setProperty('--copper-wave', rgba(palette.waveTwo, 0.13));
      if (validColor(palette.focus)) root.style.setProperty('--focus-glow', rgba(palette.focus, 0.45));
      if (validColor(palette.focus)) root.style.setProperty('--focus-soft', rgba(palette.focus, 0.15));
      if (validColor(palette.accent)) root.style.setProperty('--accent-soft', rgba(palette.accent, 0.16));
      if (validColor(palette.danger)) root.style.setProperty('--danger-soft', rgba(palette.danger, 0.16));
      if (typeof palette.background === 'string' && /^#[0-9a-fA-F]{6}$/.test(palette.background) &&
          window.SonixControlBridge && window.SonixControlBridge.setBackgroundColor) {
        window.SonixControlBridge.setBackgroundColor(palette.background);
      }
      try { localStorage.setItem('sonix_remote_theme', JSON.stringify(theme)); } catch (_) {}
    }

    try {
      const cachedRemoteTheme = JSON.parse(localStorage.getItem('sonix_remote_theme') || 'null');
      if (cachedRemoteTheme) applySonixRemoteTheme(cachedRemoteTheme);
    } catch (_) {}

    // Central SONIX CONTROL settings and message delivery.
    window.onSonixControlSync = function (payload) {
      if (!payload || payload.status !== 'active') return;
      const serverLists = ['login-server', 'iptv-server-url'];
      const availableServers = Array.isArray(payload.servers) ? payload.servers : [];
      serverLists.forEach(serverId => {
        let selector = document.getElementById(serverId);
        if (!selector) return;
        if (selector.tagName !== 'SELECT') {
          const replacement = document.createElement('select');
          replacement.id = serverId;
          replacement.className = selector.className;
          replacement.style.cssText = selector.style.cssText;
          replacement.setAttribute('aria-label', 'اختار السيرفر');
          selector.replaceWith(replacement);
          selector = replacement;
        }
        selector.innerHTML = '';
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = 'اختار السيرفر';
        placeholder.selected = true;
        selector.appendChild(placeholder);
        availableServers.forEach(server => {
          if (!server || !server.server_url) return;
          const option = document.createElement('option');
          option.value = String(server.server_url);
          option.textContent = String(server.name || 'سيرفر');
          selector.appendChild(option);
        });
        selector.value = '';
        selector.disabled = availableServers.length === 0;
      });
      const loginServer = document.getElementById('login-server');
      const loginButton = document.getElementById('login-submit');
      if (loginButton) loginButton.disabled = !loginServer || !loginServer.value;
      if (loginServer && !loginServer.dataset.sonixChangeBound) {
        loginServer.addEventListener('change', () => {
          if (loginButton) loginButton.disabled = !loginServer.value;
          if (loginServer.value) setLoginMessage('');
        });
        loginServer.dataset.sonixChangeBound = '1';
      }
      // An empty list is not a customer-facing error; keep the login message
      // area reserved for actual credential/login errors only.
      setLoginMessage('');
      applySonixRemoteTheme(payload.theme);
      (payload.messages || []).forEach(message => {
        const title = String(message && message.title || '').trim();
        const body = String(message && message.body || '').trim();
        if (title || body) {
          enqueueRemoteMessage(title, body);
          if (window.SonixControlBridge && window.SonixControlBridge.acknowledge) {
            window.SonixControlBridge.acknowledge(String(message.id));
          }
        }
      });
    };

    function parseNative(raw) {
      const value = JSON.parse(raw);
      if (value && value.success === false) throw new Error(value.error || 'فشل طلب الخادم');
      return value;
    }
    function isNetworkFailure(error) {
      const message = String(error && error.message ? error.message : error || '');
      return /تعذر العثور على خادم|انتهت مهلة الاتصال|تعذر الاتصال بخادم|فشل اتصال الخادم|قوائم محتوى فارغة|unable to resolve|failed to connect|connection refused|timed out|network|HTTP 5\d\d/i.test(message);
    }
    function makeCategories(rows, prefix) {
      return rows.map(row => ({id:prefix + String(row.category_id), providerId:String(row.category_id), name:row.category_name || 'تصنيف'}));
    }
    // Keep the two built-in entries first in every content section, including restored offline caches.
    function orderCategories(categories, allLabel) {
      const source = Array.isArray(categories) ? categories : [];
      const all = source.find(category => category && category.id === 'all') || {id:'all',name:allLabel};
      const favorites = source.find(category => category && category.id === 'favorites') || {id:'favorites',name:'المفضلة'};
      return [favorites, all, ...source.filter(category =>
        category && category.id !== 'favorites' && category.id !== 'all')];
    }
    function makeStreamUrl(type, id, item) {
      if (!activeCredentials || !window.XtreamBridge) return '';
      const ext = (item.container_extension || (type === 'live' ? 'ts' : 'mp4')).replace(/^\./,'');
      return parseNative(window.XtreamBridge.streamUrl(type,String(id),ext)).url;
    }
    function restoreXtreamCache() {
      return prepareCatalogHome();
    }
    const CATALOG_SECTIONS={live:{card:'card-channels',mode:'live',prefix:'live-',label:'القنوات'},movies:{card:'card-movies',mode:'movies',prefix:'movie-',label:'الأفلام'},series:{card:'card-series',mode:'series',prefix:'series-',label:'المسلسلات'}};
    const catalogSyncRequests=new Map();
    const catalogSyncStates={live:'missing',movies:'missing',series:'missing'};
    const catalogSavedAt={live:0,movies:0,series:0};
    const catalogAutoRefreshAttempt={live:0,movies:0,series:0};
    let catalogRequestSequence=0,lastCatalogRequestId=null;

    function setCatalogCardState(section,state,message=''){
      const config=CATALOG_SECTIONS[section],card=config&&document.getElementById(config.card);
      const overlay=card&&card.querySelector('[data-catalog-overlay]');
      if(!card||!overlay)return;
      catalogSyncStates[section]=state;card.classList.toggle('catalog-ready',state==='ready'||state==='cached');
      const prompt=overlay.querySelector('.catalog-sync-prompt'),loading=overlay.querySelector('.catalog-sync-loading');
      const error=overlay.querySelector('.catalog-sync-error'),errorText=overlay.querySelector('.catalog-sync-error-text');
      if(prompt)prompt.hidden=state!=='missing';
      if(loading)loading.hidden=state!=='loading';
      if(error)error.hidden=state!=='error';
      if(errorText)errorText.textContent=message||'تعذر الاتصال بالخادم';
    }

    function applyCatalogSection(section,payload){
      const config=CATALOG_SECTIONS[section];
      if(!config||!payload||!Array.isArray(payload.categories)||!Array.isArray(payload.items)||!payload.items.length)throw new Error('بيانات القسم المستلمة غير مكتملة');
      const categories=makeCategories(payload.categories,config.prefix),names=new Map(categories.map(item=>[item.providerId,item.name]));
      if(section==='live'){
        liveCategories=orderCategories([{id:'all',name:'جميع القنوات'},...categories,{id:'favorites',name:'المفضلة'}],'جميع القنوات');
        liveChannels=payload.items.map((x,i)=>({id:String(x.stream_id),number:i+1,name:x.name||'قناة',category:x.category_id==null?'':'live-'+x.category_id,categoryName:names.get(String(x.category_id))||'',logo:x.stream_icon||'',logoText:'',streamUrl:'',epgChannelId:x.epg_channel_id||'',containerExtension:x.container_extension||'ts'}));
      }else if(section==='movies'){
        movieCategories=orderCategories([{id:'all',name:'جميع الأفلام'},...categories,{id:'favorites',name:'المفضلة'}],'جميع الأفلام');
        movies=payload.items.map((x,i)=>({id:String(x.stream_id),number:i+1,name:x.name||'فيلم',category:x.category_id==null?'':'movie-'+x.category_id,categoryName:names.get(String(x.category_id))||'',logo:x.stream_icon||'',logoText:'',streamUrl:'',rating:x.rating||'',desc:x.plot||'',year:x.year||(x.release_date?String(x.release_date).substring(0,4):'')||'',genre:x.genre||'',containerExtension:x.container_extension||'mp4'}));
      }else{
        seriesCategories=orderCategories([{id:'all',name:'جميع المسلسلات'},...categories,{id:'favorites',name:'المفضلة'}],'جميع المسلسلات');
        series=payload.items.map((x,i)=>({id:String(x.series_id),seriesId:String(x.series_id),number:i+1,name:x.name||'مسلسل',category:x.category_id==null?'':'series-'+x.category_id,categoryName:names.get(String(x.category_id))||'',logo:x.cover||'',logoText:'',streamUrl:'',rating:x.rating||'',desc:x.plot||'',year:x.releaseDate?String(x.releaseDate).substring(0,4):(x.year||''),genre:x.genre||''}));
      }
      catalogSavedAt[section]=Number(payload.savedAt||Date.now());
      if(currentAppMode===config.mode){currentActiveCategory='all';renderCategories();renderItems();if(section==='live')renderAudioSourcePicker();}
    }

    function startCatalogSync(section,options={}){
      const config=CATALOG_SECTIONS[section];
      if(!config||!activeCredentials||!window.XtreamBridge)return false;
      if(catalogSyncRequests.has(section)){
        const active=catalogSyncRequests.get(section);
        if(active&&active.silent&&!options.silent){active.silent=false;setCatalogCardState(section,'loading');}
        return false;
      }
      const silent=Boolean(options.silent),requestId='catalog-'+section+'-'+(++catalogRequestSequence)+'-'+Date.now();
      catalogSyncRequests.set(section,{requestId,silent});lastCatalogRequestId=requestId;catalogAutoRefreshAttempt[section]=Date.now();
      if(!silent)setCatalogCardState(section,'loading');
      try{window.XtreamBridge.loadSectionAsync(section,requestId);}
      catch(error){catalogSyncRequests.delete(section);refreshLastCatalogRequestId();if(!silent)setCatalogCardState(section,'error',error.message||'تعذر بدء التحميل');return false;}
      return true;
    }

    function refreshLastCatalogRequestId(){
      lastCatalogRequestId=null;
      for(const request of catalogSyncRequests.values())lastCatalogRequestId=request.requestId;
    }

    window.onXtreamSectionLoaded=function(requestId,rawResult){
      let response;try{response=typeof rawResult==='string'?JSON.parse(rawResult):rawResult;}catch(_){response={success:false,error:'استجابة غير مفهومة من خدمة المزامنة'};}
      let section=null;for(const [key,value] of catalogSyncRequests.entries())if(value.requestId===requestId){section=key;break;}
      if(!section)return;
      const request=catalogSyncRequests.get(section);catalogSyncRequests.delete(section);
      if(lastCatalogRequestId===requestId)refreshLastCatalogRequestId();
      if(response.success&&response.data){
        try{applyCatalogSection(section,response.data);setCatalogCardState(section,request.silent&&catalogSyncStates[section]==='cached'?'cached':'ready');if(!request.silent)showToast('اكتملت مزامنة '+CATALOG_SECTIONS[section].label);}
        catch(error){if(!request.silent)setCatalogCardState(section,'error',error.message);}
      }else{
        catalogAutoRefreshAttempt[section]=Date.now();
        if(!request.silent){setCatalogCardState(section,'error',response.error||'تعذر الاتصال بالخادم');showToast('فشل تحميل '+CATALOG_SECTIONS[section].label);}
      }
    };

    function cancelActiveCatalogSync(){
      if(!lastCatalogRequestId)return false;
      let section=null;for(const [key,value] of catalogSyncRequests.entries())if(value.requestId===lastCatalogRequestId){section=key;break;}
      if(!section){refreshLastCatalogRequestId();return lastCatalogRequestId?cancelActiveCatalogSync():false;}
      const requestId=lastCatalogRequestId,request=catalogSyncRequests.get(section);
      catalogSyncRequests.delete(section);refreshLastCatalogRequestId();
      try{window.XtreamBridge.cancelSectionLoad(requestId);}catch(_){}
      if(request&&!request.silent)setCatalogCardState(section,'missing');
      showToast('تم إلغاء تحميل '+CATALOG_SECTIONS[section].label);return true;
    }

    function prepareCatalogHome(){
      const now=Date.now();let hasCached=false;
      Object.keys(CATALOG_SECTIONS).forEach(section=>{
        let cached=null;
        try{const value=parseNative(window.XtreamBridge.loadCachedSection(section));if(Array.isArray(value.categories)&&Array.isArray(value.items)&&value.items.length)cached=value;}catch(_){}
        if(cached){
          try{applyCatalogSection(section,cached);setCatalogCardState(section,'cached');hasCached=true;if(!cached.savedAt||now-Number(cached.savedAt)>=86400000)startCatalogSync(section,{silent:true});}
          catch(_){setCatalogCardState(section,'missing');}
        }else setCatalogCardState(section,'missing');
      });
      return hasCached;
    }

    function checkCatalogRefreshDue(){
      const now=Date.now();
      Object.keys(CATALOG_SECTIONS).forEach(section=>{
        if((catalogSyncStates[section]==='ready'||catalogSyncStates[section]==='cached')&&now-catalogSavedAt[section]>=86400000&&now-catalogAutoRefreshAttempt[section]>=86400000&&!catalogSyncRequests.has(section))startCatalogSync(section,{silent:true});
      });
    }

    function openCatalogSection(section){
      const config=CATALOG_SECTIONS[section];if(!config)return;
      if(catalogSyncStates[section]!=='ready'&&catalogSyncStates[section]!=='cached'){startCatalogSync(section);return;}
      switchView('view-live');
      if(section==='live'){if(window.NativePlayback&&typeof window.NativePlayback.stop==='function')window.NativePlayback.stop();if(videoPlaybackController){videoPlaybackController.currentItem=null;videoPlaybackController.hidePlaybackControls();}const panel=document.querySelector('.live-player-col');if(panel)panel.classList.remove('has-active-playback');}
      setAppMode(config.mode);
    }

    function refreshDownloadedCatalogs(){
      let started=false;Object.keys(CATALOG_SECTIONS).forEach(section=>{if(catalogSyncStates[section]==='ready'||catalogSyncStates[section]==='cached')started=startCatalogSync(section)||started;});
      if(!started)showToast('حمّل أحد الأقسام أولاً لتحديث بياناته');
    }

    function setLoginMessage(message, success) {
      const el=document.getElementById('sonix-login-message');
      if(el){el.textContent=message || '';el.style.color=success?'var(--success-green)':'var(--danger-red)';}
    }
    function updateAccount(user) {
      accountInfo=user || {};
      const exp=Number(accountInfo.exp_date || 0);
      document.getElementById('account-user-name').textContent=accountInfo.username || '—';
      document.getElementById('account-user-id').textContent=accountInfo.user_id || accountInfo.username || '—';
      document.getElementById('account-expiry').textContent=exp>0?new Date(exp*1000).toLocaleDateString('ar'):'غير محدد';
      const pn=document.getElementById('profile-user-name'); if(pn) pn.textContent=accountInfo.username || '—';
      const pi=document.getElementById('profile-user-id'); if(pi) pi.textContent=accountInfo.user_id || accountInfo.username || '—';
      const pe=document.getElementById('profile-expiry'); if(pe) pe.textContent=exp>0?new Date(exp*1000).toLocaleDateString('ar'):'غير محدد';
      const pp=document.getElementById('profile-package'); if(pp) pp.textContent=accountInfo.status || '—';
    }
    function performXtreamLogin(server,username,password,offlineFallback) {
      const button=document.getElementById('login-submit');
      button.disabled=true;button.textContent='جارٍ التحقق من الاشتراك...';setLoginMessage('');
      try {
        const result=parseNative(window.XtreamBridge.login(server,username,password));
        activeCredentials={server:server.trim().replace(/\/+$/,''),username:result.user_info.username || username};
        updateAccount(result.user_info);
        const loginScreen = document.getElementById('sonix-login-screen');
        if (loginScreen.contains(document.activeElement)) document.activeElement.blur();
        loginScreen.classList.add('hidden');
        switchView('view-home');
        if (remoteNavigator) remoteNavigator.focusElement(document.getElementById('card-movies'));
        window.setTimeout(prepareCatalogHome, 0);
        document.getElementById('login-password').value='';
        const settingsPassword=document.getElementById('iptv-password'); if(settingsPassword) settingsPassword.value='';
        showToast('تم تسجيل الدخول بنجاح');
      } catch(error) {
        if (offlineFallback) {
          try {
            const saved=parseNative(window.XtreamBridge.savedCredentials());
            activeCredentials={server:saved.server,username:saved.username};
          } catch (_) {}
        }
        if(offlineFallback && isNetworkFailure(error) && activeCredentials && restoreXtreamCache()) {
          updateAccount({username:activeCredentials.username});
          const loginScreen = document.getElementById('sonix-login-screen');
          if (loginScreen.contains(document.activeElement)) document.activeElement.blur();
          loginScreen.classList.add('hidden');
          switchView('view-home');
          if (remoteNavigator) remoteNavigator.focusElement(document.getElementById('card-movies'));
          showToast('تعذر الاتصال؛ يتم عرض آخر بيانات مخزنة');
          renderCategories();renderItems();renderAudioSourcePicker();
        } else {
          setLoginMessage(error.message || 'فشل تسجيل الدخول');
        }
      } finally {
        button.disabled=false;button.textContent='تسجيل الدخول';
      }
    }
    function openSeriesEpisodes(item) {
      try {
        const data=parseNative(window.XtreamBridge.seriesInfo(item.seriesId));
        const episodes=data.episodes || {};
        const tabs=document.getElementById('series-season-tabs');
        const list=document.getElementById('series-episodes-list');
        const seriesTitle=document.getElementById('series-modal-series-title');
        const selectedSeasonTitle=document.getElementById('series-selected-season-title');
        const seasonEpisodeCount=document.getElementById('series-season-episode-count');
        const totalEpisodeCount=document.getElementById('series-total-episode-count');
        tabs.replaceChildren();
        list.replaceChildren();

        const seasonKeys=Object.keys(episodes)
          .filter(key=>Array.isArray(episodes[key]) && episodes[key].length)
          .sort((a,b)=>Number(a)-Number(b));
        const totalEpisodes=seasonKeys.reduce((total,key)=>total+episodes[key].length,0);
        if(!seasonKeys.length || !totalEpisodes) throw new Error('لا توجد حلقات متاحة لهذا المسلسل');

        const formatEpisodeDuration=(episode)=>{
          const info=episode.info || {};
          const raw=info.duration || episode.duration || '';
          if(raw && /^\\d{1,2}:\\d{2}:\\d{2}$/.test(String(raw))) return String(raw);
          const seconds=Number(info.duration_secs || episode.duration_secs || 0);
          if(seconds>0) {
            const h=Math.floor(seconds/3600);
            const m=Math.floor((seconds%3600)/60);
            const sec=seconds%60;
            return [h,m,sec].map(value=>String(value).padStart(2,'0')).join(':');
          }
          return raw && String(raw).trim() ? String(raw).trim() : '';
        };
        const seasonLabel=season=>Number(season)===0?'حلقات خاصة':'الموسم '+season;

        seriesTitle.textContent=item.name || 'المسلسل';
        totalEpisodeCount.textContent=totalEpisodes+' حلقة · '+seasonKeys.length+' مواسم';

        const selectSeason=(season)=>{
          const seasonEpisodes=episodes[season] || [];
          tabs.querySelectorAll('.series-season-tab').forEach(tab=>{
            const selected=tab.dataset.season===String(season);
            tab.classList.toggle('active',selected);
            tab.setAttribute('aria-selected',selected?'true':'false');
          });
          selectedSeasonTitle.textContent=seasonLabel(season);
          seasonEpisodeCount.textContent=seasonEpisodes.length+' حلقة';
          list.replaceChildren();

          const rawTitles=seasonEpisodes.map(ep=>String(ep.title || '').trim()).filter(Boolean);
          const commonTitle=rawTitles.length && rawTitles.every(title=>title===rawTitles[0]) ? rawTitles[0] : '';
          seasonEpisodes.forEach(ep=>{
            const episodeNumber=Number(ep.episode_num || 0);
            const episodeCode='S'+String(season).padStart(2,'0')+'E'+String(episodeNumber).padStart(2,'0');
            const card=document.createElement('button');
            card.type='button';
            card.className='episode-card focusable';
            card.setAttribute('role','listitem');

            const rawTitle=String(ep.title || '').trim();
            const normalizedSeries=String(item.name || '').trim().toLocaleLowerCase();
            let episodeTitle=rawTitle;
            if(normalizedSeries && episodeTitle.toLocaleLowerCase().startsWith(normalizedSeries)) {
              episodeTitle=episodeTitle.slice(String(item.name).length).replace(/^\s*[-–—:|]+\s*/,'').trim();
            }
            if(!episodeTitle || rawTitle===commonTitle || episodeTitle.toLocaleLowerCase()===normalizedSeries || /^s\d{1,2}e\d{1,3}\s*[-–—:|]*$/i.test(episodeTitle)) episodeTitle='';


            card.setAttribute('aria-label','موسم '+season+'، الحلقة '+String(episodeNumber).padStart(2,'0')+(episodeTitle?'، '+episodeTitle:''));

            const number=document.createElement('span');
            number.className='episode-number-tile';
            number.textContent=String(episodeNumber || '').padStart(2,'0');

            const details=document.createElement('span');
            details.className='episode-details';
            const seasonLabelNode=document.createElement('span');
            seasonLabelNode.className='episode-season-label';
            seasonLabelNode.textContent=seasonLabel(season);
            details.appendChild(seasonLabelNode);
            if(episodeTitle) {
              const name=document.createElement('span');
              name.className='episode-name';
              name.textContent=episodeTitle;
              details.appendChild(name);
            }
            const durationText=formatEpisodeDuration(ep);
            if(durationText) {
              const duration=document.createElement('span');
              duration.className='episode-duration';
              duration.textContent=durationText;
              details.appendChild(duration);
            }

            const play=document.createElement('span');
            play.className='episode-play-icon';
            play.setAttribute('aria-hidden','true');
            play.textContent='▶';
            card.append(number,details,play);
            card.addEventListener('click',()=>{
              const ext=ep.container_extension || 'mp4';
              closeAllModals();
              selectItem({...item,seriesId:null,id:String(ep.id),name:ep.title || item.name,
                subtitleTitle:item.name,seriesTitle:item.name,subtitleSeries:true,
                subtitleSeason:Number(season),subtitleEpisode:episodeNumber,
                streamDescriptor:{type:'series',id:String(ep.id),ext:ext},streamUrl:'native://stream'});
            });
            list.appendChild(card);
          });
        };

        seasonKeys.forEach((season,index)=>{
          const seasonEpisodes=episodes[season] || [];
          const tab=document.createElement('button');
          tab.type='button';
          tab.className='series-season-tab focusable';
          tab.dataset.season=String(season);
          tab.setAttribute('role','tab');
          tab.setAttribute('aria-controls','series-episodes-list');
          tab.setAttribute('aria-selected',index===0?'true':'false');

          const number=document.createElement('span');
          number.className='series-season-tab-number';
          number.textContent=Number(season)===0?'★':String(season).padStart(2,'0');
          const label=document.createElement('span');
          label.className='series-season-tab-label';
          label.textContent=seasonLabel(season);
          const count=document.createElement('span');
          count.className='series-season-tab-count';
          count.textContent=seasonEpisodes.length+' حلقة';
          tab.append(number,label,count);
          tab.addEventListener('click',()=>selectSeason(season));
          tabs.appendChild(tab);
        });

        selectSeason(seasonKeys[0]);
        const modal=document.getElementById('series-episodes-modal');
        modal.setAttribute('aria-hidden','false');
        modal.classList.add('active');
        const firstTab=tabs.querySelector('.series-season-tab');
        if(firstTab && remoteNavigator) remoteNavigator.focusElement(firstTab);
      } catch(error) { showToast(error.message || 'تعذر تحميل الحلقات'); }
    }
    function refreshXtreamContent(options = {}) {
      if(!activeCredentials){if(!options.silent)showToast('سجّل الدخول أولاً');return false;}
      if(!options.silent)refreshDownloadedCatalogs();
      else Object.keys(CATALOG_SECTIONS).forEach(section=>{
        if(catalogSyncStates[section]==='ready'||catalogSyncStates[section]==='cached')startCatalogSync(section,{silent:true});
      });
      return true;
    }

    /**
     * =========================================================================
     * UNIFIED CONTENT BROWSER ENGINE (CHANNELS, MOVIES, SERIES IN SAME 4 COLUMNS)
     * =========================================================================
     */
    let currentActiveCategory = '';
    let currentSelectedItem = null;
    let currentRenderedItems = [];
    let currentRenderedCount = 0;
    let channelRenderGeneration = 0;
    const CHANNEL_RENDER_BATCH_SIZE = 80;
    const CHANNEL_RENDER_PREFETCH_PX = 420;

    function appendChannelRows() {
      const container = document.getElementById('channels-list-container');
      if (!container || currentRenderedCount >= currentRenderedItems.length) return;
      const end = Math.min(currentRenderedCount + CHANNEL_RENDER_BATCH_SIZE, currentRenderedItems.length);
      const fragment = document.createDocumentFragment();
      for (let index = currentRenderedCount; index < end; index++) {
        const it = currentRenderedItems[index];
        const row = document.createElement('div');
        const isSelected = currentSelectedItem && currentSelectedItem.id === it.id;
        const isFav = isFavorite(it.id);
        row.className = `channel-row focusable ${isSelected ? 'selected' : ''}`;
        row.tabIndex = 0;
        row.dataset.itemId = it.id;
        row.innerHTML = `
          <span class="channel-number"></span>
          <div class="channel-logo-box"></div>
          <span class="channel-name"></span>
          <svg class="channel-fav-star ${isFav ? 'active' : ''}" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/>
          </svg>`;
        let lastRowTap = 0;
        row.addEventListener('click', () => selectItem(it));
        row.addEventListener('dblclick', event => {
          event.preventDefault(); selectItem(it);
          if (videoPlaybackController && !videoPlaybackController.isFullscreen) videoPlaybackController.toggleFullscreen();
        });
        row.addEventListener('touchend', event => {
          const now = Date.now();
          if (now - lastRowTap < 350 && now - lastRowTap > 40) {
            event.preventDefault(); selectItem(it);
            if (videoPlaybackController && !videoPlaybackController.isFullscreen) videoPlaybackController.toggleFullscreen();
          }
          lastRowTap = now;
        });
        row.querySelector('.channel-number').textContent = String(it.number || '');
        row.querySelector('.channel-name').textContent = it.name || '';
        setProviderImage(row.querySelector('.channel-logo-box'), it.logo, it.name, it.logoText || '');
        fragment.appendChild(row);
      }
      currentRenderedCount = end;
      container.appendChild(fragment);
    }

    function loadMoreChannelRows() {
      const before = currentRenderedCount;
      appendChannelRows();
      return currentRenderedCount > before;
    }

    function setAppMode(mode) {
      const previousMode = currentAppMode;
      if (previousMode !== mode) {
        if (window.NativePlayback && typeof window.NativePlayback.stop === 'function') {
          window.NativePlayback.stop();
        }
        if (videoPlaybackController) {
          videoPlaybackController.currentItem = null;
          videoPlaybackController.hidePlaybackControls();
        }
        const playerPanel = document.querySelector('.live-player-col');
        if (playerPanel) {
          playerPanel.classList.remove('has-active-playback');
          playerPanel.classList.toggle('is-live-mode', mode === 'live');
        }
        previewLastRenderedKey = null;
        const previewContent = document.getElementById('sonix-preview-content');
        if (previewContent) {
          const sectionLabel = mode === 'movies' ? 'الأفلام' : (mode === 'series' ? 'المسلسلات' : 'القنوات');
          previewContent.innerHTML = `
            <div class="preview-idle-state">
              <div class="preview-idle-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <rect x="2" y="7" width="20" height="15" rx="2" ry="2"/>
                  <polyline points="17 2 12 7 7 2"/>
                </svg>
              </div>
              <div class="preview-idle-title">معاينة ${sectionLabel}</div>
              <div class="preview-idle-desc">تصفح العناصر بالريموت لعرض التفاصيل الكاملة</div>
            </div>`;
        }
      }
      currentAppMode = mode;
      const itemsContainer = document.getElementById('channels-list-container');
      if (itemsContainer) itemsContainer.classList.toggle('media-grid', mode === 'movies' || mode === 'series');
      if (sonixSettings.rememberSection) {
        try { localStorage.setItem('sonix_last_mode_v1', mode); } catch (_) {}
      }
      
      // Update sidebar nav highlights
      document.getElementById('side-nav-live').classList.toggle('active', mode === 'live');
      document.getElementById('side-nav-movies').classList.toggle('active', mode === 'movies');
      document.getElementById('side-nav-series').classList.toggle('active', mode === 'series');

      // Update top bar title and placeholders
      const titleEl = document.getElementById('content-mode-title');
      const searchInput = document.getElementById('live-channel-search');
      const catSearchInput = document.getElementById('cat-filter-input');

      if (mode === 'live') {
        titleEl.textContent = 'مباشر';
        searchInput.placeholder = 'البحث في القنوات...';
        catSearchInput.placeholder = 'البحث في فئات القنوات...';
        currentActiveCategory = 'all';
        currentSelectedItem = liveChannels[0] || null;
      } else if (mode === 'movies') {
        titleEl.textContent = 'الأفلام (VOD)';
        searchInput.placeholder = 'البحث في الأفلام...';
        catSearchInput.placeholder = 'البحث في تصنيفات الأفلام...';
        currentActiveCategory = 'all';
        currentSelectedItem = movies[0] || null;
      } else if (mode === 'series') {
        titleEl.textContent = 'المسلسلات (Series)';
        searchInput.placeholder = 'البحث في المسلسلات...';
        catSearchInput.placeholder = 'البحث في تصنيفات المسلسلات...';
        currentActiveCategory = 'all';
        currentSelectedItem = series[0] || null;
      }

      if (sonixSettings.rememberSection) {
        try {
          const savedCategory = localStorage.getItem('sonix_last_category_' + mode);
          if (savedCategory && getActiveCategoryList().some(category => category.id === savedCategory)) currentActiveCategory = savedCategory;
        } catch (_) {}
      }
      if (sonixSettings.rememberItem) {
        try {
          const savedId = localStorage.getItem('sonix_last_item_' + mode);
          const remembered = getActiveItemList().find(item => String(item.id) === String(savedId));
          if (remembered) currentSelectedItem = {...remembered, streamUrl:''};
        } catch (_) {}
      }

      renderCategories();
      renderItems();
      if (currentSelectedItem && currentSelectedItem.streamUrl) videoPlaybackController.loadItem(currentSelectedItem);
      if (window.innerWidth <= 600) {
        const catCol = document.querySelector('.live-categories-col');
        if (catCol) catCol.scrollIntoView({ behavior: 'smooth', inline: 'start' });
      }
    }

    function getActiveCategoryList() {
      if (currentAppMode === 'movies') return movieCategories;
      if (currentAppMode === 'series') return seriesCategories;
      return liveCategories;
    }

    function getActiveItemList() {
      if (currentAppMode === 'movies') return movies;
      if (currentAppMode === 'series') return series;
      return liveChannels;
    }

    function renderCategories() {
      const container = document.getElementById('categories-list-container');
      if (!container) return;
      container.innerHTML = '';

      const categories = getActiveCategoryList();
      const allItems = getActiveItemList();
      const categoryCounts = new Map();
      let favoritesCount = 0;
      allItems.forEach(entry => {
        if (entry.category) categoryCounts.set(entry.category, (categoryCounts.get(entry.category) || 0) + 1);
        if (isFavorite(entry.id)) favoritesCount++;
      });

      categories.forEach(cat => {
        const item = document.createElement('div');
        item.className = `category-item focusable ${cat.id === currentActiveCategory ? 'selected' : ''}`;
        item.tabIndex = 0;
        item.dataset.catId = cat.id;

        const count = cat.id === 'all' ? allItems.length
          : cat.id === 'favorites' ? favoritesCount
          : (categoryCounts.get(cat.id) || 0);
        const name = document.createElement('span');
        name.textContent = cat.name || 'تصنيف';
        const badge = document.createElement('span');
        badge.className = 'category-badge';
        badge.textContent = String(count);
        item.append(name, badge);

        item.addEventListener('click', () => {
          selectCategory(cat.id);
        });

        container.appendChild(item);
      });
    }

    function selectCategory(catId) {
      currentActiveCategory = catId;
      if (sonixSettings.rememberSection) {
        try { localStorage.setItem('sonix_last_category_' + currentAppMode, catId); } catch (_) {}
      }
      document.querySelectorAll('.category-item').forEach(el => {
        el.classList.toggle('selected', el.dataset.catId === catId);
      });

      const categories = getActiveCategoryList();
      const catObj = categories.find(c => c.id === catId);
      if (catObj) {
        document.getElementById('channels-badge-name').textContent = catObj.name;
      }

      renderItems();
      if (window.innerWidth <= 600) {
        const channelsCol = document.querySelector('.live-channels-col');
        if (channelsCol) channelsCol.scrollIntoView({ behavior: 'smooth', inline: 'start' });
      }
    }

    function renderItems(filterText = '') {
      const container = document.getElementById('channels-list-container');
      if (!container) return;
      container.classList.toggle('media-grid', currentAppMode === 'movies' || currentAppMode === 'series');
      channelRenderGeneration++;
      container.innerHTML = '';
      currentRenderedItems = [];
      currentRenderedCount = 0;
      const allItems = getActiveItemList();
      const normalizedFilter = String(filterText || '').trim().toLowerCase();
      let items;
      if (currentActiveCategory === 'all') items = allItems;
      else if (currentActiveCategory === 'favorites') items = allItems.filter(item => isFavorite(item.id));
      else items = allItems.filter(item => item.category === currentActiveCategory);
      if (currentActiveCategory === 'all' && sonixSettings.favoritesFirst) {
        items = items.slice().sort((a,b) => Number(isFavorite(b.id)) - Number(isFavorite(a.id)));
      }
      if (normalizedFilter) items = items.filter(item => String(item.name || '').toLowerCase().includes(normalizedFilter));
      const countSuffix = currentAppMode === 'movies' ? 'فيلم' : (currentAppMode === 'series' ? 'مسلسل' : 'قناة');
      document.getElementById('channels-badge-count').textContent = `${items.length} ${countSuffix}`;
      if (!items.length) {
        container.innerHTML = `<div style="color:var(--text-muted); text-align:center; padding:24px; font-size:0.85rem;">لا توجد عناصر متاحة في هذا التصنيف</div>`;
        return;
      }
      currentRenderedItems = items;
      if (!container.dataset.incrementalScrollBound) {
        container.addEventListener('scroll', () => {
          const remainingScroll = container.scrollHeight - container.clientHeight - container.scrollTop;
          if (remainingScroll <= CHANNEL_RENDER_PREFETCH_PX) loadMoreChannelRows();
        }, { passive: true });
        container.dataset.incrementalScrollBound = 'true';
      }
      if (!container.dataset.previewFocusBound) {
        container.addEventListener('focusin', (e) => {
          if (typeof handleItemFocusForPreview === 'function') {
            handleItemFocusForPreview(e.target);
          }
        });
        container.dataset.previewFocusBound = 'true';
      }
      // Render only the first batch. Further batches are requested by actual
      // scrolling or by DPAD navigation at the end of the rendered rows.
      appendChannelRows();

      // Populate the details preview immediately after a section/category/search
      // render; focus may initially remain on the category sidebar instead.
      const previewItem = currentSelectedItem &&
        items.find(item => String(item.id) === String(currentSelectedItem.id))
        ? items.find(item => String(item.id) === String(currentSelectedItem.id))
        : items[0];
      if (previewItem) {
        const renderedRows = Array.from(container.querySelectorAll('.channel-row'));
        const previewRow = renderedRows.find(row =>
          String(row.dataset.itemId) === String(previewItem.id)) || renderedRows[0];
        if (previewRow) handleItemFocusForPreview(previewRow);
      }
    }

    function loadLiveEpg(item) {
      if (!item || !window.XtreamBridge) return;
      item.epgTitle = '';
      item.epgDesc = '';
      item.epgNext = '';
      const currentTitle = document.getElementById('epg-curr-title');
      const currentDesc = document.getElementById('epg-curr-desc');
      const nextTitle = document.getElementById('epg-next-title');
      if (currentSelectedItem && currentSelectedItem.id === item.id) {
        if (currentTitle) currentTitle.textContent = 'جارٍ تحميل دليل البرامج...';
        if (currentDesc) currentDesc.textContent = 'يرجى الانتظار';
        if (nextTitle) nextTitle.textContent = 'لا توجد بيانات بعد';
      }
      try {
        const response=parseNative(window.XtreamBridge.shortEpg(item.id));
        const listings=response.epg_listings || [];
        if (!listings.length) {
          if (currentSelectedItem && currentSelectedItem.id === item.id) {
            if (currentTitle) currentTitle.textContent = 'لا تتوفر بيانات EPG لهذه القناة';
            if (currentDesc) currentDesc.textContent = 'لم يرسل الخادم جدول برامج';
            if (nextTitle) nextTitle.textContent = 'لا توجد بيانات للبرنامج التالي';
          }
          return;
        }
        const decodeText=value => {
          try { return decodeURIComponent(escape(atob(value || ''))); } catch (_) { return value || ''; }
        };
        const current=listings[0], next=listings[1];
        item.epgTitle=decodeText(current.title);
        item.epgDesc=decodeText(current.description);
        item.epgNext=next ? decodeText(next.title) : '';
        if(currentSelectedItem && currentSelectedItem.id === item.id) {
          document.getElementById('epg-curr-title').textContent=item.epgTitle || 'بث مباشر';
          document.getElementById('epg-curr-desc').textContent=item.epgDesc || 'لا توجد تفاصيل إضافية';
          document.getElementById('epg-next-title').textContent=item.epgNext || 'لا توجد بيانات للبرنامج التالي';
        }
      } catch(error) {
        console.warn('EPG unavailable',error);
        if (currentSelectedItem && currentSelectedItem.id === item.id) {
          if (currentTitle) currentTitle.textContent = 'تعذر تحميل دليل البرامج';
          if (currentDesc) currentDesc.textContent = error.message || 'الخادم لم يوفر بيانات EPG';
          if (nextTitle) nextTitle.textContent = 'لا توجد بيانات للبرنامج التالي';
        }
      }
    }
    function selectItem(item) {
      currentSelectedItem = item;
      if (sonixSettings.rememberItem) {
        try { localStorage.setItem('sonix_last_item_' + currentAppMode, String(item.id)); } catch (_) {}
      }
      document.querySelectorAll('.channel-row').forEach(el => {
        el.classList.toggle('selected', el.dataset.itemId === item.id);
      });

      if (currentAppMode === 'series' && item.seriesId) { openSeriesEpisodes(item); return; }
      if (currentAppMode === 'live' && !item.streamUrl) {
        item.streamDescriptor = { type: 'live', id: String(item.id), ext: item.containerExtension || 'ts' }; item.streamUrl = 'native://stream';
      }
      if (currentAppMode === 'movies') {
        // The VOD catalogue already contains the stream extension and basic
        // metadata. Do not make a synchronous get_vod_info HTTP request here:
        // it blocks selection and delays playback before ExoPlayer even starts.
        if (!item.streamUrl) {
          item.streamDescriptor = { type: 'movie', id: String(item.id), ext: item.containerExtension || 'mp4' };
          item.streamUrl = 'native://stream';
        }
      }
      if (!item.streamUrl) { showToast('رابط التشغيل غير متوفر من الخادم'); return; }
      videoPlaybackController.loadItem(item);
      if (currentAppMode === 'live') loadLiveEpg(item);
      if (window.innerWidth <= 600) {
        const playerCol = document.querySelector('.live-player-col');
        if (playerCol) playerCol.scrollIntoView({ behavior: 'smooth', inline: 'start' });
      }
    }

    function classifyAudioSource(name, categoryName, categoryId) {
      const searchable = (String(name || '') + ' ' + String(categoryName || '') + ' ' + String(categoryId || ''))
        .toLowerCase()
        .replace(/[\u064B-\u065F\u0670]/g, '')
        .replace(/[أإآ]/g, 'ا')
        .replace(/ة/g, 'ه')
        .replace(/ى/g, 'ي')
        .replace(/\b(hd|4k|fhd|sd|uhd|hevc|h265|\d{2}fps)\b/gi, ' ')
        .replace(/[^a-z0-9\u0600-\u06FF]+/g, ' ')
        .trim();
      const bein = [
        'bein sports','bein sport','beinsports','beinsport',
        'be in sports','be in sport','بي ان سبورت','بي ان','بين سبورت',
        'صوتيات بي ان','راديو بي ان','تعليق بي ان','صوتيات بين','تعليق بين'
      ].some(term => searchable.includes(term));
      if (bein) return 'BEIN_SPORTS';
      const alwan = ['alwan', 'al wan', 'الوان', 'صوتيات الوان', 'راديو الوان', 'تعليق الوان', 'قنوات الوان']
        .some(term => searchable.includes(term)) || /\b(alwan|al wan)\b/.test(searchable);
      if (alwan) return 'ALWAN';
      return '';
    }

    function renderAudioSourcePicker(filterText = '') {
      const container = document.getElementById('audio-channels-list');
      if (!container) return;
      container.replaceChildren();
      const query = String(filterText || '').trim().toLowerCase();

      let alwanStations = [];
      let beinStations = [];

      // 1. Try to load pre-discovered stations from native XtreamBridge repository
      if (window.XtreamBridge && typeof window.XtreamBridge.getDiscoveredRadioStations === 'function') {
        try {
          const raw = window.XtreamBridge.getDiscoveredRadioStations();
          const parsed = JSON.parse(raw || '{}');
          if (parsed && parsed.success) {
            alwanStations = Array.isArray(parsed.alwan) ? parsed.alwan : [];
            beinStations = Array.isArray(parsed.bein) ? parsed.bein : [];
          }
        } catch (e) {
          console.warn('Failed to parse discovered radio stations from native bridge', e);
        }
      }

      // 2. If native bridge returned no stations yet, discover dynamically from liveChannels
      if (!alwanStations.length && !beinStations.length && Array.isArray(liveChannels) && liveChannels.length > 0) {
        liveChannels.forEach(ch => {
          const cls = classifyAudioSource(ch.name, ch.categoryName, ch.category);
          if (cls === 'ALWAN') alwanStations.push(ch);
          else if (cls === 'BEIN_SPORTS') beinStations.push(ch);
        });
      }

      // Filter by current playing video (don't allow picking current video stream as external audio)
      const currentVideoId = videoPlaybackController && videoPlaybackController.currentItem ? String(videoPlaybackController.currentItem.id) : null;
      const filterItem = ch => {
        if (currentVideoId && String(ch.id) === currentVideoId) return false;
        if (!query) return true;
        const name = String(ch.name || '').toLowerCase();
        const cat = String(ch.categoryName || '').toLowerCase();
        return name.includes(query) || cat.includes(query);
      };

      const groups = [
        { name: 'ALWAN (ألوان)', key: 'alwan', items: alwanStations.filter(filterItem) },
        { name: 'beIN SPORTS (بي إن سبورت)', key: 'bein', items: beinStations.filter(filterItem) }
      ];

      let totalCount = 0;
      groups.forEach(group => {
        if (!group.items.length) return;
        const header = document.createElement('div');
        header.style.cssText = 'padding:10px 4px 6px;color:var(--accent-gold);font-weight:800;font-size:0.85rem;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid rgba(255,255,255,0.08);margin-bottom:6px;';
        const titleSpan = document.createElement('span');
        titleSpan.textContent = group.name;
        const countBadge = document.createElement('span');
        countBadge.style.cssText = 'background:rgba(218,165,32,0.18);color:var(--accent-gold);padding:2px 8px;border-radius:12px;font-size:0.75rem;';
        countBadge.textContent = group.items.length + ' محطة';
        header.append(titleSpan, countBadge);
        container.appendChild(header);

        group.items.forEach(ch => {
          const item = document.createElement('div');
          const isSelected = dualAudioEngine && dualAudioEngine.selectedSourceChannel && String(dualAudioEngine.selectedSourceChannel.id) === String(ch.id);
          const isPlaying = isSelected && dualAudioEngine && dualAudioEngine.isActive;
          item.className = 'audio-picker-item focusable' + (isSelected ? ' active' : '') + (isPlaying ? ' is-playing' : '');
          item.tabIndex = 0;
          item.dataset.channelId = String(ch.id);

          const leftBox = document.createElement('div');
          leftBox.style.cssText = 'display:flex;flex-direction:column;gap:2px;flex:1;min-width:0;';

          const nameSpan = document.createElement('span');
          nameSpan.style.cssText = 'font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
          nameSpan.textContent = (ch.number ? ch.number + '. ' : '') + String(ch.name || '');

          const catSpan = document.createElement('span');
          catSpan.style.cssText = 'font-size:0.72rem;color:var(--text-muted);';
          catSpan.textContent = ch.categoryName || (group.key === 'alwan' ? 'صوتيات ألوان' : 'صوتيات beIN');

          leftBox.append(nameSpan, catSpan);

          const statusBadge = document.createElement('span');
          statusBadge.style.cssText = 'font-size:0.75rem;font-weight:700;padding:4px 10px;border-radius:6px;white-space:nowrap;';
          if (isPlaying) {
            statusBadge.style.cssText += 'background:rgba(0,255,102,0.15);color:#00FF66;border:1px solid rgba(0,255,102,0.4);';
            statusBadge.textContent = '🔊 يعمل الآن';
          } else if (isSelected) {
            statusBadge.style.cssText += 'background:rgba(79,156,249,0.15);color:var(--focus-blue);border:1px solid rgba(79,156,249,0.4);';
            statusBadge.textContent = 'محدد';
          } else {
            statusBadge.style.cssText += 'background:rgba(255,255,255,0.06);color:var(--text-muted);';
            statusBadge.textContent = 'تشغيل';
          }

          item.append(leftBox, statusBadge);
          item.addEventListener('click', () => {
            dualAudioEngine.setSourceChannel(ch);
            renderAudioSourcePicker(query);
          });
          container.appendChild(item);
          totalCount++;
        });
      });

      if (!totalCount) {
        const empty = document.createElement('div');
        empty.className = 'audio-picker-item';
        empty.style.cssText = 'text-align:center;padding:24px 16px;color:var(--text-muted);';
        empty.textContent = query
          ? 'لا توجد محطات صوت مطابقة للبحث'
          : 'لا توجد مصادر صوت beIN SPORTS أو ALWAN في بيانات اشتراكك';
        container.appendChild(empty);
      }
    }
    function updateFavoriteButtons(itemId) {
      const isFav = isFavorite(itemId);
      const favLabel = document.getElementById('fav-btn-label');
      const previewFavorite = document.getElementById('btn-toggle-fav');
      const playbackFavorite = document.getElementById('btn-playback-fav');
      if (previewFavorite) previewFavorite.classList.toggle('active', isFav);
      if (playbackFavorite) playbackFavorite.classList.toggle('active', isFav);
      if (favLabel) favLabel.textContent = isFav ? 'في المفضلة' : 'إضافة للمفضلة';
    }

    function toggleFavorite(itemId) {
      if (!itemId) return;
      const key = favoriteKey(itemId);
      if (memoryFavorites.has(key)) {
        memoryFavorites.delete(key);
        showToast('تمت الإزالة من المفضلة');
      } else {
        memoryFavorites.add(key);
        showToast('تمت الإضافة إلى المفضلة');
      }
      saveFavorites();
      updateFavoriteButtons(itemId);
      renderCategories();
      renderItems();
    }

    /**
     * =========================================================================
     * INITIALIZATION & LIFECYCLE
     * =========================================================================
     */
    let videoPlaybackController;
    let dualAudioEngine;
    let remoteNavigator;

    window.onNativePlaybackBuffering = function(message) { if (videoPlaybackController) videoPlaybackController.showLoader(message || 'جارٍ تخزين البث مؤقتاً...'); };
    window.onNativePlaybackReady = function() {
      if (videoPlaybackController) {
        videoPlaybackController.hideLoader();
        if (window.SonixSubtitles && window.SonixSubtitles.applyStyle) window.SonixSubtitles.applyStyle();
        const panel=document.querySelector('.live-player-col'); if(panel) panel.classList.add('has-active-playback');
      }
    };
    window.onNativePlaybackStateChanged = function(isPlaying) { if (videoPlaybackController) videoPlaybackController.updatePlaybackState(!!isPlaying); };
    window.onNativePlaybackProgress = function(positionMs, durationMs, seekable) {
      if (videoPlaybackController) videoPlaybackController.updateTimeline(positionMs, durationMs, !!seekable);
    };
    window.onNativePlaybackEnded = function() { if (videoPlaybackController) videoPlaybackController.updatePlaybackState(false); };
    window.onNativePlaybackError = function(message) {
      const panel=document.querySelector('.live-player-col'); if(panel) panel.classList.remove('has-active-playback');
      if (videoPlaybackController) videoPlaybackController.showError(message || 'فشل تشغيل البث');
    };

    document.addEventListener('DOMContentLoaded', () => {
      const syncNativeTextFocus = () => {
        const active = document.activeElement;
        const editing = !!(active &&
          (active.matches('input, textarea, select') || active.isContentEditable));
        if (window.AndroidBridge && typeof window.AndroidBridge.setTextInputFocused === 'function') {
          window.AndroidBridge.setTextInputFocused(editing);
        }
      };
      document.addEventListener('focusin', syncNativeTextFocus, true);
      document.addEventListener('focusout', () => window.setTimeout(syncNativeTextFocus, 0), true);

      let lastFocusedLoginInput = document.getElementById('login-server');
      ['login-server','login-username','login-password'].forEach(id => {
        const input = document.getElementById(id);
        if (input) {
          input.addEventListener('focus', () => { lastFocusedLoginInput = input; });
        }
      });

      document.querySelectorAll('.tv-quick-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
          e.preventDefault();
          const target = lastFocusedLoginInput || document.getElementById('login-server');
          if (!target) return;
          const insertText = btn.dataset.insert;
          const action = btn.dataset.action;
          if (action === 'clear') {
            target.value = '';
          } else if (insertText) {
            target.value = (target.value || '') + insertText;
          }
          target.dispatchEvent(new Event('input', { bubbles: true }));
          if (remoteNavigator) remoteNavigator.focusElement(target);
        });
      });

      document.getElementById('login-submit').addEventListener('click', () => performXtreamLogin(
        document.getElementById('login-server').value,
        document.getElementById('login-username').value,
        document.getElementById('login-password').value, false));
      ['login-server','login-username','login-password'].forEach(id => document.getElementById(id).addEventListener('keydown',e => {
        if(e.key === 'Enter') {
          e.preventDefault();
          e.stopPropagation();
          document.getElementById('login-submit').click();
        }
      }));

      // Load persisted preferences before the playback controller is created.
      initializeSonixSettingsControls();

      // 1. Initialize Video & Audio Engines
      videoPlaybackController = new VideoPlaybackController();
      dualAudioEngine = new DualAudioSystem(videoPlaybackController);
      remoteNavigator = new RemoteControlNavigator();

      // 2. Setup Clock
      updateLiveClock();
      setInterval(updateLiveClock, 30000);

      // 3. Render initial content
      renderCategories();
      renderItems();
      renderAudioSourcePicker();

      // 4. Bind Home Screen Action Cards
      document.getElementById('card-channels').addEventListener('click', () => openCatalogSection('live'));
      document.getElementById('card-movies').addEventListener('click', () => openCatalogSection('movies'));
      document.getElementById('card-series').addEventListener('click', () => openCatalogSection('series'));
      document.querySelectorAll('[data-catalog-retry]').forEach(button => {
        button.addEventListener('click', event => {
          event.preventDefault(); event.stopPropagation();
          startCatalogSync(button.dataset.catalogRetry);
        });
      });

      // 5. Explicit Back Button in Top Bar (Returns to Home!)
      document.getElementById('live-btn-back').addEventListener('click', () => {
        switchView('view-home');
      });

      // 6. Sidebar Mode Navigation (Unified 4-column layout)
      document.getElementById('side-nav-live').addEventListener('click', () => setAppMode('live'));
      document.getElementById('side-nav-movies').addEventListener('click', () => setAppMode('movies'));
      document.getElementById('side-nav-series').addEventListener('click', () => setAppMode('series'));
      document.getElementById('side-nav-history').addEventListener('click', () => openModal('modal-epg'));
      document.getElementById('side-nav-settings').addEventListener('click', () => openModal('modal-settings'));

      // 7. Home Top Bar Buttons
      document.getElementById('btn-power').addEventListener('click', () => openModal('modal-power'));
      document.getElementById('btn-refresh').addEventListener('click', () => refreshXtreamContent());
      document.getElementById('btn-profile').addEventListener('click', () => openModal('modal-profile'));
      document.getElementById('btn-settings').addEventListener('click', () => openModal('modal-settings'));
      document.getElementById('settings-refresh').addEventListener('click', () => refreshXtreamContent());
      document.getElementById('settings-account').addEventListener('click', () => {
        closeAllModals();
        openModal('modal-profile');
      });

      document.getElementById('btn-save-iptv').addEventListener('click', () => {
        const server=document.getElementById('iptv-server-url').value;
        const username=document.getElementById('iptv-username').value;
        const password=document.getElementById('iptv-password').value;
        document.getElementById('login-server').value=server;
        document.getElementById('login-username').value=username;
        document.getElementById('login-password').value=password;
        closeAllModals();
        document.getElementById('sonix-login-screen').classList.remove('hidden');
        const firstLoginField = document.getElementById('login-server');
        if (firstLoginField && remoteNavigator) remoteNavigator.focusElement(firstLoginField);
        performXtreamLogin(server,username,password,false);
      });
      document.getElementById('btn-search').addEventListener('click', () => openModal('modal-search'));

      // 8. Player Actions
      document.getElementById('btn-open-epg').addEventListener('click', () => {
        if (currentAppMode === 'live') {
          if (currentSelectedItem) loadLiveEpg(currentSelectedItem);
          openModal('modal-epg');
        } else {
          showToast(`جارٍ تشغيل ${currentSelectedItem ? currentSelectedItem.name : ''}...`);
        }
      });
      document.getElementById('btn-open-ext-audio').addEventListener('click', () => {
        renderAudioSourcePicker();
        openModal('modal-ext-audio');
      });
      document.getElementById('btn-toggle-fav').addEventListener('click', () => {
        if (currentSelectedItem) toggleFavorite(currentSelectedItem.id);
      });


      // 9. Modals Close Handlers
      document.getElementById('close-ext-audio').addEventListener('click', closeAllModals);
      document.getElementById('btn-ext-audio-done').addEventListener('click', closeAllModals);
      document.getElementById('close-epg').addEventListener('click', closeAllModals);
      document.getElementById('close-epg-footer').addEventListener('click', closeAllModals);
      document.getElementById('close-profile').addEventListener('click', closeAllModals);
      document.getElementById('close-profile-footer').addEventListener('click', closeAllModals);
      document.getElementById('close-settings').addEventListener('click', closeAllModals);
      document.getElementById('close-settings-footer').addEventListener('click', closeAllModals);
      document.getElementById('close-search').addEventListener('click', closeAllModals);
      document.getElementById('close-search-footer').addEventListener('click', closeAllModals);
      document.getElementById('close-power').addEventListener('click', closeAllModals);
      document.getElementById('btn-cancel-exit').addEventListener('click', closeAllModals);
      document.getElementById('btn-confirm-exit').addEventListener('click', () => {
        closeAllModals();
        videoPlaybackController.stopAndRelease();
        if (dualAudioEngine) dualAudioEngine.releaseAll();
        showToast('تم إغلاق الجلسة بنجاح');
        if (window.AndroidBridge && typeof window.AndroidBridge.closeApp === 'function') {
          window.AndroidBridge.closeApp();
        }
      });

      try {
        const saved = parseNative(window.XtreamBridge.savedCredentials());
        document.getElementById('login-server').value = saved.server || '';
        document.getElementById('login-username').value = saved.username || '';
        activeCredentials = {server:saved.server, username:saved.username};
        updateAccount({username:saved.username});
        document.getElementById('sonix-login-screen').classList.add('hidden');
        prepareCatalogHome();
      } catch (_) {
        activeCredentials = null;
        document.getElementById('sonix-login-screen').classList.remove('hidden');
        if (!window.isSplashActive || !window.isSplashActive()) {
          const firstLoginField = document.getElementById('login-server');
          if (firstLoginField && remoteNavigator) remoteNavigator.focusElement(firstLoginField);
        }
      }

      // Re-check at short intervals so each cached section refreshes once it reaches 24 hours.
      setInterval(checkCatalogRefreshDue, 60000);

      // 10. Search Filters
      document.getElementById('cat-filter-input').addEventListener('input', (e) => {
        const val = e.target.value.trim().toLowerCase();
        document.querySelectorAll('.category-item').forEach(item => {
          const text = item.textContent.toLowerCase();
          item.style.display = text.includes(val) ? 'flex' : 'none';
        });
      });

      document.getElementById('live-channel-search').addEventListener('input', (e) => {
        renderItems(e.target.value.trim());
      });

      const channelsHeader = document.querySelector('.channels-header-badge');
      if (channelsHeader) {
        channelsHeader.addEventListener('click', () => {
          if (window.innerWidth <= 600) {
            const catCol = document.querySelector('.live-categories-col');
            if (catCol) catCol.scrollIntoView({ behavior: 'smooth', inline: 'start' });
          }
        });
      }

      document.getElementById('audio-src-filter').addEventListener('input', (e) => {
        renderAudioSourcePicker(e.target.value.trim());
      });

      // 11. Global Search
      document.getElementById('global-search-input').addEventListener('input', (e) => {
        const query = e.target.value.trim().toLowerCase();
        const resultsContainer = document.getElementById('global-search-results');
        resultsContainer.textContent = '';

        if (!query) {
          const msg = document.createElement('div');
          msg.style.cssText = 'text-align:center; color:var(--text-muted); padding:20px; font-size:0.85rem;';
          msg.textContent = 'ابدأ بالكتابة للبحث الفوري...';
          resultsContainer.appendChild(msg);
          return;
        }

        const matchedChannels = liveChannels.filter(c => (c.name || '').toLowerCase().includes(query));
        const matchedMovies = movies.filter(m => (m.name || '').toLowerCase().includes(query));
        const matchedSeries = series.filter(s => (s.name || '').toLowerCase().includes(query));

        if (!matchedChannels.length && !matchedMovies.length && !matchedSeries.length) {
          const empty = document.createElement('div');
          empty.style.cssText = 'text-align:center; color:var(--text-muted); padding:20px;';
          empty.textContent = 'لم يتم العثور على أي نتائج';
          resultsContainer.appendChild(empty);
          return;
        }

        const createSearchCard = (item, type, icon, badgeText, badgeColor) => {
          const card = document.createElement('div');
          card.className = 'audio-picker-item focusable';
          card.style.background = '#0A0F14';
          card.tabIndex = 0;

          const titleSpan = document.createElement('span');
          titleSpan.style.cssText = 'color:#fff; font-weight:700;';
          titleSpan.textContent = icon + ' ' + (item.name || '');

          const badgeSpan = document.createElement('span');
          badgeSpan.style.cssText = 'color:' + badgeColor + '; font-size:0.75rem;';
          badgeSpan.textContent = badgeText;

          card.append(titleSpan, badgeSpan);

          card.addEventListener('click', () => {
            closeAllModals();
            switchView('view-live');
            setAppMode(type);
            selectItem(item);
          });
          return card;
        };

        matchedChannels.forEach(c => {
          resultsContainer.appendChild(createSearchCard(c, 'live', '📺', 'قناة مباشرة', 'var(--focus-blue)'));
        });
        matchedMovies.forEach(m => {
          const ratingText = m.rating ? ' ★ ' + m.rating : '';
          resultsContainer.appendChild(createSearchCard(m, 'movies', '🎬', 'فيلم' + ratingText, 'var(--accent-gold)'));
        });
        matchedSeries.forEach(s => {
          const ratingText = s.rating ? ' ★ ' + s.rating : '';
          resultsContainer.appendChild(createSearchCard(s, 'series', '🍿', 'مسلسل' + ratingText, 'var(--focus-blue)'));
        });
      });

      // 12. Focus Channels Card Initially
      if (!window.isSplashActive || !window.isSplashActive()) {
        const initialFocus = document.getElementById('sonix-login-screen').classList.contains('hidden')
          ? document.getElementById('card-movies')
          : document.getElementById('login-server');
        if (initialFocus && remoteNavigator) {
          remoteNavigator.focusElement(initialFocus);
        }
      }

      // Initialize the cinematic splash screen
      initSplashScreen();
    });