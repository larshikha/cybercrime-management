package com.cyber.complaint;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CaseActivityRepository extends JpaRepository<CaseActivity, Long> {
    List<CaseActivity> findByCaseIdOrderByCreatedAtDesc(Long caseId);
}
