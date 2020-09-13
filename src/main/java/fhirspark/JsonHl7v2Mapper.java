package fhirspark;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.Connection;
import ca.uhn.hl7v2.llp.LLPException;
import ca.uhn.hl7v2.model.DataTypeException;
import ca.uhn.hl7v2.model.v281.datatype.CWE;
import ca.uhn.hl7v2.model.v281.datatype.ST;
import ca.uhn.hl7v2.model.v281.group.ORU_R01_PATIENT_RESULT;
import ca.uhn.hl7v2.model.v281.message.ORU_R01;
import ca.uhn.hl7v2.model.v281.segment.NTE;
import ca.uhn.hl7v2.model.v281.segment.OBR;
import ca.uhn.hl7v2.model.v281.segment.OBX;
import ca.uhn.hl7v2.model.v281.segment.SPM;
import fhirspark.resolver.HgncGeneName;
import fhirspark.resolver.OncoKbDrug;
import fhirspark.resolver.PubmedPublication;
import fhirspark.resolver.model.Genenames;
import fhirspark.restmodel.GeneticAlteration;
import fhirspark.restmodel.Mtb;
import fhirspark.restmodel.Reference;
import fhirspark.restmodel.TherapyRecommendation;
import fhirspark.restmodel.Treatment;
import java.io.IOException;
import java.util.List;

/**
 * Fulfils a mapping to the HL7 Version 2 standard and transfers the message to
 * a configured target.
 */
public class JsonHl7v2Mapper {

    private HapiContext context = new DefaultHapiContext();
    private Connection connection;
    private PubmedPublication pubmedResolver = new PubmedPublication();

    public JsonHl7v2Mapper(Settings settings) throws HL7Exception {
        this.connection = context.newClient(settings.getHl7v2config().get(0).getServer(),
                settings.getHl7v2config().get(0).getPort(), false);
    }

