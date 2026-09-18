// The console's one script: the theme, sortable tables, and the basemaps every map draws.
(function () {
    'use strict';

    // ---- theme: dark by default, light or the system on request, remembered per browser
    var root = document.documentElement;
    var system = window.matchMedia('(prefers-color-scheme: dark)');
    function apply(choice) {
        var dark = choice === 'dark' || (choice === 'auto' && system.matches);
        root.setAttribute('data-bs-theme', dark ? 'dark' : 'light');
        root.setAttribute('data-theme-choice', choice);
        document.dispatchEvent(new CustomEvent('gully:theme', {detail: {dark: dark}}));
    }
    document.addEventListener('DOMContentLoaded', function () {
        var select = document.getElementById('theme');
        if (select) {
            select.value = root.getAttribute('data-theme-choice') || 'dark';
            select.addEventListener('change', function () {
                try { localStorage.setItem('gully.theme', select.value); } catch (e) {}
                apply(select.value);
            });
        }
        document.querySelectorAll('table.sortable').forEach(sortable);
    });
    system.addEventListener('change', function () {
        if ((root.getAttribute('data-theme-choice') || 'dark') === 'auto') apply('auto');
    });

    // ---- sortable tables: click a header, numbers as numbers, blanks last
    function sortable(table) {
        var heads = table.querySelectorAll('thead th');
        heads.forEach(function (th, index) {
            th.addEventListener('click', function () {
                var asc = !th.classList.contains('asc');
                heads.forEach(function (h) { h.classList.remove('asc', 'desc'); });
                th.classList.add(asc ? 'asc' : 'desc');
                var body = table.tBodies[0];
                var rows = Array.prototype.slice.call(body.rows);
                rows.sort(function (a, b) {
                    var x = key(a.cells[index]), y = key(b.cells[index]);
                    if (x === null && y === null) return 0;
                    if (x === null) return 1;
                    if (y === null) return -1;
                    if (typeof x === 'number' && typeof y === 'number') return asc ? x - y : y - x;
                    return asc ? String(x).localeCompare(String(y)) : String(y).localeCompare(String(x));
                });
                rows.forEach(function (r) { body.appendChild(r); });
            });
        });
    }
    function key(cell) {
        if (!cell) return null;
        var v = cell.getAttribute('data-sort');
        if (v === null) v = cell.textContent.trim();
        if (v === '' || v === '—') return null;
        var n = Number(v);
        return isNaN(n) ? v : n;
    }

    // ---- basemaps: CARTO light/dark while a key is present, OpenStreetMap otherwise
    window.gullyDark = function () { return root.getAttribute('data-bs-theme') === 'dark'; };
    var cartoKey = (document.querySelector('meta[name="carto-key"]') || {}).content || '';
    function cartoUrl(dark) {
        return 'https://basemaps.cartocdn.com/rastertiles/' + (dark ? 'dark_all' : 'light_all') + '/{z}/{x}/{y}{r}.png?key=' + encodeURIComponent(cartoKey);
    }
    window.gullyBaseLayer = function (map) {
        var bases = {};
        if (cartoKey) {
            bases['CARTO'] = L.tileLayer(cartoUrl(window.gullyDark()), {attribution: '&copy; OpenStreetMap &copy; CARTO', maxZoom: 20, noInvert: true});
        }
        bases['OpenStreetMap'] = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {attribution: '&copy; OpenStreetMap contributors', maxZoom: 19});
        bases['OpenTopoMap'] = L.tileLayer('https://tile.opentopomap.org/{z}/{x}/{y}.png', {attribution: '&copy; OpenStreetMap, SRTM · OpenTopoMap (CC-BY-SA)', maxZoom: 17, noInvert: true});
        bases['Esri imagery'] = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {attribution: 'Tiles &copy; Esri', maxZoom: 19, noInvert: true});
        var chosen = cartoKey ? 'CARTO' : 'OpenStreetMap';
        try { chosen = localStorage.getItem('gully.map.base') || chosen; } catch (e) {}
        if (!bases[chosen]) chosen = cartoKey ? 'CARTO' : 'OpenStreetMap';
        var mark = function (layer) { map.getContainer().classList.toggle('no-invert', !!(layer && layer.options.noInvert)); };
        var tiles = bases[chosen].addTo(map);
        mark(tiles);
        L.control.layers(bases, null, {position: 'topleft'}).addTo(map);
        if (bases['CARTO']) document.addEventListener('gully:theme', function (e) { bases['CARTO'].setUrl(cartoUrl(e.detail.dark)); });
        map.on('baselayerchange', function (e) {
            mark(e.layer);
            try { localStorage.setItem('gully.map.base', e.name); } catch (err) {}
        });
        if (typeof ResizeObserver !== 'undefined') {
            new ResizeObserver(function () { map.invalidateSize({animate: false}); }).observe(map.getContainer());
        }
        return tiles;
    };
    L.Map.mergeOptions({zoomAnimation: false, fadeAnimation: false, markerZoomAnimation: false});

    window.gullyCsrf = function () {
        var t = document.querySelector('meta[name="_csrf"]'), h = document.querySelector('meta[name="_csrf_header"]');
        var out = {};
        if (t && h && t.content) out[h.content] = t.content;
        return out;
    };
})();
