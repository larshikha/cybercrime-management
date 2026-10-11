package com.cyber.analysis;

import java.util.List;
import java.util.Map;

public class Dto {

    public record TxnDto(String txnRef, double amount, String fromAccount, String toAccount, String mode) {}

    public record ComplaintDto(
            Long id, String complaintNo, String category, String description,
            String victimName, String victimProfile, String incidentDate, double amountLost,
            String suspectUpi, String suspectPhone, String suspectEmail, String suspectUrl,
            String suspectAccount, String suspectIp, List<TxnDto> transactions) {}

    public record AnalyzeRequest(ComplaintDto complaint, List<ComplaintDto> history) {}

    public record RelatedCase(Long id, String complaintNo, String category, double score, List<String> factors) {}

    public record AnalyzeResponse(
            String category, double confidence, Map<String, Double> categoryScores, List<String> categoryKeywords,
            String priority, int priorityScore, List<String> priorityFactors,
            List<RelatedCase> related, List<String> patterns, String summary) {}
}
