package au.gully.hexagons;

import au.gully.bureau.Observation;
import au.gully.bureau.StationRegistry;
import au.gully.bureau.Warning;
import au.gully.bureau.WarningsReader;
import au.gully.cfs.Curing;
import au.gully.cfs.Ratings;
import au.gully.science.*;
import au.gully.upstreams.Forecast;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the {@link FirePicture} for a hexagon from what it holds and what the registries hold: the
 * station values where there is a station, the model's otherwise; the drought state; the district's
 * curing; the official rating; the warnings. Called whenever any input changes, never on request.
 */
@Component
public class FirePictures {

    /**
     * How old a station's latest values may be before the model's "now" is used instead. The files
     * refresh every ten minutes; an hour without one means the station or the feed is down.
     */
    static final Duration STATION_STALE = Duration.ofMinutes(70);

    /**
     * The fuel load the grassland indices are run at: McArthur's own standard pasture load.
     */
    static final double GRASS_LOAD_T_HA = GrassFireDanger.STANDARD_LOAD_T_HA;

    /**
     * The window the wind change is searched in, and the hourly view is cut at.
     */
    public static final int FORECAST_HOURS = 48;

    private final StationRegistry stations;
    private final WarningsReader warnings;
    private final Ratings ratings;
    private final Curing curing;

    public FirePictures(StationRegistry stations, WarningsReader warnings, Ratings ratings, Curing curing) {
        this.stations = stations;
        this.warnings = warnings;
        this.ratings = ratings;
        this.curing = curing;
    }

    /**
     * The conditions "now" for a hexagon: its own station's latest values when it has a station and
     * they are fresh, else the model's current block, else nothing.
     */
    public Optional<Now> now(Hexagon h, Instant at) {
        if (h.stationId() != null) {
            Optional<Observation> o = stations.latest(h.stationId());
            if (o.isPresent() && o.get().at() != null && Duration.between(o.get().at(), at).compareTo(STATION_STALE) < 0) {
                return Optional.of(new Now(conditions(o.get()), "station", o.get().at()));
            }
        }
        Forecast f = h.forecast();
        if (f != null && f.current() != null) {
            return Optional.of(new Now(f.current(), "model", f.current().at()));
        }
        return Optional.empty();
    }

    /**
     * A station's values in the reading's own shape. Only what a station measures: no soil, no
     * cloud fraction, no probability — those stay null rather than borrowed from a model.
     */
    public static Conditions conditions(Observation o) {
        return Conditions.at(o.at())
                .temperature(o.temperatureC())
                .apparent(o.apparentTemperatureC())
                .dewPoint(o.dewPointC())
                .humidity(o.humidityPct())
                .wind(o.windSpeedKmh())
                .windDirection(o.windDirectionDeg())
                .gust(o.windGustKmh())
                .precipitation(o.rainSince9amMm())
                .pressure(o.pressureMslHpa())
                .visibility(o.visibilityKm() == null ? null : o.visibilityKm() * 1000)
                .condition(o.cloud())
                .build();
    }

