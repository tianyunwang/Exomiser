package org.monarchinitiative.exomiser.core.prioritisers.util;

import org.monarchinitiative.exomiser.core.model.Model;
import org.monarchinitiative.exomiser.core.model.PhenotypeMatch;
import org.monarchinitiative.exomiser.core.model.PhenotypeTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phive algorithm for scoring the semantic similarity of a model against the best theoretical model
 * for a set of phenotypes in a given organism.
 *
 * @since 8.0.0
 * @author Jules Jacobsen <j.jacobsen@qmul.ac.uk>
 */
public class PhiveModelScorer implements ModelScorer {

    private static final Logger logger = LoggerFactory.getLogger(PhiveModelScorer.class);

    private final double theoreticalMaxMatchScore;
    private final double theoreticalBestAvgScore;

    private final OrganismPhenotypeMatches organismPhenotypeMatches;
    private final int numQueryPhenotypes;

    /**
     * Use this constructor when running a single (HP-HP) or single cross-species (e.g. HP-MP) comparisons.
     * For multi cross-species comparisons use the constructor which requires the {@link TheoreticalModel} against which
     * all models are compared.
     *
     * @param organismPhenotypeMatches the best phenotype matches for this organism e.g. HP-HP, HP-MP or HP-MP
     * @param numQueryPhenotypes
     */
    PhiveModelScorer(OrganismPhenotypeMatches organismPhenotypeMatches, int numQueryPhenotypes) {
        this(organismPhenotypeMatches.getBestTheoreticalModel(), organismPhenotypeMatches, numQueryPhenotypes);
    }

    /**
     * Use this constructor when running multi cross-species comparisons. For single or single cross-species comparisons,
     * use the alternate constructor.
     *
     * @param theoreticalModel against which all models are compared.
     * @param organismPhenotypeMatches the best phenotype matches for this organism e.g. HP-HP, HP-MP or HP-MP
     * @param numQueryPhenotypes
     */
    PhiveModelScorer(TheoreticalModel theoreticalModel, OrganismPhenotypeMatches organismPhenotypeMatches, int numQueryPhenotypes) {
        this.theoreticalMaxMatchScore = theoreticalModel.getMaxMatchScore();
        this.theoreticalBestAvgScore = theoreticalModel.getBestAvgScore();

        this.organismPhenotypeMatches = organismPhenotypeMatches;
        this.numQueryPhenotypes = numQueryPhenotypes;
        logOrganismPhenotypeMatches();
    }

    private void logOrganismPhenotypeMatches() {
        logger.info("Best {} phenotype matches:", organismPhenotypeMatches.getOrganism());
        Map<PhenotypeTerm, Set<PhenotypeMatch>> termPhenotypeMatches = organismPhenotypeMatches.getTermPhenotypeMatches();
        for (Map.Entry<PhenotypeTerm, Set<PhenotypeMatch>> entry : termPhenotypeMatches.entrySet()) {
            PhenotypeTerm queryTerm = entry.getKey();
            Set<PhenotypeMatch> matches = entry.getValue();
            if (matches.isEmpty()) {
                logger.info("{}-NOT MATCHED", queryTerm.getId());
            } else {
                PhenotypeMatch bestMatch = matches.stream()
                        .max(Comparator.comparingDouble(PhenotypeMatch::getScore))
                        .get();
                logger.info("{}-{}={}", queryTerm.getId(), bestMatch.getMatchPhenotypeId(), bestMatch.getScore());
            }
        }
        TheoreticalModel organismTheoreticalModel = organismPhenotypeMatches.getBestTheoreticalModel();
        logger.info("bestMaxScore={} bestAvgScore={}", organismTheoreticalModel.getMaxMatchScore(), organismTheoreticalModel.getBestAvgScore());
    }

    @Override
    public ModelPhenotypeMatchScore scoreModel(Model model) {
        OrganismPhenotypeMatchScore rawModelScore = organismPhenotypeMatches.calculateModelPhenotypeScores(model.getPhenotypeIds());
        double score = calculateCombinedScore(rawModelScore);
        return ModelPhenotypeMatchScore.of(score, model, rawModelScore.getBestPhenotypeMatches());
    }

    private double calculateCombinedScore(OrganismPhenotypeMatchScore rawModelScore) {
        double maxModelMatchScore = rawModelScore.getMaxModelMatchScore();
        double sumModelBestMatchScores = rawModelScore.getSumModelBestMatchScores();
        int numMatchingPhenotypesForModel = rawModelScore.getMatchingPhenotypes().size();

        /**
         * hpIdsWithPhenotypeMatch.size() = no. of HPO disease annotations for human and the no. of annotations with an entry in hp_*_mappings table for other species
         * matchedPhenotypeIDsForModel.size() = no. of annotations for model with a match in hp_*_mappings table for at least one of the disease annotations
         * Aug 2015 - changed calculation to take into account all HPO terms for averaging after DDD benchmarking - keeps consistent across species then
         */
        //int rowColumnCount = hpIdsWithPhenotypeMatch.size() + matchedPhenotypeIdsForModel.size();
        int totalPhenotypesWithMatch = numQueryPhenotypes + numMatchingPhenotypesForModel;
        if (sumModelBestMatchScores > 0) {
            double modelBestAvgScore = sumModelBestMatchScores / totalPhenotypesWithMatch;
            // calculate combined score
            double combinedScore = 50 * (maxModelMatchScore / theoreticalMaxMatchScore + modelBestAvgScore / theoreticalBestAvgScore);
            if (combinedScore > 100) {
                combinedScore = 100;
            }
            return combinedScore / 100;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PhiveModelScorer that = (PhiveModelScorer) o;
        return Double.compare(that.theoreticalMaxMatchScore, theoreticalMaxMatchScore) == 0 &&
                Double.compare(that.theoreticalBestAvgScore, theoreticalBestAvgScore) == 0 &&
                numQueryPhenotypes == that.numQueryPhenotypes &&
                Objects.equals(organismPhenotypeMatches, that.organismPhenotypeMatches);
    }

    @Override
    public int hashCode() {
        return Objects.hash(theoreticalMaxMatchScore, theoreticalBestAvgScore, organismPhenotypeMatches, numQueryPhenotypes);
    }

    @Override
    public String toString() {
        return "PhiveModelScorer{" +
                "theoreticalMaxMatchScore=" + theoreticalMaxMatchScore +
                ", theoreticalBestAvgScore=" + theoreticalBestAvgScore +
                ", organismPhenotypeMatches=" + organismPhenotypeMatches +
                ", numQueryPhenotypes=" + numQueryPhenotypes +
                '}';
    }
}
