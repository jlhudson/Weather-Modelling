-- A dropped point (W-7): a place nobody's reach contained, given a station's row of its own - the
-- same terrain, reach, record and drought as a Bureau station - with its current from the model
-- rather than a file. The kind tells them apart; last_asked_at is what a point expires on, 548 days
-- after the last reading that used it.
alter table station add column if not exists kind varchar(8) not null default 'bureau';
alter table station add column if not exists last_asked_at timestamptz;
create index if not exists ix_station_kind on station (kind);
