package au.weather.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WeatherRepositories {

    interface WeatherAnchorRepository extends JpaRepository<WeatherAnchorEntity, UUID> {

        List<WeatherAnchorEntity> findByFetchedAtAfter(Instant since);

        void deleteByFetchedAtBefore(Instant before);
    }

    interface WeatherCallRepository extends JpaRepository<WeatherCallEntity, Long> {

        /**
         * Allowance spent by one provider inside one window. Null when the provider has never been called.
         */
        @Query("select coalesce(sum(c.weight), 0) from WeatherCallEntity c where c.provider = :provider and c.at >= :since")
        double weightSince(String provider, Instant since);

        List<WeatherCallEntity> findByProviderAndAtGreaterThanEqual(String provider, Instant since);

        List<WeatherCallEntity> findTop50ByOrderByAtDesc();

        void deleteByAtBefore(Instant before);
    }

    interface DroughtCellRepository extends JpaRepository<DroughtCellEntity, UUID> {

        List<DroughtCellEntity> findByComputedForGreaterThanEqual(LocalDate from);

        void deleteByComputedForBefore(LocalDate before);
    }

    interface RiverCellRepository extends JpaRepository<RiverCellEntity, UUID> {

        List<RiverCellEntity> findByComputedForGreaterThanEqual(LocalDate from);

        void deleteByComputedForBefore(LocalDate before);
    }
}
