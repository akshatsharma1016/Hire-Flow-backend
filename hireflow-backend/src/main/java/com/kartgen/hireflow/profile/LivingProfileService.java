package com.kartgen.hireflow.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartgen.hireflow.document.ResumeFile;
import com.kartgen.hireflow.extraction.dto.ExtractedResumeSchema;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.CandidateRepository;
import com.kartgen.hireflow.matching.JobMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LivingProfileService {

    private final ResumeVersionRepository resumeVersionRepo;
    private final CandidateProfileVersionRepository profileVersionRepo;
    private final CandidateSkillRepository candidateSkillRepo;
    private final SkillRepository skillRepo;
    private final CandidateRepository candidateRepo;
    private final JobMatchRepository matchRepo;
    private final ObjectMapper objectMapper;

    public record PromotionResult(ResumeVersion resumeVersion, boolean promoted) {}

    @Transactional
    public PromotionResult promoteResume(UUID orgId, Candidate candidate,
                                         ResumeFile resumeFile, ExtractedResumeSchema schema,
                                         UUID sourceAttachmentId) {
        // Serialize concurrent promotions for the same candidate.
        Candidate locked = candidateRepo.findByIdForUpdate(candidate.getId())
                .orElseThrow(() -> new IllegalStateException("Candidate disappeared: " + candidate.getId()));

        int nextVersion = resumeVersionRepo.findMaxVersionNumber(locked.getId()) + 1;
        LocalDate incomingDate = parseDate(schema.getDocumentDate());

        ResumeVersion rv = ResumeVersion.builder()
                .organizationId(orgId)
                .candidateId(locked.getId())
                .resumeFileId(resumeFile.getId())
                .versionNumber(nextVersion)
                .documentDate(incomingDate)
                .sourceAttachmentId(sourceAttachmentId)
                .parseStatus("READY")
                .validationStatus("VALID")
                .parserVersion(parserVersion())
                .processedAt(Instant.now())
                .build();
        resumeVersionRepo.save(rv);

        CandidateProfileVersion pv = CandidateProfileVersion.builder()
                .organizationId(orgId)
                .candidateId(locked.getId())
                .resumeVersionId(rv.getId())
                .profileVersion((long) nextVersion)
                .fullName(schema.getFullName())
                .experienceMonths(schema.getTotalExperienceMonths())
                .experienceBasis(schema.getExperienceBasis())
                .locationText(schema.getCurrentCity())
                .educationJson(serializeSafe(schema.getEducationList()))
                .sourceEvidenceJson(serializeSafe(schema.getSourceEvidenceJson()))
                .build();
        profileVersionRepo.save(pv);

        storeSkills(orgId, locked.getId(), pv.getId(), schema.getExtractedSkills());

        boolean promote = shouldPromote(locked, incomingDate);
        if (!promote) {
            log.info("Kept current profile for candidate={} — incoming document is older (v{})",
                    locked.getId(), nextVersion);
            return new PromotionResult(rv, false);
        }

        ResumeVersion previous = locked.getCurrentResumeVersionId() == null ? null
                : resumeVersionRepo.findById(locked.getCurrentResumeVersionId()).orElse(null);
        if (previous != null && !previous.getId().equals(rv.getId())) {
            previous.setSupersededAt(Instant.now());
            resumeVersionRepo.save(previous);
        }

        rv.setPromotedAt(Instant.now());
        resumeVersionRepo.save(rv);

        applyProfile(locked, schema, pv.getId(), rv.getId());
        candidateRepo.save(locked);

        // Matches computed against older profile versions are now stale.
        matchRepo.markStaleForCandidate(locked.getId(), pv.getId());

        log.info("Promoted candidate id={} to resume version {}", locked.getId(), nextVersion);
        return new PromotionResult(rv, true);
    }

    private boolean shouldPromote(Candidate locked, LocalDate incomingDate) {
        if (locked.getCurrentResumeVersionId() == null) return true;

        ResumeVersion current = resumeVersionRepo.findById(locked.getCurrentResumeVersionId()).orElse(null);
        if (current == null) return true;

        // When both documents have dates, never let an older document downgrade the profile.
        if (incomingDate != null && current.getDocumentDate() != null) {
            return !incomingDate.isBefore(current.getDocumentDate());
        }
        // Without a date on either side, the most recently received resume wins.
        return true;
    }

    private void applyProfile(Candidate candidate, ExtractedResumeSchema schema,
                              UUID profileVersionId, UUID resumeVersionId) {
        candidate.setCurrentResumeVersionId(resumeVersionId);
        candidate.setCurrentProfileVersionId(profileVersionId);

        if (schema.getFullName() != null && !schema.getFullName().isBlank()) {
            candidate.setDisplayName(schema.getFullName());
        }
        if (schema.getEmails() != null && !schema.getEmails().isEmpty()) {
            candidate.setPrimaryEmail(schema.getEmails().get(0));
        }
        if (schema.getPhones() != null && !schema.getPhones().isEmpty()) {
            candidate.setPrimaryPhone(schema.getPhones().get(0));
        }
        if (schema.getLinkedinUrl() != null && !schema.getLinkedinUrl().isBlank()) {
            candidate.setLinkedinUrl(schema.getLinkedinUrl());
        }
        if (schema.getTotalExperienceMonths() != null) {
            candidate.setCurrentExperienceMonths(schema.getTotalExperienceMonths());
            candidate.setExperienceConfidence("explicit".equals(schema.getExperienceBasis()) ? "HIGH" : "MEDIUM");
        }
        if (schema.getCurrentCity() != null && !schema.getCurrentCity().isBlank()) {
            candidate.setCurrentCity(schema.getCurrentCity());
        }
        candidate.setProfileUpdatedAt(Instant.now());
    }

    private void storeSkills(UUID orgId, UUID candidateId, UUID profileVersionId, List<String> skillNames) {
        Set<String> allSkillNames = new LinkedHashSet<>();
        if (skillNames != null) {
            allSkillNames.addAll(skillNames);
        }

        // Carry forward prior skills from previous profile versions so living profile accumulates verified skills
        if (candidateId != null) {
            List<CandidateSkill> priorSkills = candidateSkillRepo.findByCandidateId(candidateId);
            if (!priorSkills.isEmpty()) {
                // ⚡ Bolt: Fixed N+1 query by batching skill resolution instead of finding them one-by-one
                Set<UUID> priorSkillIds = priorSkills.stream()
                        .map(CandidateSkill::getSkillId)
                        .collect(Collectors.toSet());
                skillRepo.findAllById(priorSkillIds).forEach(s -> allSkillNames.add(s.getCanonicalName()));
            }
        }

        if (allSkillNames.isEmpty()) return;

        Map<String, Skill> byCanonical = new LinkedHashMap<>();
        for (Skill skill : skillRepo.findByActiveTrue()) {
            byCanonical.put(skill.getCanonicalName().toLowerCase(Locale.ROOT), skill);
        }

        for (String name : allSkillNames) {
            if (name == null) continue;
            Skill skill = byCanonical.get(name.toLowerCase(Locale.ROOT));
            if (skill == null) continue;
            candidateSkillRepo.save(CandidateSkill.builder()
                    .organizationId(orgId)
                    .candidateId(candidateId)
                    .skillId(skill.getId())
                    .profileVersionId(profileVersionId)
                    .evidenceType("RESUME")
                    .confidence(new BigDecimal("0.800"))
                    .lastConfirmedAt(Instant.now())
                    .build());
        }
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private String parserVersion() {
        return "heuristic-v2";
    }

    private String serializeSafe(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
