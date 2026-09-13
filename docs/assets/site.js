// Shared by every Feurstagram page: loads the release list once (cached for the
// session), fills in the total download count and points download buttons at the
// latest APKs. Pages work without it: counts stay hidden, buttons keep their
// "latest release" link.
(function () {
    var API = 'https://api.github.com/repos/jean-voila/FeurStagram/releases?per_page=100';
    var CACHE_KEY = 'feurstagram-releases-v1';
    var CACHE_MS = 10 * 60 * 1000;

    function readCache() {
        try {
            var cached = JSON.parse(sessionStorage.getItem(CACHE_KEY));
            if (cached && Date.now() - cached.time < CACHE_MS) return cached.releases;
        } catch (e) { }
        return null;
    }

    function writeCache(releases) {
        try { sessionStorage.setItem(CACHE_KEY, JSON.stringify({ time: Date.now(), releases: releases })); } catch (e) { }
    }

    function fetchPage(page, all) {
        return fetch(API + '&page=' + page, { headers: { Accept: 'application/vnd.github+json' } })
            .then(function (response) {
                if (!response.ok) throw new Error('GitHub API ' + response.status);
                return response.json();
            })
            .then(function (list) {
                list.forEach(function (release) {
                    all.push({
                        tag: release.tag_name,
                        draft: release.draft,
                        prerelease: release.prerelease,
                        publishedAt: release.published_at,
                        assets: (release.assets || []).map(function (asset) {
                            return { name: asset.name, url: asset.browser_download_url, downloads: asset.download_count || 0 };
                        })
                    });
                });
                return list.length === 100 ? fetchPage(page + 1, all) : all;
            });
    }

    function loadReleases() {
        var cached = readCache();
        if (cached) return Promise.resolve(cached);
        return fetchPage(1, []).then(function (releases) {
            writeCache(releases);
            return releases;
        });
    }

    function summarize(releases) {
        var total = 0;
        releases.forEach(function (release) {
            release.assets.forEach(function (asset) { total += asset.downloads; });
        });
        var latest = releases.filter(function (r) { return !r.draft && !r.prerelease; })
            .sort(function (a, b) { return new Date(b.publishedAt) - new Date(a.publishedAt); })[0] || null;
        return { total: total, latest: latest };
    }

    function isClone(name) { return /(^|[-_.])clone([-_.]|$)/i.test(name); }
    function isApk(name) { return /\.apkm?$/i.test(name); }

    function apply(summary) {
        var lang = document.documentElement.lang || 'en';
        var formatted = new Intl.NumberFormat(lang).format(summary.total);
        document.querySelectorAll('[data-total-downloads]').forEach(function (el) { el.textContent = formatted; });
        document.querySelectorAll('[data-downloads-wrap]').forEach(function (el) { el.hidden = false; });

        if (!summary.latest) return;
        var apks = summary.latest.assets.filter(function (a) { return isApk(a.name); });
        var standard = apks.find(function (a) { return !isClone(a.name); });
        var clone = apks.find(function (a) { return isClone(a.name); });
        document.querySelectorAll('[data-apk="standard"]').forEach(function (el) { if (standard) el.href = standard.url; });
        document.querySelectorAll('[data-apk="clone"]').forEach(function (el) { if (clone) el.href = clone.url; });
        document.querySelectorAll('[data-latest-tag]').forEach(function (el) { el.textContent = summary.latest.tag; });
    }

    window.feurstagramReleases = loadReleases().then(function (releases) {
        var summary = summarize(releases);
        apply(summary);
        return summary;
    });
    window.feurstagramReleases.catch(function () { /* offline or rate-limited: leave the static fallbacks */ });
})();
