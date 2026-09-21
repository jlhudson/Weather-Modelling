// The map: South Australia's Bureau stations, each drawn where it is, coloured by what it last said,
// and one question at a time - click a station for everything held for it. Deferred, so it runs
// after Leaflet and console.js.
(function () {
    'use strict';
    var $ = function (id) { return document.getElementById(id); };
    var esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]; }); };
    var fmt = function (v, d) { return v == null ? '—' : (typeof v === 'number' ? (d == null ? v : v.toFixed(d)) : String(v)); };
    var when = function (iso) { if (!iso) return '—'; var d = new Date(iso); return isNaN(d) ? String(iso) : d.toLocaleString(undefined, {weekday: 'short', day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit'}); };
    var clock = function (iso) { if (!iso) return '—'; var d = new Date(iso); return isNaN(d) ? String(iso) : d.toLocaleTimeString(undefined, {hour: '2-digit', minute: '2-digit'}); };
    var ago = function (iso) { if (!iso) return '—'; var m = Math.round((Date.now() - Date.parse(iso)) / 60000); return m < 1 ? 'just now' : m < 120 ? m + ' min ago' : m < 2880 ? Math.round(m / 60) + ' h ago' : Math.round(m / 1440) + ' d ago'; };
    var icon = function (id) { return '<svg class="ico"><use href="#i-' + id + '"/></svg>'; };

    // ---- the catalogue: what a station can be coloured by, each a property of the feed with a range.
    var VARS = [
        {id: 'temperatureC', name: 'Temperature', unit: '°C', icon: 'temp', range: [0, 45], d: 1},
        {id: 'humidityPct', name: 'Humidity', unit: '%', icon: 'humidity', range: [0, 100], reverse: true},
        {id: 'windSpeedKmh', name: 'Wind', unit: 'km/h', icon: 'wind', range: [0, 80]},
        {id: 'windGustKmh', name: 'Gust', unit: 'km/h', icon: 'gust', range: [0, 110]},
        {id: 'rainSince9amMm', name: 'Rain', hint: 'since 9 am', unit: 'mm', icon: 'rain', range: [0, 25], d: 1},
        {id: 'heightM', name: 'Height', hint: 'of the station', unit: 'm', icon: 'elevation', range: [0, 800], always: true},
        {id: 'ageMinutes', name: 'Age', hint: 'of the observation', unit: 'min', icon: 'clock', range: [0, 120], reverse: true}
    ];
    var GROUND = '#3b82f6', NONE = '#6b7280';
    function ramp(t) {
        t = Math.max(0, Math.min(1, t));
        var stops = [[0, [33, 102, 172]], [.5, [247, 247, 190]], [1, [178, 24, 43]]];
        for (var i = 1; i < stops.length; i++) {
            if (t <= stops[i][0]) {
                var f = (t - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0]), a = stops[i - 1][1], b = stops[i][1];
                return 'rgb(' + Math.round(a[0] + (b[0] - a[0]) * f) + ',' + Math.round(a[1] + (b[1] - a[1]) * f) + ',' + Math.round(a[2] + (b[2] - a[2]) * f) + ')';
            }
        }
        return 'rgb(178,24,43)';
    }

    // ---- state
    var map = L.map('map', {zoomControl: false}).setView([-34.6, 137.6], 7);
    L.control.zoom({position: 'bottomright'}).addTo(map);
    window.gullyBaseLayer(map);
    var canvas = L.canvas({padding: .3});
    var stationLayer = L.layerGroup().addTo(map);
    var labelLayer = L.layerGroup().addTo(map);
    var state = {id: 'temperatureC', selected: null};
    var togs = {labels: true};
    var lastStations = null;

    function current() {
        for (var i = 0; i < VARS.length; i++) if (VARS[i].id === state.id) return VARS[i];
        return VARS[0];
    }
    // The value a station is coloured by: what it last said, if it is fresh; a station's own facts whenever.
    function valueOf(p, v) {
        v = v || current();
        if (!v.always && !p.fresh) return null;
        return p[v.id];
    }
    function colourOf(p, v) {
        v = v || current();
        var x = valueOf(p, v);
        if (x == null) return NONE;
        var t = (x - v.range[0]) / (v.range[1] - v.range[0]);
        return ramp(v.reverse ? 1 - t : t);
    }

    // ---- the side panel
    function buildSide() {
        var chips = $('varChips');
        chips.innerHTML = '';
        VARS.forEach(function (x) {
            var b = document.createElement('button');
            b.type = 'button';
            b.className = 'chip' + (x.id === state.id ? ' on' : '');
            b.innerHTML = icon(x.icon) + '<span>' + esc(x.name) + (x.hint ? '<small>' + esc(x.hint) + '</small>' : '') + '</span>';
            b.title = x.name + (x.unit ? ' in ' + x.unit : '');
            b.addEventListener('click', function () { state.id = x.id; buildSide(); stations(); legend(); });
            chips.appendChild(b);
        });
        Object.keys(togs).forEach(function (k) { var b = document.querySelector('.tog[data-tog=' + k + ']'); if (b) b.classList.toggle('on', togs[k]); });
    }
    function legend() {
        var v = current(), props = lastStations ? lastStations.features.map(function (f) { return f.properties; }) : [];
        var vals = props.map(function (p) { return valueOf(p, v); }).filter(function (x) { return x != null; });
        $('legendTitle').textContent = v.name + (v.unit ? ' · ' + v.unit : '');
        $('legendCount').textContent = vals.length + ' of ' + props.length + ' stations';
        var lo = v.range[0], hi = v.range[1];
        var mean = vals.length ? vals.reduce(function (a, b) { return a + b; }, 0) / vals.length : null;
        var min = vals.length ? Math.min.apply(null, vals) : null, max = vals.length ? Math.max.apply(null, vals) : null;
        $('legendBody').innerHTML = '<div class="ramp" style="background:linear-gradient(to right,' + [0, .25, .5, .75, 1].map(function (t) { return ramp(v.reverse ? 1 - t : t); }).join(',') + ')"></div>'
            + '<div class="ramp-labels"><span>' + lo + '</span><span>' + ((lo + hi) / 2) + '</span><span>' + hi + '</span></div>'
            + '<div class="ramp-labels"><span>min ' + fmt(min, v.d || 0) + '</span><span>mean ' + fmt(mean, v.d || 1) + '</span><span>max ' + fmt(max, v.d || 0) + '</span></div>';
    }
    function tiles() {
        var props = lastStations ? lastStations.features.map(function (f) { return f.properties; }) : [];
        var fresh = props.filter(function (p) { return p.fresh; });
        var t = [
            {v: props.length, k: 'stations'},
            {v: fresh.length, k: 'reporting', cls: 'ground'},
            {v: lastStations && lastStations.updatedAt ? ago(lastStations.updatedAt) : '—', k: 'file read'},
            {v: fresh.length ? fmt(fresh.reduce(function (a, p) { return a + (p.temperatureC || 0); }, 0) / fresh.filter(function (p) { return p.temperatureC != null; }).length, 1) + ' °C' : '—', k: 'mean temperature'}
        ];
        $('tiles').innerHTML = t.map(function (x) { return '<div class="tile ' + (x.cls || '') + '"><div class="v">' + esc(x.v) + '</div><div class="k">' + esc(x.k) + '</div></div>'; }).join('');
    }

    // ---- the stations
    function load() {
        fetch('/console/map/stations.geojson').then(function (r) { return r.json(); }).then(function (fc) {
            lastStations = fc;
            stations();
            legend();
            tiles();
        }).catch(function (e) { note('stations failed: ' + e); });
    }
    function dirWord(deg) { return deg == null ? '—' : ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'][Math.round(deg / 22.5) % 16]; }
    function windWords(deg, kmh, gust) { return deg == null && kmh == null ? '—' : (deg != null ? dirWord(deg) + ' ' + deg + '°' : '—') + ' ' + (kmh != null ? Math.round(kmh) : '—') + ' km/h' + (gust != null ? ' <span class="muted">gust ' + Math.round(gust) + '</span>' : ''); }
    function tip(p) {
        return '<b>' + esc(p.name) + '</b> <span class="muted">' + esc(p.id) + (p.heightM != null ? ' · ' + p.heightM + ' m' : '') + '</span><br>'
            + (p.fresh ? esc(fmt(p.temperatureC, 1)) + ' °C · ' + esc(fmt(p.humidityPct)) + ' % · ' + windWords(p.windDirectionDeg, p.windSpeedKmh, p.windGustKmh) + (p.rainSince9amMm != null ? ' · ' + p.rainSince9amMm + ' mm since 9 am' : '') + '<br><span class="muted">' + when(p.at) + '</span>'
                : '<span class="muted">' + (p.at ? 'last reported ' + ago(p.at) : 'nothing reported yet') + '</span>');
    }
    function stations() {
        stationLayer.clearLayers();
        labelLayer.clearLayers();
        if (!lastStations) return;
        var v = current(), z = map.getZoom(), r = Math.max(2.5, Math.min(5, z * .6));
        lastStations.features.forEach(function (f) {
            var p = f.properties, ll = [f.geometry.coordinates[1], f.geometry.coordinates[0]], c = colourOf(p, v);
            var on = state.selected === p.id;
            if (p.fresh) L.circleMarker(ll, {renderer: canvas, radius: r * 2.2, color: c, weight: 0, fillColor: c, fillOpacity: .18, interactive: false}).addTo(stationLayer);
            L.circleMarker(ll, {renderer: canvas, radius: on ? r * 1.4 : r, color: on ? '#22d3ee' : c, weight: on ? 2 : p.fresh ? 1 : 1.2, opacity: p.fresh ? 1 : .7, fillColor: c, fillOpacity: p.fresh ? .95 : 0})
                .bindTooltip(function () { return tip(p); }, {sticky: true, className: 'hx-tip'})
                .on('click', function (e) { L.DomEvent.stopPropagation(e); detail(p.id); })
                .addTo(stationLayer);
            var x = valueOf(p, v);
            if (togs.labels && z >= 8 && x != null) {
                L.marker(ll, {icon: L.divIcon({className: 'st-glyph', html: '<span class="st-label">' + esc(fmt(x, v.d || 0)) + '</span>', iconSize: [0, 0], iconAnchor: [0, -r - 1]}), interactive: false, keyboard: false}).addTo(labelLayer);
            }
        });
    }

    // ---- the drawer: everything held for one station
    function kv(rows) {
        var s = '<table class="table table-sm kv mb-1">';
        rows.forEach(function (r) { if (r[1] != null && r[1] !== '' && r[1] !== '—') s += '<tr><th>' + esc(r[0]) + '</th><td class="mono">' + r[1] + '</td></tr>'; });
        return s + '</table>';
    }
    function detail(id) {
        state.selected = id;
        stations();
        fetch('/console/map/station/' + encodeURIComponent(id)).then(function (r) { return r.ok ? r.json() : null; }).then(function (s) {
            var el = $('detail');
            if (!s) { el.innerHTML = '<div class="drawer-head"><h3>' + esc(id) + '</h3><button class="icon-btn" id="close" type="button">' + icon('close') + '</button></div><p class="muted">not held</p>'; el.classList.remove('hidden'); $('close').addEventListener('click', closeDetail); return; }
            var html = '<div class="drawer-head"><h3>' + esc(s.name) + ' <span class="muted">' + esc(s.id) + (s.wmoId ? ' · WMO ' + esc(s.wmoId) : '') + '</span></h3><button class="icon-btn" id="close" title="Close (Esc)" type="button">' + icon('close') + '</button></div>';
            html += '<h2>Station</h2>' + kv([
                ['position', fmt(s.lat, 4) + ', ' + fmt(s.lon, 4)],
                ['height', s.heightM != null ? s.heightM + ' m' : null],
                ['district', esc(s.district)],
                ['zone', esc(s.zone)]
            ]);
            html += '<h2>Latest ' + (s.at ? '<span class="muted">' + esc(when(s.at)) + ' · ' + esc(ago(s.at)) + (s.fresh ? '' : ' · not reporting') + '</span>' : '') + '</h2>';
            if (s.at) {
                html += kv([
                    ['temperature', s.temperatureC != null ? fmt(s.temperatureC, 1) + ' °C' + (s.apparentTemperatureC != null ? ' <span class="muted">feels ' + fmt(s.apparentTemperatureC, 1) + '</span>' : '') : null],
                    ['dew point', s.dewPointC != null ? fmt(s.dewPointC, 1) + ' °C' : null],
                    ['humidity', s.humidityPct != null ? s.humidityPct + ' %' : null],
                    ['wind', windWords(s.windDirectionDeg, s.windSpeedKmh, s.windGustKmh)],
                    ['pressure', s.pressureMslHpa != null ? fmt(s.pressureMslHpa, 1) + ' hPa' : null],
                    ['rain since 9 am', s.rainSince9amMm != null ? fmt(s.rainSince9amMm, 1) + ' mm' : null],
                    ['rain to 9 am', s.rain24hMm != null ? fmt(s.rain24hMm, 1) + ' mm' : null],
                    ['today', (s.minTemperatureC != null || s.maxTemperatureC != null) ? 'min ' + fmt(s.minTemperatureC, 1) + ' · max ' + fmt(s.maxTemperatureC, 1) + ' °C' : null],
                    ['sky', esc(s.cloud)]
                ]);
            } else {
                html += '<p class="muted">Nothing reported since the start.</p>';
            }
            if (s.recent && s.recent.length > 1) {
                html += '<h2>Last readings <span class="muted">newest first, since the start</span></h2><table class="table table-sm recent"><thead><tr><th>at</th><th class="num">°C</th><th class="num">%</th><th class="num">km/h</th><th>from</th><th class="num">gust</th><th class="num">mm</th></tr></thead><tbody>';
                s.recent.forEach(function (x) { html += '<tr><td class="mono">' + clock(x.at) + '</td><td class="num">' + fmt(x.temperatureC, 1) + '</td><td class="num">' + fmt(x.humidityPct) + '</td><td class="num">' + fmt(x.windSpeedKmh) + '</td><td class="dir">' + (x.windDirectionDeg != null ? '<span class="arrow" style="transform:rotate(' + ((x.windDirectionDeg + 180) % 360) + 'deg)">↑</span> ' + x.windDirectionDeg + '°' : '—') + '</td><td class="num">' + fmt(x.windGustKmh) + '</td><td class="num">' + fmt(x.rainSince9amMm, 1) + '</td></tr>'; });
                html += '</tbody></table>';
            }
            el.innerHTML = html;
            el.classList.remove('hidden');
            $('close').addEventListener('click', closeDetail);
        }).catch(function (e) { note('station failed: ' + e); });
    }
    function closeDetail() { $('detail').classList.add('hidden'); state.selected = null; stations(); }

    // ---- the footer: read now, and live
    var noteTimer = null;
    function note(text) {
        var el = $('note');
        el.textContent = text; el.classList.remove('hidden');
        clearTimeout(noteTimer);
        noteTimer = setTimeout(function () { el.classList.add('hidden'); }, 3200);
    }
    function readNow() {
        var headers = window.gullyCsrf ? window.gullyCsrf() : {};
        $('readNow').disabled = true;
        fetch('/console/map/bureau/read', {method: 'POST', headers: headers}).then(function (r) { return r.json(); }).then(function (o) {
            $('readNow').disabled = false;
            note(o.downloaded ? 'read: ' + o.bureau.stationsInFile + ' stations in the file' : (o.bureau.failure ? 'failed: ' + o.bureau.failure : 'unchanged since ' + ago(o.bureau.readAt)));
            load();
        }).catch(function (e) { $('readNow').disabled = false; note('read failed: ' + e); });
    }
    var lastLoadAt = null;
    function liveTick() {
        var el = $('live');
        el.classList.toggle('paused', document.hidden);
        $('liveText').textContent = document.hidden ? 'paused' : (lastStations && lastStations.at ? 'live · ' + clock(lastStations.at) : 'live');
    }

    // ---- wiring
    document.querySelectorAll('.tog').forEach(function (b) {
        b.addEventListener('click', function () { var k = b.dataset.tog; togs[k] = !togs[k]; b.classList.toggle('on', togs[k]); stations(); });
    });
    $('readNow').addEventListener('click', readNow);
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') closeDetail();
    });
    map.on('zoomend', stations);
    map.on('click', closeDetail);
    document.addEventListener('gully:theme', stations);
    document.addEventListener('visibilitychange', function () { liveTick(); if (!document.hidden) load(); });

    buildSide();
    legend();
    tiles();
    load();
    setInterval(liveTick, 1000);
    setInterval(function () { if (!document.hidden) load(); }, 60000);
})();
