-- How far a station is from the sea (W-19), found when its terrain is sampled: the open sea and the
-- gulfs, not the salt lakes. A reach grows with it by the rule's inland share. Null where not known.
-- The terrain is now sampled to 150 km; a row sampled to 50 is read as absent and sampled again.
alter table terrain add column if not exists inland_km double precision;
