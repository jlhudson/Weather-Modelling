package au.gully.api;

import au.gully.science.CsiroGrassland;
import au.gully.science.FireDanger;
import au.gully.science.GrassFireDanger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static au.gully.science.Numbers.round1;
import static au.gully.science.Numbers.round2;

/**
 * {@code GET /api/v1/fire-indices}: the fire indices for a set of inputs, from the one set of
 * formulas everything else here uses (docs/06 item 4). For a calculator, a what-if, or a check
 * against a published worked example: nothing is fetched and nothing is held.
 */
@RestController
@Tag(name = "fire-indices", description = "The fire indices from given inputs")
public class FireIndicesController {

    @GetMapping(path = "/api/v1/fire-indices", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "McArthur forest and grassland indices, and the AFDRS grassland index, for given inputs")
    public Result compute(
            @Parameter(description = "air temperature, °C") @RequestParam double temperatureC,
            @Parameter(description = "relative humidity, %") @RequestParam int humidityPct,
            @Parameter(description = "10 m mean wind, km/h") @RequestParam double windKmh,
            @Parameter(description = "Griffiths drought factor, 0-10") @RequestParam(defaultValue = "10") double droughtFactor,
            @Parameter(description = "grass curing, %; without it there is no grassland index") @RequestParam(required = false) Double curingPct,
            @Parameter(description = "fine fuel load, t/ha; McArthur's 4.5 by default") @RequestParam(defaultValue = "4.5") double fuelLoadTHa,
            @Parameter(description = "grass condition: natural, grazed or eaten-out; grazed by default") @RequestParam(required = false) String condition) {
        if (humidityPct < 0 || humidityPct > 100) {
            throw ReadingsController.bad("humidityPct must be 0..100");
        }
        if (droughtFactor < 0 || droughtFactor > 10) {
            throw ReadingsController.bad("droughtFactor must be 0..10");
        }
        if (curingPct != null && (curingPct < 0 || curingPct > 100)) {
            throw ReadingsController.bad("curingPct must be 0..100");
        }
        double ffdi = round1(FireDanger.ffdi(temperatureC, humidityPct, windKmh, droughtFactor));
        Grass grass = null;
        if (curingPct != null && curingPct > 0) {
            double gfdi = round1(GrassFireDanger.gfdi(temperatureC, humidityPct, windKmh, curingPct, fuelLoadTHa));
            CsiroGrassland.Condition c = condition == null ? CsiroGrassland.Condition.fromLoad(fuelLoadTHa) : CsiroGrassland.Condition.parse(condition);
            CsiroGrassland.Result csiro = CsiroGrassland.of(temperatureC, humidityPct, windKmh, curingPct, fuelLoadTHa, c);
            grass = new Grass(curingPct, fuelLoadTHa, c.name().toLowerCase().replace('_', '-'),
                    round1(GrassFireDanger.moisture(temperatureC, humidityPct, curingPct)), gfdi, GrassFireDanger.rating(gfdi),
                    round2(GrassFireDanger.spreadKmh(gfdi)), csiro.moisturePct(), csiro.rateOfSpreadKmh(), csiro.intensityKwm(),
                    csiro.flameHeightM(), csiro.fbi(), csiro.rating());
        }
        return new Result(new Inputs(temperatureC, humidityPct, windKmh, droughtFactor),
                new Forest(ffdi, FireDanger.rating(ffdi)), grass);
    }

    public record Result(Inputs inputs, Forest forest, Grass grass) {
    }

    public record Inputs(double temperatureC, int humidityPct, double windKmh, double droughtFactor) {
    }

    public record Forest(double ffdi, String ffdiRating) {
    }

    /**
     * @param mcArthurMoisturePct the McArthur meter's grass moisture, which its index is drawn from
     * @param moisturePct         the CSIRO model's dead fuel moisture, floored at 5
     */
    public record Grass(double curingPct, double fuelLoadTHa, String condition, double mcArthurMoisturePct,
                        double gfdi, String gfdiRating, double spreadKmh, double moisturePct, double rateOfSpreadKmh,
                        long intensityKwm, double flameHeightM, int fbi, String afdrsRating) {
    }
}
