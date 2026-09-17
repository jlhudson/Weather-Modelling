package au.weather.access;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Which key read what. Cheap now and impossible to reconstruct later (OQ-22, G3).
 */
@Entity
@Table(name = "api_access_log", indexes = @Index(name = "ix_api_access_key_at", columnList = "api_key_id, at"))
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Getter
@Setter
@NoArgsConstructor
public class ApiAccessLogEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "api_key_id")
    private Long apiKeyId;

    @Column(length = 64)
    private String consumer;

    @Column(nullable = false, length = 8)
    private String method;

    @Column(nullable = false, length = 512)
    private String path;

    @Column(length = 1024)
    private String query;

    @Column(nullable = false)
    private int status;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(length = 64)
    private String remoteIp;
}
