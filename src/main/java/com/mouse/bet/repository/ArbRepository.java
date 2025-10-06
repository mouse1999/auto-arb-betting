package com.mouse.bet.repository;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.enums.OutcomeStatus;
import com.mouse.bet.enums.Sport;
import com.mouse.bet.enums.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArbRepository extends JpaRepository<Arb, Long> {
    List<Arb> findByStatus(Status status);

    List<Arb> findByActiveTrue();
    Optional<Arb> findByEventId(String eventId);

    List<Arb> findByLeague(String league);

    // Find by sport and league
    List<Arb> findBySportAndLeague(Sport sport, String league);

    // Find by teams
    List<Arb> findByHomeTeamOrAwayTeam(String homeTeam, String awayTeam);

}
