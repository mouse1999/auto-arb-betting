package com.mouse.bet.converter;

import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BetLegStatus;
import com.mouse.bet.model.NormalizedOutcome;

import java.math.BigDecimal;
import java.util.Objects;

public class ModelConverter {

    public static BetLeg fromOutcome(NormalizedOutcome outcome,
                                     BigDecimal stake,
                                     BigDecimal rawStake,
                                     boolean primaryLeg) {
        Objects.requireNonNull(outcome, "outcome must not be null");


        return BetLeg.builder()
                .bookmaker(outcome.getBookmaker())
                .eventId(outcome.getEventId())
                .homeTeam(outcome.getHomeTeam())
                .awayTeam(outcome.getAwayTeam())
                .league(outcome.getLeague())
                .sportEnum(outcome.getSportEnum())

                .odds(outcome.getOdds())
                .outcomeId(outcome.getOutcomeId())
                .outcomeDescription(outcome.getOutcomeDescription())
                .period(outcome.getPeriod())
                .matchStatus(outcome.getMatchStatus())
                .cashOutAvailable(outcome.getCashOutAvailable())

                .status(BetLegStatus.PENDING)
                .attemptCount(0)
                .isPrimaryLeg(primaryLeg)

                .rawStake(rawStake)
                .stake(stake)
                .potentialPayout(stake.multiply(outcome.getOdds()))

                .build();
    }

    /**
     * Convenience overload without stake (e.g., pre-sizing phase).
     */
    public static BetLeg fromOutcome(NormalizedOutcome outcome) {
        return fromOutcome(outcome, null, null, false);
    }
}
