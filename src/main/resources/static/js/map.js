// No zoom glide and no tile fade-in, on any map this console draws.
//
// Leaflet animates a zoom as a 250 ms CSS transition on the tile containers and fades each tile in
// with a requestAnimationFrame loop. Both assume a renderer that paints continuously. The embedded
// browser pane the console is reviewed in does not: it paints on demand, so a zoom is caught half-way
// with the tiles at one scale and the SVG pins projected for another, and a tile left at the opacity
// of its first frame stays invisible - the map that "will not load anything" and the pins that are
// "all wrong until refresh" (12 Sep 2026). Without the animations a zoom is one synchronous
// reprojection and a tile is opaque the moment it arrives, which is right on every renderer and costs
// only the glide. mergeOptions changes the defaults for every map created after this file runs, which
// is all of them: layout.html loads this after leaflet.js and every page builds its map on
// DOMContentLoaded.
L.Map.mergeOptions({zoomAnimation: false, fadeAnimation: false, markerZoomAnimation: false});

// The basemaps every map surface offers, and the one map every page draws.
//
// CARTO first, while the page carries a key (James, 14 September 2026: "light and dark mode carto
// maps"): its light_all and dark_all styles are drawn for each theme, so the map follows the console's
// Light / Dark / System choice by swapping its tile URL rather than by filtering. CARTO began stamping
// "API key required" across unkeyed tiles in August 2026, which is why it was dropped for a while and
// why it is only offered when CARTO_API_KEY is set; a free key covers five million tiles a month.
//
// OpenStreetMap is the keyless fallback and the default without a key. Its dark variant is a CSS
// filter on the tile pane, declared in layout.html, so switching theme repaints the existing tiles
// instead of refetching them. Imagery and hypsometric tints cannot be inverted without becoming a
// negative, and CARTO's dark style is already dark, so those layers carry hubNoInvert and the
// container is marked while one of them shows. OpenTopoMap (contours and hillshade, what a radio
// planner wants) and Esri's imagery were added for the MeshCore planner. The choice is remembered in
// this browser.
window.hubCartoKey = function () {
    const meta = document.querySelector('meta[name="hub-carto-key"]');
    return meta ? (meta.getAttribute('content') || '').trim() : '';
};

window.hubDark = function () {
    return document.documentElement.getAttribute('data-bs-theme') === 'dark';
};

window.hubCartoUrl = function (dark) {
    return 'https://basemaps.cartocdn.com/rastertiles/' + (dark ? 'dark_all' : 'light_all')
        + '/{z}/{x}/{y}{r}.png?key=' + encodeURIComponent(window.hubCartoKey());
};

window.hubBaseLayers = function () {
    const bases = {};
    if (window.hubCartoKey()) {
        bases['CARTO'] = L.tileLayer(window.hubCartoUrl(window.hubDark()), {
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors '
                + '&copy; <a href="https://carto.com/attributions">CARTO</a>',
            maxZoom: 20, hubNoInvert: true, hubCarto: true
        });
    }
    return Object.assign(bases, {
        'OpenStreetMap': L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
            maxZoom: 19
        }),
        'OpenTopoMap': L.tileLayer('https://tile.opentopomap.org/{z}/{x}/{y}.png', {
            attribution: 'Map data &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors, SRTM '
                + '&middot; Map style &copy; <a href="https://opentopomap.org">OpenTopoMap</a> (CC-BY-SA)',
            maxZoom: 17, hubNoInvert: true
        }),
        'Esri World Imagery': L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
            attribution: 'Tiles &copy; Esri &mdash; Esri, Maxar, Earthstar Geographics, and the GIS User Community',
            maxZoom: 19, hubNoInvert: true
        })
    });
};

window.hubBaseLayer = function (map) {
    if (map._hubTiles) {
        return map._hubTiles;
    }
    const bases = window.hubBaseLayers();
    const preferred = bases['CARTO'] ? 'CARTO' : 'OpenStreetMap';
    let chosen = preferred;
    try {
        chosen = localStorage.getItem('weather.map.base') || chosen;
    } catch (e) { /* storage blocked: the default is fine */
    }
    if (!bases[chosen]) {
        chosen = preferred;
    }
    const mark = layer => map.getContainer().classList.toggle('hub-no-invert', !!(layer && layer.options.hubNoInvert));
    map._hubTiles = bases[chosen].addTo(map);
    mark(map._hubTiles);
    L.control.layers(bases, null, {position: 'topright'}).addTo(map);
    // The theme changed under the map: CARTO has a style for each, so point it at the other one. The
    // layer is retargeted whether or not it is showing, so choosing it later shows the right style.
    if (bases['CARTO']) {
        document.addEventListener('hub:theme', e => bases['CARTO'].setUrl(window.hubCartoUrl(e.detail.dark)));
    }
    map.on('baselayerchange', e => {
        map._hubTiles = e.layer;
        mark(e.layer);
        try {
            localStorage.setItem('weather.map.base', e.name);
        } catch (err) { /* nothing to remember with */
        }
    });
    // Leaflet measures its container once and again only on a window resize, and even that it
    // defers to an animation frame. A map built while its pane was collapsed to nothing keeps a
    // zero-width view: a strip of tiles down the middle, clicks that land on the wrong ground, and
    // an SVG too small to hold the pins. Re-measure whenever the container itself changes size.
    if (typeof ResizeObserver !== 'undefined') {
        const watcher = new ResizeObserver(() => map.invalidateSize({animate: false}));
        watcher.observe(map.getContainer());
        map.on('unload', () => watcher.disconnect());
    }
    return map._hubTiles;
};

// Every page draws one map into #map, and more than one script may want it: the console map builds it,
// the operations layers attach to it. First caller wins, the rest share.
window.hubMapAt = function (centre, zoom) {
    if (window.hubMap) {
        return window.hubMap;
    }
    window.hubMap = L.map('map').setView(centre, zoom);
    window.hubBaseLayer(window.hubMap);
    return window.hubMap;
};
