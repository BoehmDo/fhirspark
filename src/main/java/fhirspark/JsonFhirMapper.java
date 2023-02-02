package fhirspark;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ReferenceClientParam;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fhirspark.adapter.ClinicalTrialAdapter;
import fhirspark.adapter.FollowUpAdapter;
import fhirspark.adapter.MtbAdapter;
import fhirspark.adapter.ReasoningAdapter;
import fhirspark.adapter.TherapyRecommendationAdapter;
import fhirspark.definitions.GenomicsReportingEnum;
import fhirspark.definitions.Hl7TerminologyEnum;
import fhirspark.definitions.LoincEnum;
import fhirspark.definitions.UriEnum;
import fhirspark.restmodel.CbioportalRest;
import fhirspark.restmodel.ClinicalDatum;
import fhirspark.restmodel.Deletions;
import fhirspark.restmodel.FollowUp;
import fhirspark.restmodel.GeneticAlteration;
import fhirspark.restmodel.Mtb;
import fhirspark.restmodel.Reasoning;
import fhirspark.restmodel.ResponseCriteria;
import fhirspark.restmodel.TherapyRecommendation;
import fhirspark.restmodel.Treatment;
import fhirspark.settings.Settings;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Identifier.IdentifierUse;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedArtifact;
import org.hl7.fhir.r4.model.RelatedArtifact.RelatedArtifactType;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fulfils the persistence in HL7 FHIR resources.
 */
public class JsonFhirMapper {

    public static final int TIMEOUT = 60000;

    private static String patientUri;
    private static String therapyRecommendationUri;
    private static String followUpUri;
    private static String mtbUri;
    private static String studyUri;
    private static Settings settings;

    private FhirContext ctx = FhirContext.forR4();
    private IGenericClient client;
    private ObjectMapper objectMapper = new ObjectMapper(new JsonFactory());

    /**
     *
     * Constructs a new FHIR mapper and stores the required configuration.
     *
     * @param settings Settings object with containing configuration
     */
    public JsonFhirMapper(Settings settings) {
        JsonFhirMapper.settings = settings;
        ctx.getRestfulClientFactory().setConnectTimeout(TIMEOUT);
        ctx.getRestfulClientFactory().setSocketTimeout(TIMEOUT);
        this.client = ctx.newRestfulGenericClient(settings.getFhirDbBase());
        MtbAdapter.initialize(settings, client);
        FollowUpAdapter.initialize(settings, client);
        JsonFhirMapper.patientUri = settings.getPatientSystem();
        JsonFhirMapper.therapyRecommendationUri = settings.getObservationSystem();
        JsonFhirMapper.followUpUri = settings.getFollowUpSystem();
        JsonFhirMapper.mtbUri = settings.getDiagnosticReportSystem();
        JsonFhirMapper.studyUri = settings.getStudySystem();

    }

    /**
     * Retrieves MTB data from FHIR server and transforms it into JSON format for
     * cBioPortal.
     *
     * @param patientId
     * @return
     * @throws JsonProcessingException
     */
    public String mtbToJson(String patientId) throws JsonProcessingException {
        List<Mtb> mtbs = new ArrayList<Mtb>();
        Bundle bPatient = (Bundle) client.search().forResource(Patient.class)
                .where(new TokenClientParam("identifier").exactly().systemAndCode(patientUri, patientId)).prettyPrint()
                .execute();
        Patient fhirPatient = (Patient) bPatient.getEntryFirstRep().getResource();

        if (fhirPatient == null) {
            return this.objectMapper.writeValueAsString(new CbioportalRest().withId(patientId).withMtbs(mtbs));
        }

        Bundle bDiagnosticReports = (Bundle) client.search().forResource(DiagnosticReport.class)
                .where(new ReferenceClientParam("subject").hasId(harmonizeId(fhirPatient))).prettyPrint()
                .include(DiagnosticReport.INCLUDE_BASED_ON)
                .include(DiagnosticReport.INCLUDE_RESULT.asRecursive())
                .include(DiagnosticReport.INCLUDE_SPECIMEN.asRecursive()).execute();

        List<BundleEntryComponent> diagnosticReports = bDiagnosticReports.getEntry();

        for (int i = 0; i < diagnosticReports.size(); i++) {
            if (!(diagnosticReports.get(i).getResource() instanceof DiagnosticReport)) {
                continue;
            }
            DiagnosticReport diagnosticReport = (DiagnosticReport) diagnosticReports.get(i).getResource();
            mtbs.add(MtbAdapter.toJson(settings.getRegex(), patientId, diagnosticReport));

        }

        mtbs.sort(Comparator.comparing(Mtb::getId).reversed());

        return this.objectMapper.writeValueAsString(new CbioportalRest().withId(patientId).withMtbs(mtbs));

    }