    /**
     *
     * @param patientId id of the patient.
     * @param mtbs      mtb entries of the patient.
     * @throws HL7Exception General Exception.
     * @throws IOException  Network Exception.
     * @throws LLPException Exception when sending message.
     */
    public void toHl7v2Oru(String patientId, List<Mtb> mtbs) throws HL7Exception, IOException, LLPException {
        ORU_R01 oru = new ORU_R01();
        oru.initQuickstart("ORU", "R01", "P");

        for (Mtb mtb : mtbs) {

            // Send only finished MTB results
            if (mtb.getMtbState() == null || !mtb.getMtbState().toUpperCase().equals("FINAL")) {
                continue;
            }

            ORU_R01_PATIENT_RESULT result = oru.insertPATIENT_RESULT(oru.getPATIENT_RESULTReps());
            result.getPATIENT().getPID().getPid1_SetIDPID().setValue("1");
            result.getPATIENT().getPID()
                    .getPatientIdentifierList(result.getPATIENT().getPID().getPatientIdentifierListReps()).getIDNumber()
                    .setValue(patientId);

            int therapyRecommendationOrder = result.getORDER_OBSERVATIONReps();

            OBR masterPanel = result.getORDER_OBSERVATION(therapyRecommendationOrder).getOBR();
            masterPanel.getSetIDOBR().setValue(String.valueOf(result.getORDER_OBSERVATIONReps()));
            masterPanel.getResultStatus().setValue("F");
            masterPanel.getUniversalServiceIdentifier().getIdentifier().setValue("81247-9");
            masterPanel.getUniversalServiceIdentifier().getText()
                    .setValue("Master HL7 genetic variant reporting panel");
            masterPanel.getUniversalServiceIdentifier().getNameOfCodingSystem().setValue("LN");
            masterPanel.getFillerOrderNumber().getEntityIdentifier().setValue(mtb.getId());

            masterPanel.getObservationDateTime().setValue(mtb.getDate().replaceAll("-", ""));

            if (mtb.getGeneticCounselingRecommendation() != null && mtb.getGeneticCounselingRecommendation()) {
                addFollowUp(oru, result, therapyRecommendationOrder, "LA14020-4", "Genetic counseling recommended");
            }

            if (mtb.getRebiopsyRecommendation() != null && mtb.getRebiopsyRecommendation()) {
                addFollowUp(oru, result, therapyRecommendationOrder, "LA14021-2", "Confirmatory testing recommended");
            }

            mtb.getSamples().forEach(sample -> addSample(result, therapyRecommendationOrder, sample));

            masterPanel.getFillerOrderNumber().getEntityIdentifier().setValue(mtb.getId());

            NTE generealRecommendation = result.getORDER_OBSERVATION(therapyRecommendationOrder).getNTE(0);
            generealRecommendation.getSetIDNTE().setValue("1");
            generealRecommendation.getSourceOfComment().setValue("L");
            generealRecommendation.getCommentType().getIdentifier().setValue("GI");
            generealRecommendation.getCommentType().getText().setValue("General Instructions");
            generealRecommendation.getComment(0).setValue(mtb.getGeneralRecommendation());

            for (TherapyRecommendation therapyRecommendation : mtb.getTherapyRecommendations()) {

                int orderNumber = result.getORDER_OBSERVATIONReps();
                OBR medicationEfficacy = result.insertORDER_OBSERVATION(orderNumber).getOBR();
                medicationEfficacy.getResultStatus().setValue("F");
                medicationEfficacy.getUniversalServiceIdentifier().getIdentifier().setValue("51961-1");
                medicationEfficacy.getUniversalServiceIdentifier().getText()
                        .setValue("Genetic variation's effect on drug efficacy");
                medicationEfficacy.getUniversalServiceIdentifier().getNameOfCodingSystem().setValue("LN");
                medicationEfficacy.getFillerOrderNumber().getEntityIdentifier().setValue(therapyRecommendation.getId());

                therapyRecommendation.getReasoning().getGeneticAlterations()
                        .forEach(g -> addAlteration(oru, result, g));

                therapyRecommendation.getTreatments()
                        .forEach(treatment -> addTreatment(oru, result, orderNumber, treatment));

                NTE comments = result.getORDER_OBSERVATION(orderNumber).getNTE(0);
                comments.getSetIDNTE().setValue("1");
                comments.getSourceOfComment().setValue("L");
                comments.getCommentType().getIdentifier().setValue("1R");
                comments.getCommentType().getText().setValue("Primary Reason");
                therapyRecommendation.getComment().forEach(comment -> {
                    try {
                        comments.getComment(therapyRecommendation.getComment().indexOf(comment)).setValue(comment);
                    } catch (DataTypeException e1) {
                        // TODO Auto-generated catch block
                        e1.printStackTrace();
                    }
                });

                addEvidenceLevel(oru, result, orderNumber, therapyRecommendation.getEvidenceLevel());

                therapyRecommendation.getReferences()
                        .forEach(reference -> addReference(oru, result, orderNumber, reference));

                // Set authorship
                result.getORDER_OBSERVATIONAll().forEach(order -> {
                    try {
                        order.getOBSERVATIONAll().forEach(observation -> {
                            try {
                                observation.getOBX().insertResponsibleObserver(0).getPersonIdentifier()
                                        .setValue(therapyRecommendation.getAuthor());
                            } catch (HL7Exception e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            }
                        });
                    } catch (HL7Exception e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                });

            }

        }

        if (oru.getPATIENT_RESULTReps() > 0) {
            connection.getInitiator().sendAndReceive(oru.getMessage());
        }

    }

    private void addSample(ORU_R01_PATIENT_RESULT result, int position, String sample) {
        try {
            SPM specimen = result.getORDER_OBSERVATION(position)
                    .getSPECIMEN(result.getORDER_OBSERVATION(position).getSPECIMENReps()).getSPM();
            specimen.getSetIDSPM().setValue(String
                    .valueOf(result.getORDER_OBSERVATION(result.getORDER_OBSERVATIONReps() - 1).getSPECIMENReps()));
            specimen.getSpecimenID().getFillerAssignedIdentifier().getEntityIdentifier().setValue(sample);
            specimen.getSpecimenType().getIdentifier().setValue("TUMOR");
            specimen.getSpecimenType().getText().setValue("Tumor");
        } catch (DataTypeException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void addAlteration(ORU_R01 oru, ORU_R01_PATIENT_RESULT result, GeneticAlteration g) {
        try {
            int orderNumber = result.getORDER_OBSERVATIONReps();
            OBR variant = result.insertORDER_OBSERVATION(orderNumber).getOBR();
            variant.getSetIDOBR().setValue(String.valueOf(result.getORDER_OBSERVATIONReps()));
            variant.getUniversalServiceIdentifier().getIdentifier().setValue("81250-3");
            variant.getUniversalServiceIdentifier().getText().setValue("Discrete genetic variant panel");
            variant.getUniversalServiceIdentifier().getNameOfCodingSystem().setValue("LN");

            OBX variantAssessment = result.getORDER_OBSERVATION(orderNumber).getOBSERVATION(0).getOBX();
            variantAssessment.getSetIDOBX().setValue(String.valueOf(1));
            variantAssessment.getObservationIdentifier().getIdentifier().setValue("69548-6");
            variantAssessment.getObservationIdentifier().getText().setValue("Genetic variant assessment");
            variantAssessment.getObservationIdentifier().getNameOfCodingSystem().setValue("LN");
            variantAssessment.getValueType().setValue("CWE");
            CWE variantAssessmentValue = new CWE(oru);
            variantAssessmentValue.getNameOfCodingSystem().setValue("LN");
            variantAssessmentValue.getText().setValue("Present");
            variantAssessmentValue.getIdentifier().setValue("LA9633-4");
            variantAssessment.insertObservationValue(0).setData(variantAssessmentValue);

            OBX hgvs = result.getORDER_OBSERVATION(orderNumber).getOBSERVATION(1).getOBX();
            hgvs.getSetIDOBX().setValue(String.valueOf(2));
            hgvs.getObservationIdentifier().getIdentifier().setValue("48005-3");
            hgvs.getObservationIdentifier().getText().setValue("Amino acid change (pHGVS)");
            hgvs.getObservationIdentifier().getNameOfCodingSystem().setValue("LN");
            hgvs.getValueType().setValue("CWE");
            CWE hgvsValue = new CWE(oru);
            hgvsValue.getCodingSystemOID().setValue("2.16.840.1.113883.6.282");
            hgvsValue.getText().setValue("p." + g.getAlteration());
            hgvsValue.getIdentifier().setValue("p." + g.getAlteration());
            hgvs.insertObservationValue(0).setData(hgvsValue);

            OBX entrez = result.getORDER_OBSERVATION(orderNumber).getOBSERVATION(2).getOBX();
            entrez.getSetIDOBX().setValue(String.valueOf(3));
            entrez.getObservationIdentifier().getIdentifier().setValue("81252-9");
            entrez.getObservationIdentifier().getText().setValue("Discrete genetic variant");
            entrez.getObservationIdentifier().getNameOfCodingSystem().setValue("LN");
            entrez.getValueType().setValue("CWE");
            CWE entrezValue = new CWE(oru);
            entrezValue.getCodingSystemOID().setValue("2.16.840.1.113883.4.642.3.1041");
            entrezValue.getText().setValue(String.valueOf(g.getEntrezGeneId()));
            entrezValue.getIdentifier().setValue(String.valueOf(g.getEntrezGeneId()));
            entrez.insertObservationValue(0).setData(entrezValue);

            OBX hgnc = result.getORDER_OBSERVATION(orderNumber).getOBSERVATION(3).getOBX();
            hgnc.getSetIDOBX().setValue(String.valueOf(4));
            hgnc.getObservationIdentifier().getIdentifier().setValue("48018-6");
            hgnc.getObservationIdentifier().getText().setValue("Gene studied [ID]");
            hgnc.getObservationIdentifier().getNameOfCodingSystem().setValue("LN");
            hgnc.getValueType().setValue("CWE");
            CWE hgncValue = new CWE(oru);
            Genenames genenames = HgncGeneName.resolve(g.getEntrezGeneId());
            hgncValue.getCodingSystemOID().setValue("2.16.840.1.113883.6.281");
            hgncValue.getIdentifier().setValue(genenames.getHgncId());
            hgncValue.getText().setValue(genenames.getApprovedSymbol());
            hgnc.insertObservationValue(0).setData(hgncValue);
        } catch (HL7Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void addReference(ORU_R01 oru, ORU_R01_PATIENT_RESULT result, int position, Reference reference) {
        try {
            OBX ref = result.getORDER_OBSERVATION(position)
                    .getOBSERVATION(result.getORDER_OBSERVATION(position).getOBSERVATIONReps()).getOBX();
            ref.getSetIDOBX().setValue(String.valueOf(result.getORDER_OBSERVATION(position).getOBSERVATIONReps()));
            ref.getValueType().setValue("CWE");
            ref.getObservationIdentifier().getIdentifier().setValue("75608-0");
            ref.getObservationIdentifier().getText().setValue("Citation in Reference lab test Narrative");
            ref.getObservationIdentifier().getNameOfCodingSystem().setValue("LN");
            CWE v2ref = new CWE(oru);
            v2ref.getCodingSystemOID().setValue("2.16.840.1.113883.13.191");
            v2ref.getIdentifier().setValue(String.valueOf(reference.getPmid()));
            String name = reference.getName() != null ? reference.getName()
                    : pubmedResolver.resolvePublication(reference.getPmid());
            v2ref.getText().setValue(name);
            ref.insertObservationValue(0).setData(v2ref);
        } catch (HL7Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void addEvidenceLevel(ORU_R01 oru, ORU_R01_PATIENT_RESULT result, int position, String evidenceLevel) {
        try {
            OBX evidence = result.getORDER_OBSERVATION(position)
                    .getOBSERVATION(result.getORDER_OBSERVATION(position).getOBSERVATIONReps()).getOBX();
            evidence.getSetIDOBX().setValue(String.valueOf(result.getORDER_OBSERVATION(position).getOBSERVATIONReps()));
            evidence.getValueType().setValue("ST");
            evidence.getObservationIdentifier().getIdentifier().setValue("93044-6");
            evidence.getObservationIdentifier().getText().setValue("Level of evidence");
            evidence.getObservationIdentifier().getNameOfCodingSystem().setValue("LN");
            ST evidenceValue = new ST(oru);
            evidenceValue.setValue(evidenceLevel);
            evidence.insertObservationValue(0).setData(evidenceValue);
        } catch (HL7Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void addTreatment(ORU_R01 oru, ORU_R01_PATIENT_RESULT result, int position, Treatment treatment) {
        try {
            String ncitCode = treatment.getNcitCode() != null ? treatment.getNcitCode()
                    : OncoKbDrug.resolve(treatment.getName()).getNcitCode();
            OBX treat = result.getORDER_OBSERVATION(position)
                    .getOBSERVATION(result.getORDER_OBSERVATION(position).getOBSERVATIONReps()).getOBX();
            treat.getSetIDOBX().setValue(String.valueOf(result.getORDER_OBSERVATION(position).getOBSERVATIONReps()));
            treat.getValueType().setValue("ST");
            treat.getObservationIdentifier().getIdentifier().setValue("51963-7");
            treat.getObservationIdentifier().getText().setValue("Medication assessed [ID]");
            treat.getObservationIdentifier().getNameOfCodingSystem().setValue("LN");
            CWE v2treat = new CWE(oru);
            v2treat.getCodingSystemOID().setValue("2.16.840.1.113883.3.26.1.1");
            v2treat.getIdentifier().setValue(ncitCode);
            v2treat.getText().setValue(treatment.getName());
            treat.insertObservationValue(0).setData(v2treat);
        } catch (HL7Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void addFollowUp(ORU_R01 oru, ORU_R01_PATIENT_RESULT result, int position, String loincId,
            String loincText) {
        try {
            OBX followUp = result.getORDER_OBSERVATION(position)
                    .getOBSERVATION(result.getORDER_OBSERVATION(position).getOBSERVATIONReps()).getOBX();
            followUp.getSetIDOBX().setValue(String.valueOf(result.getORDER_OBSERVATION(position).getOBSERVATIONReps()));
            followUp.getValueType().setValue("CWE");
            followUp.getObservationIdentifier().getIdentifier().setValue("62366-0");
            followUp.getObservationIdentifier().getText().setValue("Recommended follow-up [Identifier]");
            followUp.getObservationIdentifier().getNameOfCodingSystem().setValue("LN");
            CWE v2follow = new CWE(oru);
            v2follow.getCodingSystemOID().setValue("LN");
            v2follow.getIdentifier().setValue(String.valueOf(loincId));
            v2follow.getText().setValue(loincText);
            followUp.insertObservationValue(0).setData(v2follow);
        } catch (HL7Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

}