    /**
     * The picture, or null when there is nothing to draw one from: no "now", or no drought state.
     */
    public FirePicture compute(Hexagon h, Instant at) {
        Optional<Now> now = now(h, at);
        if (now.isEmpty()) {
            return null;
        }
        Conditions c = now.get().conditions();
        Forecast f = h.forecast();
        Conditions model = f == null ? null : f.current();
        DroughtState drought = h.drought();
        DroughtIndex index = drought == null ? null : drought.index();

        Double ffdi = index == null ? null : FireDanger.of(c, index.droughtFactor());

        // The grassland indices, where the district has a curing figure.
        Optional<Curing.Entry> curingEntry = curing.forDistrict(h.fireBanDistrict()).filter(e -> e.percent() != null);
        FireDanger.GrassInputs grassInputs = curingEntry
                .map(e -> new FireDanger.GrassInputs(e.percent(), GRASS_LOAD_T_HA, CsiroGrassland.Condition.fromLoad(GRASS_LOAD_T_HA)))
                .orElse(null);
        FirePicture.Grass grass = null;
        if (grassInputs != null) {
            Double gfdi = GrassFireDanger.of(c.temperatureC(), c.humidityPct(), c.windSpeedKmh(), grassInputs.curingPct(), GRASS_LOAD_T_HA);
            CsiroGrassland.Result csiro = CsiroGrassland.of(c.temperatureC(), c.humidityPct(), c.windSpeedKmh(),
                    grassInputs.curingPct(), GRASS_LOAD_T_HA, grassInputs.condition());
            grass = FirePicture.Grass.of(curingEntry.get().percent(), curingEntry.get().enteredOn(), GRASS_LOAD_T_HA, gfdi, csiro);
        }

        // The outlook, day by day, with the deficit carried forward through the forecast.
        List<FireOutlook> outlook = List.of();
        if (index != null && f != null && !f.daily().isEmpty()) {
            List<FireDanger.FireDay> days = new ArrayList<>();
            for (DayOutlook d : f.daily()) {
                days.add(new FireDanger.FireDay(d.date(), d.maxTemperatureC(), d.minHumidityPct(), d.maxWindKmh(), d.maxGustKmh(), d.precipitationMm()));
            }
            outlook = FireDanger.outlook(days, index.kbdiMm(), index.meanAnnualRainfallMm(), index.recentRainMm(), grassInputs);
        }
        Double peak = ffdi;
        for (FireOutlook day : outlook) {
            if (day.ffdi() != null && (peak == null || day.ffdi() > peak)) {
                peak = day.ffdi();
            }
        }

        FirePicture.Official official = ratings.rating(h.fireBanDistrict()).map(r -> {
            Optional<Ratings.RatingDay> today = r.at(at);
            return new FirePicture.Official(r.district(),
                    today.map(Ratings.RatingDay::rating).orElse(null),
                    today.map(Ratings.RatingDay::fbi).orElse(null),
                    today.map(Ratings.RatingDay::totalFireBan).orElse(false),
                    today.map(Ratings.RatingDay::date).orElse(null),
                    today.map(Ratings.RatingDay::from).orElse(null),
                    today.map(Ratings.RatingDay::to).orElse(null),
                    r.days(), r.readAt());
        }).orElse(null);

        WindChange change = f == null ? null : WindChange.find(f.hourly(), FORECAST_HOURS);
        FirePicture.Wind wind = new FirePicture.Wind(c.windSpeedKmh(), c.windDirectionDeg(), c.windGustKmh(),
                c.windSpeedKmh() == null ? null : Bands.wind(c.windSpeedKmh()), change);

        List<Warning> covering = warnings.forDistrict(h.bureauDistrict(), at);
        boolean fww = covering.stream().anyMatch(Warning::fireWeather);

        LandUse land = h.landUse();
        return new FirePicture(now.get().at(), ffdi, ffdi == null ? null : FireDanger.rating(ffdi), peak,
                index == null ? null : index.droughtFactor(), index == null ? null : index.kbdiMm(),
                index == null ? null : index.kbdiBand(),
                land == null ? null : land.leads(), land == null ? null : land.burnablePct(),
                grass, official, wind, fww, covering, outlook,
                model == null ? null : model.vapourPressureDeficitKpa(),
                model == null ? null : model.soilMoistureSurface(),
                model == null ? null : model.soilMoistureRootZone(),
                model == null ? null : model.boundaryLayerHeightM(),
                model == null ? null : model.windSpeed80mKmh(),
                model == null ? null : model.windDirection80mDeg(),
                model == null ? null : model.capeJkg(),
                model == null ? null : model.liftedIndex());
    }

    /**
     * The conditions now, and where they came from.
     */
    public record Now(Conditions conditions, String from, Instant at) {
    }
}
