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

package de.charite.compbio.exomiser.core.prioritisers.util;

import de.charite.compbio.exomiser.core.model.InheritanceMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author Jules Jacobsen <jules.jacobsen@sanger.ac.uk>
 */
public final class DiseaseData {

    public enum DiseaseType {

        DISEASE("disease", "D"),
        NON_DISEASE("non-disease", "N"),
        SUSCEPTIBILITY("susceptibility", "S"),
        UNCONFIRMED("unconfirmed", "?"),
        CNV("CNV", "C");

        private final String value;
        private final String columnValue;

        private DiseaseType(String value, String columnValue) {
            this.value = value;
            this.columnValue = columnValue;
        }

        public static DiseaseType code(String key) {
            for (DiseaseType diseaseType : DiseaseType.values()) {
                if (diseaseType.columnValue.equals(key)) {
                    return diseaseType;
                }
            }
            return UNCONFIRMED;
        }

        public String getValue() {
            return value;
        }

        public String getColumnValue() {
            return columnValue;
        }
    }

    private final String diseaseId;
    private final String diseaseName;

    private final int entrezGeneId;
    private final String humanGeneSymbol;

    private final DiseaseType diseaseType;
    private final InheritanceMode inheritanceMode;

    private final List<String> phenotypeIds;

    private DiseaseData(DiseaseDataBuilder builder) {
        this.diseaseId = builder.diseaseId;
        this.diseaseName = builder.diseaseName;
        this.entrezGeneId = builder.entrezGeneId;
        this.humanGeneSymbol = builder.humanGeneSymbol;
        this.diseaseType = builder.diseaseType;
        this.inheritanceMode = builder.inheritanceMode;
        this.phenotypeIds = builder.phenotypeIds;
    }

    public String getDiseaseId() {
        return diseaseId;
    }

    public String getDiseaseName() {
        return diseaseName;
    }

    public int getAssociatedGeneId() {
        return entrezGeneId;
    }

    public String getAssociatedGeneSymbol() {
        return humanGeneSymbol;
    }

    public DiseaseType getDiseaseType() {
        return diseaseType;
    }

    public InheritanceMode getInheritanceMode() {
        return inheritanceMode;
    }

    public List<String> getPhenotypeIds() {
        return phenotypeIds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DiseaseData)) return false;
        DiseaseData that = (DiseaseData) o;
        return entrezGeneId == that.entrezGeneId &&
                Objects.equals(diseaseId, that.diseaseId) &&
                Objects.equals(diseaseName, that.diseaseName) &&
                Objects.equals(humanGeneSymbol, that.humanGeneSymbol) &&
                diseaseType == that.diseaseType &&
                inheritanceMode == that.inheritanceMode &&
                Objects.equals(phenotypeIds, that.phenotypeIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(diseaseId, diseaseName, entrezGeneId, humanGeneSymbol, diseaseType, inheritanceMode, phenotypeIds);
    }


    @Override
    public String toString() {
        return "DiseaseData{" +
                "diseaseId='" + diseaseId + '\'' +
                ", diseaseName='" + diseaseName + '\'' +
                ", entrezGeneId=" + entrezGeneId +
                ", humanGeneSymbol='" + humanGeneSymbol + '\'' +
                ", diseaseType=" + diseaseType +
                ", inheritanceMode=" + inheritanceMode +
                ", phenotypeIds=" + phenotypeIds +
                '}';
    }

    public static DiseaseDataBuilder builder() {
        return new DiseaseDataBuilder();
    }

    public static final class DiseaseDataBuilder {

        private String diseaseId = "";
        private String diseaseName = "";

        private int entrezGeneId = 0;
        private String humanGeneSymbol = "";

        private DiseaseType diseaseType = DiseaseType.UNCONFIRMED;
        private InheritanceMode inheritanceMode = InheritanceMode.UNKNOWN;

        private List<String> phenotypeIds = new ArrayList<>();

        private DiseaseDataBuilder() {
        }

        public DiseaseDataBuilder diseaseId(String diseaseId) {
            this.diseaseId = diseaseId;
            return this;
        }

        public DiseaseDataBuilder diseaseName(String diseaseName) {
            this.diseaseName = diseaseName;
            return this;
        }

        public DiseaseDataBuilder associatedGeneId(int entrezGeneId) {
            this.entrezGeneId = entrezGeneId;
            return this;
        }

        public DiseaseDataBuilder associatedGeneSymbol(String humanGeneSymbol) {
            this.humanGeneSymbol = humanGeneSymbol;
            return this;
        }

        public DiseaseDataBuilder diseaseType(DiseaseType diseaseType) {
            this.diseaseType = diseaseType;
            return this;
        }

        public DiseaseDataBuilder diseaseTypeCode(String diseaseCode) {
            this.diseaseType = DiseaseType.code(diseaseCode);
            return this;
        }

        public DiseaseDataBuilder inheritanceMode(InheritanceMode inheritanceMode) {
            this.inheritanceMode = inheritanceMode;
            return this;
        }

        public DiseaseDataBuilder inheritanceModeCode(String inheritanceCode) {
            this.inheritanceMode = InheritanceMode.valueOfInheritanceCode(inheritanceCode);
            return this;
        }

        public DiseaseDataBuilder phenotypeIds(List<String> phenotypeIds) {
            this.phenotypeIds = phenotypeIds;
            return this;
        }

        public DiseaseData build() {
            return new DiseaseData(this);
        }

    }
}
