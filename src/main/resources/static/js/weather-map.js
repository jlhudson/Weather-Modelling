/*
 * Everything the weather service knows about a piece of ground, in one layer and one look: weather,
 * fire danger now, drought and flood as four concentric outlines, and a fifth for the weather that has
 * aged past its time-to-live. Plus every anchor and cell as a pin carrying its own detail, and, while
 * the layer is on, a click on the map that forces a fresh call at that point. Registered with the
 * engine in map-layers.js under the id the layer declares.
 *
 * The Hub's layer engine (map-layers.js) and its catalogue did not come across with the weather — there
 * is one map here, not a panel of switches — so this file is dormant: the guard below returns when the
 * engine is absent, and it is carried intact so that wiring a map page is adding the engine back rather
 * than rewriting the renderer. The data it draws is live either way at
 * GET /api/weather/coverage.geojson.
 *
 * There is no view selector. The four are different radii on the same points, so the nesting is the
 * picture; behind a selector it takes four looks and a memory to see what one look shows.
 */
(function () {
    const H = window.hubLayers;
    if (!H) {
        return;
    }
    const esc = H.esc;
    const num = (v, unit) => v == null ? '&mdash;' : esc(v) + (unit || '');
    // PT30M reads as machine output; the operator tuning this wants 30m.
    const dur = s => String(s || '').replace('PT', '').toLowerCase();

    function mins(seconds) {
        if (seconds == null) {
            return '';
        }
        const s = Math.round(seconds), a = Math.abs(s);
        const t = a < 60 ? a + ' s' : a < 3600 ? Math.round(a / 60) + ' min' : (a / 3600).toFixed(1) + ' h';
        return s < 0 ? t + ' ago' : 'in ' + t;
    }

    // Four colours, one per thing the service knows, mirroring what the server puts on each outline so a
    // pin and the ring it sits in the middle of always agree. Freshness is not among them: it is the
    // weather ring's line pattern, which the band carries as its own dashArray.
    const GROUP_COLOUR = {
        weather: '#22c55e', fire: '#ef4444', drought: '#a78bfa', flood: '#38bdf8'
    };
    // The published fire danger rating colours, so a rating reads like every other fire product rather than
    // like a palette somebody picked here. Popups only: the fire ring is one colour because it answers
    // "is there an index here", and the rating itself is a number at a point, not a footprint.
    const RATING_COLOUR = {
        'LOW-MODERATE': '#9bc466', 'HIGH': '#f7e463', 'VERY HIGH': '#f0a04b',
        'SEVERE': '#e35d3c', 'EXTREME': '#c1272d', 'CATASTROPHIC': '#6d2077'
    };
    // A rating badge needs dark text on the pale end and light on the dark end, or half of them are unreadable.
    const RATING_INK = r => (r === 'EXTREME' || r === 'CATASTROPHIC') ? '#fff' : '#111';
    const ratingBadge = r => '<span class="badge" style="background:' + (RATING_COLOUR[r] || '#9ca3af')
        + ';color:' + RATING_INK(r) + '">' + esc(r || 'no rating') + '</span>';
    // A pin is coloured by what it is, never by how old it is — the same rule as the rings.
    const kindColour = p => p.kind === 'anchor' ? GROUP_COLOUR.weather
        : p.kind === 'drought' ? GROUP_COLOUR.drought : GROUP_COLOUR.flood;
    // An anchor's age shows on its pin the way it shows on its ring: an outline once it is past the ttl,
    // hollow once it is past max-stale.
    const stateFill = s => s === 'fresh' ? .9 : s === 'stale' ? .35 : 0;
    const km2 = v => v == null ? '' : Math.round(v).toLocaleString() + ' km²';

    /**
     * The days ahead, one row each, from the single forecast structure the server now serves: the day's
     * own weather, the fire index for that day and the river for that day arrive together on the day
     * rather than in three parallel arrays this had to join.
     */
    function forecastTable(forecast) {
        const days = (forecast && forecast.days) || [];
        if (!days.length) {
            return '';
        }
        const anyFire = days.some(d => d.fire),
            anyRiver = days.some(d => d.flood && d.flood.riverDischargeCumecs != null);
        const body = days.map(d => {
            const f = d.fire || {}, w = d.flood || {};
            return '<tr>'
                + '<td class="mono text-nowrap">' + esc((d.date || '').slice(5)) + '</td>'
                + '<td>' + num(d.minTemperatureC) + '<span class="text-secondary">/</span>' + num(d.maxTemperatureC) + '</td>'
                + '<td>' + num(d.maxWindKmh) + (d.maxGustKmh == null ? '' : '<span class="text-secondary">/' + esc(d.maxGustKmh) + '</span>') + '</td>'
                + '<td>' + num(d.precipitationMm) + (d.precipitationProbabilityPct == null ? '' : ' <span class="text-secondary">' + esc(d.precipitationProbabilityPct) + '%</span>') + '</td>'
                + (anyFire ? '<td>' + (f.ffdiRating ? ratingBadge(f.ffdiRating) + ' ' + num(f.ffdi) : '<span class="text-secondary">&mdash;</span>') + '</td>' : '')
                + (anyRiver ? '<td>' + num(w.riverDischargeCumecs) + (w.dischargeRatioToMean == null ? '' : ' <span class="text-secondary">' + esc(w.dischargeRatioToMean) + '&times;</span>') + '</td>' : '')
                + '<td class="text-secondary">' + esc(d.condition || '') + '</td></tr>';
        }).join('');
        return '<div class="text-secondary mt-1" style="font-size:.7rem">Days ahead &mdash; weather, fire and river on the same row</div>'
            + '<table class="table table-sm mb-0" style="font-size:.72rem">'
            + '<thead><tr><th>day</th><th>&deg;C min/max</th><th>km/h</th><th>mm</th>'
            + (anyFire ? '<th>fire</th>' : '') + (anyRiver ? '<th>m&sup3;/s</th>' : '') + '<th>cond</th></tr></thead>'
            + '<tbody>' + body + '</tbody></table>';
    }

    function hourlyTable(rows) {
        if (!rows || !rows.length) {
            return '<div class="text-secondary small">No hourly series on this anchor.</div>';
        }
        const body = rows.slice(0, 48).map(h => '<tr>'
            + '<td class="mono">' + esc((h.at || '').slice(11, 16)) + '</td>'
            + '<td>' + num(h.temperatureC, '&deg;') + '</td>'
            + '<td>' + num(h.humidityPct, '%') + '</td>'
            + '<td>' + num(h.windSpeedKmh) + (h.windGustKmh == null ? '' : '<span class="text-secondary">/' + esc(h.windGustKmh) + '</span>') + '</td>'
            + '<td>' + num(h.precipitationMm) + (h.precipitationProbabilityPct == null ? '' : ' <span class="text-secondary">' + esc(h.precipitationProbabilityPct) + '%</span>') + '</td>'
            + '<td class="text-secondary">' + esc(h.condition || '') + '</td></tr>').join('');
        return '<div style="max-height:190px;overflow:auto"><table class="table table-sm mb-0" style="font-size:.72rem">'
            + '<thead><tr><th>h</th><th>&deg;C</th><th>RH</th><th>km/h</th><th>mm</th><th>cond</th></tr></thead>'
            + '<tbody>' + body + '</tbody></table></div>'
            + (rows.length > 48 ? '<div class="text-secondary" style="font-size:.7rem">first 48 of ' + rows.length + ' hours</div>' : '');
    }

    function anchorPopup(p, lat, lon) {
        const c = p.current || {}, ch = p.cache, f = p.fire;
        return '<div style="min-width:360px">'
            + '<b>' + esc(p.provider) + '</b> <span class="text-secondary">' + esc(p.model || '') + '</span>'
            + ' <span class="badge text-bg-secondary">' + esc(ch.state) + '</span>'
            + '<table class="table table-sm mb-1" style="font-size:.75rem">'
            + '<tr><th>Fetched</th><td>' + esc(ch.age) + ' ago <span class="text-secondary mono">' + esc((ch.fetchedAt || '').slice(11, 19)) + 'Z</span></td></tr>'
            + '<tr><th>Serves without a call</th><td>' + (ch.servesForSeconds > 0 ? 'until ' + mins(ch.servesForSeconds) : '<span class="text-warning">no longer</span>')
            + ' <span class="text-secondary">(ttl ' + dur(ch.ttl) + ')</span></td></tr>'
            + '<tr><th>Usable as a last resort</th><td>' + (ch.lastResortForSeconds > 0 ? 'until ' + mins(ch.lastResortForSeconds) : '<span class="text-secondary">no</span>')
            + ' <span class="text-secondary">(max-stale ' + dur(ch.maxStale) + ')</span></td></tr>'
            + '<tr><th>Provider asked to re-ask</th><td>' + (ch.providerExpiresAt ? mins((new Date(ch.providerExpiresAt) - Date.now()) / 1000) : '&mdash;')
            + (ch.providerExpired ? ' <span class="badge text-bg-secondary">past it</span>' : '') + '</td></tr>'
            + '<tr><th>Reuse reach</th><td>' + (p.radiusMetres / 1000) + ' km · reused <b>' + esc(ch.hits) + '</b> times</td></tr>'
            + '<tr><th>Height</th><td>' + (p.terrainM == null
                ? '<span class="text-secondary">no terrain tile here; matched horizontally</span>'
                : '<b>' + Math.round(p.terrainM) + ' m</b> ground'
                + (p.elevationM == null ? '' : ' <span class="text-secondary">· model grid ' + Math.round(p.elevationM) + ' m</span>'))
            + '</td></tr>'
            + '<tr><th>Now</th><td>' + num(c.temperatureC, ' &deg;C') + ' · ' + num(c.humidityPct, ' %') + ' RH · '
            + num(c.windSpeedKmh, ' km/h') + (c.windGustKmh == null ? '' : ' gust ' + esc(c.windGustKmh)) + '<br>' + esc(c.condition || '') + '</td></tr>'
            + dangerRows(f, p.flood)
            + '</table>'
            + forecastTable(p.forecast)
            + '<div class="text-secondary mt-1" style="font-size:.7rem">Hourly · next ' + esc(p.hourlyShown) + ' of '
            + esc(p.hourlyCount) + ' steps held, ' + esc(p.dailyCount) + ' days · '
            + '<a href="/console/weather?lat=' + lat.toFixed(4) + '&amp;lon=' + lon.toFixed(4) + '">full series</a></div>'
            + hourlyTable(p.forecast && p.forecast.hours)
            + '<div class="text-secondary mt-1" style="font-size:.65rem">' + esc(p.attribution || '') + '</div></div>';
    }

    function cellPopup(p) {
        if (p.kind === 'drought') {
            const d = p.drought || {};
            return '<b>Drought cell</b> <span class="text-secondary">' + (p.radiusMetres / 1000) + ' km, one day</span>'
                + '<table class="table table-sm mb-0" style="font-size:.75rem">'
                + '<tr><th>KBDI</th><td>' + num(d.kbdiMm, ' mm') + ' <span class="text-secondary">' + esc(d.kbdiBand || '') + '</span></td></tr>'
                + '<tr><th>Drought factor</th><td><b>' + num(d.droughtFactor) + '</b>' + (d.complete ? '' : ' <span class="badge text-bg-warning">shallow</span>') + '</td></tr>'
                + '<tr><th>Mean annual rain</th><td>' + num(d.meanAnnualRainfallMm, ' mm') + '</td></tr>'
                + '<tr><th>Spun up</th><td>' + num(d.spinUpDays) + ' days from ' + esc(d.spunUpFrom || '') + '</td></tr>'
                + '<tr><th>Computed for</th><td>' + esc(d.computedFor || '') + '</td></tr></table>';
        }
        return '<b>Flood cell</b> <span class="text-secondary">river discharge, ' + (p.radiusMetres / 1000) + ' km</span><br>'
            + (p.hasRiver ? 'Discharge modelled, ' + esc(p.days) + ' days held' : 'No modelled river within 5 km')
            + '<br><span class="text-secondary">computed for ' + esc(p.computedFor || '') + '</span>';
    }

    /** The operator's probe: skip the cache, pay for a reading, and show what came back. */
    async function forceAt(ctx, latlng) {
        const map = ctx.map;
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
        const csrfToken = document.querySelector('meta[name="_csrf"]').content;
        const popup = L.popup({maxWidth: 420}).setLatLng(latlng)
            .setContent('<span class="text-secondary">Forcing a fresh call at ' + latlng.lat.toFixed(4) + ', ' + latlng.lng.toFixed(4) + '&hellip;</span>')
            .openOn(map);
        try {
            const res = await fetch('/console/weather/probe?lat=' + latlng.lat.toFixed(4) + '&lon=' + latlng.lng.toFixed(4) + '&force=true',
                {method: 'POST', credentials: 'same-origin', headers: {[csrfHeader]: csrfToken}});
            const b = await res.json();
            if (b.unavailable || b.error) {
                popup.setContent('<b>Nothing answered</b><br><span class="text-secondary">' + esc(b.unavailable || b.error) + '</span>');
                return;
            }
            const pr = b.provenance, c = b.current || {}, f = b.fire;
            popup.setContent('<div style="min-width:360px"><b>Forced call</b> <span class="text-secondary">'
                + esc(pr.provider) + ' · ' + esc(pr.model || '') + '</span>'
                + '<div class="text-secondary" style="font-size:.7rem">' + esc(pr.decision) + '</div>'
                + '<table class="table table-sm mb-1" style="font-size:.75rem">'
                + '<tr><th>Now</th><td>' + num(c.temperatureC, ' &deg;C') + ' · ' + num(c.humidityPct, ' %') + ' RH · '
                + num(c.windSpeedKmh, ' km/h') + (c.windGustKmh == null ? '' : ' gust ' + esc(c.windGustKmh)) + '<br>' + esc(c.condition || '') + '</td></tr>'
                + '<tr><th>Stretched</th><td>' + num(pr.offsetMetres, ' m') + ', ' + num(pr.ageMinutes, ' min') + ' old</td></tr>'
                + dangerRows(f, b.flood)
                + '</table>' + forecastTable(b.forecast)
                + hourlyTable(b.forecast && b.forecast.hours)
                + '<div class="text-secondary mt-1" style="font-size:.65rem">' + esc(pr.attribution || '') + '</div></div>');
            // This popup is standalone, not part of the layer group, so a forced reload of the group
            // leaves it open while the new anchor appears beneath it.
            ctx.reload(true);
        } catch (e) {
            popup.setContent('<b>Probe failed</b><br><span class="text-secondary">' + esc(e) + '</span>');
        }
    }

    const probe = {handler: null};
    const banner = () => document.getElementById('probeArmed');

    /**
     * A coverage band: the outline of everything the group reaches, holes and all.
     * <p>
     * Outline only, never filled. The five bands are deliberately drawn over each other — an anchor's
     * reach, the fire index inside it, the drought cell inside that and the river cell inside that are
     * four concentric rings around the same point, and that nesting <em>is</em> the picture. Five
     * translucent fills stacked would make the middle of the map the darkest simply for having been
     * drawn on five times, and the rings would be the one thing you could not see.
     * <p>
     * The colour comes off the feature, so the server owns it and the legend beside the switch cannot
     * drift from the ink on the map. Never interactive — like the radii it must let the probe click
     * through to the map beneath.
     */
    function coverageBand(f) {
        const p = f.properties;
        const fire = p.group === 'fire';
        const colour = p.colour || GROUP_COLOUR[p.group] || '#9ca3af';
        return L.geoJSON(f, {
            interactive: false,
            style: {
                // Fire shares the anchors' radius exactly, so wherever every anchor has an index its ring
                // and the weather ring are the same circle. It arrives first from the server and is drawn
                // as the wider line underneath, so the weather ring runs down the middle of it and both
                // colours are on the map at once.
                color: colour, weight: fire ? 5 : 2, opacity: fire ? .55 : .95,
                // The band's own pattern, from the server: solid for everything except the weather ring
                // once it is stale (dashed) or expired (dotted). Round caps so dots are dots.
                dashArray: p.dash || null, lineCap: p.dash ? 'round' : 'butt',
                fill: false
            }
        });
    }

    /**
     * The fire and flood rows of the "now" table. Everything on this map is now; the days ahead are the
     * forecast table underneath, which is why neither of these carries a series any more.
     */
    function dangerRows(f, w) {
        const fire = !f
            ? '<tr><th>Fire danger</th><td class="text-secondary">no drought cell covers this point, so an index '
            + 'here would rest on an assumed drought factor. Left out rather than guessed</td></tr>'
            : '<tr><th>Fire danger</th><td>' + ratingBadge(f.ffdiRating) + ' <b>FFDI ' + num(f.ffdi) + '</b>'
            + ' <span class="text-secondary">DF ' + num(f.droughtFactor) + ' · KBDI ' + num(f.kbdiMm, ' mm') + '</span>'
            + (f.estimated ? ' <span class="badge text-bg-warning">shallow</span>' : '') + '</td></tr>';
        if (!w) {
            return fire;
        }
        // Both halves of the flood block, because they fail separately: the rain comes off the anchor's
        // own series and is always there, the discharge needs a river cell and often is not.
        return fire
            + '<tr><th>Rain 24/48/72 h</th><td>' + num(w.rain1dMm) + ' / ' + num(w.rain2dMm) + ' / ' + num(w.rain3dMm)
            + ' mm <span class="text-secondary">back</span> · ' + num(w.forecastRain24hMm) + ' / '
            + num(w.forecastRain48hMm) + ' / ' + num(w.forecastRain72hMm) + ' mm <span class="text-secondary">ahead</span></td></tr>'
            + '<tr><th>River</th><td>' + (w.riverDischargeCumecs == null
                ? '<span class="text-secondary">no modelled river within reach of a cell here</span>'
                : '<b>' + num(w.riverDischargeCumecs, ' m³/s') + '</b>'
                + (w.dischargeRatioToMean == null ? '' : ' <span class="text-secondary">' + esc(w.dischargeRatioToMean) + '× the 92-day mean</span>')
                + (w.riverTrend == null ? '' : ' · ' + esc(w.riverTrend))) + '</td></tr>';
    }

    H.register('weather', {
        draw(fc) {
            // All five bands, always, and no view selector. Weather, fire, drought and flood are four
            // different radii on the same points; the reason to draw them is to see them nested, and a
            // selector that shows one at a time is four looks and a memory test instead of one look.
            const group = L.layerGroup();
            for (const f of fc.features) {
                if (!f.geometry) {
                    continue;
                }
                const p = f.properties;
                if (p.kind === 'coverage') {
                    group.addLayer(coverageBand(f));
                    continue;
                }
                const colour = kindColour(p);
                const [lon, lat] = f.geometry.coordinates;
                const marker = L.circleMarker([lat, lon], {
                    radius: p.kind === 'anchor' ? 6 : 4, color: colour, weight: 2,
                    fillColor: colour,
                    fillOpacity: p.kind === 'anchor' ? stateFill(p.cache.state) : .5
                }).bindPopup(p.kind === 'anchor' ? anchorPopup(p, lat, lon) : cellPopup(p), {maxWidth: 460});
                marker.on('click', e => L.DomEvent.stopPropagation(e));
                group.addLayer(marker);
            }
            return group;
        },
        status(fc) {
            const n = fc.counts, c = fc.cache;
            const bands = (fc.features || []).filter(f => f.properties && f.properties.kind === 'coverage');
            const area = g => {
                const b = bands.find(f => f.properties.group === g);
                return b ? b.properties.areaKm2 : 0;
            };
            // The three weather bands are disjoint, so their areas add up to something meaningful: how much
            // ground the cache can answer for at all, and how much of it on a reading still inside its ttl.
            const anchored = area('fresh') + area('stale') + area('expired');
            // "reach", not "radius", and it says the vertical term is not drawn: a circle over the Hills
            // claims ground the anchor will not actually serve, and the caption is the only honest fix
            // until the true footprint is affordable to compute.
            const reach = c.reachKm + ' km reach'
                + (c.verticalWeight ? ' (horizontal; the vertical term is not drawn)' : ' (flat: vertical term off)');
            return n.anchors + ' anchors (' + n.fresh + ' fresh, ' + n.stale + ' stale, ' + n.expired + ' expired)'
                + ' · ' + reach + ', ttl ' + dur(c.ttl)
                + (anchored ? ' · weather over ' + km2(anchored) + ', ' + km2(area('fresh')) + ' of it fresh' : '')
                + ' · fire danger over ' + km2(area('fire')) + ' from ' + n.withFireIndex + ' of ' + n.anchors + ' anchors'
                + ' · drought ' + km2(area('drought')) + ' from ' + n.droughtCells + ' cells'
                + ' · flood ' + km2(area('flood')) + ' from ' + n.riverCells + ' cells'
                + ' · hit rate ' + c.hitRate + '%'
                + (c.staleRate ? ', ' + c.staleRate + '% served stale' : '');
        },
        // Armed only while the layer is on, so a click cannot spend allowance on a page nobody is
        // watching for it.
        onOn(ctx) {
            probe.handler = e => forceAt(ctx, e.latlng);
            ctx.map.on('click', probe.handler);
            const b = banner();
            if (b) {
                b.hidden = false;
            }
        },
        onOff(ctx) {
            if (probe.handler) {
                ctx.map.off('click', probe.handler);
            }
            probe.handler = null;
            const b = banner();
            if (b) {
                b.hidden = true;
            }
        }
    });
})();
