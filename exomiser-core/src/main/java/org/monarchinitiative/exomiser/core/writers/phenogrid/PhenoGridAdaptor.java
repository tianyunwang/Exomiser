/*
 * The Exomiser - A tool to annotate and prioritize variants
 *
 * Copyright (C) 2012 - 2016  Charite Universitätsmedizin Berlin and Genome Research Ltd.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.monarchinitiative.exomiser.core.writers.phenogrid;

import org.monarchinitiative.exomiser.core.model.*;
import org.monarchinitiative.exomiser.core.prioritisers.HiPhivePriorityResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Makes a Phenogrid from a set of HiPhivePriorityResults
 * @author Jules Jacobsen <jules.jacobsen@sanger.ac.uk>
 */
public class PhenoGridAdaptor {

    private static final Logger logger = LoggerFactory.getLogger(PhenoGridAdaptor.class);
    
    protected static final PhenoGridMatchTaxon HUMAN_TAXON = new PhenoGridMatchTaxon("NCBITaxon:9606", "Homo sapiens");
    protected static final PhenoGridMatchTaxon MOUSE_TAXON = new PhenoGridMatchTaxon("NCBITaxon:10090", "Mus musculus");
    protected static final PhenoGridMatchTaxon FISH_TAXON = new PhenoGridMatchTaxon("NCBITaxon:7955", "Danio rerio");

    public PhenoGrid makePhenoGridFromHiPhiveResults(String phenoGridId, List<HiPhivePriorityResult> hiPhiveResults) {
        Set<String> phenotypeIds = new LinkedHashSet<>();
        if (!hiPhiveResults.isEmpty()) {
            HiPhivePriorityResult result = hiPhiveResults.get(0);
            for (PhenotypeTerm phenoTerm : result.getQueryPhenotypeTerms()) {
                phenotypeIds.add(phenoTerm.getId());
            }
        }
        PhenoGridQueryTerms phenoGridQueryTerms = new PhenoGridQueryTerms(phenoGridId, phenotypeIds);
        
        List<ModelPhenotypeMatch> diseaseModels = new ArrayList<>();
        List<ModelPhenotypeMatch> mouseModels = new ArrayList<>();
        List<ModelPhenotypeMatch> fishModels = new ArrayList<>();

        for (HiPhivePriorityResult result : hiPhiveResults) {
            for (ModelPhenotypeMatch model : result.getPhenotypeEvidence()) {
                switch(model.getOrganism()) {
                    case HUMAN:
                        diseaseModels.add(model);
                        break;
                    case MOUSE:
                        mouseModels.add(model);
                        break;
                    case FISH:
                        fishModels.add(model);
                        break;
                }
            }
        }
        List<PhenoGridMatchGroup> phenoGridMatchGroups = createPhenogridMatchGroups(phenotypeIds, diseaseModels, mouseModels, fishModels);
        
        return new PhenoGrid(phenoGridQueryTerms, phenoGridMatchGroups);        
    }

    private List<PhenoGridMatchGroup> createPhenogridMatchGroups(Set<String> phenotypeIds, List<ModelPhenotypeMatch> diseaseModels, List<ModelPhenotypeMatch> mouseModels, List<ModelPhenotypeMatch> fishModels) {
        List<PhenoGridMatchGroup> phenoGridMatchGroups = new ArrayList<>();
        
        if (!diseaseModels.isEmpty()){
            PhenoGridMatchGroup diseaseMatchGroup = makePhenoGridMatchGroup(HUMAN_TAXON, diseaseModels, phenotypeIds);
            phenoGridMatchGroups.add(diseaseMatchGroup);
        }
        
        if (!mouseModels.isEmpty()){
            PhenoGridMatchGroup mouseMatchGroup = makePhenoGridMatchGroup(MOUSE_TAXON, mouseModels, phenotypeIds);
            phenoGridMatchGroups.add(mouseMatchGroup);
        }
        
        if(!fishModels.isEmpty()) {
            PhenoGridMatchGroup fishMatchGroup = makePhenoGridMatchGroup(FISH_TAXON, fishModels, phenotypeIds);
            phenoGridMatchGroups.add(fishMatchGroup);
        }
        
        return phenoGridMatchGroups;
    }

