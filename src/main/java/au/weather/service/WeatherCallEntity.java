package au.weather.service;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One upstream call, recorded whether it succeeded or not. This is the ledger the free allowance is
 * counted against, and the reason the count survives a restart: an in-memory counter that resets on
 * deploy would let a month of Google calls be spent several times over.
 *
 */
@Entity
@Table(name = "weather_call", indexes = @Index(name = "ix_weather_call_provider_at", columnList = "provider, at"))
@Getter
@Setter
@NoArgsConstructor
public class WeatherCallEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false)
    private Instant at;

    @Column(nullable = false)
    private double weight;

    @Column(nullable = false)
    private boolean ok;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(length = 512)
    private String detail;
}
