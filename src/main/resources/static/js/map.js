// The map: South Australia's Bureau stations, each drawn where it is, coloured by what it last said,
// and each with its reach - the ground it speaks for, a polygon drawn from the terrain around it by
// the rule on the sliders (W-2): the clicked station's in cyan, every station's at once on a toggle, each in
// its station's colour. A click anywhere asks for the reading there (W-8): the weather now and the drought
// blended from the stations in reach, or from a point of ours (W-7, an amber diamond) where none reaches.
// One question at a time: click a station for everything held for it, or click anywhere for the
// stations whose reach contains the point (W-5). Deferred, so it runs after Leaflet and console.js.
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
        {id: 'windSpeedKmh', name: 'Wind', hint: 'and its direction', unit: 'km/h', icon: 'wind', range: [0, 80], wind: true},
        {id: 'windGustKmh', name: 'Gust', hint: 'and the wind\'s direction', unit: 'km/h', icon: 'gust', range: [0, 110], wind: true},
        {id: 'rainSince9amMm', name: 'Rain', hint: 'since 9 am', unit: 'mm', icon: 'rain', range: [0, 25], d: 1},
        {id: 'pressureMslHpa', name: 'Pressure', hint: 'mean sea level', unit: 'hPa', icon: 'pressure', range: [990, 1040], d: 1},
        {id: 'kbdiMm', name: 'KBDI', hint: 'soil moisture deficit', unit: 'mm', icon: 'drought', range: [0, 203], always: true},
        {id: 'droughtFactor', name: 'Drought factor', hint: '0 to 10', icon: 'drought', range: [0, 10], always: true, d: 1},
        {id: 'heightM', name: 'Height', hint: 'of the station', unit: 'm', icon: 'elevation', range: [0, 800], always: true},
        {id: 'ageMinutes', name: 'Age', hint: 'of the observation', unit: 'min', icon: 'clock', range: [0, 120], reverse: true}
    ];
    var GROUND = '#3b82f6', NONE = '#6b7280', SEA = '#0ea5e9', MODEL = '#f59e0b';
    // A diamond of a size in pixels at a place, as a polygon in the map's own units: it keeps its size across zoom.
    function diamond(ll, px, style) {
        var c = map.latLngToLayerPoint(ll), pts = [[c.x, c.y - px], [c.x + px, c.y], [c.x, c.y + px], [c.x - px, c.y]].map(function (q) { return map.layerPointToLatLng(L.point(q[0], q[1])); });
        style.renderer = canvas;
        return L.polygon(pts, style);
    }
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
    // The reaches under the stations: every station's faintly when All reaches is on, the clicked one's over them.
    var allLayer = L.layerGroup().addTo(map);
    var reachLayer = L.layerGroup().addTo(map);
    var stationLayer = L.layerGroup().addTo(map);
    var labelLayer = L.layerGroup().addTo(map);
    var probeLayer = L.layerGroup().addTo(map);
    var state = {id: 'temperatureC', selected: null, probe: null};
    var togs = {labels: true, reach: true, all: false};
    var lastStations = null, lastReach = null;
    var REACH = getComputedStyle(document.querySelector('.map-page')).getPropertyValue('--p-reach').trim() || '#22d3ee';

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
            b.addEventListener('click', function () { state.id = x.id; buildSide(); stations(); legend(); if (togs.all) drawReach(); });
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
        var all = lastStations ? lastStations.features.map(function (f) { return f.properties; }) : [];
        var props = all.filter(function (p) { return p.kind !== 'point'; }), pts = all.filter(function (p) { return p.kind === 'point'; });
        var fresh = props.filter(function (p) { return p.fresh; });
        var reaches = lastReach ? lastReach.features.map(function (f) { return f.properties; }) : [];
        var t = [
            {v: props.length, k: 'stations'},
            {v: fresh.length, k: 'reporting', cls: 'ground'},
            {v: pts.length, k: 'points of ours', cls: 'model', t: "Places nobody's reach contained when asked, dropped as stations of our own: the model's current, a year of the archive, the same reach"},
            {v: lastStations && lastStations.updatedAt ? ago(lastStations.updatedAt) : '—', k: 'file read'},
            {v: fresh.length ? fmt(fresh.reduce(function (a, p) { return a + (p.temperatureC || 0); }, 0) / fresh.filter(function (p) { return p.temperatureC != null; }).length, 1) + ' °C' : '—', k: 'mean temperature'},
            {v: lastReach ? reaches.length + (reaches.length < all.length ? ' of ' + all.length : '') : '—', k: 'reaches drawn', cls: 'reach', t: 'Stations whose terrain has been sampled; the daily housekeeping samples the rest'},
            {v: reaches.length ? fmt(reaches.reduce(function (a, p) { return a + p.areaKm2; }, 0) / reaches.length, 0) + ' km²' : '—', k: 'mean reach area', cls: 'reach'}
        ];
        $('tiles').innerHTML = t.map(function (x) { return '<div class="tile ' + (x.cls || '') + '" title="' + esc(x.t || '') + '"><div class="v">' + esc(x.v) + '</div><div class="k">' + esc(x.k) + '</div></div>'; }).join('');
    }

    // ---- the stations
    function load() {
        fetch('/console/map/stations.geojson').then(function (r) { return r.json(); }).then(function (fc) {
            lastStations = fc;
            stations();
            legend();
            tiles();
            if (togs.all) drawReach();
        }).catch(function (e) { note('stations failed: ' + e); });
    }
    function dirWord(deg) { return deg == null ? '—' : ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'][Math.round(deg / 22.5) % 16]; }
    function windWords(deg, kmh, gust) { return deg == null && kmh == null ? '—' : (deg != null ? dirWord(deg) + ' ' + deg + '°' : '—') + ' ' + (kmh != null ? Math.round(kmh) : '—') + ' km/h' + (gust != null ? ' <span class="muted">gust ' + Math.round(gust) + '</span>' : ''); }
    function tip(p) {
        return '<b>' + esc(p.name) + '</b> <span class="muted">' + esc(p.id) + (p.kind === 'point' ? ' · a point of ours, from the model' : '') + (p.heightM != null ? ' · ' + Math.round(p.heightM) + ' m' : '') + '</span><br>'
            + (p.fresh ? esc(fmt(p.temperatureC, 1)) + ' °C · ' + esc(fmt(p.humidityPct)) + ' % · ' + windWords(p.windDirectionDeg, p.windSpeedKmh, p.windGustKmh) + (p.pressureMslHpa != null ? ' · ' + fmt(p.pressureMslHpa, 1) + ' hPa' : '') + (p.rainSince9amMm != null ? ' · ' + p.rainSince9amMm + ' mm since 9 am' : '') + windTrend(p) + '<br><span class="muted">' + when(p.at) + '</span>'
                : '<span class="muted">' + (p.at ? 'last reported ' + ago(p.at) : 'nothing reported yet') + '</span>');
    }
    function stations() {
        stationLayer.clearLayers();
        labelLayer.clearLayers();
        if (!lastStations) return;
        var v = current(), z = map.getZoom(), r = Math.max(2.5, Math.min(5, z * .6));
        lastStations.features.forEach(function (f) {
            var p = f.properties, ll = [f.geometry.coordinates[1], f.geometry.coordinates[0]], c = colourOf(p, v);
            var on = state.selected === p.id, rf = reachFeature(p.id);
            if (p.fresh) L.circleMarker(ll, {renderer: canvas, radius: r * 2.2, color: c, weight: 0, fillColor: c, fillOpacity: .18, interactive: false}).addTo(stationLayer);
            // A coastal station (W-3) wears a thin ring in the sea's blue; an island (W-12), the ring dashed.
            if (rf && rf.properties.coastal) L.circleMarker(ll, {renderer: canvas, radius: r * 1.9, color: SEA, weight: 1, opacity: .8, fill: false, interactive: false, dashArray: rf.properties.island ? '2 2' : null}).addTo(stationLayer);
            var mark;
            if (p.kind === 'point') {
                // A point of ours (W-7): a diamond in the model's amber, its fill the value, so it is never taken for a station.
                var d = on ? r * 1.6 : r * 1.25;
                mark = diamond(ll, d, {color: on ? REACH : MODEL, weight: on ? 2 : 1.3, opacity: p.fresh ? 1 : .7, fillColor: c, fillOpacity: p.fresh ? .95 : 0});
            } else {
                mark = L.circleMarker(ll, {renderer: canvas, radius: on ? r * 1.4 : r, color: on ? REACH : c, weight: on ? 2 : p.fresh ? 1 : 1.2, opacity: p.fresh ? 1 : .7, fillColor: c, fillOpacity: p.fresh ? .95 : 0});
            }
            mark.bindTooltip(function () { return tip(p); }, {sticky: true, className: 'hx-tip'})
                .on('click', function (e) { L.DomEvent.stopPropagation(e); detail(p.id); })
                .addTo(stationLayer);
            var x = valueOf(p, v);
            if (togs.labels && z >= 8 && x != null) {
                L.marker(ll, {icon: L.divIcon({className: 'st-glyph', html: '<span class="st-label">' + esc(fmt(x, v.d || 0)) + '</span>', iconSize: [0, 0], iconAnchor: [0, -r - 1]}), interactive: false, keyboard: false}).addTo(labelLayer);
            }
            // The wind (W-9, W-11): when the colour is the wind or the gust, every reporting station wears its direction - the
            // latest as a solid arrow the way it blows, its length the speed coloured by; from zoom 8 the mean of the last five behind it in grey.
            if (v.wind && p.fresh && x != null && p.windDirectionDeg != null) {
                L.marker(ll, {icon: L.divIcon({className: 'st-glyph', html: windGlyph(p, x, z >= 8), iconSize: [64, 64], iconAnchor: [32, 32]}), interactive: false, keyboard: false}).addTo(labelLayer);
            }
        });
    }
    // An arrow from the centre, its length the speed (capped), pointing where the wind goes: the direction is where it comes from.
    function arrow(deg, kmh, cls) {
        var len = 8 + Math.min(22, kmh * .45), a = (deg + 180 - 90) * Math.PI / 180, x = 32 + Math.cos(a) * len, y = 32 + Math.sin(a) * len;
        var hx = x - Math.cos(a) * 5, hy = y - Math.sin(a) * 5, px = Math.cos(a + Math.PI / 2) * 3, py = Math.sin(a + Math.PI / 2) * 3;
        return '<g class="' + cls + '"><line x1="32" y1="32" x2="' + x.toFixed(1) + '" y2="' + y.toFixed(1) + '"/><polyline points="' + (hx + px).toFixed(1) + ',' + (hy + py).toFixed(1) + ' ' + x.toFixed(1) + ',' + y.toFixed(1) + ' ' + (hx - px).toFixed(1) + ',' + (hy - py).toFixed(1) + '"/></g>';
    }
    function windGlyph(p, kmh, withMean) {
        var s = '<svg class="wind" viewBox="0 0 64 64" width="64" height="64">';
        if (withMean && p.windMeanDeg != null && p.windMeanKmh != null && p.windMeanOver > 1) s += arrow(p.windMeanDeg, p.windMeanKmh, 'mean');
        s += arrow(p.windDirectionDeg, kmh, 'now');
        return s + '</svg>';
    }
    function windTrend(p) {
        return p.windMeanOver > 1 ? '<br><span class="muted">mean of the last ' + p.windMeanOver + ' (' + p.windMeanMinutes + ' min): ' + (p.windMeanDeg != null ? dirWord(p.windMeanDeg) + ' ' + p.windMeanDeg + '° ' : 'calm ') + Math.round(p.windMeanKmh) + ' km/h' + (p.windMeanGustKmh != null ? ' gust ' + Math.round(p.windMeanGustKmh) : '') + '</span>' : '';
    }

    // ---- the reach (W-2, W-3): every station's polygon under the rule on the sliders, the clicked one's lit
    function ruleOnSliders() { return {km: Number($('reachKm').value), per: Number($('kmPer100m').value), coastal: Number($('coastalKm').value)}; }
    function ruleQuery(r) { return 'km=' + r.km + '&kmPer100m=' + r.per + '&coastalKm=' + r.coastal; }
    function ruleWords(r) { return r.reachKm + ' km · 100 m costs ' + r.kmPer100m + ' km · coastal at most ' + r.coastalKm + ' km'; }
    var reachTimer = null, reachLoading = false, reachAgain = false;
    function loadReach(immediate) {
        clearTimeout(reachTimer);
        reachTimer = setTimeout(function () {
            if (reachLoading) { reachAgain = true; return; }
            reachLoading = true;
            var r = ruleOnSliders();
            fetch('/console/map/reach.geojson?' + ruleQuery(r)).then(function (x) { return x.json(); }).then(function (fc) {
                reachLoading = false;
                lastReach = fc;
                drawReach();
                stations();
                tiles();
                reachHint();
                if (reachAgain) { reachAgain = false; loadReach(true); }
            }).catch(function (e) { reachLoading = false; note('reach failed: ' + e); });
        }, immediate ? 0 : 150);
    }
    function reachFeature(id) {
        if (!lastReach) return null;
        for (var i = 0; i < lastReach.features.length; i++) if (lastReach.features[i].properties.id === id) return lastReach.features[i];
        return null;
    }
    // Subtle: a hairline of cyan and the faintest wash, the clicked one a little firmer.
    function reachStyle(lit) {
        return lit ? {color: REACH, weight: 1.5, opacity: .85, fillColor: REACH, fillOpacity: .06, lineJoin: 'round'}
            : {color: REACH, weight: 1, opacity: .5, fillColor: REACH, fillOpacity: .03, lineJoin: 'round'};
    }
    // Every reach at once, in its station's colour on the ramp for what the map is coloured by, so the
    // value and the ground it speaks for read together; grey where the station has no value.
    function reachStyleBy(id) {
        var p = stationProps(id), c = p ? colourOf(p) : NONE;
        return {color: c, weight: 1, opacity: .75, fillColor: c, fillOpacity: .16, lineJoin: 'round'};
    }
    function stationProps(id) {
        if (!lastStations) return null;
        for (var i = 0; i < lastStations.features.length; i++) if (lastStations.features[i].properties.id === id) return lastStations.features[i].properties;
        return null;
    }
    function reachTip(p) {
        return '<b>' + esc(p.name) + '</b> <span class="muted">' + esc(p.id) + (p.coastal ? ' · coastal' : '') + '</span><br>reach ' + fmt(p.areaKm2, 0) + ' km² · ' + fmt(p.minKm, 0) + '–' + fmt(p.maxKm, 0) + ' km, mean ' + fmt(p.meanKm, 1)
            + '<br><span class="muted">' + cutWords(p.cut) + ' · rule ' + ruleWords(lastReach.rule) + '</span>';
    }
    function drawReach() {
        allLayer.clearLayers();
        reachLayer.clearLayers();
        if (!lastReach) return;
        lastReach.features.forEach(function (f) {
            var p = f.properties, lit = p.id === state.selected, probed = state.probe && state.probe.ids.indexOf(p.id) >= 0;
            if (lit && togs.reach) {
                L.geoJSON(f, {style: reachStyle(true), interactive: false}).addTo(reachLayer);
            } else if (probed && togs.reach) {
                // The reaches that contain the probed point, faintly, so the membership shows.
                L.geoJSON(f, {style: reachStyle(false), interactive: false}).addTo(reachLayer);
            } else if (togs.all) {
                L.geoJSON(f, {style: reachStyleBy(p.id)}).bindTooltip(function () { return reachTip(p); }, {sticky: true, className: 'hx-tip'})
                    .on('click', function (e) { L.DomEvent.stopPropagation(e); detail(p.id); }).addTo(allLayer);
            }
        });
    }
    function reachHint() {
        var r = ruleOnSliders(), s = $('reachSaved');
        var inForce = lastReach && lastReach.inForce;
        $('reachHint').textContent = inForce ? '' : 'preview';
        $('reach').classList.toggle('on', !inForce);
        $('reachSet').disabled = !!inForce;
        $('reachKmValue').textContent = r.km;
        $('kmPer100mValue').textContent = r.per;
        $('coastalKmValue').textContent = r.coastal;
    }
    function reachSaved(km, per, coastal, by, since) {
        var s = $('reachSaved');
        s.dataset.km = km; s.dataset.per = per; s.dataset.coastal = coastal;
        s.textContent = 'set to ' + km + ' km · ' + per + ' km/100 m · ' + coastal + ' km coastal' + (by ? ' by ' + by + ', ' + ago(since) : ' (default)');
        s.title = since ? when(since) : '';
    }
    function setReach() {
        var r = ruleOnSliders(), headers = window.gullyCsrf ? window.gullyCsrf() : {};
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        $('reachSet').disabled = true;
        fetch('/console/map/reach/rule', {method: 'POST', headers: headers, body: ruleQuery(r)}).then(function (x) { return x.json(); }).then(function (o) {
            reachSaved(o.reachKm, o.kmPer100m, o.coastalKm, o.by, o.since);
            note('reach set: ' + ruleWords(o));
            loadReach(true);
            if (state.selected) detail(state.selected);
        }).catch(function (e) { $('reachSet').disabled = false; note('set failed: ' + e); });
    }
    function sampleTerrain(id) {
        var headers = window.gullyCsrf ? window.gullyCsrf() : {};
        var b = $('sampleNow');
        if (b) { b.disabled = true; b.textContent = 'sampling…'; }
        fetch('/console/map/terrain/' + encodeURIComponent(id), {method: 'POST', headers: headers}).then(function (x) { return x.json(); }).then(function (o) {
            note(o.sampled ? 'terrain sampled: ' + o.terrain.tiles + ' tiles fetched' : 'sampling failed: ' + (o.failure || ''));
            loadReach(true);
            detail(id);
        }).catch(function (e) { note('sampling failed: ' + e); detail(id); });
    }

    // ---- the drawer: everything held for one station
    function kv(rows) {
        var s = '<table class="table table-sm kv mb-1">';
        rows.forEach(function (r) { if (r[1] != null && r[1] !== '' && r[1] !== '—') s += '<tr><th>' + esc(r[0]) + '</th><td class="mono">' + r[1] + '</td></tr>'; });
        return s + '</table>';
    }
    function cutWords(c) {
        var parts = [];
        if (c.distance) parts.push(c.distance + ' at the reach');
        if (c.height) parts.push(c.height + ' by height');
        if (c.water) parts.push(c.water + ' at the water');
        if (c.coastal) parts.push(c.coastal + ' at the coastal limit');
        if (c.unknown) parts.push(c.unknown + ' unknown');
        return parts.join(' · ');
    }
    function reachSection(s) {
        var t = s.terrain, r = s.reach, html = '<h2>Reach <span class="muted">the ground it speaks for</span></h2>';
        if (!t || !t.sampled) {
            return html + '<p class="muted mb-1">Its terrain has not been sampled yet: ' + (t ? t.points : '') + ' points of the elevation tiles, some fifteen tiles, once. The daily housekeeping samples every station lacking it; or</p>'
                + '<button class="pill" id="sampleNow" type="button">' + icon('reach') + ' sample it now</button>';
        }
        html += kv([
            ['area', fmt(r.areaKm2, 0) + ' km²'],
            ['reach', fmt(r.minKm, 0) + '–' + fmt(r.maxKm, 0) + ' km <span class="muted">mean ' + fmt(r.meanKm, 1) + '</span>'],
            ['rays', cutWords(r.cut) + ' <span class="muted">of ' + r.rays.length + '</span>'],
            ['the sea', (r.coastal ? '<span class="coastal">coastal</span> · water ' + fmt(r.waterKm, 0) + ' km away at the nearest, so held to the coastal limit' : r.waterKm != null ? 'water ' + fmt(r.waterKm, 0) + ' km away at the nearest' : 'none inside the reach')
                + (r.island ? ' · <span class="coastal">an island</span>: the water would end ' + r.waterRays + ' of ' + r.rays.length + ' rays, so ends none' : r.waterRays ? ' · ends ' + r.waterRays + ' of ' + r.rays.length + ' rays' : '')],
            ['rule', ruleWords(r.rule)],
            ['model height', fmt(t.elevationM, 0) + ' m' + (s.heightM != null ? ' <span class="muted">the Bureau says ' + s.heightM + '</span>' : '')],
            ['sampled', esc(ago(t.sampledAt)) + ' <span class="muted">' + t.tiles + ' tiles fetched · ' + esc(t.source) + '</span>']
        ]);
        // The rays as a rose: each bearing's reach as a bar, coloured by why it stopped.
        var w = 240, h = 120, cx = w / 2, cy = h / 2, R = 56, max = Math.max.apply(null, r.rays.map(function (x) { return x.km; })) || 1;
        var svg = '<svg class="rose" viewBox="0 0 ' + w + ' ' + h + '" width="' + w + '" height="' + h + '">';
        svg += '<circle cx="' + cx + '" cy="' + cy + '" r="' + R + '" class="ring"/><circle cx="' + cx + '" cy="' + cy + '" r="' + (R / 2) + '" class="ring"/>';
        r.rays.forEach(function (x) {
            var a = (x.bearing - 90) * Math.PI / 180, len = R * x.km / max;
            svg += '<line x1="' + cx + '" y1="' + cy + '" x2="' + (cx + Math.cos(a) * len).toFixed(1) + '" y2="' + (cy + Math.sin(a) * len).toFixed(1) + '" class="ray ' + x.cut + '"><title>' + x.bearing + '°: ' + x.km + ' km, ' + x.cut + '</title></line>';
        });
        svg += '<text x="' + cx + '" y="' + (cy - R - 3) + '" text-anchor="middle">N</text><text x="' + (w - 2) + '" y="' + (h - 3) + '" text-anchor="end">' + fmt(max, 0) + ' km</text></svg>';
        return html + '<div class="rose-wrap">' + svg + '<div class="rose-key"><span class="swatch"><i class="k-distance"></i>at the reach</span><span class="swatch"><i class="k-height"></i>cut by height</span>'
            + (r.cut.water ? '<span class="swatch"><i class="k-water"></i>at the water</span>' : '') + (r.cut.coastal ? '<span class="swatch"><i class="k-coastal"></i>coastal limit</span>' : '')
            + (r.cut.unknown ? '<span class="swatch"><i class="k-unknown"></i>unknown</span>' : '') + '</div></div>';
    }
    // ---- the reading (W-8): click anywhere, and ask - the weather now and the drought, blended from the
    // stations whose reach contains the point, or from a point of ours where none can say; then the
    // stations that fed it with their shares, and the nearest outside with why (the probe, W-5).
    function clearProbe() { state.probe = null; probeLayer.clearLayers(); }
    // A click is an ask from outside (W-14): it goes through the API's front door with the console's own key - scope,
    // rate and access log like any consumer's - so what the map shows is what The Hub gets. A refusal is a problem
    // detail, and its title and detail are the note.
    var API_KEY = document.querySelector('.map-page').dataset.apiKey || '';
    function api(url) {
        return fetch(url, {headers: {'X-Api-Key': API_KEY, 'Accept': 'application/json'}}).then(function (r) {
            return r.json().then(function (o) { if (!r.ok) throw new Error((o && o.title ? o.title + ': ' + o.detail : 'HTTP ' + r.status) + ' (' + url.split('?')[0] + ')'); return o; });
        });
    }
    // The reading at a point; forced (W-13), the upstreams are asked first - the Bureau's file now, the days the stations
    // in reach are missing, a point of ours' current again - and the drawer says what came.
    function probe(lat, lon, force) {
        state.selected = null;
        clearProbe();
        stations();
        drawReach();
        probeLayer.addLayer(L.marker([lat, lon], {icon: L.divIcon({className: 'probe-mark', html: '<i></i>', iconSize: [18, 18], iconAnchor: [9, 9]}), interactive: false, keyboard: false}));
        note(force ? 'grabbing…' : 'asking…');
        var q = 'lat=' + lat.toFixed(5) + '&lon=' + lon.toFixed(5);
        Promise.all([api('/api/v1/reading?' + q + (force ? '&force=true' : '')), api('/api/v1/stations/at?' + q)]).then(function (both) {
            var o = both[0], pr = both[1];
            var ids = o.stations.map(function (s) { return s.id; });
            state.probe = {lat: lat, lon: lon, ids: ids};
            if (o.from !== 'stations') { lastStations = null; load(); loadReach(true); } else drawReach();
            o.stations.forEach(function (s) { L.polyline([[lat, lon], [s.lat, s.lon]], {color: REACH, weight: 1.5, opacity: .85, interactive: false}).addTo(probeLayer); });
            var outside = pr.outside.filter(function (s) { return ids.indexOf(s.id) < 0; });
            outside.forEach(function (s) { L.polyline([[lat, lon], [s.lat, s.lon]], {color: NONE, weight: 1, opacity: .6, dashArray: '4 5', interactive: false}).addTo(probeLayer); });
            var el = $('detail'), c = o.current, d = o.drought, f = o.fire;
            var html = '<div class="drawer-head"><h3>' + (o.point.water ? 'A reading on the water' : 'A reading') + ' <span class="muted">' + fmt(lat, 4) + ', ' + fmt(lon, 4) + (o.point.heightM != null ? ' · ' + fmt(o.point.heightM, 0) + ' m' : '') + '</span></h3><button class="pill" id="grab" title="Ask the upstreams now, whatever the timers say: the Bureau\'s file, the days the stations in reach are missing, a point of ours\' current" type="button">' + icon('refresh') + ' force grab</button><button class="icon-btn" id="close" title="Close (Esc)" type="button">' + icon('close') + '</button></div>';
            html += '<p class="muted mb-1">' + (o.from === 'stations' ? 'From the Bureau\'s stations whose reach contains this point' : o.from === 'point' ? 'From a point of ours whose reach contains this place: the model\'s current, its own year of record' : 'Nobody\'s reach contained this place: a point of ours dropped here just now, with the model\'s current and a year of the archive') + '.</p>';
            if (o.grabbed) html += '<p class="grabbed">' + icon('refresh') + ' Grabbed just now: ' + esc(grabWords(o.grabbed, o.from)) + '</p>';
            html += '<div class="reading">'
                + tile(fmt(c.temperatureC, 1) + ' °C', 'temperature', c.from.temperatureC, 'ground')
                + tile(fmt(c.humidityPct, 0) + ' %', 'humidity', c.from.humidityPct, 'ground')
                + tile(c.windSpeedKmh != null ? dirWord(c.windDirectionDeg) + ' ' + fmt(c.windSpeedKmh, 0) + '<small>km/h' + (c.windGustKmh != null ? ' · gust ' + fmt(c.windGustKmh, 0) : '') + '</small>' : '—', 'wind', c.from.windSpeedKmh, 'ground')
                + tile(fmt(c.rainSince9amMm, 1) + '<small>mm</small>', 'rain since 9 am', c.from.rainSince9amMm, 'ground')
                + tile(fmt(d.kbdiMm, 0) + '<small>mm' + (d.band ? ' · ' + esc(String(d.band).toLowerCase()) : '') + '</small>', 'KBDI', d.from, 'drought')
                + tile(fmt(d.droughtFactor, 1) + '<small>of 10' + (d.complete === false ? ' · spin-up short' : '') + '</small>', 'drought factor', d.from, 'drought')
                + tile(f.ffdi != null ? fmt(f.ffdi, 0) + '<small>' + esc(String(f.ffdiRating).toLowerCase()) + '</small>' : '—', 'FFDI', f.ffdi != null ? null : Object.keys(f.inputs).filter(function (k) { return !f.inputs[k]; }), 'fire')
                + tile(c.dewPointC != null ? fmt(c.dewPointC, 1) + ' °C' : '—', 'dew point', c.from.dewPointC, 'ground')
                + '</div>';
            html += '<p class="muted control-note">' + (c.at ? 'The current is as of ' + esc(when(c.at)) + ', ' + esc(ago(c.at)) + '. ' : '') + 'Temperature and dew point are brought to this point\'s height by the lapse rate; the rest is blended as it is, each value from the stations named under it, weighted by 1/cost² with the cost measured along the ray as the reach is.</p>';
            html += '<h2>The stations <span class="muted">' + o.stations.length + ' in reach · their share of the blend</span></h2>' + stationRows(o.stations, true);
            if (outside.length) html += '<h2>Not in reach <span class="muted">the nearest ' + outside.length + ', and why</span></h2>' + stationRows(outside, false);
            el.innerHTML = html;
            el.classList.add('wide');
            el.classList.remove('hidden');
            $('note').classList.add('hidden');
            // The point, and the nearest station in reach, into the clear between the panel and the drawer.
            var pad = {paddingTopLeft: [$('side').offsetWidth + 24, 24], paddingBottomRight: [el.offsetWidth + 24, 24]};
            map.panInside([lat, lon], pad);
            if (o.stations.length) map.panInside([o.stations[0].lat, o.stations[0].lon], pad);
            $('close').addEventListener('click', closeDetail);
            $('grab').addEventListener('click', function () { probe(lat, lon, true); });
            el.querySelectorAll('[data-station]').forEach(function (a) { a.addEventListener('click', function (e) { e.preventDefault(); detail(a.dataset.station); }); });
        }).catch(function (e) { note('reading failed: ' + e); });
    }
    // What a forced ask brought, in a line.
    function grabWords(g, from) {
        var parts = [];
        parts.push(g.bureauDownloaded ? 'a new Bureau file' : 'the Bureau file, unchanged');
        if (from !== 'stations') parts.push(g.currentFetched ? 'the model\'s current' + (from === 'point' ? ' again' : '') : 'the model\'s current could not be fetched');
        parts.push(g.daysFilled ? g.daysFilled + ' missing day' + (g.daysFilled === 1 ? '' : 's') + ' of record filled' : 'no days of record were missing');
        return parts.join(' · ') + '.';
    }
    // One figure of the reading: the value, what it is, and the stations it came from - or what it lacked.
    function tile(v, k, from, cls) {
        var who = from && from.length ? (k === 'FFDI' ? 'missing ' + from.join(', ') : from.join(', ')) : (from ? 'nothing gave it' : '');
        return '<div class="tile ' + cls + '"><div class="v">' + v + '</div><div class="k">' + esc(k) + '</div><div class="who" title="' + esc(who) + '">' + esc(who) + '</div></div>';
    }
    function stationRows(list, inReach) {
        var html = '<table class="table table-sm probe"><thead><tr><th>station</th><th class="num">km</th><th>from</th><th class="num">Δ m</th><th class="num">°C</th><th class="num">%</th><th>wind</th><th class="num">mm</th><th class="num" title="Keetch-Byram drought index, mm">KBDI</th><th class="num" title="drought factor, 0 to 10">DF</th><th>age</th></tr></thead><tbody>';
        list.forEach(function (s) {
            html += '<tr' + (s.fresh ? '' : ' class="stale"') + '><td><a href="#" data-station="' + esc(s.id) + '">' + esc(s.name) + '</a>' + (s.coastal ? ' <span class="coastal" title="coastal">~</span>' : '') + '</td>'
                + '<td class="num">' + fmt(s.km, 1) + '</td><td class="mono">' + dirWord(s.bearingDeg) + '</td><td class="num">' + (s.aboveM != null ? (s.aboveM > 0 ? '+' : '') + s.aboveM : '—') + '</td>'
                + '<td class="num">' + fmt(s.temperatureC, 1) + '</td><td class="num">' + fmt(s.humidityPct) + '</td><td class="mono">' + (s.windSpeedKmh != null ? dirWord(s.windDirectionDeg) + ' ' + Math.round(s.windSpeedKmh) : '—') + '</td><td class="num">' + fmt(s.rainSince9amMm, 1) + '</td><td class="num">' + fmt(s.kbdiMm, 0) + '</td><td class="num">' + fmt(s.droughtFactor, 1) + '</td><td class="muted">' + (s.at ? ago(s.at) : '—') + '</td></tr>';
            if (!inReach) html += '<tr class="why"><td colspan="11" class="muted">' + esc(s.why) + '</td></tr>';
            else if (s.margin != null) html += '<tr class="why"><td colspan="11" class="muted">' + (s.weight != null && list.length ? 'share ' + Math.round(s.weight / list.reduce(function (a, x) { return a + (x.weight || 0); }, 0) * 100) + ' % · cost ' + fmt(s.costKm, 1) + ' km · gives ' + (s.gives && s.gives.length ? s.gives.join(", ") : "nothing") + ' · ' : '') + 'its ray towards here reaches ' + fmt(s.rayKm, 1) + ' km, ' + fmt(s.margin, 1) + ' km past the point' + (s.rayCut !== 'distance' ? ' · ' + esc(s.rayCut === 'height' ? 'cut by height' : s.rayCut === 'water' ? 'ends at the water' : s.rayCut === 'coastal' ? 'at its coastal limit' : s.rayCut) : '') + '</td></tr>';
        });
        return html + '</tbody></table>';
    }

    // ---- the drought (W-6): the station's deficit and factor from its own record, and the record itself
    function droughtSection(s) {
        var d = s.drought, html = '<h2>Drought <span class="muted">from its own record</span></h2>';
        if (!d) return html;
        if (!d.held) {
            return html + '<p class="muted mb-1">Its record holds ' + d.days + ' day' + (d.days === 1 ? '' : 's') + (d.days ? ' (' + d.bureauDays + ' from the file, ' + d.archiveDays + ' from the archive)' : '') + ': too few for a drought to speak of. The daily housekeeping fills a year from the archive; an ask for a reading here fills it now.</p>';
        }
        html += kv([
            ['KBDI', fmt(d.kbdiMm, 0) + ' mm <span class="muted">' + esc(String(d.band).toLowerCase()) + ' · 0 saturated, 203 dry</span>'],
            ['drought factor', fmt(d.droughtFactor, 1) + ' <span class="muted">of 10</span>'],
            ['integrated', d.yearDays + ' days, ' + esc(d.integratedFrom) + ' to ' + esc(d.integratedTo) + (d.complete ? '' : ' <span class="muted">· not a whole year yet</span>')],
            ['mean annual rain', fmt(d.meanAnnualRainMm, 0) + ' mm <span class="muted">from that year</span>'],
            ['record', d.days + ' days <span class="muted">' + d.bureauDays + ' from the file · ' + d.archiveDays + ' from the archive</span>'],
            ['today so far', d.rainSoFarMm != null ? fmt(d.rainSoFarMm, 1) + ' mm' : null]
        ]);
        // The twenty days behind the factor, as bars, today last.
        var rr = d.recentRainMm || [], max = Math.max.apply(null, rr.concat([1]));
        html += '<div class="rain-strip" title="the last twenty days\' rain, today last">' + rr.map(function (mm, i) { return '<i style="height:' + Math.max(2, mm / max * 28) + 'px" title="' + (rr.length - 1 - i) + ' days ago: ' + mm.toFixed(1) + ' mm"></i>'; }).join('') + '</div>';
        if (s.recordDays && s.recordDays.length) {
            html += '<details class="rec"><summary class="muted">the last ' + s.recordDays.length + ' days of the record</summary><table class="table table-sm recent"><thead><tr><th>day</th><th class="num">rain</th><th class="num">max °C</th><th>from</th></tr></thead><tbody>';
            s.recordDays.forEach(function (x) { html += '<tr><td class="mono">' + esc(x.day) + '</td><td class="num">' + fmt(x.rainMm, 1) + '</td><td class="num">' + fmt(x.maxTempC, 1) + '</td><td class="muted">' + esc(x.source) + '</td></tr>'; });
            html += '</tbody></table></details>';
        }
        if (s.recordWindows && s.recordWindows.length) {
            html += '<details class="rec"><summary class="muted">the last ' + s.recordWindows.length + ' six-hour windows</summary><table class="table table-sm recent"><thead><tr><th>to</th><th class="num">n</th><th class="num">°C min·mean·max</th><th class="num">% min·max</th><th class="num">wind mean·max</th><th class="num">gust</th><th class="num">rain 9 am</th></tr></thead><tbody>';
            s.recordWindows.forEach(function (x) { html += '<tr><td class="mono">' + esc(when(x.at)) + '</td><td class="num">' + fmt(x.readings) + '</td><td class="num">' + fmt(x.temp_min_c, 1) + '·' + fmt(x.temp_mean_c, 1) + '·' + fmt(x.temp_max_c, 1) + '</td><td class="num">' + fmt(x.rh_min_pct) + '·' + fmt(x.rh_max_pct) + '</td><td class="num">' + fmt(x.wind_mean_kmh) + '·' + fmt(x.wind_max_kmh) + '</td><td class="num">' + fmt(x.gust_max_kmh) + '</td><td class="num">' + fmt(x.rain_since_9am_mm, 1) + '</td></tr>'; });
            html += '</tbody></table></details>';
        }
        return html;
    }

    function detail(id) {
        state.selected = id;
        clearProbe();
        stations();
        drawReach();
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
                    ['wind now', windWords(s.windDirectionDeg, s.windSpeedKmh, s.windGustKmh)],
                    ['wind, mean of ' + (s.windMeanOver || 0), s.windMeanOver > 1 ? (s.windMeanDeg != null ? dirWord(s.windMeanDeg) + ' ' + s.windMeanDeg + '°' : 'calm') + ' ' + Math.round(s.windMeanKmh) + ' km/h' + (s.windMeanGustKmh != null ? ' <span class="muted">gust ' + Math.round(s.windMeanGustKmh) + '</span>' : '') + ' <span class="muted">over ' + s.windMeanMinutes + ' min</span>' : null],
                    ['pressure', s.pressureMslHpa != null ? fmt(s.pressureMslHpa, 1) + ' hPa' : null],
                    ['rain since 9 am', s.rainSince9amMm != null ? fmt(s.rainSince9amMm, 1) + ' mm' : null],
                    ['rain to 9 am', s.rain24hMm != null ? fmt(s.rain24hMm, 1) + ' mm' : null],
                    ['today', (s.minTemperatureC != null || s.maxTemperatureC != null) ? 'min ' + fmt(s.minTemperatureC, 1) + ' · max ' + fmt(s.maxTemperatureC, 1) + ' °C' : null],
                    ['sky', s.cloud != null ? esc(s.cloud) + (s.cloudOktas != null ? ' <span class="muted">' + s.cloudOktas + ' oktas</span>' : '') : null],
                    ['visibility', s.visibilityKm != null ? fmt(s.visibilityKm, 0) + ' km' : null],
                    ['delta-T', s.deltaTC != null ? fmt(s.deltaTC, 1) + ' °C <span class="muted">dry-bulb minus wet-bulb</span>' : null]
                ]);
            } else {
                html += '<p class="muted">Nothing reported since the start.</p>';
            }
            html += reachSection(s);
            html += droughtSection(s);
            if (s.recent && s.recent.length > 1) {
                html += '<h2>Last readings <span class="muted">newest first, since the start</span></h2><table class="table table-sm recent"><thead><tr><th>at</th><th class="num">°C</th><th class="num">%</th><th class="num">km/h</th><th>from</th><th class="num">gust</th><th class="num">mm</th></tr></thead><tbody>';
                s.recent.forEach(function (x) { html += '<tr><td class="mono">' + clock(x.at) + '</td><td class="num">' + fmt(x.temperatureC, 1) + '</td><td class="num">' + fmt(x.humidityPct) + '</td><td class="num">' + fmt(x.windSpeedKmh) + '</td><td class="dir">' + (x.windDirectionDeg != null ? '<span class="arrow" style="transform:rotate(' + ((x.windDirectionDeg + 180) % 360) + 'deg)">↑</span> ' + x.windDirectionDeg + '°' : '—') + '</td><td class="num">' + fmt(x.windGustKmh) + '</td><td class="num">' + fmt(x.rainSince9amMm, 1) + '</td></tr>'; });
                html += '</tbody></table>';
            }
            el.innerHTML = html;
            el.classList.remove('wide');
            el.classList.remove('hidden');
            $('close').addEventListener('click', closeDetail);
            if ($('sampleNow')) $('sampleNow').addEventListener('click', function () { sampleTerrain(id); });
        }).catch(function (e) { note('station failed: ' + e); });
    }
    function closeDetail() { $('detail').classList.add('hidden'); state.selected = null; clearProbe(); stations(); drawReach(); }

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
        b.addEventListener('click', function () { var k = b.dataset.tog; togs[k] = !togs[k]; b.classList.toggle('on', togs[k]); if (k === 'labels') stations(); else drawReach(); });
    });
    $('readNow').addEventListener('click', readNow);
    ['reachKm', 'kmPer100m', 'coastalKm'].forEach(function (id) {
        $(id).addEventListener('input', function () { reachHint(); loadReach(false); });
        $(id).addEventListener('change', function () { loadReach(true); });
    });
    $('reachSet').addEventListener('click', setReach);
    reachSaved($('reachSaved').dataset.km, $('reachSaved').dataset.per, $('reachSaved').dataset.coastal, $('reachSaved').dataset.by, $('reachSaved').dataset.since);
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') closeDetail();
    });
    map.on('zoomend', stations);
    map.on('click', function (e) { probe(e.latlng.lat, e.latlng.lng); });
    document.addEventListener('gully:theme', function () { REACH = getComputedStyle(document.querySelector('.map-page')).getPropertyValue('--p-reach').trim() || REACH; stations(); drawReach(); });
    document.addEventListener('visibilitychange', function () { liveTick(); if (!document.hidden) { load(); loadReach(true); } });

    buildSide();
    legend();
    tiles();
    load();
    loadReach(true);
    setInterval(liveTick, 1000);
    setInterval(function () { if (!document.hidden) load(); }, 60000);
    // The reaches follow the sampler: a station sampled since the last look appears on the next.
    setInterval(function () { if (!document.hidden && lastReach && lastStations && lastReach.sampled < lastStations.stations) loadReach(true); }, 20000);
})();