    private PhenoGridMatchGroup makePhenoGridMatchGroup(PhenoGridMatchTaxon taxon, List<ModelPhenotypeMatch> modelPhenotypeMatches, Set<String> phenotypeIds) {
        List<PhenoGridMatch> phenoGridMatches = makePhenogridMatchesFromModels(modelPhenotypeMatches, taxon);
        PhenoGridMatchGroup phenoGridMatchGroup = new PhenoGridMatchGroup(phenoGridMatches, phenotypeIds);
        return phenoGridMatchGroup;
    }

    private List<PhenoGridMatch> makePhenogridMatchesFromModels(List<ModelPhenotypeMatch> diseaseGeneModels, PhenoGridMatchTaxon taxon) {
        List<PhenoGridMatch> phenoGridMatches = new ArrayList<>();
        //the models will be ordered according to the exomiser combined score, we want to re-order things purely by phenotype score
        Collections.sort(diseaseGeneModels, new DescendingScoreBasedModelComparator());
        
        int modelCount = 0;
        for (ModelPhenotypeMatch modelPhenotypeMatch : diseaseGeneModels) {
            PhenoGridMatchScore score = new PhenoGridMatchScore("hiPhive", (int) (modelPhenotypeMatch.getScore() * 100f), modelCount++);
            logger.debug("Made new {} score modelScore:{} gridScore:{} rank:{}", modelPhenotypeMatch.getOrganism(), modelPhenotypeMatch.getScore(), score.getScore(), score.getRank());
            List<PhenotypeMatch> phenotypeMatches = modelPhenotypeMatch.getBestModelPhenotypeMatches();
            if (modelPhenotypeMatch.getOrganism() == Organism.HUMAN) {
                PhenoGridMatch match = makeDiseasePhenoGridMatch(modelPhenotypeMatch, phenotypeMatches, score, taxon);
                phenoGridMatches.add(match);            
            } else {
                PhenoGridMatch match = makeGenePhenoGridMatch(modelPhenotypeMatch, phenotypeMatches, score, taxon);
                phenoGridMatches.add(match);  
            }
        }
        
        return phenoGridMatches;
    }

    private PhenoGridMatch makeDiseasePhenoGridMatch(ModelPhenotypeMatch modelPhenotypeMatch, List<PhenotypeMatch> phenotypeMatches, PhenoGridMatchScore score, PhenoGridMatchTaxon taxon) {
        GeneDiseaseModel geneDiseaseModel = (GeneDiseaseModel) modelPhenotypeMatch.getModel();
        return new PhenoGridMatch(geneDiseaseModel.getDiseaseId(), geneDiseaseModel.getDiseaseTerm(), "disease", phenotypeMatches, score, taxon);
    }
    
    private PhenoGridMatch makeGenePhenoGridMatch(ModelPhenotypeMatch modelPhenotypeMatch, List<PhenotypeMatch> phenotypeMatches, PhenoGridMatchScore score, PhenoGridMatchTaxon taxon) {
        GeneOrthologModel geneOrthologModel = (GeneOrthologModel) modelPhenotypeMatch.getModel();
        return new PhenoGridMatch(geneOrthologModel.getModelGeneId(), geneOrthologModel.getModelGeneSymbol(), "gene", phenotypeMatches, score, taxon);
    }

    private static class DescendingScoreBasedModelComparator implements Comparator<ModelPhenotypeMatch> {
        @Override
        public int compare(ModelPhenotypeMatch model1, ModelPhenotypeMatch model2) {
            //we want the results in descending order i.e. greater score first
            return - Double.compare(model1.getScore(), model2.getScore());
        }
    }
    
}
