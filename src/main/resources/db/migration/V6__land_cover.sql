-- W-15: the land-cover raster a hexagon's land use was counted from, when it was read from Digital
-- Earth Australia for the hexagon rather than a mounted file: a few kilobytes of GeoTIFF, kept so the
-- class at any point later asked about is read off it.
alter table hexagon add column if not exists land_cover bytea;
