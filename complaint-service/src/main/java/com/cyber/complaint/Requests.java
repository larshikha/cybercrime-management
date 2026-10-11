package com.cyber.complaint;

public class Requests {
    public record ComplaintRequest(String category, String description, String victimName, String victimProfile,
                                   String victimContact, String incidentDate, Double amountLost,
                                   String suspectUpi, String suspectPhone, String suspectEmail, String suspectUrl,
                                   String suspectAccount, String suspectIp,
                                   String txnRef, String paymentMode, String fromAccount, String toAccount) {}

    public record TxnRequest(String txnRef, Double amount, String fromAccount, String toAccount, String paymentMode, String txnDate) {}
    public record EvidenceRequest(String evidenceType, String refValue, String note) {}
    public record ActivityRequest(String activityType, String note, String evidenceRef, String createdBy) {}
    public record CaseRequest(String investigator) {}
    public record StatusRequest(String status) {}
}
