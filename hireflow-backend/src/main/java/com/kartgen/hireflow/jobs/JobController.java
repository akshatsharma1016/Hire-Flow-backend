package com.kartgen.hireflow.jobs;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kartgen.hireflow.common.PageResponse;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.CandidateRepository;
import com.kartgen.hireflow.matching.JobMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final JobRepository jobRepo;
    private final CandidateRepository candidateRepo;

    public record JobDto(UUID id, String title, String department, String location,
                         String status, UUID currentVersionId, Instant createdAt) {}

    public record MatchDto(UUID candidateId, String candidateName, String candidateEmail,
                           BigDecimal score, String explanationJson, Instant computedAt) {}

    public record StatusRequest(@NotBlank String status) {}

    public record VersionRequest(
            @NotBlank @JsonAlias({"rawJdText", "jd", "description"}) String jdText,
            Integer minExperienceMonths,
            Integer maxExperienceMonths
    ) {}

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','MANAGER')")
    public ResponseEntity<?> create(@Valid @RequestBody JobService.CreateJobRequest req) {
        UUID orgId = TenantContext.getOrganizationId();
        Job job = jobService.createJob(orgId, req);
        return ResponseEntity.ok(toDto(job));
    }

    @GetMapping
    public ResponseEntity<?> list() {
        UUID orgId = TenantContext.getOrganizationId();
        List<JobDto> dtos = jobService.listJobs(orgId).stream().map(this::toDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(toDto(jobService.getJob(orgId, id)));
    }

    @PostMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','MANAGER')")
    public ResponseEntity<?> createVersion(@PathVariable UUID id,
                                           @Valid @RequestBody VersionRequest req) {
        UUID orgId = TenantContext.getOrganizationId();
        JobVersion jv = jobService.createJobVersion(orgId, id,
                new JobService.CreateJobVersionRequest(req.jdText(), req.minExperienceMonths(),
                        req.maxExperienceMonths()));
        return ResponseEntity.ok(Map.of(
                "id", jv.getId(), "jobId", id, "versionNumber", jv.getVersionNumber()));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','MANAGER')")
    public ResponseEntity<?> updateStatus(@PathVariable UUID id,
                                          @Valid @RequestBody StatusRequest req) {
        UUID orgId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(toDto(jobService.updateStatus(orgId, id, req.status())));
    }

    @GetMapping("/{id}/matches")
    public ResponseEntity<?> matches(@PathVariable UUID id,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int size) {
        UUID orgId = TenantContext.getOrganizationId();
        Page<JobMatch> matchPage = jobService.getJobMatches(orgId, id,
                PageRequest.of(page, Math.min(100, Math.max(1, size))));

        // ⚡ Bolt Performance Optimization:
        // Collect all candidate IDs from the page and fetch them in a single query
        // to avoid N+1 queries when mapping matches to DTOs.
        List<UUID> candidateIds = matchPage.getContent().stream()
                .map(JobMatch::getCandidateId)
                .toList();

        Map<UUID, Candidate> candidatesMap = candidateIds.isEmpty() ? Map.of() :
                candidateRepo.findByIdInAndOrganizationId(candidateIds, orgId).stream()
                .collect(java.util.stream.Collectors.toMap(Candidate::getId, c -> c));

        Page<MatchDto> dtos = matchPage.map(m -> {
            Candidate c = candidatesMap.get(m.getCandidateId());
            return new MatchDto(
                    m.getCandidateId(),
                    c != null ? c.getDisplayName() : "Unknown",
                    c != null ? c.getPrimaryEmail() : null,
                    m.getScore(), m.getExplanationJson(), m.getComputedAt());
        });

        return ResponseEntity.ok(PageResponse.of(dtos));
    }

    private JobDto toDto(Job j) {
        return new JobDto(j.getId(), j.getTitle(), j.getDepartment(), j.getLocation(),
                j.getStatus(), j.getCurrentVersionId(), j.getCreatedAt());
    }
}
