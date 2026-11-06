package com.kartgen.hireflow.identity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateRepository extends JpaRepository<Candidate, UUID> {

    long countByOrganizationId(UUID orgId);

    long countByOrganizationIdAndStatus(UUID orgId, String status);

    Page<Candidate> findByOrganizationIdAndStatusOrderByLastApplicationAtDesc(
            UUID orgId, String status, Pageable pageable);

    List<Candidate> findByOrganizationIdAndStatus(UUID orgId, String status);

    java.util.Optional<Candidate> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Candidate> findByIdInAndOrganizationId(List<UUID> ids, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Candidate c WHERE c.id = :id")
    Optional<Candidate> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT c FROM Candidate c WHERE c.organizationId = :orgId AND c.status = 'ACTIVE' " +
           "AND (:search IS NULL OR :search = '' " +
           "  OR LOWER(c.displayName) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "  OR LOWER(c.primaryEmail) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "  OR LOWER(c.primaryPhone) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "AND (:jobVersionId IS NULL OR EXISTS (" +
           "  SELECT m.id FROM JobMatch m WHERE m.candidateId = c.id AND m.jobVersionId = :jobVersionId)) " +
           "AND (:minExpMonths IS NULL OR c.currentExperienceMonths >= :minExpMonths) " +
           "AND (:location IS NULL OR :location = '' OR LOWER(c.currentCity) LIKE LOWER(CONCAT('%', :location, '%'))) " +
           "AND (:sourceType IS NULL OR :sourceType = '' OR EXISTS (" +
           "  SELECT 1 FROM ResumeVersion rv, InboundAttachment ia, InboundMessage im " +
           "  WHERE rv.candidateId = c.id AND rv.sourceAttachmentId = ia.id " +
           "  AND ia.messageId = im.id AND im.sourceType = :sourceType)) " +
           "AND (:skillCount = 0 OR EXISTS (" +
           "  SELECT 1 FROM CandidateSkill cs WHERE cs.candidateId = c.id " +
           "  AND cs.profileVersionId = c.currentProfileVersionId AND cs.skillId IN :skillIds)) " +
           "ORDER BY c.lastApplicationAt DESC NULLS LAST, c.id DESC")
    Page<Candidate> search(@Param("orgId") UUID orgId,
                           @Param("search") String search,
                           @Param("jobVersionId") UUID jobVersionId,
                           @Param("minExpMonths") Integer minExpMonths,
                           @Param("location") String location,
                           @Param("sourceType") String sourceType,
                           @Param("skillIds") List<UUID> skillIds,
                           @Param("skillCount") int skillCount,
                           Pageable pageable);
}
