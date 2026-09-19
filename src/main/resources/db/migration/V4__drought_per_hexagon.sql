-- The drought is the hexagon's, like everything else it holds (hexagon.drought); the shared areas of
-- V2 are gone. Dropping the table loses nothing: every hexagon carries its own copy of the state.
drop table if exists drought_area;