    /**
     * Retrieves MTB data from cBioPortal and persists it in FHIR resources.
     */
    public void mtbFromJson(String patientId, String studyId, List<Mtb> mtbs) throws DataFormatException, IOException {

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTION);

        Reference fhirPatient = getOrCreatePatient(bundle, patientId);

        for (Mtb mtb : mtbs) {
            MtbAdapter.fromJson(bundle, settings.getRegex(), fhirPatient, patientId, studyId, mtb);
        }

        try {
            System.out.println(bundle);
            System.out.println(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle));

            Bundle resp = client.transaction().withBundle(bundle).execute();

            // Log the response
            System.out.println(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(resp));
        } catch (UnprocessableEntityException entityException) {
            try (FileWriter f = new FileWriter("error.json")) {
                f.write(entityException.getResponseBody());
            }
        }

    }

    /**
     * Retrieves MTB data from FHIR server and transforms it into JSON format for
     * cBioPortal.
     * @param patientId
     * @return
     * @throws JsonProcessingException
     */
    public String followUpToJson(String patientId) throws JsonProcessingException {
        List<FollowUp> followUps = new ArrayList<FollowUp>();
        Bundle bPatient = (Bundle) client.search().forResource(Patient.class)
                .where(new TokenClientParam("identifier").exactly().systemAndCode(patientUri, patientId)).prettyPrint()
                .execute();
        Patient fhirPatient = (Patient) bPatient.getEntryFirstRep().getResource();

        if (fhirPatient == null) {
            return this.objectMapper.writeValueAsString(
                new CbioportalRest()
                .withId(patientId)
                .withFollowUps(followUps)
            );
        }

        Bundle bMedicationStatements = (Bundle) client.search().forResource(MedicationStatement.class)
                .where(new ReferenceClientParam("subject").hasId(harmonizeId(fhirPatient))).prettyPrint()
                .include(MedicationStatement.INCLUDE_PART_OF)
                .include(MedicationStatement.INCLUDE_CONTEXT.asRecursive())
                .execute();

        List<BundleEntryComponent> medicationStatements = bMedicationStatements.getEntry();

        for (int i = 0; i < medicationStatements.size(); i++) {
            if (!(medicationStatements.get(i).getResource() instanceof MedicationStatement)) {
                continue;
            }
            MedicationStatement medicationStatement = (MedicationStatement) medicationStatements.get(i).getResource();
            followUps.add(FollowUpAdapter.toJson(settings.getRegex(), patientId, medicationStatement));

        }

        return this.objectMapper.writeValueAsString(new CbioportalRest().withId(patientId).withFollowUps(followUps));

    }

    /**
     * Retrieves FollowUp data from cBioPortal and persists it in FHIR resources.
     */
    public void followUpFromJson(String patientId, List<FollowUp> followUps) throws DataFormatException, IOException {

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTION);

        Reference fhirPatient = getOrCreatePatient(bundle, patientId);

        for (FollowUp followUp : followUps) {
            FollowUpAdapter.fromJson(bundle, settings.getRegex(), fhirPatient, patientId, followUp);
        }

        try {
            System.out.println(bundle);
            System.out.println(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle));

            Bundle resp = client.transaction().withBundle(bundle).execute();

            // Log the response
            System.out.println(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(resp));
        } catch (UnprocessableEntityException entityException) {
            try (FileWriter f = new FileWriter("error.json")) {
                f.write(entityException.getResponseBody());
            }
        }

    }

    /**
     *
     * @param patientId id of the patient.
     * @param deletions entries that should be deleted. Either MTB or therapy
     *                  recommendation.
     */
    public void deleteEntries(String patientId, Deletions deletions) {
        // deletions.getTherapyRecommendation()
        // .forEach(recommendation -> deleteTherapyRecommendation(patientId,
        // recommendation));
        deletions.getMtb().forEach(mtb -> deleteMtb(patientId, mtb));
        deletions.getFollowUp().forEach(followUp -> deleteFollowUps(patientId, followUp));
        deletions.getTherapyRecommendation()
                .forEach(therapyRecommendationId -> deleteTherapyRecommendation(patientId, therapyRecommendationId));
    }

    private void deleteTherapyRecommendation(String patientId, String therapyRecommendationId) {
        if (!therapyRecommendationId.startsWith(patientId)) {
            throw new IllegalArgumentException("Invalid patientId!");
        }
        client.delete().resourceConditionalByUrl(
                "Observation?identifier=" + therapyRecommendationUri + "|" + therapyRecommendationId).execute();
    }

    private void deleteMtb(String patientId, String mtbId) {
        if (!mtbId.startsWith("mtb_" + patientId + "_")) {
            throw new IllegalArgumentException("Invalid patientId!");
        }
        client.delete().resourceConditionalByUrl("DiagnosticReport?identifier=" + mtbUri + "|" + mtbId).execute();
    }

    private void deleteFollowUps(String patientId, String followUpId) {
        if (!followUpId.startsWith("followUp_" + patientId + "_")) {
            throw new IllegalArgumentException("Invalid patientId!");
        }
        client.delete().resourceConditionalByUrl("MedicationStatement?identifier="
            + followUpUri + "|" + followUpId).execute();
    }

    private Reference getOrCreatePatient(Bundle b, String patientId) {

        Patient patient = new Patient();
        patient.setId(IdType.newRandomUuid());
        patient.getIdentifierFirstRep().setSystem(patientUri).setValue(patientId);
        patient.getIdentifierFirstRep().setUse(IdentifierUse.USUAL);
        patient.getIdentifierFirstRep().getType().addCoding(Hl7TerminologyEnum.MR.toCoding());
        b.addEntry().setFullUrl(patient.getIdElement().getValue()).setResource(patient).getRequest()
                .setUrl("Patient?identifier=" + patientUri + "|" + patientId)
                .setIfNoneExist("identifier=" + patientUri + "|" + patientId).setMethod(Bundle.HTTPVerb.PUT);

        return new Reference(patient);
    }

    private String harmonizeId(IAnyResource resource) {
        if (resource.getIdElement().getValue().startsWith("urn:uuid:")) {
            return resource.getIdElement().getValue();
        } else {
            return resource.getIdElement().getResourceType() + "/" + resource.getIdElement().getIdPart();
        }
    }

    /**
     * Fetched Pubmed IDs that have been previously associated with the same
     * alteration.
     *
     * @param alterations List of alterations to consider
     * @return List of matching references
     */
    public Collection<fhirspark.restmodel.Reference> getPmidsByAlteration(List<GeneticAlteration> alterations) {

        Set<String> entrez = new HashSet<>();
        for (GeneticAlteration a : alterations) {
            entrez.add(String.valueOf(a.getEntrezGeneId()));
        }

        Bundle bStuff = (Bundle) client.search().forResource(Observation.class)
                .where(new TokenClientParam("component-value-concept").exactly()
                        .systemAndValues(UriEnum.NCBI_GENE.getUri(), new ArrayList<>(entrez)))
                .prettyPrint().revInclude(Observation.INCLUDE_DERIVED_FROM).execute();

        Map<Integer, fhirspark.restmodel.Reference> refMap = new HashMap<>();

        for (BundleEntryComponent bec : bStuff.getEntry()) {
            Observation o = (Observation) bec.getResource();
            if (!o.getMeta().hasProfile(GenomicsReportingEnum.THERAPEUTIC_IMPLICATION.getSystem())) {
                continue;
            }
            o.getExtensionsByUrl(GenomicsReportingEnum.RELATEDARTIFACT.getSystem()).forEach(relatedArtifact -> {
                if (((RelatedArtifact) relatedArtifact.getValue()).getType() == RelatedArtifactType.CITATION) {
                    Integer pmid = Integer.valueOf(
                            ((RelatedArtifact) relatedArtifact.getValue()).getUrl().replaceFirst(
                                    UriEnum.PUBMED_URI.getUri(),
                                    ""));
                    refMap.put(pmid, new fhirspark.restmodel.Reference().withPmid(pmid)
                            .withName(((RelatedArtifact) relatedArtifact.getValue()).getCitation()));
                }
            });
        }

        return refMap.values();

    }

    /**
     * Fetches therapy recommendations that have been previously associated with the
     * same alteration.
     *
     * @param alterations List of alterations to consider
     * @return List of matching therapies
     */
    public Collection<TherapyRecommendation> getTherapyRecommendationsByAlteration(
            List<GeneticAlteration> alterations) {

        Set<String> entrez = new HashSet<>();
        for (GeneticAlteration a : alterations) {
            entrez.add(String.valueOf(a.getEntrezGeneId()));
        }

        Bundle bStuff = (Bundle) client.search().forResource(Observation.class)
                .where(new TokenClientParam("component-value-concept").exactly()
                        .systemAndValues(UriEnum.NCBI_GENE.getUri(), new ArrayList<>(entrez)))
                .prettyPrint().revInclude(Observation.INCLUDE_DERIVED_FROM).execute();
        Map<String, TherapyRecommendation> tcMap = new HashMap<>();
        for (BundleEntryComponent bec : bStuff.getEntry()) {
            Observation ob = (Observation) bec.getResource();
            if (!ob.getMeta().hasProfile(GenomicsReportingEnum.THERAPEUTIC_IMPLICATION.getSystem())) {
                continue;
            }
            TherapyRecommendation therapyRecommendation = new TherapyRecommendation()
                    .withComment(new ArrayList<>()).withReasoning(new Reasoning()).withClinicalTrial(new ArrayList<>());
            List<ClinicalDatum> clinicalData = new ArrayList<>();
            List<GeneticAlteration> geneticAlterations = new ArrayList<>();
            therapyRecommendation.getReasoning().withClinicalData(clinicalData)
                    .withGeneticAlterations(geneticAlterations);
            String[] id = ob.getIdentifierFirstRep().getValueElement().getValue().split("_");
            therapyRecommendation.setId(id[id.length - 1]);

            Bundle bDiagnosticReports = (Bundle) client.search().forResource(DiagnosticReport.class)
                .where(DiagnosticReport.RESULT.hasId(ob.getIdElement().getIdPart())).prettyPrint()
                .include(DiagnosticReport.INCLUDE_SUBJECT).execute();
            if (bDiagnosticReports.hasEntry()) {
                DiagnosticReport mtb = (DiagnosticReport) bDiagnosticReports.getEntryFirstRep().getResource();
                Bundle bPat = (Bundle) client.search().forResource(Patient.class)
                    .where(new TokenClientParam("_id")
                    .exactly().code(mtb.getSubject().getReference())).prettyPrint().execute();
                Patient subject = (Patient) bPat.getEntryFirstRep().getResource();
                String studyId = "";
                for (Identifier ident : mtb.getIdentifier()) {
                    studyId = ident.getValue();
                }
                therapyRecommendation.setCaseId(subject.getIdentifierFirstRep().getValue());
                therapyRecommendation.setStudyId(studyId);
            }

            therapyRecommendation
            .setReasoning(ReasoningAdapter.toJson(settings.getRegex(), ob.getDerivedFrom(), ob.getHasMember(), client));

            if (ob.hasPerformer()) {
                Bundle b2 = (Bundle) client.search().forResource(Practitioner.class)
                        .where(new TokenClientParam("_id").exactly().code(ob.getPerformerFirstRep().getReference()))
                        .prettyPrint().execute();
                Practitioner author = (Practitioner) b2.getEntryFirstRep().getResource();
                therapyRecommendation.setAuthor(author.getIdentifierFirstRep().getValue());
            }

            tcMap.put(ob.getIdentifierFirstRep().getValue(), therapyRecommendation);

            List<Treatment> treatments = new ArrayList<>();
            therapyRecommendation.setTreatments(treatments);

            List<fhirspark.restmodel.Reference> references = new ArrayList<>();
            ob.getExtensionsByUrl(GenomicsReportingEnum.RELATEDARTIFACT.getSystem()).forEach(relatedArtifact -> {
                if (((RelatedArtifact) relatedArtifact.getValue()).getType() == RelatedArtifactType.CITATION) {
                    references.add(new fhirspark.restmodel.Reference()
                            .withPmid(Integer.valueOf(((RelatedArtifact) relatedArtifact.getValue()).getUrl()
                                    .replaceFirst(UriEnum.PUBMED_URI.getUri(), "")))
                            .withName(((RelatedArtifact) relatedArtifact.getValue()).getCitation()));
                }
            });

            therapyRecommendation.setReferences(references);

            ob.getComponent().forEach(result -> {
                if (result.getCode().getCodingFirstRep().getCode().equals("93044-6")) {
                    String[] evidence = result.getValueCodeableConcept().getCodingFirstRep().getDisplay().split(" ");
                    therapyRecommendation.setEvidenceLevel(evidence[0]);
                    if (evidence.length > 1 && !"null".equals(evidence[1])) {
                        therapyRecommendation.setEvidenceLevelExtension(evidence[1]);
                    }
                    if (evidence.length > 2) {
                        therapyRecommendation.setEvidenceLevelM3Text(
                                String.join(" ", Arrays.asList(evidence).subList(2, evidence.length))
                                        .replace("(", "").replace(")", ""));
                    }
                }
                if (result.getCode().getCodingFirstRep().getCode().equals("51963-7")) {
                    therapyRecommendation.getTreatments()
                            .add(new Treatment()
                                    .withNcitCode(result.getValueCodeableConcept().getCodingFirstRep().getCode())
                                    .withName(result.getValueCodeableConcept().getCodingFirstRep().getDisplay()));
                }
                if (result.getCode().getCodingFirstRep().getCode().equals("associated-therapy")) {
                    therapyRecommendation.setClinicalTrial(ClinicalTrialAdapter.toJson(result));
                }
            });

            ob.getDerivedFrom().forEach(reference1 -> {
                if (reference1.getResource() == null) {
                    return;
                }
                GeneticAlteration g = new GeneticAlteration();
                ((Observation) reference1.getResource()).getComponent().forEach(variant -> {
                    switch (LoincEnum.fromCode(variant.getCode().getCodingFirstRep().getCode())) {
                        case AMINO_ACID_CHANGE:
                            g.setAlteration(variant.getValueCodeableConcept().getCodingFirstRep().getCode()
                                    .replaceFirst("p.", ""));
                            break;
                        case DISCRETE_GENETIC_VARIANT:
                            variant.getValueCodeableConcept().getCoding().forEach(coding -> {
                                switch (coding.getSystem()) {
                                    case "http://www.ncbi.nlm.nih.gov/gene":
                                        g.setEntrezGeneId(Integer.valueOf(coding.getCode()));
                                        break;
                                    default:
                                        break;
                                }
                            });
                            break;
                        case GENE_STUDIED:
                            g.setHugoSymbol(variant.getValueCodeableConcept().getCodingFirstRep().getDisplay());
                            break;
                        case CHROMOSOME_COPY_NUMBER_CHANGE:
                            switch (LoincEnum
                                    .fromCode(variant.getValueCodeableConcept().getCodingFirstRep().getCode())) {
                                case COPY_NUMBER_GAIN:
                                    g.setAlteration("Amplification");
                                    break;
                                case COPY_NUMBER_LOSS:
                                    g.setAlteration("Deletion");
                                    break;
                                default:
                                    break;
                            }
                            break;
                        default:
                            break;
                    }
                });
                geneticAlterations.add(g);
            });

            ob.getNote().forEach(note -> therapyRecommendation.getComment().add(note.getText()));

        }

        return tcMap.values();

    }

    /**
     * Fetches followUps that have been previously associated with the
     * same alteration.
     *
     * @param alterations List of alterations to consider
     * @return List of matching therapies
     */
    public Collection<FollowUp> getFollowUpsByAlteration(List<GeneticAlteration> alterations) {

        Set<String> entrez = new HashSet<>();
        for (GeneticAlteration a : alterations) {
            entrez.add(String.valueOf(a.getEntrezGeneId()));
        }

        Bundle bStuff = (Bundle) client.search().forResource(Observation.class)
                .where(new TokenClientParam("component-value-concept").exactly()
                        .systemAndValues(UriEnum.NCBI_GENE.getUri(), new ArrayList<>(entrez)))
                .prettyPrint().revInclude(Observation.INCLUDE_DERIVED_FROM).execute();

        Map<String, FollowUp> tcMap = new HashMap<>();

        ArrayList<Identifier> idents = new ArrayList<>();

        for (BundleEntryComponent bec : bStuff.getEntry()) {
            Observation ob = (Observation) bec.getResource();
            if (!ob.getMeta().hasProfile(GenomicsReportingEnum.THERAPEUTIC_IMPLICATION.getSystem())) {
                continue;
            }
            idents.addAll(ob.getIdentifier());
        }

        Bundle bFollowUps = (Bundle) client.search().forResource(MedicationStatement.class)
            .execute();

        for (BundleEntryComponent bec : bFollowUps.getEntry()) {
            MedicationStatement ms = (MedicationStatement) bec.getResource();
            if (!ms.hasReasonReference()) {
                continue;
            }
            FollowUp followUp = new FollowUp();

            if (ms.hasInformationSource()) {
                Bundle b2 = (Bundle) client.search().forResource(Practitioner.class).where(new TokenClientParam("_id")
                        .exactly().code(ms.getInformationSource().getReference())).prettyPrint()
                        .execute();
                Practitioner author = (Practitioner) b2.getEntryFirstRep().getResource();
                followUp.setAuthor(author.getIdentifierFirstRep().getValue());
            }

            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
            followUp.setDate(f.format(ms.getEffectiveDateTimeType().toCalendar().getTime()));

            followUp.setId(ms.getIdentifierFirstRep().getValue());

            if (ms.getNote().size() > 0) {
                followUp.setComment(ms.getNote().get(0).getText());
            }


            followUp.setTherapyRecommendationRealized(ms.getStatus()
                .compareTo(MedicationStatement.MedicationStatementStatus.fromCode("completed")) == 0);
            followUp.setSideEffect(ms.hasStatusReason());


            ResponseCriteria respCrit = new ResponseCriteria();
            TherapyRecommendation therapyRecommendation = new TherapyRecommendation();

            for (Reference reference : ms.getReasonReference()) {

                Bundle b1 = (Bundle) client.search().forResource(Observation.class)
                    .where(new TokenClientParam("_id")
                    .exactly().code(reference.getReference())).prettyPrint()
                    .include(Observation.INCLUDE_DERIVED_FROM)
                    .include(Observation.INCLUDE_SPECIMEN.asRecursive())
                    .execute();

                Observation obs = (Observation) b1.getEntryFirstRep().getResource();

                if (obs.getIdentifierFirstRep().getValue().startsWith("response_")) {
                    String tag = obs.getIdentifierFirstRep().getValue().split("_")[1];

                    try {
                        ResponseCriteria.class
                            .getDeclaredMethod("set" + tag, Boolean.class)
                            .invoke(respCrit, true);
                    } catch (NoSuchMethodException e) {
                        e.printStackTrace();
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    } catch (InvocationTargetException e) {
                        e.printStackTrace();
                    }
                } else {

                    therapyRecommendation = TherapyRecommendationAdapter.toJson(client,
                            settings.getRegex(), obs);

                }

            }

            followUp.setTherapyRecommendation(therapyRecommendation);
            followUp.setResponse(respCrit);

            tcMap.put(ms.getIdentifierFirstRep().getValue(), followUp);

        }
        return tcMap.values();
    }

}
