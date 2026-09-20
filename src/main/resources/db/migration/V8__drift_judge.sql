-- The drift ledger's judge is one station, or the stations of a blend joined with "+" (docs/02 §2.3);
-- a hexagon with three inside it is twenty characters, and varchar(16) refused the row - and with it
-- the reading. Unbounded, as a list is.
alter table forecast_drift alter column station_id type text;
