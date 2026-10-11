package com.cyber.complaint;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InvestigationCaseRepository extends JpaRepository<InvestigationCase, Long> {
    List<InvestigationCase> findByComplaintId(Long complaintId);
}
